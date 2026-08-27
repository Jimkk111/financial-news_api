package com.financial.news.service.crawler;

/**
 * 写入 Agent — 清洗分类、标签关联、入库
 * <p>负责将结构化文章数据保存到数据库，处理分类/标签的创建和关联。</p>
 *
 * @author financial-news
 * @since 1.0.0
 */
public interface WriterAgent {

    /**
     * 将处理后的文章数据入库
     *
     * @param processResult 处理结果 JSON（含完整文章数据）
     * @return 入库结果 JSON
     */
    String write(String processResult);
}
