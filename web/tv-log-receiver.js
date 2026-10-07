#!/usr/bin/env node
const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = Number(process.env.TV_LOG_PORT || 5002);
const OUTPUT = process.env.TV_LOG_OUTPUT || path.join(process.cwd(), 'tv-received-lanshare.log');
let received = 0;

const server = http.createServer((req, res) => {
  if (req.method !== 'POST' || req.url !== '/upload') {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    return res.end('Not found');
  }
  const output = fs.createWriteStream(OUTPUT);
  let size = 0;
  req.on('data', chunk => { size += chunk.length; output.write(chunk); });
  req.on('end', () => output.end(() => {
    received += 1;
    console.log(`received TV log: ${size} bytes -> ${OUTPUT}`);
    res.writeHead(201, { 'Content-Type': 'application/json', 'Content-Length': 11 });
    res.end('{"ok":true}');
  }));
  req.on('error', error => {
    output.destroy();
    console.error(`TV log upload failed: ${error.message}`);
    if (!res.headersSent) res.writeHead(500);
    res.end('Upload failed');
  });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`TV log receiver listening on http://0.0.0.0:${PORT}/upload`);
  console.log(`output: ${OUTPUT}`);
});
