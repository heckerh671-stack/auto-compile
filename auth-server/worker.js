/**
 * Zalard auth server — Cloudflare Worker.
 *
 * Usage is FREE: anyone can register. The account exists so the server has an IDENTITY it can refuse or
 * revoke — the payload key is handed out only to a logged-in, non-banned account on a bound machine, and
 * a leaked build is cut off by banning that account. The jar ships ONLY ciphertext; this server holds the
 * key (Worker secret PAYLOAD_KEY), so unzipping the jar yields nothing usable.
 *
 * Endpoints (JSON POST):
 *   /register {username, password}        -> create a free account
 *   /login    {username, password, hwid}  -> bind/verify machine, return a session token (sliding window)
 *   /key      {token, hwid}               -> (auth + not-banned + hwid) return the payload key
 *   /heartbeat{token, hwid}               -> re-authorize + renew the session; {ok} | {renew} | {revoked}
 *   /admin/ban   {admin, username, banned} /admin/reset {admin, username} /admin/stats {admin}
 *
 * Sessions use a SLIDING window: a token lives SESSION_SLIDING seconds and each heartbeat renews it, up to
 * an absolute SESSION_MAX. So an active client never hits the old hard 1h cutoff; an abandoned one dies in
 * SESSION_SLIDING. Past SESSION_MAX the client silently re-logs-in (renew), which is NOT a revocation.
 *
 * Storage: one KV namespace (binding `KV`). Secrets: PAYLOAD_KEY, ADMIN_KEY. See wrangler.toml / README.md.
 * KV is eventually-consistent with no atomic ops — see the registration-race note in /register; counters
 * here are best-effort. For strict atomicity migrate accounts to D1 (SQLite UNIQUE) or a Durable Object.
 *
 * Honest limit: an authenticated user can still capture the key at runtime and share the *decrypted*
 * payload. Fight that with per-user watermarking + revocation, not by hiding the key better.
 */

const JSON_HEADERS = { "content-type": "application/json", "access-control-allow-origin": "*" };
const ok = (obj) => new Response(JSON.stringify(obj), { status: 200, headers: JSON_HEADERS });
const err = (status, msg) => new Response(JSON.stringify({ error: msg }), { status, headers: JSON_HEADERS });

const enc = new TextEncoder();
const toHex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
function randHex(n) {
  const b = new Uint8Array(n);
  crypto.getRandomValues(b);
  return toHex(b);
}

