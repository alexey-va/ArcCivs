# Navigation and scenario tests

Run the commands below from the `ArcCivs` repository root. These commands use
Gradle's JUnit class filter and do not activate the opt-in private replays.

## Portable navigation and resident suites

```sh
./gradlew test \
  --tests 'ru.arc.civs.CivsNavigationSearchTest' \
  --tests 'ru.arc.civs.CivsNavigationTerrainTest' \
  --tests 'ru.arc.civs.CivsResidentNavigationTest' \
  --tests 'ru.arc.civs.CivsOpenDoorCornerRegressionTest' \
  --tests 'ru.arc.civs.CivsLongDoorApproachTest' \
  --tests 'ru.arc.civs.CivsResidentRecoveryRuntimeTest' \
  --tests 'ru.arc.civs.CivsResidentWorkReachTest' \
  --tests 'ru.arc.civs.CivsResidentCollisionsTest' \
  --tests 'ru.arc.civs.CivsResidentJourneyNavigationTest' \
  --tests 'ru.arc.civs.CivsResidentRestartJourneyTest' \
  --tests 'ru.arc.civs.CivsPathsTest' \
  --tests 'ru.arc.civs.CivsMineExitNavigationTest' \
  --tests 'ru.arc.civs.CivsMineAccessRegressionTest' \
  --tests 'ru.arc.civs.CivsMineWorkStandingTest' \
  --tests 'ru.arc.civs.CivsMineRouteEpisodeTest' \
  --tests 'ru.arc.civs.CivsMineFrontierRepairTest' \
  --tests 'ru.arc.civs.CivsRoadMaintenanceTest' \
  --tests 'ru.arc.civs.CivsResidentsRuntimeTest' \
  --tests 'ru.arc.civs.CivsAutonomyTest' \
  --tests 'ru.arc.civs.CivsResidentMealsTest' \
  --tests 'ru.arc.civs.CivsResidentMealElevationTest' \
  --tests 'ru.arc.civs.CivsTavernStaffingRuntimeTest'
```

`CivsResidentsRuntimeTest` also covers meal interruptions and resident job
recovery, so it is included for the full journey/runtime pass.

The door-corner regression pins the observed fractional starting offset and
native open-door collision panel. It checks actual packet movement for open,
closed, and mirrored door approaches, plus centered controls. A required
same-height first-cell center is retained without changing clear direct routes,
door-opening behavior, collision tolerances, or search budgets.

The long-door regression follows a clear diagonal to an already open door
4.47 blocks away. The old route failed before movement because the opener's
interaction range was applied to an open door. A protected footprint and a
world lock still deny that passage; closed doors still require a nearby opening.

The resident recovery runtime cases exercise an enclosed builder and a courier
retrying the same denied passage, including 60-second monotonic expiry across
route retries, a cancelled delayed search result and a pending cargo receipt.
Safe destinations require loaded chunks, support, collision clearance and
existing territory and occupancy rules. Actual movement or a new cargo resets
the wait; unavailable endpoints do not teleport or grant work. The portable
builder geometry is translated and synthetic; it is not a live-world capture.

The meal-elevation regression builds an explicit three-block staircase and an
upstairs food barrel. Both ordinary and autonomous residents must walk to it,
remain physically clear, observe the meal session and debit one existing meal.
Per-tick coordinates bound horizontal movement and single-block height changes;
the same actor must remain alive throughout. The fixture's `teleports` counter
also counts ordinary packet movement, so it is not an oracle for travel skips.
Two pure cases retain ordinary leisure bounds and reject points above/below an
explicit meal destination. The original runtime cases failed 2/2 without the
repair; the final four cases and existing idle/citizen checks passed 64/64.

The tavern cases cover temporary staffing by an idle builder, recovery for
accepted construction or its queue head, approaching guests and pending meal
receipts, and keeping the last producers at work regardless of worker order.
Existing visitors finish at their chosen building; new visitors use the hall
while the sole keeper returns to construction. These are domain and synthetic
resident-runtime checks, not live dining acceptance.

The paused-field regression uses the existing monotonic clock seam to expire
the 2 ms slice after 16 cells, retain its cursor and unused read quotas, and
resume at the exact next target. Semantic one-pulse assertions use a fixed
clock; production time and read budgets remain unchanged.

