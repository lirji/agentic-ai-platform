package com.lrj.agentic.task.usage.domain;

/** 封闭业务集合均有显式稳定code，展示文案与持久值分离。 */
public final class UsageTypes {
    private UsageTypes() { }
    public interface Code { String code(); }
    public enum UsageAccountState implements Code {
        ACTIVE("ACTIVE"), FROZEN("FROZEN"), CLOSED("CLOSED");
        private final String code;
        UsageAccountState(String code) { this.code = code; }
        public String code() { return code; }
    }
    public enum ContextType implements Code {
        TASK("TASK"), KNOWLEDGE("KNOWLEDGE");
        private final String code;
        ContextType(String code) { this.code = code; }
        public String code() { return code; }
    }
    public enum Purpose implements Code {
        QA("QA"), RESEARCH("RESEARCH"), ASSISTANT("ASSISTANT"), INGESTION("INGESTION"), SEARCH("SEARCH"), EVALUATION("EVALUATION"), MEMORY("MEMORY");
        private final String code;
        Purpose(String code) { this.code = code; }
        public String code() { return code; }
    }
    public enum Outcome implements Code {
        SUCCEEDED("SUCCEEDED"), FAILED("FAILED"), CANCELLED("CANCELLED"), UNKNOWN("UNKNOWN");
        private final String code;
        Outcome(String code) { this.code = code; }
        public String code() { return code; }
    }
    public enum Event implements Code {
        INITIALIZED("INITIALIZED"), CONFIGURED("CONFIGURED"), RESERVED("RESERVED"), DISPATCHED("DISPATCHED"), RELEASED("RELEASED"), UNKNOWN("UNKNOWN"), SETTLED("SETTLED"), IMPORTED("IMPORTED");
        private final String code;
        Event(String code) { this.code = code; }
        public String code() { return code; }
    }
    /** 明确拒绝未知code，不按名字、ordinal或展示文案猜测业务类型。 */
    public static <E extends Enum<E> & Code> E parse(E[] values, String code, UsageError error) {
        for (E value : values) { if (value.code().equals(code)) { return value; } }
        throw new UsageFailure(error);
    }
}
