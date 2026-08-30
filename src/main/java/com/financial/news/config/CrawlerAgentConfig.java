package com.financial.news.config;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import com.financial.news.service.crawler.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 爬虫 Agent 配置
 * <p>创建独立的 LLM 实例和 Agent 服务，与主 AI 助手隔离</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Configuration
@ConditionalOnExpression("'${crawler.agent.enabled:false}' == 'true' && '${crawler.agent.api-key:}' != ''")
public class CrawlerAgentConfig {

    @Value("${crawler.agent.api-key:}")
    private String apiKey;

    @Value("${crawler.agent.api-base-url:https://api.openai.com/v1}")
    private String apiBaseUrl;

    @Value("${crawler.agent.model:gpt-4o-mini}")
    private String model;

    @Value("${crawler.agent.temperature:0.3}")
    private double temperature;

    @Value("${crawler.agent.max-tokens:4096}")
    private int maxTokens;

    @Value("${crawler.agent.http-timeout:120}")
    private int httpTimeout;

    /**
     * 爬虫专用 LLM 聊天模型
     */
    @Bean("crawlerChatModel")
    public OpenAiChatModel crawlerChatModel() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new org.springframework.beans.factory.BeanCreationException(
                    "crawlerChatModel: crawler.agent.api-key 不能为空，" +
                    "请在 .env 中配置 AI_API_KEY 或 CRAWLER_AI_API_KEY");
        }
        log.info("初始化爬虫 LLM — 模型: {}, 基础URL: {}", model, apiBaseUrl);
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(apiBaseUrl)
                .modelName(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(java.time.Duration.ofSeconds(httpTimeout))
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    /**
     * PlannerAgent — 纯 LLM 推理，无工具
     */
    @Bean
    public PlannerAgent plannerAgent(OpenAiChatModel crawlerChatModel) {
        log.info("初始化 PlannerAgent — 爬取规划Agent");
        return AiServices.builder(PlannerAgent.class)
                .chatLanguageModel(crawlerChatModel)
                .build();
    }

    /**
     * ScraperAgent — 页面抓取 Agent
     */
    @Bean
    public ScraperAgent scraperAgent(OpenAiChatModel crawlerChatModel, CrawlerTools crawlerTools) {
        log.info("初始化 ScraperAgent — 页面抓取Agent");
        return AiServices.builder(ScraperAgent.class)
                .chatLanguageModel(crawlerChatModel)
                .tools(crawlerTools)
                .build();
    }

    /**
     * ProcessorAgent — 内容处理 Agent
     */
    @Bean
    public ProcessorAgent processorAgent(OpenAiChatModel crawlerChatModel, CrawlerTools crawlerTools) {
        log.info("初始化 ProcessorAgent — 内容处理Agent");
        return AiServices.builder(ProcessorAgent.class)
                .chatLanguageModel(crawlerChatModel)
                .tools(crawlerTools)
                .build();
    }

    /**
     * WriterAgent — 入库 Agent
     */
    @Bean
    public WriterAgent writerAgent(OpenAiChatModel crawlerChatModel, CrawlerTools crawlerTools) {
        log.info("初始化 WriterAgent — 入库Agent");
        return AiServices.builder(WriterAgent.class)
                .chatLanguageModel(crawlerChatModel)
                .tools(crawlerTools)
                .build();
    }
}
