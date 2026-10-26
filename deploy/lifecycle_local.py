"""S10B 本地发布开关及 Tasks→Knowledge 独立只读身份；由 local.py 统一进入。"""

from __future__ import annotations

import json

import local

CONFIG = local.STATE / "lifecycle.json"
PRIVATE_KEY = local.STATE / "task-knowledge-reader-private.pem"
PUBLIC_KEY = local.STATE / "task-knowledge-reader-public.pem"
DEFAULTS = {
    "createEnabled": True,
    "generationEnabled": False,
    "updatesEnabled": False,
    "backfillEnabled": False,
}
FLAGS = {
    "createEnabled": "KNOWLEDGE_LIFECYCLE_CREATE_ENABLED",
    "generationEnabled": "KNOWLEDGE_LIFECYCLE_GENERATION_ENABLED",
    "updatesEnabled": "KNOWLEDGE_LIFECYCLE_UPDATES_ENABLED",
    "backfillEnabled": "KNOWLEDGE_LIFECYCLE_BACKFILL_ENABLED",
}


def init() -> None:
    """仅初始化独立身份及关闭的更新开关；重跑不轮换密钥或覆盖迁移状态。"""
    if PUBLIC_KEY.exists() and not PRIVATE_KEY.exists():
        raise RuntimeError("K08 公钥已存在但私钥缺失，未轮换身份")
    for path in (PRIVATE_KEY, PUBLIC_KEY, CONFIG):
        if path.is_symlink() or (path.exists() and path.stat().st_mode & 0o077):
            raise RuntimeError("生命周期配置权限不符合要求")
    if not PRIVATE_KEY.exists():
        local.private(PRIVATE_KEY, local.run([
            "openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048",
        ]).decode())
    public = local.run(["openssl", "pkey", "-in", str(PRIVATE_KEY), "-pubout"]).decode()
    if PUBLIC_KEY.exists() and PUBLIC_KEY.read_text() != public:
        raise RuntimeError("K08 签名材料不配对，未覆盖原文件")
    if not PUBLIC_KEY.exists():
        local.private(PUBLIC_KEY, public)
    if not CONFIG.exists():
        local.private(CONFIG, json.dumps(DEFAULTS, indent=2) + "\n")
    environment("dev")
    print("K08 独立身份已就绪；发布开关沿用原配置，未迁移数据或调用模型。")


def environment(mode: str) -> dict[str, str]:
    """测试不继承开发写开关；迁移配置关闭所有 writer，回填失败则启动失败。"""
    if not CONFIG.exists():
        return {}
    for path in (CONFIG, PRIVATE_KEY, PUBLIC_KEY):
        if path.is_symlink() or not path.is_file() or path.stat().st_mode & 0o077:
            raise RuntimeError("生命周期配置或 K08 身份材料不完整")
    settings = json.loads(CONFIG.read_text())
    if set(settings) != set(DEFAULTS) or any(type(value) is not bool for value in settings.values()):
        raise RuntimeError("生命周期开关必须使用已定义的布尔字段")
    if settings["updatesEnabled"] and not settings["generationEnabled"]:
        raise RuntimeError("更新开关依赖代次协议")
    if settings["backfillEnabled"] and any(settings[key] for key in (
        "createEnabled", "generationEnabled", "updatesEnabled",
    )):
        raise RuntimeError("回填必须先关闭写入与代次受理")
    selected = DEFAULTS if mode == "test" else settings
    result = {variable: str(selected[key]).lower() for key, variable in FLAGS.items()}
    if selected["backfillEnabled"]:
        result["KNOWLEDGE_WORKER_ENABLED"] = "false"
    result.update({
        "KNOWLEDGE_CITATION_READER_ENABLED": "true",
        "KNOWLEDGE_CITATION_READER_PUBLIC_KEY": PUBLIC_KEY.as_uri(),
        "TASK_KNOWLEDGE_READER_ENABLED": "true",
        "TASK_KNOWLEDGE_READER_ENDPOINT": "http://127.0.0.1:28084",
        "TASK_KNOWLEDGE_READER_PUBLIC_KEY": PUBLIC_KEY.as_uri(),
        "TASK_KNOWLEDGE_READER_PRIVATE_KEY": PRIVATE_KEY.as_uri(),
    })
    return result
