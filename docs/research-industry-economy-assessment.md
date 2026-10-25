# Proposed research industry economy assessment

**Status:** Design review approved the explicit values below for implementation. Source/package checks, delivery, activation and measured production remain separate. The calculator input and arithmetic output are [JSON](research-industry-economy-assessment.json) and [result](research-industry-economy-assessment-result.json).

## Scope and scenario basis

The proposed package adds University, Smeltery, and Crusher, plus the Auto Blacksmith target: 11 to 15 ordinary Auto buildings, leaving one slot for the Plan Board under the 16-building cap. Infrastructure 96, population 12 per city, and the 48-NPC backend ceiling stay unchanged. Four staffed industry buildings use four residents per city; six high-case owners would allocate 24 NPCs before existing work competes for the same backend cap.

The low/base/high sensitivities use 1/4/6 active city owners, 0.25/1/4 active hours, and 2/8/24 successful batches per selected regular recipe or 4/16/48 per selected Crusher recipe. These imply 8/8/6 regular batches and 16/16/12 Crusher batches per active hour. They are supplied assumptions, not telemetry or a full-utilization projection. Each building has one worker and each recipe row is one selected recipe for that worker; alternative recipes must not be summed for that worker. The 60-second interval is a floor before travel and sleep; the supplied behavior says hunger may double the effective cycle. There is no offline catch-up or skill-speed bonus.

The canonical assessment keeps Vault, tokens, player XP, resident job XP, Auto city stock, and native items separate. It does not translate any item into money. Project funding and construction costs are one-time resource sinks; they are not daily production events and are not multiplied by seven. Calculator `disposable_per_player_day` is zero because net stock after committed downstream consumption is not established; no native withdrawal API is assumed. This does not mean gross item flows are zero.

The release's resident-yield correction restores ordinary walking when residents mutually block a depot approach. It changes neither nominal work intervals nor recipe quantities, fees, stock caps, or rewards. Yield steps never grant arrival or completed work; the normal observed-arrival and commit checks still apply.

## One-time funding and construction costs

### Auto city-stock costs

The proposal prices each target level at the L1 price multiplied by the squared target level, following the existing `CivsRules.cost` convention (`CivsRules.kt:32-56`). This treats an L2 or L3 upgrade as a new target-level charge. Values are internal WOOD / STONE / FOOD units, not physical items or currency.

| Building | L1 | L2 target | L3 target | L1 + L2 + L3 path |
|---|---:|---:|---:|---:|
| University | 48 / 32 / 8 | 192 / 128 / 32 | 432 / 288 / 72 | 672 / 448 / 112 |
| Smeltery | 32 / 24 / 0 | 128 / 96 / 0 | 288 / 216 / 0 | 448 / 336 / 0 |
| Crusher | 24 / 24 / 0 | 96 / 96 / 0 | 216 / 216 / 0 | 336 / 336 / 0 |
| Blacksmith | 32 / 24 / 0 | 128 / 96 / 0 | 288 / 216 / 0 | 448 / 336 / 0 |
| **All four** | **136 / 104 / 8** | **544 / 416 / 32** | **1,224 / 936 / 72** | **1,904 / 1,456 / 112** |

The Auto research funding is separately debited once per city before work and is not restored by rebuilding:

| Project | One-time Auto cost (internal units) | Sessions |
|---|---|---:|
| Materials | 24 WOOD, 16 STONE, 4 FOOD | 3 |
| Metallurgy | 8 WOOD, 2 FOOD, 8 RAW_IRON, 2 CHARCOAL | 5 |
| Glasswork | 8 WOOD, 2 FOOD, 8 SAND, 2 CHARCOAL | 5 |
| Engineering | 16 WOOD, 16 STONE, 4 FOOD, 8 IRON_INGOT, 8 GLASS, 1 IRON_TOOL_KIT | 8 |
| **All projects** | **56 WOOD, 32 STONE, 12 FOOD, 8 RAW_IRON, 4 CHARCOAL, 8 SAND, 8 IRON_INGOT, 8 GLASS, 1 IRON_TOOL_KIT** | **21** |

If one city completes all four Auto building paths and funds all Auto research, the one-time total is **1,960 WOOD, 1,488 STONE, 124 FOOD, 8 RAW_IRON, 4 CHARCOAL, 8 SAND, 8 IRON_INGOT, 8 GLASS, and 1 IRON_TOOL_KIT**. This combines the four building paths above with the four research projects; it is not a recurring weekly cost.

