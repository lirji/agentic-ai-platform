package com.lrj.agentic.conversation.api;

import com.lrj.agentic.conversation.domain.DispatchState;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/** 公共协议不泄露分派租约、指纹和身份，正文只返回给资源所有者。 */
public final class ConversationDtos {
    private ConversationDtos() { }
    public record Create(@NotNull String title) { }
    public record Ask(@NotNull @Min(0) Long expectedRevision, @NotNull String question,
            @NotNull List<String> documentIds, String contextTurnId) { }
    /** v2策略是不可变请求的一部分，不接受省略后的部署默认值。 */
    public record AskGeneration(@NotNull @Min(0) Long expectedRevision, @NotNull String question,
            @NotNull List<String> documentIds, String contextTurnId, @NotNull String retrievalStrategy) { }
    /** v3明确声明改写意图；是否实际改写由结果诊断报告。 */
    public record AskContextual(@NotNull @Min(0) Long expectedRevision, @NotNull String question,
            @NotNull List<String> documentIds, String contextTurnId, @NotNull String retrievalStrategy,
            @NotNull String queryPolicy) { }
    /** 仅报告受理配置，不探测worker或消费模型预算。 */
    public record Capabilities(String schemaVersion, boolean contextualQueryEnabled) { }
    public record Retry(@NotNull @Min(0) Long expectedRevision) { }
    public record Conversation(String conversationId, String title, long revision,
            Instant createdAt, Instant updatedAt) { }
    public record Turn(String turnId, String conversationId, long sequence, String question,
            List<String> documentIds, String contextTurnId, String taskId, DispatchState dispatchState,
            int dispatchAttempts, int retryCount, long revision, String errorCode,
            Instant createdAt, Instant updatedAt,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            String retrievalStrategy,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            String queryPolicy) { }
    public record Conversations(List<Conversation> items, String nextCursor) { }
    public record Turns(List<Turn> items, Long nextCursor) { }
}
