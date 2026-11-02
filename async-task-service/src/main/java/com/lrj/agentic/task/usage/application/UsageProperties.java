package com.lrj.agentic.task.usage.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 中央预算仅显式开启；停用不能成为客户端自动回退文件账本的理由。 */
@ConfigurationProperties("usage")
public record UsageProperties(@DefaultValue("LEGACY_LOCAL") Mode mode) {
    public enum Mode {
        LEGACY_LOCAL("LEGACY_LOCAL"), CENTRAL("CENTRAL");
        private final String code;
        Mode(String code) { this.code = code; }
        public String code() { return code; }
    }
    /** 模式是固定部署配置，不接受请求级切换。 */
    public boolean central() { return mode == Mode.CENTRAL; }
}
