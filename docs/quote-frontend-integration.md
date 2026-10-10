# 行情功能前端对接文档

> 对应后端实现：`financial-news_api` 仓库 `feat-v800` 分支（行情模块）。
> 架构与接口设计背景见《[quote-backend-design.md](./quote-backend-design.md)》；本文档是前端（`financial-news` 仓库）开发与联调的**接口契约 + 行为规格**，以后端实际代码与 Knife4j 在线文档为准。
> 更新时间：2026-10-07。

---

## 1. 快速开始

- **鉴权**：行情接口全部公开只读，**无需登录、无需携带任何 Header**（未登录可完整浏览，PRD 4.2）。
- **CORS**：后端已全开放（`allowedOriginPatterns=*` + 凭据），本地 dev 直连即可。
- **在线接口文档**（Knife4j，可在线调试）：`http://{host}:3000/doc.html` → 分组"行情模块"。
- **路径前缀**：所有行情接口挂在 `/api/quotes/**` 下。前端现有 axios 实例 `baseURL` 已是 `/api`（`src/api/request.ts`），因此业务代码中调用路径**不带** `/api`：

```ts
import { get } from '@/api/request'
// GET /api/quotes/indices?market=CN
const data = await get<IndexListVO>('/quotes/indices', { params: { market: 'CN' } })
```

- **响应壳**：`{ code: string, msg: string, data: T }`，成功 `code === "200"`，现有响应拦截器会自动解包 `data`，无需处理。

---

## 2. 全局约定（先读这节，后面接口字段都依赖它）

### 2.1 错误处理 —— 一个与现有代码不同的关键点

行情接口的**业务错误返回非 200 的 HTTP 状态码**（400/404/429/502），响应体仍是 `{code, msg, data}`。而现有 `request.ts` 的响应拦截器只在 HTTP 200 时检查业务码，**非 200 会直接进 axios error 分支**（目前只特判了 401）。因此行情模块需要在 API 层统一归一化：

```ts
// 建议：api/quote.ts 里包一层
import axios from 'axios'
import { get, ApiError } from '@/api/request'
import type { QuoteApiError } from '@/types/quote'

export async function quoteGet<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  try {
    return await get<T>(url, { params })
  } catch (e) {
    if (axios.isAxiosError(e) && e.response?.data?.code) {
      throw new ApiError(e.response.data.code, e.response.data.msg ?? '行情服务暂不可用')
    }
    throw e
  }
}
```

| HTTP | code | 含义 | 前端动作 |
|------|------|------|---------|
| 400 | `QUOTE_MARKET_INVALID` / `QUOTE_SYMBOL_INVALID` | 参数非法 | 正常使用中不应出现（标识一律来自搜索/列表返回值）；出现按 bug 处理 |
| 404 | `QUOTE_NOT_FOUND` | 标的不存在 | 详情页展示"未找到该标的" |
| 429 | `RATE_LIMIT_EXCEEDED` | 触发限流 | **停止轮询 ≥60s**，Toast 提示"请求过于频繁"；见 §7 限流预算 |
| 502 | `QUOTE_UPSTREAM_FAILED` | 行情上游不可用 | **仅当前分段**展示错误+重试按钮（E7/E8），不影响页面其他分段 |
| 200 | 非 `200` 的业务码 | — | 行情模块正常使用中不会出现 |

### 2.2 市场与标的标识（硬性红线）

- `market`：`CN`（A股）/ `HK`（港股）/ `US`（美股），入参大小写不敏感，响应恒为大写。
- `secType`：`stock`（股票）/ `index`（指数）。
- `symbol`：`代码.后缀`，如 `600519.SH`、`000001.SZ`、`00700.HK`、`AAPL.US`、`HSI.HK`、`DJI.US`。
- **同号异实（PRD 5.2-5）**：`000001` 既是上证指数（`000001.SH` index）又是平安银行（`000001.SZ` stock）。**跳转详情页必须携带完整的 `symbol + secType`**（详情接口路径里就含 `type` 段），任何地方不得只按数字代码跳转。搜索结果里这两条会同时返回，靠 `secType` 区分。
- 指数代码可能不是数字：港股指数 `HSI.HK / HSTECH.HK`，美股指数 `DJI.US / IXIC.US / SPX.US`。
- 入参 symbol 大小写均可（后端会转大写），但路由/存储请使用接口返回的原始值。

