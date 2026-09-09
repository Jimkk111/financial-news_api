-- 历史表增加 (user_id, news_id) 唯一索引，防止并发写入重复浏览记录
-- 注意：需先去重存量数据（保留每组最新一条），再建索引；请在低峰期于 dev 演练后执行

-- 1. 去重：删除每组 (user_id, news_id) 中除最新一条外的记录
DELETE h1 FROM history h1
JOIN history h2
  ON h1.user_id = h2.user_id
 AND h1.news_id = h2.news_id
 AND h1.viewed_at < h2.viewed_at;

-- 同一秒内的并列重复（viewed_at 相同）按主键保留最大者
DELETE h1 FROM history h1
JOIN history h2
  ON h1.user_id = h2.user_id
 AND h1.news_id = h2.news_id
 AND h1.viewed_at = h2.viewed_at
 AND h1.id < h2.id;

-- 2. 建唯一索引
ALTER TABLE `history` ADD UNIQUE KEY `uk_user_news` (`user_id`, `news_id`);
