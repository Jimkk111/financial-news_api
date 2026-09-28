package com.financial.news.model.content;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 块级内容接口 — 正文 JSON 中的 9 种块类型（与前端 types/content.ts 的 Block 契约一致）
 * <p>所有块类型通过 {@code type} 字段做多态序列化；schema 中不出现 style/class 等样式字段。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ParagraphBlock.class, name = "paragraph"),
        @JsonSubTypes.Type(value = HeadingBlock.class, name = "heading"),
        @JsonSubTypes.Type(value = BulletListBlock.class, name = "bulletList"),
        @JsonSubTypes.Type(value = OrderedListBlock.class, name = "orderedList"),
        @JsonSubTypes.Type(value = BlockquoteBlock.class, name = "blockquote"),
        @JsonSubTypes.Type(value = CodeBlock.class, name = "codeBlock"),
        @JsonSubTypes.Type(value = ImageBlock.class, name = "image"),
        @JsonSubTypes.Type(value = VideoBlock.class, name = "video"),
        @JsonSubTypes.Type(value = DividerBlock.class, name = "divider")
})
public interface Block {

}
