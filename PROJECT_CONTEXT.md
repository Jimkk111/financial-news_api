# Project Context

> 可提交的项目速览，供新会话定向阅读使用。当前实现以源码与运行配置为准；历史文档只作补充。更新时间：2026-08-30。分支：`feat-v300`；HEAD：`e3b59b9`。

## 项目定位与权威性

这是一个 Java/Spring Boot 财经新闻后端，提供认证、新闻浏览与搜索、草稿/收藏/历史、AI 对话和多 Agent 新闻爬取能力。事实优先级为：当前源码与配置 > Docker/部署文件 > 项目文档 > Git 历史。仓库当前没有 README、CI 配置或业务测试源码。

## 技术栈

- Maven JAR 项目；Spring Boot `3.4.4`；Java `23`（`pom.xml:6-26`）。
- Spring Web、Validation、Security、Mail、Data Redis；MySQL Connector/J；MyBatis-Plus `3.5.5`（`pom.xml:29-72`）。
- Knife4j/OpenAPI、JJWT `0.12.3`、Hutool、jsoup、LangChain4j `0.35.0`、Lombok（`pom.xml:74-147`）。
- 应用入口：`src/main/java/com/financial/news/FinancialNewsApplication.java:12-18`；启用 Spring Boot，并扫描 `com.financial.news.mapper`。

## 目录地图

```text
src/main/java/com/financial/news/
  common/       统一响应、错误码、异常处理
  config/       Spring、Security、Redis、MyBatis-Plus、OpenAPI、爬虫 Agent 配置
  controller/   REST 控制器
  dto/          请求 DTO / 响应 VO
  entity/       数据库实体
  mapper/       MyBatis-Plus Mapper
  model/content/块级内容模型
  security/     JWT 过滤器、Token、用户详情
  service/      业务服务
  service/crawler/多 Agent 爬虫工作流
  utils/        内容编解码、ID、TypeHandler 等
src/main/resources/
  application.yml             通用配置（默认 dev、端口）
  application-dev.yml         本地配置，已忽略且可能含敏感值
  application-prod.yml        生产环境变量配置
  db/init.sql                 数据库初始化 schema
```

运行时/生成目录 `target/`、`logs/`、上传目录和 `.idea/` 不属于权威上下文。

## 运行、测试与部署

### 本地 Maven（Inference）

仓库未文档化本地命令，也没有 Maven Wrapper；以下是标准 Maven/Spring Boot 约定：

```bash
mvn spring-boot:run
mvn test
mvn clean package
```

`pom.xml` 虽包含测试依赖，但当前未发现 `src/test` 或其他业务测试源码，不能据此推断存在测试覆盖。

### Docker Compose（Fact）

`docker-compose.yml:1-7` 已给出：

```bash
docker compose up -d
docker compose logs -f app
docker compose down
docker compose down -v   # 会删除数据卷，谨慎执行
```

编排 MySQL 8.0、Redis 7 Alpine 和 Spring Boot app（`docker-compose.yml:8-115`）；数据库挂载 `src/main/resources/db/init.sql`（`docker-compose.yml:21-24`），应用映射到 `127.0.0.1:3000`（`docker-compose.yml:109-115`）。Compose 中的 Nginx 服务目前注释掉，通常由前端侧 Nginx 反代。

### Dockerfile（Fact）

构建阶段执行 `mvn clean package -DskipTests -B -q`（`Dockerfile:17-24`）；运行阶段使用 Java 23 JRE、非 root `appuser`、默认 `prod` profile，暴露端口 `3000`（`Dockerfile:25-58`）。

## 配置与外部依赖

- 通用键、默认 profile/端口、数据库、Redis、邮件、JWT、AI、爬虫和上传/日志配置：`src/main/resources/application.yml:1-147`。
- 生产配置从环境变量读取：`src/main/resources/application-prod.yml:1-51`；模板和启动提示见 `.env.example:1-7`、`.env.docker:1-65`。
- 爬虫配置会校验 API key，并支持 `CRAWLER_AI_*` 优先、通用 `AI_*` 回退：`src/main/java/com/financial/news/config/CrawlerAgentConfig.java:19-61`。
- 只记录配置键名和是否必需，不记录任何 key、secret、密码、Token 或本地 `application-dev.yml`/真实 `.env` 值。

## API 与安全边界

统一返回结构是 `{ code, msg, data }`：`src/main/java/com/financial/news/common/Result.java:10-84`。

主要端点：

- 认证：`POST /api/auth/login|register|logout|send-code|reset-password`（`controller/AuthController.java:20-64`）。登录/注册将 JWT 写入 Cookie；登出清除 Cookie。
- 新闻：`GET /api/news`、`/{id}`、`/{id}/views`、`/categories`、`/tags`、`/search`（`controller/NewsController.java:19-69`）。
- AI：会话/消息 CRUD、`POST /api/ai/chat`、`POST /api/ai/chat/stream`（`controller/AiController.java:23-77`）。
- 爬虫：`POST /api/crawler/crawl`、`POST /api/crawler/crawl/stream`，请求 `instruction` 必填 2–500 字符，`sessionId` 可选（`controller/CrawlerController.java:24-63`、`dto/request/CrawlerRequest.java:13-25`）。
- 媒体：`POST /api/media/image`（≤10MB，jpeg/png/gif/webp）、`POST /api/media/video`（≤200MB，mp4/mov、webm/mkv），multipart 字段 `file`，存储到阿里云 OSS（`service/MediaService.java`），返回 `{url}`；未配置 OSS 时返回 503 `MEDIA_NOT_CONFIGURED`。
- 其他：草稿、收藏、浏览历史、用户资料；系统健康检查 `GET /health` 和 `GET /api`。

