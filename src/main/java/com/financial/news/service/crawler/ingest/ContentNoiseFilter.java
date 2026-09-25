package com.financial.news.service.crawler.ingest;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * 内容噪声过滤器
 * <p>广告/分享/推荐类节点的词元级匹配，替代 CSS 子串选择器（[class*=ad]）——
 * 子串匹配会误删 class 含 "ad" 的正常节点（lazyload、download、readmore、head、
 * padding 等），导致正文零散丢图丢段。这里逐词元整词判定：ad/ads 词元开头、
 * 含 _adv_ 词元（如 em_handle_adv_close）、share/recommend/comment/related 词元。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
final class ContentNoiseFilter {

    /** 无条件移除的标签（与正文语义无关） */
    private static final String STRIP_TAGS = "script, style, iframe, noscript, ins";

    private ContentNoiseFilter() {
    }

    /**
     * 就地移除文档中的噪声节点
     */
    static void remove(Document doc) {
        doc.select(STRIP_TAGS).remove();
        for (Element el : doc.getAllElements()) {
            if (isNoise(el)) {
                el.remove();
            }
        }
    }

    static boolean isNoise(Element el) {
        for (String token : el.classNames()) {
            if (isNoiseToken(token)) {
                return true;
            }
        }
        String id = el.id();
        return id != null && !id.isBlank() && isNoiseToken(id);
    }

    /**
     * 噪声词元判定：整词匹配，不做子串匹配
     * <ul>
     *   <li>ad / ads / ad-xxx / ad_xxx（如 id="ad_context3"）</li>
     *   <li>含 _adv_ 词元（如 class="em_handle_adv_close"，东财正文中嵌广告）</li>
     *   <li>share / recommend / comment / related 及其 -/_ 后缀组合，以及 *_related 后缀
     *       （如新浪港股文章正文里内嵌的 hqimg_related 热点栏目链接簇）</li>
     * </ul>
     * 注意 "admin"、"lazyload"、"readmore" 等含 ad 子串的正常词元不会命中。
     */
    private static boolean isNoiseToken(String token) {
        return token.matches("(?i)^(ad|ads)([-_].+)?")
                || token.matches("(?i)^.+_adv([_-].+)?$")
                || token.matches("(?i)^(share|recommend|comment|related)([-_].+)?")
                || token.matches("(?i)^.+_related$");
    }
}
