// Mock OpenAI 兼容上游:验证后端直连客户端的 SSE 解析
// - 流式:先发 reasoning_content 分片,再发 content 分片,末尾发空 choices 的 usage 块 + [DONE]
// - 消息中含 "STALL" 时首 token 前静默 20s,用于验证心跳
const http = require('http');

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
      const reasoning = ['用户在测试。', '这是一个简单场景，', '给出友好简洁的回复即可。'];
      const content = ['你好', '！这是 mock', ' 模型的流式', '回复。'];

      let i = 0;
      const startDelay = stall ? 20000 : 200;
      setTimeout(() => {
        const timer = setInterval(() => {
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
      res.end(JSON.stringify({
        choices: [{ message: { role: 'assistant', content: '非流式 mock 回复', reasoning_content: 'mock 思考链' } }]
      }));
    }
  });
}).listen(3100, () => console.log('mock upstream listening on 3100'));
