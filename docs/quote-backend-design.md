# 行情功能后端技术方案

> 对应 PRD：《行情功能需求文档》（行情 Tab、标的详情、标的搜索）。
> 本方案只覆盖后端（本仓库，分支 `feat-v800`）。前端交互、图表选型与导航改动由 `financial-news` 前端仓库另行设计，两端契约以本文第 5 章 API 设计 + Knife4j 在线文档为准。
> 撰写时间：2026-10-06。

---

## 1. 背景与范围

### 1.1 后端职责边界

按 PRD 5.3 的职责划分，后端负责：

1. 对接第三方行情数据源，并完成**多源兜底、容错、降级**（对上游的一切不确定性在 backend 内消化）；
2. 向前端输出**统一、口径一致**的行情数据（统一标的标识、统一涨跌幅精度、统一交易状态）；
3. 交易日历与交易时段维护（含美股冬/夏令时，PRD Q2）；
4. 热门标的名单的后端可配置存储（PRD Q1）；
5. 公开只读接口的**防滥用限流**（PRD 七-5）。

### 1.2 不在后端本期范围

- 新闻×行情联动、自选股、WebSocket 推送、盘前盘后、复权切换/周K月K/技术指标（PRD 明确不做）；
- 全局搜索接入股票（PRD Q3：搜索仅提供行情页专用接口，不并入 `/api/news/search`）；
- 管理后台界面（热门名单首期通过 SQL/种子数据人工圈定，表结构预留管理能力）。

---

## 2. 总体架构

```text
前端 (financial-news, Vue3)
   │  GET /api/quotes/**（公开、限流）
   ▼
┌─────────────────────────────── Spring Boot（本仓库）───────────────────────────────┐
│ QuoteController                    ← Knife4j 注解输出契约                          │
│   └─ QuoteService（门面：快照 / 分时 / K线 / 搜索 / 指数卡 / 热门）                  │
│        ├─ QuoteCacheService        Redis 双 TTL 缓存（fresh / stale+delayed）       │
│        ├─ TradingSessionService    交易日历 + 会话规则 → 交易状态、TTL 计算          │
│        ├─ QuoteSearchService       本地标的主表检索（代码/名称/拼音缩写）            │
│        └─ QuoteProviderRouter      上游路由：主源 → 备源 → stale → 报错              │
│             ├─ EastMoneyQuoteProvider（主源，push2/push2his）                       │
│             └─ SinaQuoteProvider（备源，仅快照）                                    │
│ 同步任务（@Scheduled）：日历生成 / 标的主表同步 / 盘中缓存预热                        │
└──────────────────────────────────────────────────────────────────────────────────┘
   │                        │                          │
   ▼                        ▼                          ▼
 MySQL（日历/主表/热门名单）  Redis（行情缓存+限流）   东方财富 / 新浪（上游 HTTP）
```

设计原则：

1. **防腐层隔离上游**：免费行情接口均为非官方契约，字段可能变动。所有上游字段解析只发生在 `provider/` 包内，provider 输出统一的领域模型（`QuoteSnapshot`、`TrendPoint`、`KlineBar`），上游变更只改一处。
2. **有状态数据落库、易变数据进 Redis**：日历、标的主表、热门名单进 MySQL；快照/分时/K线进 Redis。
3. **无会话状态**：所有接口无登录态、无服务端会话；"记住用户所在市场"由前端 localStorage 承担，后端只按 `market` 参数返回数据。

---

## 3. 标的标识与市场规则

### 3.1 标的标识（对外契约，必须遵守 PRD 5.2-5）

- **symbol** = `代码.市场后缀`：`600519.SH`、`000001.SZ`、`00700.HK`、`AAPL.US`。
- **type（secType）**：`stock`（股票）/ `index`（指数），与 symbol 一起构成标的完整唯一标识。
- `000001` 同号异实问题：平安银行是 `000001.SZ`（stock），上证指数是 `000001.SH`（index），交易所后缀 + type 双重区分；**详情类接口路径必须携带 type**（见 5.1），任何端都不允许只按数字代码跳转。
- 指数代码：A股指数沿用 6 位数字 + 交易所后缀；港股指数用字母代码（`HSI.HK`、`HSTECH.HK`）；美股指数用字母代码（`DJI.US`、`IXIC.US`、`SPX.US`）。

### 3.2 三市场参数表（与 PRD 5.1 对齐）

| 参数 | A股（CN） | 港股（HK） | 美股（US） |
|------|-----------|-----------|-----------|
| 代码格式 | 6 位数字 | 5 位数字（前导零保留） | 字母代码 |
| 交易时段（北京时间，按日历表逐日生成） | 09:30–11:30, 13:00–15:00 | 09:30–12:00, 13:00–16:00 | 夏令时 21:30–次日 04:00；冬令时 22:30–次日 05:00 |
| 币种符号 | ¥ | HK$ | $ |
| 币种代码 | CNY | HKD | USD |
| 价格精度 | 2 位 | 上游原样（最多 3 位，仙股 4 位） | 2 位 |
| 分时轴长度（分钟） | 240 | 330（午休压缩） | 390 |
| 首期覆盖范围 | 沪深主板/创业板/科创板（00/30/60/68 开头），不含北交所 | 主板/创业板 | 三大交易所普通股 |

美股冬/夏令时不在运行时判断，而是由日历生成任务按「3 月第二个周日起至 11 月第一个周六为夏令时」规则逐日写入日历表（见第 6 章），运行时只查表——PRD Q2 的结论"以交易日历为准"即落在这一层。

---

## 4. 数据源选型

### 4.1 选型结论

