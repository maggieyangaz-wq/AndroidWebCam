#!/usr/bin/env node
// Android WebCam AI bridge: takes one picture from the phone, either as a command
// (`node awc-ai.mjs snap`, for agents that run commands, e.g. on Windows 10 whose curl lacks TLS 1.3)
// or as a small MCP server over stdio (default), for AI apps such as Claude Desktop.
// No dependencies, Node.js 18 or newer. Download it from the phone's viewer page.
//
// Security and privacy:
//  - connects only to the configured phone in your local network (private IPv4 address),
//    over TLS 1.3, and checks the phone's public-key pin BEFORE sending anything;
//  - authenticates with the AI access key created in the viewer page (or the access code);
//  - takes a picture only when asked; nothing runs in the background;
//  - the pictures are sent to the AI service you use with this tool.
//
// Configuration (environment variables or --address/--pin/--key; the viewer page shows them):
//   AWC_ADDRESS      the phone's address, e.g. 192.168.1.23:8443
//   AWC_PIN          the phone's public-key pin, e.g. sha256/AbC...=
//   AWC_KEY          the AI access key for this computer (awc_...)
//   AWC_ACCESS_CODE  optional instead of a key: the access code shown on the phone
//
// Take a picture without an AI (prints the file's path):
//   node awc-ai.mjs snap --address 192.168.1.23:8443 --pin sha256/... --key awc_... [--out picture.jpg]
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import readline from 'node:readline';
import tls from 'node:tls';

const VERSION = '1.0.0';
const TOOL_NAME = 'camera_snapshot';
const PROTOCOL_VERSIONS = ['2025-11-25', '2025-06-18', '2025-03-26', '2024-11-05'];
const FALLBACK_PROTOCOL_VERSION = '2025-06-18';
const MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
const REQUEST_TIMEOUT_MS = 20000;
const USER_AGENT = `AndroidWebCam-AI/${VERSION} (${{ win32: 'Windows', darwin: 'Macintosh', linux: 'Linux' }[process.platform] || process.platform})`;

class BridgeError extends Error {
  constructor(kind, message) {
    super(message);
    this.kind = kind;
  }
}

// ------------------------------------------------------------------ configuration

/**
 * "192.168.1.23:8443" (optionally with https:// and a path) -> target, or null unless it is a
 * private LAN address (or loopback, used by tests): never connects to the internet.
 */
function parseAddress(value) {
  const text = String(value || '').trim().replace(/^https:\/\//i, '').replace(/\/.*$/, '');
  const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3}):(\d{1,5})$/.exec(text);
  if (!m) return null;
  const o = m.slice(1, 5).map(Number);
  const port = Number(m[5]);
  if (o.some((n) => n > 255) || port < 1 || port > 65535) return null;
  const lan = o[0] === 10 || (o[0] === 172 && o[1] >= 16 && o[1] <= 31) ||
    (o[0] === 192 && o[1] === 168) || (o[0] === 169 && o[1] === 254) || o[0] === 127;
  if (!lan) return null;
  const host = o.join('.');
  return { host, port, authority: `${host}:${port}` };
}

function parsePin(value) {
  const text = String(value || '').trim();
  return /^sha256\/[A-Za-z0-9+/]{43}=$/.test(text) ? text : null;
}

/** Access codes are 8 Crockford base32 characters; accepts dashes, spaces and look-alikes. */
function normalizeCode(value) {
  const text = String(value || '').toUpperCase().replace(/[\s-]/g, '').replace(/O/g, '0').replace(/[IL]/g, '1');
  return /^[0-9A-HJKMNP-TV-Z]{8}$/.test(text) ? text : null;
}

// ------------------------------------------------------------------ certificate pin

function readTlv(der, pos, limit) {
  if (pos + 2 > limit) throw new Error('truncated DER');
  const tag = der[pos];
  if ((tag & 0x1f) === 0x1f) throw new Error('unsupported DER tag');
  let p = pos + 1;
  const first = der[p++];
  let length = first;
  if (first >= 0x80) {
    const n = first & 0x7f;
    if (n < 1 || n > 3 || p + n > limit) throw new Error('bad DER length');
    length = 0;
    for (let i = 0; i < n; i++) length = (length << 8) | der[p++];
  }
  if (length > limit - p) throw new Error('truncated DER');
  return { tag, start: pos, contentStart: p, end: p + length };
}

