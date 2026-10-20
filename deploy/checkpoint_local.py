#!/usr/bin/env python3
"""只初始化本项目 PG 逻辑库；原生 saver 迁移与平台不可变保护分开记账。"""

from __future__ import annotations

import asyncio
import hashlib
import json
import secrets
import sys
from pathlib import Path
from urllib.parse import quote

import local

CONFIG = local.STATE / "checkpoints.json"
POSTGRES = "dev-infra-postgres16-1"
RESOURCES = {
    "dev": ("agentic_checkpoints", "agentic_cp_app", "agentic_cp_migrate"),
    "test": ("agentic_checkpoints_test", "agentic_cp_test_app", "agentic_cp_test_migrate"),
}
MIGRATION = local.ROOT / "deploy/checkpoints/V1__immutable_snapshots.sql"


def pg(sql: str, database: str | None = None) -> str:
    """管理身份只从目标容器环境取得，密码不进入宿主命令参数。"""
    container = json.loads(local.run(["docker", "inspect", POSTGRES]))[0]
    env = dict(item.split("=", 1) for item in container["Config"]["Env"] if "=" in item)
    user = env["POSTGRES_USER"]
    target = database or env.get("POSTGRES_DB", user)
    return local.run(
        [
            "docker",
            "exec",
            "-i",
            POSTGRES,
            "psql",
            "-U",
            user,
            "-d",
            target,
            "-v",
            "ON_ERROR_STOP=1",
            "-At",
        ],
        data=sql.encode(),
    ).decode()


def load() -> dict:
    """记录归属和文件权限必须成立，不接管其他项目已有库。"""
    if not CONFIG.exists() or CONFIG.stat().st_mode & 0o077:
        raise RuntimeError("PG 运行记录缺失或权限错误")
    value = json.loads(CONFIG.read_text())
    for mode, names in RESOURCES.items():
        if tuple(value["resources"][mode][key] for key in ("database", "app", "migrator")) != names:
            raise RuntimeError("PG 资源不在本项目白名单内")
    return value


def dsn(mode: str, *, migration: bool = False) -> str:
    """连接串仅交给 psycopg，调用方不得打印返回值。"""
    entry = load()["resources"][mode]
    role = entry["migrator" if migration else "app"]
    password = entry["migration_password" if migration else "app_password"]
    return (
        f"postgresql://{role}:{quote(password, safe='')}@127.0.0.1:45432/{entry['database']}"
        "?sslmode=disable&connect_timeout=3"
    )


async def migrate(mode: str) -> None:
    """同一初始化过程补全原生表注释，应用身份从不运行 setup 或 DDL。"""
    from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
    from psycopg import AsyncConnection
    from psycopg.rows import dict_row

    async with await AsyncConnection.connect(
        dsn(mode, migration=True),
        autocommit=True,
        prepare_threshold=0,
        row_factory=dict_row,
        options="-c statement_timeout=10000 -c lock_timeout=3000",
    ) as connection:
        saver = AsyncPostgresSaver(connection)
        await saver.setup()
        # 组合 DDL 用 simple query；原生 saver 的单条语句仍可使用 prepare_threshold=0。
        await connection.execute(
            """
            CREATE TABLE IF NOT EXISTS runtime_schema_migrations (
                version INTEGER PRIMARY KEY, sha256 CHAR(64) NOT NULL,
                applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
            );
            COMMENT ON TABLE runtime_schema_migrations IS '本项目图存储保护迁移账本';
            COMMENT ON COLUMN runtime_schema_migrations.version IS '平台迁移版本';
            COMMENT ON COLUMN runtime_schema_migrations.sha256 IS '不可改写的迁移文件摘要';
            COMMENT ON COLUMN runtime_schema_migrations.applied_at IS '迁移 UTC 提交时间';
        """,
            prepare=False,
        )
        digest = hashlib.sha256(MIGRATION.read_bytes()).hexdigest()
        async with connection.transaction():
            # 两个重复初始化进程也按数据库锁顺序核对同一迁移。
            await connection.execute("LOCK TABLE runtime_schema_migrations IN EXCLUSIVE MODE")
            cursor = await connection.execute(
                "SELECT sha256 FROM runtime_schema_migrations WHERE version=1"
            )
            row = await cursor.fetchone()
            if row:
                if row["sha256"] != digest:
                    raise RuntimeError("已应用 PG 平台迁移不能改写")
            else:
                await connection.execute(MIGRATION.read_text(), prepare=False)
                await connection.execute(
                    "INSERT INTO runtime_schema_migrations(version,sha256) VALUES(1,%s)", (digest,)
                )
        cursor = await connection.execute("""
            SELECT COUNT(*) AS missing FROM pg_attribute a
            JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname='public' AND c.relkind='r' AND a.attnum>0 AND NOT a.attisdropped
              AND col_description(c.oid,a.attnum) IS NULL
        """)
        result = await cursor.fetchone()
        if result is None or result["missing"]:
            raise RuntimeError("PG 表列注释不完整")


def init() -> None:
    """先记录随机凭据与归属，再幂等创建库/角色，保留所有已有记录。"""
    if CONFIG.exists():
        config = load()
    else:
        for database, app, migrator in RESOURCES.values():
            existing = pg(
                f"SELECT datname FROM pg_database WHERE datname='{database}';"
                f"SELECT rolname FROM pg_roles WHERE rolname IN ('{app}','{migrator}');"
            )
            if existing.strip():
                raise RuntimeError("PG 同名资源已存在但无本项目所有权记录")
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
    for mode, entry in config["resources"].items():
        database, app, migrator = (entry[key] for key in ("database", "app", "migrator"))
        for role, password in (
            (app, entry["app_password"]),
            (migrator, entry["migration_password"]),
        ):
            if not pg(f"SELECT 1 FROM pg_roles WHERE rolname='{role}'").strip():
                pg(
                    f"CREATE ROLE {role} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE "
                    f"NOINHERIT PASSWORD '{password}';"
                )
        if not pg(f"SELECT 1 FROM pg_database WHERE datname='{database}'").strip():
            pg(f"CREATE DATABASE {database} OWNER {migrator};")
        pg(
            f"REVOKE ALL ON DATABASE {database} FROM PUBLIC;"
            f"GRANT CONNECT ON DATABASE {database} TO {app},{migrator};"
        )
        pg(
            f"REVOKE ALL ON SCHEMA public FROM PUBLIC;GRANT USAGE ON SCHEMA public TO {app};",
            database,
        )
        # uv 环境已锁定驱动实现；单独子进程避免全局 Python 环境安装数据库包。
        local.run(
            [
                str(local.ROOT / "ai-runtime/.venv/bin/python"),
                str(Path(__file__)),
                "--migrate",
                mode,
            ]
        )
        pg(
            "GRANT SELECT,INSERT,UPDATE ON checkpoints,checkpoint_blobs,checkpoint_writes "
            f"TO {app};"
            f"GRANT SELECT ON checkpoint_migrations,runtime_schema_migrations TO {app};",
            database,
        )
    print("本项目 PG dev/test、分权账号、原生迁移/中文注释与不可变保护已就绪。")


if __name__ == "__main__":
    try:
        if len(sys.argv) == 3 and sys.argv[1] == "--migrate" and sys.argv[2] in RESOURCES:
            asyncio.run(migrate(sys.argv[2]))
        else:
            init()
    except Exception as failure:
        # 数据库异常可能携带地址和上下文，不将原始异常消息写到终端。
        print(f"PG 初始化失败：{type(failure).__name__}；未输出凭据或 SQL", file=sys.stderr)
        raise SystemExit(1) from None
