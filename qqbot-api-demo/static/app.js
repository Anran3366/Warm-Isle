/* global fetch, FormData, Audio */

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));

const els = {
  modePill: $("#modePill"),
  modeText: $("#modeText"),
  botName: $("#botName"),
  phoneAvatar: $("#phoneAvatar"),
  addBotLink: $("#addBotLink"),
  chatBody: $("#chatBody"),
  chatEmpty: $("#chatEmpty"),
  chatDate: $("#chatDate"),
  targetType: $("#targetType"),
  targetId: $("#targetId"),
  contactRow: $("#contactRow"),
  contactSelect: $("#contactSelect"),
  targetHelp: $("#targetHelp"),
  liveToggle: $("#liveToggle"),
  saveTarget: $("#saveTarget"),
  restartGateway: $("#restartGateway"),
  gatewayChip: $("#gatewayChip"),
  gatewayText: $("#gatewayText"),
  textInput: $("#textInput"),
  textCount: $("#textCount"),
  sendTextBtn: $("#sendTextBtn"),
  stickerInput: $("#stickerInput"),
  dropzone: $("#dropzone"),
  dropEmpty: $("#dropEmpty"),
  dropPreview: $("#dropPreview"),
  stickerPreview: $("#stickerPreview"),
  stickerName: $("#stickerName"),
  stickerSize: $("#stickerSize"),
  removeFile: $("#removeFile"),
  sendStickerBtn: $("#sendStickerBtn"),
  voiceText: $("#voiceText"),
  voiceCount: $("#voiceCount"),
  voiceSelect: $("#voiceSelect"),
  formatBadge: $("#formatBadge"),
  audioPreview: $("#audioPreview"),
  previewAudio: $("#previewAudio"),
  previewPlayBtn: $("#previewPlayBtn"),
  audioDuration: $("#audioDuration"),
  sendVoiceBtn: $("#sendVoiceBtn"),
  logList: $("#logList"),
  logCount: $("#logCount"),
  settingsBtn: $("#settingsBtn"),
  settingsModal: $("#settingsModal"),
  closeSettings: $("#closeSettings"),
  appIdInput: $("#appIdInput"),
  appSecretInput: $("#appSecretInput"),
  autoCaptureToggle: $("#autoCaptureToggle"),
  testBtn: $("#testBtn"),
  saveSettings: $("#saveSettings"),
  settingsBotName: $("#settingsBotName"),
  settingsBotState: $("#settingsBotState"),
  settingsShareLink: $("#settingsShareLink"),
  settingsBot: $("#settingsBot"),
  toastStack: $("#toastStack"),
  pasteBtn: $("#pasteBtn"),
};

let state = null;
let selectedFile = null;
let selectedPreviewUrl = null;
let targetDirty = false;
let targetType = "c2c";
let lastMessageSignature = "";
let lastContactSignature = "";
let polling = false;
let settingsDirty = false;
let currentAudio = null;
let currentVoiceButton = null;

const MAX_UPLOAD = 5 * 1024 * 1024;

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function fmtTime(iso) {
  if (!iso) return "";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "";
  return date.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false });
}

function fmtSize(bytes) {
  if (!bytes && bytes !== 0) return "";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}

function toast(message, tone = "success", duration = 3200) {
  const node = document.createElement("div");
  node.className = `toast ${tone === "success" ? "" : tone}`.trim();
  node.textContent = message;
  els.toastStack.appendChild(node);
  window.setTimeout(() => {
    node.classList.add("leaving");
    window.setTimeout(() => node.remove(), 240);
  }, duration);
}

async function apiFetch(url, options = {}) {
  const response = await fetch(url, options);
  let data = {};
  try {
    data = await response.json();
  } catch {
    data = {};
  }
  if (!response.ok || data.ok === false) {
    const error = new Error(data.error || `请求失败（HTTP ${response.status}）`);
    error.detail = data.detail;
    error.status = response.status;
    throw error;
  }
  return data;
}

async function refreshState({ quiet = true } = {}) {
  if (polling) return;
  polling = true;
  try {
    state = await apiFetch("/api/state");
    render();
  } catch (error) {
    if (!quiet) toast(error.message, "error");
  } finally {
    polling = false;
  }
}

