package com.financial.news.model.content;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 分割线块
 */
@Data
@Builder
@AllArgsConstructor
public class DividerBlock implements Block {

    @Override
    public String getType() { return "divider"; }
}
