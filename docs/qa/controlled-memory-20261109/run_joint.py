"""真实服务/双索引/PG隔离联合测试，保留test记录；不调用付费模型。"""

import importlib
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(root / "deploy"))
env = importlib.import_module("local").environment("test")
env.update(
    AGENTIC_RETRIEVAL_JOINT_IT="1",
    KNOWLEDGE_MANAGEMENT_PORT="0",
    KNOWLEDGE_QDRANT_ENDPOINT="http://127.0.0.1:28333",
    KNOWLEDGE_ELASTIC_ENDPOINT="http://127.0.0.1:29300",
)
raise SystemExit(
    subprocess.run(
        [
            "mvn",
            "-B",
            "-ntp",
            "-pl",
            "knowledge-service",
            "-am",
            "-Pintegration",
            "-Dit.test=CrossRuntimeRetrievalIT#controlledMemoryUsesRealAuthorityAndPostgresWithoutBodyCopies",
            "-Dfailsafe.failIfNoSpecifiedTests=false",
            "test-compile",
            "failsafe:integration-test",
            "failsafe:verify",
        ],
        cwd=root,
        env=env,
        timeout=260,
    ).returncode
)
