package com.financial.news.model.content;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 行内节点接口 — 段落/标题/列表项内部的富文本节点
 * <p>仅两种：text（可带 marks）与 hardBreak（&lt;br&gt;）。
 * 与前端 types/content.ts 的 Inline 契约一致。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextNode.class, name = "text"),
        @JsonSubTypes.Type(value = HardBreakNode.class, name = "hardBreak")
})
public interface InlineNode {

}
