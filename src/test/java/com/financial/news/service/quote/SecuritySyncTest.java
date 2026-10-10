package com.financial.news.service.quote;

import com.financial.news.config.QuoteProperties;
import com.financial.news.mapper.QuoteSecurityMapper;
import com.financial.news.service.quote.provider.EastMoneyQuoteProvider;
import com.financial.news.service.quote.provider.UpstreamException;
import com.financial.news.service.quote.provider.model.UpstreamSecurity;
import com.financial.news.service.quote.sync.SecurityMasterSyncJob;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecuritySyncTest {
    @Test
    void retriesFailedMarketWithoutRepeatingSuccessfulMarkets() {
        EastMoneyQuoteProvider provider = mock(EastMoneyQuoteProvider.class);
        QuoteSecurityMapper mapper = mock(QuoteSecurityMapper.class);
        AtomicInteger hkCalls = new AtomicInteger();
        when(provider.listAllSecurities(anyString())).thenAnswer(invocation -> {
            String fs = invocation.getArgument(0);
            if (fs.equals("m:116") && hkCalls.incrementAndGet() == 1) {
                throw new UpstreamException("temporary failure");
            }
            int market = fs.equals("m:116") ? 116 : fs.contains("105") ? 105 : 1;
            List<UpstreamSecurity> rows = IntStream.range(0, 100)
                    .mapToObj(i -> new UpstreamSecurity(market == 116 ? "%05d".formatted(i)
                            : market == 105 ? "T" + i : "%06d".formatted(600000 + i), market, "stock"))
                    .toList();
            return rows;
        });
        SecurityMasterSyncJob job = new SecurityMasterSyncJob(provider, mapper, new QuoteProperties());
        job.sync();
        assertNotNull(job.lastSuccessfulSync(MarketEnum.CN));
        assertNull(job.lastSuccessfulSync(MarketEnum.HK));
        job.retryIncomplete();
        assertNotNull(job.lastSuccessfulSync(MarketEnum.HK));
        verify(provider, times(4)).listAllSecurities(anyString());
        verify(mapper, times(300)).upsert(any());
    }

    @Test
    void tinyUniverseNeverOverwritesStocksOrMarksThemDelisted() {
        EastMoneyQuoteProvider provider = mock(EastMoneyQuoteProvider.class);
        QuoteSecurityMapper mapper = mock(QuoteSecurityMapper.class);
        when(provider.listAllSecurities(anyString())).thenReturn(List.of(
                new UpstreamSecurity("600001", 1, "stock")));
        SecurityMasterSyncJob job = new SecurityMasterSyncJob(provider, mapper, new QuoteProperties());
        job.sync();
        assertNull(job.lastSuccessfulSync(MarketEnum.CN));
        verify(mapper, never()).upsert(any());
        verify(mapper, never()).incrementMissing(anyString(), any(), anyInt());
    }
}
