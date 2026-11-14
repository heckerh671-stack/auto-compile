# Zalard Security Remediation — Version 4 status

Same rule as V3: **nothing marked done unless a test in this repo demonstrates it.** "Verified" = a test
here passes. "Implemented, unverified" = written correctly but needs an environment I don't have (Windows,
macOS, a deployed Worker) to prove.

## Priority status

| # | Item | Status | Evidence / why |
|---|------|--------|----------------|
| 1 | Check `sodium_init()` failures; verify library at startup | **Done** | `sodiumReady()` caches `sodium_init()`, treats `<0` as fail-closed; `JNI_OnLoad` + every decrypt call it. Linux build + selftest pass. |
| 1 | Reliable libsodium across platforms / static builds | **Partial** | `build_dll.sh` takes `SODIUM=` (static link, e.g. `-l:libsodium.a`); CI installs it per-OS and has a "Verify libsodium present" step. Static link **verified on Linux only**. |
| 1 | Dependency verification in CI | **Done (written)** | `native` job checks libsodium exists before building. Runs on GitHub; non-Linux legs unexecuted here. |
| 2 | Independent Windows/Linux/macOS builds; per-platform loader+payload | **Implemented, unverified (non-Linux)** | `build.yml` `native` matrix builds each OS's payload+loader from one shared guard; `release` seals all + builds the jar. **Linux leg works; Windows/macOS not run here.** Not claiming they pass. |
| 2 | Verify payload hashes / build compatibility | **Done (Linux)** | `seal.py` pins per-OS SHA-256; CI `Verify release artifacts` checks the `ZLD1` format + no plaintext. |
| 3 | Secure session renewal; fix 1h expiry | **Done (tested)** | Sliding-window sessions (`SESSION_SLIDING` renewed by heartbeat, `SESSION_MAX` absolute cap). `worker_test.mjs` proves expiry→renew and age-out→renew. |
| 3 | Heartbeat auth; network vs revocation; long-session/expired-token tests | **Done (tested)** | `Auth.Beat {OK,RENEW,REVOKED,NETWORK}`: a network blip is survived, only a real ban disables. Tests cover renew, aged-out, ban→revoked, expired token. |
| 4 | Replace placeholder URL; secure prod endpoint; no accidental override; validate TLS; fail safe | **Done (tested)** | `Auth.validateBase`: prod uses the compiled URL only (ignores stray overrides), requires https, fails closed on the placeholder. `AuthConfigTest` (9 cases) passes. |
| 5 | Admin endpoint protection + audit logging; per-account rate limit; registration race | **Done (tested, with caveat)** | Admin paths: separate rate limit + constant-time key + audit. Audit log on all security events. Per-IP **and** per-account login limits. Best-effort registration-race guard (`worker_test.mjs` concurrent test passes). |
| 5 | Replace race-prone KV counters with atomic ops | **Partial / documented** | Cloudflare KV has **no atomic ops** and is eventually consistent — counters here are best-effort. The correct fix (D1 `UNIQUE` / Durable Object) is documented in `worker.js`; not migrated. |
| 6 | `sodium_init` return check; JNI failures; memory/error review | **Done** | sodium check added; JNI hard-fail (prior round); decrypt paths wipe+free on every exit. |
| 6 | Windows Unicode path handling | **Implemented, unverified** | `zlWrite` now uses `MultiByteToWideChar(CP_UTF8)` + `CreateFileW`. Standard Win32; **not compiled here** (no Windows SDK). |
| 7 | Automated tests | **Mostly done** | `test_crypto.py` (keys/ciphertext/corrupt/malformed), `worker_test.mjs` (auth fail, expiry, renewal, revocation, concurrent reg, rate limit), `AuthConfigTest` (prod config). Gaps: missing-runtime-dep + platform-loading tests need the target OS. |
| 8 | Signed updates (manifests, publisher sig, SHA-256, rollback, cred protection, interrupted recovery) | **Done (Linux-verified)** | Ed25519-signed manifests (`tools/sign_release.py` via libsodium) verified client-side with the JDK's built-in Ed25519 (`UpdateVerifier`, no native dep). Private key never ships. `test_sign.py` (7) + `UpdateVerifierTest` (11) pass: signature, per-file SHA-256, rollback, path-traversal, atomic install + recovery. Transport layer not included — see `SIGNED-UPDATES.md`. |

## Changed files (this round)

- `auth-server/worker.js` — sliding-window sessions + renew/revoke; audit log; admin rate-limit; per-account login limit; best-effort registration-race guard.
- `src/main/java/dev/zalard/auth/Auth.java` — `validateBase` (prod/dev endpoint rules, https, fail-safe); `Beat` outcomes; sliding re-login; response sanity bounds.
- `src/test/java/dev/zalard/auth/AuthConfigTest.java` — **new**: production-config tests.
- `auth-server/worker_test.mjs` — expanded: session lifecycle, concurrency, rate limiting.
- `native/zalard_loader.cpp` — `sodiumReady()` init check; Windows `CreateFileW` Unicode path.
- `tools/build_dll.sh` — `SODIUM=` link var (static libsodium); `-fstack-protector-strong` on the stub (prior).
- `.github/workflows/build.yml` — cross-platform matrix (prepare → native{linux,win,mac} → seal → jar) with per-platform libsodium + dependency checks.

## Remaining security limitations

1. **Extracted plaintext can't be remotely revoked** (unchanged). Fix = per-user watermarking + ban; not built.
2. **Signed-update transport not included (Priority 8 core done).** Manifest signing, signature + hash
   verification, rollback protection and atomic-recovery install are implemented and tested; the download
   layer (fetch manifest + files into a staging dir) and the swap-at-next-launch wiring are the thin
   remaining piece. See `SIGNED-UPDATES.md`.
3. **KV is not atomic.** Registration-race guard and counters are best-effort; a determined simultaneous
   double-register could still slip through. Real fix: D1 (SQLite `UNIQUE`) or a Durable Object.
4. **Windows/macOS unverified here.** Native Unicode path, static libsodium linking, and the CI matrix's
   non-Linux legs are written but not executed. Per Priority 2, treat Win/macOS as "pending first green CI run."
5. **HWID is spoofable** (unchanged) — device binding deters casual sharing, not a determined attacker.

## Known bugs / compatibility issues

- **Committed `natives/*.bin` are stale.** The repo's placeholder payloads are from an older format/key and
  will NOT decrypt with the current code — they're regenerated by the release pipeline (`gen_guard → build_dll
  → seal`). A bare `gradle build` without that pipeline produces a non-working jar. (By design; the CI does it.)
- **libsodium is a new build dependency.** Every build host (and the CI runners) must have libsodium; release
  builds should static-link it so end users need nothing. Only the Linux static link is verified.
- **macOS anti-debug** (`obf.h`) remains `TODO(mac)` — fail-safe but not runtime-tested on a Mac.
- **Heartbeat re-login** re-reads credentials from config each renewal; if the user deletes
  `zalard-auth.properties` mid-session, a later age-out renewal will fail (client disables). Expected.

## Build verification matrix

| Platform | Built here | Tested here |
|----------|-----------|-------------|
| Linux x64 | ✅ payload + libsodium stub | ✅ native AEAD decrypt + tamper/wrong-key rejection |
| Windows x64 | ❌ (no Windows SDK) | ❌ — CI leg written, not run |
| macOS ARM64 | ❌ (no macOS SDK) | ❌ — CI leg written, not run |

The CI `native` matrix is the mechanism to fill the ❌ rows: it must pass on `windows-latest` and
`macos-latest` before Windows/macOS support is claimed.
