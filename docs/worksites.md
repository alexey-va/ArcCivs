# Authored worksite coordinates

`src/main/resources/worksites.json` is a small, generated spatial contract for
the 27 curated FARM, FORESTER and QUARRY schematics. It contains coordinates and
source hashes, not third-party building voxels. The private input remains at
`artifacts/arccivs-20261017/curated/` in the workspace. Each source is verified
against that library's `curation-report.json` before generation.

Regenerate or verify from the ArcCivs repository:

```sh
python3 -B scripts/generate-worksites.py
python3 -B scripts/generate-worksites.py --verify
```

The script uses the existing `sponge.py` reader in
`../ruscrafting-ops/scripts/location-atelier`; both roots can be supplied through
`--library` and `--atelier`. It does not write to either input tree. Validation
checks all 27 hashes, family frames, crop substrates/clearance, hydration,
preserved field center, existing ladder facings, connected gallery floors and
the four quarter-turn transforms. The source schematics are never modified.

## Coordinates and schema

All coordinates are integer **block positions relative to the schematic
origin**, matching `CivsBlueprintGeometry`, not source-array indices. A world
coordinate is `building.anchor + rotate(local, building.rotation / 90)`.
One clockwise quarter-turn maps `(x,y,z)` to `(-z,y,x)`. Rotate directional
blockstates as well as positions. Both ends of a box must be transformed into
new minimum/maximum bounds; rotating only its minimum is incorrect.

The root object has `version: 1`, `coordinateSpace:
"schematic-origin-relative"`, and `sites`, keyed by exact extensionless schematic
ID. Each site has `schematicId`, `schematicSha256`, `family`, `type`, `level`,
`schematicBounds: {min,max}`, `entrance`, and one of the following objects:

| Object | Fields and meaning |
| --- | --- |
| `farm` | `soilCells` are blocks to till; crops occupy the block above. `irrigationCells` are water source positions. `irrigationExisting` distinguishes authored water from a new source requiring one WATER_BUCKET and returning one BUCKET. `workEntry` and optional `alternateEntry` are feet positions. `gateCells` identifies authored L3 gates. Optional `preservedCenter` is an inclusive box excluded from tilling/planting/harvest. |
| `forester` | `workEntry` is the hut entrance. `searchBounds` is only a finite search envelope. Runtime must intersect it with the city and exclude all buildings, fields, mine access and any protected/user-owned material. It does not authorize cutting every log inside it. |
| `mine` | `planVersion: 2` identifies the explicit continuation order. `ladderBottom` and `ladderFacing` identify the existing shaft access in every level. `firstSubgroundPoint` is the first ladder cell below the schematic. `shaftCenter` records the original visible hole center. `shaftFootprint` contains local `[x,z]` pairs for the real 3×3 continuation around the ladder. `floors` has one entry per unlocked mining floor. |
| `mine.floors[]` | `index` is one-based; `feetY` is the walker's feet block layer; `height` is three blocks of clearance. `landing` is the feet position immediately in front of the ladder. `floorCells` are ordered **support blocks**, not targets to remove. `workSections` partitions that order into landing, complete main corridor, complete left arm, complete right arm. Clear only `y+1..y+height` after runtime validation. `torchCells` preserves the seven historical light positions. Ladder and solid backing columns are excluded from floor supports and remain continuous. |

Fields and the existing ladder connection are identical across each family's
three building levels. Mining unlocks one, two or three floors, six blocks apart.
There are 53 support cells on each floor: a two-block-wide gallery extending
twelve blocks from the ladder, one two-block-wide crosscut extending six blocks
to each side, and a small landing. All new excavation is below the lowest
schematic layer. This bounded shape is an ArcCivs design decision, not a claim
that it reproduces every MineColonies mining rule.
The complete vertical shaft, its landing platforms and lights precede gallery
excavation. The preview and runtime use the same shaft footprint, cobblestone
platforms and fixed torch coordinates. Existing completed cells are reused;
the plan does not remove paid lights to scan a new level. Outer cave sealing is
conditional on native terrain and uses ordinary paid, protected work receipts.

## Farm and access anchors

| Style | Soil coordinates before rotation | Water | Entry |
| --- | --- | --- | --- |
| medievaloak | `x=8..16, y=1, z=-20..-12`, excluding center `x=11..13,z=-17..-15`: 72 cells | Four existing sources at `x=11/13,y=1,z=-17/-15` | `(7,2,-16)`; alternate `(12,2,-11)` |
| nordic | `x=10..18,y=0,z=-17..-9`, excluding center: 80 cells | New `(14,0,-13)` | `(9,1,-13)` |
| caledonia | `x=9..17,y=0,z=-11..-3`, excluding center: 80 cells | New `(13,0,-7)` | `(8,1,-7)` |

The medievaloak coordinates identify the irrigated crop area in the user's
screenshot. Its central 3×3 arrangement and original barriers/paths/fences are
preserved. A schematic `barrier` is a skip marker; it is not proof that the
corresponding live cell is empty. The runtime checks the actual world.

Nordic and Caledonia contain a farm hut without an embedded crop field. Their
new fields sit beside the right side of the largest family footprint with two
untouched columns between hut and field. No new building asset or decoration
is introduced. The flat, supported soil and free space must already be valid;
this metadata never grants permission to flatten arbitrary terrain.

| Style | Existing bottom ladder | Facing | First subground cell | Floor feet Y (L1/L2/L3) |
| --- | --- | --- | --- | --- |
| nordic | `(-4,-2,-11)` | north | `(-4,-3,-11)` | `-8 / -14 / -20` |
| medievaloak | `(7,-5,-8)` | west | `(7,-6,-8)` | `-11 / -17 / -23` |
| caledonia | `(-3,-5,-10)` | east | `(-3,-6,-10)` | `-11 / -17 / -23` |

## Runtime boundary

The metadata supplies geometry; it never credits resources. Runtime owns actual
arrivals, loaded world/protection/city bounds, block expectations, inventory
receipts, finite tree recognition, seed/sapling consumption and the journal.
Farming uses actual native crop growth. Forestry removes actual tree blocks and
replants the actual root. Mining produces only the material from real removed
blocks, with no regenerated deposits. Ladder backing, ladders and lighting must
be paid from local stock. Original decorative logs and building cells are not
harvestable worksite resources. A changed world cell must be revalidated, not
replaced merely because its coordinate appears in this file.

The design follows the official MineColonies separation of
[farm huts and assigned fields](https://minecolonies.com/wiki/buildings/farmer/),
[forester zones and sapling-limited replanting](https://minecolonies.com/wiki/buildings/lumberjack/),
and [a descending shaft followed by mining levels and branches](https://minecolonies.com/wiki/buildings/miner/).
Its documented optional lucky-ore replacement is deliberately outside this
real-block contract.

## Private visual preview

`--preview-dir /private/output --preview-id <exact-id>` produces an editable
atelier recipe using the exact source voxels and the proposed worksite. Repeat
`--preview-id` for further assets. Compile it with the existing atelier CLI and
inspect its actual block-model render before expanding a visual change.
Preview crops are shown mature, on a synthetic flat terrain context; this is an
offline spatial proposal and does not prove planting, native growth, inventory
consumption, NPC pathfinding, server activation or ordinary client behavior.
Keep these generated derivatives private along with the original schematics.