That table is the purchasable maximum, not the automatic plan. The new automatic plan keeps Blacksmith at L1, raises only University/Smeltery/Crusher to L3, and opens Town Hall L3 after Engineering. It does not automatically raise the old raw producers, warehouse, houses, builder hut or Blacksmith to L3. The three new L1–L3 paths plus Blacksmith L1 cost **1,488 WOOD / 1,144 STONE / 112 FOOD**; Town Hall L3 adds **384 / 288 / 72**. Including all four research fees gives **1,928 / 1,464 / 196**, before the separately consumed intermediates, feeding and recruitment.

Town Hall L3 makes the existing population limit of twelve automatically reachable after the previous eight-person L2 era: at most four additional residents, a potential 1.5× headcount rather than a yield bonus. Existing beds, the 48-NPC backend ceiling and the normal one-per-two-active-minute recruitment still apply. Those four admissions consume another **48 FOOD once** under the unchanged twelve-FOOD admission price. Their fully working hunger can require up to approximately **40 FOOD/hour** together under the existing one-hunger/minute and six-hunger/FOOD rule; idle work and the actual active clock reduce that bound. No automatic old-producer L3 yield or warehouse-capacity increase is included.

A minimum intermediate batch accounting for research samples alone is: 24 RAW_IRON quarry cycles; 10 WOOD → 8 CHARCOAL; 32 STONE → 16 GRAVEL → 8 SAND in 24 Crusher batches; two iron batches yielding 16 ingots; one glass batch yielding eight glass; and 3 ingots + 2 WOOD → one toolkit. Research and these batches consume seven charcoal, eleven ingots and the requested samples, leaving one charcoal and five ingots. This adds **12 WOOD and 32 STONE** to direct raw-stock research fees; ore is produced instead of a stone delivery, not alongside it. The director's finite reserve buffers can consume additional resources. These are whole-batch sample requirements, not the total production cost, elapsed-time or sale-income claims.

### Native research costs

Native research is a separate item-funded mode. Do not add these item recipes to Auto funding for the same project unless the implementation explicitly charges both.

| Project | One-time physical item inputs | Sessions |
|---|---|---:|
| Materials | 1 BOOK, 8 PAPER, 8 COBBLESTONE | 3 |
| Metallurgy | 1 BOOK, 8 RAW_IRON, 2 CHARCOAL | 5 |
| Glasswork | 1 BOOK, 8 SAND, 2 CHARCOAL | 5 |
| Engineering | 2 BOOK, 8 IRON_INGOT, 8 GLASS | 8 |
| **All projects** | **5 BOOK, 8 PAPER, 8 COBBLESTONE, 8 RAW_IRON, 4 CHARCOAL, 8 SAND, 8 IRON_INGOT, 8 GLASS** | **21** |

Each session is a real 60 seconds with no offline catch-up. The nominal total is 21 minutes if one researcher completes the projects serially, before travel, sleep, or hunger. At +1 internal resident job-XP per completed session, a single researcher could gain 21 job-XP across these one-time projects, below the existing per-worker cap of 40. This is not player XP or a monetary payout.

### Native construction bill

Curated candidate schematics are now present at [the private industry output](../../artifacts/arccivs-industry-20261025/curated-entry-v2/industry-manifest.json): 27 files, nine families, for University, Smeltery, and Crusher at L1–L3 in nordic, medievaloak, and caledonia. The curation report records sizes, volume, origin, marker, and cell-readback status, but neither report nor manifest contains material palette counts or a native construction bill. Its 31,104 maximum volume is an **upper bound on schematic cells**, including air/clearing and marker cells; it is not a solid-block count, item issuance, or a construction price. Native construction therefore remains priced by the exact captured schematic bill when that bill is available; no universal raw-stock estimate is substituted.

An independent decode using the pinned existing curation reader records the actual non-air/non-barrier/non-marker world cells in [world-body-counts](../../artifacts/arccivs-industry-20261025/curated-entry-v2/artifact-world-body-counts.json). University + Smeltery + Crusher contain respectively 4,202/5,826/1,676 body cells at L1 and 6,068/10,727/3,184 at L3 for Nordic/MedievalOak/Caledonia. Standard control installation adds three barrels to each pack. These are world-cell counts, not guaranteed salvage drops or the exact paid native item bill: paired blocks, state-to-item rules and retained upgrade cells matter. Auto retains the existing privileged abstract-stock-to-world construction bridge; no new ordinary-player right to dismantle Auto or redeem closed products is granted.

