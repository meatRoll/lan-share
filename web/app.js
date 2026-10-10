let target = null;
let deviceRenderKey = null;

function reportClientLog(level, message, error) {
  const detail = error ? `; error=${error.stack || error.message || error}` : '';
  const line = `[${level}] ${message}${detail}`;
  fetch('/api/log', { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: line }).catch(() => {});
  return line;
}
window.addEventListener('error', event => reportClientLog('ERROR', `window error: ${event.message}`, event.error));
window.addEventListener('unhandledrejection', event => reportClientLog('ERROR', 'unhandled promise rejection', event.reason));

const $ = (selector) => document.querySelector(selector);
const devices = $("#devices");
const files = $("#files");
const status = $("#status");
const picker = $("#picker");
const drop = $("#drop");
const chooseFiles = $("#chooseFiles");
function updateSendControls() {
  const disabled = !target;
  picker.disabled = disabled;
  chooseFiles.disabled = disabled;
}
chooseFiles.onclick = () => { if (target) picker.click(); };
updateSendControls();

function formatSize(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function escapeHtml(value) {
  return value.replace(/[&<>"']/g, (character) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;",
  })[character]);
}

async function loadDevices() {
  try {
    const response = await fetch("/api/devices");
    const discoveredDevices = await response.json();
    const allDevices = discoveredDevices;
    if (target && !allDevices.some(device => device.id === target.id)) {
      target = null;
      $("#targetHint").textContent = "目标设备已离线，请重新选择";
    }

    updateSendControls();
    const renderKey = JSON.stringify({ devices: allDevices.map(({ id, name, host }) => ({ id, name, host })), selected: target?.id });
    if (renderKey === deviceRenderKey) return;
    deviceRenderKey = renderKey;
    devices.innerHTML = allDevices
      .map((device) => `
        <button class="device ${target?.id === device.id ? "selected" : ""}" data-id="${device.id}">
          <b>${escapeHtml(device.name)}</b>
          <small>${escapeHtml(device.host)}</small>
        </button>
      `)
      .join("") || '<p class="muted">暂未发现设备，请保持手机或 TV 上的 LanShare 打开</p>';

    document.querySelectorAll(".device").forEach((button) => {
      button.onclick = () => {
        target = allDevices.find((device) => device.id === button.dataset.id);
        $("#targetHint").textContent = `发送到：${target.name}`;
        updateSendControls();
        loadFiles();
        loadDevices();
      };
    });
  } catch (error) {
    reportClientLog('ERROR', 'load devices failed', error);
    target = null;
    updateSendControls();
    $("#targetHint").textContent = "本地服务未运行，请重新选择设备";
    status.textContent = "本地服务未运行";
  }
}

async function requestDevice(path, options = {}) {
  if (!target) throw new Error("请先选择目标设备");
  return fetch(`/device/${encodeURIComponent(target.id)}${path}`, options);
}

async function loadFiles() {
  try {
    const response = await fetch("/api/desktop/files");
    const deviceFiles = await response.json();

    files.innerHTML = deviceFiles.length
      ? deviceFiles.map((file) => {
          const downloadUrl = file.url;
          return `
            <div class="file">
              <div class="file-info">
                <div class="file-name" title="${escapeHtml(file.name)}">${escapeHtml(file.name)}</div>
                <div class="file-size">${formatSize(file.size)}</div>
              </div>
              <a class="download" href="${downloadUrl}">下载</a>
              <button class="locate" data-url="${file.locateUrl}">打开位置</button>
            </div>
          `;
        }).join("")
      : '<p class="muted">暂无文件</p>';
    files.querySelectorAll('.locate').forEach(button => {
      button.onclick = async () => {
        const response = await fetch(button.dataset.url);
        status.textContent = response.ok ? '已在文件管理器中选中文件' : '无法打开文件位置';
      };
    });
  } catch (error) {
    reportClientLog('ERROR', 'load files failed', error);
    files.innerHTML = '<p class="muted">无法读取已接收文件</p>';
  }
}

async function upload(fileList) {
  try {
    if (!target) throw new Error("请先选择目标设备");

    for (const file of fileList) {
      status.textContent = `正在上传 ${file.name}…`;
      const response = await requestDevice("/upload", {
            method: "POST",
            headers: { "X-Filename": encodeURIComponent(file.name) },
            body: file,
          });

      if (!response.ok) throw new Error("上传失败");
    }

    status.textContent = "上传完成";
    await loadFiles();
  } catch (error) {
    reportClientLog('ERROR', 'upload failed', error);
    status.textContent = error.message;
  }
}

