package com.financial.news.service;

import com.financial.news.common.Result;
import com.financial.news.dto.response.HistoryVO;
import com.financial.news.mapper.HistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

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
public class HistoryService {

    private final HistoryMapper historyMapper;

    private static final int MAX_PAGE_SIZE = 50;

    /**
     * 获取浏览历史（分页，联表查询新闻信息，按浏览时间倒序）
     */
    public Result.PageResult<HistoryVO> listHistory(Integer userId, int page, int pageSize) {
        page = Math.max(page, 1);
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);
        long total = historyMapper.countByUser(userId);
        List<HistoryVO> records = total == 0 ? List.of()
                : historyMapper.selectHistoryNewsPage(userId, (page - 1) * pageSize, pageSize);
        return new Result.PageResult<>(records, total, page, pageSize);
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
        historyMapper.deleteByUserId(userId);
    }
}
