// End-to-end test of the browser viewer against `./gradlew :core:devServer`.
// Usage: node tools/viewer-e2e.cjs <port> <info.json written by devServer> <screenshot dir>
// Requires Playwright with Chromium (not part of the Gradle build).
'use strict';
const { chromium } = require('playwright');
const fs = require('fs');

const [port, infoPath, outDir] = process.argv.slice(2);
const info = JSON.parse(fs.readFileSync(infoPath, 'utf8'));
const base = `https://127.0.0.1:${port}`;
const results = [];

function check(name, ok, detail) {
  results.push({ name, ok: !!ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
}

async function newPage(browser, options = {}) {
  const context = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1280, height: 800 }, ...options });
  const page = await context.newPage();
  const problems = [];
  page.on('console', (m) => { if (/Content Security Policy|Refused to|Uncaught/i.test(m.text())) problems.push(m.text()); });
  page.on('pageerror', (e) => problems.push('pageerror: ' + e.message));
  return { context, page, problems };
}

async function loginWithCode(page, code) {
  await page.goto(base + '/');
  await page.waitForSelector('#login:not([hidden])');
  await page.fill('#code', code);
  await page.click('#btn-connect');
  await page.waitForSelector('#viewer:not([hidden])', { timeout: 10000 });
}

async function waitForPicture(page) {
  const handle = await page.waitForFunction(() => {
    const canvas = document.getElementById('canvas');
    const img = document.getElementById('mjpeg');
    if (!img.hidden && img.naturalWidth > 0) return 'mjpeg';
    if (!canvas.hidden && canvas.width > 300) {
      const d = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
      let sum = 0;
      for (let i = 0; i < d.length; i += 4000) sum += d[i] + d[i + 1] + d[i + 2];
      if (sum > 0) return 'h264';
    }
    return false;
  }, null, { timeout: 20000 });
  return handle.jsonValue();
}

