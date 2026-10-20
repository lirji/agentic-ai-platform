package com.lrj.agentic.knowledge;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** 知识领域独立启动入口，不依赖旧平台进程或网关内部实现。 */
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.lrj.agentic.knowledge.infrastructure.persistence")
public class KnowledgeApplication {
    /** 启动本项目资料受理与查询服务。 */
    public static void main(String[] args) {
        SpringApplication.run(KnowledgeApplication.class, args);
    }
}
