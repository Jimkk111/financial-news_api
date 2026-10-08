package com.financial.news.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 热门标的名单（PRD Q1：后端可配置，首期人工圈定，月度更新）
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuoteHotList {

    private Integer id;

    /** 市场：CN/HK/US */
    private String market;

    /** 完整标识，须存在于 quote_security */
    private String symbol;

    /** 展示顺序，小在前 */
    private Integer sortOrder;

    /** 是否启用：0 否 1 是 */
    private Integer enabled;

    /** 备注（圈定依据/日期） */
    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
