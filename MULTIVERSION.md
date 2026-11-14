# Zalard multi-version (1.21.11 → 26.2)

Goal: one source tree that builds for **MC 1.21.11** (Java 21) and **MC 26.2** (Java 25),
on Windows / Linux / Mac. The native half is already cross-OS (pure compute + JNI; see
`tools/build_dll.sh`). This doc is about the **Java/Fabric half across MC versions**.

## Why Stonecutter

The Java mod is where versions diverge (mappings, refactored classes, changed method
signatures). [Stonecutter](https://stonecutter.kikugie.dev/) is the standard tool for this:
preprocessor comments pick a code branch per MC version at build time.

The property that makes this safe to adopt incrementally: **the active version's code is
live (uncommented); every other version's code sits inside `/* */`.** `vcsVersion` is the
"source of truth" branch that stays uncommented in the repo. We set `vcsVersion = "1.21.11"`,
so **until you run Stonecutter, the tree is exactly today's working 1.21.11 build** — the
`//?` lines are inert comments and the 1.21.11 code compiles as-is. Nothing about the current
build breaks by adding the conditional blocks.

## What I can't do from here (needs your machine)

I can't run Gradle, download the 26.2 dependency set, or verify 26.2 Mojang mappings, so
**the 26.2 branches below are written from the decompiled 26.2 reference (Mace Client) and
must be compiled + tested in your environment.** Two things only you can fill in:

1. **Exact 26.2 dependency coordinates** (table below has the slots). The 26.2 reference's
   `fabric.mod.json` only pins ranges (`minecraft ~26.2`, `java >=25`, `fabricloader >=0.19.3`).
2. **Running `./gradlew chiseledBuild`** per version and fixing any mapping mismatch the
   compiler flags (I've marked every spot that's a likely mismatch).

## Version matrix — fill the 26.2 column

| Property            | 1.21.11 (known good) | 26.2 (you confirm)        |
|---------------------|----------------------|---------------------------|
| `minecraft_version` | `1.21.11`            | `26.2`                    |
| `java`              | 21                   | 25                        |
| `loom`              | `1.19-SNAPSHOT`      | newest Loom w/ MC26 support (likely `1.11.x`+) |
| `loader_version`    | `0.20.1`             | `>=0.19.3` (confirm exact)|
| `fabric_version`    | `0.141.6+1.21.11`    | the FAPI build for 26.2   |
| `yarn/mojmap`       | Mojang               | Mojang                    |

Find the 26.2 FAPI + loader at the Fabric meta / Modrinth once, drop them in `versions/`.

## Build setup (apply when ready — does NOT change the 1.21.11 build)

`settings.gradle` — add the plugin + version list:
```groovy
pluginManagement {
    repositories { gradlePluginPortal(); maven { url = "https://maven.fabricmc.net/" }; maven { url = "https://maven.kikugie.dev/releases" } }
}
plugins { id "dev.kikugie.stonecutter" version "0.5+" }   // confirm newest
stonecutter {
    create(rootProject) {
        versions("1.21.11", "26.2")
        vcsVersion = "1.21.11"        // keeps the repo tree = working 1.21.11
    }
}
```
`build.gradle` — read per-version props from `stonecutter.current` and set `JavaLanguageVersion`
21 vs 25 off the active version. `gradle.properties` stays the 1.21.11 defaults; per-version
overrides go in `versions/1.21.11/gradle.properties` and `versions/26.2/gradle.properties`.

Build: `./gradlew "Set active version to 1.21.11"` then `build`, or `./gradlew chiseledBuild`
for all versions. Then per built jar, run the native pipeline unchanged
(`gen_guard.py → build_dll.sh <os> → seal.py`).

## Mixin delta map — the whole surface, triaged

**STABLE (27 → no change needed, same code both versions).** These touch classes/signatures
that didn't move between 1.21.11 and 26.2, so they compile unchanged under both:
`AttackHookMixin`, `AntiKnockbackMixin`, `ExplosionPacketMixin`, `PingSpoofMixin`,
`MoveFixMixin`, `MoveFixEntityMixin`, `SpoofName/Skin/Chat/TabName/TabSkin`,
`ConnectionAccessor`, `ContainerScreenAccessor`, `EntityAccessor`, `LocalPlayerAccessor`,
`GameModeInvoker`, `KeybindsMixin`, `WindowMixin`, `WorldRenderMixin`, `SpoofNameTagMixin`.
→ Compile under 26.2 first; promote any that fail into the list below.

**VERSION-SENSITIVE (need a `//? if >=26` branch).** Confirmed changed in 26.2:

| Mixin | 1.21.11 | 26.2 change | Status |
|---|---|---|---|
| `InGameHudMixin` | `Gui.render(GuiGraphics,DeltaTracker)` TAIL | HUD split: `Gui`→`Hud`+`GuiRenderer`; hook `GuiRenderer.render()V` TAIL, **no GuiGraphics in that sig** — see note | **ported below** |
| `NoSlowMixin` | `LocalPlayer.modifyInput(Vec2)` RETURN | `modifyInput` **removed**; input is `KeyboardInput.tick()` w/ `this.moveVector`(Vec2)+`this.keyPresses`(`Input` record) | **ported below** |
| `KeyMappingAccessor` | `@Accessor("clickCount")` etc. | field set unchanged in ref, but confirm `isDown`/`clickCount` names | low risk |
| `MovePlayerPacketMixin` | packet ctor | confirm `ServerboundMovePlayerPacket` inner-class ctor arity | confirm |
| `UseItemPacketMixin` | `ServerboundUseItemPacket` yRot/xRot | confirm field names persist | confirm |
| `SilentHeadMixin` / `OwnNametagMixin` | render-state | render pipeline reshuffle in 26.x — confirm target methods resolve | confirm |
| `LocalPlayerMixin` | `aiStep`/input | confirm `aiStep` unchanged | confirm |

### GuiGraphics note (the one real design question for 26.2)
Zalard's HUD draws through a `GuiGraphics` handed to the native overlay (`ext.setGui(g)`).
In 26.2 the `GuiRenderer.render()V` hook **doesn't receive a `GuiGraphics`**. Two options
when you get there:
- **A (recommended):** keep hooking a method that still has a `GuiGraphics` in 26.2 (the
  per-element Hud draw path), OR
- **B:** acquire the frame's `GuiGraphics` the way 26.2's own HUD does and pass that.

I've left the 26.2 branch targeting `GuiRenderer.render()V` with a `TODO(26.2)` at the exact
line where the `GuiGraphics` source must be wired, because that choice needs the real 26.2
classes in front of you to pick correctly. Everything else in the branch is ready.

## Rendering backend (OpenGL → Vulkan)

MC Java is moving OpenGL → Vulkan. 26.x ships **both** renderers side-by-side (player picks one);
OpenGL stays through the transition. Mojang's rule: code that goes through MC's render abstraction
survives; **raw OpenGL calls break under Vulkan**.

Audit result: exactly **one** place in Zalard made raw GL calls — the native-GUI/overlay draw path.
Everything else (ESP, hitboxes, world render, HUD, nametags) already routes through MC's abstraction and
is renderer-agnostic. So the whole Vulkan job is that one draw path.

**How it's split (by MC version, not by GL-vs-Vulkan):**

| File | Role | Live on |
|---|---|---|
| `nativegui/GuiRenderBackend.java` | interface: "draw these native vertices as an overlay" | all |
| `nativegui/GlGuiRenderBackend.java` | the original raw-GL pipeline, extracted unchanged | all (used <26) |
| `nativegui/PipelineGuiRenderBackend.java` | Blaze3D `RenderPipeline`/`GpuDevice` path — works on GL **and** Vulkan | impl in `//? if >=26` |
| `nativegui/NativeRenderer.java` | frame-prep + backend selection + one-time GL fallback | all |
| `assets/zalard/shaders/core/gui.{vsh,fsh}` | pipeline-form MSDF shaders (std140 UBO `GuiUniforms`, `Sampler0`) | used by pipeline path |

`NativeRenderer` selects per version via Stonecutter: **`>=26` → PipelineGuiRenderBackend**, **`<26` →
GlGuiRenderBackend**. Pre-26 is GL-only, so the raw-GL path is correct and needed there; the Blaze3D GPU
API exists on 1.21.11 too but has no Vulkan to drive and its signatures drifted, so we don't use it below
26. On 26.x the pipeline path covers both the GL and Vulkan renderer from one implementation. At runtime,
if the pipeline backend fails to initialize (an API-shape mismatch on a version we couldn't test), it
degrades to the GL backend once. Force a backend for debugging: `-Dzalard.gui.backend=gl|pipeline`.

**Guarantee preserved:** the entire pipeline implementation sits in a `//? if >=26` comment branch, so the
un-chiseled tree is still exactly today's working 1.21.11 GL build (`PipelineGuiRenderBackend` is a throwing
stub until chiseled to 26.2).

### TODO(26) checklist — PipelineGuiRenderBackend (confirm against real 26.2 classes)
Every item below is marked `TODO(26)` at its line. These are the Blaze3D GPU API shapes I couldn't verify
from here (no 26.2 deps / no Vulkan snapshot):
- `VertexFormatElement.register(...)` + `VertexFormat.builder().add(name, element)` for the 6 generic float
  attrs (pos2, uv2, p4, col4, clip4, kind1); names must match the shader `in` names.
- `RenderPipeline.builder()` method names: `withVertexShader`/`withFragmentShader(ResourceLocation)`,
  `withVertexFormat(fmt, Mode.TRIANGLES)`, `withUniform("GuiUniforms", UNIFORM_BUFFER)`,
  `withSampler("Sampler0")`, blend (want SRC_ALPHA/ONE_MINUS_SRC_ALPHA rgb, ONE/ONE_MINUS_SRC_ALPHA a),
  `withDepthTestFunction(NO_DEPTH_TEST)`, `withCull(false)`; register via `RenderPipelines.register(...)`.
- `GpuDevice.createBuffer(label, int usage, int size)` + usage flag names (`USAGE_VERTEX`/`USAGE_UNIFORM`/
  `USAGE_MAP_WRITE`); `CommandEncoder.writeToBuffer(GpuBufferSlice, ByteBuffer)`.
- `Std140Builder.intoBuffer(...).putVec2(...)` for the `uView` UBO; `getUniformOffsetAlignment()`.
- `createTexture(...)` arg list + `TextureFormat.RGBA8` (atlas is expanded RGB→RGBA), `setTextureFilter`
  (LINEAR), `setAddressMode` (CLAMP_TO_EDGE), `writeToTexture(...)`, `createTextureView`.
- `createCommandEncoder().createRenderPass(Supplier<String>, colorView, Optional, depthView, OptionalDouble)`
  on `mc.getMainRenderTarget()`'s color+depth views; `setPipeline`/`setUniform`/`bindSampler`/
  `setVertexBuffer`/`draw(first,count)` (use a 0..n-1 index buffer + `drawIndexed` if non-indexed `draw` is gone).
- **Hook timing:** the overlay is driven from `WindowMixin` just before buffer swap. Confirm that's still a
  valid point to open a RenderPass on the main target under Vulkan on 26.2; if not, move the call to the end
  of `GameRenderer.render`.
- **Shaders:** confirm MC's pipeline shader loader accepts `core/gui.{vsh,fsh}` as-is (it may inject a
  header / want `layout(location=N)` on inputs). `fwidth`/`gl_FragCoord` are used by MC core shaders, so
  they're fine on both backends.

## Suggested order of work
1. Add Stonecutter (settings/build), confirm **1.21.11 still builds** (sanity — should be
   byte-identical behavior, GL GUI backend live).
2. `./gradlew "Set active version to 26.2"`, `build`. Fix compile errors top-down; each one
   is a mapping/signature the "STABLE" list guessed wrong — move it to version-sensitive.
3. Wire the two `TODO(26.2)` spots (HUD GuiGraphics source; NoSlow slowdown site).
4. Work the **TODO(26) checklist** above in `PipelineGuiRenderBackend` until it compiles on 26.2; test the
   native GUI on the **OpenGL** renderer first (`-Dzalard.gui.backend=pipeline` to bypass the GL fallback),
   then switch MC to the **Vulkan** renderer and confirm it still draws.
5. Build + seal per version per OS.
