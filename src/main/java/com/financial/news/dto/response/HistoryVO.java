package com.financial.news.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 浏览历史视图对象（联表查询结果）
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
public class HistoryVO {

    private Integer newsId;
    private String title;
    private String summary;
    private String source;
    private LocalDateTime publishTime;
    private Integer views;
    private Boolean hasImage;
    private String imageUrl;
    private Integer categoryId;
    private LocalDateTime viewedAt;
}
