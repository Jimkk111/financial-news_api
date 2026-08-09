package com.financial.news.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户实体
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("users")
public class User {

    /** 内部主键 */
    @TableId(type = IdType.AUTO)
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
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 软删除时间 */
    @TableLogic
    private LocalDateTime deletedAt;
}