// PBKDF2-SHA256 password hashing (Web Crypto). 120k iterations.
async function hashPw(password, saltHex) {
  const key = await crypto.subtle.importKey("raw", enc.encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", hash: "SHA-256", salt: hexToBytes(saltHex), iterations: 100000 }, key, 256);  // Workers Web Crypto caps PBKDF2 at 100k
  return toHex(bits);
}
function hexToBytes(hex) {
  const a = new Uint8Array(hex.length / 2);
  for (let i = 0; i < a.length; i++) a[i] = parseInt(hex.substr(i * 2, 2), 16);
  return a;
}
// constant-time-ish string compare (same length required)
function eq(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}

// Audit log: structured line to the Worker log (view with `wrangler tail` or the dashboard). No secrets.
function audit(event, detail) {
  try { console.log("AUDIT " + JSON.stringify({ t: new Date().toISOString(), event, ...detail })); } catch (e) {}
}

// Fixed-window rate limit backed by KV. Best-effort (KV is not atomic); fine for abuse-slowing.
async function rateLimited(env, key, limit, windowSec) {
  const bucket = `rl:${key}:${Math.floor(Date.now() / 1000 / windowSec)}`;
  const n = parseInt((await env.KV.get(bucket)) || "0", 10) + 1;
  await env.KV.put(bucket, String(n), { expirationTtl: windowSec + 5 });
  return n > limit;
}

// --- usage telemetry (so the owner can see how many people use the client) ---
function today() { return new Date().toISOString().slice(0, 10); }
async function markActive(env, user) {
  const d = today();
  const seen = `seen:${d}:${user}`;
  if (!(await env.KV.get(seen))) {
    await env.KV.put(seen, "1", { expirationTtl: 90000 });
    const n = parseInt((await env.KV.get(`dau:${d}`)) || "0", 10) + 1;
    await env.KV.put(`dau:${d}`, String(n), { expirationTtl: 90000 });
  }
  await env.KV.put(`online:${user}`, String(Date.now()), { expirationTtl: 150 });
}

const USERNAME_RE = /^[A-Za-z0-9_.-]{3,32}$/;
const HWID_RE = /^[a-f0-9]{16,128}$/;
const SESSION_SLIDING = 900;    // 15 min; each heartbeat renews it
const SESSION_MAX = 86400;      // 24 h absolute cap -> client re-logs-in (renew, not revoke)

async function newSession(env, username, hwid) {
  const token = randHex(32);
  const now = Date.now();
  await env.KV.put(`sess:${token}`, JSON.stringify({ username, hwid, born: now }),
    { expirationTtl: SESSION_SLIDING });
  return token;
}

function adminOk(body, env) { return eq(body.admin || "", env.ADMIN_KEY || "\0"); }

async function handle(request, env) {
  if (request.method === "OPTIONS") return new Response(null, { headers: JSON_HEADERS });
  if (request.method !== "POST") return err(405, "POST only");
  const url = new URL(request.url);
  let body;
  try { body = await request.json(); } catch { return err(400, "bad json"); }
  if (!body || typeof body !== "object") return err(400, "bad body");
  const ip = request.headers.get("cf-connecting-ip") || "0";

  // Admin endpoints: separate rate limit + audit, constant-time key check.
  if (url.pathname.startsWith("/admin/")) {
    if (await rateLimited(env, `admin:${ip}`, 30, 60)) return err(429, "slow down");
    if (!adminOk(body, env)) { audit("admin_denied", { ip, path: url.pathname }); return err(403, "nope"); }
  }

  switch (url.pathname) {
    case "/register": {
      if (await rateLimited(env, `reg:${ip}`, 5, 3600)) return err(429, "slow down");
      const { username, password } = body;
      if (!USERNAME_RE.test(username || "")) return err(400, "username must be 3-32 chars [A-Za-z0-9_.-]");
      if (typeof password !== "string" || password.length < 8) return err(400, "password min 8 chars");
      if (await env.KV.get(`user:${username}`)) return err(409, "username taken");
      // Best-effort race guard: claim the name with a unique nonce, then confirm we own the claim before
      // writing the account. KV is eventually-consistent so this NARROWS but cannot fully close a
      // simultaneous double-register; the real fix is D1 (UNIQUE) or a Durable Object (see header).
      const nonce = randHex(8);
      const lockKey = `reglock:${username}`;
      const existingLock = await env.KV.get(lockKey);
      if (existingLock && existingLock !== nonce) return err(409, "registration in progress");
      await env.KV.put(lockKey, nonce, { expirationTtl: 60 });   // KV minimum expirationTtl is 60s
      if ((await env.KV.get(lockKey)) !== nonce) return err(409, "registration in progress");
      if (await env.KV.get(`user:${username}`)) return err(409, "username taken");
      const salt = randHex(16);
      const hash = await hashPw(password, salt);
      await env.KV.put(`user:${username}`, JSON.stringify({ salt, hash, hwid: null, banned: false, created: Date.now() }));
      const total = parseInt((await env.KV.get("stat:users")) || "0", 10) + 1;
      await env.KV.put("stat:users", String(total));
      audit("register", { username, ip });
      return ok({ ok: true });
    }

    case "/login": {
      const { username, password, hwid } = body;
      if (!USERNAME_RE.test(username || "") || !HWID_RE.test(hwid || "")) return err(400, "bad params");
      // Rate-limit per IP AND per account (slows credential stuffing against one username).
      if (await rateLimited(env, `login:${ip}`, 20, 600)) return err(429, "slow down");
      if (await rateLimited(env, `loginacct:${username}`, 10, 600)) return err(429, "slow down");
      const raw = await env.KV.get(`user:${username}`);
      if (!raw) { audit("login_fail", { username, ip, why: "nouser" }); return err(401, "invalid login"); }
      const u = JSON.parse(raw);
      if (u.banned) { audit("login_fail", { username, ip, why: "banned" }); return err(403, "account banned"); }
      if (!eq(await hashPw(password, u.salt), u.hash)) { audit("login_fail", { username, ip, why: "badpw" }); return err(401, "invalid login"); }
      if (u.hwid && !eq(u.hwid, hwid)) { audit("login_fail", { username, ip, why: "device" }); return err(403, "account locked to another device"); }
      if (!u.hwid) { u.hwid = hwid; await env.KV.put(`user:${username}`, JSON.stringify(u)); }
      const token = await newSession(env, username, hwid);
      await markActive(env, username);
      audit("login", { username, ip });
      return ok({ token, ttl: SESSION_SLIDING, maxAge: SESSION_MAX });
    }

    case "/key": {
      const { token, hwid } = body;
      if (typeof token !== "string" || !HWID_RE.test(hwid || "")) return err(400, "bad params");
      if (await rateLimited(env, `key:${hwid}`, 30, 600)) return err(429, "slow down");
      const sraw = await env.KV.get(`sess:${token}`);
      if (!sraw) return err(401, "expired; log in again");
      const s = JSON.parse(sraw);
      if (!eq(s.hwid, hwid)) return err(403, "device mismatch");
      const uraw = await env.KV.get(`user:${s.username}`);
      if (!uraw) return err(401, "no account");
      if (JSON.parse(uraw).banned) { audit("key_denied", { username: s.username, why: "banned" }); return err(403, "account banned"); }
      if (!env.PAYLOAD_KEY) return err(500, "server missing PAYLOAD_KEY");
      audit("key_issued", { username: s.username });
      return ok({ key: env.PAYLOAD_KEY });
    }

    case "/heartbeat": {
      // Re-authorization + sliding-window renewal. Three outcomes the client must distinguish:
      //   {ok}      -> session renewed, carry on
      //   {renew}   -> session expired or hit the absolute max age; re-login (NOT a revocation)
      //   {revoked} -> account banned or device mismatch; STOP
      const { token, hwid } = body;
      if (typeof token !== "string" || !HWID_RE.test(hwid || "")) return err(400, "bad params");
      const sraw = await env.KV.get(`sess:${token}`);
      if (!sraw) return ok({ renew: true, reason: "session expired" });
      const s = JSON.parse(sraw);
      if (!eq(s.hwid, hwid)) return ok({ revoked: true, reason: "device mismatch" });
      const uraw = await env.KV.get(`user:${s.username}`);
      if (!uraw || JSON.parse(uraw).banned) { audit("revoked", { username: s.username }); return ok({ revoked: true, reason: "account banned" }); }
      if (Date.now() - s.born >= SESSION_MAX * 1000) {
        await env.KV.delete(`sess:${token}`);   // let it lapse (KV min TTL is 60s, so remove outright)
        return ok({ renew: true, reason: "max session age" });
      }
      // renew the sliding window
      await env.KV.put(`sess:${token}`, sraw, { expirationTtl: SESSION_SLIDING });
      await markActive(env, s.username);
      return ok({ ok: true });
    }

    case "/admin/ban": {
      const raw = await env.KV.get(`user:${body.username}`);
      if (!raw) return err(404, "no such user");
      const u = JSON.parse(raw);
      u.banned = body.banned !== false;
      await env.KV.put(`user:${body.username}`, JSON.stringify(u));
      audit("admin_ban", { username: body.username, banned: u.banned });
      return ok({ ok: true, username: body.username, banned: u.banned });
    }

    case "/admin/reset": {
      const raw = await env.KV.get(`user:${body.username}`);
      if (!raw) return err(404, "no such user");
      const u = JSON.parse(raw);
      u.hwid = null;
      await env.KV.put(`user:${body.username}`, JSON.stringify(u));
      audit("admin_reset", { username: body.username });
      return ok({ ok: true, username: body.username, hwid: null });
    }

    case "/admin/stats": {
      const registered = parseInt((await env.KV.get("stat:users")) || "0", 10);
      const activeToday = parseInt((await env.KV.get(`dau:${today()}`)) || "0", 10);
      let onlineNow = 0, cursor;
      for (;;) {
        const r = await env.KV.list({ prefix: "online:", cursor });
        onlineNow += r.keys.length;
        if (r.list_complete) break;
        cursor = r.cursor;
      }
      return ok({ registered, activeToday, onlineNow });
    }

    default:
      return err(404, "not found");
  }
}

export default {
  async fetch(request, env) {
    try { return await handle(request, env); }
    catch (e) { audit("server_error", { msg: String(e && e.message || e) }); return err(500, "server error"); }
  },
};
