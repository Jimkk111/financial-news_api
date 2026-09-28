package com.financial.news.model.content;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 标题块：级别 1-3（schema 上限），内容为行内节点序列
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HeadingBlock implements Block {

    private Integer level;

    private List<InlineNode> children;
}
