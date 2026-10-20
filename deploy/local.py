#!/usr/bin/env python3
"""本地独立资源与进程入口；只创建 agentic 资源，不停止其他项目或清理数据库。"""

from __future__ import annotations

import argparse
import base64
import json
import os
import secrets
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from http import HTTPStatus
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / ".local"
LOGS = ROOT / ".logs"
CONFIG = STATE / "runtime.json"
MYSQL = "dev-infra-mysql84-1"
MINIO = "dev-infra-minio-1"
MC_IMAGE = "minio/mc:RELEASE.2025-08-13T08-35-41Z"
NETWORK = "dev-infra"
NAMES = {
    "dev": ("agentic_knowledge", "agentic_app", "agentic_migrate", "agentic-ai-knowledge"),
    "test": (
        "agentic_knowledge_test",
        "agentic_test_app",
        "agentic_test_migrate",
        "agentic-ai-knowledge-test",
    ),
}
SERVICES = {
    "knowledge": ("knowledge-service", 28084, 29084),
    "gateway": ("edge-gateway", 28080, 29080),
}


def run(args: list[str], *, data: bytes | None = None, env: dict | None = None) -> bytes:
    """失败只输出命令名称，避免命令参数、SQL 或上游输出泄漏凭据。"""
    result = subprocess.run(args, input=data, env=env, capture_output=True, cwd=ROOT, timeout=90)
    if result.returncode:
        raise RuntimeError(
            f"{Path(args[0]).name} 执行失败（退出码 {result.returncode}），未输出敏感参数"
        )
    return result.stdout


def private(path: Path, value: str) -> None:
    """新文件从创建起即仅当前用户可读，已有私密状态不进入 Git。"""
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, "w") as stream:
        stream.write(value)


def mysql(sql: str) -> str:
    """管理员密码仅在共享 MySQL 容器内部展开，不出现在宿主命令参数。"""
    return run(
        [
            "docker",
            "exec",
            "-i",
            MYSQL,
            "sh",
            "-c",
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --batch --skip-column-names -uroot',
        ],
        data=sql.encode(),
    ).decode()


def minio(script: str, extra: dict[str, str] | None = None) -> bytes:
    """读取目标实例的管理环境，仅传给临时 mc 进程，不记录或返回原始环境。"""
    info = json.loads(run(["docker", "inspect", MINIO]))[0]
    admin = dict(item.split("=", 1) for item in info["Config"]["Env"] if "=" in item)
    env = os.environ.copy()
    env.update({name: admin[name] for name in ("MINIO_ROOT_USER", "MINIO_ROOT_PASSWORD")})
    env.update(extra or {})
    args = ["docker", "run", "--rm", "-i", "--network", NETWORK, "--entrypoint", "/bin/sh"]
    for name in ("MINIO_ROOT_USER", "MINIO_ROOT_PASSWORD", *(extra or {})):
        args.extend(["-e", name])
    prefix = (
        "set -eu\nmc alias set infra http://infra-minio:9000 "
        '"$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null\n'
    )
    return run([*args, MC_IMAGE, "-c", prefix + script], env=env)


def load() -> dict:
    """没有私有运行记录时不猜测既有资源所有权。"""
    if not CONFIG.exists():
        raise RuntimeError("请先运行 python3 deploy/local.py init")
    if CONFIG.stat().st_mode & 0o077:
        raise RuntimeError("私有运行记录权限必须为 0600")
    return json.loads(CONFIG.read_text())


