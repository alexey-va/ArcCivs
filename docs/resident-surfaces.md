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

`CivsHolograms` receives the actual Citizens Player carrier plus current
profession, status and health. Its packet TextDisplay is a native client
passenger, within 16 blocks, at feet +2.15 and scale 0.70. Its local Y subtracts
the carrier's native height. Core 2.7.45 captures Citizens carriers separately
from online viewers; no second world-position follower or five-tick teleport
interpolation moves the label. Content updates remain dirty and bounded. The
permanent profession uses warm gold, the separate action uses neutral gray,
and health uses muted red. A missing tool names the required hoe, axe or pickaxe.

The resident detail page derives a request from `CivsUiHost.toolRequest`.
When eligible, its single action delegates `giveRequestedTool`; the service
owns selection and the existing item receipt. Otherwise the request remains
visible as noninteractive explanatory text. The native inventory is unchanged.

`CivsCourierCart` shares the same packet owner, so the label, cart and cargo
merge into one passenger set. It uses the existing ItemsAdder
`elitecreatures:medieval_market_decoration_v1_cart_2` in GROUND context at scale
3, with its measured wheel correction +0.5625. The body is one block **ahead**
of body yaw. Position follows the carrier; only changed heading/height updates
the local pose. The cart persists while the snapshot requests it, including
empty pickup and post-unload states. Up to three representative cargo pieces
derive only from the current accepted cargo map, never the planned pickup.
They sit on the measured 22.5-degree bed. These pieces represent a shipment;
they are not one visible object per inventory item.

`CivsWorkplaceDecor` adds two small profession items outside the control barrel's
side and below its upper face. Its snapshot contains building ID, world ID,
current canonical control, building type and rotation. Relocation replaces
the old decoration. The packet items have no native entity or hitbox, so the
barrel retains its normal interaction. Labels, carts and decorations use one
renderer and the existing shutdown drain. Absent actors/buildings are removed.

During placement or selection, `CivsBoundaryRenderer` also shows nearby saved
building footprints immediately, even before a candidate has been prepared.
Thin sandstone marks and short corner posts distinguish them from the existing
construction fence. A candidate that overlaps another building changes that
building's marks to red in place; an upgrade excludes its own ID. Reserved
construction bounds replace the old footprint. The marks use at most 128
packet parts within 32 horizontal blocks, with conflicts first, and retire when
placement ends. The service triggers the same renderer on selection and preview
transitions; the shared queue retains its normal per-tick work budget.

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
  --tests ru.arc.civs.CivsCourierCartTest \
  --tests ru.arc.civs.CivsWorkplaceDecorTest \
  --tests ru.arc.civs.CivsBoundaryRendererTest \
  --tests ru.arc.civs.CivsTextTest \
  --tests ru.arc.civs.CivsUiTest
```

`scripts/render-resident-surfaces.py` consumes the compiled inventory and label
exports. Supply the Minecraft client JAR, its external font asset ZIP, and the
installed `design-ruscrafting-surfaces` `render_plugin_surface_dump.py` through
the command's named arguments. `--only equipment-equipped` renders a first
representative inventory before the remaining states. The renderer records
source, export and asset hashes with its limitations.

For the cart, pass `--cart-grounding` with the measured export of that exact
model. The renderer verifies the current model's source hashes and renders all
authored textured faces using the compiled local transforms. The compact review
set includes empty/loading/walking/45-degree and 90-degree turns/unloaded states,
front/side/plan views, and farmer/miner barrel props at player eye height.
Timestamps identify sampled states; they do not claim observed client playback.
The selection and overlap comparison renders the compiled neighbour marks.
Low stone masses locate saved bounds, and a translucent orange area annotates
the candidate; these context aids are not new runtime buildings or displays.

The reviewed offline set contains equipped items and a custom-item tooltip,
pending transfer, occupied cursor, and nameplates at four and eight blocks.
Minecraft textures and glyphs are used. Inventory icons are texture previews;
the shield is its real board UV rendered flat. The player reference is a vanilla
skin mannequin with simplified UVs. TextDisplay raster origin is approximate.
These observations do not establish native item-model appearance, packet
tracking, interpolation, input or ordinary-client acceptance.
