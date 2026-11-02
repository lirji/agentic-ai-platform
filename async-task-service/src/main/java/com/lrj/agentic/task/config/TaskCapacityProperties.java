package com.lrj.agentic.task.config;

import com.lrj.agentic.task.domain.TaskError;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 份额不能借用；配置内求和保证任意实例数量下租户总配额不超过全局上限。 */
@ConfigurationProperties(prefix="task.capacity", ignoreUnknownFields=false)
public record TaskCapacityProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("2") int maxInteractive, @DefaultValue("2") int maxBackground,
        Map<String, Share> tenants) {
    public static final int MAX_TENANTS = 32, MAX_CAPACITY = 64, MAX_QUEUED = 1000;

    /** 租户来源必须固定，不接受请求里的任意配额或未分配租户。 */
    public TaskCapacityProperties {
        tenants = tenants == null ? Map.of() : Map.copyOf(new TreeMap<>(tenants));
        if (maxInteractive < 1 || maxInteractive > MAX_CAPACITY || maxBackground < 1
                || maxBackground > MAX_CAPACITY || tenants.size() > MAX_TENANTS
                || enabled && tenants.isEmpty()
                || tenants.keySet().stream().anyMatch(key -> !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
                || tenants.values().stream().mapToInt(Share::interactive).sum() > maxInteractive
                || tenants.values().stream().mapToInt(Share::background).sum() > maxBackground) {
            throw new IllegalArgumentException(TaskError.CAPACITY_CONFIG_INVALID.message());
        }
    }

    /** 交互与后台可分别禁用；已接受的排队任务不受其他lane占额影响。 */
    public record Share(int interactive, int background, int queuedPerLane) {
        public Share {
            if (interactive < 0 || interactive > MAX_CAPACITY || background < 0 || background > MAX_CAPACITY
                    || interactive + background == 0 || queuedPerLane < Math.max(interactive, background)
                    || queuedPerLane < 1 || queuedPerLane > MAX_QUEUED) {
                throw new IllegalArgumentException(TaskError.CAPACITY_CONFIG_INVALID.message());
            }
        }
    }
}
