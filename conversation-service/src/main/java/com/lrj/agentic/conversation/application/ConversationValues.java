package com.lrj.agentic.conversation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.conversation.api.ConversationDtos.*;
import com.lrj.agentic.conversation.domain.*;
import com.lrj.agentic.conversation.infrastructure.*;
import com.lrj.agentic.contract.knowledge.RetrievalStrategy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 命令在事务前规范化一次，避免幂等账本和实际执行分别理解同一输入。 */
@Component
public class ConversationValues {
    private final ObjectMapper json;
    /** 复用应用 JSON 规则，避免同一命令在不同入口产生不同摘要。 */
    public ConversationValues(ObjectMapper json) { this.json = json; }

    /** 只接受标准小写 UUID，避免路径、大小写和数据库排序规则产生别名。 */
    public static void id(String id) {
        try {
            if (id != null && UUID.fromString(id).toString().equals(id)) { return; }
        } catch (IllegalArgumentException invalid) {
            throw new ConversationFailure(ConversationError.INPUT_INVALID);
        }
        throw new ConversationFailure(ConversationError.INPUT_INVALID);
    }

    /** 对 Unicode 码点计数，拒绝不完整代理字符，不默默截断用户输入。 */
    public static void text(String value, int maximum) {
        if (value == null || value.isEmpty() || value.codePoints().allMatch(c ->
                Character.isWhitespace(c) || Character.isSpaceChar(c))
                || value.indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(value)
                || value.codePointCount(0, value.length()) > maximum) {
            throw new ConversationFailure(ConversationError.INPUT_INVALID);
        }
    }

    /** 幂等键只保留不可逆摘要，不将用户自定义键写入日志。 */
    public static String key(String value) {
        if (value == null || !value.matches("[\\x21-\\x7e]{1,128}")) {
            throw new ConversationFailure(ConversationError.INPUT_INVALID);
        }
        return digest(value.getBytes(StandardCharsets.US_ASCII));
    }

    /** 知识范围排序后固定，两个排列不能被误认为两次不同命令。 */
    public static Ask validated(Ask value) {
        if (value == null || value.expectedRevision() == null || value.expectedRevision() < 0
                || value.documentIds() == null
                || value.documentIds().size() > ConversationProtocol.MAX_DOCUMENTS) {
            throw new ConversationFailure(ConversationError.INPUT_INVALID);
        }
        text(value.question(), ConversationProtocol.MAX_QUESTION);
        value.documentIds().forEach(ConversationValues::id);
        if (new HashSet<>(value.documentIds()).size() != value.documentIds().size()) {
            throw new ConversationFailure(ConversationError.INPUT_INVALID);
        }
        if (value.contextTurnId() != null) { id(value.contextTurnId()); }
        return new Ask(value.expectedRevision(), value.question(),
                value.documentIds().stream().sorted().toList(), value.contextTurnId());
    }

    /** 冻结 DTO 字段与规范列表生成稳定请求摘要。 */
    public String fingerprint(Object value) { return digest(write(value).getBytes(StandardCharsets.UTF_8)); }
    /** 序列化失败不拼接对象正文。 */
    public String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException invalid) { throw new ConversationFailure(ConversationError.INPUT_INVALID); }
    }
    /** 持久范围按类型读取，格式漂移不是空范围。 */
    public List<String> documents(String value) {
        try { return List.copyOf(json.readValue(value, new TypeReference<List<String>>() { })); }
        catch (JsonProcessingException | IllegalArgumentException | NullPointerException invalid) {
            throw new ConversationFailure(ConversationError.STORAGE_UNAVAILABLE);
        }
    }
    /** 会话公开视图过滤所有权和幂等内部字段。 */
    public Conversation view(ConversationRow row) {
        return new Conversation(row.conversationId(), row.title(), row.revision(), row.createdAt(), row.updatedAt());
    }
    /** 问题与中央任务关联可查，但不将交接状态转换为执行状态。 */
    public Turn view(TurnRow row) {
        return new Turn(row.turnId(), row.conversationId(), row.sequence(), row.question(),
                documents(row.documentIds()), row.contextTurnId(), row.taskId(), row.dispatchState(),
                row.dispatchAttempts(), row.retryCount(), row.revision(), row.errorCode(),
                row.createdAt(), row.updatedAt(), legacy(row) ? null : row.retrievalStrategy());
    }
    /** 下游输入只能从已提交问题重建，不接受运行时覆盖原问题或身份。 */
    public TaskGateway.QuestionInput input(TurnRow row) {
        return new TaskGateway.QuestionInput(legacy(row) ? ConversationProtocol.INPUT_VERSION : ConversationProtocol.INPUT_VERSION_V2, row.conversationId(),
                row.turnId(), row.question(), documents(row.documentIds()), row.contextTurnId(),
                row.contextTaskId(), row.contextQuestion(), legacy(row) ? null : row.retrievalStrategy());
    }
    /** 只有历史显式策略走旧图，未知持久值由数据库闭集约束拒绝。 */
    public static boolean legacy(TurnRow row) { return RetrievalStrategy.LEGACY.code().equals(row.retrievalStrategy()); }
    /** 固定时间/ID 对形成稳定列表位置，游标不包含身份。 */
    public static String cursor(ConversationRow row) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (row.createdAt() + "\n" + row.conversationId()).getBytes(StandardCharsets.US_ASCII));
    }
    /** 游标长度、精度和 UUID 在进入 Mapper 前校验。 */
    public static Position position(String value) {
        if (value == null) { return new Position(null, null); }
        try {
            if (value.isBlank() || value.length() > 128 || !value.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException();
            }
            String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII)
                    .split("\n", -1);
            if (parts.length != 2) { throw new IllegalArgumentException(); }
            id(parts[1]);
            var at = Instant.parse(parts[0]);
            if (at.getNano() % 1000 != 0) { throw new IllegalArgumentException(); }
            return new Position(at, parts[1]);
        } catch (RuntimeException invalid) { throw new ConversationFailure(ConversationError.INPUT_INVALID); }
    }
    /** 有界游标只携带排序位置，不携带或覆盖调用者身份。 */
    public record Position(Instant at, String id) { }

    private static String digest(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 不可用"); }
    }
}
