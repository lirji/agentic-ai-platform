package com.lrj.agentic.knowledge.api;

import com.lrj.agentic.contract.knowledge.ExtractionProtocol;
import com.lrj.agentic.knowledge.application.LifecycleService;
import com.lrj.agentic.knowledge.application.UploadRules;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.lifecycle.LifecycleModels.*;
import com.lrj.agentic.knowledge.infrastructure.processing.ParserProperties;
import com.lrj.agentic.security.JwtTrust;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** K07单独版本化管理端点；S10B不开放撤下、删除或重索引命令。 */
@RestController
@RequestMapping("/api/v2/knowledge")
public class LifecycleController {
    private final LifecycleService service;
    private final ParserProperties parsing;
    public LifecycleController(LifecycleService service, ParserProperties parsing) { this.service = service; this.parsing = parsing; }

    /** 文件边界沿K01，命令只异步受理，不将202冒充已发布。 */
    @PostMapping(value = "/documents/{id}/updates", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CommandReceipt> update(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @RequestHeader("Idempotency-Key") String key, @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String displayName, @RequestParam String expectedRevision) {
        if (!expectedRevision.matches("0|[1-9][0-9]{0,9}")) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
        if (file.getSize() > ExtractionProtocol.MAX_SOURCE_BYTES) { throw new KnowledgeFailure(KnowledgeError.FILE_TOO_LARGE); }
        try (var input = file.getInputStream()) {
            var upload = UploadRules.validate(key, file.getOriginalFilename(), displayName,
                    input.readNBytes(ExtractionProtocol.MAX_SOURCE_BYTES + 1), file.getContentType(),
                    parsing.pdfUploadEnabled(), parsing.docxUploadEnabled());
            return ResponseEntity.accepted().body(service.update(JwtTrust.caller(jwt), id, key, Long.parseLong(expectedRevision), upload));
        } catch (IOException | NumberFormatException invalid) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
    }

    /** 恢复响应未知的管理命令，不能查询K01的旧幂等命名空间。 */
    @GetMapping("/commands/{key}")
    public CommandReceipt command(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        return service.command(JwtTrust.caller(jwt), key);
    }
    /** 元数据按当前owner授权。 */
    @GetMapping("/documents/{id}")
    public DocumentView document(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return service.document(JwtTrust.caller(jwt), id);
    }
    /** 空页与游标均由真实数据库产生。 */
    @GetMapping("/documents")
    public Page<DocumentView> documents(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor) { return service.documents(JwtTrust.caller(jwt), limit, cursor); }
    /** 不向历史修订列表附加原文或下载地址。 */
    @GetMapping("/documents/{id}/revisions")
    public Page<RevisionView> revisions(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String cursor) {
        return service.revisions(JwtTrust.caller(jwt), id, limit, cursor);
    }
    /** 当前发布关系不能仅由READY状态推断。 */
    @GetMapping("/documents/{id}/generations")
    public Page<GenerationView> generations(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String cursor) {
        return service.generations(JwtTrust.caller(jwt), id, limit, cursor);
    }
}
