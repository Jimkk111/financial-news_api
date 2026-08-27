package com.financial.news.service.crawler;

/**
 * 处理 Agent — 逐篇解析文章正文，生成结构化内容
 * <p>负责文章详情获取、正文提取、内容结构化、去重检查。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
public interface ProcessorAgent {

    /**
     * 处理文章列表，逐篇解析正文并结构化
     *
     * @param scrapeResultAndPlan 抓取结果 + 爬取计划（含选择器配置）
     * @return 处理结果 JSON（含完整正文的文章列表）
     */
    String process(String scrapeResultAndPlan);
}
