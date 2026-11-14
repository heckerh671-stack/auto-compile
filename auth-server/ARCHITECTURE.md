# Zalard login + server-held key — architecture

Usage is free. The account exists only to give the server an identity it can **refuse or revoke** — that's
what turns "the jar is crackable offline" into "the key isn't in the jar, and a leaker can be banned."

## The flow

```
                         ┌─────────────────────────── Cloudflare Worker (auth-server/) ──────────┐
  client launch          │  /register  free account (username+password, PBKDF2)                  │
      │                  │  /login     verify + bind machine (HWID) -> short-lived token         │
      │  1. login ──────▶│  /key       token + HWID + not-banned  -> PAYLOAD_KEY (Worker secret) │
      │ (user+pass+hwid)  │  /admin/ban revoke a leaker   /admin/reset clear a bound machine     │
      │◀───── token ──────│                                                                      │
      │  2. get key ─────▶│                                                                      │
      │◀───── key ────────└──────────────────────────────────────────────────────────────────────┘
      │
      ▼
  dev.zalard.auth.Auth.fetchKey()  returns the 32-byte key
      │
      ▼
  ZalardNative.load()  →  native stub decrypts /natives/*.bin  WITH THAT KEY  (key never embedded)
```

1. **Client (`dev.zalard.auth.Auth`)** computes a stable HWID (hash of MACs + OS user), reads the user's
   credentials (`zalard-auth.properties` or `-Dzalard.auth.user/-Dzalard.auth.pass`), calls `/login`, then
   `/key`, and returns the 32-byte key.
2. **`ZalardNative.load()`** passes that key to the native stub (`Loader.decryptToFile(..., key)`), which
   decrypts the shipped ciphertext payload. **No key is stored in the jar** — not in `ZalardNative`, not in
   the stub. Unzip the jar and you get ciphertext and a decrypt routine with no key.
3. **Server (`worker.js`)** only hands the key to a logged-in, non-banned account on its bound machine, and
   rate-limits. Ban an account and its next `/key` fails — that build goes dark.

## Why this is the real jump in difficulty

- **Offline cracking is dead:** the earlier attacks all recovered a key that shipped in the jar. There is
  no key in the jar now, so there is nothing to recover offline. You must have a live, valid login.
- **Leakers become revocable:** offline protection can never say "no" to a specific person. With an account
  it can — `/admin/ban` cuts them off, everyone else keeps working.
- **Free:** anyone can `/register`. You're not charging; you're getting an identity to revoke.

## Honest limits (unchanged by any of this)

- An **authenticated user on their own machine** can still capture the key at runtime (memory/MITM of their
  own TLS) and share the **decrypted payload**. You can't stop that client-side. You fight it with **per-user
  watermarking** (make each delivered payload traceable) + **ban the account it traces to**. This design
  gives you the revocation half; watermarking is the next step if leaks become a problem.
- Keep the **native obfuscation on** (strip + O-MVLL). The binary still runs on the attacker's machine.
- HWID is not spoof-proof; it binds casual sharing and enables revocation, nothing stronger.

## Build/release flow (key never touches the jar or git)

```
python tools/gen_guard.py       # makes/reuses tools/.release_key (gitignored); PRINTS the PAYLOAD_KEY
sh tools/build_dll.sh <os>      # stub takes the key at runtime — no key compiled in
python tools/seal.py            # encrypts the payload with tools/.release_key; pins per-OS hashes
./gradlew build                 # jar ships ciphertext only
```
Set the printed key once as the server secret: `wrangler secret put PAYLOAD_KEY`. The key is **stable across
builds** (gen_guard reuses `.release_key`) so it keeps matching the server; delete `.release_key` to rotate
(then update the secret). In CI, write your key secret into `tools/.release_key` before `gen_guard`.
See `auth-server/README.md` for deployment.
