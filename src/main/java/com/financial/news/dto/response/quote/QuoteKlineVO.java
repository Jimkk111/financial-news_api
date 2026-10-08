package com.financial.news.dto.response.quote;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 日 K VO（固定前复权）
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class QuoteKlineVO extends QuoteBaseVO {

    /** 复权方式，首期固定 qfq */
    private String fq;

    private List<KlineBarVO> bars;

    private KlineMaVO ma;
}
