#!/usr/bin/env python3
"""本项目持久任务资源；共享实例上只创建自有命名空间，不读取旧业务数据。"""

from __future__ import annotations

import json
import secrets

import local

CONFIG = local.STATE / "tasks.json"
RESOURCES = {
    "dev": ("agentic_tasks", "agentic_task_app", "agentic_task_migrate"),
    "test": ("agentic_tasks_test", "agentic_task_test_app", "agentic_task_test_migrate"),
}


def load() -> dict:
    """凭据状态也是资源所有权记录，没有记录不接管同名资源。"""
    if not CONFIG.exists() or CONFIG.stat().st_mode & 0o077:
        raise RuntimeError("任务运行记录缺失或权限不为 0600，请先 init-tasks")
    config = json.loads(CONFIG.read_text())
    for mode, names in RESOURCES.items():
        recorded = tuple(config["resources"][mode][key] for key in ("database", "app", "migrator"))
        if recorded != names:
            raise RuntimeError("任务资源名称不在白名单内")
    return config


def init() -> None:
    """先检查命名空间，再保存随机凭据；重试只恢复本项目已登记资源。"""
    if CONFIG.exists():
        config = load()
    else:
        for database, app, migrator in RESOURCES.values():
            occupied = local.mysql(
                f"SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA "
                f"WHERE SCHEMA_NAME='{database}';"
                f"SELECT User FROM mysql.user WHERE User IN ('{app}','{migrator}');"
            )
            if occupied.strip():
                raise RuntimeError("同名任务资源已存在但没有所有权记录，停止初始化")
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
    for name in ("task-submit", "task-worker"):
        key = local.STATE / f"{name}-private.pem"
        public = local.STATE / f"{name}-public.pem"
        if not key.exists():
            local.private(
                key,
                local.run(
                    ["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048"]
                ).decode(),
            )
        if not public.exists():
            local.private(
                public, local.run(["openssl", "pkey", "-in", str(key), "-pubout"]).decode()
            )
    print("本项目 task dev/test 数据库、分权账号与独立服务密钥就绪。")


def environment(mode: str) -> dict[str, str]:
    """只返回当前项目运行变量，不将凭据打印到终端或命令参数。"""
    entry = load()["resources"][mode]
    return {
        "TASK_DB_URL": f"jdbc:mysql://127.0.0.1:43306/{entry['database']}"
        "?sslMode=DISABLED&allowPublicKeyRetrieval=true",
        "TASK_DB_USER": entry["app"],
        "TASK_DB_PASSWORD": entry["app_password"],
        "TASK_MIGRATION_USER": entry["migrator"],
        "TASK_MIGRATION_PASSWORD": entry["migration_password"],
        "TASK_SUBMIT_PUBLIC_KEY": (local.STATE / "task-submit-public.pem").as_uri(),
        "TASK_WORKER_PUBLIC_KEY": (local.STATE / "task-worker-public.pem").as_uri(),
        "TASK_BIND_ADDRESS": "127.0.0.1",
        "TASK_SERVICE_URL": "http://127.0.0.1:28087",
    }
