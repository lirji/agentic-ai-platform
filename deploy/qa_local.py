#!/usr/bin/env python3
"""问答执行的独立只读服务身份；此阶段不创建或调用未经确认的聊天配置。"""

from __future__ import annotations

import local

PRIVATE_KEY = local.STATE / "runtime-knowledge-private.pem"
PUBLIC_KEY = local.STATE / "runtime-knowledge-public.pem"


def init() -> None:
    """新建签名角色不复用网关或中央密钥；中途失败可以从已保存的私钥恢复。"""
    if PUBLIC_KEY.exists() and not PRIVATE_KEY.exists():
        raise RuntimeError("只读知识公钥已存在但私钥缺失，不能轮换或接管现有角色")
    for path in (PRIVATE_KEY, PUBLIC_KEY):
        if path.exists() and (path.is_symlink() or path.stat().st_mode & 0o077):
            raise RuntimeError("只读知识签名材料权限不符合要求")
    if not PRIVATE_KEY.exists():
        local.private(
            PRIVATE_KEY,
            local.run(
                ["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048"]
            ).decode(),
        )
    public = local.run(["openssl", "pkey", "-in", str(PRIVATE_KEY), "-pubout"]).decode()
    if PUBLIC_KEY.exists():
        if PUBLIC_KEY.read_text() != public:
            raise RuntimeError("只读知识签名材料不配对，未覆盖原文件")
    else:
        local.private(PUBLIC_KEY, public)
    print("只读知识服务身份已就绪；未创建聊天路由或发起付费调用。")


def environment() -> dict[str, str]:
    """Knowledge 仅接收验签公钥，AI 私钥由其独立 worker 配置读取。"""
    if not PUBLIC_KEY.exists():
        return {}
    if PUBLIC_KEY.is_symlink() or PUBLIC_KEY.stat().st_mode & 0o077:
        raise RuntimeError("只读知识公钥权限错误")
    return {
        "KNOWLEDGE_RUNTIME_ENABLED": "true",
        "KNOWLEDGE_RUNTIME_PUBLIC_KEY": PUBLIC_KEY.as_uri(),
    }
