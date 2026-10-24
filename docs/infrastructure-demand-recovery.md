# Infrastructure demand recovery — 0.1.72

The Auto snapshot captured on Spawn at 2026-10-24 13:24:20–22 UTC contains 107 ACTIVE records: eleven ordinary buildings, 93 roads and three gates. No wall records exist. All 96 infrastructure slots are occupied. Snapshot SHA-256: `2e731e4ef4824000197f753321f4e8a3444afd33da8d44b8785fb5fdc0c4d145`.

The planner previously used every retained construction approach as demand for new paving. This included approaches to roads and gates themselves. Placement then preferred roads over gates and walls. The planner's `infrastructure-budget` rejection was diagnostic; the final rules and autonomy admission enforced the 96-record limit. Consequently, infrastructure approaches could create further road demand and consume the capacity before wall work.

The correction uses ordinary-building approaches as paving demand. All retained approaches still supply crossing protection and grades, and accepted infrastructure still retains its exact geometry. Candidate placement prefers gates, then walls, then roads. The capacity, prices, admission guards, native capture and immutable accepted records are unchanged.

## Replay and limits

A detached replay used the compiled 0.1.71 planner, the verified snapshot's city, surface, routes and infrastructure. The export contains block counts, not full infrastructure after-states. Accepted cells were therefore reconstructed only as the XZ/minimum-height projection consumed by this planner. This establishes the demand and rejection behavior; it is not an exact journal-byte or native collision replay.

| Replay | Existing pieces | New walls | New roads | New road columns | Total pieces |
|---|---:|---:|---:|---:|---:|
| All 107 routes generate paving | 96 | 6 | 10 | 106 | 112 |
| All 107 routes protect access; eleven ordinary routes generate paving | 96 | 6 | 3 | 48 | 105 |

Filtering every use of the routes would instead produce 106 pieces and weaken the protection contract. Four ordinary legacy approaches (quarry, farm, warehouse and tavern) already cross the reserved perimeter outside gates, at twelve distinct columns. None of the infrastructure approaches or accepted road columns crosses that reserved wall area. Of sixteen nominal wall segments, six pass, seven fail on neighbouring grade differences of three to six blocks, and three intersect retained approaches. One of those three also includes a non-natural column; its actual native block type is unknown. The correction preserves these refusals.

Existing Auto is not repaired by changing demand: its 96 occupied slots still prevent new infrastructure, and six possible wall pieces do not constitute a closed perimeter. Even hypothetical free capacity would not resolve the terrain and crossing constraints. This release does not remove, merge, relocate or refund accepted roads, raise a limit, activate V2, or rebuild the village. A separate migration/layout decision is required for that town.

## Resource assessment

The unchanged abstract unit prices are WALL 8 wood/16 stone, GATE 12/16 and ROAD 0/8; the infrastructure cap remains 96. In the detached unaccepted candidate sets above, six walls plus ten roads would cost 48 wood/176 stone; six walls plus three roads would cost 48 wood/120 stone. The difference is 56 abstract stone of candidate demand, not a grant, refund or observed debit. Both sets remain inadmissible in the full live town; their current admitted incremental cost is zero.

Existing accepted costs and resource receipts are preserved. There is no native item conversion, payout, output-rate, population or stock-limit change. Direct deltas remain separately zero Vault, zero premium tokens and zero player XP. Existing SELL and the planned SELL-to-contract transition are unchanged. Restored scheduling or reduced unnecessary construction is not measured production or currency income.

Source regressions, CI, delivery, active runtime and live walking are recorded separately in the operations report. Retained routes and ACTIVE records do not establish current native connectivity.
