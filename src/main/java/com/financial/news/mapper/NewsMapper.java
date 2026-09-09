package com.financial.news.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.news.entity.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NewsMapper extends BaseMapper<News> {

    /**
     * 原子递增浏览量，避免读-改-写并发丢更新
     */
    @Update("UPDATE news SET views = views + 1 WHERE id = #{id}")
    int incrementViews(@Param("id") Integer id);
}