def init() -> None:
    """先核查未占用的命名空间，再持久化凭据；中断后按同一状态幂等恢复。"""
    STATE.mkdir(mode=0o700, parents=True, exist_ok=True)
    if CONFIG.exists():
        config = load()
    else:
        for database, app, migrator, bucket in NAMES.values():
            occupied = mysql(
                f"SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA "
                f"WHERE SCHEMA_NAME='{database}'; "
                f"SELECT User FROM mysql.user WHERE User IN ('{app}','{migrator}');"
            )
            if occupied.strip():
                raise RuntimeError("计划的数据库或账号已存在但没有本项目所有权记录；停止初始化")
            found = minio(
                'if mc stat "infra/$PROJECT_BUCKET" >/dev/null 2>&1; then echo occupied; fi\n'
                'if mc admin user info infra "$PROJECT_USER" >/dev/null 2>&1; '
                "then echo occupied; fi\n"
                'if mc admin policy info infra "$PROJECT_BUCKET" >/dev/null 2>&1; '
                "then echo occupied; fi\n",
                {"PROJECT_BUCKET": bucket, "PROJECT_USER": app},
            )
            if found.strip():
                raise RuntimeError("计划的存储桶或账号已存在但没有本项目所有权记录；停止初始化")
        config = {"version": 1, "resources": {}}
        for mode, (database, app, migrator, bucket) in NAMES.items():
            config["resources"][mode] = {
                "database": database,
                "app": app,
                "migrator": migrator,
                "bucket": bucket,
                "app_password": secrets.token_hex(24),
                "migration_password": secrets.token_hex(24),
                "s3_password": secrets.token_hex(24),
            }
        private(CONFIG, json.dumps(config, indent=2))
    for mode, names in NAMES.items():
        entry = config["resources"][mode]
        if tuple(entry[key] for key in ("database", "app", "migrator", "bucket")) != names:
            raise RuntimeError("运行记录中的资源名称与本项目白名单不符")
        database, app, migrator, bucket = names
        mysql(
            f"CREATE DATABASE IF NOT EXISTS `{database}` "
            "CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;\n"
            f"CREATE USER IF NOT EXISTS '{app}'@'%' IDENTIFIED BY '{entry['app_password']}';\n"
            f"CREATE USER IF NOT EXISTS '{migrator}'@'%' "
            f"IDENTIFIED BY '{entry['migration_password']}';\n"
            f"GRANT SELECT,INSERT,UPDATE,DELETE ON `{database}`.* TO '{app}'@'%';\n"
            f"GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES ON `{database}`.* "
            f"TO '{migrator}'@'%';\n"
        )
        policy = {
            "Version": "2012-10-17",
            "Statement": [
                {
                    "Effect": "Allow",
                    "Action": ["s3:GetBucketLocation", "s3:ListBucket"],
                    "Resource": [f"arn:aws:s3:::{bucket}"],
                },
                {
                    "Effect": "Allow",
                    "Action": ["s3:GetObject", "s3:PutObject"],
                    "Resource": [f"arn:aws:s3:::{bucket}/*"],
                },
            ],
        }
        minio(
            'mc mb --ignore-existing "infra/$PROJECT_BUCKET" >/dev/null\n'
            'if ! mc admin user info infra "$PROJECT_USER" >/dev/null 2>&1; then\n'
            '  mc admin user add infra "$PROJECT_USER" "$PROJECT_PASSWORD" >/dev/null\nfi\n'
            'printf "%s" "$PROJECT_POLICY" >/tmp/policy.json\n'
            'mc admin policy create infra "$PROJECT_BUCKET" /tmp/policy.json >/dev/null\n'
            'mc admin policy attach infra "$PROJECT_BUCKET" --user "$PROJECT_USER" >/dev/null\n',
            {
                "PROJECT_BUCKET": bucket,
                "PROJECT_USER": app,
                "PROJECT_PASSWORD": entry["s3_password"],
                "PROJECT_POLICY": json.dumps(policy),
            },
        )
    for name in ("iam-test", "internal"):
        key = STATE / f"{name}-private.pem"
        public = STATE / f"{name}-public.pem"
        if not key.exists():
            private(
                key,
                run(
                    ["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048"]
                ).decode(),
            )
        if not public.exists():
            private(public, run(["openssl", "pkey", "-in", str(key), "-pubout"]).decode())
    print("独立 dev/test 数据库、分权账号、原文桶与测试密钥就绪；凭据仅存 .local/。")


