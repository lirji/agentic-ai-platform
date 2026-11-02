"""完整固定制品的隔离本机验证；沿原Compose派生，不更新原Docker运行入口。"""

import hashlib
import json
import os
import shutil
import subprocess
from enum import StrEnum
from http import HTTPStatus
from pathlib import Path

import release_artifacts
import validation_resources

ROOT = Path(__file__).resolve().parents[2]
FRONTEND = ROOT.parent / "agentic-ai-platform-web"
BASE_RECEIPT = ROOT / "docs/deployment/docker-latest-20261102/deployment-result.json"
PROTOCOL = "release-validation-v1"
WEB_PORT = 28096
GATEWAY_PORT = 28090
WEB_BUILD_TIMEOUT_SECONDS = 300


class ValidationPhase(StrEnum):
    """可恢复运行阶段；显式序列化值与既有私密收据兼容。"""

    INIT = "INIT"
    RESOURCES_READY = "RESOURCES_READY"
    WEB_BUILT = "WEB_BUILT"
    CONFIG_READY = "CONFIG_READY"
    RUNNING = "RUNNING"
    STOPPED = "STOPPED"
    VERIFIED = "VERIFIED"


class OriginalPhase(StrEnum):
    """获准短暂停服后的恢复状态独立记录，不改变隔离验证阶段。"""

    STOPPING = "STOPPING"
    STOPPED = "STOPPED"
    RESTORING = "RESTORING"
    RESTORED = "RESTORED"


def private(path: Path, value: str) -> None:
    """共用既有0600写入机制，所有派生secret只留在忽略目录。"""
    validation_resources.local.private(path, value)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(directory: Path) -> dict:
    """拒绝错误归属、公开权限及配置位置；不能操作原开发project。"""
    validation_resources.names(directory.name)
    path = directory / "state.json"
    if path.is_symlink() or path.stat().st_mode & 0o077:
        raise RuntimeError(validation_resources.ValidationError.VALIDATION_STATE_INVALID)
    value = json.loads(path.read_text())
    if value.get("protocol") != PROTOCOL or directory.name != value.get("id"):
        raise RuntimeError(validation_resources.ValidationError.VALIDATION_STATE_INVALID)
    if directory.resolve() != (ROOT / ".local/release-validation" / value["id"]).resolve():
        raise RuntimeError(validation_resources.ValidationError.VALIDATION_STATE_INVALID)
    if value["project"] != "agentic-s17-" + value["id"]:
        raise RuntimeError(validation_resources.ValidationError.VALIDATION_PROJECT_INVALID)
    return value


def save(directory: Path, state: dict) -> None:
    private(directory / "state.json", json.dumps(state, indent=2) + "\n")


def compose(directory: Path, *args: str) -> list[str]:
    state = load(directory)
    return [
        "docker",
        "compose",
        "-p",
        state["project"],
        "-f",
        str(directory / "compose.json"),
        *args,
    ]


def run_logged(command: list[str], directory: Path, name: str) -> None:
    """长输出只进本次日志；不打印可能含凭据的展开配置或环境。"""
    with (directory / name).open("ab") as log:
        subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)


def base_images(receipt: dict) -> dict:
    """消费已验收的逐镜像ID，旧标签被移动时立即拒绝。"""
    if receipt.get("status") != "PASS" or receipt.get("includesUncommitted"):
        raise RuntimeError(validation_resources.ValidationError.VALIDATION_BASE_UNVERIFIED)
    result = {}
    for item in receipt["containers"]:
        service = item["service"].removeprefix(receipt["project"] + "-").removesuffix("-1")
        actual = release_artifacts.image_fact(item["image"])
        if (
            actual["imageId"] != item["imageId"]
            or actual["sourceFingerprint"] != receipt["sourceFingerprint"]
        ):
            raise RuntimeError(validation_resources.ValidationError.VALIDATION_BASE_IMAGE_CHANGED)
        result[service] = actual
    if set(result) != release_artifacts.SERVICES:
        raise RuntimeError(validation_resources.ValidationError.VALIDATION_BASE_SERVICES_INVALID)
    return result


