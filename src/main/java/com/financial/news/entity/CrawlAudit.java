package com.financial.news.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 采集审计记录
 * <p>每篇候选文章的采集结果（成功/去重/拒绝/失败），用于排查"探测到未入库"类问题</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawlAudit {

    public static final String SAVED = "SAVED";
    public static final String DUP_URL = "DUP_URL";
    public static final String DUP_TITLE = "DUP_TITLE";
    public static final String DUP_CONTENT = "DUP_CONTENT";
    public static final String REJECTED = "REJECTED";
    public static final String FAILED = "FAILED";

    private Integer id;

    /** 采集批次ID */
    private String runId;

    /** 数据源标识 */
    private String source;

    /** 文章URL */
    private String articleUrl;

    /** 文章标题 */
    private String title;

    /** 结果: SAVED/DUP_URL/DUP_TITLE/DUP_CONTENT/REJECTED/FAILED */
    private String status;

    /** 说明（拒绝原因/失败原因） */
    private String reason;

    /** 正文纯文本长度 */
    private Integer contentLength;

    /** 解析出的发布时间 */
    private LocalDateTime publishTime;

    /** 单篇处理耗时（毫秒） */
    private Integer durationMs;

    private LocalDateTime createdAt;
}