| 角色 | 数据源 | 覆盖 | 说明 |
|------|--------|------|------|
| 主源 | 东方财富 push2 / push2his HTTP 接口 | 三市场快照、分时、K线、证券列表 | 免费、字段齐全（含均价线、前复权 K线、批量请求）；项目爬虫链路已有与东财生态交互的经验。**非官方接口**，仅经防腐层使用 |
| 备源 | 新浪财经 `hq.sinajs.cn` | 三市场**实时快照** | 仅兜底快照；分时/K线无备源，走 stale 降级 |
| 预留 | `QuoteProvider` SPI | — | 后续如切付费源（Tushare Pro / 阿里云市场行情等），实现新 Provider 即可，上层不动 |

选型理由：PRD 5.3 将多源兜底划归后端；首期无预算假设下，东财接口覆盖度最高（唯一同时提供三市场分时+前复权K线+均价线的免费源），新浪做快照级兜底满足"整体不可用"概率最小化。**合规提示**：免费接口为非商用灰色地带，接口地址、字段、频率限制均可能变动，因此第 4.3 节的防腐层、熔断与配置开关为强制项；若后续商业化，应整体切换付费源（只改 provider 实现）。

### 4.2 上游 secid 映射（东财内部标识）

对外一律用 `symbol + type`，对内由主表 `upstream_secid` 字段承载东财 secid，**运行时不做格式猜算**（尤其美股，同一代码可能属 NASDAQ/NYSE/AMEX 不同市场段）：

| 标的 | 东财 secid 规则 |
|------|----------------|
| 沪股/沪指 | `1.{code}`（如 `1.600519`、上证指数 `1.000001`） |
| 深股/深指 | `0.{code}`（如 `0.000001`、深证成指 `0.399001`） |
| 港股 | `116.{5位代码}`（如 `116.00700`） |
| 港股指数 | `100.{代码}`（恒指 `100.HSI`、恒生科技 `100.HSTECH`） |
| 美股个股 | `105.{code}`（NASDAQ）/ `106.{code}`（NYSE）/ `107.{code}`（AMEX），由同步任务从列表接口落库 |
| 美股指数 | `100.DJIA`、`100.NDX`（纳斯达克综合）、`100.SPX` |

### 4.3 上游接口清单（provider 内使用，详见附录 A）

| 用途 | 接口 | 频控策略 |
|------|------|---------|
| 批量快照（指数卡/热门/详情头部共用） | `push2.eastmoney.com/api/qt/ulist.np/get` | 批量合并请求（指数卡+热门一次拉） |
| 分时 | `push2his.eastmoney.com/api/qt/stock/trends2/get` | 单标的，缓存削峰 |
| 日 K | `push2his.eastmoney.com/api/qt/stock/kline/get`（`fqt=1` 前复权） | 单标的，缓存削峰 |
| 证券列表（主表同步） | `push2.eastmoney.com/api/qt/clist/get` | 每日一次全量分页 |
| 快照备源 | `hq.sinajs.cn/list=...`（需带 Referer 头） | 仅主源快照失败时 |

HTTP 客户端复用项目已有依赖 OkHttp 4.12（`pom.xml`）：连接超时 2s、读超时 4s、单次请求失败重试 1 次。

---

## 5. API 设计

### 5.1 端点总览

全部为 `GET`、公开（无需登录），统一响应 `{ code, msg, data }`（`common/Result.java` 现行契约，`code` 为字符串业务码，HTTP 状态码由 `ErrorCode` 映射）。

| # | 端点 | 用途 | 对应 PRD |
|---|------|------|---------|
| 1 | `GET /api/quotes/indices?market=CN` | 指数卡片（该市场全部指数批量快照） | 4.1 |
| 2 | `GET /api/quotes/hot?market=CN&limit=20` | 热门标的列表 | 4.1 |
| 3 | `GET /api/quotes/search?keyword=gzmt&limit=10` | 三市场混合搜索 | 4.3 |
| 4 | `GET /api/quotes/{type}/{symbol}/snapshot` | 单标的实时快照（报价区） | 4.2 |
| 5 | `GET /api/quotes/{type}/{symbol}/trend` | 当日分时 | 4.2 |
| 6 | `GET /api/quotes/{type}/{symbol}/kline?count=250` | 日 K（前复权 + MA） | 4.2 |

- `type` ∈ `stock | index`，进入路径保证 5.2-5 的唯一寻址；`market` ∈ `CN | US | HK`（大小写不敏感）。
- 详情页首屏 = 端点 4 + 5 并行请求，K 线切换时请求端点 6——两段请求彼此独立，是 E7（单市场失败不影响其他市场）在前端分段加载的配合基础。
- `indices` 与 `hot` 是"按市场分段"的独立请求，任一市场段失败只影响该段。

### 5.2 通用数据约定

所有含行情数值的响应统一携带：

| 字段 | 类型 | 说明 |
|------|------|------|
| `market` | string | `CN / HK / US` |
| `secType` | string | `stock / index` |
| `symbol` | string | 完整标识，如 `600519.SH` |
| `name` | string | 证券名称 |
| `currency` | string | `CNY / HKD / USD`（币种符号映射由前端持有） |
| `tradeStatus` | string | `PRE_OPEN / OPEN / LUNCH_BREAK / CLOSED / HOLIDAY / SUSPENDED / DELISTED` |
| `dataDate` | string | 行情数据归属交易日 `yyyy-MM-dd`（休市展示最近交易日，防误读，PRD 5.2-3） |
| `dataTime` | string | 快照时间 `HH:mm:ss` |
| `delayed` | boolean | **true 表示数据延迟/降级**，前端必须展示"数据延迟"提示（PRD 5.3，见第 7/8 章） |

- 涨跌额/涨跌幅统一 2 位小数、数值型输出（不用字符串），渲染精度由前端控制；价格字段按 3.2 的精度输出数值。
- 退市标的：`tradeStatus=DELISTED`，价格类字段为 `null`，K 线接口仍可用。

