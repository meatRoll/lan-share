const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').join(__dirname, 'bridge.js'), 'utf8');
const sent = [];
let receive;
const socket = {
  on(event, callback) { if (event === 'message') receive = callback; },
  bind(port, callback) { callback(); },
  setBroadcast() {},
  send(message, port, host, callback) { sent.push({ message: message.toString(), port, host }); callback(); },
};
const devices = new Map();
const knownHosts = new Set(['192.168.3.54', '10.0.0.8']);
const context = { Buffer, console, devices, knownHosts, MAGIC: 'LANSHARE', desktopId: 'desktop-test', TCP_PORT: 45881,
  DISCOVERY_PORT: 45454, dgram: { createSocket: () => socket },
  os: { networkInterfaces: () => ({ en0: [{ internal: false, family: 'IPv4', address: '192.168.3.42', netmask: '255.255.255.0' }] }) },
  rememberHost: host => knownHosts.add(host), setInterval: () => {},
};
vm.createContext(context);
vm.runInContext(source.slice(source.indexOf('function lanInterfaces'), source.indexOf('const pendingReceives')), context);
vm.runInContext(source.slice(source.indexOf('function sendDiscovery'), source.indexOf('function json')), context);
context.discover();
assert(sent.some(packet => packet.host === '192.168.3.255' && packet.message.includes('|HELLO|')));
assert(sent.some(packet => packet.host === '192.168.3.54' && packet.message.includes('|QUERY|')));
assert(!sent.some(packet => packet.host === '10.0.0.8'));
receive(Buffer.from('LANSHARE|QUERY|phone-one|Phone|45455'), { address: '192.168.3.54', port: 45454 });
assert.equal(devices.get('phone-one').host, '192.168.3.54');
assert(sent.some(packet => packet.host === '192.168.3.54' && packet.port === 45454 && packet.message.includes('|HELLO|desktop-test|')));
receive(Buffer.from('LANSHARE|QUERY|phone-new-id|Phone|45455'), { address: '192.168.3.54', port: 45454 });
assert.equal(devices.size, 1);
const count = sent.length;
receive(Buffer.from('LANSHARE|HELLO|phone-new-id|Phone|45455'), { address: '192.168.3.54', port: 45454 });
assert.equal(sent.length, count, 'HELLO must not trigger reply loops');
console.log('PASS: subnet broadcast, known-peer QUERY, unicast HELLO reply, LAN restriction, dedup, no reply loop');
