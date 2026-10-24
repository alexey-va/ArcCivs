# Settlement variety and infrastructure assessment — 0.1.68

The catalog adds taverns, bakeries and guard towers, each with three levels in three existing architectural families. Autonomous villages add a tavern, a guard tower and two more ordinary houses, reaching eleven sites across nine types. The resident cap remains twelve. Bakeries require native wheat and are available to player settlements; autonomous primitive stock is not converted into withdrawable ingredients or products.

## Inputs, outputs and limits

Native bakery work consumes exactly three wheat for one bread through the existing inventory receipt. A fed worker has a 60-second cycle; hunger doubles it, and travel, ingredients and storage can reduce throughput. Levels allow one, two or three workers. At assumed 10/30/60 completed cycles per active hour and 1/4/8 active hours per day, a worker produces 10/120/480 bread while consuming 30/360/1,440 wheat. These are sensitivity assumptions, not measured utilization. The vanilla transformation ratio is unchanged; no item-sale price or currency profit is assumed.

Taverns provide four actual beds at every level. Their primitive wood/stone costs are 32/16, 128/64 and 288/144. Guard towers cost 24/24, 96/96 and 216/216, employ one guard and issue no free gear. Two extra houses through level two cost a combined 240 wood and 120 stone. Together with the new tavern and guard through level two, these additions consume 520 wood and 320 stone. More beds do not mint residents, food or equipment.

Walls, gates and roads have one level, no employment or inventory capacity and a separate limit of 96 pieces. A piece spans at most 24 blocks; a road piece includes at most 96 columns. Primitive prices per piece remain wall 8 wood/16 stone, gate 12/16, road 0/8. Native player construction continues to use its exact captured schematic bill. Existing accepted orders retain their costs.

## Deterministic planning examples

These are actual outputs of the runtime planner over specified fixtures, not hand-drawn settlement concepts. All seven fixtures place eleven buildings. Infrastructure costs are separate from those buildings:

| Fixture | Walls | Gates | Roads | Wood | Stone | Closed perimeter |
|---|---:|---:|---:|---:|---:|---|
| Medieval oak, flat | 21 | 3 | 33 | 204 | 648 | Yes |
| Medieval oak, hills | 21 | 3 | 70 | 204 | 944 | Yes |
| Medieval oak, water | 21 | 3 | 28 | 204 | 608 | Yes |
| Nordic, flat | 23 | 3 | 32 | 220 | 672 | Yes |
| Caledonia, flat | 23 | 3 | 34 | 220 | 688 | Yes |
| Cliff fixture | 19 | 3 | 28 | 188 | 576 | No: two steep gaps |
| Existing-building fixture | 20 | 2 | 26 | 184 | 560 | No: an existing house intersects the perimeter |

The planner reports specific blockers instead of placing walls through accepted buildings or claiming a partial perimeter is complete. Infrastructure exhaustion must not prevent ordinary building upgrades.

The 0.1.72 demand correction stops infrastructure construction approaches from generating additional paving demand and schedules gates/walls before optional roads. Prices, the 96-piece limit and accepted receipts stay unchanged. The verified full Auto snapshot still requires a separate migration decision; the correction does not claim its perimeter is restored. See the [paired replay and resource assessment](infrastructure-demand-recovery.md).

## Mine frames and storage

V5 preserves immutable V2/V3/V4 receipt ordinals, one ladder shaft, and the existing three/four/five tiers. Fences, framing and lining consume actual carried items. Compared with V4, levels one/two/three add 346/444/542 cobblestone, 152/200/248 logs, 243/324/405 planks and 64/80/96 fences. The inaccessible outer corner columns and one backing-side column remain native and are excluded from these bills; ordinary frontier safety checks remain active. Each tier also has 60 additional clearance operations. Only actual native blocks produce their normal drops; unchanged cells do not require replacement payments.

Ore probabilities and the renewable generator remain unchanged. The additional buildings' private payload contains 27 current templates and seven exact legacy bakery copies. Source-authored rack conversions add eleven empty chests and clear one stacked rack cell; genuine decorative bookshelves remain. These are catalog capacities and construction inputs, not recurring item grants or measured live inventory.

## Currency and evidence boundaries

Direct deltas are separately zero Vault, zero premium tokens and zero player XP. Abstract autonomous wood/stone/food remain separate from native items and currency. The current SELL flow and planned SELL-to-contract transition are unchanged. Restored worker progress may increase realized production, but no measured yield or new sale value is inferred.

[Machine-readable assumptions](settlement-variety-assessment.json) are checked by the canonical economy evaluator. Source tests, native decoder/persistence smoke tests, delivery hashes, active runtime and observed live work are distinct evidence layers; offline preview geometry does not prove native-client appearance or every real terrain route.
