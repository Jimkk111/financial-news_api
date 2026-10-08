package com.financial.news.dto.response.quote;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 搜索结果（无结果时 items 为空数组，HTTP 200）
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuoteSearchVO {

    private String keyword;

    private List<QuoteSearchItemVO> items;
}
