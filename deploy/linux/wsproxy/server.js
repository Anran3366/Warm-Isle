// qqbot <-> NapCat 的 WebSocket 缓冲代理
// - NapCat 侧连接始终由代理保持
// - qqbot 重启期间事件先落盘缓存，新连接上来后补发
// - qqbot 断开超过 5 分钟，通过 NapCat 给主人 QQ 发告警（每小时最多一次）
const fs = require('fs');
const path = require('path');
const WebSocket = require('ws');

const PORT = Number(process.env.PORT || 3002);
const UPSTREAM = process.env.UPSTREAM || 'ws://napcat:3001';
const TOKEN = process.env.NAPCAT_ACCESS_TOKEN || '';
const ALERT_QQ = Number(process.env.ALERT_QQ || 0);
const MAX_BUFFER = Number(process.env.MAX_BUFFER || 500);
const MAX_AGE_MS = Number(process.env.MAX_AGE_MS || 5 * 60 * 1000);
const DATA_DIR = process.env.DATA_DIR || '/data';
const BUFFER_FILE = path.join(DATA_DIR, 'buffer.jsonl');

let upstream = null;
let downstream = null;
let buffer = [];
let upstreamReady = false;
let downstreamDownSince = Date.now();
let lastAlertAt = 0;

function log(...args) {
  console.log(new Date().toISOString(), ...args);
}

function ensureDir() {
  try {
    fs.mkdirSync(DATA_DIR, { recursive: true });
  } catch (e) {
    // 忽略
  }
}

function loadBuffer() {
  try {
    if (!fs.existsSync(BUFFER_FILE)) return;
    const lines = fs.readFileSync(BUFFER_FILE, 'utf8').split('\n').filter(Boolean);
    for (const line of lines) {
      try {
        const item = JSON.parse(line);
        if (item && item.data) buffer.push(item);
      } catch (e) {
        // 跳过坏行
      }
    }
    log(`从磁盘恢复缓存事件 ${buffer.length} 条`);
  } catch (e) {
    log('恢复缓存失败:', e.message);
  }
}

function persist(item) {
  try {
    ensureDir();
    fs.appendFileSync(BUFFER_FILE, JSON.stringify(item) + '\n');
  } catch (e) {
    log('写入缓存失败:', e.message);
  }
}

function clearBufferFile() {
  try {
    fs.writeFileSync(BUFFER_FILE, '');
  } catch (e) {
    // 忽略
  }
}

function bufferEvent(data) {
  const item = { at: Date.now(), data };
  buffer.push(item);
  persist(item);
  const cutoff = Date.now() - MAX_AGE_MS;
  buffer = buffer.filter((x) => x.at >= cutoff);
  if (buffer.length > MAX_BUFFER) {
    buffer.splice(0, buffer.length - MAX_BUFFER);
  }
}

function connectUpstream() {
  const headers = TOKEN ? { Authorization: `Bearer ${TOKEN}` } : {};
  const ws = new WebSocket(UPSTREAM, { headers });
  upstream = ws;

  ws.on('open', () => {
    upstreamReady = true;
    log('已连接 NapCat:', UPSTREAM);
  });

  ws.on('message', (raw) => {
    const text = raw.toString();
    if (downstream && downstream.readyState === WebSocket.OPEN) {
      downstream.send(text);
    } else {
      bufferEvent(text);
    }
  });

  ws.on('close', () => {
    upstreamReady = false;
    log('NapCat 连接断开，2 秒后重连');
    setTimeout(connectUpstream, 2000);
  });

  ws.on('error', (err) => {
    log('NapCat 连接错误:', err.message);
  });
}

function flushBuffer(ws) {
  if (!buffer.length) return;
  log(`补发缓存事件 ${buffer.length} 条`);
  for (const item of buffer) {
    if (ws.readyState !== WebSocket.OPEN) break;
    ws.send(item.data);
  }
  buffer = [];
  clearBufferFile();
}

function sendAlert(text) {
  if (!ALERT_QQ || !upstream || upstream.readyState !== WebSocket.OPEN) return;
  const payload = {
    action: 'send_private_msg',
    params: { user_id: ALERT_QQ, message: text },
    echo: 'alert-' + Date.now()
  };
  try {
    upstream.send(JSON.stringify(payload));
    lastAlertAt = Date.now();
    log('已发送离线告警');
  } catch (e) {
    log('发送告警失败:', e.message);
  }
}

const server = new WebSocket.Server({ port: PORT }, () => {
  log('代理已启动，监听', PORT);
  ensureDir();
  loadBuffer();
  connectUpstream();
});

server.on('connection', (ws, req) => {
  log('qqbot 已连接，来源', req.socket.remoteAddress);
  if (downstream && downstream.readyState === WebSocket.OPEN) {
    try {
      downstream.close(1000, 'replaced');
    } catch (e) {
      // 忽略
    }
  }
  downstream = ws;
  downstreamDownSince = 0;
  flushBuffer(ws);

  ws.on('message', (raw) => {
    if (upstream && upstream.readyState === WebSocket.OPEN) {
      upstream.send(raw.toString());
    }
  });

  ws.on('close', () => {
    log('qqbot 连接断开，事件进入缓存');
    if (downstream === ws) {
      downstream = null;
      downstreamDownSince = Date.now();
    }
  });

  ws.on('error', (err) => {
    log('qqbot 连接错误:', err.message);
  });
});

setInterval(() => {
  const downMin = downstreamDownSince ? Math.floor((Date.now() - downstreamDownSince) / 60000) : 0;
  log(`状态 upstream=${upstreamReady ? 'up' : 'down'} downstream=${downstream ? 'up' : 'down'} buffer=${buffer.length} downMin=${downMin}`);

  if (!downstream && downstreamDownSince && Date.now() - downstreamDownSince > 5 * 60 * 1000) {
    if (Date.now() - lastAlertAt > 60 * 60 * 1000) {
      sendAlert(`【小满告警】机器人与 NapCat 的连接已断开 ${downMin} 分钟，请检查 qqbot 容器。`);
    }
  }
}, 60000);
