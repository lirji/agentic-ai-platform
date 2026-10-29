package com.lrj.agentic.knowledge.api;

import com.lrj.agentic.contract.knowledge.CitationProtocol;
import com.lrj.agentic.contract.knowledge.GenerationCitationProtocol;
import com.lrj.agentic.knowledge.application.GenerationCitationCheck;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.processing.ProcessingRepository;
import com.lrj.agentic.knowledge.infrastructure.processing.ProcessingJson;
import com.lrj.agentic.security.JwtTrust;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tasks仅可批量重验引用；统一使用当前发布权威，不返回任何正文或存在原因。 */
@RestController
@ConditionalOnProperty(name = "knowledge.citation-reader.enabled", havingValue = "true")
public class CitationCheckController {
    private final ProcessingRepository repository;
    private final ProcessingJson json;
    private final GenerationCitationCheck generations;
    private final Semaphore slots = new Semaphore(CitationProtocol.MAX_CONCURRENT);
    /** 两版本共享容量，新增核验路径不扩大数据库并发。 */
    public CitationCheckController(ProcessingRepository repository, ProcessingJson json, GenerationCitationCheck generations) {
        this.repository = repository; this.json = json; this.generations = generations;
    }

    /** 一次SQL读取有界候选并逐项匹配，避免N+1查询超出总期限。 */
    @PostMapping(value = CitationProtocol.PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public CitationProtocol.Reply check(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        if (!slots.tryAcquire()) { throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE); }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CitationProtocol.DEADLINE_SECONDS);
        try {
            byte[] body = request.getInputStream().readNBytes(CitationProtocol.MAX_BYTES + 1);
            if (body.length > CitationProtocol.MAX_BYTES) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
            var command = json.read(body, CitationProtocol.Check.class, KnowledgeError.INPUT_INVALID);
            if (command == null) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
            command.validated();
            var rows = repository.published(JwtTrust.caller(jwt), command.references().stream().map(CitationProtocol.Reference::chunkId).toList());
            boolean accessible = command.references().stream().allMatch(reference -> rows.stream().anyMatch(row ->
                    row.documentId().equals(reference.documentId()) && row.version() == reference.version() && row.chunkId().equals(reference.chunkId())));
            if (System.nanoTime() >= deadline) { throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE); }
            return new CitationProtocol.Reply(accessible);
        } catch (IOException | IllegalArgumentException invalid) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
        finally { slots.release(); }
    }

    /** v2完整身份不能经v1三元组核验，响应仍只含可读布尔值。 */
    @PostMapping(value = GenerationCitationProtocol.PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public CitationProtocol.Reply checkGeneration(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        if (!slots.tryAcquire()) { throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE); }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CitationProtocol.DEADLINE_SECONDS);
        try {
            byte[] body = request.getInputStream().readNBytes(CitationProtocol.MAX_BYTES + 1);
            if (body.length > CitationProtocol.MAX_BYTES) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
            var command = json.read(body, GenerationCitationProtocol.Check.class, KnowledgeError.INPUT_INVALID);
            if (command == null) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
            command.validated();
            boolean accessible = generations.accessible(JwtTrust.caller(jwt), command.references());
            if (System.nanoTime() >= deadline) { throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE); }
            return new CitationProtocol.Reply(accessible);
        } catch (IOException | IllegalArgumentException invalid) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
        finally { slots.release(); }
    }
}
