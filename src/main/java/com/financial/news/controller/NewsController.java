package com.financial.news.controller;

import com.financial.news.common.Result;
import com.financial.news.dto.response.NewsDetailVO;
import com.financial.news.entity.Category;
import com.financial.news.entity.News;
import com.financial.news.service.NewsService;
import com.financial.news.security.JwtUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 新闻控制器
 */
@Tag(name = "新闻模块", description = "新闻列表、详情、搜索、分类/标签")
@RestController
@RequestMapping("/api/news")
@RequiredArgsConstructor
public class NewsController {

    private final NewsService newsService;

    @Operation(summary = "获取新闻列表（分页）")
    @GetMapping
    public Result<Result.PageResult<News>> listNews(@RequestParam(required = false) Integer categoryId,
                               @RequestParam(defaultValue = "newest") String sort,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "10") int pageSize) {
        Result.PageResult<News> result = newsService.listNews(categoryId, sort, page, pageSize);
        return Result.ok(result);
    }

    @Operation(summary = "获取新闻详情")
    @GetMapping("/{id}")
    public Result<NewsDetailVO> getNewsDetail(@PathVariable Integer id) {
        return Result.ok(newsService.getNewsDetail(id));
    }

    @Operation(summary = "增加浏览量")
    @PostMapping("/{id}/views")
    public Result<Map<String, Object>> incrementViews(@PathVariable Integer id,
                                                      HttpServletRequest request) {
        int views = newsService.incrementViews(id, resolveViewerKey(request));
        return Result.ok(Map.of("id", id, "views", views));
    }

    /**
     * 解析访问者标识：登录用户用用户ID，否则退化为客户端 IP
     */
    private String resolveViewerKey(HttpServletRequest request) {
        JwtUserDetails user = JwtUserDetails.getCurrentUserOrNull();
        if (user != null) {
            return "u" + user.getUserId();
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(xff)) {
            return "ip" + xff.split(",")[0].trim();
        }
        return "ip" + request.getRemoteAddr();
    }

    @Operation(summary = "获取分类列表")
    @GetMapping("/categories")
    public Result<List<Category>> getCategories() {
        return Result.ok(newsService.getCategories());
    }

    @Operation(summary = "获取标签列表")
    @GetMapping("/tags")
    public Result<List<com.financial.news.entity.Tag>> getTags() {
        return Result.ok(newsService.getTags());
    }

    @Operation(summary = "搜索新闻")
    @GetMapping("/search")
    public Result<Result.PageResult<News>> searchNews(@RequestParam String keyword,
                                 @RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "10") int pageSize) {
        Result.PageResult<News> result = newsService.searchNews(keyword, page, pageSize);
        return Result.ok(result);
    }
}
