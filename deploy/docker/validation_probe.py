"""S17A真实隔离验收：只写合成会话，保留数据与原环境指纹，支持重启后复核。"""

import argparse
import hashlib
import json
import subprocess
from enum import StrEnum
from http import HTTPStatus
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import validation_resources as resources
import validation_runtime as runtime

DOMAINS = ("knowledge", "tasks", "conversations")
BASE = f"http://127.0.0.1:{runtime.WEB_PORT}"
REQUEST_TIMEOUT_SECONDS = 20
HEALTH_TIMEOUT_SECONDS = 15


class ProbePhase(StrEnum):
    """独立证据阶段固定序列化值，不用展示文本驱动恢复。"""

    ARTIFACTS = "artifacts"
    BASELINE = "baseline"
    BEFORE_RESTART = "before-restart"
    AFTER_RESTART = "after-restart"


def http(path: str, *, origin=BASE, headers=None, body=None, port=runtime.WEB_PORT):
    """只访问本机显式端口；错误响应保留状态码，不输出可能敏感的正文。"""
    values = {"X-Workbench-Request": "1", "Origin": origin}
    values.update(headers or {})
    data = None if body is None else json.dumps(body).encode()
    if data is not None:
        values["Content-Type"] = "application/json"
    request = Request(f"http://127.0.0.1:{port}" + path, data=data, headers=values)
    try:
        with urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            return response.status, response.read()
    except HTTPError as error:
        return error.code, error.read()


def database_snapshot(databases: dict) -> dict:
    """按字段/行规范排序；只保存摘要和行数，不把业务正文导出到公共报告。"""
    result = {}
    for domain, database in databases.items():
        metadata = resources.local.mysql(
            "SELECT table_name,column_name FROM information_schema.columns "
            f"WHERE table_schema='{database}' ORDER BY table_name,ordinal_position"
        ).splitlines()
        tables = {}
        for line in metadata:
            table, column = line.split("\t")
            tables.setdefault(table, []).append(column)
        queries = []
        for table, columns in tables.items():
            expression = ",".join(f"IFNULL(HEX(`{name}`),'NULL')" for name in columns)
            queries.append(
                f"SELECT '{table}',CONCAT_WS(':',{expression}) FROM `{database}`.`{table}`;"
            )
        rows_by_table = {table: [] for table in tables}
        for row in resources.local.mysql("\n".join(queries)).splitlines():
            table, value = row.split("\t", 1)
            rows_by_table[table].append(value)
        result[domain] = {
            table: {
                "rows": len(rows),
                "sha256": hashlib.sha256("\n".join(sorted(rows)).encode()).hexdigest(),
            }
            for table, rows in rows_by_table.items()
        }
    return result


def containers(project: str) -> dict:
    """逐个已知名字读取，避免扫描/干预共享宿主其他项目。"""
    result = {}
    for name in sorted(runtime.release_artifacts.SERVICES):
        item = json.loads(
            subprocess.check_output(
                ["docker", "inspect", f"{project}-{name}-1"],
                text=True,
                timeout=REQUEST_TIMEOUT_SECONDS,
            )
        )[0]
        result[name] = {
            "id": item["Id"],
            "imageId": item["Image"],
            "state": item["State"]["Status"],
            "health": item["State"].get("Health", {}).get("Status"),
            "restarts": item["RestartCount"],
        }
    return result


def original() -> dict:
    """现有开发资源只读；原账本任何变动均阻止自动判定通过。"""
    import conversation_local
    import task_local

    databases = {
        "knowledge": resources.local.load()["resources"]["dev"]["database"],
        "tasks": task_local.load()["resources"]["dev"]["database"],
        "conversations": conversation_local.load()["resources"]["dev"]["database"],
    }
    return {
        "data": database_snapshot(databases),
        "containers": containers("agentic-ai-app"),
        "budgets": {
            kind: runtime.digest(runtime.ROOT / f".local/{kind}-budget-20261020.json")
            for kind in ("chat", "embedding")
        },
    }


