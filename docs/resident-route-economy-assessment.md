# ArcCivs .25 routed work vs .26 direct-to-work routing

This source-based assessment compares the .25 physical-work cycle reported active at Spawn in the task context with the .26 source change. The route removes storage travel from physical FARM, FORESTER and QUARRY work cycles; it does not change work duration or output per completed action. The .26 source has not been delivered or activated by this assessment.

## Matched-activity comparison

For a controlled comparison, low/base/high retain the prior .25 assessment's illustrative activity: 1/10/16 participating cities, each with a level 1/2/3 worksite and 1/2/3 assigned residents. Counts below are per participating city-day, not measured telemetry. Worksite types and FARM modes are alternatives, not additive.

| Flow | .25 before | .26 at the same activity | Weekly network quantity, low/base/high |
|---|---:|---:|---:|
| FORESTER, one selected timber: 1/2/3 complete cuts at assumed 4/12/128 logs each | 4 / 24 / 384 logs | 4 / 24 / 384 logs | 28 / 1,680 / 43,008 logs |
| Mature FARM crop mode: completed harvest receipts | 1 / 4 / 9 | 1 / 4 / 9 | 7 / 280 / 1,008 receipts |
| FARM BREAD alternative: bread output; wheat input | 1 / 4 / 9 bread; 3 / 12 / 27 wheat | 1 / 4 / 9 bread; 3 / 12 / 27 wheat | 7 / 280 / 1,008 bread; 21 / 840 / 3,024 wheat |
| QUARRY: changed mine-cell receipts | 5 / 10 / 15 | 5 / 10 / 15 | 35 / 700 / 1,680 receipts |
| Matching native tool moved from player inventory to a resident | 1 / 2 / 3 tools | 1 / 2 / 3 tools | 7 / 140 / 336 transfers |

The FORESTER high case is a finite-world stress scenario using the source's 128-log maximum, not a sustainable daily yield; tree stock is finite and replanting does not create mature trees. The nominal sapling rows assume an ordinary one-root species: 1/2/3 paid saplings per city-day. DARK_OAK is a separate alternative requiring 4/8/12 paid saplings for the same cut counts. Farm output remains gated on fully mature native crops. Quarry item drops depend on the actual blocks and equipped tool; mined deposits do not regenerate. The exact native crop/ore basket is therefore not projected here.

## Route-time sensitivity, not a throughput forecast

No route-time measurement is available. If storage travel had represented 10%, 30% or 60% of a work-bound .25 cycle, removing that share would allow theoretical cycle-rate multipliers of 1.11×, 1.43× or 2.50× (`1 / (1 − route share)`). These are explicit sensitivity assumptions, not observed speedups. The matched-activity table intentionally holds completed actions fixed; natural crop growth, finite mature trees/ore, supplies, native durability, storage space, viewer availability and worker schedule can prevent any increase.

Per action, physical work duration remains 3 seconds for FARM actions, 4 seconds per mine cell and `max(6 seconds, 1 second per felled log)` for a tree. Paid inputs, native tool durability and one internal NPC profession point per qualifying committed work action remain unchanged; each worker-profession still caps at 40 internal points. If direct routing enables additional completed actions, daily item demand and native tool wear could rise with that activity, but the increase is not measured. Internal points are not player XP.

The requested-tool button is a convenience for moving one actual matching native tool from player inventory to a resident's main hand. Selection sorts by native material tier, enchantment level, remaining durability and stable inventory slot order; the full native item metadata is retained. If the resident already holds a tool, it moves into the resident's bag. The player-slot debit and resident update use the existing journaled transfer path. At matched activity, the illustrative 1/2/3 tool transfers per city-day are unchanged from manual native-inventory transfer; the button grants no tool or reward.

Idle wandering uses a slower 0.55 speed modifier and resets footstep tracking, so it does not accrue Trails wear. Decorative changes are cosmetic. Neither adds items, currency, player XP or a payout.

Direct Vault, premium-token and player-XP deltas are each zero before and after. No item-to-money value is assigned. EconomyShopGUI SELL remains current and the planned SELL-to-contract transition remains future; fixed item outputs imply no changed indirect sale exposure in the matched scenario, while any output increase from actual extra work remains unmeasured.

## Evidence and boundary

- `build.gradle.kts:6`: source version 0.1.26.
- `src/main/kotlin/ru/arc/civs/CivsCitizens.kt:278-283,1181-1185,1421-1432`: physical work plan attachment, direct-to-work cycle start and worksite completion at the workplace; `:340-343` slows idle travel and resets footsteps.
- `src/main/kotlin/ru/arc/civs/CivsWorksites.kt:147-155,210-216,297-299,330-346`: mature-only crop harvest, per-action duration, native drops and tool wear behavior.
- `src/main/kotlin/ru/arc/civs/CivsEquipment.kt:68-105`, `CivsService.kt:911-932`, and `CivsStorage.kt:334-342,741-752`: requested-tool selection, metadata-preserving player debit, resident bag handling and the existing durable transfer journal.
- `src/main/kotlin/ru/arc/civs/CivsResidents.kt:11-14,38-44`: internal skill progression and 40-point per-worker-profession cap.
- `docs/economy-assessment.md`, `docs/residents-economy-assessment.json`, and `docs/warehouse-logistics-economy-assessment.json`: item/currency separation, resident rules and SELL-to-contract boundary.

This assessment verifies source assumptions and arithmetic only. It does not claim a .26 build, delivery, activation, observed route duration or player acceptance.
