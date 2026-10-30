"""逐题根图：同步检查点只存下一题与不可变候选引用，完整答案留在 Knowledge。"""

import json
from hashlib import sha256
from time import monotonic
from typing import Any, TypedDict, cast

from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph
from pydantic import Field, JsonValue

from agentic_ai.evaluation.client import EvaluationPort
from agentic_ai.evaluation.metrics import retrieval_metrics
from agentic_ai.evaluation.models import (
    LEGACY_RETRIEVAL_PROFILE,
    MAX_QUESTIONS,
    CandidateReceipt,
    Completion,
    Cost,
    EvaluationInput,
    GenerationEvaluationInput,
    Opened,
    Profile,
    Question,
    QuestionReport,
    Retrieved,
    Timing,
)
from agentic_ai.qa.chat import ChatPort
from agentic_ai.qa.chat_config import ChatConfig
from agentic_ai.qa.errors import QaCode, QaError, failure_code
from agentic_ai.qa.models import KnowledgeHit, QaInput, QaResult
from agentic_ai.qa.pipeline import SYSTEM_PROMPT, QaPipeline
from agentic_ai.qa.pipeline import GraphState as QaState
from agentic_ai.qa.retriever import KnowledgePort
from agentic_ai.tasks.client import CentralTaskClient
from agentic_ai.tasks.errors import TaskRuntimeCode, TaskRuntimeError
from agentic_ai.tasks.models import QaStage, TaskFailureCode, TaskLease


class EvaluationFailed(Exception):
    """仅携带已保存清单和安全目录，原模型/资料异常不进入中央事件。"""

    def __init__(self, code: TaskFailureCode, retryable: bool, proof: Completion) -> None:
        self.code, self.retryable, self.proof = code, retryable, proof
        super().__init__(code.value)


class RunSnapshot(EvaluationInput):
    """PG 只保存已完成题的不可变寻址，未确认模型调用不能冒充已完成。"""

    next_index: int = Field(ge=0, le=MAX_QUESTIONS)
    profile_digest: str
    receipts: list[CandidateReceipt] = Field(max_length=MAX_QUESTIONS)


class GenerationRunSnapshot(GenerationEvaluationInput):
    """v2检查点额外冻结策略，仍只保存不可变候选寻址。"""

    next_index: int = Field(ge=0, le=MAX_QUESTIONS)
    profile_digest: str
    receipts: list[CandidateReceipt] = Field(max_length=MAX_QUESTIONS)


class GraphInput(TypedDict):
    """中央冻结输入原样进入根图。"""

    schemaVersion: str
    runId: str
    datasetId: str
    datasetRevision: int
    definitionDigest: str
    topK: int


class GraphState(GraphInput, total=False):
    """每题完成后一次同步保存进度，成功前缀可跨代次恢复。"""

    nextIndex: int
    profileDigest: str
    receipts: list[dict[str, JsonValue]]


def model_profile(config: ChatConfig) -> Profile:
    """只提取安全配置；provider key、地址和账本路径都不进入图和报告。"""
    return Profile(
        model=config.approved_model,
        route=config.route,
        max_output_tokens=config.max_tokens,
        prompt_digest=sha256(SYSTEM_PROMPT.encode()).hexdigest(),
        retrieval_profile=LEGACY_RETRIEVAL_PROFILE,
    )


