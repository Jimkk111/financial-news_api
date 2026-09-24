# AI 对话流式接口对接文档（思考链版）

> 背景：思考型模型（MiMo 等）生成正文前会先输出 `reasoning_content` 思考链。旧后端用的
> langchain4j 不映射该字段，思考期间前端收不到任何数据，表现为「发消息后十几秒无响应，
> 然后内容一瞬间全部到达」。本次后端改为直连上游并把思考链实时透传，**SSE 事件契约有新增**。

## 一、流式对话 `POST /api/ai/chat/stream`

- Content-Type: `application/json`；响应 `text/event-stream`
- 鉴权：HttpOnly Cookie `jwt_token`（同现有方式）
- 请求体不变：`{ "messages": [{ "role": "user", "content": "..." }], "sessionId": "..." }`

### 事件契约（data 行 JSON，按到达顺序）

| 顺序 | 事件 | 字段 | 说明 |
|---|---|---|---|
| 1 | `{"sessionId":"session-xxxx"}` | `sessionId` | **请求受理即发送**（原来在结束时发）。收到即表示连接已建立，可用于绑定会话 |
| 2..n | `{"reasoning":"增量文本"}` | `reasoning` | **新增**。思考链增量，出现在正文之前，模型边想边推。**一个事件只含一小段，需要前端自行拼接** |
| n+1.. | `{"content":"增量文本"}` | `content` | 正文增量，同旧逻辑 |
| 末尾-1 | `{"error":"AI 响应内容为空"}` | `error` | 仅异常场景：上游只输出了思考链没有正文。发送后仍会发 `[DONE]`，按错误处理 |
| 末尾 | `[DONE]` | — | 正常结束标记（纯文本，非 JSON） |

### 其它链路行为

- **心跳**：静默期每 15s 发一条 SSE 注释行 `:keep-alive`。现有 `extractSseEvents` 只取 `data:` 行，
  天然忽略，**无需改动**；若自行解析请注意跳过 `:` 开头的行。
- **早冲刷**：响应头随第 1 个事件立即提交（原来要等首个正文 token），DevTools 不再长时间 pending。
- **断连即停**：用户点「停止生成」断开 SSE 后，后端会中止上游请求，不再白白生成计费。
- 超时：SseEmitter 300s；上游整体 280s；前端现有 30s 空闲超时因心跳+思考链流不再误杀。

### 实测时序（mock 上游，可直接作为前端开发的联调预期）

```
t=0.00s  data:{"sessionId":"session-71c52f56"}   ← 立即到达
t+0.5s   data:{"reasoning":"用户在测试。"}         ← 思考链开始滚动
...
t+1.4s   data:{"content":"你好"}                   ← 正文开始
...
t+2.0s   data:[DONE]
```

## 二、非流式对话 `POST /api/ai/chat`

响应 `data` 新增可选字段：

```json
{ "role": "assistant", "content": "...", "sessionId": "session-xxxx", "reasoning": "完整思考链" }
```

`reasoning` 可能为空/缺失（非思考型模型），前端按可选字段处理。

## 三、历史消息 `GET /api/ai/sessions/{sessionId}/messages`

assistant 消息对象新增字段 `reasoningContent`（数据库列 `reasoning_content`，MEDIUMTEXT，可能为 null）：

```json
{ "id": 1, "sessionId": 75, "role": "assistant", "content": "回复正文", "reasoningContent": "完整思考链", "createdAt": "..." }
```

建议归一化时映射 `reasoningContent ?? reasoning_content ?? undefined` → `reasoning`。

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

- `service/ai/OpenAiCompatibleClient.java`（新增）：OkHttp 直连 OpenAI 兼容接口，SSE 流式解析
  `delta.reasoning_content` + `delta.content`；非流式同步返回两者；上游非 2xx/断流统一抛
  `AiRemoteException`；callback 抛异常（客户端断连）会取消上游请求。
- `AiService`：移除 langchain4j 对话链路与「流式失败回退非流式假打字」逻辑；chatStream 改为
  受理即发 sessionId → 思考链/正文事件 → 落库（含 reasoning_content）→ `[DONE]`；
  空正文以流内 `error` 事件优雅收尾；首轮自动生成标题（与非流式对齐）。
- `ai_messages` 新增列 `reasoning_content`（开发库已 ALTER，新装走 init.sql）。

## 六、联调环境

mock 上游脚本：`.zcode/mock_sse_server.js`（端口 3100，消息含 `STALL` 可模拟 20s 静默验证心跳）：

```bash
node .zcode/mock_sse_server.js
SERVER_PORT=3001 DB_USERNAME=root DB_PASSWORD=123456 \
AI_BASE_URL=http://127.0.0.1:3100/v1 AI_API_KEY=test-key CRAWLER_INGEST_CRON=- \
mvn spring-boot:run
```
