package com.lrj.agentic.knowledge.application;

import static com.lrj.agentic.knowledge.domain.evaluation.EvaluationModels.*;
import static com.lrj.agentic.knowledge.domain.evaluation.EvaluationRules.*;

import com.lrj.agentic.knowledge.domain.ContentDigest;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.evaluation.EvaluationProtocol;
import com.lrj.agentic.knowledge.domain.evaluation.EvaluationRepository;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/** 评测定义用例，资料选择不触发检索或模型调用。 */
@Service
public class EvaluationService {
    private final EvaluationRepository repository;
    private final EvaluationJson json;

    /** 事务由持久端口承担，应用层不持有 JDBC 或外部模型。 */
    public EvaluationService(EvaluationRepository repository, EvaluationJson json) {
        this.repository = repository;
        this.json = json;
    }

    /** 新建空集也持久保存修订和回执，后续运行再校验非空。 */
    public Receipt create(Caller caller, String key, Definition input) {
        Definition definition = definition(input);
        String keyHash = keyHash(key);
        String fingerprint = json.digest(new Command(EvaluationProtocol.Operation.CREATE.code(), null, null, definition));
        return repository.receipt(caller, keyHash, fingerprint).orElseGet(() ->
                repository.save(caller, keyHash, fingerprint, UUID.randomUUID().toString(), null, definition));
    }

    /** 全量替换生成新修订，原定义不修改。 */
    public Receipt revise(Caller caller, String datasetId, String key, Revise command) {
        id(datasetId);
        require(command != null && command.expectedRevision() != null);
        positive(command.expectedRevision());
        Definition definition = definition(command.definition());
        String keyHash = keyHash(key);
        String fingerprint = json.digest(new Command(EvaluationProtocol.Operation.REVISE.code(), datasetId, command.expectedRevision(), definition));
        return repository.receipt(caller, keyHash, fingerprint).orElseGet(() -> repository.save(caller,
                keyHash, fingerprint, datasetId, command.expectedRevision(), definition));
    }

    /** 结果未知时仅查询原键，不因刷新生成第二个命令。 */
    public Receipt receipt(Caller caller, String key) {
        return repository.receipt(caller, keyHash(key), null)
                .orElseThrow(() -> new KnowledgeFailure(KnowledgeError.NOT_FOUND));
    }

    /** 集合稳定排序，cursor 只描述位置，不携带权限。 */
    public Page<DatasetSummary> datasets(Caller caller, Integer requestedLimit, String cursor, String purpose) {
        int size = limit(requestedLimit);
        if (purpose != null) { EvaluationProtocol.Purpose.fromCode(purpose); }
        String scope = "datasets:" + (purpose == null ? "ALL" : purpose);
        String after = after(scope, cursor);
        if (after != null) { id(after); }
        return page(repository.datasets(caller, after, size + 1, purpose), size, scope, DatasetSummary::datasetId);
    }

    /** 任一当前资料版本或权限失效即抑制定义正文，不能绕过撤权读取历史标注。 */
    public RevisionDetail detail(Caller caller, String datasetId, Long revision) {
        id(datasetId);
        if (revision != null) { positive(revision); }
        Revision stored = repository.revision(caller, datasetId, revision)
                .orElseThrow(() -> new KnowledgeFailure(KnowledgeError.NOT_FOUND));
        var bound = stored.bound();
        var current = repository.bindings(caller, bound.documents().stream().map(DocumentBinding::documentId).toList());
        boolean available = current.equals(bound.documents());
        return new RevisionDetail(stored.summary(), (available ? EvaluationProtocol.BindingState.AVAILABLE
                : EvaluationProtocol.BindingState.UNAVAILABLE).code(),
                available ? bound.definition() : null, available ? bound.documents() : List.of());
    }

    /** 不因空历史列表泄露某个他人资源是否存在。 */
    public Page<RevisionSummary> history(Caller caller, String datasetId, Integer requestedLimit, String cursor) {
        id(datasetId);
        repository.dataset(caller, datasetId).orElseThrow(() -> new KnowledgeFailure(KnowledgeError.NOT_FOUND));
        int size = limit(requestedLimit);
        String scope = datasetId + ":revisions";
        String value = after(scope, cursor);
        Long before = null;
        if (value != null) {
            try { before = positive(Long.parseLong(value)); }
            catch (NumberFormatException invalid) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
            require(Long.toString(before).equals(value));
        }
        return page(repository.history(caller, datasetId, before, size + 1), size, scope,
                row -> Long.toString(row.revision()));
    }

    /** 先验证当前版本，再查询有界原文页；无 Embedding 或跨服务调用。 */
    public Page<EvidenceOption> evidence(Caller caller, String documentId, long version, Integer requestedLimit, String cursor) {
        id(documentId);
        positive(version);
        int size = limit(requestedLimit);
        String scope = documentId + ":" + version + ":evidence";
        String after = after(scope, cursor);
        if (after != null) { digest(after); }
        var documents = repository.bindings(caller, List.of(documentId));
        if (documents.size() != 1 || documents.getFirst().version() != version) {
            throw new KnowledgeFailure(KnowledgeError.NOT_FOUND);
        }
        return page(repository.evidence(caller, documentId, version, after, size + 1), size, scope, EvidenceOption::chunkId);
    }

    private Definition definition(Definition input) {
        Definition normalized = normalize(input);
        if (json.bytes(normalized).length > EvaluationProtocol.MAX_BODY_BYTES) {
            throw new KnowledgeFailure(KnowledgeError.EVALUATION_TOO_LARGE);
        }
        return normalized;
    }

    private static String keyHash(String key) {
        return ContentDigest.sha256(key(key).getBytes(StandardCharsets.UTF_8));
    }

    private static <T> Page<T> page(List<T> rows, int size, String scope, Function<T, String> key) {
        boolean more = rows.size() > size;
        List<T> items = List.copyOf(rows.subList(0, Math.min(size, rows.size())));
        return new Page<>(items, more ? cursor(scope, key.apply(items.getLast())) : null);
    }

    private record Command(String operation, String datasetId, Long expectedRevision, Definition definition) { }
}
