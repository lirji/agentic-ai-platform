"""浏览器验收用Conversation实例：仅test数据库、无worker、无模型调用。"""

import importlib
import os
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(root / "deploy"))
env = importlib.import_module("local").environment("test")
env.update(
    CONVERSATION_PORT="28388",
    CONVERSATION_MANAGEMENT_PORT="0",
    CONVERSATION_MEMORY_ENABLED="true",
    CONVERSATION_WORKER_ENABLED="false",
)
os.execvpe(
    "java",
    [
        "java",
        "-jar",
        str(
            root / "conversation-service/target/conversation-service-0.1.0-SNAPSHOT.jar"
        ),
    ],
    env,
)