function currentTarget(type = targetType) {
  return {
    target_type: type,
    target_id: els.targetId.value.trim(),
  };
}

function ensureTargetForLive() {
  if (!state?.config.live) return true;
  if (!els.targetId.value.trim()) {
    toast("先在 QQ 里给「小满」发一条消息，或手动填 OpenID", "warn", 4200);
    els.targetId.focus();
    return false;
  }
  return true;
}

/* ----------------------------------------------------------------- render */
function render() {
  if (!state) return;
  const { config, bot, gateway, runtime } = state;

  const connected = state.configured && !!bot;
  els.modePill.classList.toggle("online", connected && config.live);
  els.modePill.classList.toggle("mock", !config.live || !state.configured);
  els.modePill.classList.toggle("offline", state.configured && !bot && config.live);
  if (!config.live) {
    els.modeText.textContent = "模拟模式";
  } else if (!state.configured) {
    els.modeText.textContent = "未配置凭据";
  } else if (connected) {
    els.modeText.textContent = "真实发送 · 已连接";
  } else {
    els.modeText.textContent = "凭据待验证";
  }

  const botName = bot?.username || "小满";
  els.botName.textContent = botName;
  if (bot?.avatar) {
    els.phoneAvatar.innerHTML = `<img src="${escapeHtml(bot.avatar)}" alt="${escapeHtml(botName)}">`;
  } else {
    els.phoneAvatar.innerHTML = `<span>${escapeHtml(botName.slice(0, 1))}</span>`;
  }
  if (bot?.share_url) {
    els.addBotLink.href = bot.share_url;
    els.addBotLink.style.display = "";
    els.settingsShareLink.href = bot.share_url;
    els.settingsShareLink.style.display = "";
  } else {
    els.addBotLink.style.display = "none";
    els.settingsShareLink.style.display = "none";
  }

  if (targetType !== config.target_type && !targetDirty) {
    targetType = config.target_type || "c2c";
  }
  setTargetType(targetType, false);
  if (!targetDirty) {
    els.targetId.value = config.target_id || "";
  }
  els.liveToggle.checked = !!config.live;

  renderContacts();
  renderGateway(gateway);
  renderMessages();
  renderLogs();
  renderRuntime(runtime);
  els.saveTarget.disabled = false;
}

function setTargetType(type, markDirty = true) {
  targetType = type === "group" ? "group" : "c2c";
  $$(".seg-btn", els.targetType).forEach((button) => {
    const active = button.dataset.type === targetType;
    button.classList.toggle("active", active);
    button.setAttribute("aria-selected", active ? "true" : "false");
  });
  els.targetType.classList.toggle("group", targetType === "group");
  if (markDirty) targetDirty = true;
  els.targetId.placeholder = targetType === "c2c"
    ? "在 QQ 里给机器人发条消息，自动填入"
    : "群里 @机器人 后自动填入 group_openid";
}

function renderGateway(gateway) {
  const status = gateway?.status || "disabled";
  els.gatewayChip.className = "gateway-chip";
  let text = "监听未启动";
  if (status === "online") {
    els.gatewayChip.classList.add("online");
    text = "OpenID 监听中";
  } else if (status === "connecting") {
    text = "正在连接监听";
  } else if (status === "reconnecting") {
    els.gatewayChip.classList.add("error");
    text = "监听重连中";
  } else if (status === "disabled") {
    text = "监听未启用";
  }
  els.gatewayText.textContent = text;

  let help = "";
  if (!state.configured) {
    help = "先在设置中填写 AppID 和 AppSecret，保存后即可开始监听。";
  } else if (!state.config.auto_capture) {
    help = "自动捕获已关闭。打开设置里的「自动捕获 OpenID」并重启监听。";
  } else if (status === "online") {
    help = `正在监听「${state.bot?.username || "小满"}」的消息。在 QQ 里发一句任意内容，OpenID 就会自动填入。`;
  } else if (status === "reconnecting") {
    help = `监听重连中${gateway.error ? `：${gateway.error}` : ""}`;
  } else {
    help = "点击「重连监听」启动 WebSocket；也可以手动填写对方 OpenID。";
  }
  els.targetHelp.innerHTML = `<span class="pulse-ring"></span>${escapeHtml(help)}`;
}

