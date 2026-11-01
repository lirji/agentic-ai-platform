"""v4根阶段恢复；只复用中央确认快照，Deep Agents内部仍保持原子阶段。"""

from typing import Any, cast
from uuid import uuid4

from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph
from pydantic import ValidationError

from agentic_ai.agents.generation_pipeline import GenerationWorkInput
from agentic_ai.agents.models import WorkInput
from agentic_ai.agents.pipeline import WorkGraphOutput, WorkGraphState
from agentic_ai.agents.research_models import EMPTY_QUESTION, PlanQuestion, ResearchPlan
from agentic_ai.agents.research_pipeline import ResearchPipeline, ResearchState
from agentic_ai.qa.errors import QaCode, QaError
from agentic_ai.qa.models import KnowledgeHit
from agentic_ai.retrieval.models import RetrievalStrategy
from agentic_ai.tasks.models import TaskEventType
from agentic_ai.tasks.research import RESEARCH_RESUMING, ResearchResumeStage

STAGED_RESEARCH_GRAPH = "knowledge-work-v4"
STAGED_RESEARCH_INPUT = "knowledge-work-input-v4"


class StagedResearchPipeline(ResearchPipeline):
    """旧v3仍按三节点运行；新图只在已确认计划之后开始生成报告。"""

    graph_version = STAGED_RESEARCH_GRAPH

    def request_input(self, value: object) -> tuple[WorkInput, RetrievalStrategy]:
        """独立输入版本不能被旧图误领，内容/权限沿已冻结研究规则。"""
        if not isinstance(value, dict) or value.get("schemaVersion") != STAGED_RESEARCH_INPUT:
            raise QaError(QaCode.INPUT_REJECTED)
        return super().request_input({**value, "schemaVersion": "knowledge-work-input-v3"})

    async def plan_stage(self, state: ResearchState) -> dict[str, object]:
        """计划不接收可执行工具；原文和租约重新验证后才调用计划模型。"""
        self.generation.restore(cast(dict[str, object], state))
        hits = self.evidence(state)
        await self.check_evidence(hits)
        if hits:
            await super().prepare_analysis(hits, [])
        else:
            self.plan = ResearchPlan(items=[PlanQuestion(id="Q1", question=EMPTY_QUESTION)])
        if self.plan is None:
            raise QaError(QaCode.ANSWER_INVALID)
        return {"plan": self.plan.model_dump(mode="json", by_alias=True)}

    async def prepare_analysis(self, hits: list[KnowledgeHit], tools: list[Any]) -> None:
        """分析只消费已确认计划，恢复时不得隐式再调用计划模型。"""
        if self.plan is None:
            raise QaError(QaCode.ANSWER_INVALID)
        await self.assert_active()

    def restore_plan(self, value: object) -> None:
        """计划结构和顺序与v3相同，缺失或被篡改不能进入后续阶段。"""
        try:
            self.plan = ResearchPlan.model_validate(value)
        except (ValidationError, ValueError, TypeError):
            raise QaError(QaCode.ANSWER_INVALID) from None

    async def analyze(self, state: WorkGraphState) -> dict[str, object]:
        """普通执行和接管执行都显式从根快照读取计划，实例内存不作为权威。"""
        self.restore_plan(cast(dict[str, object], state).get("plan"))
        return await super().analyze(state)

    async def before_restore(self, value: dict[str, object]) -> None:
        """确认权限后报告恢复位置和中央累计预留，不暴露检查点或正文。"""
        # 初始空快照尚未投影输入；一旦存在业务字段，必须与中央冻结输入逐项一致。
        if any(key in value for key in (*self.input_fields, "evidence", "plan", "draft", "report")):
            try:
                frozen = {key: value[key] for key in self.input_fields}
                if self.parse_input(frozen) != self.request:
                    raise ValueError
            except (KeyError, ValueError, TypeError, ValidationError):
                raise QaError(QaCode.INPUT_REJECTED) from None
        await super().before_restore(value)
        if "report" in value:
            stage = ResearchResumeStage.SUBMITTING
        elif "draft" in value:
            stage = ResearchResumeStage.VERIFYING
        elif "plan" in value:
            stage = ResearchResumeStage.ANALYZING
        elif "evidence" in value:
            stage = ResearchResumeStage.PLANNING
        else:
            stage = ResearchResumeStage.RETRIEVING
        if stage in (ResearchResumeStage.VERIFYING, ResearchResumeStage.SUBMITTING):
            self.restore_plan(value.get("plan"))
        usage = await self.central.research_usage(self.binding.task.task_id, self.binding.epoch)
        await self.central.event(
            self.binding.task.task_id,
            self.binding.epoch,
            f"research-resume:{self.binding.epoch}:{uuid4()}",
            TaskEventType.PROGRESS,
            {
                "stage": RESEARCH_RESUMING,
                "nextStage": stage.value,
                "usage": usage.model_dump(mode="json", by_alias=True),
            },
        )

    def build(
        self, saver: BaseCheckpointSaver[Any], *, interrupt_before: list[str] | None = None
    ) -> CompiledStateGraph[Any, Any, Any, Any]:
        """同步durability由既有执行入口提供；每个根阶段保存纯JSON。"""
        graph = StateGraph(
            ResearchState, input_schema=GenerationWorkInput, output_schema=WorkGraphOutput
        )
        for name, node in [
            ("retrieve", self.retrieve),
            ("plan", self.plan_stage),
            ("analyze", self.analyze),
            ("verify", self.verify),
        ]:
            graph.add_node(name, node, input_schema=ResearchState)
        for source, target in [
            (START, "retrieve"),
            ("retrieve", "plan"),
            ("plan", "analyze"),
            ("analyze", "verify"),
            ("verify", END),
        ]:
            graph.add_edge(source, target)
        return graph.compile(checkpointer=saver, interrupt_before=interrupt_before)
