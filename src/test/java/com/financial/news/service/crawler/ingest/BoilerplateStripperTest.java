package com.financial.news.service.crawler.ingest;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BoilerplateStripperTest {
    private static final String QR_URL = "https://n.sinaimg.cn/finance/transform/340/w170h170/20220415/"
            + "bd6a-a2376d5226aaa796dfdca62b1d9b1fcb.png";

    @Test
    void removesFuturesPromotionFromNews1793() {
        String html = "<p>贝森特最近任命两位顾问。</p>"
                + "<p>新浪合作大平台期货开户 安全快捷有保障</p>"
                + "<div><img src='" + QR_URL + "'></div>";
        var body = Jsoup.parseBodyFragment(BoilerplateStripper.strip(html)).body();
        assertEquals("贝森特最近任命两位顾问。", body.text());
        assertTrue(body.select("img, div").isEmpty());
    }

    @Test
    void removesLazyLoadedAndResponsivePromotionImages() {
        for (String attribute : new String[]{"src", "data-src", "data-original", "data-url", "srcset", "data-srcset"}) {
            String placeholder = attribute.equals("src") ? "" : "src='placeholder.gif' ";
            String html = "<p>正文</p><img " + placeholder + attribute + "='" + QR_URL + "'>";
            assertTrue(Jsoup.parseBodyFragment(BoilerplateStripper.strip(html)).select("img").isEmpty(), attribute);
        }
    }

    @Test
    void preservesOtherImagesAndNewsAboutQrCodes() {
        String html = "<p>本文介绍二维码支付。</p><div><img src='chart.png'><img src='" + QR_URL + "'></div>"
                + "<p><img src='payment-qr.png' alt='二维码'></p>";
        var body = Jsoup.parseBodyFragment(BoilerplateStripper.strip(html)).body();
        assertEquals(2, body.select("img").size());
        assertEquals("chart.png", body.selectFirst("div img").attr("src"));
        assertEquals("本文介绍二维码支付。", body.text());
        assertEquals(body.html(), BoilerplateStripper.strip(body.html()));
    }
}
