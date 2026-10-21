"""R02/A01 输入和报告；证据身份由服务端派生，模型仅返回内容。"""

import re
from enum import StrEnum
from typing import Literal, Self

from pydantic import Field, field_validator, model_validator

from agentic_ai.qa.models import Answerability, CitationSource, valid_text
from agentic_ai.tasks.models import CanonicalId, Sha256, WireModel

WORK_GRAPH = "knowledge-work-v1"
MAX_EVIDENCE = 10
MAX_EVIDENCE_CHARS = 24000
MAX_TOOL_CALLS = 5
MAX_SEARCH_CALLS = 2
MAX_MODEL_CALLS = 3
WORK_EXECUTION_SECONDS = 120
ALLOWED_TOOLS = frozenset({"search_knowledge", "read_evidence"})
NO_EVIDENCE = "未检索到可引用的已发布资料。请调整问题或知识范围后再试。"
NO_EVIDENCE_TITLE = "资料不足"
NO_EVIDENCE_LIMIT = "当前知识范围没有可用证据，尚未生成分析或草稿。"


class WorkKind(StrEnum):
    """和中央协议保持稳定 code，不承载身份或可写操作。"""

    RESEARCH = "RESEARCH"
    READONLY_ASSISTANT = "READONLY_ASSISTANT"


class WorkMode(StrEnum):
    """报告与三种只读输出模式。"""

    REPORT = "REPORT"
    EXPLAIN = "EXPLAIN"
    CHECKLIST = "CHECKLIST"
    DRAFT = "DRAFT"


class WorkInput(WireModel):
    """恢复必须使用中央冻结的请求，不从模型参数读取范围。"""

    schema_version: Literal["knowledge-work-input-v1"]
    kind: WorkKind
    question: str = Field(min_length=1, max_length=2000)
    document_ids: list[CanonicalId] = Field(max_length=20)
    mode: WorkMode

    @field_validator("question")
    @classmethod
    def text(cls, value: str) -> str:
        """与 Java 输入边界一致，拒绝无效文本。"""
        return valid_text(value)

    @model_validator(mode="after")
    def consistent(self) -> Self:
        """类型与模式必须匹配，范围已经在受理时排序去重。"""
        if (self.kind == WorkKind.RESEARCH) != (self.mode == WorkMode.REPORT):
            raise ValueError("任务模式不匹配")
        if self.document_ids != sorted(set(self.document_ids)):
            raise ValueError("知识范围不规范")
        return self


class WorkCitation(WireModel):
    """引用最多十条；只有 Knowledge 原始响应能决定其标识。"""

    citation_id: str = Field(pattern=r"^(?:[1-9]|10)$")
    document_id: CanonicalId
    version: int = Field(ge=1)
    chunk_id: Sha256
    source: CitationSource


class ToolRecord(WireModel):
    """只记录实际工具名称与结果数量，不保存凭据或未执行的计划。"""

    name: Literal["search_knowledge", "read_evidence"]
    hit_count: int = Field(ge=0, le=MAX_EVIDENCE)


class WorkDraft(WireModel):
    """模型仅能填写这些字段；不得接受它自报的成功状态或来源 ID。"""

    title: str = Field(min_length=1, max_length=120)
    facts: str = Field(min_length=1, max_length=6000)
    analysis: str = Field(min_length=1, max_length=6000)
    output: str = Field(min_length=1, max_length=6000)
    limitations: str = Field(min_length=1, max_length=6000)

    @field_validator("title", "facts", "analysis", "output", "limitations")
    @classmethod
    def text(cls, value: str) -> str:
        """所有持久文本都要能被原样、安全地重新读取。"""
        return valid_text(value)

    def used_citations(self) -> set[str]:
        """分析/草稿中的引用也必须属于本次受控证据。"""
        return set(
            re.findall(
                r"\[([0-9]+)\]",
                "\n".join((self.title, self.facts, self.analysis, self.output, self.limitations)),
            )
        )


class WorkResult(WorkDraft):
    """最终报告把事实、生成推断和可用输出分开，同时严格校验引用集合。"""

    schema_version: Literal["knowledge-work-result-v1"]
    kind: WorkKind
    mode: WorkMode
    answerability: Answerability
    citations: list[WorkCitation] = Field(max_length=MAX_EVIDENCE)
    tool_calls: list[ToolRecord] = Field(max_length=MAX_TOOL_CALLS)

    @model_validator(mode="after")
    def cited(self) -> Self:
        """空资料不能伪造报告；未知编号或重复来源一律拒绝。"""
        ids = {item.citation_id for item in self.citations}
        if len(ids) != len(self.citations) or len(
            {item.chunk_id for item in self.citations}
        ) != len(ids):
            raise ValueError("引用重复")
        if (self.kind == WorkKind.RESEARCH) != (self.mode == WorkMode.REPORT):
            raise ValueError("任务模式不匹配")
        if self.answerability == Answerability.NO_EVIDENCE:
            if (
                self.citations
                or self.tool_calls
                or self.used_citations()
                or any(value != NO_EVIDENCE for value in (self.facts, self.analysis, self.output))
                or self.title != NO_EVIDENCE_TITLE
                or self.limitations != NO_EVIDENCE_LIMIT
            ):
                raise ValueError("无证据结果无效")
        elif not re.search(r"\[([0-9]+)\]", self.facts) or self.used_citations() != ids:
            raise ValueError("事实与引用不一致")
        return self
