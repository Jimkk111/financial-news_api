package com.financial.news.dto.response.quote;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分时单点（minute 为折叠午休后的轴上偏移，0 起）
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TrendPointVO {

    private Integer minute;

    /** HH:mm（北京时间） */
    private String time;

    private Double price;

    private Double avgPrice;

    /** 成交量（股） */
    private Long volume;
}
