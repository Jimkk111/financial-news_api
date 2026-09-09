package com.financial.news.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.news.entity.AiMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AiMessageMapper extends BaseMapper<AiMessage> {

    /**
     * 批量查询每个会话的最后一条消息（窗口函数，消除 listSessions 的 N+1 查询）
     */
    @Select("<script>" +
            "SELECT t.* FROM (" +
            "  SELECT m.*, ROW_NUMBER() OVER (PARTITION BY m.session_id ORDER BY m.created_at DESC) AS rn " +
            "  FROM ai_messages m WHERE m.session_id IN " +
            "  <foreach collection='sessionIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            ") t WHERE t.rn = 1" +
            "</script>")
    List<AiMessage> selectLastMessages(@Param("sessionIds") List<Integer> sessionIds);
}
