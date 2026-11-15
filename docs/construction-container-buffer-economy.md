# Temporary construction container custody

The owner requested temporary virtual storage for chest and barrel contents that an accepted construction, upgrade or refit must replace. This applies to permitted buildings generally. The design preserves the current container ownership, world permissions and construction admission rules.

This is custody of existing items. Before the change, a full set of retained and external receivers can reject an upgrade with `storage-full`. After the change, the affected contents can wait in a durable buffer and return to the same building's available containers after completion or cancellation. Insufficient destination space leaves a persisted remainder for another ordinary attempt.

| Unit or flow | Before | After |
| --- | --- | --- |
| Direct Vault mint / burn | 0 / 0 | 0 / 0 |
| Direct premium-token mint / burn | 0 / 0 | 0 / 0 |
| Direct EMERALD mint / burn | 0 / 0 | 0 / 0 |
| Player XP reward | 0 | 0 |
| Construction material price and per-row debit | Existing accepted recipe | Same accepted recipe |
| Buffered items | No overflow custody | Existing exact stacks, less only ordinary receipted consumption |

For every item identity `(material, serialized components)`, native contents plus remaining custody must be conserved by each evacuation and return receipt. A normal cargo pickup additionally transfers the debited quantity into the resident's existing bag. The ordinary arrival, demand, capacity, delivery, builder pickup and construction payment path still owns consumption. Items with custom components must not become plain construction materials. Partial returns and retries must conserve the remaining stack amounts and components.

Hypothetical low/base/high custody volumes of 10 / 100 / 1,000 full 64-item stacks mean 640 / 6,400 / 64,000 existing items temporarily moved, with zero item mint and zero direct currency or XP reward. These are sensitivity examples, not measured stock, income or a capacity grant. Reopening formerly blocked construction can resume the existing progression and downstream production rates; its realised throughput and indirect sale income have not been measured.

The active EconomyShopGUI SELL flow and the owner's planned SELL-to-contract transition remain separate. No item-to-currency exchange rate is assumed. Prices, payouts, loot, production multipliers, resident carrying limits and transfer permissions are unchanged.

Required evidence is separate: exact-stack conservation and historical receipt compatibility; durable write before native removal; restart and uncertain-commit recovery; ordinary pickup and partial return; published artifact; then observed activation and construction progress in the isolated Lab. No operator item issuance, queue deletion, artificial receipt or free construction credit is part of acceptance.
