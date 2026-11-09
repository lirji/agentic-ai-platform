package com.lrj.agentic.contract.knowledge;

/** S9E的固定算法与有界资源；变更识别语义必须新增profile，不能覆盖旧提取物。 */
public final class OcrProtocol {
    public static final String VERSION = "5.5.4";
    public static final String PARSER = "pdfbox-3.0.8-tesseract-5.5.4-chi_sim-eng-v1";
    public static final String PROCESSOR = "pdf-v4-tesseract5.5.4-pages512-overlap64-embedding-v4-1024";
    public static final String LANGUAGES = "chi_sim+eng";
    public static final String EXECUTABLE_PROPERTY = "knowledge.ocr.executable";
    public static final String DATA_PROPERTY = "knowledge.ocr.data";
    public static final int MAX_PAGES = 10;
    public static final int DPI = 200;
    public static final long MAX_PAGE_PIXELS = 5_000_000;
    public static final long MAX_DOCUMENT_PIXELS = 50_000_000;
    public static final int PAGE_TIMEOUT_SECONDS = 5;
    public static final int CONFIDENCE_WARNING = 70;
    public static final int PDF_POINTS_PER_INCH = 72;
    public static final int WORD_LEVEL = 5;
    public static final String TSV_HEADER = "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext";
    private OcrProtocol() { }
}
