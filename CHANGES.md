# Changes

## Audit round 2: update call site, atomic activate, workflow trace (see AUDIT-RESPONSE-2.md)
- **Update call site wired** — `Updater.startBackgroundCheck()` is called from `ZalardMod` startup; checks
  for a newer SIGNED release over HTTPS in a daemon thread and stages it under `mako/versions/` for next
  launch. No-op/fail-safe if `UPDATE_BASE`/`PUBLISHER_KEY` aren't set; errors never block startup.
- **activate() no longer degrades silently** — the temp pointer is created in the same dir (same FS) and
  committed with `ATOMIC_MOVE`; an unsupported atomic move now THROWS (leaving the old version active)
  instead of a silent non-atomic replace.
- **Update integration tests** — failed download, corrupted file, missing manifest, and over-the-wire
  invalid signature, each asserting the active version is unchanged. Update suite now 25 cases.
- **Workflow key-handling statically verified** — proved `.release_key` is in no artifact and the jar audit
  rejects a planted key/plaintext. Still can't execute Windows/macOS CI → those stay unverified.

## P8: signed updates (Ed25519 manifests) — see SIGNED-UPDATES.md
- Ed25519-signed release manifests: `tools/keygen_release.py` (keypair; private key gitignored, never
  shipped), `tools/sign_release.py` (hash + sign a release), `tools/zsign.py` (libsodium, no custom crypto).
- Client `dev.zalard.update.UpdateVerifier` verifies with the JDK's built-in Ed25519 (no native dep):
  publisher signature, per-file SHA-256, **rollback protection** (strictly-newer version), path-traversal
  safety, and atomic install that leaves the current version intact on any failure (recovery).
- Tested on Linux: `tools/test_sign.py` (7) + `UpdateVerifierTest` (11) all pass, wired into CI.
- Not included: the download transport + swap-at-next-launch wiring (thin layer around the verifier).

## V4: session renewal, prod config, backend reliability, cross-platform CI (see REMEDIATION-V4.md)
- **Sessions (P3):** sliding-window tokens renewed by the heartbeat (fixes the hard 1h cutoff) with a 24h
  absolute cap. Client distinguishes `RENEW` (re-login) from `REVOKED` (stop) from `NETWORK` (keep trying),
  so an outage never disables a legit session but a ban does. Tested in `worker_test.mjs`.
- **Production config (P4):** `Auth.validateBase` uses the compiled URL in prod and **ignores** stray
  `-Dzalard.auth.base` overrides, requires https, and fails closed on the placeholder. `AuthConfigTest`
  (9 cases) passes.
- **Backend (P5):** audit logging on all security events; admin endpoints get a separate rate limit +
  constant-time key check; per-account login rate limit; best-effort registration-race guard. Honest note:
  KV isn't atomic — the real fix is D1/Durable Objects (documented).
- **Native loader (P6):** `sodium_init()` is checked (fail-closed); Windows path handling switched to
  `CreateFileW` (UTF-8→UTF-16) — unverified (no Windows SDK here).
- **CI (P1/P2):** cross-platform matrix (prepare → native{linux,win,mac} → seal → jar) sharing one guard,
  with per-OS libsodium + dependency checks. **Linux verified; Windows/macOS written but not run here.**
- **Deferred:** signed updates (P8) — the largest remaining piece.

## V3 remediation: vetted AEAD, usage telemetry, hardened CI (see REMEDIATION-V3.md)
- **Crypto (P1):** replaced the custom SHA-256 XOR keystream with **libsodium XChaCha20-Poly1305** —
  versioned header + unique nonce + Poly1305 tag that authenticates before any plaintext is accepted
  (`tools/zcrypto.py`, `seal.py`, `native/zalard_loader.cpp`). Hand-rolled crypto removed from the stub.
  Automated negative tests (`tools/test_crypto.py`, 11 cases) + a native cross-check pass on Linux; the
  Java in-heap decrypt path is gone (encrypted ⇒ stub-only, fail-closed).
- **Telemetry (your ask):** `/heartbeat` + `/admin/stats` report `registered / activeToday / onlineNow`.
  The heartbeat doubles as periodic re-authorization: a ban revokes a live session within ~90s.
- **Auth (P2):** removed the fixed `"0000…"` HWID fallback (fail instead of binding everyone to one id).
  `auth-server/worker_test.mjs` exercises register/login/HWID-bind/key/stats/ban/revoke end-to-end (12 cases).
- **CI (P3):** the workflow loads the release key from secret `ZALARD_PAYLOAD_KEY`, **fails** if it's
  missing/invalid (never silently generates a prod key), runs the test suites as a gate, and validates the
  sealed `ZLD1` payload + stub are present with no plaintext before building.
