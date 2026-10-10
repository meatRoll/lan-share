'use strict';
const MAX_HOSTS = 4096;
function ipv4Number(text) {
  const parts = text.split('.');
  if (parts.length !== 4 || !parts.every(part => /^\d{1,3}$/.test(part) && Number(part) <= 255)) throw new Error(`发现网段格式错误\n错误内容：${text}\n正确格式：192.168.1.0/24`);
  return parts.reduce((value, part) => value * 256 + Number(part), 0);
}
function format(ip) { return [24, 16, 8, 0].map(shift => (ip >>> shift) & 255).join('.'); }
function subnet(ip, prefix) {
  const size = 2 ** (32 - prefix);
  const network = Math.floor(ip / size) * size;
  return { network, prefix, size, cidr: `${format(network)}/${prefix}` };
}
function privateIp(ip) { return Math.floor(ip / 2 ** 24) === 10 || Math.floor(ip / 2 ** 20) === 0xac1 || Math.floor(ip / 2 ** 16) === 0xc0a8; }
function contains(net, ip) { return ip >= net.network && ip < net.network + net.size; }
function parse(text) {
  if (typeof text !== 'string' || text.length > 16384) throw new Error('网段配置过长或格式无效');
  const networks = text.split(/[\s,，;；]+/).filter(Boolean).map(token => {
    const parts = token.split('/');
    if (parts.length !== 2) throw new Error(`发现网段格式错误\n错误内容：${token}\n正确格式：192.168.1.0/24`);
    if (!/^\d{1,2}$/.test(parts[1]) || Number(parts[1]) < 20 || Number(parts[1]) > 32) throw new Error(`发现网段格式错误\n错误内容：${token}\n前缀需为：/20～/32\n正确格式：192.168.1.0/24`);
    const net = subnet(ipv4Number(parts[0]), Number(parts[1]));
    if (!privateIp(net.network) || !privateIp(net.network + net.size - 1)) throw new Error(`发现网段格式错误\n错误内容：${token}\n只支持私有网段：10.x.x.x、172.16～31.x.x、192.168.x.x\n正确格式：192.168.1.0/24`);
    return net;
  }).sort((a, b) => a.prefix - b.prefix);
  const minimal = [];
  for (const net of networks) if (!minimal.some(existing => contains(existing, net.network))) minimal.push(net);
  if (minimal.reduce((total, net) => total + net.size, 0) > MAX_HOSTS) throw new Error(`发现网段范围过大\n当前配置合计超过 ${MAX_HOSTS} 个地址\n请缩小网段或分开配置`);
  return minimal;
}
function* targets(networks, ownAddresses = new Set()) {
  for (const net of networks) {
    const start = net.network + (net.prefix <= 30 ? 1 : 0);
    const end = net.network + net.size - (net.prefix <= 30 ? 1 : 0);
    for (let ip = start; ip < end; ip++) {
      const host = format(ip);
      if (!ownAddresses.has(host)) yield host;
    }
  }
}
function localSubnets(interfaces) {
  const networks = [];
  for (const iface of interfaces) {
    if (!iface.cidr) continue;
    const prefix = Number(iface.cidr.split('/')[1]);
    if (!Number.isInteger(prefix) || prefix < 1 || prefix > 32) continue;
    const net = subnet(ipv4Number(iface.address), prefix);
    if (!networks.includes(net.cidr)) networks.push(net.cidr);
  }
  return networks;
}
module.exports = { MAX_HOSTS, ipv4Number, format, parse, contains, targets, localSubnets };
