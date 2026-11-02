package com.lrj.agentic.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 候选写入口默认关闭；原命令回执始终可按owner恢复。 */
@ConfigurationProperties("knowledge.operations")
public record CandidateProperties(@DefaultValue("false") boolean enabled) { }
