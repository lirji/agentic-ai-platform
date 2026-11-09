"""只连接已有Task test库，保留测试记录，不启动模型或修改dev数据。"""

import importlib
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(root / "deploy"))
env = importlib.import_module("local").environment("test")
env.update(TASK_MANAGEMENT_PORT="0")
raise SystemExit(
    subprocess.run(
        [
            "mvn",
            "-B",
            "-ntp",
            "-pl",
            "async-task-service",
            "-am",
            "-Pintegration",
            "-Dit.test=MemoryTaskPersistenceIT,TaskPersistenceIT",
            "-Dfailsafe.failIfNoSpecifiedTests=false",
            "test-compile",
            "failsafe:integration-test",
            "failsafe:verify",
        ],
        cwd=root,
        env=env,
        timeout=240,
    ).returncode
)
