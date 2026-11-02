"""S14D真实Python/Knowledge/浏览器；仅隔离夹具与无费用向量端口。"""

import json
import os
import subprocess
import sys
from pathlib import Path

import candidate_browser_bridge
import local


def browser(fixture: Path) -> None:
    """签名只在本机桥，页面接真实API；测试进程结束即关闭桥，不清理业务数据。"""
    value = json.loads(fixture.read_text())
    web = local.ROOT.parent / "agentic-ai-platform-web"
    env = os.environ.copy()
    env["PATH"] = (
        str(web / ".local/toolchains/node-v24.22.0-darwin-arm64/bin")
        + os.pathsep
        + env["PATH"]
    )
    env["CANDIDATE_FIXTURE"] = str(fixture.resolve())
    with candidate_browser_bridge.serve(value) as endpoint:
        env["PLAYWRIGHT_BASE_URL"] = endpoint
        with (fixture.parent / "browser.log").open("w") as log:
            result = subprocess.run(
                ["pnpm", "exec", "playwright", "test", "tests/recovery-real.spec.ts"],
                check=False,
                cwd=web,
                env=env,
                stdout=log,
                stderr=subprocess.STDOUT,
            )
        assert result.returncode == 0, "补偿浏览器验收失败，请查看本轮browser.log"


def verify() -> None:
    """Java测试自己启动并关闭无费用Python链路，保留每轮日志与真实test记录。"""
    env = local.environment("test")
    env["AGENTIC_RECOVERY_BROWSER"] = "1"
    result = subprocess.run(
        [
            "mvn",
            "-B",
            "-ntp",
            "-pl",
            "knowledge-service",
            "-am",
            "-Pintegration",
            "-Dit.test=KnowledgeCandidateIT#realPythonAndBrowserRecoveryWorkflow",
            "-Dfailsafe.failIfNoSpecifiedTests=false",
            "verify",
        ],
        check=False,
        cwd=local.ROOT,
        env=env,
    )
    assert result.returncode == 0, "发布后检查与补偿联合验收失败"


if __name__ == "__main__":
    if sys.argv[1] == "browser":
        browser(Path(sys.argv[2]))
    elif sys.argv[1] == "verify":
        verify()
    else:
        raise SystemExit("expected browser or verify")
