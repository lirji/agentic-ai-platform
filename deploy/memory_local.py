"""受控记忆的独立执行身份；只有显式启用时准备材料，不接触模型账本。"""

from pathlib import Path

import local


def configuration(enabled: bool, governed: bool, state: Path) -> tuple[dict[str, str], dict]:
    """只返回当前开关的完整配置，关闭时不能继承宿主残留的签名与开关。"""
    if enabled and not governed:
        raise ValueError("--memory-ai需要同时启用--memory")
    env = {
        "CONVERSATION_MEMORY_AI_ENABLED": str(enabled).lower(),
        "TASK_MEMORY_ENABLED": str(enabled).lower(),
    }
    worker = {
        "memory_enabled": enabled,
        "memory_endpoint": None,
        "memory_private_key": None,
    }
    if not enabled:
        return env, worker
    private, public = (
        state / "runtime-memory-private.pem",
        state / "runtime-memory-public.pem",
    )
    if public.exists() and not private.exists():
        raise RuntimeError("记忆公钥已有归属但私钥缺失，拒绝轮换")
    for path in (private, public):
        if path.is_symlink() or (
            path.exists() and (not path.is_file() or path.stat().st_mode & 0o077)
        ):
            raise RuntimeError("记忆签名材料路径或权限不符合要求")
    if not private.exists():
        local.private(
            private,
            local.run(
                [
                    "openssl",
                    "genpkey",
                    "-algorithm",
                    "RSA",
                    "-pkeyopt",
                    "rsa_keygen_bits:2048",
                ]
            ).decode(),
        )
    expected = local.run(["openssl", "pkey", "-in", str(private), "-pubout"]).decode()
    if public.exists() and public.read_text() != expected:
        raise RuntimeError("记忆签名材料不配对，保留原文件")
    if not public.exists():
        local.private(public, expected)
    env.update(
        MEMORY_RUNTIME_PUBLIC_KEY=public.as_uri(),
        CONVERSATION_GENERATION_ENABLED="true",
        CONVERSATION_CONTEXTUAL_QUERY_ENABLED="true",
        TASK_GENERATION_ENABLED="true",
        TASK_CONTEXTUAL_QA_ENABLED="true",
        KNOWLEDGE_RETRIEVAL_ENABLED="true",
    )
    worker.update(
        memory_endpoint="http://127.0.0.1:28088",
        memory_private_key=str(private),
        retrieval_enabled=True,
        contextual_qa_enabled=True,
    )
    return env, worker


def worker_environment(environment: dict[str, str], enabled: bool) -> dict[str, str]:
    """检索worker只获取原embedding模型与预算配置，不继承AI服务签名等身份。"""
    return {
        key: value
        for key, value in environment.items()
        if enabled and key.startswith(("RAG_EMBEDDING_", "AI_EMBEDDING_"))
    }
