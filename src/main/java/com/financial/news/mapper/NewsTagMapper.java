package com.financial.news.mapper;

import com.financial.news.entity.NewsTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 新闻-标签关联 Mapper
 */
@Mapper
public interface NewsTagMapper {

    int insert(NewsTag newsTag);

    List<NewsTag> selectByNewsId(@Param("newsId") Integer newsId);
}
