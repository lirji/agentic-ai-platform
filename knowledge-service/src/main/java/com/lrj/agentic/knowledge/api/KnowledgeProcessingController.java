package com.lrj.agentic.knowledge.api;

import com.lrj.agentic.knowledge.application.KnowledgeSearch;
import com.lrj.agentic.knowledge.application.KnowledgeService;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.processing.ProcessingRepository;
import com.lrj.agentic.knowledge.infrastructure.processing.ProcessingJson;
import com.lrj.agentic.security.JwtTrust;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 新 JSON 入口有独立正文上限，避免 Servlet 默认先无界收集再验证。 */
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeProcessingController {
    private static final int MAX_JSON_BYTES = 32768;
    private final KnowledgeService knowledge;
    private final KnowledgeSearch search;
    private final ProcessingRepository repository;
    private final ProcessingJson json;

    /** 重用身份、资料查询及错误机制，不建立第二套权限来源。 */
    public KnowledgeProcessingController(KnowledgeService knowledge, KnowledgeSearch search,
            ProcessingRepository repository, ProcessingJson json) {
        this.knowledge = knowledge;
        this.search = search;
        this.repository = repository;
        this.json = json;
    }

    /** 已验证所有者和原修订号共同限制显式恢复。 */
    @PostMapping(value = "/ingestions/{jobId}/retry", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<KnowledgeViews.IngestionView> retry(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String jobId, HttpServletRequest request) {
        RetryRequest input = read(request, RetryRequest.class);
        if (input.expectedRevision() == null || input.expectedRevision() < 0) {
            throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID);
        }
        var caller = JwtTrust.caller(jwt);
        knowledge.ingestion(caller, jobId);
        var job = repository.retry(caller, jobId, input.expectedRevision());
        return ResponseEntity.accepted().location(URI.create("/api/v1/knowledge/ingestions/" + jobId))
                .body(KnowledgeViews.IngestionView.of(job));
    }

    /** 搜索属于读权限，不能因为使用 POST 就要求写权限。 */
    @PostMapping(value = "/search", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SearchReply search(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return new SearchReply(search.search(JwtTrust.caller(jwt), read(request, KnowledgeSearch.SearchRequest.class)));
    }

    /** 直接读取引用也重新验证当前发布记录。 */
    @GetMapping("/documents/{documentId}/versions/{version}/chunks/{chunkId}")
    public KnowledgeSearch.Hit citation(@AuthenticationPrincipal Jwt jwt, @PathVariable String documentId,
            @PathVariable long version, @PathVariable String chunkId) {
        return search.citation(JwtTrust.caller(jwt), documentId, version, chunkId);
    }

    private <T> T read(HttpServletRequest request, Class<T> type) {
        try {
            byte[] body = request.getInputStream().readNBytes(MAX_JSON_BYTES + 1);
            if (body.length > MAX_JSON_BYTES) { throw new KnowledgeFailure(KnowledgeError.FILE_TOO_LARGE); }
            T result = json.read(body, type, KnowledgeError.INPUT_INVALID);
            if (result == null) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
            return result;
        } catch (IOException invalid) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
    }

    /** CAS 输入不可缺失或由字符串强制转换。 */
    public record RetryRequest(Long expectedRevision) { }
    /** 空数组只表示真实查询没有已发布可见结果。 */
    public record SearchReply(List<KnowledgeSearch.Hit> hits) { }
}
