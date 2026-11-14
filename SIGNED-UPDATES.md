# Signed updates (Priority 8)

Ed25519-signed release manifests so the client only installs an update that genuinely came from you, hasn't
been tampered with, and isn't a downgrade. The **private signing key never ships** — the jar embeds only the
32-byte public key. Verified on Linux (`tools/test_sign.py` + `UpdateVerifierTest`, 18 cases pass).

## How it fits together

| Piece | Role |
|-------|------|
| `tools/keygen_release.py` | makes the publisher Ed25519 keypair. Private → `tools/.release_signing_key` (gitignored, 0600). Prints the public key to embed. |
| `tools/sign_release.py <version> <dir>` | hashes every file in the release dir and writes a signed `manifest.txt`. |
| `tools/zsign.py` | libsodium Ed25519 (sign/verify) — no custom crypto. |
| `dev.zalard.update.UpdateVerifier` | client side: verifies the manifest signature with the JDK's built-in Ed25519 (no native dep), enforces rollback, verifies each file's SHA-256, installs atomically with recovery. |

Manifest format (line-based so Python-sign and Java-verify agree byte-for-byte):
```
ZALARD-MANIFEST 1
version 0.2.0
created 2026-...
file natives/zalard_gui.bin <sha256>
file zalard-0.2.0.jar <sha256>
-----SIGNATURE-----
<base64 Ed25519 signature over every byte above the marker>
```

## What's enforced (all tested)

- **Publisher signature** — a forged or edited manifest is rejected (wrong/!matching key → reject).
- **Per-file SHA-256** — a tampered or truncated download is rejected.
- **Rollback protection** — `UpdateVerifier.isNewer` requires a strictly newer version; a validly-signed
  *older* manifest is rejected, so an attacker can't force a downgrade to a known-weak build.
- **Path-traversal safety** — manifest paths with `..`, absolute paths, or drive letters are rejected.
- **Atomic install + recovery** — every staged file is verified *before any* is moved into place, so a
  failed/interrupted/corrupted download leaves the current version fully intact (tested).

## Using it

```
python3 tools/keygen_release.py                 # once; paste the printed public key into
                                                #   UpdateVerifier.PUBLISHER_KEY
python3 tools/sign_release.py 0.2.0 release/    # after building; writes release/manifest.txt
# publish release/ (manifest.txt + files) wherever your client fetches updates from
```
Client: download `manifest.txt` + the listed files into a staging dir, then:
```java
String v = UpdateVerifier.applyUpdate(manifestText, UpdateVerifier.hex(UpdateVerifier.PUBLISHER_KEY),
                                      currentVersion, stagingDir, installDir);
```

## Honest limitations

- **Transport not included.** `UpdateVerifier` takes files already downloaded into a staging dir. The
  fetch (hit a URL, download manifest + files, pick the install dir) is a thin layer to add around it — the
  security-critical part (verify before install) is what's implemented and tested.
- **No hot-swap.** A running Fabric mod can't replace itself in memory; an update lands on disk and applies
  on next launch. The verifier is where that on-disk swap is gated.
- **Signing key protection is operational.** Keep `tools/.release_signing_key` offline/secret; if it leaks,
  rotate (`keygen_release.py --force`) and ship a client with the new public key. The key is never in the
  jar, so a leaked *client* can't sign updates.
- **Verified on Linux only.** The JDK Ed25519 path is platform-independent, but like the rest, the
  Windows/macOS runs happen in CI.