/** The SubjectPublicKeyInfo of a DER certificate, byte for byte (same as the phone computes). */
function subjectPublicKeyInfo(der) {
  const cert = readTlv(der, 0, der.length);
  if (cert.tag !== 0x30) throw new Error('not a certificate');
  const tbs = readTlv(der, cert.contentStart, cert.end);
  if (tbs.tag !== 0x30) throw new Error('not a certificate');
  let element = readTlv(der, tbs.contentStart, tbs.end);
  if (element.tag === 0xa0) element = readTlv(der, element.end, tbs.end); // [0] version
  let pos = element.end; // after serialNumber: signature, issuer, validity, subject
  for (let i = 0; i < 4; i++) pos = readTlv(der, pos, tbs.end).end;
  const spki = readTlv(der, pos, tbs.end);
  if (spki.tag !== 0x30) throw new Error('malformed certificate');
  return der.subarray(spki.start, spki.end);
}

function pinOf(certificateDer) {
  return 'sha256/' + crypto.createHash('sha256').update(subjectPublicKeyInfo(certificateDer)).digest('base64');
}

function fingerprintOf(certificateDer) {
  return crypto.createHash('sha256').update(certificateDer).digest('hex').toUpperCase().match(/../g).join(':');
}

// ------------------------------------------------------------------ HTTPS (pinned, TLS 1.3 only)

function parseResponse(buffer) {
  const end = buffer.indexOf('\r\n\r\n');
  if (end < 0) throw new BridgeError('network', 'Incomplete response from the phone.');
  const lines = buffer.subarray(0, end).toString('latin1').split('\r\n');
  const m = /^HTTP\/1\.1 (\d{3})/.exec(lines[0]);
  if (!m) throw new BridgeError('network', 'Unexpected response from the phone.');
  const headers = {};
  for (const line of lines.slice(1)) {
    const i = line.indexOf(':');
    if (i > 0) {
      const key = line.slice(0, i).trim().toLowerCase();
      if (!(key in headers)) headers[key] = line.slice(i + 1).trim();
    }
  }
  let body = buffer.subarray(end + 4);
  if (headers['content-length'] !== undefined) {
    const n = Number(headers['content-length']);
    if (!Number.isInteger(n) || n < 0 || n > body.length) throw new BridgeError('network', 'Incomplete response from the phone.');
    body = body.subarray(0, n);
  }
  return { status: Number(m[1]), headers, body };
}

/**
 * One HTTP/1.1 request. Nothing is written to the connection until the TLS 1.3 handshake is
 * complete and the phone's public key matches the pin.
 */
function request(target, pin, method, path, headers = {}, body = null) {
  return new Promise((resolve, reject) => {
    let settled = false;
    let timer = null;
    const chunks = [];
    let size = 0;
    const socket = tls.connect({
      host: target.host,
      port: target.port,
      minVersion: 'TLSv1.3',
      maxVersion: 'TLSv1.3',
      // The phone's certificate is self-signed; it is verified by the public-key pin below.
      rejectUnauthorized: false,
    });
    const finish = (error, value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      socket.destroy();
      if (error) reject(error);
      else resolve(value);
    };
    timer = setTimeout(() => finish(new BridgeError('network', `The phone at ${target.authority} did not answer in time.`)), REQUEST_TIMEOUT_MS);
    socket.once('secureConnect', () => {
      const certificate = socket.getPeerCertificate(true);
      const der = certificate && certificate.raw;
      let actual = null;
      try { actual = der ? pinOf(der) : null; } catch (e) { actual = null; }
      if (actual !== pin || socket.getProtocol() !== 'TLSv1.3') {
        const error = new BridgeError('pin', `The device at ${target.authority} is not the configured phone (its key does not match AWC_PIN). ` +
          (der ? `Its certificate fingerprint is ${fingerprintOf(der)}; compare it with the fingerprint shown in the app. ` : '') +
          'If the phone was reset or reinstalled, copy the new settings from the viewer page.');
        return finish(error);
      }
      const payload = body === null ? null : Buffer.from(body, 'utf8');
      const head = [`${method} ${path} HTTP/1.1`, `Host: ${target.authority}`, `User-Agent: ${USER_AGENT}`, 'Connection: close'];
      for (const [key, value] of Object.entries(headers)) head.push(`${key}: ${value}`);
      if (payload) head.push(`Content-Length: ${payload.length}`);
      socket.write(head.join('\r\n') + '\r\n\r\n');
      if (payload) socket.write(payload);
    });
    socket.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_RESPONSE_BYTES) return finish(new BridgeError('network', 'The response from the phone is too large.'));
      chunks.push(chunk);
    });
    const complete = () => {
      try { finish(null, parseResponse(Buffer.concat(chunks))); } catch (e) { finish(e); }
    };
    socket.on('end', complete);
    socket.on('close', complete);
    socket.on('error', (e) => finish(new BridgeError('network', `Cannot reach the phone at ${target.authority} (${e.code || e.message}). ` +
      'Is streaming running, and is this computer in the same network as the phone?')));
  });
}

