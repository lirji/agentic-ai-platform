package com.lrj.agentic.knowledge.application;

import com.lrj.agentic.knowledge.config.LifecycleProperties;
import com.lrj.agentic.knowledge.config.ProcessingAdmissionProperties;
import com.lrj.agentic.knowledge.domain.ContentDigest;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.SourceStore;
import com.lrj.agentic.knowledge.domain.lifecycle.LifecycleModels.*;
import com.lrj.agentic.knowledge.domain.lifecycle.LifecycleRepository;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/** K07显式区分受理和发布；请求未知时沿原键查询，不能另起更新意图。 */
@Service
public class LifecycleService {
    private static final int MAX_LIMIT = 100;
    private static final long FIRST_HISTORY_BOUND = (long) Integer.MAX_VALUE + 1;
    private final LifecycleRepository repository;
    private final SourceStore storage;
    private final LifecycleProperties properties;
    private final ProcessingAdmissionProperties admission;

    /** 使用原源对象端口及profile选择，不新增存储或算法默认。 */
    public LifecycleService(LifecycleRepository repository, SourceStore storage, LifecycleProperties properties,
            ProcessingAdmissionProperties admission) {
        this.repository = repository; this.storage = storage; this.properties = properties; this.admission = admission;
    }

    /** 同键重放可在关闭写开关后恢复，且不重复写原文或分配新代次。 */
    public CommandReceipt update(Caller caller, String id, String key, long expectedRevision, UploadRules.Upload upload) {
        uuid(id); key(key);
        if (expectedRevision < 0 || expectedRevision > Integer.MAX_VALUE) { invalid(); }
        String fingerprint = ContentDigest.sha256(String.join("\0", Operation.UPDATE.code(), id,
                Long.toString(expectedRevision), upload.hash(), upload.contentType(), upload.displayName()).getBytes(StandardCharsets.UTF_8));
        var replay = repository.command(caller, key, fingerprint);
        if (replay.isPresent()) { return replay.get(); }
        document(caller, id);
        if (!properties.updatesEnabled()) { throw new KnowledgeFailure(KnowledgeError.PROCESSING_DISABLED); }
        var source = storage.put(caller, upload.hash(), upload.contentType(), upload.bytes());
        return repository.update(caller, new Update(UUID.randomUUID().toString(), key, fingerprint, id,
                expectedRevision, UUID.randomUUID().toString(), upload.displayName(), source,
                admission.profile(source.contentType()), Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }

    /** 命令回执只属于当前tenant/owner，不包含原文或对象键。 */
    public CommandReceipt command(Caller caller, String key) {
        key(key);
        return repository.command(caller, key, null).orElseThrow(() -> new KnowledgeFailure(KnowledgeError.NOT_FOUND));
    }
    /** 详情包括失败新稿与当前发布两个版本事实。 */
    public DocumentView document(Caller caller, String id) {
        uuid(id);
        return repository.document(caller, id).orElseThrow(() -> new KnowledgeFailure(KnowledgeError.NOT_FOUND));
    }
    /** 游标绑定当前所有者，不能借他人游标改变查询身份或排序范围。 */
    public Page<DocumentView> documents(Caller caller, int limit, String cursor) {
        limit(limit);
        String after = decode(caller, "documents", "-", cursor, "");
        if (!after.isEmpty()) { uuid(after); }
        return page(repository.documents(caller, after, limit + 1), limit, caller, "documents", "-", DocumentView::documentId);
    }
    /** 历史只提供元数据，原文继续遵守当前发布版本读取政策。 */
    public Page<RevisionView> revisions(Caller caller, String id, int limit, String cursor) {
        document(caller, id); limit(limit);
        long before = bound(decode(caller, "revisions", id, cursor, Long.toString(FIRST_HISTORY_BOUND)));
        return page(repository.revisions(caller, id, before, limit + 1), limit, caller, "revisions", id,
                row -> Long.toString(row.contentRevision()));
    }
    /** 历史READY与当前发布分开返回，失败任务的代次不回退。 */
    public Page<GenerationView> generations(Caller caller, String id, int limit, String cursor) {
        document(caller, id); limit(limit);
        long before = bound(decode(caller, "generations", id, cursor, Long.toString(FIRST_HISTORY_BOUND)));
        return page(repository.generations(caller, id, before, limit + 1), limit, caller, "generations", id,
                row -> Long.toString(row.indexGeneration()));
    }

    private static <T> Page<T> page(List<T> rows, int limit, Caller caller, String kind, String id, Function<T, String> position) {
        List<T> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        String cursor = rows.size() > limit ? Base64.getUrlEncoder().withoutPadding().encodeToString(String.join("\n",
                caller.tenantId(), caller.userId(), kind, id, position.apply(items.getLast())).getBytes(StandardCharsets.UTF_8)) : null;
        return new Page<>(items, cursor);
    }
    private static String decode(Caller caller, String kind, String id, String cursor, String initial) {
        if (cursor == null) { return initial; }
        if (cursor.length() > 1024) { invalid(); }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\n", -1);
            if (parts.length != 5 || !parts[0].equals(caller.tenantId()) || !parts[1].equals(caller.userId())
                    || !parts[2].equals(kind) || !parts[3].equals(id)) { invalid(); }
            return parts[4];
        } catch (IllegalArgumentException failure) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
    }
    private static long bound(String value) {
        try {
            long result = Long.parseLong(value);
            if (result < 1 || result > FIRST_HISTORY_BOUND || !Long.toString(result).equals(value)) { invalid(); }
            return result;
        } catch (NumberFormatException failure) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
    }
    private static void uuid(String value) {
        try { if (!UUID.fromString(value).toString().equals(value)) { invalid(); } }
        catch (IllegalArgumentException | NullPointerException failure) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
    }
    private static void key(String value) { if (value == null || !value.matches("[A-Za-z0-9._:-]{16,128}")) { invalid(); } }
    private static void limit(int value) { if (value < 1 || value > MAX_LIMIT) { invalid(); } }
    private static void invalid() { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
}
