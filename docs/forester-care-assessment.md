# Forester sapling care: balance assessment

Baseline is 0.1.80. Proposed 0.1.82 care takes four seconds after a real walk and changes one existing sapling from stage 0 to stage 1. It does not generate a mature tree, saplings, bone meal, WOOD, items, money, player XP or resident skill points. A stage-1 sapling cannot receive the same care again. Native tree growth remains subject to loaded chunks, light, soil, room and protections. Mature trees take priority over care and planting.

The preceding [native forestry assessment](forestry-and-roads-assessment.md) remains the baseline for yields, planting and the one-use paid recovery kit. Care now deliberately accelerates one natural stage, so its statement that there is no growth acceleration is historical. Existing durable world-change and inventory receipts remain the sole payout owner. Exact before-images and completed cycles prevent a repeated care receipt from advancing or rewarding the same plant again.

## Explicit sensitivity scenarios

These are assumptions, not observed production: one level-2 forester, 1/2/4 active hours per day, 6/12/18 complete trees per hour before, and twice those rates after. Skipping one of two growth stages can roughly halve growth waiting in a growth-limited scenario with negligible travel, care and harvest time. This is not a guaranteed speed factor or hard stochastic ceiling. A light/space limitation or a full warehouse can instead give zero improvement.

| Per owner-day | Low | Base | High |
| --- | ---: | ---: | ---: |
| Finished trees, before → after | 6 → 12 | 24 → 48 | 72 → 144 |
| Gross abstract WOOD, before → after | 48 → 96 | 192 → 384 | 576 → 1152 |
| Recurring paid replant debit | 6 → 12 | 24 → 48 | 72 → 144 |
| Recurring net abstract WOOD, before → after | 42 → 84 | 168 → 336 | 504 → 1008 |
| Illustrative physical logs, before → after | 24 → 48 | 192 → 384 | 1152 → 2304 |

Physical log counts assume 4/8/16 assumed logs per tree in the respective scenarios; actual native drops vary. These physical-town examples are separate from Auto's abstract WOOD. At mill levels 1/2/3, the unchanged completed-tree yield is 4/8/12 WOOD and the unchanged paid replant costs one WOOD, leaving 3/7/11. Care has no yield or seed debit. No recurring grant or second completion credit is introduced.

The mill's unchanged construction/upgrade costs are 20/80/180 WOOD and 12/48/108 STONE, totaling 280 WOOD plus 168 STONE for levels 1–3. Care requires no new unlock or purchase. A monetary saving time would mix units and is not defined; combined building progression also depends on independently mined STONE. Capacity, hunger, 128-cell mutation batches and physical input reserves stay unchanged.

For 1/10/16 participating owners, calculated recurring weekly abstract WOOD net is 294/11760/56448 before and 588/23520/112896 after. Starting one fresh grove costs one additional WOOD once per city, giving first-week totals 293/11750/56432 before and 587/23510/112880 after; it is not charged again every day. The previous assessment's daily table included that initial plot, so its 41/167/503 values are one WOOD below the recurring daily figures here. Direct Vault, tokens and player XP remain separately zero. Ordinary leaf drops, apples, sticks and saplings have no new fixed payout. Any EcoJobs or trade interaction requires its own actual player action; care does not emit a player block-break reward.

## Current economy evidence and alternative redemption

Read-only `arc_ops_server` on classic at 2026-10-25T10:06:32.796Z covered the preceding 168 hours: 24 ledger players, 2604 records and 33278 operations. Vault mint was 1226592.6355820035 and burn 855418.4400000003; tokens separately 94 and 12. Unknown Vault caller volume was 983329.17 absolute, with 99.0006% operation attribution. This is a network ledger, not a forester throughput measurement or a normal newcomer income. Compact evidence is retained in `artifacts/arccivs-auto2-check-20261025/care081-economy-audit.json` in the workspace.

Shop sales remain current: historical oak/spruce log amounts have allocated/mixed unit-price evidence, unsuitable as a guaranteed live quote. The current open oak/spruce contracts sampled 123 minor units = 1.23 Vault per log, each with a 10000-Vault envelope. If all illustrative physical logs were accepted at that frozen quote, the alternative gross would be 29.52/236.16/1416.96 Vault before and 59.04/472.32/2833.92 after per owner-day, subject to changing quotes, quotas, funding and actual acceptance. This is not added to a simultaneous shop sale of the same logs or to Auto WOOD. For 1/10/16 owners the uncapped weekly conditional values are 206.64/16531.20/158699.52 before and 413.28/33062.40/317399.04 after; existing shared envelopes reduce accepted totals. No contract price, budget or limit changes here. Planned SELL-off is not presumed active.

The prior adversarial planting bridge remains: a paid plot can become one or four world saplings. With immediate interception and ready stock, the existing three-second planting duration admits at most 20 four-sapling plots/minute before other delays, or 80 saplings until stock runs out. Prior disk SELL 2 Vault/sapling would value that at a nominal 160 Vault/minute; it is not a confirmed loaded quote. Care adds four seconds and creates no saplings, so it does not increase that planting-only ceiling. One recorded recovery kit per paid mill is unchanged. Players cannot both intercept those seedlings and receive the same plot's mature-tree harvest.

## Decision and verification

Accept free one-stage care, matching the farmer's existing free growth-stage care. Keep mature harvest first, no training or payment for tending, unchanged completed-tree yields and paid seeds. This improves an actual wait while retaining native growth and ordinary hauling. No new caps, prices, currencies, configurable bonuses or dependency are introduced.

The [calculator input](forester-care-assessment.json) and [result](forester-care-assessment-result.json) check arithmetic in separate units. Regressions must cover priority across a bounded scan, zero-stock care, exact stage/species/before-image validation, receipt replay, preserved seed markers and no rewards. Native growth must reject collisions with buildings, roads and resident bodies. After any authorized activation, verify actual care commits and later complete-tree receipts separately; source tests or delivered bytes do not establish live growth or native-client animation.
