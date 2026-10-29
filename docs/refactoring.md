# Settlement runtime refactoring

The target is a maintainable common runtime for player and autonomous villages.
Planning differences must not create a second work, movement, food or inventory
engine. A harmless world edit should refresh a proposal or rediscover a target;
it must not quarantine the town. Accepted item transfers, permissions, lifecycle
identity and durable payments remain authoritative.

## Source inventory, 29 October 2026

The initial pass covers all 122 production Kotlin files and 26 scripts. The large
owners requiring a responsibility review are:

| Original owner | Lines before | Responsibility to separate | Status |
| --- | ---: | --- | --- |
| CivsCitizens | 4949 | Actor lifecycle, life, navigation, collisions, work and leisure | 952 lines; isolated full suite green |
| CivsService | 3878 | Application authority, player requests, placement and worker commits | 1246 lines; 323 focused cases and literal/API comparisons green |
| CivsUi | 2680 | Settlement, building, resident and inventory pages | 463 lines; 116 cases and 99 body-equivalence checks green |
| CivsWorksites | 2312 | Shared discovery context, forestry, farming and mine work | 486 lines; 224 contract cases green; coherent native-work counters match baseline |
| CivsStorage | 2165 | Preparation, native container access and journal reconciliation | 954 lines; 171 passed, 5 existing skips |
| CivsWorld | 1708 | Bounded scan scheduling, capture, terrain and native writes | 1002 lines; 172 passed, 2 existing skips |
| CivsRules | 1093 | Shared invariants, construction and resident transitions | 699 lines; 53 focused cases green |
| CivsResidentNavigation | 877 | Terrain capture, solver jobs and movement ownership | Route 600, geometry 220, state 73; 100 cases green |
| scripts/curate-catalog.py | 1034 | Catalog parsing, conversion and export | 796 lines plus geometry module; 49 cases green |

Line counts identify review candidates, not a reason to split a cohesive algorithm.
Extracted state belongs to a concrete domain owner. Avoid a generic behavior
framework, a new callback registry or a second path around the existing journal.

The remaining owners above 1,000 lines have specific responsibilities:
`CivsService` (1,246) retains publication, loading and mutation authority;
`CivsWorld` (1,002) retains aggregate scan admission and native capture;
`CivsMineWorksite` (1,073) contains the cohesive mine discovery, safety and
versioned recovery algorithm. Their request/activity state has its own concrete
owners rather than additional independent lifecycles in the roots.

## Change-sensitive checks

- Ordinary schematic revalidation already tolerates modified before-images.
- Accepted terrain batches already replay the intended write against current
  authority without requiring an old terrain before-image.
- Fresh terrain capture and route analysis still compared harmless natural
  ground against an earlier proposal. Two regressions reproduce cancellation;
  remove this comparison after current ownership, natural-terrain and container
  checks, and capture the actual current original instead.
- Fresh work that produces real items must still validate its actual target and
  debit. A stale discovery invalidates that worker's proposal, not the whole town.
- Old cosmetic receipts are retired without replaying over later player edits.
  Keep this separate from item-bearing receipt recovery.
- Production-only changes incorrectly invalidated the plan-board geometry key
  and retained autonomous access readiness. Both regressions failed on the old
  comparison and pass with a shared geometry comparison; moving an entrance
  still invalidates the proof. The focused Service/PlanBoard run is 257 green.

The [module map](runtime-modules.md) records the architecture comparison with
the pinned MineColonies sources and the actual extension seams. Catalog recipe
identity now includes the extracted geometry module's bytes as well as the
entry script; a scene-only change is covered by a transitive-digest regression.

Independent source reviews found no new P1/P2 in the worksite, application,
native-scan, resident-lifecycle, navigation, storage and dialog integration
paths. Root publication/loading and the accepted item journal remain singular.
All original root APIs remain: Citizens 64, Service 141, Storage 96 and UI 10.
The 99 moved screen bodies and extracted literal segments were compared with
the original source independently of identifier changes.

Three coherent mine counter runs (baseline, extraction and profession contract)
performed identical native work. Their varying wall times do not establish a
production regression. The later mixed-bytecode timing experiment failed with
`NoClassDefFoundError` and supplies no performance result.

## Verification and acceptance

The final coherent 0.1.121 snapshot passed `test shadowJar` on 29 October 2026:
187 suites, 2,296 cases, zero failures/errors, and 24 existing skips (2,272
passes). The skip identities match the initial integrated run. Python discovery
passed 49 cases; the central Core consumer verifier and production JAR verifier
passed against Core 2.7.75 / host ArcCore 0.1.25.

The initial integrated run exposed one obsolete `world-changed` expectation.
Its SITE regression now independently proves refreshed permitted terrain and
rejection of an uncovered high obstacle; its 22-case focused suite passed before
the final full run. Native container, ownership and Lands guards remain intact.
The unreachable second QUARRY receipt branch was removed; the earlier dedicated
mine validator remains authoritative.

Build and test inputs were frozen in one isolated output tree, without borrowed
compiled tests. Source/artifact manifests and XML reports are retained with the
release evidence. CI adds disposable MySQL verification on the published source.
Spawn delivery, process readiness, loaded version/hash and observed Auto2 work
must be recorded separately in the operational release receipt. Native client
appearance requires its own observation.
