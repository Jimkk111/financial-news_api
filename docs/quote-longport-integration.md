# 长桥行情接入

## 启用配置

报价主源默认改为 `longport`，使用官方 Java SDK `io.github.longbridge:openapi-sdk:5.2.0`。
该坐标与包名来自官方仓库当前发行版，项目对外环境变量仍使用 `LONGPORT_*`。
在 IDEA 的后端运行配置中设置环境变量，然后重启你自己的后端进程：

```dotenv
QUOTE_PROVIDER=longport
LONGPORT_APP_KEY=YOUR_APP_KEY
LONGPORT_APP_SECRET=YOUR_APP_SECRET
LONGPORT_ACCESS_TOKEN=YOUR_ACCESS_TOKEN
LONGPORT_REGION=cn
QUOTE_SECURITY_PROVIDER=itick
ITICK_TOKEN=YOUR_EXISTING_ITICK_TOKEN
```

已有 `itick-local.properties` 中的 Token 可继续使用，不必重复配置。
本地根目录的 `.env` 不作为 Spring Boot 配置自动加载；上面配置可直接填入 IDEA
的环境变量。Docker 则读取 `.env` 并由 `docker-compose.yml` 注入。
如需使用文件，复制根目录 `longport-local.properties.example` 为
`longport-local.properties`，填入三项凭证；工作目录须是项目根目录。
真实配置文件已加入 Git 和 Docker 忽略规则。

| 配置 | 默认值 | 作用 |
|---|---|---|
| `QUOTE_PROVIDER` | `longport` | 报价、分时、日 K 主源 |
| `LONGPORT_APP_KEY` | 空 | 长桥应用 Key，必填 |
| `LONGPORT_APP_SECRET` | 空 | 长桥应用 Secret，必填 |
| `LONGPORT_ACCESS_TOKEN` | 空 | 长桥访问 Token，必填；过期后更换并重启 |
| `LONGPORT_REGION` | `cn` | `cn` 大陆接入点，`hk` 国际接入点 |
| `LONGPORT_TIMEOUT_MS` | `10000` | 单次请求/连接等待上限，毫秒 |
| `LONGPORT_FALLBACK_ENABLED` | `false` | 是否允许长桥支持的标的失败后回退其他行情源 |
| `QUOTE_SECURITY_PROVIDER` | `auto` | 长桥主源时自动使用 iTick 同步股票目录 |
| `QUOTE_SNAPSHOT_FRESH_SECONDS` | `15` | 报价缓存新鲜窗口 |
| `QUOTE_TREND_FRESH_SECONDS` | `60` | 分时缓存新鲜窗口 |
| `QUOTE_LIST_FRESH_SECONDS` | `15` | 股票页/指数/热门列表缓存新鲜窗口 |

连接按首次行情请求懒加载，复用单个 SDK QuoteContext；关闭应用时释放连接。
未配置凭证仍可启动后端，股票目录也可继续查数据库；行情请求会报上游错误，
后端日志提示缺失配置名称。已有缓存仍按原降级规则处理。不会自动刷新 Token。

## 股票目录与行情分工

长桥的 `securityList` 目前是美股夜盘可交易证券名单，不是三市场全量证券目录。
因此不使用它覆盖现有主表。iTick 继续每日同步沪深、港股、美股目录，
股票列表和搜索读取本地数据库；长桥负责列表报价、单股报价、分时、日 K。
已有普通股过滤和历史非股票隐藏规则保持生效。目录覆盖范围见
[iTick 接入说明](quote-itick-integration.md)。iTick 免费额度只用于目录时，
正常每日全量同步需要 4 次调用；失败重试和主动切回 iTick 行情仍消耗额度。

## 返回口径与兼容

- `/api/quotes/**` 地址、公开 symbol、分页参数和响应结构保持兼容。
- 港股公开代码仍为 `00700.HK`，请求长桥时转换为 `700.HK`，返回映射回原代码。
- 道指/纳指请求转换为 `.DJI.US` / `.IXIC.US`；沪深指数、恒指、恒生科技也使用长桥。
- 长桥官方支持范围未列出北交所和标普指数，这些标的继续使用现有行情源。
- 行情报价按市场分组，每批最多 20 个标的；名称沿用本地正股主表，价格时间来自长桥。
- 成交量直接采用上游股数，成交额保持原币种，不乘 A 股手数系数。
- 分时查询常规交易时段，直接使用 `avg_price`；昨收从长桥报价读取。
- 分时转换北京时间后，由现有交易日历折叠午休并裁剪特殊交易时段。
- 日 K 用历史偏移接口向前取数，前复权 `fq=qfq`；按市场时区确定交易日期。
  支持服务层要求的最近 500 根加 60 根均线预留窗口。
- Redis 按主源隔离，切换后不会复用 iTick 图表缓存，无须清空整个 Redis。
- 此次接入是后端 SDK 拉取行情，前端仍调用现有 HTTP 接口，没有新增前端推送协议。

## 限流与账户权限

官方说明为每秒最多 10 次、最多 5 个并发请求。本适配每进程限制滚动 1 秒 10 次，
最多 4 个并发；上游实际处理结束才释放并发槽位，避免超时后放大并发。
本地/上游配额不足返回 `429 / QUOTE_UPSTREAM_RATE_LIMITED`，`Retry-After: 1`。
iTick 仍返回原来的 `Retry-After: 60`。缓存失败时可使用 stale 降级。
多个后端实例或其他程序共享同一账户时，应统一账户请求额度。

App 内的行情权限与 OpenAPI 权限独立；港股 BMP 或延迟权限并不等于实时 LV1。
默认缓存窗口恢复到报价 15 秒、分时 60 秒，但账户延迟不会因缩短缓存消失。
报价会用上游时间戳判断盘中延迟；无权限错误日志提示长桥错误码 `301604`。
不存在/无行情的图表返回现有 `404 / QUOTE_CHART_UNAVAILABLE`，不计入故障熔断。

## 本次完成范围

已接入官方 SDK 并完成 Maven 编译，未运行测试或启动本地服务器。
尚未提供长桥三项凭证，因此未验证真实账户的报价、图表、权限及连通性。
填入配置后重启后端即可使用；无需重建证券表或清理现有股票目录。

官方依据：[SDK 源码](https://github.com/longbridge/openapi/tree/main/java)、
[Maven 发布版本](https://repo.maven.apache.org/maven2/io/github/longbridge/openapi-sdk/maven-metadata.xml)、
[报价权限与限流](https://open.longportapp.com/docs/qa/broker)、
[证券分时](https://open.longportapp.com/docs/quote/pull/intraday)、
[历史 K 线](https://open.longportapp.com/docs/quote/pull/history-candlestick)、
[夜盘证券名单](https://open.longportapp.com/docs/quote/security/security_list)。
