"""独立研究v3复用授权检索与Deep Agents；计划和报告均遵守全任务持久额度。"""

import json
from collections.abc import Awaitable, Callable
from typing import Any, cast
from uuid import uuid4

from langchain_core.messages import AIMessage
from langchain_core.utils.function_calling import convert_to_openai_tool
from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph
from pydantic import JsonValue, ValidationError

from agentic_ai.agents.chat import ToolChatPort, validate_reply
from agentic_ai.agents.generation_pipeline import (
    GenerationWorkInput,
    GenerationWorkPipeline,
    GenerationWorkState,
)
from agentic_ai.agents.models import ToolRecord, WorkCitation, WorkInput
from agentic_ai.agents.pipeline import WorkGraphOutput, WorkGraphState, WorkPipeline
from agentic_ai.agents.research_models import (
    EMPTY_GAP,
    EMPTY_QUESTION,
    EMPTY_SUMMARY,
    EMPTY_TITLE,
    PLAN_PROMPT,
    RESEARCH_GRAPH,
    RESEARCH_PROMPT,
    Coverage,
    CoverageStatus,
    PlanQuestion,
    ResearchDraft,
    ResearchPlan,
    ResearchReport,
    ResearchResult,
    outcome,
    research_input,
)
from agentic_ai.qa.errors import QaCode, QaError
from agentic_ai.qa.models import KnowledgeHit
from agentic_ai.retrieval.consumer import RetrievalUseCase
from agentic_ai.retrieval.models import RetrievalStrategy
from agentic_ai.retrieval.retriever import CandidatePort
from agentic_ai.tasks.client import CentralTaskClient
from agentic_ai.tasks.errors import TaskRuntimeCode, TaskRuntimeError
from agentic_ai.tasks.models import QaStage, TaskLease
from agentic_ai.tasks.research import ResearchCall


class ResearchState(GenerationWorkState, total=False):
    """只持久可读计划与报告JSON，不保存框架消息、服务凭据或思维链。"""

    plan: dict[str, JsonValue]


class ReservedChat:
    """计划和工具循环共用一个中央预留入口，未知结果绝不自动重发。"""

    def __init__(
        self, port: ToolChatPort, reserve: Callable[[ResearchCall], Awaitable[None]]
    ) -> None:
        self.port, self.reserve = port, reserve

    async def complete(
        self, messages: list[dict[str, Any]], tools: list[dict[str, Any]], operation_id: str
    ) -> AIMessage:
        """先取得许可，再交给既有金额账本和受控模型端口。"""
        await self.reserve(ResearchCall.MODEL)
        return await self.port.complete(messages, tools, operation_id)


