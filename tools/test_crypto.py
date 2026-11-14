"""Automated security tests for the payload crypto (tools/zcrypto.py, libsodium XChaCha20-Poly1305).

Covers the cases Priority 1 asked for: valid roundtrip, modified ciphertext, wrong key, malformed payload.
Run: python3 tools/test_crypto.py   (exit 0 = all pass). The native stub is cross-checked separately by
the build step, which decrypts a seal.py-produced blob with the real zalard_loader and compares.
"""
import os
import sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import zcrypto

fails = 0


def check(name, ok):
    global fails
    print(("PASS" if ok else "FAIL"), name)
    if not ok:
        fails += 1


def rejects(blob, key):
    try:
        zcrypto.unseal(blob, key)
        return False          # should have raised
    except (ValueError, RuntimeError):
        return True


key = os.urandom(32)
plain = os.urandom(50000)
blob = bytearray(zcrypto.seal(plain, key))

# 1. valid roundtrip
check("valid roundtrip decrypts to the original", zcrypto.unseal(bytes(blob), key) == plain)

# 2. modified ciphertext — every byte flip in the body must be rejected by the Poly1305 tag
b = bytearray(blob); b[zcrypto.HEADER + 100] ^= 0x01
check("flipped ciphertext byte is rejected", rejects(bytes(b), key))
b = bytearray(blob); b[-1] ^= 0x80
check("flipped tag byte is rejected", rejects(bytes(b), key))
b = bytearray(blob); b[5] ^= 0x01   # flip a nonce byte
check("flipped nonce byte is rejected", rejects(bytes(b), key))

# 3. wrong key
check("wrong key is rejected", rejects(bytes(blob), os.urandom(32)))

# 4. malformed payloads
check("bad magic is rejected", rejects(b"XXXX" + bytes(blob[4:]), key))
check("unknown version is rejected", rejects(bytes(blob[:4]) + bytes([99]) + bytes(blob[5:]), key))
check("truncated blob is rejected", rejects(bytes(blob[:20]), key))
check("empty blob is rejected", rejects(b"", key))
check("header-only (no ciphertext) is rejected", rejects(bytes(blob[:zcrypto.HEADER]), key))

# 5. nonce uniqueness — two seals of the same plaintext+key must differ (random nonce)
check("two seals of identical input differ (unique nonce)", zcrypto.seal(plain, key) != zcrypto.seal(plain, key))

print()
if fails:
    print("%d test(s) FAILED" % fails)
    sys.exit(1)
print("all crypto tests passed")
