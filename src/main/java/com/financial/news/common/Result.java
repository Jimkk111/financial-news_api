package com.financial.news.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一响应结果封装 — {code, msg, data}
 *
 * @param <T> 数据类型
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> {

    /** 状态码 — "200" 表示成功，否则为具体错误码 */
    private String code;

    /** 提示信息 */
    private String msg;

    /** 响应数据 */
    private T data;

    // ==================== 成功响应 ====================

    /**
     * 成功（无数据）
     */
    public static <T> Result<T> ok() {
        return Result.<T>builder().code("200").msg("success").build();
    }

    /**
     * 成功（带数据）
     */
    public static <T> Result<T> ok(T data) {
        return Result.<T>builder().code("200").msg("success").data(data).build();
    }

    /**
     * 成功（数据 + 自定义消息）
     */
    public static <T> Result<T> ok(T data, String msg) {
        return Result.<T>builder().code("200").msg(msg).data(data).build();
    }

    /**
     * 成功（纯消息，无 data 字段）
     */
    public static <T> Result<T> okMsg(String msg) {
        return Result.<T>builder().code("200").msg(msg).build();
    }

    // ==================== 失败响应 ====================

    /**
     * 失败（使用 ErrorCode 中的默认消息）
     */
    public static <T> Result<T> fail(ErrorCode errorCode) {
        return Result.<T>builder().code(errorCode.getCode()).msg(errorCode.getMessage()).build();
    }

    /**
     * 失败（自定义消息，覆盖 ErrorCode 默认消息）
     */
    public static <T> Result<T> fail(ErrorCode errorCode, String msg) {
        return Result.<T>builder().code(errorCode.getCode()).msg(msg).build();
    }

    /**
     * 失败（带详情数据，如字段验证错误列表）
     */
    public static <T> Result<T> fail(ErrorCode errorCode, T data) {
        return Result.<T>builder().code(errorCode.getCode()).msg(errorCode.getMessage()).data(data).build();
    }

    // ==================== 内部分类 ====================

    /**
     * 分页数据容器 — 作为 data 字段的值返回
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PageResult<T> {
        /** 当前页数据列表 */
        private List<T> records;
        /** 总记录数 */
        private Long total;
        /** 当前页码 */
        private Integer page;
        /** 每页条数 */
        private Integer pageSize;
    }

    /**
     * 字段错误 — 用于参数验证失败时作为 data 返回
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldError {
        /** 字段名 */
        private String field;
        /** 错误信息 */
        private String message;
    }
}
