# ArcCivs 0.1.17 construction economy

The owner requested free automatic control barrels and construction that accepts
materials during work. The baseline is 0.1.16. A new eligible site or one-time
legacy rebind now supplies one placed barrel; an existing barrel is reused with
its contents. This replaces a player-supplied item. It is not a currency reward.

[The calculation](construction-economy-assessment.json) and its
[checked result](construction-economy-assessment-result.json) keep units separate.
Assumed 1/4/16 placements per owner-day and 1/10/16 participating owners produce
1/4/16 barrels per owner-day and 7/280/1792 per network-week. These are scenarios,
not measured activity or caps. Sixteen concurrent buildings is not a lifetime
grant limit: deleting and founding elsewhere can leave an old barrel and issue
a new one. No repeated tick repair is allowed after a grant receipt completes.

Construction retains the same exact changed-material price vector. New orders
pay small successive runs, while old fully prepaid orders remain prepaid.
Unspent materials stay in storage; already paid work is not refunded. The
builder's 10-second trip interval stays unchanged, and smaller runs can increase
total completion time. A visual construction fence issues no world blocks.

Direct deltas are separately 0 Vault, 0 tokens and 0 XP. Production rates,
recruitment, starter grants and storage capacities are unchanged. Existing SELL
is still modeled active and the planned contract transition remains future.
Barrel resale availability and price are unverified; no conversion is invented.
Source tests, deployment, activation and native client acceptance are separate.