Local test runs use one JVM at a time by default. CI selects
`-PcivsTestForks=2`, bounded to one or two independent test JVMs. Each class
still gets a fresh fork, and each fork keeps the existing 2 GB heap limit.

## Portable Auto2 planning and worksite suites

```sh
./gradlew test \
  --tests 'ru.arc.civs.CivsAuto2PlanningRegressionTest' \
  --tests 'ru.arc.civs.CivsAuto2SupplyRegressionTest' \
  --tests 'ru.arc.civs.CivsAutonomousPlacementTest' \
  --tests 'ru.arc.civs.CivsAutonomousTerraformTest' \
  --tests 'ru.arc.civs.CivsSettlementLayoutTest' \
  --tests 'ru.arc.civs.CivsForestryGrassPlantingTest' \
  --tests 'ru.arc.civs.CivsForestryPlantingFairnessTest' \
  --tests 'ru.arc.civs.scenarios.Auto2WorksitesScenarioTest' \
  --tests 'ru.arc.civs.scenarios.CapturedNativeLowerOverlayTest'
```

The grass planting suite checks ordinary and large-tree plots on short and tall
grass, complete tall-grass pairs, exact sapling payment and zero grass drops or
tool wear. It follows planned work through validation, receipt, native changes
and replay, with several seeded plots and retained road/crown spacing. Missing
saplings, unavailable sites, protected or changed upper halves and unrelated
vegetation remain rejected. Its deterministic clock advances through the real
forest slice and read quotas; it does not lower production budgets.

`CivsAuto2PlanningRegressionTest` loads pinned fixtures from
`src/test/resources/scenarios/auto2-forest-city-geometry.json` and
`auto2-planner-shore.json`. It tests the surface planning and rejection
boundary in two portable cases; its native capture call is deliberately
observed as a boundary, not mocked into a successful world mutation. The
portable worksite replay uses the checked-in `auto2-worksites.json` fixture.
None of these tests needs private schematics, saved Auto2 state, or network
uploads/downloads.

`CapturedNativeLowerOverlayTest` has fifteen portable contract cases for joining
factual lower captures: unchanged public cells and strict unknown space,
duplicate windows, city/world mismatches, crop bounds, loaded data, exact
colliders, contradictory overlapping observations, and supplemental state
colliders that cannot replace known public geometry. Its synthetic capture
envelopes test the merge contract; they do not prove an actual resident route.

`CivsAuto2SupplyRegressionTest` uses the pinned public city geometry. The old
88-block guard rejected the FARM entrance-to-warehouse-control distance of
100.324 m and FORESTER lower-stance-to-control distance of 89.844 m. The
current source gives autonomous supply a scalar distance limit of
`max(88, min(256, 2 * radius))`; ordinary supply keeps its 88-block limit.
The regression models a cargo of up to 64 units through allocation, pickup,
and unload, checks that held stock stays constant across the transfer, and
rejects a duplicate unload. It also checks the territory cap and rejects
malformed limits. The owner reports the regression GREEN, with 31 pure supply
cases and 89 physical/Citizens/Journey cases passing in the current validation.

This allocation test does not prove NPC arrival. The true-NPC forestry replay
now retains all five canonical service residents and family links, focusing
only its physical actors. The single-producer case passed: 1,160 normal ticks,
22.911 m recorded travel, 6,000 ms observed work, one native receipt and three
wood. Its working zone and durable state survived completion and a duplicate
callback was rejected. Admission follows the actual staffed building, so the
earlier projected-roster expectation of at least 30 m was not retained.

The two-actor replay performed an actual initial three-wood pickup/dropoff and
an accepted meal. Its subsequent trip for six newly earned wood reached an
uncaptured lower cell at (-1872,55,-2728), so the intended after-work handoff
remains unproven with that input set. This is a strict fixture prerequisite;
the replay must not substitute AIR or treat it as a product defect. Separate
live city-history evidence establishes committed warehouse hauling.

For public diagnostic geometry fixtures, normalize an empty `transit: []` to
null (remove the field) for non-Road buildings, and give assigned workers a
positive `nextDeliveryAt` value.

