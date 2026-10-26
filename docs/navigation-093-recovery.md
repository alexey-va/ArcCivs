# 0.1.93 worksite and hotload changes

Auto2 on Spawn was healthy after .92 retired its zero-value legacy scenery
journal. The following independent failures remained in the first live window.

## Mine exit

Regina `679ad6aa-2a43-4c17-89ba-16589232fed3`, cycle 216, remained at
`(-1840.844,64,-2685.5)` while returning to storage at
`(-1867.5,64,-2758.5)`. Attempts 1–9 between 13:33:07 and 13:35:53 UTC on
26 October exhausted the capture budget without displacement. Her feet and
the depot happened to have the same Y; that shortcut skipped the required
ascent to the mine entrance at `(-1844,71,-2696)`.

The route now retains the workplace's mine context after native work finishes
and uses the shaft bottom, actual entrance and then the outside destination.
Same-floor internal work still uses its direct route. The regression uses the
observed coordinates and checks the first ladder movement and waypoint sequence;
it does not claim to reproduce the entire live entrance geometry.

## Forestry

Egor `da33dc2d-c343-48c7-9b8d-10cda594970e` did complete native trees and
deliver abstract WOOD, but restarted a roughly 1596-column scan after work and
could spend over 60 seconds rediscovering nearby trees. New scans begin near the
resident, completed work keeps the unvisited scan cursor, and reachable work
from the current stance is considered before other approaches. Aim points
outside work reach no longer consume the scarce ray checks.

New planting reserves both neighbouring crowns, including mature trunks under
leaves and saplings above ground. A newly selected tree whose trunks already
join another independently grounded tree is thinned without replanting that
root. The isolated survivor and later well-spaced plots replant normally.
Thinning consumes no replacement sapling; final harvest output is unchanged.
Old accepted jobs and receipts default to their original replant contract.
The three-adjacent-oak regression follows actual batches, receipts and native
block changes and keeps neighbouring trunks intact.

## Road repair

Roman `f2cd0cc5-a317-4985-bfbc-1fbbcd2a181d`, cycle 319, was close enough to
repair a road cell while overlapping that same cell. `road-occupied` rejected
his own action repeatedly and appeared as an unsaved delivery. The occupancy
gate and callback are removed. Real modification authority, structural road
validation and the existing paid repair receipt remain.

## Other changes and evidence boundaries

The plan board uses corrected block-centre rotation, readable legend placement
and a bounded forecast from the existing autonomy/layout planner. New v2 boards
place the control barrel centrally; existing v1 blocks and inventories retain
their original blueprint. See [plan board](plan-board.md).

ArcCivs exposes explicit stop preparation and status for an external loader;
see [hotload](hotload.md). Full server stop, ordinary config reload and fresh
classloader replacement are distinct. Unit tests do not certify PlugManX runtime
compatibility. The owner requested initial Spawn restart first; actual hotload
testing follows that first load.

Amounts, caps and work timers are unchanged. Thinning does not debit replacement seeds
when no replacement is planted; a later new plot pays the ordinary cost.
The [assessment](navigation-093-assessment.json) models this finite crowded-grove
effect separately from hypothetical restored throughput. Its calculated values
are sensitivities, not observed live rates.

## Release validation

Local full unit run exercised 1641 cases: one outdated first-pulse ray-count
assertion failed, and ten cases were skipped. The updated
assertion permits the standing/time budget to yield before the first native ray,
but still requires real rays before reporting an actual blocked line of sight,
at most eight rays per pulse, and recovery after removing the obstruction.

The final focused forestry, spatial-work, blueprints and board run passed
143 cases with one MockBukkit skip. The skipped adjacent-oak regression was
then made strict and supplied the existing empty-container seam: all 53
forestry tests passed with no skips, including thinning, ordinary replanting and
the next paid planting at a free plot. Production JAR verification, the
Core consumer contract and all 19 script tests passed. Full tests and disposable
MySQL integration are also required by CI for the published commit.
