package com.financial.news.service.quote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.news.config.QuoteProperties;
import com.financial.news.service.quote.provider.EastMoneyQuoteProvider;
import com.financial.news.service.quote.provider.UpstreamException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SecurityPaginationTest {
    @Test
    void paginationUsesActualRowsWhenUpstreamReturnsSmallerPages() throws Exception {
        check(false);
    }

    @Test
    void failedLaterPageIsRejectedInsteadOfReturningPartialUniverse() throws Exception {
        check(true);
    }

    private void check(boolean failSecondPage) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/qt/clist/get", exchange -> {
            int page = calls.incrementAndGet();
            String body = page == 1
                    ? "{\"data\":{\"total\":2,\"diff\":[{\"f12\":\"600001\",\"f13\":1,\"f14\":\"first\"}]}}"
                    : "{\"data\":{\"total\":2,\"diff\":[{\"f12\":\"600002\",\"f13\":1,\"f14\":\"second\"}]}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(failSecondPage && page > 1 ? 503 : 200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try {
            QuoteProperties properties = new QuoteProperties();
            properties.getProvider().setEastMoneyBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            EastMoneyQuoteProvider provider = new EastMoneyQuoteProvider(properties, new ObjectMapper());
            if (failSecondPage) {
                assertThrows(UpstreamException.class, () -> provider.listAllSecurities("m:1"));
            } else {
                assertEquals(2, provider.listAllSecurities("m:1").size());
            }
            assertEquals(2, calls.get());
        } finally {
            server.stop(0);
        }
    }
}
