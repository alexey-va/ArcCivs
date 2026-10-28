# Auto producer-local storage assessment — 2026-10-28

This assessment covers local storage of closed autonomous stock. It does not change production recipes, yields, rate, construction costs, food consumption, money prices, or contract rules.

## Capacity evidence

Before a successful native-storage capture, Auto2 uses its old city-level cap of 128 base units plus 256 from the active level-1 Warehouse: **384 per resource**. The owner runtime snapshot from `civs debug resources` /storage CITY, revision `32256` (2026-10-28 13:39:56–13:40:04 UTC), reports abstract stock of wood 384, stone 3, and food 382. This is a point-in-time snapshot, not a production-rate measurement. The reported 30 containers are city-wide and are not evidence that one producer owns 30 containers.

Other profiles have different legacy caps. The source rule gives 128 + 512 = 640 per resource for an active level-2 Warehouse; the earlier [autonomous assessment](autonomous-economy-assessment.json) models a completed level-2 profile. The 640 value is not substituted for this Auto2 level-1 baseline.

After a successful capture, the producer's pending abstract stock is bounded by the free slots in its own active workplace container(s). The scenarios below count empty slots for one producer workplace. They are explicit sensitivities, not observed Auto2 slot counts.

| Case | Hypothetical empty slots at one workplace | Capacity if one resource alone uses the slots |
|---|---:|---:|
| Low | 1 | 64 |
| Base | 27 | 1,728 |
| High | 54 | 3,456 |

A normal chest half or barrel has 27 slots; a double chest has 54 across both halves. These figures are theoretical empty-slot capacities before native contents. They should not be added across all 30 city containers to claim producer capacity. Existing native items occupy their actual slots and are not duplicated into abstract stock. Partial native stacks also are not counted as empty slots by this abstract-stock capacity model.

Wood, stone, and food share each workplace's free slots. A resource uses `ceil(count / 64)` slots, so the capacities in the table are not 64/1,728/3,456 for each resource simultaneously. For resource `r`, the implementation's room calculation is:

```text
max(0, 64 * max(0, emptySlots - sum(ceil(otherResourceCount / 64))) - rCount)
```

Warehouse stock also uses a shared pool across wood, stone, and food. Each resource's `ceil(count / 64)` consumes slots from that same depot pool: 27 empty warehouse slots can hold at most 1,728 units when one resource alone occupies them, not 1,728 units per resource. Producer-local slots and warehouse slots remain separate stores; a city-wide count of containers does not become a producer's capacity.

The 384 legacy value is city-level stock capacity; the new values describe a producer-local buffer. They are kept side by side as reference points, not presented as a like-for-like measured delta. If a legacy city snapshot has no successful storage observation, the old formula remains in force. The successful capture stores per-building observations; warehouse stock capacity uses active warehouse slots (or Town Hall slots when no active warehouse exists), while production room uses the assigned workplace's own slots. Couriers move saved pending stock to the warehouse without earning another production cycle.

Accepted cargo retains a stable capacity reservation across later plans until delivery or cancellation; this assessment records the invariant without describing its internal algorithm. Freshness is tied to the storage owner's capture generation: production relies on its assigned producer building's own current generation, while unload relies on all active depot generations being current. Saving a capture after its owner generation has become stale does not authorize the associated production or unload.

## Production and economic effect

Source still defines 4/8/12 abstract wood, 3/6/9 abstract stone, and 3/6/9 abstract food per completed level-1/2/3 producer delivery, with the existing 60-second production interval. Worker travel, hunger, pause, container room, and delivery remain constraints. The storage change does not raise a per-cycle yield or speed. A larger local buffer could keep more already-earned production buffered before `storage-full`; actual extra deliveries or accumulated output have not been measured.

Abstract wood, stone, and food remain closed internal stock. This change issues **zero native log items**. The existing resident-food bridge converts one already-earned abstract FOOD into one ordinary BREAD through its existing durable receipt, at the existing batch limit of four. That bridge does not create new food production or direct currency income. Potential future sale of physical items remains a separate conversion question.