// ------------------------------------------------------------------ phone session

const session = { authority: null, cookie: null, code: null };

async function login(target, pin, code) {
  const r = await request(target, pin, 'POST', '/api/login',
    { Origin: `https://${target.authority}`, 'Content-Type': 'application/json' }, JSON.stringify({ code }));
  if (r.status === 204) {
    const m = /^(__Host-awc_sid=[A-Za-z0-9_-]{43});/.exec(r.headers['set-cookie'] || '');
    if (!m) throw new BridgeError('network', 'The phone did not return a session.');
    Object.assign(session, { authority: target.authority, cookie: m[1], code });
    return;
  }
  let info = {};
  try { info = JSON.parse(r.body.toString('utf8')); } catch (e) { /* not JSON */ }
  if (r.status === 401) {
    if (session.code === code) session.code = null;
    throw new BridgeError('need-code', `The access code ${code.slice(0, 4)}-${code.slice(4)} is not valid` +
      (info.attemptsLeft !== undefined ? ` (${info.attemptsLeft} attempt(s) left before a temporary lock)` : '') +
      '. The phone shows a new code each time streaming starts and after "disconnect all". ' +
      'Ask the user for the access code currently shown in the Android WebCam app and call the tool again with access_code.');
  }
  if (r.status === 429) {
    throw new BridgeError('locked', `Too many wrong access codes: the phone refuses logins from this computer for ${info.retryAfter || r.headers['retry-after'] || 'some'} seconds.`);
  }
  throw new BridgeError('network', `The phone refused the login (HTTP ${r.status}).`);
}

function jpegSize(jpeg) {
  let i = 2;
  while (i + 9 < jpeg.length && jpeg[i] === 0xff) {
    const marker = jpeg[i + 1];
    if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
      return { width: jpeg.readUInt16BE(i + 7), height: jpeg.readUInt16BE(i + 5) };
    }
    i += 2 + jpeg.readUInt16BE(i + 2);
  }
  return null;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Takes one picture: with the AI access key, or else logging in with the access code when needed. */
