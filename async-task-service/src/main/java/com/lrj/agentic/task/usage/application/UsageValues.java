package com.lrj.agentic.task.usage.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.task.domain.TaskError;
import com.lrj.agentic.task.domain.TaskFailure;
import com.lrj.agentic.task.usage.contract.UsageDtos.*;
import com.lrj.agentic.task.usage.contract.UsagePrincipal;
import com.lrj.agentic.task.usage.domain.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 严格协议校验与规范摘要，数据库JSON格式不能影响命令重放身份。 */
@Component
public final class UsageValues {
    private final ObjectMapper json;
    public UsageValues(ObjectMapper json) { this.json = json; }
    /** 共用原Task输入错误目录，避免出现第二套400格式。 */
    public static void input(boolean accepted) { if (!accepted) { throw new TaskFailure(TaskError.INPUT_INVALID); } }
    /** 只接受规范小写UUID，避免多种文本表示绕过去重。 */
    public static String id(String value) {
        input(value != null && value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"));
        input(UUID.fromString(value).toString().equals(value)); return value;
    }
    /** 固定ASCII引用不能包含控制符、空白或正文。 */
    public static String key(String value, int max) {
        input(value != null && !value.isEmpty() && value.length() <= max && value.matches("[A-Za-z0-9._:-]+"));
        return value;
    }
    /** 已验签身份仍做数据库宽度和Unicode边界校验。 */
    public static void principal(UsagePrincipal principal) {
        input(principal != null && principal.caller() != null);
        identity(principal.caller().tenantId()); identity(principal.caller().userId());
        digest(principal.sourceRef());
        if (principal.traceId() != null) { key(principal.traceId(), 128); }
    }
    /** 标识允许中文但禁止无效代理字符、NUL和超长字符串。 */
    public static void identity(String value) {
        input(value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= 128
                && value.codePoints().noneMatch(c -> c == 0 || c >= 0xD800 && c <= 0xDFFF));
    }
    /** 摘要只传固定十六进制，不能把正文误写进审计字段。 */
    public static void digest(String value) { input(value != null && value.matches("[a-f0-9]{64}")); }
    /** 预留命令形状校验在开事务之前完成。 */
    public static void reserve(Reserve command) {
        input(command != null); id(command.attemptId());
        lookup(new Lookup(command.schemaVersion(), command.contextType(), command.contextId(), command.epoch(),
                command.stepKey(), command.purpose(), command.kind(), command.profileId(), command.inputDigest()));
        input(command.inputBytes() != null && command.maxOutputTokens() != null);
        if (command.retryOf() != null) { id(command.retryOf()); input(!command.attemptId().equals(command.retryOf())); }
    }
    /** 恢复检索的业务身份与预留完全一致，但它不接收或改变额度。 */
    public static void lookup(Lookup command) {
        input(command != null); schema(command.schemaVersion());
        var type = UsageTypes.parse(UsageTypes.ContextType.values(), command.contextType(), UsageError.PROFILE_INVALID);
        key(command.contextId(), 128); key(command.stepKey(), 128); key(command.profileId(), 64);
        UsageKind.parse(command.kind()); UsageTypes.parse(UsageTypes.Purpose.values(), command.purpose(), UsageError.PROFILE_INVALID);
        digest(command.inputDigest());
        input(type == UsageTypes.ContextType.TASK ? command.epoch() != null && command.epoch() > 0 : command.epoch() == null);
        if (type == UsageTypes.ContextType.TASK) { id(command.contextId()); }
    }
    /** 只有明确完整计量或明确未知两种报告，不把缺失usage补为零。 */
    public static void report(Report command) {
        input(command != null); schema(command.schemaVersion());
        UsageFailure.require(UsageProtocol.COMPLETE.equals(command.status()) || UsageProtocol.UNKNOWN.equals(command.status()), UsageError.REPORT_INVALID);
        UsageTypes.parse(UsageTypes.Outcome.values(), command.outcome(), UsageError.REPORT_INVALID);
        UsageFailure.require(UsageProtocol.COMPLETE.equals(command.status()) ? command.usage() != null : command.usage() == null, UsageError.REPORT_INVALID);
        if (command.providerRequestId() != null) { key(command.providerRequestId(), 128); }
    }
    /** 不兼容版本显式拒绝，不猜客户端意图。 */
    public static void schema(String schema) { input(UsageProtocol.SCHEMA.equals(schema)); }
    /** record字段顺序固定；所有写方用同一序列化产生摘要。 */
    public String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new TaskFailure(TaskError.INPUT_INVALID); }
    }
    /** 配置或持久状态损坏时拒绝，绝不默认返回空计量。 */
    public <T> T read(String value, Class<T> type, UsageError error) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException | IllegalArgumentException e) { throw new UsageFailure(error); }
    }
    /** 业务摘要与追踪标识独立。 */
    public String hash(Object value) { return sha(write(value)); }
    /** 原始UTF-8摘要只用于规范报文或已冻结的快照。 */
    public static String sha(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256不可用"); }
    }
}