The new `CivsResidentJourneyNavigationTest` uses `MockBukkitTestRuntime` and
`CivsWorksiteWorldFixture`, with explicit fixture collision shapes. It tests
plugin navigation behavior against those helpers; it is not a native Paper
server, Minecraft physics, chunk I/O, or player-client acceptance run.

The focused Mine V6 test-harness verification passed 65 tests and recorded 377
native worksite journal receipts across restart, duplicate-delivery, and
frozen-V5 checks. This is harness evidence, not live Paper or client acceptance.
The mine-cover replay keeps a controlled natural-stone continuation below the
public Y56 surface boundary; it is a declared fixture continuation, not an
underground world capture.

The V6 frontier regression uses a generated cave behind paid ladder backing.
During the new deep spine, the immediate ladder shell must close without
requiring unreachable walls across the old wide footprint. Five portable cases
cover that local shell, the unchanged V5 boundary row, widening after the spine,
reopening an exact paid seal, and rejection of liquids, locked blocks and
unreachable work. Frozen V2–V5 plan orders remain unchanged.

The opt-in resident-mining replay also runs the actual Citizens and Service
callbacks. Its original V5 control completes one economic cycle. The current
V6 case must continue after its first credit and complete a second cycle: 60
native commits, 120,000 ms observed work and six stone in the recorded run.
Native work itself grants no economic credit. The fixture retains all five
service residents and family links, focusing one physical actor. This is model
evidence with a declared natural-stone continuation, not live Paper acceptance.

Recorded verification from the task owner's session on 2026-10-27 22:58 UTC
(2026-10-28 Moscow time):
`CivsResidentJourneyNavigationTest` passed 26/26 cases, and the four planner,
placement, terraform, and layout classes passed 70/70 combined (2 + 22 + 28 +
18). These are recorded run results, not a guarantee for future runs.

The forest position-witness regression has eight controlled geometries. Three
fail on the old source: far-to-near movement, another safe angle, and reopening
one deferred tree while retaining another. The fix keeps the same zone and
cursors, revalidating only deferred targets now within reach after a standing-cell
change. Walls, distant movement and movement within the same cell remain negative
controls; unchanged positions cause no new rays. The eight cases and the 80
existing forestry/context/native cases passed together. This isolates a cache
dependency and does not identify the geometry of an uncaptured live tree.

`CivsResidentRestartJourneyTest` recreates Citizens, navigation, Store and Service
in the same fixture world/JVM, loading actual city and journal files. Three cases
cover mid-walk restart, recovery of an actual pending receipt with a late callback
to the retired owner, and a new obstruction. Fresh admission must walk more than
eight metres through clear swept body positions, complete observed work and save
one result; a duplicate token cannot add credit. NPC feet and routes are not
claimed to persist. These cases and the existing runtime/journey/worksite cases
passed 158/158. This is component recreation, not an OS crash or native Paper
acceptance.

## Coverage map

