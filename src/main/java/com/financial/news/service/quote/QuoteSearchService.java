package com.financial.news.service.quote;

import com.financial.news.entity.QuoteSecurity;
import com.financial.news.mapper.QuoteSecurityMapper;
import com.financial.news.dto.response.quote.QuoteSearchItemVO;
import com.financial.news.dto.response.quote.QuoteSearchVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 标的搜索：三市场混合，三路命中（代码前缀 / 名称包含 / 拼音首字母前缀）合并排序。
 * <p>排序：精确代码命中 > 代码前缀 > 拼音前缀 > 名称包含，同分按 symbol 稳定排序。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Service
@RequiredArgsConstructor
public class QuoteSearchService {

    private static final int PER_CHANNEL_LIMIT = 20;

    private final QuoteSecurityMapper securityMapper;

    public QuoteSearchVO search(String rawKeyword, int limit) {
        String keyword = rawKeyword == null ? "" : rawKeyword.trim();
        if (keyword.isEmpty()) {
            return new QuoteSearchVO(keyword, List.of());
        }
        String upper = keyword.toUpperCase();

        Map<String, Integer> rankBy = new LinkedHashMap<>();
        collect(rankBy, securityMapper.searchBySymbolPrefix(upper, PER_CHANNEL_LIMIT), 1);
        collect(rankBy, securityMapper.searchByPinyinPrefix(upper, PER_CHANNEL_LIMIT), 2);
        collect(rankBy, securityMapper.searchByNameLike(keyword, PER_CHANNEL_LIMIT), 3);
        // 精确代码/完整 symbol 命中置顶
        rankBy.replaceAll((key, rank) -> key.contains("|") && exactMatches(key, upper) ? 0 : rank);

        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(rankBy.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                .thenComparing(Map.Entry::getKey));

        List<QuoteSearchItemVO> items = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : sorted) {
            if (items.size() >= limit) {
                break;
            }
            String symbol = entry.getKey().split("\\|", 2)[0];
            Integer secType = Integer.valueOf(entry.getKey().split("\\|", 2)[1]);
            QuoteSecurity security = securityMapper.selectBySymbolAndType(symbol, secType);
            if (security == null) {
                continue;
            }
            items.add(new QuoteSearchItemVO(
                    QuoteSecType.fromDbValue(security.getSecType()).getApiValue(),
                    security.getSymbol(),
                    security.getName(),
                    security.getMarket(),
                    security.getCurrency(),
                    security.getStatus() != null && security.getStatus() == QuoteSecurity.STATUS_DELISTED
                            ? "DELISTED" : "ACTIVE"));
        }
        return new QuoteSearchVO(keyword, items);
    }

    private boolean exactMatches(String key, String upper) {
        String symbol = key.split("\\|", 2)[0];
        String code = symbol.substring(0, symbol.indexOf('.'));
        return symbol.equals(upper) || code.equals(upper);
    }

    private void collect(Map<String, Integer> rankBy, List<QuoteSecurity> securities, int rank) {
        for (QuoteSecurity security : securities) {
            rankBy.putIfAbsent(security.getSymbol() + "|" + security.getSecType(), rank);
        }
    }
}
