package com.financial.news.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 新闻实体
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("news")
public class News {

    @TableId(type = IdType.AUTO)
    private Integer id;

    /** 标题 */
    private String title;

    /** 摘要 */
    private String summary;

    /** 正文内容 */
    private String content;

    /** 发布时间 */
    private LocalDateTime publishTime;

    /** 来源 */
    private String source;

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

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private LocalDateTime deletedAt;
}
