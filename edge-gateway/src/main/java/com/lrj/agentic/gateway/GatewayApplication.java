package com.lrj.agentic.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** 独立 API 入口；领域服务不依赖旧项目网关。 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayApplication {
    /** 外部 IAM、内部签名材料缺失时由绑定和启动校验拒绝运行。 */
    public static void main(String[] args) { SpringApplication.run(GatewayApplication.class, args); }
}
