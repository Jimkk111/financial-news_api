package com.financial.news.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.financial.news.common.Result;
import com.financial.news.dto.request.UpdateUserRequest;
import com.financial.news.dto.response.UserResponse;
import com.financial.news.entity.News;
import com.financial.news.security.JwtUserDetails;
import com.financial.news.service.NewsService;
import com.financial.news.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 用户控制器
 */
@Tag(name = "用户模块", description = "用户信息管理")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@SecurityRequirement(name = "BearerAuth")
public class UserController {

    private final UserService userService;
    private final NewsService newsService;

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/me")
    public Result<UserResponse> getCurrentUser() {
        JwtUserDetails user = JwtUserDetails.getCurrentUser();
        return Result.ok(userService.getCurrentUser(user.getUserId()));
    }

    @Operation(summary = "更新当前用户信息")
    @PutMapping("/me")
    public Result<UserResponse> updateUser(@Valid @RequestBody UpdateUserRequest request) {
        JwtUserDetails user = JwtUserDetails.getCurrentUser();
        return Result.ok(userService.updateUser(user.getUserId(), request));
    }

    @Operation(summary = "上传头像")
    @PostMapping("/me/avatar")
    public Result<Map<String, String>> uploadAvatar(@RequestParam("avatar") MultipartFile file) {
        JwtUserDetails user = JwtUserDetails.getCurrentUser();
        String url = userService.uploadAvatar(user.getUserId(), file);
        return Result.ok(Map.of("avatar", url));
    }

    @Operation(summary = "获取当前用户发布的新闻列表")
    @GetMapping("/me/news")
    public Result<Result.PageResult<News>> getUserNews(@RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "10") int pageSize) {
        JwtUserDetails user = JwtUserDetails.getCurrentUser();
        Page<News> result = userService.getUserNews(user.getUserId(), page, pageSize);
        return Result.ok(newsService.toPageResult(result));
    }
}
