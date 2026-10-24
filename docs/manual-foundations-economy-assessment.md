# Manual foundation earthworks economy assessment

This assessment covers the manual foundation path that fills support gaps with
world DIRT and clears natural obstructions to AIR. It does not add a reward,
debit, shop rule, or authored-building material cost.

## Direct effects

The current implementation journals a bounded plan before applying it. Each
accepted building plan may change at most 32,768 cells and reads at most
131,072 cells. A changed cell becomes either AIR or a support block; AIR cuts
produce no drops. The application writes block data directly with no
`breakNaturally`, inventory transfer, or physics. Therefore generation itself
adds no DIRT to a player's inventory and pays 0 Vault, 0 tokens, and 0 player
XP. It also debits no player inventory. Existing authored building material
prices remain unchanged. Active buildings are not retrofitted; waiting manual SITE
designs receive a fresh foundation capture when construction starts. The bounds are in
[`CivsAutonomousTerraform.kt`](../src/main/kotlin/ru/arc/civs/CivsAutonomousTerraform.kt#L38)
and direct no-drop application is in
[`CivsWorld.kt`](../src/main/kotlin/ru/arc/civs/CivsWorld.kt#L1085).

The item-flow scenarios below count only world DIRT fills, not inventory items.
Low/base/high assume 0.1/1/4 accepted foundations per player-day, with
64/512/4,096 DIRT fill blocks per foundation and 0.25/1/4 active player-hours
per day. These are explicit scenarios, not observed usage or daily limits.
They imply:

| Scenario | Foundations per player-day | DIRT fills per foundation | Active hours per day | World DIRT per day | World DIRT per active hour |
|---|---:|---:|---:|---:|---:|
| Low | 0.1 | 64 | 0.25 | 6.4 | 25.6 |
| Base | 1 | 512 | 1 | 512 | 512 |
| High | 4 | 4,096 | 4 | 16,384 | 4,096 |

World DIRT is an item-unit count, not a player inventory credit.

## Conditional resale exposure

The tracked EconomyShopGUI `Blocks.yml` entry prices DIRT SELL at 1 Vault per
item. A read-only 168-hour ledger snapshot generated 2026-10-24 records 87 DIRT
sold for 86.77 Vault across two transactions by one player (effective realized
amount 0.9973563 Vault per item). This proves recent DIRT sales exist; it does
not attribute any of them to ArcCivs or establish that generated foundation
blocks are being mined.

At the tracked nominal price, the conditional all-recovered/all-sold scenario
would yield at most 6.4/512/16,384 Vault per player-day, or 44.8/3,584/114,688
Vault per week for one hypothetical participant. This is an indirect resale
ceiling, not an ArcCivs payout or observed income. It assumes every generated
fill block is later removable, obtained, and sold, with no loss or other
restriction. Cuts consume the same per-plan changed-cell budget but create no
item. If all 32,768 changed cells in one accepted plan were fills and every
block were later sold at the tracked nominal price, the per-plan conditional
ceiling would be 32,768 Vault; actual fill count is reduced by any cuts and may
be lower for terrain that needs fewer supports.

Protection does not establish a universal barrier around all earthworks:
ArcCivs protection covers authored building footprints and says the settlement
radius itself grants no event protection ([`CivsProtection.kt`](../src/main/kotlin/ru/arc/civs/CivsProtection.kt#L37),
with actor-sensitive block breaking at [line 188](../src/main/kotlin/ru/arc/civs/CivsProtection.kt#L188)).
Members may remodel their buildings; earthworks outside those footprints may
be subject to other runtime claim protections. Whether any particular support
block can be mined and resold is therefore location- and runtime-dependent.
The ceiling above is intentionally conditional, not a claim that mining or sale
is guaranteed.

The owner's planned EconomyShopGUI SELL-to-contract transition remains future.
The current tracked SELL flow is modeled above; after SELL is actually disabled,
this direct shop redemption becomes 0 until a separately active, budgeted DIRT
contract exists. No future contract income is assumed. This change introduces
no new restriction, price, or contract.

## Decision and verification boundary

Accept the requested support fill without adding economic gates: the plugin
issues no inventory item or direct currency, and the per-plan changed-cell cap
bounds each accepted earthwork operation. Keep the item count, current
conditional shop resale, and future SELL-off scenario separate. The
[calculation](manual-foundations-economy-assessment.json) and
[generated result](manual-foundations-economy-assessment-result.json) are in
adjacent JSON files. Source-level behavior is not evidence of delivery,
activation, or measured player behavior. The ledger records existing DIRT
sales, not ArcCivs activity; no plugin-activation or native-client observation
is claimed here.
