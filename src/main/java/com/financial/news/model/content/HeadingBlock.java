package com.financial.news.model.content;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 标题块（h1-h6）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HeadingBlock implements Block {

    @Override
    public String getType() { return "heading"; }

    /** 标题等级 1-6 */
    private int level;

    /** 标题文本 */
    private String text;
}
