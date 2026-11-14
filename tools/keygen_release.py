#!/usr/bin/env python3
"""Generate the Zalard publisher signing keypair (Ed25519).

The PRIVATE key is written to tools/.release_signing_key (gitignored, chmod 600) and must be kept secret —
it NEVER goes in the jar or the repo. The PUBLIC key is printed to embed in UpdateVerifier.PUBLISHER_KEY.

Run once; re-running overwrites the key (which invalidates every previously signed manifest), so it refuses
unless --force is given.
"""
import os
import sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import zsign

dst = os.path.join(HERE, ".release_signing_key")
if os.path.exists(dst) and "--force" not in sys.argv:
    raise SystemExit("tools/.release_signing_key already exists; refusing to overwrite (use --force to rotate)")

pk, sk = zsign.keypair()
with open(dst, "w") as f:
    f.write(sk.hex() + "\n")
try:
    os.chmod(dst, 0o600)
except OSError:
    pass

print("publisher keypair generated.")
print("  private key -> tools/.release_signing_key  (KEEP SECRET, never commit or ship)")
print("  PUBLIC key (embed in UpdateVerifier.PUBLISHER_KEY):")
print("    " + pk.hex())
