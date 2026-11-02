package com.lrj.agentic.task.usage.domain;

import java.math.BigDecimal;

/** 不可变计价快照；费率来自受控配置，调用者只能报告实际输入规模和提供方计量。 */
public record UsagePrice(String model, String unit, String inputRate, String outputRate, String totalRate,
        int maxInputBytes, int maxOutputTokens, int maxDocuments) {
    public static final String INPUT_OUTPUT = "INPUT_OUTPUT";
    public static final String TOTAL = "TOTAL";
    public static final int TOKEN_OVERHEAD = 512;
    public static final int MAX_INPUT_BYTES = 16 * 1024 * 1024;
    public static final int MAX_OUTPUT_TOKENS = 1_000_000;
    public static final int MAX_DOCUMENTS = 100;
    private static final BigDecimal MAX_RATE = new BigDecimal("1000000.000000");

    /** profile建立和使用时都复核，避免数据库坏配置放行未计量请求。 */
    public void validate(UsageKind kind) {
        UsageFailure.require(model != null && model.matches("[A-Za-z0-9._:/-]{1,128}")
                && maxInputBytes > 0 && maxInputBytes <= MAX_INPUT_BYTES
                && maxOutputTokens >= 0 && maxOutputTokens <= MAX_OUTPUT_TOKENS
                && maxDocuments >= 0 && maxDocuments <= MAX_DOCUMENTS, UsageError.PROFILE_INVALID);
        if (kind.generates()) {
            UsageFailure.require(INPUT_OUTPUT.equals(unit) && maxOutputTokens > 0 && totalRate == null
                    && maxDocuments == 0, UsageError.PROFILE_INVALID);
            rate(inputRate); rate(outputRate);
        } else {
            UsageFailure.require(TOTAL.equals(unit) && maxOutputTokens == 0 && inputRate == null
                    && outputRate == null && (kind == UsageKind.RERANK ? maxDocuments > 0 : maxDocuments == 0),
                    UsageError.PROFILE_INVALID);
            rate(totalRate);
        }
    }

    /** 字节只是保守估算；重排的查询要按候选文档数量重复计入。 */
    public BigDecimal reserve(UsageKind kind, int inputBytes, int outputTokens,
            Integer queryBytes, Integer documentBytes, Integer documentCount) {
        validate(kind);
        UsageFailure.require(inputBytes > 0 && inputBytes <= maxInputBytes
                && outputTokens >= 0 && outputTokens <= maxOutputTokens
                && (!kind.generates() || outputTokens > 0), UsageError.PROFILE_INVALID);
        BigDecimal cost;
        if (kind == UsageKind.RERANK) {
            UsageFailure.require(queryBytes != null && documentBytes != null && documentCount != null
                    && queryBytes > 0 && documentBytes > 0 && documentCount > 0 && documentCount <= maxDocuments
                    && (long) queryBytes + documentBytes <= inputBytes, UsageError.PROFILE_INVALID);
            long tokens = ((long) queryBytes + TOKEN_OVERHEAD) * documentCount + documentBytes;
            cost = BigDecimal.valueOf(tokens).multiply(rate(totalRate));
        } else {
            UsageFailure.require(queryBytes == null && documentBytes == null && documentCount == null,
                    UsageError.PROFILE_INVALID);
            BigDecimal tokens = BigDecimal.valueOf((long) inputBytes + TOKEN_OVERHEAD);
            cost = kind.generates() ? tokens.multiply(rate(inputRate))
                    .add(BigDecimal.valueOf(outputTokens).multiply(rate(outputRate)))
                    : tokens.multiply(rate(totalRate));
        }
        return UsageMoney.ceiling(cost.multiply(UsageMoney.RESERVE_MARGIN).divide(UsageMoney.MILLION)
                .max(UsageMoney.MIN_RESERVATION), UsageError.PROFILE_INVALID);
    }

    /** 完整计量才可结算；缺少维度不得自动按零处理。 */
    public BigDecimal settle(UsageKind kind, Long inputTokens, Long outputTokens, Long totalTokens) {
        validate(kind);
        BigDecimal cost;
        if (kind.generates()) {
            long input = tokens(inputTokens), output = tokens(outputTokens);
            UsageFailure.require(totalTokens == null || tokens(totalTokens) == input + output,
                    UsageError.REPORT_INVALID);
            cost = BigDecimal.valueOf(input).multiply(rate(inputRate))
                    .add(BigDecimal.valueOf(output).multiply(rate(outputRate)));
        } else {
            UsageFailure.require(inputTokens == null && outputTokens == null, UsageError.REPORT_INVALID);
            cost = BigDecimal.valueOf(tokens(totalTokens)).multiply(rate(totalRate));
        }
        return UsageMoney.ceiling(cost.divide(UsageMoney.MILLION), UsageError.REPORT_INVALID);
    }
    private static long tokens(Long value) {
        UsageFailure.require(value != null && value >= 0 && value <= Integer.MAX_VALUE, UsageError.REPORT_INVALID);
        return value;
    }
    private static BigDecimal rate(String value) {
        BigDecimal parsed = UsageMoney.parse(value, UsageError.PROFILE_INVALID);
        UsageFailure.require(parsed.signum() > 0 && parsed.compareTo(MAX_RATE) <= 0, UsageError.PROFILE_INVALID);
        return parsed;
    }
}
