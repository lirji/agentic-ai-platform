package com.lrj.agentic.knowledge.domain.processing;

/** 跨语言及检索投影的固定版本，变更这些值必须建立新投影与迁移。 */
public final class ProcessingProtocol {
    public static final String SCHEMA = "knowledge-preparation-v1";
    public static final String PROCESSOR = "text-v1-char512-overlap64-embedding-v4-1024";
    public static final String MODEL = "text-embedding-v4";
    public static final int DIMENSIONS = 1024;
    public static final int MAX_CHUNKS = 100;
    public static final int CHUNK_SIZE = 512;
    public static final int CHUNK_OVERLAP = 64;
    public static final int MAX_RESPONSE_BYTES = 6 * 1024 * 1024;
    public static final int MAX_ATTEMPTS = 5;
    public static final int MAX_RETRIES = 3;
    public static final int MAX_ACTIVE_INGESTIONS = 1;
    public static final int MAX_ACTIVE_QUERIES = 2;
    public static final int MAX_REMOTE_CALLS = MAX_ACTIVE_INGESTIONS + MAX_ACTIVE_QUERIES;
    public static final int CANDIDATES = 100;
    public static final int RRF_OFFSET = 60;
    public static final String AI_ISSUER = "urn:agentic-ai:knowledge";
    public static final String AI_AUDIENCE = "agentic-ai-runtime";
    public static final String AI_CALLER = "knowledge-service";
    public static final String PREPARE_SCOPE = "knowledge:prepare";
    public static final String QUERY_SCOPE = "knowledge:query";
    private ProcessingProtocol() { }

    /** 对齐 Python strip；Java isBlank 不覆盖 NBSP/NEL，空白查询应在本地拒绝。 */
    public static boolean blankText(String text) {
        return text.codePoints().allMatch(value -> Character.isWhitespace(value)
                || Character.isSpaceChar(value) || value == 0x85);
    }
}
