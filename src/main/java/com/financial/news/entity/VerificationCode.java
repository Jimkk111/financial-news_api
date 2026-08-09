package com.financial.news.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 验证码实体
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("verification_codes")
public class VerificationCode {

    @TableId(type = IdType.AUTO)
    private Integer id;

    /** 邮箱 */
    private String email;

    /** 6 位数字验证码 */
    private String code;

    /** 关联用户名（注册场景） */
    private String username;

    /** 过期时间（创建后 5 分钟） */
    private LocalDateTime expiresAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
