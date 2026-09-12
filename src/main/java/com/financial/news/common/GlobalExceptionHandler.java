
package com.financial.news.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.List;
import java.util.stream.Collectors;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 全局异常处理器
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException ex, HttpServletRequest request) {
        log.warn("[业务异常] URI:{}, Code:{}, Message:{}", request.getRequestURI(), ex.getErrorCode().getCode(), ex.getMessage());
        ErrorCode errorCode = ex.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(Result.fail(errorCode));
    }

    /**
     * 处理参数验证异常 (@Valid)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<List<Result.FieldError>>> handleValidationException(MethodArgumentNotValidException ex) {
        List<Result.FieldError> details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new Result.FieldError(e.getField(), e.getDefaultMessage()))
                .collect(Collectors.toList());
        log.warn("[参数验证失败] {}", details);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Result.fail(ErrorCode.VALIDATION_ERROR, details));
    }

    /**
     * 处理绑定异常
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<List<Result.FieldError>>> handleBindException(BindException ex) {
        List<Result.FieldError> details = ex.getFieldErrors().stream()
                .map(e -> new Result.FieldError(e.getField(), e.getDefaultMessage()))
                .collect(Collectors.toList());
        log.warn("[绑定异常] {}", details);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Result.fail(ErrorCode.VALIDATION_ERROR, details));
    }

    /**
     * 处理数据库唯一键冲突
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<Void>> handleDuplicateKeyException(DuplicateKeyException ex) {
        log.warn("[唯一键冲突] {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Result.fail(ErrorCode.DUPLICATE_ENTRY, "数据已存在，请检查唯一字段"));
    }

    /**
     * 处理 @RequestParam/@PathVariable 校验异常
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<List<Result.FieldError>>> handleConstraintViolationException(ConstraintViolationException ex) {
        List<Result.FieldError> details = ex.getConstraintViolations().stream()
                .map(v -> new Result.FieldError(extractParamName(v), v.getMessage()))
                .collect(Collectors.toList());
        log.warn("[参数校验失败] {}", details);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ErrorCode.BAD_REQUEST, details));
    }

    /**
     * 处理请求体不可读异常（JSON 格式错误等）
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleHttpMessageNotReadableException(HttpMessageNotReadableException ex) {
        log.warn("[请求体格式错误] {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ErrorCode.BAD_REQUEST, "请求体格式错误"));
    }

    /**
     * 处理非法参数异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("[非法参数] {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ErrorCode.BAD_REQUEST, ex.getMessage()));
    }

    /**
     * 处理授权拒绝（已登录但权限不足）
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDeniedException(org.springframework.security.access.AccessDeniedException ex, HttpServletRequest request) {
        log.warn("[权限不足] URI:{}, Message:{}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.fail(ErrorCode.FORBIDDEN));
    }

    /**
     * 处理文件上传过大
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex) {
        log.warn("[文件过大] {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Result.fail(ErrorCode.FILE_TOO_LARGE));
    }

    /**
     * 提取 ConstraintViolation 中的参数名（如 "getNews.id" → "id"）
     */
    private String extractParamName(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }

    /**
     * 处理其他未知异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception ex, HttpServletRequest request) {
        log.error("[系统异常] URI:{}, Message:{}", request.getRequestURI(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ErrorCode.INTERNAL_ERROR));
    }
}
