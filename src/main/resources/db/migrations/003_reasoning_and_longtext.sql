-- v5.0.x 存量库升级（对应 feat(ai) 思考链透传 与 feat(crawler) 采集强化 两笔变更）
-- 1) ai_messages 新增思考链列：思考型模型（MiMo/DeepSeek 等）的 reasoning_content，
--    仅 assistant 消息携带；旧代码不感知新列，先改库后发版均可兼容
-- 2) news.content TEXT → LONGTEXT：长文正文超 64KB 会被截断
-- 注意：
--   a) ALTER ... MODIFY 列类型会触发表重建（COPY），news 表大时耗时与锁写时长随表增长，请低峰执行
--   b) 预置财经分类种子由 CategorySeeder 在应用启动时幂等补种，此处 INSERT 仅作提前灌入，
--      清单须与 CategorySeeder.PRESET_CATEGORIES 保持一致
--   c) 执行前请先备份（mysqldump 或快照）

ALTER TABLE `ai_messages`
    ADD COLUMN `reasoning_content` MEDIUMTEXT DEFAULT NULL COMMENT '思考型模型的思考链（reasoning_content），仅 assistant 消息' AFTER `content`;

ALTER TABLE `news`
    MODIFY COLUMN `content` LONGTEXT DEFAULT NULL COMMENT '正文内容（旧 HTML，过渡期保留）';

INSERT IGNORE INTO `categories` (`name`) VALUES
('宏观经济'), ('货币政策'), ('A股'), ('港股'), ('美股'), ('全球市场'),
('公司动态'), ('产业经济'), ('基金理财'), ('债券'), ('外汇'),
('大宗商品'), ('房地产'), ('金融科技'), ('综合财经');
