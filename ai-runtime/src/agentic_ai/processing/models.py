"""P01/P02 的语言中立模型与摘要规则；框架内部对象不进入接口。"""

import hashlib
import struct
from typing import Annotated, Final, Literal, Self
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from pydantic.alias_generators import to_camel

SCHEMA_VERSION = "knowledge-preparation-v1"
PROCESSOR_VERSION = "text-v1-char512-overlap64-embedding-v4-1024"
EMBEDDING_MODEL: Final = "text-embedding-v4"
EMBEDDING_DIMENSIONS: Final = 1024
MAX_SOURCE_BYTES = 1024 * 1024
MAX_BODY_BYTES = 6 * MAX_SOURCE_BYTES
MAX_CHUNKS = 100
CHUNK_SIZE = 512
CHUNK_OVERLAP = 64
Identity = Annotated[str, Field(pattern=r"^[A-Za-z0-9._:-]{1,128}$")]
Digest = Annotated[str, Field(pattern=r"^[0-9a-f]{64}$")]


def digest(value: bytes) -> str:
    """统一字节摘要，跨语言实现不得依赖平台默认编码。"""
    return hashlib.sha256(value).hexdigest()


class WireModel(BaseModel):
    """严格 DTO 禁止宽松转换和未知字段，避免跨语言解释不一致。"""

    model_config = ConfigDict(
        frozen=True,
        extra="forbid",
        strict=True,
        hide_input_in_errors=True,
        alias_generator=to_camel,
        populate_by_name=True,
    )


class OwnedRequest(WireModel):
    """正文中的身份必须与验签后的身份逐项一致。"""

    tenant_id: Identity
    owner_id: Identity


class PrepareRequest(OwnedRequest):
    """Java 提供已授权原文及版本，Python 不接受任意远程下载地址。"""

    document_id: str
    job_id: str
    document_version: int = Field(ge=1, le=2147483647)
    source_hash: Digest
    content_type: Literal["text/plain", "text/markdown"]
    source_text: str = Field(min_length=1, max_length=MAX_SOURCE_BYTES, repr=False)

    @field_validator("document_id", "job_id")
    @classmethod
    def canonical_uuid(cls, value: str) -> str:
        """不接受缩写、大小写变体或会改变稳定摘要的 UUID 表示。"""
        if str(UUID(value)) != value:
            raise ValueError("需要规范 UUID")
        return value

    @model_validator(mode="after")
    def source_matches(self) -> Self:
        """在任何模型调用前核对实际字节，避免为损坏输入付费。"""
        raw = self.source_text.encode("utf-8")
        if (
            not self.source_text.strip()
            or "\x00" in self.source_text
            or len(raw) > MAX_SOURCE_BYTES
            or digest(raw) != self.source_hash
        ):
            raise ValueError("原文无效或哈希不符")
        return self


class QueryRequest(OwnedRequest):
    """查询不得在适配器内静默改写，确保入库/查询处理版本一致。"""

    query: str = Field(min_length=1, max_length=2000, repr=False)

    @field_validator("query")
    @classmethod
    def meaningful_query(cls, value: str) -> str:
        """拒绝空白、NUL 与不可编码 Unicode。"""
        value.encode("utf-8")
        if not value.strip() or "\x00" in value:
            raise ValueError("查询无效")
        return value


class PreparedChunk(WireModel):
    """UTF-8 字节区间直接定位原文，不使用生成内容伪造引用。"""

    ordinal: int
    chunk_id: str
    text: str = Field(repr=False)
    text_hash: str
    start_byte: int
    end_byte: int
    vector: tuple[float, ...] = Field(repr=False)
    vector_hash: str


class PreparedDocument(OwnedRequest):
    """只有所有分块和向量均校验成功才返回整个包。"""

    schema_version: str = SCHEMA_VERSION
    processor_version: str = PROCESSOR_VERSION
    document_id: str
    job_id: str
    document_version: int
    source_hash: str
    embedding_model: str = EMBEDDING_MODEL
    dimensions: int = EMBEDDING_DIMENSIONS
    chunks: tuple[PreparedChunk, ...]
    batch_digest: str


class QueryVector(WireModel):
    """向量与索引配置绑定，Java 必须核对处理版本后检索。"""

    processor_version: str = PROCESSOR_VERSION
    embedding_model: str = EMBEDDING_MODEL
    dimensions: int = EMBEDDING_DIMENSIONS
    vector: tuple[float, ...] = Field(repr=False)


def chunk_identity(request: PrepareRequest, ordinal: int, text_hash: str) -> str:
    """身份/原文/算法/顺序共同决定 ID，避免跨租户或版本覆盖。"""
    fields = (
        request.tenant_id,
        request.owner_id,
        request.document_id,
        str(request.document_version),
        request.source_hash,
        PROCESSOR_VERSION,
        str(ordinal),
        text_hash,
    )
    return digest("\n".join(fields).encode("utf-8"))


def vector_digest(vector: tuple[float, ...]) -> str:
    """使用 binary64 大端格式，避开 Java/Python 浮点 JSON 文本格式差异。"""
    return digest(struct.pack(f"!{len(vector)}d", *vector))


def batch_digest(chunks: tuple[PreparedChunk, ...]) -> str:
    """顺序与原文区间进入清单摘要，拒绝遗漏、重排或替换分块。"""
    return digest(
        "\n".join(
            f"{item.chunk_id}:{item.start_byte}:{item.end_byte}:{item.vector_hash}"
            for item in chunks
        ).encode("utf-8")
    )