function renderContacts() {
  const contacts = state.contacts || [];
  const signature = contacts.map((contact) => `${contact.key}:${contact.last_seen}`).join("|");
  if (signature !== lastContactSignature) {
    lastContactSignature = signature;
    els.contactRow.hidden = contacts.length === 0;
    const previous = els.contactSelect.value;
    els.contactSelect.innerHTML = contacts
      .map((contact) => {
        const label = `${contact.type === "group" ? "群" : "单聊"} · ${contact.name || contact.id.slice(0, 10)}`;
        return `<option value="${escapeHtml(contact.key)}">${escapeHtml(label)}</option>`;
      })
      .join("");
    if (contacts.some((contact) => contact.key === previous)) {
      els.contactSelect.value = previous;
    }
  }

  const selected = state.config.target_id;
  if (selected) {
    const match = contacts.find((contact) => contact.id === selected);
    if (match) els.contactSelect.value = match.key;
  }
}

function waveformBars(seed = 0, count = 18) {
  let html = "";
  for (let i = 0; i < count; i += 1) {
    const height = 4 + ((Math.sin((i + seed) * 1.8) + 1) / 2) * 11 + ((i * 7 + seed) % 4);
    html += `<span style="height:${height.toFixed(1)}px"></span>`;
  }
  return html;
}

function renderMessages() {
  const messages = state.messages || [];
  const signature = messages
    .map((message) => `${message.id}:${message.status}:${message.media_url || ""}`)
    .join("|");
  if (signature === lastMessageSignature) return;
  lastMessageSignature = signature;

  els.chatEmpty.hidden = messages.length > 0;
  const oldSystem = $$(".msg-system", els.chatBody);
  oldSystem.forEach((node) => node.remove());
  $$(".msg", els.chatBody).forEach((node) => node.remove());

  if (messages.length === 0) {
    els.chatDate.textContent = "今天";
    return;
  }

  const fragment = document.createDocumentFragment();
  messages.forEach((message, index) => {
    const node = document.createElement("div");
    const outgoing = message.direction === "out";
    node.className = `msg ${outgoing ? "out" : "in"}`;
    node.dataset.messageId = message.id;
    const time = fmtTime(message.ts);
    const avatar = outgoing
      ? ""
      : `<div class="msg-avatar user">${escapeHtml((message.target_name || "Q").slice(0, 1))}</div>`;

    let content = "";
    if (message.kind === "sticker") {
      content = `<div class="bubble image-bubble"><img src="${escapeHtml(message.media_url)}" alt="发送的表情包" loading="lazy"></div>`;
    } else if (message.kind === "voice") {
      content = `
        <div class="bubble voice-bubble">
          <button class="voice-play" type="button" data-audio="${escapeHtml(message.audio_url || message.media_url)}" data-message-id="${escapeHtml(message.id)}" aria-label="播放语音">
            <svg viewBox="0 0 20 20" fill="none"><path d="M7 5.5 14 10l-7 4.5v-9Z" fill="currentColor"/></svg>
          </button>
          <div class="waveform" data-wave>${waveformBars(index, 14)}</div>
          <span class="voice-duration">${Number(message.duration || 0).toFixed(1)}s</span>
        </div>
        <span class="voice-caption">${escapeHtml(message.content || "")}</span>`;
    } else {
      content = `<div class="bubble ${message.status === "failed" ? "failed" : ""}">${escapeHtml(message.content || "")}</div>`;
    }

    let meta = "";
    if (outgoing) {
      const statusText = {
        sending: "发送中",
        sent: message.mock ? "模拟发送" : "已发送",
        failed: "发送失败",
      }[message.status] || "";
      const mark = message.status === "failed"
        ? `<span class="fail-mark">!</span>`
        : message.status === "sent"
          ? `<span class="sent-check">✓</span>`
          : "";
      meta = `<div class="msg-meta"><span class="msg-time">${time}</span><span>${statusText}</span>${mark}</div>`;
      if (message.status === "failed" && message.error) {
        meta = `<div class="msg-meta"><span class="fail-mark">发送失败 · ${escapeHtml(message.error)}</span></div>`;
      }
    } else {
      meta = `<div class="msg-meta"><span class="msg-time">${time}</span><span>来自 QQ</span></div>`;
    }

    node.innerHTML = `${avatar}<div class="msg-stack">${content}${meta}</div>`;
    fragment.appendChild(node);
  });
  els.chatBody.appendChild(fragment);

  const last = messages[messages.length - 1];
  if (last?.direction === "out" || isNearBottom()) {
    scrollChatToBottom();
    $$("img", els.chatBody).forEach((image) => {
      if (!image.complete) image.addEventListener("load", scrollChatToBottom, { once: true });
    });
  }
}

