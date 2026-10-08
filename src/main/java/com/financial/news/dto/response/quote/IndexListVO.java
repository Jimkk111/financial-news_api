package com.financial.news.dto.response.quote;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 指数卡片列表（单市场）
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IndexListVO {

    /** CN | HK | US */
    private String market;

    /** 任一指数数据延迟即为 true */
    private boolean delayed;

    private List<QuoteSnapshotVO> indices;
}
