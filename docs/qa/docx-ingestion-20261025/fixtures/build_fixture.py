"""生成自有中文 DOCX 夹具；固定 ZIP 时间戳，可复核真实段落和合并网格。"""

import base64
from pathlib import Path
from zipfile import ZIP_STORED, ZipFile, ZipInfo

ROOT = Path(__file__).parent
W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
P = "http://schemas.openxmlformats.org/package/2006/relationships"


def paragraph(text: str, properties: str = "") -> str:
    return f"<w:p>{properties}<w:r><w:t>{text}</w:t></w:r></w:p>"


def cell(text: str, properties: str = "") -> str:
    return f"<w:tc><w:tcPr>{properties}</w:tcPr>{paragraph(text)}</w:tc>"


table = "<w:tbl><w:tblGrid>" + "<w:gridCol w:w='2400'/>" * 3 + "</w:tblGrid>"
table += "<w:tr><w:trPr><w:tblHeader/></w:trPr>"
table += "".join(cell(text) for text in ["项目", "办理规则", "备注"]) + "</w:tr>"
table += "<w:tr><w:tc><w:tcPr><w:gridSpan w:val='2'/><w:vMerge w:val='restart'/>"
table += "</w:tcPr>" + paragraph("资料审核") + paragraph("两个工作日") + "</w:tc>"
table += cell("提交材料") + "</w:tr>"
table += "<w:tr>" + cell("", "<w:gridSpan w:val='2'/><w:vMerge/>") + cell("") + "</w:tr>"
table += "<w:tr>" + "".join(cell(text) for text in ["咨询", "工作日 09:00–17:00", "线上受理"]) + "</w:tr></w:tbl>"
body = paragraph("资料办理说明", "<w:pPr><w:pStyle w:val='CustomTitle'/></w:pPr>")
body += "<w:p/>" + paragraph("本资料用于验证段落和表格来源，审核时限为两个工作日。") + table
body += "<w:p><w:r><w:t>查询</w:t><w:tab/></w:r><w:hyperlink r:id='link'>"
body += "<w:r><w:t>办理说明</w:t></w:r></w:hyperlink><w:r><w:br/><w:t>仅保留链接文字。</w:t></w:r></w:p>"
body += paragraph("注意事项", "<w:pPr><w:outlineLvl w:val='1'/></w:pPr>")
body += "<w:p><w:r><w:t>附件图示不参与文字检索。</w:t><w:footnoteReference w:id='1'/>"
body += "<w:endnoteReference w:id='1'/><w:commentReference w:id='0'/><w:pict>"
body += "<v:shape id='picture' style='width:1pt;height:1pt'><v:imagedata r:id='image'/></v:shape>"
body += "</w:pict></w:r></w:p><w:sectPr><w:headerReference w:type='default' r:id='header'/>"
body += "<w:footerReference w:type='default' r:id='footer'/></w:sectPr>"
parts = {
    "word/document.xml": f"<w:document xmlns:w='{W}' xmlns:r='{R}' xmlns:v='urn:schemas-microsoft-com:vml'><w:body>{body}</w:body></w:document>",
    "word/styles.xml": f"<w:styles xmlns:w='{W}'><w:style w:type='paragraph' w:styleId='Heading1'><w:name w:val='heading 1'/><w:pPr><w:outlineLvl w:val='0'/></w:pPr></w:style><w:style w:type='paragraph' w:styleId='CustomTitle'><w:name w:val='自定义标题'/><w:basedOn w:val='Heading1'/></w:style></w:styles>",
    "word/header1.xml": f"<w:hdr xmlns:w='{W}'>{paragraph('页眉示例')}</w:hdr>",
    "word/footer1.xml": f"<w:ftr xmlns:w='{W}'>{paragraph('页脚示例')}</w:ftr>",
    "word/comments.xml": f"<w:comments xmlns:w='{W}'><w:comment w:id='0' w:author='fixture'>{paragraph('批注示例')}</w:comment></w:comments>",
    "word/footnotes.xml": f"<w:footnotes xmlns:w='{W}'><w:footnote w:id='1'>{paragraph('脚注示例')}</w:footnote></w:footnotes>",
    "word/endnotes.xml": f"<w:endnotes xmlns:w='{W}'><w:endnote w:id='1'>{paragraph('尾注示例')}</w:endnote></w:endnotes>",
}
types = "<Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'><Default Extension='rels' ContentType='application/vnd.openxmlformats-package.relationships+xml'/><Default Extension='png' ContentType='image/png'/>"
for name, kind in [
    ("document", "document.main"), ("styles", "styles"), ("header1", "header"),
    ("footer1", "footer"), ("comments", "comments"), ("footnotes", "footnotes"),
    ("endnotes", "endnotes"),
]:
    types += f"<Override PartName='/word/{name}.xml' ContentType='application/vnd.openxmlformats-officedocument.wordprocessingml.{kind}+xml'/>"
parts["[Content_Types].xml"] = types + "</Types>"
parts["_rels/.rels"] = f"<Relationships xmlns='{P}'><Relationship Id='root' Type='{R}/officeDocument' Target='word/document.xml'/></Relationships>"
rels = f"<Relationships xmlns='{P}'>"
for ident, kind, target in [
    ("styles", "styles", "styles.xml"), ("header", "header", "header1.xml"),
    ("footer", "footer", "footer1.xml"), ("comments", "comments", "comments.xml"),
    ("footnotes", "footnotes", "footnotes.xml"), ("endnotes", "endnotes", "endnotes.xml"),
    ("image", "image", "media/pixel.png"),
]:
    rels += f"<Relationship Id='{ident}' Type='{R}/{kind}' Target='{target}'/>"
rels += f"<Relationship Id='link' Type='{R}/hyperlink' Target='https://example.invalid/docx-fixture' TargetMode='External'/></Relationships>"
parts["word/_rels/document.xml.rels"] = rels
parts["word/media/pixel.png"] = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/a9sAAAAASUVORK5CYII=")
def write(name: str, entries: dict) -> None:
    with ZipFile(ROOT / name, "w") as archive:
        for part_name, content in sorted(entries.items()):
            info = ZipInfo(part_name, (2026, 10, 25, 0, 0, 0))
            info.compress_type = ZIP_STORED
            archive.writestr(info, content.encode() if isinstance(content, str) else content)


write("chinese-structure.docx", parts)
# 固定坏文件验证P03整体失败；完整类型元数据仍可通过K01容器判别。
for filename, bad_body in {
    "empty.docx": "<w:p/>",
    "too-many-blocks.docx": paragraph("唯一正文") + "<w:p/>" * 1000,
    "revisions.docx": paragraph("修订前正文") + "<w:ins>" + paragraph("修订内容") + "</w:ins>",
    "nested-table.docx": "<w:tbl><w:tblGrid><w:gridCol/></w:tblGrid><w:tr><w:tc><w:tbl/>" + paragraph("嵌套表") + "</w:tc></w:tr></w:tbl>",
    "alt-chunk.docx": paragraph("已有正文") + "<w:altChunk r:id='external-content'/>",
}.items():
    document = f"<w:document xmlns:w='{W}' xmlns:r='{R}'><w:body>{bad_body}</w:body></w:document>"
    write(filename, {**parts, "word/document.xml": document})
write("xml-entity.docx", {**parts, "word/document.xml": "<!DOCTYPE w:document SYSTEM 'http://127.0.0.1:1/never'>" + parts["word/document.xml"]})
