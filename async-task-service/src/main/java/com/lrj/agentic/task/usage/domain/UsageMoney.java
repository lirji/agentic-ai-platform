package com.lrj.agentic.task.usage.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** CNY六位小数；所有API金额都保持十进制字符串，不经过浮点数。 */
public final class UsageMoney {
    public static final String CURRENCY = "CNY";
    public static final int SCALE = 6;
    public static final BigDecimal ZERO = new BigDecimal("0.000000");
    public static final BigDecimal MAX = new BigDecimal("99999999999999.999999");
    public static final BigDecimal MILLION = new BigDecimal("1000000");
    public static final BigDecimal MIN_RESERVATION = new BigDecimal("0.010000");
    public static final BigDecimal RESERVE_MARGIN = new BigDecimal("2");
    private UsageMoney() { }

    /** 拒绝指数、符号、非规范小数和超出数据库精度的值。 */
    public static BigDecimal parse(String text, UsageError error) {
        UsageFailure.require(text != null && text.matches("(?:0|[1-9][0-9]{0,13})\\.[0-9]{6}"), error);
        return new BigDecimal(text);
    }
    /** 向上舍入以免六位精度截断造成少占额。 */
    public static BigDecimal ceiling(BigDecimal amount, UsageError error) {
        UsageFailure.require(amount != null && amount.signum() >= 0 && amount.compareTo(MAX) <= 0, error);
        BigDecimal result = amount.setScale(SCALE, RoundingMode.CEILING);
        UsageFailure.require(result.compareTo(MAX) <= 0, error);
        return result;
    }
    /** 持久值必须本来就符合精度，不能在输出时悄悄修改账目。 */
    public static String text(BigDecimal amount) {
        return amount == null ? null : amount.setScale(SCALE, RoundingMode.UNNECESSARY).toPlainString();
    }
}