| Area | Existing coverage | Remaining boundary |
| --- | --- | --- |
| Grades and roads | Navigation tests cover one-block rises/descents and stair travel. `CivsRoadMaintenanceTest` covers short captured hills, composed local cuts, rejected long slopes, and receipt replay. | These fixtures do not establish route quality across arbitrary long natural grades. |
| Auto2 producer-to-warehouse supply | `CivsAuto2SupplyRegressionTest` covers public-geometry FARM/FORESTER transfers through model allocation, pickup and unload. The canonical single-producer replay earns three wood; a pair completes an initial pickup/dropoff and a meal. | The pair's later after-work transfer still needs an uncaptured lower cell. Model transfer tests and the initial handoff do not prove that later transfer. |
| Slabs, stairs, and shaped collisions | `CivsResidentNavigationTest` covers lower slabs and changed geometry; `CivsResidentCollisionsTest` checks gates, doors, and swept body collisions. The new journey class covers top slab → bottom slab → stair → deck across four directions, both travel directions, and three speeds. | Corner-shape checks remain fixture-defined collision behavior, not native client movement proof. |
| Fences, gates, and doors | Closed gates can be treated as passages and opened during packet motion; tall fences/gates are swept for collision. Door-corner collision and route replanning after shape changes are covered. | No live-server/client acceptance is implied by these helper tests. |
| Mine exits and work reach | `CivsMineExitNavigationTest`, `CivsMineAccessRegressionTest`, `CivsMineWorkStandingTest`, and `CivsMineRouteEpisodeTest` cover ladders, headroom, supported exits, standing positions, and recovery behavior. | Mine-exit coverage does not prove every uncaptured stair or shaft layout. |
| Chunks, cache, and budgets | Navigation suites cover unknown chunk frontiers, unload/reload invalidation, world separation, bounded cache behavior, route budgets, cancellation, and replanning. The new journey class unloads/reloads the active chunk in both travel directions and continues after replanning. | These tests exercise test-owned chunk/cache seams, not actual Paper chunk streaming under a live client. |
| Dynamic obstruction and stuck recovery | Resident navigation/runtime tests cover inserted walls, changed stair geometry, occupied routes, waits, detours, progress accounting, repeated mine-route episodes, and recovery. Component-recreation cases start again from fresh admission and bypass a newly inserted obstacle. | A full OS/process crash in the middle of walking is not covered. |
| Food interruption and duplicate durability | Resident runtime tests invalidate meal work when its assumptions change and resume jobs without granting travel credit. Meal-elevation cases require actual stair travel and one existing meal debit while retaining ordinary leisure bounds. Road/mine tests replay receipts; component recreation checks real arrival, persisted receipt recovery, an old-owner callback and duplicate-token rejection. | The combined sequence has component-level coverage, not an actual process-crash or native Paper run. |

The two recent journey gaps are now covered by portable cases: mid-journey
chunk unload/reload and the top-slab-to-bottom-slab-to-stair/deck transition.
Component recreation now covers walking, arrival and duplicate receipt recovery;
the remaining boundary is a full process crash on native Paper.

## Opt-in local replays with private inputs

The environment-variable names below are read directly by the scenario test
classes under `src/test/kotlin/ru/arc/civs/scenarios/`. With no enable variable,
these private replays remain disabled. Keep private state, captures, and
schematic snapshots in local `/private/tmp` paths; never add them to Git. They
are not required by regular CI and do not need to be uploaded or downloaded for
CI.

| Scenario | Enable variable | Required local inputs | Optional input |
| --- | --- | --- | --- |
| Real private-blueprint worksite replay | `CIVS_SCENARIO_SCHEMATICS_ROOT` | Private schematic root | `CIVS_SCENARIO_ATELIER_ROOT` (defaults to `../ruscrafting-ops/scripts/location-atelier`) |
| Auto2 mine cover | `CIVS_SCENARIO_MINE_STATE` | `CIVS_SCENARIO_PUBLIC_MAP`, `CIVS_SCENARIO_NATIVE_FACTS`, `CIVS_SCENARIO_SCHEMATICS_ROOT` | `CIVS_SCENARIO_CITY_STATE` (checks the source building and mine plan), `CIVS_SCENARIO_ATELIER_ROOT` |
| Auto2 resident forestry | `CIVS_SCENARIO_CITY_STATE` | `CIVS_SCENARIO_CITY_MAP`, `CIVS_SCENARIO_NATIVE_FACTS` | `CIVS_SCENARIO_NATIVE_OVERLAYS` (factual lower native captures), `CIVS_SCENARIO_NATIVE_COLLISION_CAPTURES` (exact state colliders only) |
| Auto2 resident mining | `CIVS_SCENARIO_MINING_CITY_STATE` (V5), `CIVS_SCENARIO_MINING_CURRENT_CITY_STATE` (V6) | `CIVS_SCENARIO_PUBLIC_MAP`, `CIVS_SCENARIO_NATIVE_FACTS`, `CIVS_SCENARIO_SCHEMATICS_ROOT`; V6 also requires `CIVS_SCENARIO_CURRENT_MINE_CAPTURE` and `CIVS_SCENARIO_CURRENT_MINE_SHA256` | Factual lower overlays; exact bush/farm collider captures with hashes; `CIVS_SCENARIO_ATELIER_ROOT` |

