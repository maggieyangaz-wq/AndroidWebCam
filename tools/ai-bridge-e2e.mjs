// End-to-end test of the AI bridge (core/src/main/resources/web/awc-ai.mjs, an MCP server over
// stdio) against `./gradlew :core:devServer`, talking MCP exactly like an AI app does.
// Usage: node tools/ai-bridge-e2e.mjs <port> <info.json written by devServer> <output dir>
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const [port, infoPath, outDir] = process.argv.slice(2);
const info = JSON.parse(fs.readFileSync(infoPath, 'utf8'));
const bridge = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'core', 'src', 'main', 'resources', 'web', 'awc-ai.mjs');
const address = `127.0.0.1:${port}`;
const results = [];

function check(name, ok, detail) {
  results.push({ name, ok: !!ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + String(detail).slice(0, 300) : ''}`);
}

class McpClient {
  constructor(env) {
    this.child = spawn(process.execPath, [bridge], { env: { PATH: process.env.PATH, ...env }, stdio: ['pipe', 'pipe', 'pipe'] });
    this.nextId = 1;
    this.pending = new Map();
    this.buffer = '';
    this.stderr = '';
    this.child.stdout.setEncoding('utf8');
    this.child.stdout.on('data', (chunk) => {
      this.buffer += chunk;
      let i;
      while ((i = this.buffer.indexOf('\n')) >= 0) {
        const line = this.buffer.slice(0, i);
        this.buffer = this.buffer.slice(i + 1);
        if (!line.trim()) continue;
        const message = JSON.parse(line); // anything but JSON-RPC on stdout would break AI apps
        const resolve = this.pending.get(message.id);
        if (resolve) {
          this.pending.delete(message.id);
          resolve(message);
        }
      }
    });
    this.child.stderr.on('data', (d) => { this.stderr += d; });
  }

  request(method, params, timeoutMs = 60000) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`timeout waiting for ${method}; stderr: ${this.stderr}`));
      }, timeoutMs);
      this.pending.set(id, (message) => {
        clearTimeout(timer);
        resolve(message);
      });
      this.child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
    });
  }

  notify(method, params) {
    this.child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method, params }) + '\n');
  }

  async initialize() {
    const r = await this.request('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'e2e', version: '1' } });
    this.notify('notifications/initialized');
    return r;
  }

  async snapshot(args = {}) {
    return (await this.request('tools/call', { name: 'camera_snapshot', arguments: args })).result;
  }

  async close() {
    const exited = new Promise((resolve) => this.child.once('exit', resolve));
    this.child.stdin.end();
    await exited;
  }
}

const textOf = (result) => (result.content || []).filter((c) => c.type === 'text').map((c) => c.text).join(' ');
const imageOf = (result) => (result.content || []).find((c) => c.type === 'image');

(async () => {
  const mcp = new McpClient({ AWC_ADDRESS: address, AWC_PIN: info.pin });
  try {
    const init = await mcp.initialize();
    check('MCP initialize', init.result && init.result.protocolVersion === '2025-06-18' && init.result.capabilities.tools,
      JSON.stringify(init.result && init.result.serverInfo));
    const list = await mcp.request('tools/list', {});
    const tool = list.result.tools.find((t) => t.name === 'camera_snapshot');
    check('tool camera_snapshot listed', tool && tool.inputSchema && tool.inputSchema.type === 'object');

    let r = await mcp.snapshot();
    check('asks for the access code before the first login', r.isError && /access code/i.test(textOf(r)), textOf(r));

    r = await mcp.snapshot({ access_code: '0000-0000' });
    check('wrong access code reported (phone counts the attempt)', r.isError && /not valid/.test(textOf(r)) && /attempt/.test(textOf(r)), textOf(r));

    r = await mcp.snapshot({ access_code: info.code.toLowerCase() });
    const image = imageOf(r);
    const jpeg = image ? Buffer.from(image.data, 'base64') : Buffer.alloc(0);
    check('picture returned as JPEG image content', !r.isError && image && image.mimeType === 'image/jpeg' && jpeg[0] === 0xff && jpeg[1] === 0xd8, textOf(r));
    check('picture size and orientation note', /640×360/.test(textOf(r)) && /not mirrored/.test(textOf(r)), textOf(r));
    fs.writeFileSync(path.join(outDir, 'ai-bridge-picture.jpg'), jpeg);

    // Right away again, without the code: the session is reused; the phone's limit of one picture
    // per second answers 429 first, and the bridge waits and retries.
    r = await mcp.snapshot();
    check('next picture reuses the session (rate limit handled)', !r.isError && imageOf(r), textOf(r));

    const unknownTool = await mcp.request('tools/call', { name: 'nope', arguments: {} });
    check('unknown tool is a JSON-RPC error', unknownTool.error && unknownTool.error.code === -32602);
    const unknownMethod = await mcp.request('resources/list', {});
    check('unknown method is a JSON-RPC error', unknownMethod.error && unknownMethod.error.code === -32601);
    const ping = await mcp.request('ping', {});
    check('ping answered', ping.result && Object.keys(ping.result).length === 0);
  } finally {
    await mcp.close();
  }

  // Another key at that address (not the configured phone): refused before anything is sent.
  {
    const wrongPin = 'sha256/' + Buffer.alloc(32, 7).toString('base64');
    const mcp2 = new McpClient({ AWC_ADDRESS: address, AWC_PIN: wrongPin });
    await mcp2.initialize();
    const r = await mcp2.snapshot({ access_code: info.code });
    check('different key refused (certificate fingerprint shown)', r.isError && /not the configured phone/.test(textOf(r)) && textOf(r).includes(info.fingerprint), textOf(r));
    await mcp2.close();
  }

  // Never connects outside the local network, and needs its settings.
  {
    const mcp3 = new McpClient({ AWC_ADDRESS: '8.8.8.8:443', AWC_PIN: info.pin });
    await mcp3.initialize();
    let r = await mcp3.snapshot({ access_code: info.code });
    check('address outside the local network refused', r.isError && /local network/.test(textOf(r)), textOf(r));
    await mcp3.close();
    const mcp4 = new McpClient({});
    await mcp4.initialize();
    r = await mcp4.snapshot({ access_code: info.code });
    check('missing settings explained', r.isError && /AWC_ADDRESS/.test(textOf(r)), textOf(r));
    await mcp4.close();
  }

  // Command-line check, as shown on the viewer page.
  {
    const out = path.join(outDir, 'ai-bridge-check.jpg');
    const p = spawnSync(process.execPath, [bridge, '--check', '--address', address, '--pin', info.pin, '--code', info.code, '--out', out],
      { encoding: 'utf8', timeout: 60000 });
    const ok = p.status === 0 && fs.existsSync(out) && fs.readFileSync(out)[0] === 0xff;
    check('--check saves a picture', ok, (p.stdout + p.stderr).trim());
  }

  const failed = results.filter((x) => !x.ok).length;
  console.log(`\n${results.length - failed}/${results.length} AI bridge checks passed`);
  process.exit(failed ? 1 : 0);
})().catch((e) => {
  console.error(e);
  process.exit(2);
});
