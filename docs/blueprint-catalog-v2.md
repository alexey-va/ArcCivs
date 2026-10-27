# Private blueprint catalog v2

The public repository contains the recipe, runtime reader and checks. The source
packs, schematics, per-cell receipts, complete catalog and native previews remain
in private artifact storage. None is bundled in the plugin JAR.

The inventory has **14,830 exact source IDs from 36 styles**. Every row receives
one verdict: `READY`, `REJECTED` with a concrete reason, or `METADATA_ONLY`.
Mapped functional source levels 4–5 are metadata only: the current supported
maximum is level 3. A source level is never relabelled as a different game level.
A rejected row is accounted for, but is not a usable placement or a claimed fix.

## Identity and upgrade contract

Functional roles follow the source's primary MineColonies hut, not a filename
substring. Alternate source families remain independent. Other source variants
use `DECORATION`, maximum level 1, without workplaces, beds or production.

The immutable payload ID is
`civs/v2/<style>/<family>/<recipe-and-source-revision>/level<1..3>`.
The revision includes the exact recipe inputs and every source hash in that
family. A family publishes only after all its supported levels pass. A failure
at its last level cannot leave orphan payloads from an earlier level in the pool.

New placements choose a style and, where applicable, a family/variant. Automatic
settlements prefer their chosen style, then deterministically select a READY
family from another style when necessary. The automatic planner prefers a
family capable of its required maximum and skips unavailable upgrades.
Existing buildings retain their schematic ID: upgrades resolve the exact family
and revision, including retained older v2 entries. The legacy 153 IDs keep their
legacy resolver and bytes. No old schematic is replaced by this migration.

All supported levels share the maximum-level X/Z frame and public front.
Per-level source ground is aligned before taking the union frame. The common
outside entrance and forecourt have a supported route to an authored door or
open entrance. Lower levels can have an unfinished or differently located door;
the maximum supported level determines the common orientation.

## Ground, access and materials

Explicit source ground tags have priority. The pinned normalizer's exposed
terrain policy is retained. When it can only guess `primaryY - 1`, the recipe
may use a broad exterior source contact plane instead. It must have native-valid
standing space and enough boundary contact; an enclosed basement floor or an
upper roof does not qualify. Receipts record the exact boundary coordinates and
states used as evidence.

A source barrier is a terrain placeholder, not clearing air. Barriers at or below
the selected ground become paid dirt/support. Explicit AIR basement volumes and
all authored physical blocks stay intact. The exterior forecourt and its bottom
support are also ordinary paid construction cells. This removes reliance on an
imaginary floor while keeping the source's rooms and elevations.

Functional hut markers are consumed and one supported exterior control marker is
created. Runtime installs its existing control barrel through the existing
control receipt. Decoration consumes the technical marker as AIR and has no
physical control barrel. Its catalog `control` coordinate is only the common
alignment point, not a native container grant.

Native collision AABBs drive the offline 0.6 × 1.8 standing graph, one-block
steps, openable doors/gates, and four-block container rays. Native endpoints on
carpet or a dirt path can prove the same integer cell within 1/8 block; this
projection never invents an edge or bypasses collision checks.

Each source rack and literal container remains a real chest or barrel. Stacked
racks use accessible barrel faces; clear lids may use reciprocal double chests.
Every container must have bounded grounded support and a reachable face. The
recipe does not turn an inaccessible source container into a decorative shelf.
WAREHOUSE requires at least one real accessible chest.

Water checks use the pinned native state predicates and a bounded conservative
flow closure, including waterlogged sources and falling-water behavior. Small
banks are permitted only in new exterior padding and are paid cells. Lava may
remain in a closed noncombustible hearth with a safe approach; source fire must
have a safe noncombustible support. This is not a loaded native-world fluid
simulation. Neighbor-sensitive states and the city's fire protection still need
runtime validation.

## Worksite and dining metadata

`worksite` contains only bounded coordinates and scalar rules, not voxel arrays:

- FORESTER gets its entrance and bounded outside search area.
- FARM uses the existing paid 9 × 9 field contract outside the reserved maximum
  building footprint. Authored garden scenery is preserved; it is not credited
  as free crops or resources.
- QUARRY must expose a reachable authored ladder. Its extension through empty
  union-frame padding has exact backing, and all levels retain the same shaft.
  A separate repair can reopen a short cap above that same authored ladder:
  at most four new rungs, existing full solid backing at every rung, AIR and at
  most one natural-soil cap. Crafted blocks, rooms and hut markers are never cut.
  Fluids, container access and the final shaft approach are checked again.
- `upgradeSchematics` maps exact family levels to their immutable IDs, including
  the mine family's future paid-cut provenance.
