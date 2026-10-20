#!/usr/bin/env python3
"""会话服务专用 MySQL 资源，复用已建立的会话提交签名角色，不读取旧平台数据。"""

from __future__ import annotations

import json
import os
import secrets

import local

CONFIG = local.STATE / "conversations.json"
RESOURCES = {
    "dev": ("agentic_conversations", "agentic_conv_app", "agentic_conv_migrate"),
    "test": (
        "agentic_conversations_test",
        "agentic_conv_test_app",
        "agentic_conv_test_migrate",
    ),
}


def load() -> dict:
    """私有所有权记录是恢复依据，不接管没有记录的同名资源。"""
    if not CONFIG.exists() or CONFIG.stat().st_mode & 0o077:
        raise RuntimeError("会话资源记录缺失或权限错误，请先 init-conversations")
    config = json.loads(CONFIG.read_text())
    for mode, names in RESOURCES.items():
        if (
            tuple(config["resources"][mode][key] for key in ("database", "app", "migrator"))
            != names
        ):
            raise RuntimeError("会话资源名称不在本项目白名单内")
    return config


def init() -> None:
    """先记录归属和随机凭据，再幂等创建；不清理已受理问题或轮换既有密码。"""
    for name in ("task-submit-public.pem", "task-submit-private.pem"):
        key = local.STATE / name
        if not key.exists() or key.stat().st_mode & 0o077:
            raise RuntimeError("会话提交密钥未就绪，请先 init-tasks")
    if CONFIG.exists():
        config = load()
    else:
        for database, app, migrator in RESOURCES.values():
            if local.mysql(
                "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA "
                f"WHERE SCHEMA_NAME='{database}';"
                f"SELECT User FROM mysql.user WHERE User IN ('{app}','{migrator}');"
            ).strip():
                raise RuntimeError("会话同名资源已存在但无所有权记录，停止初始化")
        config = {"version": 1, "resources": {}}
        for mode, (database, app, migrator) in RESOURCES.items():
            config["resources"][mode] = {
                "database": database,
                "app": app,
                "migrator": migrator,
                "app_password": secrets.token_hex(24),
                "migration_password": secrets.token_hex(24),
            }
        local.private(CONFIG, json.dumps(config, indent=2))
    for entry in config["resources"].values():
        database, app, migrator = (entry[key] for key in ("database", "app", "migrator"))
        local.mysql(
            f"CREATE DATABASE IF NOT EXISTS `{database}` CHARACTER SET utf8mb4 "
            "COLLATE utf8mb4_0900_ai_ci;"
            f"CREATE USER IF NOT EXISTS '{app}'@'%' IDENTIFIED BY '{entry['app_password']}';"
            f"CREATE USER IF NOT EXISTS '{migrator}'@'%' "
            f"IDENTIFIED BY '{entry['migration_password']}';"
            f"GRANT SELECT,INSERT,UPDATE,DELETE ON `{database}`.* TO '{app}'@'%';"
            f"GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES "
            f"ON `{database}`.* TO '{migrator}'@'%';"
        )
    print("本项目 conversation dev/test 数据库与分权账号已就绪，原问题和签名材料保留。")


def environment(mode: str) -> dict[str, str]:
    """只返回会话专用变量，不把明文凭据写入参数或日志。"""
    entry = load()["resources"][mode]
    return {
        "CONVERSATION_DB_URL": f"jdbc:mysql://127.0.0.1:43306/{entry['database']}"
        "?sslMode=DISABLED&allowPublicKeyRetrieval=true",
        "CONVERSATION_DB_USER": entry["app"],
        "CONVERSATION_DB_PASSWORD": entry["app_password"],
        "CONVERSATION_MIGRATION_USER": entry["migrator"],
        "CONVERSATION_MIGRATION_PASSWORD": entry["migration_password"],
        "CONVERSATION_SUBMIT_PUBLIC_KEY": (local.STATE / "task-submit-public.pem").as_uri(),
        "CONVERSATION_SUBMIT_PRIVATE_KEY": (local.STATE / "task-submit-private.pem").as_uri(),
        "CONVERSATION_TASK_ENDPOINT": "http://127.0.0.1:28087",
        "CONVERSATION_SERVICE_URL": "http://127.0.0.1:28088",
        "CONVERSATION_WORKER_ENABLED": "true" if mode == "dev" else "false",
        "CONVERSATION_WORKER_TENANT": "local-dev",
    }


def service_environment() -> dict[str, str]:
    """新会话进程只接收自身资源与验签公钥，不注入 Knowledge/Tasks 数据库密码。"""
    return {
        **os.environ,
        **environment("dev"),
        "INTERNAL_PUBLIC_KEY": (local.STATE / "internal-public.pem").as_uri(),
        "OTEL_EXPORT_ENABLED": "false",
    }
