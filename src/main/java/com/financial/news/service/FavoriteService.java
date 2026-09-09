package com.financial.news.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.financial.news.common.BusinessException;
import com.financial.news.common.ErrorCode;
import com.financial.news.dto.response.FavoriteVO;
import com.financial.news.entity.Favorite;
import com.financial.news.entity.News;
import com.financial.news.mapper.FavoriteMapper;
import com.financial.news.mapper.NewsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
public class FavoriteService extends ServiceImpl<FavoriteMapper, Favorite> {

    private final FavoriteMapper favoriteMapper;
    private final NewsMapper newsMapper;

    /**
     * 获取收藏列表（分页，联表查询新闻信息）
     */
    public IPage<FavoriteVO> listFavorites(Integer userId, int page, int pageSize) {
        Page<FavoriteVO> p = new Page<>(page, Math.min(pageSize, 50));
        return favoriteMapper.selectFavoriteNewsPage(p, userId);
    }

    /**
     * 添加收藏（并发下由 uk_user_news 唯一索引兜底，冲突转为业务错误）
     */
    @Transactional
    public void addFavorite(Integer userId, Integer newsId) {
        if (!newsMapper.exists(new LambdaQueryWrapper<News>().eq(News::getId, newsId))) {
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
        Favorite fav = favoriteMapper.selectOne(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, userId).eq(Favorite::getNewsId, newsId));
        if (fav == null) {
            throw new BusinessException(ErrorCode.FAVORITE_NOT_FOUND);
        }
        favoriteMapper.deleteById(fav.getId());
    }

    /**
     * 检查是否已收藏
     */
    public boolean isFavorite(Integer userId, Integer newsId) {
        return favoriteMapper.exists(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, userId).eq(Favorite::getNewsId, newsId));
    }
}
