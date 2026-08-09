package com.financial.news.controller;

import com.financial.news.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 系统控制器 - 健康检查和 API 版本信息
 *
 * @author financial-news
 * @since 1.0.0
 */
@Tag(name = "系统接口", description = "健康检查、版本信息")
@RestController
public class SystemController {

    /**
     * 服务健康检查
     */
    @Operation(summary = "服务健康检查")
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.ok(Map.of(
                "status", "healthy",
                "timestamp", LocalDateTime.now().toString(),
                "uptime", java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0
        ));
    }

    /**
     * API 版本信息
     */
    @Operation(summary = "API 版本信息")
    @GetMapping("/api")
    public Result<Map<String, String>> apiVersion() {
        return Result.ok(Map.of(
                "message", "财经新闻API服务",
                "version", "1.0.0"
        ));
    }
}
