package com.financial.news.mapper;

import org.apache.ibatis.annotations.*;

/** 数据源独立标识，不覆盖东方财富 secid。 */
@Mapper
public interface QuoteProviderSymbolMapper {
    record Mapping(String symbol, String region, String code, String exchange) {}

    @Select("SELECT symbol, region, code, `exchange` FROM quote_provider_symbol "
            + "WHERE provider = 'itick' AND symbol = #{symbol} AND sec_type = 1")
    Mapping find(@Param("symbol") String symbol);

    @Insert("INSERT INTO quote_provider_symbol(provider, symbol, sec_type, region, code, `exchange`) "
            + "VALUES ('itick', #{symbol}, 1, #{region}, #{code}, #{exchange}) "
            + "ON DUPLICATE KEY UPDATE region=VALUES(region), code=VALUES(code), `exchange`=VALUES(`exchange`)")
    void upsert(Mapping mapping);
}
