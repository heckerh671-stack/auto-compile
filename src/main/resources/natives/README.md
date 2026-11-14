# natives/

The encrypted native payloads (`zalard_gui.bin`, `zalard_gui.so.bin`, `zalard_gui.dylib.bin`) and the
decryptor stubs (`zalard_loader.{dll,so,dylib}`) are **build artifacts** — they are NOT committed. They are
produced by the release pipeline:

    python tools/gen_guard.py          # per-release guard
    sh tools/build_dll.sh <os>         # builds payload + loader (needs libsodium)
    python tools/seal.py               # XChaCha20-Poly1305 encrypts the payload -> *.bin

The CI `build` workflow does this for every OS. A bare `gradle build` without the pipeline will produce a
jar with no payload (the client fails closed). Stale/old-format binaries are intentionally absent so a
release can't accidentally ship one.
