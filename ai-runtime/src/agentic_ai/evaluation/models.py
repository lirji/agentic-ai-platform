"""E01/E02 严格传输模型；问题定义和运行身份由 Knowledge/Tasks 冻结。"""

from typing import Annotated, Literal, Self

from pydantic import Field, StringConstraints, model_validator

from agentic_ai.qa.models import CitationSource, QaResult
from agentic_ai.tasks.models import CanonicalId, Sha256, TaskFailureCode, WireModel

EVALUATION_GRAPH = "knowledge-evaluation-v1"
EVALUATION_KIND = "KNOWLEDGE_EVALUATION"
EXECUTION_SECONDS = 600
GRAPH_STEPS = 202
MAX_QUESTIONS = 100
MAX_TEXT = 2000
MAX_REFERENCE = 4000
MAX_DOCUMENTS_PER_QUESTION = 20
MAX_EVIDENCE = 10
MAX_NAME = 120
MAX_BINDINGS = 100
MAX_VERSION_LABEL = 128
TOP_K = 5
MAX_REPORT_BYTES = 128 * 1024
MAX_OPEN_BYTES = 1024 * 1024
Money = Annotated[str, StringConstraints(pattern=r"^[0-9]{1,9}(\.[0-9]{1,12})?$")]


class EvaluationInput(WireModel):
    """浏览器不能在任务中选择模型、图或执行身份。"""

    schema_version: Literal["evaluation-input-v1"]
    run_id: CanonicalId
    dataset_id: CanonicalId
    dataset_revision: int = Field(ge=1)
    definition_digest: Sha256
    top_k: Literal[5]


class Gold(WireModel):
    """标准证据按版本与块身份匹配，等级不是相似度阈值。"""

    document_id: CanonicalId
    version: int = Field(ge=1)
    chunk_id: Sha256
    relevance: Literal[1, 2]


class Question(WireModel):
    """问题以发布语料为范围，金标与参考答案不会传给生成模型。"""

    question_id: CanonicalId
    question: str = Field(min_length=1, max_length=MAX_TEXT)
    expectation: Literal["ANSWERABLE", "NO_ANSWER"]
    critical: bool
    rubric: str = Field(min_length=1, max_length=MAX_TEXT)
    reference_answer: str = Field(max_length=MAX_REFERENCE)
    document_ids: list[CanonicalId] = Field(min_length=1, max_length=MAX_DOCUMENTS_PER_QUESTION)
    evidence: list[Gold] = Field(max_length=MAX_EVIDENCE)

    @model_validator(mode="after")
    def bound(self) -> Self:
        """当前 QA 只接收规范范围，不能在执行时悄悄去重或重写定义。"""
        if (
            self.document_ids != sorted(set(self.document_ids))
            or (self.expectation == "ANSWERABLE") != bool(self.evidence)
            or any(item.document_id not in self.document_ids for item in self.evidence)
            or len({(g.document_id, g.version, g.chunk_id) for g in self.evidence})
            != len(self.evidence)
        ):
            raise ValueError("评测题目绑定无效")
        return self


class Definition(WireModel):
    """只消费指定修订，不在 worker 读取 latest。"""

    name: str = Field(min_length=1, max_length=MAX_NAME)
    description: str = Field(max_length=MAX_TEXT)
    purpose: Literal["MECHANISM", "BUSINESS"]
    questions: list[Question] = Field(min_length=1, max_length=MAX_QUESTIONS)


class DocumentBinding(WireModel):
    """资料内容与处理版本绑定，当前权限仍由 Knowledge 核验。"""

    document_id: CanonicalId
    version: int = Field(ge=1)
    source_hash: Sha256
    batch_digest: Sha256
    processor_version: str = Field(min_length=1, max_length=MAX_VERSION_LABEL)


