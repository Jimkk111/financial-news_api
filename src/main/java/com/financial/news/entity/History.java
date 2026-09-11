package com.financial.news.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 浏览历史实体
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class History {

    private Integer id;

    private Integer userId;

    private Integer newsId;

    private LocalDateTime viewedAt;
}
