# iTick 行情接入

## 配置与启动

主源默认 `itick`，免费入口 `https://api-free.itick.org`。Token 从 `ITICK_TOKEN`
读取，或放入根目录 `itick-local.properties`（已忽略，不提交）。Spring Boot
会自动导入该本地文件；IDEA 的工作目录需为项目根目录。示例：

```properties
quote.provider.primary=itick
quote.provider.itick-base-url=https://api-free.itick.org
quote.provider.itick-token=YOUR_TOKEN
quote.provider.itick-requests-per-minute=5
quote.provider.itick-batch-size=3
quote.sync.security-provider=itick
```

Docker 通过环境变量传入上述配置，见 `.env.example` 和 `docker-compose.yml`。
本地文件不会复制进 Docker。替换 Token 后重启后端，应用启动时创建
`quote_provider_symbol` 映射表，再异步同步股票目录；同步失败保留已有数据并重试。

## 目录范围与清理

- CN 同步 SH、SZ，合并成沪深列表；不推断北交所覆盖，不根据缺失名单隐藏已有北交所记录。
- HK 保存原始代码 `700`，项目公开代码维持 `00700.HK`。
- US 保留 NASDAQ、NYSE、AMEX；去重代码，OTC 不纳入当前页面范围。
- 清除名称中的 HTML 和多余空白，保留供应商原始语言（简繁混合不自动翻译）。
- 类型 `stock` 仍可能包括优先股、权证等，需要名称及代码规则过滤；
  `Preferred Bank` 等公司名称不会仅因包含 Preferred 被删除。
- 沪深通过正股代码段排除混入股票目录的指数等；港股排除 ETF、杠杆基金、优先股、窝轮、牛熊证代码段。
- 新目录先整批验证再写库；历史记录保留。iTick 未覆盖的旧普通股不判退市，已识别非股票状态为 3。
- iTick 代码和交易所存独立映射表，东方财富 `upstream_secid` 保留兼容。

## 报价与图表

接口地址、公开 symbol 和分页参数兼容现有前端。

| 功能 | iTick 请求 | 处理 |
|---|---|---|
| 目录 | `/symbol/list?type=stock&region=...` | 本地同步，页面查数据库 |
| 报价 | `/stock/quotes` | 按 region 分组批量请求；共享短期缓存 |
| 分时 | `/stock/kline?kType=1` | 向前补页取最近常规交易日，剔除盘前盘后、折叠午休 |
| 昨收 | `/stock/quotes` 的 `p` | 分时价格基准 |
| 日 K | `/stock/kline?kType=8` | 按时间补页，排序后计算均线 |

免费入口报价携带目录里的 exchange 会返回 `code=0,data={}`（2026-10-10 实测），
因此报价使用省略 exchange 的主上市交易所查询。K 线保留 exchange 参数。
成交量按上游股数使用，不沿用东方财富 A 股乘 100 的规则。
时间戳按毫秒转换；分钟点转北京时间、美股按纽约时区筛选常规时段；
日 K 采用 UTC 日期锚点，避免美股日期偏移一天。

**iTick 日 K 的 `fq=none`，表示未额外复权的供应商价格。**
当前未实现完整前复权计算；不能把拆股比例直接当作现金分红前复权因子。
东方财富返回 `fq=qfq`，前端按字段显示。均价由常规时段累计成交额/成交量计算，
必要字段缺失时均价为 null。半日市等特殊交易时段仍由项目交易日历在展示时裁剪。

指数和北交所保留原有数据源；iTick 股票默认不自动回退，避免不知情混合不同价格口径。
需启用旧源兜底时设置 `quote.provider.itick-fallback-enabled=true`。
Redis 缓存按主源隔离，切换时不会复用旧主源缓存。

## 免费额度

默认每个应用进程最多 5 次请求/滚动 60 秒，配额不足快速返回，不阻塞页面。
单应用进程内所有目录、报价、图表共用配额；多实例部署或其他程序共享 Token 时需统一限流。
快照和列表缓存 120 秒、分钟线 120 秒、日 K 300 秒；默认关闭后台报价预热。
缓存失败时仍可使用原有 stale 降级，列表无报价时保留名称和代码，标记 delayed。
后续刷新优先补齐未取到报价的标的，避免有限额度始终只更新页面前几行。

无缓存且额度不足返回 HTTP 429 / `QUOTE_UPSTREAM_RATE_LIMITED`，带 `Retry-After: 60`。
启动目录同步需 4 次调用，因此第一分钟剩余额度较少。免费入口已验证 3 只批量成功，
10 只返回 `code=1,msg=your request is too much`，因此默认每批 3 只。
50 只股票一页最多需要 17 个报价批次，免费额度无法保证所有页面及图表同时刷新；
提高额度后修改配置。此信息来自免费入口实测，不直接套用付费套餐的批量上限。

## 本次检查

2026-10-10 上游读取成功：沪深港美目录、苹果/英伟达批量报价、苹果分钟线及历史分页、
腾讯分钟线/日 K、茅台日 K。目录原始行数为 SH 2338、SZ 2910、HK 2917、US 9624，
这些是过滤前数量，不能直接当普通股数或交易所完整覆盖证明。
已执行 Maven 编译检查；没有启动或重启本地服务，也没有执行数据库同步。
