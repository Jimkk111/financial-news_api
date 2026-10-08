package com.financial.news.service.quote.provider.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 行情快照领域模型（上游无关；provider 输出、服务层缓存与组装 VO 的统一载体）
 * <p>成交量已归一化为"股"（东财 A 股字段为手，provider 内 ×100）</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuoteSnapshot {

    private String symbol;

    /** 市场代码：CN/HK/US */
    private String market;

    /** 证券名称（上游返回名） */
    private String name;

    /** 最新价；上游无数据（如停牌、未开盘）为 null */
    private Double latestPrice;

    private Double changeAmount;

    private Double changePercent;

    private Double open;

    private Double prevClose;

    private Double high;

    private Double low;

    /** 成交量（股） */
    private Long volume;

    /** 成交额（原币种元） */
    private Double turnover;

    /** 上游数据时间（epoch 秒，北京时间语义）；缺失为 null，由服务层回退为拉取时间 */
    private Long dataTimeEpochSec;

    /** 上游明确给出了当前价（false 通常意味着停牌/无成交，由状态机判定最终状态） */
    private boolean priceAvailable;
}
