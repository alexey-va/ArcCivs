# ArcCivs 0.1.94 recovery and verification

## Runtime and release status

The latest runtime read, at 18:54:41 UTC on 26 October 2026, showed ArcCivs
0.1.93 enabled on Spawn with full coverage of 110 plugins and no truncation.
No 0.1.94 runtime acceptance was observed in that check. Publication, CI and
deferred delivery evidence are recorded separately in the
[operations report](https://github.com/alexey-va/ruscrafting-ops/blob/main/docs/reports/arccivs-094-workflow-and-plan.md).
No native hotload was run.

## Confirmed runtime causes

### Builder handoff

On Roman's Auto2 construction
`1a91dce0-15b5-4bed-bf56-6667a595909f`, credited 64-row groups paused for
4.260–6.046 seconds while their durable row commits took 39–57 ms. A separate
1,170-save sample had a 9 ms median, 20 ms p95 and 397 ms maximum. The delays
came from work-session and polling transitions, not SQL commit latency: the
actor skipped observation while placement was active, then restarted its
two-second session after credit ran out; the phase change reset the cycle.

The candidate counts the observed interval during placement, retains the same
session when only the internal placing flag changes, and requests new credit
after the current credit runs out. Builder handoffs keep the bounded five-tick
pulse and up-to-64-row credit. Placement cadence, payment and receipt recovery
are unchanged.

### Forestry target recovery

Egor's forester repeatedly chose the same unreachable, unaccepted tree after its
stance retry expired, so the field cursor did not reach the other trees or
queued planting targets. Failed unpaid roots now yield to the cursor and remain
deferred until the full field has had its turn. Accepted jobs retain their
pending batch and alternative-stance recovery. This changes target selection,
not receipts or yield.

### Route preparation and supported work positions

Regina's route evidence records 9.406 seconds preparing a return route and a
later 16.003-second preparation timeout with 2,414 visited nodes and 20,643
captured blocks; pure search took 87.134 ms. She later walked 85.167 path blocks
with 67.388 blocks of displacement. A subsequent near-entrance recovery
teleport is recorded separately and is not evidence of successful walking.
The raw record is
`artifacts/arccivs-094-diagnosis/regina-latest-through1657.json`.

The candidate shares immutable collision shapes across routes, invalidates
affected cells and chunks on native changes or unloads, and retains fresh
permissions and route costs per request. Long trips capture a connected
frontier up to the existing 256-probe batch; close trips retain a one-layer
capture. A short-trip latency regression from unconditional prefetch was
corrected. Distant work uses the resident's actual supported position,
including slabs and stair treads, with a 12-block maximum fallback reach. After
the first ordinary stance slice, one resumable distant-work probe is offered;
it does not wait for the complete stance sweep. A missing NPC does not consume
that attempt. The captured work batch remains exact and executes real native
operations. Unloaded optional stances are skipped;
unloaded work targets and receipts remain availability constraints.

### Stair movement

The old controller lifted to a stair's upper support height before moving
horizontally, and descending could skip an intermediate tread. The candidate
derives a short contact profile from actual collision boxes so supported
intermediate treads remain in the route. Geometry changes discard the profile.
Full blocks and ladders keep their existing behavior; search caps and recovery
teleports are unchanged.

### Autonomous warehouse transport

Autonomous warehouses use normal staff slots and assign a courier. Producers
earn existing output into durable pending stock; pickup reserves it into cargo,
and only durable unload makes it spendable at the warehouse. The existing
self-delivery fallback and 64-unit cargo capacity remain. Closed stock is not
materialized as native inventory items. A disappearing courier can trigger
self-delivery of already-earned pending stock without new output or experience;
partial unloads preserve the remainder, and receipt identity prevents duplicate
unloads.

Forestry's 66-case suite passed, including cold-start remote probing, a one-ray
second pulse, skipping an unloaded optional stance and exact reuse of captured
targets.

## Board and real-terrain plan

The revised board has 105 structural solid cells versus the 60-cell baseline,
excluding the unchanged 27-slot barrel. The price remains 12 abstract WOOD plus
8 abstract STONE. A one-time refit keeps 8 sandstone blocks in v1 and writes 97
solids while removing 52; v2 keeps 20 solids, writes 85 and removes 40. Removed
decoration grants no items.

The final replay uses the captured Auto2 terrain and exact authored templates.
All 16 plots and a closed perimeter pass: 3 GATE, 34 WALL and 18 new ROAD orders,
55 total within the unchanged 96-infrastructure limit, with no rejected
infrastructure. SMELTERY and CRUSHER remain inside the core; the forester, mine
and farm retain exterior sites. Existing buildings, paid approaches and
accepted infrastructure remain pinned. New footpaths start one column wide;
canonical approach earthworks and turning landings remain.

The same unadopted layout with three-wide paving quoted 308 WOOD + 1128 STONE;
the compact initial footpaths quote 308 WOOD + 736 STONE. Unit prices are
unchanged, and replay committed no payment or world change. The reduction is
49 unnecessary road orders, including shoulder work and old-road-only pieces.
The compiled board export is available for independent offline visual review;
this is not native-client or live settlement acceptance.

## Verification status

The final broad local run exercised 1,718 unit cases across 96 suites. Its only
failure was an old expected board-description substring; the rendered dialog
contained the revised copy. Updating that expectation and rerunning all 109 UI
cases passed, followed by a successful production JAR build. Nine unchanged
cases remain skipped for concrete MockBukkit limitations (protection 1,
LeafLitter world behavior 2, preferred-tool mine behavior 1, storage 5).

The real-terrain six-suite checkpoint passed all 90 cases, including complete
Auto2 plan adoption, exact compiled board export, retained-road preparation and
SITE activation. Independent review inspected eight compiled board renders
(new and V1 refit, rotations 0/270, front/player), found no blocking defect, and
reconstructed exact displayed coverage: 337/337 road cells, 547/547 future-route,
wall and gate cells, and 425/425 perimeter cells. All 16 plot labels are present.
The north marker sits close to the roof and the small board footprint has a tiny
map number; these are nonblocking scale limitations.

The production JAR and central consumer-architecture guards passed against
ArcCore 2.7.66. Python tooling tests passed 19 cases. The final-source CI also
runs the repository's disposable MySQL integration checks; its receipt is linked
from the operations report. No local integration server, bot, native hotload or
client session was started. Stair assertions and offline board views do not
establish post-activation walking, font filtering, lighting or client appearance.

## Hotload and data compatibility

The generation shutdown barrier and task retirement are unchanged. New shape
caches retire with their navigation owner. The nullable `pendingStock` and
`cargo.stockResource` fields preserve upgrade defaults for older cities, but a
downgrade to 0.1.93 is incompatible after 0.1.94 has produced closed pending or
carried goods because older serializers do not retain those fields. A rollback
scenario must use a compatible reader or an isolated original snapshot. No
native hotload or downgrade scenario was executed.

Bounded raw observations are retained under
`artifacts/arccivs-094-diagnosis/`. Assessment inputs and results are
`docs/workflow-094-assessment.json` and
`docs/workflow-094-assessment-result.json`.
