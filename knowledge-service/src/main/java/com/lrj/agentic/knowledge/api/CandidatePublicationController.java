package com.lrj.agentic.knowledge.api;

import com.lrj.agentic.knowledge.application.*;
import com.lrj.agentic.knowledge.domain.*;
import com.lrj.agentic.knowledge.domain.operations.CandidateModels;
import com.lrj.agentic.knowledge.domain.operations.PublicationModels;
import com.lrj.agentic.security.JwtTrust;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 批准是显式owner命令，HTTP受理不代表原资料已生效。 */
@RestController
@RequestMapping("/api/v1/knowledge/operations")
public class CandidatePublicationController {
    private final CandidatePublicationService service;
    private final CandidateJson json;
    /** 协议层只解析有界严格JSON，发布身份和源均由服务端读取。 */
    public CandidatePublicationController(CandidatePublicationService service,CandidateJson json) {
        this.service=service;this.json=json;
    }
    /** 批准说明及状态属于owner私有数据，禁止共享缓存。 */
    @ModelAttribute public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    /** 写门禁不影响原命令或历史状态恢复。 */
    @GetMapping("/publication-capabilities") public CandidatePublicationService.Capabilities capabilities() { return service.capabilities(); }
    /** 当前或指定修订的真实发布读模型，无批准返回404。 */
    @GetMapping("/candidates/{id}/publication")
    public PublicationModels.View get(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestParam(required=false) Long revision) {
        return service.get(JwtTrust.caller(jwt),id,revision);
    }
    /** 事务成功仅确认批准与入库交接，原键重放不会建立第二个作业。 */
    @PostMapping(value="/candidates/{id}/publication",consumes=MediaType.APPLICATION_JSON_VALUE)
    public CandidateModels.Receipt approve(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        try {
            var input=json.input(request.getInputStream().readNBytes(CandidateModels.MAX_METADATA_BYTES+1),PublicationModels.Approve.class);
            return service.approve(JwtTrust.caller(jwt),id,key,input);
        } catch(IOException invalid) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
    }
}
