package com.financial.news.model.content;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 表格块
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableBlock implements Block {

    @Override
    public String getType() { return "table"; }

    /** 表头行 */
    private List<String> header;

    /** 数据行列表，每行为一个单元格列表 */
    private List<List<String>> rows;
}
