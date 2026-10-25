# Auto2 recovery in 0.1.79, 25 October 2026

Target: Spawn, `arc_civs_test`, settlement `8b890e82-2bff-4e27-b3f9-5932754a820b`.
Times below are MSK. Evidence is in the workspace artifact directory
`artifacts/arccivs-auto2-check-20261025/`.

## Observed recovery in 0.1.77

The first live check used 0.1.76. Auto2 had five residents and four building
records. Its fixed quarry site was rejected during earthworks, with
`autonomy-terrain` and `autonomy-no-site`. Loaded chunks and available construction
resources were sufficient. The height-field snapshot does not contain the exact
historical rejected block, so that block is not identified retrospectively.

0.1.77 enabled at 10:50:01; the server was ready at 10:50:10. The existing
countdown/restart was observed, not repeated by this investigation. The original
quarry site and anchor were retained. Native preparation cut 96 cells and filled
1802, with an 88-block approach. All 561 construction batches completed at
11:03:15. At 11:03:29 autonomy queued a farm, followed by normal preparation and
construction. No manual world edits, item grants, or resident relocation were
used to obtain this result.

The old idle routine used one eight-point ring and identical rest intervals.
The three observed residents had different initial indices but had converged
to the same ring phase. The recorded window does not establish the original
convergence trigger. The worker-specific destinations and timing in 0.1.77
produced separate live walking arrivals and positions.

## Additional confirmed failures

Forester Asya (`c2ab79a3-8081-4186-ad2c-4190a6a13eb9`) planted adjacent oak
saplings. At 11:03:58–11:04:17 she repeatedly suffered suffocation at
`(-1885,64,-2698)` and died. A later native read-only block predicate confirmed
leaves at head cell `(-1885,65,-2698)`. This is a post-death observation, not a
capture of the full body geometry at the moment of injury. The saved Anvil
chunk was older than the incident and is not used as its current geometry.

CraftRegionAccessor's default highest-block query uses `MOTION_BLOCKING`, which
omits saplings. The forestry spacing scan therefore could miss a young tree,
while the previous fixture's highest-non-air behavior concealed the defect.
The spacing scan uses `WORLD_SURFACE`; its existing crown radius, costs and
native growth behavior are retained.

Separately, a pending injury cancelled movement while ordinary collision checks
rejected every move from an already-overlapping native solid. Recovery must
permit a bounded physical exit while preserving the injury's durable outcome.
It must not award work, alter blocks, deepen overlap, enter another obstacle,
or bypass resident and territory checks. A bounded WARN/clear transition makes
this condition visible to operators.

Pillagers subsequently killed other residents. These attacks are not attributed
to a pathfinding defect. The owner explicitly approved enabling only Auto2's
`immortal-residents` setting; its durable receipt committed at 11:11:55 and the
readback confirmed `true`. Chunk loading remains enabled and automatic
construction material grants remain disabled.

After these losses, the one survivor, Flora, retained the forester assignment.
The farm still required a builder and stock held only 3 FOOD, below the existing
12-FOOD recruitment cost. Recruitment's shortage is legitimate. The separate
assignment defect is that an already-assigned, idle survivor is never considered
for a missing critical role. Recovery must reuse the ordinary assignment and
trip fencing, protect accepted work/cargo, finish the farm, and then staff food
production so normal paid recruitment can recover.

## Economic boundaries

This correction does not change prices, recruitment cost, stock capacity,
production quantities, action durations, growth rates, or the existing forestry
seed debit. No free worker, food, seed, construction material or world item is
introduced. Critical reassignment redistributes the existing workforce; it does
not add a parallel producer. Existing pending receipts and forestry jobs remain
authoritative. Direct currency deltas remain separately 0 Vault, 0 tokens and
0 player XP. The existing native-sapling bridge and SELL exposure are assessed
in `forestry-and-roads-assessment.md`; this correction adds no new conversion.

## Acceptance boundaries

Local regressions, CI, delivery and live activation of the additional correction
are recorded separately. The 0.1.77 quarry completion and independent walking
observations do not establish that the additional collision/assignment changes
are active. All live observations here are logs and native command predicates,
not visual acceptance in a Minecraft client.
