package com.financial.news.dto.response.quote;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 日 K 单根
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KlineBarVO {

    /** 交易日 yyyy-MM-dd */
    private String date;

    private Double open;

    private Double high;

    private Double low;

    private Double close;

    /** 成交量（股） */
    private Long volume;

    /** 成交额（原币种元） */
    private Double turnover;

    /** 相对前一根收盘的涨跌幅（%） */
    private Double changePercent;
}
