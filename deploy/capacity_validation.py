"""S16C可复现入口：创建独立test schema，保留记录，仅运行本机合成负载和模型替身。"""

import hashlib
import json
import re
import subprocess
from pathlib import Path
from uuid import uuid4

import local

ROOT = Path(__file__).resolve().parents[1]


def ledger_digests():
    return {
        str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in (ROOT / ".local").glob("*-budget*.json")
    }


def main():
    """显式入口才创建新库；不删除、复用或重置任何已登记策略/历史数据。"""
    run_id = uuid4().hex[:8]
    directory = ROOT / ".logs" / ("s16c-" + run_id)
    directory.mkdir(mode=0o700)
    environment = local.environment("test")
    databases = {}
    for domain, prefix in (("knowledge", "KNOWLEDGE"), ("tasks", "TASK")):
        database = f"agentic_{domain}_test_s16c_{run_id}"
        app = environment[prefix + "_DB_USER"]
        migrator = environment[prefix + "_MIGRATION_USER"]
        assert all(
            re.fullmatch(r"[a-z0-9_]+", item) for item in (database, app, migrator)
        )
        assert (
            local.mysql(
                f"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='{database}'"
            ).strip()
            == "0"
        )
        local.mysql(
            f"CREATE DATABASE `{database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
            f"GRANT SELECT,INSERT,UPDATE,DELETE ON `{database}`.* TO '{app}'@'%';"
            f"GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES "
            f"ON `{database}`.* TO '{migrator}'@'%';"
        )
        databases[domain] = database
        environment[prefix + "_DB_URL"] = (
            f"jdbc:mysql://127.0.0.1:43306/{database}"
            "?sslMode=DISABLED&allowPublicKeyRetrieval=true"
        )
    receipt = ROOT / ".local" / ("s16c-" + run_id + ".json")
    record = {
        "runId": run_id,
        "directory": str(directory),
        "databases": databases,
        "status": "RUNNING",
        "baseline": subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
        ).strip(),
        "budgetsBefore": ledger_digests(),
    }
    local.private(receipt, json.dumps(record, indent=2))
    local.private(
        ROOT / ".local/s16c-latest.json", json.dumps({"receipt": str(receipt)})
    )
    environment.update(
        AGENTIC_CAPACITY_IT=str(receipt),
        LANGSMITH_TRACING="false",
        LANGCHAIN_TRACING_V2="false",
    )
    with (directory / "pytest.log").open("w") as log:
        process = subprocess.Popen(
            [
                str(ROOT / "ai-runtime/.venv/bin/python"),
                "-m",
                "pytest",
                "tests/test_capacity_integration.py",
                "-q",
                "-x",
            ],
            cwd=ROOT / "ai-runtime",
            env=environment,
            stdout=log,
            stderr=subprocess.STDOUT,
        )
        record["pid"] = process.pid
        local.private(receipt, json.dumps(record, indent=2))
        code = process.wait()
    record.update(
        exitCode=code,
        status="PASS" if code == 0 else "FAIL",
        budgetsAfter=ledger_digests(),
    )
    assert record["budgetsBefore"] == record["budgetsAfter"], (
        "原模型账本有变化，必须独立核对"
    )
    local.private(receipt, json.dumps(record, indent=2))
    print(
        json.dumps(
            {
                "status": record["status"],
                "receipt": str(receipt),
                "log": str(directory / "pytest.log"),
            }
        )
    )
    return code


if __name__ == "__main__":
    raise SystemExit(main())
