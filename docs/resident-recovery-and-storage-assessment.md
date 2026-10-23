# ArcCivs .67 resident recovery and Auto storage assessment

This assessment covers the scoped recovery and storage behavior only. The source references describe the current working tree; they do not certify a release, delivery, activation, or native-client acceptance.

## Scroll issuance and resurrection

`/civs give-resurrection-scroll` is an administrator-only issuance route. It gives exactly one `PAPER` with the `arccivs:resurrection_scroll` persistent tag. Untagged vanilla paper does not match. The assessed sensitivity is one administrator issuing 1 / 10 / 100 scrolls per day. Those are illustrative item counts, not observed usage, a configured cap, or a claim that all scrolls are used.

Each successful corpse resurrection consumes one matching scroll as part of the durable resurrection transaction. Attempts rejected before that transaction is accepted consume none; after acceptance, the one-item debit and corpse-to-worker revision share the durable receipt. The conditional all-used scenario mirrors the 1 / 10 / 100 issuance counts as item consumption; actual successful use is unknown and can be lower. The evaluator records issuance and consumption as two directional item flows. Its combined `item` volume is handling throughput, not net inventory or a monetary balance.

| Sensitivity | Issued per administrator-day | Consumed if all issued scrolls succeed | Issued per administrator-week | Conditional consumption per week |
| --- | ---: | ---: | ---: | ---: |
| Low | 1 | 1 | 7 | 7 |
| Base | 10 | 10 | 70 | 70 |
| High | 100 | 100 | 700 | 700 |

The evaluator's combined item-flow total is 2 / 20 / 200 item movements per day and 14 / 140 / 1,400 per week in the conditional all-used case because it adds the issuance and consumption directions. It does not claim that many items remain in inventory.

The resident retains the same saved UUID, skills and currently remaining corpse items. Previously removed items are not recreated. This recovery path has no ordinary craft, shop, reward, or payout issuance route. Safe corpse placement and the returning resident's idle walk are behavior changes with no item, Vault, token, or player-XP emission.

## Auto barrels and deferred storage work

Auto barrels contain real items, separate from abstract village stock. Ordinary users with `arccivs.use` can inspect a barrel read-only; an administrator with `arccivs.admin` can take or place its contents. This assessment does not increase storage capacity or model item creation from barrel access. Rack/pickup remains pending for a later release and is excluded; no capacity or implementation claim is made for it.

## Balance and evidence limits

Vault, premium tokens, and Minecraft player XP are each explicitly zero before and after. The tagged scroll is tracked only in its own item unit. No item-to-currency value, sale price, shop payout, contract payout, or new currency flow is invented. Current item SELL behavior and the planned SELL-to-contract transition are unchanged.

For the existing 168-hour ledger context, see [the autonomous-economy assessment](autonomous-economy-assessment.md#direct-currency-and-observed-economy). That network-wide observation is not refreshed or attributed to resident recovery here.

Source anchors in the current working tree:

- `src/main/kotlin/ru/arc/civs/CivsCommand.kt:78-90` restricts scroll issuance to an in-game `arccivs.admin` command and gives one item.
- `src/main/kotlin/ru/arc/civs/CivsResurrectionScroll.kt:12-33` tags one PAPER item; vanilla paper is not accepted by the tag check.
- `src/main/kotlin/ru/arc/civs/CivsStorage.kt:421-433` prepares a one-item native-slot debit only for a matching scroll. `CivsService.kt:2075-2118` validates the accepted corpse and location, applies the receipt, and commits the city revision.
- `src/main/kotlin/ru/arc/civs/CivsRules.kt:320-340` removes the matching corpse and rebuilds the worker by copying the saved worker; its UUID, skills, and current items remain in that copy.
- `src/main/kotlin/ru/arc/civs/CivsUi.kt:235-239`, `CivsProtection.kt:179-184,349-361`, and `src/main/resources/locales.yml:18-19,1038-1039` describe and enforce read-only ordinary Auto-barrel inspection and administrator editing.
- `src/main/kotlin/ru/arc/civs/CivsCorpsePlacement.kt` owns safe corpse placement; `CivsCitizens.kt:532-542` admits a committed resurrection at the accepted body site. Neither path is modeled as an economic flow.

The assessment was run through the canonical balance evaluator. The result confirms zero direct Vault, token, and player-XP deltas in every scenario. Scroll issuance is 1 / 10 / 100 items per administrator-day; conditional consumption is the same only if every issued scroll is successfully used. No measured scroll-use rate or storage-capacity change is asserted.
