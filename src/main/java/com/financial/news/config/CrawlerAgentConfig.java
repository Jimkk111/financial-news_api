package com.financial.news.config;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import com.financial.news.service.crawler.CrawlerAgent;
import com.financial.news.service.crawler.CrawlerTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(name = "crawler.agent.api-key", matchIfMissing = false)
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

    @Value("${crawler.agent.http-timeout:30}")
    private int httpTimeout;

    /**
     * 爬虫专用 LLM 聊天模型
     */
    @Bean("crawlerChatModel")
    public OpenAiChatModel crawlerChatModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(apiBaseUrl)
                .modelName(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(java.time.Duration.ofSeconds(httpTimeout))
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    /**
     * 爬虫 Agent（LangChain4j AiServices 代理）
     */
    @Bean
    public CrawlerAgent crawlerAgent(OpenAiChatModel crawlerChatModel, CrawlerTools crawlerTools) {
        log.info("初始化爬虫 Agent — 模型: {}, 基础URL: {}", model, apiBaseUrl);
        return AiServices.builder(CrawlerAgent.class)
                .chatLanguageModel(crawlerChatModel)
                .tools(crawlerTools)
                .build();
    }
}
