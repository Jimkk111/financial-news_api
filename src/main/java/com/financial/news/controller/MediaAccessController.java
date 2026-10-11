package com.financial.news.controller;

import com.financial.news.common.Result;
import com.financial.news.security.JwtUserDetails;
import com.financial.news.service.MediaAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaAccessController {
    private final MediaAccessService mediaAccessService;

    @GetMapping("/access")
    public ResponseEntity<Result<Map<String, String>>> access(@RequestParam String url,
            @RequestParam(required = false) Integer newsId, @RequestParam(required = false) String draftId) {
        JwtUserDetails user = JwtUserDetails.getCurrentUserOrNull();
        String signed = mediaAccessService.refresh(url, newsId, draftId, user == null ? null : user.getUserId());
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(Result.ok(Map.of("url", signed)));
    }
}