def compile_web(directory: Path, context: Path, record: dict) -> dict:
    """宿主对冻结源码编译；固定Node/依赖版本，避免挤占共享Docker内存。"""
    # 当前pnpm12锁的末段是安装锁，核对传递依赖而不只比较顶层版本。
    active_lock = (context / "frontend/pnpm-lock.yaml").read_text().rsplit("\n---\n", 1)[-1].strip()
    installed_lock = (FRONTEND / "node_modules/.pnpm/lock.yaml").read_text().strip()
    if active_lock != installed_lock:
        raise RuntimeError(
            validation_resources.ValidationError.VALIDATION_WEB_HOST_DEPENDENCIES_CHANGED
        )
    receipt_path = directory / "web-bundle.json"
    if receipt_path.exists():
        bundle = json.loads(receipt_path.read_text())
        if bundle["sourceFingerprint"] != record["fingerprint"]:
            raise RuntimeError(
                validation_resources.ValidationError.VALIDATION_WEB_BUNDLE_SOURCE_CHANGED
            )
        for name, expected in bundle["files"].items():
            if digest(context / "built-dist" / name) != expected:
                raise RuntimeError(
                    validation_resources.ValidationError.VALIDATION_WEB_BUNDLE_CHANGED
                )
        return bundle
    node = FRONTEND / ".local/node-v24.22.0-darwin-arm64/bin/node"
    if subprocess.check_output([str(node), "--version"], text=True).strip() != "v24.22.0":
        raise RuntimeError(validation_resources.ValidationError.VALIDATION_WEB_NODE_CHANGED)
    package = json.loads((context / "frontend/package.json").read_text())
    modules = FRONTEND / "node_modules"
    dependencies = {**package["dependencies"], **package["devDependencies"]}
    for name, version in dependencies.items():
        installed = json.loads((modules / name / "package.json").read_text())
        if installed["version"] != version:
            raise RuntimeError(
                validation_resources.ValidationError.VALIDATION_WEB_HOST_DEPENDENCIES_CHANGED
            )
    compile_context = directory / "web-compile-context"
    for name in record["files"]:
        destination = compile_context / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(context / "frontend" / name, destination)
    module_link = compile_context / "node_modules"
    if not module_link.exists():
        module_link.symlink_to(modules, target_is_directory=True)
    if not module_link.is_symlink() or module_link.resolve() != modules.resolve():
        raise RuntimeError(validation_resources.ValidationError.VALIDATION_WEB_HOST_MODULES_INVALID)
    environment = {
        "PATH": str(node.parent) + os.pathsep + os.environ["PATH"],
        "NODE_OPTIONS": "--max-old-space-size=2048",
        "VITE_IDENTITY_MODE": "local",
        "LOCAL_DEV_IDENTITY": "0",
    }
    commands = [
        [str(node), str(modules / "typescript/bin/tsc"), "--noEmit"],
        [
            str(node),
            str(modules / "vite/bin/vite.js"),
            "build",
            "--outDir",
            str(context / "built-dist"),
            "--emptyOutDir",
        ],
    ]
    with (directory / "web-host-build.log").open("ab") as log:
        for command in commands:
            subprocess.run(
                command,
                cwd=compile_context,
                env=environment,
                stdout=log,
                stderr=subprocess.STDOUT,
                check=True,
                timeout=WEB_BUILD_TIMEOUT_SECONDS,
            )
    bundle = {
        "sourceFingerprint": record["fingerprint"],
        "node": "24.22.0",
        "dependencies": dependencies,
        "typecheck": "PASS",
        "build": "PASS",
        "files": {
            path.relative_to(context / "built-dist").as_posix(): digest(path)
            for path in (context / "built-dist").rglob("*")
            if path.is_file()
        },
    }
    private(receipt_path, json.dumps(bundle, indent=2) + "\n")
    return bundle


