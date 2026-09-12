package com.financial.news.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.financial.news.model.content.Block;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 新闻实体
 * <p>content_json 列通过 BlockListTypeHandler 映射（见 mapper/NewsMapper.xml）；
 * deleted_at 为软删除标记，过滤条件由各查询 SQL 显式携带</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class News {

    private Integer id;

    /** 标题 */
    private String title;

    /** 摘要 */
    private String summary;

    /** 正文内容（旧 HTML，过渡期保留） */
    private String content;

    /** 正文内容（块级 JSON，新契约；列表查询排除列后为 null 不输出） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<Block> contentJson;

    /** 发布时间 */
    private LocalDateTime publishTime;

    /** 来源 */
    private String source;

    /** 文章来源URL */
    private String url;

    /** 正文simhash指纹（近似去重） */
    private Long contentFingerprint;

    /** 浏览量 */
    private Integer views;

    /** 是否有图片 */
    private Boolean hasImage;

    /** 图片 URL */
    private String imageUrl;

    /** 分类 ID */
    private Integer categoryId;

    /** 发布用户 ID */
    private Integer userId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 软删除时间，非 null 即已删除 */
    private LocalDateTime deletedAt;
}