安全规则见 `src/main/java/com/financial/news/config/SecurityConfig.java:22-52`：健康检查、认证接口、GET 新闻和上传静态资源公开，其余接口默认需要认证。JWT 认证相关实现位于 `security/`。

## 数据模型与新闻服务

`src/main/resources/db/init.sql:1-180` 定义 users、categories、tags、news、news_tags、drafts、draft_tags、favorites、history、ai_sessions、ai_messages、verification_codes 等表及关系。`news.content` 保留旧 HTML，`news.content_json` 保存块级内容（`init.sql:57-101`）。

`service/NewsService.java:22-199` 使用 MyBatis-Plus 和 Redis：列表/搜索避免返回 `content_json`，详情读取缓存并在需要时把旧 HTML 转为 Block JSON，浏览量变更会清理详情缓存，分类/标签有独立缓存。

## 当前爬虫架构

当前实际入口是 `CrawlerAgentService` 委托 `CrawlerOrchestrator`（`service/crawler/CrawlerAgentService.java:27-70`）。编排阶段依次为：

```text
Planner -> Scraper -> Processor -> Writer
```

阶段实现和错误/报告逻辑见 `service/crawler/CrawlerOrchestrator.java:25-191`；共享指令、计划、文章、处理结果、保存结果、统计和执行日志见 `WorkflowContext.java:17-68`。Agent 接口分别为 `PlannerAgent.java`、`ScraperAgent.java`、`ProcessorAgent.java`、`WriterAgent.java`。

`CrawlerTools.java` 提供网页抓取、CSS/正文解析、清洗与 Block JSON 转换、新闻/分类/标签入库、通用 JSON API 和华尔街见闻 API（网页工具约 `:93-359`，入库约 `:362-555`，Wallstreet API `:646-796`）。当前 orchestrator prompt 对来源的处理是：东方财富/新浪财经/同花顺使用 SSR，华尔街见闻使用 API，财联社因 SPA 被跳过（`CrawlerOrchestrator.java:193-230`）。

## 约定与当前实现注意事项

- 以当前 Java 实现为准；`docs/crawler-agent-delivery.md` 仍含旧单 Agent/能力描述，`docs/refactor.md` 是 Node.js 到 Java 的历史迁移资料，可能与现状不符。
- `POST /api/crawler/crawl/stream` 虽声明 `text/event-stream`，当前仍同步执行并返回普通 `Result`（`CrawlerController.java:49-63`、`CrawlerAgentService.java:65-70`）。AI stream 也会先得到完整响应后逐字符发送，而非真正的上游流式（`service/AiService.java:218-225`）。
- 爬虫 `userId` 当前只用于记录/传递，`sessionId` 原样返回；当前流程不再把爬虫消息保存到 `ai_messages`。
- 旧 `service/crawler/CrawlerAgent.java` 仍存在，但当前配置没有为它注册 Bean，实际使用四个新 Agent。
- `CrawlerTools.checkUrlsExist` 的说明声称按 URL 检查，但实现只统计新闻总数（`CrawlerTools.java:627-642`），修改前需核实契约。
- 不要把 `application-dev.yml`、真实 `.env`、日志、构建产物或上传内容写入本文件。

## Git 与近期演进

当前分支/提交：`feat-v300` / `e3b59b9`（多 Agent 爬虫工作流编排）。近期相关提交：

- `e3b59b9`：引入 Planner/Scraper/Processor/Writer 和 `WorkflowContext`。
- `17b931e`：加入华尔街见闻专用列表/详情 API 工具。
- `688f377`：加入爬虫 LLM key 校验与环境变量回退。
- `b371946`、`d5b4de3`：调整 Docker app 绑定和 Nginx 反代。

刷新本文件时重新确认当前 branch/HEAD；不要仅凭历史提交判断现状。

## Sources

优先从以下文件定向核验：

- `pom.xml`
- `Dockerfile`
- `docker-compose.yml`
- `src/main/java/com/financial/news/FinancialNewsApplication.java`
- `src/main/resources/application.yml`
- `src/main/resources/application-prod.yml`
- `src/main/resources/db/init.sql`
- `src/main/java/com/financial/news/common/Result.java`
- `src/main/java/com/financial/news/config/SecurityConfig.java`
- `src/main/java/com/financial/news/config/CrawlerAgentConfig.java`
- `src/main/java/com/financial/news/controller/{AuthController,NewsController,AiController,CrawlerController,SystemController}.java`
- `src/main/java/com/financial/news/service/{NewsService,AiService}.java`
- `src/main/java/com/financial/news/service/crawler/`
- `docs/crawler-agent-delivery.md`（专题补充，可能滞后）
- `docs/refactor.md`（历史背景，非现状权威）
