package com.lrj.agentic.contract.knowledge;

/** 表格解析的固定版本与容量边界；不会随上传开关改变历史任务语义。 */
public final class SheetProtocol {
    public static final String XLSX_PARSER = "poi-6.0.0-sheets-v1";
    public static final String CSV_PARSER = "commons-csv-1.14.1-rfc4180-v1";
    public static final String XLSX_PROCESSOR = "xlsx-v1-poi6.0.0-rows512-overlap64-ctx256-embedding-v4-1024";
    public static final String CSV_PROCESSOR = "csv-v1-commons1.14.1-rows512-overlap64-ctx256-embedding-v4-1024";
    public static final String XLSX_MAIN_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml";
    public static final String CSV_SHEET = "CSV";
    public static final int MAX_SHEETS = 20;
    public static final int MAX_ROWS = 10000;
    public static final int MAX_NAME = 31;
    public static final int MAX_MERGES = 1000;
    public static final int MAX_GROUP_CELLS = 16;
    private SheetProtocol() { }
}
