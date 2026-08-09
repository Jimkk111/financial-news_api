# 财经新闻 API 项目文档

> 本文档为 Java 重构提供完整的接口、数据库表结构和配置信息参考。

---

## 一、项目概览

| 项目 | 说明 |
|------|------|
| **技术栈** | Node.js + Express 5 + TypeScript + Prisma ORM + MySQL(MariaDB) + Redis |
| **端口** | 3000（可配置） |
| **数据库** | MySQL (MariaDB Adapter)，数据库名 `cls_financial_news_database` |
| **缓存** | Redis（ioredis），键前缀 `cls:` |
| **认证** | JWT（jsonwebtoken），有效期 7 天，Bearer Token |
| **日志** | Winston（控制台 + 文件），日志目录 `logs/` |
| **验证** | Zod 4 |
| **文件上传** | Multer 2，支持本地存储（OSS 预留） |

### 自定义 ID 生成规则

| ID 类型 | 格式 | 示例 |
|---------|------|------|
| 用户 UID | `user-{uuid前8位}` | `user-a1b2c3d4` |
| 用户 Display ID | `U{时间戳后6位}{3位随机数}` | `U123456001` |
| 草稿 ID | `draft-{uuid前8位}` | `draft-a1b2c3d4` |
| AI 会话 ID | `session-{uuid前8位}` | `session-a1b2c3d4` |
| 验证码 | 6 位随机数字 | `483920` |

---

## 二、数据库表结构

### 2.1 ER 关系图（文字描述）

```
User (1) ──< (N) Draft        (用户 → 草稿)
User (1) ──< (N) Favorite     (用户 → 收藏)
User (1) ──< (N) History      (用户 → 浏览历史)
User (1) ──< (N) AiSession    (用户 → AI会话)
User (1) ──< (N) News         (用户 → 发布的新闻)

Category (1) ──< (N) News     (分类 → 新闻)
Category (1) ──< (N) Draft    (分类 → 草稿)

News (1) ──< (N) NewsTag      (新闻 → 新闻标签关联)
Tag (1) ──< (N) NewsTag       (标签 → 新闻标签关联)

News (1) ──< (N) Favorite     (新闻 → 收藏)
News (1) ──< (N) History      (新闻 → 浏览历史)

AiSession (1) ──< (N) AiMessage  (AI会话 → 消息)

VerificationCode               (独立表，无外键关联)
```

### 2.2 表详细定义

#### users（用户表）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | INT | PK, AUTO_INCREMENT | 内部主键 |
| `uid` | VARCHAR(50) | UNIQUE, NOT NULL | 用户公开 ID，格式 `user-xxxxxxxx` |
| `display_id` | VARCHAR(20) | UNIQUE, NOT NULL | 用户展示 ID，格式 `U123456001` |
| `username` | VARCHAR(50) | UNIQUE, NOT NULL | 用户名 |
| `email` | VARCHAR(100) | UNIQUE, NOT NULL | 邮箱 |
| `password_hash` | VARCHAR(255) | NOT NULL | bcrypt 密码哈希（12轮） |
| `avatar` | VARCHAR(500) | NULLABLE | 头像 URL |
| `created_at` | DATETIME | NOT NULL, DEFAULT NOW() | 创建时间 |
| `updated_at` | DATETIME | NOT NULL, AUTO UPDATE | 更新时间 |
| `deleted_at` | DATETIME | NULLABLE | 软删除时间 |

**索引**: `uid` (UNIQUE), `display_id` (UNIQUE), `username` (UNIQUE), `email` (UNIQUE)

---

#### categories（分类表）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | INT | PK, AUTO_INCREMENT | 主键 |
| `name` | VARCHAR(50) | UNIQUE, NOT NULL | 分类名称 |
| `created_at` | DATETIME | NOT NULL, DEFAULT NOW() | 创建时间 |

---

#### tags（标签表）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | INT | PK, AUTO_INCREMENT | 主键 |
| `name` | VARCHAR(50) | UNIQUE, NOT NULL | 标签名称 |
| `created_at` | DATETIME | NOT NULL, DEFAULT NOW() | 创建时间 |

---

