"""隔离test资源验收表格真实解析/发布/检索；模型仅用共享金标，不产生费用。"""

import importlib
import os
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(root / "deploy"))
env = importlib.import_module("local").environment("test")
engine = os.environ.get("KNOWLEDGE_OCR_EXECUTABLE", "/opt/homebrew/bin/tesseract")
data = str(root / ".local/s9e/tessdata")
env.update(
    KNOWLEDGE_OCR_EXECUTABLE=engine,
    KNOWLEDGE_OCR_DATA=data,
    KNOWLEDGE_STRUCTURED_PROFILE="BLOCK_V2",
    KNOWLEDGE_MANAGEMENT_PORT="0",
    KNOWLEDGE_QDRANT_ENDPOINT="http://127.0.0.1:28333",
    KNOWLEDGE_ELASTIC_ENDPOINT="http://127.0.0.1:29300",
)
command = [
    "mvn",
    "-B",
    "-ntp",
    "-pl",
    "knowledge-service",
    "-am",
    "-Pintegration",
    "-Dit.test=KnowledgeStructuredProcessingIT",
    "-Dfailsafe.failIfNoSpecifiedTests=false",
    f"-Docr.executable={engine}",
    f"-Docr.data={data}",
    "test-compile",
    "failsafe:integration-test",
    "failsafe:verify",
]
raise SystemExit(subprocess.run(command, cwd=root, env=env, timeout=360).returncode)
