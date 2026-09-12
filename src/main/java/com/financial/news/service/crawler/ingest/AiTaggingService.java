package com.financial.news.service.crawler.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.news.entity.Category;
import com.financial.news.entity.Tag;
import com.financial.news.mapper.CategoryMapper;
import com.financial.news.mapper.TagMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 分类打标服务
 * <p>采集入库前的内容增强环节：由 LLM 从"库中已有分类"里逐字选择一个分类、
 * 提出 2~4 个标签（优先复用已有标签）。LLM 只做内容增强不做流程决策：
 * 输出经 schema 校验与白名单约束，任何失败返回 null，由调用方降级为来源默认分类。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiTaggingService {

    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;

    @Value("${crawler.ingest.ai-tagging.enabled:true}")
    private boolean enabled;

    @Value("${crawler.ingest.ai-tagging.excerpt-length:800}")
    private int excerptLength;

    @Value("${ai.api-key:}")
    private String apiKey;

    @Value("${ai.api-base-url:https://api.openai.com/v1}")
    private String apiBaseUrl;

    @Value("${ai.model:gpt-3.5-turbo}")
    private String model;

    @Value("${ai.temperature:0.2}")
    private double temperature;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 分类与标签结果；category 保证属于库中已有分类（可能为 null），tags 已清洗去重 */
    public record TaggingResult(String category, List<String> tags) {
    }

    /**
     * AI 是否可用（开关开启且配置了 api-key）
     */
    public boolean available() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    /**
     * 对文章进行分类打标
     *
     * @return 结果；服务关闭/未配 key/调用失败均返回 null（调用方降级），不抛异常
     */
    public TaggingResult classify(String title, String plainText) {
        if (!available()) {
            return null;
        }
        try {
            List<String> categoryNames = categoryMapper.selectListAll().stream()
                    .map(Category::getName).toList();
            if (categoryNames.isEmpty()) {
                return null;
            }
            List<String> existingTags = tagMapper.selectListAll().stream()
                    .map(Tag::getName).toList();

            String excerpt = plainText.length() > excerptLength
                    ? plainText.substring(0, excerptLength) : plainText;
            String content = callAi(buildPrompt(title, excerpt, categoryNames, existingTags));
            return parseResult(content, categoryNames);
        } catch (Exception e) {
            log.warn("AI 分类打标失败，降级为来源默认分类: {}", e.getMessage());
            return null;
        }
    }

    private String buildPrompt(String title, String excerpt, List<String> categories, List<String> tags) {
        return """
                你是财经新闻编辑，为下面的新闻选择分类和标签。

                ## 规则
                1. category 必须从【分类列表】中逐字选择一个，不得自创
                2. tags 给 2~4 个：优先从【已有标签列表】选择，不够时才新增，每个 2~8 个汉字
                3. 只输出 JSON，不要任何其他内容：{"category":"...","tags":["...","..."]}

                ## 分类列表
                %s

                ## 已有标签列表
                %s

                ## 新闻
                标题：%s
                正文：%s
                """.formatted(String.join("、", categories),
                tags.isEmpty() ? "（暂无）" : String.join("、", tags),
                title, excerpt);
    }

    private String callAi(String prompt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("max_tokens", 200);
        body.put("temperature", temperature);

        String json = new ObjectMapper().writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBaseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("AI 接口返回 HTTP " + resp.statusCode());
        }
        JsonNode root = new ObjectMapper().readTree(resp.body());
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException("AI 响应缺少 choices");
        }
        String content = choices.get(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new IllegalStateException("AI 响应内容为空");
        }
        return content;
    }

    /**
     * 解析并校验 LLM 输出：分类必须命中白名单，标签清洗去重限量
     */
    private TaggingResult parseResult(String content, List<String> allowedCategories) throws Exception {
        String json = content.trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("AI 输出不含 JSON");
        }
        JsonNode node = new ObjectMapper().readTree(json.substring(start, end + 1));

        String category = node.path("category").asText("").trim();
        String matched = allowedCategories.stream()
                .filter(c -> c.equalsIgnoreCase(category))
                .findFirst().orElse(null);

        List<String> tags = new ArrayList<>();
        for (JsonNode t : node.path("tags")) {
            String name = t.asText("").trim().replaceAll("[\\s#*\"']", "");
            if (name.length() < 2 || name.length() > 8) {
                continue;
            }
            if (!tags.contains(name)) {
                tags.add(name);
            }
            if (tags.size() >= 4) {
                break;
            }
        }
        return new TaggingResult(matched, tags);
    }
}