#### news（新闻表）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | INT | PK, AUTO_INCREMENT | 主键 |
| `title` | VARCHAR(200) | NOT NULL | 标题 |
| `summary` | TEXT | NULLABLE | 摘要 |
| `content` | TEXT | NULLABLE | 正文内容 |
| `publish_time` | DATETIME | NULLABLE | 发布时间 |
| `source` | VARCHAR(100) | NULLABLE | 来源 |
| `views` | INT | NOT NULL, DEFAULT 0 | 浏览量 |
| `has_image` | BOOLEAN | NOT NULL, DEFAULT FALSE | 是否有图片 |
| `image_url` | VARCHAR(500) | NULLABLE | 图片 URL |
| `category_id` | INT | NULLABLE, FK → categories.id | 分类 ID（SET NULL） |
| `user_id` | INT | NULLABLE, FK → users.id | 发布用户 ID（SET NULL） |
| `created_at` | DATETIME | NOT NULL, DEFAULT NOW() | 创建时间 |
| `updated_at` | DATETIME | NOT NULL, AUTO UPDATE | 更新时间 |
| `deleted_at` | DATETIME | NULLABLE | 软删除时间 |

**索引**: `(category_id)`, `(publish_time)`, `(views)`, `(user_id)`

---

#### news_tags（新闻-标签关联表）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | INT | PK, AUTO_INCREMENT | 主键 |
| `news_id` | INT | NOT NULL, FK → news.id (CASCADE) | 新闻 ID |
| `tag_id` | INT | NOT NULL, FK → tags.id (CASCADE) | 标签 ID |
| `created_at` | DATETIME | NOT NULL, DEFAULT NOW() | 创建时间 |

**索引**: `UNIQUE(news_id, tag_id)`, `(news_id)`, `(tag_id)`

---

#### drafts（草稿表）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | VARCHAR(50) | PK | 草稿 ID，格式 `draft-xxxxxxxx` |
| `user_id` | INT | NOT NULL, FK → users.id (CASCADE) | 用户 ID |
| `title` | VARCHAR(200) | NULLABLE | 标题 |
| `content` | TEXT | NULLABLE | 内容 |
| `cover_image` | VARCHAR(500) | NULLABLE | 封面图 URL |
| `category_id` | INT | NULLABLE, FK → categories.id (SET NULL) | 分类 ID |
| `status` | VARCHAR(20) | NOT NULL, DEFAULT 'draft' | 状态：`draft` / `published` |
| `created_at` | DATETIME | NOT NULL, DEFAULT NOW() | 创建时间 |
| `updated_at` | DATETIME | NOT NULL, AUTO UPDATE | 更新时间 |

**索引**: `(user_id)`, `(status)`

---

#### favorites（收藏表）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | INT | PK, AUTO_INCREMENT | 主键 |
| `user_id` | INT | NOT NULL, FK → users.id (CASCADE) | 用户 ID |
| `news_id` | INT | NOT NULL, FK → news.id (CASCADE) | 新闻 ID |
| `created_at` | DATETIME | NOT NULL, DEFAULT NOW() | 收藏时间 |

**索引**: `UNIQUE(user_id, news_id)`, `(user_id)`, `(news_id)`

---

#### history（浏览历史表）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | INT | PK, AUTO_INCREMENT | 主键 |
| `user_id` | INT | NOT NULL, FK → users.id (CASCADE) | 用户 ID |
| `news_id` | INT | NOT NULL, FK → news.id (CASCADE) | 新闻 ID |
| `viewed_at` | DATETIME | NOT NULL, DEFAULT NOW() | 浏览时间 |

**索引**: `(user_id)`, `(viewed_at)`

---

#### ai_sessions（AI 会话表）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | INT | PK, AUTO_INCREMENT | 内部主键 |
| `session_id` | VARCHAR(50) | UNIQUE, NOT NULL | 会话公开 ID，格式 `session-xxxxxxxx` |
| `user_id` | INT | NOT NULL, FK → users.id (CASCADE) | 用户 ID |
| `title` | VARCHAR(100) | NULLABLE | 会话标题（AI 自动生成，最长30字截断） |
| `created_at` | DATETIME | NOT NULL, DEFAULT NOW() | 创建时间 |
| `updated_at` | DATETIME | NOT NULL, AUTO UPDATE | 更新时间 |

**索引**: `(user_id)`, `(session_id)` (UNIQUE)

---

#### ai_messages（AI 消息表）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | INT | PK, AUTO_INCREMENT | 主键 |
| `session_id` | INT | NOT NULL, FK → ai_sessions.id (CASCADE) | 会话内部 ID |
| `role` | VARCHAR(20) | NOT NULL | 角色：`user` / `assistant` / `system` |
| `content` | TEXT | NOT NULL | 消息内容 |
| `created_at` | DATETIME | NOT NULL, DEFAULT NOW() | 创建时间 |

**索引**: `(session_id)`

---

