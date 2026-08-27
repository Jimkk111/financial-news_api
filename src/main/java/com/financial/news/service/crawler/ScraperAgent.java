package com.financial.news.service.crawler;

/**
 * 爬取 Agent — 按计划抓取页面，提取文章列表
 * <p>负责所有页面获取工作：列表页抓取、文章链接提取、华尔街见闻 API 调用等。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
public interface ScraperAgent {

    /**
     * 根据爬取计划执行页面抓取，返回文章列表
     *
     * @param crawlPlanAndContext 爬取计划 JSON + 已有的分类/标签信息
     * @return 抓取结果 JSON（文章列表）
     */
    String scrape(String crawlPlanAndContext);
}
