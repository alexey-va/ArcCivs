# ArcCivs 0.1.14 economy assessment

The requested change opens the former closed WOOD/STONE/FOOD stock into real
vanilla items. Buildings now reserve their exact material palette; owners can
withdraw production and recover paid blocks by remodeling completed buildings.
ArcCivs still has **zero direct Vault, token or XP issuance**. Item export is a
new economic flow and may create indirect coin issuance through active shops.

This is a source assessment, not activation or measured player throughput.
[Calculator input](economy-assessment.json) and [validated result](economy-assessment-result.json)
keep items and currencies separate. The previous closed-stock design described
in older revisions is superseded by this document.

| Flow | Before 0.1.13 | Proposed 0.1.13 |
| --- | --- | --- |
| Founder grant, once | 96 wood, 96 stone, 24 food, inaccessible outside city | 96 oak logs, 96 cobblestone, 24 bread in the common reserve |
| Legacy migration | Existing closed stock | Exact remaining stock converted 1:1, after any paid legacy order completes |
| Construction | Abstract W/S/F price; protected generated structure | Exact changed blocks from captured plan, reserved once; completed blocks editable |
| Logs per worker trip, L1/L2/L3 | 4/8/12 closed wood | 4/8/12 chosen logs |
| Stone per worker trip | 3/6/9 closed stone | 3/6/9 chosen stone items |
| Farm per worker trip | 3/6/9 closed food | 3/6/9 raw crop items or 1/2/3 bread, using the three-wheat recipe ratio |
| Additional resident | 12 closed food | 12 bread; first four arrive gradually without an extra charge |
| Native ↔ virtual storage | Not available | Transfer of the same items; no issuance |
| Vault / tokens / XP | 0 / 0 / 0 | 0 / 0 / 0 directly |

Construction costs depend on style, level and terrain already matching the
plan. The immutable plan determines the exact list before reservation; no
fictional universal coin cost or accumulation time is asserted. As a scale
reference, the curated Nordic L1 builder hut requires approximately 1,000
placeable items and the L1 hall approximately 2,200 before terrain reuse.
Recruitment and construction consume items, while deposits/withdrawals merely
move them. Repeated clicks and recovered committed receipts cannot pay twice;
metadata-bearing items are not consumed as plain materials.

## Rates, capacity and participation

Every reward requires a completed NPC trip with an active nearby viewer. The
production interval is at least 60 seconds, plus navigation. No offline catch-up
is implemented. A city has at most 12 workers and 16 buildings; one server can
show at most 48 NPCs. The 500-city setting is a per-server durable-data cap,
not simultaneous production capacity.

Each building has its own virtual warehouse: 1,728 / 3,456 / 5,184 items at
levels 1 / 2 / 3, plus its physical 27-slot control barrel. Virtual production
credits only the producing building's warehouse and stops when it is full.
Physical production requires space in loaded registered city containers.
There are at most 16 additionally registered chests/barrels per city. A double
chest contributes only each explicitly registered 27-slot half. The common
reserve contains the one-time grant or converted legacy stock; production does
not replenish it. Switching storage mode does not duplicate stock.

These mutually exclusive scenarios allocate all listed workers to one resource:
low = one L1 worker for one hour; base = one L2 worker for two hours; high =
12 L3 workers for four hours. Network participation is 1 / 10 / 16 cities per
day; the high case must be staggered to respect the 48-NPC server limit. The
figures assume uninterrupted trips and withdrawals and are gross ceilings.

| Separate item unit | Owner-day low / base / high | Network-week low / base / high |
| --- | ---: | ---: |
| Logs | 240 / 960 / 34,560 | 1,680 / 67,200 / 3,870,720 |
| Stone or raw crop | 180 / 720 / 25,920 | 1,260 / 50,400 / 2,903,040 |
| Bread | 60 / 240 / 8,640 | 420 / 16,800 / 967,680 |

## Indirect coin exposure and observations