def prepare_web(directory: Path, state: dict) -> dict:
    """已锁定依赖输入相同才复用旧镜像依赖层；新源码快照离线构建local模式。"""
    import runtime

    context = directory / "web-context"
    record_path = directory / "web-inputs.json"
    base = state["baseImages"]["web"]
    recipe = (
        f"FROM {base['reference']}\nUSER root\nWORKDIR /app\n"
        "COPY frontend/ /app/\nCOPY docker.config.ts /app/docker.config.ts\n"
        "COPY built-dist/ /app/isolated-dist/\n"
        "ENV VITE_IDENTITY_MODE=local\nUSER 10001:10001\n"
        'CMD ["node", "node_modules/vite/bin/vite.js", "preview", "--outDir", "isolated-dist", '
        '"--config", "docker.config.ts", "--configLoader", "runner"]\n'
    )
    if not record_path.exists():
        files = runtime.source_contents(FRONTEND)
        context.mkdir(parents=True, exist_ok=True)
        hashes = {}
        for name, content in files.items():
            destination = context / "frontend" / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(content)
            hashes[name] = hashlib.sha256(content).hexdigest()
        config = ROOT / "deploy/docker/web.config.ts"
        shutil.copyfile(config, context / "docker.config.ts")
        fingerprint = hashlib.sha256(
            json.dumps(
                {
                    "files": hashes,
                    "dockerConfig": digest(config),
                    "mode": "local",
                    "recipe": recipe,
                    "baseImageId": base["imageId"],
                },
                sort_keys=True,
            ).encode()
        ).hexdigest()
        record = {
            "protocol": "web-validation-inputs-v1",
            "fingerprint": fingerprint,
            "files": hashes,
            "baseCommit": subprocess.check_output(
                ["git", "rev-parse", "HEAD"], cwd=FRONTEND, text=True
            ).strip(),
            "includesUncommitted": True,
            "mode": "local",
            "dockerConfig": digest(config),
            "recipe": recipe,
            "baseImageId": base["imageId"],
        }
        private(record_path, json.dumps(record, indent=2) + "\n")
    record = json.loads(record_path.read_text())
    input_digest = hashlib.sha256(
        json.dumps(
            {
                key: record[key]
                for key in ("files", "dockerConfig", "mode", "recipe", "baseImageId")
            },
            sort_keys=True,
        ).encode()
    ).hexdigest()
    if input_digest != record["fingerprint"]:
        raise RuntimeError(release_artifacts.ReleaseError.SOURCE_CHANGED)
    snapshot = context / "frontend"
    if any(path.is_symlink() for path in snapshot.rglob("*")) or {
        path.relative_to(snapshot).as_posix() for path in snapshot.rglob("*") if path.is_file()
    } != set(record["files"]):
        raise RuntimeError(release_artifacts.ReleaseError.SOURCE_CHANGED)

    if record.get("recipe") != recipe or record.get("baseImageId") != base["imageId"]:
        raise RuntimeError(validation_resources.ValidationError.VALIDATION_WEB_RECIPE_CHANGED)
    if digest(context / "docker.config.ts") != record["dockerConfig"]:
        raise RuntimeError(validation_resources.ValidationError.VALIDATION_WEB_CONFIG_CHANGED)
    for name, expected in record["files"].items():
        if digest(context / "frontend" / name) != expected:
            raise RuntimeError(validation_resources.ValidationError.VALIDATION_WEB_SOURCE_CHANGED)
    source_check = (
        'const fs=require("fs"),c=require("crypto");'
        "console.log(JSON.stringify(Object.fromEntries("
        '["package.json","pnpm-lock.yaml"].map(p=>[p,'
        'c.createHash("sha256").update(fs.readFileSync("/app/"+p)).digest("hex")]))))'
    )
    installed = json.loads(
        subprocess.check_output(
            [
                "docker",
                "run",
                "--rm",
                "--network",
                "none",
                "--entrypoint",
                "node",
                base["imageId"],
                "-e",
                source_check,
            ],
            text=True,
        )
    )
    if any(installed[name] != record["files"][name] for name in installed):
        raise RuntimeError(validation_resources.ValidationError.VALIDATION_WEB_DEPENDENCIES_CHANGED)
    compile_web(directory, context, record)
    tag = "agentic-ai/web-validation:" + record["fingerprint"][:16]
    dockerfile = recipe + f'LABEL io.agentic.source-fingerprint="{record["fingerprint"]}"\n'
    (context / "Dockerfile").write_text(dockerfile)
    run_logged(
        ["docker", "build", "--network", "none", "-t", tag, str(context)],
        directory,
        "web-build.log",
    )
    image = release_artifacts.image_fact(tag)
    if image["sourceFingerprint"] != record["fingerprint"]:
        raise RuntimeError(validation_resources.ValidationError.VALIDATION_WEB_IMAGE_INVALID)
    # 即使构建期间源tag变化，基底层不相同也不允许进入启动阶段。
    facts = json.loads(
        subprocess.check_output(
            ["docker", "image", "inspect", base["imageId"], image["imageId"]], text=True
        )
    )
    layers = facts[0]["RootFS"]["Layers"]
    if facts[1]["RootFS"]["Layers"][: len(layers)] != layers:
        raise RuntimeError(validation_resources.ValidationError.VALIDATION_WEB_BASE_CHANGED)
    private(
        directory / "web-artifact.json",
        json.dumps({**image, "inputs": str(record_path), "baseImageId": base["imageId"]}, indent=2)
        + "\n",
    )
    return image


