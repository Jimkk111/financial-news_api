# Financial News API

基于 Spring Boot 的财经新闻后端，提供新闻浏览与搜索、用户认证、收藏与历史、草稿管理、AI 对话、新闻采集和股票行情接口。

## 技术栈

- Java 23、Spring Boot 3.4.4、Maven
- MySQL 8、MyBatis、Redis 7
- Spring Security / JWT、LangChain4j、OpenAI 兼容接口
- 阿里云 OSS（图片与视频上传）、长桥 / iTick（行情数据）

## 快速启动

安装 Docker 和 Docker Compose，在项目根目录执行：

```bash
cp .env.docker .env
# 修改 .env 中的数据库、Redis、JWT、邮件和 AI 配置
docker compose up -d --build
docker compose logs -f app
```

Windows PowerShell 可用 `Copy-Item .env.docker .env` 复制模板。Compose 会启动 MySQL、Redis 和应用，并在首次创建数据库数据卷时执行初始化 SQL。

- 服务地址：<http://localhost:3000>
- 健康检查：`GET /health`
- 停止服务：`docker compose down`

本地通过 HTTP 登录时，在 `.env` 中设置 `JWT_COOKIE_SECURE=false`；生产环境使用 HTTPS 并保持为 `true`。配置文件中的密码和密钥请替换为自己的值，勿提交真实凭据。

## 本地开发

准备 JDK 23、Maven、MySQL 和 Redis，执行 `src/main/resources/db/init.sql` 初始化数据库。参考 `.env.example`，通过终端环境变量或 IDE 运行配置设置数据库、Redis、JWT 和所需外部服务参数。

```bash
mvn spring-boot:run
mvn test
mvn clean package
```

直接通过 Maven 启动时不会自动读取 `.env`。AI 功能需配置 `AI_API_KEY`、`AI_BASE_URL` 和 `AI_MODEL`；媒体上传和行情功能分别需要 OSS 和行情服务凭据。

新闻定时采集默认每 30 分钟执行，设置 `CRAWLER_INGEST_CRON=-` 可关闭。自然语言爬虫 Agent 默认关闭，可通过 `CRAWLER_AGENT_ENABLED=true` 及对应 AI 配置启用。

## 日志持久化与轮转

本地日志写入 `./logs/financial-news-api.log`，可通过环境变量 `LOG_DIR` 修改目录。控制台和日志文件均使用 UTF-8 编码。

Docker 部署写入 `/app/logs/financial-news-api.log`，由 `logs-data` 数据卷持久保存；重建容器或执行 `docker compose down` 后仍保留，`docker compose down -v` 会删除该卷。

- 每天或单文件达到 50MB 时轮转，归档名为 `financial-news-api.log.YYYY-MM-DD.序号.gz`。
- 归档保留 30 天，总量上限 1GB；启动时也清理过期归档。上限只针对归档，不包含当前日志及 JVM 堆转储。
- 在 `.env` 中通过 `LOG_MAX_FILE_SIZE`、`LOG_MAX_HISTORY`、`LOG_TOTAL_SIZE_CAP` 调整规则，通过 `LOG_LEVEL` 调整应用日志级别；修改后执行 `docker compose up -d --build app` 生效。
- Docker 控制台日志单文件上限 10MB，最多保留 3 个文件。

查看实时日志：

```bash
docker compose logs -f --tail=200 app
# 查看持久化文件（容器中默认保留最后 200 行）
docker compose exec app tail -n 200 -f /app/logs/financial-news-api.log
```

## 目录与文档

```text
src/main/java/com/financial/news/  控制器、业务服务、数据访问与配置
src/main/resources/               应用配置、SQL 初始化与迁移、Mapper XML
src/test/                         测试
docs/                             接口契约与模块接入说明
deploy/                           部署配置
```

- [AI 生成任务与流式接入](docs/ai-generation-lifecycle.md)
- [新闻正文 JSON 契约](docs/content-json-contract.md)
- [行情前端接入](docs/quote-frontend-integration.md)
- [长桥行情配置](docs/quote-longport-integration.md)

新闻和行情查询接口公开，草稿、收藏、AI 对话等接口需要登录。开发环境的 API 文档入口为 `/doc.html`，生产配置默认关闭 Knife4j。

## 许可证

本项目采用 [MIT License](LICENSE)。
