# Creative supply requests and resident routes, 0.1.44

An explicit current supply request now has the same Creative issuance semantics
as existing profession-tool and construction grants. Both the building and NPC
menu use the shared service operation and durable inventory receipt. Each click
issues only the requested plain material, capped by the current shortage, the
native stack size and the real destination's capacity. A full or satisfied
request issues nothing. Tools remain one per request. Survival still transfers
existing native items; operators in Survival cannot use this Creative shortcut.

This is an explicit Creative testing capability. Removing goods and repeating a
request can issue more, as existing Creative construction grants already can;
there is no lifetime issue limit. Creative already has native item creation.
It is not ordinary-player income. Current SELL, trade and contracts can monetize
native items; no redemption price or exchange rate is invented, and the planned
SELL-to-contract transition is not assumed active.

The low/base/high sensitivity assumes 1/2/4 Creative managers, 0.25/1/2 active
hours and 4/4/6 accepted requests per hour, respectively. At 8/32/64 fitting
torch or ladder items per request, incremental issuance is 8/128/768 items per
manager-day and 56/1,792/21,504 items per network-week. These are separate
illustrative scenarios, not telemetry, guarantees or daily limits. Vault,
tokens and player XP each remain separately 0→0. No purchase price changes, so
normal-player days-to-save is not derived from Creative item creation.

The read-only 168-hour leader ledger generated at 1792669038272 contains 3,511
records, 34,712 operations and 28 players, with exact boundaries and no excluded
crossing records. Typed totals are 63 tokens minted / 21 burned and
67,708,156.95970435 Vault minted / 2,750,268.039999998 burned. Classified
unknown net is zero, but source attribution is unclassified for five token
operations (21 tokens absolute) and 146 Vault operations (191,244.75 Vault
absolute). Those currencies are kept separate. Two administrative Vault credits dominate issuance; the
largest is 99.9978% of administrative issuance. This is network background,
not ArcCivs attribution or a normal-player income estimate.

Correcting the 3×3 shaft route and reserving disjoint resident sleep spaces may
restore useful travel and production. Work durations, native drops, per-action
profession training and sleep rewards do not change. A physical mine repair still
costs one cobblestone or one torch per corresponding placed block. Reconciliation
after a completed cut or at the end of the mine can now perform a previously
missed repair: each such recovered wall or light changes that repair's actual
debit from zero to one item in its own material unit. Its frequency, total extra
material use, restored throughput and indirect sale income remain unmeasured.
Disconnected cave walls are deferred until their real excavation frontier opens;
this does not imply an additional charge for an already committed repair. The
miner's supply pickup receives a profession-neutral label rather than a
construction label.

The [input](creative-request-supplies-assessment.json) and
[calculator result](creative-request-supplies-assessment-result.json) retain
separate units. Source tests, delivery, active versions, protocol observations
and native Minecraft pixels are separate evidence layers.
