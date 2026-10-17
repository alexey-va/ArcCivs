# ArcCivs economy assessment

## Decision

The source-defined design adds **no direct Vault or token mint/burn**, no XP reward,
and no player-inventory item export. It adds a closed settlement stock for wood,
stone, and food. Those stocks pay construction and recruitment costs; they cannot
be withdrawn or redeemed as money. The monetary balance impact is therefore zero
in this static model. The production figures below are gross, assumed ceilings,
not measured player throughput. Storage caps and real NPC travel reduce them.

This is a source assessment only. It does not establish that ArcCivs has been
published, deployed, activated, or observed by players. The calculator input is
[`economy-assessment.json`](economy-assessment.json); its machine-readable result
is [`../../artifacts/arccivs-20261017/economy-assessment-result.json`](../../artifacts/arccivs-20261017/economy-assessment-result.json).

## External economy flows

| Ledger | Before | After | Evidence |
|---|---:|---:|---|
| Vault | 0 mint / 0 burn | 0 mint / 0 burn | No Vault payout, price, or integration in the Civs rules; building costs are resource stock ([`CivsRules.kt:5-13,15-31`](../src/main/kotlin/ru/arc/civs/CivsRules.kt)). |
| Tokens | 0 mint / 0 burn | 0 mint / 0 burn | No token payout, price, or redemption is defined by the source model. |
| XP | 0 | 0 | No XP reward path is defined. |
| Physical inventory items | 0 | 0 | Internal stock is not withdrawable as items or money; generated structures are permanent protected assets, not loot ([`ARCHITECTURE.md:17-21,32-38`](../ARCHITECTURE.md), [`CivsProtection.kt:35-38,139,198-216`](../src/main/kotlin/ru/arc/civs/CivsProtection.kt)). |

The local source search found no Vault/RedisEconomy, token, XP-grant, or inventory
reward calls in ArcCivs' Kotlin source or resources. `CivsProtection` cancels
block drops and inventory interaction in protected building bounds. Its protection
does not cover the whole settlement radius. The plugin does not remove or replace
the current EconomyShopGUI SELL or AutoSellChests flows; they are not modeled as
ArcCivs income.

## Internal stock and construction costs

All amounts in this section are separate, non-transferable **wood, stone, or food
stock units**. They are not combined with Vault, tokens, XP, or item prices.
`CivsRules.found` grants 96 wood, 96 stone, and 24 food with four pioneers once
when a city is created; reload/rejoin does not repeat that grant
([`CivsRules.kt:80-95`](../src/main/kotlin/ru/arc/civs/CivsRules.kt),
[`ARCHITECTURE.md:17-21`](../ARCHITECTURE.md)). At the requested 500-city ceiling,
those one-time grants total 48,000 wood, 48,000 stone, and 12,000 food over the
lifetime of all cities. This is internal stock, not coin or token issuance.

The table gives the **cost paid when starting each target level**. A level 3
building has paid the L1, L2, and L3 rows. The source calculates levels 1..3 from
these costs and reserves the cost once before construction; retries/recovery do
not charge again ([`CivsRules.kt:15-31,139-173`](../src/main/kotlin/ru/arc/civs/CivsRules.kt)).

| Building | L1 cost W/S/F | L2 cost W/S/F | L3 cost W/S/F | Cumulative L1→L3 W/S/F |
|---|---:|---:|---:|---:|
| Town hall | 0 / 0 / 0 | 128 / 96 / 24 | 384 / 288 / 72 | 512 / 384 / 96 |
| Warehouse | 32 / 16 / 0 | 128 / 64 / 0 | 288 / 144 / 0 | 448 / 224 / 0 |
| House | 24 / 12 / 0 | 96 / 48 / 0 | 216 / 108 / 0 | 336 / 168 / 0 |
| Forester | 20 / 12 / 0 | 80 / 48 / 0 | 180 / 108 / 0 | 280 / 168 / 0 |
| Quarry | 20 / 20 / 0 | 80 / 80 / 0 | 180 / 180 / 0 | 280 / 280 / 0 |
| Farm | 24 / 8 / 0 | 96 / 32 / 0 | 216 / 72 / 0 | 336 / 112 / 0 |

