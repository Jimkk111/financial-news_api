package com.financial.news.mapper;

import com.financial.news.dto.response.HistoryVO;
import com.financial.news.entity.History;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface HistoryMapper {

    /**
     * 幂等写入浏览记录：并发下由 uk_user_news 唯一索引兜底，重复浏览仅刷新时间
     */
    @Insert("INSERT INTO history (user_id, news_id, viewed_at) VALUES (#{userId}, #{newsId}, NOW()) " +
            "ON DUPLICATE KEY UPDATE viewed_at = NOW()")
    int upsertHistory(@Param("userId") Integer userId, @Param("newsId") Integer newsId);

    /**
     * 分页查询用户浏览过的新闻（联表查询新闻信息，按浏览时间倒序）
     */
    List<HistoryVO> selectHistoryNewsPage(@Param("userId") Integer userId,
                                          @Param("offset") int offset,
                                          @Param("limit") int limit);

    long countByUser(@Param("userId") Integer userId);

    int deleteByUserId(@Param("userId") Integer userId);
}
