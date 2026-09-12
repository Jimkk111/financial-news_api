package com.financial.news.service;

import com.financial.news.common.BusinessException;
import com.financial.news.common.ErrorCode;
import com.financial.news.common.Result;
import com.financial.news.dto.response.FavoriteVO;
import com.financial.news.entity.Favorite;
import com.financial.news.mapper.FavoriteMapper;
import com.financial.news.mapper.NewsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 收藏服务
 * <p>负责用户收藏的新增、删除、查询与检查</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final FavoriteMapper favoriteMapper;
    private final NewsMapper newsMapper;

    private static final int MAX_PAGE_SIZE = 50;

    /**
     * 获取收藏列表（分页，联表查询新闻信息）
     */
    public Result.PageResult<FavoriteVO> listFavorites(Integer userId, int page, int pageSize) {
        page = Math.max(page, 1);
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);
        long total = favoriteMapper.countByUser(userId);
        List<FavoriteVO> records = total == 0 ? List.of()
                : favoriteMapper.selectFavoriteNewsPage(userId, (page - 1) * pageSize, pageSize);
        return new Result.PageResult<>(records, total, page, pageSize);
    }

    /**
     * 添加收藏（并发下由 uk_user_news 唯一索引兜底，冲突转为业务错误）
     */
    @Transactional
    public void addFavorite(Integer userId, Integer newsId) {
        if (newsMapper.countById(newsId) == 0) {
            throw new BusinessException(ErrorCode.NEWS_NOT_FOUND);
        }
        try {
            favoriteMapper.insert(Favorite.builder().userId(userId).newsId(newsId).build());
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.ALREADY_FAVORITE);
        }
    }

    /**
     * 取消收藏
     */
    public void removeFavorite(Integer userId, Integer newsId) {
        Favorite fav = favoriteMapper.selectByUserAndNews(userId, newsId);
        if (fav == null) {
            throw new BusinessException(ErrorCode.FAVORITE_NOT_FOUND);
        }
        favoriteMapper.deleteById(fav.getId());
    }

    /**
     * 检查是否已收藏
     */
    public boolean isFavorite(Integer userId, Integer newsId) {
        return favoriteMapper.countByUserAndNews(userId, newsId) > 0;
    }
}
