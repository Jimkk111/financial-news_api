package com.financial.news.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 消息实体
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiMessage {

    private Integer id;

    /** 会话内部 ID */
    private Integer sessionId;

    /** 角色：user / assistant / system */
    private String role;

    /** 消息内容 */
    private String content;

    /** 思考型模型的思考链（reasoning_content），仅 assistant 消息可能携带 */
    private String reasoningContent;

    /** 联网搜索引用来源（JSON 数组字符串：title/url/summary/siteName/publishTime/logoUrl） */
    private String sources;

    private LocalDateTime createdAt;
}
