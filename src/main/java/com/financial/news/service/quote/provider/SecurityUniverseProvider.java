package com.financial.news.service.quote.provider;

import com.financial.news.service.quote.MarketEnum;
import com.financial.news.service.quote.provider.model.UpstreamSecurity;
import java.util.List;

public interface SecurityUniverseProvider {
    String name();
    List<UpstreamSecurity> listSecurities(MarketEnum market);
}
