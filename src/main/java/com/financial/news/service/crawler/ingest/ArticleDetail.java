package com.financial.news.service.crawler.ingest;

import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文章详情（详情阶段产物，正文为原始 HTML，由流水线统一清洗提取）
 *
 * @param title       标题（解析失败时可为空，回退用列表标题）
 * @param url         文章页面 URL
 * @param publishTime 发布时间
 * @param contentHtml 正文区域 HTML（连接器尽力提取，流水线兜底 Readability）
 * @param imageUrl    封面图
 * @param summaryHint 摘要提示（列表接口附带的 intro/content_short）
 * @param categories  来源站分类名（可选，用于入库分类参考）
 */
@Builder
public record ArticleDetail(String title, String url, LocalDateTime publishTime,
                            String contentHtml, String imageUrl, String summaryHint,
                            List<String> categories) {
}
