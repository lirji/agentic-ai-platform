"""共享配置只从同一次正式身份配置派生，不持久复制第二份成员目录。"""

from collections.abc import Mapping
from uuid import UUID

FIELDS = {
    "ISSUER": "ISSUER",
    "ORGANIZATION": "ORGANIZATION",
    "TENANT": "TENANT_ID",
    "CLIENT_ID": "CLIENT_ID",
    "CLIENT_SECRET": "CLIENT_SECRET",
    "MEMBERS": "MEMBERS",
}


def environment(browser: Mapping[str, str], enabled: bool = False) -> dict[str, str]:
    """不开关不读凭据；启用必须是同组织的已准入正式身份，失败不回退local-dev。"""
    if not enabled:
        return {"KNOWLEDGE_SHARING_ENABLED": "false"}
    if browser.get("BROWSER_IDENTITY_ENABLED") != "true":
        raise ValueError("资料共享必须使用正式OIDC身份")
    values = {
        target: browser.get("BROWSER_IDENTITY_" + source, "")
        for source, target in FIELDS.items()
    }
    if (
        any(not value for value in values.values())
        or values["ORGANIZATION"] != values["TENANT_ID"]
    ):
        raise ValueError("共享配置必须与同一次OIDC组织和准入一致")
    ids = values["MEMBERS"].split(",")
    if (
        len(ids) > 100
        or len(ids) != len(set(ids))
        or any(str(UUID(value)) != value for value in ids)
    ):
        raise ValueError("共享成员必须使用有界、唯一、稳定的用户编号")
    return {
        "KNOWLEDGE_SHARING_ENABLED": "true",
        **{"KNOWLEDGE_SHARING_" + key: value for key, value in values.items()},
    }
