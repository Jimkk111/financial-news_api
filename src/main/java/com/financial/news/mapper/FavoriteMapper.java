package com.financial.news.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.financial.news.dto.response.FavoriteVO;
import com.financial.news.entity.Favorite;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FavoriteMapper extends BaseMapper<Favorite> {

    /**
     * 分页查询用户收藏的新闻（联表查询新闻信息，按收藏时间倒序）
     *
     * @param page   分页参数
     * @param userId 用户ID
     * @return 收藏新闻列表（含新闻详情字段）
     */
    @Select("SELECT n.id AS news_id, n.title, n.summary, n.source, n.publish_time, " +
            "n.views, n.has_image, n.image_url, n.category_id, f.created_at AS favorited_at " +
            "FROM favorites f " +
            "JOIN news n ON f.news_id = n.id " +
            "WHERE f.user_id = #{userId} AND n.deleted_at IS NULL " +
            "ORDER BY f.created_at DESC")
    IPage<FavoriteVO> selectFavoriteNewsPage(Page<FavoriteVO> page, @Param("userId") Integer userId);
}