class Profile(WireModel):
    """公开模型画像不包含 URL、账户、文件路径和余额。"""

    model: Annotated[str, StringConstraints(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$")]
    route: Literal["chat-default"]
    max_output_tokens: int = Field(ge=1, le=1024)
    prompt_digest: Sha256
    retrieval_profile: Literal["java-rrf60-v1"]


class Opened(WireModel):
    """真实当前绑定有效才返回完整定义；开图前再比对中央输入。"""

    run_id: CanonicalId
    task_id: CanonicalId
    dataset_id: CanonicalId
    dataset_revision: int = Field(ge=1)
    definition_digest: Sha256
    top_k: Literal[5]
    profile_digest: Sha256
    definition: Definition
    bindings: list[DocumentBinding] = Field(max_length=MAX_BINDINGS)

    @model_validator(mode="after")
    def coverage(self) -> Self:
        """范围与绑定必须完整对应，防止无答案题遗漏语料版本。"""
        questions = self.definition.questions
        expected = {document for question in questions for document in question.document_ids}
        if (
            len({q.question_id for q in questions}) != len(questions)
            or {b.document_id for b in self.bindings} != expected
            or len(self.bindings) != len(expected)
        ):
            raise ValueError("评测资料绑定不完整")
        return self


class Retrieved(WireModel):
    """报告只保存召回身份、摘要和来源，不再复制一份原文。"""

    document_id: CanonicalId
    version: int = Field(ge=1)
    chunk_id: Sha256
    text_hash: Sha256
    score: float = Field(ge=0, allow_inf_nan=False)
    source: CitationSource


class Metrics(WireModel):
    """失败/无答案题整体为 null；应答题空返回为三个零。"""

    recall: float = Field(ge=0, le=1, allow_inf_nan=False)
    mrr: float = Field(ge=0, le=1, allow_inf_nan=False)
    ndcg: float = Field(ge=0, le=1, allow_inf_nan=False)


class Timing(WireModel):
    """未执行步骤为 null，阶段计时使用单调时钟。"""

    retrieval_ms: int | None = Field(default=None, ge=0, le=600_000)
    generation_ms: int | None = Field(default=None, ge=0, le=600_000)
    verification_ms: int | None = Field(default=None, ge=0, le=600_000)
    total_ms: int = Field(ge=0, le=600_000)


class Usage(WireModel):
    """提供方 usage 缺失不能推测为零。"""

    input_tokens: int = Field(ge=0)
    output_tokens: int = Field(ge=0)


class Cost(WireModel):
    """保守预留和账单分开；S8C 沿用文本流端口，未知值明确保留。"""

    chat_attempts: int | None = Field(default=None, ge=0, le=100)
    provider_usage: Usage | None = None
    provider_amount_cny: Money | None = None
    chat_reserved_cny: Money | None = None
    embedding_reserved_cny: Money | None = None


class QuestionReport(WireModel):
    """每题结果已与当前 QA 对齐；人工正确性评分由 S8D 独立判定。"""

    question_id: CanonicalId
    outcome: Literal["SUCCEEDED", "FAILED"]
    result: QaResult | None
    retrieved: list[Retrieved] = Field(max_length=TOP_K)
    metrics: Metrics | None
    timing: Timing
    cost: Cost
    error_code: TaskFailureCode | None

    @model_validator(mode="after")
    def outcome_bound(self) -> Self:
        """外部执行错误不能作为一条质量成功数据。"""
        if self.outcome == "SUCCEEDED":
            if self.result is None or self.error_code is not None:
                raise ValueError("评测成功结果缺失")
        elif self.result is not None or self.metrics is not None or self.error_code is None:
            raise ValueError("评测失败报告无效")
        return self


class CandidateReceipt(WireModel):
    """检查点只存候选寻址，不存整份逐题答案。"""

    question_id: CanonicalId
    epoch: int = Field(ge=1)
    digest: Sha256


class ManifestReceipt(WireModel):
    """清单尚未提交中央，不是正式成功结果。"""

    epoch: int = Field(ge=1)
    manifest_digest: Sha256


class Completion(WireModel):
    """中央只保存小型凭据，报告权威仍在 Knowledge。"""

    schema_version: Literal["evaluation-completion-v1"] = "evaluation-completion-v1"
    run_id: CanonicalId
    epoch: int = Field(ge=1)
    manifest_digest: Sha256
