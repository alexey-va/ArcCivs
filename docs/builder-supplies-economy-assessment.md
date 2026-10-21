# Builder supplies and free water

Normal construction remains an exact transfer and consumption of existing
vanilla items. Its permitted stores are builder huts and builders' real bags;
the destination building, hall and unrelated warehouse are not payment sources.
Moving the supply button to those destinations creates no items. Optional
virtual mode retains only builder-hut-local stock plus real resident bags.
Prepaid historical rows keep their receipts; no old payment is refunded.

Water placement is free at every level, including waterlogged states and filled
water cauldrons. The underlying block/cauldron, lava and powder snow still cost
their existing items. Farmer irrigation repairs also consume no filled bucket
and return no empty bucket. Water remains collectable using a player's own
bucket, as with native renewable water. No bucket item is issued. Stable
historical row keys preserve saved construction indices despite the new price;
old unpaid water requests are excluded from the bill and recalculated rows.

An explicit Creative grant issues only the current deficit fitting the selected
hut or resident inventory. It retains city-management permissions and the
durable receipt. Survival transfers real player items; existing admin issuance
is unchanged. Repeated clicks with unchanged stock issue nothing more. Taking
goods out and granting again can mint more; this is an explicit testing/Creative
capability, not a normal-player reward or a lifetime issuance limit.

The accompanying calculator uses illustrative low/base/high owners of1/10/16.
Construction water placements are1/16/64 per owner-day, irrigation repairs1/4/16.
The modeled filled-bucket sink falls from2/20/80 to0; the empty-bucket return
falls from1/4/16 to0. Water placements themselves are unchanged. Incremental
Creative-without-admin issue sensitivity is64/2048/27648 mixed items per owner-day,
or448/143360/3096576 per network-week. These are assumptions, not observed
usage, guaranteed fit, enforced caps or monetary valuations.

Direct Vault, tokens and player-XP deltas are separately zero. The read-only
168-hour ledger at1792587379667 covers3706 records,37518 operations and30 players;
one boundary-crossing record/two operations were excluded. Typed totals are
tokens62 minted/21 burned and Vault67712749.7876233 minted/2862234.6599999983
burned, with zero classified unknown net in each currency. Mixed legacy totals
are not used. This background audit does not attribute income to ArcCivs or
establish current material redemption prices. Existing SELL, trade and contracts
can monetize Creative items; the owner's future contract-only transition is not
assumed active. There is no price/progression change or measured savings period.

Removing supply stalls, changing route preference and returning couriers can
change realized travel time. Job quantities, growth checkpoints, work durations,
hunger and native-drop limits stay unchanged. Sleep recovery and empty courier
returns award no goods or work credit. Existing Trails widening is reused.

See [input](builder-supplies-economy-assessment.json) and
[calculator result](builder-supplies-economy-assessment-result.json).
Source tests, delivery, activation and native-client acceptance remain separate.
