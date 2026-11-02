#!/usr/bin/env python3
"""S16D真实数据库验证入口：新建独立test schema，使用既有DML/迁移账号并保留证据。"""

import json
import re
import subprocess
import sys
from pathlib import Path
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "deploy"))
import local  # noqa: E402


def main():
    """只创建唯一新测试命名空间，不覆盖现有数据库或任何预算文件。"""
    run_id = uuid4().hex[:8]
    directory = ROOT / ".logs" / ("s16d-db-" + run_id)
    directory.mkdir(mode=0o700)
    environment = local.environment("test")
    databases = {}
    for domain, prefix in (("knowledge", "KNOWLEDGE"), ("tasks", "TASK")):
        database = f"agentic_{domain}_test_s16d_{run_id}"
        app = environment[prefix + "_DB_USER"]
        migrator = environment[prefix + "_MIGRATION_USER"]
        assert all(re.fullmatch(r"[a-z0-9_]+", item) for item in (database, app, migrator))
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
            f"jdbc:mysql://127.0.0.1:43306/{database}?sslMode=DISABLED&allowPublicKeyRetrieval=true"
        )
    environment["OPS_EMPTY_IT"] = "true"
    receipt = ROOT / ".local" / ("s16d-db-" + run_id + ".json")
    record = dict(runId=run_id, directory=str(directory), databases=databases, status="RUNNING")
    local.private(receipt, json.dumps(record, indent=2))
    with (directory / "maven.log").open("w") as log:
        process = subprocess.Popen(
            [
                "mvn",
                "-B",
                "-ntp",
                "-pl",
                "knowledge-service,async-task-service",
                "-am",
                "-Pintegration",
                "-Dit.test=OperationalMetricsIT",
                "-Dfailsafe.failIfNoSpecifiedTests=false",
                "-Dtest=OperationalMetricsTest",
                "-Dsurefire.failIfNoSpecifiedTests=false",
                "verify",
            ],
            cwd=ROOT,
            env=environment,
            stdout=log,
            stderr=subprocess.STDOUT,
        )
        record["pid"] = process.pid
        local.private(receipt, json.dumps(record, indent=2))
        code = process.wait()
    record.update(status="PASS" if code == 0 else "FAIL", exitCode=code)
    local.private(receipt, json.dumps(record, indent=2))
    print(json.dumps(record))
    raise SystemExit(code)


if __name__ == "__main__":
    main()
