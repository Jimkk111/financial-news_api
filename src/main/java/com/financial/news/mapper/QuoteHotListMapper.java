package com.financial.news.mapper;

import com.financial.news.entity.QuoteHotList;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 热门标的名单 Mapper
 *
 * @author financial-news
 * @since 1.0.0
 */
@Mapper
public interface QuoteHotListMapper {

    List<QuoteHotList> selectEnabledByMarket(@Param("market") String market);

    long countAll();
}
