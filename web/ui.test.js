const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, 'app.js'), 'utf8');
const elements = new Map();
function element() { return { innerHTML: '', textContent: '', value: '', style: {}, dataset: {}, open: false,
  showModal() { this.open = true; }, close() { this.open = false; }, focus() {},
  addEventListener() {}, append() {}, querySelectorAll() { return []; } }; }
const timers = [];
const longName = '中文超长文件名😀'.repeat(80) + '.zip';
let savedNetworks = '192.168.211.0/24';
let copiedText;
const context = {
  navigator: { clipboard: { async writeText(text) { copiedText = text; } } },
  console, setInterval(fn) { timers.push(fn); },
  window: { addEventListener() {} },
  document: {
    querySelector(selector) { if (!elements.has(selector)) elements.set(selector, element()); return elements.get(selector); },
    querySelectorAll() { return []; }, createElement: element, body: element(),
  },
  async fetch(url, options) {
    if (url === '/api/discovery/settings') {
      if (options?.method === 'POST') {
        const input = JSON.parse(options.body).networks;
        try { savedNetworks = require('./discovery-networks').parse(input).map(net => net.cidr).join('\n'); }
        catch (error) { return { ok: false, async json() { return { error: error.message }; } }; }
      }
      return { ok: true, async json() { return { currentNetworks: ['192.168.216.0/24'], networks: savedNetworks }; } };
    }
    return { ok: true, async json() {
      if (url === '/api/devices') return [{ id: 'tv', name: '极米', host: '192.168.1.10' }];
      if (url === '/api/desktop/files') return [{ name: longName, size: 1, url: '/download/test', locateUrl: '/locate/test' }];
      return [];
    } };
  },
};
vm.createContext(context);
vm.runInContext(source, context);
(async () => {
  assert.equal(elements.get('#picker').disabled, true);
  assert.equal(elements.get('#chooseFiles').disabled, true);
  vm.runInContext("target = { id: 'tv' }; updateSendControls();", context);
  assert.equal(elements.get('#picker').disabled, false);
  assert.equal(elements.get('#chooseFiles').disabled, false);
  vm.runInContext("target = { id: 'offline' };", context);
  await vm.runInContext('loadDevices();', context);
  assert.equal(elements.get('#picker').disabled, true);
  assert.equal(elements.get('#chooseFiles').disabled, true);
  await vm.runInContext('loadFiles();', context);
  assert.ok(elements.get('#devices').innerHTML.includes('极米'));
  assert.ok(!elements.get('#devices').innerHTML.includes('这台电脑'));
  assert.ok(elements.get('#files').innerHTML.includes(longName));
  assert.ok(elements.get('#files').innerHTML.includes('class="file-info"'));
  await timers[timers.length - 1]();
  const css = fs.readFileSync(path.join(__dirname, 'style.css'), 'utf8');
  assert.ok(css.includes('overflow-wrap:anywhere'));
  assert.ok(css.includes('.file-info{flex:1 1 160px;min-width:0}'));
  await vm.runInContext('loadNetworkSettings();', context);
  assert.equal(elements.get('#currentNetworks').textContent, '192.168.216.0/24');
  await elements.get('#currentNetworks').onclick();
  assert.equal(copiedText, '192.168.216.0/24');
  await elements.get('#settings').onclick();
  assert.equal(elements.get('#networkSettings').open, true);
  assert.equal(elements.get('#networkInput').value, '192.168.211.0/24');
  elements.get('#addCurrentNetwork').onclick();
  assert.equal(elements.get('#networkInput').value, '192.168.211.0/24\n192.168.216.0/24');
  elements.get('#networkInput').value = '8.8.8.0/24';
  await elements.get('#networkForm').onsubmit({ preventDefault() {} });
  assert.equal(elements.get('#networkSettings').open, true);
  assert(elements.get('#networkError').textContent.length > 0);
  elements.get('#networkInput').value = '192.168.211.229/24\n192.168.216.0/24';
  await elements.get('#networkForm').onsubmit({ preventDefault() {} });
  assert.equal(elements.get('#networkSettings').open, false);
  assert.equal(savedNetworks, '192.168.211.0/24\n192.168.216.0/24');
  console.log('PASS: send controls, Unicode file layout, current subnet copy, multiple network settings and validation errors');
})().catch(error => { console.error(error); process.exitCode = 1; });
