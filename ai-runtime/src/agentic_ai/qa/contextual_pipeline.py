"""独立v3图在检索前解释指代，保留原问题、完整依赖与原生检查点语义。"""

import json
from typing import Any, cast

from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph
from pydantic import JsonValue

from agentic_ai.qa.chat import ChatPort
from agentic_ai.qa.contextual_models import (
    MAX_CONTEXT_ANSWER,
    MAX_CONTEXT_DEPENDENCIES,
    QA_GRAPH_V3,
    ContextualQaInput,
    ContextualQaResult,
    QueryPlan,
)
from agentic_ai.qa.errors import QaCode, QaError
from agentic_ai.qa.generation_pipeline import (
    GenerationQaInput,
    GenerationQaOutput,
    GenerationQaPipeline,
    GenerationQaState,
)
from agentic_ai.qa.models import QaInput
from agentic_ai.qa.pipeline import GraphState
from agentic_ai.qa.query_rewrite import rewrite_query
from agentic_ai.retrieval.consumer import RetrievalUseCase
from agentic_ai.retrieval.models import RetrievalStrategy
from agentic_ai.retrieval.retriever import CandidatePort
from agentic_ai.retrieval.task_models import GenerationResult
from agentic_ai.tasks.client import CentralTaskClient
from agentic_ai.tasks.models import TaskLease


class ContextualGraphInput(GenerationQaInput):
    """原中央输入持久保存策略，不用部署开关重建历史意图。"""

    queryPolicy: str


class ContextualGraphOutput(GenerationQaOutput):
    """只导出实际查询诊断，不把前文答案或内部依赖作为回答来源。"""

    query: dict[str, JsonValue]


class ContextualGraphState(GenerationQaState, total=False):
    """查询与已授权前文依赖在同一个确认快照中保存。"""

    queryPolicy: str
    query: dict[str, JsonValue]


