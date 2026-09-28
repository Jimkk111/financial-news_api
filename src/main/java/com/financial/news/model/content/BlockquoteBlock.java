package com.financial.news.model.content;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 引用块：唯一可递归嵌套块级内容的块
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlockquoteBlock implements Block {

    private List<Block> children;
}
