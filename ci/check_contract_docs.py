"""检查当前CI登记的跨语言契约证据；只读文件，不接触本机运行配置。"""

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DESIGN = ROOT / "docs/design/knowledge-first-20261020"
REGISTERED_CONTRACTS = {
    "CI_CONTRACTS.md": "ci-v1",
    "RETRIEVAL_CONTRACTS.md": "knowledge-search-v2",
    "QUALITY_CONTRACTS.md": "quality-v1",
    "EVALUATION_RUN_CONTRACTS.md": "evaluation-run-v1",
    "PROCESSING_CONTRACTS.md": "knowledge-preparation-v1",
    "STRUCTURED_PROCESSING_CONTRACTS.md": "knowledge-preparation-v2",
    "LIFECYCLE_CONTRACTS.md": "knowledge-lifecycle-v1",
}
FIXTURES = ROOT / "knowledge-service/src/test/resources"


def main() -> None:
    """版本标记与共享报文路径须一致；明确不把文档存在当业务语义通过。"""
    for name, marker in REGISTERED_CONTRACTS.items():
        text = (DESIGN / name).read_text(encoding="utf-8")
        assert marker in text, f"{name}: missing protocol marker {marker}"
        assert sum(line.startswith("```") for line in text.splitlines()) % 2 == 0, name
    evaluation = json.loads((FIXTURES / "evaluation-wire-golden.json").read_text())
    generation = json.loads((FIXTURES / "evaluation-generation-golden.json").read_text())
    assert generation["protocol"] == "evaluation-run-v2"
    assert generation["protocol"] in (DESIGN / "EVALUATION_RUN_CONTRACTS.md").read_text()
    assert generation["input"]["schemaVersion"] == "evaluation-input-v2"
    assert set(generation["reports"]) == set(evaluation["reports"])
    processing = json.loads((FIXTURES / "processing-golden.json").read_text())
    assert evaluation["protocol"] == REGISTERED_CONTRACTS["EVALUATION_RUN_CONTRACTS.md"]
    assert (
        processing["prepared"]["schemaVersion"]
        == REGISTERED_CONTRACTS["PROCESSING_CONTRACTS.md"]
    )
    assert set(evaluation["reports"]) == {"answerable", "noAnswer", "failed"}
    for name in (
        "structured-processing-golden.json",
        "structured-processing-chinese-golden.json",
        "structured-processing-docx-golden.json",
    ):
        structured = json.loads((FIXTURES / name).read_text())
        assert (
            structured["prepared"]["schemaVersion"]
            == REGISTERED_CONTRACTS["STRUCTURED_PROCESSING_CONTRACTS.md"]
        )
        assert (ROOT / structured["sourceFixture"]).is_file()
    for name in ("text", "pdf-v2", "docx-v2", "pdf-v3", "docx-v3"):
        fixture = json.loads((FIXTURES / f"generation-{name}-golden.json").read_text())
        assert len(fixture["generations"]) == 2
        for generation in fixture["generations"]:
            assert generation["prepared"]["schemaVersion"] == "knowledge-generation-preparation-v1"
        if fixture["sourceFixture"] is not None:
            assert (ROOT / fixture["sourceFixture"]).is_file()
    search = json.loads((FIXTURES / "search-consumer-golden.json").read_text())
    assert search["protocol"] == REGISTERED_CONTRACTS["RETRIEVAL_CONTRACTS.md"]
    assert search["input"]["schemaVersion"] == "knowledge-search-input-v2"
    assert search["result"]["schemaVersion"] == "knowledge-search-result-v2"
    assert search["capabilities"]["schemaVersion"] == "knowledge-retrieval-capabilities-v1"
    print("7 registered contracts and 12 shared wire fixture files: PASS")


if __name__ == "__main__":
    main()
