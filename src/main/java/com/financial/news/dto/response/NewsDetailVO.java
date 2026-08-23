package com.financial.news.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.financial.news.entity.Category;
import com.financial.news.entity.Tag;
import com.financial.news.model.content.Block;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 新闻详情视图对象
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NewsDetailVO {

    private Integer id;
    private String title;
    private String summary;
    private String content;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<Block> contentJson;

    private LocalDateTime publishTime;
    private String source;
    private Integer views;
    private Boolean hasImage;
    private String imageUrl;
    private Integer categoryId;
    private Integer userId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 标签列表（联表查询填充） */
    private List<Tag> tags;

    /** 分类信息（联表查询填充） */
    private Category category;
}
