"""S17验证资源Owner：只创建已记录的唯一新命名空间，不清理、不接管旧资源。"""

import asyncio
import json
import re
import secrets
import sys
from enum import StrEnum
from pathlib import Path
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "deploy"))
import checkpoint_local  # noqa: E402
import local  # noqa: E402
import search_local  # noqa: E402

PROTOCOL = "release-validation-resources-v1"


class ValidationError(StrEnum):
    """隔离发布专用稳定错误目录；不把凭据或环境正文加入异常。"""

    BASELINE_ALREADY_RECORDED = "BASELINE_ALREADY_RECORDED"
    VALIDATION_ACTION_INVALID = "VALIDATION_ACTION_INVALID"
    VALIDATION_ARTIFACT_TAG_MISMATCH = "VALIDATION_ARTIFACT_TAG_MISMATCH"
    VALIDATION_ARTIFACT_TAG_OCCUPIED = "VALIDATION_ARTIFACT_TAG_OCCUPIED"
    VALIDATION_BASE_IMAGE_CHANGED = "VALIDATION_BASE_IMAGE_CHANGED"
    VALIDATION_BASE_RECEIPT_CHANGED = "VALIDATION_BASE_RECEIPT_CHANGED"
    VALIDATION_BASE_SERVICES_INVALID = "VALIDATION_BASE_SERVICES_INVALID"
    VALIDATION_BASE_SETTINGS_CHANGED = "VALIDATION_BASE_SETTINGS_CHANGED"
    VALIDATION_BASE_UNVERIFIED = "VALIDATION_BASE_UNVERIFIED"
    VALIDATION_COMPOSE_CHANGED = "VALIDATION_COMPOSE_CHANGED"
    VALIDATION_ID_INVALID = "VALIDATION_ID_INVALID"
    VALIDATION_IMAGE_CHANGED = "VALIDATION_IMAGE_CHANGED"
    VALIDATION_MOUNT_NOT_ISOLATED = "VALIDATION_MOUNT_NOT_ISOLATED"
    VALIDATION_NAMESPACE_OCCUPIED = "VALIDATION_NAMESPACE_OCCUPIED"
    VALIDATION_PRIVATE_STATE_INVALID = "VALIDATION_PRIVATE_STATE_INVALID"
    VALIDATION_PROJECT_INVALID = "VALIDATION_PROJECT_INVALID"
    VALIDATION_RUNTIME_CHANGED = "VALIDATION_RUNTIME_CHANGED"
    VALIDATION_STATE_INVALID = "VALIDATION_STATE_INVALID"
    VALIDATION_STATE_LOCATION_INVALID = "VALIDATION_STATE_LOCATION_INVALID"
    VALIDATION_STATE_MISMATCH = "VALIDATION_STATE_MISMATCH"
    VALIDATION_WEB_BASE_CHANGED = "VALIDATION_WEB_BASE_CHANGED"
    VALIDATION_WEB_BUNDLE_CHANGED = "VALIDATION_WEB_BUNDLE_CHANGED"
    VALIDATION_WEB_BUNDLE_SOURCE_CHANGED = "VALIDATION_WEB_BUNDLE_SOURCE_CHANGED"
    VALIDATION_WEB_CONFIG_CHANGED = "VALIDATION_WEB_CONFIG_CHANGED"
    VALIDATION_WEB_DEPENDENCIES_CHANGED = "VALIDATION_WEB_DEPENDENCIES_CHANGED"
    VALIDATION_WEB_HOST_DEPENDENCIES_CHANGED = "VALIDATION_WEB_HOST_DEPENDENCIES_CHANGED"
    VALIDATION_WEB_HOST_MODULES_INVALID = "VALIDATION_WEB_HOST_MODULES_INVALID"
    VALIDATION_WEB_IMAGE_INVALID = "VALIDATION_WEB_IMAGE_INVALID"
    VALIDATION_WEB_NODE_CHANGED = "VALIDATION_WEB_NODE_CHANGED"
    VALIDATION_WEB_RECIPE_CHANGED = "VALIDATION_WEB_RECIPE_CHANGED"
    VALIDATION_WEB_SOURCE_CHANGED = "VALIDATION_WEB_SOURCE_CHANGED"


