package com.lrj.agentic.task.domain;

/** 研究调用额度按稳定业务code分类；一次搜索工具同时消费工具和检索额度。 */
public enum ResearchCall {
    MODEL("MODEL", 1, 0, 0),
    INITIAL_SEARCH("INITIAL_SEARCH", 0, 0, 1),
    SEARCH_TOOL("SEARCH_TOOL", 0, 1, 1),
    READ_TOOL("READ_TOOL", 0, 1, 0);

    public static final int MAX_MODELS = 3;
    public static final int MAX_TOOLS = 5;
    public static final int MAX_SEARCHES = 3;
    public static final int MAX_EXTRA_SEARCHES = 2;
    public static final int MAX_RESERVATIONS = MAX_MODELS + MAX_TOOLS + 1;
    public static final String KEY_PREFIX = "b:";
    public static final String STAGE = "RESEARCH_ALLOWANCE";
    private final String code;
    private final int models;
    private final int tools;
    private final int searches;

    ResearchCall(String code, int models, int tools, int searches) {
        this.code = code; this.models = models; this.tools = tools; this.searches = searches;
    }
    /** 持久/API稳定值，不依赖ordinal或展示文案。 */
    public String code() { return code; }
    /** 聊天调用增量；包含计划生成。 */
    public int models() { return models; }
    /** 只读工具增量；搜索工具也必须计入。 */
    public int tools() { return tools; }
    /** 检索增量；含初始检索，间接约束Embedding/精排次数。 */
    public int searches() { return searches; }
    /** 未知调用不能绕过计数或自定义消耗权重。 */
    public static ResearchCall parse(String code) {
        for (var call : values()) { if (call.code.equals(code)) { return call; } }
        throw new TaskFailure(TaskError.INPUT_INVALID);
    }
}
