package com.lrj.agentic.knowledge.infrastructure.processing;

import com.lrj.agentic.knowledge.domain.KnowledgeError;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 只绑定已由Tasks登记的profile，Knowledge不能选择资金池、价格或供应商地址。 */
@ConfigurationProperties("usage")
public record KnowledgeUsageProperties(Mode mode, String embeddingProfile, String rerankProfile) {
    /** 默认保留旧模式；显式中央模式缺profile时启动失败，不能静默回退。 */
    public KnowledgeUsageProperties {
        mode = mode == null ? Mode.LEGACY_LOCAL : mode;
        embeddingProfile = empty(embeddingProfile); rerankProfile = empty(rerankProfile);
        if (mode == Mode.CENTRAL && (!profile(embeddingProfile)
                || rerankProfile != null && (!profile(rerankProfile) || rerankProfile.equals(embeddingProfile)))
                || mode == Mode.LEGACY_LOCAL && (embeddingProfile != null || rerankProfile != null)) {
            throw new IllegalArgumentException(KnowledgeError.PROCESSOR_UNAVAILABLE.message());
        }
    }
    private static String empty(String value) { return value == null || value.isEmpty() ? null : value; }
    private static boolean profile(String value) { return value != null && value.matches("[A-Za-z0-9._:-]{1,64}"); }
    /** 两种部署权威互斥，未知枚举由类型化配置绑定拒绝。 */
    public enum Mode { LEGACY_LOCAL, CENTRAL }
    /** 知识入口只能签发自身两种用途；任务用途由Tasks租约解析。 */
    public enum Purpose {
        INGESTION("INGESTION"), SEARCH("SEARCH");
        private final String code;
        Purpose(String code) { this.code = code; }
        /** 固定协议code与展示文字独立。 */
        public String code() { return code; }
    }
    /** 线格式的模型种类白名单，不开放聊天或裁判。 */
    public enum Kind {
        EMBEDDING("EMBEDDING"), RERANK("RERANK");
        private final String code;
        Kind(String code) { this.code = code; }
        /** 固定协议code与展示文字独立。 */
        public String code() { return code; }
    }
}