def save(path: Path, record: dict) -> None:
    """凭据只写私密记录；日志和公开报告只引用阶段与非敏感名称。"""
    local.private(path, json.dumps(record, indent=2) + "\n")


def names(run_id: str) -> dict:
    """标识完全由受限随机ID派生，SQL标识符不接受外部自由输入。"""
    if not re.fullmatch(r"[a-f0-9]{8}", run_id):
        raise RuntimeError(ValidationError.VALIDATION_ID_INVALID)
    values = {}
    for domain, code in (
        ("knowledge", "k"),
        ("tasks", "t"),
        ("conversations", "c"),
        ("checkpoints", "p"),
    ):
        values[domain] = {
            "database": f"agentic_{domain}_s17_{run_id}",
            "app": f"a17{code}_{run_id}",
            "migrator": f"m17{code}_{run_id}",
            "app_password": secrets.token_hex(24),
            "migration_password": secrets.token_hex(24),
        }
    values["source"] = {
        "bucket": f"agentic-s17-{run_id}",
        "user": f"s17_{run_id}",
        "password": secrets.token_hex(24),
    }
    values["search"] = {
        "index": f"agentic-s17-{run_id}",
        "collection": f"agentic_s17_{run_id}",
        "user": f"s17_{run_id}",
        "password": secrets.token_hex(24),
    }
    return values


def preflight(resources: dict) -> None:
    """全部命名空间先确认未占用；缺所有权记录绝不覆盖同名资源。"""
    for domain in ("knowledge", "tasks", "conversations"):
        item = resources[domain]
        found = local.mysql(
            "SELECT schema_name FROM information_schema.schemata "
            f"WHERE schema_name='{item['database']}';"
            f"SELECT user FROM mysql.user WHERE user IN ('{item['app']}','{item['migrator']}');"
        )
        if found.strip():
            raise RuntimeError(ValidationError.VALIDATION_NAMESPACE_OCCUPIED)
    item = resources["checkpoints"]
    if checkpoint_local.pg(
        f"SELECT datname FROM pg_database WHERE datname='{item['database']}';"
        f"SELECT rolname FROM pg_roles WHERE rolname IN ('{item['app']}','{item['migrator']}');"
    ).strip():
        raise RuntimeError(ValidationError.VALIDATION_NAMESPACE_OCCUPIED)
    item = resources["source"]
    found = local.minio(
        'if mc stat "infra/$BUCKET" >/dev/null 2>&1; then echo occupied; fi\n'
        'if mc admin user info infra "$USER_NAME" >/dev/null 2>&1; then echo occupied; fi\n'
        'if mc admin policy info infra "$BUCKET" >/dev/null 2>&1; then echo occupied; fi\n',
        {"BUCKET": item["bucket"], "USER_NAME": item["user"]},
    )
    if found.strip():
        raise RuntimeError(ValidationError.VALIDATION_NAMESPACE_OCCUPIED)
    item = resources["search"]
    if search_local.request("GET", f"/_security/user/{item['user']}") or search_local.request(
        "GET", f"/_security/role/{item['user']}"
    ):
        raise RuntimeError(ValidationError.VALIDATION_NAMESPACE_OCCUPIED)
    if (
        search_local.request("GET", f"/{item['index']}/_mapping") is not None
        or search_local.request("GET", f"/collections/{item['collection']}", vector=True)
        is not None
    ):
        raise RuntimeError(ValidationError.VALIDATION_NAMESPACE_OCCUPIED)


