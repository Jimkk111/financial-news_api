package com.financial.news.service.crawler;

/**
 * 规划 Agent — 分析用户指令，制定爬取计划
 * <p>纯 LLM 推理，无需工具。负责理解用户意图，决定爬取哪些数据源、用什么选择器、爬多少篇。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
public interface PlannerAgent {

    /**
     * 分析用户指令，生成结构化爬取计划
     *
     * @param userMessage 用户自然语言指令（如 "帮我爬取东方财富最新20篇财经新闻"）
     * @return 结构化爬取计划 JSON
     */
    String plan(String userMessage);
}
