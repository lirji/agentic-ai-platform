package com.lrj.agentic.task.application;

import com.lrj.agentic.task.api.TaskDtos.*;
import com.lrj.agentic.task.domain.*;
import com.lrj.agentic.contract.knowledge.RetrievalStrategy;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

/** 事务前冻结输入；排序只规范知识范围，不改写用户问题。 */
public final class WorkRequests {
    private WorkRequests() { }
    /** 新路径只增加显式策略，不能借版本入口扩大工具能力或知识范围。 */
    public static WorkInput validateGeneration(GenerationWorkSubmit command) {
        if (command == null) { throw new TaskFailure(TaskError.INPUT_INVALID); }
        var original = validate(new WorkSubmit(command.taskId(), command.kind(), command.question(), command.documentIds(), command.mode()));
        try { RetrievalStrategy.consumer(command.retrievalStrategy()); }
        catch (IllegalArgumentException invalid) { throw new TaskFailure(TaskError.INPUT_INVALID); }
        return new WorkInput(TaskProtocol.WORK_INPUT_V2, original.kind(), original.question(), original.documentIds(),
                original.mode(), command.retrievalStrategy());
    }
    /** 公共入口仅生成已注册的研究/只读输入，身份不进入可控正文。 */
    public static WorkInput validate(WorkSubmit command) {
        TaskValues.id(command.taskId());
        var kind = WorkKind.parse(command.kind());
        var mode = WorkMode.parse(command.mode(), kind);
        String question = command.question();
        if (question == null || question.isEmpty() || question.codePoints().allMatch(c ->
                Character.isWhitespace(c) || Character.isSpaceChar(c)) || question.indexOf('\0') >= 0
                || !StandardCharsets.UTF_8.newEncoder().canEncode(question)
                || question.codePointCount(0, question.length()) > TaskProtocol.MAX_QUESTION
                || command.documentIds() == null || command.documentIds().size() > TaskProtocol.MAX_DOCUMENTS) {
            throw new TaskFailure(TaskError.INPUT_INVALID);
        }
        command.documentIds().forEach(TaskValues::id);
        if (new HashSet<>(command.documentIds()).size() != command.documentIds().size()) {
            throw new TaskFailure(TaskError.INPUT_INVALID);
        }
        return new WorkInput(TaskProtocol.WORK_INPUT, kind.code(), question,
                command.documentIds().stream().sorted().toList(), mode.code());
    }
}
