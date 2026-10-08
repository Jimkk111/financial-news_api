package com.financial.news.service.quote.provider.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 日 K 单根（前复权）
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
}
