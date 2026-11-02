"""固定制品边界：标签用于定位，不可变imageId用于决定能否启动。"""

import hashlib
import json
import subprocess
from enum import StrEnum
from pathlib import Path

PROTOCOL = "release-artifacts-v1"
SERVICES = frozenset(
    {
        "gateway",
        "knowledge",
        "tasks",
        "conversations",
        "ai",
        "model-proxy",
        "qa-worker",
        "search-bridge",
        "web",
    }
)
FINGERPRINT_LABEL = "io.agentic.source-fingerprint"
BUILD_OUTPUT_DIRS = frozenset(
    {
        "target",
        "node_modules",
        ".venv",
        "__pycache__",
        ".pytest_cache",
        ".mypy_cache",
        ".ruff_cache",
    }
)
GENERATED_FILES = {
    "docker/web.config.ts": "deploy/docker/web.config.ts",
    "docker/search_bridge.py": "deploy/docker/search_bridge.py",
}
DOCKER_IGNORE = (
    "**/target\n**/node_modules\n**/.venv\n**/.local\n**/.logs\n**/__pycache__\n"
    "**/.pytest_cache\n**/.mypy_cache\n**/.ruff_cache\n.git\ndocs\n.engineering\n"
)


class ReleaseError(StrEnum):
    """只输出稳定运维错误，不把展开的环境或私密路径拼到错误中。"""

    SOURCE_CHANGED = "RELEASE_SOURCE_CHANGED"
    IMAGE_CHANGED = "RELEASE_IMAGE_CHANGED"
    IMAGE_SOURCE_MISMATCH = "RELEASE_IMAGE_SOURCE_MISMATCH"
    SERVICES_CHANGED = "RELEASE_SERVICES_CHANGED"
    RECEIPT_MISSING = "RELEASE_RECEIPT_MISSING"
    RECEIPT_INVALID = "RELEASE_RECEIPT_INVALID"


class SourceKind(StrEnum):
    """仓库角色使用稳定清单值，与镜像标签和目录名区分。"""

    BACKEND = "backend"
    FRONTEND = "frontend"


def verify_source(settings: dict) -> dict:
    """构建后快照仍必须匹配原清单；不读取用户当前工作区或信任易变HEAD。"""
    manifest = json.loads(Path(settings["manifest"]).read_text())
    digest = hashlib.sha256(
        json.dumps(
            {"sources": manifest["sources"], "options": manifest["options"]}, sort_keys=True
        ).encode()
    ).hexdigest()
    if digest != manifest["fingerprint"] or digest != settings["SOURCE_FINGERPRINT"]:
        raise RuntimeError(ReleaseError.SOURCE_CHANGED)
    context = Path(settings["BUILD_CONTEXT"])
    expected_paths = set()
    for kind, files in manifest["sources"].items():
        if kind not in {SourceKind.BACKEND, SourceKind.FRONTEND}:
            raise RuntimeError(ReleaseError.SOURCE_CHANGED)
        base = context if kind == SourceKind.BACKEND else context / SourceKind.FRONTEND
        for name, expected in files.items():
            path = base / name
            expected_paths.add(path.relative_to(context).as_posix())
            if (
                Path(name).is_absolute()
                or ".." in Path(name).parts
                or path.is_symlink()
                or not path.resolve().is_relative_to(base.resolve())
                or not path.is_file()
                or hashlib.sha256(path.read_bytes()).hexdigest() != expected
            ):
                raise RuntimeError(ReleaseError.SOURCE_CHANGED)
    for path in context.rglob("*"):
        relative = path.relative_to(context)
        if any(part in BUILD_OUTPUT_DIRS for part in relative.parts):
            continue
        if path.is_symlink():
            raise RuntimeError(ReleaseError.SOURCE_CHANGED)
        if not path.is_file() or relative.as_posix() in expected_paths:
            continue
        name = relative.as_posix()
        if (
            name in GENERATED_FILES
            and path.read_bytes() == (context / GENERATED_FILES[name]).read_bytes()
        ):
            continue
        if name == ".dockerignore" and path.read_text() == DOCKER_IGNORE:
            continue
        # 编译JAR只作为固定镜像的构建输入；不允许额外源码混入COPY目录。
        if relative.parts[0] == "jars" and len(relative.parts) == 2 and path.suffix == ".jar":
            continue
        raise RuntimeError(ReleaseError.SOURCE_CHANGED)
    return manifest


def image_fact(reference: str) -> dict:
    """仅保留镜像ID与源码标签，不能把镜像内部环境输出为交付证据。"""
    result = json.loads(
        subprocess.check_output(["docker", "image", "inspect", reference], text=True)
    )[0]
    return {
        "reference": reference,
        "imageId": result["Id"],
        "sourceFingerprint": result["Config"].get("Labels", {}).get(FINGERPRINT_LABEL),
    }


def record(settings: dict, services: dict[str, str]) -> dict:
    """只有成功构建的调用方记录收据；多服务共用镜像仍保留服务到ID映射。"""
    manifest = verify_source(settings)
    if set(services) != SERVICES:
        raise RuntimeError(ReleaseError.SERVICES_CHANGED)
    images = {name: image_fact(reference) for name, reference in services.items()}
    if any(fact["sourceFingerprint"] != settings["SOURCE_FINGERPRINT"] for fact in images.values()):
        raise RuntimeError(ReleaseError.IMAGE_SOURCE_MISMATCH)
    return {
        "protocol": PROTOCOL,
        "sourceFingerprint": settings["SOURCE_FINGERPRINT"],
        "commits": manifest["baseCommits"],
        "includesUncommitted": manifest["includesUncommitted"],
        "services": images,
    }


def verify(settings: dict, services: dict[str, str], receipt: dict) -> None:
    """启动前再次按标签解析ID；任何一项漂移都先拒绝，不部分启动。"""
    verify_source(settings)
    if (
        receipt.get("protocol") != PROTOCOL
        or receipt.get("sourceFingerprint") != settings["SOURCE_FINGERPRINT"]
    ):
        raise RuntimeError(ReleaseError.RECEIPT_INVALID)
    if set(services) != SERVICES or set(receipt.get("services", {})) != SERVICES:
        raise RuntimeError(ReleaseError.SERVICES_CHANGED)
    for service, reference in services.items():
        expected = receipt["services"][service]
        actual = image_fact(reference)
        if actual != expected:
            raise RuntimeError(ReleaseError.IMAGE_CHANGED)
        if actual["sourceFingerprint"] != settings["SOURCE_FINGERPRINT"]:
            raise RuntimeError(ReleaseError.IMAGE_SOURCE_MISMATCH)
