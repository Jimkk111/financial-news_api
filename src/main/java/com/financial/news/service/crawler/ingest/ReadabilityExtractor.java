package com.financial.news.service.crawler.ingest;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

/**
 * Readability 式正文提取器
 * <p>Readability 算法的紧凑实现：对段落按文本长度与标点密度打分，
 * 分数累加到父/祖父容器，取最高分容器作为正文区域，
 * 并用链接密度剔除导航/推荐类噪声。作为每站定点选择器的统一兜底。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Component
public class ReadabilityExtractor {

    private static final String STRIP_SELECTOR =
            "script, style, noscript, iframe, form, nav, header, footer, aside, " +
            "[class*=ad], [id*=ad], [class*=comment], [class*=recommend], [class*=related], [class*=share]";

    /**
     * 提取正文区域 HTML；无法定位时返回空字符串（由调用方决定拒绝）
     */
    public String extract(Document doc) {
        Document work = doc.clone();
        work.select(STRIP_SELECTOR).remove();
        work.getElementsByClass("ad").remove();

        Elements candidates = work.select("p, pre, td");
        if (candidates.isEmpty()) {
            return "";
        }

        // 段落打分，并累加到父/祖父容器
        java.util.Map<Element, Double> scores = new java.util.IdentityHashMap<>();
        for (Element p : candidates) {
            String text = p.text();
            int len = text.length();
            if (len < 20) {
                continue;
            }
            double score = 1 + len / 25.0 + countPunctuation(text);
            score *= (1.0 - linkDensity(p));
            Element parent = p.parent();
            if (parent != null) {
                scores.merge(parent, score, Double::sum);
                Element grand = parent.parent();
                if (grand != null) {
                    scores.merge(grand, score / 2.0, Double::sum);
                }
            }
        }
        if (scores.isEmpty()) {
            return "";
        }

        Element best = scores.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey)
                .orElse(null);
        if (best == null) {
            return "";
        }
        return best.html();
    }

    /**
     * 链接密度：元素内 <a> 文本占比，导航/推荐区块通常 > 0.5
     */
    public static double linkDensity(Element el) {
        String allText = el.text();
        if (allText.isEmpty()) {
            return 1.0;
        }
        int linkLen = 0;
        for (Element a : el.select("a")) {
            linkLen += a.text().length();
        }
        return (double) linkLen / allText.length();
    }

    private static int countPunctuation(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '，' || c == '。' || c == '、' || c == '；' || c == '：'
                    || c == ',' || c == '.' || c == ';' || c == ':' || c == '”' || c == '"') {
                count++;
            }
        }
        return count;
    }
}
