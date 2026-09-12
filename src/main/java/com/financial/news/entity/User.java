package com.financial.news.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户实体
 * <p>deleted_at 为软删除标记，过滤条件由各查询 SQL 显式携带</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    /** 内部主键 */
    private Integer id;

    /** 用户公开 ID，格式 user-xxxxxxxx */
    private String uid;

    /** 用户展示 ID，格式 U123456001 */
    private String displayId;

    /** 用户名 */
    private String username;

    /** 邮箱 */
    private String email;

    /** bcrypt 密码哈希（12轮） */
    private String passwordHash;

    /** 头像 URL */
    private String avatar;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 软删除时间，非 null 即已删除 */
    private LocalDateTime deletedAt;
}
