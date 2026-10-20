package com.lrj.agentic.knowledge.api;

import com.lrj.agentic.contract.ApiError;
import com.lrj.agentic.contract.ErrorCode;
import com.lrj.agentic.contract.PlatformError;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/** 所有对外异常都映射稳定目录，避免原始 SQL、对象地址和用户输入进入响应。 */
@RestControllerAdvice
public class KnowledgeErrors {
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeErrors.class);

    /** 业务错误保留契约状态；日志只包含稳定代码及关联标识。 */
    @ExceptionHandler(KnowledgeFailure.class)
    public ResponseEntity<ApiError> knowledge(KnowledgeFailure failure, HttpServletRequest request) {
        return response(failure.error(), request);
    }

    /** 超大文件在解析阶段即可被拒绝，不等待进入领域层。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> size(HttpServletRequest request) {
        return response(KnowledgeError.FILE_TOO_LARGE, request);
    }

    /** 缺字段、参数类型和非法 multipart 不暴露框架的详细输入内容。 */
    @ExceptionHandler({ServletRequestBindingException.class, MethodArgumentTypeMismatchException.class,
            MultipartException.class, ErrorResponseException.class})
    public ResponseEntity<ApiError> input(Exception ignored, HttpServletRequest request) {
        return response(KnowledgeError.INPUT_INVALID, request);
    }

    /** 最后防线只记录异常类型，不将不可控异常消息或调用参数写入日志。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception failure, HttpServletRequest request) {
        LOG.error("knowledge_unexpected type={} requestId={}",
                failure.getClass().getSimpleName(), RequestCorrelation.id(request));
        return response(PlatformError.INTERNAL_ERROR, request);
    }

    private static ResponseEntity<ApiError> response(ErrorCode code, HttpServletRequest request) {
        return ResponseEntity.status(code.httpStatus()).body(ApiError.of(code, RequestCorrelation.id(request)));
    }
}
