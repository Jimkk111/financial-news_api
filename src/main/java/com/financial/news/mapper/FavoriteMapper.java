package com.financial.news.mapper;

import com.financial.news.dto.response.FavoriteVO;
import com.financial.news.entity.Favorite;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface FavoriteMapper {

    int insert(Favorite favorite);

    Favorite selectByUserAndNews(@Param("userId") Integer userId, @Param("newsId") Integer newsId);

    int deleteById(@Param("id") Integer id);

    long countByUserAndNews(@Param("userId") Integer userId, @Param("newsId") Integer newsId);

    /**
     * 分页查询用户收藏的新闻（联表查询新闻信息，按收藏时间倒序）
     */
    List<FavoriteVO> selectFavoriteNewsPage(@Param("userId") Integer userId,
                                            @Param("offset") int offset,
                                            @Param("limit") int limit);

    long countByUser(@Param("userId") Integer userId);
}
