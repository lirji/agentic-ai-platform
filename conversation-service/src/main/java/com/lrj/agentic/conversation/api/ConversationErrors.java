package com.lrj.agentic.conversation.api;

import com.lrj.agentic.contract.*;
import com.lrj.agentic.conversation.domain.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 错误始终使用稳定目录，不把数据库、JWT 或会话原文带回浏览器。 */
@RestControllerAdvice
public class ConversationErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ConversationErrors.class);
    /** 业务拒绝保持契约状态。 */
    @ExceptionHandler(ConversationFailure.class)
    public ResponseEntity<ApiError> expected(ConversationFailure error, HttpServletRequest request) {
        return response(error.error(), request);
    }
    /** 弱类型/重复字段/未知字段和无效参数统一拒绝。 */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            ServletRequestBindingException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> invalid(Exception ignored, HttpServletRequest request) {
        return response(ConversationError.INPUT_INVALID, request);
    }
    /** 数据库不可用不伪装为会话不存在或空事件。 */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> storage(Exception ignored, HttpServletRequest request) {
        LOG.warn("conversation_storage_unavailable requestId={}", RequestCorrelation.id(request));
        return response(ConversationError.STORAGE_UNAVAILABLE, request);
    }
    /** 意外失败只记录安全类型，原始异常消息可能包含正文。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception error, HttpServletRequest request) {
        LOG.error("conversation_unexpected type={} requestId={}", error.getClass().getSimpleName(), RequestCorrelation.id(request));
        return response(PlatformError.INTERNAL_ERROR, request);
    }
    private ResponseEntity<ApiError> response(ErrorCode code, HttpServletRequest request) {
        return ResponseEntity.status(code.httpStatus()).body(ApiError.of(code, RequestCorrelation.id(request)));
    }
}