### 5.3 端点明细

#### 1) 指数卡片

`GET /api/quotes/indices?market=CN`

```json
{ "code": "SUCCESS", "msg": "ok", "data": {
  "market": "CN",
  "delayed": false,
  "indices": [
    { "secType": "index", "symbol": "000001.SH", "name": "上证指数",
      "currency": "CNY", "latestPrice": 3245.16, "changeAmount": -12.35,
      "changePercent": -0.38, "tradeStatus": "OPEN",
      "dataDate": "2026-10-06", "dataTime": "10:30:00", "delayed": false }
  ]
} }
```

指数清单固定（顺序即展示顺序，见附录 C），由配置 `quote.indices.{market}` 定义，便于调整不刷库。

#### 2) 热门标的列表

`GET /api/quotes/hot?market=CN&limit=20`

`data` 结构同上（`stocks` 数组），每项多 `open / prevClose / high / low / volume / turnover`。名单来自 `quote_hot_list` 表（`enabled=1` 按 `sort_order` 排序，PRD Q1）；`limit` 上限 50。

#### 3) 搜索

`GET /api/quotes/search?keyword=gzmt&limit=10`

```json
{ "code": "SUCCESS", "msg": "ok", "data": { "keyword": "000001", "items": [
  { "secType": "stock", "symbol": "000001.SZ", "name": "平安银行", "market": "CN",
    "currency": "CNY", "status": "ACTIVE" },
  { "secType": "index", "symbol": "000001.SH", "name": "上证指数", "market": "CN",
    "currency": "CNY", "status": "ACTIVE" }
] } }
```

- 命中规则（PRD 4.3）：① 代码精确/前缀（含去掉后缀的纯数字/纯字母输入，`600519`、`00700`、`AAPL` 均可命中）；② 名称包含（`茅台`）；③ 拼音首字母前缀（`gzmt`，大小写不敏感）。三类结果合并，精确命中优先，同分按 market 稳定排序。
- 同号异实（PRD 5.2-5）：如上例，`000001` 同时返回股票与指数两条，`secType` 与完整 `symbol` 是前端跳转的唯一依据。
- 每项标注 `market` 与 `secType`（区分股票与指数，PRD 4.3）；`status: ACTIVE | DELISTED` 支撑 E4 标注。
- 不带实时价格（搜索联想不需要，减少上游依赖；价格由详情页 snapshot 提供）。keyword 空返回空数组；无结果返回空数组（E9 空态由前端渲染，保留已输入内容）。

#### 4) 单标的快照

`GET /api/quotes/stock/600519.SH/snapshot`

```json
{ "code": "SUCCESS", "msg": "ok", "data": {
  "secType": "stock", "symbol": "600519.SH", "name": "贵州茅台", "market": "CN",
  "currency": "CNY", "tradeStatus": "OPEN",
  "latestPrice": 1523.60, "changeAmount": 8.60, "changePercent": 0.57,
  "open": 1516.00, "prevClose": 1515.00, "high": 1533.98, "low": 1512.01,
  "volume": 28612, "turnover": 4356218952.00,
  "dataDate": "2026-10-06", "dataTime": "10:30:00", "delayed": false
} }
```

`volume` 单位：股（三市场统一，避免前端按市场换算手/股）；`turnover` 单位：元（原币种）。

#### 5) 分时

`GET /api/quotes/stock/600519.SH/trend`

```json
{ "code": "SUCCESS", "msg": "ok", "data": {
  "symbol": "600519.SH", "name": "贵州茅台", "market": "CN", "currency": "CNY",
  "tradeStatus": "LUNCH_BREAK", "prevClose": 1515.00,
  "timelineMinutes": 240, "delayed": false,
  "points": [
    { "minute": 0,   "time": "09:30", "price": 1516.00, "avgPrice": 1516.00, "volume": 123400 },
    { "minute": 151, "time": "13:00", "price": 1520.50, "avgPrice": 1518.22, "volume": 87600 }
  ],
  "dataDate": "2026-10-06", "dataTime": "11:35:00"
} }
```

- `minute` 为**折叠午休后的轴上偏移**：A股 0–239；港股上午段 0–149、下午段从 150 起连续编号（轴长 330）；美股 0–389。前端直接按 `minute` 落点，无需自行处理午休断轴（E2 由该字段 + `tradeStatus=LUNCH_BREAK` 共同表达）。
- `avgPrice` 为当日累计均价线（东财 trends2 原生返回）；`prevClose` 支撑昨收基准线与涨跌幅刻度。
- 休市时返回最近交易日全天数据（`tradeStatus=CLOSED/HOLIDAY` + `dataDate` 标注，E1）。

#### 6) 日 K

`GET /api/quotes/stock/600519.SH/kline?count=250`（`fq` 参数预留，首期固定 `qfq`，PRD 明确不做切换）

```json
{ "code": "SUCCESS", "msg": "ok", "data": {
  "symbol": "600519.SH", "name": "贵州茅台", "market": "CN", "currency": "CNY",
  "fq": "qfq", "barsTotal": 250, "tradeStatus": "CLOSED",
  "bars": [
    { "date": "2026-09-30", "open": 1508.00, "high": 1530.00, "low": 1501.00,
      "close": 1515.00, "volume": 31200, "turnover": 4712300000.00, "changePercent": 0.46 }
  ],
  "ma": { "ma5":  [1509.2, null], "ma10": [null], "ma20": [null], "ma60": [null] },
  "dataDate": "2026-09-30", "delayed": false
} }
```

- 默认 `count=250`（约一年）一次返回：首屏由前端展示最近约 120 根，缩放/拖动在已返回数据上进行（U3 无需翻页请求）；`count` 上限 500。
- `ma.ma5/ma10/ma20/ma60` 与 `bars` 按下标对齐，历史不足的窗口为 `null`（E5：缺失均线不绘制、不报错）。
- 后端拉取 `count+60` 根计算均线，避免前端自行补数据。

