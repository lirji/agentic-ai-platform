#!/usr/bin/env python3
"""E02 本机独立服务角色初始化；不重置聊天/Embedding 账本，不调用模型。"""

from __future__ import annotations

import json
from pathlib import Path

import local

TASK_PRIVATE = local.STATE / "knowledge-evaluation-private.pem"
TASK_PUBLIC = local.STATE / "knowledge-evaluation-public.pem"
WORKER_PRIVATE = local.STATE / "runtime-evaluation-private.pem"
WORKER_PUBLIC = local.STATE / "runtime-evaluation-public.pem"


def _keypair(private: Path, public: Path) -> None:
    """同名公钥必须属于原私钥；未知材料不强行轮换。"""
    if public.exists() and not private.exists():
        raise RuntimeError("评测公钥已存在但私钥缺失，不能接管原角色")
    for path in (private, public):
        if path.exists() and (path.is_symlink() or path.stat().st_mode & 0o077):
            raise RuntimeError("评测密钥权限不符合要求")
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
    derived = local.run(["openssl", "pkey", "-in", str(private), "-pubout"]).decode()
    if public.exists():
        if public.read_text() != derived:
            raise RuntimeError("评测密钥不配对，未覆盖原文件")
    else:
        local.private(public, derived)


def init() -> None:
    """增加已批准的独立签名身份，保留已有 worker 输入、PG 与两个累计额度。"""
    _keypair(TASK_PRIVATE, TASK_PUBLIC)
    _keypair(WORKER_PRIVATE, WORKER_PUBLIC)
    worker = local.STATE / "qa-worker.json"
    if worker.exists():
        if worker.is_symlink() or worker.stat().st_mode & 0o077:
            raise RuntimeError("现有 worker 私密配置权限错误")
        value = json.loads(worker.read_text())
        expected = str(WORKER_PRIVATE)
        if value.get("evaluation_private_key") not in (None, expected):
            raise RuntimeError("现有评测身份路径不同，未覆盖配置")
        if value.get("tenant_id") != "local-dev":
            raise RuntimeError("当前 worker 不属于本机开发租户，未扩大范围")
        if "evaluation_private_key" not in value:
            value["evaluation_private_key"] = expected
            local.private(worker, json.dumps(value, ensure_ascii=False, indent=2) + "\n")
    print("评测专用身份已就绪；现有模型配置与累计预算保留，未发起模型调用。")


def worker_config() -> dict[str, str]:
    """后续首次创建 worker 配置也沿用已初始化角色，未初始化不隐式启用。"""
    return {"evaluation_private_key": str(WORKER_PRIVATE)} if WORKER_PRIVATE.exists() else {}


def environment(mode: str) -> dict[str, str]:
    """各服务只读取各自需要的角色；测试环境不自动启动本地交接循环。"""
    paths = (TASK_PRIVATE, TASK_PUBLIC, WORKER_PUBLIC)
    if not any(path.exists() for path in paths):
        return {}
    if any(not path.exists() or path.is_symlink() or path.stat().st_mode & 0o077 for path in paths):
        raise RuntimeError("评测身份不完整，请恢复原材料，不能自动回退")
    return {
        "KNOWLEDGE_EVALUATION_ENABLED": "true",
        "KNOWLEDGE_EVALUATION_TASK_ENDPOINT": "http://127.0.0.1:28087",
        "KNOWLEDGE_EVALUATION_TASK_PUBLIC_KEY": TASK_PUBLIC.as_uri(),
        "KNOWLEDGE_EVALUATION_TASK_PRIVATE_KEY": TASK_PRIVATE.as_uri(),
        "KNOWLEDGE_EVALUATION_WORKER_PUBLIC_KEY": WORKER_PUBLIC.as_uri(),
        "KNOWLEDGE_EVALUATION_WORKER_ENABLED": "true" if mode == "dev" else "false",
        "KNOWLEDGE_EVALUATION_WORKER_TENANT": "local-dev",
        "TASK_EVALUATION_ENABLED": "true",
        "TASK_EVALUATION_PUBLIC_KEY": TASK_PUBLIC.as_uri(),
    }
