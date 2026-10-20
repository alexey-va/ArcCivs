# ArcCivs 0.1.27: construction and resident lifecycle

Compared with the active Spawn 0.1.26 baseline, this release changes when an
observed builder places a paid block and how residents travel, rest and appear.
It changes no recipe, item quantity, reward, price or currency integration.

Night sheltering already existed at world ticks 12000–23000. Visible sleep adds
no new production shutdown window. Native construction now waits for an awake,
stationary builder and places one block or indivisible pair per five ticks,
including across cropped schematic parts. Previously accepted resources remain
covered by the existing batch journal. Cosmetic held blocks are never deposited
into inventories.

The attached calculator keeps the previous matched-activity scenarios:
1/10/16 participating cities with level 1/2/3 worksites and 1/2/3 residents.
Before and after, the respective city-day alternatives are 1/4/9 mature crop
receipts or bread recipes, 1/2/3 tree cuts with assumed 4/12/128 logs per cut,
and 5/10/15 changed quarry cells. Inputs and outputs are identical at that
activity; the exact native crop/ore basket is not projected. The largest tree
case consumes finite world stock and is not a sustainable income forecast.

Campfires add bounded idle/evening rest. Improved recovery may increase work
availability, while rest and observed placement may decrease it. No actual
throughput measurement is available. As an explicitly hypothetical sensitivity,
spending 0/5/15 percent of previously active time resting yields work-bound
activity multipliers of 1.00/0.95/0.85. Growth, deposits, supplies, native tool
durability, viewers and storage can dominate either effect.

Direct Vault, premium tokens and player XP each remain 0 before and after.
Internal profession points remain distinct from player XP. No item-to-money
exchange rate is introduced. Current EconomyShopGUI SELL and the planned future
SELL-to-contract transition remain separate.

A fresh read-only 168-hour Spawn ledger contains 3,769 records, 31,068 operations
and 33 players, with exact window boundaries. Currency-specific records are
separate; administrative and unknown-source operations remain. This network
observation is not an ArcCivs yield measurement or ordinary-player income model.

The [input](resident-lifecycle-economy-assessment.json) and
[calculator result](resident-lifecycle-economy-assessment-result.json) verify
matched-activity arithmetic. Delivery, activation, real route duration and
native-client behavior require separate evidence.