#### verification_codes（验证码表）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | INT | PK, AUTO_INCREMENT | 主键 |
| `email` | VARCHAR(100) | NOT NULL | 邮箱 |
| `code` | VARCHAR(10) | NOT NULL | 6 位数字验证码 |
| `username` | VARCHAR(50) | NULLABLE | 关联用户名（注册场景） |
| `expires_at` | DATETIME | NOT NULL | 过期时间（创建后 5 分钟） |
| `created_at` | DATETIME | NOT NULL, DEFAULT NOW() | 创建时间 |

**索引**: `(email)`, `(expires_at)`

---

## 三、API 接口文档

### 3.1 通用约定

**Base URL**: `http://localhost:3000/api`

**通用响应格式**:

```json
// 成功 - 单条数据
{ "success": true, "data": { ... } }

// 成功 - 分页数据
{
  "success": true,
  "data": [ ... ],
  "pagination": {
    "page": 1,
    "pageSize": 10,
    "total": 100,
    "totalPages": 10
  }
}

// 失败
{
  "success": false,
  "error": {
    "code": "ERROR_CODE",
    "message": "错误描述",
    "details": [{ "field": "字段名", "message": "错误信息" }]
  }
}
```

**认证方式**: `Authorization: Bearer <access_token>`

**请求头**: `Content-Type: application/json`，可选 `X-Request-ID`（请求追踪）

**HTTP 状态码**: 200(成功), 201(创建), 204(无内容), 400(参数错误), 401(未认证), 403(无权限), 404(不存在), 409(冲突), 413(文件过大), 422(验证失败), 429(限流), 500(服务器错误)

---

### 3.2 认证模块 `/api/auth`

| 方法 | 路径 | 认证 | 限流 | 说明 |
|------|------|------|------|------|
| POST | `/api/auth/login` | 否 | authLimiter | 用户登录 |
| POST | `/api/auth/register` | 否 | authLimiter | 用户注册 |
| POST | `/api/auth/logout` | 否 | 无 | 用户登出（仅返回成功） |
| POST | `/api/auth/send-code` | 否 | sendCodeLimiter | 发送邮箱验证码 |
| POST | `/api/auth/reset-password` | 否 | authLimiter | 重置密码 |

#### POST /api/auth/login

**Request Body**:
```json
{
  "username": "string (1-50字符)",
  "password": "string (6-128字符)"
}
```
> 注：`username` 字段支持传用户名或邮箱

**Response**:
```json
{
  "success": true,
  "data": {
    "access_token": "jwt_token_string",
    "user": {
      "id": 1,
      "uid": "user-a1b2c3d4",
      "username": "john",
      "email": "john@example.com",
      "avatar": null
    }
  }
}
```

#### POST /api/auth/register

**Request Body**:
```json
{
  "username": "string (3-50字符, 字母开头+字母/数字/下划线)",
  "email": "string (邮箱格式, 最长100字符)",
  "password": "string (8-128字符, 必须包含字母和数字)",
  "code": "string (6位数字验证码)"
}
```

**Response**: 同 login，返回 `access_token` + `user`

#### POST /api/auth/send-code

**Request Body**:
```json
{
  "email": "string (邮箱格式, 最长100字符)",
  "username": "string (可选, 最长50字符)"
}
```

**Response**: `{ "success": true, "data": { "message": "验证码已发送" } }`

**说明**: 验证码有效期 5 分钟，同一邮箱 1 小时内最多 5 次

#### POST /api/auth/reset-password

**Request Body**:
```json
{
  "username": "string (1-50字符)",
  "email": "string (邮箱格式)",
  "code": "string (6位数字验证码)",
  "password": "string (8-128字符, 必须含字母和数字)"
}
```

**Response**: `{ "success": true, "data": { "message": "密码重置成功" } }`

**说明**: 需先调用 send-code 获取验证码；用户名与邮箱必须匹配

---

### 3.3 用户模块 `/api/users`

| 方法 | 路径 | 认证 | 限流 | 说明 |
|------|------|------|------|------|
| GET | `/api/users/me` | 是 | generalLimiter | 获取当前用户信息 |
| PUT | `/api/users/me` | 是 | generalLimiter | 更新当前用户信息 |
| POST | `/api/users/me/avatar` | 是 | uploadLimiter | 上传头像 |
| GET | `/api/users/me/news` | 是 | generalLimiter | 获取当前用户发布的新闻列表 |

#### GET /api/users/me

