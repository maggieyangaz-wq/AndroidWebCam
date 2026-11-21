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
      if (!blob) return;
      const link = document.createElement('a');
      link.href = URL.createObjectURL(blob);
      link.download = 'androidwebcam-' + new Date().toISOString().replace(/[:.]/g, '-') + '.png';
      document.body.appendChild(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(link.href), 10000);
    }, 'image/png');
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
