# Project Context

> 可提交的项目速览，供新会话定向阅读使用。当前实现以源码与运行配置为准；历史文档只作补充。更新时间：2026-09-09。分支：`main`；HEAD：`4010b0e`。

## 项目定位与权威性

这是一个 Java/Spring Boot 财经新闻后端，提供认证、新闻浏览与搜索、草稿/收藏/历史、AI 对话和多 Agent 新闻爬取能力。事实优先级为：当前源码与配置 > Docker/部署文件 > 项目文档 > Git 历史。仓库当前没有 README 和 CI 配置；业务测试目前仅 `src/test` 下 1 个单元测试类。

分支注意：本地同时存在 `main` 与 `master`，`origin/HEAD` 指向 `origin/master`；日常开发在 `main`，PR 目标通常是 `master`。

## 技术栈

- Maven JAR 项目；Spring Boot `3.4.4`；Java `23`（`pom.xml:7-28`）。
- Spring Web、Validation、Security、Mail、Data Redis；MySQL Connector/J；MyBatis-Plus `3.5.5`（`pom.xml:30-73`）。
- Knife4j/OpenAPI、JJWT `0.12.3`、Hutool、jsoup、LangChain4j `0.35.0`、Lombok（`pom.xml:75-148`）。
- spring-boot-maven-plugin 启用分层 JAR（`pom.xml:169-172`），供 Dockerfile 按变化频率分层缓存。
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
  application.yml             通用配置（默认 dev、端口 3000）
  application-dev.yml         本地配置，已忽略且可能含敏感值
  application-prod.yml        生产环境变量配置
  db/init.sql                 数据库初始化 schema
