package com.lrj.agentic.task.usage.domain;

/** usage内部的固定值；对外身份受众另由公共契约登记。 */
public final class UsageProtocol {
    public static final String SCHEMA = "usage-v1";
    public static final String PERIOD = "LIFETIME";
    public static final String COMPLETE = "COMPLETE";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String NOT_DISPATCHED = "NOT_DISPATCHED";
    public static final String NONE = "NONE";
    public static final String PROVIDER_REPORTED = "PROVIDER_REPORTED";
    public static final String LEGACY_IMPORT = "LEGACY_IMPORT";
    public static final String LEGACY_UNATTRIBUTED = "LEGACY_UNATTRIBUTED";
    public static final int MAX_BODY = 32 * 1024;
    public static final int MAX_PAGE = 50;
    public static final int TRANSACTION_SECONDS = 5;
    private UsageProtocol() { }
}
