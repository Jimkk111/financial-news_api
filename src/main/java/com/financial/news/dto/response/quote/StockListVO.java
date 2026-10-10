package com.financial.news.dto.response.quote;

import java.util.List;

/** 按市场分页的全部在市股票；行情暂不可用时仍保留标的信息。 */
public record StockListVO(String market, int page, int pageSize, long total, boolean hasMore,
                          boolean syncComplete, String lastSyncedAt, boolean delayed,
                          List<QuoteSnapshotVO> stocks) {
}
