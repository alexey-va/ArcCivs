# Demand-driven carpentry — source assessment

The baseline is ArcCivs 0.1.51 active on Spawn, Survival and Parkour. Sawmills
currently repeat one selected species conversion, one log into four planks.
The proposed carpenter serves unpaid active construction and operating supply
demand using level-gated native recipes. It stops when those demands are covered;
queued schematics are not preloaded or manufactured speculatively.

One completed batch keeps the existing 60-second working session, actual input
consumption, output capacity check and durable inventory receipt. Recipes use
native Minecraft 1.21.11 quantities; stripping also requires a real axe operation.
Inputs from other professions remain real requirements. Unlocking containers and
advanced workstation blocks changes the range of automated goods, not their
manual crafting ratios. Workshop upgrades pause that workshop's production.

The [calculation](carpentry-economy-assessment.json) models one supplied carpenter
per owner at 0.25/1/4 active hours a day and 1/5/10 owners. These are sensitivity
assumptions, not player telemetry. At the unchanged nominal interval the ceiling
is 60 native batches/hour; for planks that is 240/hour, consuming 60 logs.
Low/base/high plank envelopes are 60/240/960 per owner-day, consuming 15/60/240
logs. Three workers can triple that conditional ceiling; actual demand, unlocked
level, walking, hunger, sleep, supplies, storage and receipt latency reduce it.
Finite orders and rounding to a single native batch prevent an idle stockpile.

Direct deltas remain separately zero Vault, zero premium tokens and zero Minecraft
player XP. Resident profession experience remains its existing separately capped
statistic. Producer-barrel cleanup and courier handoffs transfer existing items;
they do not create goods or grant a second crafting reward. Global stock, carried
ingredients and accepted orders must be reserved exactly once. Receipt replay
must not repeat inputs or outputs; obsolete unstarted orders release intent only.

The 168-hour read-only economy baseline captured on 2026-10-23 contained 3,381
records, 33,924 operations and 27 players. Vault mint was 67,915,416.2394 and burn
2,582,343.35; 67,000,000 minted by one administrative operation prevents using
the aggregate as ordinary player income. Unknown-source coverage remained 134
Vault operations and three token operations. Tokens separately minted 59 and
burned 15. The selected-material sales query was complete and untruncated: only
OAK_LOG had history (2,686 units, allocated income 4,942.1688). That allocation is
not a price catalog; missing histories do not prove that crafted goods cannot be
sold. No fictional currency conversion or projected sales income is applied.

EconomyShopGUI SELL remains an active external redemption channel. The planned
SELL-to-contract transition is retained as a future state. Native conversion
ratios introduce no new manual crafting arbitrage ratio, but automation can make
existing sell chains easier to use; realised throughput and complete crafted-item
prices are not established by this baseline. No prices, budgets or contract rules
are changed here. Construction costs and the complete level-up material bill
remain the actual schematic costs, without an invented fixed coin price.

Source/package checks, publication, activation and live work are separate. After
activation verify native input/output deltas, pinned order identity across delayed
callbacks, no duplicate carpenter reservation, level gates, full-store waits,
exact receipt replay, and courier pickup-to-warehouse delivery. No technical
observer or synthetic item grants are part of that acceptance.
