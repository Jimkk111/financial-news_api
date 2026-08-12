package com.financial.news.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.financial.news.common.Result;
import com.financial.news.dto.response.NewsDetailVO;
import com.financial.news.entity.Category;
import com.financial.news.entity.News;
import com.financial.news.service.NewsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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
        Page<News> result = newsService.listNews(categoryId, sort, page, pageSize);
        return Result.ok(newsService.toPageResult(result));
    }

    @Operation(summary = "获取新闻详情")
    @GetMapping("/{id}")
    public Result<NewsDetailVO> getNewsDetail(@PathVariable Integer id) {
        return Result.ok(newsService.getNewsDetail(id));
    }

    @Operation(summary = "增加浏览量")
    @PostMapping("/{id}/views")
    public Result<Map<String, Object>> incrementViews(@PathVariable Integer id) {
        int views = newsService.incrementViews(id);
        return Result.ok(Map.of("id", id, "views", views));
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
        Page<News> result = newsService.searchNews(keyword, page, pageSize);
        return Result.ok(newsService.toPageResult(result));
    }
}