## Recurring recipe arithmetic

The calculator reports gross item input/output units for each selected recipe. For regular recipes the per-owner-day batch counts are `[2, 8, 24]`, and for Crusher recipes they are `[4, 16, 48]`. Network-week values equal the per-owner daily quantity multiplied by `[7, 28, 42]` for 1/4/6 participants. Inputs are consumed only on a committed recipe batch; an unavailable input or full output rejects the entire batch without debit or creation.

### Auto mode: closed city stock

These are seven internal stock units with a 64-unit on-hand cap each. They cannot be converted into native items, shop sales, or contract submissions in this proposal. The rows below are alternatives per selected worker and recipe; do not add alternative outputs as if one worker did every recipe. The 8-unit recipes show gross daily batch potential (16/64/192); stock can hold at most 64 at a time, so only committed downstream recipe consumption can free room. There is no native withdrawal API. Without downstream consumption, production stops at the cap.

| Route | Per committed batch | Gross output per owner-day L/B/H | Network-week L/B/H |
|---|---|---:|---:|
| Quarry output | 1 ORE (assessed as RAW_IRON) instead of existing 3 × quarry level STONE | 2 / 8 / 24 RAW_IRON | 14 / 224 / 1,008 RAW_IRON |
| Charcoal | 10 WOOD → 8 CHARCOAL | 16 / 64 / 192 CHARCOAL | 112 / 1,792 / 8,064 CHARCOAL |
| Gravel | 2 STONE → 1 GRAVEL | 4 / 16 / 48 GRAVEL | 28 / 448 / 2,016 GRAVEL |
| Sand | 2 GRAVEL → 1 SAND | 4 / 16 / 48 SAND | 28 / 448 / 2,016 SAND |
| Iron ingot | 8 RAW_IRON + 1 CHARCOAL → 8 IRON_INGOT | 16 / 64 / 192 IRON_INGOT | 112 / 1,792 / 8,064 IRON_INGOT |
| Glass | 8 SAND + 1 CHARCOAL → 8 GLASS | 16 / 64 / 192 GLASS | 112 / 1,792 / 8,064 GLASS |
| Tool kit | 3 IRON_INGOT + 2 WOOD → 1 IRON_TOOL_KIT | 2 / 8 / 24 kits | 14 / 224 / 1,008 kits |

The old quarry comparator is 3 × level STONE per trip (`CivsRules.deliveryAmount`). The proposal replaces it with one ORE per real cycle, never both. The three old levels are alternative baselines, and each is evaluated across all supplied activity cases (2 / 8 / 24 trips per owner-day), rather than assigning one quarry level to each scenario.

| Old quarry baseline | Per trip | Owner-day STONE L/B/H | Network-week STONE L/B/H |
|---|---:|---:|---:|
| L1 | 3 | 6 / 24 / 72 | 42 / 672 / 3,024 |
| L2 | 6 | 12 / 48 / 144 | 84 / 1,344 / 6,048 |
| L3 | 9 | 18 / 72 / 216 | 126 / 2,016 / 9,072 |

New Auto quarry output is 2 / 8 / 24 ORE (assessed as RAW_IRON) per owner-day and 14 / 224 / 1,008 per network-week. Parent confirmed Auto `ORE` maps to `RAW_IRON`, and the toolkit recipe's `IRON` maps to `IRON_INGOT`.

### Native mode: physical item conversion

The Native recipes are a separate mode from Auto stock. The existing forge recipes remain unchanged; only the Engineering-gated bucket and shears recipes below are new. Recipe outputs are backed by their listed inputs, and all listed recipe alternatives use the same selected-worker activity sensitivity.

