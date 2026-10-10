package com.financial.news.service.quote.provider.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 日 K 单根（fq 表示实际复权口径）
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KlineBar {

    private LocalDate date;

    private Double open;

    private Double close;

    private Double high;

    private Double low;

    /** 成交量（股，已归一化） */
    private Long volume;

    /** 成交额（原币种元） */
    private Double turnover;

    /** qfq 前复权；none 原始价格。 */
    private String fq = "qfq";

    public KlineBar(LocalDate date, Double open, Double close, Double high, Double low,
                    Long volume, Double turnover) {
        this.date = date;
        this.open = open;
        this.close = close;
        this.high = high;
        this.low = low;
        this.volume = volume;
        this.turnover = turnover;
    }
}
