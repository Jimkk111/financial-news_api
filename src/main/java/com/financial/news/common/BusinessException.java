package com.financial.news.common;

import lombok.Getter;

/**
 * 业务异常类
 *
 * @author financial-news
 * @since 1.0.0
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 错误码 */
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
