package com.financial.news.model.content;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 有序列表块（列表扁平，不嵌套）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderedListBlock implements Block {

    private List<ListItem> items;
}