(async () => {
  const browser = await chromium.launch();
  try {
    // 1) Access code login (Chinese UI), preferred H.264/WebCodecs path.
    {
      const { context, page, problems } = await newPage(browser, { locale: 'zh-CN' });
      await page.goto(base + '/');
      await page.waitForSelector('#login:not([hidden])');
      check('login form shown before authentication', true);
      check('Chinese UI when browser language is zh', (await page.textContent('#btn-connect')).includes('连接'));
      await page.fill('#code', '00000000');
      await page.click('#btn-connect');
      await page.waitForFunction(() => document.getElementById('login-error').textContent.length > 0);
      check('wrong code rejected with remaining attempts', true, await page.textContent('#login-error'));
      await page.fill('#code', info.code.toLowerCase());
      await page.click('#btn-connect');
      await page.waitForSelector('#viewer:not([hidden])', { timeout: 10000 });
      const env = await page.evaluate(async () => ({
        secureContext: isSecureContext,
        webCodecs: typeof VideoDecoder === 'function',
        h264: typeof VideoDecoder === 'function'
          ? (await VideoDecoder.isConfigSupported({ codec: 'avc1.64001f', codedWidth: 1280, codedHeight: 720 })).supported
          : false,
      }));
      console.log('      browser:', JSON.stringify(env));
      const mode = await waitForPicture(page);
      check('live picture rendered', !!mode, 'mode=' + mode);
      if (env.h264) check('H.264 decoded via WebCodecs when supported', mode === 'h264', mode);
      await page.waitForTimeout(2000);
      await page.screenshot({ path: `${outDir}/viewer-${mode}.png` });
      check('stats visible', (await page.textContent('#stats')).length > 0, await page.textContent('#stats'));
      check('no CSP violations or script errors', problems.length === 0, problems.join(' | '));
      await page.click('#btn-logout');
      await page.waitForSelector('#login:not([hidden])');
      const after = await page.evaluate(async () => (await fetch('/api/status')).status);
      check('logout invalidates the session', after === 401, 'status=' + after);
      await context.close();
    }

    // 2) One-time QR pairing link.
    {
      const { context, page } = await newPage(browser, { locale: 'en-US' });
      await page.goto(`${base}/#pair=${info.pair}`);
      await page.waitForSelector('#viewer:not([hidden])', { timeout: 10000 });
      check('QR pairing link logs in without typing the code', true);
      check('pairing token removed from the address bar', !page.url().includes('pair='), page.url());
      await context.close();
      const second = await newPage(browser, { locale: 'en-US' });
      await second.page.goto(`${base}/#pair=${info.pair}`);
      await second.page.waitForSelector('#login:not([hidden])');
      const message = await second.page.textContent('#login-error');
      check('pairing link cannot be reused', /QR/.test(message), message);
      await second.context.close();
    }

    // 3) Compatibility mode: browser without WebCodecs falls back to MJPEG.
    {
      const { context, page, problems } = await newPage(browser, { locale: 'en-US' });
      await context.addInitScript(() => { delete window.VideoDecoder; delete window.EncodedVideoChunk; });
      await loginWithCode(page, info.code);
      const mode = await waitForPicture(page);
      check('MJPEG fallback renders', mode === 'mjpeg', 'mode=' + mode);
      await page.waitForTimeout(1000);
      await page.screenshot({ path: `${outDir}/viewer-mjpeg-fallback.png` });
      check('no CSP violations in MJPEG mode', problems.length === 0, problems.join(' | '));
      await context.close();
    }

    // 4) WebCodecs available but WebSockets blocked (e.g. wss:// refused for a self-signed
    //    certificate): the viewer must fall back to MJPEG instead of retrying forever.
    {
      const { context, page, problems } = await newPage(browser, { locale: 'en-US' });
      await context.addInitScript(() => {
        window.WebSocket = class {
          constructor() { setTimeout(() => this.onclose && this.onclose({ code: 1006 }), 20); }
          send() {}
          close() {}
        };
      });
      await loginWithCode(page, info.code);
      const mode = await waitForPicture(page);
      check('falls back to MJPEG when WebSockets cannot connect', mode === 'mjpeg', 'mode=' + mode);
      check('no script errors in WebSocket fallback', problems.length === 0, problems.join(' | '));
      await context.close();
    }

    // 5) "Let an AI see" panel: an AI key for this computer and instructions any agent can follow
    //    (one curl command, pinned to the phone's key), run here with the real curl.
    {
      const { context, page, problems } = await newPage(browser, { locale: 'zh-CN', acceptDownloads: true });
      await loginWithCode(page, info.code);
      await page.click('#btn-ai');
      await page.waitForSelector('#ai-panel:not([hidden])');
      check('AI panel in Chinese', (await page.textContent('#ai-title')).includes('让 AI'));
      check('copy disabled until a key exists', await page.isDisabled('#ai-panel [data-copy="ai-agent-text"]'));
      await page.click('#ai-create-key');
      await page.waitForFunction(() => document.getElementById('ai-agent-text').textContent.includes('awc_'));
      const text = await page.textContent('#ai-agent-text');
      const command = text.split('\n').find((line) => line.startsWith('curl '));
      const pinBase64 = info.pin.slice('sha256/'.length);
      check('agent instructions: curl pinned to the phone key with an AI key',
        command && command.includes(`--pinnedpubkey "sha256//${pinBase64}"`) && /Bearer awc_[A-Za-z0-9_-]{43}/.test(command) &&
        command.includes(`https://127.0.0.1:${port}/api/snapshot`), command);
      const out = require('path').join(outDir, 'agent-curl.jpg');
      let ran = '';
      try {
        ran = require('child_process').execSync(command.replace('/tmp/awc-camera.jpg', `'${out}'`), { encoding: 'utf8', timeout: 30000, shell: '/bin/bash' });
      } catch (e) {
        ran = String(e.stderr || e.message);
      }
      const jpeg = fs.existsSync(out) ? fs.readFileSync(out) : Buffer.alloc(0);
      check('the curl command from the instructions saves a JPEG', jpeg[0] === 0xff && jpeg[1] === 0xd8, ran.trim());
      const server = JSON.parse(await page.textContent('#ai-config-desktop')).mcpServers['android-webcam'];
      check('MCP settings with address, key pin and AI key',
        server.command === 'node' && server.env.AWC_ADDRESS === `127.0.0.1:${port}` && server.env.AWC_PIN === info.pin &&
        /^awc_/.test(server.env.AWC_KEY), JSON.stringify(server.env).replace(/awc_[^"]+/, 'awc_…'));
      check('Node command for Windows 10', (await page.textContent('#ai-node-command')).includes(' snap --address '));
      // The tool is fetched over the page's own connection and saved from memory (blob): browsers
      // may fail direct downloads from a self-signed server although the page works.
      await page.click('#ai-panel summary');
      const [saved] = await Promise.all([page.waitForEvent('download'), page.click('#ai-download')]);
      const savedText = fs.readFileSync(await saved.path(), 'utf8');
      check('AI panel: connection tool saved from memory',
        saved.url().startsWith('blob:') && saved.suggestedFilename() === 'awc-ai.mjs' && savedText.includes('camera_snapshot'), saved.url());
      await page.screenshot({ path: `${outDir}/viewer-ai-panel.png`, fullPage: true });
      await page.click('#ai-close');
      check('AI panel closes', await page.isHidden('#ai-panel'));
      check('no CSP violations in the AI panel', problems.length === 0, problems.join(' | '));
      await context.close();
    }
  } finally {
    await browser.close();
  }
  const failed = results.filter((r) => !r.ok).length;
  console.log(`\n${results.length - failed}/${results.length} checks passed`);
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(2); });
