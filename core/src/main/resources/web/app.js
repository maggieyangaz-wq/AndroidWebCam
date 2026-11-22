'use strict';
// Android WebCam viewer. No external resources, no inline code (strict CSP), no storage.
(function () {
  const zh = (navigator.language || '').toLowerCase().startsWith('zh');
  const STRINGS = {
    en: {
      loginHint: 'Enter the access code shown on the phone.',
      connect: 'Connect',
      secureNote: 'Encrypted with TLS 1.3 inside your local network. Nothing is uploaded anywhere.',
      live: 'LIVE',
      snapshot: 'Snapshot (saved only on this device)',
      fullscreen: 'Fullscreen',
      logout: 'Disconnect',
      invalid: 'Wrong code. {n} attempt(s) left before a temporary lock.',
      locked: 'Too many attempts. Try again in {n} s.',
      busy: 'The maximum number of viewers is connected. Retrying…',
      connecting: 'Connecting…',
      waiting: 'Waiting for video…',
      reconnecting: 'Connection lost. Reconnecting…',
      sessionEnded: 'Session ended. Enter the current access code again.',
      pairingFailed: 'This QR link was already used or has expired. Enter the access code instead.',
      compat: 'This browser cannot decode H.264 here, switched to compatibility mode (MJPEG).',
      error: 'Error {n}',
      network: 'Cannot reach the phone. Check that you are on the same network.',
      aiOpen: 'Let an AI see the picture',
      close: 'Close',
      copy: 'Copy',
      copied: 'Copied',
      copyManually: 'Select the text and copy it manually.',
      aiTitle: 'Let an AI see the picture',
      aiIntro: 'AI agents on this computer that can run commands (Codex, Claude Code, Cursor, Antigravity, Kimi and others) can take a current picture from the phone whenever you ask them. No installation needed: they use curl.',
      aiStepKey: 'Create an access key for this computer. It works only from this computer, only for single pictures, and keeps working after streaming restarts until you revoke it on the phone. Creating a new key replaces the old one.',
      aiCreateKey: 'Create key',
      aiKeyCreated: 'Key created.',
      aiStepAgent: 'Copy these instructions to your agent: paste them into the chat, or into its standing instructions (AGENTS.md, CLAUDE.md, Cursor rules, …). Then ask, for example: "What does the camera see right now?"',
      aiNoKeyYet: '(Create a key first.)',
      aiNoteVision: 'The agent\'s model must be able to look at images. The built-in curl of Windows 10 does not support TLS 1.3: use Windows 11, or the Node.js command below.',
      aiMcpTitle: 'AI apps that cannot run commands (MCP, e.g. Claude Desktop), or Windows 10',
      aiStep1: 'Download the connection tool (needs Node.js 18 or newer, nothing else):',
      aiStepDesktop: 'Claude Desktop: Settings → Developer → Edit Config. Add this to claude_desktop_config.json (if it already has "mcpServers", add only the "android-webcam" entry) and correct the path to awc-ai.mjs:',
      aiStepNode: 'On Windows 10, agents can run this instead of curl (it prints the path of the saved picture; correct the path to awc-ai.mjs):',
      aiPrivacy: 'The phone itself never uploads anything. Only this computer can use the key; it never opens the live stream; at most one picture per second; the phone lists every picture taken and can revoke AI access at any time ("disconnect all" also revokes it). The pictures are sent to the AI service your agent uses.',
      aiPath: 'full path of awc-ai.mjs',
      agentTitle: 'Phone camera (Android WebCam)',
      agentWhen: 'When I ask about the camera or what it sees, take a new picture with this command and then look at the saved image file:',
      agentWindowsShell: '(PowerShell syntax. In cmd use %TEMP%\\awc-camera.jpg, in Git Bash /tmp/awc-camera.jpg.)',
      agentNotes: [
        'Each run takes a new picture from the phone\'s rear camera (not mirrored); at most one picture per second.',
        '-k only skips the certificate authority check: --pinnedpubkey still requires exactly this phone\'s key.',
        'Keep the picture in the temporary folder: never copy it into a project or commit it.',
        'If it fails: cannot connect = the phone is not streaming, not in the same network, or its address changed; HTTP 401 = the key was revoked on the phone or this computer\'s address changed (ask me to create a new key in the viewer page); HTTP 503 = no picture right now, retry in a few seconds.',
      ],
    },
    zh: {
      loginHint: '请输入手机上显示的访问码。',
      connect: '连接',
      secureNote: '仅在局域网内通过 TLS 1.3 加密传输，画面不会上传到任何地方。',
      live: '直播中',
      snapshot: '截图（只保存在本设备）',
      fullscreen: '全屏',
      logout: '断开连接',
      invalid: '访问码错误，再错 {n} 次将被暂时锁定。',
      locked: '尝试次数过多，请 {n} 秒后再试。',
      busy: '观看人数已达上限，正在重试…',
      connecting: '正在连接…',
      waiting: '正在等待画面…',
      reconnecting: '连接中断，正在重连…',
      sessionEnded: '会话已结束，请重新输入当前访问码。',
      pairingFailed: '此二维码链接已使用或已过期，请改为输入访问码。',
      compat: '此浏览器无法在这里解码 H.264，已切换为兼容模式（MJPEG）。',
      error: '错误 {n}',
      network: '无法连接到手机，请确认与手机处于同一网络。',
      aiOpen: '让 AI 看到画面',
      close: '关闭',
      copy: '复制',
      copied: '已复制',
      copyManually: '请选中文字后手动复制。',
      aiTitle: '让 AI 看到画面',
      aiIntro: '这台电脑上能运行命令的 AI agent（Codex、Claude Code、Cursor、Antigravity、Kimi 等）可以在你提问时，从手机取一张当前画面来看。不需要安装任何东西，它们用系统自带的 curl。',
      aiStepKey: '为这台电脑生成访问密钥。密钥只能在这台电脑上使用、只能取单张画面，手机重新推流后仍然有效，直到你在手机上撤销。重新生成会替换旧密钥。',
      aiCreateKey: '生成密钥',
      aiKeyCreated: '密钥已生成。',
      aiStepAgent: '把下面这段说明交给你的 agent：直接粘贴到对话里，或者放进它的长期说明（AGENTS.md、CLAUDE.md、Cursor Rules 等）。之后直接问，例如“摄像头现在拍到了什么？”',
      aiNoKeyYet: '（请先生成密钥）',
      aiNoteVision: '前提是 agent 用的模型能看图片。Windows 10 自带的 curl 不支持 TLS 1.3：请用 Windows 11，或者用下面的 Node.js 命令。',
      aiMcpTitle: '不能运行命令的 AI 应用（MCP，例如 Claude 桌面版），或 Windows 10',
      aiStep1: '下载连接工具（需要 Node.js 18 或更新版本，不需要其他软件）：',
      aiStepDesktop: 'Claude 桌面版：设置 → 开发者 → 编辑配置，把下面内容加入 claude_desktop_config.json（文件里已有 "mcpServers" 时，只加入 "android-webcam" 这一段），并改成 awc-ai.mjs 实际保存的路径：',
      aiStepNode: 'Windows 10 上的 agent 可以用这条命令代替 curl（会输出保存的图片路径；同样改成 awc-ai.mjs 的实际路径）：',
      aiPrivacy: '手机本身不会上传任何东西。密钥只有这台电脑能用，永远打不开实时画面，每秒最多取 1 张；手机上会记录每一次取画面，并可随时撤销 AI 访问（“断开所有观看者”也会撤销）。画面会发送给你的 agent 所使用的 AI 服务。',
      aiPath: 'awc-ai.mjs 的完整路径',
      agentTitle: '手机摄像头（Android WebCam）',
      agentWhen: '当我问到摄像头或它拍到了什么时，用下面的命令拍一张新画面，然后查看保存的图片文件：',
      agentWindowsShell: '（这是 PowerShell 写法；cmd 里改成 %TEMP%\\awc-camera.jpg，Git Bash 里改成 /tmp/awc-camera.jpg。）',
      agentNotes: [
        '每次运行都会拍一张新画面（手机后置摄像头，没有镜像）；每秒最多 1 张。',
        '-k 只是跳过证书机构校验，--pinnedpubkey 仍然严格核对这台手机的公钥。',
        '图片只放在临时目录，不要复制进项目或提交到仓库。',
        '失败时：连不上 = 手机没在推流、不在同一网络或地址变了；HTTP 401 = 密钥已在手机上撤销或这台电脑的地址变了（请让我在观看页面重新生成）；HTTP 503 = 手机暂时没有画面，几秒后重试。',
      ],
    },
  };
  const L = zh ? STRINGS.zh : STRINGS.en;
  const t = (key, n) => (L[key] || key).replace('{n}', n === undefined ? '' : String(n));
  const $ = (id) => document.getElementById(id);

  let ws = null;
  let decoder = null;
  let decoderConfig = null;
  let configGeneration = 0;
  let waitingKey = true;
  let retry = 0;
  let stopped = true;
  let pingTimer = null;
  let mode = null;
  let decodeErrors = [];
  let wsOpenFailures = 0;
  let frames = 0;
  let bytes = 0;
  let wakeLock = null;
  let ctx = null;

  function applyI18n() {
    document.documentElement.lang = zh ? 'zh-CN' : 'en';
    document.querySelectorAll('[data-i18n]').forEach((el) => { el.textContent = t(el.dataset.i18n); });
    document.querySelectorAll('[data-i18n-title]').forEach((el) => {
      el.title = t(el.dataset.i18nTitle);
      el.setAttribute('aria-label', el.title);
    });
  }

  function toast(text) {
    const el = document.createElement('div');
    el.className = 'toast';
    el.textContent = text;
    document.body.appendChild(el);
    setTimeout(() => el.remove(), 5000);
  }

  function post(path, body) {
    return fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      credentials: 'same-origin',
      cache: 'no-store',
      redirect: 'error',
    });
  }

  function showLogin(message) {
    stopStreaming();
    $('ai-panel').hidden = true;
    $('viewer').hidden = true;
    $('login').hidden = false;
    $('login-error').textContent = message || '';
    $('code').focus();
  }

  async function handleLoginResponse(r) {
    if (r.status === 204) {
      $('code').value = '';
      startViewer();
      return;
    }
    let info = {};
    try { info = await r.json(); } catch (e) { /* not JSON */ }
    if (r.status === 429) {
      $('login-error').textContent = t('locked', info.retryAfter || r.headers.get('Retry-After') || '?');
    } else if (r.status === 401) {
      $('login-error').textContent = t('invalid', info.attemptsLeft === undefined ? '?' : info.attemptsLeft);
    } else {
      $('login-error').textContent = t('error', r.status);
    }
  }

  async function onLoginSubmit(event) {
    event.preventDefault();
    const code = $('code').value.trim();
    if (!code) return;
    const button = $('btn-connect');
    button.disabled = true;
    try {
      await handleLoginResponse(await post('/api/login', { code }));
    } catch (e) {
      $('login-error').textContent = t('network');
    } finally {
      button.disabled = false;
    }
  }

  async function init() {
    applyI18n();
    $('login-form').addEventListener('submit', onLoginSubmit);
    $('btn-snapshot').addEventListener('click', snapshot);
    $('btn-fullscreen').addEventListener('click', toggleFullscreen);
    $('btn-logout').addEventListener('click', logout);
    $('btn-ai').addEventListener('click', openAiPanel);
    $('ai-create-key').addEventListener('click', createAiKey);
    $('ai-download').addEventListener('click', downloadBridge);
    $('ai-close').addEventListener('click', () => { $('ai-panel').hidden = true; });
    document.querySelectorAll('[data-copy]').forEach((button) => button.addEventListener('click', () => copyText(button)));
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape') $('ai-panel').hidden = true; });
    $('stage').addEventListener('dblclick', toggleFullscreen);
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible' && !stopped) requestWakeLock();
    });
    window.addEventListener('pagehide', () => stopStreaming());
    setInterval(updateStats, 1000);

    // A one-time pairing token may arrive in the URL fragment (from the QR code).
    // Fragments are never sent to the server; remove it from the address bar right away.
    const params = new URLSearchParams(location.hash.slice(1));
    const pairToken = params.get('pair');
    if (location.hash) history.replaceState(null, '', location.pathname);
    try {
      if (pairToken) {
        const r = await post('/api/pair', { token: pairToken });
        if (r.status === 204) { startViewer(); return; }
        if (r.status !== 401) { await handleLoginResponse(r); showLogin($('login-error').textContent); return; }
      }
      const status = await fetch('/api/status', { cache: 'no-store', credentials: 'same-origin' });
      if (status.ok) startViewer();
      else showLogin(pairToken ? t('pairingFailed') : '');
    } catch (e) {
      showLogin(t('network'));
    }
  }

  function setStatus(text) {
    $('status-text').textContent = text || '';
    $('overlay').hidden = !text;
  }

  function startViewer() {
    $('login').hidden = true;
    $('viewer').hidden = false;
    stopped = false;
    retry = 0;
    setStatus(t('connecting'));
    requestWakeLock();
    if (typeof window.VideoDecoder === 'function' && typeof window.EncodedVideoChunk === 'function') {
      startWebCodecs();
    } else {
      startMjpeg(true);
    }
  }

  function stopStreaming() {
    stopped = true;
    if (pingTimer) { clearInterval(pingTimer); pingTimer = null; }
    if (ws) { try { ws.close(1000); } catch (e) { /* ignore */ } ws = null; }
    closeDecoder();
    const img = $('mjpeg');
    if (img && img.getAttribute('src')) img.removeAttribute('src');
    if (wakeLock) { wakeLock.release().catch(() => {}); wakeLock = null; }
  }

  // ------------------------------------------------------------- H.264 / WebCodecs

  function startWebCodecs() {
    mode = 'h264';
    $('mjpeg').hidden = true;
    $('canvas').hidden = false;
    ctx = $('canvas').getContext('2d', { alpha: false, desynchronized: true });
    connectWebSocket();
  }

  function connectWebSocket() {
    if (stopped || mode !== 'h264') return;
    let opened = false;
    let socket;
    try {
      socket = new WebSocket('wss://' + location.host + '/ws');
    } catch (e) {
      fallbackToMjpeg();
      return;
    }
    socket.binaryType = 'arraybuffer';
    ws = socket;
    socket.onopen = () => {
      opened = true;
      retry = 0;
      wsOpenFailures = 0;
      setStatus(t('waiting'));
      pingTimer = setInterval(() => send({ type: 'ping' }), 5000);
    };
    socket.onmessage = (event) => {
      if (typeof event.data === 'string') onText(event.data);
      else onBinary(event.data);
    };
    socket.onclose = async (event) => {
      if (pingTimer) { clearInterval(pingTimer); pingTimer = null; }
      if (ws === socket) ws = null;
      closeDecoder();
      if (stopped || mode !== 'h264') return;
      if (event.code === 4401) { showLogin(t('sessionEnded')); return; }
      // A rejected handshake (401/503) only surfaces as code 1006, so ask the API why.
      const reason = await diagnose();
      if (stopped || mode !== 'h264') return;
      if (reason === 'auth') { showLogin(t('sessionEnded')); return; }
      // The server is reachable but WebSockets are not (e.g. some browsers refuse wss:// to a
      // self-signed certificate even after the page was accepted): use the MJPEG stream instead.
      if (!opened && reason === 'ok' && ++wsOpenFailures >= 2) { fallbackToMjpeg(); return; }
      scheduleRetry(reason, connectWebSocket);
    };
  }

  /** 'auth' | 'busy' | 'ok' | 'error' | 'network' */
  async function diagnose() {
    try {
      const r = await fetch('/api/status', { cache: 'no-store', credentials: 'same-origin' });
      if (r.status === 401) return 'auth';
      if (!r.ok) return 'error';
      const info = await r.json();
      return info.viewers >= info.maxViewers ? 'busy' : 'ok';
    } catch (e) {
      return 'network';
    }
  }

  function scheduleRetry(reason, reconnect) {
    setStatus(reason === 'busy' ? t('busy') : t('reconnecting'));
    const delay = Math.min(10000, 500 * Math.pow(2, retry++));
    setTimeout(reconnect, delay);
  }

  function send(message) {
    if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(message));
  }

  function onText(text) {
    let message;
    try { message = JSON.parse(text); } catch (e) { return; }
    if (message.type === 'config') configureDecoder(message);
  }

  function base64ToBytes(b64) {
    const bin = atob(b64);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }

  async function configureDecoder(message) {
    const generation = ++configGeneration;
    const config = {
      codec: message.codec,
      codedWidth: message.width,
      codedHeight: message.height,
      description: base64ToBytes(message.description),
      optimizeForLatency: true,
    };
    let supported = false;
    try {
      supported = (await VideoDecoder.isConfigSupported(config)).supported === true;
    } catch (e) {
      supported = false;
    }
    if (generation !== configGeneration || stopped) return;
    if (!supported) { fallbackToMjpeg(); return; }
    closeDecoder();
    decoderConfig = config;
    createDecoder();
    send({ type: 'keyframe' });
  }

  function createDecoder() {
    decoder = new VideoDecoder({ output: onDecodedFrame, error: onDecoderError });
    decoder.configure(decoderConfig);
    waitingKey = true;
  }

  function closeDecoder() {
    if (decoder) {
      try { if (decoder.state !== 'closed') decoder.close(); } catch (e) { /* ignore */ }
      decoder = null;
    }
  }

  function onBinary(buffer) {
    bytes += buffer.byteLength;
    if (!decoder || decoder.state !== 'configured' || buffer.byteLength < 11) return;
    const view = new DataView(buffer);
    if (view.getUint8(0) !== 1) return;
    const key = (view.getUint8(1) & 1) === 1;
    const timestamp = view.getUint32(2) * 4294967296 + view.getUint32(6);
    if (waitingKey && !key) return;
    if (!key && decoder.decodeQueueSize > 8) {
      // Decoder is falling behind: skip to the next keyframe to keep latency low.
      waitingKey = true;
      send({ type: 'keyframe' });
      return;
    }
    waitingKey = false;
    try {
      decoder.decode(new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp, data: new Uint8Array(buffer, 10) }));
    } catch (e) {
      onDecoderError(e);
    }
  }

  function onDecodedFrame(frame) {
    const canvas = $('canvas');
    if (canvas.width !== frame.displayWidth || canvas.height !== frame.displayHeight) {
      canvas.width = frame.displayWidth;
      canvas.height = frame.displayHeight;
    }
    ctx.drawImage(frame, 0, 0, canvas.width, canvas.height);
    frame.close();
    frames++;
    if (!$('overlay').hidden) setStatus('');
  }

  function onDecoderError() {
    const now = Date.now();
    decodeErrors = decodeErrors.filter((ts) => now - ts < 10000);
    decodeErrors.push(now);
    closeDecoder();
    if (decodeErrors.length >= 4 || !decoderConfig) { fallbackToMjpeg(); return; }
    try {
      createDecoder();
      send({ type: 'keyframe' });
    } catch (e) {
      fallbackToMjpeg();
    }
  }

  // ------------------------------------------------------------- MJPEG fallback

  function fallbackToMjpeg() {
    if (mode === 'mjpeg') return;
    mode = 'mjpeg';
    if (pingTimer) { clearInterval(pingTimer); pingTimer = null; }
    if (ws) { try { ws.close(1000); } catch (e) { /* ignore */ } ws = null; }
    closeDecoder();
    startMjpeg(true);
  }

  function startMjpeg(announce) {
    mode = 'mjpeg';
    $('canvas').hidden = true;
    const img = $('mjpeg');
    img.hidden = false;
    img.onload = () => setStatus('');
    img.onerror = async () => {
      if (stopped || mode !== 'mjpeg') return;
      img.removeAttribute('src');
      const reason = await diagnose();
      if (stopped || mode !== 'mjpeg') return;
      if (reason === 'auth') { showLogin(t('sessionEnded')); return; }
      scheduleRetry(reason, () => { if (!stopped && mode === 'mjpeg') img.src = '/mjpeg?t=' + Date.now(); });
    };
    img.src = '/mjpeg?t=' + Date.now();
    if (announce) toast(t('compat'));
  }

  // ------------------------------------------------------------- toolbar

  function updateStats() {
    if (stopped) { $('stats').textContent = ''; return; }
    if (mode === 'h264') {
      const canvas = $('canvas');
      $('stats').textContent = canvas.width + '×' + canvas.height + ' · ' + frames + ' fps · ' +
        (bytes * 8 / 1e6).toFixed(1) + ' Mbps · H.264';
    } else if (mode === 'mjpeg') {
      const img = $('mjpeg');
      $('stats').textContent = (img.naturalWidth || 0) + '×' + (img.naturalHeight || 0) + ' · MJPEG';
    }
    frames = 0;
    bytes = 0;
  }

  function snapshot() {
    const source = mode === 'mjpeg' ? $('mjpeg') : $('canvas');
    const width = source.naturalWidth || source.width;
    const height = source.naturalHeight || source.height;
    if (!width || !height) return;
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    canvas.getContext('2d').drawImage(source, 0, 0, width, height);
    canvas.toBlob((blob) => {
      if (blob) saveBlob(blob, 'androidwebcam-' + new Date().toISOString().replace(/[:.]/g, '-') + '.png');
    }, 'image/png');
  }

  // ------------------------------------------------------------- AI agent access

  let aiInfo = null;

  /** Example path where a browser saves the download on this computer's system. */
  function examplePath() {
    const ua = navigator.userAgent;
    const name = zh ? '用户名' : 'you';
    if (/Windows/.test(ua)) return 'C:/Users/' + name + '/Downloads/awc-ai.mjs';
    if (/Macintosh|Mac OS X/.test(ua)) return '/Users/' + name + '/Downloads/awc-ai.mjs';
    return '/home/' + name + '/Downloads/awc-ai.mjs';
  }

  async function openAiPanel() {
    try {
      const r = await fetch('/api/status', { cache: 'no-store', credentials: 'same-origin' });
      if (r.status === 401) { showLogin(t('sessionEnded')); return; }
      aiInfo = await r.json();
    } catch (e) {
      toast(t('network'));
      return;
    }
    if (!$('ai-agent-text').textContent) showAiSnippets(null);
    $('ai-panel').hidden = false;
  }

  async function createAiKey() {
    const button = $('ai-create-key');
    button.disabled = true;
    try {
      const r = await post('/api/ai-key', {});
      if (r.status === 401) { showLogin(t('sessionEnded')); return; }
      if (!r.ok) { toast(t('error', r.status)); return; }
      showAiSnippets((await r.json()).key);
      $('ai-key-status').textContent = t('aiKeyCreated');
    } catch (e) {
      toast(t('network'));
    } finally {
      button.disabled = false;
    }
  }

  /** Fills the instructions for agents and the MCP settings (placeholders until a key exists). */
  function showAiSnippets(key) {
    const address = location.host;
    const pin = aiInfo.pin;
    const windows = /Windows/.test(navigator.userAgent);
    document.querySelectorAll('#ai-panel [data-copy]').forEach((b) => { b.disabled = !key; });
    if (!key) {
      $('ai-agent-text').textContent = t('aiNoKeyYet');
      $('ai-config-desktop').textContent = t('aiNoKeyYet');
      $('ai-node-command').textContent = t('aiNoKeyYet');
      return;
    }
    const output = windows ? '"$env:TEMP\\awc-camera.jpg"' : '/tmp/awc-camera.jpg';
    const command = (windows ? 'curl.exe' : 'curl') + ' -fsS --tlsv1.3 -k --pinnedpubkey "sha256//' + pin.slice('sha256/'.length) +
      '" -H "Authorization: Bearer ' + key + '" -o ' + output + ' https://' + address + '/api/snapshot';
    const lines = ['# ' + t('agentTitle'), '', t('agentWhen'), '', command];
    if (windows) lines.push(t('agentWindowsShell'));
    lines.push('');
    L.agentNotes.forEach((note) => lines.push('- ' + note));
    $('ai-agent-text').textContent = lines.join('\n');
    const path = examplePath();
    $('ai-config-desktop').textContent = JSON.stringify({
      mcpServers: {
        'android-webcam': { command: 'node', args: [path], env: { AWC_ADDRESS: address, AWC_PIN: pin, AWC_KEY: key } },
      },
    }, null, 2);
    $('ai-node-command').textContent = 'node "' + path + '" snap --address ' + address + ' --pin "' + pin + '" --key ' + key;
  }

  /**
   * Fetches the tool over the page's own (already accepted) connection and saves it from memory:
   * browsers may fail a direct download from a server with a self-signed certificate
   * ("network error") although the page itself works.
   */
  async function downloadBridge(event) {
    event.preventDefault();
    try {
      const r = await fetch('/awc-ai.mjs', { cache: 'no-store', credentials: 'same-origin' });
      if (!r.ok) { toast(t('error', r.status)); return; }
      saveBlob(new Blob([await r.arrayBuffer()], { type: 'application/octet-stream' }), 'awc-ai.mjs');
    } catch (e) {
      toast(t('network'));
    }
  }

  function saveBlob(blob, name) {
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = name;
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(link.href), 10000);
  }

  async function copyText(button) {
    const source = $(button.dataset.copy);
    try {
      await navigator.clipboard.writeText(source.textContent);
      button.textContent = t('copied');
      setTimeout(() => { button.textContent = t('copy'); }, 2000);
    } catch (e) {
      const range = document.createRange();
      range.selectNodeContents(source);
      const selection = window.getSelection();
      selection.removeAllRanges();
      selection.addRange(range);
      toast(t('copyManually'));
    }
  }

  function toggleFullscreen() {
    const stage = $('stage');
    const current = document.fullscreenElement || document.webkitFullscreenElement;
    if (current) {
      const exit = document.exitFullscreen || document.webkitExitFullscreen;
      if (exit) exit.call(document);
      return;
    }
    const request = stage.requestFullscreen || stage.webkitRequestFullscreen;
    try {
      const result = request ? request.call(stage) : null;
      if (!request) document.body.classList.toggle('immersive');
      else if (result && result.catch) result.catch(() => document.body.classList.toggle('immersive'));
    } catch (e) {
      document.body.classList.toggle('immersive');
    }
  }

  async function logout() {
    stopStreaming();
    try { await fetch('/api/logout', { method: 'POST', credentials: 'same-origin', cache: 'no-store' }); } catch (e) { /* ignore */ }
    showLogin('');
  }

  async function requestWakeLock() {
    try {
      if ('wakeLock' in navigator && !wakeLock) {
        wakeLock = await navigator.wakeLock.request('screen');
        wakeLock.addEventListener('release', () => { wakeLock = null; });
      }
    } catch (e) { /* not allowed: ignore */ }
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
