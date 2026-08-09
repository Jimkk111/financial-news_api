package com.financial.news.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 草稿更新请求
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Schema(description = "草稿更新请求")
public class DraftUpdateRequest {
    @Size(min = 1, max = 200)
    @Schema(description = "标题")
    private String title;

    @Size(max = 50000)
    @Schema(description = "内容")
    private String content;

    @Size(max = 500)
    @Schema(description = "封面图URL")
    private String coverImage;

    @Schema(description = "分类ID")
    private Integer categoryId;
}
