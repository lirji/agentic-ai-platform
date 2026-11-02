package com.lrj.agentic.task;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** 中央任务独立启动入口，不依赖旧平台进程或网关内部实现。 */
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan({"com.lrj.agentic.task.infrastructure", "com.lrj.agentic.task.usage.infrastructure"})
public class TaskApplication {
    /** 启动本项目持久任务服务。 */
    public static void main(String[] args) {
        var context = SpringApplication.run(TaskApplication.class, args);
        // 管理命令完成即关闭连接池；正常Web服务继续运行，不能依赖调用方强杀退出。
        if (context.getEnvironment().getProperty("usage.admin.enabled", Boolean.class, false)) {
            SpringApplication.exit(context);
        }
    }
}
