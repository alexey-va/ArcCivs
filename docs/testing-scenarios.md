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
  --tests 'ru.arc.civs.CivsResidentWorkReachTest' \
  --tests 'ru.arc.civs.CivsResidentCollisionsTest' \
  --tests 'ru.arc.civs.CivsResidentJourneyNavigationTest' \
  --tests 'ru.arc.civs.CivsPathsTest' \
  --tests 'ru.arc.civs.CivsMineExitNavigationTest' \
  --tests 'ru.arc.civs.CivsMineAccessRegressionTest' \
  --tests 'ru.arc.civs.CivsMineWorkStandingTest' \
  --tests 'ru.arc.civs.CivsMineRouteEpisodeTest' \
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
  --tests 'ru.arc.civs.scenarios.Auto2WorksitesScenarioTest' \
  --tests 'ru.arc.civs.scenarios.CapturedNativeLowerOverlayTest'
```

`CivsAuto2PlanningRegressionTest` loads pinned fixtures from
`src/test/resources/scenarios/auto2-forest-city-geometry.json` and
`auto2-planner-shore.json`. It tests the surface planning and rejection
boundary in two portable cases; its native capture call is deliberately
observed as a boundary, not mocked into a successful world mutation. The
portable worksite replay uses the checked-in `auto2-worksites.json` fixture.
None of these tests needs private schematics, saved Auto2 state, or network
uploads/downloads.

`CapturedNativeLowerOverlayTest` has eight portable contract cases for joining
factual lower captures: unchanged public cells and strict unknown space,
duplicate windows, city/world mismatches, crop bounds, loaded data, exact
colliders, and contradictory overlapping observations. Its synthetic capture
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

This allocation test does not prove NPC arrival. A separate true-NPC,
single-actor forestry replay passed 1/1 at 82.176 m in 6,000 ms with one
receipt. A later two-actor attempt performed an actual nine-wood pickup but
did not reach warehouse dropoff: strict captured-world checks rejected missing
lower native cells on the return search. That attempt also projected the
service roster, which changed normal staffing and recruitment. The next replay
retains the complete canonical service roster and scopes only physical actors.
Refresh factual overlays before treating the complete chain as arrival proof.

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

Recorded verification from the task owner's session on 2026-10-27 22:58 UTC
(2026-10-28 Moscow time):
`CivsResidentJourneyNavigationTest` passed 26/26 cases, and the four planner,
placement, terraform, and layout classes passed 70/70 combined (2 + 22 + 28 +
18). These are recorded run results, not a guarantee for future runs.

## Coverage map

| Area | Existing coverage | Remaining boundary |
| --- | --- | --- |
| Grades and roads | Navigation tests cover one-block rises/descents and stair travel. `CivsRoadMaintenanceTest` covers short captured hills, composed local cuts, rejected long slopes, and receipt replay. | These fixtures do not establish route quality across arbitrary long natural grades. |
| Auto2 producer-to-warehouse supply | `CivsAuto2SupplyRegressionTest` covers public-geometry FARM/FORESTER transfers through model allocation, pickup, and unload, plus ordinary-distance and malformed-limit boundaries. A separate single-actor true-NPC forestry replay passed; a later pair performed an actual pickup. | The pair still has no complete dropoff result; native return-search data and the full canonical service roster must be present. Model transfer tests are not NPC arrival acceptance. |
| Slabs, stairs, and shaped collisions | `CivsResidentNavigationTest` covers lower slabs and changed geometry; `CivsResidentCollisionsTest` checks gates, doors, and swept body collisions. The new journey class covers top slab → bottom slab → stair → deck across four directions, both travel directions, and three speeds. | Corner-shape checks remain fixture-defined collision behavior, not native client movement proof. |
| Fences, gates, and doors | Closed gates can be treated as passages and opened during packet motion; tall fences/gates are swept for collision. Door-corner collision and route replanning after shape changes are covered. | No live-server/client acceptance is implied by these helper tests. |
| Mine exits and work reach | `CivsMineExitNavigationTest`, `CivsMineAccessRegressionTest`, `CivsMineWorkStandingTest`, and `CivsMineRouteEpisodeTest` cover ladders, headroom, supported exits, standing positions, and recovery behavior. | Mine-exit coverage does not prove every uncaptured stair or shaft layout. |
| Chunks, cache, and budgets | Navigation suites cover unknown chunk frontiers, unload/reload invalidation, world separation, bounded cache behavior, route budgets, cancellation, and replanning. The new journey class unloads/reloads the active chunk in both travel directions and continues after replanning. | These tests exercise test-owned chunk/cache seams, not actual Paper chunk streaming under a live client. |
| Dynamic obstruction and stuck recovery | Resident navigation/runtime tests cover inserted walls, changed stair geometry, occupied routes, waits, detours, progress accounting, repeated mine-route episodes, and recovery. | A full process restart in the middle of a resident walking episode is not covered. |
| Food interruption and duplicate durability | Resident runtime tests invalidate meal work when its assumptions change and resume jobs without granting travel credit. Meal-elevation cases require actual stair travel and one existing meal debit while retaining ordinary leisure bounds. Road and mine tests replay durable receipts and check for duplicate work; resident delivery tests cover an exactly-once arrival in-process. | Walking episode + arrival + duplicate durable receipt across a process restart remains an uncovered combination. |

The two recent journey gaps are now covered by portable cases: mid-journey
chunk unload/reload and the top-slab-to-bottom-slab-to-stair/deck transition.
The remaining concrete gap is crash/restart through the complete walking,
arrival, and duplicate-receipt sequence.

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
| Auto2 resident forestry | `CIVS_SCENARIO_CITY_STATE` | `CIVS_SCENARIO_CITY_MAP`, `CIVS_SCENARIO_NATIVE_FACTS` | `CIVS_SCENARIO_NATIVE_OVERLAYS` (factual lower native captures) |

Enable only the replay whose local inputs are available. For example:
Gradle does not track these runtime environment variables or external files as
test-task inputs, so each opt-in invocation uses `--rerun-tasks`. Keep it when
replaying after a disabled run or whenever the supplied state, map, native
facts, schematic, or atelier root changes; otherwise the filtered task may be
considered up-to-date.

Without the enable variable, Kotest disables the one blueprint case, two mine
cases, or two resident-forestry cases for that replay. They do not substitute
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
