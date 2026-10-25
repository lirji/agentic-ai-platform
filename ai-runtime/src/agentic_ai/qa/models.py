"""Q 输入、K 证据和最终回答严格消费已冻结契约。"""

import re
from enum import StrEnum
from typing import Literal, Self

from pydantic import Field, field_validator, model_validator

from agentic_ai.tasks.models import CanonicalId, Sha256, WireModel

MAX_QUESTION = 2000
MAX_ANSWER = 12000
MAX_DOCUMENTS = 20
MAX_HITS = 5
MAX_RESPONSE = 256 * 1024
NO_EVIDENCE_ANSWER = "未检索到可引用的已发布资料。请调整问题或知识范围后再试。"


def valid_text(value: str) -> str:
    """不悄悄改写问题或原文；无效 Unicode 和空白文本不能进入图状态。"""
    if not value.strip() or "\0" in value:
        raise ValueError("文本内容无效")
    value.encode("utf-8")
    return value


class QaInput(WireModel):
    """中央持久输入与会话原问题保持一致，身份另从 Lease 绑定。"""

    schema_version: Literal["knowledge-qa-input-v1"]
    conversation_id: CanonicalId
    turn_id: CanonicalId
    question: str = Field(min_length=1, max_length=MAX_QUESTION)
    document_ids: list[CanonicalId] = Field(max_length=MAX_DOCUMENTS)
    context_turn_id: CanonicalId | None
    context_task_id: CanonicalId | None
    context_question: str | None = Field(min_length=1, max_length=MAX_QUESTION)

    @field_validator("question", "context_question")
    @classmethod
    def text(cls, value: str | None) -> str | None:
        """上下文问题使用与当前问题相同的文本边界。"""
        return None if value is None else valid_text(value)

    @model_validator(mode="after")
    def consistent(self) -> Self:
        """拒绝局部关联或不规范范围，不能替中央任务改写已冻结命令。"""
        values = (self.context_turn_id, self.context_task_id, self.context_question)
        if any(value is None for value in values) and not all(value is None for value in values):
            raise ValueError("上下文关联不完整")
        if len(set(self.document_ids)) != len(self.document_ids) or self.document_ids != sorted(
            self.document_ids
        ):
            raise ValueError("知识范围不规范")
        if self.context_turn_id == self.turn_id:
            raise ValueError("不能关联当前问题")
        return self


class CitationSource(WireModel):
    """展示名与定位必须来自已授权 Knowledge 响应。"""

    display_name: str = Field(min_length=1, max_length=512)
    locator: str = Field(min_length=1, max_length=512)

    @field_validator("locator")
    @classmethod
    def valid_locator(cls, value: str) -> str:
        """QA、评测和研究共用闭合来源语法；不能把任意模型字符串当作真实页号。"""
        old = re.fullmatch(r"utf8-bytes:(0|[1-9][0-9]*)-([1-9][0-9]*)", value)
        if old and 0 <= int(old[1]) < int(old[2]) <= 1024 * 1024:
            return value
        if value.startswith("pdf-pages:"):
            pages = value.removeprefix("pdf-pages:").split(",")
            if (
                1 <= len(pages) <= 16
                and all(re.fullmatch(r"[1-9][0-9]{0,2}", page) for page in pages)
                and all(int(page) <= 200 for page in pages)
                and list(map(int, pages)) == sorted(set(map(int, pages)))
            ):
                return value
        if value.startswith("docx-blocks:"):
            positions = value.removeprefix("docx-blocks:").split(",")
            if 1 <= len(positions) <= 16 and len(set(positions)) == len(positions):
                valid = True
                previous_paragraph = previous_table = previous_row = previous_column = 0
                table_closed = False
                for position in positions:
                    paragraph = re.fullmatch(r"p([1-9][0-9]{0,3})", position)
                    cell = re.fullmatch(
                        r"t([1-9][0-9]{0,3})r([1-9][0-9]{0,3})c([1-9][0-9]{0,2})", position
                    )
                    if paragraph:
                        next_paragraph = int(paragraph[1])
                        valid &= previous_paragraph < next_paragraph <= 1000
                        previous_paragraph, table_closed = next_paragraph, True
                    elif cell:
                        next_table, next_row, next_column = map(int, cell.groups())
                        valid &= next_table <= 1000 and next_row <= 1000 and next_column <= 100
                        valid &= next_table > previous_table or (
                            next_table == previous_table
                            and not table_closed
                            and (next_row, next_column) > (previous_row, previous_column)
                        )
                        previous_table, previous_row, previous_column = (
                            next_table,
                            next_row,
                            next_column,
                        )
                        table_closed = False
                    else:
                        valid = False
                if valid:
                    return value
        raise ValueError("资料来源定位无效")


class KnowledgeHit(WireModel):
    """K04/K05 原文片段；分数仅排序，不授予访问权。"""

    document_id: CanonicalId
    version: int = Field(ge=1)
    chunk_id: Sha256
    text: str = Field(min_length=1, max_length=MAX_ANSWER)
    score: float = Field(ge=0, allow_inf_nan=False)
    source: CitationSource

    @field_validator("text")
    @classmethod
    def content(cls, value: str) -> str:
        """证据不能包含会在 prompt/检查点中产生不同解释的无效字符。"""
        return valid_text(value)


class SearchReply(WireModel):
    """返回超过请求 topK 的正文视为协议漂移，不无界收集。"""

    hits: list[KnowledgeHit] = Field(max_length=MAX_HITS)


class Answerability(StrEnum):
    """回答依据情况独立于中央任务状态。"""

    ANSWERED = "ANSWERED"
    NO_EVIDENCE = "NO_EVIDENCE"


class Citation(WireModel):
    """最终结果只保存出处，不复制一份可绕过 Knowledge 的原文读取入口。"""

    citation_id: Literal["1", "2", "3", "4", "5"]
    document_id: CanonicalId
    version: int = Field(ge=1)
    chunk_id: Sha256
    source: CitationSource


class QaResult(WireModel):
    """最终结果引用集合必须与回答中实际使用的编号精确对应。"""

    schema_version: Literal["knowledge-qa-result-v1"]
    answerability: Answerability
    answer: str = Field(min_length=1, max_length=MAX_ANSWER)
    citations: list[Citation] = Field(max_length=MAX_HITS)

    @model_validator(mode="after")
    def cited(self) -> Self:
        """未知编号、空证据伪回答和重复出处均不能标记成功。"""
        valid_text(self.answer)
        used = set(re.findall(r"\[([0-9]+)\]", self.answer))
        ids = {item.citation_id for item in self.citations}
        chunks = {item.chunk_id for item in self.citations}
        if len(ids) != len(self.citations) or len(chunks) != len(self.citations):
            raise ValueError("引用重复")
        if self.answerability == Answerability.NO_EVIDENCE:
            if self.citations or used or self.answer != NO_EVIDENCE_ANSWER:
                raise ValueError("无证据结果无效")
        elif not used or used != ids:
            raise ValueError("回答与引用不一致")
        return self
