package com.financial.news.service.crawler;

import com.financial.news.common.BusinessException;
import com.financial.news.common.ErrorCode;
import com.financial.news.entity.AiMessage;
import com.financial.news.entity.AiSession;
import com.financial.news.mapper.AiMessageMapper;
import com.financial.news.mapper.AiSessionMapper;
import com.financial.news.utils.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 爬虫 Agent 服务
 * <p>封装 LangChain4j Agent 的调用流程，管理会话上下文，提供统一的爬取入口。</p>
 *
 * <h3>工作流概览</h3>
 * <pre>
 *   用户输入 "帮我爬取财联社的新闻数据"
 *          ↓
 *   CrawlerAgent（LLM ReAct 循环）
 *     ├─ 调用 getSourceInfo("财联社") → 获取 URL 配置
 *     ├─ 调用 fetchPage(url) → 获取列表页 HTML
 *     ├─ 调用 extractArticleList(html, selector) → 提取文章列表
 *     ├─ 循环处理每篇文章：
 *     │   ├─ 调用 fetchPage(articleUrl) → 获取文章 HTML
 *     │   ├─ 调用 parseArticleContent(html) → 解析正文
 *     │   ├─ 调用 htmlToContentJson(html) → 结构化为 Block 列表
 *     │   └─ 调用 saveNews(...) → 存入数据库
 *     └─ 返回执行摘要
 * </pre>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrawlerAgentService {

    private final CrawlerAgent crawlerAgent;
    private final AiSessionMapper aiSessionMapper;
    private final AiMessageMapper aiMessageMapper;

    @org.springframework.beans.factory.annotation.Value("${crawler.agent.max-articles-per-run:20}")
    private int maxArticlesPerRun;

    /**
     * 执行爬取任务（同步）
     *
     * @param userId    用户 ID
     * @param instruction 自然语言爬取指令
     * @param sessionId 会话 ID（可选）
     * @return 执行结果，包含 Agent 回复和会话信息
     */
    @Transactional
    public Map<String, Object> execute(Integer userId, String instruction, String sessionId) {
        // 1. 解析或创建会话
        AiSession session = resolveSession(userId, sessionId);

        // 2. 保存用户消息
        aiMessageMapper.insert(AiMessage.builder()
                .sessionId(session.getId())
                .role("user")
                .content(instruction)
                .build());

        // 3. 构建系统提示 + 用户指令
        String systemPrompt = buildSystemPrompt();
        String fullMessage = systemPrompt + "\n\n用户指令：" + instruction;

        // 4. 调用 Agent（ReAct 循环，Agent 自主决策工具调用）
        log.info("开始执行爬取任务: userId={}, instruction={}", userId, instruction);
        String agentResponse;
        try {
            agentResponse = crawlerAgent.crawl(fullMessage);
        } catch (Exception e) {
            log.error("爬取 Agent 执行异常", e);
            agentResponse = "爬取任务执行失败: " + e.getMessage();
        }
        log.info("爬取任务完成: {}", agentResponse);

        // 5. 保存 Agent 回复
        aiMessageMapper.insert(AiMessage.builder()
                .sessionId(session.getId())
                .role("assistant")
                .content(agentResponse)
                .build());

        // 6. 更新会话标题（首次交互时）
        if (session.getTitle() == null) {
            String title = instruction.length() > 30
                    ? instruction.substring(0, 30)
                    : instruction;
            session.setTitle("爬取: " + title);
            aiSessionMapper.updateById(session);
        } else {
            session.setUpdatedAt(java.time.LocalDateTime.now());
            aiSessionMapper.updateById(session);
        }

        // 7. 返回结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        result.put("content", agentResponse);
        result.put("sessionId", session.getSessionId());
        return result;
    }

    /**
     * 流式执行爬取任务（通过 SSE 返回 Agent 执行过程）
     * <p>注意：LangChain4j 的流式 API 与 Spring SSE 集成需要 SSE 专用模型。
     * 此方法当前为同步执行后一次性返回，后续可升级为真正的流式。</p>
     */
    public Map<String, Object> executeStream(Integer userId, String instruction, String sessionId) {
        return execute(userId, instruction, sessionId);
    }

    /**
     * 解析或创建会话
     */
    private AiSession resolveSession(Integer userId, String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            AiSession s = aiSessionMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiSession>()
                            .eq(AiSession::getSessionId, sessionId));
            if (s != null && s.getUserId().equals(userId)) return s;
        }
        String newId = IdGenerator.generateSessionId();
        AiSession s = AiSession.builder().sessionId(newId).userId(userId).build();
        aiSessionMapper.insert(s);
        return s;
    }

    /**
     * 构建 Agent 系统提示词
     * <p>指导 Agent 如何使用工具完成新闻采集工作流</p>
     */
    private String buildSystemPrompt() {
        return """
                你是一个专业的财经新闻采集 Agent。你的任务是根据用户的指令，从指定的财经新闻网站爬取最新的新闻数据，并清洗、结构化后存入数据库。

                ## 工具调用流程（必须严格按此顺序）

                ### 第一步：获取列表页
                1. 调用 fetchPage(url=列表页URL) → 返回 "OK|url=...|size=..."
                2. 调用 extractArticleList(url=同一URL, linkSelector, titleSelector, timeSelector, baseUrl) → 返回文章列表 JSON

                ### 第二步：逐篇处理文章（对列表中每篇文章重复以下步骤）
                1. 调用 fetchPage(url=文章URL) → 返回 "OK|url=...|size=..."
                2. 调用 parseArticleContent(url=同一文章URL, contentSelector, titleSelector, timeSelector) → 返回摘要 JSON（含 title/time/imageUrl/url/blocks）
                   注意：parseArticleContent 内部已自动生成 contentJson 并缓存，无需再调用 htmlToContentJson
                3. 调用 saveNews(title=标题, url=文章URL, summary=摘要, publishTimeStr=时间, source=来源, categoryName=分类, tagNamesStr=标签) → 保存
                   注意：saveNews 会通过 url 从缓存自动获取 htmlContent、contentJson、imageUrl，无需手动传递这些大数据

                ## 关键规则
                - fetchPage 返回的是缓存摘要（不是HTML原文），后续工具通过相同的 URL 从缓存获取完整 HTML
                - extractArticleList 和 parseArticleContent 的第一个参数必须是 URL，不要传 HTML 字符串
                - saveNews 的 url 参数必须传文章URL，它会从缓存获取完整数据
                - 不要调用 htmlToContentJson，parseArticleContent 已内置此功能
                - 每次 fetchPage 后必须立即用同一 URL 调用对应的解析工具
                - 每次爬取最多处理 %d 篇文章
                - 已存在的新闻（按标题匹配）应跳过
                - 如果某个数据源无法正常抓取，应尝试其他数据源
                - **SPA网站（如财联社）不要用fetchPage，应用fetchJsonApi调用其后端API**

                ## 数据源信息

                ### 东方财富 (eastmoney) — 推荐，SSR渲染
                - 列表页: https://finance.eastmoney.com/a/cgsxw.html
                - 列表选择器: "ul.news_list li a" 或 "div.text a"
                - 文章正文选择器: "#ContentBody"，标题选择器: ".title"

                ### 新浪财经 (sina) — 推荐，SSR渲染
                - 列表页: https://finance.sina.com.cn/
                - 文章正文选择器: 可留空自动识别

                ### 同花顺 (10jqka) — 推荐，SSR渲染
                - 列表页: https://news.10jqka.com.cn/
                - 文章正文选择器: 可留空自动识别

                ### 华尔街见闻 (wallstreetcn) — SPA，需用API
                - 文章详情API: https://api-one.wallstcn.com/apiv1/content/articles/{article_id}
                - 用 fetchJsonApi 调用，referer 设为 https://wallstreetcn.com/

                ### 财联社 (cls) — SPA，需用API
                - 财联社已全面转为SPA架构，普通HTTP请求无法获取动态内容
                - 如果无法找到可用API，请跳过财联社，优先使用东方财富、新浪财经、同花顺
                """.formatted(maxArticlesPerRun);
    }
}
