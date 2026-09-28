package com.financial.news.model.content;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 换行行内节点（&lt;br&gt;）
 */
@Data
@Builder
@NoArgsConstructor
public class HardBreakNode implements InlineNode {
}