### 5.4 新增错误码（`common/ErrorCode` 追加）

| 错误码 | HTTP | 场景 |
|--------|------|------|
| `QUOTE_MARKET_INVALID` | 400 | market/type 取值非法 |
| `QUOTE_SYMBOL_INVALID` | 400 | symbol 格式非法 |
| `QUOTE_NOT_FOUND` | 404 | 标的不存在（主表无记录且上游无数据） |
| `QUOTE_UPSTREAM_FAILED` | 502 | 主备源均失败且无可用的 stale 缓存（E8：前端展示错误+重试） |

限流超限复用现有 `RATE_LIMIT_EXCEEDED`（429）。参数校验失败复用 `VALIDATION_ERROR`。

---

## 6. 交易时段与交易日历

### 6.1 日历表驱动（不做运行时规则判断）

`quote_trade_calendar` 为**每个市场、每个日期**预计算一行：是否交易日 + 各时段开收盘时刻（北京时间）。运行时（状态机、TTL 计算、预热任务）只查表，不写任何"周几/节假日/冬夏令时"判断逻辑。

### 6.2 状态机

`TradingSessionService.resolve(market, now, snapshot)`：

```text
日历日非交易日                → HOLIDAY
主表 status=DELISTED         → DELISTED（终止，不再看时间）
日历日 is_open=0（临时停市）  → HOLIDAY
now < 第一时段开盘            → PRE_OPEN（展示昨收与上个交易日数据，dataDate=上一交易日）
now ∈ 第一/第二时段           → OPEN；volume==0 且命中停牌标志 → SUSPENDED
第一时段后午休间隙（港股/CN）  → LUNCH_BREAK
now > 末时段收盘             → CLOSED
```

- 停牌识别：优先用东财快照的停牌标志位（字段在 M1 spike 中实测确认并固化到防腐层）；识别不到时退化为"交易日盘中 `volume==0` 且 `high==low==prevClose`"启发式，宁可标 SUSPENDED 也不误标 OPEN（E3）。
- `dataDate` 一律取上游返回的最新数据时间戳所属交易日，不本地推算，保证与图表数据一致。

### 6.3 日历生成任务（每日 03:00）

1. 拉取各市场基准指数（上证指数 / 恒生指数 / 道琼斯）**近 90 日 + 未来滚动窗口**的日 K 日期序列——日 K 有值的日期即交易日（自动涵盖法定节假日与临时休市）；
2. 对未来窗口，按 3.2 会话规则生成各时段开收盘（美股按冬/夏令时规则逐日计算）；
3. 写入/校准 `quote_trade_calendar`，保留 `source=MANUAL` 的人工行不被覆盖（应急修正通道）；
4. 未来窗口不足 60 天时自动向后扩展，保证日历永远有充足前瞻。

> 无未来数据可用时（指数日 K 只到今天），未来日期由规则推导生成，次日任务再校准——状态机对"规则推导 vs 实际"的误差不敏感（只影响休市日 PRE_OPEN/CLOSED 文案，次日自愈）。

---

## 7. 缓存与数据新鲜度

### 7.1 Redis 键与双 TTL

沿用 `RedisConfig` 的 JSON 序列化 RedisTemplate。每个行情缓存键维护 **fresh TTL**（新鲜窗口，直接返回）与 **stale TTL**（降级窗口，上游故障时返回并置 `delayed=true`，PRD 5.3"必须向用户明示"由此落地；超过 stale 窗口才走失败路径）。

| 键 | fresh TTL（盘中） | fresh TTL（休市） | stale 窗口 |
|----|------------------|-------------------|-----------|
| `quote:idx:{market}` 指数卡 | 15s | 至该市场下次开盘（≤12h） | 10min |
| `quote:hot:{market}` 热门列表 | 15s | 至下次开盘（≤12h） | 10min |
| `quote:snap:{type}:{symbol}` 快照 | 15s | 至下次开盘（≤12h） | 10min |
| `quote:trend:{type}:{symbol}` 分时 | 60s | 至下次开盘（≤12h） | 30min |
| `quote:kline:{type}:{symbol}:{count}` K线 | 5min | 1h | 12h |

- 休市 TTL 按 6.1 日历精确计算到下次开盘，避免休市时段无谓打上游（E1 的"正常展示"低成本化）。
- 缓存值内嵌 `fetchedAt`；`delayed = (now - fetchedAt) > fresh TTL` 时为 true——同时覆盖"上游延迟"与"降级读旧"两种情形。
- 交易状态变化的边界（如 11:30 收盘）不需要主动清缓存：快照里的 `tradeStatus` 是渲染时由状态机对 `fetchedAt 数据` 重新计算的，不缓存中间态。

### 7.2 缓存击穿防护

单实例部署（docker-compose 单 app），JVM 内 per-key single-flight（`ConcurrentHashMap<String, CompletableFuture>`）即可：并发 miss 只放一个请求去上游，其余等待结果。不引入 Redis 分布式锁。

### 7.3 盘中预热（满足"≤2 秒可读"）

非首次请求全部命中缓存；首次请求的上游耗时就成为首开耗时。因此增加预热任务：各市场**交易时段内**每 30 秒批量预取该市场的指数卡 + 热门名单（每市场一次批量接口，~25 个 symbol，上游压力可控），默认开启、可配置关闭。详情页为长尾，不做预热（由 7.2 single-flight + 用户到达后自然填充）。

---

## 8. 上游容错与降级

`QuoteProviderRouter` 的取数决策链（对每个上游调用生效）：