**Response**:
```json
{
  "success": true,
  "data": {
    "uid": "user-a1b2c3d4",
    "displayId": "U123456001",
    "username": "john",
    "email": "john@example.com",
    "avatar": null,
    "createdAt": "2025-01-01T00:00:00.000Z",
    "updatedAt": "2025-01-01T00:00:00.000Z"
  }
}
```

#### PUT /api/users/me

**Request Body** (至少提供一个字段):
```json
{
  "username": "string (可选, 3-50字符, 字母开头)",
  "email": "string (可选, 邮箱格式)"
}
```

**Response**: 同 GET /me 的用户对象

#### POST /api/users/me/avatar

**Request**: `multipart/form-data`, 字段名 `avatar`
- 文件大小最大 2MB
- 支持类型：`image/jpeg`, `image/png`, `image/gif`, `image/webp`

**Response**: `{ "success": true, "data": { "avatar": "/uploads/avatars/2025/01/xxx.jpg" } }`

#### GET /api/users/me/news

**Query Params**: `page` (默认1), `pageSize` (默认10, 最大50)

**Response**: 分页新闻列表（格式同新闻列表接口）

---

### 3.4 新闻模块 `/api/news`

| 方法 | 路径 | 认证 | 限流 | 说明 |
|------|------|------|------|------|
| GET | `/api/news` | 否 | generalLimiter | 获取新闻列表（分页） |
| GET | `/api/news/:id` | 否 | generalLimiter | 获取新闻详情 |
| POST | `/api/news/:id/views` | 否 | generalLimiter | 增加浏览量 |
| GET | `/api/news/categories` | 否 | generalLimiter | 获取分类列表 |
| GET | `/api/news/tags` | 否 | generalLimiter | 获取标签列表 |
| GET | `/api/news/search` | 否 | searchLimiter | 搜索新闻 |

#### GET /api/news

**Query Params**:
| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `page` | int | 1 | 页码 |
| `pageSize` | int | 10 | 每页条数（最大50） |
| `categoryId` | int | — | 分类筛选 |
| `sort` | enum | `newest` | 排序：`newest`(按发布时间倒序) / `popular`(按浏览量倒序) |

**Response** (分页):
```json
{
  "success": true,
  "data": [{
    "id": 1,
    "title": "新闻标题",
    "summary": "摘要内容",
    "publishTime": "2025-01-01T00:00:00.000Z",
    "source": "来源",
    "views": 100,
    "hasImage": true,
    "imageUrl": "/uploads/news/2025/01/xxx.jpg",
    "categoryId": 1,
    "category": { "id": 1, "name": "财经" },
    "tags": [{ "id": 1, "name": "股市" }]
  }],
  "pagination": { "page": 1, "pageSize": 10, "total": 100, "totalPages": 10 }
}
```

#### GET /api/news/:id

**Response**: 单条新闻详情（比列表多了 `content` 和 `createdAt`）

#### POST /api/news/:id/views

**Response**: `{ "success": true, "data": { "id": 1, "views": 101 } }`

**说明**: 浏览量 +1，同时清除该新闻详情缓存

#### GET /api/news/categories

**Response**: `{ "success": true, "data": [{ "id": 1, "name": "财经" }, ...] }`

#### GET /api/news/tags

**Response**: `{ "success": true, "data": [{ "id": 1, "name": "股市" }, ...] }`

#### GET /api/news/search

**Query Params**:
| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `keyword` | string | 必填 | 搜索关键词（1-100字符） |
| `page` | int | 1 | 页码 |
| `pageSize` | int | 10 | 每页条数（最大50） |

**Response**: 分页新闻列表，搜索范围为 `title` 和 `summary` 的 `LIKE %keyword%`

---

### 3.5 收藏模块 `/api/favorites`

> 所有接口需要认证

| 方法 | 路径 | 限流 | 说明 |
|------|------|------|------|
| GET | `/api/favorites` | generalLimiter | 获取收藏列表（分页） |
| POST | `/api/favorites` | generalLimiter | 添加收藏 |
| DELETE | `/api/favorites/:newsId` | generalLimiter | 取消收藏 |
| GET | `/api/favorites/check/:newsId` | generalLimiter | 检查是否已收藏 |

#### GET /api/favorites

**Query Params**: `page` (默认1), `pageSize` (默认10, 最大50)

**Response** (分页，每项包含 `favoriteAt` 收藏时间):
```json
{
  "success": true,
  "data": [{
    "id": 1, "title": "...", "...": "...",
    "favoriteAt": "2025-01-01T00:00:00.000Z"
  }],
  "pagination": { ... }
}
```

#### POST /api/favorites

