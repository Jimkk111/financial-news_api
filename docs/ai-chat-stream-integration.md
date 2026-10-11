# AI 对话流式接口对接文档（思考链版）

> 2026-10-11 更新：生成任务现已持久化，断开 SSE 不会停止生成。新增创建、快照、续传和取消接口详见 [生成任务生命周期与前端接入](ai-generation-lifecycle.md)。停止按钮必须调用取消接口。

> 背景：思考型模型（MiMo 等）生成正文前会先输出 `reasoning_content` 思考链。旧后端用的
> langchain4j 不映射该字段，思考期间前端收不到任何数据，表现为「发消息后十几秒无响应，
> 然后内容一瞬间全部到达」。本次后端改为直连上游并把思考链实时透传，**SSE 事件契约有新增**。

## 一、流式对话 `POST /api/ai/chat/stream`

- Content-Type: `application/json`；响应 `text/event-stream`
- 鉴权：HttpOnly Cookie `jwt_token`（同现有方式）
- 请求体：`{ "messages": [...], "sessionId": "...", "webSearch": true }`
  - **`webSearch`（可选，默认 false）**：开启 MiMo Web Search 联网搜索

### 事件契约（data 行 JSON，按到达顺序）

| 顺序 | 事件 | 字段 | 说明 |
|---|---|---|---|
| 1 | `{"sessionId":"session-xxxx"}` | `sessionId` | **请求受理即发送**（原来在结束时发）。收到即表示连接已建立，可用于绑定会话 |
| 2 | `{"sources":[{title,url,summary,siteName,publishTime,logoUrl}]}` | `sources` | **联网搜索专用**。引用来源数组，随上游首包一次性到达（可能多批，前端按批追加）。`webSearch:false` 时不会出现 |
| 3..n | `{"reasoning":"增量文本"}` | `reasoning` | 思考链增量，正文开始前持续到达。**一个事件只含一小段，需要前端自行拼接** |
| n+1.. | `{"content":"增量文本"}` | `content` | 正文增量，同旧逻辑；开启搜索后正文内会出现 `[1]` `[2]` 引用编号，可与 sources 下标对应 |
| 末尾-1 | `{"error":"AI 响应内容为空"}` | `error` | 仅异常场景：上游只输出了思考链没有正文。发送后仍会发 `[DONE]`，按错误处理 |
| 末尾 | `[DONE]` | — | 正常结束标记（纯文本，非 JSON） |

### 联网搜索说明（前端相关）

- UI 上建议做成输入框旁的「联网搜索」开关，映射到请求体 `webSearch`。
- 来源卡片建议渲染 `favicon = logoUrl`、`标题 = title`、`域名/站点 = siteName`，点击跳转 `url`；
  正文中的 `[n]` 引用标记可 hover/点击定位到对应来源。
- 后端会自动注入带当前日期的系统提示（模型自己知道时效），前端无需传时间。
- 搜索由模型意图识别触发（`force-search` 默认 false），所以开启开关后也可能没有 sources 事件——
  模型判断不需要搜索时直接回答，属正常情况，前端按「有就渲染」处理。

### 其它链路行为

- **心跳**：静默期每 15s 发一条 **data 事件** `{"heartbeat":true}`（v2 起不再用 SSE 注释行——
  部分网关/CDN 会吞注释行导致前端空闲超时误杀）。前端按未知字段忽略即可，
  `consumeEvents` 对无 `content`/`reasoning`/`sources`/`sessionId` 字段的 JSON 天然跳过；
  若自行解析，注意**不要把 heartbeat 当错误或结束信号**。
- **前端空闲超时建议**：联网搜索模式下，服务商要先执行搜索再吐首包（搜索+思考的首 token
  可达 30s+），`webSearch=true` 时建议把空闲超时放宽到 60s（或按"收到任何字节"重置计时），
  否则可能误杀长搜索请求（后端表现为 Broken pipe）。
- **早冲刷**：响应头随第 1 个事件立即提交（原来要等首个正文 token），DevTools 不再长时间 pending。
- **断连继续**：关闭 SSE、页面退出或网络异常只结束订阅，后台继续保存生成内容。
- **手动停止**：收到初始化事件中的 `generationId` 后，调用 `POST /api/ai/generations/{generationId}/cancel`。
  只关闭 SSE 已不再停止上游；停止和失败都保留已保存的回复。
