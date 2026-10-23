# Autonomous settlement economy assessment

This assessment isolates the admin-created autonomous mode. Existing personal settlements remain unchanged. The model uses item units for the village's abstract stock and does not convert those units to Vault, tokens, XP, or sale prices.

## Scenarios and production ceiling

The low/base/high sensitivity models 1/10/500 autonomous cities on one home backend under one admin, with an assumed per-backend production-pool activity window of 0.25/1/4 hours per day. These are not measured sessions. The evaluator's `participants` array is `[1, 1, 1]` because each case models one admin on one backend; city-count scaling is already represented in daily delivery-event counts. `maximum-settlements: 500` and `maximum-active-npcs: 48` are backend-local ceilings; other home backends have separate limits and are not included in these totals. The 500-city sensitivity uses all city slots on this backend; with one ordinary personal settlement on the same backend, no more than 499 autonomous cities fit there.

A level-2 city can assign two residents each to Forester, Quarry and Farm, so six producers can deliver up to 16 wood, 12 stone and 12 food per minute per fully staffed city. At level 1, the four auto-staffed jobs are the builder and one worker at each producer. At level 2, the city population limit is 8; two slots per producer plus two builder slots use all eight residents. Each backend's config caps active NPCs at 48; that backend's pool is shared by personal and autonomous settlements hosted there. The base and high sensitivities allocate all 48 active slots to producers, evenly split as 16 workers/resource. That is an optimistic concurrency bound; personal-settlement NPC use, builders working, travel, hunger and night shelter reduce it.

The evaluator models completed autonomous delivery trips at the level-2 rate and a 60-second base interval. Its daily events are 30/960/3,840 per resource for the three scenarios. The service requires the worker to arrive at real storage before `CivsRules.deliver` adds stock. Route time, sleeping/night shelter, low-hunger work slowdown, unavailable storage/chunks and full stock all reduce or stop actual deliveries. The separate one-minute population `activeMinute` clock advances hunger and recruitment; it does not passively produce stock. These event counts are upper-bound sensitivities, not an observed production rate.

A level-2 warehouse gives a per-resource stock ceiling of 640 (128 base + 512). Starting stock occupies 96 wood, 96 stone and 24 food, leaving 544/544/616 units of headroom. This is an on-hand ceiling, not a lifetime production cap: construction, hunger and recruitment can consume stock and allow later deliveries. The evaluator therefore reports gross item deliveries; its weekly item totals are not predicted ending balances.

| Scenario | Autonomous cities | Active NPCs/resource | Assumed pool activity | Gross wood/day | Gross stone/day | Gross food/day |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Low | 1 | 2 | 0.25 h | 240 | 180 | 180 |
| Base | 10 | 16 | 1 h | 7,680 | 5,760 | 5,760 |
| High | 500 | 16 | 4 h | 30,720 | 23,040 | 23,040 |

This one-backend 500-city case does not multiply simultaneous throughput by 500: the assumed production pool remains bounded by that backend's 48 active NPCs. It is an administrative exposure sensitivity for one home backend, not a network-wide aggregate; other backends would have separate capacity and are not modeled. It assumes those workers can remain productive for four hours; it does not imply that 500 cities are concurrently staffed or that the backend realizes the theoretical output. The evaluator's `network_weekly` label reflects its aggregation with one participant here, not production summed across home backends.

## Finite stock and building costs

Each new autonomous city starts with 96 wood, 96 stone and 24 food in non-transferable abstract stock. The full source-defined profile through level 2 costs:

| Building/target | Level 1 cost W/S/F | Level 2 cost W/S/F |
| --- | ---: | ---: |
| Town Hall | 32 / 24 / 4 | 128 / 96 / 24 |
| Builder Hut | 16 / 8 / 0 | 64 / 32 / 0 |
| Forester | 20 / 12 / 0 | 80 / 48 / 0 |
| Quarry | 20 / 20 / 0 | 80 / 80 / 0 |
| Farm | 24 / 8 / 0 | 96 / 32 / 0 |
| Warehouse | 32 / 16 / 0 | 128 / 64 / 0 |
| House | 24 / 12 / 0 | 96 / 48 / 0 |
| **Total** | **168 / 100 / 4** | **672 / 400 / 24** |
| **Full profile through level 2** |  | **840 / 500 / 28** |

