package com.lrj.agentic.knowledge.api;

import com.lrj.agentic.knowledge.application.KnowledgeService;
import com.lrj.agentic.knowledge.application.UploadRules;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol;
import com.lrj.agentic.knowledge.infrastructure.processing.ParserProperties;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.security.JwtTrust;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** HTTP 适配层只解析契约输入，认证身份一律从已验证内部 JWT 取得。 */
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {
    private final KnowledgeService service;
    private final ParserProperties parsing;

    /** 通过应用服务保持 HTTP 与领域事务的边界。 */
    public KnowledgeController(KnowledgeService service, ParserProperties parsing) {
        this.service = service; this.parsing = parsing;
    }

    /** 文件大小在读取前和读取后均校验，避免绕过 Multipart 的边界。 */
    @PostMapping(value = "/ingestions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<KnowledgeViews.IngestionView> submit(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String displayName) {
        if (file.getSize() > ExtractionProtocol.MAX_SOURCE_BYTES) {
            throw new KnowledgeFailure(KnowledgeError.FILE_TOO_LARGE);
        }
        try (var input = file.getInputStream()) {
            byte[] bytes = input.readNBytes(ExtractionProtocol.MAX_SOURCE_BYTES + 1);
            var upload = UploadRules.validate(key, file.getOriginalFilename(), displayName, bytes,
                    file.getContentType(), parsing.pdfUploadEnabled(), parsing.docxUploadEnabled());
            var job = service.submit(JwtTrust.caller(jwt), key, upload);
            return ResponseEntity.accepted()
                    .location(URI.create("/api/v1/knowledge/ingestions/" + job.jobId()))
                    .body(KnowledgeViews.IngestionView.of(job));
        } catch (IOException unreadable) {
            throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID);
        }
    }

    /** 受理任务与文档采用相同所有者范围。 */
    @GetMapping("/ingestions/{jobId}")
    public KnowledgeViews.IngestionView ingestion(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String jobId) {
        return KnowledgeViews.IngestionView.of(service.ingestion(JwtTrust.caller(jwt), jobId));
    }

    /** 不接受来自用户表单或普通请求头的租户/用户 ID。 */
    @GetMapping("/documents/{documentId}")
    public KnowledgeViews.DocumentView document(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String documentId) {
        return KnowledgeViews.DocumentView.of(service.document(JwtTrust.caller(jwt), documentId));
    }

    /** 空列表仍来自真实存储，客户端无需猜测是否存在演示资料。 */
    @GetMapping("/documents")
    public KnowledgeViews.DocumentPage documents(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String cursor) {
        var page = service.documents(JwtTrust.caller(jwt), limit, cursor);
        return new KnowledgeViews.DocumentPage(
                page.items().stream().map(KnowledgeViews.DocumentView::of).toList(), page.nextCursor());
    }
}
