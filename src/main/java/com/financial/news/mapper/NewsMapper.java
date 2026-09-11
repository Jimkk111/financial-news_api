package com.financial.news.mapper;

import com.financial.news.entity.News;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 新闻 Mapper
 * <p>软删除（deleted_at IS NULL）过滤条件显式写在 SQL 中；
 * 列表类查询按原契约排除 content_json 列</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Mapper
public interface NewsMapper {

    int insert(News news);

    News selectById(@Param("id") Integer id);

    /**
     * 新闻列表分页查询（排除 content_json 列），popular 时按浏览量倒序，否则按发布时间倒序
     */
    List<News> selectListPage(@Param("categoryId") Integer categoryId,
                              @Param("popular") boolean popular,
                              @Param("offset") int offset,
                              @Param("limit") int limit);

    long countListPage(@Param("categoryId") Integer categoryId);

    /**
     * 标题/摘要模糊搜索分页查询（排除 content_json 列）
     */
    List<News> searchPage(@Param("keyword") String keyword,
                          @Param("offset") int offset,
                          @Param("limit") int limit);

    long countSearch(@Param("keyword") String keyword);

    /**
     * 用户发布的新闻分页查询（含 content_json 全列）
     */
    List<News> selectByUserPage(@Param("userId") Integer userId,
                                @Param("offset") int offset,
                                @Param("limit") int limit);

    long countByUser(@Param("userId") Integer userId);

    /**
     * 最近 N 篇文章的指纹记录（仅取 id 与 content_fingerprint，用于近似去重比对）
     */
    List<News> selectRecentFingerprints(@Param("limit") int limit);

    News selectFirstByTitle(@Param("title") String title);

    long countByUrl(@Param("url") String url);

    long countByTitle(@Param("title") String title);

    long countById(@Param("id") Integer id);

    /**
     * 原子递增浏览量，避免读-改-写并发丢更新
     */
    @Update("UPDATE news SET views = views + 1 WHERE id = #{id}")
    int incrementViews(@Param("id") Integer id);
}