src/test/java/com/financial/news/service/AiServiceTest.java   唯一测试类
deploy/nginx/nginx.conf       备用 Nginx 配置（gzip/日志，compose 中服务被注释）
deploy/certs/                 空，预留给证书
```

运行时/生成目录 `target/`、`logs/`、上传目录和 `.idea/` 不属于权威上下文。

## 运行、测试与部署

### 本地 Maven（Inference）

仓库未文档化本地命令，也没有 Maven Wrapper；以下是标准 Maven/Spring Boot 约定：

```bash
mvn spring-boot:run
mvn test          # 目前会执行 AiServiceTest（Mockito 单元测试）
mvn clean package
```

本地启用爬虫需设置环境变量 `CRAWLER_AGENT_ENABLED=true`，否则爬虫 Bean 不会创建（见下文）。

### Docker Compose（Fact）

`docker-compose.yml:1-7` 已给出：

```bash
docker compose up -d
docker compose logs -f app
docker compose down
docker compose down -v   # 会删除数据卷，谨慎执行
```

编排 MySQL 8.0、Redis 7 Alpine 和 Spring Boot app（`docker-compose.yml:11-116`）；数据库挂载 `src/main/resources/db/init.sql`（`docker-compose.yml:24`），应用映射到 `127.0.0.1:3000` 供前端 Nginx 反代（`docker-compose.yml:113-114`）。Compose 中的 Nginx 服务目前注释掉（`docker-compose.yml:118-132`），通常由前端侧 Nginx 反代。

**注意**：compose 的 app 环境变量列表未传 `CRAWLER_AGENT_ENABLED`（`docker-compose.yml:74-109`），而 `application.yml` 默认 `false`，因此容器内爬虫默认不启用，需自行在 environment 中补该变量。

### Dockerfile（Fact）

两阶段构建：builder 手动安装 Maven 3.9.9、配置阿里云 APT/Maven 镜像，用 BuildKit 缓存挂载执行 `mvn dependency:go-offline` + `mvn clean package -DskipTests`，并用 layertools 解包分层 JAR（`Dockerfile:4-44`）。运行阶段 Java 23 JRE、`Asia/Shanghai` 时区、非 root `appuser`、按依赖→loader→应用分层 COPY、G1GC JVM 参数、暴露 3000、默认 `prod` profile（`Dockerfile:46-90`）。

## 配置与外部依赖

- 通用键、默认 profile/端口、数据库、Redis、邮件、JWT、AI、爬虫和上传/日志配置：`src/main/resources/application.yml:1-149`。
- 爬虫配置：`crawler.agent.enabled`（默认 `false`）+ `CRAWLER_AI_*` 优先、通用 `AI_*` 回退，`http-timeout` 已提升到 120 秒（`application.yml:118-131`）。
- 生产配置从环境变量读取：`src/main/resources/application-prod.yml`；模板见 `.env.example`、`.env.docker`（爬虫键在 `.env.docker:57-59`，均注释可选）。
- 邮件发件人固定取 `spring.mail.username`（163 等 SMTP 要求 From 与登录账号一致，否则 553）：`AuthService.java:44-48`。
- 只记录配置键名和是否必需，不记录任何 key、secret、密码、Token 或本地 `application-dev.yml`/真实 `.env` 值。

## API 与安全边界

统一返回结构是 `{ code, msg, data }`：`src/main/java/com/financial/news/common/Result.java:10-84`。

主要端点：

- 认证：`POST /api/auth/login|register|logout|send-code|reset-password`（`controller/AuthController.java`）。登录/注册将 JWT 写入 Cookie；登出清除 Cookie。
- 新闻：`GET /api/news`、`/{id}`、`/{id}/views`、`/categories`、`/tags`、`/search`（`controller/NewsController.java`）。
- AI：会话/消息 CRUD、`POST /api/ai/chat`、`POST /api/ai/chat/stream`（`controller/AiController.java`）。
- 爬虫：`POST /api/crawler/crawl`（同步）与 `POST /api/crawler/crawl/stream`（真 SSE），请求 `instruction` 必填 2–500 字符，`sessionId` 可选（`controller/CrawlerController.java:38-63`、`dto/request/CrawlerRequest.java`）。
- 其他：草稿、收藏、浏览历史、用户资料；系统健康检查 `GET /health` 和 `GET /api`。

安全规则见 `src/main/java/com/financial/news/config/SecurityConfig.java:22-52`：健康检查、认证接口、GET 新闻和上传静态资源公开，其余接口默认需要认证。JWT 认证相关实现位于 `security/`。

## 数据模型与新闻服务

`src/main/resources/db/init.sql` 定义 users、categories、tags、news、news_tags、drafts、draft_tags、favorites、history、ai_sessions、ai_messages、verification_codes 等表及关系。`news.content` 保留旧 HTML，`news.content_json` 保存块级内容。

`service/NewsService.java` 使用 MyBatis-Plus 和 Redis：列表/搜索避免返回 `content_json`，详情读取缓存并在需要时把旧 HTML 转为 Block JSON，浏览量变更会清理详情缓存，分类/标签有独立缓存。`config/RedisConfig.java:29-45` 为 RedisTemplate 定制了带 `JavaTimeModule` 和默认类型信息的 ObjectMapper，支持 `LocalDateTime` 及实体还原。

AI 会话修复（`10671d0`）：每轮对话只把最后一条用户消息写入 `ai_messages`，请求中的历史消息仅作上下文且截断为最近 20 条，不再重复入库（`service/AiService.java:101-115`、`164-179`）。

## 当前爬虫架构

爬虫所有 Bean 均为条件装配：`@ConditionalOnExpression("'${crawler.agent.enabled:false}' == 'true' && '${crawler.agent.api-key:}' != ''")`（`config/CrawlerAgentConfig.java:21`、`service/crawler/CrawlerOrchestrator.java:21`）。未启用时 `CrawlerAgentService` 通过 `ObjectProvider` 拿不到编排器，直接抛 `CRAWLER_NOT_CONFIGURED`(503)（`service/crawler/CrawlerAgentService.java:49-55`、`common/ErrorCode.java:55-59`）。

入口 `CrawlerAgentService` 委托 `CrawlerOrchestrator`，阶段依次为：

```text
Planner -> Scraper -> Processor -> Writer
```

编排、计划校验、LLM 输出 JSON 提取和错误处理见 `CrawlerOrchestrator.java:40-141`；单次抓取上限 `crawler.agent.max-articles-per-run`（默认 20，`CrawlerOrchestrator.java:29-30`、`56`）。共享状态、各阶段耗时、统计与执行日志见 `WorkflowContext.java:13-65`。Agent 接口：`PlannerAgent`（纯 LLM 无工具）、`ScraperAgent`/`ProcessorAgent`/`WriterAgent`（挂 `CrawlerTools`）。

**SSE 流式（7ae1deb 起为真流式）**：`crawl/stream` 返回 `SseEmitter`（超时 300 秒），`CompletableFuture.runAsync` 异步执行，逐阶段推送 `progress` 事件，结束推 `completed`（含统计与 runId）和 `done`，异常推 `error`（`CrawlerAgentService.java:28-84`）。编排器通过 `Consumer<WorkflowContext>` 回调上报进度（`CrawlerOrchestrator.java:40`、`91-94`）。

`CrawlerTools.java` 提供 15 个 `@Tool`：网页抓取与缓存 `fetchPage`(:110)、列表提取 `extractArticleList`(:172)、正文解析 `parseArticleContent`(:255)、HTML 清洗转块级 JSON `htmlToContentJson`(:353)、入库 `saveNews`(:385)、去重 `checkNewsExists`(:478)、分类/标签查询与创建(:495-576)、数据源信息(:579)、通用 JSON API(:602)、URL 批量检查(:649)、华尔街见闻列表/详情（自动按 ID 去重）(:680、:741)。当前 Planner prompt 对来源的处理：东方财富/新浪财经/同花顺用 SSR，华尔街见闻用 API，财联社因 SPA 跳过（`CrawlerOrchestrator.java:143-181`）。

## 约定与当前实现注意事项

- 以当前 Java 实现为准；`docs/crawler-agent-delivery.md` 仍含旧单 Agent/能力描述，`docs/refactor.md` 是 Node.js 到 Java 的历史迁移资料，可能与现状不符。
- `CrawlerController.java:52` 的 javadoc 仍写"当前为同步执行后一次性返回"，已过时——实现是真异步 SSE；修改时顺手更正。
- AI stream 依旧先拿到完整响应再逐字符发送，并非上游真流式（`service/AiService.java:229-233`）。
- 爬虫 `userId` 只用于记录/传递，`sessionId` 原样返回；爬虫消息不写入 `ai_messages`。
- 旧 `service/crawler/CrawlerAgent.java` 仍存在，但当前配置没有为它注册 Bean，实际使用四个新 Agent。
- `CrawlerTools.checkUrlsExist` 把规范化后的 URL 与 `news.title` 列比较（`CrawlerTools.java:649-662`），匹配逻辑疑似错位（URL 对标题），使用前需核实契约。
- Docker compose 未注入 `CRAWLER_AGENT_ENABLED`，容器内爬虫默认关闭（见"运行、测试与部署"）。
- 不要把 `application-dev.yml`、真实 `.env`、日志、构建产物或上传内容写入本文件。

## Git 与近期演进

当前分支/提交：`main` / `4010b0e`。近期相关提交：

- `4010b0e`：邮件 553 修复（发件人固定为 `spring.mail.username`）。
- `caaab3f`：爬虫 Agent 条件装配（`crawler.agent.enabled` + api-key）。
- `c1298c4`/`d3ecd74`/`8047cba`：Dockerfile 阿里云镜像加速、BuildKit 缓存、分层 JAR 与构建优化；新增 `.dockerignore`、`deploy/`。
- `7ae1deb`：爬虫 SSE 流式化（SseEmitter + 异步 + progress 事件）、AiService 会话去重修复、新增 `AiServiceTest`。
- `10671d0`：AI 会话管理 bug 修复（历史消息重复入库）。
- `e3b59b9`：多 Agent 工作流编排（Planner/Scraper/Processor/Writer + WorkflowContext）。

刷新本文件时重新确认当前 branch/HEAD；不要仅凭历史提交判断现状。

## Sources

优先从以下文件定向核验：

- `pom.xml`
- `Dockerfile`、`.dockerignore`
- `docker-compose.yml`、`deploy/nginx/nginx.conf`
- `src/main/java/com/financial/news/FinancialNewsApplication.java`
- `src/main/resources/application.yml`
- `src/main/resources/application-prod.yml`
- `src/main/resources/db/init.sql`
- `src/main/java/com/financial/news/common/{Result,ErrorCode}.java`
- `src/main/java/com/financial/news/config/{SecurityConfig,CrawlerAgentConfig,RedisConfig}.java`
- `src/main/java/com/financial/news/controller/{AuthController,NewsController,AiController,CrawlerController,SystemController}.java`
- `src/main/java/com/financial/news/service/{NewsService,AiService,AuthService}.java`
- `src/main/java/com/financial/news/service/crawler/`
- `src/test/java/com/financial/news/service/AiServiceTest.java`
- `docs/crawler-agent-delivery.md`（专题补充，可能滞后）
- `docs/refactor.md`（历史背景，非现状权威）
