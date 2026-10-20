package com.lrj.agentic.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 验证用户输入和幂等指纹，避免无效原文产生存储副作用。 */
class UploadRulesTest {
    private static final String KEY = "upload-command-0001";

    @Test
    void bindsNameFormatAndBytesAndProtectsOriginalArray() {
        byte[] source = "知识资料".getBytes(StandardCharsets.UTF_8);
        var first = UploadRules.validate(KEY, "研究.MD", null, source);
        assertThat(first.contentType()).isEqualTo("text/markdown");
        assertThat(first.hash()).hasSize(64);
        assertThat(UploadRules.validate(KEY, "研究.MD", null, source).fingerprint())
                .isEqualTo(first.fingerprint());
        assertThat(UploadRules.validate(KEY, "研究.MD", "不同名称", source).fingerprint())
                .isNotEqualTo(first.fingerprint());
        assertThat(UploadRules.validate(KEY, "研究.txt", "研究.MD", source).fingerprint())
                .isNotEqualTo(first.fingerprint());
        source[0] = 0;
        assertThat(first.bytes()[0]).isNotZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\n\t", "hello\u0000world"})
    void rejectsEmptyWhitespaceAndNul(String text) {
        assertError(KnowledgeError.INPUT_INVALID, () ->
                UploadRules.validate(KEY, "a.txt", null, text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsWrongEncodingFormatNameAndKey() {
        assertError(KnowledgeError.UNSUPPORTED_FORMAT, () ->
                UploadRules.validate(KEY, "a.txt", null, new byte[]{(byte) 0xc3, 0x28}));
        assertError(KnowledgeError.UNSUPPORTED_FORMAT, () ->
                UploadRules.validate(KEY, "a.pdf", null, new byte[]{65}));
        assertError(KnowledgeError.INPUT_INVALID, () ->
                UploadRules.validate(KEY, "a.txt", "x\n", new byte[]{65}));
        assertError(KnowledgeError.INPUT_INVALID, () ->
                UploadRules.validate("short", "a.txt", null, new byte[]{65}));
    }

    @Test
    void enforcesInclusiveByteBoundary() {
        byte[] accepted = new byte[UploadRules.MAX_BYTES];
        Arrays.fill(accepted, (byte) 'a');
        assertThat(UploadRules.validate(KEY, "a.txt", null, accepted).bytes()).hasSize(UploadRules.MAX_BYTES);
        assertError(KnowledgeError.FILE_TOO_LARGE, () ->
                UploadRules.validate(KEY, "a.txt", null, new byte[UploadRules.MAX_BYTES + 1]));
    }

    private static void assertError(KnowledgeError expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(KnowledgeFailure.class,
                failure -> assertThat(failure.error()).isEqualTo(expected));
    }
}