def create_keys(directory: Path, source: Path) -> Path:
    """新环境使用全新服务签名密钥，不复制开发私钥或身份平台客户端secret。"""
    keys = directory / "keys"
    keys.mkdir(exist_ok=True)
    stems = {
        p.name.removesuffix("-public.pem").removesuffix("-private.pem")
        for p in source.rglob("*.pem")
    }
    stems.add("iam-test")
    for stem in stems:
        private_key = keys / (stem + "-private.pem")
        public_key = keys / (stem + "-public.pem")
        if not private_key.exists():
            value = subprocess.check_output(
                ["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048"],
                stderr=subprocess.DEVNULL,
            )
            private(private_key, value.decode())
        if not public_key.exists():
            private(
                public_key,
                subprocess.check_output(
                    ["openssl", "pkey", "-in", str(private_key), "-pubout"],
                    stderr=subprocess.DEVNULL,
                ).decode(),
            )
    return keys


def prepare_knowledge(directory: Path, state: dict) -> dict:
    """从冻结Maven源码构建支持隔离索引的Knowledge；不覆盖原部署镜像。"""
    import runtime

    context = directory / "knowledge-context"
    record_path = directory / "knowledge-inputs.json"
    base = state["baseImages"]["knowledge"]
    recipe = (
        f"FROM {base['reference']}\n"
        "COPY jars/knowledge-service.jar /app/service.jar\n"
        "COPY jars/knowledge-parser.jar /app/knowledge-parser.jar\n"
    )
    if not record_path.exists():
        files = runtime.source_contents(ROOT)
        hashes = {}
        for name, content in files.items():
            destination = context / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(content)
            hashes[name] = hashlib.sha256(content).hexdigest()
        fingerprint = hashlib.sha256(
            json.dumps(
                {"files": hashes, "recipe": recipe, "baseImageId": base["imageId"]}, sort_keys=True
            ).encode()
        ).hexdigest()
        record = {
            "protocol": "knowledge-validation-inputs-v1",
            "fingerprint": fingerprint,
            "files": hashes,
            "recipe": recipe,
            "baseImageId": base["imageId"],
            "baseCommit": subprocess.check_output(
                ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
            ).strip(),
            "includesUncommitted": True,
        }
        private(record_path, json.dumps(record, indent=2) + "\n")
    record = json.loads(record_path.read_text())
    input_digest = hashlib.sha256(
        json.dumps(
            {key: record[key] for key in ("files", "recipe", "baseImageId")}, sort_keys=True
        ).encode()
    ).hexdigest()
    if input_digest != record["fingerprint"]:
        raise RuntimeError(release_artifacts.ReleaseError.SOURCE_CHANGED)

    if record["recipe"] != recipe or record["baseImageId"] != base["imageId"]:
        raise RuntimeError(validation_resources.ValidationError.VALIDATION_BASE_IMAGE_CHANGED)
    for name, expected in record["files"].items():
        if digest(context / name) != expected:
            raise RuntimeError(release_artifacts.ReleaseError.SOURCE_CHANGED)
    artifact_path = directory / "knowledge-artifact.json"
    if artifact_path.exists():
        artifact = json.loads(artifact_path.read_text())
        if (
            artifact["sourceFingerprint"] != record["fingerprint"]
            or release_artifacts.image_fact(artifact["reference"]) != artifact["image"]
        ):
            raise RuntimeError(release_artifacts.ReleaseError.IMAGE_CHANGED)
        return artifact["image"]
    with (directory / "knowledge-build.log").open("ab") as log:
        subprocess.run(
            [
                "mvn",
                "-o",
                "-B",
                "-ntp",
                "-pl",
                "knowledge-service,knowledge-parser",
                "-am",
                "verify",
            ],
            cwd=context,
            stdout=log,
            stderr=subprocess.STDOUT,
            check=True,
            timeout=600,
        )
    jars = context / "jars"
    jars.mkdir(exist_ok=True)
    for module in ("knowledge-service", "knowledge-parser"):
        shutil.copyfile(
            context / module / "target" / f"{module}-0.1.0-SNAPSHOT.jar", jars / f"{module}.jar"
        )
    (context / "Dockerfile").write_text(
        recipe + f'LABEL io.agentic.source-fingerprint="{record["fingerprint"]}"\n'
    )
    (context / ".dockerignore").write_text("*\n!Dockerfile\n!jars/\n!jars/**\n")
    tag = "agentic-ai/knowledge-validation:" + record["fingerprint"][:16]
    run_logged(
        ["docker", "build", "--network", "none", "-t", tag, str(context)],
        directory,
        "knowledge-image.log",
    )
    image = release_artifacts.image_fact(tag)
    facts = json.loads(
        subprocess.check_output(["docker", "image", "inspect", base["imageId"], image["imageId"]])
    )
    layers = facts[0]["RootFS"]["Layers"]
    if (
        image["sourceFingerprint"] != record["fingerprint"]
        or facts[1]["RootFS"]["Layers"][: len(layers)] != layers
    ):
        raise RuntimeError(release_artifacts.ReleaseError.IMAGE_SOURCE_MISMATCH)
    private(
        artifact_path,
        json.dumps(
            {
                **image,
                "image": image,
                "inputs": str(record_path),
                "jars": {path.name: digest(path) for path in jars.glob("*.jar")},
                "mavenVerify": "PASS",
            },
            indent=2,
        )
        + "\n",
    )
    return image


