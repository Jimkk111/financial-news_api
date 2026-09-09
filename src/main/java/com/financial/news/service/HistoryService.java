package com.financial.news.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.financial.news.dto.response.HistoryVO;
import com.financial.news.entity.History;
import com.financial.news.mapper.HistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 浏览历史服务
 * <p>负责用户浏览历史的新增、查询与清空</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryService extends ServiceImpl<HistoryMapper, History> {

    private final HistoryMapper historyMapper;

    /**
     * 获取浏览历史（分页，联表查询新闻信息，按浏览时间倒序）
     */
    public IPage<HistoryVO> listHistory(Integer userId, int page, int pageSize) {
        Page<HistoryVO> p = new Page<>(page, Math.min(pageSize, 50));
        return historyMapper.selectHistoryNewsPage(p, userId);
    }

    /**
     * 添加/更新浏览记录（数据库幂等 upsert，并发下不产生重复记录）
     */
    public void addHistory(Integer userId, Integer newsId) {
        historyMapper.upsertHistory(userId, newsId);
    }

    /**
     * 清空浏览历史
     */
    public void clearHistory(Integer userId) {
        historyMapper.delete(new LambdaQueryWrapper<History>().eq(History::getUserId, userId));
    }
}
