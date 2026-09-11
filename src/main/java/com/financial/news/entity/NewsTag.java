package com.financial.news.entity;

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
public class NewsTag {

    private Integer id;

    /** 新闻 ID */
    private Integer newsId;

    /** 标签 ID */
    private Integer tagId;

    private LocalDateTime createdAt;
}
