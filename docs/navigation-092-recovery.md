# Resident search and decorative work recovery

ArcCivs 0.1.92 repairs two separate causes of stalled work. Activation remains
deferred to the owner; source tests and shipped bytes do not establish live recovery.

## Search latency

The loaded 0.1.91 forester repeated the same inaccessible stance three times:
52.348, 49.347 and 53.345 seconds, 3,974 visited nodes, 32,768 captured blocks
and no walking progress. The rejected stance expired after 60 seconds while
rediscovery took about 96 seconds, so the same point became eligible again.
The miner's next native selection took 330.308 seconds after a completed seal.
A single 323-candidate stance pass does not explain that entire gap.

The detached search now tests whether a nearby centred endpoint has a legal
incoming edge before flooding a large region. Unloaded or ambiguous shapes,
including ladders, retain ordinary search. A narrow initial corridor retains
its boundary frontier for a wider detour; it cannot alone prove no path.
Preparation has configurable real server-tick deadlines and never authorizes
a recovery teleport. Main-thread capture and arbitrary route policies share
one operation/time budget. Async slices contain detached geometry only. Each later capture includes a bounded
adjacent layer, so the worker expands already captured nodes without returning to
Main for each grid step. Optional prefetch exhaustion cannot reject a complete
required path. This is a per-route exact geometry cache, not a shared reactive
whole-chunk cache.

Defaults are 256 search nodes per worker batch, 10,000 shared native operations
per tick and 4 ms native time. These are independent ceilings: one operation
cannot be preempted. The `navigation`, `native-work` and `worksites` sections
in config.yml expose the actual production limits. Existing configurations
receive missing keys on startup. A deadline is a backstop, not a latency target.

Worksite admissions rotate fairly under one aggregate budget across professions.
Failed forestry approaches retain the same captured work and try another stance;
exclusions persist through the complete alternative search and then retry after
a short backoff. Paid mine seals retain compatible frontier progress, while cuts
and changed geometry still trigger discovery. Debug includes discovery admissions,
elapsed ticks, target switches and current aggregate quotas.

The existing public-transit predicate now compares its two enum values directly,
removing the per-call set allocation identified in the 0.1.91 Spark captures.
No reduction in live sampled time is claimed before a comparable measurement.

## Auto2 stopped by a decorative journal

On 26 October at 10:39:56 UTC, Auto2's zero-value native mine action
`553ab96b-965b-45c7-85ae-be02a1a252aa` failed with
`inventory-conflict / stale-worksite`. The action removed DIRT at
(-1841,64,-2686), had no item inputs/outputs and advanced no economic worker
cycle. All five residents were retired one second later. The server continued
running; overview at 11:35:14 UTC confirmed revision 7223, unhealthy=true and
one blocked inventory recovery.

Source review reproduced a mismatch: admission allows a deferred AIR wall beside
a cut, but the additional Auto receipt validator rejects that same condition as
a missing wall. A regression reproduces this class of stale-worksite refusal.
The preserved live journal proves the zero-value operation and quarantine; it
does not itself contain every neighbouring native block at failure time.

Input/output-free Auto field and mine scenery now uses ordinary settlement
state. It validates the selected work once, applies its bounded native changes
on Main, and saves the gameplay cursor normally. A changed target is a local
retry/reselection; it cannot create an inventory recovery journal. Existing
typed visual-only journals retire during bootstrap before loading cities,
without native replay, replacing saved city state, or crediting items, stock or
XP. Real item transfers, forestry stock receipts, paid construction and economic
deliveries retain their own accounting.

The loaded chunk, modification authority and solid-placement occupancy checks
remain at the actual write. No test player, manual NPC/block change or time
change is used for acceptance.

## Verification before delivery

The full local `test shadowJar` run completed 1,623 cases with no failures.
Twelve were initially skipped by existing MockBukkit limitations, including two
new forestry cases. The latter received an explicit empty native-container
fixture and a fail-on-unsupported wrapper; the focused 46-case forestry rerun
passed with only its one pre-existing skip. No production source changed after
the complete run. Final CI reruns the complete source and disposable MySQL tests.
Python packaging checks (19), production JAR and central consumer verifier pass.

Short-route regressions cover exact shapes, ladders, directed policies, broad
detours and both supported queue budgets. Nine mixed routes complete within
18 simulated server ticks at production operation limits; a sealed endpoint
uses126 captured cells. These are deterministic tests, not observed .92 live
latencies. The harvest fixture measures scheduler pulses with a deterministic
clock; separate advancing-clock tests exercise native-budget yield/resume.
Post-conversation collision now reaches existing bounded yielding before the
optional walk is cancelled; the real-movement runtime regression passes.