def configure(directory: Path, state: dict, resources: dict, receipt: dict) -> None:
    """派生原Compose，替换全部资金/资源/签名上下文，不改变源部署文件。"""
    source = ROOT / ".local/docker-runtime/releases" / receipt["release"] / "runtime"
    env_file = ROOT / ".local/docker-runtime/compose.env"
    source_compose = source.parent / "context/deploy/docker/compose.yaml"
    current = json.loads((env_file.parent / "current.json").read_text())
    if current["SOURCE_FINGERPRINT"] != receipt["sourceFingerprint"]:
        raise RuntimeError(validation_resources.ValidationError.VALIDATION_BASE_SETTINGS_CHANGED)
    config = json.loads(
        subprocess.check_output(
            [
                "docker",
                "compose",
                "--env-file",
                str(env_file),
                "-f",
                str(source_compose),
                "config",
                "--format",
                "json",
            ]
        )
    )
    config["name"] = state["project"]
    keys = create_keys(directory, source)
    target = directory / "runtime"
    target.mkdir(exist_ok=True)
    budgets = directory / "budgets"
    budgets.mkdir(exist_ok=True)
    for kind in ("chat", "embedding"):
        path = budgets / (kind + ".json")
        if not path.exists():
            private(
                path,
                json.dumps(
                    {
                        "version": 1,
                        "scope": kind + "-integration-20261020",
                        "currency": "CNY",
                        "testOnly": True,
                        "limitCny": "1",
                        "reservedCny": "0.75",
                        "calls": [
                            {
                                "id": "synthetic-preserved-reservation",
                                "reservedCny": "0.75",
                                "status": "RESERVED",
                            }
                        ],
                    }
                ),
            )
    proxy_key = state["proxyKey"]
    for name, service in config["services"].items():
        service.pop("build", None)
        service["image"] = state["images"][name]["imageId"]
        service["restart"] = "no"
        environment = service.pop("environment", {})
        if name in {"gateway", "knowledge", "tasks", "conversations", "ai", "model-proxy"}:
            destination = target / name
            destination.mkdir(exist_ok=True)
            original = source / name
            for file in original.glob("*.pem"):
                private(destination / file.name, (keys / file.name).read_text())
            if name == "gateway":
                environment = {
                    key: value
                    for key, value in environment.items()
                    if not key.startswith("BROWSER_")
                }
                environment.update(
                    BROWSER_IDENTITY_ENABLED="false",
                    IAM_ISSUER="urn:agentic-ai:local-test",
                    IAM_PUBLIC_KEY="file:/run/agentic/iam-test-public.pem",
                )
                private(
                    destination / "iam-test-public.pem", (keys / "iam-test-public.pem").read_text()
                )
                service["ports"] = [
                    {
                        "target": 28080,
                        "published": str(GATEWAY_PORT),
                        "host_ip": "127.0.0.1",
                        "protocol": "tcp",
                    },
                    {
                        "target": 28086,
                        "published": str(WEB_PORT),
                        "host_ip": "127.0.0.1",
                        "protocol": "tcp",
                    },
                ]
            if name in {"knowledge", "tasks", "conversations"}:
                prefix = {
                    "knowledge": "KNOWLEDGE",
                    "tasks": "TASK",
                    "conversations": "CONVERSATION",
                }[name]
                item = resources[name]
                environment.update(
                    {
                        prefix
                        + "_DB_URL": f"jdbc:mysql://dev-infra-mysql84-1:3306/{item['database']}?sslMode=DISABLED&allowPublicKeyRetrieval=true",
                        prefix + "_DB_USER": item["app"],
                        prefix + "_DB_PASSWORD": item["app_password"],
                        prefix + "_MIGRATION_USER": item["migrator"],
                        prefix + "_MIGRATION_PASSWORD": item["migration_password"],
                    }
                )
                environment[prefix + "_WORKER_TENANT"] = "local-dev"
                environment[prefix + "_WORKER_ENABLED"] = "false"
            if name == "knowledge":
                blob = resources["source"]
                search = resources["search"]
                environment.update(
                    KNOWLEDGE_S3_BUCKET=blob["bucket"],
                    KNOWLEDGE_S3_ACCESS_KEY=blob["user"],
                    KNOWLEDGE_S3_SECRET_KEY=blob["password"],
                    KNOWLEDGE_ELASTIC_INDEX=search["index"],
                    KNOWLEDGE_ELASTIC_USER=search["user"],
                    KNOWLEDGE_ELASTIC_PASSWORD=search["password"],
                    KNOWLEDGE_QDRANT_COLLECTION=search["collection"],
                    KNOWLEDGE_EVALUATION_WORKER_ENABLED="false",
                )
            if name == "ai":
                environment.update(
                    RAG_EMBEDDING_API_KEY="synthetic-no-provider-access",
                    RAG_EMBEDDING_BASE_URL="https://127.0.0.1:28119/v1",
                )
            if name == "model-proxy":
                environment.update(
                    DEEPSEEK_API_KEY="synthetic-no-provider-access", LITELLM_MASTER_KEY=proxy_key
                )
                proxy = json.loads(
                    json.dumps(
                        {
                            "model_list": [
                                {
                                    "model_name": "chat-default",
                                    "litellm_params": {
                                        "model": "deepseek/deepseek-flash",
                                        "api_base": "http://127.0.0.1:28119/v1",
                                        "api_key": "synthetic-no-provider-access",
                                        "num_retries": 0,
                                        "timeout": 3,
                                    },
                                }
                            ],
                            "litellm_settings": {
                                "num_retries": 0,
                                "set_verbose": False,
                                "turn_off_message_logging": True,
                                "cache": False,
                            },
                            "general_settings": {
                                "master_key": "os.environ/LITELLM_MASTER_KEY",
                                "background_health_checks": False,
                                "disable_spend_logs": True,
                            },
                        }
                    )
                )
                private(destination / "config.yaml", json.dumps(proxy))
                service.setdefault("volumes", []).append(
                    {
                        "type": "bind",
                        "source": str(destination / "config.yaml"),
                        "target": "/app/config.yaml",
                        "read_only": True,
                    }
                )
            private(
                destination / "service.env",
                "".join(f"{key}={value}\n" for key, value in sorted(environment.items())),
            )
            service["env_file"] = [{"path": str(destination / "service.env"), "format": "raw"}]
        else:
            service["environment"] = environment
        for volume in service.get("volumes", []):
            value = volume.get("source", "")
            if value.startswith(str(source) + "/"):
                volume["source"] = str(target) + value[len(str(source)) :]
            elif value.endswith("embedding-budget-20261020.json"):
                volume["source"] = str(budgets / "embedding.json")
            elif value.endswith("chat-budget-20261020.json"):
                volume["source"] = str(budgets / "chat.json")
            if volume.get("type") == "bind" and not Path(volume["source"]).is_relative_to(
                directory
            ):
                raise RuntimeError(
                    validation_resources.ValidationError.VALIDATION_MOUNT_NOT_ISOLATED
                )
    worker = json.loads((source / "qa-worker/qa-worker.json").read_text())
    worker.update(
        tenant_id="local-dev",
        checkpoints={
            "dsn": validation_resources.checkpoint_dsn(resources["checkpoints"], docker=True)
        },
    )
    chat = json.loads((source / "qa-worker/chat.json").read_text())
    chat.update(api_key=proxy_key, budget_file="/run/budgets/chat.json")
    private(target / "qa-worker/qa-worker.json", json.dumps(worker))
    private(target / "qa-worker/chat.json", json.dumps(chat))
    for file in (source / "qa-worker").glob("*.pem"):
        private(target / "qa-worker" / file.name, (keys / file.name).read_text())
    private(target / "web/.local/iam-test-private.pem", (keys / "iam-test-private.pem").read_text())
    web = config["services"]["web"]
    web["environment"] = {
        "VITE_IDENTITY_MODE": "local",
        "AGENTIC_LOCAL_ORIGIN": f"http://127.0.0.1:{WEB_PORT}",
    }
    web["healthcheck"]["test"] = [
        "CMD",
        "node",
        "-e",
        f'require("node:http").get("http://127.0.0.1:28086/api/local-session",{{headers:{{Host:"127.0.0.1:{WEB_PORT}","X-Workbench-Request":"1"}}}},r=>{{r.resume();r.on("end",()=>process.exit(r.statusCode==={HTTPStatus.OK}?0:1))}}).on("error",()=>process.exit(1))',
    ]
    config["services"]["search-bridge"]["environment"] = {"IDENTITY_MODE": "local"}
    private(directory / "compose.json", json.dumps(config, indent=2) + "\n")
    state["composeSha256"] = digest(directory / "compose.json")
    state["runtimeSha256s"] = {
        path.relative_to(directory).as_posix(): digest(path)
        for path in target.rglob("*")
        if path.is_file()
    }
    state["phase"] = ValidationPhase.CONFIG_READY
    save(directory, state)


