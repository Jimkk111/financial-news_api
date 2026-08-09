package com.financial.news.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 新闻-标签关联实体
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("news_tags")
public class NewsTag {

    @TableId(type = IdType.AUTO)
    private Integer id;

    /** 新闻 ID */
    private Integer newsId;

    /** 标签 ID */
    private Integer tagId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
