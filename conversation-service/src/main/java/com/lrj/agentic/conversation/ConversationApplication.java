package com.lrj.agentic.conversation;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** 知识会话独立启动入口，不依赖旧平台进程或网关内部实现。 */
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.lrj.agentic.conversation.infrastructure")
public class ConversationApplication {
    /** 启动本项目持久会话服务。 */
    public static void main(String[] args) {
        SpringApplication.run(ConversationApplication.class, args);
    }
}
