package com.financial.news.model.content;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 列表项：行内节点序列（列表扁平，不嵌套子列表）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ListItem {

    private List<InlineNode> children;
}