One of each building type progressed through L3 costs 2,192 wood, 1,336 stone,
and 96 food. Reaching 12 residents also needs an extra L1 house: the town hall
provides four beds and a level 3 house adds six, so one house alone gives only
10 beds. Eight recruits cost another 96 food at 12 each. That example totals
2,216 wood, 1,348 stone, and 192 food. After subtracting the once-only starting
grant, the city must produce a gross 2,120 wood, 1,252 stone, and 168 food to
fund that progression. These are resource requirements, not daily rates or
real-money equivalents. Recruitment, housing, population, and cost rules are in
[`CivsRules.kt:34-49,98-107`](../src/main/kotlin/ru/arc/civs/CivsRules.kt).

New buildings also reserve enough stock to keep the L1 forester and quarry
affordable during bootstrap ([`CivsRules.kt:154-170`](../src/main/kotlin/ru/arc/civs/CivsRules.kt)).
This makes startup ordering a real constraint: the initial stock is not free
spending capacity for arbitrary upgrades.

## Production potential and caps

An active forester produces 4 wood per trip per level; an active quarry or farm
produces 3 stone or food per trip per level. A production building has 1/2/3
worker slots at L1/L2/L3, and the rules enforce a 60-second production interval
([`CivsRules.kt:46-73`](../src/main/kotlin/ru/arc/civs/CivsRules.kt)). The game
counts a delivery only after an observed completed worker trip; elapsed time
alone does not award stock. Each cycle includes travel to storage, work, and
return, and production needs a nearby viewer. There is no offline catch-up
([`CivsRules.kt:208-238`](../src/main/kotlin/ru/arc/civs/CivsRules.kt),
[`CivsCitizens.kt:480-539`](../src/main/kotlin/ru/arc/civs/CivsCitizens.kt),
[`ARCHITECTURE.md:23-30`](../ARCHITECTURE.md)).

For a transparent upper-bound comparison, the input assumes 60 completed trips
per worker-hour before travel: one L1 producer for one hour in low, one L2
producer for two hours in base, and three L3 producers for four hours in high.
The network participants are 1, 10, and 16 active city owners respectively.
High uses exactly 48 active NPCs (16 × 3), at the server-wide limit. These are
scenarios, not telemetry. Each resource is evaluated independently; adding the
three high rows would reuse those same 48 NPCs three times.

| Resource | L1/L2/L3 per trip | Per active owner-day, low / base / high | Network week, low / base / high |
|---|---:|---:|---:|
| Wood | 4 / 8 / 12 | 240 / 960 / 8,640 | 1,680 / 67,200 / 967,680 |
| Stone | 3 / 6 / 9 | 180 / 720 / 6,480 | 1,260 / 50,400 / 725,760 |
| Food | 3 / 6 / 9 | 180 / 720 / 6,480 | 1,260 / 50,400 / 725,760 |

The table is gross production potential before construction/recruitment spending,
route duration, city sleep, and storage-full pauses. Internal stock has a base
capacity of 128 per resource; each active L1/L2/L3 warehouse adds 256/512/1,024
to each resource's capacity. One active L3 warehouse therefore gives 1,152
capacity per resource. If storage lacks room for the next trip, delivery is
rejected as `storage-full`; the system does not overflow stock or export it
([`CivsRules.kt:34-38,230-235`](../src/main/kotlin/ru/arc/civs/CivsRules.kt),
[`CivsCitizens.kt:500-504`](../src/main/kotlin/ru/arc/civs/CivsCitizens.kt)).

The city limit is 500, each city caps at 12 residents and 16 buildings, and the
active NPC ceiling is 48 across the server. The first two are durable-data caps;
they do not mean that 6,000 workers can all produce concurrently. Offline cities
do not produce, and inactive/hidden NPCs sleep. The storage cap makes high gross
throughput unsustainable without resource spending: with one L3 warehouse and
the starting grant still in stock, only 1,056 additional wood or stone and
1,128 food fit. The actual time to fill depends on travel and spending, neither
of which is available as production telemetry here.

## Balance interpretation and follow-up

The design is closed with respect to the measured monetary economy: there is no
new Vault/token source, no conversion of stock to money, and no item-sale path
for generated buildings. The principal balance variable is internal progression
speed. Source-defined production has a hard storage cap, requires active players
nearby, and pays only observed trips, while full L3 progression has explicit
wood/stone/food costs. The theoretical high case reaches storage quickly if
spending and travel are ignored, so it must not be presented as a sustainable
weekly earning rate.

No rates or prices were changed by this assessment. To validate progression
speed after delivery, observe completed trips, route/work duration, stock-full
pauses, construction reservations, and stock spending in a bounded player test;
then compare actual per-city stock changes against these source limits. That
runtime and client check is not part of this static assessment.