def create_mysql(item: dict) -> None:
    """应用只有DML；迁移账号权限仅限新库，无DROP权限。"""
    local.mysql(
        f"CREATE DATABASE IF NOT EXISTS `{item['database']}` "
        "CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
        f"CREATE USER IF NOT EXISTS '{item['app']}'@'%' IDENTIFIED BY '{item['app_password']}';"
        f"CREATE USER IF NOT EXISTS '{item['migrator']}'@'%' "
        f"IDENTIFIED BY '{item['migration_password']}';"
        f"GRANT SELECT,INSERT,UPDATE,DELETE ON `{item['database']}`.* TO '{item['app']}'@'%';"
        "GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES "
        f"ON `{item['database']}`.* TO '{item['migrator']}'@'%';"
    )


def checkpoint_dsn(item: dict, *, migration: bool = False, docker: bool = False) -> str:
    """连接串只供进程消费，禁止写公开日志。"""
    user = item["migrator" if migration else "app"]
    password = item["migration_password" if migration else "app_password"]
    host = "dev-infra-postgres16-1:5432" if docker else "127.0.0.1:45432"
    return (
        f"postgresql://{user}:{quote(password, safe='')}@{host}/{item['database']}"
        "?sslmode=disable&connect_timeout=3"
    )


def create_postgres(item: dict) -> None:
    """复用已冻结的原生Saver/保护迁移；临时函数注入只在本初始化进程内有效。"""
    for role, password in (
        (item["app"], item["app_password"]),
        (item["migrator"], item["migration_password"]),
    ):
        if not checkpoint_local.pg(f"SELECT 1 FROM pg_roles WHERE rolname='{role}'").strip():
            checkpoint_local.pg(
                f"CREATE ROLE {role} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE "
                f"NOINHERIT PASSWORD '{password}';"
            )
    if not checkpoint_local.pg(
        f"SELECT 1 FROM pg_database WHERE datname='{item['database']}'"
    ).strip():
        checkpoint_local.pg(f"CREATE DATABASE {item['database']} OWNER {item['migrator']};")
    checkpoint_local.pg(
        f"REVOKE ALL ON DATABASE {item['database']} FROM PUBLIC;"
        f"GRANT CONNECT ON DATABASE {item['database']} TO {item['app']},{item['migrator']};"
    )
    checkpoint_local.pg(
        f"REVOKE ALL ON SCHEMA public FROM PUBLIC;GRANT USAGE ON SCHEMA public TO {item['app']};",
        item["database"],
    )
    original = checkpoint_local.dsn
    checkpoint_local.dsn = lambda mode, migration=False: checkpoint_dsn(item, migration=migration)
    try:
        asyncio.run(checkpoint_local.migrate("validation"))
    finally:
        checkpoint_local.dsn = original
    checkpoint_local.pg(
        "GRANT SELECT,INSERT,UPDATE ON checkpoints,checkpoint_blobs,checkpoint_writes "
        f"TO {item['app']};GRANT SELECT ON checkpoint_migrations,runtime_schema_migrations "
        f"TO {item['app']};",
        item["database"],
    )


def create_source(item: dict) -> None:
    """策略仅允许本桶读/写，不授予其他项目或物理删除能力。"""
    policy = {
        "Version": "2012-10-17",
        "Statement": [
            {
                "Effect": "Allow",
                "Action": ["s3:GetBucketLocation", "s3:ListBucket"],
                "Resource": [f"arn:aws:s3:::{item['bucket']}"],
            },
            {
                "Effect": "Allow",
                "Action": ["s3:GetObject", "s3:PutObject"],
                "Resource": [f"arn:aws:s3:::{item['bucket']}/*"],
            },
        ],
    }
    local.minio(
        'mc mb --ignore-existing "infra/$BUCKET" >/dev/null\n'
        'if ! mc admin user info infra "$USER_NAME" >/dev/null 2>&1; then\n'
        'mc admin user add infra "$USER_NAME" "$USER_PASSWORD" >/dev/null\nfi\n'
        'printf "%s" "$POLICY" >/tmp/policy.json\n'
        'mc admin policy create infra "$BUCKET" /tmp/policy.json >/dev/null\n'
        'mc admin policy attach infra "$BUCKET" --user "$USER_NAME" >/dev/null\n',
        {
            "BUCKET": item["bucket"],
            "USER_NAME": item["user"],
            "USER_PASSWORD": item["password"],
            "POLICY": json.dumps(policy),
        },
    )