Enable only the replay whose local inputs are available. For example:
Gradle does not track these runtime environment variables or external files as
test-task inputs, so each opt-in invocation uses `--rerun-tasks`. Keep it when
replaying after a disabled run or whenever the supplied state, map, native
facts, schematic, or atelier root changes; otherwise the filtered task may be
considered up-to-date.

Without the enable variable, Kotest disables the one blueprint case, two mine
cases, two resident-forestry cases, or the two independently enabled
resident-mining cases for that replay. Mining uses its own enable variables;
enabling forestry must not activate mining's private-blueprint requirements.
They do not substitute
placeholder private state or fetch private inputs; the optional paths are read
only when explicitly supplied locally.

For missing native terrain below public Y56, add
`CIVS_SCENARIO_NATIVE_OVERLAYS=/private/tmp/native-lower-captures/captures.json`
to the resident-forestry invocation. This optional file is a JSON array of the
unmodified successful native operator-capture objects, with only the
`ARCCIVS_TERRAIN_CAPTURE ` line prefix removed. Keep the original receipts
and capture hashes locally. The consumer checks the source city/world,
complete 891-cell availability, entity-ticking chunks, coordinates and native
colliders. It joins only recorded cells below Y56 within the public X/Z crop,
before the single fixture decode. Public cells and their known colliders stay
unchanged, and every uncaptured cell remains unavailable in the strict replay.
Supplying this file does not itself establish two-actor pickup/dropoff or live
acceptance; the actual resident callbacks and receipt assertions must pass.

The lower join also accepts the original resident-feet capture format when
`state` is absent, `source` is `fresh-native-command`, and the finite numeric
`actual` position floors to the fixed window center. An existing
`capture_center` must agree. This legacy branch retains all city/world,
availability, chunk, voxel and collider checks; errored records and malformed
new operator captures remain rejected. Four added legacy cases bring the
portable overlay/collider contract suite to 15 passing cases.

If the pinned facts lack a visible block state's collider, optionally provide
`CIVS_SCENARIO_NATIVE_COLLISION_CAPTURES=/private/tmp/native-state-facts/captures.json`.
It uses the same raw capture-array format and validates the city/world,
891-cell availability, ticking chunks and native block-local collision boxes.
Only collider facts for observed states are consumed; none of these later
captures replaces a public world cell. Contradictory observations and attempts
to replace an already known public collider fail. Keep separate capture times
and hashes: combining these inputs does not make an atomic world snapshot.

```sh
CIVS_SCENARIO_SCHEMATICS_ROOT=/private/tmp/private-schematics \
  ./gradlew test --rerun-tasks --tests 'ru.arc.civs.scenarios.PrivateBlueprintScenarioTest'

CIVS_SCENARIO_MINE_STATE=/private/tmp/auto2-mine-state.json \
CIVS_SCENARIO_PUBLIC_MAP=/private/tmp/public-map \
CIVS_SCENARIO_NATIVE_FACTS=/private/tmp/native-facts.json \
CIVS_SCENARIO_SCHEMATICS_ROOT=/private/tmp/private-schematics \
  ./gradlew test --rerun-tasks --tests 'ru.arc.civs.scenarios.Auto2MineCoverScenarioTest'

CIVS_SCENARIO_CITY_STATE=/private/tmp/auto2-city-state.json \
CIVS_SCENARIO_CITY_MAP=/private/tmp/public-map \
CIVS_SCENARIO_NATIVE_FACTS=/private/tmp/native-facts.json \
  ./gradlew test --rerun-tasks --tests 'ru.arc.civs.scenarios.Auto2ResidentForestryScenarioTest'
```

The mine replay's public capture does not observe the subsurface below public
Y56. It therefore adds a controlled continuation of natural stone beneath
Y56; this is a modeled mine fixture, not a complete underground world capture.
Public `opaque-fill` cells retain occupied geometry while their original block
IDs remain unknown. Native facts pin collision geometry for each known state;
they do not establish the original identity of blocks inside opaque fills. The
separate native-block snapshot 891 pins actual block states only for its
sampled cells. These replays still use test helpers and do not replace native
Paper/server or player-client acceptance.

