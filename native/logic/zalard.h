// Zalard native logic - everything the client does, in C++. Java only hosts the Fabric entrypoint,
// the mixins and dev.zalard.adapter.Bridge (how this code reads Minecraft and presses keys).
// Included at the end of zalard_gui.cpp (one translation unit, freestanding, no CRT).
#pragma once
#include "rt.h"
#include "obf.h"
#include "vm.h"
#include "bridge.h"
#include "settings.h"
#include "combat.h"
#include "mod_combat.h"
#include "mod_combat2.h"
#include "mod_misc.h"
#include "mod_render.h"
#include "mod_visual.h"
#include "config.h"
#include "entry.h"
