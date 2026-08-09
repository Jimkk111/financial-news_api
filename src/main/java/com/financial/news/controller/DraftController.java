package com.financial.news.controller;

import com.financial.news.common.Result;
import com.financial.news.dto.request.DraftCreateRequest;
import com.financial.news.dto.request.DraftUpdateRequest;
import com.financial.news.entity.Draft;
import com.financial.news.entity.News;
import com.financial.news.security.JwtUserDetails;
import com.financial.news.service.DraftService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 草稿控制器
 */
@Tag(name = "草稿模块", description = "草稿的增删改查及发布")
@RestController @RequestMapping("/api/drafts") @RequiredArgsConstructor @SecurityRequirement(name = "BearerAuth")
public class DraftController {

    private final DraftService draftService;

    @Operation(summary = "获取草稿列表") @GetMapping
    public Result<List<Draft>> list() {
        return Result.ok(draftService.listDrafts(JwtUserDetails.getCurrentUser().getUserId()));
    }

    @Operation(summary = "创建草稿") @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Result<Draft> create(@Valid @RequestBody DraftCreateRequest request) {
        return Result.ok(draftService.createDraft(JwtUserDetails.getCurrentUser().getUserId(), request));
    }

    @Operation(summary = "获取草稿详情") @GetMapping("/{id}")
    public Result<Draft> get(@PathVariable String id) {
        return Result.ok(draftService.getDraft(id, JwtUserDetails.getCurrentUser().getUserId()));
    }

    @Operation(summary = "更新草稿") @PutMapping("/{id}")
    public Result<Draft> update(@PathVariable String id, @Valid @RequestBody DraftUpdateRequest request) {
        return Result.ok(draftService.updateDraft(id, JwtUserDetails.getCurrentUser().getUserId(), request));
    }

    @Operation(summary = "删除草稿") @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        draftService.deleteDraft(id, JwtUserDetails.getCurrentUser().getUserId());
        return Result.okMsg("草稿删除成功");
    }

    @Operation(summary = "发布草稿") @PostMapping("/{id}/publish") @ResponseStatus(HttpStatus.CREATED)
    public Result<News> publish(@PathVariable String id) {
        return Result.ok(draftService.publishDraft(id, JwtUserDetails.getCurrentUser().getUserId()));
    }
}
