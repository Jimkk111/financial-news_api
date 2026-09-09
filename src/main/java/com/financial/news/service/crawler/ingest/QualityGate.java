package com.financial.news.service.crawler.ingest;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 入库质量门禁
 * <p>确定性规则决定"能不能入库"，不达标一律拒绝并记录原因：</p>
 * <ul>
 *   <li>正文纯文本长度达标（防垃圾段落/空壳页）</li>
 *   <li>链接密度不超标（防导航/聚合页当正文）</li>
 *   <li>必须有可解析的发布时间（不伪造当前时间）</li>
 * </ul>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Component
public class QualityGate {

    @Value("${crawler.ingest.min-content-length:200}")
    private int minContentLength;

    @Value("${crawler.ingest.max-link-density:0.6}")
    private double maxLinkDensity;

    public record Result(boolean pass, String reason) {
    }

    public Result check(String contentHtml, String plainText, LocalDateTime publishTime) {
        if (publishTime == null) {
            return new Result(false, "缺少发布时间");
        }
        if (plainText == null || plainText.isBlank()) {
            return new Result(false, "正文为空");
        }
        if (plainText.length() < minContentLength) {
            return new Result(false, "正文过短(" + plainText.length() + "<" + minContentLength + "字)");
        }
        if (contentHtml != null && !contentHtml.isBlank()) {
            Element body = Jsoup.parseBodyFragment(contentHtml).body();
            double density = ReadabilityExtractor.linkDensity(body);
            if (density > maxLinkDensity) {
                return new Result(false, String.format("链接密度过高(%.2f)，疑似导航页", density));
            }
        }
        return new Result(true, null);
    }
}
