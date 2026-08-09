package com.financial.news.common;

import lombok.Getter;

/**
 * 统一错误码枚举
 *
 * @author financial-news
 * @since 1.0.0
 */
@Getter
public enum ErrorCode {

    // ========== 通用错误 ==========
    BAD_REQUEST(400, "BAD_REQUEST", "请求参数错误"),
    UNAUTHORIZED(401, "UNAUTHORIZED", "未认证或 Token 无效"),
    FORBIDDEN(403, "FORBIDDEN", "权限不足"),
    NOT_FOUND(404, "NOT_FOUND", "资源不存在"),
    CONFLICT(409, "CONFLICT", "资源冲突"),
    VALIDATION_ERROR(422, "VALIDATION_ERROR", "参数验证失败"),
    RATE_LIMIT_EXCEEDED(429, "RATE_LIMIT_EXCEEDED", "请求频率超限"),
    INTERNAL_ERROR(500, "INTERNAL_ERROR", "服务器内部错误"),

    // ========== 业务错误 ==========
    DUPLICATE_ENTRY(409, "DUPLICATE_ENTRY", "数据库唯一键冲突"),
    FILE_TOO_LARGE(413, "FILE_TOO_LARGE", "上传文件过大"),
    FILE_COUNT_EXCEEDED(422, "FILE_COUNT_EXCEEDED", "上传文件数量超限"),
    INVALID_FILE_TYPE(422, "INVALID_FILE_TYPE", "文件类型不支持"),
    USERNAME_EXISTS(422, "USERNAME_EXISTS", "用户名已被使用"),
    EMAIL_EXISTS(422, "EMAIL_EXISTS", "邮箱已被使用"),
    NO_UPDATE_DATA(422, "NO_UPDATE_DATA", "无更新数据"),
    NO_FILE(422, "NO_FILE", "未提供文件"),

    // ========== 认证相关 ==========
    INVALID_CREDENTIALS(401, "INVALID_CREDENTIALS", "用户名或密码错误"),
    INVALID_CODE(400, "INVALID_CODE", "验证码无效或已过期"),
    CODE_SEND_TOO_FREQUENT(429, "CODE_SEND_TOO_FREQUENT", "验证码发送过于频繁"),
    PASSWORD_RULE_MISMATCH(422, "PASSWORD_RULE_MISMATCH", "密码不符合规则，必须包含字母和数字"),
    USERNAME_EMAIL_MISMATCH(422, "USERNAME_EMAIL_MISMATCH", "用户名与邮箱不匹配"),

    // ========== 收藏相关 ==========
    ALREADY_FAVORITE(409, "ALREADY_FAVORITE", "已收藏该新闻"),
    FAVORITE_NOT_FOUND(404, "FAVORITE_NOT_FOUND", "未收藏该新闻"),

    // ========== 草稿相关 ==========
    DRAFT_NOT_FOUND(404, "DRAFT_NOT_FOUND", "草稿不存在"),
    DRAFT_ALREADY_PUBLISHED(409, "DRAFT_ALREADY_PUBLISHED", "草稿已发布，不可编辑"),
    DRAFT_NOT_OWNER(403, "DRAFT_NOT_OWNER", "无权操作此草稿"),

    // ========== AI 相关 ==========
    AI_SESSION_NOT_FOUND(404, "AI_SESSION_NOT_FOUND", "AI 会话不存在"),
    AI_SERVICE_UNAVAILABLE(503, "AI_SERVICE_UNAVAILABLE", "AI 服务暂不可用"),
    AI_SESSION_NOT_OWNER(403, "AI_SESSION_NOT_OWNER", "无权操作此会话"),

    // ========== 新闻相关 ==========
    NEWS_NOT_FOUND(404, "NEWS_NOT_FOUND", "新闻不存在");

    /** HTTP 状态码 */
    private final int httpStatus;

    /** 业务错误码 */
    private final String code;

    /** 错误描述 */
    private final String message;

    ErrorCode(int httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }
}
