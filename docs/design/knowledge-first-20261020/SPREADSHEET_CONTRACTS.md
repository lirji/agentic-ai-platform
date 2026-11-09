# S9F · XLSX / CSV 来源契约

用户已批准两种格式；沿 C-PARSE 的隔离解析、P03/P04/P05、发布与权限边界实施。现有 PDF、DOCX 和文本算法输出不变。新上传由 `knowledge.parsing.spreadsheet-upload-enabled` 控制，默认关闭；历史任务按持久版本恢复。

- XLSX：POI 6.0.0，parser `poi-6.0.0-sheets-v1`，processor `xlsx-v1-poi6.0.0-rows512-overlap64-ctx256-embedding-v4-1024`。
- CSV：Commons CSV 1.14.1（Apache-2.0、Java 8+），parser `commons-csv-1.14.1-rfc4180-v1`，processor `csv-v1-commons1.14.1-rows512-overlap64-ctx256-embedding-v4-1024`。固定 UTF-8（可有 BOM）、逗号、双引号、双引号转义；字段内CRLF/CR规范为LF，其余文字不trim。保留空行所占记录号，引用内换行属于同一记录。拒绝非法 UTF-8、未闭合引号及控制字符；不猜编码/分隔符，不转换数字/日期或执行公式。
- 新格式 `XLSX`/`CSV`，新块 `SHEET_CELL`；位置是 `sheetIndex,sheetName,row,column,rowSpan,columnSpan,header`，全部一基。CSV 仅 sheet 1，固定名 `CSV`。位置 key 为 `sheet:<index>:<SHA256(UTF8 name)>:<row>:<column>:<rowSpan>:<columnSpan>:<0|1>`，摘要绑定真实工作表名。公开简短 locator 为 `sheet-cells:s1r2c1,s1r2c3`；完整名称、表头标识和合并范围由详细来源返回。不是 PDF 页码或 DOCX 表格。
- 每张表首个非空行标为表头（明确的启发式规则，始终提示）；空格保留原字符串，空单元格可省略但绝不压缩真实坐标。仅非空锚格参与提取；合并覆盖范围完整保留并告警，合并区域重叠/越界拒绝。隐藏表/行/列仍读取并统一提示，不把隐藏当授权。
- XLSX 使用固定 Locale.US 的显示值；前导零按单元格格式保留，日期使用文件数字格式和 1900/1904 日期系统。公式仅读文件中存在的缓存值，不运行 evaluator；缺缓存或缓存错误拒绝，存在缓存始终提示可能过期。宏容器、嵌入宏、非超链接外部关系拒绝；超链接只留单元格文本，不访问目标。
- 资源边界继承原文件10 MiB、提取正文1 MiB、1000块、输出4 MiB、20秒、ZIP 2000项/单项16 MiB/总32 MiB/倍率100；新增最多20张表、真实行号≤10000、列号≤100、合并区域≤1000。超限失败不截断。宽于16个非空格的行按来源上限拆组并告警。表头上下文只按当前组覆盖的列选择，工作表名+表头总计≤256码点，否则拒绝，不静默截断。
- 告警闭合集合：`SHEET_HEADER_INFERRED`（必有）、`SHEET_MERGED_CELLS`、`SHEET_WIDE_ROW`、`SHEET_FORMULA_CACHED`、`SHEET_HIDDEN_CONTENT`、`SHEET_EXTERNAL_LINK_TEXT_ONLY`。所有告警 `locationKey=null`，有序去重；前两项/宽行由接收端独立复核，XLSX专属警告不得出现在CSV。
- 结构分块按工作表与真实行划分，每组最多16个非空格，窗口512/重叠64。检索上下文增加工作表名和相关列的表头；原文与来源只来自对应格，不把增强上下文当引用证据。Java/Python分别验证分组、跨度、顺序、名称和来源摘要。
- Knowledge Owner 串行追加 V21 扩展固定 profile CHECK，不改旧行。先部署新读端（Java/Python/Web），再启用上传；旧读端不认识新类型，回退先关受理并保留新读能力/隔离新格式，不能宣称无条件旧版本回滚。

验收覆盖前导零/日期、引号/空行、多表/隐藏、缓存公式/缺缓存、宏/外链、合并/宽行/边界，跨语言金标、真实数据库发布/检索/引用及浏览器来源恢复。受控样本不代表业务表格质量。

选型依据：[Commons CSV 1.14.1 下载与 Java 要求](https://commons.apache.org/proper/commons-csv/download_csv.cgi)、[官方安全公告](https://commons.apache.org/proper/commons-csv/security.html)、[POI DataFormatter 缓存值接口](https://poi.apache.org/apidocs/dev/org/apache/poi/ss/usermodel/DataFormatter.html)。2026-11-09 核验；安全公告未列已修漏洞不等于证明不存在风险。
