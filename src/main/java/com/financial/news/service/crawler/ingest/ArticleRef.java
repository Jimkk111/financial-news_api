package com.financial.news.service.crawler.ingest;

import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 文章引用（列表阶段产物）
 *
 * @param refId       数据源内部标识（如华尔街见闻文章ID），详情抓取时使用
 * @param url         文章页面 URL
 * @param title       标题
 * @param publishTime 发布时间（列表阶段可解析时填充，可为空）
 */
@Builder
public record ArticleRef(String refId, String url, String title, LocalDateTime publishTime) {
}