def artifacts(directory: Path) -> dict:
    """在专用测试tag上实际更换镜像，确认核对入口拒绝；不动任何部署标签。"""
    import runtime as normal_runtime

    source_state = runtime.ROOT / ".local/docker-runtime"
    settings = json.loads((source_state / "current.json").read_text())
    images = normal_runtime.service_images(source_state)
    gate = runtime.release_artifacts
    tag = "agentic-ai/validation-drift:" + directory.name
    ownership = directory / "artifact-probe-owner.json"
    if not ownership.exists():
        exists = subprocess.run(["docker", "image", "inspect", tag], capture_output=True)
        if exists.returncode == 0:
            raise RuntimeError(resources.ValidationError.VALIDATION_ARTIFACT_TAG_OCCUPIED)
        runtime.private(ownership, json.dumps({"tag": tag, "protocol": "artifact-probe-v1"}))
    elif json.loads(ownership.read_text()) != {"tag": tag, "protocol": "artifact-probe-v1"}:
        raise RuntimeError(resources.ValidationError.VALIDATION_ARTIFACT_TAG_MISMATCH)
    subprocess.run(["docker", "tag", images["gateway"], tag], check=True)
    images["gateway"] = tag
    receipt = gate.record(settings, images)
    gate.verify(settings, images, receipt)
    subprocess.run(["docker", "tag", images["knowledge"], tag], check=True)
    try:
        gate.verify(settings, images, receipt)
        raise AssertionError("CHANGED_TAG_ACCEPTED")
    except RuntimeError as error:
        assert str(error) == gate.ReleaseError.IMAGE_CHANGED
    try:
        normal_runtime.verify_artifacts(directory / "no-receipt")
        raise AssertionError("MISSING_RECEIPT_ACCEPTED")
    except RuntimeError as error:
        assert str(error) == gate.ReleaseError.RECEIPT_MISSING
    return {
        "fixedSourceAndImages": "PASS",
        "realTagReplacementDenied": "PASS",
        "missingReceiptDenied": "PASS",
        "sourceFingerprint": receipt["sourceFingerprint"],
        "tag": tag,
        "previousImageId": receipt["services"]["gateway"]["imageId"],
        "changedImageId": gate.image_fact(tag)["imageId"],
    }


def mysql_as_app(item: dict, sql: str) -> subprocess.CompletedProcess:
    """密码走stdin，既不出现进程参数也不回显到错误报告。"""
    script = (
        "read -r MYSQL_PWD; export MYSQL_PWD; read -r login; "
        'exec mysql -u "$login" --batch --skip-column-names'
    )
    return subprocess.run(
        ["docker", "exec", "-i", resources.local.MYSQL, "sh", "-c", script],
        input=(item["app_password"] + "\n" + item["app"] + "\n" + sql).encode(),
        capture_output=True,
        timeout=REQUEST_TIMEOUT_SECONDS,
    )


def acl(values: dict) -> dict:
    """真数据库验证DML身份不能DDL或跨库；意外授权时保留探针表供调查。"""
    checks = {}
    for domain in DOMAINS:
        item = values[domain]
        own = item["database"]
        assert (
            mysql_as_app(item, f"SELECT COUNT(*) FROM `{own}`.flyway_schema_history").returncode
            == 0
        )
        denied = mysql_as_app(
            item,
            f"CREATE TABLE `{own}`.s17_forbidden_ddl_probe (id INT COMMENT '隔离权限负例') "
            "COMMENT='隔离权限负例，正常必须拒绝';",
        )
        assert denied.returncode != 0 and b"1142" in denied.stderr, "APPLICATION_DDL_ALLOWED"
        other = values[DOMAINS[(DOMAINS.index(domain) + 1) % len(DOMAINS)]]["database"]
        denied = mysql_as_app(item, f"SELECT COUNT(*) FROM `{other}`.flyway_schema_history")
        assert denied.returncode != 0 and (b"1142" in denied.stderr or b"1044" in denied.stderr), (
            "CROSS_DATABASE_ALLOWED"
        )
        checks[domain] = {"ownRead": True, "ddlDenied": True, "crossDatabaseDenied": True}
    import psycopg

    with psycopg.connect(resources.checkpoint_dsn(values["checkpoints"])) as connection:
        assert connection.execute("SELECT count(*) FROM checkpoints").fetchone()[0] >= 0
        try:
            connection.execute(
                "CREATE TABLE s17_forbidden_ddl_probe(id integer);"
                "COMMENT ON TABLE s17_forbidden_ddl_probe IS '隔离权限负例，正常必须拒绝';"
                "COMMENT ON COLUMN s17_forbidden_ddl_probe.id IS '隔离权限负例标识';"
            )
            raise AssertionError("CHECKPOINT_DDL_ALLOWED")
        except psycopg.errors.InsufficientPrivilege:
            connection.rollback()
    checks["checkpoints"] = {"ownRead": True, "ddlDenied": True}
    return checks