### 2.3 币种、精度与红涨绿跌

- `currency` → 符号映射（前端持有）：`CNY → ¥`、`HKD → HK$`、`USD → $`。
- 价格展示精度：**A股/美股 2 位小数**；**港股按数据原样展示**（最多 3 位、仙股 4 位，接口给的数值本身就带这些小数，别强转 2 位）。
- `changeAmount` / `changePercent` 已由后端**统一四舍五入 2 位小数**，直接展示即可；正负号建议前端渲染（`+0.57%`）。
- **红涨绿跌**（PRD 5.2-1，前端职责）：`changePercent > 0` 红、`< 0` 绿、`== 0` 灰，三市场一致。K 线阴阳判定用 `close > open`（前复权后仍正确）。
- 数值可能出现科学计数法序列化（如 `4.797246636E9`），`JSON.parse`/axios 会正常转成 number，不要按字符串处理。

### 2.4 量纲

- `volume`：**股**（三市场统一，后端已归一化，前端不要再换算手/股）。
- `turnover`：成交额，**原币种元**（CNY 元 / HKD 元 / USD 元）。

### 2.5 限流预算（防止 429）

后端对所有 `/api/quotes/**` 限流：**每 IP 60 次/分钟**。按推荐的轮询策略（§7），最坏用量约 15 次/分钟，余量充足。注意搜索联想要做 **300ms 防抖**，且键盘上下选择**不重新请求**（对已返回结果本地过滤/移动高亮）。

---

## 3. 接口明细

### 3.1 指数卡片

`GET /quotes/indices?market={CN|US|HK}`

Tab 页每个市场的指数卡（数据源：后端配置的固定指数清单，顺序即展示顺序，**前端按返回顺序渲染，不要自己排序**）。

响应 `IndexListVO`：

| 字段 | 类型 | 说明 |
|------|------|------|
| `market` | string | `CN/HK/US` |
| `delayed` | boolean | 任一指数延迟即为 true（= items 内 delayed 的 OR） |
| `indices` | QuoteSnapshotVO[] | 指数快照数组，卡片所需字段都在内 |

示例（真实响应，A股休市时段）：

```json
{ "market": "CN", "delayed": false, "indices": [
  { "secType": "index", "symbol": "000001.SH", "name": "上证指数", "market": "CN", "currency": "CNY",
    "tradeStatus": "CLOSED", "dataDate": "2026-09-30", "dataTime": "15:00:03", "delayed": false,
    "latestPrice": 3842.19, "changeAmount": 11.74, "changePercent": 0.31,
    "open": 3839.25, "prevClose": 3830.45, "high": 3851.22, "low": 3833.09,
    "volume": 41456024700, "turnover": 679398992444.8 }
] }
```

卡片只展示：`name`、`latestPrice`、`changePercent`（+ `changeAmount` 可选），点击整卡 → 指数详情页（`/quotes/index/{symbol}`）。

### 3.2 热门标的列表

`GET /quotes/hot?market={CN|US|HK}&limit=20`

`limit` 1–50，缺省 20（后端配置名单约 20 只/市场）。响应 `HotListVO`：

| 字段 | 类型 | 说明 |
|------|------|------|
| `market` | string | 同上 |
| `delayed` | boolean | 同上 |
| `stocks` | QuoteSnapshotVO[] | 按 `quote_hot_list` 配置顺序返回 |

每行展示：`name`、`symbol`、`latestPrice`、`changePercent`；**点击整行**进详情页（`/quotes/stock/{symbol}`）。

### 3.3 标的搜索（三市场混合联想）

