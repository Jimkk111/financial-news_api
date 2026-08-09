package com.financial.news.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 会话标题更新请求
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Schema(description = "更新会话标题请求")
public class AiSessionUpdateRequest {
    @NotBlank @Size(min = 1, max = 100)
    @Schema(description = "会话标题")
    private String title;
}
