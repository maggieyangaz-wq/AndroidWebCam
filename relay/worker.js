// Android WebCam cloud relay (Cloudflare Worker + Durable Object).
//
// Lets AI agents running in the cloud take single pictures from the phone:
//   - the phone keeps ONE outgoing WebSocket to this relay (it never accepts connections from the
//     internet) and proves its identity by signing a random challenge with its hardware-backed key,
//     whose public-key pin is configured here (PHONE_PIN);
//   - an agent calls GET /v1/snapshot with its token; the relay asks the phone for a picture;
//   - pictures are encrypted on the phone for that agent (AES-256-GCM, key never sent to the relay):
//     this relay only forwards bytes it cannot read, and keeps nothing.
//
// Configuration (Worker variable): PHONE_PIN = "sha256/<base64 SHA-256 of the SubjectPublicKeyInfo>"
// (comma-separated to allow several phones). Binding HUB: Durable Object class PhoneHub.
import { DurableObject } from "cloudflare:workers";

const PROTOCOL = "AWC-RELAY-1";
const SNAPSHOT_TIMEOUT_MS = 20000;
const AUTH_TIMEOUT_MS = 15000;
const MAX_PENDING = 4;
const MIN_INTERVAL_MS = 2000; // per agent; the phone enforces its own (stricter) limits as well
const MAX_AGENTS = 32;

