"""Ed25519 signing for Zalard release manifests — libsodium via ctypes (no custom crypto).

The PRIVATE (signing) key stays with the publisher and is NEVER shipped. The client embeds only the
32-byte PUBLIC key and verifies with the JDK's built-in Ed25519 (see UpdateVerifier.java). Manifest format
is a line-based text block signed as-is, so Python (sign) and Java (verify) agree byte-for-byte with no
JSON-canonicalization ambiguity:

    ZALARD-MANIFEST 1
    version <semver>
    created <iso8601>
    file <relative/path> <sha256hex>
    ...
    -----SIGNATURE-----
    <base64 Ed25519 signature over every byte before the marker line>
"""
import ctypes
import ctypes.util

_s = ctypes.CDLL(ctypes.util.find_library("sodium") or "libsodium.so.23")
if _s.sodium_init() < 0:
    raise RuntimeError("libsodium init failed")

MARKER = "-----SIGNATURE-----\n"

_s.crypto_sign_keypair.argtypes = [ctypes.c_char_p, ctypes.c_char_p]
_s.crypto_sign_detached.argtypes = [ctypes.c_char_p, ctypes.POINTER(ctypes.c_ulonglong),
                                    ctypes.c_char_p, ctypes.c_ulonglong, ctypes.c_char_p]
_s.crypto_sign_verify_detached.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_ulonglong, ctypes.c_char_p]


def keypair():
    pk = ctypes.create_string_buffer(32)
    sk = ctypes.create_string_buffer(64)   # libsodium secret key = seed||pubkey
    if _s.crypto_sign_keypair(pk, sk) != 0:
        raise RuntimeError("keypair failed")
    return pk.raw, sk.raw


def sign(message: bytes, sk: bytes) -> bytes:
    if len(sk) != 64:
        raise ValueError("secret key must be 64 bytes")
    sig = ctypes.create_string_buffer(64)
    n = ctypes.c_ulonglong(0)
    if _s.crypto_sign_detached(sig, ctypes.byref(n), message, len(message), sk) != 0:
        raise RuntimeError("sign failed")
    return sig.raw[:n.value]


def verify(message: bytes, sig: bytes, pk: bytes) -> bool:
    if len(pk) != 32 or len(sig) != 64:
        return False
    return _s.crypto_sign_verify_detached(sig, message, len(message), pk) == 0


def split_manifest(text: str):
    """Return (signed_bytes, signature_bytes) for a manifest string, or raise ValueError."""
    i = text.find(MARKER)
    if i < 0:
        raise ValueError("no signature marker")
    import base64
    signed = text[:i].encode("utf-8")
    sig_b64 = text[i + len(MARKER):].strip()
    return signed, base64.b64decode(sig_b64)
