package com.financial.news.controller;

import com.financial.news.common.Result;
import com.financial.news.dto.request.HistoryRequest;
import com.financial.news.dto.response.HistoryVO;
import com.financial.news.security.JwtUserDetails;
import com.financial.news.service.HistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 浏览历史控制器
 *
 * @author financial-news
 * @since 1.0.0
 */
@Tag(name = "浏览历史模块", description = "浏览历史的记录与查询")
@RestController
@RequestMapping("/api/history")
@RequiredArgsConstructor
@SecurityRequirement(name = "BearerAuth")
public class HistoryController {

    private final HistoryService historyService;

    @Operation(summary = "获取浏览历史")
    @GetMapping
    public Result<Result.PageResult<HistoryVO>> list(@RequestParam(defaultValue = "1") int page,
                          @RequestParam(defaultValue = "10") int pageSize) {
        JwtUserDetails u = JwtUserDetails.getCurrentUser();
        return Result.ok(historyService.listHistory(u.getUserId(), page, pageSize));
    }

    @Operation(summary = "添加浏览记录")
    @PostMapping
    public Result<Void> add(@Valid @RequestBody HistoryRequest req) {
        historyService.addHistory(JwtUserDetails.getCurrentUser().getUserId(), req.getNewsId());
        return Result.okMsg("添加浏览记录成功");
    }

    @Operation(summary = "清空浏览历史")
    @DeleteMapping
    public Result<Void> clear() {
        historyService.clearHistory(JwtUserDetails.getCurrentUser().getUserId());
        return Result.okMsg("清空浏览历史成功");
    }
}
