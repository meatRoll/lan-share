const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');
const vm = require('node:vm');
const { once } = require('node:events');

(async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'lanshare-desktop-send-'));
  const source = fs.readFileSync(path.join(__dirname, 'bridge.js'), 'utf8');
  const functions = source.slice(source.indexOf('function safeName'), source.indexOf('discover();'));
  const received = new Map();
  const activeSends = new Map();
  const logs = [];
  const context = {
    fs, path, http, require, console, Buffer, setTimeout, clearTimeout, receivedDir: dir, received, activeSends,
    log: (level, message) => logs.push(`${level}:${message}`),
  };
  vm.createContext(context);
  vm.runInContext(functions, context);

  const name = '转发 文件😀.txt';
  const content = 'desktop resend content';
  const filePath = path.join(dir, name);
  fs.writeFileSync(filePath, content);
  received.set(name, { name, size: Buffer.byteLength(content), path: filePath, receivedAt: Date.now() });

  let requestHeaders;
  const target = http.createServer((req, res) => {
    requestHeaders = req.headers;
    let body = '';
    req.on('data', chunk => { body += chunk; });
    req.on('end', () => {
      assert.equal(body, content);
      res.writeHead(201, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ name, size: Buffer.byteLength(content) }));
    });
  });
  target.listen(0, '127.0.0.1');
  await once(target, 'listening');
  try {
    const transfer = context.startDesktopSend({ id: 'tv-1', name: 'TV', host: '127.0.0.1', port: 45881, webPort: target.address().port }, received.get(name));
    assert.equal(transfer.direction, 'send');
    assert.equal(transfer.name, name);
    assert.equal(transfer.status, 'sending');
    for (let attempt = 0; attempt < 100 && transfer.status === 'sending'; attempt++) {
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    assert.equal(transfer.status, 'done');
    assert.equal(transfer.received, Buffer.byteLength(content));
    assert.equal(transfer.size, Buffer.byteLength(content));
    assert.equal(decodeURIComponent(requestHeaders['x-filename']), name);
    assert.equal(Number(requestHeaders['content-length']), Buffer.byteLength(content));
    assert.ok(activeSends.has(transfer.id));
    assert.ok(logs.some(line => line.includes('desktop send done')));
  } finally {
    await new Promise(resolve => target.close(resolve));
  }

  const rejecting = http.createServer((req, res) => {
    req.resume();
    req.on('end', () => {
      res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('接收已拒绝');
    });
  });
  rejecting.listen(0, '127.0.0.1');
  await once(rejecting, 'listening');
  try {
    const transfer = context.startDesktopSend({ id: 'tv-2', name: 'TV 2', host: '127.0.0.1', webPort: rejecting.address().port }, received.get(name));
    for (let attempt = 0; attempt < 100 && transfer.status === 'sending'; attempt++) {
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    assert.equal(transfer.status, 'failed');
    assert.equal(transfer.error, '接收已拒绝');
  } finally {
    await new Promise(resolve => rejecting.close(resolve));
    fs.rmSync(dir, { recursive: true, force: true });
  }
  console.log('PASS: desktop resend streams received files with progress and reports target rejection');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
