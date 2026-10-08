package com.financial.news.service.quote.provider.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分时数据（当日）
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TrendData {

    private String symbol;

    /** 昨收基准 */
    private Double prevClose;

    private List<TrendPoint> points;
}
