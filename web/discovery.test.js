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
const discoveryNetworks = require('./discovery-networks');
const timeouts = [];
const context = { Buffer, console, devices, knownHosts, discoveryNetworks, configuredNetworks: [], networkScanGeneration: 0, networkScanTimer: null,
  setTimeout(callback) { timeouts.push(callback); return timeouts.length; }, clearTimeout() {},
  MAGIC: 'LANSHARE', desktopId: 'desktop-test', desktopName: 'Web Desktop', TCP_PORT: 45881,
  DISCOVERY_PORT: 45454, dgram: { createSocket: () => socket },
  os: { networkInterfaces: () => ({ en0: [{ internal: false, family: 'IPv4', address: '192.168.3.42', netmask: '255.255.255.0' }] }) },
  rememberHost: host => knownHosts.add(host), setInterval: () => {},
};
vm.createContext(context);
vm.runInContext(source.slice(source.indexOf('function lanInterfaces'), source.indexOf('const pendingReceives')), context);
vm.runInContext(source.slice(source.indexOf('function sendDiscovery'), source.indexOf('function json')), context);
assert.equal(context.localAddresses().join(','), '192.168.3.42');
assert.equal(context.normalizeDeviceName('  书房|电脑\n用户名  '), '书房 电脑 用户名');
assert.equal(context.normalizeDeviceName(''), 'Web Desktop');
assert.equal(Array.from(context.normalizeDeviceName('名'.repeat(60))).length, 40);
context.discover();
assert(sent.some(packet => packet.host === '192.168.3.255' && packet.message.includes('|HELLO|')));
assert(sent.some(packet => packet.host === '192.168.3.54' && packet.message.includes('|QUERY|')));
assert(!sent.some(packet => packet.host === '10.0.0.8'));
receive(Buffer.from('LANSHARE|QUERY|phone-one|Phone|45455'), { address: '192.168.3.54', port: 45454 });
assert.equal(devices.get('phone-one').host, '192.168.3.54');
assert(sent.some(packet => packet.host === '192.168.3.54' && packet.port === 45454 && packet.message.includes('|HELLO|desktop-test|Web Desktop|')));
receive(Buffer.from('LANSHARE|QUERY|phone-new-id|Phone|45455'), { address: '192.168.3.54', port: 45454 });
assert.equal(devices.size, 1);
const count = sent.length;
receive(Buffer.from('LANSHARE|HELLO|phone-new-id|Phone|45455'), { address: '192.168.3.54', port: 45454 });
assert.equal(sent.length, count, 'HELLO must not trigger reply loops');
context.configuredNetworks = discoveryNetworks.parse('192.168.211.0/24\n10.0.0.8/32');
context.startNetworkScan();
while (!sent.some(packet => packet.host === '192.168.211.229')) timeouts.shift()();
assert(sent.some(packet => packet.host === '192.168.211.229' && packet.message.includes('|QUERY|')));
receive(Buffer.from('LANSHARE|HELLO|cross-subnet-phone|Phone|45455'), { address: '192.168.211.229', port: 45454 });
assert.equal(devices.get('cross-subnet-phone').host, '192.168.211.229');
context.sendDiscovery();
assert(sent.some(packet => packet.host === '10.0.0.8' && packet.message.includes('|QUERY|')));
const beforeCancel = sent.length;
context.configuredNetworks = [];
context.startNetworkScan();
while (timeouts.length) timeouts.shift()();
assert.equal(sent.length, beforeCancel, 'clearing settings cancels outstanding subnet queries');
context.desktopName = '书房电脑';
context.sendDiscovery();
assert(sent.some(packet => packet.message.includes('|HELLO|desktop-test|书房电脑|')), 'renamed desktop should advertise its new name');
console.log('PASS: broadcast, known peers, cross-subnet QUERY/reply, rename, scan cancellation, dedup and no reply loops');
