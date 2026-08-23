# 新闻爬取 Agent 工作流 — 交付文档

> 交付日期：2026-08-22  
> 版本：1.0.0  
> 模块：新闻爬取 Agent 工作流

---

## 一、功能概述

基于 LangChain4j 框架实现的**财经新闻自动化采集 Agent 工作流**。前端输入自然语言指令（如"帮我爬取财联社的新闻数据"），后台 Agent 自主完成**网页爬取 → 内容清洗 → 结构化 → 分类/标签绑定 → 数据入库**的完整流程。

### 核心能力

| 能力 | 说明 |
|------|------|
| 多源采集 | 支持财联社、东方财富、新浪财经、华尔街见闻、同花顺 5 大财经数据源 |
| 智能解析 | Agent 根据网页 HTML 结构自主选择 CSS 选择器，自适应不同网站 |
| 内容清洗 | 自动过滤广告、导航、页脚等噪声，只保留新闻正文 |
| 结构化存储 | 转换为项目定义的 Block 块级 JSON 格式（9 种块类型） |
| 分类/标签 | Agent 根据文章内容自动判断分类和标签，支持新建 |
| 去重机制 | 按标题去重，避免重复入库 |
| 会话管理 | 复用现有 AI 会话基础设施，爬取过程可追溯 |

---

## 二、架构设计

### Agent 工作流

```
用户: "帮我爬取财联社的新闻数据"
         ↓
   CrawlerController (REST API)
         ↓
   CrawlerAgentService (会话管理 + 调度)
         ↓
   CrawlerAgent (LangChain4j 动态代理)
         ↓
   ┌─── ReAct 循环 (LLM 决策) ───────────────────────────┐
   │                                                        │
   │  Thought: 用户想爬取财联社新闻，我需要获取网站信息     │
   │  Action: getSourceInfo("财联社")                       │
   │  Observation: {"name":"财联社","homeUrl":"...","listUrl":"..."}  │
   │                                                        │
   │  Thought: 需要获取列表页 HTML                          │
   │  Action: fetchPage("https://www.cls.cn/telegraph")    │
   │  Observation: <html>...                               │
   │                                                        │
   │  Thought: 需要提取文章链接                             │
   │  Action: extractArticleList(html, selector, ...)       │
   │  Observation: [{"url":"...","title":"...","time":"..."}]│
   │                                                        │
   │  Thought: 逐篇处理文章                                 │
   │  Action: fetchPage(articleUrl) → parseArticleContent   │
   │          → htmlToContentJson → saveNews                │
   │  Observation: saved:123                                │
   │                                                        │
   │  ... (重复处理每篇文章)                                 │
   │                                                        │
   │  Final: 爬取完成，共处理 X 篇文章，成功 Y 篇          │
   └────────────────────────────────────────────────────────┘
         ↓
   返回执行结果摘要
```

### 类图

```
CrawlerController ──→ CrawlerAgentService
                           │
                           ├─→ CrawlerAgent (interface, LLM 代理)
                           ├─→ AiSessionMapper (会话持久化)
                           └─→ AiMessageMapper (消息持久化)

CrawlerAgent (LLM proxy) ──→ CrawlerTools (@Tool 工具集)
                                 │
                                 ├─→ NewsMapper
                                 ├─→ CategoryMapper
                                 ├─→ TagMapper
                                 └─→ NewsTagMapper
```

---

## 三、新增文件清单

### 3.1 基础模型层（补全现有编译依赖）

| 文件 | 说明 |
|------|------|
| `model/content/Block.java` | 块级内容接口（多态序列化） |
| `model/content/ParagraphBlock.java` | 段落块 |
| `model/content/HeadingBlock.java` | 标题块 |
| `model/content/ImageBlock.java` | 图片块 |
| `model/content/QuoteBlock.java` | 引用块 |
| `model/content/ListBlock.java` | 列表块 |
| `model/content/TableBlock.java` | 表格块 |
| `model/content/CodeBlock.java` | 代码块 |
| `model/content/VideoBlock.java` | 视频块 |
| `model/content/DividerBlock.java` | 分割线块 |
| `utils/ContentCodec.java` | 块级内容编解码器（JSON序列化 + HTML迁移） |
| `utils/BlockListTypeHandler.java` | MyBatis TypeHandler |
| `dto/response/NewsDetailVO.java` | 新闻详情视图对象 |
| `dto/response/FavoriteVO.java` | 收藏视图对象 |
| `dto/response/HistoryVO.java` | 浏览历史视图对象 |
| `entity/DraftTag.java` | 草稿-标签关联实体 |
| `mapper/DraftTagMapper.java` | 草稿-标签关联 Mapper |

### 3.2 爬取 Agent 核心

| 文件 | 说明 |
|------|------|
| `service/crawler/CrawlerAgent.java` | Agent 接口（LangChain4j 代理） |
| `service/crawler/CrawlerTools.java` | Agent 工具集（@Tool 注解方法） |
| `service/crawler/CrawlerAgentService.java` | 爬取服务（会话管理 + 调度） |
| `config/CrawlerAgentConfig.java` | Agent 配置（LLM 实例 + AiServices） |
| `dto/request/CrawlerRequest.java` | 爬取请求 DTO |
| `controller/CrawlerController.java` | REST 控制器 |

### 3.3 修改文件

| 文件 | 变更 |
|------|------|
| `pom.xml` | 新增 LangChain4j 依赖、更新 Java 版本 |
| `application.yml` | 新增爬虫 Agent 配置段 |
| `application-dev.yml` | 新增爬虫 Agent 开发环境配置 |

---

