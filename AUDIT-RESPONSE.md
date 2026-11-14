# Audit response — fixes for the 8 findings

Evidence-based. Each item says what changed and how it was verified. Items needing environments I don't
have (Windows/macOS CI) are called out, not claimed.

## 1. CRITICAL — payload key was published as a CI artifact  ✅ FIXED
The old workflow uploaded `tools/.release_key` inside the `guard-state` artifact (downloadable, persistent)
and `gen_guard.py` printed the key to the log. Both are closed:
- The guard artifact now publishes **only** `guard_token.h`, `vm_gen.h`, `Z.java` — no key. (The guard
  sources don't contain the key; the payload key is server-side.)
- The key is materialized **only** in the `release` job, from the secret, into a local `tools/.release_key`
  that is scrubbed in an `if: always()` step. It is never an artifact.
- `gen_guard.py --quiet` (used by CI) suppresses the key print; the `prepare` job uses a throwaway key it
  never uploads. Verified: `gen_guard.py --quiet` prints `payload key written … (not shown)`.

## 2. AUTH — placeholder AUTH_BASE  ✅ FIXED (fail-closed, no invented URL)
`Auth.AUTH_BASE` is still the placeholder (I did **not** invent a URL). It is marked with a loud banner as
the single place to set it, and production already fails closed (`validateBase` throws on the placeholder;
the release gate below also blocks). See "Manual configuration".

## 3. AUTH TESTS — test the real constant  ✅ FIXED
`AuthConfigTest` now inspects the actual compiled `Auth.AUTH_BASE`: in normal mode it prints `NOTE … still
the placeholder`; with `--release` it **exits non-zero** if the constant is the placeholder or invalid.
Verified: `AuthConfigTest --release` → `FAIL release gate: Auth.AUTH_BASE is still the placeholder`, exit 1.
The release CI job runs this gate.

## 4. P8 — compiler/linker flag audit  ✅ DONE (Linux-verified)
- **Why `-fno-stack-protector` on the payload:** it is `-ffreestanding -nostdlib`, so there is no
  `__stack_chk_fail`/`__stack_chk_guard` runtime — enabling the stack protector fails to link. It is off
  **only** on the payload; the CRT-linked stub uses `-fstack-protector-strong`.
- **Added, compatible with freestanding** (no runtime symbols): Linux payload+stub now get
  `-fstack-clash-protection -fcf-protection=branch` and linker `-z relro -z now -z noexecstack`; the stub
  also gets `-D_FORTIFY_SOURCE=2`. Windows uses CET branch only (stack-clash would reintroduce a `__chkstk`
  the `-nostdlib` build avoids); macOS uses `-mbranch-protection=standard` (ARM BTI/PAC).
- **Stripping / no debug:** `-O2`, no `-g`; linker strips. Verified on Linux: both libs `stripped`, **0**
  `.debug` sections, `GNU_RELRO` + non-exec `GNU_STACK` present, 186 `endbr64` (CET) in the payload. CI has
  a "Confirm stripped / no debug info" gate.

## 5. SIGNED UPDATES — download + integration  ✅ DONE (core, Linux-verified)
New `dev.zalard.update.Updater`: fetches the manifest + files (abstract `Fetcher`; `HttpFetcher` for prod
with status + 64 MB size caps) and installs. **Verification is mandatory** (always calls
`UpdateVerifier.verifyManifest`/`applyUpdate`) and it is **fail-closed on an empty publisher key**
(`checkAndApply(baseUrl,…)` refuses if `PUBLISHER_KEY` is empty; the release CI gate also fails on it).
Verified by test: downloads+installs a newer release, returns null when current, throws on empty key.

## 6. UPDATE RECOVERY — atomic multi-file install  ✅ DONE (tested)
Re-architected to a versioned model: files stage into `versions/<ver>/`, then a **single atomic rename** of
the `current` pointer commits them. A crash/failure before the flip leaves the old version fully live; there
is no window where a half-set of files is active. Verified by a new **interrupted-install test**: after
`stageVersion` without `activate`, the active version is still the old one; after `activate`, it's the new
one. A tampered download is rejected with the active version untouched (recovery).

## 7. CROSS-PLATFORM — green CI for Linux/Windows/macOS  ⚠️ CANNOT VERIFY HERE
I cannot execute GitHub Actions from this environment, so I **cannot produce green Windows/macOS runs** and
do **not** claim them. What's done: the `native` matrix builds each OS with static libsodium, a dependency
check, and (Linux) a native-decrypt + strip gate. **Linux is verified here; Windows/macOS are "pending first
green run."** You must push and run the workflow; share any red logs and I'll fix them.

## 8. FINAL AUDIT of release artifacts  ✅ DONE
- **Stale native binaries removed.** The committed `natives/*.bin` were the old XOR format (magic ≠ `ZLD1`)
  and wouldn't decrypt with the libsodium stub — deleted. `natives/README.md` explains they're build-only.
- No key/credential files in the tree (`.release_key`, `.release_signing_key`, `zalard-auth.properties`
  absent and gitignored). No plaintext native libs committed.
- CI now **audits the built jar**: fails if it contains a key/credential file, a plaintext payload, or lacks
  a sealed `ZLD1` payload; and audits the resources before packaging.

---

## Every modified file (this round)
- `.github/workflows/build.yml` — key never in artifacts/logs; production-config gates; jar + resource audit; strip/debug gate.
- `tools/gen_guard.py` — `--quiet` (no key in logs); `import sys`.
- `tools/build_dll.sh` — per-target hardening (CET/branch-protection, stack-clash, RELRO/now/noexecstack, FORTIFY on the stub); documented `-fno-stack-protector`.
- `src/main/java/dev/zalard/auth/Auth.java` — AUTH_BASE "set here" banner.
- `src/test/java/dev/zalard/auth/AuthConfigTest.java` — real-constant check + `--release` gate.
- `src/main/java/dev/zalard/update/UpdateVerifier.java` — atomic versioned install (`stageVersion`/`activate`/`activeVersion`), crash-safe.
- `src/main/java/dev/zalard/update/Updater.java` — **new**: download transport, mandatory verify, fail-closed on empty key.
- `src/test/java/dev/zalard/update/UpdateVerifierTest.java` — interrupted-install + Updater tests.
- `src/main/resources/natives/` — removed stale `*.bin`; added `README.md`.

## Every test result (run here, Linux)
```
[crypto]   all crypto tests passed        (11)
[signing]  all signing unit tests passed  (7)
[worker]   all worker tests passed        (20)
[authcfg]  all auth-config tests passed   (9)  + release-gate blocks on placeholder (exit 1)
[update]   all update-verifier tests passed (18, incl. interrupted-install + download)
```
Native: payload+stub build stripped, 0 debug sections, RELRO + non-exec stack + CET; native XChaCha decrypt == original, tamper/wrong-key rejected.

## Remaining security limitations
1. **Windows/macOS unverified** — the native builds, hardening flags, and CI legs for those OSes are written
   but not run here. Treat as unsupported until CI is green on them.
2. **Extracted plaintext can't be remotely revoked** — ban stops future key fetches + kills live sessions;
   it can't un-leak an already-decrypted payload. Needs per-user watermarking (not built).
3. **KV isn't atomic** — registration-race guard + counters are best-effort; real fix is D1/Durable Objects.
4. **Update transport trust** — `HttpFetcher` requires https and verifies signatures, but the manifest URL
   itself is config; point it at a host you control.
5. **HWID is spoofable**; client-side obfuscation is cost-raising, not a wall (both unchanged).

## Manual configuration you MUST perform (production fails closed without these)
1. `Auth.AUTH_BASE` — set your real **https** Worker URL (the banner marks the spot). Until then, prod and
   the release CI gate fail closed.
2. `UpdateVerifier.PUBLISHER_KEY` — run `python3 tools/keygen_release.py` and paste the printed **public**
   key. The private key stays in `tools/.release_signing_key` (gitignored). Release CI fails if it's empty.
3. CI secret `ZALARD_PAYLOAD_KEY` — set it (same value as the auth server's `PAYLOAD_KEY`). The build fails
   without it; it is never logged or uploaded.
4. Deploy the auth Worker (`auth-server/README.md`) and set its `PAYLOAD_KEY` + `ADMIN_KEY` secrets.
5. Install libsodium on every build host (CI does `libsodium-dev`); static-link for release.

## Is the release pipeline genuinely safe to use?
**On Linux: yes, with the manual config above** — the key is never exposed, the payload is authenticated
encryption, the jar is audited for secrets/plaintext, stale binaries are gone, and production fails closed
when unconfigured. **For Windows/macOS: not yet proven** — the workflow is written correctly but has not had
a green run here, so don't ship those platforms until the CI matrix passes on them. I verified everything
that can be verified in a Linux-only environment; the cross-platform runs are yours to execute.