def create_search(item: dict) -> None:
    """复制已运行项目的字段定义，创建新索引；不复制原数据或调整原映射。"""
    source = search_local.request("GET", f"/{search_local.INDEX}/_mapping")
    mapping = source[search_local.INDEX]["mappings"]
    if search_local.request("GET", f"/{item['index']}/_mapping") is None:
        search_local.request(
            "PUT",
            f"/{item['index']}",
            {"settings": {"number_of_shards": 1, "number_of_replicas": 0}, "mappings": mapping},
        )
    search_local.request(
        "PUT",
        f"/_security/role/{item['user']}",
        {
            "cluster": [],
            "indices": [
                {"names": [item["index"]], "privileges": ["read", "write", "view_index_metadata"]}
            ],
        },
    )
    search_local.request(
        "PUT",
        f"/_security/user/{item['user']}",
        {"password": item["password"], "roles": [item["user"]]},
    )
    original = search_local.request("GET", f"/collections/{search_local.COLLECTION}", vector=True)[
        "result"
    ]
    if search_local.request("GET", f"/collections/{item['collection']}", vector=True) is None:
        search_local.request(
            "PUT",
            f"/collections/{item['collection']}",
            {"vectors": original["config"]["params"]["vectors"]},
            vector=True,
        )
    for name, field in original.get("payload_schema", {}).items():
        search_local.request(
            "PUT",
            f"/collections/{item['collection']}/index?wait=true",
            {"field_name": name, "field_schema": field["data_type"]},
            vector=True,
        )


def prepare(directory: Path, run_id: str) -> dict:
    """阶段落盘后才推进；中断恢复不创建新ID，不覆盖其他任务。"""
    if directory.resolve() != (ROOT / ".local/release-validation" / run_id).resolve():
        raise RuntimeError(ValidationError.VALIDATION_STATE_LOCATION_INVALID)
    path = directory / "resources.json"
    if path.exists():
        if path.is_symlink() or path.stat().st_mode & 0o077:
            raise RuntimeError(ValidationError.VALIDATION_PRIVATE_STATE_INVALID)
        record = json.loads(path.read_text())
        if record.get("protocol") != PROTOCOL or record.get("id") != run_id:
            raise RuntimeError(ValidationError.VALIDATION_STATE_MISMATCH)
        # 即使文件被人工改动，也不能将管理DDL定向到任意现存项目。
        for values in record["resources"].values():
            for key, value in values.items():
                if key in {"password", "app_password", "migration_password"}:
                    if not isinstance(value, str) or not re.fullmatch(r"[a-f0-9]{48}", value):
                        raise RuntimeError(ValidationError.VALIDATION_STATE_MISMATCH)
        expected = names(run_id)
        for domain in expected:
            for key, value in expected[domain].items():
                if (
                    key not in {"password", "app_password", "migration_password"}
                    and record["resources"][domain][key] != value
                ):
                    raise RuntimeError(ValidationError.VALIDATION_STATE_MISMATCH)
    else:
        resources = names(run_id)
        preflight(resources)
        record = {"protocol": PROTOCOL, "id": run_id, "resources": resources, "completed": []}
        save(path, record)
    for domain, action in [
        ("knowledge", create_mysql),
        ("tasks", create_mysql),
        ("conversations", create_mysql),
        ("checkpoints", create_postgres),
        ("source", create_source),
        ("search", create_search),
    ]:
        if domain not in record["completed"]:
            action(record["resources"][domain])
            record["completed"].append(domain)
            save(path, record)
    return record
