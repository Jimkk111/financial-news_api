package com.financial.news.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 草稿-标签关联实体
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DraftTag {

    private Integer id;

    /** 草稿 ID */
    private String draftId;

    /** 标签 ID */
    private Integer tagId;

    private LocalDateTime createdAt;
}
