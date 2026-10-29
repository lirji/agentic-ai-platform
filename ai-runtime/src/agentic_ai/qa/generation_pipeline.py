"""问答v2保留原生成语义，检索、上下文和恢复统一使用完整代次证据。"""

import json
from typing import Any, TypedDict, cast

from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph
from pydantic import JsonValue

from agentic_ai.qa.chat import ChatPort
from agentic_ai.qa.errors import QaCode, QaError
from agentic_ai.qa.models import MAX_HITS, QaInput, QaResult
from agentic_ai.qa.pipeline import GraphInput, GraphState, QaPipeline
from agentic_ai.retrieval.consumer import RetrievalUseCase
from agentic_ai.retrieval.evidence import GenerationReference
from agentic_ai.retrieval.retriever import CandidatePort
from agentic_ai.retrieval.task_evidence import GenerationEvidenceRegistration, GenerationKnowledge
from agentic_ai.retrieval.task_models import QA_GRAPH_V2, GenerationResult, generation_input
from agentic_ai.tasks.client import CentralTaskClient
from agentic_ai.tasks.models import TaskLease, TaskStatus


class GenerationQaInput(GraphInput):
    """策略随原始问题一起持久，不因全局配置改变恢复结果。"""

    retrievalStrategy: str


class GenerationQaOutput(TypedDict):
    """最终仅导出新信封，完整生成依赖仍在内部检查点和中央清单中。"""

    schemaVersion: str
    strategy: str
    content: dict[str, JsonValue]
    references: list[dict[str, JsonValue]]
    retrieval: list[dict[str, JsonValue]]


class GenerationQaState(GraphState, total=False):
    """正文投影与完整证据在同一个快照中，恢复不依赖旧进程缓存。"""

    retrievalStrategy: str
    generationEvidence: list[dict[str, JsonValue]]
    retrievalDiagnostics: list[dict[str, JsonValue]]
    contextEvidence: list[dict[str, JsonValue]]
    strategy: str
    content: dict[str, JsonValue]
    references: list[dict[str, JsonValue]]
    retrieval: list[dict[str, JsonValue]]