**Request Body**:
```json
{
  "newsId": 1
}
```

**Response**: `{ "success": true, "data": { "message": "收藏成功" } }`

**错误**: 新闻不存在(404), 已收藏(409)

#### DELETE /api/favorites/:newsId

**Response**: `{ "success": true, "data": { "message": "取消收藏成功" } }`

**错误**: 未收藏该新闻(404)

#### GET /api/favorites/check/:newsId

**Response**: `{ "success": true, "data": { "is_favorite": true } }`

---

### 3.6 浏览历史模块 `/api/history`

> 所有接口需要认证

| 方法 | 路径 | 限流 | 说明 |
|------|------|------|------|
| GET | `/api/history` | generalLimiter | 获取浏览历史（分页） |
| POST | `/api/history` | generalLimiter | 添加浏览记录 |
| DELETE | `/api/history` | generalLimiter | 清空浏览历史 |

#### GET /api/history

**Query Params**: `page` (默认1), `pageSize` (默认10, 最大50)

**Response** (分页，按 `viewedAt` 倒序，每项包含 `viewedAt` 浏览时间)

#### POST /api/history

**Request Body**:
```json
{
  "newsId": 1
}
```

**说明**: 若已有该新闻的浏览记录则更新 `viewedAt` 时间，否则新增

**Response**: `{ "success": true, "data": { "message": "添加浏览记录成功" } }`

#### DELETE /api/history

**Response**: `{ "success": true, "data": { "message": "清空浏览历史成功" } }`

---

### 3.7 草稿模块 `/api/drafts`

> 所有接口需要认证

| 方法 | 路径 | 限流 | 说明 |
|------|------|------|------|
| GET | `/api/drafts` | generalLimiter | 获取草稿列表 |
| POST | `/api/drafts` | generalLimiter | 创建草稿 |
| GET | `/api/drafts/:id` | generalLimiter | 获取草稿详情 |
| PUT | `/api/drafts/:id` | generalLimiter | 更新草稿 |
| DELETE | `/api/drafts/:id` | generalLimiter | 删除草稿 |
| POST | `/api/drafts/:id/publish` | generalLimiter | 发布草稿 |

#### GET /api/drafts

**Response**: 所有 `status='draft'` 的草稿列表（不分页，按 updatedAt 倒序）

#### POST /api/drafts

**Request Body**:
```json
{
  "title": "string (必填, 1-200字符)",
  "content": "string (可选, 最长50000字符)",
  "coverImage": "string (可选, 有效URL, 最长500字符)",
  "categoryId": "int (可选, 正整数, 需存在)"
}
```

**Response** (201): 草稿对象

#### GET /api/drafts/:id

**Response**: 草稿详情（含 category 关联信息）

#### PUT /api/drafts/:id

**Request Body**: 同 create 但所有字段可选；已发布的草稿不可编辑

**Response**: 更新后的草稿对象

#### DELETE /api/drafts/:id

**Response**: `{ "success": true, "data": { "message": "草稿删除成功" } }`

#### POST /api/drafts/:id/publish

**说明**: 将草稿转为新闻发布。要求：草稿状态为 `draft` 且标题不为空。事务操作：创建 news + 更新 draft.status='published'

**Response** (201): 新创建的新闻对象

**业务逻辑**:
- `summary` = content 前 200 字符
- `hasImage` = coverImage 是否非空
- `source` = 发布用户的 username
- `publishTime` = 当前时间
- `views` = 0

---

### 3.8 AI 模块 `/api/ai`

> 除 health 外所有接口需要认证

| 方法 | 路径 | 限流 | 说明 |
|------|------|------|------|
| GET | `/api/ai/sessions` | generalLimiter | 获取会话列表 |
| POST | `/api/ai/sessions` | generalLimiter | 创建新会话 |
| GET | `/api/ai/sessions/:sessionId/messages` | generalLimiter | 获取会话消息列表 |
| PUT | `/api/ai/sessions/:sessionId` | generalLimiter | 更新会话标题 |
| DELETE | `/api/ai/sessions/:sessionId` | generalLimiter | 删除会话 |
| POST | `/api/ai/chat` | aiLimiter | AI 对话（支持流式） |
| GET | `/api/ai/health` | generalLimiter | AI 服务健康检查 |

#### GET /api/ai/sessions

**Response**:
```json
{
  "success": true,
  "data": [{
    "sessionId": "session-a1b2c3d4",
    "title": "对话标题",
    "createdAt": "...",
    "updatedAt": "...",
    "lastMessage": "最后一条消息前100字符"
  }]
}
```

