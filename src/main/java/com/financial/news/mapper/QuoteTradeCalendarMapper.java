package com.financial.news.mapper;

import com.financial.news.entity.QuoteTradeCalendar;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 行情交易日历 Mapper
 *
 * @author financial-news
 * @since 1.0.0
 */
@Mapper
public interface QuoteTradeCalendarMapper {

    QuoteTradeCalendar selectByMarketAndDate(@Param("market") String market, @Param("tradeDate") LocalDate tradeDate);

    /** 查询日期区间（含首尾），用于下一次开盘扫描与同步比对 */
    List<QuoteTradeCalendar> selectRange(@Param("market") String market,
                                         @Param("fromDate") LocalDate fromDate,
                                         @Param("toDate") LocalDate toDate);

    int insert(QuoteTradeCalendar row);

    /** 仅覆盖非 MANUAL 来源行，由调用方先查出旧值判断 */
    int updateAuto(QuoteTradeCalendar row);

    long countAll();
}
