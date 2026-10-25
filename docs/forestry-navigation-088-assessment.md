# ArcCivs 0.1.88 forestry and navigation repair: economy assessment

**Status:** source-only candidate assessment. It is not evidence of a built or delivered JAR, activation, live throughput, or player acceptance; activation is pending a later restart.

## Scope and unchanged rates

The forester continues to mint abstract WOOD only for a completed replanted real tree: 4/8/12 WOOD at mill levels 1/2/3. A completed seed plot still costs 1 WOOD. Partial work and planting by themselves pay 0 WOOD. The change repairs availability and ownership/navigation behavior; it does not change prices, payouts, caps, production durations, boosts, or nominal yields.

The supplied baseline records zero completed tree outputs in the affected level-1 deadlocked stands. The bounded active-0.1.87 observation window was 2026-10-25 19:20–19:44 UTC (22:20–22:44 MSK); this is not presented as a daily rate. The after values below are hypothetical restored-work sensitivities, not observed production. A healthy isolated stand is unchanged before and after and is not counted as another population in the calculator.

| One affected L1 owner per scenario | Low | Base | High |
| --- | ---: | ---: | ---: |
| Assumed active hours/day | 0.25 | 1 | 4 |
| Affected completed plots/day, before → after | 0 → 6 | 0 → 24 | 0 → 72 |
| Gross abstract WOOD/day, before → after | 0 → 24 | 0 → 96 | 0 → 288 |
| Modeled seed-plot WOOD burn/day, before → after | 0 → 6 | 0 → 24 | 0 → 72 |
| Net abstract WOOD/day, before → after | 0 → 18 | 0 → 72 | 0 → 216 |
| Net abstract WOOD/network-week, before → after | 0 → 126 | 0 → 504 | 0 → 1,512 |

The calculator pairs one recurring seed debit with each modeled completed replacement plot and excludes the initial grove and unfinished work. Unknown seed expenditure for any incomplete pre-change cycles is not estimated. The daily net is internal abstract WOOD, not a player item or cash-equivalent.

## Physical-tree ownership and monetary boundary

Automatic forester stock does not pay physical logs. Physical towns retain their native log drops and sapling debit. The new logs-first ownership check keeps foreign, shared, and out-of-bounds leaves for native decay on touching crowns; isolated owned crowns keep their existing collection behavior. Leaf loot location or timing may change, but no extra item quantity or currency payout is promised. Unavailable/protected or oversized trunk captures still reject safely; unavailable, shared, or escaping foliage stays unmodified. Native mutation still revalidates loaded blocks and protection.

Direct Vault, tokens, and player XP are each 0 before and after. Physical log throughput and native SELL quote are unmeasured, so this assessment assigns no money value to wood or logs. Current EconomyShopGUI SELL remains current; the planned SELL-off and SELL-to-contract transition remains future state. Any recovered throughput or monetization effect is unknown and is not added to the abstract-WOOD totals.

## Decision and remaining evidence

Treat this as a reliability/availability repair with unchanged nominal rewards. Restoring an affected L1 forester from zero observed completed outputs may restore the hypothetical net flows above, but the assumptions do not predict a guaranteed production rate. The 2,048-cell mutation bound and live block/protection revalidation remain in force; removing false-yield replans could change throughput indirectly, which is not measured.

The [calculator input](forestry-navigation-088-assessment.json) and [result](forestry-navigation-088-assessment-result.json) keep abstract WOOD separate from monetary units. Verify completion-only reward, the one-WOOD seed debit, no payout for partial work or planting alone, no duplicate payout on receipt replay, safe ownership on touching/shared/out-of-bounds crowns, and retained collection for isolated owned crowns. Source review and calculations do not prove the JAR, delivery, activation, live production, or native-client behavior. Recheck those only after the separately authorized restart and runtime observation.
