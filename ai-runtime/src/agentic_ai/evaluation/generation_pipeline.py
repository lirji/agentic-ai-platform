"""评测v2直接复用逐题QA和统一检索，恢复只核验已落盘题目，不重做模型调用。"""

from typing import Any

from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.graph import START, StateGraph
from langgraph.graph.state import CompiledStateGraph

from agentic_ai.evaluation.client import EvaluationPort
from agentic_ai.evaluation.models import (
    TOP_K,
    GenerationEvaluationInput,
    GenerationEvidence,
    GenerationQuestionReport,
    Opened,
    Profile,
    Question,
    QuestionReport,
)
from agentic_ai.evaluation.pipeline import (
    EvaluationPipeline,
    GenerationRunSnapshot,
    GraphInput,
    GraphState,
)
from agentic_ai.qa.chat import ChatPort
from agentic_ai.qa.errors import QaCode, QaError
from agentic_ai.qa.pipeline import QaPipeline
from agentic_ai.qa.retriever import KnowledgePort
from agentic_ai.retrieval.consumer import RetrievalUseCase
from agentic_ai.retrieval.retriever import CandidatePort
from agentic_ai.retrieval.task_evidence import GenerationKnowledge
from agentic_ai.tasks.client import CentralTaskClient
from agentic_ai.tasks.models import TaskLease


class GenerationGraphInput(GraphInput):
    """策略进入每个根图节点，不能被继承方法的schema投影丢弃。"""

    retrievalStrategy: str


class GenerationGraphState(GraphState):
    """状态不累计所有题目的全文，完整依赖在Knowledge不可变候选中。"""

    retrievalStrategy: str


class GenerationEvaluationPipeline(EvaluationPipeline):
    """本实例的策略来自中央持久输入；每题独立适配器共享同一容量用例。"""

    input_model = GenerationEvaluationInput
    snapshot_model = GenerationRunSnapshot

    def __init__(
        self,
        binding: TaskLease,
        central: CentralTaskClient,
        candidates: CandidatePort,
        chat: ChatPort,
        evaluations: EvaluationPort,
        profile: Profile,
        opened: Opened,
        retrieval: RetrievalUseCase,
    ) -> None:
        self.candidates, self.retrieval = candidates, retrieval
        # 父类初始化guard会访问本策略，因此先从严格输入冻结。
        import json

        incoming = GenerationEvaluationInput.model_validate_json(json.dumps(binding.input))
        self.strategy = incoming.retrieval_strategy
        if profile.retrieval_profile != self.strategy.value:
            raise QaError(QaCode.INPUT_REJECTED)
        first = GenerationKnowledge(
            retrieval, candidates, self.strategy, opened.definition.questions[0].document_ids, TOP_K
        )
        super().__init__(binding, central, first, chat, evaluations, profile, opened)

    def _knowledge(self, question: Question) -> KnowledgePort:
        return GenerationKnowledge(
            self.retrieval, self.candidates, self.strategy, question.document_ids, TOP_K
        )

    def _report(self, qa: QaPipeline, report: QuestionReport) -> QuestionReport:
        knowledge = qa.knowledge
        if not isinstance(knowledge, GenerationKnowledge) or len(knowledge.diagnostics) > 1:
            raise QaError(QaCode.EVIDENCE_REJECTED)
        return GenerationQuestionReport(
            **report.model_dump(),
            generation=GenerationEvidence(
                strategy=self.strategy,
                references=knowledge.references(report.retrieved),
                retrieval=knowledge.diagnostics[0] if knowledge.diagnostics else None,
            ),
        )

    async def before_restore(self, value: dict[str, object]) -> None:
        """新执行器核验成功前缀及全部完整引用，失败后不能从未核验的前缀继续花费。"""
        if "nextIndex" not in value:
            if self.parse_input(value) != self.input:
                raise QaError(QaCode.INPUT_REJECTED)
            await self._current()
            return
        snapshot = self._snapshot(value)
        await self._current()
        for receipt in snapshot.receipts:
            await self.assert_active()
            report = await self.evaluations.candidate(receipt)
            if (
                not isinstance(report, GenerationQuestionReport)
                or report.outcome != "SUCCEEDED"
                or report.question_id != receipt.question_id
                or report.generation.strategy != self.strategy
            ):
                raise QaError(QaCode.EVIDENCE_REJECTED)
            # 此受限端点按当前owner核验全部八分量身份，报告没有正文，不在此降级三元组解析。

    def build(
        self, saver: BaseCheckpointSaver[Any], *, interrupt_before: list[str] | None = None
    ) -> CompiledStateGraph[Any, Any, Any, Any]:
        """显式节点输入schema保留策略，原逐题报告/故障前缀逻辑继续共用。"""
        graph = StateGraph(
            GenerationGraphState,
            input_schema=GenerationGraphInput,
            output_schema=GenerationGraphState,
        )
        graph.add_node("initialize", self.initialize, input_schema=GenerationGraphState)
        graph.add_node("question", self.evaluate, input_schema=GenerationGraphState)
        graph.add_edge(START, "initialize")
        graph.add_conditional_edges("initialize", lambda state: self.route(state))
        graph.add_conditional_edges("question", lambda state: self.route(state))
        return graph.compile(checkpointer=saver, interrupt_before=interrupt_before)