class ContextualQaPipeline(GenerationQaPipeline):
    """每个任务独享改写状态；v1/v2图保持原节点与恢复方式。"""

    graph_version = QA_GRAPH_V3
    maximum_dependencies = MAX_CONTEXT_DEPENDENCIES
    input_fields = (*GenerationQaPipeline.input_fields, "queryPolicy")

    def __init__(
        self,
        binding: TaskLease,
        central: CentralTaskClient,
        candidates: CandidatePort,
        chat: ChatPort,
        use_case: RetrievalUseCase,
    ) -> None:
        self.query_plan: QueryPlan | None = None
        super().__init__(binding, central, candidates, chat, use_case)

    def decode_input(self, value: object) -> tuple[QaInput, RetrievalStrategy]:
        """只投影问题校验，绝不将v3中央输入写成v1或v2。"""
        request = ContextualQaInput.from_wire(json.dumps(value).encode())
        return request.question_input(), request.retrieval_strategy

    def previous_content(self, previous: TaskLease) -> tuple[QaInput, GenerationResult]:
        """完整证据家族可作前文，v3还必须验证此前查询属于此前原问题。"""
        if previous.graph_version != QA_GRAPH_V3:
            return super().previous_content(previous)
        request = ContextualQaInput.from_wire(json.dumps(previous.input).encode())
        result = ContextualQaResult.from_wire(json.dumps(previous.task.result).encode())
        result.validate_for(request)
        return request.question_input(), result.generation_content()

    async def prepare_query(self, state: GraphState) -> dict[str, object]:
        """先继承全部授权依赖，再进行一次改写；确认快照前再次检查租约。"""
        raw = cast(dict[str, object], state)
        if any(raw.get(key) != self.binding.input.get(key) for key in self.input_fields):
            raise QaError(QaCode.INPUT_REJECTED)
        context = await super()._context()
        await self.assert_active()
        self.query_plan = await rewrite_query(
            self.chat,
            self.question,
            context,
            f"qa-rewrite:{self.binding.task.task_id}:{self.binding.epoch}",
        )
        await self.assert_active()
        await self._context()
        return {
            "query": self.query_plan.model_dump(mode="json", by_alias=True),
            "contextEvidence": [
                ref.model_dump(mode="json", by_alias=True) for ref in self.context_references or ()
            ],
        }

    async def _context(self) -> dict[str, object] | None:
        context = await super()._context()
        if context is None:
            return None
        answer = context["answer"]
        if not isinstance(answer, str):
            raise QaError(QaCode.EVIDENCE_REJECTED)
        truncated = len(answer) > MAX_CONTEXT_ANSWER
        if self.query_plan is not None and self.query_plan.context_truncated != truncated:
            raise QaError(QaCode.EVIDENCE_REJECTED)
        return {**context, "answer": answer[:MAX_CONTEXT_ANSWER]}

    def _query(self, value: dict[str, object]) -> QueryPlan:
        plan = QueryPlan.from_wire(json.dumps(value.get("query")).encode())
        plan.validate_for(self.question)
        if self.query_plan is not None and self.query_plan != plan:
            raise QaError(QaCode.EVIDENCE_REJECTED)
        self.query_plan = plan
        return plan

    def retrieval_question(self, state: GraphState) -> str:
        """原问题用于回答和历史，只把已确认的派生问题传给当前一次检索。"""
        return self._query(cast(dict[str, object], state)).retrieval_question

    async def verify(self, state: GraphState) -> dict[str, object]:
        """复用完整引用验证后包入独立v3信封，不将历史正文变成引用。"""
        plan = self._query(cast(dict[str, object], state))
        content = await super().verify(state)
        result = ContextualQaResult.from_wire(
            json.dumps(
                {
                    **content,
                    "schemaVersion": "knowledge-qa-result-v3",
                    "query": plan.model_dump(mode="json", by_alias=True),
                }
            ).encode()
        )
        return result.model_dump(mode="json", by_alias=True)

    async def before_restore(self, value: dict[str, object]) -> None:
        """改写后尚未检索的快照也必须重验前文；确认的节点不得再次调用模型。"""
        if "query" not in value:
            if any(key in value for key in ("evidence", "draft", "content", "contextEvidence")):
                raise QaError(QaCode.EVIDENCE_REJECTED)
            return
        self._query(value)
        if "contextEvidence" not in value:
            raise QaError(QaCode.EVIDENCE_REJECTED)
        if "evidence" in value:
            await super().before_restore(value)
        else:
            self._restore_context(value)
            await self._context()

    async def before_complete(self, value: dict[str, object]) -> None:
        """完成快照必须匹配已确认查询，再按完整身份重验当前和历史依赖。"""
        result = ContextualQaResult.from_wire(json.dumps(value).encode())
        request = ContextualQaInput.from_wire(json.dumps(self.binding.input).encode())
        result.validate_for(request)
        if self.query_plan is None or result.query != self.query_plan:
            raise QaError(QaCode.ANSWER_INVALID)
        await super().before_complete(
            result.generation_content().model_dump(mode="json", by_alias=True)
        )

    def build(
        self, saver: BaseCheckpointSaver[Any], *, interrupt_before: list[str] | None = None
    ) -> CompiledStateGraph[Any, Any, Any, Any]:
        """新图显式保留扩展schema，不能让继承方法的注解丢失派生查询。"""
        graph = StateGraph(
            ContextualGraphState,
            input_schema=ContextualGraphInput,
            output_schema=ContextualGraphOutput,
        )
        graph.add_node("prepare_query", self.prepare_query, input_schema=ContextualGraphState)
        graph.add_node("retrieve", self.retrieve, input_schema=ContextualGraphState)
        graph.add_node("generate", self.generate, input_schema=ContextualGraphState)
        graph.add_node("verify", self.verify, input_schema=ContextualGraphState)
        graph.add_edge(START, "prepare_query")
        graph.add_edge("prepare_query", "retrieve")
        graph.add_edge("retrieve", "generate")
        graph.add_edge("generate", "verify")
        graph.add_edge("verify", END)
        return graph.compile(checkpointer=saver, interrupt_before=interrupt_before)