function scrollChatToBottom() {
  els.chatBody.scrollTop = els.chatBody.scrollHeight;
  window.setTimeout(() => {
    els.chatBody.scrollTop = els.chatBody.scrollHeight;
  }, 120);
  window.setTimeout(() => {
    els.chatBody.scrollTop = els.chatBody.scrollHeight;
  }, 420);
}

function isNearBottom() {
  return els.chatBody.scrollHeight - els.chatBody.scrollTop - els.chatBody.clientHeight < 100;
}

function renderLogs() {
  const logs = state.logs || [];
  els.logCount.textContent = `${logs.length} 条`;
  if (!logs.length) {
    els.logList.innerHTML = `<div class="log-empty">调用官方接口后，这里会留下可检查的记录。</div>`;
    return;
  }
  els.logList.innerHTML = logs
    .slice(0, 9)
    .map((log) => {
      const method = log.method || "API";
      const status = log.status ? `${log.status}` : log.ok ? "OK" : "ERR";
      const duration = log.duration_ms ? `${log.duration_ms}ms` : "";
      const title = log.path || "";
      return `
        <div class="log-item" title="${escapeHtml(log.message || "")}">
          <span class="log-method">${escapeHtml(method.slice(0, 5))}</span>
          <div class="log-main">
            <div class="log-path"><span class="${log.ok ? "ok-dot" : "fail-dot"}"></span>${escapeHtml(title)}</div>
            <div class="log-msg">${escapeHtml(log.message || "")}</div>
          </div>
          <div class="log-right"><b>${escapeHtml(status)}</b>${escapeHtml(duration)}</div>
        </div>`;
    })
    .join("");
}

function renderRuntime(runtime) {
  if (!runtime) return;
  if (runtime.tts_silk) {
    els.formatBadge.innerHTML = `<span></span> SILK 语音条`;
    els.formatBadge.style.display = "";
  } else {
    els.formatBadge.innerHTML = `<span></span> WAV 回退`;
    els.formatBadge.style.color = "#a27638";
    els.formatBadge.style.background = "#fdf5e7";
    els.formatBadge.style.borderColor = "#f5e4c7";
  }
  if (runtime.voices && !els.voiceSelect.dataset.ready) {
    els.voiceSelect.innerHTML = Object.entries(runtime.voices)
      .map(([key, label]) => `<option value="${escapeHtml(key)}">${escapeHtml(label)}</option>`)
      .join("");
    els.voiceSelect.value = state.config.tts_voice || "Tingting";
    els.voiceSelect.dataset.ready = "1";
  }
}

/* ------------------------------------------------------------- interactions */
function setLoading(button, loading, label) {
  button.classList.toggle("loading", loading);
  if (label) {
    const span = $(".btn-label", button);
    if (span) span.textContent = label;
  }
}

async function sendText() {
  const text = els.textInput.value.trim();
  if (!text) {
    toast("先输入要发送的文字", "warn");
    els.textInput.focus();
    return;
  }
  if (!ensureTargetForLive()) return;
  setLoading(els.sendTextBtn, true, "发送中…");
  try {
    const data = await apiFetch("/api/send/text", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ text, ...currentTarget() }),
    });
    toast(data.mock ? "模拟发送完成，已加入预览" : "文本消息已发送到 QQ");
    targetDirty = false;
    await refreshState();
  } catch (error) {
    toast(error.message, "error", 4600);
    await refreshState();
  } finally {
    setLoading(els.sendTextBtn, false, "发送文字消息");
  }
}