```text
1. fresh 缓存命中 → 直接返回
2. 主源 EastMoney：熔断器闭合 → 调用（超时 2s/4s，失败重试 1 次）
3. 主源失败 → 备源 Sina（仅快照类需求；trend/kline 跳过本步）
4. 仍失败 → stale 缓存可用 → 返回 stale，delayed=true
5. 仍不行 → 抛 QUOTE_UPSTREAM_FAILED（E8）
```

- **简易熔断**：每 provider 内存计数，连续 5 次失败熔断 30s（期间直接跳到下一步），半开恢复。防止上游故障时雪崩式重试拖垮自身线程池。
- 上游调用统一走独立 OkHttp 连接池 + 有界线程池，队列满快速失败，不占用 Tomcat 工作线程做长等待。
- `ulist` 批量快照对部分标的失败：整批成功才整批缓存；部分字段缺失按标的级剔除并记日志，不整批报错（热门列表部分残缺优于整体失败）。

---

## 9. 限流（防滥用）

PRD 七-5：行情接口公开只读、需限流。

- 实现：`QuoteRateLimitInterceptor`（注册进 `WebMvcConfig`，仅拦截 `/api/quotes/**`），Redis `INCR + EXPIRE` 固定窗口，key = `rate:quote:{ip}`，窗口 60s。
- 默认阈值：每 IP **60 次/分钟**（详情页首屏 2 请求 + 30s 刷新 2 请求 ≈ 6 次/分钟，正常使用余量充足；突发抓取场景被约束）。可配置，超限返回 429 + `RATE_LIMIT_EXCEEDED`。
- 取 IP 逻辑与现有日志/MDC 口径一致（X-Forwarded-For 首个值），防止代理后全部计入同一桶。
- 上游侧自我保护与对外限流是两层：本节是对外层；对上游的频控体现在 7.1 TTL + 7.3 预热的请求预算（预估稳态对东财 ≤ 8 req/min，远低于其公开页面的正常请求强度）。

---

## 10. 数据库设计

### 10.1 新增表（3 张，风格对齐 `db/init.sql`：utf8mb4 / InnoDB / created_at+updated_at）

