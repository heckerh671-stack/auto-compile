# Seals a release: authenticated-encrypts each built native lib into the jar and pins its hash. Run AFTER
# building (gen_guard.py -> build_dll.sh [win|linux|mac] -> seal.py). It:
#   - reads the per-release key from tools/.release_key (written by gen_guard.py; the jar carries NO key —
#     the client fetches it from the auth server at launch),
#   - encrypts every zalard_gui.{dll,so,dylib} present to its .bin with libsodium XChaCha20-Poly1305
#     (tools/zcrypto.py: versioned header + unique random nonce + Poly1305 tag) and removes the plaintext,
#   - SHA-256s each built lib into its per-OS pin in ZalardNative (DLL_SHA256/SO_SHA256/DYLIB_SHA256) as a
#     defense-in-depth check; the AEAD tag is the primary authentication the native stub verifies.
# ZalardNative loads /natives/zalard_gui.bin (win), zalard_gui.so.bin (linux) or zalard_gui.dylib.bin (mac).
import os, re, hashlib, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import zcrypto   # libsodium XChaCha20-Poly1305 (authenticated); replaces the old custom XOR keystream
res = os.path.join(HERE, "..", "src", "main", "resources", "natives")
mn = os.path.join(HERE, "..", "src", "main", "java", "dev", "zalard", "nativegui", "ZalardNative.java")

m = open(mn).read()
# The key is read from tools/.release_key (written by gen_guard.py). It is NOT in any jar source — the jar
# ships only ciphertext and the client fetches this key from the auth server at launch. This script just
# needs it to encrypt the payload; it never embeds it anywhere.
_secret = os.path.join(HERE, ".release_key")
if not os.path.exists(_secret):
    raise SystemExit("no tools/.release_key — run tools/gen_guard.py first")
key = bytes.fromhex(open(_secret).read().strip())
if len(key) != 32:
    raise SystemExit("tools/.release_key is not a 32-byte (64 hex) key")

# (lib file, encrypted .bin, ZalardNative constant to pin this lib's SHA-256 into)
LIBS = [("zalard_gui.dll", "zalard_gui.bin", "DLL_SHA256"),
        ("zalard_gui.so", "zalard_gui.so.bin", "SO_SHA256"),
        ("zalard_gui.dylib", "zalard_gui.dylib.bin", "DYLIB_SHA256")]

sealed = 0
for lib, binname, const in LIBS:
    path = os.path.join(res, lib)
    if not os.path.exists(path):
        continue
    plain = open(path, "rb").read()
    open(os.path.join(res, binname), "wb").write(zcrypto.seal(plain, key))   # XChaCha20-Poly1305
    os.remove(path)                                      # jar ships only the encrypted .bin
    sealed += 1
    digest = hashlib.sha256(plain).hexdigest()           # pin the plaintext hash for this OS (defense-in-depth)
    m = re.sub(rf'String {const} = "[0-9a-fA-F]*";', f'String {const} = "{digest}";', m)
    print(f"sealed: {binname} ({len(plain)} bytes), {const} {digest}")

if sealed == 0:
    raise SystemExit("no zalard_gui.{dll,so,dylib} found — run tools/build_dll.sh first")
open(mn, "w").write(m)                                   # write ZalardNative once, with every per-OS pin applied
