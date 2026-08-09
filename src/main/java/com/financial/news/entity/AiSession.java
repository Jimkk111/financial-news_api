package com.financial.news.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 会话实体
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ai_sessions")
public class AiSession {

    @TableId(type = IdType.AUTO)
    private Integer id;

    /** 会话公开 ID，格式 session-xxxxxxxx */
    private String sessionId;

    /** 用户 ID */
    private Integer userId;

    /** 会话标题（AI 自动生成，最长30字截断） */
    private String title;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
