package com.financial.news.entity;

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
public class AiSession {

    private Integer id;

    /** 会话公开 ID，格式 session-xxxxxxxx */
    private String sessionId;

    /** 用户 ID */
    private Integer userId;

    /** 会话标题（AI 自动生成，最长30字截断） */
    private String title;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
