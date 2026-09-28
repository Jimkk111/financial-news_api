package com.financial.news.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.financial.news.model.content.Block;
import com.financial.news.utils.ContentCodec;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 草稿创建请求
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Schema(description = "草稿创建请求")
public class DraftCreateRequest {
    @NotBlank @Size(min = 1, max = 200)
    @Schema(description = "标题")
    private String title;

    @Schema(description = "正文：块级 JSON 数组（严格契约）；过渡期兼容 HTML 字符串")
    private JsonNode content;

    @Size(max = 5000)
    @JsonDeserialize(using = ContentCodec.BlockListDeserializer.class)
    @Schema(description = "正文（块级 JSON，兼容字段；优先级低于 content 数组）")
    private List<Block> contentJson;

    @Size(max = 500)
    @Schema(description = "封面图URL")
    private String coverImage;

    @Schema(description = "分类ID")
    private Integer categoryId;

    @Schema(description = "标签ID列表")
    private List<Integer> tags;
}
