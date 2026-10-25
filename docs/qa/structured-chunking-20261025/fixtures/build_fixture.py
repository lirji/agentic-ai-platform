"""以既有自有DOCX容器生成长章节/代码/表格夹具；固定ZIP时间戳，禁止覆盖。"""

from pathlib import Path
from xml.sax.saxutils import escape
from zipfile import ZIP_STORED, ZipFile, ZipInfo

root = Path(__file__).parent
base = root.parents[1] / "docx-ingestion-20261025/fixtures/chinese-structure.docx"
with ZipFile(base) as source:
    parts = {name: source.read(name) for name in source.namelist()}


def paragraph(text, heading=None):
    prop = "" if heading is None else f'<w:pPr><w:outlineLvl w:val="{heading - 1}"/></w:pPr>'
    return f'<w:p>{prop}<w:r><w:t xml:space="preserve">{escape(text)}</w:t></w:r></w:p>'


body = paragraph("资料审核与代码保真", 1)
body += paragraph("资料必须保留原版本。Python example: if ready: publish()。🐋 e\u0301\n" * 28)
body += "<w:p/>" + "".join(
    paragraph(f"步骤{i:02d}：核对资料。重复正文应定位原段。") for i in range(1, 19)
)
body += paragraph("办理时限", 2)
body += "<w:tbl><w:tblGrid>" + '<w:gridCol w:w="2400"/>' * 3 + "</w:tblGrid>"
for index, row in enumerate(
    [
        ["事项", "办理时限", "渠道"],
        ["资料审核", "两个工作日", "线上提交"],
        ["复核", "一个工作日", "人工审核"],
    ]
):
    body += "<w:tr>" + ("<w:trPr><w:tblHeader/></w:trPr>" if index == 0 else "")
    body += "".join("<w:tc><w:tcPr/>" + paragraph(cell) + "</w:tc>" for cell in row) + "</w:tr>"
body += (
    "</w:tbl>"
    + paragraph("第二章", 1)
    + paragraph(
        "function publish(version) {\n  return version !== null;\n}\n原始代码缩进和换行不可改写。"
    )
)
parts["word/document.xml"] = (
    '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>'
    + body
    + "</w:body></w:document>"
).encode()
with ZipFile(root / "long-structure.docx", "x") as target:
    for name, content in sorted(parts.items()):
        info = ZipInfo(name, (2026, 10, 25, 0, 0, 0))
        info.compress_type = ZIP_STORED
        target.writestr(info, content)
