package com.lrj.agentic.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 候选写入口默认关闭；原命令回执始终可按owner恢复。 */
@ConfigurationProperties("knowledge.operations")
public record CandidateProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean publicationEnabled) {
    /** 存在兼容构造器时显式指定配置绑定入口，两个写门禁均安全默认关闭。 */
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public CandidateProperties { }
    /** 旧候选配置不隐式授权发布，兼容内部只启用候选的调用者。 */
    public CandidateProperties(boolean enabled) { this(enabled, false); }
}
