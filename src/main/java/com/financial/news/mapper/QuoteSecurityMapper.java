package com.financial.news.mapper;

import com.financial.news.entity.QuoteSecurity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 行情标的主表 Mapper
 *
 * @author financial-news
 * @since 1.0.0
 */
@Mapper
public interface QuoteSecurityMapper {

    QuoteSecurity selectBySymbolAndType(@Param("symbol") String symbol, @Param("secType") Integer secType);

    List<QuoteSecurity> selectBySymbolsAndType(@Param("symbols") List<String> symbols, @Param("secType") Integer secType);

    List<QuoteSecurity> selectActiveStocksByMarket(@Param("market") String market);

    /** 新增或更新（uk_symbol_type 冲突时更新）；名称变更时调用方需重算拼音 */
    int upsert(QuoteSecurity security);

    /** 搜索：代码前缀（symbol 前缀，如 600519、600519.SH、AAPL） */
    List<QuoteSecurity> searchBySymbolPrefix(@Param("prefix") String prefix, @Param("limit") int limit);

    /** 搜索：名称包含 */
    List<QuoteSecurity> searchByNameLike(@Param("keyword") String keyword, @Param("limit") int limit);

    /** 搜索：拼音首字母前缀 */
    List<QuoteSecurity> searchByPinyinPrefix(@Param("prefix") String prefix, @Param("limit") int limit);

    /** 退市判定：连续缺失计数 +1，达到阈值置为退市 */
    int incrementMissing(@Param("symbol") String symbol, @Param("lastActiveDate") LocalDate lastActiveDate, @Param("threshold") int threshold);

    /** 标的重新出现时恢复状态 */
    int reactivate(@Param("symbol") String symbol, @Param("secType") Integer secType, @Param("lastActiveDate") LocalDate lastActiveDate);

    long countAll();
}