- **Compiler (P4):** `-fstack-protector-strong` added to the CRT-linked stub (the freestanding payload keeps
  `-fno-stack-protector` — it has no `__stack_chk_*` runtime; removing it breaks the link).
- **Deferred (honest):** signed updates/rollback (P6), Windows/macOS build verification (P5), account
  recovery + per-user entitlements (P2). Reasons + the full matrix in `REMEDIATION-V3.md`.

## Review follow-ups: JNI hard-fail, thread-safe string ring, macOS anti-debug
Three fixes from a second-opinion review (the cheap, real ones — two were actual bugs):
- **JNI registration now hard-fails.** `JNI_OnLoad` (jni_table.h + gen_jni.py) captures each
  `RegisterNatives` result; a failed class lookup or binding returns `JNI_ERR` so `System.load` throws and
  the client disables cleanly, instead of limping on with some natives unbound. `GetEnv` failure also
  returns `JNI_ERR` now, not a version.
- **String ring is thread-safe.** The global XS() slot ring advanced its index with an unsynchronized
  read-modify-write — a data race if XS() is hit from the render and tick threads at once (duplicate/torn
  slots → garbled strings or crashes). Now `__atomic_fetch_add` (lock xadd, still freestanding).
- **macOS anti-debug parity.** Replaced the no-op with a `KERN_PROC`/`P_TRACED` sysctl via raw syscalls
  (import-free, matching the Linux path), **fail-safe** (any error → "not debugged", never a false positive
  that would disable a legit user). Assembles for both Apple arches; marked `TODO(mac)` — runtime behavior
  (struct offset, syscall ABI) needs testing on a real Mac.

Not done (deliberately, per the review discussion): AES-GCM swap (hygiene, not an exploitable hole — the
SHA-256 pin already detects tampering); in-memory loading (inherent `System.load` limit); the `-fno-stack-
protector` flag stays on the freestanding payload (it has no `__stack_chk_*` runtime; removing it breaks the
build). Verified: full native build (payload + stub) compiles with all three changes; generator reproduces
the fixed header.

## Free login + server-held key (the key leaves the jar entirely)
The root problem every pen-test exploited was that the decryption key shipped in the jar alongside the
ciphertext. Now it doesn't. The key is held by an auth server and fetched at launch after a free login.
- **`auth-server/`** — a Cloudflare Worker (free tier): `/register` (free accounts), `/login` (verifies +
  binds a machine via HWID, returns a short-lived token), `/key` (token + HWID + not-banned → returns the
  payload key held as a Worker secret), `/admin/ban` + `/admin/reset`. PBKDF2 passwords, KV storage,
  rate-limited. See `auth-server/README.md` (deploy) and `ARCHITECTURE.md` (flow + honest limits).
- **`dev.zalard.auth.Auth`** — computes a stable HWID, reads credentials (`zalard-auth.properties` or
  `-Dzalard.auth.user/-Dzalard.auth.pass`), logs in, returns the 32-byte key. JDK-only.
- **Key is now a runtime parameter.** `Loader.decryptToFile(enc, out, hash, key)` and the native stub take
  the key as an argument; `loader_key.h`, `realKey()`, the Java `DLL_KEY`/`dllKey()` and the split-fragment
  scheme are all **gone**. `gen_guard.py` writes the key to gitignored `tools/.release_key` (stable across
  builds so it matches the server) and prints it for `wrangler secret put PAYLOAD_KEY`; `seal.py` reads it.
- **Result:** the shipped jar carries only ciphertext — there is no key in it, split or otherwise, so the
  offline extraction that worked in every prior round now recovers nothing. A leaked build is cut off by
  banning the account it traces to. For local dev without the server, `-Dzalard.devkey=<64hex>`.
- **Honest ceiling (unchanged):** an authenticated user can still capture the key at runtime and share the
  *decrypted* payload — fight that with per-user watermarking + revocation, and keep O-MVLL on. Verified:
  native stub compiles + roundtrips with a runtime key; `Auth`/`Loader`/`ZalardNative` compile clean.

## Payload-extraction hardening (after a static pen-test cracked the old scheme in seconds)
Pen-test finding: the shipped jar carried both the ciphertext AND a trivially reversible key — `javap`
the `DLL_KEY` constant out of `ZalardNative.class`, XOR with `SHA-256("jvm-natives")` (a literal salt
also in the jar), and both native payloads decrypted offline to a valid PE/ELF in seconds, with every
runtime defense (anti-debug, VM guard, token binding) bypassed because the client never runs. Fixes:
- **Java key blanked on sealed releases.** `seal.py` now sets `ZalardNative.DLL_KEY = ""` after sealing.
  A sealed build is fail-closed (decrypts only via the native stub), so the Java key was dead weight an
  attacker could lift straight from the class. The shipped jar's `ZalardNative.class` now carries no key.