function pickSticker(file) {
  if (!file) return;
  if (!file.type.startsWith("image/")) {
    toast("请选择图片文件", "error");
    return;
  }
  if (file.size > MAX_UPLOAD) {
    toast("图片超过 5MB，换一张小一点的吧", "error");
    return;
  }
  selectedFile = file;
  if (selectedPreviewUrl) URL.revokeObjectURL(selectedPreviewUrl);
  selectedPreviewUrl = URL.createObjectURL(file);
  els.stickerPreview.src = selectedPreviewUrl;
  els.stickerName.textContent = file.name;
  els.stickerSize.textContent = `${fmtSize(file.size)} · 将作为富媒体素材上传`;
  els.dropEmpty.hidden = true;
  els.dropPreview.hidden = false;
  els.sendStickerBtn.disabled = false;
}

function clearSticker() {
  selectedFile = null;
  if (selectedPreviewUrl) URL.revokeObjectURL(selectedPreviewUrl);
  selectedPreviewUrl = null;
  els.stickerInput.value = "";
  els.dropEmpty.hidden = false;
  els.dropPreview.hidden = true;
  els.sendStickerBtn.disabled = true;
}

async function sendSticker() {
  if (!selectedFile) return;
  if (!ensureTargetForLive()) return;
  setLoading(els.sendStickerBtn, true, "上传中…");
  const form = new FormData();
  form.append("file", selectedFile, selectedFile.name);
  form.append("target_type", targetType);
  form.append("target_id", els.targetId.value.trim());
  try {
    const data = await apiFetch("/api/send/sticker", { method: "POST", body: form });
    toast(data.mock ? "表情包已加入模拟预览" : "表情包已发送到 QQ");
    targetDirty = false;
    await refreshState();
    clearSticker();
  } catch (error) {
    toast(error.message, "error", 4600);
    await refreshState();
  } finally {
    setLoading(els.sendStickerBtn, false, "上传并发送表情包");
    if (selectedFile) els.sendStickerBtn.disabled = false;
  }
}

async function sendVoice() {
  const text = els.voiceText.value.trim();
  if (!text) {
    toast("先输入要转成语音的文字", "warn");
    els.voiceText.focus();
    return;
  }
  if (!ensureTargetForLive()) return;
  setLoading(els.sendVoiceBtn, true, "合成语音中…");
  try {
    const data = await apiFetch("/api/send/voice", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ text, voice: els.voiceSelect.value, ...currentTarget() }),
    });
    const audio = data.audio || {};
    els.audioPreview.hidden = false;
    els.previewAudio.src = audio.url || data.message.audio_url;
    els.audioDuration.textContent = `${Number(audio.duration || 0).toFixed(1)}s`;
    toast(
      data.mock
        ? `已生成 ${String(audio.format || "silk").toUpperCase()} 语音并加入预览`
        : `语音已发送 · ${String(audio.format || "silk").toUpperCase()} · ${Number(audio.duration || 0).toFixed(1)}s`,
      "success",
      4200,
    );
    targetDirty = false;
    await refreshState();
  } catch (error) {
    toast(error.message, "error", 5000);
    await refreshState();
  } finally {
    setLoading(els.sendVoiceBtn, false, "生成并发送语音");
  }
}

async function saveTarget({ silent = false } = {}) {
  const id = els.targetId.value.trim();
  if (state?.config.live && !id) {
    toast("真实发送需要 OpenID；也可以先关闭「真实发送」", "warn", 4200);
    return false;
  }
  try {
    await apiFetch("/api/config", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        target_type: targetType,
        target_id: id,
        live: els.liveToggle.checked,
      }),
    });
    targetDirty = false;
    await refreshState();
    if (!silent) toast("发送目标已保存");
    return true;
  } catch (error) {
    toast(error.message, "error");
    return false;
  }
}

async function toggleLive(checked) {
  if (checked && !els.targetId.value.trim()) {
    toast("还没有 OpenID，可以先去 QQ 给机器人发条消息", "warn", 4200);
  }
  try {
    await apiFetch("/api/config", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ live: checked }),
    });
    await refreshState();
    toast(checked ? "已切换到真实发送" : "已切换到本地模拟");
  } catch (error) {
    toast(error.message, "error");
    els.liveToggle.checked = !checked;
  }
}

