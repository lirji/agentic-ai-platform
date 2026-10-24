package com.lrj.agentic.contract.knowledge;

import com.lrj.agentic.contract.ErrorCode;

/** 上传链路共用边界；multipart 为文件以外的字段和边界保留 1 MiB。 */
public final class KnowledgeUploadProtocol {
    public static final String PATH = "/api/v1/knowledge/ingestions";
    public static final int MAX_REQUEST_BYTES = ExtractionProtocol.MAX_SOURCE_BYTES + 1024 * 1024;

    private KnowledgeUploadProtocol() { }

    /** 网关和领域共用相同错误定义，避免代理拒绝时改变已发布错误契约。 */
    public enum Failure implements ErrorCode {
        FILE_TOO_LARGE("KNOWLEDGE_FILE_TOO_LARGE", "资料大小超过当前允许范围。", 413);

        private final String code;
        private final String message;
        private final int httpStatus;

        Failure(String code, String message, int httpStatus) {
            this.code = code; this.message = message; this.httpStatus = httpStatus;
        }

        /** 各上传入口共同使用的稳定业务编码。 */
        public String code() { return code; }
        /** 不含原文件内容或内部异常的默认提示。 */
        public String message() { return message; }
        /** 保持已发布的超限HTTP状态。 */
        public int httpStatus() { return httpStatus; }
    }
}