def environment(mode: str = "dev") -> dict[str, str]:
    """只输出给子进程，不提供打印环境命令；测试使用独立数据库和存储桶。"""
    resource = load()["resources"][mode]
    env = os.environ.copy()
    env.update(
        {
            "KNOWLEDGE_DB_URL": f"jdbc:mysql://127.0.0.1:43306/{resource['database']}"
            "?sslMode=DISABLED&allowPublicKeyRetrieval=true",
            "KNOWLEDGE_DB_USER": resource["app"],
            "KNOWLEDGE_DB_PASSWORD": resource["app_password"],
            "KNOWLEDGE_MIGRATION_USER": resource["migrator"],
            "KNOWLEDGE_MIGRATION_PASSWORD": resource["migration_password"],
            "KNOWLEDGE_S3_ENDPOINT": "http://127.0.0.1:49000",
            "KNOWLEDGE_S3_REGION": "us-east-1",
            "KNOWLEDGE_S3_BUCKET": resource["bucket"],
            "KNOWLEDGE_S3_ACCESS_KEY": resource["app"],
            "KNOWLEDGE_S3_SECRET_KEY": resource["s3_password"],
            "IAM_ISSUER": "urn:agentic-ai:local-test",
            "IAM_AUDIENCE": "agentic-ai-api",
            "IAM_TENANT_CLAIM": "tenant_id",
            "IAM_PUBLIC_KEY": (STATE / "iam-test-public.pem").as_uri(),
            "INTERNAL_PUBLIC_KEY": (STATE / "internal-public.pem").as_uri(),
            "INTERNAL_PRIVATE_KEY": (STATE / "internal-private.pem").as_uri(),
            "OTEL_EXPORT_ENABLED": "false",
            "KNOWLEDGE_BIND_ADDRESS": "127.0.0.1",
            "GATEWAY_BIND_ADDRESS": "127.0.0.1",
            "KNOWLEDGE_PORT": "28084",
            "GATEWAY_PORT": "28080",
            "KNOWLEDGE_MANAGEMENT_PORT": "29084",
            "GATEWAY_MANAGEMENT_PORT": "29080",
            "KNOWLEDGE_SERVICE_URL": "http://127.0.0.1:28084",
        }
    )
    import search_local

    if search_local.CONFIG.exists():
        env.update(search_local.environment(mode))
    return env


