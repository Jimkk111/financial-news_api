package com.financial.news.model.content;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 引用块
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuoteBlock implements Block {

    @Override
    public String getType() { return "quote"; }

    /** 引用文本 */
    private String text;

    /** 引用来源（可选） */
    private String source;
}
