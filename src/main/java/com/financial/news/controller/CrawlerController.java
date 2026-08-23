package com.financial.news.controller;

import com.financial.news.common.Result;
import com.financial.news.dto.request.CrawlerRequest;
import com.financial.news.security.JwtUserDetails;
import com.financial.news.service.crawler.CrawlerAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 新闻爬取控制器
 * <p>提供基于 Agent 的新闻爬取接口，前端输入自然语言指令触发工作流</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Tag(name = "新闻爬取模块", description = "基于 Agent 的新闻数据采集")
@RestController
@RequestMapping("/api/crawler")
@RequiredArgsConstructor
@SecurityRequirement(name = "BearerAuth")
public class CrawlerController {

    private final CrawlerAgentService crawlerAgentService;

    /**
     * 执行爬取任务（同步）
     * <p>接收自然语言指令，启动 Agent 工作流爬取、清洗并入库新闻数据。</p>
     */
    @Operation(summary = "执行新闻爬取任务", description = "输入自然语言指令（如：帮我爬取财联社的新闻数据），Agent 自动完成采集工作流")
    @PostMapping("/crawl")
    public Result<Map<String, Object>> crawl(@Valid @RequestBody CrawlerRequest request) {
        JwtUserDetails user = JwtUserDetails.getCurrentUser();
        Map<String, Object> result = crawlerAgentService.execute(
                user.getUserId(),
                request.getInstruction(),
                request.getSessionId()
        );
        return Result.ok(result);
    }

    /**
     * 执行爬取任务（流式 SSE）
     * <p>通过 Server-Sent Events 实时返回 Agent 执行过程。当前为同步执行后一次性返回。</p>
     */
    @Operation(summary = "执行新闻爬取任务（流式）", description = "通过 SSE 实时返回 Agent 执行进度")
    @PostMapping(value = "/crawl/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Result<Map<String, Object>> crawlStream(@Valid @RequestBody CrawlerRequest request) {
        JwtUserDetails user = JwtUserDetails.getCurrentUser();
        Map<String, Object> result = crawlerAgentService.executeStream(
                user.getUserId(),
                request.getInstruction(),
                request.getSessionId()
        );
        return Result.ok(result);
    }
}