def prepare(run_id: str) -> Path:
    """新ID检查后分阶段初始化；失败保留该ID，继续必须复用状态。"""
    validation_resources.names(run_id)
    directory = ROOT / ".local/release-validation" / run_id
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    receipt = json.loads(BASE_RECEIPT.read_text())
    if (directory / "state.json").exists():
        state = load(directory)
        if state["baseReceiptSha256"] != digest(BASE_RECEIPT):
            raise RuntimeError(validation_resources.ValidationError.VALIDATION_BASE_RECEIPT_CHANGED)
    else:
        images = base_images(receipt)
        state = {
            "protocol": PROTOCOL,
            "id": run_id,
            "project": "agentic-s17-" + run_id,
            "baseReceiptSha256": digest(BASE_RECEIPT),
            "baseImages": images,
            "images": images.copy(),
            "proxyKey": "sk-" + os.urandom(24).hex(),
            "phase": ValidationPhase.INIT,
        }
        save(directory, state)
    if state["phase"] in {
        ValidationPhase.CONFIG_READY,
        ValidationPhase.RUNNING,
        ValidationPhase.STOPPED,
        ValidationPhase.VERIFIED,
    }:
        return directory
    resources = validation_resources.prepare(directory, run_id)["resources"]
    state["phase"] = ValidationPhase.RESOURCES_READY
    save(directory, state)
    state["images"]["web"] = prepare_web(directory, state)
    state["images"]["knowledge"] = prepare_knowledge(directory, state)
    state["phase"] = ValidationPhase.WEB_BUILT
    save(directory, state)
    configure(directory, state, resources, receipt)
    run_logged(compose(directory, "config", "--quiet"), directory, "compose-check.log")
    return directory


