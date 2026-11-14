# Zalard (anarchy build)

Fabric client mod for Minecraft 1.21.11 (Mojang mappings, Java 21).
GUI is native C++ (`native/` -> `src/main/resources/natives/zalard_gui.dll`); open it with Right Shift.

## Modules
Combat: Rotation, Aim Assist, Silent Aim, Auto Crystal, Auto Anchor, Auto Totem, Auto Mace,
Adaptive Stun Slam, Trigger Bot, Player Freeze, Backtrack, Lag (entity-position hold: backtrack/blink), Ping Spoof, Anti Knockback,
Wind Charge Stack, Fake Elytra, Elytra Fly, Pearl Catch, Auto Elytra, Auto Pot,
Shield Breaker, Movement Reset, Auto Bridge, Humanizer.
Render: Player/Entity/Chest/Storage/Hole ESP, ArrayList HUD, Target HUD. Xray.
Client Spoofer (client-side only): Name Spoof, Skin Spoof, Ping Spoof.

## Removed from the original source
Injector (Rust), in-memory loader/agent, Lunar/Dawn watchdog neutralizer, tamper check,
string obfuscation, native license/HWID/anti-debug bridge, anti-screenshare process watch,
locked-config DRM, Spotify lyrics, schematic auto-build, all non-1.21.11 adapters.

## Build
Windows: double-click build-zalard.bat (in Downloads) or run `gradlew.bat build`.
Jar: build/libs/zalard-<version>.jar. Needs Fabric API for 1.21.11.

## Layout
src/main/java/dev/zalard/
  adapter/     1.21.11 platform implementation + mixins + entrypoint (ZalardMod)
  combat/ render/ xray/ humanizer/ rotation/   modules
  module/ config/ platform/                     core
  nativegui/ gui/                               bridge to the C++ GUI
native/        C++ GUI source; tools/build_dll.sh rebuilds the DLL (clang + lld)
