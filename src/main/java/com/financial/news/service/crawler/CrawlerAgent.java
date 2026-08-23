package com.financial.news.service.crawler;

/**
 * 爬虫 Agent 接口 — 由 LangChain4j AiServices 动态代理实现
 * <p>Agent 根据用户自然语言指令，自主调用工具完成新闻采集工作流</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
public interface CrawlerAgent {

    /**
     * 执行爬取任务
     *
     * @param userMessage 用户自然语言指令（如 "帮我爬取财联社的新闻数据"）
     * @return Agent 执行结果摘要
     */
    String crawl(String userMessage);
}
