#!/usr/bin/env node
const http = require('http');
const dgram = require('dgram');
const net = require('net');
const fs = require('fs');
const path = require('path');
const { URL } = require('url');
const { execFile } = require('child_process');

const WEB_PORT = Number(process.env.PORT || 45880);
const TCP_PORT = Number(process.env.TCP_PORT || 45881);
const WEB_LOG_ENABLED = /^(1|true|yes|on)$/i.test(process.env.LANSHARE_WEB_LOG || 'false');
const DISCOVERY_PORT = 45454;
const receivedDir = process.env.LANSHARE_RECEIVED_DIR || path.join(require('os').homedir(), 'Downloads', 'LanShare');
const logFile = process.env.LANSHARE_WEB_LOG_PATH || path.join(require('os').homedir(), 'Downloads', 'LanShare', 'lanshare-web.log');
fs.mkdirSync(receivedDir, { recursive: true });
fs.mkdirSync(path.dirname(logFile), { recursive: true });
function log(level, message, error) {
  if (!WEB_LOG_ENABLED) return;
  const detail = error ? `; error=${error.stack || error.message || error}` : '';
  const line = `${new Date().toISOString()} [${level}] ${message}${detail}\n`;
  fs.appendFile(logFile, line, writeError => { if (writeError) console.error(`Web log write failed: ${writeError.message}`); });
  if (level === 'ERROR') console.error(line.trim());
}
log('INFO', `session started; webPort=${WEB_PORT}; tcpPort=${TCP_PORT}; receivedDir=${receivedDir}; logEnabled=${WEB_LOG_ENABLED}; logFile=${logFile}`);
const received = new Map();
for (const name of fs.readdirSync(receivedDir)) {
  const file = path.join(receivedDir, name);
  if (fs.statSync(file).isFile()) received.set(name, { name, size: fs.statSync(file).size, path: file });
}
const MAGIC = 'LANSHARE';
const devices = new Map();
const webRoot = __dirname;
const os = require('os');
const stateFile = path.join(__dirname, '.discovery-state.json');
let discoveryState;
try { discoveryState = JSON.parse(fs.readFileSync(stateFile, 'utf8')); } catch (_) { }
if (!discoveryState || typeof discoveryState.id !== 'string' || !Array.isArray(discoveryState.hosts)) {
  discoveryState = { id: require('crypto').randomUUID(), hosts: [] };
}
const desktopId = `desktop-${discoveryState.id}`;
const knownHosts = new Set(discoveryState.hosts.filter(host => net.isIPv4(host)));
let discoverySocket;
function rememberHost(host) {
  if (knownHosts.has(host)) return;
  knownHosts.add(host);
  log('INFO', `remembered discovery host=${host}`);
  try { fs.writeFileSync(stateFile, JSON.stringify({ id: discoveryState.id, hosts: [...knownHosts] })); }
  catch (error) { log('ERROR', 'discovery state save failed', error); }
}
function lanInterfaces() {
  return Object.values(os.networkInterfaces()).flat().filter(iface => iface && !iface.internal && iface.family === 'IPv4');
}
function ipv4Number(ip) { return ip.split('.').reduce((value, part) => (value << 8) | Number(part), 0) >>> 0; }
function onLocalLan(host) {
  return lanInterfaces().some(iface => (ipv4Number(host) & ipv4Number(iface.netmask)) === (ipv4Number(iface.address) & ipv4Number(iface.netmask)));
}
function discoveryMessage(kind) { return Buffer.from(`${MAGIC}|${kind}|${desktopId}|Web Desktop|${TCP_PORT}`); }
const pendingReceives = new Map();
function requestReceive(name, size, host, decide) {
  const id = require('crypto').randomUUID();
  const timer = setTimeout(() => finish(false), 25000);
  function finish(accepted) {
    clearTimeout(timer);
    pendingReceives.delete(id);
    decide(accepted);
  }
  pendingReceives.set(id, { id, name, size, host, finish });
  return () => { clearTimeout(timer); pendingReceives.delete(id); };
}

