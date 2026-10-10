package com.financial.news.service.quote;

import com.financial.news.config.QuoteProperties;
import com.financial.news.mapper.QuoteSecurityMapper;
import com.financial.news.mapper.QuoteProviderSymbolMapper;
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
        when(provider.name()).thenReturn("eastmoney");
        when(provider.listSecurities(any())).thenAnswer(invocation -> {
            MarketEnum requested = invocation.getArgument(0);
            if (requested == MarketEnum.HK && hkCalls.incrementAndGet() == 1) {
                throw new UpstreamException("temporary failure");
            }
            int market = requested == MarketEnum.HK ? 116 : requested == MarketEnum.US ? 105 : 1;
            List<UpstreamSecurity> rows = IntStream.range(0, 100)
                    .mapToObj(i -> new UpstreamSecurity(market == 116 ? "%05d".formatted(i)
                            : market == 105 ? "T" + i : "%06d".formatted(600000 + i), market, "stock"))
                    .toList();
            return rows;
        });
        SecurityMasterSyncJob job = new SecurityMasterSyncJob(List.of(provider), mock(QuoteProviderSymbolMapper.class), mapper, new QuoteProperties());
        job.sync();
        assertNotNull(job.lastSuccessfulSync(MarketEnum.CN));
        assertNull(job.lastSuccessfulSync(MarketEnum.HK));
        job.retryIncomplete();
        assertNotNull(job.lastSuccessfulSync(MarketEnum.HK));
        verify(provider, times(4)).listSecurities(any());
        verify(mapper, times(300)).upsert(any());
    }

    @Test
    void tinyUniverseNeverOverwritesStocksOrMarksThemDelisted() {
        EastMoneyQuoteProvider provider = mock(EastMoneyQuoteProvider.class);
        QuoteSecurityMapper mapper = mock(QuoteSecurityMapper.class);
        when(provider.name()).thenReturn("eastmoney");
        when(provider.listSecurities(any())).thenReturn(List.of(
                new UpstreamSecurity("600001", 1, "stock")));
        SecurityMasterSyncJob job = new SecurityMasterSyncJob(List.of(provider), mock(QuoteProviderSymbolMapper.class), mapper, new QuoteProperties());
        job.sync();
        assertNull(job.lastSuccessfulSync(MarketEnum.CN));
        verify(mapper, never()).upsert(any());
        verify(mapper, never()).incrementMissing(anyString(), any(), anyInt());
    }
}
