package com.financial.news.mapper;

import com.financial.news.entity.Draft;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 草稿 Mapper
 */
@Mapper
public interface DraftMapper {

    int insert(Draft draft);

    Draft selectById(@Param("id") String id);

    /**
     * 用户草稿列表（status='draft'，排除 content_json 列，按更新时间倒序）
     */
    List<Draft> selectListByUser(@Param("userId") Integer userId);

    int updateById(Draft draft);

    int deleteById(@Param("id") String id);
}
