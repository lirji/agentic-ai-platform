package com.lrj.agentic.task.usage.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.task.usage.contract.UsageAdminDtos.*;
import com.lrj.agentic.task.usage.domain.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.lrj.agentic.task.usage.contract.UsageAdminDtos.*;
import static com.lrj.agentic.task.usage.application.UsageValues.*;

/** 文件读取和哈希在事务外完成；拒绝弱类型、重复键、符号链接和不私密文件。 */
@Component
public final class UsageAdminFiles {
    private final ObjectMapper json;
    public UsageAdminFiles(ObjectMapper json) { this.json = json; }

    /** manifest不得由公网请求提供，只接受本机受控绝对路径。 */
    public Manifest manifest(String file) {
        try { return json.readValue(privateBytes(file), Manifest.class); }
        catch (IOException e) { throw new UsageFailure(UsageError.PROFILE_INVALID); }
    }

    /** 导入前验证冻结标记、原始字节摘要和逐条总和，不从历史字符串猜测用户。 */
    public FrozenSource frozen(Manifest manifest) {
        Source source = manifest.source();
        if (source == null) { return null; }
        try {
            input((source.path() + ".frozen.json").equals(source.proofPath()));
            byte[] raw = privateBytes(source.path());
            // UTF-8必须无替换字符：往返检查防止非法字节形成另一份可解析账本。
            String text = new String(raw, StandardCharsets.UTF_8);
            input(Arrays.equals(raw, text.getBytes(StandardCharsets.UTF_8)));
            input(sha(text).equals(source.sourceSha256()));
            FreezeProof proof = json.readValue(privateBytes(source.proofPath()), FreezeProof.class);
            input(FREEZE_PROTOCOL.equals(proof.protocol()) && source.sourceLedgerId().equals(proof.sourceLedgerId())
                    && source.sourceSha256().equals(proof.sourceSha256()) && manifest.kind().equals(proof.kind())
                    && source.freezeProofRef().equals(proof.freezeProofRef()));
            key(proof.stopEvidenceRef(), 128);
            input(proof.stoppedWriterPids() != null && !proof.stoppedWriterPids().isEmpty()
                    && proof.stoppedWriterPids().size() <= MAX_ENTRIES
                    && proof.stoppedWriterPids().stream().allMatch(pid -> pid != null && pid > 1));
            JsonNode ledger = json.readTree(raw);
            validateShape(ledger, manifest.kind());
            input(legacyAmount(ledger.get("limitCny")).compareTo(new BigDecimal(manifest.limitAmount())) == 0);
            BigDecimal reserved = legacyAmount(ledger.get("reservedCny"));
            input(reserved.compareTo(new BigDecimal(source.reservedAmount())) == 0
                    && reserved.compareTo(new BigDecimal(manifest.limitAmount())) <= 0);
            JsonNode calls = ledger.get("calls");
            input(calls != null && calls.isArray() && calls.size() <= MAX_ENTRIES && calls.size() == source.entriesCount());
            List<Entry> entries = new ArrayList<>(); Set<String> ids = new HashSet<>();
            BigDecimal sum = UsageMoney.ZERO;
            for (JsonNode call : calls) {
                input(call.isObject());
                String id = id(string(call.get("id")));
                input(ids.add(id));
                BigDecimal amount = legacyAmount(call.get("reservedCny"));
                input(amount.signum() > 0);
                String status = string(call.get("status"));
                // 早期Embedding smoke记录HTTP状态；它仍是保守占额，不能按200结算或按错误退款。
                input(UsageState.RESERVED.code().equals(status) || UsageKind.EMBEDDING.code().equals(manifest.kind()) && status.matches("HTTP_[1-5][0-9]{2}"));
                entries.add(new Entry(id, amount)); sum = sum.add(amount);
            }
            input(sum.compareTo(reserved) == 0);
            return new FrozenSource(List.copyOf(entries));
        } catch (IOException | IllegalArgumentException e) { throw new UsageFailure(UsageError.PROFILE_INVALID); }
    }
    private static void validateShape(JsonNode ledger, String kind) {
        input(ledger != null && ledger.isObject());
        Set<String> fields = new HashSet<>(); ledger.fieldNames().forEachRemaining(fields::add);
        if (UsageKind.EMBEDDING.code().equals(kind)) {
            input(fields.equals(Set.of("limitCny", "reservedCny", "calls")));
        } else {
            Set<String> expected = new HashSet<>(Set.of("version", "scope", "currency", "limitCny", "reservedCny", "calls"));
            if (UsageKind.RERANK.code().equals(kind)) { expected.add("configFingerprint"); }
            input(fields.equals(expected) && ledger.path("version").isIntegralNumber() && ledger.path("version").canConvertToInt() && ledger.path("version").intValue() == 1
                    && "CNY".equals(string(ledger.get("currency"))));
            input(UsageKind.CHAT.code().equals(kind) ? "chat-integration-20261020".equals(string(ledger.get("scope")))
                    : UsageKind.RERANK.code().equals(kind) && "rerank-integration-v1".equals(string(ledger.get("scope"))));
        }
    }
    private static BigDecimal legacyAmount(JsonNode node) {
        String value = string(node);
        input(value.matches("(?:0|[1-9][0-9]{0,13})(?:\\.[0-9]{1,6})?"));
        return new BigDecimal(value).setScale(UsageMoney.SCALE);
    }
    private static String string(JsonNode node) { input(node != null && node.isTextual()); return node.textValue(); }
    private static byte[] privateBytes(String name) throws IOException {
        input(name != null);
        Path path = Path.of(name);
        input(path.isAbsolute() && path.normalize().equals(path) && path.toRealPath().equals(path)
                && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rw-------")));
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(MAX_FILE_BYTES + 1);
            input(bytes.length > 0 && bytes.length <= MAX_FILE_BYTES); return bytes;
        }
    }
}