async function restartGateway() {
  els.restartGateway.disabled = true;
  try {
    await apiFetch("/api/gateway/restart", { method: "POST" });
    toast("正在重新连接 OpenID 监听");
    window.setTimeout(refreshState, 900);
  } catch (error) {
    toast(error.message, "error");
  } finally {
    els.restartGateway.disabled = false;
  }
}

/* ---------------------------------------------------------------- settings */
function openSettings() {
  if (!state) return;
  els.appIdInput.value = state.config.app_id || "";
  els.appSecretInput.value = "";
  els.appSecretInput.placeholder = state.config.secret_configured
    ? `已保存（尾号 ${state.config.secret_tail}），留空不修改`
    : "填写开放平台的 AppSecret";
  els.autoCaptureToggle.checked = !!state.config.auto_capture;
  els.settingsBotName.textContent = state.bot?.username || "小满";
  els.settingsBotState.textContent = state.configured
    ? `AppID ${state.config.app_id} · 凭据已配置`
    : "尚未配置机器人凭据";
  els.settingsShareLink.href = state.bot?.share_url || "#";
  els.settingsShareLink.style.display = state.bot?.share_url ? "" : "none";
  els.settingsModal.hidden = false;
  document.body.style.overflow = "hidden";
  els.appIdInput.focus();
}

function closeSettings() {
  els.settingsModal.hidden = true;
  document.body.style.overflow = "";
}

async function persistSettings({ test = false } = {}) {
  const appId = els.appIdInput.value.trim();
  const secret = els.appSecretInput.value.trim();
  const autoCapture = els.autoCaptureToggle.checked;
  if (!appId) {
    toast("AppID 不能为空", "warn");
    return;
  }
  els.saveSettings.disabled = true;
  els.testBtn.disabled = true;
  try {
    await apiFetch("/api/config", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ app_id: appId, app_secret: secret, auto_capture: autoCapture }),
    });
    if (test) {
      const result = await apiFetch("/api/test", { method: "POST" });
      toast(`连接成功：${result.bot?.username || "机器人"}`, "success", 4000);
    } else {
      toast("连接设置已保存");
    }
    closeSettings();
    await refreshState();
  } catch (error) {
    toast(error.message, "error", 4800);
    await refreshState();
  } finally {
    els.saveSettings.disabled = false;
    els.testBtn.disabled = false;
  }
}

async function testConnection() {
  await persistSettings({ test: true });
}

/* ------------------------------------------------------------------- audio */
function playVoice(url, button) {
  if (!url) return;
  if (currentAudio) {
    currentAudio.pause();
    if (currentVoiceButton) currentVoiceButton.classList.remove("playing");
  }
  if (currentAudio && currentAudio.dataset.url === url) {
    currentAudio = null;
    currentVoiceButton = null;
    return;
  }
  currentAudio = new Audio(url);
  currentAudio.dataset.url = url;
  currentVoiceButton = button;
  button.classList.add("playing");
  currentAudio.addEventListener("ended", () => {
    button.classList.remove("playing");
    currentAudio = null;
    currentVoiceButton = null;
  });
  currentAudio.addEventListener("error", () => {
    toast("语音试听加载失败", "error");
    button.classList.remove("playing");
  });
  currentAudio.play().catch(() => toast("浏览器拦截了播放，请再点一次", "warn"));
}

