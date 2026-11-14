"""Zalard payload crypto — libsodium XChaCha20-Poly1305 (authenticated encryption).

Replaces the old custom SHA-256 XOR keystream. This module is used by tools/seal.py (to encrypt the
payload) and tools/test_crypto.py (negative tests). The native stub (native/zalard_loader.cpp) decrypts
with the SAME libsodium primitive and format, so a tampered payload, a wrong key, or a malformed blob are
rejected by the Poly1305 tag before any plaintext is accepted.

Sealed payload format (all little-endian):
    magic "ZLD1" (4) | version u8 (=1) | nonce (24) | ciphertext+tag (len(plain)+16)

No custom cryptographic algorithm is implemented here — this is a thin ctypes binding to libsodium.
"""
import ctypes
import ctypes.util
import os

_libname = ctypes.util.find_library("sodium") or "libsodium.so.23"
_s = ctypes.CDLL(_libname)
if _s.sodium_init() < 0:
    raise RuntimeError("libsodium init failed")

MAGIC = b"ZLD1"
VERSION = 1
KEYBYTES = 32
NONCEBYTES = 24
ABYTES = 16   # Poly1305 tag
HEADER = len(MAGIC) + 1 + NONCEBYTES

_enc = _s.crypto_aead_xchacha20poly1305_ietf_encrypt
_enc.restype = ctypes.c_int
_enc.argtypes = [ctypes.c_char_p, ctypes.POINTER(ctypes.c_ulonglong),
                 ctypes.c_char_p, ctypes.c_ulonglong,
                 ctypes.c_char_p, ctypes.c_ulonglong,
                 ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p]
_dec = _s.crypto_aead_xchacha20poly1305_ietf_decrypt
_dec.restype = ctypes.c_int
_dec.argtypes = [ctypes.c_char_p, ctypes.POINTER(ctypes.c_ulonglong), ctypes.c_void_p,
                 ctypes.c_char_p, ctypes.c_ulonglong,
                 ctypes.c_char_p, ctypes.c_ulonglong,
                 ctypes.c_char_p, ctypes.c_char_p]


def seal(plain: bytes, key: bytes) -> bytes:
    if len(key) != KEYBYTES:
        raise ValueError("key must be 32 bytes")
    nonce = os.urandom(NONCEBYTES)
    c = ctypes.create_string_buffer(len(plain) + ABYTES)
    clen = ctypes.c_ulonglong(0)
    if _enc(c, ctypes.byref(clen), plain, len(plain), None, 0, None, nonce, key) != 0:
        raise RuntimeError("encrypt failed")
    return MAGIC + bytes([VERSION]) + nonce + c.raw[:clen.value]


def unseal(blob: bytes, key: bytes) -> bytes:
    """Decrypt + authenticate. Raises ValueError on any tamper / wrong key / malformed input."""
    if len(key) != KEYBYTES:
        raise ValueError("key must be 32 bytes")
    if len(blob) < HEADER + ABYTES or blob[:4] != MAGIC:
        raise ValueError("bad header / too short")
    if blob[4] != VERSION:
        raise ValueError("unsupported version %d" % blob[4])
    nonce = blob[5:5 + NONCEBYTES]
    ct = blob[HEADER:]
    m = ctypes.create_string_buffer(len(ct))
    mlen = ctypes.c_ulonglong(0)
    if _dec(m, ctypes.byref(mlen), None, ct, len(ct), None, 0, nonce, key) != 0:
        raise ValueError("authentication failed (tampered ciphertext or wrong key)")
    return m.raw[:mlen.value]


if __name__ == "__main__":
    k = os.urandom(32)
    pt = b"the quick brown fox" * 1000
    blob = seal(pt, k)
    assert unseal(blob, k) == pt, "roundtrip"
    print("roundtrip OK, blob overhead =", len(blob) - len(pt), "bytes")