class EvaluationPipeline:
    """每题直接复用生产 QA 方法，图只负责顺序、报告与恢复。"""

    input_model: type[EvaluationInput | GenerationEvaluationInput] = EvaluationInput
    snapshot_model: type[RunSnapshot | GenerationRunSnapshot] = RunSnapshot

    def parse_input(self, value: object) -> EvaluationInput | GenerationEvaluationInput:
        """输入版本由执行图固定；JSON边界保持严格枚举与类型校验。"""
        return self.input_model.model_validate_json(json.dumps(value))

    def _knowledge(self, question: Question) -> KnowledgePort:
        """旧图保持原端口，新图按每题范围创建完整身份适配。"""
        return self.knowledge

    def _report(self, qa: QaPipeline, report: QuestionReport) -> QuestionReport:
        """旧报告字节结构不扩充，版本扩展在保存前统一完成。"""
        return report

    def __init__(
        self,
        binding: TaskLease,
        central: CentralTaskClient,
        knowledge: KnowledgePort,
        chat: ChatPort,
        evaluations: EvaluationPort,
        profile: Profile,
        opened: Opened,
    ) -> None:
        self.binding, self.central, self.knowledge, self.chat = binding, central, knowledge, chat
        self.evaluations, self.profile, self.opened = evaluations, profile, opened
        self.input = self.parse_input(binding.input)
        if (
            opened.run_id != self.input.run_id
            or opened.task_id != binding.task.task_id
            or opened.dataset_id != self.input.dataset_id
            or opened.dataset_revision != self.input.dataset_revision
            or opened.definition_digest != self.input.definition_digest
            or opened.top_k != self.input.top_k
        ):
            raise QaError(QaCode.INPUT_REJECTED)
        expected_profile = (
            self.input.retrieval_strategy.value
            if isinstance(self.input, GenerationEvaluationInput)
            else LEGACY_RETRIEVAL_PROFILE
        )
        if profile.retrieval_profile != expected_profile:
            raise QaError(QaCode.INPUT_REJECTED)
        self.guard = self._qa(opened.definition.questions[0])

    def _qa(self, question: Question) -> QaPipeline:
        independent = QaInput(
            schema_version="knowledge-qa-input-v1",
            conversation_id=self.input.run_id,
            turn_id=question.question_id,
            question=question.question,
            document_ids=question.document_ids,
            context_turn_id=None,
            context_task_id=None,
            context_question=None,
        )
        return QaPipeline(
            self.binding,
            self.central,
            self._knowledge(question),
            self.chat,
            evaluation_question=independent,
            operation_scope=f"eval:{question.question_id}",
            emit_text=False,
        )

    async def assert_active(self) -> None:
        """保持生产 QA 的中央身份/代次/取消核验；固定心跳与节点调用共用规则。"""
        await self.guard.assert_active()

    async def _current(self) -> None:
        await self.assert_active()
        current = await self.evaluations.open(self.profile)
        if current != self.opened:
            raise QaError(QaCode.EVIDENCE_REJECTED)

    def _snapshot(self, state: dict[str, object]) -> RunSnapshot | GenerationRunSnapshot:
        snapshot = self.snapshot_model.model_validate_json(json.dumps(state))
        if (
            self.parse_input(
                snapshot.model_dump(
                    mode="json", by_alias=True, include=set(self.input_model.model_fields)
                )
            )
            != self.input
            or snapshot.profile_digest != self.opened.profile_digest
            or snapshot.next_index != len(snapshot.receipts)
            or snapshot.next_index > len(self.opened.definition.questions)
            or any(
                receipt.question_id != self.opened.definition.questions[index].question_id
                or receipt.epoch > self.binding.epoch
                for index, receipt in enumerate(snapshot.receipts)
            )
        ):
            raise TaskRuntimeError(TaskRuntimeCode.CHECKPOINT_INVALID)
        return snapshot

    async def initialize(self, state: GraphState) -> dict[str, object]:
        """首次运行只初始化顺序状态，恢复不会覆盖已有成功前缀。"""
        if self.parse_input(state) != self.input:
            raise QaError(QaCode.INPUT_REJECTED)
        await self._current()
        return {"nextIndex": 0, "receipts": [], "profileDigest": self.opened.profile_digest}

    async def evaluate(self, state: GraphState) -> dict[str, object]:
        """候选保存和失败处理分开，保存响应未知时不能用失败正文覆盖同一题。"""
        snapshot = self._snapshot(cast(dict[str, object], state))
        question = self.opened.definition.questions[snapshot.next_index]
        await self._current()
        qa = self._qa(question)
        qa_state = cast(QaState, qa.question.model_dump(mode="json", by_alias=True))
        retrieved: list[Retrieved] = []
        durations: dict[QaStage, int | None] = {
            QaStage.RETRIEVING: None,
            QaStage.GENERATING: None,
            QaStage.VERIFYING: None,
        }
        started = monotonic()
        phase: QaStage | None = None
        phase_started = started
        try:
            for phase, operation in (
                (QaStage.RETRIEVING, qa.retrieve),
                (QaStage.GENERATING, qa.generate),
                (QaStage.VERIFYING, qa.verify),
            ):
                phase_started = monotonic()
                values = await operation(qa_state)
                durations[phase] = int((monotonic() - phase_started) * 1000)
                qa_state.update(cast(QaState, values))
                if phase == QaStage.RETRIEVING:
                    hits = [KnowledgeHit.model_validate(hit) for hit in qa_state["evidence"]]
                    versions = {item.document_id: item.version for item in self.opened.bindings}
                    if any(hit.version != versions.get(hit.document_id) for hit in hits):
                        raise QaError(QaCode.EVIDENCE_REJECTED)
                    retrieved = [
                        Retrieved(
                            document_id=hit.document_id,
                            version=hit.version,
                            chunk_id=hit.chunk_id,
                            text_hash=sha256(hit.text.encode()).hexdigest(),
                            score=hit.score,
                            source=hit.source,
                        )
                        for hit in hits
                    ]
            # QA 导出的是 JSON 编码值；严格枚举须按 JSON 边界读取，不能当 Python Enum 实例。
            result = QaResult.model_validate_json(
                json.dumps(
                    {
                        key: cast(dict[str, object], qa_state)[key]
                        for key in ("schemaVersion", "answerability", "answer", "citations")
                    }
                )
            )
        except Exception as error:
            if isinstance(error, TaskRuntimeError) and error.code == TaskRuntimeCode.LEASE_LOST:
                raise
            if phase is not None and durations[phase] is None:
                durations[phase] = int((monotonic() - phase_started) * 1000)
            code, retryable = failure_code(error)
            report = QuestionReport(
                question_id=question.question_id,
                outcome="FAILED",
                result=None,
                retrieved=retrieved,
                metrics=None,
                timing=self._timing(started, durations),
                cost=Cost(),
                error_code=code,
            )
            # 只在仍有权限/活跃租约时形成失败报告；失败上报本身未知则由中央接管，不伪造凭据。
            await self._current()
            failed = await self.evaluations.save(self._report(qa, report))
            manifest = await self.evaluations.manifest(
                self.opened.profile_digest, False, [*snapshot.receipts, failed]
            )
            proof = Completion(
                run_id=self.input.run_id,
                epoch=self.binding.epoch,
                manifest_digest=manifest.manifest_digest,
            )
            raise EvaluationFailed(code, retryable, proof) from None
        report = QuestionReport(
            question_id=question.question_id,
            outcome="SUCCEEDED",
            result=result,
            retrieved=retrieved,
            metrics=retrieval_metrics(question, retrieved),
            timing=self._timing(started, durations),
            cost=Cost(chat_attempts=0, chat_reserved_cny="0") if not retrieved else Cost(),
            error_code=None,
        )
        await self._current()
        receipt = await self.evaluations.save(self._report(qa, report))
        return {
            "nextIndex": snapshot.next_index + 1,
            "receipts": [
                item.model_dump(mode="json", by_alias=True)
                for item in [*snapshot.receipts, receipt]
            ],
        }

    @staticmethod
    def _timing(started: float, stages: dict[QaStage, int | None]) -> Timing:
        return Timing(
            total_ms=int((monotonic() - started) * 1000),
            retrieval_ms=stages[QaStage.RETRIEVING],
            generation_ms=stages[QaStage.GENERATING],
            verification_ms=stages[QaStage.VERIFYING],
        )

    def route(self, state: GraphState) -> str:
        """固定顺序，不让模型决定下一道题或省略失败题。"""
        snapshot = self._snapshot(cast(dict[str, object], state))
        return "question" if snapshot.next_index < len(self.opened.definition.questions) else END

    async def before_complete(self, value: dict[str, object]) -> dict[str, object]:
        """完整图快照恢复也重验绑定，并为当前 epoch 形成新凭据；不再次调用模型。"""
        snapshot = self._snapshot(value)
        if snapshot.next_index != len(self.opened.definition.questions):
            raise TaskRuntimeError(TaskRuntimeCode.CHECKPOINT_INVALID)
        await self._current()
        manifest = await self.evaluations.manifest(
            self.opened.profile_digest, True, snapshot.receipts
        )
        return Completion(
            run_id=self.input.run_id,
            epoch=self.binding.epoch,
            manifest_digest=manifest.manifest_digest,
        ).model_dump(mode="json", by_alias=True)

    def build(
        self, saver: BaseCheckpointSaver[Any], *, interrupt_before: list[str] | None = None
    ) -> CompiledStateGraph[Any, Any, Any, Any]:
        """一题一节点；候选成功后完整检查点同步提交，再开始下一题。"""
        graph = StateGraph(GraphState, input_schema=GraphInput, output_schema=GraphState)
        graph.add_node("initialize", self.initialize)
        graph.add_node("question", self.evaluate)
        graph.add_edge(START, "initialize")
        graph.add_conditional_edges("initialize", self.route)
        graph.add_conditional_edges("question", self.route)
        return graph.compile(checkpointer=saver, interrupt_before=interrupt_before)
