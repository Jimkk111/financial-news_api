-- 确定性采集流水线基础设施
-- 1) news 表补来源 URL 与正文指纹列（URL 唯一索引支撑精确去重）
-- 2) 新建 crawl_audit 审计表，记录每篇文章的采集结果
-- 注意：url 列为新增，存量行均为 NULL，MySQL 唯一索引允许多个 NULL，可直接创建

ALTER TABLE `news`
    ADD COLUMN `url` VARCHAR(500) DEFAULT NULL COMMENT '文章来源URL' AFTER `source`,
    ADD COLUMN `content_fingerprint` BIGINT DEFAULT NULL COMMENT '正文simhash指纹(近似去重)' AFTER `url`,
    ADD UNIQUE KEY `uk_url` (`url`),
    ADD KEY `idx_content_fingerprint` (`content_fingerprint`);

CREATE TABLE `crawl_audit` (
    `id` INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `run_id` VARCHAR(64) NOT NULL COMMENT '采集批次ID',
    `source` VARCHAR(50) NOT NULL COMMENT '数据源标识',
    `article_url` VARCHAR(500) DEFAULT NULL COMMENT '文章URL',
    `title` VARCHAR(300) DEFAULT NULL COMMENT '文章标题',
    `status` VARCHAR(32) NOT NULL COMMENT '结果: SAVED/DUP_URL/DUP_TITLE/DUP_CONTENT/REJECTED/FAILED',
    `reason` VARCHAR(500) DEFAULT NULL COMMENT '说明(拒绝原因/失败原因)',
    `content_length` INT DEFAULT NULL COMMENT '正文纯文本长度',
    `publish_time` DATETIME DEFAULT NULL COMMENT '解析出的发布时间',
    `duration_ms` INT DEFAULT NULL COMMENT '单篇处理耗时(毫秒)',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
    PRIMARY KEY (`id`),
    KEY `idx_run_id` (`run_id`),
    KEY `idx_source_status` (`source`, `status`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='采集审计表';
