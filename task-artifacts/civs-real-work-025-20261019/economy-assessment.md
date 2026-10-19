# ArcCivs .24 timer production vs physical farm, forest and mine work

This is a source-based before/after assessment of the current uncommitted worksite changes. It is not evidence of a build, delivery, activation or ordinary-player acceptance. The calculator input and result sit beside this note.

## Before and proposed work

The .24 baseline used fixed item-producing cycles every 60 seconds plus a resident trip: a forester delivered 4/8/12 selected logs per L1/L2/L3 cycle; a quarry delivered 3/6/9 selected stone-family items; a farm delivered 3/6/9 selected raw crop items or 1/2/3 bread using 3/6/9 wheat. The previous economy assessment treated these as item flows and kept Vault, tokens and player XP separate.

The current worksite code commits item and block changes together through the durable storage receipt. A FARM can till, plant or harvest one field action in a nominal 3 seconds; it harvests only fully mature native crops. In BREAD mode it spends 3 wheat for 1 bread in a 3-second receipt. That recipe changes no world block and awards no profession point. A FORESTER cuts a connected, leaf-bearing tree of at most 128 selected timber blocks, with work duration `max(6s, 1s per removed log)`. Each block uses the held axe's native drop; all tree changes and drops commit through one existing receipt bounded to 128 world cells. Replanting a normal one-root species consumes one paid matching sapling; DARK_OAK needs a supported 2×2 plot and four paid saplings, so one sapling cannot replant a dark-oak tree. A QUARRY changes one actual mine cell per nominal 4-second action. Actual dug blocks use native drops from the held pick; level 1/2/3 has at most 231/462/693 planned positions. Air/already-correct steps are skipped and the mine does not regenerate.

The used damageable tool takes native durability only from actual non-air source blocks; its native Unbreaking chance applies, so exact wear depends on the held item. A mine's maximum one-time paid support bill if every planned target changes is 59/118/177 cobblestone, 6/12/18 ladders and 7/14/21 torches for levels 1/2/3. The actual bill is smaller when cells already match. Initial crop fields use one paid matching seed per planted plot; the medievaloak field has authored water, while nordic and caledonia need one supplied water bucket for their added source and return one bucket. Warehouse replenishment requests the selected farm seed and matching IRON_HOE/IRON_AXE/IRON_PICKAXE at one tool per assigned worker (1/2/3 by headcount); these are deficit-based stock transfers, not item creation or free tool grants. Seeds, saplings and support items also come from building stock.

## Illustrative activity, not telemetry

Each scenario chooses one producer site per participating city at L1/L2/L3 with 1/2/3 assigned workers. Low/base/high participant counts are 1/10/16 cities. Before, timer activity assumes 1/5/10 completed site cycles per city-day. After, the scenario assumes these completed actions per active city-day after travel and waiting:

| Site and selected mode | Before timer item flow per city-day, low/base/high | After physical work per city-day, low/base/high | Weekly network activity |
| --- | --- | --- | --- |
| Forester, one matching log type | 4 / 40 / 120 logs | 1 / 2 / 3 trees, assumed 4 / 12 / 128 logs each = 4 / 24 / 384 logs | Logs: 28 / 1,680 / 43,008 |
| Quarry, one stone-family type | 3 / 30 / 90 items | 5 / 10 / 15 changed mine cells; native drops depend on each real block and pick | Mine-cell receipts: 35 / 700 / 1,680 |
| Farm, one raw crop | 3 / 30 / 90 crop items | 1 / 4 / 9 mature-crop harvest receipts; item quantity is the native crop drop, not projected here | Harvest receipts: 7 / 280 / 1,008 |
| Farm, BREAD recipe alternative | 1 / 10 / 30 bread; consumes 3 / 30 / 90 wheat | 1 / 4 / 9 bread; consumes 3 / 12 / 27 wheat | Bread: 7 / 280 / 1,008; wheat input: 21 / 840 / 3,024 |

