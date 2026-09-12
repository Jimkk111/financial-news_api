package com.financial.news.mapper;

import com.financial.news.entity.AiSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiSessionMapper {

    int insert(AiSession aiSession);

    AiSession selectBySessionId(@Param("sessionId") String sessionId);

    /**
     * 用户会话列表，按更新时间倒序
     */
    List<AiSession> selectByUserId(@Param("userId") Integer userId);

    int updateById(AiSession aiSession);

    int deleteById(@Param("id") Integer id);
}
