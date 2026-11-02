"""Knowledge短时证明只用于可信归因；Tasks仍独立复验每次准入。"""

import re
import time
from uuid import UUID

import jwt
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPublicKey

from agentic_ai.processing.errors import ProcessingCode, ProcessingError
from agentic_ai.usage.context import UsageContext
from agentic_ai.usage.models import ContextType, Kind, Purpose, UsageError

CONTEXT_ISSUER = "urn:agentic-ai:knowledge-usage"
CONTEXT_AUDIENCE = "agentic-ai-usage-context"
CONTEXT_SECONDS = 30
MAX_CONTEXT_BYTES = 8192


def verify_context(
    token: str | None,
    key: RSAPublicKey,
    *,
    tenant_id: str,
    owner_id: str,
    purpose: Purpose,
    context_id: str | None,
) -> UsageContext:
    """绑定已认证服务的原用户及本次业务；不从未验签payload提取身份。"""
    try:
        if not token or len(token) > MAX_CONTEXT_BYTES:
            raise ValueError
        claims = jwt.decode(
            token,
            key,
            algorithms=["RS256"],
            issuer=CONTEXT_ISSUER,
            audience=CONTEXT_AUDIENCE,
            options={
                "require": ["iat", "nbf", "exp", "jti", "sub", "tenant_id"],
                "strict_aud": True,
            },
        )
        if (
            claims.get("sub") != owner_id
            or claims.get("tenant_id") != tenant_id
            or claims.get("azp") != "knowledge-service"
            or claims.get("delegate_azp") != "ai-runtime-worker"
            or claims.get("scope") != "usage:context"
            or claims.get("purpose") != purpose.value
            or (context_id is not None and claims.get("context_id") != context_id)
            or any(type(claims.get(name)) is not int for name in ("iat", "nbf", "exp"))
            or not 0 < claims["exp"] - claims["iat"] <= CONTEXT_SECONDS
            or claims["nbf"] < claims["iat"]
            or claims["iat"] > time.time()
            or str(UUID(claims["jti"])) != claims["jti"]
        ):
            raise ValueError
        for name in ("kinds", "profiles"):
            values = claims.get(name)
            if (
                not isinstance(values, list)
                or not 1 <= len(values) <= 8
                or any(
                    not isinstance(value, str)
                    or re.fullmatch(r"[A-Za-z0-9._:-]{1,64}", value) is None
                    for value in values
                )
                or len(set(values)) != len(values)
            ):
                raise ValueError
        for value in claims["kinds"]:
            Kind(value)
        return UsageContext(
            tenant_id,
            ContextType.KNOWLEDGE,
            claims["context_id"],
            None,
            purpose,
            claims["step_prefix"],
            token,
        )
    except (jwt.PyJWTError, ValueError, TypeError, KeyError, AttributeError, UsageError):
        raise ProcessingError(ProcessingCode.AUTH_FORBIDDEN) from None
