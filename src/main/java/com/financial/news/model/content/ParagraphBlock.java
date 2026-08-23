package com.financial.news.model.content;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 段落块
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParagraphBlock implements Block {

    @Override
    public String getType() { return "paragraph"; }

    /** 富文本 HTML（允许 <b><i><a> 等内联标签） */
    private String html;
}
