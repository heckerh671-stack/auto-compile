# O-MVLL obfuscation policy for the Zalard native DLL. Loaded when you build with:
#   OMVLL=/path/to/libOMVLL.{so,dll} ./tools/build_dll.sh
#
# This decides WHICH functions get obfuscated, by demangled name. Two rules of thumb baked in:
#   1. Harden branchy, high-value logic that runs rarely (protection + combat decisions).
#   2. NEVER flatten the per-frame GUI render path — control-flow flattening turns tight inner loops
#      into a dispatch loop and will cost you FPS. Flattening is for logic, not for drawing.
#
# NOTE: o-mvll's Python API has shifted slightly across releases (method names/signatures). This matches
# the current open-obfuscator docs; if your installed plugin rejects a method, check
# https://github.com/open-obfuscator/o-mvll/tree/main/docs and adjust the handful of method names here.

import omvll
from functools import lru_cache

# Protect: protection core + combat *decision* logic (all relatively low-frequency).
PROTECT = ("z::vm::", "z::rm::", "z::rot::", "z::aim", "z::crystal", "z::tbot", "z::mace", "z::lag", "JNI_OnLoad")
# Never touch: per-frame render / text / atlas paths (perf-critical inner loops).
SKIP = ("draw", "render", "z::gui", "blit", "glyph", "atlas", "font")


def _name(func):
    return getattr(func, "demangled_name", "") or getattr(func, "name", "") or ""


def _hit(func):
    n = _name(func)
    if any(s in n for s in SKIP):
        return False
    return any(s in n for s in PROTECT)


class Zalard(omvll.ObfuscationConfig):
    def __init__(self):
        super().__init__()

    # Control-flow flattening: dissolves the function's CFG into a dispatch loop — the single biggest
    # obstacle to decompiling the logic. Scoped to PROTECT so the render path keeps its clean loops.
    def flatten_control_flow(self, mod, func):
        return _hit(func)

    # Opaque constants: the recognizable magic numbers (e.g. the GCD constants 0.6/0.2/1.2, token bytes)
    # become runtime-computed instead of literals a reverser can grep for.
    def obfuscate_constants(self, mod, func):
        return _hit(func)

    # Mixed boolean-arithmetic on integer ops only (o-mvll leaves floats alone). Keep it to the
    # protection paths where the extra instructions don't matter.
    def obfuscate_arithmetic(self, mod, func):
        return any(s in _name(func) for s in ("z::vm::", "JNI_OnLoad"))

    # Encrypt every string literal — cheap, and strings are the first foothold a reverser looks for.
    def obfuscate_string_encoding(self, mod, func, string):
        return True

    # Anti-hooking on the guard so a trampoline over it is detected at runtime.
    def anti_hooking(self, mod, func):
        return "z::vm::" in _name(func)


@lru_cache(maxsize=1)
def omvll_get_config():
    return Zalard()
