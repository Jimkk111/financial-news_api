package com.financial.news.dto.response.quote;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 热门标的列表（单市场）
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HotListVO {

    private String market;

    private boolean delayed;

    private List<QuoteSnapshotVO> stocks;
}
