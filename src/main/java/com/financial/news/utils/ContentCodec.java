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
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 块级内容编解码器
 * <p>负责 List&lt;Block&gt; 与 JSON 字符串之间的序列化/反序列化，
 * 以及旧 HTML 正文向块级 JSON 的迁移转换。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
public final class ContentCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ContentCodec() {}

    // ======================== 序列化 ========================

    /**
     * 将块级列表序列化为 JSON 字符串（MyBatis TypeHandler 入库时调用）
     */
    public static String toJson(List<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(blocks);
        } catch (JsonProcessingException e) {
            log.error("Block 序列化失败", e);
            return null;
        }
    }

    /**
     * 将 JSON 字符串反序列化为块级列表（MyBatis TypeHandler 出库时调用）
     */
    public static List<Block> fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, new TypeReference<List<Block>>() {});
        } catch (JsonProcessingException e) {
            log.error("Block 反序列化失败: {}", e.getMessage());
            return null;
        }
    }

    // ======================== HTML 迁移 ========================

    /**
     * 将旧 HTML 正文转换为块级 JSON 列表
     */
    public static List<Block> fromHtml(String html) {
        if (html == null || html.isBlank()) return List.of();
        Document doc = Jsoup.parseBodyFragment(html);
        Element body = doc.body();
        List<Block> blocks = new ArrayList<>();

        for (Element el : body.children()) {
            blocks.addAll(parseElement(el));
        }
        return blocks;
    }

    // ======================== 块级内容标准化 ========================

    /**
     * 标准化块级列表：过滤空块、修正类型错误
     */
    public static List<Block> normalize(List<Block> blocks) {
        if (blocks == null) return null;
        return blocks.stream()
                .filter(b -> b != null && isValidBlock(b))
                .toList();
    }

    // ======================== 纯文本提取 ========================

    /**
     * 将块级列表提取为纯文本（用于生成摘要）
     */
    public static String toPlainText(List<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Block block : blocks) {
            switch (block) {
                case ParagraphBlock p -> sb.append(stripHtml(p.getHtml())).append("\n\n");
                case HeadingBlock h -> sb.append(h.getText()).append("\n\n");
                case QuoteBlock q -> sb.append(q.getText()).append("\n\n");
                case ListBlock l -> l.getItems().forEach(item -> sb.append("• ").append(item).append("\n"));
                default -> { /* 忽略图片、表格等非文本块 */ }
            }
        }
        return sb.toString().trim();
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

    // ======================== 私有方法 ========================

    private static List<Block> parseElement(Element el) {
        List<Block> blocks = new ArrayList<>();
        String tag = el.tagName().toLowerCase();

        switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                int level = Integer.parseInt(tag.substring(1));
                String text = el.text();
                if (!text.isBlank()) {
                    blocks.add(HeadingBlock.builder().level(level).text(text).build());
                }
            }
            case "p" -> {
                String html = el.html().strip();
                if (!html.isBlank()) {
                    blocks.add(ParagraphBlock.builder().html(html).build());
                }
            }
            case "img" -> {
                String src = el.attr("src");
                if (!src.isBlank()) {
                    blocks.add(ImageBlock.builder().url(src).caption(el.attr("alt")).build());
                }
            }
            case "blockquote" -> {
                String text = el.text();
                if (!text.isBlank()) {
                    blocks.add(QuoteBlock.builder().text(text).build());
                }
            }
            case "ul", "ol" -> {
                List<String> items = new ArrayList<>();
                for (Element li : el.select("li")) {
                    String text = li.text();
                    if (!text.isBlank()) items.add(text);
                }
                if (!items.isEmpty()) {
                    String style = "ol".equals(tag) ? "ordered" : "unordered";
                    blocks.add(ListBlock.builder().style(style).items(items).build());
                }
            }
            case "table" -> {
                List<String> header = new ArrayList<>();
                List<List<String>> rows = new ArrayList<>();
                Elements ths = el.select("thead th");
                if (!ths.isEmpty()) {
                    ths.forEach(th -> header.add(th.text()));
                } else {
                    el.select("tr:first-child th, tr:first-child td").forEach(th -> header.add(th.text()));
                }
                Elements dataRows = el.select("tbody tr, tr");
                int startIdx = header.isEmpty() ? 0 : 1;
                for (int i = startIdx; i < dataRows.size(); i++) {
                    List<String> row = new ArrayList<>();
                    dataRows.get(i).select("td").forEach(td -> row.add(td.text()));
                    if (!row.isEmpty()) rows.add(row);
                }
                if (!header.isEmpty() || !rows.isEmpty()) {
                    blocks.add(TableBlock.builder().header(header).rows(rows).build());
                }
            }
            case "pre" -> {
                String text = el.text();
                if (!text.isBlank()) {
                    blocks.add(CodeBlock.builder().text(text).build());
                }
            }
            case "hr" -> blocks.add(DividerBlock.builder().build());
            case "figure" -> {
                Element img = el.selectFirst("img");
                if (img != null) {
                    String src = img.attr("src");
                    String caption = "";
                    Element figcaption = el.selectFirst("figcaption");
                    if (figcaption != null) caption = figcaption.text();
                    if (!src.isBlank()) {
                        blocks.add(ImageBlock.builder().url(src).caption(caption).build());
                    }
                }
            }
            default -> {
                // 递归处理嵌套的子元素
                for (Element child : el.children()) {
                    blocks.addAll(parseElement(child));
                }
                // 如果没有子元素但有文本内容
                if (el.children().isEmpty()) {
                    String text = el.text();
                    if (!text.isBlank()) {
                        blocks.add(ParagraphBlock.builder().html(text).build());
                    }
                }
            }
        }
        return blocks;
    }

    private static String stripHtml(String html) {
        if (html == null) return "";
        return Jsoup.parse(html).text();
    }

    private static boolean isValidBlock(Block block) {
        return switch (block) {
            case ParagraphBlock p -> p.getHtml() != null && !p.getHtml().isBlank();
            case HeadingBlock h -> h.getText() != null && !h.getText().isBlank();
            case ImageBlock i -> i.getUrl() != null && !i.getUrl().isBlank();
            case QuoteBlock q -> q.getText() != null && !q.getText().isBlank();
            case ListBlock l -> l.getItems() != null && !l.getItems().isEmpty();
            case TableBlock t -> (t.getHeader() != null && !t.getHeader().isEmpty())
                    || (t.getRows() != null && !t.getRows().isEmpty());
            case CodeBlock c -> c.getText() != null && !c.getText().isBlank();
            case VideoBlock v -> v.getUrl() != null && !v.getUrl().isBlank();
            case DividerBlock d -> true;
            default -> false;
        };
    }
}
