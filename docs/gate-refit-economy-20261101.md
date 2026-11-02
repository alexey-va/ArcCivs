# Same-bounds Caledonia gate: source assessment

The new Caledonia gate has grounded supports, a three- or four-block-wide opening
and a shallow timber cap. The profile accepts exact older level-one gate captures
five or six columns across, five columns deep, and six or seven blocks high, with
the saved flat or one-block-stepped transit floor. The clean V3 opening has no
freestanding fence-gate panels; frozen V2 receipts retain their original recipe
for exact recovery. Six support columns cease to be public transit,
leaving 19 or 24 walkable columns inside the unchanged paid bounds.

All four supplied Auto2 captures pass the source audit. One gate's own approach
needs a two-step detour inside its existing floor; its external endpoints, outside
segments, prior grade repair and every other route remain unchanged. Typed gate
and capture metadata persist this narrow exception with the geometry. Unsupported
captures or routes that cannot satisfy these constraints are rejected; the native
floor and body/step clearance are checked before any refit writes.

The canonical economy inventory was consulted. This is source evidence, not a
live price, delivery or throughput claim. The parent owns the corresponding
inventory entry and all native operations.

## Material debit for a new physical gate

These are raw items for a full new capture from air. Native cells already matching
the target cost nothing through the existing `CivsMaterials.required` calculation.
Amounts for distinct item types are not combined into a currency.

| Item | Previous generator | New profile |
|---|---:|---:|
| Cobblestone | 25 | 25 |
| Stone bricks | 5 | 6 |
| Dark oak fence gates | 5 | 0 |
| Dark oak logs | 0 | 27 |
| Dark oak stairs | 0 | 10 |
| Dark oak slabs | 0 | 5 |

The legacy closed-Stock construction price remains 12 wood, 16 stone, 0 food.
Physical inventory and closed Stock are alternative accounting modes, not two
payments for one gate. Existing accepted construction receipts keep their captured
materials; no old bill is repriced. The new material demand can slow future
construction; acquisition and builder throughput have not been measured.

## Saved stepped and six-column variants

The following counts come from the exact source audit with every captured cell
priced from air. Each cell is **before → after**, in raw items. They describe the
captured structure, not a material debit for the administrative refit. Future
ordinary construction still prices its actual native delta; these examples are
neither a fixed bill nor an upper bound for every possible stepped profile.

| Item | Stepped 5×5, no leaves | Stepped 5×5, open leaves | Stepped 6×5, open leaves |
|---|---:|---:|---:|
| Cobblestone | 25 → 25 | 9 → 9 | 6 → 6 |
| Stone bricks | 5 → 6 | 5 → 6 | 6 → 6 |
| Dark oak fence gates | 0 → 0 | 5 → 0 | 6 → 0 |
| Dark oak logs | 0 → 33 | 0 → 28 | 0 → 32 |
| Dark oak stairs | 0 → 10 | 0 → 10 | 0 → 12 |
| Dark oak slabs | 0 → 5 | 0 → 5 | 0 → 6 |

The latter two captures omit 16 and 24 existing floor cells with KEEP_NATIVE
semantics. Those native floors are neither added to the bill nor replaced. The
source audit reports zero floor changes for all four gates; the supplied native
support samples agree with every explicitly captured cobblestone floor cell.
Support sampling is not proof that a refit has been applied or is traversable.
The frequency sensitivity calculation below remains the flat 5×5 example.

## Compact generated gate

Fallback settlement layout also generates a 3×3 gate. Its ordinary construction
uses the same grounded timber arch at the existing bounds, with a clear centre
column and corner bypasses. It cannot enter the native maintenance operation.

| Item | Previous compact generator | New compact profile |
|---|---:|---:|
| Cobblestone | 9 | 9 |
| Stone bricks | 3 | 2 |
| Dark oak fence | 3 | 0 |
| Dark oak logs | 0 | 13 |
| Dark oak stairs | 0 | 6 |
| Dark oak slabs | 0 | 3 |

An admitted one-block step changes only the log count to 13–15. Existing native
floor cells remain excluded from payment. These are alternative gate shapes,
not cumulative charges. The separate `compact-gate-economy-20261102.json` assessment records compact
item burns at the same hypothetical frequencies; they are not added to the
large-gate totals. Currency and player XP issuance stay zero.

## Existing-gate maintenance

The explicit console operation repairs one already-owned authored structure. The
initial four audited V2 variants have 48, 49, 54 or 56 native cell changes. The V3
cleanup leaves the already-clean first two gates unchanged and removes exactly
three and four panels from the stepped gates; their floors, transit and route
points stay unchanged. Gate12's saved detour changes only its capture reference.
These changes add
no inventory items, do not refund the removed lintel, do not
simulate production, and do not alter Stock, workers, cycles or skills. It uses the
existing exact world-change journal and a fresh immutable capture, retaining the
original plan and schematic. Replaying an accepted receipt cannot issue resources.
The versioned capture reference preserves old replay and makes an already-clean
gate a no-op without another capture or world write.

Direct before/after issuance is 0 Vault, 0 premium tokens and 0 player XP. No
rewards, sale rates, crafting prices or auto-supply policy change. Current SELL
and the intended SELL-to-contract transition remain separate and unchanged.

## Sensitivity and limits

The adjacent assessment assumes 1/3/10 settlements and 0/1/4 new gates per
settlement-day. These scenarios are assumptions, not observed activity or an
economic limit. Each item has its own burn row. For example, new dark-oak-log
debits are 0/27/108 per settlement-day and 0/567/7,560 per network-week. No
disposable income is assumed, so accumulation time is unknown.

The calculation was checked with the mandatory economy evaluator; its JSON
result is adjacent. A fresh ledger was not collected by this worker because the
parent owns native operations. There is no source path issuing currency or XP,
and no market value is invented for the materials. Future actual construction
frequency and transit effects must be observed separately after delivery.

Source checks must cover the exact item map, unchanged paid floors and old
captures, route conflict rejection, journal preimages/replay, console-only
admission and unchanged worker/inventory/Stock snapshots. Native acceptance must
separately verify the real gate approach, collision clearance and passage in
both directions. The current receipt framework does not independently acknowledge
arbitrary block-region disk persistence before retiring a world-change receipt;
this assessment does not claim that stronger crash guarantee.

Decision: keep ordinary material pricing from the actual new geometry and retain
the existing closed-Stock price. Make no compensating reward, refund, material
substitution or throughput adjustment without evidence.
