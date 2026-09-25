package com.financial.news.service.crawler.ingest;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.regex.Pattern;

/**
 * 尾部固定文案清理
 * <p>各源正文尾部随附的运营模板：新浪财经每篇结尾的二维码图、"海量资讯…APP"
 * 导流文案与责编署名，东方财富的"郑重声明"免责块。这些内容不是新闻本体，
 * 混入库后前端每篇都带一段固定垃圾。按整段文本匹配（限长防误伤正文引用）
 * 与固定素材 URL 识别，在入库清洗阶段统一剥离。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
final class BoilerplateStripper {

    /** 固定文案规则（整段匹配且长度受限，避免误删正文中引用这些词的段落） */
    private static final Pattern[] TEXT_RULES = {
            Pattern.compile("海量资讯.*?(APP|客户端).*"),
            Pattern.compile(".*新浪财经(APP|客户端).*"),
            Pattern.compile("责任编辑[：:].*"),
            Pattern.compile("郑重声明[：:].*")
    };

    /** 新浪尾部二维码固定素材（多分辨率同名系列） */
    private static final Pattern QR_IMG = Pattern.compile("n\\.sinaimg\\.cn/finance/cece9e13|655959900_");

    /** 段内元数据尾巴的起点标记（来源署名/责编/原标题常连排挤在末段） */
    private static final String[] TAIL_MARKERS = {"（文章来源", "文章来源：", "责任编辑：", "原标题："};

    /** 元数据尾巴的最大长度（超过视为正文本身） */
    private static final int MAX_TAIL_LEN = 150;

    /** 文案匹配的最大段落长度 */
    private static final int MAX_TEXT_LEN = 80;

    private BoilerplateStripper() {
    }

    static String strip(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document doc = Jsoup.parseBodyFragment(html);
        for (Element el : doc.select("p, span, div")) {
            String text = el.text().trim();
            if (text.isBlank()) {
                continue;
            }
            if (isBoilerplate(text)) {
                el.remove();
                continue;
            }
            trimMetaTail(el, text);
        }
        for (Element img : doc.select("img")) {
            if (QR_IMG.matcher(img.attr("src")).find()) {
                Element parent = img.parent();
                img.remove();
                // 外层容器只剩这个二维码时一并移除，避免留空段
                if (parent != null && !parent.tagName().equals("body")
                        && parent.parent() != null && parent.text().isBlank()) {
                    parent.remove();
                }
            }
        }
        return doc.body().html();
    }

    /**
     * 纯文本段落尾部的元数据裁剪：旧数据里"正文末句 + （文章来源）+ 责任编辑 + 原标题"
     * 常挤在同一段。从最早的元数据标记处截断，保留之前的正文；标记就在段首的整段移除。
     */
    private static void trimMetaTail(Element el, String text) {
        if (!el.children().isEmpty()) {
            return; // 含行内标签的段落不做文本级改写，避免破坏结构
        }
        int cut = -1;
        for (String marker : TAIL_MARKERS) {
            int idx = text.indexOf(marker);
            if (idx >= 0 && (cut < 0 || idx < cut)) {
                cut = idx;
            }
        }
        if (cut < 0) {
            return;
        }
        String tail = text.substring(cut);
        if (tail.length() > MAX_TAIL_LEN) {
            return;
        }
        if (cut == 0) {
            el.remove();
        } else {
            el.text(text.substring(0, cut).trim());
        }
    }

    static boolean isBoilerplate(String text) {
        if (text.length() > MAX_TEXT_LEN) {
            return false;
        }
        for (Pattern rule : TEXT_RULES) {
            if (rule.matcher(text).matches()) {
                return true;
            }
        }
        return false;
    }
}
