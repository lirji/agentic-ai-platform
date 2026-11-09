package com.lrj.agentic.conversation.domain;

/** 记忆治理固定协议；稳定 code 与 Java 枚举名字分离，模型不能调用确认操作。 */
public final class MemoryProtocol {
    public static final String COMMAND = "memory-command-v1";
    public static final String SNAPSHOT = "memory-snapshot-v1";
    public static final String RECEIPT = "memory-receipt-v1";
    public static final int DEFAULT_DAYS = 90;
    public static final int MAX_DAYS = 3650;
    public static final int MAX_ENTRIES = 100;
    public static final int MAX_CONTENT = 500;
    public static final int TRANSACTION_SECONDS = 5;
    private MemoryProtocol() { }

    /** 只支持用户已确认的两种用途。 */
    public enum Category {
        PREFERENCE("PREFERENCE"), PROJECT_CONTEXT("PROJECT_CONTEXT");
        private final String code;
        Category(String code) { this.code = code; }
        /** 持久化与API只使用显式稳定code。 */
        public String code() { return code; }
        /** 未知用途拒绝输入，不能默认归类。 */
        public static Category parse(String code) {
            for (var value : values()) { if (value.code.equals(code)) { return value; } }
            throw new ConversationFailure(ConversationError.INPUT_INVALID);
        }
    }
    /** 过期是数据库当前时间下的读取状态，不依赖调度器刷新。 */
    public enum State {
        PENDING("PENDING"), ACTIVE("ACTIVE"), DISABLED("DISABLED"),
        FORGOTTEN("FORGOTTEN"), REJECTED("REJECTED"), EXPIRED("EXPIRED");
        private final String code;
        State(String code) { this.code = code; }
        /** 持久化与API只使用显式稳定code。 */
        public String code() { return code; }
    }
    /** 所有修改经过同一聚合行锁和无正文幂等账本。 */
    public enum Action {
        CREATE("CREATE"), EDIT("EDIT"), CONFIRM("CONFIRM"), DISABLE("DISABLE"),
        FORGET("FORGET"), REJECT("REJECT"), CLEAR("CLEAR"), SET_ENABLED("SET_ENABLED");
        private final String code;
        Action(String code) { this.code = code; }
        /** 持久化与API只使用显式稳定code。 */
        public String code() { return code; }
        /** 撤销类动作使已捕获旧代次的模型任务失效。 */
        public boolean invalidatesProposals() {
            return this == FORGET || this == REJECT || this == CLEAR || this == SET_ENABLED;
        }
        /** 未知操作不能降级为普通编辑。 */
        public static Action parse(String code) {
            for (var value : values()) { if (value.code.equals(code)) { return value; } }
            throw new ConversationFailure(ConversationError.INPUT_INVALID);
        }
    }
}
