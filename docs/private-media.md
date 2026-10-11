# 私有新闻媒体

## OSS 配置

- Bucket 使用私有权限，保持阻止公共访问开启。
- 上传账号对目标 Bucket 的 `media/*` 目录需要 `oss:PutObject` 和 `oss:GetObject` 权限。密钥仅配置在后端。
- `OSS_ENDPOINT` 使用 Bucket 所在地域的 HTTPS 外网 Endpoint；浏览器不能访问 OSS 内网地址。
- `OSS_REGION` 如 `cn-hangzhou`，标准 Endpoint 可自动推断，其他 Endpoint 需显式配置。
- `OSS_SIGNED_URL_TTL_SECONDS` 默认 3600 秒，实际限制在 60–86400 秒。
- `OSS_CUSTOM_DOMAIN` 可留空。稳定地址可使用该域名；临时读取签名始终使用 OSS 原始外网域名，避免 CDN 改写签名。
- 修改服务器 `.env` 后执行 `docker compose up -d --force-recreate app`，并部署匹配的前端。

## 存储与展示

- 新上传 Key：`media/users/{userId}/{images|videos}/{year}/{month}/{random}.{ext}`。
- 数据库存储无签名的稳定媒体地址；草稿创建、更新和发布时清除自己的 OSS 地址上的签名参数。外部媒体 URL 的查询参数保持原样。
- 未授权的其他用户上传媒体不能写入自己的草稿。存量草稿已有的旧路径继续允许编辑；升级前已上传但未保存的旧路径需重新上传。
- 新闻、草稿、收藏、历史、个人已发布新闻和上传响应，经过业务权限校验后在出站 JSON 副本中生成 V4 GET 签名。不修改数据库实体、Redis 缓存或草稿请求对象。
- 封面、图片、视频、视频 poster 与引用块中的媒体均处理。响应使用 `Cache-Control: private, no-store`。
- 只识别当前配置的 Bucket/自定义域名与 `media/` 目录；不会给外部 URL 或其他 Bucket 签名。
- 更换 Bucket 不会迁移已有文件或旧 Bucket URL。

## 续签

`GET /api/media/access?url=...&newsId=...`：允许访客为该新闻实际引用的文件续签。不存在或被软删除的新闻、未引用的文件均拒绝。

`GET /api/media/access?url=...&draftId=...`：需要登录且为草稿作者，并确认文件被该草稿引用。

`GET /api/media/access?url=...`：用于上传后尚未保存的预览，只允许新 Key 对应的上传者续签。

前端从业务响应记录媒体所属资源，在加载出错、页面恢复可见及每 30 秒检查时，对即将到期/已到期的已展示媒体请求续签。相同文件合并请求；失败至少间隔 30 秒重试。视频刷新保留播放位置及播放状态。

签名 URL 在有效期内持有者均能读取，不能即时撤回已经发出的链接。

## 验证

- `MediaUploadTest`：图片/视频 multipart、文件类型、大小限制、OSS 未配置。
- `PrivateMediaTest`：真实 SDK 离线 V4 签名、作者权限、匿名新闻续签、跨用户引用拒绝、去签名存储、响应副本和缓存隔离。
- 前端 `mediaAccess.spec.ts`：到期解析、新闻/草稿续签上下文、请求合并、视频与 poster、失败退避。

本地测试不写入真实 OSS。上线后需验证真实私有对象的签名读取、匿名普通链接 403、草稿越权拒绝和过期续签。

官方签名说明：https://help.aliyun.com/zh/oss/developer-reference/download-using-a-presigned-url