- **Native stub is the sole key holder, and the key is split.** `loader_key.h` no longer stores one masked
  blob + a literal salt word; it stores two fragments with `key = A XOR B XOR SHA-256(A)` (matched in
  `gen_guard.py`, `zalard_loader.cpp realKey()`, and `seal.py`). `strings` on the stub yields two opaque
  blobs and no salt to grep; recovery means replaying the recombination in native code.
- **Native binaries stripped** (`-Wl,--strip-all` / `-Wl,-x -Wl,-S`). The shipped `.so` was `not stripped`
  (symbol names intact); now 0 symbols. O-MVLL (already supported via `OMVLL=…`) matters more than ever
  since the key recombination now lives in native code — enable it for release builds.

Reality check (unchanged): client-side, no-login — this raises extraction cost from "seconds" to
"reverse stripped, obfuscated native code," it is not a wall. True secrecy needs server-gated keys, which
needs an identity to gate on. Verified: stub recovers the exact key from the split; `.so` builds stripped;
committed payload still decrypts to a valid ELF.

## Vulkan-ready rendering (1.21.11 → 26.2)
- MC Java is moving OpenGL → Vulkan (26.x ships both renderers; raw-GL code breaks under Vulkan). Audit
  found exactly one raw-GL spot in Zalard — the native-GUI/overlay draw path; everything else (ESP,
  hitboxes, world/HUD, nametags) already routes through MC's abstraction and is renderer-agnostic.
- Split that draw path behind a `GuiRenderBackend` interface: `GlGuiRenderBackend` (the original raw-GL
  pipeline, extracted unchanged, used <26) and `PipelineGuiRenderBackend` (Blaze3D `RenderPipeline`/
  `GpuDevice` — works on the GL **and** Vulkan renderer, used on 26.x). `NativeRenderer` keeps the
  backend-independent frame-prep, selects per MC version via Stonecutter, and degrades to GL once if the
  pipeline path can't init. Force with `-Dzalard.gui.backend=gl|pipeline`.
- The 26.x pipeline implementation + its MSDF pipeline shaders (`shaders/core/gui.{vsh,fsh}`, std140 UBO +
  `Sampler0`) live in a `//? if >=26` branch, so the un-chiseled tree stays byte-identical to today's
  working 1.21.11 GL build. Every unverifiable Blaze3D-GPU API shape is marked `TODO(26)`; see the new
  "Rendering backend" section + TODO(26) checklist in MULTIVERSION.md. Must be compiled/tested on 26.2.

## Sealed builds are fail-closed (anti-dump can't be downgraded)
- A sealed build (encrypted payload + per-OS hash pinned) now **refuses to start** if the native decrypt
  stub is unavailable or fails, instead of falling back to decrypting in the JVM heap. This closes the
  last anti-dump gap: previously an attacker who could reliably make the stub fail (delete/corrupt/block
  it) would downgrade to the in-heap fallback and get a heap-dump window at the genuine plaintext. Now
  that path is gone for release builds — the only in-heap decrypt left is on **unsealed dev builds**,
  which carry no pinned secret. A stub that rejects a tampered payload also stops startup (no fallback).
- Gated on `sealed = enc[0] && !expectedHash().isEmpty()`, so a normal dev build (plain lib, or encrypted
  with no pin for the current OS) still falls back freely and your local workflow is unaffected.

## Integrity enforced on every load path, every OS
- The decrypted-payload SHA-256 check now runs on **both** load paths and **all three** platforms, closing
  the asymmetry a reviewer flagged: previously only the Java fallback hash-checked, and only on Windows, so
  the native-decrypt path and the Linux/Mac builds leaned on the encryption alone as their integrity gate.
- `seal.py` now pins each built library's hash into its own per-OS constant (`DLL_SHA256` / `SO_SHA256` /
  `DYLIB_SHA256`) and writes `ZalardNative.java` once with all pins applied. `expectedHash()` selects the
  pin for the running OS; an unsealed dev build leaves it `""` and skips the check.
- The native stub (`Loader.decryptToFile` / `zalard_loader.cpp`) takes the expected hash and verifies the
  decrypted bytes in native memory **before** writing — so on the primary path the plaintext library is
  both integrity-checked and never exposed as a JVM-heap `byte[]`. On a stub miss the Java fallback re-runs
  the same check, so forcing the fallback can't load a modified payload either. Verified: native cipher +
  hash parity with `seal.py`, native stub syntax-clean, `Loader`/`ZalardNative` compile clean.

