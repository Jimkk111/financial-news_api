package com.financial.news.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 浏览记录请求
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Schema(description = "浏览记录请求")
public class HistoryRequest {
    @NotNull(message = "新闻ID不能为空")
    @Schema(description = "新闻ID", example = "1")
    private Integer newsId;
}