`GET /quotes/search?keyword={kw}&limit=10`

- `limit` 1–30，缺省 10。keyword 空 / 无结果 → HTTP 200 + `items: []`（**空态由前端渲染，且保留已输入关键词**，E9）。
- 输入即联想：300ms 防抖；键盘 ↑↓ 在当前结果内移动（不重发请求），回车进详情；移动端直接点选（PRD 4.3）。

响应 `QuoteSearchVO`：

```json
{ "keyword": "000001", "items": [
  { "secType": "stock", "symbol": "000001.SZ", "name": "平安银行",  "market": "CN", "currency": "CNY", "status": "ACTIVE" },
  { "secType": "index", "symbol": "000001.SH", "name": "上证指数",  "market": "CN", "currency": "CNY", "status": "ACTIVE" }
] }
```

| 字段 | 说明 |
|------|------|
| `market` | 结果项标注所属市场，**列表中需展示市场徽标** |
| `secType` | 股票/指数区分，**详情跳转必须连同此值** |
| `status` | `ACTIVE` / `DELISTED`；DELISTED 展示「已退市」标签（E4） |

命中规则（后端已实现，前端无需处理）：代码精确/前缀（`600519`、`00700`、`AAPL`）、名称包含（`茅台`）、拼音首字母（`gzmt`，大小写不敏感）。搜索接口**不含实时价格**，价格由详情页快照提供。

### 3.4 单标的实时快照（详情页报价区）

`GET /quotes/{type}/{symbol}/snapshot`，例：`/quotes/stock/600519.SH/snapshot`

响应 `QuoteSnapshotVO`（字段与 3.1 数组项完全一致，另见 §4 状态语义）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `secType` / `symbol` / `name` / `market` / `currency` | string | 标的标识与展示信息 |
| `tradeStatus` | string | 交易状态，见 §4 对照表 |
| `dataDate` / `dataTime` | string | 数据归属交易日 `yyyy-MM-dd` / 数据时间 `HH:mm:ss` |
| `delayed` | boolean | **true 时必须展示"行情数据延迟"提示**（PRD 5.3） |
| `latestPrice` | number? | 最新价；**退市/无成交为 null**，前端判空展示"—" |
| `changeAmount` / `changePercent` | number? | 涨跌额/涨跌幅（2 位小数） |
| `open` / `prevClose` / `high` / `low` | number? | 今开/昨收/最高/最低 |
| `volume` | number? | 成交量（股） |
| `turnover` | number? | 成交额（原币种元；美股指数可能为 null） |

### 3.5 当日分时（详情页默认图）

`GET /quotes/{type}/{symbol}/trend`

响应 `QuoteTrendVO`：

| 字段 | 类型 | 说明 |
|------|------|------|
| `prevClose` | number? | 昨收基准线取值 |
| `timelineMinutes` | number | 分时轴总分钟数：CN 240 / HK 330 / US 390 |
| `points` | TrendPointVO[] | 分钟点数组 |

`TrendPointVO`：`{ minute, time, price, avgPrice, volume }`

**minute 轴约定（重要）**：

- `minute` 是**折叠午休后的轴上偏移**，落在 `[0, timelineMinutes]`：起点（开盘）= 0，收盘最后一根 = `timelineMinutes`。午休边界：CN `11:30=120 → 13:00/13:01=121`，HK `12:00=150 → 13:00/13:01=151`，美股无午休、跨午夜连续（`21:30=0 → 次日 04:00=390`）。
- 图表 x 轴按 `[0, timelineMinutes]` 布局：`price` 线 + `avgPrice` 均价线 + `prevClose` 水平虚线基准，下半区 `volume` 柱状；涨跌幅刻度参照 = `(price - prevClose) / prevClose`（PRD 4.2）。
- `time`（`HH:mm`，北京时间）直接用于轴刻度与十字线取值显示，不要用 minute 反推时间（美股跨日反推必错）。
- 盘中未到收盘时，points 只到当前分钟；休市时返回**最近交易日全天**数据（`tradeStatus=CLOSED/HOLIDAY` + `dataDate` 标注，E1）。
- 港股午休（E2）：`tradeStatus=LUNCH_BREAK`，points 保留上午段（minute 到 ~150），下午开盘后继续追加。
- 退市标的 trend 返回空 points——**图区展示"已退市，仅可查看历史 K 线"说明**，默认切到 K 线页签（E4）。

