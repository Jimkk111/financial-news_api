package com.financial.news.model.content;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 代码块
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeBlock implements Block {

    @Override
    public String getType() { return "code"; }

    /** 编程语言标识 */
    private String language;

    /** 代码文本 */
    private String text;
}
