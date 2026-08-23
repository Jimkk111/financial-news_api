package com.financial.news.model.content;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 块级内容接口 — 定义正文 JSON 中的 9 种块类型
 * <p>所有块类型通过 {@code type} 字段做多态序列化</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ParagraphBlock.class, name = "paragraph"),
        @JsonSubTypes.Type(value = HeadingBlock.class, name = "heading"),
        @JsonSubTypes.Type(value = ImageBlock.class, name = "image"),
        @JsonSubTypes.Type(value = QuoteBlock.class, name = "quote"),
        @JsonSubTypes.Type(value = ListBlock.class, name = "list"),
        @JsonSubTypes.Type(value = TableBlock.class, name = "table"),
        @JsonSubTypes.Type(value = CodeBlock.class, name = "code"),
        @JsonSubTypes.Type(value = VideoBlock.class, name = "video"),
        @JsonSubTypes.Type(value = DividerBlock.class, name = "divider")
})
public interface Block {

    /** 块类型标识 */
    String getType();
}
