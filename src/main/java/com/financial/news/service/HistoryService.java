package com.financial.news.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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
     * 获取浏览历史（分页，按浏览时间倒序）
     */
    public Page<History> listHistory(Integer userId, int page, int pageSize) {
        Page<History> p = new Page<>(page, Math.min(pageSize, 50));
        return historyMapper.selectPage(p,
                new LambdaQueryWrapper<History>().eq(History::getUserId, userId).orderByDesc(History::getViewedAt));
    }

    /**
     * 添加/更新浏览记录
     */
    @Transactional
    public void addHistory(Integer userId, Integer newsId) {
        History existing = historyMapper.selectOne(new LambdaQueryWrapper<History>()
                .eq(History::getUserId, userId).eq(History::getNewsId, newsId));
        if (existing != null) {
            existing.setViewedAt(LocalDateTime.now());
            historyMapper.updateById(existing);
        } else {
            historyMapper.insert(History.builder().userId(userId).newsId(newsId).build());
        }
    }

    /**
     * 清空浏览历史
     */
    public void clearHistory(Integer userId) {
        historyMapper.delete(new LambdaQueryWrapper<History>().eq(History::getUserId, userId));
    }
}
