"""固定OOXML和RFC4180字节样本；只生成测试资料，不访问用户文件或模型。"""
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

OUT = Path(__file__).parent / 'fixtures'
NS = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'
REL = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
PART = 'http://schemas.openxmlformats.org/package/2006/relationships'
parts = {
'[Content_Types].xml': '''<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>''',
'_rels/.rels': f'<Relationships xmlns="{PART}"><Relationship Id="rId1" Type="{REL}/officeDocument" Target="xl/workbook.xml"/></Relationships>',
'xl/workbook.xml': f'<workbook xmlns="{NS}" xmlns:r="{REL}"><workbookPr date1904="0"/><sheets><sheet name="账目" sheetId="1" r:id="rId1"/><sheet name="项目背景" sheetId="2" state="hidden" r:id="rId2"/></sheets></workbook>',
'xl/_rels/workbook.xml.rels': f'<Relationships xmlns="{PART}"><Relationship Id="rId1" Type="{REL}/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="{REL}/worksheet" Target="worksheets/sheet2.xml"/><Relationship Id="rId3" Type="{REL}/styles" Target="styles.xml"/></Relationships>',
'xl/styles.xml': f'<styleSheet xmlns="{NS}"><numFmts count="2"><numFmt numFmtId="164" formatCode="000000"/><numFmt numFmtId="165" formatCode="yyyy-mm-dd"/></numFmts><fonts count="1"><font><sz val="11"/><name val="Arial"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="3"><xf xfId="0"/><xf numFmtId="164" applyNumberFormat="1" xfId="0"/><xf numFmtId="165" applyNumberFormat="1" xfId="0"/></cellXfs></styleSheet>',
'xl/worksheets/sheet1.xml': f'<worksheet xmlns="{NS}"><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>编号</t></is></c><c r="B1" t="inlineStr"><is><t>日期</t></is></c><c r="C1" t="inlineStr"><is><t>数量</t></is></c></row><row r="2"><c r="A2" s="1"><v>123</v></c><c r="B2" s="2"><v>45292</v></c><c r="C2"><f>SUM(1,2)</f><v>3</v></c></row><row r="4"><c r="A4" t="inlineStr"><is><t>合并说明</t></is></c></row></sheetData><mergeCells count="1"><mergeCell ref="A4:C4"/></mergeCells></worksheet>',
'xl/worksheets/sheet2.xml': f'<worksheet xmlns="{NS}"><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>项目</t></is></c><c r="B1" t="inlineStr"><is><t>状态</t></is></c></row><row r="2"><c r="A2" t="inlineStr"><is><t>知识工作台</t></is></c><c r="B2" t="inlineStr"><is><t>验收中</t></is></c></row></sheetData></worksheet>',
}
with ZipFile(OUT / 'multi-sheet.xlsx', 'w') as archive:
    for name, text in parts.items():
        entry = ZipInfo(name, (2026, 11, 9, 0, 0, 0)); entry.compress_type = ZIP_DEFLATED
        archive.writestr(entry, text.encode())
(OUT / 'quoted.csv').write_bytes(('\ufeff编号,描述,日期\r\n00123,"含逗号,以及""引号""",2024-01-01\r\n\r\n00007,"跨\r\n行文本",2024-02-02\r\n').encode())