/* ------------------------------------------------------------------ events */
els.textInput.addEventListener("input", () => {
  els.textCount.textContent = els.textInput.value.length;
});
els.voiceText.addEventListener("input", () => {
  els.voiceCount.textContent = els.voiceText.value.length;
});
$$(".quick-chip").forEach((chip) => {
  chip.addEventListener("click", () => {
    els.textInput.value = chip.dataset.text;
    els.textInput.dispatchEvent(new Event("input"));
    els.textInput.focus();
  });
});
els.sendTextBtn.addEventListener("click", sendText);
els.sendStickerBtn.addEventListener("click", sendSticker);
els.sendVoiceBtn.addEventListener("click", sendVoice);
els.saveTarget.addEventListener("click", () => saveTarget());
els.restartGateway.addEventListener("click", restartGateway);
els.liveToggle.addEventListener("change", () => toggleLive(els.liveToggle.checked));
els.targetId.addEventListener("input", () => {
  targetDirty = true;
});
els.targetId.addEventListener("paste", () => {
  window.setTimeout(() => { targetDirty = true; }, 0);
});
els.targetType.addEventListener("click", (event) => {
  const button = event.target.closest(".seg-btn");
  if (!button) return;
  setTargetType(button.dataset.type);
});
els.contactSelect.addEventListener("change", () => {
  const contact = (state.contacts || []).find((item) => item.key === els.contactSelect.value);
  if (!contact) return;
  setTargetType(contact.type);
  els.targetId.value = contact.id;
  targetDirty = true;
  saveTarget({ silent: true });
});
els.pasteBtn.addEventListener("click", async () => {
  try {
    const text = await navigator.clipboard.readText();
    if (text) {
      els.targetId.value = text.trim();
      targetDirty = true;
    }
  } catch {
    toast("无法读取剪贴板，请手动粘贴", "warn");
    els.targetId.focus();
  }
});

els.dropzone.addEventListener("click", (event) => {
  if (event.target.closest("#removeFile")) return;
  els.stickerInput.click();
});
els.dropzone.addEventListener("keydown", (event) => {
  if (event.key === "Enter" || event.key === " ") {
    event.preventDefault();
    els.stickerInput.click();
  }
});
els.stickerInput.addEventListener("change", () => pickSticker(els.stickerInput.files?.[0]));
els.removeFile.addEventListener("click", (event) => {
  event.stopPropagation();
  clearSticker();
});
["dragenter", "dragover"].forEach((name) => {
  els.dropzone.addEventListener(name, (event) => {
    event.preventDefault();
    els.dropzone.classList.add("dragging");
  });
});
["dragleave", "drop"].forEach((name) => {
  els.dropzone.addEventListener(name, (event) => {
    event.preventDefault();
    els.dropzone.classList.remove("dragging");
  });
});
els.dropzone.addEventListener("drop", (event) => {
  const file = event.dataTransfer?.files?.[0];
  pickSticker(file);
});

els.previewPlayBtn.addEventListener("click", () => {
  if (!els.previewAudio.src) return;
  if (els.previewAudio.paused) {
    els.previewAudio.play().catch(() => toast("无法试听这段语音", "error"));
  } else {
    els.previewAudio.pause();
  }
});
els.previewAudio.addEventListener("play", () => els.previewPlayBtn.classList.add("playing"));
els.previewAudio.addEventListener("pause", () => els.previewPlayBtn.classList.remove("playing"));
els.previewAudio.addEventListener("ended", () => els.previewPlayBtn.classList.remove("playing"));
els.voiceSelect.addEventListener("change", async () => {
  try {
    await apiFetch("/api/config", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ tts_voice: els.voiceSelect.value }),
    });
    if (state) state.config.tts_voice = els.voiceSelect.value;
  } catch (error) {
    toast(error.message, "error");
  }
});

els.chatBody.addEventListener("click", (event) => {
  const button = event.target.closest(".voice-play");
  if (button) playVoice(button.dataset.audio, button);
});

els.settingsBtn.addEventListener("click", openSettings);
els.closeSettings.addEventListener("click", closeSettings);
els.settingsModal.addEventListener("click", (event) => {
  if (event.target === els.settingsModal) closeSettings();
});
els.saveSettings.addEventListener("click", () => persistSettings());
els.testBtn.addEventListener("click", testConnection);
els.appSecretInput.addEventListener("input", () => { settingsDirty = true; });
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape" && !els.settingsModal.hidden) closeSettings();
  if ((event.metaKey || event.ctrlKey) && event.key === "Enter") {
    if (document.activeElement === els.voiceText) {
      sendVoice();
    } else if (!els.settingsModal.hidden) {
      persistSettings();
    } else {
      sendText();
    }
  }
});

document.addEventListener("visibilitychange", () => {
  if (!document.hidden) refreshState();
});

/* -------------------------------------------------------------------- boot */
els.textCount.textContent = els.textInput.value.length;
els.voiceCount.textContent = els.voiceText.value.length;
refreshState({ quiet: false });
window.setInterval(() => {
  if (!document.hidden) refreshState();
}, 3000);
