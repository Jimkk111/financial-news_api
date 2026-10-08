package com.financial.news.dto.response.quote;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 均线数组（与 bars 按下标对齐，窗口不足为 null——E5：缺失均线前端不绘制）
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KlineMaVO {

    private List<Double> ma5;

    private List<Double> ma10;

    private List<Double> ma20;

    private List<Double> ma60;
}
