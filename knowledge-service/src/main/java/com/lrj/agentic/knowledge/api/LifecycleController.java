package com.lrj.agentic.knowledge.api;

import com.fasterxml.jackson.databind.JsonNode;
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

/** K07单独版本化管理端点；可见性命令只改变权威状态，不删除存储对象。 */
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

    /** 撤下成功即不可再交付正文，响应丢失通过原命令键恢复。 */
    @PostMapping(value = "/documents/{id}/withdrawals", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CommandReceipt withdraw(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode input) {
        return service.visibility(JwtTrust.caller(jwt), id, key, expectedRevision(input), Operation.WITHDRAW);
    }

    /** 逻辑删除保留原文件及审计；删除后公共详情404，原owner回执仍可查询。 */
    @PostMapping(value = "/documents/{id}/deletions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CommandReceipt delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode input) {
        return service.visibility(JwtTrust.caller(jwt), id, key, expectedRevision(input), Operation.DELETE);
    }

    private static long expectedRevision(JsonNode input) {
        // JSON命令不接受字符串、浮点截断或额外参数，避免恢复指纹与实际意图不一致。
        if (input == null || !input.isObject() || input.size() != 1 || !input.has("expectedRevision")) {
            throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID);
        }
        JsonNode value = input.get("expectedRevision");
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID);
        }
        return value.longValue();
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
