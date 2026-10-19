# ArcCivs 0.1.20 construction queues and recovery

The observed builder-hut order on Spawn had 220 small material rows. It completed
13 rows in roughly five minutes, with a full trip and work wait for every row.
At an observed approximately 23 seconds per row, continuing that cadence would
take about 84 minutes for this specific order. That is an estimate from one live
order, not a universal building duration.

The new builder session authorizes at most eight existing rows after two seconds
of work at the building. Each row retains its exact material debit, journal,
placement and durable completion boundary. Existing row indices and already paid
materials remain valid. A 220-row order requires at least 28 full or partial
sessions instead of 220 trips; actual time still includes movement, persistence,
world writes and missing materials. No guaranteed final duration is assumed.

Queues add no parallel builders or free construction. They retain one active
order and begin the next requested SITE after the current order ends. Starting a
queued job captures current terrain and computes its remaining material bill;
an estimate is not a second prepaid purchase. Duplicate queue requests do not
create a second order. Canceling/removing a plan does not refund already placed
blocks or mint the free control barrel.

Removing a building or settlement removes only its registered control barrels
and drops their existing native contents, including item metadata. Nearby chests
and other world blocks remain. Direct changes are separately 0 Vault, 0 tokens,
0 XP and 0 newly minted ordinary items. Lost/free-barrel farming must not be
introduced through the cleanup path. World blocks and dropped entities retain
ordinary native save semantics rather than a new item escrow.

The explicitly requested stuck-NPC teleport can restore previously blocked work
and shorten travel. It must preserve the existing cycle token and work/delivery
deadline checks; it never itself issues materials or completes a delivery twice.
Resource-production rates, quantities, slots, food use and offline behavior are
unchanged. A newly unblocked level-1 producer still has the existing upper rate
of 240 logs, 180 quarry blocks or 60 bread per active hour, mutually exclusive
per worker and subject to storage. Faster construction can bring those existing
rates online earlier; it does not make the first day's output neutral compared
with an hour of waiting for a builder. No global repricing is justified.

The preceding release's 168-hour ledger baseline and active SELL-to-contract
transition assumptions are in [the supply assessment](supplies-economy-assessment.md).
The narrow post-0.1.19 window from 02:28:21 to 02:31:19 MSK had zero currency
records. It does not measure the physical administrator grants (468 items in
that window and another 385 later), future resale, or ordinary-player earnings.
No conversion rate between those items and Vault, tokens or XP is assumed.

Focused checks must cover queue order/restart compatibility, one active job,
cancel/deletion of queued and active plans, unchanged paid row indices, bounded
work credit, navigation failure with one successful continuation, and exact
control-barrel cleanup without metadata loss or duplicate drops on retry.