def original_action(directory: Path, operation: str) -> None:
    """正常暂停/恢复原九容器，不重新创建或构建；操作前先记录恢复入口。"""
    import runtime

    state = load(directory)
    original_state = ROOT / ".local/docker-runtime"
    settings = json.loads((original_state / "current.json").read_text())
    receipt = json.loads(BASE_RECEIPT.read_text())
    if settings["SOURCE_FINGERPRINT"] != receipt["sourceFingerprint"]:
        raise RuntimeError(validation_resources.ValidationError.VALIDATION_BASE_SETTINGS_CHANGED)
    expected = json.loads((directory / "probe-baseline.json").read_text())["original"]["containers"]
    if set(expected) != release_artifacts.SERVICES:
        raise RuntimeError(validation_resources.ValidationError.VALIDATION_BASE_SERVICES_INVALID)
    for name, fact in expected.items():
        item = json.loads(
            subprocess.check_output(
                ["docker", "inspect", f"agentic-ai-app-{name}-1"], text=True, timeout=20
            )
        )[0]
        if item["Id"] != fact["id"] or item["Image"] != fact["imageId"]:
            raise RuntimeError(validation_resources.ValidationError.VALIDATION_BASE_IMAGE_CHANGED)
    if operation == "pause-original":
        state["originalPhase"] = OriginalPhase.STOPPING
        save(directory, state)
        run_logged(runtime.compose(original_state, "stop"), directory, "original-stop.log")
        state["originalPhase"] = OriginalPhase.STOPPED
    else:
        state["originalPhase"] = OriginalPhase.RESTORING
        save(directory, state)
        run_logged(compose(directory, "stop"), directory, "stop.log")
        run_logged(
            runtime.compose(original_state, "start", "--wait", "--wait-timeout", "240"),
            directory,
            "original-start.log",
        )
        state["originalPhase"] = OriginalPhase.RESTORED
        if state["phase"] == ValidationPhase.RUNNING:
            state["phase"] = ValidationPhase.STOPPED
    save(directory, state)


