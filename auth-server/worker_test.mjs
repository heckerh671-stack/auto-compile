// Mock-KV end-to-end tests for the Zalard auth Worker. Run: node auth-server/worker_test.mjs (exit 0 = pass).
// Covers: auth failures, session expiry/renewal/revocation, admin protection, telemetry, concurrent
// registration, and rate limiting. NOTE: the mock KV is a synchronous Map; it exercises the logic and
// node's async interleaving, but it is NOT Cloudflare's distributed eventual-consistency model — passing
// here does not by itself prove behaviour on real KV (see the registration-race note in worker.js).
import worker from "./worker.js";

const store = new Map();
const KV = {
  async get(k) { return store.has(k) ? store.get(k) : null; },
  async put(k, v) { store.set(k, v); },
  async delete(k) { store.delete(k); },
  async list({ prefix = "", cursor } = {}) {
    return { keys: [...store.keys()].filter(k => k.startsWith(prefix)).map(name => ({ name })), list_complete: true };
  },
};
const env = { KV, PAYLOAD_KEY: "aa".repeat(32), ADMIN_KEY: "secret-admin" };
const call = (path, body) =>
  worker.fetch(new Request("https://x" + path, { method: "POST", body: JSON.stringify(body) }), env)
    .then(r => r.json().then(j => ({ status: r.status, j })));
const HWID = "a".repeat(64);
let fails = 0;
const want = (name, cond) => { console.log((cond ? "PASS " : "FAIL ") + name); if (!cond) fails++; };

async function main() {
  // --- registration + auth failures
  want("register alice", (await call("/register", { username: "alice", password: "hunter2hunter" })).j.ok);
  want("duplicate register rejected", (await call("/register", { username: "alice", password: "hunter2hunter" })).status === 409);
  want("short password rejected", (await call("/register", { username: "bob", password: "x" })).status === 400);
  const login = await call("/login", { username: "alice", password: "hunter2hunter", hwid: HWID });
  want("login returns token", login.status === 200 && !!login.j.token);
  const token = login.j.token;
  want("wrong password rejected", (await call("/login", { username: "alice", password: "nope-nope-nope", hwid: HWID })).status === 401);
  want("second device rejected (HWID bind)", (await call("/login", { username: "alice", password: "hunter2hunter", hwid: "b".repeat(64) })).status === 403);

  // --- key + heartbeat
  want("key returned to valid session", (await call("/key", { token, hwid: HWID })).j.key === env.PAYLOAD_KEY);
  want("heartbeat ok", (await call("/heartbeat", { token, hwid: HWID })).j.ok === true);

  // --- P3: session expiry -> RENEW (not revoked), so the client re-logs-in rather than disabling
  store.delete(`sess:${token}`);
  const expired = await call("/heartbeat", { token, hwid: HWID });
  want("expired session -> renew (not revoked)", expired.j.renew === true && !expired.j.revoked);
  // re-login works and gives a fresh token
  const relog = await call("/login", { username: "alice", password: "hunter2hunter", hwid: HWID });
  want("re-login after expiry issues a new token", relog.status === 200 && !!relog.j.token && relog.j.token !== token);
  const token2 = relog.j.token;

  // --- P3: absolute max-age -> RENEW
  const sraw = JSON.parse(store.get(`sess:${token2}`));
  sraw.born = Date.now() - 25 * 3600 * 1000;            // older than SESSION_MAX (24h)
  store.set(`sess:${token2}`, JSON.stringify(sraw));
  want("aged-out session -> renew", (await call("/heartbeat", { token: token2, hwid: HWID })).j.renew === true);

  // --- P3: ban -> REVOKED (stop), distinct from renew
  const relog2 = await call("/login", { username: "alice", password: "hunter2hunter", hwid: HWID });
  const token3 = relog2.j.token;
  want("ban alice", (await call("/admin/ban", { admin: "secret-admin", username: "alice", banned: true })).j.banned === true);
  const afterBan = await call("/heartbeat", { token: token3, hwid: HWID });
  want("banned session -> revoked (not renew)", afterBan.j.revoked === true && !afterBan.j.renew);
  want("key after ban -> rejected", (await call("/key", { token: token3, hwid: HWID })).status === 403);
  await call("/admin/ban", { admin: "secret-admin", username: "alice", banned: false });   // unban for later

  // --- telemetry + admin protection
  const stats = await call("/admin/stats", { admin: "secret-admin" });
  want("stats report registered/active/online", typeof stats.j.registered === "number" && typeof stats.j.activeToday === "number" && typeof stats.j.onlineNow === "number");
  want("stats rejects bad admin", (await call("/admin/stats", { admin: "nope" })).status === 403);
  want("ban rejects bad admin", (await call("/admin/ban", { admin: "nope", username: "alice", banned: true })).status === 403);

  // --- P5: concurrent registration of the same new name -> at most one succeeds
  const [r1, r2] = await Promise.all([
    call("/register", { username: "charlie", password: "goodpassword1" }),
    call("/register", { username: "charlie", password: "goodpassword1" }),
  ]);
  const ok200 = [r1, r2].filter(r => r.status === 200).length;
  want("concurrent register of same name -> exactly one succeeds", ok200 === 1);

  // --- P5: rate limiting kicks in on repeated failed logins for one account
  let saw429 = false;
  for (let i = 0; i < 14; i++) {
    const r = await call("/login", { username: "alice", password: "wrong-wrong-wrong", hwid: HWID });
    if (r.status === 429) { saw429 = true; break; }
  }
  want("repeated bad logins eventually rate-limited (429)", saw429);

  console.log();
  if (fails) { console.log(fails + " test(s) FAILED"); process.exit(1); }
  console.log("all worker tests passed");
}
main();
