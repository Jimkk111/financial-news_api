package com.financial.news.service;

import com.financial.news.common.BusinessException;
import com.financial.news.common.ErrorCode;
import com.financial.news.common.Result;
import com.financial.news.dto.response.NewsDetailVO;
import com.financial.news.entity.*;
import com.financial.news.mapper.*;
import com.financial.news.utils.ContentCodec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
public class NewsService {

    private final NewsMapper newsMapper;
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;
    private final NewsTagMapper newsTagMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String CACHE_NEWS_DETAIL = "cls:news:detail:";
    private static final String CACHE_NEWS_CATEGORIES = "cls:news:categories";
    private static final String CACHE_NEWS_TAGS = "cls:news:tags";
    private static final String VIEW_DEDUP_KEY = "cls:news:viewed:";
    private static final long DETAIL_TTL = 600;   // 10分钟
    private static final long LIST_TTL = 3600;    // 1小时
    private static final long VIEW_DEDUP_TTL = 3600; // 同一访问者 1 小时内浏览去重
    private static final int MAX_PAGE_SIZE = 50;

    /** 缓存延迟双删用的调度器 */
    private final ScheduledExecutorService cacheEvictScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "news-cache-evict");
                t.setDaemon(true);
                return t;
            });

    /**
     * 获取新闻列表（分页）
     */
    public Result.PageResult<News> listNews(Integer categoryId, String sort, int page, int pageSize) {
        page = Math.max(page, 1);
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);
        long total = newsMapper.countListPage(categoryId);
        List<News> records = total == 0 ? List.of()
                : newsMapper.selectListPage(categoryId, "popular".equals(sort), (page - 1) * pageSize, pageSize);
        return new Result.PageResult<>(records, total, page, pageSize);
    }

    /**
     * 获取新闻详情（带缓存，附带标签与分类）
     */
    public NewsDetailVO getNewsDetail(Integer id) {
        News news = getNewsFromCache(CACHE_NEWS_DETAIL + id, id);

        NewsDetailVO vo = new NewsDetailVO();
        // Spring BeanUtils 浅拷贝（Hutool BeanUtil 深拷贝会把 Block 接口降级为 Map 导致转换异常）
        BeanUtils.copyProperties(news, vo);
        // 存量新闻未迁移时，响应中实时从 HTML 转换（不落库，由迁移任务负责）
        if (vo.getContentJson() == null && vo.getContent() != null) {
            vo.setContentJson(ContentCodec.fromHtml(vo.getContent()));
        }
        vo.setTags(getNewsTags(id));
        if (news.getCategoryId() != null) {
            vo.setCategory(categoryMapper.selectById(news.getCategoryId()));
        }
        return vo;
    }

    /**
     * 从缓存或数据库获取新闻（基础信息，不含标签）
     */
    private News getNewsFromCache(String cacheKey, Integer id) {
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached instanceof News news) {
            return news;
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
     * 增加浏览量（原子更新 + 同一访问者 1 小时内只计 1 次）
     *
     * @param viewerKey 访问者标识（用户ID 或 IP）
     * @return 计数后的最新浏览量
     */
    public int incrementViews(Integer id, String viewerKey) {
        News news = newsMapper.selectById(id);
        if (news == null) {
            throw new BusinessException(ErrorCode.NEWS_NOT_FOUND);
        }

        // Redis 不可用时放行计数，降级为不防刷
        boolean counted = true;
        try {
            Boolean first = redisTemplate.opsForValue().setIfAbsent(
                    VIEW_DEDUP_KEY + id + ":" + viewerKey, 1, VIEW_DEDUP_TTL, TimeUnit.SECONDS);
            counted = !Boolean.FALSE.equals(first);
        } catch (Exception e) {
            log.warn("Redis 浏览去重失败，降级放行: {}", e.getMessage());
        }
        if (!counted) {
            return news.getViews();
        }

        newsMapper.incrementViews(id);
        evictDetailCache(id);
        return news.getViews() + 1;
    }

    /**
     * 清除详情缓存（延迟双删：读写并发下旧值可能在删除后被重新写入，延迟再删一次）
     */
    private void evictDetailCache(Integer id) {
        String key = CACHE_NEWS_DETAIL + id;
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Redis 删除缓存失败: {}", e.getMessage());
        }
        cacheEvictScheduler.schedule(() -> {
            try {
                redisTemplate.delete(key);
            } catch (Exception ignored) {
            }
        }, 1, TimeUnit.SECONDS);
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

        List<Category> categories = categoryMapper.selectListAll();
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

        List<Tag> tags = tagMapper.selectListAll();
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
    public Result.PageResult<News> searchNews(String keyword, int page, int pageSize) {
        page = Math.max(page, 1);
        pageSize = Math.min(pageSize, MAX_PAGE_SIZE);
        long total = newsMapper.countSearch(keyword);
        List<News> records = total == 0 ? List.of()
                : newsMapper.searchPage(keyword, (page - 1) * pageSize, pageSize);
        return new Result.PageResult<>(records, total, page, pageSize);
    }

    /**
     * 获取新闻关联的标签
     */
    public List<Tag> getNewsTags(Integer newsId) {
        List<NewsTag> newsTags = newsTagMapper.selectByNewsId(newsId);
        List<Integer> tagIds = newsTags.stream().map(NewsTag::getTagId).toList();
        if (tagIds.isEmpty()) return List.of();
        return tagMapper.selectByIds(tagIds);
    }
}
