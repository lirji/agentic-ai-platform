#!/usr/bin/env python3
"""将现有本机配置转换为本项目 Docker 制品；不初始化账号、不清理数据或重置预算。"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
from pathlib import Path
from urllib.request import Request, urlopen

HERE = Path(__file__).resolve().parent
MODULES = (
    "edge-gateway",
    "knowledge-service",
    "async-task-service",
    "conversation-service",
)
NODE_IMAGE = (
    "node:24.22.0-bookworm-slim@sha256:"
    "91acba91b842bec0bf7ea6f5e09c644c7ccad72c4996ed9616d861d1c7cd60d4"
)


def run(command: list[str], cwd: Path | None = None) -> None:
    """保留命令的真实退出码；参数中不包含凭据。"""
    subprocess.run(command, cwd=cwd, check=True)


def private(path: Path, data: str) -> None:
    """只写派生私密配置，原密钥和原账本始终由原目录持有。"""
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as stream:
        stream.write(data)
    path.chmod(0o600)


def source_contents(root: Path, revision: str | None = None) -> dict[str, bytes]:
    """正式部署从固定提交取源码，避免夹带工作区内其他任务的修改。"""
    if revision:
        archive = subprocess.check_output(["git", "archive", revision], cwd=root)
        with tarfile.open(fileobj=io.BytesIO(archive)) as source:
            result = {}
            for item in source:
                if item.isdir():
                    continue
                if (
                    not item.isfile()
                    or Path(item.name).is_absolute()
                    or ".." in Path(item.name).parts
                ):
                    raise RuntimeError("源码快照不接受链接或逃逸路径")
                result[item.name] = source.extractfile(item).read()
            return result
    raw = subprocess.check_output(["git", "ls-files", "-co", "--exclude-standard", "-z"], cwd=root)
    result = {}
    for name in sorted(set(raw.decode().strip("\0").split("\0"))):
        path = root / name
        if not path.is_file():
            continue
        if path.is_symlink():
            raise RuntimeError("源码快照不接受符号链接，请先明确归属")
        result[name] = path.read_bytes()
    return result


def prepare(args: argparse.Namespace) -> None:
    """根据真实本机 helper 生成各服务最小配置，制作可追溯的源码副本。"""
    if args.spreadsheets and not args.parsing:
        raise ValueError("表格受理需要同时启用--parsing")
    if args.ocr and not args.parsing:
        raise ValueError("OCR需要同时启用--parsing")
    if args.structured_profile == "OCR_V4" and not args.ocr:
        raise ValueError("OCR_V4受理模式需要同时启用--ocr")
    backend, frontend = args.backend.resolve(), args.frontend.resolve()
    state = backend / ".local"
    store = state / "docker-runtime"
    commits = {
        kind: subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root).decode().strip()
        for kind, root in (("backend", backend), ("frontend", frontend))
    }
    contents = {
        kind: source_contents(root, commits[kind] if args.committed_only else None)
        for kind, root in (("backend", backend), ("frontend", frontend))
    }
    files = {
        kind: {name: hashlib.sha256(data).hexdigest() for name, data in values.items()}
        for kind, values in contents.items()
    }
    options = {
        "identity": args.identity_mode,
        "sharing": args.sharing,
        "feedback": args.feedback,
        "operations": args.operations,
        "operationsPublication": args.operations_publication,
        "operationsRecovery": args.operations_recovery,
        "operationalMetrics": args.operational_metrics,
        "parsing": args.parsing,
        "ocr": args.ocr,
        "pdf": args.pdf,
        "docx": args.docx,
        "spreadsheets": args.spreadsheets,
        "memory": args.memory,
        "structuredProfile": args.structured_profile,
    }
    fingerprint = hashlib.sha256(
        json.dumps({"sources": files, "options": options}, sort_keys=True).encode()
    ).hexdigest()
    release = store / "releases" / fingerprint[:16]
    context, runtime = release / "context", release / "runtime"
    manifest_path = release / "manifest.json"
    if not manifest_path.exists():
        for kind, _root in (("backend", backend), ("frontend", frontend)):
            destination = context if kind == "backend" else context / "frontend"
            for name, digest in files[kind].items():
                data = contents[kind][name]
                if hashlib.sha256(data).hexdigest() != digest:
                    raise RuntimeError("源码在快照期间发生变化，请重新核对后准备")
                target = destination / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
        private(
            manifest_path,
            json.dumps(
                {
                    "fingerprint": fingerprint,
                    "sources": files,
                    "backend": str(backend),
                    "frontend": str(frontend),
                    "baseCommits": commits,
                    "includesUncommitted": not args.committed_only,
                    "options": options,
                },
                indent=2,
            )
            + "\n",
        )
    (context / "docker").mkdir(exist_ok=True)
    shutil.copyfile(context / "deploy/docker/web.config.ts", context / "docker/web.config.ts")
    shutil.copyfile(context / "deploy/docker/search_bridge.py", context / "docker/search_bridge.py")
    (context / ".dockerignore").write_text(
        "**/target\n**/node_modules\n**/.venv\n**/.local\n**/.logs\n**/__pycache__\n"
        "**/.pytest_cache\n**/.mypy_cache\n**/.ruff_cache\n.git\ndocs\n.engineering\n"
    )
    sys.path.insert(0, str(backend / "deploy"))
    import chat_local
    import local

    # V11 升级后不能误用尚未合入该迁移的 main 构建旧 Knowledge writer。
    database = local.load()["resources"]["dev"]["database"]
    if not re.fullmatch(r"[a-z][a-z0-9_]+", database):
        raise RuntimeError("项目数据库名称不符合已初始化约定")
    current_schema = int(
        local.mysql(
            f"SELECT COALESCE(MAX(CAST(version AS UNSIGNED)),0) "
            f"FROM `{database}`.flyway_schema_history WHERE success=1"
        ).strip()
    )
    available_schema = max(
        int(path.name.split("__")[0][1:])
        for path in (context / "knowledge-service/src/main/resources/db/migration").glob(
            "V*__*.sql"
        )
    )
    if current_schema > available_schema:
        raise RuntimeError("开发库版本高于待部署源码；请恢复兼容该迁移的代码，不启动旧 writer")

    # 解析开关由操作参数显式选择；生命周期仍读取原项目配置。
    os.environ.update(
        {
            "KNOWLEDGE_PARSING_ENABLED": str(args.parsing).lower(),
            "KNOWLEDGE_PDF_UPLOAD_ENABLED": str(args.pdf).lower(),
            "KNOWLEDGE_DOCX_UPLOAD_ENABLED": str(args.docx).lower(),
            "KNOWLEDGE_SPREADSHEET_UPLOAD_ENABLED": str(args.spreadsheets).lower(),
            "KNOWLEDGE_STRUCTURED_PROFILE": args.structured_profile,
            "CONVERSATION_MEMORY_ENABLED": str(args.memory).lower(),
        }
    )
    source_env = local.environment("dev")
    tenant = "local-dev"
    browser = {}
    if args.identity_mode == "oidc":
        import identity_local

        identity = identity_local.read_private(state / "identity/state.json")
        registration = identity_local.read_private(state / "identity/registration-result.json")
        if (
            registration.get("status") != "PASS"
            or identity.get("organization") != identity_local.ORGANIZATION
        ):
            raise RuntimeError("必须复用已验收的专属IAM身份登记")
        tenant = identity_local.ORGANIZATION
        browser = {
            "BROWSER_IDENTITY_ENABLED": "true",
            "BROWSER_IDENTITY_ISSUER": identity_local.ISSUER,
            "BROWSER_IDENTITY_ORIGIN": "http://localhost:28086",
            "BROWSER_IDENTITY_CLIENT_ID": identity_local.CLIENT,
            "BROWSER_IDENTITY_CLIENT_SECRET": identity["clientSecret"],
            "BROWSER_IDENTITY_ORGANIZATION": tenant,
            "BROWSER_IDENTITY_TENANT": tenant,
            "BROWSER_IDENTITY_MEMBERS": ",".join(
                identity["users"][name]["id"] for name in identity_local.USERS[:2]
            ),
            "BROWSER_IDENTITY_MAX_IDLE": "15m",
        }

    import sharing_local

    source_env.update(sharing_local.environment(browser, args.sharing))
    import feedback_local

    source_env.update(feedback_local.environment(source_env, args.feedback))
    import operations_local

    source_env.update(
        operations_local.environment(
            args.operations, args.operations_publication, args.operations_recovery
        )
    )

    def transform(value: str, service: str) -> str:
        """仅替换本项目已知地址；内部服务保留共享网络内 loopback。"""
        value = value.replace("127.0.0.1:43306", "dev-infra-mysql84-1:3306")
        value = value.replace("127.0.0.1:49000", "dev-infra-minio-1:9000")
        value = value.replace("127.0.0.1:45432", "dev-infra-postgres16-1:5432")
        if str(state) + "/" in value:
            name = value.split(str(state) + "/", 1)[1]
            if "/" in name or not name.endswith(".pem"):
                raise RuntimeError("未知私密文件映射，未复制")
            source = state / name
            if source.is_symlink() or source.stat().st_mode & 0o077:
                raise RuntimeError("原密钥权限或路径不符合要求")
            private(runtime / service / name, source.read_text())
            value = value.replace(str(state), "/run/agentic")
        return value

    def env_file(service: str, values: dict[str, str]) -> None:
        if any("\n" in value or "\r" in value for value in values.values()):
            raise RuntimeError("环境值含换行，拒绝写入 raw env 文件")
        private(
            runtime / service / "service.env",
            "".join(f"{key}={value}\n" for key, value in sorted(values.items())),
        )

    owners = {
        "knowledge": "KNOWLEDGE_",
        "tasks": "TASK_",
        "conversations": "CONVERSATION_",
    }
    for service in ("gateway", *owners):
        if service == "gateway":
            selected = {
                key: value
                for key, value in source_env.items()
                if key.startswith(("GATEWAY_", "IAM_", "INTERNAL_"))
                or key
                in {
                    "KNOWLEDGE_SERVICE_URL",
                    "TASK_SERVICE_URL",
                    "CONVERSATION_SERVICE_URL",
                }
            }
            selected["GATEWAY_BIND_ADDRESS"] = "0.0.0.0"
            selected.update(browser)
        else:
            selected = {
                key: value
                for key, value in source_env.items()
                if key.startswith(owners[service]) or key == "INTERNAL_PUBLIC_KEY"
            }
        if service in {"knowledge", "tasks"}:
            selected["OPERATIONS_METRICS_ENABLED"] = str(args.operational_metrics).lower()
        if service == "knowledge":
            selected.update(
                {
                    "KNOWLEDGE_PARSER_JAVA": "/opt/java/openjdk/bin/java",
                    "KNOWLEDGE_PARSER_JAR": "/app/knowledge-parser.jar",
                    "KNOWLEDGE_QDRANT_ENDPOINT": "http://127.0.0.1:26333",
                    "KNOWLEDGE_ELASTIC_ENDPOINT": "http://127.0.0.1:29200",
                    "KNOWLEDGE_WORKER_TENANT": tenant,
                    "KNOWLEDGE_EVALUATION_WORKER_TENANT": tenant,
                }
            )
        if service == "knowledge":
            # 宿主OCR绝对路径不能带入Linux容器；能力与镜像一起冻结，关闭时也移除继承值。
            selected.pop("KNOWLEDGE_OCR_EXECUTABLE", None)
            selected.pop("KNOWLEDGE_OCR_DATA", None)
            if args.ocr:
                selected.update(
                    KNOWLEDGE_OCR_EXECUTABLE="/opt/ocr/bin/tesseract",
                    KNOWLEDGE_OCR_DATA="/opt/ocr/tessdata",
                )
        if service == "conversations":
            selected["CONVERSATION_WORKER_TENANT"] = tenant
        selected["OTEL_EXPORT_ENABLED"] = "false"
        env_file(service, {key: transform(value, service) for key, value in selected.items()})

    ai_env = {
        key: transform(str(value), "ai")
        for key, value in json.loads((state / "ai-runtime.json").read_text()).items()
    }
    ai_env.update(
        AI_EMBEDDING_BUDGET_FILE="/run/budgets/embedding.json",
        AI_EMBEDDING_BUDGET_LIMIT_CNY="1",
    )
    env_file("ai", ai_env)
    proxy_env = {
        key: value
        for key, value in chat_local.environment(proxy=True).items()
        if key not in {"PATH", "HOME", "TMPDIR", "LANG", "PYTHONPATH"}
    }
    proxy_env["PYTHONPATH"] = "/app"
    env_file("model-proxy", proxy_env)

    worker = json.loads((state / "qa-worker.json").read_text())
    if worker["tenant_id"] != "local-dev":
        raise RuntimeError("仅支持已授权的 local-dev worker")
    # 仅派生配置切换到已登记租户，不改旧数据所有者或原宿主配置。
    worker["tenant_id"] = tenant
    for key in ("task_private_key", "knowledge_private_key", "evaluation_private_key"):
        if worker.get(key):
            worker[key] = transform(worker[key], "qa-worker")
    worker["chat_config"] = "/run/agentic/chat.json"
    worker["checkpoints"]["dsn"] = transform(worker["checkpoints"]["dsn"], "qa-worker")
    chat = json.loads((state / "chat.json").read_text())
    chat["budget_file"] = "/run/budgets/chat.json"
    private(runtime / "qa-worker/qa-worker.json", json.dumps(worker) + "\n")
    private(runtime / "qa-worker/chat.json", json.dumps(chat) + "\n")
    (runtime / "web").mkdir(parents=True, exist_ok=True, mode=0o700)
    if args.identity_mode == "local":
        private(
            runtime / "web/.local/iam-test-private.pem",
            (state / "iam-test-private.pem").read_text(),
        )
    for name in ("embedding-budget-20261020.json", "chat-budget-20261020.json"):
        ledger = state / name
        if not ledger.is_file() or ledger.is_symlink() or ledger.stat().st_mode & 0o077:
            raise RuntimeError("必须沿用现存私密预算账本")
    settings = {
        "RELEASE_ID": fingerprint[:16],
        "SOURCE_FINGERPRINT": fingerprint,
        "BUILD_CONTEXT": str(context),
        "RUNTIME_DIR": str(runtime),
        "DOCKER_FILES": str(context / "deploy/docker"),
        "KNOWLEDGE_DOCKERFILE": "Ocr.Dockerfile" if args.ocr else "Java.Dockerfile",
        "IDENTITY_MODE": args.identity_mode,
        "LOCAL_UID": str(os.getuid()),
        "LOCAL_GID": str(os.getgid()),
        "NODE_IMAGE": NODE_IMAGE,
        "EMBEDDING_LEDGER": str(state / "embedding-budget-20261020.json"),
        "CHAT_LEDGER": str(state / "chat-budget-20261020.json"),
    }
    private(
        store / "compose.env",
        "".join(f"{key}={value}\n" for key, value in settings.items()),
    )
    private(
        store / "current.json",
        json.dumps(
            {
                **settings,
                "backend": str(backend),
                "frontend": str(frontend),
                "manifest": str(manifest_path),
            },
            indent=2,
        )
        + "\n",
    )
    print(f"源码快照 {fingerprint[:16]} 已准备；原工作区/预算未更改。")


def compose(state: Path, *arguments: str) -> list[str]:
    """固定项目和配置，避免从不同工作目录误操作其他 Compose。"""
    settings = json.loads((state / "current.json").read_text())
    override = []
    if arguments and arguments[0] == "up":
        # 校验完成后仍使用不可变ID，避免标签在核对与启动之间变化。
        receipt = json.loads((state / "artifacts.json").read_text())
        pinned = state / "pinned-images.json"
        private(
            pinned,
            json.dumps(
                {
                    "services": {
                        name: {"image": item["imageId"]}
                        for name, item in receipt["services"].items()
                    }
                }
            ),
        )
        override = ["-f", str(pinned)]
    return [
        "docker",
        "compose",
        "--env-file",
        str(state / "compose.env"),
        "-f",
        str(Path(settings["DOCKER_FILES"]) / "compose.yaml"),
        *override,
        *arguments,
    ]


def service_images(state: Path) -> dict[str, str]:
    """Compose展开可能含凭据，仅在内存提取服务镜像映射，禁止打印原配置。"""
    resolved = json.loads(subprocess.check_output(compose(state, "config", "--format", "json")))
    return {name: service["image"] for name, service in resolved["services"].items()}


def verify_artifacts(state: Path) -> None:
    """旧成功标记不足以证明当前标签，必须有匹配的不可变ID收据。"""
    import release_artifacts

    receipt = state / "artifacts.json"
    if not receipt.is_file():
        raise RuntimeError(release_artifacts.ReleaseError.RECEIPT_MISSING)
    settings = json.loads((state / "current.json").read_text())
    release_artifacts.verify(settings, service_images(state), json.loads(receipt.read_text()))


def build(state: Path) -> None:
    """只在隔离源码快照构建，不能覆盖宿主仍在使用的 fat JAR。"""
    settings = json.loads((state / "current.json").read_text())
    import release_artifacts

    release_artifacts.verify_source(settings)
    context = Path(settings["BUILD_CONTEXT"])
    run(["mvn", "-B", "-ntp", "verify"], context)
    (context / "jars").mkdir(exist_ok=True)
    for module in (*MODULES, "knowledge-parser"):
        shutil.copyfile(
            context / module / "target" / f"{module}-0.1.0-SNAPSHOT.jar",
            context / "jars" / f"{module}.jar",
        )
    run(compose(state, "config", "--quiet"))
    bake = state / "build-bake.json"
    with bake.open("wb") as output:
        subprocess.run(compose(state, "build", "--print"), stdout=output, check=True)
    run(["docker", "buildx", "bake", "--file", str(bake), "--load"])
    private(
        state / "artifacts.json",
        json.dumps(release_artifacts.record(settings, service_images(state)), indent=2) + "\n",
    )
    private(
        state / "build-result.json",
        json.dumps(
            {
                "sourceFingerprint": settings["SOURCE_FINGERPRINT"],
                "java": "PASS",
                "images": "PASS",
            },
            indent=2,
        )
        + "\n",
    )


def smoke(state: Path) -> None:
    """只读取页面和已有业务，不上传、不检索 Embedding、不发问答。"""
    checks = {}
    if json.loads((state / "current.json").read_text()).get("IDENTITY_MODE") == "oidc":
        raise RuntimeError("正式登录模式请执行真实浏览器只读验收；不使用本地身份smoke")
    base = "http://127.0.0.1:28086"
    for path in (
        "/",
        "/knowledge",
        "/quality",
        "/api/local-session",
        "/api/v1/knowledge/documents?limit=1",
        "/api/v2/knowledge/documents?limit=1",
        "/api/v1/conversations?limit=1",
        "/api/v1/tasks?kind=RESEARCH&limit=1",
        "/api/v1/tasks?kind=READONLY_ASSISTANT&limit=1",
        "/api/v1/knowledge/evaluations/datasets?limit=1",
    ):
        request = Request(base + path, headers={"X-Workbench-Request": "1", "Origin": base})
        with urlopen(request, timeout=15) as response:
            if response.status != 200:
                raise RuntimeError("只读页面/接口验证失败")
            payload = response.read()
            if path.startswith("/api/"):
                json.loads(payload)
            checks[path] = "PASS"
    private(state / "smoke-result.json", json.dumps(checks, indent=2) + "\n")
    print(f"{len(checks)} 项只读页面/业务检查通过：{base}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "action",
        choices=(
            "prepare",
            "build",
            "up",
            "status",
            "stop",
            "smoke",
            "validation-prepare",
            "validation-up",
            "validation-stop",
            "validation-status",
            "validation-pause-original",
            "validation-restore-original",
        ),
    )
    parser.add_argument("--backend", type=Path, required=True)
    parser.add_argument("--frontend", type=Path)
    parser.add_argument("--parsing", action="store_true")
    parser.add_argument("--ocr", action="store_true", help="构建固定中英OCR引擎，需--parsing")
    parser.add_argument("--pdf", action="store_true")
    parser.add_argument("--docx", action="store_true")
    parser.add_argument("--spreadsheets", action="store_true", help="启用XLSX/CSV受理，需--parsing")
    parser.add_argument("--memory", action="store_true", help="启用个人记忆新建与确认，默认关闭")
    parser.add_argument("--identity-mode", choices=("local", "oidc"), default="local")
    parser.add_argument("--sharing", action="store_true", help="仅与oidc一起启用已验收的只读共享")
    parser.add_argument("--feedback", action="store_true", help="启用所有者反馈及任务引用证明")
    parser.add_argument(
        "--operations", action="store_true", help="启用所有者候选修订；不会自动开启处理worker"
    )
    parser.add_argument(
        "--operations-publication",
        action="store_true",
        help="显式启用候选批准入库；要求同时指定--operations",
    )
    parser.add_argument(
        "--operations-recovery",
        action="store_true",
        help="显式启用发布后检查与补偿；要求同时指定--operations",
    )
    parser.add_argument(
        "--operational-metrics", action="store_true", help="显式启用只读管理指标；不启动采集或外发"
    )
    parser.add_argument("--validation-id", help="复用8位十六进制隔离验证ID，不操作原开发项目")
    parser.add_argument("--committed-only", action="store_true")
    parser.add_argument(
        "--structured-profile", choices=("BLOCK_V2", "STRUCTURE_V3", "OCR_V4"), default="BLOCK_V2"
    )
    args = parser.parse_args()
    state = args.backend.resolve() / ".local/docker-runtime"
    if args.action.startswith("validation-"):
        import validation_runtime

        if not args.validation_id:
            parser.error("validation操作需要--validation-id")
        validation_runtime.action(args.action.removeprefix("validation-"), args.validation_id)
        return
    if args.action == "prepare":
        if args.frontend is None:
            parser.error("prepare 必须提供 --frontend")
        prepare(args)
    elif args.action == "build":
        build(state)
    elif args.action == "smoke":
        smoke(state)
    elif args.action == "up":
        settings = json.loads((state / "current.json").read_text())
        result = json.loads((state / "build-result.json").read_text())
        if result["sourceFingerprint"] != settings["SOURCE_FINGERPRINT"]:
            raise RuntimeError("构建结果不属于当前快照")
        verify_artifacts(state)
        sys.path.insert(0, str(args.backend.resolve() / "deploy"))
        import ai_local
        import chat_local
        import local

        if (
            any(local.alive(service) for service in local.SERVICES)
            or ai_local.alive()
            or any(chat_local.alive(service) for service in ("qa-worker", "model-proxy"))
        ):
            raise RuntimeError("宿主旧应用仍运行，请正常停止后再切换，不能同时启动两套 writer")
        run(
            compose(
                state,
                "up",
                "-d",
                "--no-build",
                "--force-recreate",
                "--wait",
                "--wait-timeout",
                "240",
            )
        )
    elif args.action == "status":
        run(compose(state, "ps"))
    else:
        # 普通停止不删除容器/卷/网络；保持失败现场和恢复能力。
        run(compose(state, "stop"))


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode) from None