These daily counts are scenario inputs, not rates inferred from a precise clock. The farm case assumes only the listed mature crop actions occur; natural growth needs eligible random ticks in loaded field chunks, and new work plans are not created while world time is 12000 through 23000. It does not claim that a fixed number of loaded hours produces a specific crop. Forestry assumes loaded, safe and reachable mature trees at the assumed sizes. The 128-log L3 case is a maximum-size stress scenario, not typical telemetry: three cuts/day for seven days require 21 existing mature trees per site, and the source world's tree stock is finite; paid replanting does not create instant mature trees. A cut without enough matching saplings is not replanted; DARK_OAK replanting needs all four saplings for its 2×2 plot. Mining assumes 5/10/15 safe cell changes per city-day (five per assigned worker/day); the high case changes 105 of its 693 L3 candidate positions in one illustrative week. Travel, viewer presence, worker availability, stock, world protection and storage space may reduce the totals. There is no offline catch-up.

The evaluator counts physical crop harvest, tree cut and mine-cell receipts separately from item stacks. The exact crop/mining item basket cannot be derived without the actual world blocks and native drop calls; the code records those drops through the receipt. The logs scenario assumes ordinary one-to-one same-log drops for the listed 4/12/128-block trees, with no bonus enchantment. Sapling counts in the evaluator use a selected non-dark-oak species (one per replanted tree); DARK_OAK is a mutually exclusive alternative needing four saplings per tree (4/8/12 per city-day under the same cut counts). The six timber variants, three quarry materials and three raw crops are alternatives, not additive output.

## Internal progression and economy boundary

Before, an assigned worker gained one internal profession point per completed 60-second cycle. After, source awards one point only for a committed action with world changes: one for a crop action, one for a complete multi-log tree cut, and one per changed mine cell. Tilling and planting are separate point-bearing actions. Bread-only recipe and tool transfer have no block changes and award zero. The three profession calculations are alternatives and must not be summed as one city scenario. Each worker-profession stores 0..40 points, advances every 10 points through levels 1..5, and may raise health from 20 to 28. These are NPC progression points, not vanilla/player XP; there is no network-wide weekly cap.

The physical work introduces no direct Vault or token payout and does not change player XP. No item-to-money value is assigned. EconomyShopGUI SELL remains a current path; the planned SELL-to-contract transition is future, and this assessment assumes no contract replacement or SELL price. Recruitment, the one-time founder stock, building/population limits and schematic construction costs remain unchanged.

After any separately authorized delivery, verify the active plugin version, mature-crop gating, each selected native crop/ore/log receipt against real world drops, one-time and recurring input debits, tool durability, work receipt replay/restart behavior, and the absence of Vault/token/player-XP events. This note does not claim those runtime checks have happened.

## Evidence anchors

- src/main/kotlin/ru/arc/civs/CivsRules.kt: timer interval, fixed tier quantities, worker slots and finishWorldWork skill condition.
- src/main/kotlin/ru/arc/civs/CivsWorksites.kt: farm actions and native crop drops, wheat-to-bread recipe, 128-log tree bound, `max(6s, 1s/log)` duration, four-sapling DARK_OAK 2×2 replant and 128-cell receipt bound, 4-second mine cell, paid inputs and durability.
- src/main/kotlin/ru/arc/civs/CivsLogistics.kt: deficit-based warehouse request quantities; one matching iron work tool per assigned worker and only the selected farm seed.
- src/main/kotlin/ru/arc/civs/CivsResidents.kt: 40-point per-worker-profession cap, levels and health.
- src/main/kotlin/ru/arc/civs/CivsStorage.kt: durable receipt for actual item/world work.
- docs/worksites.md: 72/80 crop cells, 53 mine supports per floor, one to three floors and authored access.
- docs/economy-assessment.md, docs/residents-economy-assessment.json, and docs/warehouse-logistics-economy-assessment.json: prior timer-item baseline, internal-skill rules and current/future SELL boundary.