For resident mining, `CIVS_SCENARIO_NATIVE_COLLISION_CAPTURE` and
`CIVS_SCENARIO_NATIVE_COLLISION_SHA256` can supply an exact missing state
collider; the farm equivalents are `CIVS_SCENARIO_NATIVE_FARM_CAPTURE` and
`CIVS_SCENARIO_NATIVE_FARM_SHA256`. Raw native captures supply observed collision
facts without replacing public block identities. Missing facts and uncaptured
space continue to fail explicitly. The current V6 clock starts at its supplied
native capture's timestamp; the original V5 control retains its public capture
timestamp. Separate capture epochs remain recorded; the composed fixture is not
an atomic world snapshot.

## Portable terrain resources

Small, synthetic or otherwise publishable terrain fixtures belong under
`src/test/resources/scenarios/`. `RecordedNativeMap.fromResourceRle(path, sha256)`
loads a pinned JSON resource and can seed `CivsWorksiteWorldFixture` or supply
collision information to a strict captured-world fixture. The existing version 1
resources remain supported. New resources should use version 2; the minimal
example is `scenarios/terrain-fixture-v2-example.json`.

The file contains `version: 2`, `order: "y,z,x"`, inclusive `bounds` with
`min` and `max` positions, a human-readable `provenance` object, a `palette`,
and `runs`. Each palette entry contains exact `block_data` and may include
`collision` boxes in block-local coordinates. The optional boolean
`unknown_opaque_identity` marks a solid shape whose actual material was hidden
by capture occlusion; an observed stone block must leave that flag unset.
`runs` is a flat list of `[length, palette index]` pairs, with x varying fastest,
then z, then y. Index `-1` means the cell was not observed at all. Runs must
cover the entire bounds exactly and the loader limits fixtures to two million
cells. Unknown cells are omitted from `states`; do not use `seed()` alone to
claim they are empty because its destination fixture may have a default block.
Strict replays must reject missing observations instead of inventing terrain.

Keep private player or settlement data outside Git; a local capture can be read
with `RecordedNativeMap.fromFile(path, sha256)` for a native capture or
`RecordedNativeMap.fromRleFile(path, sha256)` for a converted RLE fixture. Record
capture time and source, and do not combine separate observations as though they
were an atomic snapshot.

Convert one or more native capture JSON files into this resource format with:

```sh
python3 scripts/terrain_capture_to_fixture.py /private/tmp/capture-1.json /private/tmp/capture-2.json \
  --output /private/tmp/terrain-fixture.json
shasum -a 256 /private/tmp/terrain-fixture.json
```

The converter leaves unobserved cells unknown and rejects conflicting overlap,
conflicting collision shapes for the same state, and failed captures. Review the
source, coordinates and contents before copying a fixture into test resources.

To keep an exact target box even when some captures are missing, pass
`--bounds MIN_X MIN_Y MIN_Z MAX_X MAX_Y MAX_Z`. The converter crops inputs to
that box and writes every unobserved cell as unknown. Local RLE files can be
opened with `RecordedNativeMap.fromRleFile` using the returned SHA-256.

`CivsAuto2ForestCaptureTest` is an opt-in check for a **partial** captured Auto2
field. Set `CIVS_AUTO2_FOREST_CITY` and `CIVS_AUTO2_FOREST_CITY_SHA256` to a
pinned raw Settlement snapshot, and `CIVS_AUTO2_FOREST_TERRAIN` and
`CIVS_AUTO2_FOREST_TERRAIN_SHA256` to a pinned converted RLE capture. The test
checks the actual forester footprint, observed saplings and unsampled columns;
it does not simulate planting or claim that the partial snapshot covers the
whole field. Keep the raw snapshots outside Git. A full planting replay needs
all block observations required by the search and its safety checks.

`CivsAuto2ObservedPlantingTest` uses those same pinned inputs for a worksite
regression: it starts with the captured city and blocks unobserved and out-of-field
columns using explicit synthetic sapling barriers, then exercises work planning,
validation, native-state receipts and replay until at least 12 saplings occupy
observed columns (the target can be raised with `CIVS_AUTO2_FOREST_TARGET_SAPLINGS`).
The boundary is intentionally more restrictive than the
actual world; it does not turn missing blocks into evidence. The captured vertical
slice ends at y69 and capture times differ, so this remains a partial, simulated
replay, not proof of natural tree growth or an atomic live-world snapshot.
