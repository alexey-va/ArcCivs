# Mine runtime diagnostics

`ARCCIVS_MINE_ROUTE_BLOCKED` is a WARN for a terminal mine work-route failure.
The first failure is reported immediately, the third is reported as `stalled`,
then repeated reports are limited to one per minute. One episode retains its
building, plan version, worker cycle and mining step across replacement routes
and neighbouring focus targets. Player collision waiting is excluded.

Fields include city/worker/building UUIDs, step, cycle, failure count, age,
actual position, target, focus, chosen stance, path/search progress, captured
blocks and suppression count. `standing_exclusion=pending` means that the
route has failed before the worksite rejection callback runs.

`ARCCIVS_MINE_ROUTE_RECOVERED` records either at least 0.5 blocks of accepted
forward walking or a completed physical work receipt. `reason=work-completed`
does not claim walking. A safe teleport retains the failure episode and clears
its accumulated walking distance. Ordinary phase cancellation can close an
episode without claiming recovery.

Cached state:

```text
civs debug citizens CITY_UUID WORKER_UUID
civs debug worksites CITY_UUID WORKER_UUID
```

Explicit fresh native capture:

```text
civs debug terrain CITY_UUID WORKER_UUID
```

The terrain command captures at most 9×9×11 cells around the resident's actual
feet. It reports exact block states, block-local collision boxes, availability,
climbable/liquid/hot/door flags, bounds and timestamp. It reads loaded columns
only, never loads chunks, and excludes block NBT and inventories. Both exact
UUID filters are mandatory. `debug all` retains its nine cached sections and
does not perform this fresh capture.

Correlate failures with actual positions, `miningAccessStep`, retained version
prefixes, physical receipts and subsequent route progress. A selected stance,
changed status or successful teleport is insufficient evidence of a usable
walked route. Duplicate logger copies of the same event are one observation.

Structural quarry worksite blockers (`worksite-no-access`, `worksite-obstacle`,
`worksite-layout`) also emit `ARC_CIVS event=worksite_blocked` at WARN. A new
blocker bypasses the informational cooldown; exact repeats are deduplicated.
These include the persisted mine version/step, point and material, so a blocked
planner with no active actor route is still visible.

`ARC_CIVS event=mine_search_slow` is a separate WARN after 1,200 active
server ticks spent searching for mine work, repeated at most once per 1,200
active ticks. At 20 TPS this is approximately one minute. `active_ms` is
nominal simulation time (`active_ticks * 50`), not wall-clock elapsed time.
Night, receipt pauses and resource/tool or explicit worksite blockers do not
accrue search time. Worker removal, assignment/cycle/persisted-plan changes
and admission of work reset the episode; advancing an inspection cursor alone
does not.

The warning includes persisted version/committed step, inspected scan step,
frontier target/excavation/index/deferred count and candidate target/index/aim.
It describes slow planning and does not label a finite advancing scan as an
unreachable route. After a warned search admits work,
`mine_search_work_selected` records that selection at INFO. Selection does not
prove a world receipt or walking recovery. `civs debug worksites` exposes the
same episode's active tick and warning counters without advancing the search.

If a terminal `line-of-sight-blocked` seal is an AIR cell hidden by the current
solid, non-gravity excavation, that single cut may complete first. Its previous
frontier is then repaired before another cut, also after save/load or restart.
Already open boundaries and liquids do not receive this exemption. Native rays,
protection and receipt-time liquid checks remain authoritative.

Optional IDLE wandering abandons an unreachable destination when the resident's
exact actual feet remain supported, body-clear and safe within its idle area.
It then uses the existing eight-second retry delay and another destination.
Unsafe or displaced residents retain recovery; mandatory work routes are unchanged.
This does not establish a physical exit from a disconnected upper floor.
