"""本项目 AI 本地运行支持，由 local.py 提供唯一命令入口。"""

from __future__ import annotations

import csv
import json
import os
import signal
import socket
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / ".local"
LOGS = ROOT / ".logs"
CONFIG = STATE / "ai-runtime.json"
PID = STATE / "ai-runtime.pid"
PORT = 28085
PYTHON = ROOT / "ai-runtime/.venv/bin/python"


def private(path: Path, text: str) -> None:
    """创建时即限制文件权限，避免先明文写出再 chmod 的窗口。"""
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as file:
        file.write(text)
    path.chmod(0o600)


def init(reference_env: Path) -> None:
    """仅迁入已授权的 Embedding 配置；不执行来源脚本或拷贝整份环境。"""
    if CONFIG.exists():
        print("AI 私密配置已存在，未覆盖；需要变更时请明确修改本项目配置。")
        return
    values = {}
    for line in reference_env.read_text().splitlines():
        key, separator, value = line.partition("=")
        if separator and not key.strip().startswith("#"):
            values[key.strip()] = value.strip().strip("'\"")
    credential = values.get("BAILIAN_CREDENTIAL_CSV")
    if not credential:
        raise RuntimeError("来源环境未配置百炼凭据文件")
    with Path(credential).expanduser().open(encoding="utf-8-sig", newline="") as file:
        rows = {row[0]: row[1] for row in csv.reader(file) if len(row) >= 2}
    key = values.get("RAG_EMBEDDING_API_KEY") or rows.get("apiKey")
    base = values.get("RAG_EMBEDDING_BASE_URL") or rows.get("openAiCompatible")
    if not key or not base or urlsplit(base).scheme != "https":
        raise RuntimeError("来源模型配置缺失或不是 HTTPS")
    STATE.mkdir(exist_ok=True)
    signing = STATE / "knowledge-ai-private.pem"
    public = STATE / "knowledge-ai-public.pem"
    if signing.exists() or public.exists():
        raise RuntimeError("服务密钥已存在但 AI 配置缺失，请核对现场；未覆盖密钥")
    generated = subprocess.run(
        ["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048"],
        capture_output=True,
        check=True,
    ).stdout
    private(signing, generated.decode())
    result = subprocess.run(
        ["openssl", "pkey", "-pubout"], input=generated, capture_output=True, check=True
    )
    private(public, result.stdout.decode())
    environment = {
        "AI_PROCESSING_ENABLED": "true",
        "AI_SERVICE_PUBLIC_KEY": str(public),
        "AI_PROCESSING_MAX_CONCURRENT": "2",
        "AI_PROCESSING_DEADLINE_SECONDS": "40",
        "RAG_EMBEDDING_PROVIDER": "openai",
        "RAG_EMBEDDING_MODEL": "text-embedding-v4",
        "RAG_EMBEDDING_DIMENSIONS": "1024",
        "RAG_EMBEDDING_BASE_URL": base,
        "RAG_EMBEDDING_API_KEY": key,
        "RAG_EMBEDDING_MAX_TEXTS": "100",
        "LANGSMITH_TRACING": "false",
        "LANGCHAIN_TRACING_V2": "false",
    }
    private(CONFIG, json.dumps(environment, indent=2))
    print("AI 凭据与独立服务密钥已保存至 .local/（0600），未调用模型。")
    print("已沿用来源 HTTPS 接口与地域；型号 text-embedding-v4 / 1024。")


def environment() -> dict[str, str]:
    """启动环境不从旧业务进程继承，只消费本项目明确保存的配置。"""
    if not CONFIG.exists():
        raise RuntimeError("尚未执行 init-ai")
    return {**os.environ, **json.loads(CONFIG.read_text())}


def alive() -> int | None:
    """检查 PID 的完整命令归属，防止 PID 复用后误操作其他项目。"""
    if not PID.exists():
        return None
    pid = int(PID.read_text())
    process = subprocess.run(
        ["ps", "-p", str(pid), "-o", "command="], capture_output=True, text=True
    )
    return (
        pid
        if process.returncode == 0
        and str(PYTHON) in process.stdout
        and "agentic_ai.cli serve" in process.stdout
        else None
    )


def start() -> None:
    """只启动已锁定本项目虚拟环境，不安装依赖或调用模型作为存活检查。"""
    if alive():
        print("AI 运行时已启动")
        return
    env = environment()
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        probe.bind(("127.0.0.1", PORT))
    LOGS.mkdir(exist_ok=True)
    with (LOGS / "ai-runtime.log").open("ab") as log:
        process = subprocess.Popen(
            [
                str(PYTHON),
                "-m",
                "agentic_ai.cli",
                "serve",
                "--host",
                "127.0.0.1",
                "--port",
                str(PORT),
            ],
            cwd=ROOT / "ai-runtime",
            env=env,
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
    private(PID, str(process.pid))
    deadline = time.monotonic() + 45
    last_failure = "NOT_RESPONDING"
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("AI 运行时启动退出，请查看本项目 .logs/ai-runtime.log")
        try:
            with urllib.request.urlopen(
                f"http://127.0.0.1:{PORT}/health/live", timeout=2
            ) as response:
                if json.load(response).get("status") == "alive":
                    print(f"AI 存活端点可访问：127.0.0.1:{PORT}；模型可用性需独立验证。")
                    return
        except (OSError, urllib.error.URLError) as unavailable:
            last_failure = type(unavailable).__name__
        time.sleep(0.2)
    raise RuntimeError(f"AI 存活检查超时（{last_failure}），保留进程与日志")


def stop() -> None:
    """只正常停止本项目已核实的 AI 进程，不删除配置或持久数据。"""
    pid = alive()
    if pid:
        os.kill(pid, signal.SIGTERM)
        deadline = time.monotonic() + 15
        while alive() and time.monotonic() < deadline:
            time.sleep(0.2)
        if alive():
            raise RuntimeError("AI 进程仍在退出，未强杀；请核对日志")
    print("AI 运行时已停止")
