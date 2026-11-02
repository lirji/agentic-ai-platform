package com.lrj.agentic.knowledge.api;

import com.lrj.agentic.knowledge.application.CandidateJson;
import com.lrj.agentic.knowledge.application.CandidateRecoveryService;
import com.lrj.agentic.knowledge.domain.*;
import com.lrj.agentic.knowledge.domain.operations.CandidateModels;
import com.lrj.agentic.knowledge.domain.operations.RecoveryModels;
import com.lrj.agentic.security.JwtTrust;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 只读探测与显式补偿命令分开授权；所有状态来自owner的真实资料和审计。 */
@RestController
@RequestMapping("/api/v1/knowledge/operations/candidates/{id}")
public class CandidateRecoveryController {
    private final CandidateRecoveryService service;
    private final CandidateJson json;
    /** Controller不接收模型、索引或对象地址，全部由服务器既有配置决定。 */
    public CandidateRecoveryController(CandidateRecoveryService service,CandidateJson json) { this.service=service;this.json=json; }
    /** 探测词和补偿原因可能包含资料信息，不允许中间缓存。 */
    @ModelAttribute public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    /** 关闭写入口后仍可查看来源历史；可执行性不构成提交授权。 */
    @GetMapping("/recovery")
    public RecoveryModels.View get(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestParam(required=false) Long revision) {
        return service.get(JwtTrust.caller(jwt),id,revision);
    }
    /** 每次探测均由用户显式触发，沿现有检索/预算执行，不隐式批准或发布。 */
    @PostMapping(value="/publication/probe",consumes=MediaType.APPLICATION_JSON_VALUE)
    public RecoveryModels.ProbeResult probe(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,HttpServletRequest request) {
        return service.probe(JwtTrust.caller(jwt),id,read(request,RecoveryModels.Probe.class));
    }
    /** 写权限由安全过滤器核验，资料owner与并发条件由应用服务重新裁决。 */
    @PostMapping(value="/compensations",consumes=MediaType.APPLICATION_JSON_VALUE)
    public CandidateModels.Receipt compensate(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        return service.compensate(JwtTrust.caller(jwt),id,key,read(request,RecoveryModels.Compensate.class));
    }
    private <T> T read(HttpServletRequest request,Class<T> type) {
        try { return json.input(request.getInputStream().readNBytes(CandidateModels.MAX_METADATA_BYTES+1),type); }
        catch(IOException invalid) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
    }
}