## Anti-dump, virtualizer & original font
- The whole feature map is now hidden from the binary: every module name (Aim Assist, Crystal, Ping
  Spoof…), setting name (Reach, Silent Auto Hit, Spoof Name…), category, combat enum option
  (Camera/Silent, W-Tap/S-Tap…) and the config-migration table are XS-wrapped. `strings` on the
  decrypted DLL shows none of them (only generic UI chrome + the vanilla cape list remain). The GUI
  model interns each name into its own pool as it registers (newModule/newSetting/sEnum), so the
  short-lived decrypt buffer is never aliased; a runtime test confirms all 20 modules / 174 settings
  still read back correctly. Raises the crack bar from "unzip and grep" to "dump live memory".
- Anti-RAM-dump: transient obfuscated strings decrypt into a small reused ring buffer instead of a
  static buffer, so such a secret's plaintext only exists for the instant it's used. See obf.h.
- The DLL is now shipped **encrypted** inside the jar: seal.py encrypts it to /natives/zalard_gui.bin
  (SHA-256 keystream, per-release key from gen_guard.py) and deletes the plaintext .dll. Unzipping the
  jar yields only ciphertext — `strings zalard_gui.bin` shows nothing, not even the PE "DOS mode" stub.
  ZalardNative decrypts it in memory, checks the pinned hash, loads it from a throwaway temp file, and
  wipes the plaintext heap copy.
- Instrumentation-agent block: ZalardNative refuses to run under a -javaagent/-agentlib/-agentpath JVM
  agent (the usual "attach and dump every class" route). Pair with -XX:+DisableAttachMechanism.
- Virtualized guard (native/logic/vm.h): the token + debugger check runs as XOR-packed bytecode on a
  tiny stack VM, not a plain branch a cracker can NOP. Reversing it means reversing the VM first.
- Original GUI font (tools/zfont.py): the typeface is now Zalard's own hand-drawn pixel bitmaps.
  Dropped the bundled Pixelify Sans / Inter / DejaVu dependencies (and pxgrid.py) — the client ships
  no third-party font and no OFL attribution. gen_atlas.py bakes the MSDF atlas from zfont directly.
- Honest limits (a running process can't be made secret-free; the distributed jar is what's fully
  opaque) are written up in SECURITY.md.

## Anti-crack hardening
- DLL strings are obfuscated: item IDs, file names, the bridge class name and the JNI method names no
  longer show up in `strings zalard_gui.dll` (on-screen labels stay readable on purpose). See obf.h.
- JNI exports hidden: the DLL exported 64 labelled `Java_dev_zalard_*` symbols; now it exports only
  JNI_OnLoad and binds the methods with RegisterNatives (obfuscated names).
- Jar<->DLL binding: a per-release 128-bit token is baked into both halves (boot refuses to run on a
  mismatch), and the jar pins the DLL's SHA-256 (a swapped/patched DLL is refused at load).
- Anti-debug: reads the Windows PEB (BeingDebugged / NtGlobalFlag), and quietly winds the client down
  after a random delay if a debugger is attached.
- New tools: gen_guard.py (token), gen_jni.py (JNI table), seal.py (DLL hash). Rebuild order and a
  licensing/activation design for stopping leaks are written up in SECURITY.md.


## Native port: all client logic is C++ now
- Every module, the rotation engine, Target Lock, aim assist smoothing, ESP, HUD, Client Spoofer
  decisions, configs, friends, keybinds and the GUI's pages run inside zalard_gui.dll (native/logic/).
  The jar only keeps what has to be Java: the Fabric entrypoint, the mixins, the Minecraft adapter
  (PlatformImpl) and dev.zalard.adapter.Bridge, which the C++ code calls through JNI to read the game
  and press keys. Nothing about how modules act changed: same inputs, same timing.
- Bridge operation numbers come from one table: python3 tools/gen_ops.py writes native/logic/ops.h and
  dev/zalard/nat/Op.java. Rebuild the DLL with sh tools/build_dll.sh.
