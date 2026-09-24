package com.financial.news.service.crawler.ingest;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据源连接器
 * <p>每个站点一个确定性实现：列表 API/RSS + 详情抓取。
 * 不依赖 LLM，失败时抛异常由流水线记录审计，不吞错误。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
public interface SourceConnector {

    /** 数据源标识（如 wallstreetcn / sina / eastmoney） */
    String sourceKey();

    /** 展示名（入库 source 字段） */
    String sourceName();

    /** 是否启用（配置开关） */
    boolean enabled();

    /** 入库默认分类（LLM 打标前先用来源站默认分类） */
    default String defaultCategory() {
        return null;
    }

    /**
     * 拉取最新文章列表
     *
     * @param limit 数量上限
     */
    List<ArticleRef> listLatest(int limit) throws Exception;

    /**
     * 抓取文章详情（正文 HTML）
     */
    ArticleDetail fetchDetail(ArticleRef ref) throws Exception;

    /**
     * 由已入库的文章 URL 还原抓取引用（存量回填用）。
     * 默认以 URL 自身作为 refId；数据源内部 ID 与 URL 不同时（如华尔街见闻）覆写。
     */
    default ArticleRef refFromUrl(String url, String title, LocalDateTime publishTime) {
        return ArticleRef.builder().refId(url).url(url).title(title).publishTime(publishTime).build();
    }
}
