-- ============================================================
-- 财经新闻 API 数据库初始化脚本
-- 数据库名: cls_financial_news_database
-- ============================================================

CREATE DATABASE IF NOT EXISTS cls_financial_news_database
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE cls_financial_news_database;

-- ----------------------------
-- 用户表
-- ----------------------------
DROP TABLE IF EXISTS `users`;
CREATE TABLE `users` (
    `id` INT NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `uid` VARCHAR(50) NOT NULL COMMENT '用户公开ID，格式 user-xxxxxxxx',
    `display_id` VARCHAR(20) NOT NULL COMMENT '用户展示ID，格式 U123456001',
    `username` VARCHAR(50) NOT NULL COMMENT '用户名',
    `email` VARCHAR(100) NOT NULL COMMENT '邮箱',
    `password_hash` VARCHAR(255) NOT NULL COMMENT 'bcrypt密码哈希',
    `avatar` VARCHAR(500) DEFAULT NULL COMMENT '头像URL',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted_at` DATETIME DEFAULT NULL COMMENT '软删除时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_uid` (`uid`),
    UNIQUE KEY `uk_display_id` (`display_id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ----------------------------
-- 分类表
-- ----------------------------
DROP TABLE IF EXISTS `categories`;
CREATE TABLE `categories` (
    `id` INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name` VARCHAR(50) NOT NULL COMMENT '分类名称',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分类表';

-- ----------------------------
-- 标签表
-- ----------------------------
DROP TABLE IF EXISTS `tags`;
CREATE TABLE `tags` (
    `id` INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name` VARCHAR(50) NOT NULL COMMENT '标签名称',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='标签表';

-- ----------------------------
-- 新闻表
-- ----------------------------
DROP TABLE IF EXISTS `news`;
CREATE TABLE `news` (
    `id` INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `title` VARCHAR(200) NOT NULL COMMENT '标题',
    `summary` TEXT DEFAULT NULL COMMENT '摘要',
    `content` TEXT DEFAULT NULL COMMENT '正文内容（旧 HTML，过渡期保留）',
    `content_json` LONGTEXT DEFAULT NULL COMMENT '正文内容（块级 JSON 字符串，新契约）',
    `publish_time` DATETIME DEFAULT NULL COMMENT '发布时间',
    `source` VARCHAR(100) DEFAULT NULL COMMENT '来源',
    `url` VARCHAR(500) DEFAULT NULL COMMENT '文章来源URL',
    `content_fingerprint` BIGINT DEFAULT NULL COMMENT '正文simhash指纹(近似去重)',
    `views` INT NOT NULL DEFAULT 0 COMMENT '浏览量',
    `has_image` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否有图片',
    `image_url` VARCHAR(500) DEFAULT NULL COMMENT '图片URL',
    `category_id` INT DEFAULT NULL COMMENT '分类ID',
    `user_id` INT DEFAULT NULL COMMENT '发布用户ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted_at` DATETIME DEFAULT NULL COMMENT '软删除时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_url` (`url`),
    KEY `idx_content_fingerprint` (`content_fingerprint`),
    KEY `idx_category_id` (`category_id`),
    KEY `idx_publish_time` (`publish_time`),
    KEY `idx_views` (`views`),
    KEY `idx_user_id` (`user_id`),
    CONSTRAINT `fk_news_category` FOREIGN KEY (`category_id`) REFERENCES `categories` (`id`) ON DELETE SET NULL,
    CONSTRAINT `fk_news_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='新闻表';

-- ----------------------------
-- 新闻-标签关联表
-- ----------------------------
DROP TABLE IF EXISTS `news_tags`;
CREATE TABLE `news_tags` (
    `id` INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `news_id` INT NOT NULL COMMENT '新闻ID',
    `tag_id` INT NOT NULL COMMENT '标签ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_news_tag` (`news_id`, `tag_id`),
    KEY `idx_news_id` (`news_id`),
    KEY `idx_tag_id` (`tag_id`),
    CONSTRAINT `fk_news_tags_news` FOREIGN KEY (`news_id`) REFERENCES `news` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_news_tags_tag` FOREIGN KEY (`tag_id`) REFERENCES `tags` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='新闻-标签关联表';

-- ----------------------------
-- 草稿表
-- ----------------------------
DROP TABLE IF EXISTS `drafts`;
CREATE TABLE `drafts` (
    `id` VARCHAR(50) NOT NULL COMMENT '草稿ID，格式 draft-xxxxxxxx',
    `user_id` INT NOT NULL COMMENT '用户ID',
    `title` VARCHAR(200) DEFAULT NULL COMMENT '标题',
    `content` TEXT DEFAULT NULL COMMENT '内容（旧 HTML，过渡期保留）',
    `content_json` LONGTEXT DEFAULT NULL COMMENT '内容（块级 JSON 字符串，新契约）',
    `cover_image` VARCHAR(500) DEFAULT NULL COMMENT '封面图URL',
    `category_id` INT DEFAULT NULL COMMENT '分类ID',
    `status` VARCHAR(20) NOT NULL DEFAULT 'draft' COMMENT '状态：draft/published',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_status` (`status`),
    CONSTRAINT `fk_drafts_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_drafts_category` FOREIGN KEY (`category_id`) REFERENCES `categories` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='草稿表';

-- ----------------------------
-- 草稿-标签关联表
-- ----------------------------
DROP TABLE IF EXISTS `draft_tags`;
CREATE TABLE `draft_tags` (
    `id` INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `draft_id` VARCHAR(50) NOT NULL COMMENT '草稿ID',
    `tag_id` INT NOT NULL COMMENT '标签ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_draft_tag` (`draft_id`, `tag_id`),
    KEY `idx_draft_id` (`draft_id`),
    KEY `idx_tag_id` (`tag_id`),
    CONSTRAINT `fk_draft_tags_draft` FOREIGN KEY (`draft_id`) REFERENCES `drafts` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_draft_tags_tag` FOREIGN KEY (`tag_id`) REFERENCES `tags` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='草稿-标签关联表';

-- ----------------------------
-- 收藏表
-- ----------------------------
DROP TABLE IF EXISTS `favorites`;
CREATE TABLE `favorites` (
    `id` INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id` INT NOT NULL COMMENT '用户ID',
    `news_id` INT NOT NULL COMMENT '新闻ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_news` (`user_id`, `news_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_news_id` (`news_id`),
    CONSTRAINT `fk_favorites_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_favorites_news` FOREIGN KEY (`news_id`) REFERENCES `news` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='收藏表';

-- ----------------------------
-- 浏览历史表
-- ----------------------------
DROP TABLE IF EXISTS `history`;
CREATE TABLE `history` (
    `id` INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id` INT NOT NULL COMMENT '用户ID',
    `news_id` INT NOT NULL COMMENT '新闻ID',
    `viewed_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '浏览时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_news` (`user_id`, `news_id`),
    KEY `idx_viewed_at` (`viewed_at`),
    CONSTRAINT `fk_history_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_history_news` FOREIGN KEY (`news_id`) REFERENCES `news` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='浏览历史表';

-- ----------------------------
-- AI会话表
-- ----------------------------
DROP TABLE IF EXISTS `ai_sessions`;
CREATE TABLE `ai_sessions` (
    `id` INT NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    `session_id` VARCHAR(50) NOT NULL COMMENT '会话公开ID，格式 session-xxxxxxxx',
    `user_id` INT NOT NULL COMMENT '用户ID',
    `title` VARCHAR(100) DEFAULT NULL COMMENT '会话标题',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_id` (`session_id`),
    KEY `idx_user_id` (`user_id`),
    CONSTRAINT `fk_ai_sessions_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI会话表';

-- ----------------------------
-- AI消息表
-- ----------------------------
DROP TABLE IF EXISTS `ai_messages`;
CREATE TABLE `ai_messages` (
    `id` INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `session_id` INT NOT NULL COMMENT '会话内部ID',
    `role` VARCHAR(20) NOT NULL COMMENT '角色：user/assistant/system',
    `content` TEXT NOT NULL COMMENT '消息内容',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_session_id` (`session_id`),
    CONSTRAINT `fk_ai_messages_session` FOREIGN KEY (`session_id`) REFERENCES `ai_sessions` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI消息表';

-- ----------------------------
-- 验证码表
-- ----------------------------
DROP TABLE IF EXISTS `verification_codes`;
CREATE TABLE `verification_codes` (
    `id` INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `email` VARCHAR(100) NOT NULL COMMENT '邮箱',
    `code` VARCHAR(10) NOT NULL COMMENT '6位数字验证码',
    `username` VARCHAR(50) DEFAULT NULL COMMENT '关联用户名',
    `expires_at` DATETIME NOT NULL COMMENT '过期时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_email` (`email`),
    KEY `idx_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='验证码表';