- 超时：订阅 300s，上游任务 280s，订阅超时不取消后台任务。
- **持久化与重连**：每个增量事件先提交数据库，事件带 `seq` 和 SSE `id`；
  重连使用新 GET 订阅端点或快照，不能无幂等键重新提交 POST。

### 实测时序（mock 上游，可直接作为前端开发的联调预期）

```
t=0.00s  data:{"sessionId":"session-bd7eec0e"}         ← 立即到达
t+0.7s   data:{"sources":[...2 条引用...]}              ← webSearch=true 时
t+0.9s   data:{"reasoning":"用户要最新行情。"}            ← 思考链开始滚动
...
t+1.8s   data:{"content":"根据最新消息[1]"}               ← 正文开始（含引用编号）
...
t+2.5s   data:[DONE]
```

## 二、非流式对话 `POST /api/ai/chat`

请求体同上（含可选 `webSearch`）。响应 `data` 新增可选字段：

```json
{ "role": "assistant", "content": "...", "sessionId": "session-xxxx",
  "reasoning": "完整思考链", "sources": [{ "title": "...", "url": "...", "summary": "...", "siteName": "...", "publishTime": "...", "logoUrl": "..." }] }
```

`reasoning` / `sources` 可能为空/缺失（非思考型模型、未开搜索），前端按可选字段处理。

## 三、历史消息 `GET /api/ai/sessions/{sessionId}/messages`

assistant 消息对象新增字段（均可为 null）：

```json
{ "id": 1, "sessionId": 75, "role": "assistant", "content": "回复正文",
  "reasoningContent": "完整思考链", "sources": "[{...同上 JSON 数组字符串...}]",
  "createdAt": "..." }
```

注意 `sources` 在此接口是 **JSON 字符串**（数据库列原样返回），前端需 `JSON.parse`；
建议归一化时映射 `sources ? JSON.parse(sources) : undefined`。

## 四、前端改动建议（最小集）

1. `streamChat` 增加 `onReasoning` 回调（现有 `consumeEvents` 里加一个 `parsed.reasoning` 分支即可，
   拼接逻辑与 `content` 相同）；`StreamChatResult` 增加 `reasoning: string`。
2. 消息模型（`Message`/`ChatMessage`）加 `reasoning?: string`；store 里思考增量可复用现有
   50ms 批量刷新机制，与 `content` 共用同一个 timer。
3. UI：建议在 assistant 气泡内加可折叠「深度思考」面板（DeepSeek 官网样式）：
   - `status === 'streaming' && !content && reasoning` → 展开并滚动显示，标题「思考中…」
   - 正文开始到达 → 自动折叠，标题变为「已深度思考（用时 Xs）」（可选）
   - 历史消息默认折叠，可手动展开
   - 思考链建议纯文本/`white-space: pre-wrap` 渲染，**不要走 Markdown 渲染**（体量大且无格式语义）
4. 兼容性：sessionId 事件移到了最前，现有「任意位置捕获 sessionId」的写法无需改；
   新事件不认识时按现有 catch 分支跳过即可，旧前端不改也不会坏，只是看不到思考链。

## 五、后端实现摘要（供 review）

- `OpenAiCompatibleClient`：解析正文、思考链和来源，支持独立取消信号；
  静默期主动取消也能关闭上游请求；不完整上游 EOF 被识别为失败。
- `AiGenerationStore`：数据库持久队列、任务租约、增量事件、事务快照和取消写入边界。
- `AiGenerationService`：后台生成与浏览器订阅分离；订阅读取已提交事件，不影响模型生命周期。
- `AiService.chatStream`：兼容原 data JSON 与 `[DONE]` 协议，委托持久任务服务。
- `ai_generation_tasks`、`ai_generation_events`：启动时自动执行幂等建表迁移。
- `ai_messages` 保留正文、思考链与来源；历史接口关联返回任务 ID、状态与游标。

## 六、联调环境

mock 上游脚本：`.zcode/mock_sse_server.js`（端口 3100；消息含 `STALL` 可模拟 20s 静默验证心跳；
请求带 `webSearch:true` 时会返回 2 条固定 sources 并在思考链/正文中带 `[1]`/`[2]` 引用编号）：

```bash
node .zcode/mock_sse_server.js
SERVER_PORT=3001 DB_USERNAME=root DB_PASSWORD=123456 \
AI_BASE_URL=http://127.0.0.1:3100/v1 AI_API_KEY=test-key CRAWLER_INGEST_CRON=- \
mvn spring-boot:run
```