The separate calculator result shows direct deltas of **0 Vault, 0 premium tokens, and 0 player XP** in each sensitivity case. There is no new price, purchase, progression cost, or item-to-currency exchange rate. The calculation input keeps storage scenarios separate from monetary flows because slots describe a stock ceiling, not daily production events.

## SELL and contract context

A read-only 168-hour network ledger observation for 2026-10-21 14:02:22.317 UTC through 2026-10-28 14:02:22.317 UTC returned an exact window with 2,736 records, 16,885 operations, and 23 players. It reports 58,121.28 Vault across 28 current `shop_sell` transactions and two players, but does not attribute these sales to ArcCivs. The complete selected-material view (`complete=true`, `truncated=false`) matched only COBBLESTONE: 218 items and 10.85004456161785 Vault across four transactions. It returned no BREAD or OAK_LOG rows for that window, so this report assigns neither a current sale price nor sale proceeds to the food bridge or abstract wood.

The same window reports 4,822.56 Vault across ten `resource_contracts` transactions and two players. This is a separate network flow, not an Auto2 receipt and not a replacement amount for SELL. The canonical inventory says EconomyShopGUI SELL is current and its planned removal / contract replacement is a future target; the listed active contracts do not turn Auto's abstract wood, stone, or food into current contract income. Current SELL and target contracts remain separate, and this storage change does not disable SELL or add a contract.

## Sources and verification boundary

- `src/main/kotlin/ru/arc/civs/CivsRules.kt:63-68` — legacy 128 + active Warehouse level addition and captured-capacity branch.
- `src/main/kotlin/ru/arc/civs/CivsRules.kt:173-190,652-720` — existing per-cycle yields, interval, and abstract-stock production path.
- `src/main/kotlin/ru/arc/civs/CivsAutonomousStorage.kt:11-45` — observed per-building storage, producer-local room, and 64-unit slot sharing.
- `src/main/kotlin/ru/arc/civs/CivsAutonomousStorage.kt:19-68` — shared depot packing and single-resource room against captured empty slots.
- `src/main/kotlin/ru/arc/civs/CivsSupplyCapture.kt:81-101,138-200` — active-building storage keys, bounded 27-slot chest-half/barrel scan, actual empty-slot/content accounting.
- `src/main/kotlin/ru/arc/civs/CivsStorage.kt:408-420` and `CivsService.kt:2046-2063,2225-2276` — producer-own generation checks, depot-wide unload freshness, and persistence/currentness checks around capture saves.
- `src/main/kotlin/ru/arc/civs/CivsService.kt:2225-2276` — successful capture validation and persistence of per-building storage before the observed snapshot is used.
- `src/main/kotlin/ru/arc/civs/CivsAutonomousLogistics.kt:1-150` — closed stock, pending worker stock, local capacity and courier transfer.
- `src/main/kotlin/ru/arc/civs/CivsResidentFood.kt:9-49` and `CivsStorage.kt:637-656` — existing 1:1 FOOD-to-BREAD receipt and existing batch.
- `docs/storage-idle-and-door-recovery.md:1-6,38-40` — earlier Auto level-1 warehouse snapshot and cap 384; `docs/autonomous-economy-assessment.json:7-15,399` records the distinct completed level-2 profile with cap 640.
- `../../ruscrafting-ops/docs/knowledge/economy-inventory.md:63-65` — current SELL and active resource-contract inventory; the read-only live ledger above is only network-wide context.

The assessment JSON records the runtime snapshot separately from source rules and hypothetical slot sensitivities. The calculator checks the zero-valued direct currency flows; it does not prove the native slot counts or forecast extra production. Target version is `0.1.113`; source integration review, build, delivery, and live confirmation are pending. After activation, compare the successful per-building capture, producer `pendingStock`, warehouse stock and cycle/yield debug without counting the same units twice. Verify SELL or contract redemption only from a separately attributable item transaction.