| Recipe | Input → output per batch | Gross output per owner-day L/B/H | Network-week L/B/H |
|---|---|---:|---:|
| Smeltery: charcoal | 8 OAK_LOG + 6 OAK_PLANKS → 8 CHARCOAL | 16 / 64 / 192 CHARCOAL | 112 / 1,792 / 8,064 CHARCOAL |
| Smeltery: stone | 8 COBBLESTONE + 1 CHARCOAL → 8 STONE | 16 / 64 / 192 STONE | 112 / 1,792 / 8,064 STONE |
| Smeltery: iron (Metallurgy) | 8 RAW_IRON + 1 CHARCOAL → 8 IRON_INGOT | 16 / 64 / 192 IRON_INGOT | 112 / 1,792 / 8,064 IRON_INGOT |
| Smeltery: glass (Glasswork) | 8 SAND + 1 CHARCOAL → 8 GLASS | 16 / 64 / 192 GLASS | 112 / 1,792 / 8,064 GLASS |
| Crusher: gravel | 2 COBBLESTONE → 1 GRAVEL | 4 / 16 / 48 GRAVEL | 28 / 448 / 2,016 GRAVEL |
| Crusher: sand | 2 GRAVEL → 1 SAND | 4 / 16 / 48 SAND | 28 / 448 / 2,016 SAND |
| Blacksmith (Engineering): bucket | 3 IRON_INGOT → 1 BUCKET | 2 / 8 / 24 buckets | 14 / 224 / 1,008 buckets |
| Blacksmith (Engineering): shears | 2 IRON_INGOT → 1 SHEARS | 2 / 8 / 24 shears | 14 / 224 / 1,008 shears |

These are gross completed-batch item flows, not ending balances. In particular, a batch that outputs eight items consumes the corresponding eight inputs; the output count is not free block or money creation. The project and craft receipts must debit and credit atomically at the same city revision so a failure or replay cannot create items.

## Money exposure and current observations

Direct ArcCivs Vault, token, and player-XP flows are **0 before and after**. Resident job-XP remains a separate internal progression unit: +1 per successful session, with the existing 40-per-worker cap. The calculator shows a representative one-worker recipe stream and does not add alternative recipes or turn job-XP into money.

The fresh read-only ledger artifact covers **2026-10-18 14:16:43 UTC to 2026-10-25 14:16:43 UTC**: 2,728 records, 33,058 operations, and 23 players. Coverage is split by currency: Vault has 495 unclassified operations and **20,443,625.26 absolute Vault amount**, with **31.8372% classified-amount coverage**; tokens have 2 unclassified operations and **12 tokens absolute amount**, with **88.3495% classified-amount coverage**. These amounts have different units and are not summed. The whole-window currency totals are Vault mint 10,510,270.88, burn 11,025,938.83, net −515,667.95; tokens mint 91, burn 12, net +79. The observations are incomplete aggregates, not an ordinary-player income rate or the industry's projected delta.

The requested-material shop query returned five of twelve item types, with no truncation: COBBLESTONE, GLASS, GRAVEL, OAK_LOG, and SAND. Recent matched sales were:

| Item | Quantity | Recorded income | Evidence and concentration |
|---|---:|---:|---|
| GLASS | 177 | 706.49 Vault | Exact, 3 transactions, 1 player |
| GRAVEL | 128 | 191.52 Vault | Exact, 1 transaction, 1 player |
| SAND | 456 | 227.58 Vault | Exact, 2 transactions, 1 player |
| COBBLESTONE | 29 | 1.45 Vault | Exact, 1 transaction, 1 player |
| OAK_LOG | 2,686 | 4,942.1688 Vault | Allocated, 2 transactions, 1 player |

These observations show that some Native outputs have a current sale route, but they are concentrated historical results, not current item quotes or a forecast. Missing rows for BOOK, CHARCOAL, IRON_INGOT, OAK_PLANKS, PAPER, RAW_IRON, and STONE mean no observation, not a zero price. The assessment assigns no item sale price.

The canonical operations inventory still treats EconomyShopGUI SELL and AutoSellChests as current; SELL-off is a future target scenario, not current state ([economy inventory](../../ruscrafting-ops/docs/knowledge/economy-inventory.md)). Its dated contract description includes 7.50 Vault base rates and a shared weekly budget; active offers, rank conditions and remaining budgets were not reverified here, so no contract income is forecast. A physical item may be sold through a shop or submitted to an eligible contract, but this model never counts both. Auto closed stock is not saleable through those native-item routes. Future SELL-off sets that shop route to zero; it does not prove contract demand or replacement income.

### Configured shop quotes and conversion checks

