package com.financial.news.controller;

import com.financial.news.common.Result;
import com.financial.news.dto.request.FavoriteRequest;
import com.financial.news.dto.response.FavoriteVO;
import com.financial.news.security.JwtUserDetails;
import com.financial.news.service.FavoriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 收藏控制器
 *
 * @author financial-news
 * @since 1.0.0
 */
@Tag(name = "收藏模块", description = "新闻收藏的增删查")
@RestController
@RequestMapping("/api/favorites")
@RequiredArgsConstructor
@SecurityRequirement(name = "BearerAuth")
public class FavoriteController {

    private final FavoriteService favoriteService;

    @Operation(summary = "获取收藏列表")
    @GetMapping
    public Result<Result.PageResult<FavoriteVO>> list(@RequestParam(defaultValue = "1") int page,
                          @RequestParam(defaultValue = "10") int pageSize) {
        JwtUserDetails u = JwtUserDetails.getCurrentUser();
        return Result.ok(favoriteService.listFavorites(u.getUserId(), page, pageSize));
    }

    @Operation(summary = "添加收藏")
    @PostMapping
    public Result<Void> add(@Valid @RequestBody FavoriteRequest req) {
        favoriteService.addFavorite(JwtUserDetails.getCurrentUser().getUserId(), req.getNewsId());
        return Result.okMsg("收藏成功");
    }

    @Operation(summary = "取消收藏")
    @DeleteMapping("/{newsId}")
    public Result<Void> remove(@PathVariable Integer newsId) {
        favoriteService.removeFavorite(JwtUserDetails.getCurrentUser().getUserId(), newsId);
        return Result.okMsg("取消收藏成功");
    }

    @Operation(summary = "检查是否已收藏")
    @GetMapping("/check/{newsId}")
    public Result<Map<String, Boolean>> check(@PathVariable Integer newsId) {
        boolean favorited = favoriteService.isFavorite(JwtUserDetails.getCurrentUser().getUserId(), newsId);
        return Result.ok(Map.of("is_favorite", favorited));
    }
}
