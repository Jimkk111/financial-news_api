package com.financial.news.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.news.entity.AiMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiMessageMapper extends BaseMapper<AiMessage> {

    /**
     * 批量查询每个会话的最后一条消息（窗口函数，消除 listSessions 的 N+1 查询）
     */
    List<AiMessage> selectLastMessages(@Param("sessionIds") List<Integer> sessionIds);
}
