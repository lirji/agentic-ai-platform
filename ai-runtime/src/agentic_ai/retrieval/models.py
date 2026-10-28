"""消费knowledge-retrieval-v1完整身份；内部原始排名不会扩充旧公开响应。"""

import hashlib
import json
from typing import Literal, Self

from pydantic import Field, field_validator, model_validator

from agentic_ai.qa.models import CitationSource, valid_text
from agentic_ai.retrieval.errors import RetrievalCode
from agentic_ai.tasks.models import CanonicalId, Sha256, WireModel

SCHEMA = "knowledge-retrieval-v1"
STRATEGY = "LLAMA_RRF_V1"
MAX_REQUEST = 128 * 1024
MAX_RESPONSE = 1024 * 1024
MAX_CANDIDATES = 100
MAX_RESOLVE = 50
MAX_DOCUMENTS = 20
MAX_TOP_K = 20
RRF_OFFSET = 60
QUERY_SECONDS = 30
EMBEDDING_SECONDS = 10
CANDIDATE_SECONDS = 12
RESOLVE_SECONDS = 5
QUERY_CAPACITY = 2


class RetrievalWire(WireModel):
    """在JSON边界同时拒绝重复键与snake_case别名，避免跨语言取值分歧。"""

    @classmethod
    def from_wire(cls, body: bytes) -> Self:
        """调用者先执行有界读取；解析错误只由边界转换为安全失败码。"""

        def unique(pairs: list[tuple[str, object]]) -> dict[str, object]:
            values: dict[str, object] = {}
            for key, value in pairs:
                if key in values:
                    raise ValueError(RetrievalCode.EVIDENCE_REJECTED.value)
                values[key] = value
            return values

        json.loads(body, object_pairs_hook=unique)
        return cls.model_validate_json(body, by_name=False)


class RetrievalInput(WireModel):
    """可组合用例的输入不接收tenant、owner、URL或模型配置。"""

    query: str = Field(min_length=1, max_length=2000, repr=False)
    document_ids: tuple[CanonicalId, ...] | None = Field(min_length=1, max_length=MAX_DOCUMENTS)
    top_k: int = Field(ge=1, le=MAX_TOP_K)

    @field_validator("query")
    @classmethod
    def query_text(cls, value: str) -> str:
        """沿现行查询规则，拒绝非法文本而非截断或替换。"""
        return valid_text(value)

    @model_validator(mode="after")
    def unique_scope(self) -> Self:
        """显式空范围不得变为全部资料，重复范围不悄悄归一化。"""
        if self.document_ids is not None and len(set(self.document_ids)) != len(self.document_ids):
            raise ValueError(RetrievalCode.INPUT_INVALID.value)
        return self


class CandidateIdentity(RetrievalWire):
    """完整身份可直接作为不可变映射键，正文相同也不会合并资料或代次。"""

    document_id: CanonicalId
    version: int = Field(ge=1)
    index_generation: int = Field(ge=1)
    job_id: CanonicalId
    processor_version: str = Field(min_length=1, max_length=128)
    chunk_id: Sha256
    batch_digest: Sha256
    text_hash: Sha256

    def node_id(self) -> str:
        """节点身份绑定完整元组，不依赖TextNode.hash或可变的全局配置。"""
        body = json.dumps(self.model_dump(by_alias=True), sort_keys=True, separators=(",", ":"))
        return "retrieval:" + hashlib.sha256(body.encode()).hexdigest()


class RetrievalHit(CandidateIdentity):
    """正文与来源始终取自Knowledge当前发布权威。"""

    text: str = Field(min_length=1, max_length=512, repr=False)
    source: CitationSource

    @model_validator(mode="after")
    def verified_text(self) -> Self:
        """HTTP成功不代替正文完整性校验。"""
        valid_text(self.text)
        if hashlib.sha256(self.text.encode()).hexdigest() != self.text_hash:
            raise ValueError(RetrievalCode.EVIDENCE_REJECTED.value)
        return self

    def identity(self) -> CandidateIdentity:
        """解析请求仅发送身份和摘要，不能把调用者正文作为判权依据。"""
        return CandidateIdentity.model_validate(
            self.model_dump(include=set(CandidateIdentity.model_fields))
        )


