# Mine V7 finite continuation and lava clearance

This is a source-and-test assessment for candidate ArcCivs 0.1.113. Source
review and focused native-fixture checks have passed; final package, delivery,
activation and live confirmation are pending. The native
exact-capture scenario is fixture evidence; its readback is not production
proof.

V7 appends one lower floor to the V6 plan while retaining every V6 ordinal as
the exact prefix. `CivsMineV7MigrationTest` checks this for three mine styles
and levels 1–3, verifies a finite 1,599-step continuation (990 distinct planned
AIR-cut cells and four ladder placements), and confirms the lower floor is
four blocks below the former lowest floor. The plan adds one floor per
supported building level, so one quarry has at most three appended floors over
its lifetime. A first-V7-receipt test verifies that sparse V6 proof remains
frozen and that an unfinished V6 branch cannot be paid by migration.

For item sensitivity, the plain-STONE scenario assumes one native COBBLESTONE
drop per rock cleared: 990 planned AIR cuts plus up to four additional native
ladder-cell clearance cuts give 994 COBBLESTONE per added floor. The geometry
has 410 planned COBBLESTONE placements; four sound backing walls may remain
native, while the other 406 modeled placements consume a COBBLESTONE and
return one from the replaced STONE, net zero under this drop assumption.
The same floor plans four LADDER placements, 50 TORCHES, 16 timber fences,
48 logs and 81 planks. These are geometry and item-flow assumptions, not
guaranteed drops: natural AIR/caves can yield nothing and need support, while
ore mix, tool-dependent drops, safety repairs, tool wear and later player
removal are not priced or measured.

The low/base/high scenario models one quarry and 0/1/3 added floors on its
first active day. Three lifetime floors yield at most 2,982 modeled
COBBLESTONE for one quarry, 29,820 for ten and 47,712 for sixteen. These are
finite item counts, not a recurring daily or weekly reward and not a monetary
forecast. Quarry creation or upgrades continue to use their existing costs.

Autonomous native scenery remains visual only: the Auto receipt has empty
inputs, outputs and equipment changes, so it credits no real items. Existing
canonical production costs and cadence are unchanged. Direct deltas are shown
separately as 0 Vault, 0 premium tokens and 0 player XP. The ordinary 2-second
mine action, existing 6-second generator and its 95/4/1
COBBLESTONE/COAL/RAW_IRON distribution are unchanged; the repeatable generator
is a separate existing flow.

Owner-authorized V7 lava clearance removes one existing LAVA cell to AIR with
the existing single-cell `securing-mine` receipt. It has no item inputs or
outputs, no equipment change, no direct Vault/tokens/player-XP credit and no
excavation-ordinal credit. The next real STONE cut keeps its ordinary native
drop behavior; lava itself is never stone credit. WATER stays blocked, and
existing ownership, loaded-chunk, protection and terrain-cover gates remain.
The exact-capture scenario checks 891 captured voxels with zero unavailable,
commits the source-removal receipt, reads back saved state, and resumes the
next real STONE cut at the same ordinal. This is fixture readback, not a
production census or proof of activation; native fluid ticks and live resident
behavior are outside that fixture proof.

Current EconomyShopGUI SELL remains a possible downstream conversion of real
items. The owner's SELL-to-contract transition remains a future target, not an
active additional flow; no current sale rate, contract rate, income or
accumulation time is assumed. Direct currency and player-XP deltas remain zero.

Evidence anchors in the current source tree:

- `src/main/kotlin/ru/arc/civs/CivsMinePlan.kt:45-50, 643` defines the extra
  floor by version and sets plan version 7.
- `src/test/kotlin/ru/arc/civs/CivsMineV7MigrationTest.kt:14-45, 49-79`
  checks V6 prefix identity, finite geometry, floor depth and frozen sparse
  predecessor proof.
- `src/main/kotlin/ru/arc/civs/CivsMineSafety.kt:30-34` and
  `CivsMineProgress.kt:178-195` constrain LAVA-to-AIR to the V7 securing
  receipt and prevent excavation credit.
- `src/test/kotlin/ru/arc/civs/CivsMineLavaClearanceTest.kt:12-54` checks
  unchanged items, stock, plan progress, WATER rejection and protection gates.
- `src/test/kotlin/ru/arc/civs/scenarios/Auto2MineLavaBoundaryScenarioTest.kt:57-62, 228-253`
  exercises exact captured fixture data, saved-state readback and resumed
  mining; it does not establish production activation or player acceptance.

The source JSON and evaluator output are
`mine-v7-continuation-economy-assessment.json` and
`mine-v7-continuation-economy-assessment-result.json`.
