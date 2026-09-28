# 正文内容 JSON 契约（后端实现说明）

> 前后端共同事实源：前端 `src/types/content.ts` + `src/utils/content/{validateContent,sanitizeUrl,htmlToBlocks,blocksToHtml}.ts`，
> 后端 `src/main/java/com/financial/news/model/content/*` + `src/main/java/com/financial/news/utils/ContentCodec.java`。
> 两侧规则逐条对齐，本文档描述后端实现与接口行为。

## 一、契约总则

- 正文 = **块级 JSON 数组**（九种块 + text/hardBreak 行内节点 + bold/italic/code/link 四种 marks）。
- **schema 中永远不出现 HTML 字符串、style、class**；富文本由 marks 表达，样式只存在于前端渲染组件。
- DB：`content` 列仍存派生 HTML（遗留导出用途，API **不再返回**）；`content_json` 列存块 JSON（LONGTEXT 字符串，合法性由服务端校验保证，不依赖 DB JSON 类型）。

## 二、接口行为（本次切换后）

| 接口 | 行为 |
|---|---|
| `GET /api/news/:id` | `content` 字段 = 块 JSON 数组；`contentJson` 字段已删除；HTML 不再返回 |
| 新闻/草稿列表 | `content` 不返回（实体 HTML 列已 @JsonIgnore，列表查询本就排除 content_json） |
| `POST/PUT /api/drafts` | `content` 接收块 JSON 数组（过渡期兼容 HTML 字符串，服务端自动转换）；`contentJson` 字段仍可用但优先级低于 content 数组 |
| `GET /api/drafts/:id`、发布 | `content` = 块 JSON 数组 |
| `POST /api/crawler/ingest*` | 爬虫入库直接产块 JSON |

安全闸门（写入与爬虫路径都过）：九块白名单（未知块丢弃）、URL 协议白名单（拦 `javascript:`/`vbscript:`/`data:`/`file:`，含控制字符混淆）、heading 钳制 1-3、块数上限 1000。

## 三、后端实现要点

- **模型**：`model/content` 包，`Block`/`InlineNode` 接口 + @JsonTypeInfo(property="type") 多态。
  块：paragraph{children} / heading{level,children} / bulletList|orderedList{items:[{children}]} /
  blockquote{children:Block[]} / codeBlock{code,lang?} / image{src,alt?,caption?} / video{src,poster?} / divider。
  行内：text{text,marks?} / hardBreak。
- **序列化**：`ContentCodec.toJson` 必须用 `writerFor(TypeReference<List<Block>>)`——根层级泛型擦除后接口多态注解不生效，块 type 判别字段会丢（有回归测试 `ContentCodecShapeTest` 把守）。
- **HTML→块**：`ContentCodec.fromHtml`，规则对齐前端 `htmlToBlocks`（白名单标签、行内 marks、figure/figcaption 图注、未知容器递归提文本丢结构）；另做两处源站数据清理：段首全角/半角空白剥离（缩进交给前端 CSS）、空段落剔除。
- **校验**：`ContentCodec.normalize` 对齐前端 `normalizeContent`（含 `sanitizeUrl`）。
- **兜底**：读取时 `content_json` 为空或旧格式（TypeHandler 反序列化失败返回 null）→ 从 `content` HTML 现场转换。存量 1300 条已于 2026-09-28 全量迁移，兜底仅防御漏网记录。

## 四、迁移与运维端点

| 端点 | 用途 |
|---|---|
| `POST /api/crawler/ingest/migrate-content` | 全量契约迁移（content→块 JSON，幂等可重跑） |
| `POST /api/crawler/ingest/renormalize` | 重结构化（剥尾部模板后再转换，用于发现新脏模式时） |
| `POST /api/crawler/ingest/backfill` | 缺正文重抓 |

迁移前备份：`news_content_json_bak_v2`（上一代契约 content_json）、`news_content_bak_20260925`（HTML 全量）。

## 五、前端切换清单（对应前端仓库）

1. `types/news.ts`：`NewsItem.content: string` → `ArticleContent`（或 `ArticleContent | null`）。
2. `NewsDetail.vue:153`：`htmlToBlocks(news.value.content)` → `normalizeContent(news.value.content)`。
3. `NewsEditor.vue:80/100`：`htmlToBlocks(draft.content)` → `normalizeContent(draft.content)`；`content: blocksToHtml(content.value)` → `content: content.value`。
4. 删除 `utils/content/htmlToBlocks.ts`、`utils/content/blocksToHtml.ts`（及其测试）。
5. 其余（渲染组件、TipTap 翻译、validateContent、sanitizeUrl）不动。

## 六、已知的两侧对称性约定

- 图注：块 ↔ `<figure><img><figcaption>` 互转，两侧对称（避免往返丢图注）。
- 纯文本：按空行分段、段内换行转 hardBreak；后端对超长单段按句末标点聚合（约 150 字/段，仅旧文本转储迁移路径）。
- marks 集合锁定四种：bold/italic/code/link——**下划线会被剥离**（华尔街见闻源站有 underline，契约不保留）。
