# AI 生成任务：断线恢复与主动停止

## 已实现的行为

- 浏览器断网、刷新、关闭或 SSE 超时只结束订阅，后台继续生成。
- 正文、思考链和搜索来源每个上游增量都先持久化，再通过 SSE 推送。
- 停止使用独立取消接口。服务端接受取消之后，不再保存新的模型增量。
- 停止、失败均保留助手消息；历史消息携带任务 ID、状态和事件游标。
- 创建请求可以幂等重试，重连补发已保存事件，不重新调用模型。

本仓库只有后端。前端需要接入下面的创建、订阅、恢复与取消流程，才能完整提供这些交互。

## 架构与运行参数

`AiGenerationStore` 在一个数据库事务中创建会话（必要时）、本轮用户消息、空助手消息和 `queued` 任务。
任务表同时作为持久队列，无须增加外部队列或 Outbox 投递器。

`AiGenerationService` 在应用就绪后每 5 秒领取任务，每个实例最多执行 4 个任务。
领取使用 MySQL 8+ 的 `FOR UPDATE SKIP LOCKED`，任务租约为 30 秒，并每 5 秒续约。
写入增量和完成任务都校验当前执行者标识与有效租约，旧执行者不能覆盖恢复后的状态。

SSE 订阅由独立调度器每 250 毫秒读取数据库中的已提交事件。订阅发送失败不会进入模型回调。
订阅每 15 秒发送心跳，300 秒超时；上游模型整体等待上限仍为 280 秒，与订阅生命周期分离。
当前按上游分片直接保存，优先保证部分内容可靠性；没有使用尚未落库的内存批量缓冲。

同一会话只允许一个未结束的生成任务，重叠创建返回 409。
存在未结束任务时删除会话也返回 409；请先主动停止，并等待最终 `cancelled` 状态。

## 数据库升级

启动时 `AiGenerationSchemaInitializer` 自动执行 UTF-8 幂等迁移：

```text
src/main/resources/db/migration/v900-ai-generations.sql
```

新增 `ai_generation_tasks` 和 `ai_generation_events` 两张表，不修改现有消息列。
历史消息查询通过关联任务表返回 `generationId`、`status`、`lastSeq`；旧消息这些字段为 null。
任务和事件随会话/消息删除级联清理。运行账号需要具备建表权限，或由部署流程预先执行迁移。
迁移依赖现有 `ai_sessions` 和 `ai_messages` 表；不能把完整 `init.sql` 当成存量库升级脚本。

## 接口与鉴权

所有接口沿用 JWT Cookie / Bearer 鉴权，返回结构为 `{code,msg,data}`。
任务读取、订阅和取消均验证任务所有者。

### 创建任务

```http
POST /api/ai/generations
Content-Type: application/json
Idempotency-Key: 75c7d4e3-02d5-473f-801b-074ed460a834

{"sessionId":"session-existing","messages":[{"role":"user","content":"今天的财经新闻有哪些？"}],"webSearch":false}
```

请求体沿用 `AiChatRequest`，`messages` 是上下文，最多使用最后 20 条；只保存最后一条用户消息。
`sessionId` 可省略，由服务端创建新会话；显式传入不存在或不属于当前用户的会话会被拒绝。

响应 `data`：

```json
{
  "generationId": "g_...",
  "sessionId": "session-...",
  "messageId": 123,
  "status": "queued",
  "lastSeq": 0
}
```

幂等键允许字母、数字、点、下划线、冒号、连字符，长度 1～128。
同一用户的相同幂等键与相同请求返回原任务；不同请求复用该键返回 409。
前端应在发送之前持久化请求体和幂等键，创建请求超时后使用原请求重试。
新的用户提问或重新生成必须使用新键。不要在创建请求重试时改变 `sessionId` 或消息上下文。

### 读取快照

```http
GET /api/ai/generations/{generationId}
```

响应 `data`：

```json
{
  "generationId": "g_...",
  "sessionId": "session-...",
  "messageId": 123,
  "status": "running",
  "content": "已经保存的正文",
  "reasoning": "已经保存的思考链",
  "sources": [],
  "lastSeq": 42,
  "errorCode": null
}
```

快照内容与游标来自同一受任务行锁保护的事务。加载后必须用快照替换本地正文、思考链、来源和游标。
快照中的 `sources` 是数组；原历史消息接口的 `sources` 仍是 JSON 字符串。

### 订阅与补发

```http
GET /api/ai/generations/{generationId}/events?afterSeq=42
```

事件示例：

```text
data: {"sessionId":"session-...","generationId":"g_...","messageId":123,"status":"running"}

id: 43
event: delta
data: {"content":"新增正文","generationId":"g_...","seq":43,"type":"delta"}

id: 44
event: completed
data: {"status":"completed","generationId":"g_...","seq":44,"type":"completed"}

event: end
data: {"status":"completed","lastSeq":44}
```

- `delta`：包含一个 `content`、`reasoning` 或 `sources` 字段；文字拼接，来源数组追加。
- `completed` / `cancelled` / `failed`：持久终态事件，有序号。
- `end`：订阅结束信号，没有序号；终态快照游标已在末尾时也会发送，前端收到后必须关闭连接。
- 初始化信息与 `{"heartbeat":true}` 使用默认 `message` 事件，无游标，不拼接正文。
- 每次补发最多查询 200 条，后续轮询继续补发直到追上当前进度。
- `Last-Event-ID` 存在时优先于 URL 的 `afterSeq`，避免原生 EventSource 重连重复使用旧 URL 游标。
- 负数或超过当前持久游标的请求返回 400；认证失效须重新登录，不能无限重试。

