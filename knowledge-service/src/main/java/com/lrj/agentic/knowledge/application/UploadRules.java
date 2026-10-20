package com.lrj.agentic.knowledge.application;

import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.ContentDigest;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/** 首条契约的输入边界集中维护，避免控制器和存储分别接受不同内容。 */
public final class UploadRules {
    public static final int MAX_BYTES = 1024 * 1024;
    public static final int MAX_DISPLAY_NAME = 200;
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{16,128}");

    private UploadRules() { }

    /** 在任何存储副作用之前验证输入，文件名只参与展示和格式识别。 */
    public static Upload validate(String idempotencyKey, String fileName, String displayName, byte[] bytes) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()
                || bytes == null || bytes.length == 0 || fileName == null) {
            throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID);
        }
        if (bytes.length > MAX_BYTES) {
            throw new KnowledgeFailure(KnowledgeError.FILE_TOO_LARGE);
        }
        String name = displayName == null ? fileName : displayName;
        if (name.isBlank() || name.length() > MAX_DISPLAY_NAME
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID);
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        String contentType;
        if (lower.endsWith(".txt")) {
            contentType = "text/plain";
        } else if (lower.endsWith(".md")) {
            contentType = "text/markdown";
        } else {
            throw new KnowledgeFailure(KnowledgeError.UNSUPPORTED_FORMAT);
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (text.isBlank() || text.indexOf('\0') >= 0) {
                throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID);
            }
        } catch (CharacterCodingException ignored) {
            throw new KnowledgeFailure(KnowledgeError.UNSUPPORTED_FORMAT);
        }
        String hash = ContentDigest.sha256(bytes);
        // 使用不允许出现在展示名中的 NUL 分隔，避免不同字段拼接产生相同指纹。
        String fingerprint = ContentDigest.sha256((name + '\0' + contentType + '\0' + hash)
                .getBytes(StandardCharsets.UTF_8));
        return new Upload(name, contentType, hash, fingerprint, bytes.clone());
    }

    /** 输入仅在一次受理用例内持有，不进入全局可变状态。 */
    public record Upload(String displayName, String contentType, String hash,
                         String fingerprint, byte[] bytes) { }
}
