package com.lrj.agentic.task.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.task.domain.TaskError;
import com.lrj.agentic.task.domain.TaskFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 规范 JSON 指纹避免对象键排序变化破坏幂等，不对用户文本做语义归一化。 */
@Component
public class TaskValues {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private final ObjectMapper json;
    /** 沿用服务唯一的严格 JSON 配置。 */
    public TaskValues(ObjectMapper json) { this.json = json; }
    /** 规范 UUID 防止数据库排序/大小写引入第二个资源标识。 */
    public static String id(String value) {
        try {
            if (value != null && UUID.fromString(value).toString().equals(value)) { return value; }
        } catch (IllegalArgumentException ignored) {
            // 原始输入不附在错误中。
            throw new TaskFailure(TaskError.INPUT_INVALID);
        }
        throw new TaskFailure(TaskError.INPUT_INVALID);
    }
    /** 幂等键是标识而不是任意文本，避免控制字符与无界索引。 */
    public static String key(String value) {
        if (value == null || !KEY.matcher(value).matches()) { throw new TaskFailure(TaskError.INPUT_INVALID); }
        return value;
    }
    /** 有界对象序列化，JSON 数组或标量不能冒充命令对象。 */
    public String object(JsonNode node, int limit) {
        if (node == null || !node.isObject()) { throw new TaskFailure(TaskError.INPUT_INVALID); }
        String value = write(node);
        if (value.getBytes(StandardCharsets.UTF_8).length > limit) { throw new TaskFailure(TaskError.INPUT_INVALID); }
        return value;
    }
    /** 对数据类与树采用同一规范化规则。 */
    public String write(Object value) {
        try { return json.writeValueAsString(canonical(json.valueToTree(value))); }
        catch (JsonProcessingException error) { throw new IllegalStateException("任务序列化失败"); }
    }
    /** 只读取服务器持久化的 JSON，不输出解析细节。 */
    public JsonNode read(String value) {
        if (value == null) { return null; }
        try { return json.readTree(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("任务记录无效"); }
    }
    /** 持久凭据与正式 DTO 对齐。 */
    public <T> T read(String value, Class<T> type) {
        if (value == null) { return null; }
        try { return json.readValue(value, type); }
        catch (JsonProcessingException error) { throw new IllegalStateException("任务记录无效"); }
    }
    /** 摘要只用于幂等核对，不替代授权或租约验证。 */
    public static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 不可用"); }
    }
    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var sorted = json.createObjectNode();
            var keys = new TreeSet<String>();
            node.fieldNames().forEachRemaining(keys::add);
            keys.forEach(key -> sorted.set(key, canonical(node.get(key))));
            return sorted;
        }
        if (node.isArray()) {
            var result = json.createArrayNode();
            node.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return node;
    }
}
