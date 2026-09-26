# AI 对话流式接口对接文档（思考链版）

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

- **心跳**：静默期每 15s 发一条 SSE 注释行 `:keep-alive`。现有 `extractSseEvents` 只取 `data:` 行，
  天然忽略，**无需改动**；若自行解析请注意跳过 `:` 开头的行。
- **早冲刷**：响应头随第 1 个事件立即提交（原来要等首个正文 token），DevTools 不再长时间 pending。
- **断连即停**：用户点「停止生成」断开 SSE 后，后端会中止上游请求，不再白白生成计费。
- 超时：SseEmitter 300s；上游整体 280s；前端现有 30s 空闲超时因心跳+思考链流不再误杀。

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

- `service/ai/OpenAiCompatibleClient.java`（新增）：OkHttp 直连 OpenAI 兼容接口，SSE 流式解析
  `delta.reasoning_content` + `delta.content` + `delta.annotations`（web_search 引用来源归一化）；
  非流式同步返回三者；上游非 2xx/断流统一抛 `AiRemoteException`；callback 抛异常（客户端断连）会取消上游请求。
  联网搜索按官方格式注入 `tools:[{type:"web_search", max_keyword, force_search}]` + `tool_choice:"auto"`，
  并切换到 `ai.web-search.model`（默认 mimo-v2.5-pro；**仅 v2.5 系支持搜索，默认的 v2.6-flash 不支持**）。
- `AiService`：移除 langchain4j 对话链路与「流式失败回退非流式假打字」逻辑；chatStream 改为
  受理即发 sessionId → sources（如有）→ 思考链/正文事件 → 落库（含 reasoning_content/sources）→ `[DONE]`；
  空正文以流内 `error` 事件优雅收尾；首轮自动生成标题（与非流式对齐）；
  `webSearch=true` 时在消息首位注入带当前日期的系统提示（引用编号要求）。
- `ai_messages` 新增列 `reasoning_content`（MEDIUMTEXT）、`sources`（JSON）（开发库已 ALTER，新装走 init.sql）。
- 联网搜索计费：搜索调用 ¥16/千次（`max_keyword` 个关键词按次计），网页内容进入 prompt 计 token 费；
  `force-search:false` 为意图识别模式，模型自行判断是否搜索以控制成本。

## 六、联调环境

mock 上游脚本：`.zcode/mock_sse_server.js`（端口 3100；消息含 `STALL` 可模拟 20s 静默验证心跳；
请求带 `webSearch:true` 时会返回 2 条固定 sources 并在思考链/正文中带 `[1]`/`[2]` 引用编号）：

```bash
node .zcode/mock_sse_server.js
SERVER_PORT=3001 DB_USERNAME=root DB_PASSWORD=123456 \
AI_BASE_URL=http://127.0.0.1:3100/v1 AI_API_KEY=test-key CRAWLER_INGEST_CRON=- \
mvn spring-boot:run
```
