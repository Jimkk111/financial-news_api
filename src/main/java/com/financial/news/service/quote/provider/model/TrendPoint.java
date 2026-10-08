package com.financial.news.service.quote.provider.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 分时单点（1 分钟）
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TrendPoint {

    /** 时间（北京时间） */
    private LocalDateTime time;

    /** 该分钟价格 */
    private Double price;

    /** 该分钟成交量（股，已归一化） */
    private Long volume;

    /** 当日累计均价 */
    private Double avgPrice;
}
