package com.financial.news.mapper;

import com.financial.news.entity.Category;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 分类 Mapper
 */
@Mapper
public interface CategoryMapper {

    int insert(Category category);

    Category selectById(@Param("id") Integer id);

    List<Category> selectListAll();

    Category selectFirstByName(@Param("name") String name);
}
