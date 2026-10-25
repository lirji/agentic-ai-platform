"""在项目test资源运行真实依赖验收；不package，避免覆盖活动服务JAR。"""

import importlib
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(root / "deploy"))
local = importlib.import_module("local")

env = local.environment("test")
env["KNOWLEDGE_STRUCTURED_PROFILE"] = "BLOCK_V2"
command = [
    "mvn",
    "-B",
    "-ntp",
    "-pl",
    "knowledge-service",
    "-am",
    "-Pintegration",
    "-Dit.test=KnowledgeStructuredProcessingIT,KnowledgePersistenceIT,KnowledgeProcessingIT,EvaluationPersistenceIT",
    "-Dfailsafe.failIfNoSpecifiedTests=false",
    "test-compile",
    "failsafe:integration-test",
    "failsafe:verify",
]
raise SystemExit(subprocess.run(command, cwd=root, env=env, timeout=360).returncode)
