// Mock OpenAI 兼容上游:验证后端直连客户端的 SSE 解析
// - 流式:先发 reasoning_content 分片,再发 content 分片,末尾发空 choices 的 usage 块 + [DONE]
// - 消息中含 "STALL" 时首 token 前静默 20s,用于验证心跳
// - 请求带 tools[type=web_search] 时(联网搜索):首个分片随 delta.annotations 返回 url_citation 来源
const http = require('http');

const SOURCES = [
  { type: 'url_citation', url_citation: { url: 'https://finance.sina.com.cn/a/1.html', title: 'A股三大指数收涨', summary: '沪指涨1.2%…', site_name: '新浪财经', publish_time: '2026-09-26 10:30:00', logo_url: 'https://g.sinaimg.cn/favicon.ico' } },
  { type: 'url_citation', url_citation: { url: 'https://wallstreetcn.com/a/2.html', title: '央行公开市场操作', summary: '净投放…', site_name: '华尔街见闻', publish_time: '2026-09-26 09:15:00', logo_url: 'https://api-one-wscn.awtmt.com/favicon.png' } },
];

http.createServer((req, res) => {
  let body = '';
  req.on('data', c => body += c);
  req.on('end', () => {
    if (!(req.headers.authorization || '').startsWith('Bearer ')) {
      res.writeHead(401, { 'Content-Type': 'application/json' });
      res.end('{"error":{"message":"missing key"}}');
      return;
    }
    let json;
    try { json = JSON.parse(body); } catch { res.writeHead(400); res.end(); return; }

    const lastUser = (json.messages || []).filter(m => m.role === 'user').pop() || {};
    const stall = /STALL/.test(lastUser.content || '');
    const webSearch = Array.isArray(json.tools) && json.tools.some(t => t && t.type === 'web_search');
    if (webSearch) {
      console.log(`[mock] web_search 请求: model=${json.model} max_keyword=${json.tools[0].max_keyword} force=${json.tools[0].force_search} tool_choice=${json.tool_choice}`);
    }

    if (json.stream) {
      res.writeHead(200, {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
        Connection: 'keep-alive'
      });
      const send = obj => res.write('data: ' + JSON.stringify(obj) + '\n\n');
      const chunk = delta => send({
        id: 'mock-1', object: 'chat.completion.chunk', created: 0, model: json.model,
        choices: [{ index: 0, delta, finish_reason: null }]
      });
      const reasoning = webSearch
        ? ['用户要最新行情。', '结合搜索结果回答并标注来源。']
        : ['用户在测试。', '这是一个简单场景，', '给出友好简洁的回复即可。'];
      const content = webSearch
        ? ['根据最新消息[1]', '，A股三大指数今日收涨', '，沪指涨1.2%[2]。']
        : ['你好', '！这是 mock', ' 模型的流式', '回复。'];

      let i = 0;
      let sentAnnotations = false;
      const startDelay = stall ? 20000 : 200;
      setTimeout(() => {
        const timer = setInterval(() => {
          if (webSearch && !sentAnnotations) {
            sentAnnotations = true;
            chunk({ annotations: SOURCES });
            return; // 来源分片不占用 reasoning/content 的序号
          }
          if (i < reasoning.length) {
            chunk({ role: 'assistant', reasoning_content: reasoning[i] });
          } else if (i < reasoning.length + content.length) {
            chunk({ content: content[i - reasoning.length] });
          } else if (i === reasoning.length + content.length) {
            send({
              id: 'mock-1', object: 'chat.completion.chunk', created: 0, model: json.model,
              choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
              usage: { prompt_tokens: 10, completion_tokens: 12, total_tokens: 22 }
            });
          } else {
            res.write('data: [DONE]\n\n');
            res.end();
            clearInterval(timer);
          }
          i++;
        }, 60);
      }, startDelay);
    } else {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      const msg = { role: 'assistant', content: '非流式 mock 回复', reasoning_content: 'mock 思考链' };
      if (webSearch) msg.annotations = SOURCES;
      res.end(JSON.stringify({ choices: [{ message: msg }] }));
    }
  });
}).listen(3100, () => console.log('mock upstream listening on 3100'));
