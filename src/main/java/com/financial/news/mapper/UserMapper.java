package com.financial.news.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.news.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper
 *
 * @author financial-news
 * @since 1.0.0
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
