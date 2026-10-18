# ArcCivs 0.1.19 supplies and automatic jobs

The ordinary **Пополнить** action transfers only missing plain construction items
from the player's 36 storage slots into physical town containers. It mints zero
items. Existing town stock is subtracted first; capacity limits the transfer and
unmoved items remain with their owner. Metadata-bearing stacks are excluded.

The **Выдать всё** action is an explicit administrator testing grant, guarded by
`arccivs.admin` at both UI and service boundaries. Before: no grant button.
After: mint only the current material deficit that fits real storage. Repeating
without changing the stock grants zero additional items. Taking those items away
and clicking again can mint more: this is an administrator capability, not a
normal-player reward or a lifetime issuance limit. Do not describe it as bounded
normal income. Each grant logs actor, city, building and total quantity.

Direct deltas are separately **0 Vault, 0 tokens and 0 XP**. Granted items can
be sold through still-active shop SELL or player markets; that indirect exposure
depends on the exact material basket and current prices and is not a plugin cash
payout. No item-to-currency exchange rate is assumed. The planned SELL-to-contract
transition remains future; no replacement contract income is introduced here.
The reported unfinished builder-hut fixture used by the UI tests contains
896 remaining items across 13 material types. This is a specific remaining bill,
not the full raw schematic or a universal building cost; matching and already
paid cells change the bill.

Automatic assignment fills vacant jobs, builder first, and preserves existing
assignments. Before, a free resident produced zero until manually assigned.
After, that resident can use the existing work cycle. Rates, quantities, slots,
food requirements, population limits and offline behavior do not change. At
level 1, for an active producer with available storage, the existing theoretical
one-worker upper rates remain 240 logs,
180 quarry blocks or 60 bread per active hour; route and work time can only
reduce these figures. One job per worker is mutually exclusive.

For illustrative 1/2/4 active-hour days, one newly assigned level-1 worker can
produce at most 240/480/960 logs, **or** 180/360/720 quarry blocks, **or**
60/120/240 bread. Seven-day equivalents are 1680/3360/6720 logs,
1260/2520/5040 quarry blocks, or 420/840/1680 bread. Weekly totals describe
cumulative production with storage emptied or expanded, not a guaranteed stock
in one barrel. A previously manually
assigned worker has zero rate delta. These are ceilings, not measured income;
no active-hour catch-up is awarded after absence. Saving time may fall for
previously unassigned residents; no construction price or recipe changes.

The single-replica 168-hour ledger read at generatedAt 1792364236160 contains
12,777 records, 67,149 operations and 32 players. Vault has 135 unclassified
operations with 152,587.99 absolute units; tokens have 3 with 9 units. Admin
Vault mint is 67,001,499 across two operations, and shop mint is highly
concentrated (largest player share 99.21%). Neither measures normal ArcCivs
income or time to afford construction. The bounded material basket is not
priced from ranked historical sales. No global repricing is justified.

Verification must cover native-slot capacity, metadata preservation, permission
denial, duplicate clicks, before/after receipt reconciliation, disconnect and
restart recovery; automatic assignments must preserve existing jobs, slot caps,
monotonic delivery tokens and one durable city revision. The approved changes
improve testing and activate already available jobs without changing economy
rates or giving ordinary players the administrator grant.