#### POST /api/ai/sessions

**Response**: `{ "success": true, "data": { "session_id": "session-a1b2c3d4" } }`

#### GET /api/ai/sessions/:sessionId/messages

**参数**: `sessionId` 格式 `session-xxxxxxxx` (8位hex)

**Response**: `{ "success": true, "data": [{ "role": "user", "content": "...", "createdAt": "..." }, ...] }`

#### PUT /api/ai/sessions/:sessionId

**Request Body**:
```json
{ "title": "string (1-100字符)" }
```

#### DELETE /api/ai/sessions/:sessionId

**Response**: `{ "success": true, "data": { "message": "删除成功" } }`

**说明**: 级联删除关联的所有消息

#### POST /api/ai/chat

**Request Body**:
```json
{
  "messages": [{
    "role": "user | assistant | system",
    "content": "string (1-4000字符)"
  }],
  "sessionId": "string (可选, 格式 session-xxxxxxxx)",
  "stream": "boolean (可选, 默认false)"
}
```
- `messages` 数组长度 1-50，发送时截取最后 20 条
- `sessionId` 不传则自动创建新会话

**非流式 Response**:
```json
{
  "success": true,
  "data": {
    "role": "assistant",
    "content": "AI回复内容",
    "sessionId": "session-a1b2c3d4"
  }
}
```

**流式 Response** (`stream: true`):
```
Content-Type: text/event-stream

data: {"content":"你"}
data: {"content":"好"}
data: {"sessionId":"session-a1b2c3d4"}
data: [DONE]
```

**说明**:
- 首次对话自动生成会话标题（调用 AI 生成，最长 30 字符截断）
- 消息持久化在事务中完成（user消息 + assistant消息 + 会话title更新）
- AI API 基于 OpenAI 兼容接口

#### GET /api/ai/health

**Response**: `{ "success": true, "status": "healthy" }`

---

### 3.9 系统接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/health` | 服务健康检查 |
| GET | `/api` | API 版本信息 |

#### GET /health

**Response**:
```json
{
  "success": true,
  "data": {
    "status": "healthy",
    "timestamp": "2025-01-01T00:00:00.000Z",
    "uptime": 12345.678
  }
}
```

#### GET /api

**Response**: `{ "success": true, "data": { "message": "财经新闻API服务", "version": "1.0.0" } }`

---

### 3.10 静态文件

- 上传文件通过 `/uploads/**` 路径提供静态访问
- 文件存储结构: `uploads/{category}/{year}/{month}/{timestamp}_{random}.{ext}`
- 例如: `uploads/avatars/2025/01/1704067200000_a1b2c3.jpg`

---

## 四、配置信息

### 4.1 环境变量（`.env`）

```bash
# ===== 运行环境 =====
NODE_ENV=development          # development | production
PORT=3000                     # 服务端口

# ===== 数据库 =====
DATABASE_URL="mysql://root:password@localhost:3306/cls_financial_news_database"

# ===== JWT 认证 =====
JWT_SECRET=your_jwt_secret_key_here
JWT_EXPIRES_IN=7d             # Token 有效期

# ===== Redis 缓存 =====
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=               # 为空则不设密码
REDIS_DB=0

# ===== SMTP 邮件 =====
SMTP_HOST=smtp.example.com
SMTP_PORT=587
SMTP_SECURE=false             # true 表示 SSL (端口465时自动启用)
SMTP_USER=your_email@example.com
SMTP_PASS=your_smtp_password
SMTP_FROM=noreply@example.com # 可选，默认使用 SMTP_USER

# ===== AI 服务 (OpenAI 兼容接口) =====
AI_API_KEY=your_ai_api_key
AI_API_BASE_URL=https://api.openai.com/v1   # 也支持 AI_API_URL
AI_MODEL=gpt-3.5-turbo
AI_MAX_TOKENS=2000
AI_TEMPERATURE=0.7

# ===== 文件上传 =====
UPLOAD_STORAGE_TYPE=local     # local | oss（OSS 当前未实现）
UPLOAD_MAX_SIZE=5242880       # 默认 5MB（字节）
UPLOAD_ALLOWED_TYPES=image/jpeg,image/png,image/gif,image/webp
UPLOAD_DIR=uploads
UPLOAD_URL_PREFIX=/uploads

# ===== OSS（当前预留，未实现）=====
OSS_ACCESS_KEY_ID=
OSS_ACCESS_KEY_SECRET=
OSS_BUCKET=
OSS_REGION=
OSS_ENDPOINT=

# ===== CORS =====
CORS_ORIGIN=*                 # 生产环境应改为具体域名
```

