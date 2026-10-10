package com.financial.news.service.quote;

import com.financial.news.config.QuoteProperties;
import com.financial.news.entity.QuoteSecurity;
import com.financial.news.mapper.QuoteHotListMapper;
import com.financial.news.mapper.QuoteSecurityMapper;
import com.financial.news.service.quote.sync.SecurityMasterSyncJob;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockListTest {
    @Test
    void allStocksRemainVisibleWhenQuotesFailAndHotListIsNotUsed() {
        QuoteSecurityMapper mapper = mock(QuoteSecurityMapper.class);
        QuoteCacheService cache = mock(QuoteCacheService.class);
        QuoteHotListMapper hot = mock(QuoteHotListMapper.class);
        when(mapper.countActiveStocksByMarket("CN")).thenReturn(5000L);
        when(mapper.selectStockPage("CN", 50L, 50)).thenReturn(List.of(
                QuoteSecurity.builder().symbol("920001.BJ").name("北交所股票")
                        .market("CN").currency("CNY").secType(1).status(1).build()));
        when(cache.getOrLoad(anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("upstream unavailable"));
        QuoteService service = new QuoteService(null, cache, mock(TradingSessionService.class),
                mapper, hot, new QuoteProperties(), mock(SecurityMasterSyncJob.class));
        var page = service.getStockList("cn", 2, 50);
        assertEquals(5000, page.total());
        assertTrue(page.hasMore());
        assertEquals("920001.BJ", page.stocks().getFirst().getSymbol());
        assertNull(page.stocks().getFirst().getLatestPrice());
        assertTrue(page.delayed());
        assertFalse(page.syncComplete());
        verifyNoInteractions(hot);
    }

    @Test
    void outOfRangePageDoesNotRequestQuotesAndLargePageSizeIsBounded() {
        QuoteSecurityMapper mapper = mock(QuoteSecurityMapper.class);
        QuoteCacheService cache = mock(QuoteCacheService.class);
        when(mapper.countActiveStocksByMarket("US")).thenReturn(1000L);
        QuoteService service = new QuoteService(null, cache, null, mapper, null,
                new QuoteProperties(), mock(SecurityMasterSyncJob.class));
        var page = service.getStockList("US", Integer.MAX_VALUE, 10000);
        assertEquals(100, page.pageSize());
        assertFalse(page.hasMore());
        assertTrue(page.stocks().isEmpty());
        verifyNoInteractions(cache);
        verify(mapper, never()).selectStockPage(anyString(), anyLong(), anyInt());
    }
}
