"""Signing tests + fixture generator for the signed-update system (Priority 8).

  python3 tools/test_sign.py                 -> run the Python-side unit tests
  python3 tools/test_sign.py --fixtures DIR  -> write fixtures for the Java UpdateVerifierTest
"""
import base64
import hashlib
import os
import sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import zsign


def sha(b):
    return hashlib.sha256(b).hexdigest()


def build_manifest(version, files, sk):
    header = "ZALARD-MANIFEST 1\nversion {}\ncreated 2026-01-01T00:00:00Z\n".format(version)
    for path, content in files:
        header += "file {} {}\n".format(path, sha(content))
    sig = zsign.sign(header.encode(), sk)
    return header + zsign.MARKER + base64.b64encode(sig).decode() + "\n"


def unit_tests():
    fails = 0
    def check(n, c):
        nonlocal fails
        print(("PASS " if c else "FAIL ") + n)
        if not c:
            fails += 1

    pk, sk = zsign.keypair()
    msg = b"ZALARD-MANIFEST 1\nversion 1.2.3\n"
    sig = zsign.sign(msg, sk)
    check("valid signature verifies", zsign.verify(msg, sig, pk))
    check("tampered message rejected", not zsign.verify(msg + b"x", sig, pk))
    check("flipped signature rejected", not zsign.verify(msg, bytes([sig[0] ^ 1]) + sig[1:], pk))
    pk2, _ = zsign.keypair()
    check("wrong public key rejected", not zsign.verify(msg, sig, pk2))

    manifest = build_manifest("0.2.0", [("a.bin", b"hello"), ("b.bin", b"world")], sk)
    signed, s2 = zsign.split_manifest(manifest)
    check("split recovers signed bytes", signed.startswith(b"ZALARD-MANIFEST 1\nversion 0.2.0\n"))
    check("manifest signature verifies", zsign.verify(signed, s2, pk))
    bad = manifest.replace("version 0.2.0", "version 9.9.9")   # tamper the signed region
    sb, ss = zsign.split_manifest(bad)
    check("tampered manifest body rejected", not zsign.verify(sb, ss, pk))

    print()
    if fails:
        print("%d test(s) FAILED" % fails); sys.exit(1)
    print("all signing unit tests passed")


def fixtures(d):
    os.makedirs(os.path.join(d, "good"), exist_ok=True)
    pk, sk = zsign.keypair()
    a, b = b"payload-A-" * 100, b"payload-B-" * 100
    open(os.path.join(d, "good", "a.bin"), "wb").write(a)
    open(os.path.join(d, "good", "b.bin"), "wb").write(b)
    open(os.path.join(d, "pub.hex"), "w").write(pk.hex())

    open(os.path.join(d, "manifest.txt"), "w").write(build_manifest("0.2.0", [("a.bin", a), ("b.bin", b)], sk))
    # a properly-signed older version (for the rollback test)
    open(os.path.join(d, "manifest_rollback.txt"), "w").write(build_manifest("0.0.1", [("a.bin", a), ("b.bin", b)], sk))
    # a valid manifest whose signature byte is corrupted
    good = build_manifest("0.2.0", [("a.bin", a), ("b.bin", b)], sk)
    signed, _ = zsign.split_manifest(good)
    badsig = base64.b64encode(b"\x00" * 64).decode()
    open(os.path.join(d, "manifest_badsig.txt"), "w").write(signed.decode() + zsign.MARKER + badsig + "\n")
    # a correctly-signed manifest that lists a path-traversal file
    header = "ZALARD-MANIFEST 1\nversion 0.2.0\ncreated x\nfile ../evil.bin " + sha(a) + "\n"
    sig = zsign.sign(header.encode(), sk)
    open(os.path.join(d, "manifest_traversal.txt"), "w").write(header + zsign.MARKER + base64.b64encode(sig).decode() + "\n")
    print("fixtures written to", d)


if __name__ == "__main__":
    if len(sys.argv) > 2 and sys.argv[1] == "--fixtures":
        fixtures(sys.argv[2])
    else:
        unit_tests()