### 4.2 Redis 缓存策略

| 缓存键模式 | TTL | 说明 |
|-----------|-----|------|
| `cls:news:list:{base64}` | 300s (5分钟) | 新闻列表分页缓存 |
| `cls:news:detail:{id}` | 600s (10分钟) | 新闻详情缓存 |
| `cls:news:categories` | 3600s (1小时) | 分类列表缓存 |
| `cls:news:tags` | 3600s (1小时) | 标签列表缓存 |

> 全局键前缀: `cls:`，默认 TTL: 300s。Redis 不可用时跳过缓存（不阻塞业务）。

### 4.3 限流策略

| 限流器 | 时间窗口 | 最大请求数 | 键策略 | 适用场景 |
|--------|----------|-----------|--------|---------|
| `authLimiter` | 15分钟 | 10次 | IP | 登录、注册、重置密码 |
| `sendCodeLimiter` | 1小时 | 5次 | IP | 发送验证码 |
| `aiLimiter` | 1小时 | 30次 | 用户UID | AI 对话 |
| `uploadLimiter` | 1小时 | 20次 | 用户UID | 文件上传 |
| `searchLimiter` | 1分钟 | 30次 | IP | 新闻搜索 |
| `generalLimiter` | 1分钟 | 100次 | 用户UID优先, IP回退 | 所有 `/api/*` 路由 |

### 4.4 密码规则

- 加密算法: bcrypt，12 轮盐值
- 最小长度 8 位，最大 128 位
- 必须包含至少 1 个字母和 1 个数字

### 4.5 用户名规则

- 长度 3-50 字符
- 必须以字母开头，只允许字母、数字、下划线
- 正则: `^[a-zA-Z][a-zA-Z0-9_]*$`

### 4.6 验证码规则

- 6 位随机数字
- 有效期 5 分钟
- 验证成功后立即删除

### 4.7 JWT Token Payload

```typescript
{
  userId: number    // 数据库内部 ID
  uid: string       // 用户公开 UID
  username: string  // 用户名
  iat: number       // 签发时间戳
  exp: number       // 过期时间戳（7天后）
}
```

签发者: `cls-financial-news`，受众: `cls-financial-news-users`

### 4.8 文件上传配置

| 配置项 | 值 |
|--------|-----|
| 存储类型 | `local`（`oss` 预留未实现） |
| 默认最大文件大小 | 5MB |
| 头像上传最大 | 2MB |
| 允许的文件类型 | JPEG, PNG, GIF, WebP |
| 存储路径格式 | `{uploadDir}/{category}/{year}/{month}/{timestamp}_{random}.{ext}` |
| URL 前缀 | `/uploads` |

### 4.9 中间件调用链

```
helmet → cors → compression → JSON解析 → morgan(日志) → X-Request-ID → 静态文件(/uploads) → /health → rateLimit → 路由 → 404 → errorHandler
```

---

## 五、错误码汇总

| 错误码 | HTTP 状态码 | 说明 |
|--------|------------|------|
| `BAD_REQUEST` | 400 | 请求参数错误 |
| `UNAUTHORIZED` | 401 | 未认证或 Token 无效 |
| `FORBIDDEN` | 403 | 权限不足 |
| `NOT_FOUND` | 404 | 资源不存在 |
| `CONFLICT` | 409 | 资源冲突（如重复收藏） |
| `DUPLICATE_ENTRY` | 409 | 数据库唯一键冲突 |
| `VALIDATION_ERROR` | 422 | 参数验证失败 |
| `RATE_LIMIT_EXCEEDED` | 429 | 请求频率超限 |
| `INTERNAL_ERROR` | 500 | 服务器内部错误 |
| `FILE_TOO_LARGE` | 413 | 上传文件过大 |
| `FILE_COUNT_EXCEEDED` | 422 | 上传文件数量超限 |
| `INVALID_FILE_TYPE` | 422 | 文件类型不支持 |
| `USERNAME_EXISTS` | 422 | 用户名已被使用 |
| `EMAIL_EXISTS` | 422 | 邮箱已被使用 |
| `NO_UPDATE_DATA` | 422 | 无更新数据 |
| `NO_FILE` | 422 | 未提供文件 |

---

## 六、技术依赖对照表

