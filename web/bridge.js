#!/usr/bin/env node
const http = require('http');
const dgram = require('dgram');
const net = require('net');
const fs = require('fs');
const path = require('path');
const { URL } = require('url');
const { execFile } = require('child_process');
const discoveryNetworks = require('./discovery-networks');
const QRCode = require('./vendor/qrcode');
const QRLevels = require('./vendor/qrcode/QRErrorCorrectLevel');

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
  const stat = fs.statSync(file);
  if (stat.isFile()) received.set(name, { name, size: stat.size, path: file, receivedAt: stat.mtimeMs });
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
let desktopName = normalizeDeviceName(discoveryState.name);
const knownHosts = new Set(discoveryState.hosts.filter(host => net.isIPv4(host)));
let discoverySocket;
let configuredNetworks;
try { configuredNetworks = discoveryNetworks.parse(discoveryState.networks || ''); }
catch (_) { configuredNetworks = []; }
let networkScanTimer;
let networkScanGeneration = 0;
function saveDiscoveryState() {
  fs.writeFileSync(stateFile, JSON.stringify({ id: discoveryState.id, name: desktopName, hosts: [...knownHosts], networks: configuredNetworks.map(net => net.cidr).join('\n') }));
}
function rememberHost(host) {
  if (knownHosts.has(host)) return;
  knownHosts.add(host);
  log('INFO', `remembered discovery host=${host}`);
  try { saveDiscoveryState(); }
  catch (error) { log('ERROR', 'discovery state save failed', error); }
}
function lanInterfaces() {
  return Object.values(os.networkInterfaces()).flat().filter(iface => iface && !iface.internal && iface.family === 'IPv4');
}
function ipv4Number(ip) { return ip.split('.').reduce((value, part) => (value << 8) | Number(part), 0) >>> 0; }
function localAddresses() {
  return [...new Set(lanInterfaces().map(iface => iface.address))].sort((a, b) => ipv4Number(a) - ipv4Number(b));
}
function onLocalLan(host) {
  return lanInterfaces().some(iface => (ipv4Number(host) & ipv4Number(iface.netmask)) === (ipv4Number(iface.address) & ipv4Number(iface.netmask)));
}
function normalizeDeviceName(value) {
  const cleaned = String(value ?? '').replace(/[\p{Cc}\p{Cf}|]/gu, ' ').replace(/\s+/gu, ' ').trim();
  return Array.from(cleaned).slice(0, 40).join('').trim() || 'Web Desktop';
}
function discoveryMessage(kind) { return Buffer.from(`${MAGIC}|${kind}|${desktopId}|${desktopName}|${TCP_PORT}`); }
const pendingReceives = new Map();
const activeReceives = new Map();
const activeSends = new Map();
function failTransfer(transfer, message) {
  if (!transfer) return;
  transfer.status = 'failed';
  transfer.error = message;
  transfer.updatedAt = Date.now();
  const timer = setTimeout(() => activeReceives.delete(transfer.id), 8000);
  if (timer.unref) timer.unref();
}
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
  knownHosts.forEach(host => {
    if (onLocalLan(host) || configuredNetworks.some(net => discoveryNetworks.contains(net, discoveryNetworks.ipv4Number(host)))) send(host, 'QUERY');
  });
}
function startNetworkScan() {
  clearTimeout(networkScanTimer);
  const generation = ++networkScanGeneration;
  if (!discoverySocket || !configuredNetworks.length) return;
  const hosts = discoveryNetworks.targets(configuredNetworks, new Set(lanInterfaces().map(iface => iface.address)));
  const batch = () => {
    if (generation !== networkScanGeneration) return;
    for (let index = 0; index < 10; index++) {
      const next = hosts.next();
      if (next.done) {
        networkScanTimer = setTimeout(startNetworkScan, 30000);
        return;
      }
      discoverySocket.send(discoveryMessage('QUERY'), DISCOVERY_PORT, next.value, error => {
        if (error) log('ERROR', `configured network query failed host=${next.value}`, error);
      });
    }
    networkScanTimer = setTimeout(batch, 100);
  };
  batch();
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
    startNetworkScan();
  });
  setInterval(() => { for (const [id, d] of devices) if (Date.now() - d.seen > 10000) devices.delete(id); }, 3000);
}
function discoveryQrSvg(networks) {
  networks = discoveryNetworks.parse(networks).map(net => net.cidr).join('\n');
  if (!networks) throw new Error('没有可分享的网段');
  if (networks.length > 1800) throw new Error('网段过多，请分批分享二维码');
  const qr = new QRCode(0, QRLevels.M);
  qr.addData(`LANSHARE|NETWORKS|${networks.trim()}`);
  qr.make();
  const count = qr.getModuleCount();
  const quiet = 4;
  const size = count + quiet * 2;
  const paths = [];
  for (let row = 0; row < count; row++) for (let col = 0; col < count; col++) if (qr.isDark(row, col)) paths.push(`M${col + quiet} ${row + quiet}h1v1h-1z`);
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${size} ${size}" role="img" aria-label="发现网段二维码"><rect width="100%" height="100%" fill="#f5f7ef"/><path d="${paths.join('')}" fill="#10120f"/></svg>`;
}
function saveNetworkSettings(req, res) {
  let body = '';
  let tooLarge = false;
  req.setEncoding('utf8');
  req.on('data', chunk => {
    body += chunk.toString();
    if (body.length > 16384) { tooLarge = true; body = ''; }
  });
  req.on('end', () => {
    let next;
    let nextName;
    try {
      if (tooLarge) throw new Error('设置过长');
      const payload = JSON.parse(body);
      next = discoveryNetworks.parse(payload.networks);
      nextName = payload.name === undefined ? desktopName : normalizeDeviceName(payload.name);
    } catch (error) { return json(res, 400, { error: error.message }); }
    const previous = configuredNetworks;
    const previousName = desktopName;
    configuredNetworks = next;
    desktopName = nextName;
    try { saveDiscoveryState(); }
    catch (error) {
      configuredNetworks = previous;
      desktopName = previousName;
      log('ERROR', 'network settings save failed', error);
      return json(res, 500, { error: '无法保存设置' });
    }
    startNetworkScan();
    sendDiscovery();
    return json(res, 200, { networks: configuredNetworks.map(net => net.cidr).join('\n'), name: desktopName });
  });
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
  res.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-store' });
  fs.createReadStream(path.join(webRoot, file)).pipe(res);
}
function handler(req, res) {
  const url = new URL(req.url, `http://${req.headers.host}`);
  log('INFO', `http ${req.method} ${url.pathname}${url.search}`);
  if (req.method === 'OPTIONS') { res.writeHead(204, { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'Content-Type, X-Filename' }); return res.end(); }
  if (url.pathname === '/' || url.pathname === '/index.html') return serveFile(req, res, 'index.html');
  if (url.pathname === '/app.js' || url.pathname === '/style.css') return serveFile(req, res, url.pathname.slice(1));
  if (url.pathname === '/api/receives') return json(res, 200, [...pendingReceives.values()].map(({ finish, ...request }) => request));
  if (url.pathname === '/api/transfers') return json(res, 200, [
    ...[...activeReceives.values()].map(({ updatedAt, ...transfer }) => ({ ...transfer, direction: 'receive' })),
    ...[...activeSends.values()].map(({ updatedAt, ...transfer }) => ({ ...transfer, direction: 'send' })),
  ]);
  const decision = url.pathname.match(/^\/api\/receives\/([^/]+)\/(accept|reject)$/);
  if (decision && req.method === 'POST') {
    const pending = pendingReceives.get(decision[1]);
    if (!pending) return json(res, 404, { error: '请求已过期' });
    pending.finish(decision[2] === 'accept');
    return json(res, 200, { ok: true });
  }
  if (url.pathname === '/api/discovery/qr.svg' && req.method === 'GET') {
    try {
      const requested = url.searchParams.get('networks') || configuredNetworks.map(net => net.cidr).join('\n') || discoveryNetworks.localSubnets(lanInterfaces()).join('\n');
      const networks = discoveryNetworks.parse(requested).map(net => net.cidr).join('\n');
      const svg = Buffer.from(discoveryQrSvg(networks));
      res.writeHead(200, { 'Content-Type': 'image/svg+xml; charset=utf-8', 'Cache-Control': 'no-store', 'Content-Length': svg.length });
      return res.end(svg);
    } catch (error) { return json(res, 400, { error: error.message }); }
  }
  if (url.pathname === '/api/discovery/settings' && req.method === 'GET') return json(res, 200, {
    currentNetworks: discoveryNetworks.localSubnets(lanInterfaces()),
    addresses: localAddresses(),
    networks: configuredNetworks.map(net => net.cidr).join('\n'),
    name: desktopName,
  });
  if (url.pathname === '/api/discovery/settings' && req.method === 'POST') return saveNetworkSettings(req, res);
  if (url.pathname === '/api/discovery/refresh' && req.method === 'POST') { sendDiscovery(); startNetworkScan(); return json(res, 200, { ok: true }); }
  if (url.pathname === '/api/devices') return json(res, 200, [...devices.values()].map(({ seen, ...device }) => device));
  if (url.pathname === '/api/desktop/files') return json(res, 200, [...received.values()].sort((a, b) => b.receivedAt - a.receivedAt).map(file => ({ name: file.name, size: file.size, url: `/desktop-download/${encodeURIComponent(file.name)}`, locateUrl: `/desktop-locate/${encodeURIComponent(file.name)}` })));
  if (url.pathname === '/api/desktop/send' && req.method === 'POST') return sendDesktopFile(req, res);
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
  // Linking publishes the completed file without replacing an existing path.
  fs.link(temporary, file, error => {
    if (error?.code === 'EEXIST') return publishReceivedFile(temporary, requestedName, callback);
    if (error) return callback(error);
    fs.unlink(temporary, cleanupError => {
      if (cleanupError && typeof log === 'function') log('ERROR', 'temporary file cleanup failed', cleanupError);
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
  let transfer;
  let cancelConfirmation = () => {};
  const fail = error => {
    if (finished) return;
    finished = true;
    cancelConfirmation();
    failTransfer(transfer, error.message || String(error));
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
    transfer = { id: require('crypto').randomUUID(), name, size: expected, received: 0, host: socket.remoteAddress, status: 'receiving', startedAt: Date.now(), updatedAt: Date.now() };
    activeReceives.set(transfer.id, transfer);
    temporary = file + '.' + require('crypto').randomUUID() + '.part';
    output = fs.createWriteStream(temporary);
    output.on('error', fail);
    let total = 0;
    const complete = () => {
      if (finished) return;
      finished = true;
      if (transfer) { transfer.received = total; transfer.status = 'saving'; transfer.updatedAt = Date.now(); }
      output.end(() => {
        publishReceivedFile(temporary, name, (error, finalName, finalFile) => {
          if (error) {
            log('ERROR', 'tcp save failed', error);
            failTransfer(transfer, '保存文件失败');
            fs.rm(temporary, { force: true }, () => {});
            socket.destroy();
            return;
          }
          received.set(finalName, { name: finalName, size: total, path: finalFile, receivedAt: fs.statSync(finalFile).mtimeMs });
          if (transfer) activeReceives.delete(transfer.id);
          if (typeof log === 'function') log('INFO', `received file name=${finalName}; requested=${name}; size=${total}; host=${socket.remoteAddress}`); else console.log(`Received ${finalName}: ${total} bytes`);
          socket.end('DONE\n');
        });
      });
    };
    const consume = chunk => {
      if (finished) return;
      if (expected >= 0 && total + chunk.length > expected) return fail(new Error('数据超过声明长度'));
      total += chunk.length;
      if (transfer) { transfer.received = total; transfer.updatedAt = Date.now(); }
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
function startDesktopSend(device, file) {
  const stat = fs.statSync(file.path);
  const id = require('crypto').randomUUID();
  const transfer = { id, direction: 'send', name: file.name, size: stat.size, received: 0, status: 'sending', startedAt: Date.now(), updatedAt: Date.now() };
  activeSends.set(id, transfer);
  const finish = (status, error) => {
    if (transfer.status !== 'sending') return;
    transfer.status = status;
    transfer.updatedAt = Date.now();
    if (status === 'done') transfer.received = stat.size;
    if (error) transfer.error = error;
    log(status === 'done' ? 'INFO' : 'ERROR', `desktop send ${status} name=${file.name}; device=${device.id}; size=${stat.size}${error ? `; error=${error}` : ''}`);
    const timer = setTimeout(() => activeSends.delete(id), 8000);
    if (timer.unref) timer.unref();
  };
  const input = fs.createReadStream(file.path);
  const request = http.request({
    host: device.host,
    port: device.webPort || 45888,
    path: '/upload',
    method: 'POST',
    headers: {
      'X-Filename': encodeURIComponent(file.name),
      'Content-Type': 'application/octet-stream',
      'Content-Length': stat.size,
      Connection: 'close',
    },
  }, response => {
    let body = '';
    response.setEncoding('utf8');
    response.on('data', chunk => { if (body.length < 4096) body += chunk; });
    response.on('end', () => {
      const status = response.statusCode || 0;
      if (status >= 200 && status < 300) return finish('done');
      finish('failed', sendResponseError(body, status));
    });
    response.on('error', error => finish('failed', `读取设备响应失败：${error.message}`));
  });
  request.setTimeout(75000, () => request.destroy(new Error('等待接收确认超时')));
  input.on('error', error => request.destroy(error));
  request.on('error', error => { input.destroy(); finish('failed', `发送失败：${error.message}`); });
  input.on('data', chunk => { transfer.received += chunk.length; transfer.updatedAt = Date.now(); });
  input.pipe(request);
  return transfer;
}

function sendResponseError(body, status) {
  const text = (body || '').trim();
  if (text) {
    try {
      const result = JSON.parse(text);
      if (result.error) return result.error;
    } catch (_) { }
    if (text.length <= 120) return text;
  }
  return `目标设备拒绝接收（${status}）`;
}

function sendDesktopFile(req, res) {
  let body = '';
  req.setEncoding('utf8');
  req.on('data', chunk => { body += chunk; if (body.length > 4096) req.destroy(); });
  req.on('end', () => {
    let payload;
    try { payload = JSON.parse(body); } catch (_) { return json(res, 400, { error: '无效请求' }); }
    if (!payload || typeof payload.name !== 'string' || typeof payload.deviceId !== 'string') return json(res, 400, { error: '无效请求' });
    const file = received.get(payload.name) || received.get(safeName(payload.name));
    if (!file || !fs.existsSync(file.path)) return json(res, 404, { error: '文件不存在' });
    const device = devices.get(payload.deviceId);
    if (!device) return json(res, 404, { error: '设备未发现' });
    try {
      const transfer = startDesktopSend(device, file);
      return json(res, 202, { id: transfer.id, name: file.name, deviceId: device.id });
    } catch (error) {
      log('ERROR', `desktop send start failed name=${payload.name}`, error);
      return json(res, 500, { error: `无法读取文件: ${error.message}` });
    }
  });
  req.on('error', error => log('ERROR', 'desktop send request failed', error));
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
      received.set(name, { name, size, path: file, receivedAt: fs.statSync(file).mtimeMs });
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