## 四、@Tool 工具清单

Agent 在 ReAct 循环中可调用的工具方法：

| 工具方法 | 功能 | 返回 |
|----------|------|------|
| `fetchPage(url)` | 获取网页 HTML | HTML 字符串 |
| `extractArticleList(html, linkSelector, titleSelector, timeSelector, baseUrl)` | 从 HTML 提取文章列表 | JSON 数组 |
| `parseArticleContent(html, contentSelector, titleSelector, timeSelector)` | 提取文章正文 | JSON 对象 |
| `htmlToContentJson(htmlContent)` | HTML → Block JSON | JSON 数组 |
| `saveNews(title, summary, htmlContent, contentJsonStr, publishTimeStr, source, imageUrl, categoryName, tagNamesStr)` | 保存新闻到数据库 | "saved:ID" |
| `checkNewsExists(title)` | 检查新闻是否存在 | "exists:true/false" |
| `listCategories()` | 获取已有分类 | JSON 数组 |
| `listTags()` | 获取已有标签 | JSON 数组 |
| `createCategory(categoryName)` | 创建分类 | "created:ID" |
| `createTag(tagName)` | 创建标签 | "created:ID" |
| `getSourceInfo(sourceName)` | 获取数据源配置 | JSON 对象 |
| `checkUrlsExist(urlsJson)` | 批量检查 URL | 统计 JSON |

---

## 五、API 接口

### POST /api/crawler/crawl

执行新闻爬取任务（同步）。

**请求头**：需要 JWT 认证

**Request Body**:
```json
{
  "instruction": "帮我爬取财联社的最新财经新闻",
  "sessionId": "session-xxxxxxxx (可选)"
}
```

**Response**:
```json
{
  "code": "200",
  "msg": "success",
  "data": {
    "role": "assistant",
    "content": "爬取任务完成：共处理10篇文章，成功入库8篇，跳过2篇（已存在）。分类：财经新闻(5)、股票市场(3)。标签：A股(4)、美联储(2)、降息(1)...",
    "sessionId": "session-a1b2c3d4"
  }
}
```

### POST /api/crawler/crawl/stream

执行新闻爬取任务（流式 SSE）。当前为同步执行后一次性返回，后续可升级为真正的流式。

---

## 六、配置说明

### application.yml 新增配置

```yaml
crawler:
  agent:
    api-key: ${CRAWLER_AI_API_KEY:${AI_API_KEY:}}   # 爬虫专用 LLM API Key
    api-base-url: ${CRAWLER_AI_BASE_URL:${AI_BASE_URL:https://api.openai.com/v1}}
    model: ${CRAWLER_AI_MODEL:gpt-4o-mini}            # 建议使用便宜快速的模型
    temperature: 0.3                                   # 低温度保证稳定性
    max-tokens: 4096
    max-articles-per-run: 20                           # 单次最多爬取文章数
    http-timeout: 30                                   # HTTP 请求超时（秒）
```

### 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `CRAWLER_AI_API_KEY` | 爬虫专用 API Key | 复用 `AI_API_KEY` |
| `CRAWLER_AI_BASE_URL` | 爬虫专用 API 基础 URL | 复用 `AI_BASE_URL` |
| `CRAWLER_AI_MODEL` | 爬虫模型 | `gpt-4o-mini` |

---

## 七、数据源配置

| 数据源 | 首页 | 列表页 |
|--------|------|--------|
| 财联社 | https://www.cls.cn/ | https://www.cls.cn/telegraph |
| 东方财富 | https://www.eastmoney.com/ | https://finance.eastmoney.com/a/cgsxw.html |
| 新浪财经 | https://finance.sina.com.cn/ | https://finance.sina.com.cn/ |
| 华尔街见闻 | https://wallstreetcn.com/ | https://wallstreetcn.com/news/global |
| 同花顺 | https://www.10jqka.com.cn/ | https://news.10jqka.com.cn/ |

---

## 八、依赖变更

### 新增 Maven 依赖

```xml
<!-- LangChain4j Core -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j</artifactId>
    <version>0.35.0</version>
</dependency>

<!-- LangChain4j OpenAI 兼容 -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-open-ai</artifactId>
    <version>0.35.0</version>
</dependency>
```

> jsoup 已存在于项目中（1.17.2），用于网页解析。

---

## 九、使用示例

### 前端调用

```javascript
const response = await fetch('/api/crawler/crawl', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${token}`
  },
  body: JSON.stringify({
    instruction: '帮我爬取财联社的最新财经新闻'
  })
});

const result = await response.json();
console.log(result.data.content); // Agent 执行结果摘要
```

### 示例指令

- "帮我爬取财联社的最新财经新闻"
- "从东方财富爬取股票市场的最新资讯"
- "采集新浪财经的宏观经济新闻"
- "爬取华尔街见闻的全球财经新闻，限制5篇"
- "从同花顺获取A股相关新闻"

---

## 十、注意事项

1. **LLM API 费用**：每次爬取任务会多次调用 LLM（Agent ReAct 循环），建议使用 `gpt-4o-mini` 等低成本模型
2. **执行时间**：单次爬取（10篇文章）预计需要 1-3 分钟，取决于网络和 LLM 响应速度
3. **超时设置**：前端请求建议设置 5 分钟超时
4. **CSS 选择器**：Agent 会根据 HTML 结构自主尝试选择器，如遇反爬或网站改版需调整系统提示词
5. **内容去重**：按标题精确匹配去重，同名不同内容的新闻会被跳过
6. **分类/标签**：Agent 会自动推断分类和标签，首次运行时会自动创建新的分类和标签
