-- ============================================================
-- v800 行情模块迁移脚本（幂等，应用启动时自动执行，也可手工执行）
-- 新库无需执行：db/init.sql 已包含同等结构（DROP+CREATE 版本）
-- ============================================================
SET NAMES utf8mb4;
-- 行情模块（v800）：标的主表 / 交易日历 / 热门名单
-- 不写死数据库名，始终使用当前数据源连接选择的 schema
-- ============================================================

-- ----------------------------
-- 行情标的主表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `quote_security` (
    `id` INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `symbol` VARCHAR(20) NOT NULL COMMENT '完整标识 code.SUFFIX，如 600519.SH、AAPL.US',
    `sec_type` TINYINT NOT NULL DEFAULT 1 COMMENT '类型：1 股票 2 指数',
    `market` VARCHAR(4) NOT NULL COMMENT '市场：CN/HK/US',
    `name` VARCHAR(64) NOT NULL COMMENT '证券名称',
    `pinyin_abbr` VARCHAR(40) DEFAULT NULL COMMENT '名称拼音首字母（大写，仅字母数字），如 GZMT',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1 正常 2 已退市',
    `currency` VARCHAR(4) NOT NULL COMMENT '币种代码：CNY/HKD/USD',
    `upstream_secid` VARCHAR(24) NOT NULL COMMENT '主源内部标识，如 1.600519、116.00700、124.HSTECH',
    `missing_days` INT NOT NULL DEFAULT 0 COMMENT '连续同步未出现次数（退市判定）',
    `last_active_date` DATE DEFAULT NULL COMMENT '最近一次出现在上游列表的日期',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_symbol_type` (`symbol`, `sec_type`),
    KEY `idx_market_status` (`market`, `status`),
    KEY `idx_pinyin` (`pinyin_abbr`),
    KEY `idx_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='行情标的主表';

-- ----------------------------
-- 行情交易日历
-- ----------------------------
CREATE TABLE IF NOT EXISTS `quote_trade_calendar` (
    `id` INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `market` VARCHAR(4) NOT NULL COMMENT '市场：CN/HK/US',
    `trade_date` DATE NOT NULL COMMENT '日期（北京时间）',
    `is_open` TINYINT NOT NULL DEFAULT 0 COMMENT '是否交易日：0 否 1 是',
    `session1_open` TIME DEFAULT NULL COMMENT '第一时段开盘（北京时间）',
    `session1_close` TIME DEFAULT NULL COMMENT '第一时段收盘',
    `session2_open` TIME DEFAULT NULL COMMENT '第二时段开盘，无则 NULL',
    `session2_close` TIME DEFAULT NULL COMMENT '第二时段收盘；收盘早于开盘表示跨日（美股）',
    `source` VARCHAR(10) NOT NULL DEFAULT 'AUTO' COMMENT '来源：AUTO 推导 / MANUAL 人工（不被覆盖）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_market_date` (`market`, `trade_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='行情交易日历';

-- ----------------------------
-- 热门标的名单
-- ----------------------------
CREATE TABLE IF NOT EXISTS `quote_hot_list` (
    `id` INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `market` VARCHAR(4) NOT NULL COMMENT '市场：CN/HK/US',
    `symbol` VARCHAR(20) NOT NULL COMMENT '完整标识，须存在于 quote_security',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '展示顺序，小在前',
    `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：0 否 1 是',
    `remark` VARCHAR(100) DEFAULT NULL COMMENT '备注（圈定依据/日期）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_market_symbol` (`market`, `symbol`),
    KEY `idx_market_enabled_sort` (`market`, `enabled`, `sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='热门标的名单';

-- 种子：指数（sec_type=2）
INSERT IGNORE INTO `quote_security`
    (`symbol`, `sec_type`, `market`, `name`, `pinyin_abbr`, `status`, `currency`, `upstream_secid`) VALUES
('000001.SH', 2, 'CN', '上证指数', 'SZZS',  1, 'CNY', '1.000001'),
('399001.SZ', 2, 'CN', '深证成指', 'SZCZ',  1, 'CNY', '0.399001'),
('399006.SZ', 2, 'CN', '创业板指', 'CYBZ',  1, 'CNY', '0.399006'),
('000300.SH', 2, 'CN', '沪深300', 'HS300', 1, 'CNY', '1.000300'),
('DJI.US',    2, 'US', '道琼斯',   'DJS',   1, 'USD', '100.DJIA'),
('IXIC.US',   2, 'US', '纳斯达克', 'NSDK',  1, 'USD', '100.NDX'),
('SPX.US',    2, 'US', '标普500',  'BP500', 1, 'USD', '100.SPX'),
('HSI.HK',    2, 'HK', '恒生指数', 'HSZS',  1, 'HKD', '100.HSI'),
('HSTECH.HK', 2, 'HK', '恒生科技', 'HSKJ',  1, 'HKD', '124.HSTECH');

-- 种子：热门标的（与 quote_hot_list 一致；pinyin_abbr 为初值，每日同步任务重算）
INSERT IGNORE INTO `quote_security`
    (`symbol`, `sec_type`, `market`, `name`, `pinyin_abbr`, `status`, `currency`, `upstream_secid`) VALUES
('600519.SH', 1, 'CN', '贵州茅台', 'GZMT', 1, 'CNY', '1.600519'),
('300750.SZ', 1, 'CN', '宁德时代', 'NDSD', 1, 'CNY', '0.300750'),
('002594.SZ', 1, 'CN', '比亚迪',   'BYD',  1, 'CNY', '0.002594'),
('601318.SH', 1, 'CN', '中国平安', 'ZGPA', 1, 'CNY', '1.601318'),
('600036.SH', 1, 'CN', '招商银行', 'ZSYH', 1, 'CNY', '1.600036'),
('000858.SZ', 1, 'CN', '五粮液',   'WLY',  1, 'CNY', '0.000858'),
('300059.SZ', 1, 'CN', '东方财富', 'DFCF', 1, 'CNY', '0.300059'),
('600030.SH', 1, 'CN', '中信证券', 'ZXZQ', 1, 'CNY', '1.600030'),
('600900.SH', 1, 'CN', '长江电力', 'CJDL', 1, 'CNY', '1.600900'),
('601899.SH', 1, 'CN', '紫金矿业', 'ZJKY', 1, 'CNY', '1.601899'),
('000333.SZ', 1, 'CN', '美的集团', 'MDJT', 1, 'CNY', '0.000333'),
('000651.SZ', 1, 'CN', '格力电器', 'GLDQ', 1, 'CNY', '0.000651'),
('600276.SH', 1, 'CN', '恒瑞医药', 'HRYY', 1, 'CNY', '1.600276'),
('601012.SH', 1, 'CN', '隆基绿能', 'LJLN', 1, 'CNY', '1.601012'),
('600309.SH', 1, 'CN', '万华化学', 'WHHX', 1, 'CNY', '1.600309'),
('601166.SH', 1, 'CN', '兴业银行', 'XYYH', 1, 'CNY', '1.601166'),
('000725.SZ', 1, 'CN', '京东方A',  'JDFA', 1, 'CNY', '0.000725'),
('688981.SH', 1, 'CN', '中芯国际', 'ZXGJ', 1, 'CNY', '1.688981'),
('002415.SZ', 1, 'CN', '海康威视', 'HKWS', 1, 'CNY', '0.002415'),
('603259.SH', 1, 'CN', '药明康德', 'YMKD', 1, 'CNY', '1.603259'),
('AAPL.US',  1, 'US', '苹果',      NULL, 1, 'USD', '105.AAPL'),
('MSFT.US',  1, 'US', '微软',      NULL, 1, 'USD', '105.MSFT'),
('NVDA.US',  1, 'US', '英伟达',    NULL, 1, 'USD', '105.NVDA'),
('GOOGL.US', 1, 'US', '谷歌',      NULL, 1, 'USD', '105.GOOGL'),
('AMZN.US',  1, 'US', '亚马逊',    NULL, 1, 'USD', '105.AMZN'),
('META.US',  1, 'US', 'Meta',      NULL, 1, 'USD', '105.META'),
('TSLA.US',  1, 'US', '特斯拉',    NULL, 1, 'USD', '105.TSLA'),
('AVGO.US',  1, 'US', '博通',      NULL, 1, 'USD', '105.AVGO'),
('LLY.US',   1, 'US', '礼来',      NULL, 1, 'USD', '106.LLY'),
('JPM.US',   1, 'US', '摩根大通',  NULL, 1, 'USD', '106.JPM'),
('V.US',     1, 'US', '维萨',      NULL, 1, 'USD', '106.V'),
('XOM.US',   1, 'US', '埃克森美孚', NULL, 1, 'USD', '106.XOM'),
('UNH.US',   1, 'US', '联合健康',  NULL, 1, 'USD', '106.UNH'),
('MA.US',    1, 'US', '万事达',    NULL, 1, 'USD', '106.MA'),
('PG.US',    1, 'US', '宝洁',      NULL, 1, 'USD', '106.PG'),
('JNJ.US',   1, 'US', '强生',      NULL, 1, 'USD', '106.JNJ'),
('HD.US',    1, 'US', '家得宝',    NULL, 1, 'USD', '106.HD'),
('WMT.US',   1, 'US', '沃尔玛',    NULL, 1, 'USD', '106.WMT'),
('ORCL.US',  1, 'US', '甲骨文',    NULL, 1, 'USD', '106.ORCL'),
('CRM.US',   1, 'US', '赛富时',    NULL, 1, 'USD', '106.CRM'),
('00700.HK', 1, 'HK', '腾讯控股',     'TXKG',   1, 'HKD', '116.00700'),
('09988.HK', 1, 'HK', '阿里巴巴-W',   'ALBBW',  1, 'HKD', '116.09988'),
('03690.HK', 1, 'HK', '美团-W',       'MTW',    1, 'HKD', '116.03690'),
('09618.HK', 1, 'HK', '京东集团-SW',  'JDJTSW', 1, 'HKD', '116.09618'),
('00941.HK', 1, 'HK', '中国移动',     'ZGYD',   1, 'HKD', '116.00941'),
('01211.HK', 1, 'HK', '比亚迪股份',   'BYDGF',  1, 'HKD', '116.01211'),
('01810.HK', 1, 'HK', '小米集团-W',   'XMJTW',  1, 'HKD', '116.01810'),
('02318.HK', 1, 'HK', '中国平安',     'ZGPA',   1, 'HKD', '116.02318'),
('00388.HK', 1, 'HK', '香港交易所',   'XGJYS',  1, 'HKD', '116.00388'),
('00005.HK', 1, 'HK', '汇丰控股',     'HFKG',   1, 'HKD', '116.00005'),
('01299.HK', 1, 'HK', '友邦保险',     'YZBX',   1, 'HKD', '116.01299'),
('00939.HK', 1, 'HK', '建设银行',     'JSYH',   1, 'HKD', '116.00939'),
('01398.HK', 1, 'HK', '工商银行',     'GSYH',   1, 'HKD', '116.01398'),
('03988.HK', 1, 'HK', '中国银行',     'ZGYH',   1, 'HKD', '116.03988'),
('03968.HK', 1, 'HK', '招商银行',     'ZSYH',   1, 'HKD', '116.03968'),
('00883.HK', 1, 'HK', '中国海洋石油', 'ZGHYSY', 1, 'HKD', '116.00883'),
('00857.HK', 1, 'HK', '中国石油股份', 'ZGSYGF', 1, 'HKD', '116.00857'),
('09999.HK', 1, 'HK', '网易-S',       'WLYS',   1, 'HKD', '116.09999'),
('01024.HK', 1, 'HK', '快手-W',       'KSW',    1, 'HKD', '116.01024'),
('02015.HK', 1, 'HK', '理想汽车-W',   'LXQCW',  1, 'HKD', '116.02015');

-- 种子：热门名单（PRD Q1：首期人工圈定，月度复审）
INSERT IGNORE INTO `quote_hot_list` (`market`, `symbol`, `sort_order`, `enabled`, `remark`) VALUES
('CN', '600519.SH', 1,  1, '2026-10 首期圈定'),
('CN', '300750.SZ', 2,  1, NULL),
('CN', '002594.SZ', 3,  1, NULL),
('CN', '601318.SH', 4,  1, NULL),
('CN', '600036.SH', 5,  1, NULL),
('CN', '000858.SZ', 6,  1, NULL),
('CN', '300059.SZ', 7,  1, NULL),
('CN', '600030.SH', 8,  1, NULL),
('CN', '600900.SH', 9,  1, NULL),
('CN', '601899.SH', 10, 1, NULL),
('CN', '000333.SZ', 11, 1, NULL),
('CN', '000651.SZ', 12, 1, NULL),
('CN', '600276.SH', 13, 1, NULL),
('CN', '601012.SH', 14, 1, NULL),
('CN', '600309.SH', 15, 1, NULL),
('CN', '601166.SH', 16, 1, NULL),
('CN', '000725.SZ', 17, 1, NULL),
('CN', '688981.SH', 18, 1, NULL),
('CN', '002415.SZ', 19, 1, NULL),
('CN', '603259.SH', 20, 1, NULL),
('US', 'AAPL.US',  1,  1, '2026-10 首期圈定'),
('US', 'MSFT.US',  2,  1, NULL),
('US', 'NVDA.US',  3,  1, NULL),
('US', 'GOOGL.US', 4,  1, NULL),
('US', 'AMZN.US',  5,  1, NULL),
('US', 'META.US',  6,  1, NULL),
('US', 'TSLA.US',  7,  1, NULL),
('US', 'AVGO.US',  8,  1, NULL),
('US', 'LLY.US',   9,  1, NULL),
('US', 'JPM.US',   10, 1, NULL),
('US', 'V.US',     11, 1, NULL),
('US', 'XOM.US',   12, 1, NULL),
('US', 'UNH.US',   13, 1, NULL),
('US', 'MA.US',    14, 1, NULL),
('US', 'PG.US',    15, 1, NULL),
('US', 'JNJ.US',   16, 1, NULL),
('US', 'HD.US',    17, 1, NULL),
('US', 'WMT.US',   18, 1, NULL),
('US', 'ORCL.US',  19, 1, NULL),
('US', 'CRM.US',   20, 1, NULL),
('HK', '00700.HK', 1,  1, '2026-10 首期圈定'),
('HK', '09988.HK', 2,  1, NULL),
('HK', '03690.HK', 3,  1, NULL),
('HK', '09618.HK', 4,  1, NULL),
('HK', '00941.HK', 5,  1, NULL),
('HK', '01211.HK', 6,  1, NULL),
('HK', '01810.HK', 7,  1, NULL),
('HK', '02318.HK', 8,  1, NULL),
('HK', '00388.HK', 9,  1, NULL),
('HK', '00005.HK', 10, 1, NULL),
('HK', '01299.HK', 11, 1, NULL),
('HK', '00939.HK', 12, 1, NULL),
('HK', '01398.HK', 13, 1, NULL),
('HK', '03988.HK', 14, 1, NULL),
('HK', '03968.HK', 15, 1, NULL),
('HK', '00883.HK', 16, 1, NULL),
('HK', '00857.HK', 17, 1, NULL),
('HK', '09999.HK', 18, 1, NULL),
('HK', '01024.HK', 19, 1, NULL),
('HK', '02015.HK', 20, 1, NULL);
