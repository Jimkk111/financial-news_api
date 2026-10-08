package com.financial.news.dto.response.quote;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 搜索结果项（三市场混合，标注市场与股票/指数类型）
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuoteSearchItemVO {

    /** stock | index */
    private String secType;

    private String symbol;

    private String name;

    /** CN | HK | US */
    private String market;

    private String currency;

    /** ACTIVE | DELISTED（E4：退市可搜、标注展示） */
    private String status;
}
