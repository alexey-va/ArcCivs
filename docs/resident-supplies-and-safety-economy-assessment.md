# ArcCivs miner speed, resident supplies and corpse retention

This task-owned assessment compares the committed baseline at `c67edf6` with the
implemented miner duration and item-handling changes in candidate 0.1.38.
The scenario is not measured production; delivery and native acceptance are separate.

## Modeled limits and item flows

The committed quarry worksite takes 4,000 ms for one block action. Hunger at 6
or lower doubles the work duration, so the current ideal ceilings are 900
healthy or 450 hungry block actions per active hour. The candidate 2,000 ms
healthy action raises those ceilings to 1,800 and 900 actions/hour
respectively. This arithmetic excludes travel, supports and ladders, supply
gathering, native work budget, night, tool wear, failed access, storage and
downtime. It is a theoretical ceiling, not an output forecast.

The item scenario assumes one miner per city and 0.25/1/4 active work hours per
city-day for 1/10/16 participating cities. It counts 225/900/3,600 actions
before and 450/1,800/7,200 after per city-day. For illustration only, each
action is assumed to excavate plain STONE and yield one COBBLESTONE. The actual
worksite uses native block drops for the current block and tool, so this is not
a claim that every quarry cell produces cobblestone. The prior lifecycle
assessment's 5/10/15 changed cells per city-day was also a sensitivity case,
not telemetry; this file uses the requested active-hour ceiling scenario.

| Case | Cities | Active hours per worker-day | Excavations per city-day, before → after | COBBLESTONE per network-week, before → after |
| --- | ---: | ---: | ---: | ---: |
| Low | 1 | 0.25 | 225 → 450 | 1,575 → 3,150 |
| Base | 10 | 1 | 900 → 1,800 | 63,000 → 126,000 |
| High | 16 | 4 | 3,600 → 7,200 | 403,200 → 806,400 |

These are item counts only. The assessment assigns no item-to-Vault or
item-to-token conversion and includes no sale proceeds. Current
EconomyShopGUI SELL remains a possible separate redemption route; the planned
SELL-to-contract change is future and is not modeled here.

The hunger rule remains one hunger lost per active minute, with one existing
bread consumed at hunger 14 or lower and six hunger restored. At steady maximum
use, that is up to 10 bread per active worker-hour: 2.5/10/40 per city-day in
these cases. The initial food reserve, breaks and missing bread can reduce
actual consumption. Hall pickup moves up to four existing bread per refill;
the assessment assumes 1/3/10 refills per worker-day, or at most 4/12/40 bread
transferred per city-day. These transfers move stock from the hall to a
resident's inventory; they do not issue items. The worker continues to consume
the same bread under the existing hunger rule. Network-week transfer potential
is 28/840/4,480 bread across the three scenarios; this is gross movement, not
net supply.

For death sensitivity only, the assessment reuses the canonical inventory's
illustrative 0/0.1/1 resident deaths per city-day. It uses illustrative carried
amounts of 16 bread, four profession tools and four armor pieces per death.
Before, those existing items are lost when the resident is removed. After, the
current task implementation stores the resident's inventory in a corpse for
five minutes and permits item transfer from it; unclaimed corpses expire. The
calculator records those items as temporary escrow, not a mint. It does not
know the actual inventory, death rate, claim rate or items recovered, so its
escrow totals are modeled availability opportunities, not player gains. If a
corpse is unclaimed, its items still expire; long-run avoided loss cannot be
quantified without recovery observations.

Under those death assumptions, the modeled loadout escrow per city-day is
0/1.6/16 bread, 0/0.4/4 profession tools and 0/0.4/4 armor pieces. Network-week
escrow potential is 0/112/1,792 bread and 0/28/448 pieces in each gear category.
The baseline contains matching loadout item-loss flows. The after-case moves
those items into temporary escrow; it does not prove that they were looted.
Resident feeding remains 2.5/10/40 bread burned per city-day before and after;
any apparent net burn reduction in the calculator comes from moving the death
loss into temporary escrow, not from lowering food consumption.

The paid builder work session changes from 8 to 64 already-paid construction
rows, while the serial placer remains capped at four placement units per
second. Existing row debits and total material costs do not change, so the
larger authorization batch adds no construction items or refunds. The current
credit cap is `MAX_POPULATION * BUILDER_SESSION_ROWS` (96 rows at 12 residents);
with 64 rows per session and the same formula, it becomes 768. This increases
the possible stored backlog, not the placer rate.

## Evidence and limits

- Baseline `CivsWorksites.mine` assigned 4,000 ms; the candidate assigns 2,000 ms and uses the current
  block's native drops: [CivsWorksites.kt](../src/main/kotlin/ru/arc/civs/CivsWorksites.kt#L461).
- Hunger timing and bread use are implemented in
  [CivsResidents.kt](../src/main/kotlin/ru/arc/civs/CivsResidents.kt#L17).
- The miner reach changes from three to 4.5 blocks from the resident's eye, with line of sight; this remains separate
  from the 88-block depot route limit: [CivsCitizens.kt](../src/main/kotlin/ru/arc/civs/CivsCitizens.kt#L1674)
  and [CivsRules.kt](../src/main/kotlin/ru/arc/civs/CivsRules.kt#L425).
- Current task changes set the paid builder batch to 64 rows in
  [CivsRules.kt](../src/main/kotlin/ru/arc/civs/CivsRules.kt#L14). Since the
  credit limit is derived from population times session rows, it becomes 768
  if that expression is retained.
- The separate placement cadence remains one block or indivisible pair every
  five server ticks, up to four placement units per second:
  [resident-lifecycle-economy-assessment.md](resident-lifecycle-economy-assessment.md#L7).
- Current task changes set corpse lifetime to five minutes in
  [CivsCorpses.kt](../src/main/kotlin/ru/arc/civs/CivsCorpses.kt#L3); corpse
  inventory transfer and expiry are in
  [CivsService.kt](../src/main/kotlin/ru/arc/civs/CivsService.kt#L999) and
  [CivsService.kt](../src/main/kotlin/ru/arc/civs/CivsService.kt#L2093).
- Hall pickup currently takes at most four available bread, limited by bag
  capacity, from the town hall: [CivsService.kt](../src/main/kotlin/ru/arc/civs/CivsService.kt#L1282).
- The prior economy inventory states the death-loss caps and the illustrative
  death scenarios: [economy-inventory.md](../../ruscrafting-ops/docs/knowledge/economy-inventory.md#L110).

The candidate worksite implements 2,000 ms mining actions. Defensive NPC combat
adds native monster deaths and drops without changing their loot tables: one attack
per second per resident, at most 48 active residents, with a 10-second threat window
and low-health retreat. Kill rate, drop mix and recovery rates are unmeasured; no
item-to-currency conversion or invented loot quantity is assigned. The evaluator
checks the arithmetic and keeps Vault, tokens and each item unit separate; it
does not verify delivery, runtime activation, native drop distributions or
player recovery from corpses. Direct Vault and token deltas are both zero.

Inputs: [assessment JSON](resident-supplies-and-safety-economy-assessment.json).
Calculator output: [assessment result](resident-supplies-and-safety-economy-assessment-result.json).