function sendDiscovery() {
  if (!discoverySocket) return;
  const broadcasts = new Set(['255.255.255.255']);
  lanInterfaces().forEach(iface => {
    const address = (ipv4Number(iface.address) | ~ipv4Number(iface.netmask)) >>> 0;
    broadcasts.add([24, 16, 8, 0].map(shift => (address >>> shift) & 255).join('.'));
  });
  const send = (host, kind) => discoverySocket.send(discoveryMessage(kind), DISCOVERY_PORT, host, error => {
    if (error) log('ERROR', `discovery send failed host=${host}`, error);
  });
  broadcasts.forEach(host => send(host, 'HELLO'));
  knownHosts.forEach(host => { if (onLocalLan(host)) send(host, 'QUERY'); });
}
function discover() {
  const socket = dgram.createSocket('udp4');
  socket.on('error', error => log('ERROR', 'discovery socket error', error));
  socket.on('message', (message, remote) => {
    const parts = message.toString().split('|');
    if (parts.length >= 5 && parts[0] === MAGIC && ['HELLO', 'QUERY'].includes(parts[1]) && parts[2] !== desktopId) {
      const port = Number(parts[4]);
      if (!Number.isInteger(port) || port < 1 || port > 65535) return;
      if (parts[1] === 'QUERY') {
        socket.send(discoveryMessage('HELLO'), remote.port, remote.address, error => {
          if (error) log('ERROR', `discovery reply failed host=${remote.address}`, error);
        });
        if (typeof log === 'function') log('INFO', `discovery query from=${remote.address}; sent unicast reply`); else console.log(`Discovery query from ${remote.address}; sent unicast reply`);
      }
      rememberHost(remote.address);
      for (const [id, device] of devices) {
        if (id !== parts[2] && device.host === remote.address && device.port === port) devices.delete(id);
      }
      devices.set(parts[2], { id: parts[2], name: parts[3], host: remote.address, port, seen: Date.now() });
    }
  });
  socket.bind(DISCOVERY_PORT, () => {
    socket.setBroadcast(true);
    discoverySocket = socket;
    sendDiscovery();
  });
  setInterval(() => { for (const [id, d] of devices) if (Date.now() - d.seen > 10000) devices.delete(id); }, 3000);
}
function json(res, code, body) { const data = Buffer.from(JSON.stringify(body)); res.writeHead(code, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*', 'Cache-Control': 'no-store', 'Content-Length': data.length }); res.end(data); }
function proxy(req, res, device, targetPath) {
  log('INFO', `proxy request device=${device.id}; host=${device.host}; path=${targetPath}`);
  const request = http.request({ host: device.host, port: 45888, path: targetPath, method: req.method, headers: { ...req.headers, host: `${device.host}:45888`, connection: 'close' } }, upstream => {
    log('INFO', `proxy response device=${device.id}; status=${upstream.statusCode}; path=${targetPath}`);
    res.writeHead(upstream.statusCode || 502, { ...upstream.headers, 'Access-Control-Allow-Origin': '*' }); upstream.pipe(res);
  });
  request.on('error', error => { log('ERROR', `proxy failed device=${device.id}; path=${targetPath}`, error); json(res, 502, { error: `设备连接失败: ${error.message}` }); }); req.pipe(request);
}
function serveFile(req, res, file) {
  const type = file.endsWith('.html')
    ? 'text/html; charset=utf-8'
    : file.endsWith('.css')
      ? 'text/css; charset=utf-8'
      : 'text/javascript; charset=utf-8';
  res.writeHead(200, { 'Content-Type': type });
  fs.createReadStream(path.join(webRoot, file)).pipe(res);
}
function handler(req, res) {
  const url = new URL(req.url, `http://${req.headers.host}`);
  log('INFO', `http ${req.method} ${url.pathname}${url.search}`);
  if (req.method === 'OPTIONS') { res.writeHead(204, { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'Content-Type, X-Filename' }); return res.end(); }
  if (url.pathname === '/' || url.pathname === '/index.html') return serveFile(req, res, 'index.html');
  if (url.pathname === '/app.js' || url.pathname === '/style.css') return serveFile(req, res, url.pathname.slice(1));
  if (url.pathname === '/api/receives') return json(res, 200, [...pendingReceives.values()].map(({ finish, ...request }) => request));
  const decision = url.pathname.match(/^\/api\/receives\/([^/]+)\/(accept|reject)$/);
  if (decision && req.method === 'POST') {
    const pending = pendingReceives.get(decision[1]);
    if (!pending) return json(res, 404, { error: '请求已过期' });
    pending.finish(decision[2] === 'accept');
    return json(res, 200, { ok: true });
  }
  if (url.pathname === '/api/discovery/refresh' && req.method === 'POST') { sendDiscovery(); return json(res, 200, { ok: true }); }
  if (url.pathname === '/api/devices') return json(res, 200, [...devices.values()].map(({ seen, ...device }) => device));
  if (url.pathname === '/api/desktop/files') return json(res, 200, [...received.values()].map(file => ({ name: file.name, size: file.size, url: `/desktop-download/${encodeURIComponent(file.name)}`, locateUrl: `/desktop-locate/${encodeURIComponent(file.name)}` })));
  if (url.pathname.startsWith('/desktop-locate/')) return locateDesktopFile(res, decodeURIComponent(url.pathname.slice('/desktop-locate/'.length)));
  if (url.pathname === '/desktop-upload' && req.method === 'POST') return uploadLocal(req, res);
  if (url.pathname.startsWith('/desktop-download/')) return downloadLocal(res, decodeURIComponent(url.pathname.slice('/desktop-download/'.length)));
  if (url.pathname === '/api/connect') return json(res, 200, { ok: true });
  if (url.pathname === '/api/log' && req.method === 'POST') return receiveClientLog(req, res);
  const match = url.pathname.match(/^\/device\/([^/]+)(\/.*)$/);
  if (match) { const device = devices.get(match[1]); if (!device) return json(res, 404, { error: '设备未发现' }); return proxy(req, res, device, match[2] + (url.search || '')); }
  json(res, 404, { error: 'Not found' });
}
function safeName(value) {
  const cleaned = value.replace(/[\p{Cc}\p{Cf}\\/:*?"<>|]/gu, '_').trim().replace(/\.+$/u, '');
  return Array.from(cleaned).slice(0, 120).join('').replace(/[ .]+$/u, '') || 'file';
}
function availableFileName(name) {
  const parsed = path.parse(name);
  let candidate = name;
  let index = 1;
  while (fs.existsSync(path.join(receivedDir, candidate)) || received.has(candidate)) {
    const suffix = ` (${index++})${parsed.ext}`;
    candidate = Array.from(parsed.name).slice(0, Math.max(1, 120 - Array.from(suffix).length)).join('') + suffix;
  }
  return candidate;
}
function publishReceivedFile(temporary, requestedName, callback) {
  const name = availableFileName(requestedName);
  const file = path.join(receivedDir, name);
  fs.link(temporary, file, error => {
    if (error?.code === 'EEXIST') return publishReceivedFile(temporary, requestedName, callback);
    if (error) return callback(error);
    fs.unlink(temporary, cleanupError => {
      if (cleanupError) log('ERROR', 'temporary file cleanup failed', cleanupError);
      callback(null, name, file);
    });
  });
}
function contentDisposition(name) {
  const fallback = safeName(name).replace(/[^a-zA-Z0-9._ -]/g, '_');
  const encoded = encodeURIComponent(name).replace(/['()*]/g, char => '%' + char.charCodeAt(0).toString(16).toUpperCase());
  return `attachment; filename="${fallback}"; filename*=UTF-8''${encoded}`;
}
function readLine(socket, callback) { let data = Buffer.alloc(0); const onData = chunk => { data = Buffer.concat([data, chunk]); const i = data.indexOf(10); if (i >= 0) { socket.off('data', onData); callback(data.subarray(0, i).toString().trim(), data.subarray(i + 1)); } }; socket.on('data', onData); }
function receiveTcp(socket) {
  let output;
  let finished = false;
  let temporary;
  let cancelConfirmation = () => {};
  const fail = error => {
    if (finished) return;
    finished = true;
    cancelConfirmation();
    log('ERROR', 'tcp receive failed', error);
    if (output) {
      output.destroy();
      output.once('close', () => fs.rm(temporary, { force: true }, () => {}));
    }
    socket.destroy();
  };
  socket.setTimeout(30000);
  socket.on('timeout', () => fail(new Error('接收超时')));
  socket.on('error', fail);
  socket.on('close', () => { if (!finished) fail(new Error('连接提前关闭')); });
  readLine(socket, (header, initial) => {
    const parts = header.split('|');
    const expected = Number(parts[3]);
    if (parts.length !== 4 || parts[0] !== MAGIC || parts[1] !== 'FILE' || !Number.isSafeInteger(expected) || expected < -1) {
      return fail(new Error('无效文件头'));
    }
    const name = safeName(parts[2]);
    const file = path.join(receivedDir, name);
    cancelConfirmation = requestReceive(name, expected, socket.remoteAddress, accepted => {
    if (finished) return;
    if (!accepted) {
      finished = true;
      socket.end('REJECT\n');
      return;
    }
    temporary = file + '.' + require('crypto').randomUUID() + '.part';
    output = fs.createWriteStream(temporary);
    output.on('error', fail);
    let total = 0;
    const complete = () => {
      if (finished) return;
      finished = true;
      output.end(() => {
        publishReceivedFile(temporary, name, (error, finalName, finalFile) => {
          if (error) {
            log('ERROR', 'tcp save failed', error);
            fs.rm(temporary, { force: true }, () => {});
            socket.destroy();
            return;
          }
          received.set(finalName, { name: finalName, size: total, path: finalFile });
          if (typeof log === 'function') log('INFO', `received file name=${finalName}; requested=${name}; size=${total}; host=${socket.remoteAddress}`); else console.log(`Received ${finalName}: ${total} bytes`);
          socket.end('DONE\n');
        });
      });
    };
    const consume = chunk => {
      if (finished) return;
      if (expected >= 0 && total + chunk.length > expected) return fail(new Error('数据超过声明长度'));
      total += chunk.length;
      if (!output.write(chunk)) {
        socket.pause();
        output.once('drain', () => socket.resume());
      }
      if (expected >= 0 && total === expected) complete();
    };
    socket.on('data', consume);
    socket.on('end', () => {
      if (expected === -1 || total === expected) complete();
      else fail(new Error(`文件不完整: ${total}/${expected} bytes`));
    });
    output.once('open', () => {
      if (finished) return;
      socket.write('ACCEPT\n');
      consume(initial);
    });
    });
  });
}
discover(); setInterval(sendDiscovery, 3000); sendDiscovery();
http.createServer(handler).listen(WEB_PORT, '127.0.0.1', () => console.log(`LanShare Web Desktop: http://127.0.0.1:${WEB_PORT}`));
net.createServer({ allowHalfOpen: true }, receiveTcp).listen(TCP_PORT, '0.0.0.0', () => console.log(`LanShare desktop receiver: TCP ${TCP_PORT}`));
function uploadLocal(req, res) {
  const requestedName = safeName(decodeURIComponent(req.headers['x-filename'] || 'file'));
  const temporary = path.join(receivedDir, `.${requestedName}.${require('crypto').randomUUID()}.part`);
  const output = fs.createWriteStream(temporary, { flags: 'wx' });
  output.on('error', error => {
    log('ERROR', 'desktop upload write failed', error);
    req.destroy();
    output.once('close', () => fs.rm(temporary, { force: true }, () => {}));
  });
  let size = 0;
  log('INFO', `desktop upload started name=${requestedName}; host=${req.socket.remoteAddress}`);
  req.on('data', chunk => { size += chunk.length; output.write(chunk); });
  req.on('end', () => output.end(() => {
    publishReceivedFile(temporary, requestedName, (error, name, file) => {
      if (error) { fs.rm(temporary, { force: true }, () => {}); return json(res, 500, { error: '保存文件失败' }); }
      received.set(name, { name, size, path: file });
      log('INFO', `desktop upload saved name=${name}; requested=${requestedName}; size=${size}`);
      json(res, 201, { name, size });
    });
  }));
  req.on('error', error => { output.destroy(); fs.rm(temporary, { force: true }, () => {}); log('ERROR', `desktop upload request failed name=${requestedName}`, error); });
}
function receiveClientLog(req, res) {
  let body = '';
  req.setEncoding('utf8');
  req.on('data', chunk => { body += chunk; if (body.length > 16 * 1024) req.destroy(); });
  req.on('end', () => { log('INFO', `browser ${body.slice(0, 16 * 1024)}`); json(res, 204, {}); });
}
function downloadLocal(res, name) { const file = received.get(safeName(name)); if (!file || !fs.existsSync(file.path)) { log('ERROR', `desktop download missing name=${name}`); return json(res, 404, { error: 'Not found' }); } log('INFO', `desktop download name=${file.name}; size=${file.size}`); res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Disposition': contentDisposition(file.name) }); fs.createReadStream(file.path).pipe(res); }
function locateDesktopFile(res, name) {
  const file = received.get(safeName(name));
  if (!file || !fs.existsSync(file.path)) return json(res, 404, { error: 'Not found' });
  execFile('open', ['-R', file.path], error => {
    if (error) return json(res, 500, { error: `无法打开文件位置: ${error.message}` });
    json(res, 200, { ok: true });
  });
}
