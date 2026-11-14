#!/bin/sh
# Builds the native GUI/logic library for one OS. The native side is pure computation + JNI (no OS
# calls but the export attribute and the per-OS anti-debug), so the same sources build everywhere.
#   ./tools/build_dll.sh              # build for the host OS (uname)
#   ./tools/build_dll.sh win|linux|mac
# Output: src/main/resources/natives/zalard_gui.{dll|so|dylib}  (ZalardNative loads the one per OS).
# Needs clang + lld. Regenerate the font atlas with tools/gen_atlas.py (or gen_atlas_ttf.py for Inter).
set -e
# On Windows/Git-Bash, MSYS rewrites args that look like absolute paths, which turns the MSVC linker
# flags /noentry, /noimplib, /opt:ref into bogus C:/.../ paths. Exclude all args from that conversion.
export MSYS2_ARG_CONV_EXCL='*'
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/../native"
JI=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}/include
case "${1:-$(uname -s)}" in
  win|Win*|MINGW*|CYGWIN*|windows) OS=win ;;
  linux|Linux)                     OS=linux ;;
  mac|Darwin|darwin|macos|osx)     OS=mac ;;
  *) echo "unknown target '${1}' — use win | linux | mac"; exit 1 ;;
esac

# Optional O-MVLL obfuscation (see tools/omvll_config.py). Default build untouched unless OMVLL is set.
OBF=""
if [ -n "$OMVLL" ]; then
  export OMVLL_CONFIG="$(cd "$(dirname "$0")" && pwd)/omvll_config.py"
  OBF="-fpass-plugin=$OMVLL"
  echo "obfuscation: O-MVLL via $OMVLL"
fi

# Per-target hardening that is COMPATIBLE with the freestanding (-nostdlib) payload — i.e. introduces no
# runtime symbols the way -fstack-protector/_FORTIFY_SOURCE would:
#   linux: CET branch (endbr) + stack-clash probes; linker full-RELRO, immediate binding, non-exec stack.
#   win:   CET branch only (NO stack-clash — it would pull in a __chkstk stack-probe the -nostdlib build lacks;
#          the win build already passes -mno-stack-arg-probe for the same reason).
#   mac:   ARM64 branch-protection (BTI/PAC) instead of x86 CET.
case "$OS" in
  linux) HARD="-fstack-clash-protection -fcf-protection=branch"; LDHARD="-Wl,-z,relro -Wl,-z,now -Wl,-z,noexecstack" ;;
  win)   HARD="-fcf-protection=branch"; LDHARD="" ;;
  mac)   HARD="-mbranch-protection=standard"; LDHARD="" ;;
esac

# NOTE on -fno-stack-protector: the payload is -ffreestanding -nostdlib, so there is NO __stack_chk_fail /
# __stack_chk_guard runtime to call — enabling the stack protector would fail to link. That is why it is
# off here (and ONLY here). The decryptor stub below links the CRT and DOES enable -fstack-protector-strong.
# -O2 with no -g: release code, no debug info emitted.
# -fvisibility=hidden: only JNI_OnLoad stays exported; internal symbol names drop out of the binary.
COMMON="-std=c++17 -O2 -ffreestanding -fdata-sections -ffunction-sections -fno-builtin -fno-exceptions \
  -fno-rtti -fvisibility=hidden -fvisibility-inlines-hidden -fno-math-errno -fno-stack-protector $HARD \
  -Wno-gnu-string-literal-operator-template -Iwin -I$JI $OBF"
OUT=../src/main/resources/natives

case "$OS" in
  win)   clang++ --target=x86_64-pc-windows-msvc $COMMON -mno-stack-arg-probe -nostdlib -fuse-ld=lld -shared \
           -Wl,/noentry -Wl,/opt:ref -Wl,/opt:icf -Wl,/noimplib -o "$OUT/zalard_gui.dll" zalard_gui.cpp ;;
  linux) clang++ --target=x86_64-linux-gnu $COMMON -fPIC -nostdlib -fuse-ld=lld -shared \
           -Wl,--gc-sections -Wl,--strip-all $LDHARD -o "$OUT/zalard_gui.so" zalard_gui.cpp ;;
  mac)   # needs the macOS SDK: build on a Mac, or pass SDKROOT=/path/to/MacOSX.sdk  (ARCH=arm64|x86_64)
         # -lSystem: macOS requires every dylib to link libSystem, even a -nostdlib one.
         clang++ --target="${ARCH:-arm64}-apple-macos11" $COMMON -fPIC -nostdlib -dynamiclib \
           -Wl,-x -Wl,-S ${SDKROOT:+-isysroot "$SDKROOT"} -o "$OUT/zalard_gui.dylib" zalard_gui.cpp -lSystem ;;
esac
echo "built zalard_gui ($OS)"

# --- decryptor stub (zalard_loader): NON-sensitive, links the normal CRT. Ships in the clear in the jar;
#     it only turns the encrypted payload into the real library in native memory (via libsodium
#     XChaCha20-Poly1305), so the JVM heap never holds the plaintext. Stack protection is ON here (unlike
#     the freestanding payload, this links the CRT so __stack_chk_* exists).
#
#     libsodium: set SODIUM to how it links on your build box. Prefer STATIC for release so end users need
#     nothing installed, e.g.  SODIUM="/path/libsodium.a"  (or -l:libsodium.a). Default -lsodium is dynamic.
# Stub links the CRT, so it gets the FULL hardening set: stack protector (strong), plus the same CET/branch
# + stack-clash as the payload, plus _FORTIFY_SOURCE on Linux (needs libc + -O, both present here).
SCOMMON="-std=c++17 -O2 -fvisibility=hidden -fstack-protector-strong $HARD -Iwin -I$JI"
SODIUM="${SODIUM:--lsodium}"
case "$OS" in
  win)   clang++ --target=x86_64-pc-windows-msvc $SCOMMON -shared -o "$OUT/zalard_loader.dll" zalard_loader.cpp $SODIUM ;;
  linux) clang++ --target=x86_64-linux-gnu $SCOMMON -D_FORTIFY_SOURCE=2 -fPIC -shared -fuse-ld=lld -Wl,--strip-all $LDHARD -o "$OUT/zalard_loader.so" zalard_loader.cpp $SODIUM ;;
  mac)   clang++ --target="${ARCH:-arm64}-apple-macos11" $SCOMMON -dynamiclib -Wl,-x -Wl,-S \
           ${SDKROOT:+-isysroot "$SDKROOT"} -o "$OUT/zalard_loader.dylib" zalard_loader.cpp $SODIUM ;;
esac
echo "built zalard_loader ($OS)"
