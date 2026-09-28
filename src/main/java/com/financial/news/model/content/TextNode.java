package com.financial.news.model.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 文本行内节点：纯文本 + 可选格式标记（加粗/斜体/行内代码/链接）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TextNode implements InlineNode {

    private String text;

    /** 格式标记；无标记时省略 */
    private List<InlineMark> marks;
}