The target-level-2 costs use the source's squared target-level multiplier; they are full target costs rather than a three-times-base incremental delta. The initial Town Hall site is charged its level-1 cost when construction is reserved. After the opening stock, completing the entire profile requires at least 744 wood, 404 stone and 4 food of additional generic output, before hunger and later recruitment consumption. Construction reserves the fixed stock cost once; retries do not charge it again.

| City count | Opening stock W/S/F | Full profile costs W/S/F | Additional output needed W/S/F, before resident food use |
| ---: | ---: | ---: | ---: |
| 1 | 96 / 96 / 24 | 840 / 500 / 28 | 744 / 404 / 4 |
| 10 | 960 / 960 / 240 | 8,400 / 5,000 / 280 | 7,440 / 4,040 / 40 |
| 500 | 48,000 / 48,000 / 12,000 | 420,000 / 250,000 / 14,000 | 372,000 / 202,000 / 2,000 |

One food restores six hunger. The initial five immigrant admissions are a separate budget; later recruitment uses 12 food per resident and is limited to one resident per two active minutes while room, beds and food remain. Hunger and recruitment consumption are conditional on resident state and are not assigned fictitious daily frequencies in the evaluator.

Construction stock is an abstract settlement balance, not a player item grant. Builders place actual schematic blocks in-world. A privileged admin can alter or mine the admin's own protected build; that is a privileged world-materialization risk. The feature does not hand those blocks to ordinary players as a repeatable free inventory source. No block count is converted to currency.

## Direct currency and observed economy

Direct deltas are explicitly zero for Vault, premium tokens and Minecraft player XP. Current item SELL behavior is not changed; the planned SELL-to-contract transition remains a separate future scenario. The assessment has no prices and makes no item-to-currency conversion.

One supported read-only 168-hour network ledger observation was captured at 2026-10-23T16:51:48.477Z. It covered 3,329 records, 32,992 operations, 29 players and 246 attempts. The boundary was not exact: 2 records / 84 operations (319.56 absolute amount) crossed the requested window and were excluded; no future events were excluded. This ledger is not ArcCivs-attributed income.

Vault separately reported minted 67,928,741.37, burned 2,440,753.22 and mint/burn net 65,487,988.15; transfer net was -110.60 and is not supply. A single `admin_command` operation accounted for 67,000,000 Vault mint, so the window is not a clean ordinary-income baseline. Tokens separately reported minted 81, burned 15 and net 66. The ledger reported 279 unclassified operations and records, 224,153.36 in absolute amount, with 99.15% of operations, 91.62% of records and 99.89% of amounts classified. `stockAudit.complete` was false. The legacy mixed-currency aggregate is marked unsafe; the sanitized snapshot is in [autonomous-economy-observation.json](autonomous-economy-observation.json). It contains no player names or balances.

## Source and validation

- `CivsAutonomy.kt:18-40` defines abstract non-transferable stock, the seven-building profile, fixed autonomy costs and the starting 96/96/24 reserve.
- `CivsRules.kt:30-61, 63-70, 119-156` defines target-level cost multipliers, storage/population caps, producer slots, output and interval.
- `CivsService.kt:1510-1528` requires actual storage arrival before autonomous production is committed; `CivsRules.kt:407-453` reserves the finite construction cost once.
- `README.md:407,456-460` and `ARCHITECTURE.md:202-206` specify server/home-backend scope for the ceilings. `CivsSettings.kt:28-35` and `config.yml:27,31` set them to 500 settlements and 48 active NPCs; `CivsService.kt:3499-3500` enforces the backend's existing-plus-pending city total.
- `CivsRules.kt:77-84, 356-375` limits autonomous staff to the builder and three producers, then fills second-level producer slots before the second builder; `CivsAutonomyTest.kt:109-124` checks that staffing layout. `CivsAutonomyTest.kt:25-68, 127-172` checks exact costs, one-time reservation, and a simulated closed stock-production path through the level-2 profile.
- `CivsResidents.kt:17-22` doubles a hungry worker’s work interval; `CivsCitizens.kt:967-971` sends workers to shelter at night. `CivsService.kt:2918-2923` keeps the population active-minute transition separate from producer deliveries.

The draft was evaluated with the canonical balance evaluator. Its output is [autonomous-economy-assessment-result.json](autonomous-economy-assessment-result.json). The item flows are gross ceilings; the result is not an ending-stock forecast, measured production rate, player inventory grant, or currency valuation.
