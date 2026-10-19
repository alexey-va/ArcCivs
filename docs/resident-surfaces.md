# Native resident inventory and labels

`CivsEquipmentMenu` presents six equipment slots and 27 bag slots in a native
54-slot inventory. Logical slots 0–5 are helmet, chestplate, leggings, boots,
main hand and offhand; they appear at view slots 1, 2, 3, 4, 6 and 7. Logical
slots 6–32 appear at view slots 18–44. The remaining cells are menu furniture.

Every visible carried item is an unmodified clone from `CivsUiHost.residentItems`.
Its name, lore, durability and native components are not replaced with menu text.
Empty equipment cells have labeled placeholders; empty bag cells stay empty.

The presenter intercepts every click and drag. LEFT, RIGHT, SHIFT_LEFT and
SHIFT_RIGHT delegate a direct transfer through `transferResidentItem`. A top
click passes the logical NPC slot; a lower-inventory click passes the player's
storage slot, 0–35. The backend owns authority, matching, equipment swaps,
serialization and the receipt. The cursor never receives a resident item.
Existing cursor contents must be put away before another transfer can start.

The window remains open while a transfer settles. A second click cannot submit
another transfer in that visit. Completions from a closed/replaced window cannot
rewrite a newer view. After completion, and once per second while idle, the menu
reads current items and updates only changed cells. Back opens the resident's
card; Escape retains ordinary native inventory behavior. Feeding is an explicit
action on the resident card.

`CivsHolograms` receives snapshots of actually spawned NPC feet and observed
status/health. One packet TextDisplay follows each actor, within 16 blocks, at
feet +2.15 and scale 0.70. Normal movement uses five-tick interpolation; a world
change or movement greater than four blocks snaps to the recovered actor.
Absent actors are removed. Creation, update and removal use the shared native
work budget. Core owns packet tracking and shutdown cleanup.

The existing building control label stays owned by `CivsBoundaryRenderer`.
`CivsText.buildingHologram` adds `blocksPlaced / blocksRequired` only when the
actual nullable counters are present and valid. Legacy batch progress is never
converted into a fabricated block count. Names remain literal components.

## Focused verification

Run the presenter, lifecycle and text fixtures, with an optional source export:

```sh
CIVS_NATIVE_PREVIEW_OUT=/private/output ./gradlew test \
  --tests ru.arc.civs.CivsEquipmentMenuTest \
  --tests ru.arc.civs.CivsHologramsTest \
  --tests ru.arc.civs.CivsTextTest \
  --tests ru.arc.civs.CivsUiTest
```

`scripts/render-resident-surfaces.py` consumes the compiled inventory and label
exports. Supply the Minecraft client JAR, its external font asset ZIP, and the
installed `design-ruscrafting-surfaces` `render_plugin_surface_dump.py` through
the command's named arguments. `--only equipment-equipped` renders a first
representative inventory before the remaining states. The renderer records
source, export and asset hashes with its limitations.

The reviewed offline set contains equipped items and a custom-item tooltip,
pending transfer, occupied cursor, and nameplates at four and eight blocks.
Minecraft textures and glyphs are used. Inventory icons are texture previews;
the shield is its real board UV rendered flat. The player reference is a vanilla
skin mannequin with simplified UVs. TextDisplay raster origin is approximate.
These observations do not establish native item-model appearance, packet
tracking, interpolation, input or ordinary-client acceptance.
