# LanShare Web Desktop

This is a standalone browser UI plus a local Node.js bridge. The bridge listens for LanShare Android/TV UDP discovery packets and proxies HTTP file operations to the selected device.

Start it on a computer with Node.js 18+:

```bash
node bridge.js
```

Open `http://127.0.0.1:45880` in the browser. Keep LanShare open on the phone or TV and connect both devices to the same Wi-Fi.

Optional diagnostics are appended to `~/Downloads/LanShare/lanshare-web.log`. Logging is disabled by default. Enable it explicitly with `LANSHARE_WEB_LOG=true node bridge.js`; use `LANSHARE_WEB_LOG_PATH=/path/to/lanshare-web.log` to override the path. The log records bridge startup, discovery, HTTP/proxy requests, uploads, downloads, TCP receiving, and browser errors, but not file contents.

The browser never accesses LAN UDP directly; only the local bridge does. Android/TV devices expose the file API on port `45888`.