def action(name: str, run_id: str) -> None:
    """只有固定状态中的独立项目可被启动/停止，所有镜像先按不可变ID核对。"""
    directory = ROOT / ".local/release-validation" / run_id
    if name in {"pause-original", "restore-original"}:
        original_action(directory, name)
    elif name == "prepare":
        prepare(run_id)
    else:
        state = load(directory)
        if digest(directory / "compose.json") != state["composeSha256"]:
            raise RuntimeError(validation_resources.ValidationError.VALIDATION_COMPOSE_CHANGED)
        if name == "up":
            for name, expected in state["runtimeSha256s"].items():
                path = directory / name
                if (
                    path.is_symlink()
                    or not path.resolve().is_relative_to(directory.resolve())
                    or digest(path) != expected
                ):
                    raise RuntimeError(
                        validation_resources.ValidationError.VALIDATION_RUNTIME_CHANGED
                    )
            for image in state["images"].values():
                actual = release_artifacts.image_fact(image["imageId"])
                if (
                    actual["imageId"] != image["imageId"]
                    or actual["sourceFingerprint"] != image["sourceFingerprint"]
                ):
                    raise RuntimeError(
                        validation_resources.ValidationError.VALIDATION_IMAGE_CHANGED
                    )
            run_logged(
                compose(directory, "up", "-d", "--no-build", "--wait", "--wait-timeout", "240"),
                directory,
                "up.log",
            )
            state["phase"] = ValidationPhase.RUNNING
            save(directory, state)
        elif name == "stop":
            run_logged(compose(directory, "stop"), directory, "stop.log")
            state["phase"] = ValidationPhase.STOPPED
            save(directory, state)
        elif name == "status":
            subprocess.run(compose(directory, "ps"), check=True)
        else:
            raise RuntimeError(validation_resources.ValidationError.VALIDATION_ACTION_INVALID)
    print(
        json.dumps(
            {
                "id": run_id,
                "phase": load(directory)["phase"],
                "state": str(directory / "state.json"),
            }
        )
    )
