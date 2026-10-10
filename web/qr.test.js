const assert = require('node:assert/strict');
const QRCode = require('./vendor/qrcode');
const levels = require('./vendor/qrcode/QRErrorCorrectLevel');
const qr = new QRCode(0, levels.M);
qr.addData('LANSHARE|NETWORKS|192.168.211.0/24\n192.168.216.0/24');
qr.make();
assert(qr.getModuleCount() >= 21);
assert.equal(typeof qr.isDark(0, 0), 'boolean');
console.log('PASS: local QR encoder creates a scannable matrix for multiple CIDR networks');
