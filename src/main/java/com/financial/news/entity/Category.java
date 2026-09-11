package com.financial.news.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 分类实体
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Category {

    /** 主键 */
    private Integer id;

    /** 分类名称 */
    private String name;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