| 功能 | Node.js 依赖 | Java 建议替代 |
|------|-------------|--------------|
| Web 框架 | Express 5 | Spring Boot Web / WebFlux |
| ORM | Prisma 7 + MariaDB Adapter | MyBatis-Plus / JPA + Hibernate |
| 数据库驱动 | mariadb 3.5 | MySQL Connector/J |
| 缓存 | ioredis 5 | Spring Data Redis / Jedis / Lettuce |
| JWT | jsonwebtoken 9 | jjwt / Nimbus JOSE + JWT |
| 密码加密 | bcryptjs 3 | Spring Security BCryptPasswordEncoder |
| 参数验证 | Zod 4 | Jakarta Bean Validation (Hibernate Validator) |
| 文件上传 | multer 2 | Spring MultipartFile |
| 邮件 | nodemailer 8 | Spring Mail (JavaMailSender) |
| 限流 | express-rate-limit 8 | Bucket4j / Guava RateLimiter |
| 日志 | winston 3 | SLF4J + Logback |
| 安全头 | helmet 8 | Spring Security headers |
| CORS | cors 2 | Spring CORS 配置 |
| 压缩 | compression 1 | Spring Gzip 压缩 |
| UUID | uuid 13 | java.util.UUID |
| AI/HTTP | fetch (内置) | Spring WebClient / RestTemplate / OkHttp |
| SSE 流式 | 原生 Response.write | Spring WebFlux SSE / SseEmitter |

---

## 七、项目目录结构

```
financial-news-api/
├── prisma/
│   ├── schema.prisma          # 数据库模型定义
│   ├── seed.ts                # 种子数据
│   └── init-database.sql      # 数据库初始化 SQL
├── src/
│   ├── index.ts               # 入口（启动服务器）
│   ├── app.ts                 # Express 应用配置
│   ├── config/                # 配置层
│   │   ├── ai.ts              # AI 服务配置
│   │   ├── database.ts        # 数据库连接
│   │   ├── index.ts           # 配置导出
│   │   ├── jwt.ts             # JWT 配置
│   │   ├── rateLimit.ts       # 限流配置
│   │   ├── redis.ts           # Redis 连接
│   │   └── upload.ts          # 文件上传配置
│   ├── controllers/           # 控制器层
│   │   ├── ai.controller.ts
│   │   ├── auth.controller.ts
│   │   ├── draft.controller.ts
│   │   ├── favorite.controller.ts
│   │   ├── history.controller.ts
│   │   ├── news.controller.ts
│   │   └── user.controller.ts
│   ├── middlewares/           # 中间件层
│   │   ├── auth.middleware.ts    # JWT 认证
│   │   ├── error.middleware.ts   # 错误处理
│   │   ├── rateLimit.middleware.ts # 限流
│   │   ├── upload.middleware.ts  # 文件上传
│   │   └── validate.middleware.ts # 参数验证
│   ├── routes/                # 路由层
│   │   ├── index.ts           # 路由汇总
│   │   ├── ai.routes.ts
│   │   ├── auth.routes.ts
│   │   ├── drafts.routes.ts
│   │   ├── favorites.routes.ts
│   │   ├── history.routes.ts
│   │   ├── news.routes.ts
│   │   └── users.routes.ts
│   ├── services/              # 业务逻辑层
│   │   ├── ai.service.ts
│   │   ├── auth.service.ts
│   │   ├── cache.service.ts
│   │   ├── draft.service.ts
│   │   ├── email.service.ts
│   │   ├── favorite.service.ts
│   │   ├── file.service.ts
│   │   ├── history.service.ts
│   │   ├── news.service.ts
│   │   └── user.service.ts
│   ├── types/                 # 类型定义
│   │   ├── index.ts           # 错误类和枚举
│   │   └── express.d.ts       # Express 类型扩展
│   ├── utils/                 # 工具函数
│   │   ├── cacheWrapper.ts    # 缓存装饰器
│   │   ├── idGenerator.ts     # ID 生成器
│   │   ├── logger.ts          # 日志
│   │   ├── password.ts        # 密码哈希
│   │   ├── response.ts        # 响应格式化
│   │   └── token.ts           # JWT Token 工具
│   └── validators/            # 请求验证 Schema
│       ├── ai.validator.ts
│       ├── auth.validator.ts
│       ├── draft.validator.ts
│       ├── favorite.validator.ts
│       ├── history.validator.ts
│       ├── news.validator.ts
│       └── user.validator.ts
├── logs/                      # 日志文件目录
├── uploads/                   # 上传文件目录
├── .env / .env.example        # 环境变量
├── package.json               # 依赖配置
├── tsconfig.json              # TypeScript 配置
└── nodemon.json               # 开发热重载配置
```

---

*文档生成日期: 2026-08-09*