async function takePicture({ address, pin, key, accessCode } = {}) {
  const target = parseAddress(address);
  if (!target) {
    throw new BridgeError('config', address
      ? `"${address}" is not a phone address in the local network (expected something like 192.168.1.23:8443).`
      : 'The phone address is not configured (AWC_ADDRESS). Copy the settings from the "Let an AI see the picture" panel (robot button) of the viewer page.');
  }
  const checkedPin = parsePin(pin);
  if (!checkedPin) {
    throw new BridgeError('config', 'The phone\'s public-key pin is missing or malformed (AWC_PIN). Copy the settings from the "Let an AI see the picture" panel (robot button) of the viewer page.');
  }
  if (key) {
    const checkedKey = String(key).trim();
    if (!/^awc_[A-Za-z0-9_-]{43}$/.test(checkedKey)) {
      throw new BridgeError('config', 'The AI access key (AWC_KEY) is malformed. Create a key in the "Let an AI see the picture" panel (robot button) of the viewer page.');
    }
    for (let attempt = 0; attempt < 3; attempt++) {
      const r = await request(target, checkedPin, 'GET', '/api/snapshot', { Authorization: `Bearer ${checkedKey}` });
      if (r.status === 200 && r.body.length > 3 && r.body[0] === 0xff && r.body[1] === 0xd8) {
        return { jpeg: r.body, size: jpegSize(r.body), target };
      }
      if (r.status === 401) {
        throw new BridgeError('key', 'The AI access key is not valid (it was revoked on the phone, or this computer\'s network address changed). ' +
          'Ask the user to create a new key in the viewer page (robot button) and update the configuration.');
      }
      if (r.status === 429) { await sleep(1100); continue; }
      if (r.status === 503) throw new BridgeError('no-picture', 'The phone has no picture right now (the camera may be in use by another app, or streaming is just starting). Try again in a few seconds.');
      throw new BridgeError('network', `Unexpected answer from the phone (HTTP ${r.status}).`);
    }
    throw new BridgeError('network', 'The phone did not provide a picture. Try again in a moment.');
  }
  let code = null;
  if (accessCode) {
    code = normalizeCode(accessCode);
    if (!code) throw new BridgeError('need-code', 'An access code has 8 letters and digits, like K7QM-3XRP. Ask the user for the code shown in the Android WebCam app.');
  }
  if (session.authority !== target.authority) Object.assign(session, { authority: target.authority, cookie: null, code: null });
  let loggedIn = false;
  for (let attempt = 0; attempt < 4; attempt++) {
    if (!session.cookie) {
      const candidate = code || session.code;
      if (!candidate || loggedIn) {
        throw new BridgeError('need-code', 'Not connected to the phone yet (or the session ended). Ask the user for the 8-character access code ' +
          'shown in the Android WebCam app on the phone and call the tool again with access_code.');
      }
      await login(target, checkedPin, candidate);
      loggedIn = true;
    }
    const r = await request(target, checkedPin, 'GET', '/api/snapshot', { Cookie: session.cookie });
    if (r.status === 200 && r.body.length > 3 && r.body[0] === 0xff && r.body[1] === 0xd8) {
      return { jpeg: r.body, size: jpegSize(r.body), target };
    }
    if (r.status === 401) { session.cookie = null; continue; }
    if (r.status === 429) { await sleep(1100); continue; }
    if (r.status === 503) throw new BridgeError('no-picture', 'The phone has no picture right now (the camera may be in use by another app, or streaming is just starting). Try again in a few seconds.');
    throw new BridgeError('network', `Unexpected answer from the phone (HTTP ${r.status}).`);
  }
  throw new BridgeError('network', 'The phone did not provide a picture. Try again in a moment.');
}

// ------------------------------------------------------------------ MCP (JSON-RPC 2.0 over stdio)

const TOOL = {
  name: TOOL_NAME,
  title: 'Phone camera picture',
  description: 'Takes one new picture from the Android WebCam phone (its rear camera, exactly as the live viewers see it, never mirrored) ' +
    'and returns it as an image. Use it whenever the user wants to know what the camera currently shows. ' +
    'If the result says an access code is needed (no AI access key configured), ask the user for the 8-character access code ' +
    'shown in the Android WebCam app and call again with access_code. If the phone\'s address changed, the user can give the new address (address).',
  inputSchema: {
    type: 'object',
    properties: {
      access_code: { type: 'string', description: 'The access code shown on the phone, e.g. K7QM-3XRP. Only needed when the tool asks for it.' },
      address: { type: 'string', description: 'Only if the user gives a new phone address, e.g. 192.168.1.23:8443.' },
    },
    additionalProperties: false,
  },
  annotations: { readOnlyHint: true, openWorldHint: false },
};

let queue = Promise.resolve();
function serialized(task) {
  const run = queue.then(task, task);
  queue = run.catch(() => {});
  return run;
}

async function callTool(params) {
  if (!params || params.name !== TOOL_NAME) {
    const error = new Error(`Unknown tool: ${params && params.name}`);
    error.code = -32602;
    throw error;
  }
  const args = params.arguments && typeof params.arguments === 'object' ? params.arguments : {};
  return serialized(async () => {
    try {
      const picture = await takePicture({
        address: args.address || process.env.AWC_ADDRESS,
        pin: process.env.AWC_PIN,
        key: process.env.AWC_KEY,
        accessCode: args.access_code || process.env.AWC_ACCESS_CODE,
      });
      const size = picture.size ? `${picture.size.width}×${picture.size.height}, ` : '';
      return {
        content: [
          { type: 'text', text: `Picture from the phone camera at ${picture.target.authority}, taken ${new Date().toISOString()} (${size}rear camera, not mirrored).` },
          { type: 'image', data: picture.jpeg.toString('base64'), mimeType: 'image/jpeg' },
        ],
      };
    } catch (e) {
      return { content: [{ type: 'text', text: e instanceof BridgeError ? e.message : `Error: ${e.message || e}` }], isError: true };
    }
  });
}

