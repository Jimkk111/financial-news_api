package com.financial.news.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新闻爬取请求
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Schema(description = "新闻爬取请求")
public class CrawlerRequest {

    @NotBlank
    @Size(min = 2, max = 500)
    @Schema(description = "爬取指令（自然语言）",
            example = "帮我爬取财联社的最新财经新闻")
    private String instruction;

    @Schema(description = "会话ID（不传则自动创建，传入可复用已有会话上下文）",
            example = "session-a1b2c3d4")
    private String sessionId;
}
