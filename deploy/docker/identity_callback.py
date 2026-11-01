#!/usr/bin/env python3
"""为现有项目专属应用追加Docker回调，保留旧回调和其他IAM字段。"""

import argparse
import base64
import json
from pathlib import Path
import sys
import urllib.parse

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import identity_local

CALLBACK = "http://localhost:28086/login/oauth2/code/workbench"


def register(management: Path) -> dict:
    """先核对归属与全部已登记安全字段；结果未知时先GET核对，不重试POST。"""
    value = identity_local.state()
    credential = identity_local.read_private(management)
    authorization = (
        "Basic "
        + base64.b64encode(
            (credential["client_id"] + ":" + credential["client_secret"]).encode()
        ).decode()
    )
    _, owner, name, fields = next(
        item for item in identity_local.specification(value) if item[0] == "application"
    )
    query = urllib.parse.urlencode({"id": owner + "/" + name})
    current = identity_local.request("get-application?" + query, authorization)
    if not current or any(
        current.get(key) != expected
        for key, expected in fields.items()
        if key != "redirectUris"
    ):
        raise RuntimeError("专属应用归属或安全字段发生变化，未更新回调")
    allowed = [identity_local.CALLBACK, CALLBACK]
    if current.get("redirectUris") not in ([identity_local.CALLBACK], allowed):
        raise RuntimeError("回调列表与授权范围不一致，未更新")
    changed = CALLBACK not in current["redirectUris"]
    if changed:
        identity_local.private(
            identity_local.STATE / "application-before-docker.json", json.dumps(current)
        )
        identity_local.request(
            "update-application?" + query,
            authorization,
            dict(current, redirectUris=allowed),
        )
    observed = identity_local.request("get-application?" + query, authorization)
    if (
        observed is None
        or observed.get("redirectUris") != allowed
        or any(
            observed.get(key) != expected
            for key, expected in fields.items()
            if key != "redirectUris"
        )
    ):
        raise RuntimeError("回调更新未通过读回核对")
    result = {
        "status": "PASS",
        "application": name,
        "callbacks": allowed,
        "changed": changed,
        "otherApplicationsChanged": False,
    }
    identity_local.private(
        identity_local.STATE / "docker-callback-result.json",
        json.dumps(result, indent=2),
    )
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--management", type=Path, required=True)
    print(json.dumps(register(parser.parse_args().management)))
