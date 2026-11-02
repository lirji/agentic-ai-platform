package com.lrj.agentic.task;

import com.lrj.agentic.task.usage.domain.*;
import com.lrj.agentic.task.usage.application.UsageValues;
import com.lrj.agentic.task.usage.contract.UsageDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 费用边界不能靠浮点近似或缺失计量按零兜底。 */
class UsagePriceTest {
    private final UsagePrice chat = new UsagePrice("test-chat", UsagePrice.INPUT_OUTPUT,
            "2.000000", "8.000000", null, 65536, 1024, 0);

    @Test void utf8RequestDigestMatchesPythonGoldenWithoutPersistingBody() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var values = new UsageValues(mapper);
        record DigestInput(String profileId, String requestSha256) { }
        try (var source = getClass().getResourceAsStream("/usage-golden.json")) {
            var fixture = mapper.readTree(source);
            assertThat(values.hash(new DigestInput(fixture.path("profileId").asText(), UsageValues.sha(fixture.path("requestBody").asText()))))
                    .isEqualTo(fixture.path("inputDigest").asText());
            var settled = mapper.treeToValue(fixture.path("settled"), UsageDtos.Attempt.class);
            var report = mapper.treeToValue(fixture.path("report"), UsageDtos.Report.class);
            assertThat(settled.usage()).isEqualTo(report.usage());
            assertThat(settled.settledAmount()).isEqualTo("0.000020");
        }
    }

    @Test void reservesInputOverheadOutputLimitAndMarginBeforeSending() {
        assertThat(chat.reserve(UsageKind.CHAT, 10000, 1024, null, null, null)).isEqualByComparingTo("0.058432");
        assertThat(chat.reserve(UsageKind.CHAT, 1, 1, null, null, null)).isEqualByComparingTo("0.010000");
        assertThat(chat.settle(UsageKind.CHAT, 10000L, 1024L, 11024L)).isEqualByComparingTo("0.028192");
    }
    @Test void repeatsRerankQueryForEveryDocument() {
        var price = new UsagePrice("test-rerank", UsagePrice.TOTAL, null, null, "10.000000", 65536, 0, 10);
        assertThat(price.reserve(UsageKind.RERANK, 1100, 0, 100, 1000, 5)).isEqualByComparingTo("0.081200");
        rejects(UsageError.PROFILE_INVALID, () -> price.reserve(UsageKind.RERANK, 1099, 0, 100, 1000, 5));
        rejects(UsageError.PROFILE_INVALID, () -> price.reserve(UsageKind.RERANK, 1100, 0, 100, 1000, 11));
    }
    @Test void neverSettlesMissingOrContradictoryUsageAsZero() {
        rejects(UsageError.REPORT_INVALID, () -> chat.settle(UsageKind.CHAT, 1L, null, 1L));
        rejects(UsageError.REPORT_INVALID, () -> chat.settle(UsageKind.CHAT, -1L, 0L, null));
        rejects(UsageError.REPORT_INVALID, () -> chat.settle(UsageKind.CHAT, 1L, 2L, 2L));
        rejects(UsageError.REPORT_INVALID, () -> chat.settle(UsageKind.CHAT, 2147483648L, 0L, null));
        // 合法但超过配置输出上限的实际计量仍保留，聚合负责冻结，不能丢掉费用事实。
        assertThat(chat.settle(UsageKind.CHAT, 1L, 2000L, null)).isEqualByComparingTo("0.016002");
    }
    @Test void roundsPositiveFractionUpAndRejectsNonCanonicalAmounts() {
        var tiny = new UsagePrice("test-tiny", UsagePrice.TOTAL, null, null, "0.000001", 1024, 0, 0);
        assertThat(tiny.settle(UsageKind.EMBEDDING, null, null, 1L)).isEqualByComparingTo("0.000001");
        assertThat(tiny.settle(UsageKind.EMBEDDING, null, null, 0L)).isEqualByComparingTo("0.000000");
        for (String bad : new String[]{"1", "1.0", "1e0", "+1.000000", "-1.000000", "01.000000", "NaN", "100000000000000.000000"}) {
            rejects(UsageError.PROFILE_INVALID, () -> UsageMoney.parse(bad, UsageError.PROFILE_INVALID));
        }
        assertThat(UsageMoney.text(new BigDecimal("0.01"))).isEqualTo("0.010000");
    }
    @Test void rejectsWrongUseAndUnsupportedPriceBeforeAnyReservation() {
        rejects(UsageError.PROFILE_INVALID, () -> chat.validate(UsageKind.EMBEDDING));
        rejects(UsageError.PROFILE_INVALID, () -> chat.reserve(UsageKind.JUDGE, 65537, 10, null, null, null));
        rejects(UsageError.PROFILE_INVALID, () -> chat.reserve(UsageKind.CHAT, 100, 0, null, null, null));
        rejects(UsageError.PROFILE_INVALID, () -> UsageKind.parse("chat"));
        assertThat(UsageState.DISPATCHED.reportable()).isTrue();
        assertThat(UsageState.RESERVED.reportable()).isFalse();
    }
    private static void rejects(UsageError code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(UsageFailure.class, e -> assertThat(e.error()).isEqualTo(code));
    }
}