class ResearchPipeline(GenerationWorkPipeline):
    """v1/v2保持原语义，新图在相同知识范围内给出有依据线索或明确缺口。"""

    graph_version = RESEARCH_GRAPH
    analysis_prompt = RESEARCH_PROMPT

    def __init__(
        self,
        binding: TaskLease,
        central: CentralTaskClient,
        candidates: CandidatePort,
        chat: ToolChatPort,
        use_case: RetrievalUseCase,
    ) -> None:
        super().__init__(binding, central, candidates, chat, use_case)
        self.chat = ReservedChat(self.chat, self.reserve)
        self.plan: ResearchPlan | None = None

    def request_input(self, value: object) -> tuple[WorkInput, RetrievalStrategy]:
        """新版本输入不允许落回旧图，也不扩大助手能力。"""
        try:
            return research_input(value)
        except (ValidationError, ValueError, TypeError):
            raise QaError(QaCode.INPUT_REJECTED) from None

    async def reserve(self, kind: ResearchCall) -> None:
        """跨epoch额度由中央保存；回复未知或额度用尽均在外部动作前停止。"""
        await self.assert_active()
        try:
            await self.central.reserve_research(
                self.binding.task.task_id, self.binding.epoch, str(uuid4()), kind
            )
        except TaskRuntimeError as error:
            if error.code == TaskRuntimeCode.RESEARCH_LIMIT:
                raise QaError(QaCode.INPUT_REJECTED) from None
            raise
        await self.assert_active()

    async def retrieve(self, state: WorkGraphState) -> dict[str, object]:
        """初始检索也必须消耗全任务额度；无已确认快照时不重复免费执行。"""
        await self.reserve(ResearchCall.INITIAL_SEARCH)
        return await super().retrieve(state)

    async def before_tool(self, name: str) -> None:
        """白名单在框架和模型端口已校验，此处按真实工具预留共享额度。"""
        if name not in {"search_knowledge", "read_evidence"}:
            raise QaError(QaCode.INPUT_REJECTED)
        await self.reserve(
            ResearchCall.SEARCH_TOOL if name == "search_knowledge" else ResearchCall.READ_TOOL
        )

    async def prepare_analysis(self, hits: list[KnowledgeHit], tools: list[Any]) -> None:
        """计划是一次受预算控制的模型调用，不执行计划响应中的工具。"""
        await self.progress(QaStage.PLANNING)
        await self.check_evidence(hits)
        reply = validate_reply(
            await self.chat.complete(
                [
                    {"role": "system", "content": PLAN_PROMPT},
                    {"role": "user", "content": self.request.question},
                ],
                [convert_to_openai_tool(tool) for tool in tools],
                f"research-plan:{self.binding.task.task_id}",
            )
        )
        try:
            if reply.tool_calls or not isinstance(reply.content, str):
                raise ValueError
            self.plan = ResearchPlan.from_wire(reply.content.encode())
        except (ValidationError, ValueError, TypeError):
            raise QaError(QaCode.ANSWER_INVALID) from None

    def analysis_context(self, hits: list[KnowledgeHit]) -> dict[str, object]:
        """已校验计划作为固定任务内容，不能让最终报告改写子问题归属。"""
        if self.plan is None:
            raise QaError(QaCode.ANSWER_INVALID)
        return {
            **super().analysis_context(hits),
            "plan": self.plan.model_dump(mode="json", by_alias=True),
        }

    def parse_draft(self, reply: str) -> dict[str, JsonValue]:
        """拒绝未知字段、替换计划ID和引用不闭合；不自动修补模型输出。"""
        draft = ResearchDraft.from_wire(reply.encode())
        if self.plan is None or [item.id for item in self.plan.items] != [
            item.question_id for item in draft.coverage
        ]:
            raise QaError(QaCode.ANSWER_INVALID)
        return draft.model_dump(mode="json", by_alias=True)

    async def analyze(self, state: WorkGraphState) -> dict[str, object]:
        """本片仍在同一根阶段内完成计划与分析，S12D才增加阶段恢复。"""
        self.generation.restore(cast(dict[str, object], state))
        hits = self.evidence(state)
        await self.check_evidence(hits)
        if not hits:
            self.plan = ResearchPlan(items=[PlanQuestion(id="Q1", question=EMPTY_QUESTION)])
            draft = ResearchDraft(
                title=EMPTY_TITLE,
                coverage=[
                    Coverage(
                        question_id="Q1",
                        status=CoverageStatus.GAP,
                        answer="",
                        evidence=[],
                        gap=EMPTY_GAP,
                    )
                ],
                summary=EMPTY_SUMMARY,
            )
            result: dict[str, object] = {
                "draft": draft.model_dump(mode="json", by_alias=True),
                "toolCalls": [],
            }
        else:
            result = await WorkPipeline.analyze(self, state)
        if self.plan is None:
            raise QaError(QaCode.ANSWER_INVALID)
        combined = cast(WorkGraphState, {**state, **result})
        return {
            **result,
            "plan": self.plan.model_dump(mode="json", by_alias=True),
            **self.generation.snapshot(self.evidence(combined)),
        }

    def check_quotes(self, draft: ResearchDraft, hits: list[KnowledgeHit]) -> None:
        """引用片段须来自对应编号的实际授权原文；这不代替语义质量评审。"""
        for item in draft.coverage:
            for quoted in item.evidence:
                index = int(quoted.citation_id) - 1
                if not 0 <= index < len(hits) or quoted.quote not in hits[index].text:
                    raise QaError(QaCode.ANSWER_INVALID)

    def result(self, report: ResearchReport) -> ResearchResult:
        """完整引用从已校验代次派生，模型不填写数据库标识。"""
        return ResearchResult(
            schema_version="knowledge-work-result-v3",
            strategy=self.strategy,
            content=report,
            references=self.generation.references(report.citations),
            retrieval=tuple(self.generation.diagnostics),
        )

    def citations(self, draft: ResearchDraft, hits: list[KnowledgeHit]) -> list[WorkCitation]:
        """编号顺序与原文/资料名绑定，恢复后的报告也不能替换展示来源或对调编号。"""
        return [
            WorkCitation.model_validate(
                {"citationId": str(index), **hit.model_dump(exclude={"score", "text"})}
            )
            for index, hit in enumerate(hits, 1)
            if str(index) in draft.used_citations()
        ]

    async def verify(self, state: WorkGraphState) -> dict[str, object]:
        """检查计划/逐字引文/完整权限和持久额度，派生业务结果后才提交。"""
        self.generation.restore(cast(dict[str, object], state))
        await self.progress(QaStage.VERIFYING)
        hits = self.evidence(state)
        await self.check_evidence(hits)
        try:
            plan = ResearchPlan.model_validate(cast(dict[str, Any], state)["plan"])
            draft = ResearchDraft.from_wire(json.dumps(state["draft"]).encode())
            self.check_quotes(draft, hits)
            report = ResearchReport(
                **draft.model_dump(),
                schema_version="research-report-v1",
                kind="RESEARCH",
                mode="REPORT",
                plan=plan.items,
                outcome=outcome(draft.coverage),
                citations=self.citations(draft, hits),
                tool_calls=[ToolRecord.model_validate(value) for value in state["toolCalls"]],
                usage=await self.central.research_usage(
                    self.binding.task.task_id, self.binding.epoch
                ),
            )
            return {"report": self.result(report).model_dump(mode="json", by_alias=True)}
        except (ValidationError, ValueError, KeyError, TypeError):
            raise QaError(QaCode.ANSWER_INVALID) from None

    async def before_restore(self, value: dict[str, object]) -> None:
        """已保存计划在恢复时重新校验，最终报告不能替换已确认计划。"""
        await super().before_restore(value)
        if "plan" in value:
            try:
                self.plan = ResearchPlan.model_validate(value["plan"])
            except (ValidationError, ValueError, TypeError):
                raise QaError(QaCode.ANSWER_INVALID) from None

    async def before_complete(self, value: dict[str, object]) -> None:
        """恢复已完成快照也必须重验全部生成依赖，不能靠最终引用子集逃过撤权。"""
        try:
            result = ResearchResult.from_wire(json.dumps(value).encode())
            if (
                result != self.result(result.content)
                or self.plan is None
                or result.content.plan != self.plan.items
            ):
                raise ValueError
            hits = [KnowledgeHit.model_validate(item) for item in self.generation.serialized()]
            self.check_quotes(result.content, hits)
            if result.content.citations != self.citations(result.content, hits):
                raise ValueError
            await self.check_evidence(hits)
            await self.assert_active()
            if result.content.usage != await self.central.research_usage(
                self.binding.task.task_id, self.binding.epoch
            ):
                raise ValueError
        except (ValidationError, ValueError, TypeError):
            raise QaError(QaCode.ANSWER_INVALID) from None

    def build(
        self, saver: BaseCheckpointSaver[Any], *, interrupt_before: list[str] | None = None
    ) -> CompiledStateGraph[Any, Any, Any, Any]:
        """计划和报告仍是有界JSON，内部Deep Agents不启用检查点或子图。"""
        graph = StateGraph(
            ResearchState, input_schema=GenerationWorkInput, output_schema=WorkGraphOutput
        )
        for name, node in [
            ("retrieve", self.retrieve),
            ("analyze", self.analyze),
            ("verify", self.verify),
        ]:
            graph.add_node(name, node, input_schema=ResearchState)
        graph.add_edge(START, "retrieve")
        graph.add_edge("retrieve", "analyze")
        graph.add_edge("analyze", "verify")
        graph.add_edge("verify", END)
        return graph.compile(checkpointer=saver, interrupt_before=interrupt_before)
