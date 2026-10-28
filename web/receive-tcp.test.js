const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const net = require('node:net');
const vm = require('node:vm');
const { once } = require('node:events');

(async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'lanshare-tcp-'));
  const source = fs.readFileSync(path.join(__dirname, 'bridge.js'), 'utf8');
  const functions = source.slice(source.indexOf('function safeName'), source.indexOf('discover();'));
  const received = new Map();
  const activeReceives = new Map();
  const context = { fs, path, require, console, Buffer, receivedDir: dir, received, activeReceives, setTimeout, MAGIC: 'LANSHARE',
    requestReceive: (name, size, host, decide) => {
      setImmediate(() => decide(name !== 'reject.txt'));
      return () => {};
    },
  };
  vm.createContext(context);
  vm.runInContext(functions, context);
  assert.equal(context.safeName('中文 文件😀.jpg'), '中文 文件😀.jpg');
  assert.equal(context.safeName('../中文/照片\\\\坏\r\n.jpg'), '.._中文_照片__坏__.jpg');
  for (const name of ['', '.', '..', '   ']) assert.equal(context.safeName(name), 'file');
  assert.equal(Array.from(context.safeName('😀'.repeat(121))).length, 120);
  assert.equal(context.safeName('中文.jpg. '), '中文.jpg');
  const disposition = context.contentDisposition('中文 文件😀.jpg');
  assert.ok(disposition.includes("filename*=UTF-8''%E4%B8%AD%E6%96%87%20"));
  assert.ok([...disposition].every(char => char.charCodeAt(0) < 128));
  const server = net.createServer({ allowHalfOpen: true }, context.receiveTcp);
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  try {
    await Promise.all([
      ['normal.txt', 'hello desktop', 13],
      ['中文 文件😀.txt', 'unicode filename', 16],
      ['empty.txt', '', 0],
      ['unknown.txt', 'unknown size', -1],
    ].map(async ([name, content, size]) => {
      const socket = net.connect(server.address().port, '127.0.0.1');
      socket.setTimeout(2000, () => socket.destroy(new Error('confirmation timeout')));
      const closed = once(socket, 'close');
      await once(socket, 'connect');
      socket.write(`LANSHARE|FILE|${name}|${size}\n`);
      const [confirmation] = await once(socket, 'data');
      assert.equal(confirmation.toString(), 'ACCEPT\n');
      const done = once(socket, 'data');
      socket.end(content);
      const [saved] = await done;
      assert.equal(saved.toString(), 'DONE\n');
      await closed;
      assert.equal(fs.readFileSync(path.join(dir, name), 'utf8'), content);
      assert.equal(received.get(name).size, Buffer.byteLength(content));
    }));
    assert.equal(activeReceives.size, 0);
    const duplicate = net.connect(server.address().port, '127.0.0.1');
    const duplicateClosed = once(duplicate, 'close');
    await once(duplicate, 'connect');
    duplicate.write('LANSHARE|FILE|normal.txt|11\n');
    const [duplicateConfirmation] = await once(duplicate, 'data');
    assert.equal(duplicateConfirmation.toString(), 'ACCEPT\n');
    const duplicateDone = once(duplicate, 'data');
    duplicate.end('replacement');
    const [duplicateReply] = await duplicateDone;
    assert.equal(duplicateReply.toString(), 'DONE\n');
    await duplicateClosed;
    assert.equal(fs.readFileSync(path.join(dir, 'normal.txt'), 'utf8'), 'hello desktop');
    assert.equal(fs.readFileSync(path.join(dir, 'normal (1).txt'), 'utf8'), 'replacement');
    assert.equal(received.get('normal (1).txt').size, 11);
    await Promise.all(['second copy', 'third copy'].map(async content => {
      const connection = net.connect(server.address().port, '127.0.0.1');
      connection.setTimeout(2000, () => connection.destroy(new Error('duplicate receive timeout')));
      const closed = once(connection, 'close');
      await once(connection, 'connect');
      connection.write(`LANSHARE|FILE|normal.txt|${Buffer.byteLength(content)}\n`);
      const [accept] = await once(connection, 'data');
      assert.equal(accept.toString(), 'ACCEPT\n');
      const done = once(connection, 'data');
      connection.end(content);
      const [saved] = await done;
      assert.equal(saved.toString(), 'DONE\n');
      await closed;
    }));
    assert.deepEqual(new Set(['normal (2).txt', 'normal (3).txt'].map(name => fs.readFileSync(path.join(dir, name), 'utf8'))), new Set(['second copy', 'third copy']));
    assert.equal(fs.readFileSync(path.join(dir, 'normal.txt'), 'utf8'), 'hello desktop');
    assert.equal(fs.readFileSync(path.join(dir, 'normal (1).txt'), 'utf8'), 'replacement');
    const progressName = 'progress.txt';
    const progress = net.connect(server.address().port, '127.0.0.1');
    progress.setTimeout(2000, () => progress.destroy(new Error('progress receive timeout')));
    const progressClosed = once(progress, 'close');
    await once(progress, 'connect');
    progress.write(`LANSHARE|FILE|${progressName}|11\n`);
    const [progressAccept] = await once(progress, 'data');
    assert.equal(progressAccept.toString(), 'ACCEPT\n');
    progress.write('hello');
    let active;
    for (let attempt = 0; attempt < 50 && (!active || active.received === 0); attempt++) {
      await new Promise(resolve => setImmediate(resolve));
      active = [...activeReceives.values()].find(transfer => transfer.name === progressName);
    }
    assert.ok(active, 'active receive progress should be published');
    assert.equal(active.received, 5);
    assert.equal(active.size, 11);
    const progressDone = once(progress, 'data');
    progress.end(' world');
    const [progressSaved] = await progressDone;
    assert.equal(progressSaved.toString(), 'DONE\n');
    await progressClosed;
    assert.equal(fs.readFileSync(path.join(dir, progressName), 'utf8'), 'hello world');
    assert.equal(activeReceives.size, 0);
    const rejected = net.connect(server.address().port, '127.0.0.1');
    const rejectedClosed = once(rejected, 'close');
    await once(rejected, 'connect');
    rejected.write('LANSHARE|FILE|reject.txt|4\n');
    const [reply] = await once(rejected, 'data');
    assert.equal(reply.toString(), 'REJECT\n');
    await rejectedClosed;
    assert.equal(fs.existsSync(path.join(dir, 'reject.txt')), false);
    console.log('PASS: accept/reject, DONE, normal, empty, unknown-size and same-name preservation');
  } finally {
    await new Promise(resolve => server.close(resolve));
    fs.rmSync(dir, { recursive: true, force: true });
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
