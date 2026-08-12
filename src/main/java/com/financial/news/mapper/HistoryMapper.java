package com.financial.news.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.financial.news.dto.response.HistoryVO;
import com.financial.news.entity.History;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HistoryMapper extends BaseMapper<History> {

    /**
     * 分页查询用户浏览过的新闻（联表查询新闻信息，按浏览时间倒序）
     *
     * @param page   分页参数
     * @param userId 用户ID
     * @return 浏览历史列表（含新闻详情字段）
     */
    @Select("SELECT n.id AS news_id, n.title, n.summary, n.source, n.publish_time, " +
            "n.views, n.has_image, n.image_url, n.category_id, h.viewed_at " +
            "FROM history h " +
            "JOIN news n ON h.news_id = n.id " +
            "WHERE h.user_id = #{userId} AND n.deleted_at IS NULL " +
            "ORDER BY h.viewed_at DESC")
    IPage<HistoryVO> selectHistoryNewsPage(Page<HistoryVO> page, @Param("userId") Integer userId);
}
