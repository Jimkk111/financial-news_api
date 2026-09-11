package com.financial.news.mapper;

import com.financial.news.entity.Tag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 标签 Mapper
 */
@Mapper
public interface TagMapper {

    int insert(Tag tag);

    List<Tag> selectListAll();

    Tag selectFirstByName(@Param("name") String name);

    List<Tag> selectByIds(@Param("ids") List<Integer> ids);
}