class RankedCandidate(RetrievalHit):
    """原score只作证据保留，融合使用原始一基sourceRank。"""

    source_rank: int = Field(ge=1, le=MAX_CANDIDATES)
    source_score: float = Field(allow_inf_nan=False)

    def hit(self) -> RetrievalHit:
        """移除来源独有排名，核对跨源正文与完整身份一致。"""
        return RetrievalHit.model_validate(self.model_dump(include=set(RetrievalHit.model_fields)))


class CandidatesReply(RetrievalWire):
    """两路完整返回才可融合，不把任一路故障视为空召回。"""

    schema_version: Literal["knowledge-retrieval-v1"]
    candidate_limit: Literal[20, 60, 100]
    vector_candidates: tuple[RankedCandidate, ...] = Field(max_length=MAX_CANDIDATES)
    lexical_candidates: tuple[RankedCandidate, ...] = Field(max_length=MAX_CANDIDATES)

    @field_validator("candidate_limit", mode="before")
    @classmethod
    def integer_limit(cls, value: object) -> object:
        """Literal的数值相等不能放行20.0或布尔强制转换。"""
        if type(value) is not int:
            raise ValueError(RetrievalCode.EVIDENCE_REJECTED.value)
        return value

    @model_validator(mode="after")
    def consistent_routes(self) -> Self:
        """拒绝压缩后乱序、重复身份以及跨源授权正文不一致。"""
        shared: dict[tuple[str, str], RetrievalHit] = {}
        for route in (self.vector_candidates, self.lexical_candidates):
            seen: set[CandidateIdentity] = set()
            previous = 0
            for item in route:
                identity, hit = item.identity(), item.hit()
                if identity in seen or not previous < item.source_rank <= self.candidate_limit:
                    raise ValueError(RetrievalCode.EVIDENCE_REJECTED.value)
                seen.add(identity)
                previous = item.source_rank
                key = (item.chunk_id, item.batch_digest)
                if key in shared and shared[key] != hit:
                    raise ValueError(RetrievalCode.EVIDENCE_REJECTED.value)
                shared[key] = hit
        return self


class ResolveReply(RetrievalWire):
    """客户端再核对条数、输入顺序、正文与来源，不接受替换或半列表。"""

    schema_version: Literal["knowledge-retrieval-v1"]
    hits: tuple[RetrievalHit, ...] = Field(max_length=MAX_RESOLVE)


class Contribution(WireModel):
    """内部贡献证据；公开连续名次和脱敏映射留给S11E。"""

    route: Literal["VECTOR", "KEYWORD"]
    source_rank: int = Field(ge=1, le=MAX_CANDIDATES)
    source_score: float = Field(allow_inf_nan=False)


class FusedHit(RetrievalHit):
    """JSON可持久证据，RRF分数不覆盖任一路原score。"""

    rrf_score: float = Field(gt=0, allow_inf_nan=False)
    contributions: tuple[Contribution, ...] = Field(min_length=1, max_length=2)

    @model_validator(mode="after")
    def exact_contributions(self) -> Self:
        """框架节点返回JSON时复核贡献和分数，不能替换为无来源依据的分数。"""
        routes = tuple(value.route for value in self.contributions)
        if routes not in (
            ("VECTOR",),
            ("KEYWORD",),
            ("VECTOR", "KEYWORD"),
        ) or self.rrf_score != sum(
            1.0 / (RRF_OFFSET + value.source_rank) for value in self.contributions
        ):
            raise ValueError(RetrievalCode.EVIDENCE_REJECTED.value)
        return self
