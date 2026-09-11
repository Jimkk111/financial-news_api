package com.financial.news.mapper;

import com.financial.news.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户 Mapper
 * <p>软删除（deleted_at IS NULL）过滤条件显式写在 SQL 中</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Mapper
public interface UserMapper {

    int insert(User user);

    User selectById(@Param("id") Integer id);

    /**
     * 按用户名或邮箱查用户（登录用）
     */
    User selectByUsernameOrEmail(@Param("account") String account);

    User selectByUsername(@Param("username") String username);

    long countByUsername(@Param("username") String username);

    long countByEmail(@Param("email") String email);

    int updateById(User user);
}
