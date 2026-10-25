"""本项目搜索资源入口；固定安全补丁版本，使用独立实例、命名卷及账号。"""

from __future__ import annotations

import base64
import json
import os
import secrets
import shutil
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

from ai_local import LOGS, ROOT, STATE, private

CONFIG = STATE / "search.json"
COMPOSE = ROOT / "deploy/search/compose.yaml"
COLLECTION = "agentic_knowledge_text_v1"
INDEX = "agentic-knowledge-text-v1"
QDRANT = "http://127.0.0.1:26333"
ELASTIC = "http://127.0.0.1:29200"


def load() -> dict:
    """缺少已有状态时拒绝猜测凭据或覆盖资源。"""
    if not CONFIG.exists():
        raise RuntimeError("尚未初始化本项目搜索资源")
    return json.loads(CONFIG.read_text())


def compose(*arguments: str) -> None:
    """不输出 Compose 展开的环境，失败日志仅保存在项目忽略目录。"""
    config = load()
    env = {
        **os.environ,
        "AGENTIC_QDRANT_KEY": config["qdrantKey"],
        "AGENTIC_ELASTIC_ADMIN_PASSWORD": config["elasticAdminPassword"],
    }
    LOGS.mkdir(exist_ok=True)
    with (LOGS / "search-runtime.log").open("ab") as log:
        result = subprocess.run(
            ["docker", "compose", "-f", str(COMPOSE), *arguments],
            env=env,
            stdout=log,
            stderr=log,
            timeout=1800 if arguments[0] in {"build", "pull"} else 180,
        )
    if result.returncode:
        raise RuntimeError("本项目搜索 Compose 操作失败，请核对 .logs/search-runtime.log")