- A separate dining enrichment recognizes at most six existing, grounded,
  opposed bottom-stair chairs at a real table. It verifies the native adult
  seated envelope, adjacent swept transition and entrance route, and checks
  each chair against its exact source transform. Metadata stores integer chair
  base blocks; the runtime derives the current surface, facing and approach.
  An unproved seat stays absent. No chairs or schematic bytes are added.

The dining overlay is applied **before** comparison with a previous accepted
catalog. A future rerun cannot rewrite accepted dining metadata under the same
ID. The overlay's script, exact base catalog and geometry input hashes are saved
in the final manifest and matching receipts. Runtime revalidates every seat.

## Private pipeline

Use the pinned private source library, atelier decoder/normalizer and native
block facts described in the recipe constants. The block facts came from
Purpur 1.21.11 offline bootstrap and `EmptyBlockGetter`, not a running server.
They cover all 9,312 converted palette states. The exact facts hash is
`8958e27b69248f53aa3015793d345207e979987fe0401574a362b9a21912050f`.
The converter, normalizer, rotation map, source inventories and repository
commits are also checked and recorded. A mismatch fails the pipeline.

These commands illustrate one complete recipe invocation; all paths below are
private artifact paths selected by the operator. Independent style partitions
can run the same frozen recipe in separate fresh directories. Their union must
be exactly the complete inventory, and every batch must have the same recipe.

```sh
python3 scripts/curate-catalog.py \
  --library "$CIVS_LIBRARY" --atelier "$CIVS_ATELIER" \
  --native-facts "$CIVS_NATIVE_FACTS" --out "$CIVS_BATCH"

python3 scripts/assemble-catalog.py \
  --batch "$CIVS_BATCH" --inventory "$CIVS_LIBRARY/inventory/files.jsonl" \
  --out "$CIVS_BASE"

python3 scripts/enrich-tavern-dining.py \
  --root "$CIVS_BASE" --library "$CIVS_LIBRARY" --atelier "$CIVS_ATELIER" \
  --native-facts "$CIVS_NATIVE_FACTS" --out "$CIVS_DINING_OVERLAY"

python3 scripts/assemble-catalog.py \
  --batch "$CIVS_BASE" --inventory "$CIVS_LIBRARY/inventory/files.jsonl" \
  --dining-overlay "$CIVS_DINING_OVERLAY" --out "$CIVS_FINAL"
```

Repeat `--batch` for multiple partitions. On a later release, add
`--previous-root "$CIVS_PREVIOUS_FINAL"` to the **last** assembly. That step
retains accepted old revisions and refuses changed rows under an accepted ID.
All outputs must be fresh; accepted trees are never overwritten.

The final assembler verifies exact source coverage, no duplicate source/output
IDs, complete upgrade families, payload hashes and receipt equality. Only
manifest-authorized READY and explicitly retained payloads are copied. Files
left in an input directory by a rejected family cannot enter the final tree.
Publication uses a staging directory followed by one rename.

`coverage.json` records the 36-style × type matrix, ready families/levels,
rejection reasons, metadata-only sources and dining proof counts. The runtime
reads only the assembled `civs/v2/catalog.json`; a partial batch cannot activate.
It bounds catalog size, retained entries, metadata depth/nodes, frame dimensions,
preview surface and worksite coordinates, and loads payloads by exact hash.

## Validation and preview

Run `python3 -m unittest scripts/test_curate_catalog.py` for synthetic storage,
water, terrain-contact, mine-cap, dining and immutable assembly regressions.
The plugin's catalog, placement, upgrade, price, protection and UI tests verify
runtime consumers. Gradle is serialized by the integration owner.

A source-backed offline preview is exported with
`scripts/export-catalog-preview.py --root ... --id ... --atelier ... --out ...`.
The existing atelier `render-chunky.py` then renders the exact block geometry
with the pinned vanilla 1.21.11 models and textures. The export records both the
source payload hash and the derivative render schematic hash; only technical
skip/control markers differ. Native UI previews use the compiled test exports
and `scripts/render-settlement-ui.py`, including source hash checks.

READY means the recipe's checks passed. Source verification, native offline
rendering, runtime placement/recovery and observation in a real client are
separate evidence. No catalog count or concept render proves live acceptance.

## Decoration accounting

Ordinary decoration uses the existing captured native construction bill.
Automatic decoration projects that saved bill into its closed stock: one WOOD
per billed item in the pinned vanilla timber tag set, one STONE per other billed
item, FOOD zero. A missing bill is invalid; an explicitly empty bill remains
empty under the existing free-water convention. Replay never reprices an
accepted order from a later catalog.

There are eight separate decorative slots in an ordinary city and two in Auto.
The functional limit remains 16, infrastructure 96 and NPCs 48. DECORATION does
not produce jobs, beds, research or manufactured goods. Existing editor rights
still permit the owner/member editor to harvest native city blocks; non-editors
remain blocked. The preceding balance review is in
[decoration-construction-assessment.json](decoration-construction-assessment.json).
