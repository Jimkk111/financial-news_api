package com.financial.news.model.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 行内格式标记
 * <p>type 取值：bold / italic / code / link；link 携带 href。
 * 序列化为 {"type":"bold"} 或 {"type":"link","href":"..."}，与前端 InlineMark 一致。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InlineMark {

    public static final String BOLD = "bold";
    public static final String ITALIC = "italic";
    public static final String CODE = "code";
    public static final String LINK = "link";

    private String type;

    /** 仅 link 标记存在 */
    private String href;
}
