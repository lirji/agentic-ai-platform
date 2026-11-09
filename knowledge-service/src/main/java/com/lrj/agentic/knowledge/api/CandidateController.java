package com.lrj.agentic.knowledge.api;

import com.lrj.agentic.contract.knowledge.ExtractionProtocol;
import com.lrj.agentic.knowledge.application.*;
import com.lrj.agentic.knowledge.domain.*;
import com.lrj.agentic.knowledge.domain.operations.CandidateModels.*;
import com.lrj.agentic.knowledge.domain.operations.CandidateModels;
import com.lrj.agentic.knowledge.infrastructure.processing.ParserProperties;
import com.lrj.agentic.security.JwtTrust;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 候选公开协议只接收内容与预期修订，owner和源对象位置从可信服务推导。 */
@RestController
@RequestMapping("/api/v1/knowledge/operations")
public class CandidateController {
    private final CandidateService service;
    private final CandidateJson json;
    private final ParserProperties parsing;
    /** Controller只负责有界协议转换；所有权和事务规则由用例负责。 */
    public CandidateController(CandidateService service,CandidateJson json,ParserProperties parsing) {
        this.service=service;this.json=json;this.parsing=parsing;
    }
    /** 候选全文和回执不进入共享缓存。 */
    @ModelAttribute public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    /** 能力状态不提供任何资源访问权。 */
    @GetMapping("/capabilities") public CandidateService.Capabilities capabilities() { return service.capabilities(); }
    /** 只有原资料owner可从反馈进入候选。 */
    @GetMapping("/feedback/{id}/candidate") public View byFeedback(@AuthenticationPrincipal Jwt jwt,@PathVariable String id) {
        return service.byFeedback(JwtTrust.caller(jwt),id);
    }
    /** 首次保存必须上传完整修正文件，反馈选中片段不会被自动当作全文件。 */
    @PostMapping(value="/feedback/{id}/candidate",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Receipt create(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,
            @RequestParam("input") String input,@RequestParam("file") MultipartFile file) {
        return save(jwt,id,key,input,file,true);
    }
    /** 不可变历史可单独查看，当前资格不能从历史回执推断。 */
    @GetMapping("/candidates/{id}") public View get(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestParam(required=false) Long revision) {
        return service.get(JwtTrust.caller(jwt),id,revision);
    }
    /** 完整替换保存以CAS保护草稿，拒绝客户端提交身份或源对象路径。 */
    @PostMapping(value="/candidates/{id}/revisions",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Receipt revise(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,
            @RequestParam("input") String input,@RequestParam("file") MultipartFile file) {
        return save(jwt,id,key,input,file,false);
    }
    /** 仅受理真实入库作业，不把HTTP成功当作准备或发布完成。 */
    @PostMapping(value="/candidates/{id}/preparation",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Receipt prepare(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        return service.prepare(JwtTrust.caller(jwt),id,key,input(request,Prepare.class));
    }
    /** 关联已有质量定义，候选模块不创建第二种评测数据集。 */
    @PostMapping(value="/candidates/{id}/datasets",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Receipt associate(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        return service.associate(JwtTrust.caller(jwt),id,key,input(request,Associate.class));
    }
    /** 显式绑定当前内容对应的真实报告和评分摘要，不触发额外模型执行。 */
    @PostMapping(value="/candidates/{id}/evaluation",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Receipt bind(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        return service.bind(JwtTrust.caller(jwt),id,key,input(request,Bind.class));
    }
    /** 未知响应通过原命令键查询，功能关闭后仍可恢复原接受事实。 */
    @GetMapping("/commands/{key}") public Receipt command(@AuthenticationPrincipal Jwt jwt,@PathVariable String key) {
        return service.command(JwtTrust.caller(jwt),key);
    }
    private Receipt save(Jwt jwt,String id,String key,String metadata,MultipartFile file,boolean create) {
        if(metadata.length()>CandidateModels.MAX_METADATA_BYTES) throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID);
        var input=json.input(metadata.getBytes(StandardCharsets.UTF_8),Save.class);
        if(input==null) throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID);
        if(file.getSize()>ExtractionProtocol.MAX_SOURCE_BYTES) throw new KnowledgeFailure(KnowledgeError.FILE_TOO_LARGE);
        try(var stream=file.getInputStream()) {
            byte[] bytes=stream.readNBytes(ExtractionProtocol.MAX_SOURCE_BYTES+1);
            var upload=UploadRules.validate(key,file.getOriginalFilename(),input.title(),bytes,file.getContentType(),parsing.pdfUploadEnabled(),parsing.docxUploadEnabled(), parsing.spreadsheetUploadEnabled());
            return service.save(JwtTrust.caller(jwt),id,key,input,upload,create);
        } catch(IOException invalid) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
    }
    private <T> T input(HttpServletRequest request,Class<T> type) {
        try { return json.input(request.getInputStream().readNBytes(CandidateModels.MAX_METADATA_BYTES+1),type); }
        catch(IOException invalid) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
    }
}