[Read-only quotes](../../artifacts/arccivs-industry-20261025/current-shop-quotes.json), captured on Spawn and Survival at 2026-10-25 14:43 UTC, confirm matching major-Vault base prices per item: cobblestone BUY 2 / SELL 0.05; gravel 16 / 1.50; sand 4 / 0.50; glass 13 / 4; stone 2 / 0.36; iron ingot 34 / 6; oak log 6 / 2. Planks have a visible Building BUY 4 and a hidden Crap BUY 3 / SELL 0.56. No matching active CHARCOAL, RAW_IRON, BOOK or PAPER entry was found. Visibility and source hashes are recorded in the artifact. These are configured base quotes: effective runtime prices, ranks and dynamic pricing were not queried; MySQL is configured while the current config states dynamic pricing requires SQLite.

At those conditional base values, buying two cobblestone and selling one gravel loses **2.50 Vault per batch**; buying two gravel and selling sand loses **31.50**. Over two charcoal batches, nineteen bought logs supply sixteen logs plus twelve crafted planks: **114 Vault for sixteen charcoal**, or **7.125 per charcoal**. The first isolated batch needs ten whole logs for 60 Vault and leaves two planks; the amortized eight-charcoal cost is 57. Buying eight sand plus one amortized charcoal costs **39.125** to sell eight glass for **32**, a **7.125 loss**. The charcoal and smelting quantities also conserve the existing vanilla input/fuel transformation; there is no ore-from-money source in the matched catalog.

Using gathered cobblestone has a different opportunity comparison: two cobblestone could sell for 0.10, while the resulting gravel sells for 1.50. The new processing margin is conditionally **1.40 Vault per real Crusher session**, or **5.60 / 22.40 / 67.20 per owner-day** and **39.20 / 627.20 / 2,822.40 per network-week** under the chosen activity scenarios. This is a possible redemption of already-funded physical output, not a direct ArcCivs payout. A one-worker 60-session/hour no-travel ceiling is 84 Vault/hour of that conditional margin; real movement, night, stock and inputs lower it. Glass from the same gathered cobblestone requires three Crusher sessions per sand, plus fuel and smelting, and competes with selling the gravel. Alternative recipe sales and contract submissions are not stacked. Auto products have no such redemption route.

## Assessment and remaining gates

As specified, the industry adds no direct Vault, tokens, or player XP. It transforms internal or physical item stocks using explicit inputs. Auto stock remains private to the city and capped at 64 units per new product. Native items have a possible external redemption path through current SELL. The configured-price checks above describe a bounded conditional processing margin, not observed player income or effective runtime quotes; contract demand and budgets remain excluded. The selected design is accepted as paid, one-worker production progression with four one-time research projects, not a new cash reward or yield multiplier.

The one-time research and Auto build costs are explicit above and are not recurring. The native research costs are separately counted physical inputs. Exact native building bills are not in the curation report; the report's maximum volume is only an upper bound on cells and must not be read as a solid-block bill or item mint. The ORE→RAW_IRON and IRON→IRON_INGOT mappings are confirmed. Current item quotes and contract demand remain unpriced; the historical shop observations above are not current quotes.

The parent approved the separate Auto/native modes, explicit prices and recipes after reviewing this model. Native construction retains the existing exact capture/material-reservation authority rather than substituting a hand-counted bill. After separately authorized activation, verify input/output atomicity, failed/replayed operation behavior, stock caps, one-time funding, research persistence after rebuild, per-worker job-XP caps, effective SELL/contract conditions and the observed ledger delta. None of those new-feature runtime checks were performed here.

## Evidence

- [Balance calculator format](../../ruscrafting-ops/.agents/skills/balance-ruscrafting-economy/references/assessment-format.md)
- [Canonical economy inventory](../../ruscrafting-ops/docs/knowledge/economy-inventory.md)
- [Fresh aggregate observation](../../artifacts/arccivs-industry-20261025/economy-observation.json)
- [Industry curation report](../../artifacts/arccivs-industry-20261025/curated-entry-v2/curation-report.json) and [private manifest](../../artifacts/arccivs-industry-20261025/curated-entry-v2/industry-manifest.json)
- [Existing resource cost and quarry-yield conventions](../src/main/kotlin/ru/arc/civs/CivsRules.kt)
- [Prior item-flow assessment pattern](artisan-buildings-economy-assessment.json)
