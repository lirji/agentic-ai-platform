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
    public record Retry(@NotNull @Min(0) Long expectedRevision) { }
    public record Conversation(String conversationId, String title, long revision,
            Instant createdAt, Instant updatedAt) { }
    public record Turn(String turnId, String conversationId, long sequence, String question,
            List<String> documentIds, String contextTurnId, String taskId, DispatchState dispatchState,
            int dispatchAttempts, int retryCount, long revision, String errorCode,
            Instant createdAt, Instant updatedAt) { }
    public record Conversations(List<Conversation> items, String nextCursor) { }
    public record Turns(List<Turn> items, Long nextCursor) { }
}