```sql
-- 标的主表（搜索、退市标注、secid 映射的唯一事实源）
CREATE TABLE `quote_security` (
    `id` INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `symbol` VARCHAR(20) NOT NULL COMMENT '完整标识 code.SUFFIX，如 600519.SH、AAPL.US',
    `sec_type` TINYINT NOT NULL DEFAULT 1 COMMENT '类型：1 股票 2 指数',
    `market` VARCHAR(4) NOT NULL COMMENT '市场：CN/HK/US',
    `name` VARCHAR(64) NOT NULL COMMENT '证券名称',
    `pinyin_abbr` VARCHAR(40) DEFAULT NULL COMMENT '名称拼音首字母，大写，如 GZMT',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1 正常 2 已退市',
    `currency` VARCHAR(4) NOT NULL COMMENT '币种代码 CNY/HKD/USD',
    `upstream_secid` VARCHAR(24) NOT NULL COMMENT '主源内部标识，如 1.600519、116.00700',
    `missing_days` INT NOT NULL DEFAULT 0 COMMENT '连续同步缺失次数（退市判定）',
    `last_active_date` DATE DEFAULT NULL COMMENT '最近一次出现在上游列表的日期',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_symbol_type` (`symbol`, `sec_type`),
    KEY `idx_market_status` (`market`, `status`),
    KEY `idx_pinyin` (`pinyin_abbr`),
    KEY `idx_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='行情标的主表';

-- 交易日历（逐市场逐日预计算，运行时只查表）
CREATE TABLE `quote_trade_calendar` (
    `id` INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `market` VARCHAR(4) NOT NULL COMMENT '市场：CN/HK/US',
    `trade_date` DATE NOT NULL COMMENT '日期（北京时间）',
    `is_open` TINYINT NOT NULL DEFAULT 0 COMMENT '是否交易日：0 否 1 是',
    `session1_open` TIME DEFAULT NULL COMMENT '第一时段开盘（北京时间）',
    `session1_close` TIME DEFAULT NULL COMMENT '第一时段收盘',
    `session2_open` TIME DEFAULT NULL COMMENT '第二时段开盘，无则 NULL',
    `session2_close` TIME DEFAULT NULL COMMENT '第二时段收盘，收盘时刻早于开盘表示跨日（美股）',
    `source` VARCHAR(10) NOT NULL DEFAULT 'AUTO' COMMENT '来源：AUTO 推导 / MANUAL 人工（不被覆盖）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_market_date` (`market`, `trade_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='行情交易日历';

-- 热门标的名单（PRD Q1：后端可配置，首期人工圈定，月度更新）
CREATE TABLE `quote_hot_list` (
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
```

指数行也写入 `quote_security`（`sec_type=2`），使指数可被搜索（PRD 4.3"区分股票与指数"）并统一 secid 映射。

### 10.2 存量库迁移

`docker-compose` 只在数据卷首建时执行 `init.sql`，因此：

1. `init.sql` 追加上述 DDL + 种子（服务新环境）；
2. 另提供 `db/migration/v800-quote.sql`（幂等：`CREATE TABLE IF NOT EXISTS` + `INSERT IGNORE` 种子），存量环境执行一次——沿用仓库现有"无 flyway、迁移脚本手工执行"的现状，在部署说明中记录步骤。

### 10.3 种子数据

- `quote_security`：3 市场指数（附录 C）+ 热门名单涉及的 60 只股票（附录 B），均预写 `upstream_secid`，保证**冷启动即可用**，不等首次同步任务；
- `quote_hot_list`：附录 B 全量，`sort_order` 即展示顺序；
- `quote_trade_calendar`：启动时若为空，同步任务立即执行一次兜底（不等 03:00）。

### 10.4 搜索 SQL 策略

主表约 2 万行（A股 ~5.4k + 港股 ~2.7k + 美股 ~11k），以下查询无需额外索引优化即可个位数毫秒：

```sql
-- 三路合并（应用层去重排序）：
SELECT ... WHERE symbol LIKE CONCAT(UPPER(:kw), '%')          -- ① 代码前缀（含 600519 / AAPL 输入）
SELECT ... WHERE name LIKE CONCAT('%', :kw, '%')              -- ② 名称包含（茅台）
SELECT ... WHERE pinyin_abbr LIKE CONCAT(UPPER(:kw), '%')     -- ③ 拼音缩写前缀（gzmt）
```

拼音缩写在同步任务中预计算落库（新增轻量依赖 `TinyPinyin`，仅 ~200KB；Hutool `PinyinUtil` 需外接引擎，不另引入）。

---

## 11. 定时任务（@Scheduled，单实例无需分布式锁）

| 任务 | cron（Asia/Shanghai） | 内容 |
|------|----------------------|------|
| `TradeCalendarSyncJob` | `0 0 3 * * *` | 6.3 日历生成/校准，滚动未来 ≥60 天 |
| `SecurityMasterSyncJob` | `0 10 3 * * *` | 三市场证券列表全量分页 upsert（名称变更更新、拼音重算）；连续 3 次同步未出现 → `status=2 退市`（E4）；同步失败保留旧数据并告警日志 |
| `QuoteCacheWarmupJob` | fixedRate 30s（仅各市场交易时段内触发，可配置） | 7.3 预热指数卡 + 热门名单 |

新增依赖：`spring-boot-starter-aop`? 不需要。仅新增 `TinyPinyin` 一个依赖，其余全部复用现有 starter（web/security/redis/mybatis/validation）。

---

## 12. 代码组织与配置

### 12.1 包结构（对齐 `service/crawler/` 子包惯例）

```text
src/main/java/com/financial/news/
  controller/QuoteController.java
  service/quote/
    QuoteService.java                 # 门面，Controller 唯一依赖
    QuoteCacheService.java
    QuoteSearchService.java
    TradingSessionService.java
    provider/
      QuoteProvider.java              # SPI：getSnapshots / getTrend / getKline
      QuoteProviderRouter.java
      EastMoneyQuoteProvider.java
      SinaQuoteProvider.java
      model/                          # 领域模型 QuoteSnapshot/TrendPoint/KlineBar（上游无关）
    sync/
      TradeCalendarSyncJob.java
      SecurityMasterSyncJob.java
      QuoteCacheWarmupJob.java
  dto/response/quote/
    IndexListVO.java HotListVO.java QuoteSearchVO.java
    QuoteSnapshotVO.java QuoteTrendVO.java QuoteKlineVO.java
  entity/QuoteSecurity.java QuoteTradeCalendar.java QuoteHotList.java
  mapper/QuoteSecurityMapper.java QuoteTradeCalendarMapper.java QuoteHotListMapper.java
  config/QuoteProperties.java         # @ConfigurationProperties("quote")
  config/QuoteRateLimitInterceptor.java
resources/mapper/Quote*.xml           # SQL 与软删除口径显式书写（仓库惯例）
```

### 12.2 配置项（`application.yml` 新增 `quote:` 段，全部有默认值，生产经 `application-prod.yml` 环境变量覆盖）

```yaml
quote:
  provider:
    primary: eastmoney        # eastmoney | sina
    connect-timeout-ms: 2000
    read-timeout-ms: 4000
  cache:
    snapshot-fresh-seconds: 15
    trend-fresh-seconds: 60
    kline-fresh-seconds: 300
    warmup-enabled: true
  rate-limit:
    enabled: true
    window-seconds: 60
    max-requests: 60
  sync:
    calendar-cron: "0 0 3 * * *"
    security-cron: "0 10 3 * * *"
  indices:                    # 指数卡清单与顺序（附录 C）
    CN: [ "000001.SH", "399001.SZ", "399006.SZ", "000300.SH" ]
    US: [ "DJI.US", "IXIC.US", "SPX.US" ]
    HK: [ "HSI.HK", "HSTECH.HK" ]
```

### 12.3 现有文件改动点

| 文件 | 改动 |
|------|------|
| `config/SecurityConfig.java:54-58` | 追加 `.requestMatchers(HttpMethod.GET, "/api/quotes/**").permitAll()` |
| `config/WebMvcConfig.java` | 注册 `QuoteRateLimitInterceptor`（`addInterceptors`） |
| `common/ErrorCode.java` | 追加 4 个 QUOTE_* 错误码 |
| `db/init.sql` + `db/migration/v800-quote.sql` | DDL 与种子（10.2/10.3） |
| `pom.xml` | 新增 `TinyPinyin` 依赖 |

---

## 13. 边界场景对照（PRD 第六章 → 后端行为）

| # | 场景 | 后端行为 |
|---|------|---------|
| E1 | 休市访问 | 日历判定 → `tradeStatus=CLOSED/HOLIDAY`；快照/分时返回最近交易日数据，`dataDate` 标注；休市缓存长 TTL；不报错 |
| E2 | 港股午间休市 | 状态机 `LUNCH_BREAK`；分时点带折叠 `minute` 偏移，上午数据完整返回 |
| E3 | 停牌 | `tradeStatus=SUSPENDED` + 停牌前最后数据（快照字段仍返回最近成交数据） |
| E4 | 退市标的 | 主表 `status=2`，搜索返回 `status=DELISTED`；快照 `tradeStatus=DELISTED`、价格字段 null；K 线正常返回 |
| E5 | 新股/新指数历史不足 | MA 数组按窗口补 `null`，bars 有多少返回多少，不报错 |
| E6 | 除权除息 | K 线固定 `fqt=1` 前复权，走势连续 |
| E7 | 单市场失败 | 接口按市场/按标的分段，前端分段请求；单段失败仅该段 5xx，其他段不受影响 |
| E8 | 行情整体不可用 | 路由链穷尽后 `QUOTE_UPSTREAM_FAILED(502)`，前端展示重试入口 |
| E9 | 搜索无结果 | 返回空数组（HTTP 200），空态语义交由前端 |

---

## 14. 里程碑与验收对照

### 14.1 里程碑

| 阶段 | 内容 | 出口标准 |
|------|------|---------|
| M1 数据接入 spike（约 2 天） | OkHttp 客户端 + `EastMoneyQuoteProvider`（快照/分时/K线/列表）实拉三市场数据；**实测确认停牌标志位、美股 secid 段、指数 secid**；Sina 备源快照 | 三市场样例标的（茅台/腾讯/AAPL/上证/恒指/DJI）数据可拉取且与主流行情软件核对一致（±0.01） |
| M2 存储 + 基础 API | DDL/种子、三张表 mapper、`QuoteService` 门面、indices/hot/search/snapshot 四端点、SecurityConfig/限流 | 验收标准 1/2/3/9 可过；限流 429 生效 |
| M3 日历 + 分时/K线 + 状态机 | 日历任务、状态机、trend/kline 端点、双 TTL 缓存 + delayed 标注 | 验收标准 5/6/7/10 可过；休市/午休/停牌/退市场景符合第 13 章表 |
| M4 容错与联调 | 备源路由、熔断、预热任务、错误码联调、Knife4j 契约冻结、前端联调支持 | 验收标准 8/10 可过；E1–E9 全场景演练通过 |

### 14.2 PRD 验收标准 → 后端职责映射

| 验收项 | 后端支撑 |
|--------|---------|
| Tab 可访问、未登录可浏览 | SecurityConfig 白名单 + 全 GET 公开 |
| 三市场切换、红涨绿跌着色 | 指数卡/热门按 market 分段接口；`changeAmount/changePercent` 数值口径统一，着色由前端执行 |
| 搜索四类输入命中、标注市场 | 10.4 三路检索 + `market/secType/status` 字段 |
| 币种符号、数值与主流软件一致 | `currency` 字段 + M1 核对（±0.01 容差由数据源精度保证） |
| 分时四要素、三市场点数 | trend 端点 `points/prevClose/avgPrice` + `timelineMinutes` 240/330/390 |
| 日 K 缩放拖动、取值完整、移动端不遮挡 | kline 端点 250 根一次返回 + MA 对齐数组（交互属前端） |
| 休市文案与数据日期、停牌标识 | `tradeStatus` 七态 + `dataDate` |
| 亮暗主题 | 后端无涉 |
| 单市场失败隔离、整体失败重试 | 分段接口 + `QUOTE_UPSTREAM_FAILED` 语义 |
| 数据仅供参考声明、延迟提示 | 声明为前端静态文案；`delayed` 字段支撑延迟提示 |

---

## 15. 风险与后续演进

| 风险 | 影响 | 缓解 |
|------|------|------|
| 东财接口为非官方契约 | 字段/地址变更导致断数 | 防腐层单点收口 + 备源 + `delayed` 明示 + 熔断；provider 可整体替换为付费源 |
| 上游频控未知 | 同步任务或预热被限 | 预热仅批量 1 req/30s/市场；同步每日一次；M1 spike 实测确认安全频率 |
| 美股 secid 市场段映射 | 美股标的寻址失败 | 主表落库 `upstream_secid`，同步任务兜底重算；M1 实测 |
| 退市判定滞后（3 天） | 退市初期仍显示 ACTIVE | 可接受（PRD 仅要求可搜索、可看历史 K 线）；必要时引入东财风险板块列表加速 |
| 指数日 K 推导日历对未来临时休市不敏感 | 休市日误判为交易日 | 状态机次日自愈 + `source=MANUAL` 人工修正通道 |
| 单实例 @Scheduled | 未来多实例部署会重复跑任务 | 任务均幂等（upsert/覆盖写）；多实例时引入 ShedLock，不在本期 |

后续演进（本期不做，仅预留）：`QuoteProvider` 接入付费源；`/api/quotes/ws` WebSocket 推送；新闻×行情联动所需的"正文抽取标的 → symbol"服务复用本方案的主表与搜索能力。

---

## 附录 A：东方财富上游接口速查（provider 内部实现参考）

以下均为非官方接口，字段以 M1 spike 实测为准；此处仅记录方案设计时的已知形态。

1. **批量快照** `GET https://push2.eastmoney.com/api/qt/ulist.np/get`
   参数：`fltt=2&invt=2&secids=1.600519,116.00700&fields=f2,f3,f4,f5,f6,f12,f13,f14,f15,f16,f17,f18,f124`
   关键字段：`f2` 最新价、`f3` 涨跌幅、`f4` 涨跌额、`f5` 成交量、`f6` 成交额、`f12/f13/f14` 代码/市场/名称、`f15/f16/f17/f18` 高/低/开/昨收、`f124` 更新时间戳。
2. **分时** `GET https://push2his.eastmoney.com/api/qt/stock/trends2/get`
   参数：`secid=1.600519&ndays=1&iscr=0&fields1=f1,f2,f3,f7,f8&fields2=f51,f52,f53,f56,f58`
   `f51` 时间、`f53` 价、`f56` 量、`f58` 均价；`data.preClose` 昨收。
3. **日 K** `GET https://push2his.eastmoney.com/api/qt/stock/kline/get`
   参数：`secid=1.600519&klt=101&fqt=1&lmt=310&end=20500101&fields1=f1,f2,f3&fields2=f51,f52,f53,f54,f55,f56,f57,f61`
   `f51` 日期、`f52–f55` 开收高低、`f56` 量、`f57` 额、`f61` 换手；`fqt=1` 前复权。
4. **证券列表（同步用）** `GET https://push2.eastmoney.com/api/qt/clist/get`
   参数：`pn={页}&pz=200&po=1&np=1&fltt=2&invt=2&fid=f12&fs={板块}&fields=f12,f13,f14`
   板块 fs：深主板 `m:0+t:6`、创业板 `m:0+t:80`、沪主板 `m:1+t:2`、科创板 `m:1+t:23`、港股 `m:116`、美股 `m:105,m:106,m:107`。
5. **备源快照** `GET https://hq.sinajs.cn/list=sh600519,sz000001,rt_hk00700,gb_aapl`
   需请求头 `Referer: https://finance.sina.com.cn`；返回分隔符文本，解析后映射到领域模型（仅快照）。

## 附录 B：热门标的种子名单（首期人工圈定，月度复审）

- **CN（20）**：贵州茅台 600519.SH、宁德时代 300750.SZ、比亚迪 002594.SZ、中国平安 601318.SH、招商银行 600036.SH、五粮液 000858.SZ、东方财富 300059.SZ、中信证券 600030.SH、长江电力 600900.SH、紫金矿业 601899.SH、美的集团 000333.SZ、格力电器 000651.SZ、恒瑞医药 600276.SH、隆基绿能 601012.SH、万华化学 600309.SH、兴业银行 601166.SH、京东方A 000725.SZ、中芯国际 688981.SH、海康威视 002415.SZ、药明康德 603259.SH
- **US（20）**：AAPL、MSFT、NVDA、GOOGL、AMZN、META、TSLA、AVGO、LLY、JPM、V、XOM、UNH、MA、PG、JNJ、HD、WMT、ORCL、CRM（均 .US）
- **HK（20）**：腾讯控股 00700.HK、阿里巴巴-W 09988.HK、美团-W 03690.HK、京东集团-SW 09618.HK、中国移动 00941.HK、比亚迪股份 01211.HK、小米集团-W 01810.HK、中国平安 02318.HK、香港交易所 00388.HK、汇丰控股 00005.HK、友邦保险 01299.HK、建设银行 00939.HK、工商银行 01398.HK、中国银行 03988.HK、招商银行 03968.HK、中国海洋石油 00883.HK、中国石油股份 00857.HK、网易-S 09999.HK、快手-W 01024.HK、理想汽车-W 02015.HK

## 附录 C：指数清单（secType=index，展示顺序即数组顺序）

| 市场 | 名称 | symbol | upstream_secid |
|------|------|--------|----------------|
| CN | 上证指数 | 000001.SH | 1.000001 |
| CN | 深证成指 | 399001.SZ | 0.399001 |
| CN | 创业板指 | 399006.SZ | 0.399006 |
| CN | 沪深300 | 000300.SH | 1.000300 |
| US | 道琼斯 | DJI.US | 100.DJIA |
| US | 纳斯达克 | IXIC.US | 100.NDX |
| US | 标普500 | SPX.US | 100.SPX |
| HK | 恒生指数 | HSI.HK | 100.HSI |
| HK | 恒生科技 | HSTECH.HK | 100.HSTECH |

---

## 16. 实现备注（冒烟验证后修订，2026-10-07）

实现与冒烟（本地一次性 MySQL+Redis+东财 mock 全链路实测）对第 1–15 章的修订与补充：

1. **上游地址可配置**（§12.2 补充）：`quote.provider.east-money-base-url / east-money-his-base-url / sina-base-url`，默认值为生产地址；联调/测试可指向本地 mock（本次冒烟即以此方式覆盖了真实上游被封的场景）。
2. **快照部分覆盖合并**（§8 补充）：Router 对快照类请求不再"首个源成功即返回"，而是主源成功后，对**未覆盖的标的**继续用备源补齐合并——部分数据优于整体失败。分时/K线仍为主源-only + stale 降级。
3. **delayed 判定补充**（§7.1 补充）：除"盘中降级读旧缓存"外，盘中**数据自身时间戳明显滞后**（超过 max(60s, 2×fresh TTL)）同样置 `delayed=true`——上游可连但返回陈旧数据是"数据延迟"的另一种形态。
4. **美股 dataDate 语义**（§5.3 补充）：分时归属交易日取**首点日期**——美股跨日时段（北京时间 21:30–次日 04:00）的交易日是开盘当日；状态机在跨午夜后需同时检查**昨日时段窗口**是否延续到今天凌晨，否则下半场会被误判为 PRE_OPEN。
5. **MA/涨跌幅计算口径**（§5.3-6 补充）：后端拉取 `count+60` 根后，均线与逐根涨跌幅在**全量历史**上计算、再按输出窗口对齐截取——保证输出首根的 MA60 与涨跌幅可用（E5 的 null 仅在上游真实历史不足时出现）。
6. **新浪备源实测怪癖**（附录 A 补充）：新浪 CN 快照中沪市指数（sh000xxx）成交量单位是"手"需 ×100，深市指数（sz399xxx）与个股是"股"；美股 `gb_` 昨收字段可能返回 0（置 null 处理）；响应为 GBK 编码、必须带 Referer。
7. **东财 ulist 响应结构**：业务数据在 `data.diff` 下（本次实现曾因漏下钻 `data` 节点导致主源快照恒空、静默降级备源，被 mock 全链路测试捕获——免费上游契约以防腐层+mock 回归保护是硬要求）。
8. **量纲契约**：对外 volume 统一"股"（东财沪深 f5/f56/f56(trend) 为"手"，provider 内 ×100；指数同样处理）。
9. 里程碑 M1 spike 确认项落位：恒生科技 secid = `124.HSTECH`（suggest 接口权威 QuoteID，运行期冒烟二次确认）；停牌识别采用启发式（上游标志位字段待正式接入时补充确认）。