let currentNetworks = [];
let savedNetworks = '';
async function loadNetworkSettings() {
  try {
    const response = await fetch('/api/discovery/settings');
    if (!response.ok) throw new Error('无法读取网段设置');
    const settings = await response.json();
    currentNetworks = settings.currentNetworks || [];
    savedNetworks = settings.networks || '';
    $('#currentNetworks').textContent = currentNetworks.join(' · ') || '未连接 IPv4 网络';
    $('#currentNetworks').disabled = !currentNetworks.length;
    const qrNetworks = savedNetworks || currentNetworks.join('\n');
    $('#configuredQrImage').src = qrNetworks ? `/api/discovery/qr.svg?networks=${encodeURIComponent(qrNetworks)}` : '';
  } catch (error) { $('#currentNetworks').textContent = error.message; }
}
$('#currentNetworks').onclick = async () => {
  try {
    await navigator.clipboard.writeText(currentNetworks.join('\n'));
    status.textContent = '当前网段已复制';
  } catch (_) { status.textContent = '无法访问剪贴板，请手动复制显示的网段'; }
};
$('#currentNetworkQr').onclick = async () => {
  await loadNetworkSettings();
  if (!currentNetworks.length) { status.textContent = '当前没有可显示的 IPv4 网段'; return; }
  $('#currentQrImage').src = `/api/discovery/qr.svg?networks=${encodeURIComponent(currentNetworks.join('\n'))}`;
  $('#currentQrText').textContent = currentNetworks.join('\n');
  $('#currentQrDialog').showModal();
};
$('#closeCurrentQr').onclick = () => $('#currentQrDialog').close();
$('#networkInput').oninput = () => { };
$('#showConfiguredQr').onclick = async () => {
  await loadNetworkSettings();
  const qrNetworks = savedNetworks || currentNetworks.join('\n');
  if (!qrNetworks) { status.textContent = '没有已配置或当前 IPv4 网段'; return; }
  $('#configuredQrImage').src = `/api/discovery/qr.svg?networks=${encodeURIComponent(qrNetworks)}`;
  $('#configuredQrText').textContent = qrNetworks;
  $('#configuredQrDialog').showModal();
};
$('#closeConfiguredQr').onclick = () => $('#configuredQrDialog').close();
$('#settings').onclick = async () => {
  await loadNetworkSettings();
  $('#networkInput').value = savedNetworks;
  $('#networkError').textContent = '';
  $('#networkSettings').showModal();
  $('#networkInput').focus();
};
$('#cancelNetworkSettings').onclick = () => $('#networkSettings').close();
$('#addCurrentNetwork').onclick = () => {
  $('#networkInput').value = [...new Set([...$('#networkInput').value.trim().split('\n').filter(Boolean), ...currentNetworks])].join('\n');
};
$('#networkForm').onsubmit = async event => {
  event.preventDefault();
  try {
    const response = await fetch('/api/discovery/settings', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ networks: $('#networkInput').value }),
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || '保存失败');
    savedNetworks = result.networks;
    $('#networkSettings').close();
    status.textContent = '发现网段已保存，正在搜索';
    await loadDevices();
  } catch (error) { $('#networkError').textContent = error.message; }
};
loadNetworkSettings();
setInterval(loadNetworkSettings, 10000);

$("#refresh").onclick = async () => {
  try {
    const response = await fetch('/api/discovery/refresh', { method: 'POST' });
    if (!response.ok) throw new Error('重新搜索失败');
    status.textContent = '正在搜索广播、已知 IP 和配置的网段';
    await loadDevices();
  } catch (error) { status.textContent = error.message; }
};
$("#reloadFiles").onclick = loadFiles;
picker.onchange = () => upload(picker.files);

["dragenter", "dragover"].forEach((eventName) => {
  drop.addEventListener(eventName, (event) => {
    event.preventDefault();
    if (target) drop.style.borderColor = "#d7f36b";
  });
});

drop.addEventListener("dragleave", () => {
  drop.style.borderColor = "";
});

drop.addEventListener("drop", (event) => {
  event.preventDefault();
  drop.style.borderColor = "";
  if (!target) {
    status.textContent = "请先选择目标设备";
    return;
  }
  upload(event.dataTransfer.files);
});

const receiveDialog = document.createElement('dialog');
const receiveMessage = document.createElement('p');
const acceptButton = document.createElement('button');
const rejectButton = document.createElement('button');
acceptButton.textContent = '接收';
rejectButton.textContent = '拒绝';
receiveDialog.append(receiveMessage, acceptButton, rejectButton);
document.body.append(receiveDialog);
let shownReceive = null;
let checkingReceives = false;
let decidingReceive = false;
async function decideReceive(accept) {
  if (!shownReceive || decidingReceive) return;
  decidingReceive = true;
  try {
    const response = await fetch(`/api/receives/${encodeURIComponent(shownReceive)}/${accept ? 'accept' : 'reject'}`, { method: 'POST' });
    if (!response.ok) throw new Error('接收请求已过期或处理失败');
    status.textContent = accept ? '已同意接收，等待文件保存完成' : '已拒绝接收';
    receiveDialog.close();
    shownReceive = null;
  } catch (error) {
    status.textContent = error.message;
  } finally { decidingReceive = false; }
}
acceptButton.onclick = () => decideReceive(true);
rejectButton.onclick = () => decideReceive(false);
receiveDialog.addEventListener('cancel', event => { event.preventDefault(); decideReceive(false); });
async function checkReceives() {
  if (checkingReceives || decidingReceive) return;
  checkingReceives = true;
  try {
    const response = await fetch('/api/receives');
    if (!response.ok) throw new Error('无法读取接收请求');
    const pending = await response.json();
    if (shownReceive && !pending.some(request => request.id === shownReceive)) {
      receiveDialog.close();
      shownReceive = null;
    }
    if (!shownReceive && pending.length) {
      const request = pending[0];
      shownReceive = request.id;
      receiveMessage.textContent = `${request.host} 请求发送「${request.name}」（${request.size < 0 ? '大小未知' : formatSize(request.size)}）。是否接收？请在 25 秒内选择。`;
      receiveDialog.showModal();
    }
  } catch (error) { status.textContent = error.message; }
  finally { checkingReceives = false; }
}
loadDevices();
loadFiles();
checkReceives();
setInterval(checkReceives, 1000);
setInterval(() => { loadDevices(); loadFiles(); }, 3000);
