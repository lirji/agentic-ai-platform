"""Knowledge 服务专用身份验证；不接受网关凭证或模型生成的身份。"""

import re
import time
from dataclasses import dataclass
from pathlib import Path

import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPublicKey

from agentic_ai.processing.errors import ProcessingCode, ProcessingError
from agentic_ai.processing.models import OwnedRequest

INTERNAL_HEADER = "X-Internal-Token"
SERVICE_ISSUER = "urn:agentic-ai:knowledge"
SERVICE_AUDIENCE = "agentic-ai-runtime"
SERVICE_PARTY = "knowledge-service"
PREPARE_SCOPE = "knowledge:prepare"
QUERY_SCOPE = "knowledge:query"
MAX_TOKEN_LENGTH = 8192
MAX_KEY_BYTES = 16384
MAX_TTL_SECONDS = 60
ID_PATTERN = re.compile(r"[A-Za-z0-9._:-]{1,128}\Z")


@dataclass(frozen=True, slots=True)
class ServiceIdentity:
    """只保留验签后身份，令牌和私钥不进入业务状态。"""

    tenant_id: str
    owner_id: str

    def require_owner(self, request: OwnedRequest) -> None:
        """正文身份只能复述授权主体，不能扩大处理数据范围。"""
        if request.tenant_id != self.tenant_id or request.owner_id != self.owner_id:
            raise ProcessingError(ProcessingCode.AUTH_FORBIDDEN)


class ServiceVerifier:
    """验签算法、issuer、audience 和调用服务均由服务端固定。"""

    def __init__(self, public_key: RSAPublicKey) -> None:
        if public_key.key_size < 2048:
            raise ProcessingError(ProcessingCode.CONFIGURATION_INVALID)
        self._key = public_key

    @classmethod
    def from_file(cls, path: Path) -> "ServiceVerifier":
        """只读取有界公钥文件，配置错误不把文件路径/内容带到异常链。"""
        loaded: object = None
        try:
            with path.open("rb") as source:
                raw = source.read(MAX_KEY_BYTES + 1)
            if len(raw) <= MAX_KEY_BYTES:
                loaded = serialization.load_pem_public_key(raw)
        except (OSError, ValueError, TypeError):
            loaded = None
        if not isinstance(loaded, RSAPublicKey):
            raise ProcessingError(ProcessingCode.CONFIGURATION_INVALID)
        return cls(loaded)

    def verify(self, token: str | None, scope: str) -> ServiceIdentity:
        """验证时效、签发主体和委托范围；任何解析错误统一为未认证。"""
        claims = None
        if token and len(token) <= MAX_TOKEN_LENGTH:
            try:
                claims = jwt.decode(
                    token,
                    self._key,
                    algorithms=["RS256"],
                    issuer=SERVICE_ISSUER,
                    audience=SERVICE_AUDIENCE,
                    options={
                        "require": ["iat", "nbf", "exp", "sub", "tenant_id", "scope", "azp"],
                        "strict_aud": True,
                    },
                )
            except (jwt.PyJWTError, ValueError, TypeError):
                claims = None
        if not claims:
            raise ProcessingError(ProcessingCode.AUTH_REQUIRED)
        tenant, owner, scopes = claims.get("tenant_id"), claims.get("sub"), claims.get("scope")
        times = [claims.get(key) for key in ("iat", "nbf", "exp")]
        if (
            claims.get("azp") != SERVICE_PARTY
            or not isinstance(tenant, str)
            or ID_PATTERN.fullmatch(tenant) is None
            or not isinstance(owner, str)
            or ID_PATTERN.fullmatch(owner) is None
            or not isinstance(scopes, str)
            or len(scopes) > 1024
            or any(type(value) is not int for value in times)
            or not 0 < claims["exp"] - claims["iat"] <= MAX_TTL_SECONDS
            or claims["nbf"] < claims["iat"]
            or claims["iat"] > time.time()
        ):
            raise ProcessingError(ProcessingCode.AUTH_REQUIRED)
        if scope not in scopes.split():
            raise ProcessingError(ProcessingCode.AUTH_FORBIDDEN)
        return ServiceIdentity(tenant, owner)
