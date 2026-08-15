package com.financial.news.dto.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.financial.news.model.content.Block;
import com.financial.news.utils.ContentCodec;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

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
    @Schema(description = "内容（旧 HTML，过渡兼容，contentJson 优先）")
    private String content;

    @Size(max = 5000)
    @JsonDeserialize(using = ContentCodec.BlockListDeserializer.class)
    @Schema(description = "内容（块级 JSON，新契约，传 null 表示不修改）")
    private List<Block> contentJson;

    @Size(max = 500)
    @Schema(description = "封面图URL")
    private String coverImage;

    @Schema(description = "分类ID")
    private Integer categoryId;

    @Schema(description = "标签ID列表（传 null 表示不修改，传空数组表示清空）")
    private List<Integer> tags;
}