原生 EventSource 使用 `addEventListener('delta', handler)` 接收命名事件，不能只设置 `onmessage`。
Cookie 鉴权时同源请求直接携带 Cookie；跨源使用 `withCredentials:true` 并满足部署的 CORS/Cookie 配置。
Bearer 鉴权需要使用支持请求头的流式客户端或 `fetch` 解析 SSE，并自行维护重连游标。

前端应忽略 `seq <= lastSeq` 的重复事件。如果 `seq > lastSeq + 1`，停止追加并重取快照。
记录游标前必须先应用对应内容；持久化时内容和游标保持一致。

### 主动停止

```http
POST /api/ai/generations/{generationId}/cancel
```

返回与快照相同的结构。运行中任务先进入 `cancel_requested`，随后进入 `cancelled`。
尚未领取的任务直接进入 `cancelled`；已结束的任务返回原终态，重复取消无额外副作用。

增量保存和取消更新锁定同一任务行：取消事务提交前已成功保存的内容保留，提交后新增量被拒绝。
本实例会立即取消上游 EventSource，包括静默思考阶段；其他实例通过租约续约检查在约 5 秒内发现取消。
即使上游取消尚未完成，也不能继续写入消息内容。

前端点击停止时先冻结显示并保留文字，然后发出取消请求。
收到 `cancel_requested` 不代表收尾完成，应继续订阅状态或轮询快照，直到终态，再同步最终快照。
取消前已经保存但未送达的内容可能出现在最终快照中。

断网时保存待取消请求，并显示“停止请求待同步”；联网或重新打开应用后重试。
服务器收到请求前可能已经完成。无法保证离线点击停止立即终止远端生成。

## 前端完整流程

1. 为新提问生成并保存幂等键与原始请求；创建任务失败可用原请求重试。
2. 保存返回的 `generationId`、`sessionId`、`messageId`。
3. 从游标 0 订阅；按序应用增量和状态。
4. 连接错误只重新连接，不取消任务、不重发提问；使用退避并限制重连频率。
5. 页面重新打开时，先读取历史消息找到未结束的 `generationId`，查询快照后从 `lastSeq` 订阅。
6. 点击停止时调用取消接口；单独关闭浏览器流只能结束接收。
7. 收到终态事件或 `end` 后关闭 EventSource，读取最终快照并保存。

用户发送的新提问与连接恢复必须使用不同的逻辑路径，避免“重连即新生成”。

## 旧接口兼容

`POST /api/ai/chat/stream` 已使用相同的持久任务实现。可选 `Idempotency-Key`，不传则每次请求创建新任务。
事件继续使用无名称的 `data` JSON 和最终 `[DONE]`，原来的 `content`、`reasoning`、`sources` 字段保留。
初始化增加 `generationId`、`messageId`、`status`；增量增加 `seq`、`type`；终态包含 `status`。

**旧前端必须调整停止动作**：读取 `generationId` 并调用取消接口，只执行 AbortController.abort() 已不再停止生成。
旧连接恢复应切换至新 `GET /events` 端点，不能无幂等键重复 POST。
若沿用旧接口创建后马上断线、没有收到任务 ID，可以使用相同幂等键和原请求再次 POST 找回任务。

`POST /api/ai/chat` 非流式接口保持原有行为，不提供生成任务 ID。
需要持久任务和主动取消能力的页面应接入 `/generations` 或流式接口。

## 故障处理与边界

- 模型流提前结束且没有 `[DONE]` 或 `finish_reason`，标记失败并保留部分输出。
- 持久化失败会终止上游调用；未提交的增量不会推送。
- Worker 消失后，租约到期的任务进入 `failed`，错误码为 `WORKER_INTERRUPTED`；已接受取消的任务恢复为 `cancelled`。
- 当前 OpenAI-compatible Chat Completions 没有可恢复的上游任务 ID，本实现不会自动从头重跑并拼接原消息。
- 数据库恢复后继续领取 `queued` 任务；数据库故障期间不保证推送或取消立即受理。
- 暂未清理增量事件，确保当前游标能完整重放；未来增加保留期限时须同步实现快照回退协议。

部署反代须关闭 SSE 缓冲和缓存，并确保心跳间隔小于空闲超时。响应已有 `X-Accel-Buffering: no` 和 `Cache-Control: no-cache`。

## 验证方式

常规 `mvn test` 运行单元测试和本地模拟 HTTP 上游测试，不调用真实模型。
MySQL 事务测试仅在设置 `AI_GENERATION_TEST_JDBC_URL` 时执行；不读取业务配置。
测试会创建随机前缀 `ai_generation_test_` 的数据库，并仅删除自己创建的库。

在专门的测试 MySQL 实例上设置以下变量后执行 `mvn verify`：

```text
AI_GENERATION_TEST_JDBC_URL=jdbc:mysql://127.0.0.1:3306/?allowPublicKeyRetrieval=true&useSSL=false
AI_GENERATION_TEST_DB_USER=<测试实例账号>
AI_GENERATION_TEST_DB_PASSWORD=<测试实例密码>
```

URL 指向服务器根路径，不包含数据库名。账号需要在测试实例上创建/删除数据库的权限。
CI 已配置独立 MySQL 8 服务，自动执行这些事务测试。
