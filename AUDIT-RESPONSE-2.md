# Audit response — round 2

Continues from AUDIT-RESPONSE.md. Evidence-based; nothing claimed without a test or static proof. The one
item I cannot satisfy from this environment (green Windows/macOS CI) is called out, not faked.

## 1. Connect Updater to the production startup flow  ✅ DONE (real call site)
- `Updater.startBackgroundCheck(Path)` is now called from `ZalardMod.onInitializeClient()` — a real call
  site, not a dangling API. It runs in a daemon thread, checks for a newer **signed** release over **HTTPS**
  (`HttpFetcher` enforces `https://`), verifies before installing, and stages the new version under
  `<gamedir>/mako/versions/<ver>/` for the next launch.
- Fail-safe: a no-op if auto-update isn't configured; all errors are swallowed so an update check can never
  block startup. The install/verify logic is fully tested (below). *Honest note: the call site itself is in
  `ZalardMod`, which needs the Minecraft/Fabric classpath to compile — verified in the full build, not in
  this Java-only environment. The `Updater`/`UpdateVerifier` logic it calls is compiled and tested here.*

## 2. Tests for the production update integration  ✅ DONE (tested)
Added to `UpdateVerifierTest` (via the `Updater` download path), each asserting the **active version is
unchanged** on failure: failed download (404), corrupted file (hash mismatch), missing manifest, and an
over-the-wire invalid signature. Update suite is now **25 cases, all pass**.

## 3. Review activate() — no silent non-atomic fallback  ✅ FIXED
`activate()` creates the temp pointer **in the versions dir** (same filesystem as `current`) and commits
with `ATOMIC_MOVE`. If the filesystem genuinely can't do an atomic rename it now **throws**
(`AtomicMoveNotSupportedException` → IOException) and leaves the existing pointer untouched — it never
silently does a non-atomic replace while claiming atomic activation. Tests still pass (the interrupted-
install and recovery cases exercise it).

## 4. Verify the release workflow end-to-end  ⚠️ STATICALLY VERIFIED (can't run Actions here)
I cannot execute GitHub Actions from this environment, so this is a static + logic proof, not a live run:
- **Key is in no artifact:** parsed every `upload-artifact` step — `prepare` uploads guard headers, `native`
  uploads natives, `release` uploads the jar; none include `.release_key`/`.release_signing_key`.
- **Key only in the release job:** `.release_key` appears only as written-from-secret (`printf … > tools/.release_key`),
  checked by the src + jar audits, and scrubbed (`rm -f`) in an `if: always()` step. Never `echo`/`cat`-ed.
  `gen_guard.py --quiet` suppresses the key print.
- **Jar audit works:** proved by running the exact audit logic — it REJECTS a jar containing a planted
  `.release_key` or a plaintext `zalard_gui.dll`, and passes a clean sealed (`ZLD1`) jar.
A genuine green run is still yours to produce; the key-handling is proven safe and the gate proven functional.

## 5. AUTH_BASE + PUBLISHER_KEY as explicit release requirements  ✅ ENFORCED (both gates fire)
Neither value is invented. Both fail closed and are release-gated — verified here:
- `AuthConfigTest --release` → exit 1 while `Auth.AUTH_BASE` is the placeholder.
- CI greps `PUBLISHER_KEY = ""` → fails the release while it's empty.
See "Manual configuration" for exactly how to set them.

## 6. Green Windows/macOS CI  ❌ CANNOT DO FROM HERE — marked UNVERIFIED
I have no Windows/macOS environment and cannot trigger GitHub Actions, so I cannot produce green Win/macOS
builds. They remain **unverified**; the workflow legs are written and the Linux leg is green here. You must
run the workflow on GitHub and share any red logs — I'll fix them. I am not marking these platforms verified.

## 7. Full test suite + artifact inspection  ✅ (results below)

---

## Actual test results (run here, Linux)
```
[crypto]   all crypto tests passed          (11)
[signing]  all signing unit tests passed    (7)
[worker]   all worker tests passed          (20)
[authcfg]  all auth-config tests passed     (9)   + both release gates block on placeholder (exit 1)
[update]   all update-verifier tests passed (25)  incl. failed download / corrupted file / missing manifest
                                                   / invalid sig over the wire / interrupted install + recovery
[yaml] valid   [py tools] OK
```
Artifact inspection: delivered zip contains **no** `.release_key`/`.release_signing_key`/`zalard-auth.properties`,
no plaintext native libs, and no stale `.bin` (the old-format binaries were removed last round).

## Modified files (this round)
- `src/main/java/dev/zalard/update/Updater.java` — `UPDATE_BASE` constant, `updatesEnabled()`, `startBackgroundCheck()`.
- `src/main/java/dev/zalard/update/UpdateVerifier.java` — `activate()` fails loudly instead of silent non-atomic.
- `src/main/java/dev/zalard/adapter/ZalardMod.java` — calls `startBackgroundCheck()` at startup.
- `src/test/java/dev/zalard/update/UpdateVerifierTest.java` — production failure-mode tests (now 25 cases).

## Remaining manual configuration (production fails closed without these)
1. **`Auth.AUTH_BASE`** (src/main/java/dev/zalard/auth/Auth.java) — your real **https** auth Worker URL.
2. **`UpdateVerifier.PUBLISHER_KEY`** (src/main/java/dev/zalard/update/UpdateVerifier.java) — run
   `python3 tools/keygen_release.py` and paste the printed **public** key. Private key stays in
   `tools/.release_signing_key` (gitignored).
3. **`Updater.UPDATE_BASE`** (src/main/java/dev/zalard/update/Updater.java) — your **https** update host, OR
   leave it as the placeholder to disable auto-update entirely (the client still runs).
4. CI secret **`ZALARD_PAYLOAD_KEY`** = the auth server's `PAYLOAD_KEY` (never logged or uploaded).
5. Deploy the auth Worker (`auth-server/README.md`); set its `PAYLOAD_KEY` + `ADMIN_KEY`.
6. libsodium on every build host (static-link for release).

## Remaining security limitations
- **Windows/macOS unverified** (no env here). - **Extracted plaintext can't be remotely revoked** (needs
  watermarking). - **KV not atomic** (needs D1/Durable Objects). - **HWID spoofable**; client-side is
  cost-raising, not a wall.

## Is the pipeline safe to use?
**Linux: yes, with the manual config above** — key never exposed (statically proven), authenticated-
encryption payload, signed/rollback-protected/atomic updates, artifact + jar audits, production fails closed
when unconfigured. **Windows/macOS: not yet proven** — run the CI matrix; don't ship those platforms until it's green.
