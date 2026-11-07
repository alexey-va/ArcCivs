# LAB village acceleration, 2026-11-07

Only the exact `lab` backend and `arccivs_lab` world can activate the console
mode. It expires after ten minutes and does not persist across reload or boot.
Only one tested physical autonomous settlement is selected. Running or retrying
ordinary gameplay on another settlement leaves its timing unchanged.

A healthy paid builder session normally waits 2,000 ms at its workplace. LAB
test mode shortens this to 500 ms; the same 64 row credit ceiling, exact material
debit, actual arrival, and native placement budget apply. Physical resident work
keeps the real one-second delay after its committed receipt. The modes change
the timer only on approved physical work and paid building. Measured terrain,
routes, food, rest, supplies, inventory and claims checks remain required. A
single ten-minute window permits at most 300 vs 1,200 theoretical builder
sessions from the timer alone. This is an upper bound, not measured throughput.

There are no changes to per-action item outputs, material costs, auto-supply
batches, resident bag capacities, physical emerald prices, Vault, tokens or
player XP. A single-parcel reset archives the old test stock without dropping
it. Ordinary founding of the new ID can issue its existing initial LAB reserve
once; it gives no production or proxy-wide redeemable reward. See the adjacent
calculated before/after scenarios. For acceptance compare real work timestamps
and paid debits for the selected city and a normal city; distinguish materials
and route refusals from elapsed work. Confirm that expired/off mode restores
the normal timing and that no bonus items or credit were created.