### 3.6 日 K（前复权，固定）

`GET /quotes/{type}/{symbol}/kline?count=250`

- `count` 20–500，缺省 250（约一年）。**约定：一次拉 250 根，前端首屏展示最近约 120 根，缩放/拖动在本地数据上做（dataZoom），不翻页请求**（U3）。
- 复权固定前复权（`fq` 恒为 `"qfq"`，本期无切换，PRD 明确不做）。

响应 `QuoteKlineVO`：

| 字段 | 类型 | 说明 |
|------|------|------|
| `fq` | string | 固定 `"qfq"` |
| `bars` | KlineBarVO[] | 按日期升序 |
| `ma` | `{ ma5, ma10, ma20, ma60 }` | 均线数组，**与 bars 下标一一对齐** |

`KlineBarVO`：`{ date, open, high, low, close, volume, turnover, changePercent }`

- `date`：`yyyy-MM-dd`。
- `changePercent`：相对前一根收盘的涨跌幅（首根也有值，后端用更早的历史计算）。
- **MA 数组中的 `null` 表示该位置历史不足（新股/新指数，E5）——前端跳过不绘制，页面不报错**。后端已多取 60 根预计算，只要上游历史足够，输出首根的 MA 就有值。
- 十字线取值：日期/开高低收/涨跌幅/成交量/均线值都在本地数据里，纯前端交互；移动端信息浮层注意避开手指操作区（PRD 4.2）。

---

## 4. tradeStatus 状态 ↔ 页面表现对照（E1–E4 落地）

| tradeStatus | 语义 | 前端表现 |
|-------------|------|---------|
| `OPEN` | 交易中 | 报价区「交易中」；启用 30s 轮询 |
| `PRE_OPEN` | 交易日开盘前 | 「未开盘」；展示上一交易日数据与 `dataDate` |
| `LUNCH_BREAK` | 午间休市（CN/HK） | 「午间休市」；分时保留上午数据（points 已表达） |
| `CLOSED` | 已收盘 | 「已收盘」+ 数据日期（`dataDate`）；展示最近交易日数据 |
| `HOLIDAY` | 周末/节假日/临时休市 | 「休市」+ 数据日期 |
| `SUSPENDED` | 停牌 | 报价区「停牌」标识；图区说明"停牌期间无最新行情"；数据为停牌前最后数据 |
| `DELISTED` | 已退市 | 「已退市」标识；价格字段为 null（展示"—"）；无分时；**默认切到日 K 页签**，仅历史 K 线可看 |

通用规则：**只要 `tradeStatus !== 'OPEN'`，报价区/图区必须同时展示 `dataDate`（数据日期）**，避免把最近交易日数据误读为实时（PRD 5.2-3）。遇到未知状态值按 `CLOSED` 处理（向前兼容）。

## 5. delayed 与数据声明

- `delayed === true`（快照/列表任意层级）：页面显著位置展示提示条，如「**行情数据延迟，仅供参考**」，不静默（PRD 5.3）。
- 每个行情页面固定声明：**「数据仅供参考，不构成投资建议」**（PRD 5.2-8，前端静态文案）。
- 列表接口顶层 `delayed` = 各项的 OR，可直接驱动列表头提示条。

## 6. 类型定义（可直接放入 `src/types/quote.ts`）