Read-only 168-hour live ledger observation on 2026-10-18 contained about 12.8k
records / 66k operations / 31 players. Currency-qualified Vault mint totals were
shop 5,752,268.59, jobs 396,907.80, farms 16,135 and contracts 35,352.64. Admin
67,001,499 is excluded from normal income. Tokens (61 minted, 21 burned) are a
separate ledger. Legacy totals without currency were not used. Requested item
attribution only observed 90 cobblestone for 4.49 coins; absent material rows
mean missing observation, not a zero sale price.

Tracked shop configuration gives illustrative SELL values: most logs 2 coins,
spruce log 0.5, cobblestone 0.05, stone 0.36, cobbled deepslate 0.34, bread 8,
and wheat/carrot/potato 2. These are disk values, not verified current runtime
quotes. At the bread value, low/base/high production could correspond to
480 / 1,920 / 69,120 coins per owner-day if every item is actually sold.
A theoretical 24-hour, 12-L3-worker bread city could make 34,560 bread and
276,480 coins at that quote. This requires continuous viewers, successful trips,
withdrawals and sales; it is not observed income or a guaranteed return.

The planned EconomyShopGUI SELL-to-contract transition remains a future
scenario. This task does not disable SELL, AutoSellChests, or change shop prices.
No current contract multiplier, payout or stacking bonus is assumed for these
items. Global unique membership limits simultaneous cities per account. Version
0.1.14 adds deletion: a durable deleted-city record also records the owner, so a
new city after deletion receives no new starting reserve, including on another
backend. Different accounts still have separate first grants. The grant is not
daily income.

## Decision and verification boundary

Implement the requested real storage and construction with the existing raw
production rate, recipe-adjusted bread, exact material sink and durable receipt
recovery. Monetary exposure is now nonzero indirectly; capacity and travel
bound output but do not establish long-run balance. After separately authorized
activation, a bounded real-player run should measure trip times, item export,
full warehouses and actual SELL/contract receipts before changing production
rates. No automatic activation or later monitoring is scheduled by this task.

The local inventory journal and SQL revision fence operation replay; native
chunk persistence and SQL are not a distributed transaction. A hard machine
failure that rolls back a chunk after receipt retirement remains a platform
boundary. Back up SQL, world chunks and local plans/captured/inventory journal
files together. Ordinary restart recovery and repeated actions have focused
regression coverage; this assessment does not claim power-loss atomicity.

## Deletion change (0.1.13 to 0.1.14)

First founding remains 96 oak logs, 96 cobblestone and 24 bread per owner. Before,
there was no deletion action; after, any number of delete/refound cycles grants
0 additional logs, 0 cobblestone and 0 bread. Common and building virtual stock,
and remaining legacy stock, must be withdrawn before deletion. Native contents
and placed buildings remain in the world. Reserved construction materials are
not refunded; unfinished buildings remain partially built. No direct Vault,
token or XP flow is introduced, and production rates/capacities are unchanged.

SQL migration 2 records immutable deleted city IDs and owner history under the
existing registry transaction lock. Standalone storage writes an atomic deletion
record before removing its city file; later SQL import imports that history.
Exact deletion retries are idempotent; stale saves/imports cannot restore the
deleted ID. Backups must retain the deletion history as well as active cities.
This is a resource-preservation and repeat-grant check, not a claim of live
activation or measured behavior.

Independent review also identified a free-control loop: keeping a generated
barrel after deletion could mint another on refounding. New SITE registration
now requires a player-placed barrel, checked before and after world capture.
SITE/CONSTRUCTING controls never regenerate automatically; a missing SITE
control can be replaced by an owner/member using an ordinary barrel. Starting
construction requires it present. Thus each new site uses an existing item and
delete/refound creates zero net new barrels. Existing ACTIVE legacy controls
keep compatibility repair; new cities are never founded as ACTIVE. No native
container contents are removed, converted or refunded by deletion.
