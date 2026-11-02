"""候选校验副本的显式搜索映射增量；不改索引中的已有正文。"""

import search_local


def environment(enabled: bool = False) -> dict[str, str]:
    """只开启候选用例；处理/代次/worker与模型额度保持原配置，不隐式启动付费工作。"""
    return {"KNOWLEDGE_OPERATIONS_ENABLED": "true" if enabled else "false"}


def prepare_preview_index(mode: str) -> None:
    """只给指定环境追加keyword字段；不重建索引、删数据或改已有字段。"""
    if mode not in {"dev", "test"}:
        raise ValueError("候选映射只接受明确的dev/test环境")
    index = search_local.INDEX + ("-test" if mode == "test" else "")
    collection = search_local.COLLECTION + ("_test" if mode == "test" else "")
    current = search_local.request("GET", f"/{index}/_mapping")
    if current is None or current[index]["mappings"].get("dynamic") != "strict":
        raise RuntimeError("既有搜索索引未就绪，未创建替代索引")
    purpose = current[index]["mappings"]["properties"].get("purpose")
    if purpose is not None and purpose != {"type": "keyword"}:
        raise RuntimeError("目的字段已有不兼容定义，未覆盖")
    if purpose is None:
        search_local.request(
            "PUT",
            f"/{index}/_mapping",
            {"properties": {"purpose": {"type": "keyword"}}},
        )
    search_local.request(
        "PUT",
        f"/collections/{collection}/index?wait=true",
        {"field_name": "purpose", "field_schema": "keyword"},
        vector=True,
    )


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["prepare-index"])
    parser.add_argument("--mode", choices=["dev", "test"], required=True)
    arguments = parser.parse_args()
    prepare_preview_index(arguments.mode)
    print("PASS: preview purpose mapping verified; existing documents retained")