def health(directory: Path, state: dict) -> dict:
    """live/ready记录各自实际语义；不把AI存活解释成模型可用。"""
    items = containers(state["project"])
    for name, item in items.items():
        assert item["state"] == "running" and item["health"] == "healthy", name
        assert item["imageId"] == state["images"][name]["imageId"], name
    checks = {}
    for name, port in (
        ("gateway", 29080),
        ("knowledge", 29084),
        ("tasks", 29087),
        ("conversations", 29088),
    ):
        for kind in ("liveness", "readiness"):
            result = subprocess.check_output(
                runtime.compose(
                    directory,
                    "exec",
                    "-T",
                    name,
                    "curl",
                    "-fsS",
                    "--max-time",
                    "5",
                    f"http://127.0.0.1:{port}/actuator/health/{kind}",
                ),
                timeout=HEALTH_TIMEOUT_SECONDS,
            )
            assert json.loads(result)["status"] == "UP"
            checks[f"{name}/{kind}"] = "UP"
    for port in (29080, 29084, 29087, 29088, 28084, 28087, 28088, 28085, 28089):
        # 只有两个明确公网映射；旧环境同号loopback端口可存在，检查本项目映射而非宿主监听。
        assert not any(
            str(port) == str(entry["published"])
            for entry in json.loads((directory / "compose.json").read_text())["services"][
                "gateway"
            ]["ports"]
        )
    return {"containers": items, "java": checks, "ai": "liveness-only; provider disabled"}


def api(directory: Path) -> dict:
    """真实页面/只读业务与同源拒绝，创建一次可幂等复核的合成会话。"""
    paths = (
        "/",
        "/knowledge",
        "/quality",
        "/api/local-session",
        "/api/v2/knowledge/documents?limit=1",
        "/api/v1/knowledge/readable-documents?limit=1",
        "/api/v1/conversations?limit=1",
        "/api/v1/tasks?kind=RESEARCH&limit=1",
        "/api/v1/tasks?kind=READONLY_ASSISTANT&limit=1",
        "/api/v1/knowledge/evaluations/datasets?limit=1",
    )
    for path in paths:
        status, body = http(path)
        assert status == HTTPStatus.OK, (path, status)
        if path.startswith("/api/"):
            json.loads(body)
    for headers in (
        {"Host": "attacker.invalid"},
        {"Sec-Fetch-Site": "cross-site"},
        {"X-Workbench-Request": ""},
    ):
        assert http("/api/local-session", headers=headers)[0] == HTTPStatus.FORBIDDEN
    assert http("/api/local-session", origin="http://evil.invalid")[0] == HTTPStatus.FORBIDDEN
    assert (
        http("/api/v1/conversations?limit=1", port=runtime.GATEWAY_PORT)[0]
        == HTTPStatus.UNAUTHORIZED
    )
    status, body = http(
        "/api/v1/conversations",
        headers={"Idempotency-Key": "s17a-conversation-" + directory.name},
        body={"title": "S17 隔离重启验证"},
    )
    assert status == HTTPStatus.CREATED, status
    conversation = json.loads(body)
    assert http("/api/v1/conversations/" + conversation["conversationId"])[0] == HTTPStatus.OK
    return {"readOnlyPaths": len(paths), "securityDenials": 5, "conversation": conversation}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=list(ProbePhase), type=ProbePhase)
    parser.add_argument("--id", required=True)
    args = parser.parse_args()
    directory = runtime.ROOT / ".local/release-validation" / args.id
    state = runtime.load(directory)
    values = json.loads((directory / "resources.json").read_text())["resources"]
    record = directory / ("probe-" + args.phase + ".json")
    if args.phase == ProbePhase.ARTIFACTS:
        result = artifacts(directory)
    elif args.phase == ProbePhase.BASELINE:
        if record.exists():
            raise RuntimeError(resources.ValidationError.BASELINE_ALREADY_RECORDED)
        result = {"original": original()}
    else:
        result = {"health": health(directory, state), "acl": acl(values), "api": api(directory)}
        result["data"] = database_snapshot({name: values[name]["database"] for name in DOMAINS})
        result["budgets"] = {
            kind: runtime.digest(directory / "budgets" / f"{kind}.json")
            for kind in ("chat", "embedding")
        }
        current = original()
        baseline = json.loads((directory / "probe-baseline.json").read_text())["original"]
        for key in ("data", "budgets"):
            assert current[key] == baseline[key], "ORIGINAL_ENVIRONMENT_CHANGED: " + key
        for name, item in current["containers"].items():
            for key in ("id", "imageId", "restarts"):
                assert item[key] == baseline["containers"][name][key], "ORIGINAL_CONTAINER_CHANGED"
            if state.get("originalPhase") == runtime.OriginalPhase.STOPPED:
                assert item["state"] == "exited", "ORIGINAL_NOT_STOPPED"
            else:
                assert item["state"] == "running" and item["health"] == "healthy", (
                    "ORIGINAL_NOT_HEALTHY"
                )
        result["originalContinuity"] = "PASS"
        if args.phase == ProbePhase.AFTER_RESTART:
            previous = json.loads((directory / "probe-before-restart.json").read_text())
            for key in ("api", "data", "budgets"):
                assert result[key] == previous[key], "RESTART_CONTINUITY_FAILED: " + key
    runtime.private(record, json.dumps(result, indent=2) + "\n")
    print(json.dumps({"phase": args.phase, "status": "PASS", "evidence": str(record)}))


if __name__ == "__main__":
    main()
