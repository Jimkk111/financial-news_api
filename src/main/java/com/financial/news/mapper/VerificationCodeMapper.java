package com.financial.news.mapper;

import com.financial.news.entity.VerificationCode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface VerificationCodeMapper {

    int insert(VerificationCode verificationCode);

    /**
     * 查询邮箱下匹配且未过期的验证码（取最新一条）
     */
    VerificationCode selectValid(@Param("email") String email,
                                 @Param("code") String code,
                                 @Param("now") LocalDateTime now);

    int deleteById(@Param("id") Integer id);

    int deleteByEmail(@Param("email") String email);
}
