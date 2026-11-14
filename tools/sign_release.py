#!/usr/bin/env python3
"""Build and sign a Zalard release manifest.

Usage: python3 tools/sign_release.py <version> <release_dir> [out_manifest]
  - hashes every file under <release_dir> (recursively), path relative to <release_dir>
  - writes a signed manifest (default <release_dir>/manifest.txt)

The signing key is read from tools/.release_signing_key (made by keygen_release.py). The client verifies
this manifest with the embedded public key before installing an update (see UpdateVerifier.java).
"""
import datetime
import hashlib
import os
import sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import zsign

SKEY = os.path.join(HERE, ".release_signing_key")


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    version = sys.argv[1]
    rel_dir = sys.argv[2]
    out = sys.argv[3] if len(sys.argv) > 3 else os.path.join(rel_dir, "manifest.txt")
    if not os.path.isfile(SKEY):
        raise SystemExit("no tools/.release_signing_key — run tools/keygen_release.py first")
    sk = bytes.fromhex(open(SKEY).read().strip())

    entries = []
    for root, _dirs, files in os.walk(rel_dir):
        for name in sorted(files):
            full = os.path.join(root, name)
            relpath = os.path.relpath(full, rel_dir).replace(os.sep, "/")
            if relpath == os.path.basename(out):        # don't hash the manifest itself
                continue
            entries.append((relpath, sha256_file(full)))
    entries.sort()

    created = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    header = "ZALARD-MANIFEST 1\nversion {}\ncreated {}\n".format(version, created)
    for relpath, digest in entries:
        header += "file {} {}\n".format(relpath, digest)

    sig = zsign.sign(header.encode("utf-8"), sk)
    import base64
    manifest = header + zsign.MARKER + base64.b64encode(sig).decode() + "\n"
    with open(out, "w") as f:
        f.write(manifest)
    print("signed {} file(s) into {} (version {})".format(len(entries), out, version))


if __name__ == "__main__":
    main()
