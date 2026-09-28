package com.financial.news;

import com.financial.news.model.content.Block;
import com.financial.news.model.content.HardBreakNode;
import com.financial.news.model.content.InlineMark;
import com.financial.news.model.content.InlineNode;
import com.financial.news.model.content.ListItem;
import com.financial.news.model.content.ParagraphBlock;
import com.financial.news.model.content.TextNode;
import com.financial.news.utils.ContentCodec;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * 块级 JSON 序列化形状冒烟测试：验证 type 判别字段存在与往返一致
 */
class ContentCodecShapeTest {

    @Test
    void serializationShapeAndRoundTrip() {
        List<InlineNode> children = List.of(
                TextNode.builder().text("普通").build(),
                TextNode.builder().text("加粗链接").marks(List.of(
                        InlineMark.builder().type(InlineMark.BOLD).build(),
                        InlineMark.builder().type(InlineMark.LINK).href("https://example.com").build())).build(),
                HardBreakNode.builder().build(),
                TextNode.builder().text("换行后").build());
        List<Block> blocks = List.of(
                ParagraphBlock.builder().children(children).build(),
                com.financial.news.model.content.BulletListBlock.builder()
                        .items(List.of(ListItem.builder().children(List.of(TextNode.builder().text("项").build())).build())).build());

        String json = ContentCodec.toJson(blocks);
        System.out.println("[SHAPE] " + json);

        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"type\":\"paragraph\""), "paragraph 块缺少 type 判别字段");
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"type\":\"bulletList\""), "bulletList 块缺少 type 判别字段");

        List<Block> parsed = ContentCodec.fromJson(json);
        System.out.println("[ROUNDTRIP] " + (parsed == null ? "null" : parsed.size() + " blocks"));
        org.junit.jupiter.api.Assertions.assertNotNull(parsed, "反序列化失败");
        org.junit.jupiter.api.Assertions.assertEquals(2, parsed.size(), "往返块数不一致");
    }
}
