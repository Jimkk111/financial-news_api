package com.financial.news.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.news.model.content.*;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 块级内容编解码器（严格 JSON 契约版）
 * <p>与前端 src/utils/content 的 htmlToBlocks / blocksToHtml / validateContent /
 * sanitizeUrl 逐条对齐：块级白名单提取、行内 marks 表达（schema 中不出现 HTML 字符串、
 * style 或 class）、运行时归一化校验。两侧规则以本类与前端源码为共同事实源，
 * 样本级一致性由 fixture 测试保证。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
public final class ContentCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单篇正文块数上限（防御异常输入） */
    private static final int MAX_BLOCKS = 1000;

    /** 无标签纯文本按句聚合分段的目标段长（旧文本转储结构化用） */
    private static final int PLAIN_PARAGRAPH_CHARS = 150;

    /** 完全跳过、不提取任何内容的元素（与前端 SKIP_TAGS 一致） */
    private static final Set<String> SKIP_TAGS = Set.of(
            "script", "style", "iframe", "object", "embed", "form", "input",
            "textarea", "select", "button", "noscript", "template", "svg", "math", "link", "meta");

    private static final Pattern LANG_CLASS = Pattern.compile("language-([\\w-]+)");

    private ContentCodec() {
    }

    // ======================== 序列化 ========================

    /**
     * 将块级列表序列化为 JSON 字符串（MyBatis TypeHandler 入库时调用）。
     * 必须用显式 TypeReference 建立根级声明类型，否则泛型擦除后
     * 接口上的多态注解不生效，块级 type 判别字段会丢失。
     */
    public static String toJson(List<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) return null;
        try {
            return MAPPER.writerFor(new TypeReference<List<Block>>() {}).writeValueAsString(blocks);
        } catch (JsonProcessingException e) {
            log.error("Block 序列化失败", e);
            return null;
        }
    }

    /**
     * 将 JSON 字符串反序列化为块级列表（MyBatis TypeHandler 出库时调用）。
     * 旧格式（段落装 HTML 的上一代契约）含未知字段，反序列化失败返回 null，
     * 由读取层用 content HTML 现场转换兜底，迁移完成后自然消失。
     */
    public static List<Block> fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, new TypeReference<List<Block>>() {});
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    // ======================== HTML → 块（对齐前端 htmlToBlocks） ========================

    /**
     * 将 HTML 正文转换为块级列表。转换本身即白名单清洗：
     * 只识别白名单元素与属性，script、style、iframe、事件属性等一律不携带。
     * 另做两处源站数据清理（不影响 normalize 的契约一致性）：段首全角/半角
     * 空白剥离（源站缩进交由前端 CSS 统一）、空段落块剔除。
     */
    public static List<Block> fromHtml(String html) {
        if (html == null || html.isBlank()) return List.of();
        List<Block> blocks;
        if (!Pattern.compile("<[a-z][\\s\\S]*>", Pattern.CASE_INSENSITIVE).matcher(html).find()) {
            blocks = fromPlainText(html);
        } else {
            Document doc = Jsoup.parseBodyFragment(html);
            blocks = tidyBlocks(parseBlocks(doc.body()));
        }
        return normalize(blocks);
    }

    /** 递归整理：段首空白剥离 + 空段剔除（blockquote 内部同样处理） */
    private static List<Block> tidyBlocks(List<Block> blocks) {
        List<Block> out = new ArrayList<>();
        for (Block block : blocks) {
            if (block instanceof ParagraphBlock p) {
                List<InlineNode> children = tidyInlines(p.getChildren());
                if (!children.isEmpty()) {
                    out.add(ParagraphBlock.builder().children(children).build());
                }
            } else if (block instanceof HeadingBlock h) {
                List<InlineNode> children = tidyInlines(h.getChildren());
                if (!children.isEmpty()) {
                    out.add(HeadingBlock.builder().level(h.getLevel()).children(children).build());
                }
            } else if (block instanceof BlockquoteBlock q) {
                List<Block> children = tidyBlocks(q.getChildren() == null ? List.of() : q.getChildren());
                if (!children.isEmpty()) {
                    out.add(BlockquoteBlock.builder().children(children).build());
                }
            } else {
                out.add(block);
            }
        }
        return out;
    }

    /** 去掉行内序列开头的空白文本与 hardBreak；首文本节点去前导空白 */
    private static List<InlineNode> tidyInlines(List<InlineNode> inlines) {
        List<InlineNode> out = new ArrayList<>();
        boolean leading = true;
        if (inlines != null) {
            for (InlineNode node : inlines) {
                if (leading) {
                    if (node instanceof HardBreakNode) {
                        continue;
                    }
                    if (node instanceof TextNode t && t.getText() != null) {
                        String stripped = t.getText().replaceFirst("^[\\s\\u3000]+", "");
                        if (stripped.isEmpty()) {
                            continue;
                        }
                        out.add(TextNode.builder().text(stripped).marks(t.getMarks()).build());
                        leading = false;
                        continue;
                    }
                }
                leading = false;
                out.add(node);
            }
        }
        return out;
    }

    /**
     * 无结构纯文本 → 段落块：按空行分段（段内换行转 hardBreak）；
     * 超长单段按句末标点聚合切分（旧整页文本转储的结构化）
     */
    public static List<Block> fromPlainText(String text) {
        if (text == null || text.isBlank()) return List.of();
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        List<Block> blocks = new ArrayList<>();
        for (String para : normalized.split("\\n\\s*\\n")) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            List<InlineNode> children = new ArrayList<>();
            StringBuilder sentence = new StringBuilder();
            for (String line : trimmed.split("\\n")) {
                if (!sentence.isEmpty()) {
                    appendSentence(children, sentence.toString());
                    sentence.setLength(0);
                    children.add(HardBreakNode.builder().build());
                }
                sentence.append(line.trim());
            }
            appendSentence(children, sentence.toString());
            if (!children.isEmpty()) {
                blocks.add(ParagraphBlock.builder().children(children).build());
            }
        }
        return normalize(blocks);
    }

    /** 单行超长时按句末标点聚合切分（约 PLAIN_PARAGRAPHChars 字/段），短行原样成节点 */
    private static void appendSentence(List<InlineNode> children, String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        if (line.length() <= PLAIN_PARAGRAPH_CHARS) {
            children.add(TextNode.builder().text(line).build());
            return;
        }
        StringBuilder cur = new StringBuilder();
        for (String sent : line.split("(?<=[。！？；])")) {
            cur.append(sent);
            if (cur.length() >= PLAIN_PARAGRAPH_CHARS) {
                children.add(TextNode.builder().text(cur.toString().trim()).build());
                children.add(HardBreakNode.builder().build());
                cur.setLength(0);
            }
        }
        if (!cur.isEmpty()) {
            children.add(TextNode.builder().text(cur.toString().trim()).build());
        }
    }

    /** 容器级解析：子节点按序（裸文本成段、元素按标签分派），容器展开平铺 */
    private static List<Block> parseBlocks(Element container) {
        List<Block> blocks = new ArrayList<>();
        for (Node node : container.childNodes()) {
            if (node instanceof org.jsoup.nodes.TextNode textNode) {
                String text = textNode.getWholeText().trim();
                if (!text.isEmpty()) {
                    blocks.add(ParagraphBlock.builder()
                            .children(List.of(TextNode.builder().text(text).build())).build());
                }
            } else if (node instanceof Element el) {
                blocks.addAll(parseBlock(el));
            }
        }
        return blocks;
    }

    /** 单元素解析：可产出零到多个块（容器类元素平铺展开，与前端 parseSingleBlock 一致） */
    private static List<Block> parseBlock(Element el) {
        String tag = el.tagName().toLowerCase();
        switch (tag) {
            case "p":
                return List.of(ParagraphBlock.builder().children(parseInline(el, List.of())).build());
            case "h1", "h2", "h3", "h4", "h5", "h6":
                return List.of(HeadingBlock.builder()
                        .level("h1".equals(tag) ? 1 : "h2".equals(tag) ? 2 : 3)
                        .children(parseInline(el, List.of()))
                        .build());
            case "ul":
                return List.of(BulletListBlock.builder().items(parseListItems(el)).build());
            case "ol":
                return List.of(OrderedListBlock.builder().items(parseListItems(el)).build());
            case "blockquote":
                return List.of(BlockquoteBlock.builder().children(parseBlocks(el)).build());
            case "pre":
                return List.of(parseCodeBlock(el));
            case "hr":
                return List.of(DividerBlock.builder().build());
            case "img":
                return List.of(ImageBlock.builder().src(el.attr("src")).alt(blankToNull(el.attr("alt"))).build());
            case "video":
                return List.of(VideoBlock.builder().src(el.attr("src")).poster(blankToNull(el.attr("poster"))).build());
            case "br":
                return List.of(ParagraphBlock.builder().children(List.of(HardBreakNode.builder().build())).build());
            case "figure": {
                Element img = el.selectFirst("img");
                if (img == null) {
                    return parseBlocks(el);
                }
                Element figcaption = el.selectFirst("figcaption");
                return List.of(ImageBlock.builder()
                        .src(img.attr("src"))
                        .alt(blankToNull(img.attr("alt")))
                        .caption(figcaption != null ? blankToNull(figcaption.text().trim()) : null)
                        .build());
            }
            case "div", "article", "section", "main":
                return parseBlocks(el);
            default:
                if (SKIP_TAGS.contains(tag)) {
                    return List.of();
                }
                // 未知容器（如 table/tr/td）：递归提取文本，保留内容、丢弃结构
                return parseBlocks(el);
        }
    }

    private static List<ListItem> parseListItems(Element list) {
        List<ListItem> items = new ArrayList<>();
        for (Element child : list.children()) {
            if (!"li".equals(child.tagName().toLowerCase())) {
                continue;
            }
            items.add(ListItem.builder().children(parseInline(child, List.of())).build());
        }
        return items;
    }

    private static Block parseCodeBlock(Element pre) {
        String code = pre.text();
        if (code.endsWith("\n")) {
            code = code.substring(0, code.length() - 1);
        }
        Element codeEl = pre.selectFirst("code");
        String lang = null;
        if (codeEl != null) {
            Matcher m = LANG_CLASS.matcher(codeEl.attr("class"));
            if (m.find()) {
                lang = m.group(1);
            }
        }
        return CodeBlock.builder().code(code).lang(lang).build();
    }

    /** 行内解析：白名单标签映射 marks，未知行内容器只递归不新增标记 */
    private static List<InlineNode> parseInline(Node container, List<InlineMark> marks) {
        List<InlineNode> out = new ArrayList<>();
        for (Node node : container.childNodes()) {
            if (node instanceof org.jsoup.nodes.TextNode textNode) {
                String text = textNode.getWholeText();
                if (!text.isEmpty()) {
                    out.add(TextNode.builder().text(text)
                            .marks(marks.isEmpty() ? null : new ArrayList<>(marks)).build());
                }
            } else if (node instanceof Element el) {
                String tag = el.tagName().toLowerCase();
                switch (tag) {
                    case "br" -> out.add(HardBreakNode.builder().build());
                    case "strong", "b" -> out.addAll(parseInline(el, withMark(marks, InlineMark.builder().type(InlineMark.BOLD).build())));
                    case "em", "i" -> out.addAll(parseInline(el, withMark(marks, InlineMark.builder().type(InlineMark.ITALIC).build())));
                    case "code" -> out.addAll(parseInline(el, withMark(marks, InlineMark.builder().type(InlineMark.CODE).build())));
                    case "a" -> {
                        String href = el.attr("href");
                        List<InlineMark> next = href.isEmpty() ? marks
                                : withMark(marks, InlineMark.builder().type(InlineMark.LINK).href(href).build());
                        out.addAll(parseInline(el, next));
                    }
                    default -> {
                        if (!SKIP_TAGS.contains(tag)) {
                            out.addAll(parseInline(el, marks));
                        }
                    }
                }
            }
        }
        return out;
    }

    private static List<InlineMark> withMark(List<InlineMark> marks, InlineMark mark) {
        List<InlineMark> next = new ArrayList<>(marks);
        next.add(mark);
        return next;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // ======================== 归一化校验（对齐前端 validateContent） ========================

    /**
     * 运行时结构校验：将任意输入（草稿提交、爬虫产物、库内旧数据）归一化为合法块列表。
     * 未知块丢弃、非法 URL 的 image/video 丢弃、link 标记丢弃（保留文本）、heading 钳制 1-3。
     */
    public static List<Block> normalize(List<Block> blocks) {
        if (blocks == null) return null;
        List<Block> out = new ArrayList<>();
        for (Block block : blocks) {
            Block normalized = normalizeBlock(block);
            if (normalized != null) {
                out.add(normalized);
                if (out.size() >= MAX_BLOCKS) {
                    break;
                }
            }
        }
        return out;
    }

    private static Block normalizeBlock(Block raw) {
        if (raw == null) return null;
        if (raw instanceof ParagraphBlock p) {
            return ParagraphBlock.builder().children(normalizeInlines(p.getChildren())).build();
        }
        if (raw instanceof HeadingBlock h) {
            return HeadingBlock.builder().level(normalizeHeadingLevel(h.getLevel()))
                    .children(normalizeInlines(h.getChildren())).build();
        }
        if (raw instanceof BulletListBlock b) {
            return BulletListBlock.builder().items(normalizeItems(b.getItems())).build();
        }
        if (raw instanceof OrderedListBlock o) {
            return OrderedListBlock.builder().items(normalizeItems(o.getItems())).build();
        }
        if (raw instanceof BlockquoteBlock q) {
            List<Block> children = q.getChildren() == null ? List.of() : normalize(q.getChildren());
            return BlockquoteBlock.builder().children(children == null ? List.of() : children).build();
        }
        if (raw instanceof CodeBlock c) {
            return CodeBlock.builder()
                    .code(c.getCode() == null ? "" : c.getCode())
                    .lang(blankToNull(c.getLang()))
                    .build();
        }
        if (raw instanceof ImageBlock i) {
            String src = sanitizeUrl(i.getSrc());
            if (src == null) return null;
            return ImageBlock.builder().src(src).alt(blankToNull(i.getAlt())).caption(blankToNull(i.getCaption())).build();
        }
        if (raw instanceof VideoBlock v) {
            String src = sanitizeUrl(v.getSrc());
            if (src == null) return null;
            return VideoBlock.builder().src(src).poster(sanitizeUrl(v.getPoster())).build();
        }
        if (raw instanceof DividerBlock) {
            return DividerBlock.builder().build();
        }
        return null;
    }

    private static List<ListItem> normalizeItems(List<ListItem> items) {
        List<ListItem> out = new ArrayList<>();
        if (items == null) return out;
        for (ListItem item : items) {
            List<InlineNode> children = normalizeInlines(item == null ? null : item.getChildren());
            if (!children.isEmpty()) {
                out.add(ListItem.builder().children(children).build());
            }
        }
        return out;
    }

    private static List<InlineNode> normalizeInlines(List<InlineNode> inlines) {
        List<InlineNode> out = new ArrayList<>();
        if (inlines == null) return out;
        for (InlineNode node : inlines) {
            if (node instanceof HardBreakNode) {
                out.add(HardBreakNode.builder().build());
            } else if (node instanceof TextNode t) {
                if (t.getText() == null) continue;
                List<InlineMark> marks = new ArrayList<>();
                if (t.getMarks() != null) {
                    for (InlineMark mark : t.getMarks()) {
                        InlineMark normalized = normalizeMark(mark);
                        if (normalized != null) {
                            marks.add(normalized);
                        }
                    }
                }
                out.add(TextNode.builder().text(t.getText())
                        .marks(marks.isEmpty() ? null : marks).build());
            }
        }
        return out;
    }

    private static InlineMark normalizeMark(InlineMark mark) {
        if (mark == null || mark.getType() == null) return null;
        return switch (mark.getType()) {
            case InlineMark.BOLD, InlineMark.ITALIC, InlineMark.CODE ->
                    InlineMark.builder().type(mark.getType()).build();
            case InlineMark.LINK -> {
                String href = sanitizeUrl(mark.getHref());
                yield href != null ? InlineMark.builder().type(InlineMark.LINK).href(href).build() : null;
            }
            default -> null;
        };
    }

    /** heading 级别钳制到 1-3，非法输入回落为 2（与前端 normalizeHeadingLevel 一致） */
    private static int normalizeHeadingLevel(Integer raw) {
        int n = raw == null ? 2 : raw;
        return Math.min(3, Math.max(1, n));
    }

    // ======================== URL 白名单（对齐前端 sanitizeUrl） ========================

    /**
     * 只放行安全协议：拦截 javascript:/vbscript:/data:/file:（含控制字符混淆）。
     * 通过返回原值（trim 后），不通过返回 null。
     */
    public static String sanitizeUrl(String url) {
        if (url == null) return null;
        String value = url.trim();
        if (value.isEmpty()) return null;
        String cleaned = value.replaceAll("[\\u0000-\\u001F]", "");
        String lower = cleaned.toLowerCase();
        if (lower.startsWith("javascript:") || lower.startsWith("vbscript:")
                || lower.startsWith("data:") || lower.startsWith("file:")) {
            return null;
        }
        return value;
    }

    // ======================== 纯文本提取（摘要用） ========================

    public static String toPlainText(List<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Block block : blocks) {
            String text = blockPlainText(block);
            if (!text.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append(text);
            }
        }
        return sb.toString().trim();
    }

    private static String blockPlainText(Block block) {
        if (block instanceof ParagraphBlock p) {
            return inlinesText(p.getChildren());
        }
        if (block instanceof HeadingBlock h) {
            return inlinesText(h.getChildren());
        }
        if (block instanceof BulletListBlock b) {
            return itemsText(b.getItems());
        }
        if (block instanceof OrderedListBlock o) {
            return itemsText(o.getItems());
        }
        if (block instanceof BlockquoteBlock q) {
            return toPlainText(q.getChildren());
        }
        if (block instanceof CodeBlock c) {
            return c.getCode() == null ? "" : c.getCode();
        }
        return "";
    }

    private static String itemsText(List<ListItem> items) {
        StringBuilder sb = new StringBuilder();
        if (items != null) {
            for (ListItem item : items) {
                String text = inlinesText(item.getChildren());
                if (!text.isBlank()) {
                    if (!sb.isEmpty()) sb.append('\n');
                    sb.append("• ").append(text);
                }
            }
        }
        return sb.toString();
    }

    private static String inlinesText(List<InlineNode> inlines) {
        StringBuilder sb = new StringBuilder();
        if (inlines != null) {
            for (InlineNode node : inlines) {
                if (node instanceof TextNode t && t.getText() != null) {
                    sb.append(t.getText());
                } else if (node instanceof HardBreakNode) {
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    // ======================== 块 → HTML（对齐前端 blocksToHtml，遗留导出用） ========================

    /**
     * 块级列表 → 干净 HTML（无内联样式 class）。用于 content 遗留列（HTML 契约）的派生写入，
     * 接口层不再消费该格式后仅作导出/回退用途。
     */
    public static String blocksToHtml(List<Block> blocks) {
        if (blocks == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Block block : blocks) {
            blockToHtml(sb, block);
        }
        return sb.toString();
    }

    private static void blockToHtml(StringBuilder sb, Block block) {
        if (block instanceof ParagraphBlock p) {
            sb.append("<p>").append(inlineToHtml(p.getChildren())).append("</p>\n");
        } else if (block instanceof HeadingBlock h) {
            int level = normalizeHeadingLevel(h.getLevel());
            sb.append("<h").append(level).append(">").append(inlineToHtml(h.getChildren()))
                    .append("</h").append(level).append(">\n");
        } else if (block instanceof BulletListBlock b) {
            sb.append("<ul>");
            if (b.getItems() != null) {
                b.getItems().forEach(i -> sb.append("<li>").append(inlineToHtml(i.getChildren())).append("</li>"));
            }
            sb.append("</ul>\n");
        } else if (block instanceof OrderedListBlock o) {
            sb.append("<ol>");
            if (o.getItems() != null) {
                o.getItems().forEach(i -> sb.append("<li>").append(inlineToHtml(i.getChildren())).append("</li>"));
            }
            sb.append("</ol>\n");
        } else if (block instanceof BlockquoteBlock q) {
            sb.append("<blockquote>");
            if (q.getChildren() != null) {
                q.getChildren().forEach(c -> blockToHtml(sb, c));
            }
            sb.append("</blockquote>\n");
        } else if (block instanceof CodeBlock c) {
            sb.append("<pre><code");
            if (c.getLang() != null) sb.append(" class=\"language-").append(escapeAttr(c.getLang())).append("\"");
            sb.append(">").append(escapeHtml(c.getCode() == null ? "" : c.getCode())).append("</code></pre>\n");
        } else if (block instanceof ImageBlock i) {
            String img = "<img src=\"" + escapeAttr(i.getSrc()) + "\""
                    + (i.getAlt() != null ? " alt=\"" + escapeAttr(i.getAlt()) + "\"" : "") + ">";
            // 图注用 figure/figcaption 承载，与前端 blocksToHtml 对称，保证往返不丢
            if (i.getCaption() != null) {
                sb.append("<figure>").append(img).append("<figcaption>")
                        .append(escapeHtml(i.getCaption())).append("</figcaption></figure>\n");
            } else {
                sb.append(img).append('\n');
            }
        } else if (block instanceof VideoBlock v) {
            sb.append("<video controls src=\"").append(escapeAttr(v.getSrc())).append("\"");
            if (v.getPoster() != null) sb.append(" poster=\"").append(escapeAttr(v.getPoster())).append("\"");
            sb.append("></video>\n");
        } else if (block instanceof DividerBlock) {
            sb.append("<hr>\n");
        }
    }

    private static String inlineToHtml(List<InlineNode> inlines) {
        StringBuilder sb = new StringBuilder();
        if (inlines == null) return "";
        for (InlineNode node : inlines) {
            if (node instanceof HardBreakNode) {
                sb.append("<br>");
            } else if (node instanceof TextNode t) {
                String text = escapeHtml(t.getText() == null ? "" : t.getText());
                boolean code = false, italic = false, bold = false;
                String link = null;
                if (t.getMarks() != null) {
                    for (InlineMark mark : t.getMarks()) {
                        if (mark == null || mark.getType() == null) continue;
                        switch (mark.getType()) {
                            case InlineMark.CODE -> code = true;
                            case InlineMark.ITALIC -> italic = true;
                            case InlineMark.BOLD -> bold = true;
                            case InlineMark.LINK -> link = mark.getHref();
                        }
                    }
                }
                if (code) text = "<code>" + text + "</code>";
                if (link != null) text = "<a href=\"" + escapeAttr(link) + "\">" + text + "</a>";
                if (italic) text = "<em>" + text + "</em>";
                if (bold) text = "<strong>" + text + "</strong>";
                sb.append(text);
            }
        }
        return sb.toString();
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String escapeAttr(String s) {
        return escapeHtml(s).replace("'", "&#39;");
    }

    // ======================== Jackson 反序列化器 ========================

    /**
     * 自定义反序列化器，支持前端传入裸 JSON 数组时正确解析 Block 多态
     */
    public static class BlockListDeserializer extends JsonDeserializer<List<Block>> {
        @Override
        public List<Block> deserialize(com.fasterxml.jackson.core.JsonParser p, DeserializationContext ctxt)
                throws IOException {
            JsonNode node = p.getCodec().readTree(p);
            if (node == null || node.isNull()) return null;
            return MAPPER.convertValue(node, new TypeReference<List<Block>>() {});
        }
    }
}