def token(
    tenant: str, user: str, scope: str, *, claims: dict | None = None, internal: bool = False
) -> str:
    """本地测试签名器；外部 IAM 与内部服务使用不同密钥，不提供免验证旁路。"""

    def encode(value: bytes) -> str:
        return base64.urlsafe_b64encode(value).decode().rstrip("=")

    now = int(time.time())
    payload = {
        "iss": "urn:agentic-ai:edge" if internal else "urn:agentic-ai:local-test",
        "aud": "agentic-ai-knowledge" if internal else "agentic-ai-api",
        "sub": user,
        "tenant_id": tenant,
        "scope": scope,
        "iat": now,
        "nbf": now,
        "exp": now + 900,
    }
    payload.update(claims or {})
    data = (
        encode(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
        + "."
        + encode(json.dumps(payload).encode())
    ).encode()
    key = STATE / ("internal-private.pem" if internal else "iam-test-private.pem")
    signature = run(["openssl", "dgst", "-sha256", "-sign", str(key)], data=data)
    return data.decode() + "." + encode(signature)


def alive(service: str) -> int | None:
    """PID 复用时以完整项目 jar 路径核查身份，禁止误杀其他进程。"""
    path = STATE / f"{service}.pid"
    if not path.exists():
        return None
    pid = int(path.read_text())
    result = subprocess.run(
        ["ps", "-p", str(pid), "-o", "command="], capture_output=True, text=True
    )
    expected = str(ROOT / SERVICES[service][0] / "target")
    return pid if result.returncode == 0 and expected in result.stdout else None


def wait_ready(port: int, process: subprocess.Popen | None = None) -> None:
    """轮询真实 readiness；超时保留进程与日志供诊断，不猜测已启动。"""
    deadline = time.monotonic() + 60
    last_failure = "NOT_READY"
    while time.monotonic() < deadline:
        if process is not None and process.poll() is not None:
            raise RuntimeError("服务启动退出，请查看 .logs 中对应日志")
        try:
            with urllib.request.urlopen(
                f"http://127.0.0.1:{port}/actuator/health/readiness", timeout=3
            ) as response:
                if response.status == HTTPStatus.OK and json.load(response)["status"] == "UP":
                    return
                last_failure = "DEPENDENCY_NOT_READY"
        except (OSError, urllib.error.URLError) as unavailable:
            # 启动阶段连接拒绝属于预期；保留安全类型，超时必须报告而不是吞掉失败。
            last_failure = type(unavailable).__name__
        time.sleep(0.3)
    raise RuntimeError(f"等待 readiness 超时（{last_failure}），保留进程及日志供诊断")


def start(service: str | None = None) -> None:
    """只启动本项目已构建的 jar；端口被其他进程占用时直接失败。"""
    env = environment()
    LOGS.mkdir(exist_ok=True)
    for name in [service] if service else SERVICES:
        if alive(name):
            print(f"{name} 已运行")
            continue
        module, port, management = SERVICES[name]
        for target in (port, management):
            with socket.socket() as probe:
                # 已关闭监听器可能留下 TIME_WAIT；与 Java 服务相同地允许复用，但仍拒绝活跃监听。
                probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                try:
                    probe.bind(("127.0.0.1", target))
                except OSError as failure:
                    raise RuntimeError(f"端口 {target} 已占用，未停止任何已有服务") from failure
        jar = ROOT / module / "target" / f"{module}-0.1.0-SNAPSHOT.jar"
        if not jar.exists():
            raise RuntimeError("缺少制品，请先运行 mvn -B -ntp package")
        with (LOGS / f"{name}.log").open("ab") as log:
            process = subprocess.Popen(
                ["java", "-Xms64m", "-Xmx384m", "-XX:ActiveProcessorCount=4", "-jar", str(jar)],
                stdout=log,
                stderr=subprocess.STDOUT,
                cwd=ROOT,
                env=env,
                start_new_session=True,
            )
        private(STATE / f"{name}.pid", str(process.pid))
        wait_ready(management, process)
        print(f"{name} ready：127.0.0.1:{port}")


def stop(service: str | None = None) -> None:
    """正常 SIGTERM 关闭本项目进程；不强杀、不停止 dev-infra、不删除资料。"""
    for name in [service] if service else reversed(SERVICES):
        pid = alive(name)
        if pid is None:
            continue
        os.kill(pid, signal.SIGTERM)
        deadline = time.monotonic() + 20
        while alive(name) and time.monotonic() < deadline:
            time.sleep(0.2)
        if alive(name):
            raise RuntimeError(f"{name} 未在期限内退出，未强制终止")
        print(f"{name} 已停止，数据保留")


def main() -> None:
    """入口默认不打印凭据，token 命令仅生成受保护文件。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command",
        choices=[
            "init",
            "start",
            "stop",
            "status",
            "token",
            "test",
            "init-ai",
            "start-ai",
            "stop-ai",
            "init-search",
            "stop-search",
        ],
    )
    parser.add_argument("--service", choices=SERVICES)
    parser.add_argument("--reference-env", type=Path, help="已获授权的来源模型环境文件")
    args = parser.parse_args()
    if args.command in {"init-search", "stop-search"}:
        import search_local

        if args.command == "init-search":
            search_local.init()
        else:
            search_local.compose("stop")
    elif args.command in {"init-ai", "start-ai", "stop-ai"}:
        import ai_local

        if args.command == "init-ai":
            if args.reference_env is None:
                parser.error("init-ai 需要 --reference-env")
            ai_local.init(args.reference_env)
        elif args.command == "start-ai":
            ai_local.start()
        else:
            ai_local.stop()
    elif args.command == "init":
        init()
    elif args.command == "start":
        start(args.service)
    elif args.command == "stop":
        stop(args.service)
    elif args.command == "status":
        import ai_local

        for name in SERVICES:
            print(f"{name}: {'running' if alive(name) else 'stopped'}")
        print(f"ai-runtime: {'running' if ai_local.alive() else 'stopped'}")
    elif args.command == "token":
        load()
        private(
            STATE / "test-token", token("local-dev", "developer", "knowledge:read knowledge:write")
        )
        print("本地测试令牌已写入 .local/test-token，有效期 15 分钟；勿提交或转发。")
    else:
        result = subprocess.run(
            ["mvn", "-B", "-ntp", "-Pintegration", "verify"], cwd=ROOT, env=environment("test")
        )
        raise SystemExit(result.returncode)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.TimeoutExpired) as error:
        # TimeoutExpired 可能携带 argv，因此只打印自行定义的安全消息。
        print(
            str(error) if isinstance(error, RuntimeError) else "本地命令超时，未输出参数",
            file=sys.stderr,
        )
        raise SystemExit(1) from None