const HEADERS = { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff", "Referrer-Policy": "no-referrer" };

function json(status, body, extra = {}) {
  return new Response(JSON.stringify(body) + "\n", {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", ...HEADERS, ...extra },
  });
}

function b64(bytes) {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}

function unb64(text) {
  if (typeof text !== "string" || text.length > 4096) throw new Error("bad base64");
  const s = atob(text);
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}

function hex(bytes) {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

function sameHex(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function sanitizeAgents(list) {
  if (!Array.isArray(list)) return [];
  return list
    .filter((a) => a && /^[A-Za-z0-9_-]{8}$/.test(a.id) && /^[0-9a-f]{64}$/.test(a.hash))
    .slice(0, MAX_AGENTS)
    .map((a) => ({ id: a.id, hash: a.hash }));
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/phone" || url.pathname === "/v1/snapshot") {
      return env.HUB.get(env.HUB.idFromName("phone")).fetch(request);
    }
    if (url.pathname === "/") {
      return new Response("Android WebCam relay\n", { headers: { "Content-Type": "text/plain; charset=utf-8", ...HEADERS } });
    }
    return json(404, { error: "not found" });
  },
};

export class PhoneHub extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.pending = new Map(); // request id -> { resolve, timer }
    this.lastByAgent = new Map();
    // Keep-alive pings are answered without waking the object (no duration charges while idle).
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/phone") return this.acceptPhone(request, url);
    if (request.method !== "GET") return json(405, { error: "method not allowed" }, { Allow: "GET" });
    return this.snapshot(request);
  }

  acceptPhone(request, url) {
    if ((request.headers.get("Upgrade") || "").toLowerCase() !== "websocket") {
      return json(426, { error: "websocket expected" });
    }
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server);
    const nonce = b64(crypto.getRandomValues(new Uint8Array(32)));
    server.serializeAttachment({ stage: "challenge", nonce, host: url.host, since: Date.now() });
    server.send(JSON.stringify({ type: "challenge", protocol: PROTOCOL, nonce, host: url.host }));
    this.ctx.storage.setAlarm(Date.now() + AUTH_TIMEOUT_MS);
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws, message) {
    const state = ws.deserializeAttachment() || {};
    if (state.stage === "challenge") return this.authenticate(ws, state, message);
    if (state.stage !== "ready") {
      ws.close(4400, "protocol");
      return;
    }
    if (typeof message === "string") {
      let m;
      try {
        m = JSON.parse(message);
      } catch {
        return;
      }
      if (m.type === "agents") {
        ws.serializeAttachment({ ...state, agents: sanitizeAgents(m.agents) });
      } else if (m.type === "error" && typeof m.id === "string") {
        const status = [401, 404, 429, 503].includes(m.status) ? m.status : 502;
        this.settle(m.id, json(status, { error: String(m.error || "phone error").slice(0, 200) }));
      }
      return;
    }
    // Binary: 16-byte request id followed by the encrypted picture.
    const bytes = new Uint8Array(message);
    if (bytes.length < 17) return;
    this.settle(hex(bytes.subarray(0, 16)), new Response(bytes.slice(16), {
      headers: { "Content-Type": "application/octet-stream", ...HEADERS },
    }));
  }

  async authenticate(ws, state, message) {
    try {
      if (typeof message !== "string") throw new Error("auth expected");
      const m = JSON.parse(message);
      if (m.type !== "auth") throw new Error("auth expected");
      const spki = unb64(m.spki);
      const signature = unb64(m.signature);
      const pin = "sha256/" + b64(new Uint8Array(await crypto.subtle.digest("SHA-256", spki)));
      const allowed = String(this.env.PHONE_PIN || "").split(",").map((s) => s.trim()).filter(Boolean);
      if (!allowed.includes(pin)) throw new Error("unknown phone");
      const key = await crypto.subtle.importKey("spki", spki, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
      const signed = new TextEncoder().encode(PROTOCOL + "\n" + state.host + "\n" + state.nonce);
      if (!(await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, signature, signed))) {
        throw new Error("bad signature");
      }
      // One phone connection at a time: the newest one wins.
      for (const other of this.ctx.getWebSockets()) {
        if (other !== ws && other.deserializeAttachment()?.stage === "ready") other.close(4000, "replaced");
      }
      ws.serializeAttachment({ stage: "ready", agents: sanitizeAgents(m.agents), since: Date.now() });
      ws.send(JSON.stringify({ type: "ready" }));
    } catch {
      ws.close(4401, "unauthorized");
    }
  }

  async snapshot(request) {
    const auth = (request.headers.get("Authorization") || "").trim();
    const match = /^Bearer awcc_([A-Za-z0-9_-]{8})_([A-Za-z0-9_-]{43})$/.exec(auth);
    if (!match) return json(401, { error: "unauthorized" });
    const [, agentId, secret] = match;
    const phone = this.ctx.getWebSockets().find((w) => w.deserializeAttachment()?.stage === "ready");
    if (!phone) {
      return json(503, { error: "phone offline", hint: "Streaming must be running on the phone with cloud access turned on." }, { "Retry-After": "30" });
    }
    const hash = hex(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(secret))));
    const agent = (phone.deserializeAttachment().agents || []).find((a) => a.id === agentId);
    if (!agent || !sameHex(agent.hash, hash)) return json(401, { error: "unauthorized" });

    const now = Date.now();
    if (now - (this.lastByAgent.get(agentId) || 0) < MIN_INTERVAL_MS || this.pending.size >= MAX_PENDING) {
      return json(429, { error: "too many requests" }, { "Retry-After": "2" });
    }
    this.lastByAgent.set(agentId, now);

    const id = hex(crypto.getRandomValues(new Uint8Array(16)));
    const result = new Promise((resolve) => {
      const timer = setTimeout(() => this.settle(id, json(504, { error: "the phone did not answer" })), SNAPSHOT_TIMEOUT_MS);
      this.pending.set(id, { resolve, timer });
    });
    try {
      phone.send(JSON.stringify({ type: "snap", id, agent: agentId }));
    } catch {
      this.settle(id, json(503, { error: "phone offline" }));
    }
    return result;
  }

  settle(id, response) {
    const pending = this.pending.get(id);
    if (!pending) return;
    this.pending.delete(id);
    clearTimeout(pending.timer);
    pending.resolve(response);
  }

  // Closes connections that did not authenticate in time.
  async alarm() {
    const now = Date.now();
    let next = 0;
    for (const ws of this.ctx.getWebSockets()) {
      const state = ws.deserializeAttachment();
      if (state?.stage !== "challenge") continue;
      // (alarms may fire slightly early: allow a second)
      if (now - state.since >= AUTH_TIMEOUT_MS - 1000) ws.close(4408, "timeout");
      else next = Math.max(next, state.since + AUTH_TIMEOUT_MS);
    }
    if (next) await this.ctx.storage.setAlarm(Math.max(next, now + 1000));
  }

  async webSocketClose(ws, code, reason) {
    if (ws.deserializeAttachment()?.stage === "ready") {
      // The phone went away: answer waiting agents right away instead of after the timeout.
      for (const id of [...this.pending.keys()]) this.settle(id, json(503, { error: "phone offline" }));
    }
    try {
      ws.close(code >= 1000 && code < 5000 && ![1004, 1005, 1006, 1015].includes(code) ? code : 1000, reason);
    } catch {
      // already closed
    }
  }

  async webSocketError(ws) {
    try {
      ws.close(1011, "error");
    } catch {
      // already closed
    }
  }
}