class GenerationQaPipeline(QaPipeline):
    """复用原提示词、流事件、引用编号和检查点fencing；只适配版本边界。"""

    input_fields = (*QaPipeline.input_fields, "retrievalStrategy")

    def __init__(
        self,
        binding: TaskLease,
        central: CentralTaskClient,
        candidates: CandidatePort,
        chat: ChatPort,
        use_case: RetrievalUseCase,
    ) -> None:
        if binding.graph_version != QA_GRAPH_V2:
            raise QaError(QaCode.INPUT_REJECTED)
        request, self.strategy = generation_input(binding.input, qa=True)
        self.generation = GenerationKnowledge(
            use_case, candidates, self.strategy, request.document_ids, MAX_HITS
        )
        self.context_references: tuple[GenerationReference, ...] | None = None
        super().__init__(binding, central, self.generation, chat)
        self.complete_registration = GenerationEvidenceRegistration(
            central, binding, MAX_HITS, self.generation
        )
        self.registration = self.complete_registration

    def parse_input(self, value: object) -> QaInput:
        """新schema严格要求显式策略，原问题、范围和关联校验继续复用。"""
        request, strategy = generation_input(value, qa=True)
        if not isinstance(request, QaInput) or strategy != self.strategy:
            raise QaError(QaCode.INPUT_REJECTED)
        return request

    async def _context(self) -> dict[str, object] | None:
        """继承前文全部依赖并重新核权，不只信任模型最后选中的引用子集。"""
        task_id = self.question.context_task_id
        if task_id is None:
            if self.context_references:
                raise QaError(QaCode.EVIDENCE_REJECTED)
            self.context_references = ()
            return None
        await self.assert_active()
        previous = await self.central.inspect(task_id)
        if (
            previous.graph_version != QA_GRAPH_V2
            or previous.task.status != TaskStatus.SUCCEEDED
            or previous.task.task_id != task_id
            or previous.tenant_id != self.binding.tenant_id
            or previous.owner_id != self.binding.owner_id
        ):
            raise QaError(QaCode.EVIDENCE_REJECTED)
        previous_question, previous_strategy = generation_input(previous.input, qa=True)
        result = GenerationResult.from_wire(json.dumps(previous.task.result).encode())
        if (
            not isinstance(previous_question, QaInput)
            or not isinstance(result.content, QaResult)
            or result.strategy != previous_strategy
            or previous_question.conversation_id != self.question.conversation_id
            or previous_question.turn_id != self.question.context_turn_id
            or previous_question.question != self.question.context_question
            or previous_question.document_ids != self.question.document_ids
        ):
            raise QaError(QaCode.EVIDENCE_REJECTED)
        manifest = await self.central.generation_manifest(task_id)
        if (
            manifest.epoch != previous.epoch
            or manifest.task_id != task_id
            or not set(result.references).issubset(manifest.references)
            or any(
                self.question.document_ids and r.document_id not in self.question.document_ids
                for r in manifest.references
            )
            or self.context_references is not None
            and set(self.context_references) != set(manifest.references)
        ):
            raise QaError(QaCode.EVIDENCE_REJECTED)
        await self.complete_registration.generation.register(manifest.references)
        self.context_references = manifest.references
        return {"question": previous_question.question, "answer": result.content.answer}

    async def retrieve(self, state: GraphState) -> dict[str, object]:
        """检索后保存完整身份和贡献；尚未读取前文时不伪造上下文清单。"""
        result = await super().retrieve(state)
        return {**result, **self.generation.snapshot(self._evidence(cast(GraphState, result)))}

    async def generate(self, state: GraphState) -> dict[str, object]:
        """生成前重载完整身份，模型看见的内容投影不承担授权。"""
        self.generation.restore(cast(dict[str, object], state))
        result = await super().generate(state)
        return {
            **result,
            "contextEvidence": [
                r.model_dump(mode="json", by_alias=True) for r in self.context_references or ()
            ],
        }

    async def verify(self, state: GraphState) -> dict[str, object]:
        """完整核验与原内容校验均成功才产生v2结果。"""
        self._restore(state)
        content = QaResult.model_validate_json(json.dumps(await super().verify(state)))
        return self._result(content).model_dump(mode="json", by_alias=True)

    def _result(self, content: QaResult) -> GenerationResult:
        return GenerationResult(
            schema_version="knowledge-qa-result-v2",
            strategy=self.strategy,
            content=content,
            references=self.generation.references(content.citations),
            retrieval=tuple(self.generation.diagnostics),
        )

    def _restore(self, value: GraphState) -> None:
        """生成完成的快照必须包含上下文依赖；缺字段不能降格成无上下文。"""
        state = cast(dict[str, object], value)
        self.generation.restore(state)
        context = state.get("contextEvidence")
        if context is None:
            if "draft" in state or "content" in state:
                raise QaError(QaCode.EVIDENCE_REJECTED)
            self.context_references = None
        elif isinstance(context, list) and len(context) <= MAX_HITS:
            self.context_references = tuple(
                GenerationReference.from_wire(json.dumps(r).encode()) for r in context
            )
            if len(set(self.context_references)) != len(self.context_references):
                raise QaError(QaCode.EVIDENCE_REJECTED)
        else:
            raise QaError(QaCode.EVIDENCE_REJECTED)

    async def before_restore(self, value: dict[str, object]) -> None:
        """先恢复完整依赖，再沿原图检查正文，失效时不重放任何模型输出。"""
        if "evidence" in value:
            self._restore(cast(GraphState, value))
            await super().before_restore(value)
            await self._context()

    async def before_complete(self, value: dict[str, object]) -> None:
        """完整终态快照无节点重跑时也重新核验当前和历史全部依赖。"""
        result = GenerationResult.from_wire(json.dumps(value).encode())
        if not isinstance(result.content, QaResult) or result != self._result(result.content):
            raise QaError(QaCode.ANSWER_INVALID)
        hits = self._evidence(cast(GraphState, {"evidence": self.generation.serialized()}))
        await self._check_evidence(hits)
        await self._context()
        await self.registration.register(hits)
        await super().before_complete(result.content.model_dump(mode="json", by_alias=True))

    def build(
        self, saver: BaseCheckpointSaver[Any], *, interrupt_before: list[str] | None = None
    ) -> CompiledStateGraph[Any, Any, Any, Any]:
        """显式节点schema保留v2扩展，避免继承注解使框架丢弃完整状态。"""
        graph = StateGraph(
            GenerationQaState, input_schema=GenerationQaInput, output_schema=GenerationQaOutput
        )
        graph.add_node("retrieve", self.retrieve, input_schema=GenerationQaState)
        graph.add_node("generate", self.generate, input_schema=GenerationQaState)
        graph.add_node("verify", self.verify, input_schema=GenerationQaState)
        graph.add_edge(START, "retrieve")
        graph.add_edge("retrieve", "generate")
        graph.add_edge("generate", "verify")
        graph.add_edge("verify", END)
        return graph.compile(checkpointer=saver, interrupt_before=interrupt_before)
