# Retained Auto1 field expansion: BEFORE economy assessment

Recorded 2026-11-06T08:45:32.290381+00:00. **BEFORE record for the next iteration; scoped source implementation was separately authorized afterward. No publication or runtime mutation is authorized by this assessment.** This document is separate from the `.224` mine/dispatcher candidate. [Machine assessment](farm-field-expansion-20261106.json) and [evaluator output](../../artifacts/farm-field-expansion-20261106-economy-result.json) cover the bounded arithmetic below, not native acceptance or the whole network balance.

## Target and source evidence

Parent model readback at `2026-11-06T08:32:01.257896Z`, SHA-256 `38643b7f6d9b861182b0fdbb48b59847aaa0285c995d6acf466d816c66a17c05`: retained Auto1 farm `32c439ce-87aa-38ca-80f4-f18a9269ef41`, ACTIVE level 1, one assigned worker, `production: null`, source `civs/v2/nordic/agriculture-horticulture-farmer/c0ad265fc22f522f22f9/level1`, anchor `(12314,73,9683)`, rotation 90. This assessment worker did not recapture that model.

`null` is real model data. `CivsMaterials.kt:255,309` selects the first FARM production option, **WHEAT**. `CivsFarmWorksite.kt:172` then resolves native WHEAT and planting item **WHEAT_SEEDS**. Carrots and potatoes are explicit alternatives, not the current crop. The proposed PHYSICAL path must be confirmed at admission; the older scenery path requires `inventory == null` (`CivsAutonomousWorksites.kt:9`) and is not a substitute for real seed/drops accounting.

The [read-only design proposal](../../artifacts/proposals/farm-expansion-20261106.md) uses nullable retained `farmFieldVersion`, immutable canonical geometry, existing five-second `CivsAutonomousDevelopment.pulse` admission and the existing inventory journal. It does not upgrade level 1, add a farmer, rewrite the original schematic or bulk-place the new field. Persisted version meaning and matched state/native recovery must survive restart and later building upgrades; clearing a flag after partial work is not rollback.

Read-only mode confirmation from the parent: RPC `a564585ae95657c71def84baab2f1e3e0a4c34f9061b4b18046d6d6967faf053` (`velocity exec.run`), observed `2026-11-06T08:52:17.524–651Z`, against pinned model mtime `2026-11-06T08:47:38.352Z` / SHA `f424db4e971f2f441db00f50e0d9c95621d8a111439d1f82a688ae1096fc40eb`: inventory present, **PHYSICAL**, autonomy present and not paused. This confirms the economy path at that frame; it is not a seed grant, profile intent or activation. The parent aggregate is [mode evidence](../../artifacts/lab-224-auto1-physical-mode-native-20261106.json). Admission must still recheck current state. No economic rate, amount or historical BEFORE hash changes.

## Geometry and one-time costs

| Count | Before | Proposed |
|---|---:|---:|
| Crop soil | 80 | 120 |
| Authored water cells | 1 | 15 |
| Farmer / level | 1 / 1 | 1 / 1 |
| Worker WHEAT_SEEDS reserve | 80 | 120 |

The two new flanks contain **54 columns: 48 new soil + 6 new water**. Eight old crop columns become water after ordinary mature harvesting; 72 old crop plots remain. Thus `72+48=120` soil and `1+6+8=15` water. There are 40 net additional crop plots and 14 additional water actions.

- Initial planting of the 48 new plots debits **48 real WHEAT_SEEDS**, one per planting (`CivsFarmWorksite.kt:149`). Existing native/world inventory receipts own the debit and replay protection.
- The eight old channel crops must mature, yield actual `Block.getDrops(actual tool)` into the real bag, and become AIR **without replant or seed subtraction**. Separate later work creates water only after clear-top, bank and permission checks. Do not hard-code eight wheat/seed rewards. Native drops, their possible seed recovery and actual tool wear remain unquantified.
- Worker reserve increases by **40 WHEAT_SEEDS held**, via `CivsLogistics.workerWorkReserve:252`. This is not a grant, escrow transfer or burn; **48+40 is not an 88-seed consumption charge**. The policy can reduce returned worker excess at a fixed bag balance. Existing store desired-stock buffers and trade quote reserves must not be silently rewritten as 120.
- Existing irrigation work changes a world cell to WATER without a WATER_BUCKET item input/output (`CivsFarmWorksite.kt:74–83`). The proposed 14 authored actions do not bound water spread until banks are proven. No free dirt, bank, fence or path items are included.

With the observed flanks' natural soil, the conditional transition has `48 till +48 plant +8 mature harvest +14 water =118` ordinary actions. At 1.5 s each that is **177 s of action time**, or **354 s** at hunger <=6. This excludes waiting for maturity, care, walking, scan/arrival budgets, tools, supplies, city admission and durable commits. It is an action-only lower bound, not a completion promise. Any new earthworks or bank requirement needs separate admission and cost accounting.