```ts
export type QuoteMarket = 'CN' | 'HK' | 'US'
export type QuoteSecType = 'stock' | 'index'
export type TradeStatus =
  | 'PRE_OPEN' | 'OPEN' | 'LUNCH_BREAK'
  | 'CLOSED' | 'HOLIDAY' | 'SUSPENDED' | 'DELISTED'

export interface QuoteBaseVO {
  secType: QuoteSecType
  symbol: string
  name: string
  market: QuoteMarket
  currency: 'CNY' | 'HKD' | 'USD'
  tradeStatus: TradeStatus
  dataDate: string          // yyyy-MM-dd
  dataTime: string          // HH:mm:ss
  delayed: boolean
}

export interface QuoteSnapshotVO extends QuoteBaseVO {
  latestPrice: number | null
  changeAmount: number | null
  changePercent: number | null
  open: number | null
  prevClose: number | null
  high: number | null
  low: number | null
  volume: number | null     // 股
  turnover: number | null   // 原币种元
}

export interface IndexListVO { market: QuoteMarket; delayed: boolean; indices: QuoteSnapshotVO[] }
export interface HotListVO { market: QuoteMarket; delayed: boolean; stocks: QuoteSnapshotVO[] }

export interface QuoteSearchItemVO {
  secType: QuoteSecType
  symbol: string
  name: string
  market: QuoteMarket
  currency: QuoteBaseVO['currency']
  status: 'ACTIVE' | 'DELISTED'
}
export interface QuoteSearchVO { keyword: string; items: QuoteSearchItemVO[] }

export interface TrendPointVO {
  minute: number
  time: string      // HH:mm
  price: number | null
  avgPrice: number | null
  volume: number | null
}
export interface QuoteTrendVO extends QuoteBaseVO {
  prevClose: number | null
  timelineMinutes: number
  points: TrendPointVO[]
}

export interface KlineBarVO {
  date: string
  open: number; high: number; low: number; close: number
  volume: number | null
  turnover: number | null
  changePercent: number | null
}
export interface QuoteKlineVO extends QuoteBaseVO {
  fq: 'qfq'
  bars: KlineBarVO[]
  ma: { ma5: (number | null)[]; ma10: (number | null)[]; ma20: (number | null)[]; ma60: (number | null)[] }
}
```

## 7. 刷新策略建议（PRD 4.2 的前端实现口径）

| 场景 | 行为 |
|------|------|
| Tab 页 | 首次切到某市场才加载该市场（`indices` + `hot` 两请求并行）；之后每 30s 轮询**当前所在市场**；已加载过的市场切回不重新请求（内存缓存 + 时间戳） |
| 详情页首屏 | `snapshot` + `trend` 并行；切到日 K 页签时才请求 `kline` |
| 详情页轮询 | `tradeStatus === 'OPEN'` 时每 30s 轮询 snapshot+trend；非 OPEN 状态**停止轮询**（数据不会变化，且命中后端长缓存无意义） |
| 页面切后台 | `visibilitychange` → hidden 时清掉定时器；回到前台**立即刷一次**再恢复定时器（PRD 4.2） |
| 限流退避 | 收到 429：清除定时器，60s 后恢复轮询，Toast 提示一次即可 |

轮询预算：Tab 页 4 次/分钟 + 详情页 4 次/分钟 + 搜索防抖后 ~10 次/分钟 ≪ 60 次/分钟上限。

**市场记忆**：用户最后所在市场由前端 `localStorage` 记忆（`quote.lastMarket`），后端无会话状态。

**失败隔离（E7）**：Tab 页三个市场分段、详情页三段数据（报价/分时/K线）是**独立请求**——某一段失败只在该段渲染错误+重试按钮，重试只重发该段请求；全部失败（E8）时页面框架仍在，提供整体重试入口。

## 8. 联调与自查

**本地联调**：后端 `mvn spring-boot:run`（默认 3000 端口）；前端 `VITE_API_BASE_URL=http://127.0.0.1:3000/api`（或走代理）。行情表会在后端启动时通过幂等迁移自动初始化；`db/migration/v800-quote.sql` 也可用于手工排障。

**验收自查清单（PRD 第八章中前端职责项）**：

