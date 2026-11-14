# Zalard Security Remediation — Version 3 status

Honest status of the V3 review items. Rule followed: **nothing is marked done unless its implementation
and a test demonstrate it.** "Verified" means a test in this repo passes; "unverified" means the code is
written but the environment to prove it (a Mac, a Windows box, a deployed Worker) wasn't available.

## Priority status

| # | Item | Status | Evidence / why |
|---|------|--------|----------------|
| 1 | Replace custom XOR cipher with vetted AEAD | **Done (Linux-verified)** | libsodium **XChaCha20-Poly1305**, versioned header + unique random nonce (`tools/zcrypto.py`, `seal.py`, `native/zalard_loader.cpp`). Authenticates before accepting plaintext. `tools/test_crypto.py` (11 cases) + native cross-check pass. Win/mac stubs need libsodium on the build box — **not** verified here. |
| 1 | No custom crypto | **Done** | Hand-rolled SHA-256 + XOR keystream removed from the stub; it now calls libsodium for both AEAD and the hash pin. |
| 2 | Device binding | **Done** | HWID bound on first login, other devices rejected (`worker_test.mjs` proves it). |
| 2 | Remove fixed HWID fallback | **Done** | `Auth.hwid()` now throws instead of returning the shared `"0000…"` constant. |
| 2 | Periodic authorization + session revocation | **Done (tested)** | `/heartbeat` re-checks ban state; a ban takes effect within ~90s mid-run (`startHeartbeat` + `ZalardMod` wiring). `worker_test.mjs` proves ban → revoked. |
| 2 | Account recovery, versioned per-user entitlements | **Deferred** | Larger design (email/recovery flow, per-user signed entitlements). Not started — see Limitations. |
| 2 | Document that extracted plaintext can't be revoked | **Done** | Stated below and in `auth-server/ARCHITECTURE.md`. |
| 3 | CI uses the intended release key; never silently generate one | **Done** | `.github/workflows/build.yml` loads `tools/.release_key` from secret `ZALARD_PAYLOAD_KEY`, **fails** if unset/!64-hex. `gen_guard` reuses it (doesn't rotate prod). |
| 3 | Fail builds on missing secrets/artifacts; validate release key | **Done** | "Require release key" + "Verify release artifacts" steps fail on missing key, missing stub/payload, non-`ZLD1` payload, or leftover plaintext. |
| 3 | Separate dev/prod | **Done** | This workflow is prod-only (requires the secret, seals). Dev builds are local + unsealed. |
| 4 | Remove `-fno-stack-protector` | **Rejected for the payload, applied to the stub** | The payload is `-ffreestanding -nostdlib`; it has no `__stack_chk_*` runtime, so removing the flag breaks the link. Stack protection (`-fstack-protector-strong`) **is** now on the CRT-linked stub. |
| 4 | Improve JNI registration failure handling | **Done (prev turn)** | `JNI_OnLoad` returns `JNI_ERR` on any failed `RegisterNatives`/`FindClass`; full native build compiles. |
| 4 | Audit native deps / automated tests | **Done** | Stub's only crypto dep is libsodium (vetted). `test_crypto.py` + `worker_test.mjs` are the automated suite. |
| 5 | Verified Linux x64 build | **Done** | Full `build_dll.sh linux` builds payload + libsodium stub here. |
| 5 | Verified Windows x64 / macOS ARM64 / universal | **Not verified** | No Windows box, no macOS SDK here. Code targets them; each must be built + tested on its own OS. **Not claiming it works.** |
| 6 | Signed update manifests, publisher sig verify, rollback protection, signing-cred protection, release report | **Deferred** | A whole update-distribution framework; none implemented. Biggest remaining gap — see Limitations. |
| — | "See how many people use my client" | **Done (tested)** | `/heartbeat` + `/admin/stats` → `{registered, activeToday, onlineNow}`. `worker_test.mjs` asserts the counts. |

## Changed files (this round)

- `tools/zcrypto.py` — **new**: libsodium XChaCha20-Poly1305 seal/unseal + format.
- `tools/test_crypto.py` — **new**: crypto negative tests.
- `tools/seal.py` — uses `zcrypto` instead of the XOR keystream.
- `native/zalard_loader.cpp` — libsodium AEAD decrypt + hash pin; custom SHA-256/XOR removed; key passed in.
- `tools/build_dll.sh` — links libsodium into the stub; `-fstack-protector-strong` on the stub.
- `src/main/java/dev/zalard/nativegui/ZalardNative.java` — encrypted payload decrypts via stub only (no Java in-heap fallback; Java can't do XChaCha); fail-closed.
- `src/main/java/dev/zalard/auth/Auth.java` — removed fixed HWID fallback; added `heartbeat()` / `startHeartbeat()`.
- `src/main/java/dev/zalard/adapter/ZalardMod.java` — starts the heartbeat; disables the client on revoke.
- `auth-server/worker.js` — `/heartbeat`, `/admin/stats`, usage counters (registered / DAU / online).
- `auth-server/worker_test.mjs` — **new**: mock-KV end-to-end auth + telemetry + revocation tests.
- `.github/workflows/build.yml` — prod pipeline: secret-sourced key, test gate, artifact validation.

(Prior rounds also added `auth-server/` and the server-held-key refactor; see CHANGES.md.)

## Unresolved security limitations

1. **Extracted plaintext cannot be remotely revoked.** Once an authenticated user decrypts the payload on
   their own machine, they can copy the plaintext library and run/share it with no server. Banning the
   account stops *future* key fetches and kills live sessions via heartbeat, but cannot un-leak an already
   decrypted binary. Mitigation is **per-user watermarking** (trace a leak to an account) + ban — not yet
   implemented.
2. **Windows and macOS builds are unverified here.** Only Linux x64 was built and tested. The Windows/macOS
   stubs need libsodium for those platforms; the macOS anti-debug path is `TODO(mac)` and fail-safe but
   not runtime-tested.
3. **No signed updates / rollback protection (Priority 6).** There's no update manifest signing, no
   publisher-signature verification, no rollback protection. An attacker who can place files where the
   client loads them is out of scope of the current design.
4. **HWID is spoofable.** Device binding deters casual account sharing and enables revocation; a determined
   user can spoof the HWID. It is not a strong identity.
5. **Account recovery / per-user entitlements not built.** A lost password currently needs admin action;
   there's no self-service recovery and no per-user signed entitlement format.
6. **Client-side is cost-raising, not a wall.** Unchanged truth: the binary runs on the attacker's machine,
   so obfuscation (O-MVLL) raises cost but can't make extraction impossible.

## Build verification matrix

| Platform | Built here | Tested here |
|----------|-----------|-------------|
| Linux x64 | ✅ payload + libsodium stub | ✅ native decrypt + tamper/wrong-key rejection |
| Windows x64 | ❌ (no Windows box) | ❌ |
| macOS ARM64 | ❌ (no macOS SDK) | ❌ |
| Universal mac | ❌ | ❌ |

To finish Priority 5, build + run `tools/test_crypto.py`'s native cross-check on each OS natively (the CI
`build` job is the place to add a `windows-latest` / `macos-latest` matrix once libsodium is wired there).
