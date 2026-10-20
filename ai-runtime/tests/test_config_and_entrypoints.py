"""验证配置拒绝、脱敏和运行入口；这些检查不证明真实模型账号可用。"""

import asyncio
import json

import httpx
import pytest

from agentic_ai.api.app import create_app
from agentic_ai.cli import main
from agentic_ai.rag.errors import EmbeddingError, EmbeddingErrorCode
from agentic_ai.runtime.embedding_config import ENV_PREFIX, EmbeddingConfig

VALID_ENV = {
    "RAG_EMBEDDING_BASE_URL": "https://models.example.invalid/compatible-mode/v1/",
    "RAG_EMBEDDING_API_KEY": "test-only-token",
}


def test_real_defaults_and_safe_summary():
    config = EmbeddingConfig.from_environment(VALID_ENV)
    assert config.safe_summary() == {
        "provider": "openai",
        "model": "text-embedding-v4",
        "dimensions": 1024,
    }
    assert config.base_url.endswith("/v1")
    assert VALID_ENV["RAG_EMBEDDING_API_KEY"] not in repr(config)
    assert "api_key" not in config.model_dump()


@pytest.mark.parametrize(
    ("name", "value"),
    [
        ("PROVIDER", "hash"),
        ("PROVIDER", "ollama"),
        ("MODEL", ""),
        ("DIMENSIONS", "0"),
        ("DIMENSIONS", "1.5"),
        ("BASE_URL", "http://models.example.invalid/v1"),
        ("BASE_URL", "https://user:password@models.example.invalid/v1"),
        ("BASE_URL", "https://models.example.invalid/v1?key=secret"),
        ("BASE_URL", "https://models.example.invalid/v1?"),
        ("BASE_URL", "https://models.example.invalid/v1#secret"),
        ("BASE_URL", "https://models.example.invalid/v1#"),
        ("BASE_URL", "https://models.example.invalid:99999/v1"),
        ("BASE_URL", "https://models.example.invalid/\nsecret"),
        ("BASE_URL", "https://models.example.invalid/\x00secret"),
        ("BASE_URL", "https://models.example.invalid\\@elsewhere.invalid"),
        ("API_KEY", ""),
        ("API_KEY", "test-only-token\r\ninjected-header"),
        ("BATCH_SIZE", "11"),
        ("MAX_TEXTS", "1001"),
        ("MAX_INPUT_BYTES", "16777217"),
        ("MAX_RESPONSE_BYTES", "0"),
        ("MAX_CONNECTIONS", "101"),
        ("DEADLINE_SECONDS", "0"),
        ("DEADLINE_SECONDS", "NaN"),
        ("DEADLINE_SECONDS", "Infinity"),
    ],
)
def test_invalid_config_has_only_safe_error(name, value):
    with pytest.raises(EmbeddingError) as caught:
        EmbeddingConfig.from_environment({**VALID_ENV, f"{ENV_PREFIX}{name}": value})
    assert caught.value.code == EmbeddingErrorCode.CONFIGURATION_INVALID
    assert not caught.value.retryable
    assert VALID_ENV["RAG_EMBEDDING_API_KEY"] not in str(caught.value)
    assert "models.example.invalid" not in str(caught.value)
    assert caught.value.__context__ is None
    assert caught.value.__cause__ is None


@pytest.mark.parametrize("missing", list(VALID_ENV))
def test_credentials_and_endpoint_are_required(missing):
    with pytest.raises(EmbeddingError) as caught:
        EmbeddingConfig.from_environment(
            {key: val for key, val in VALID_ENV.items() if key != missing}
        )
    assert caught.value.code == EmbeddingErrorCode.CONFIGURATION_INVALID


def test_check_config_cli(monkeypatch, capsys):
    for field in EmbeddingConfig.model_fields:
        monkeypatch.delenv(f"{ENV_PREFIX}{field.upper()}", raising=False)
    for key, value in VALID_ENV.items():
        monkeypatch.setenv(key, value)
    assert main(["check-config"]) == 0
    captured = capsys.readouterr()
    assert json.loads(captured.out) == {
        "provider": "openai",
        "model": "text-embedding-v4",
        "dimensions": 1024,
    }
    assert captured.err == ""
    monkeypatch.setenv("RAG_EMBEDDING_PROVIDER", "hash")
    assert main(["check-config"]) == 2
    captured = capsys.readouterr()
    assert captured.out == ""
    assert json.loads(captured.err)["code"] == "EMBEDDING_CONFIGURATION_INVALID"
    assert "test-only-token" not in captured.err
    assert "models.example.invalid" not in captured.err


def test_liveness_is_not_readiness_or_public_embedding_api():
    async def scenario():
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=create_app()), base_url="http://test"
        ) as client:
            response = await client.get("/health/live")
            assert response.status_code == 200
            assert response.json() == {"status": "alive", "service": "agentic-ai-runtime"}
            assert (await client.get("/health/ready")).status_code == 404
            assert (await client.post("/embeddings", json={"input": ["test"]})).status_code == 404

    asyncio.run(scenario())
