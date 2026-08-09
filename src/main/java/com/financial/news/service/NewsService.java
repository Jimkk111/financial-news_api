package com.financial.news.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.financial.news.common.BusinessException;
import com.financial.news.common.ErrorCode;
import com.financial.news.common.Result;
import com.financial.news.entity.*;
import com.financial.news.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 新闻服务
 * <p>提供新闻列表、详情、搜索、分类/标签查询等功能，集成 Redis 缓存</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsService extends ServiceImpl<NewsMapper, News> {

    private final NewsMapper newsMapper;
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;
    private final NewsTagMapper newsTagMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String CACHE_NEWS_DETAIL = "cls:news:detail:";
    private static final String CACHE_NEWS_CATEGORIES = "cls:news:categories";
    private static final String CACHE_NEWS_TAGS = "cls:news:tags";
    private static final long DETAIL_TTL = 600;   // 10分钟
    private static final long LIST_TTL = 3600;    // 1小时

    /**
     * 获取新闻列表（分页）
     */
    public Page<News> listNews(Integer categoryId, String sort, int page, int pageSize) {
        pageSize = Math.min(pageSize, 50);
        Page<News> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<News> wrapper = new LambdaQueryWrapper<>();

        if (categoryId != null) {
            wrapper.eq(News::getCategoryId, categoryId);
        }
        if ("popular".equals(sort)) {
            wrapper.orderByDesc(News::getViews);
        } else {
            wrapper.orderByDesc(News::getPublishTime);
        }

        return newsMapper.selectPage(pageParam, wrapper);
    }

    /**
     * 获取新闻详情（带缓存）
     */
    public News getNewsDetail(Integer id) {
        String cacheKey = CACHE_NEWS_DETAIL + id;
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached instanceof News) {
            return (News) cached;
        }

        News news = newsMapper.selectById(id);
        if (news == null) {
            throw new BusinessException(ErrorCode.NEWS_NOT_FOUND);
        }

        try {
            redisTemplate.opsForValue().set(cacheKey, news, DETAIL_TTL, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Redis 缓存失败: {}", e.getMessage());
        }
        return news;
    }

    /**
     * 增加浏览量并清除缓存
     */
    @Transactional
    public int incrementViews(Integer id) {
        News news = newsMapper.selectById(id);
        if (news == null) {
            throw new BusinessException(ErrorCode.NEWS_NOT_FOUND);
        }
        news.setViews(news.getViews() + 1);
        newsMapper.updateById(news);

        try {
            redisTemplate.delete(CACHE_NEWS_DETAIL + id);
        } catch (Exception e) {
            log.warn("Redis 删除缓存失败: {}", e.getMessage());
        }
        return news.getViews();
    }

    /**
     * 获取分类列表（带缓存）
     */
    public List<Category> getCategories() {
        Object cached = null;
        try {
            cached = redisTemplate.opsForValue().get(CACHE_NEWS_CATEGORIES);
        } catch (Exception e) {
            log.warn("Redis 读取失败: {}", e.getMessage());
        }
        if (cached instanceof List) {
            return (List<Category>) cached;
        }

        List<Category> categories = categoryMapper.selectList(null);
        try {
            redisTemplate.opsForValue().set(CACHE_NEWS_CATEGORIES, categories, LIST_TTL, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Redis 缓存失败: {}", e.getMessage());
        }
        return categories;
    }

    /**
     * 获取标签列表（带缓存）
     */
    public List<Tag> getTags() {
        Object cached = null;
        try {
            cached = redisTemplate.opsForValue().get(CACHE_NEWS_TAGS);
        } catch (Exception e) {
            log.warn("Redis 读取失败: {}", e.getMessage());
        }
        if (cached instanceof List) {
            return (List<Tag>) cached;
        }

        List<Tag> tags = tagMapper.selectList(null);
        try {
            redisTemplate.opsForValue().set(CACHE_NEWS_TAGS, tags, LIST_TTL, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Redis 缓存失败: {}", e.getMessage());
        }
        return tags;
    }

    /**
     * 搜索新闻（按标题和摘要模糊搜索）
     */
    public Page<News> searchNews(String keyword, int page, int pageSize) {
        pageSize = Math.min(pageSize, 50);
        Page<News> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<News> wrapper = new LambdaQueryWrapper<News>()
                .and(w -> w.like(News::getTitle, keyword).or().like(News::getSummary, keyword))
                .orderByDesc(News::getPublishTime);
        return newsMapper.selectPage(pageParam, wrapper);
    }

    /**
     * 获取新闻关联的标签
     */
    public List<Tag> getNewsTags(Integer newsId) {
        List<NewsTag> newsTags = newsTagMapper.selectList(
                new LambdaQueryWrapper<NewsTag>().eq(NewsTag::getNewsId, newsId));
        List<Integer> tagIds = newsTags.stream().map(NewsTag::getTagId).toList();
        if (tagIds.isEmpty()) return List.of();
        return tagMapper.selectBatchIds(tagIds);
    }

    /**
     * MyBatis-Plus 分页结果转换为 Result.PageResult
     */
    public <T> Result.PageResult<T> toPageResult(Page<T> page) {
        return new Result.PageResult<>(
                page.getRecords(),
                page.getTotal(),
                (int) page.getCurrent(),
                (int) page.getSize()
        );
    }
}
