package com.lrj.agentic.task.usage.application;

import com.lrj.agentic.task.usage.domain.UsageError;
import com.lrj.agentic.task.usage.domain.UsageFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

/** 显式启用的一次性运维进程；误放到Web启动配置时立即失败，不能变成隐式管理接口。 */
@Component
@ConditionalOnProperty(name = "usage.admin.enabled", havingValue = "true")
public final class UsageAdminRunner implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(UsageAdminRunner.class);
    private final ApplicationContext context;
    private final UsageAdminFiles files;
    private final UsageAdminService service;
    private final UsageValues values;
    private final String manifest;
    public UsageAdminRunner(ApplicationContext context, UsageAdminFiles files, UsageAdminService service,
            UsageValues values, @Value("${usage.admin.manifest}") String manifest) {
        this.context = context; this.files = files; this.service = service; this.values = values; this.manifest = manifest;
    }
    /** 操作主体取本机进程用户，manifest不允许伪造用户或传入模型密钥。 */
    @Override public void run(ApplicationArguments args) {
        UsageFailure.require(!(context instanceof WebApplicationContext), UsageError.DISABLED);
        String operator = "local:" + System.getProperty("user.name");
        var result = service.execute(files.manifest(manifest), operator);
        LOG.info("usage_admin_result={}", values.write(result));
    }
}