function send(message) {
  process.stdout.write(JSON.stringify(message) + '\n');
}

async function handle(message) {
  if (!message || typeof message !== 'object' || message.jsonrpc !== '2.0' || typeof message.method !== 'string') {
    if (message && typeof message === 'object' && message.method === undefined && message.id !== undefined) return; // a response
    send({ jsonrpc: '2.0', id: message && message.id !== undefined ? message.id : null, error: { code: -32600, message: 'Invalid Request' } });
    return;
  }
  const { id, method, params } = message;
  const isRequest = id !== undefined && id !== null;
  try {
    let result;
    switch (method) {
      case 'initialize': {
        const requested = params && params.protocolVersion;
        result = {
          protocolVersion: PROTOCOL_VERSIONS.includes(requested) ? requested : FALLBACK_PROTOCOL_VERSION,
          capabilities: { tools: { listChanged: false } },
          serverInfo: { name: 'android-webcam', title: 'Android WebCam', version: VERSION },
          instructions: `Use ${TOOL_NAME} to look at the Android WebCam phone camera when the user asks about it.`,
        };
        break;
      }
      case 'ping':
        result = {};
        break;
      case 'tools/list':
        result = { tools: [TOOL] };
        break;
      case 'tools/call':
        result = await callTool(params);
        break;
      default:
        if (isRequest) send({ jsonrpc: '2.0', id, error: { code: -32601, message: `Method not found: ${method}` } });
        return;
    }
    if (isRequest) send({ jsonrpc: '2.0', id, result });
  } catch (e) {
    if (isRequest) send({ jsonrpc: '2.0', id, error: { code: e.code || -32603, message: String(e.message || e) } });
  }
}

function serve() {
  process.stdout.on('error', () => process.exit(0));
  const pending = new Set();
  const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
  rl.on('line', (line) => {
    const text = line.trim();
    if (!text) return;
    let message;
    try {
      message = JSON.parse(text);
    } catch (e) {
      send({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Parse error' } });
      return;
    }
    for (const m of Array.isArray(message) ? message : [message]) {
      const p = handle(m).finally(() => pending.delete(p));
      pending.add(p);
    }
  });
  rl.on('close', async () => {
    await Promise.allSettled([...pending]);
    process.exit(0);
  });
}

// ------------------------------------------------------------------ command line

/** `snap` (alias `--check`): saves one picture and prints the file's full path. */
async function snap(argv) {
  const option = (name) => {
    const i = argv.indexOf(name);
    return i >= 0 ? argv[i + 1] : undefined;
  };
  const out = path.resolve(option('--out') || path.join(os.tmpdir(), 'awc-camera.jpg'));
  try {
    const picture = await takePicture({
      address: option('--address') || process.env.AWC_ADDRESS,
      pin: option('--pin') || process.env.AWC_PIN,
      key: option('--key') || process.env.AWC_KEY,
      accessCode: option('--code') || process.env.AWC_ACCESS_CODE,
    });
    fs.writeFileSync(out, picture.jpeg);
    const size = picture.size ? ` (${picture.size.width}x${picture.size.height}, rear camera, not mirrored)` : '';
    console.log(out);
    console.error(`Picture${size} from ${picture.target.authority} saved.`);
    return 0;
  } catch (e) {
    console.error(`FAILED: ${e.message || e}`);
    return 1;
  }
}

{
  const major = Number(process.versions.node.split('.')[0]);
  if (major < 18) {
    process.stderr.write('The Android WebCam AI bridge needs Node.js 18 or newer.\n');
    process.exit(1);
  }
  const argv = process.argv.slice(2);
  if (argv.includes('--version')) {
    console.log(VERSION);
  } else if (argv.includes('--help') || argv.includes('-h')) {
    console.log('Android WebCam AI bridge.\n' +
      '  node awc-ai.mjs snap [--out file.jpg]   take one picture, print its path\n' +
      '  node awc-ai.mjs                          MCP server over stdio (for AI apps)\n' +
      'Settings: AWC_ADDRESS, AWC_PIN, AWC_KEY (or --address, --pin, --key); optional AWC_ACCESS_CODE / --code.');
  } else if (argv[0] === 'snap' || argv.includes('--check')) {
    snap(argv).then((code) => process.exit(code));
  } else {
    serve();
  }
}
