"""研究v2只替换检索与证据边界，沿用原Deep Agents工具、限额和内容规则。"""

import json
from typing import Any, cast

from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph
from pydantic import JsonValue

from agentic_ai.agents.chat import ToolChatPort
from agentic_ai.agents.models import MAX_EVIDENCE, WorkInput, WorkResult
from agentic_ai.agents.pipeline import WorkGraphInput, WorkGraphOutput, WorkGraphState, WorkPipeline
from agentic_ai.qa.errors import QaCode, QaError
from agentic_ai.qa.models import KnowledgeHit
from agentic_ai.retrieval.consumer import RetrievalUseCase
from agentic_ai.retrieval.retriever import CandidatePort
from agentic_ai.retrieval.task_evidence import GenerationEvidenceRegistration, GenerationKnowledge
from agentic_ai.retrieval.task_models import WORK_GRAPH_V2, GenerationResult, generation_input
from agentic_ai.tasks.client import CentralTaskClient
from agentic_ai.tasks.models import TaskLease


class GenerationWorkInput(WorkGraphInput):
    """策略是输入的一部分，不能从部署默认值恢复。"""

    retrievalStrategy: str


class GenerationWorkState(WorkGraphState, total=False):
    """完整身份/贡献与内容投影在同一个中央确认的根图快照中提交。"""

    retrievalStrategy: str
    generationEvidence: list[dict[str, JsonValue]]
    retrievalDiagnostics: list[dict[str, JsonValue]]


class GenerationWorkPipeline(WorkPipeline):
    """不改变旧图版本，撤权和同内容重索引都按完整代次判定。"""

    input_fields = (*WorkPipeline.input_fields, "retrievalStrategy")

    def __init__(
        self,
        binding: TaskLease,
        central: CentralTaskClient,
        candidates: CandidatePort,
        chat: ToolChatPort,
        use_case: RetrievalUseCase,
    ) -> None:
        if binding.graph_version != WORK_GRAPH_V2:
            raise QaError(QaCode.INPUT_REJECTED)
        request, self.strategy = generation_input(binding.input, qa=False)
        self.generation = GenerationKnowledge(
            use_case, candidates, self.strategy, request.document_ids, MAX_EVIDENCE
        )
        super().__init__(binding, central, self.generation, chat)
        self.registration = GenerationEvidenceRegistration(
            central, binding, MAX_EVIDENCE, self.generation
        )

    def parse_input(self, value: object) -> WorkInput:
        """严格校验新输入，再适配原内容规则；持久binding保持v2原样。"""
        request, strategy = generation_input(value, qa=False)
        if not isinstance(request, WorkInput) or strategy != self.strategy:
            raise QaError(QaCode.INPUT_REJECTED)
        return request

    async def retrieve(self, state: WorkGraphState) -> dict[str, object]:
        """首轮检索同时提交完整代次，不能只保存原图的三元组。"""
        result = await super().retrieve(state)
        hits = self.evidence(cast(WorkGraphState, result))
        return {**result, **self.generation.snapshot(hits)}

    async def analyze(self, state: WorkGraphState) -> dict[str, object]:
        """恢复先装入完整依赖；工具补充检索的身份与诊断和分析结果一起保存。"""
        self.generation.restore(cast(dict[str, object], state))
        result = await super().analyze(state)
        combined = cast(WorkGraphState, {**state, **result})
        return {**result, **self.generation.snapshot(self.evidence(combined))}

    async def verify(self, state: WorkGraphState) -> dict[str, object]:
        """原报告校验通过后封装实际策略和对应完整引用，不改变报告内容。"""
        self.generation.restore(cast(dict[str, object], state))
        result = await super().verify(state)
        content = WorkResult.model_validate_json(json.dumps(result["report"]))
        envelope = self._result(content)
        return {"report": envelope.model_dump(mode="json", by_alias=True)}

    def _result(self, content: WorkResult) -> GenerationResult:
        return GenerationResult(
            schema_version="knowledge-work-result-v2",
            strategy=self.strategy,
            content=content,
            references=self.generation.references(content.citations),
            retrieval=tuple(self.generation.diagnostics),
        )

    async def before_restore(self, value: dict[str, object]) -> None:
        """完整快照同样重验所有生成依赖，不能仅以最后被引用的子集恢复。"""
        if "evidence" in value:
            self.generation.restore(value)
            await super().before_restore(value)

    async def before_complete(self, value: dict[str, object]) -> None:
        """已完成图无节点重跑时，仍检查完整依赖、信封及原内容规则。"""
        result = GenerationResult.from_wire(json.dumps(value).encode())
        if not isinstance(result.content, WorkResult) or result != self._result(result.content):
            raise QaError(QaCode.ANSWER_INVALID)
        await self.check_evidence(
            [KnowledgeHit.model_validate(v) for v in self.generation.serialized()]
        )
        await super().before_complete(result.content.model_dump(mode="json", by_alias=True))

    def build(
        self, saver: BaseCheckpointSaver[Any], *, interrupt_before: list[str] | None = None
    ) -> CompiledStateGraph[Any, Any, Any, Any]:
        """只扩展根图纯JSON状态，原检查点fencing和内部工具循环继续生效。"""
        graph = StateGraph(
            GenerationWorkState, input_schema=GenerationWorkInput, output_schema=WorkGraphOutput
        )
        # 覆写节点输入schema，避免框架从继承方法注解推断v1而丢弃策略/完整身份。
        graph.add_node("retrieve", self.retrieve, input_schema=GenerationWorkState)
        graph.add_node("analyze", self.analyze, input_schema=GenerationWorkState)
        graph.add_node("verify", self.verify, input_schema=GenerationWorkState)
        graph.add_edge(START, "retrieve")
        graph.add_edge("retrieve", "analyze")
        graph.add_edge("analyze", "verify")
        graph.add_edge("verify", END)
        return graph.compile(checkpointer=saver, interrupt_before=interrupt_before)
