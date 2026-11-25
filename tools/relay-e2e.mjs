// End-to-end test of the cloud relay (relay/worker.js, run locally by `wrangler dev`) with a
// simulated phone and agent; the agent decrypts with the exact command given to cloud agents.
// Usage: node tools/relay-e2e.mjs <relay base URL> <phone key dir> [jvm-phone-ready-file]
// The phone key dir holds key.pem (PKCS#8, P-256), spki.der and other.pem (an unknown phone).
import { execFile } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const [base, keyDir] = process.argv.slice(2);
const host = new URL(base).host;
const wsBase = base.replace(/^http/, 'ws');
const results = [];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
// Asynchronous on purpose: the simulated phone lives in this process and must keep answering.
const run = (command) => new Promise((resolve) => {
  execFile('bash', ['-c', command], { encoding: 'utf8', timeout: 30000 }, (error, stdout, stderr) => resolve({ ok: !error, out: stdout + stderr }));
});

function check(name, ok, detail) {
  results.push({ name, ok: !!ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + String(detail).slice(0, 240) : ''}`);
}

const key = crypto.createPrivateKey(fs.readFileSync(path.join(keyDir, 'key.pem')));
const spki = fs.readFileSync(path.join(keyDir, 'spki.der'));
const otherKey = crypto.createPrivateKey(fs.readFileSync(path.join(keyDir, 'other.pem')));
const otherSpki = crypto.createPublicKey(otherKey).export({ type: 'spki', format: 'der' });

// ------------------------------------------------------------------ phone simulator (protocol)

function seal(e2eKey, agentId, jpeg, takenAt = Date.now()) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', e2eKey, iv);
  cipher.setAAD(Buffer.from('AWC1' + agentId));
  const ts = Buffer.alloc(8);
  ts.writeBigUInt64BE(BigInt(takenAt));
  const body = Buffer.concat([cipher.update(Buffer.concat([ts, jpeg])), cipher.final()]);
  return Buffer.concat([Buffer.from('AWC1'), iv, body, cipher.getAuthTag()]);
}

/** Connects like the phone; `answer(message)` returns a Buffer (picture), an error object or null. */
function phone({ signWith = key, publicKey = spki, agents = [], answer = () => null, tamper = false } = {}) {
  return new Promise((resolve) => {
    const ws = new WebSocket(`${wsBase}/phone`);
    ws.binaryType = 'arraybuffer';
    const conn = { ws, closed: null, ready: false, requests: [], pongs: 0 };
    ws.onmessage = (event) => {
      if (typeof event.data !== 'string') return;
      if (event.data === 'pong') { conn.pongs++; return; } // answer to the phone's keep-alive ping
      const m = JSON.parse(event.data);
      if (m.type === 'challenge') {
        const text = `AWC-RELAY-1\n${m.host}\n${m.nonce}${tamper ? 'x' : ''}`;
        const signature = crypto.sign('sha256', Buffer.from(text), { key: signWith, dsaEncoding: 'ieee-p1363' });
        ws.send(JSON.stringify({ type: 'auth', spki: publicKey.toString('base64'), signature: signature.toString('base64'), agents }));
      } else if (m.type === 'ready') {
        conn.ready = true;
        resolve(conn);
      } else if (m.type === 'snap') {
        conn.requests.push(m);
        const reply = answer(m);
        if (Buffer.isBuffer(reply)) ws.send(Buffer.concat([Buffer.from(m.id, 'hex'), reply]));
        else if (reply) ws.send(JSON.stringify({ type: 'error', id: m.id, ...reply }));
      }
    };
    ws.onclose = (event) => {
      conn.closed = event.code;
      resolve(conn);
    };
  });
}

/** dash: an id and key starting with "-" (must not be taken for command-line options). */
function credential({ dash = false } = {}) {
  let id, e2e;
  do id = crypto.randomBytes(6).toString('base64url'); while (dash && !id.startsWith('-'));
  do e2e = crypto.randomBytes(32); while (dash && !e2e.toString('base64url').startsWith('-'));
  const secret = crypto.randomBytes(32).toString('base64url');
  return { id, secret, e2e, token: `awcc_${id}_${secret}`, hash: crypto.createHash('sha256').update(secret).digest('hex') };
}

/** The command cloud agents get (fetch + decrypt); here with the picture path in a temp dir. */
function agentCommand(agent, out) {
  const decrypt = 'const c=require("crypto"),f=require("fs");const [k,id,src,dst]=process.argv.slice(1);' +
    'const d=f.readFileSync(src);if(d.length<36||d.toString("latin1",0,4)!=="AWC1")throw new Error("not an Android WebCam picture");' +
    'const x=c.createDecipheriv("aes-256-gcm",Buffer.from(k,"base64url"),d.subarray(4,16));x.setAAD(Buffer.from("AWC1"+id));' +
    'x.setAuthTag(d.subarray(d.length-16));const p=Buffer.concat([x.update(d.subarray(16,d.length-16)),x.final()]);' +
    'const age=Date.now()-Number(p.readBigUInt64BE(0));if(age>300000||age<-300000)throw new Error("picture is not current");' +
    'f.writeFileSync(dst,p.subarray(8));console.log(dst+" (taken "+Math.max(0,Math.round(age/1000))+" s ago)")';
  return `curl -fsS -H "Authorization: Bearer ${agent.token}" -o "${out}.enc" ${base}/v1/snapshot && ` +
    `node -e '${decrypt}' -- ${agent.e2e.toString('base64url')} ${agent.id} "${out}.enc" "${out}"`;
}

const get = (token) => fetch(`${base}/v1/snapshot`, { headers: token ? { Authorization: `Bearer ${token}` } : {} });

(async () => {
  const jpeg = Buffer.concat([Buffer.from([0xff, 0xd8]), crypto.randomBytes(20000), Buffer.from([0xff, 0xd9])]);
  const agent = credential({ dash: true });
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'awc-relay-'));

  let r = await fetch(`${base}/`);
  check('relay answers', r.status === 200 && (await r.text()).includes('relay'));
  check('no token: 401', (await get()).status === 401);
  r = await get(agent.token);
  check('phone offline: 503', r.status === 503, await r.text());

  const stranger = await phone({ signWith: otherKey, publicKey: otherSpki, agents: [{ id: agent.id, hash: agent.hash }] });
  check('unknown phone (other key) refused', !stranger.ready && stranger.closed === 4401, `close ${stranger.closed}`);
  const forger = await phone({ publicKey: spki, signWith: otherKey, agents: [{ id: agent.id, hash: agent.hash }] });
  check('right pin but wrong signature refused', !forger.ready && forger.closed === 4401, `close ${forger.closed}`);
  const replay = await phone({ tamper: true, agents: [{ id: agent.id, hash: agent.hash }] });
  check('signature over another challenge refused', !replay.ready && replay.closed === 4401, `close ${replay.closed}`);

  let answer = (m) => (m.agent === agent.id ? seal(agent.e2e, agent.id, jpeg) : { status: 401, error: 'unknown agent' });
  const conn = await phone({ agents: [{ id: agent.id, hash: agent.hash }], answer: (m) => answer(m) });
  check('phone with the configured key is accepted', conn.ready);

  check('wrong secret: 401', (await get(`awcc_${agent.id}_${'A'.repeat(43)}`)).status === 401);
  const out = path.join(tmp, 'camera.jpg');
  const fetched = await run(agentCommand(agent, out));
  check('agent command fetches and decrypts the picture', fs.existsSync(out) && fs.readFileSync(out).equals(jpeg), fetched.out.trim());
  const encrypted = fs.existsSync(`${out}.enc`) ? fs.readFileSync(`${out}.enc`) : Buffer.alloc(0);
  check('relay only sees ciphertext', encrypted.length > 0 && encrypted.indexOf(jpeg.subarray(0, 64)) < 0 && encrypted.subarray(0, 4).toString() === 'AWC1');

  r = await get(agent.token);
  check('too fast: 429', r.status === 429, r.headers.get('retry-after'));
  await sleep(2100);

  answer = (m) => ({ status: 503, error: 'no picture' });
  r = await get(agent.token);
  check('phone error is passed on', r.status === 503, await r.text());
  await sleep(2100);

  answer = (m) => seal(agent.e2e, agent.id, jpeg, Date.now() - 3600_000);
  const stale = await run(agentCommand(agent, out + '2'));
  check('an old (replayed) picture is rejected by the agent', !stale.ok && /not current/.test(stale.out), stale.out.split('\n').find((l) => /Error/.test(l)));
  await sleep(2100);

  // Revoked on the phone: the phone sends its new agent list.
  conn.ws.send(JSON.stringify({ type: 'agents', agents: [] }));
  await sleep(300);
  check('revoked agent: 401', (await get(agent.token)).status === 401);
  conn.ws.send(JSON.stringify({ type: 'agents', agents: [{ id: agent.id, hash: agent.hash }] }));
  await sleep(2100);

  // The phone disconnects while an agent waits: the agent gets an answer at once.
  answer = () => null; // never answers
  const waiting = get(agent.token);
  await sleep(500);
  const started = Date.now();
  conn.ws.close(1000);
  r = await waiting;
  check('phone gone while waiting: 503 at once', r.status === 503 && Date.now() - started < 5000, `${r.status} after ${Date.now() - started} ms`);

  // A newer phone connection replaces the older one.
  const first = await phone({ agents: [{ id: agent.id, hash: agent.hash }] });
  const second = await phone({ agents: [{ id: agent.id, hash: agent.hash }] });
  await sleep(500);
  check('newest phone connection replaces the older one', first.ready && second.ready && first.closed === 4000, `close ${first.closed}`);
  second.ws.close(1000);

  // A phone that lost its network may never close its connection: once it has missed its keep-alive
  // pings (75 s) it counts as offline, so agents learn it at once instead of waiting for a timeout.
  let answering = true;
  const quiet = await phone({ agents: [{ id: agent.id, hash: agent.hash }], answer: () => (answering ? seal(agent.e2e, agent.id, jpeg) : null) });
  const quietSince = Date.now();
  let lastPing = quietSince;
  const pinger = setInterval(() => { quiet.ws.send('ping'); lastPing = Date.now(); }, 20000);

  // Meanwhile: a connection that never authenticates is closed by the relay.
  const idle = new WebSocket(`${wsBase}/phone`);
  const idleClosed = await new Promise((resolve) => {
    idle.onclose = (event) => resolve(event.code);
    setTimeout(() => resolve('still open'), 40000);
  });
  check('unauthenticated connection closed after the timeout', idleClosed === 4408, `close ${idleClosed}`);

  await sleep(quietSince + 77000 - Date.now());
  r = await get(agent.token);
  check('a phone that keeps pinging stays online past the silence limit', quiet.ready && quiet.pongs >= 3 && r.status === 200,
    `${r.status}, ${quiet.pongs} pongs`);
  clearInterval(pinger);
  answering = false; // gone: no pings, no answers
  await sleep(lastPing + 77000 - Date.now());
  const asked = Date.now();
  r = await get(agent.token);
  check('a silent phone (no ping for 75 s) is offline: 503 at once', r.status === 503 && Date.now() - asked < 5000,
    `${r.status} after ${Date.now() - asked} ms`);
  await sleep(1000);
  check('the silent phone connection is closed by the relay', quiet.closed === 4408, `close ${quiet.closed}`);

  const failed = results.filter((x) => !x.ok).length;
  console.log(`\n${results.length - failed}/${results.length} relay checks passed`);
  process.exit(failed ? 1 : 0);
})().catch((e) => {
  console.error(e);
  process.exit(2);
});
