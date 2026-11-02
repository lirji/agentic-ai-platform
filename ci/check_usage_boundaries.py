"""S15B持续边界检查：跨域持久化、usage包循环和迁移注释。"""

import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OWNER = ROOT / "async-task-service/src/main"
USAGE = OWNER / "java/com/lrj/agentic/task/usage"
STORAGE = re.compile(r"com\.lrj\.agentic\.task\.usage\.infrastructure(?:\.|\b)")
TABLE = r"usage_(?:account|price|binding|operation|attempt|audit|import|import_entry)"
WRITE = re.compile(
    r"\b(?:insert\s+into|update|delete\s+from)\s+" + TABLE + r"\b", re.IGNORECASE
)


def check():
    """只检查生产源码；授权端口和独立DB事务的正确性另由真实IT验证。"""
    count = 0
    graph = {
        layer: set()
        for layer in ("api", "application", "contract", "domain", "infrastructure")
    }
    for module in ROOT.iterdir():
        if not module.is_dir():
            continue
        java = module / "src/main/java"
        if java.exists():
            for source in java.rglob("*.java"):
                text = source.read_text()
                count += 1
                if USAGE not in source.parents:
                    boundary_text = text
                    if (
                        source
                        == OWNER / "java/com/lrj/agentic/task/TaskApplication.java"
                    ):
                        # 仅组合根的MapperScan注册字符串允许，不能以启动类为由引入业务持久依赖。
                        boundary_text = re.sub(
                            r'"com\.lrj\.agentic\.task\.usage\.infrastructure"',
                            "",
                            text,
                        )
                    assert not STORAGE.search(boundary_text), (
                        f"跨域引用usage持久层: {source.relative_to(ROOT)}"
                    )
                else:
                    assert not re.search(
                        r"com\.lrj\.agentic\.(?:knowledge|conversation)\.", text
                    ), f"usage依赖其他服务实现: {source}"
                    assert not re.search(
                        r"com\.lrj\.agentic\.task\.infrastructure\.", text
                    ), f"usage绕过Task公开端口: {source}"
                    layer = source.relative_to(USAGE).parts[0]
                    for target in re.findall(
                        r"com\.lrj\.agentic\.task\.usage\.(api|application|contract|domain|infrastructure)\.",
                        text,
                    ):
                        if target != layer:
                            graph[layer].add(target)
        for relative in (
            ("src/main/java", "src/main/resources/mapper", "src")
            if module.name == "ai-runtime"
            else ("src/main/java", "src/main/resources/mapper")
        ):
            base = module / relative
            if not base.exists():
                continue
            for source in base.rglob("*"):
                if source.suffix not in (".java", ".xml", ".py"):
                    continue
                if source == OWNER / "resources/mapper/UsageMapper.xml":
                    continue
                assert not WRITE.search(source.read_text()), (
                    f"绕过usage Mapper写表: {source.relative_to(ROOT)}"
                )

    # Maven模块依赖已由verify检查；这里额外检查usage自身的包层级依赖。
    def visit(layer, active):
        assert layer not in active, "usage层级循环: " + " -> ".join([*active, layer])
        for target in graph[layer]:
            visit(target, [*active, layer])

    for layer in graph:
        visit(layer, [])
    xml = (OWNER / "resources/mapper/UsageMapper.xml").read_text()
    tree = ET.fromstring(xml)
    tables = set()
    for statement in tree:
        if statement.tag not in ("select", "insert", "update", "delete"):
            continue
        sql_text = " ".join(statement.itertext())
        tables.update(
            re.findall(
                r"\b(?:FROM|JOIN|INTO|(?<!FOR )UPDATE)\s+([a-z][a-z_]+)",
                sql_text,
                re.IGNORECASE,
            )
        )
    assert tables and all(table.startswith("usage_") for table in tables), (
        f"usage Mapper跨表: {tables}"
    )
    sql = (OWNER / "resources/db/migration/V6__shared_model_usage.sql").read_text()
    creates = re.findall(r"CREATE TABLE (\w+) \((.*?)\) ENGINE=(.*?);", sql, re.DOTALL)
    assert len(creates) == 8
    columns = 0
    for name, fields, suffix in creates:
        assert re.search(r"COMMENT='[^']+'", suffix), f"缺表注释: {name}"
        for line in fields.splitlines():
            if re.match(
                r"\s+[a-z][a-z0-9_]+\s+[A-Z]+",
                line,
            ):
                columns += 1
                assert re.search(r"COMMENT '[^']+'", line), (
                    f"缺字段注释: {name}: {line}"
                )
    assert columns > 60
    print(
        f"usage boundaries PASS: {count} Java sources, 5-layer DAG, own Mapper tables, 8 tables/{columns} commented columns"
    )


if __name__ == "__main__":
    check()