- Settings live directly in the GUI's model (no more Java <-> GUI syncing every tick).
- config.json keeps the same format; old configs (and old module names) still load.
- Fixed a compile error in the previous source (ZalardMod called a tickKeys() that didn't exist).
- Removed leftovers of modules that were already gone (Backtrack, Player Freeze, X-Ray, Auto Bridge,
  Humanizer...) and their packet hooks.


## This update
- Text is drawn from an MSDF atlas (multi-channel distance field, generated with msdfgen): corners stay
  sharp at every GUI scale. To rebuild it: MSDFGEN=/path/to/msdfgen python3 tools/gen_atlas.py native/font_atlas.h atlas.png
- No caps anywhere in the modules: module names (panels, ArrayList), settings, options, keybinds, reset,
  configs / friends are all lower case. Panel headers stay as they are.
- GUI font is now Pixelify Sans (the design's font), drawn pixel-for-pixel on its own grid.
- New Automation panel (Totem, Fast Place, No Fall). HUD settings moved into the gear's Settings panel
  (with Configs / Friends; it scrolls if it gets too tall).
- Panels can be dragged all the way to the left / top now (positions reset once).
- Renames: TBot (top of Combat), Crystal, Anchor, Totem, Lunge, Flight. Old configs carry over.
- Merged: Silent Aim -> Aim Assist (Mode: Camera / Silent), Stun Slam -> Mace Swap, Auto Cart +
  Crossbow Cart -> Cart. Lunge and Velocity are in Movement.
- New: No Slow, Fast Place, No Fall (water bucket clutch with your own keys).
- Lunge: the spear's number key went in the same tick as the wind charge throw, so the throw happened
  with the spear in hand. The spear key now goes in the tick after.
- Shield Breaker: one axe hit per raised shield (needs it up 2 ticks, then waits until it drops or ~1.5 s).
- TBot: adapts to tap-jumping too (not just holding space), and its sprint drop works with Toggle Sprint.
- Aim Assist: smoother - follows a strafing target while you're on them instead of stop-and-go at the
  hitbox edge, softer glide in, steps never yank.
- Totem: Cursor Sim -> Silent Sim does the same inventory swap with the same hand timing, without the
  inventory showing on your screen.
- Client side: Crystal and Anchor (crosshair mode) now only press your mouse buttons / number keys; the
  F-swap helper presses F. Silent aim paths use vanilla's own attack / use calls (needed for a hidden head).
- Client Spoofer: names are swapped inside plugin text too (sidebar scoreboard, team prefixes, tab ranks,
  hologram / text-display nametags), whole names only. Black heads fixed (a skin pointing at a texture that
  wasn't loaded drew the missing texture). Cape Spoof with every official cape.


## Hotfix
- Fixed the crash on launch ("Mixin transformation of LocalPlayer failed"): No Slow's hook was a @Redirect,
  which MixinExtras couldn't handle. It's a plain @Inject now.


## GUI
- Top bar: Modules / Configs / Friends tabs + Search (type to filter modules, right-click clears).
- Configs page: save (type a name + Save), click to load, x to delete. Friends page: add / remove.
- Hover a panel and scroll: up zooms it out (smaller), down zooms it back. Scrolling no longer moves panels.
- Slightly smaller and more see-through. New "Mace" panel with everything mace; new "Player" panel.
- ArrayList redrawn in the GUI's style (same font, dark rows, blue accent bar).

## New
- Combo Assist (was Movement Reset; your old settings carry over).
- Sprint, Safe Walk (sneaks at real drops), No Slow (full speed while eating / blocking / drawing a bow).
- Freecam (WASD / Space / Shift fly the camera; your player stays put).
- Auto Builder: imports .litematic / .schem / .nbt from .minecraft/zalard/schematics (or .minecraft/schematics),
  preview of what's left, builds bottom-up with smooth silent rotation + random Place Delay, walks to the
  next block itself (A* path with steps and drops), pulls blocks from your inventory, tells you what's missing.
- Auto Anchor: Auto Aim (turns onto the anchor you placed) + Silent (hidden head) / visible camera.

## Fixed / better
- Ping Spoof: aims past your number so the tab list reaches it in 1-2 keep-alives (used to take ~1 min).
- Name / skin spoof: instant when the skin's owner is online; skins are cached on disk; faster lookups.
- Shield Breaker: hits right after the axe swap (it used to wait out a cooldown it never finished), turns
  onto the target first so the hit is legit, better defaults.
- Auto Bridge: sneaks at the edge until the next block is down (Safe Walk), faster rotation, shorter delay.
- X-Ray: fast palette-based ore scan (no more lag), per-ore colors, "Exposed Only" filters anti-xray fakes,
  ore toggles actually apply now.
- Wind Charge Stack: works without packet tricks: launches with a charge at your feet, then one more per
  0.5 s cooldown near the top of each rise. No Fire Key set = turning it on fires once.
- Pearl Catch: no Fire Key set = turning it on fires once; pearl pitch is aimed to land on the target
  (high lob, leads their walking); extra catch charges wait the cooldown.
- Aim Assist: pulls only to the nearest part of their hitbox and stops once you're on them.
- ESP: 2D boxes with soft fill + outline, health bars, name tags (name, health, distance); 3D style still there;
  Target Lock target shows in red.


## New GUI (ClickGUI)
- The old Mako GUI is replaced by the ClickGUI layout from clickgui.cpp: one draggable panel per
  category (Combat, Movement, Render, Misc), modules as rows, blue = on. Still native C++ (zalard_gui.dll).
- Left click a module = on/off. Right click a module = open its settings under it (Bind first).
- Settings: click a toggle; drag a slider; drag either end of a range; click / right-click an
  option to step forward / back; click Bind then press a key (Esc / Backspace = none, right-click = clear);
  click a text field, type, Enter.
- Drag a panel by its title. Right-click a title to fold the panel. Mouse wheel over a panel scrolls it.
- HUD elements can be dragged around directly behind the panels while the GUI is open.
- Gone with the old GUI: the Configs / Friends / Overlays footer pages (your main config still saves
  automatically).

## Fewer settings
- Each module only shows the settings that matter; everything else is hidden and kept at its default.

## Delay with randomization
- New range setting: a Delay like "1 - 4" ticks picks a random value inside the range every time
  (set both ends the same for a fixed delay). Added / converted on:
  Trigger Bot (Delay), Silent Aim (Hit Delay), Auto Crystal (Place / Break Delay), Auto Anchor
  (Swap / Detonate Delay), Auto Totem (Delay), Auto Pot (Delay), Mace Swap (Swap Back Delay),
  Shield Breaker (Delay, Swap Back Delay), Auto Bridge (Place Delay), Movement Reset (Tap / Jump Delay),
  Pearl Catch (Catch / Mace Swap Delay), Auto Elytra (Return Delay), Elytra Fly (Rocket Delay),
  Backtrack (Refreeze Delay).
- Delays count real game ticks (1 tick = 50 ms).


## Movement Reset
- **W-Tap fixed**: it never ran (nothing ever told it you hit someone). Now every hit you land
  (your clicks and module hits) triggers it on the next tick: W is let go for "W-Tap Hold" and put
  back, so your sprint resets and you don't run into the person you just knocked back.
- **S-Tap (new)**: "Combo Tap" = S-Tap lets go of W and taps S after your hit instead, so you stop /
  step back and the gap is right for the next hit.
- Combo Tap settings: mode (W-Tap / S-Tap / Off), Tap Chance 1-100%, Tap Delay, W-Tap Hold, S-Tap Hold,
  only on players, only on sprint hits. Only fires while you're actually holding W.
- **Jump Reset fixed**: it only worked under 14 HP and jumped 40-110 ms late. Now it jumps on the exact
  tick the knockback reaches you (when you're on the ground), with "Jump Chance" 1-100% for how often.
  "Jump Delay" (ticks) if you want it later, "Only Holding W" optional.
- Settings in different sections had the same names ("Response Min", "Trigger Chance"...) and
  overwrote each other in the config. All names are unique now.
- Sprint Reset (let go of W when YOU take damage) is now off by default.
- S-Tap / W-Tap also work while Silent Aim is turning your head (the movement fix keeps them).

## Mace Swap
- Rewritten. While you're falling (past "Min Fall", 2 blocks by default) it predicts your fall and
  swaps to your mace when you'll be within hit range of someone in the next "Look Ahead" ticks.
  The swap goes out at the start of the tick, so your click hits with the mace.
- Swaps back 1-2 ticks after your hit, or when you land / the target's gone ("Swap Back").
- "Auto Hit" (off by default): hits for you only when your crosshair (or the Silent Aim head) is
  on them within 3 blocks. The old version attacked from up to 4.5 blocks in any direction,
  which the server rejects.
- Follows Target Lock. Mace priority (Density > Breach > Wind Burst > any) kept.


## Target Lock (new)
- New **"Target Lock"** setting (ON by default) on Silent Aim, Aim Assist and Trigger Bot.
  Hit someone and that person becomes THE target: aim and Trigger Bot only go for them until they die
  (or leave render distance / log out), you die, or you hit somebody else yourself.
- Module hits (Trigger Bot, Auto Hit, ...) can set the first lock but never move it to someone else.
- Hitting a mob never steals a lock that's on a player. Friends are never locked.
- With Silent Aim turned: click with your crosshair on someone the hidden head isn't on and the
  lock moves to them. That click is sent as a plain swing (hitting them right then would be a hit
  the server sees you not looking at); the head turns to them on the next ticks.
- If the locked person is out of range / out of sight, aim goes to nobody instead of the next person.
- Target HUD and other modules that pick "the" target (Auto Mace, Shield Breaker, Backtrack,
  Auto Crystal, Stun Slam) prefer the locked person whenever they're in range.

## Anti-snap (with or without a lock)
- Aim no longer drops its target the moment it loses line of sight or leaves the FOV for a tick;
  it holds it for ~0.3 s before moving on, so it doesn't flick between people standing together.
- People behind walls no longer "block" the visible person next to them from being picked.
- Switching targets no longer counts the jump as target motion (that gave a fast snap), and the
  turn speed eases back in after a switch.

## Rotation sync
- Aim Assist reads your real camera every frame and works from that, so it never fights your mouse:
  moving away from the target backs the pull off instantly (its momentum is dropped) and it comes
  back over ~0.25 s.
- Pull strength follows your camera per frame (it used to use the angle from the last tick).
- "Vertical" OFF no longer pins your pitch to last tick's value (this dragged your mouse back).
- No more buzzing one notch back and forth on the target: steps stay on the sensitivity grid but
  never land past the target, and the leftover remainder can't build up anymore.
- Teleports / server look changes / whole-turn resyncs reset the driver instead of being read as mouse movement.
- Silent Aim hand-back fades toward where your camera is NOW (it used to aim at the angle from the
  moment it let go, then jump when the fade ended), and leftover momentum pointing away from your
  camera is dropped so the hand-back doesn't swing out first.


## Fixes
- **Ghost trigger bot**: Silent Aim had "Auto Attack" ON by default, so it hit people on its own.
  It's now "Auto Hit", OFF by default (renamed so an old config can't switch it back on).
  New "Redirect Clicks" (on): only YOUR clicks get sent to the locked target when your crosshair is on nobody.
- **Post / entity actions**: nothing sends sprint (Entity Action) packets directly anymore. Movement Reset
  lets go of W instead, and vanilla drops/resumes the sprint inside its own movement packet.
  All module logic now runs at the START of the tick, so every attack / slot swap / use-item goes out
  before the movement packet (running at the end of the tick is what got flagged as "Post").
- **Skin spoof**: the session server returns pretty-printed JSON and the parser expected no spaces,
  so it never found the skin. Fixed parsing, added the newer name-lookup endpoint, slim/wide arms follow
  the source skin, old 64x32 skins (Notch) are converted, and success/failure is shown in chat.
- **Backtrack** rewritten: freezes the nearest player on your screen for "Freeze Time", then lets them catch up.
- **Player Freeze** rewritten: freezes you on everyone else's screen; with "Hit From Anywhere" your held
  movement is sent right before each hit so the hit lands from where you really are.

## Aim
- Silent Aim "Target Mode": Crosshair (FOV cone) or Closest (nearest player all around you, keeps aiming when you look away).
- Tracking: the rotation engine now feeds the target's own motion forward and runs the same tick it's
  requested (it used to trail a strafing player by ~11 degrees).
- "Prediction" (ticks) on Silent Aim and Aim Assist leads moving targets.
- "Randomization" on Silent Aim and Aim Assist: aim point drifts around the upper body, turn speed
  wobbles a little, tiny jitter.

## Other
- "Spoof" renamed to "Client Spoofer" (old settings carry over).
- W-Tap no longer forces W down after releasing it if you weren't holding W.

## Zalard GUI + fixes
- New GUI (Zalard layout): centered window, category rail, module cards with switches, settings
  page per module, Configs / Friends pages, search, smooth open animation.
- Combo Assist: W-Tap / S-Tap now only has **Delay** (ticks you stop for after a hit, random
  inside the range, e.g. 2 - 3). Starts the moment you hit.
- Freecam rewritten as a camera override: the camera has its own position and look, the mouse
  turns only the camera, so nothing that rotates your player can make it swing. Smooth between
  ticks; hold Sprint to fly twice as fast.
- Auto Bridge reworked: sneaks at the edge until the block is down, aims only at spots the head
  can actually hit, keeps the head still once it's on the face, faster default rotation.

## Zalard
- Renamed to **Zalard** (mod name, watermark, chat prefix, jar is now `zalard.jar`).
- New click GUI from mako-gui-cpp: category panels side by side (Combat, Movement, Render, Player,
  Misc - empty ones are hidden), rows fill with the accent color when on, settings open inline
  (sliders, range sliders, checkboxes, mode buttons, keybind, text), **Reset** per module, pixel font.
  Top bar: Modules / Configs / Friends + search. Wheel scrolls the panel under the mouse.
  Keys 1-4 in the GUI change the accent (violet, red, green, orange); the array list follows it.
- Array list + watermark redrawn in the GUI style (notched tags, accent bar, pixel font).
- Mace is no longer its own category (its modules are in Combat).
- Removed: Player Freeze, Backtrack, Anti Knockback, Elytra Fly, Auto Pot, Pearl Catch,
  Auto Elytra, Wind Charge Stack, Auto Builder.
- Trigger Bot: crits fixed (drops sprint a tick before the falling hit, hits on the first falling
  tick, only waits for the fall when you jumped yourself), mace smash falls don't wait for charge,
  default Delay 0.
- Combo Assist: new **Adaptive** tap mode (S-Tap when they're close / rushing you / trading,
  W-Tap otherwise).
- Mace Swap: swaps inside the attack itself when the hit would be a smash (no prediction), so it
  works with Trigger Bot, Silent Aim and your own clicks.
- Auto Crystal: no rotations. Hold right click with crystals: places on the obsidian / bedrock you
  look at and pops the crystal you look at.
- New: **Auto Cart** (Flame bow release -> rail + TNT cart in the arrow's path) and
  **Crossbow Cart** (loaded crossbow right click on the ground -> rail + TNT cart + fire, then the
  shot goes through the fire and sets it off).
- GUI matched to the design 1:1: dirt backdrop, panels at the design's spots on a 1280 x 760
  canvas, watermark + notched array list drawn on top, no top bar (Configs and Friends are rows at
  the bottom of the Misc panel), pixel font tuned to Pixelify's size and weight. The in-game array
  list / watermark use the exact same metrics, so they look the same with the GUI open or closed.
- GUI: no dirt backdrop (the world shows through, slightly dimmed). Panels start folded: click a
  category header to open / close it. Pixel font is now drawn at whole screen pixels with exactly
  one pixel between letters, so it's crisp and even at any resolution.

## Next round
- Watermark sits a bit in from the corner, can't be turned off or dragged away (HUD is always on;
  its other parts are switched in its settings).
- Array list: modules slide in / out when toggled, the rows below move smoothly; the notch no longer
  shows hairlines (everything on whole pixels, pieces overlap).
- GUI: panels and module settings open / fold with a short eased slide (~0.15 s); no darkening.
- Removed: X-Ray, Auto Bridge, Sprint, Safe Walk, No Slow, and the Configs / Friends rows.
- Anti Knockback is back as **Velocity** (Horizontal / Vertical cut up to 100%, Grim-Safe Caps).
- New: **Fly** (WASD + Space / Shift, Anti Kick), **Motion** (Strafe bunny hop or Ground speed),
  **Spear Lunge** (after a wind charge throw it presses your spear's hotbar key and jabs in the air
  -> Lunge boost, then back to the wind charge).
- Mace Swap: only presses your mace's hotbar number key (read by vanilla before the click in the
  same tick); nothing inside the attack anymore.
- Auto Cart / Crossbow Cart: done with your own inputs, one step per tick: the camera turns onto
  the spot, the hotbar key is pressed and right click is clicked (held for the bow).
- Auto Anchor: with delays above 0 it keeps aiming at the anchor through the wait and keeps your
  held right click from double-clicking, so the combo no longer breaks.

## Round after
- Watermark a bit closer to the top-left corner.
- Spear Lunge: turn it on and bind it; the bind doesn't toggle it, each press throws a wind charge
  (number key + right click), switches to the spear and jabs once you're launched (Lunge boost).
  Module binds can now also be the middle / side mouse buttons.
- Lag: entity lists are built once per tick and shared (every module used to rebuild them), the
  GUI only sends changed visibility, and the overlay uploads its vertices in one call per frame.
- Target HUD / Coordinates / FPS / Ping drawn in the GUI's style (panels, accent bar, pixel font;
  the target's health bar glides).
- ESP name tags: placed from where the player is drawn this frame (no lag / jitter behind the
  model), pinned just above the head, GUI-style tag.
- Stun Slam: reach measured to the hitbox like the server (it never fired falling onto heads);
  works from 1.6 blocks of fall.
- Shield Breaker: only swaps (hotbar key + click) when the shielded player is in real reach under
  your crosshair.
- Auto Totem: Cursor Sim settings are back; swaps with your keys (number key, F next tick, back);
  waits for the server before trying again (no more flicking); an item you put in your offhand is
  only replaced when you're low.
- Auto Anchor: turns the shortest way onto the anchor and stops once it's on it.
- Aim Assist: one steady target box (no pull while on them, no stop/start jerks).
- Trigger Bot: when you're jump-critting and a fall ended too early, it waits for the next jump.
- Combo Assist: no Jump Reset on explosion knockback (crystals, anchors, TNT, wind charges).
- Silent Aim: throwing a wind charge or pearl hands the head to your camera for that throw.
- GUI: drag panels by their header (click still folds; spots are saved), settings gear in the
  bottom right with Configs and Friends.