def request(method: str, path: str, body: dict | None = None, *, vector: bool = False):
    """初始化阶段使用本项目管理凭据，不把认证头输出到控制台。"""
    config = load()
    base = QDRANT if vector else ELASTIC
    headers = {"Content-Type": "application/json"}
    if vector:
        headers["api-key"] = config["qdrantKey"]
    else:
        value = base64.b64encode(f"elastic:{config['elasticAdminPassword']}".encode()).decode()
        headers["Authorization"] = f"Basic {value}"
    req = urllib.request.Request(
        base + path,
        data=None if body is None else json.dumps(body).encode(),
        headers=headers,
        method=method,
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            return json.load(response) if method != "HEAD" else {}
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise RuntimeError(f"搜索初始化 HTTP {error.code}，未输出响应") from None


def init() -> None:
    """可重复执行；不删除既有集合、文档或卷，已存在 schema 必须兼容。"""
    if not CONFIG.exists():
        STATE.mkdir(exist_ok=True)
        private(
            CONFIG,
            json.dumps(
                {
                    "qdrantKey": secrets.token_urlsafe(32),
                    "elasticAdminPassword": secrets.token_urlsafe(32),
                    "devPassword": secrets.token_urlsafe(32),
                    "testPassword": secrets.token_urlsafe(32),
                },
                indent=2,
            ),
        )
    compose("config", "--quiet")
    compose("pull", "qdrant")
    compose("build", "elasticsearch")
    compose("up", "-d", "--pull", "never")
    for _ in range(60):
        try:
            request("GET", "/", vector=True)
            request("GET", "/_cluster/health")
            break
        except (OSError, RuntimeError):
            time.sleep(1)
    else:
        raise RuntimeError("搜索资源未就绪，保留配置及卷等待修复")
    config = load()
    for mode in ("dev", "test"):
        collection = COLLECTION + ("_test" if mode == "test" else "")
        index = INDEX + ("-test" if mode == "test" else "")
        current = request("GET", f"/collections/{collection}", vector=True)
        if current is None:
            request(
                "PUT",
                f"/collections/{collection}",
                {"vectors": {"size": 1024, "distance": "Cosine"}},
                vector=True,
            )
        else:
            vectors = current["result"]["config"]["params"]["vectors"]
            if vectors["size"] != 1024 or vectors["distance"] != "Cosine":
                raise RuntimeError("已有集合维度/距离不兼容，未覆盖")
        for field in ("tenantId", "ownerId", "documentId", "processorVersion"):
            request(
                "PUT",
                f"/collections/{collection}/index?wait=true",
                {"field_name": field, "field_schema": "keyword"},
                vector=True,
            )
        properties = {
            field: {"type": "keyword"}
            for field in (
                "tenantId",
                "ownerId",
                "documentId",
                "processorVersion",
                "chunkId",
                "batchDigest",
            )
        }
        properties.update(
            {
                "version": {"type": "long"},
                "text": {"type": "text", "analyzer": "smartcn"},
            }
        )
        current = request("GET", f"/{index}/_mapping")
        if current is None:
            request(
                "PUT",
                f"/{index}",
                {
                    "settings": {"number_of_shards": 1, "number_of_replicas": 0},
                    "mappings": {"dynamic": "strict", "properties": properties},
                },
            )
        elif (
            current[index]["mappings"].get("properties") != properties
            or current[index]["mappings"].get("dynamic") != "strict"
        ):
            raise RuntimeError("已有搜索 mapping 不兼容，未覆盖")
        username = f"agentic_knowledge_{mode}"
        request(
            "PUT",
            f"/_security/role/{username}",
            {
                "cluster": [],
                "indices": [
                    {
                        "names": [index],
                        "privileges": ["read", "write", "view_index_metadata"],
                    }
                ],
            },
        )
        request(
            "PUT",
            f"/_security/user/{username}",
            {"password": config[f"{mode}Password"], "roles": [username]},
        )
    print("本项目双搜索资源已就绪；开发/测试索引隔离，未操作旧平台数据。")


def environment(mode: str) -> dict[str, str]:
    """本机 worker 仅领取 local-dev；测试禁用后台领取，由测试显式控制。"""
    config = load()
    return {
        "KNOWLEDGE_PROCESSING_ENABLED": os.environ.get("KNOWLEDGE_PROCESSING_ENABLED", "true"),
        # 新受理与已有任务恢复可独立关闭；固定本仓解析 JAR，不接受请求传入路径。
        "KNOWLEDGE_PARSING_ENABLED": os.environ.get("KNOWLEDGE_PARSING_ENABLED", "false"),
        "KNOWLEDGE_PDF_UPLOAD_ENABLED": os.environ.get("KNOWLEDGE_PDF_UPLOAD_ENABLED", "false"),
        "KNOWLEDGE_DOCX_UPLOAD_ENABLED": os.environ.get("KNOWLEDGE_DOCX_UPLOAD_ENABLED", "false"),
        "KNOWLEDGE_PARSER_JAVA": str(Path(shutil.which("java") or "/usr/bin/java").resolve()),
        "KNOWLEDGE_PARSER_JAR": str(
            ROOT / "knowledge-parser/target/knowledge-parser-0.1.0-SNAPSHOT.jar"
        ),
        "KNOWLEDGE_WORKER_ENABLED": "false" if mode == "test" else "true",
        "KNOWLEDGE_WORKER_TENANT": "local-dev",
        "KNOWLEDGE_AI_PRIVATE_KEY": (STATE / "knowledge-ai-private.pem").as_uri(),
        "KNOWLEDGE_AI_PUBLIC_KEY": (STATE / "knowledge-ai-public.pem").as_uri(),
        "KNOWLEDGE_QDRANT_KEY": config["qdrantKey"],
        "KNOWLEDGE_QDRANT_COLLECTION": COLLECTION + ("_test" if mode == "test" else ""),
        "KNOWLEDGE_ELASTIC_USER": f"agentic_knowledge_{mode}",
        "KNOWLEDGE_ELASTIC_PASSWORD": config[f"{mode}Password"],
        "KNOWLEDGE_ELASTIC_INDEX": INDEX + ("-test" if mode == "test" else ""),
    }
