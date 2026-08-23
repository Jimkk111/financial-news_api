package com.financial.news.model.content;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 列表块
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ListBlock implements Block {

    @Override
    public String getType() { return "list"; }

    /** 列表类型：ordered / unordered */
    private String style;

    /** 列表项文本列表 */
    private List<String> items;
}