The 2026-11-07 irrigation correction adds one ordinary weed-clear action before WATER for each occupied admitted new water column. SHORT_GRASS/FERN clears one block; TALL_GRASS/LARGE_FERN clears both body halves in one receipt, upper first. For the six new flank water cells this adds **0–6 actions**, **0–9 s** of normal action time or **0–18 s** at hunger <=6. The clear-body 118-action lower bound remains unchanged. Weed work has no item inputs or outputs and retains ordinary hoe wear (one or two non-AIR blocks), arrival, duration, skill and journal rules; it creates no seeds or loot. Late weeds above other pending authored channels likewise require ordinary work and never permit cutting an immature crop.

## Recurring production, supply and availability

Existing ordinary `working-field` actions remain **1.5 s** (`CivsWorksiteContext.kt:170`). Accepted care remains **4 s** and advances native crop age by one even without BoneMeal; exposed rain may skip watering. Selected physical fertilizing consumes one real BoneMeal (`CivsFarmWorksite.kt:156–188`). Hunger <=6 doubles action duration (`CivsResidents.kt:23`). Existing tool wear/Unbreaking and actual native drops remain authoritative.

A single worker's ideal action-only ceilings remain 2400 ordinary actions/h or 900 care actions/h, half when hungry. These mutually exclusive ceilings are not mature harvest or item rates. More growth space can improve harvest availability but also adds tilling, planting, care and travel. **80→120 plots does not establish 1.5× output/h.** Current crop ages, mature harvest frequency, tool metadata, native drops and journal/supply delays are not measured by this assessment.

Existing AutoSupply is a nonzero item source. `CivsAutoSupplies.kt:24–25` caps a receipt to eight material kinds and a 64-unit buffer each; `CivsResidentSupplies.kt:391–400` shares a one-second guard across the backend. Those bounds do not change. The following sensitivity is a gross opportunity envelope, not actual grants or an entitlement for this farm; its unlike material units must not be added to wheat, seed or money totals.

| Scenario | Assumed active hours/day | Receipt opportunities/day | Service item-unit envelope/day | Envelope over 7 days |
|---|---:|---:|---:|---:|
| Low | 0.25 | 900 | 460800 | 3225600 |
| Base | 1 | 3600 | 1843200 | 12902400 |
| High | 4 | 14400 | 7372800 | 51609600 |

Extra real seed, tool, care and food demand can increase realized AutoSupply draws within that unchanged shared envelope. Existing seed stocks and native harvest drops may fund planting instead. There is no assumed automatic 48- or 120-seed grant.

More native wheat may become available for existing food production or trade. The unchanged bread recipe uses 3 WHEAT per BREAD (`CivsMaterials.kt:297`); the assessment adds no bakery capacity or bread rewards. Changed seed holding and delivery timing can also change available surplus. Existing emerald trades transfer actual stock and EMERALD with fresh prices/reserves; no treasury seed or refund is proposed. Exact trade availability and realized food output remain observations for later, not calculated rewards.

Direct new mint is separately **0 Vault, 0 premium tokens, 0 physical EMERALD and 0 player XP**. This does not mean zero crop goods, skill opportunities, seed consumption, AutoSupply items or indirect income. Current EconomyShopGUI SELL/AutoSell remains a distinct possible conversion flow; the owner's future SELL-to-contract model is a separate scenario. No item-to-money exchange rate or current revenue is invented.

## Arithmetic result and limits

The evaluator's one-farm finite conversion gives a **48 WHEAT_SEEDS burn** and **14 authored irrigation work cells**. `network_weekly_cap=48/14` prevents counting this one transition seven times; those are assessment bounds, not new runtime limits or daily payouts. The reserve delta 40 is recorded separately and excluded from burn. Direct-currency/player-XP delta is zero; the gross supply envelope is unchanged while actual draws may change. Evaluator PASS establishes arithmetic/unit validity only.

Historical [left](../../artifacts/lab-223-farm-left-preflight-native-20261106.json) and [right](../../artifacts/lab-223-farm-right-preflight-native-20261106.json) geometry preflights each found 27 available, supported natural-soil/clear-body columns and zero reported problem columns. Their explicit limits leave TileState absence, Lands/modify permission, end-bank safety, route/reservation conflicts, actual traversal and expansion admission unproven. They are older snapshots, not acceptance of the proposed field or the fresh model.

Before any later activation, prove exact retained source/frame and PHYSICAL state, route/reservation/city coverage, TileState/Lands/native banks, no conflicting upgrade, and intent publication through the existing city authority. Then observe ordinary mature harvest of all eight channel crops without replant or loss, separate safe water work, real seed debits for 48 new plantings, actual drops/tool wear/storage, restart/journal recovery and unchanged farmer count. No native or runtime claim is made here.

Pre-farm source SHA-256 values for the proposed owners and economic readers are embedded in the JSON. They were captured at HEAD `2e7a581dae938ecf53ff4b8b8e6aa2c95bbff28b` with the frozen `.224` working bytes where applicable; this does not imply HEAD already contains those changes. These documents are **not part of `.224` publication**.
