package com.financial.news.controller;

import com.financial.news.common.Result;
import com.financial.news.service.MediaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 媒体控制器：新闻图片/视频上传（阿里云 OSS）
 */
@Tag(name = "媒体模块", description = "新闻图片/视频上传")
@RestController @RequestMapping("/api/media") @RequiredArgsConstructor @SecurityRequirement(name = "BearerAuth")
public class MediaController {

    private final MediaService mediaService;

    @Operation(summary = "上传图片", description = "支持 jpeg/png/gif/webp，不超过 10MB；返回的 url 可用于草稿封面、正文 ImageBlock")
    @PostMapping("/image")
    public Result<Map<String, String>> uploadImage(@RequestParam("file") MultipartFile file) {
        return Result.ok(Map.of("url", mediaService.uploadImage(file)));
    }

    @Operation(summary = "上传视频", description = "支持 mp4/mov、webm/mkv，不超过 200MB；返回的 url 可用于正文 VideoBlock")
    @PostMapping("/video")
    public Result<Map<String, String>> uploadVideo(@RequestParam("file") MultipartFile file) {
        return Result.ok(Map.of("url", mediaService.uploadVideo(file)));
    }
}