- [ ] 底部导航第 2 位新增「行情」，桌面端顶部导航同步（前端）
- [ ] 未登录可访问全部行情页面（接口本就公开）
- [ ] 三市场切换正常；红涨绿跌着色正确、平盘灰（§2.3）
- [ ] 搜索五类输入命中 + 结果标注市场/类型（§3.3）
- [ ] 币种符号正确（¥/HK$/$）；精度：A股美股 2 位、港股原样（§2.3）
- [ ] 分时四要素齐全（价格线/均价线/昨收虚线/量柱）；x 轴按 `timelineMinutes`（§3.5）
- [ ] 日 K 默认 120 根、可缩放拖动、MA null 不绘制、移动端取值浮层不遮挡（§3.6）
- [ ] 休市展示状态文案 + 数据日期；停牌/退市标识与降级视图（§4）
- [ ] 亮/暗主题下图表配色正常（纯前端）
- [ ] 单市场失败不影响其他市场分段；整体失败有重试（§7）
- [ ] 「数据仅供参考，不构成投资建议」声明 + delayed 提示条（§5）

**图表实现提示**（非约束）：ECharts 足够覆盖——K 线用 `candlestick` + `dataZoom`（inside+slider，`start/end` 控制首屏 120 根），MA 用 series line、null 值传 `null` 即自动断线；分时用折线 + `markLine`（昨收）+ 双 y 轴（价格/涨跌幅刻度）+ 底部 bar series；量价联动用 `axisPointer` + `tooltip`。

---

## 9. 已知边界（联调时可能遇到）

1. **A股国庆等长假**：休市多日时 `dataDate` 会停在长假前最后一个交易日，属正常（E1），不要当作 bug。
2. **美股 `prevClose/turnover` 偶发 null**：备源新浪的字段缺失所致（仅在主源降级时出现），前端判空展示"—"即可。
3. **搜索 `000001` 只返回一条**：后台启动即同步股票主表，失败市场每5分钟重试，同时每日凌晨全量更新；上游不可用时部分标的可能不全。分页列表的 `syncComplete` 可判断本进程是否已成功同步；同步完成后 `000001` 会同时返回平安银行（stock）与上证指数（index）。
4. **分时点数量**：CN 全天 241 点、HK 332 点、US 391 点左右（各市场含边界处理略有 ±1 差异），以实际 `points` 为准渲染，不要写死点数。

## 10. 三市场全部股票列表

A股、港股、美股列表页改用 `GET /api/quotes/stocks?market=CN&page=1&pageSize=50`，分别传入 `CN/HK/US`。原 `/hot` 仍用于热门推荐区，不能用于展示全部股票。

参数 `page` 从1开始，`pageSize` 默认50、最大100。返回 `data`：

```json
{
  "market": "CN",
  "page": 1,
  "pageSize": 50,
  "total": 5000,
  "hasMore": true,
  "syncComplete": true,
  "lastSyncedAt": "2026-10-10T12:00:00",
  "delayed": false,
  "stocks": []
}
```

上例数量、时间为示意。`stocks` 的单项字段沿用快照结构（代码、名称、最新价、涨跌幅等），按完整股票代码稳定排序；`total` 是数据库中该市场当前在市股票总数。切换市场时重置页码和已加载项，下拉加载时递增 `page`，直到 `hasMore=false`。

报价获取失败或某只股票没有报价时，该股仍出现在列表，名称和代码保留，报价和行情日期字段为 `null`、`delayed=true`；前端将空值显示为“—”，不可过滤整行。

`syncComplete` 表示本应用进程已对该市场完成过整批同步，`lastSyncedAt` 为该次成功时间（北京时间）；重启后再次同步前为false/null，已入库股票仍可展示。尚未同步完成时可提示“股票列表正在更新”；这些字段不是交易所对数据覆盖范围的保证。A股含沪深北（北交所后缀 `.BJ`）；港股、美股覆盖当前上游筛选范围。
