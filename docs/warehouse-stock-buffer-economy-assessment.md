# Warehouse capacity and carpentry buffer assessment

The buffer is a new use of otherwise idle carpenters, not a native recipe or speed change.
A single city warehouse has a style-specific target:32 planks,16 sticks,12 ladders,
12 slabs,8 stairs,6 fences,2 gates,3 doors,2 trapdoors; level2 adds2 chests and2 barrels.
At maximum this is97 items across11 kinds. Actual stock at warehouses, workshops,
workers and durable shipments counts once. Real requests precede optional stock.
Missing external ingredients, tier locks, or full destination prevent new buffer work.
Completed output remains physical workshop stock and the existing courier transfers it.

Baseline with no demand:0 completed idle batches. At the unchanged60-second batch
interval, one carpenter can perform at most60 native batches/hour. Illustrative
low/base/high active0.25/1/4 hours/day permit15/60/240 batches, reduced by the finite
buffer, actual inputs, food, night, walking and storage. The plank-only envelope
is60/240/960 items/operator-day, consuming15/60/240 matching logs; this is not
simultaneous output of all catalog recipes. With1/5/10 assumed operators that is
420/8400/67200 plank items/week before finite-buffer stops, versus0 idle output.
Those operators and activity values are assumptions, not measured sessions.

Direct changes separately remain0 Vault,0 tokens,0 player XP. Normal resident skill
is unchanged. Current item resale remains possible and unpriced here; current SELL
is not silently replaced by the planned SELL-to-contract scenario. No free
construction materials are used by this buffer, even when a city enables the
separate explicit construction-testing issuance.

Private warehouse rack conversion changes decorative rack placeholders into empty
physical containers. No stocked items are inserted. Existing buildings use explicit
console maintenance on the exact old/new private schematics; changed or occupied
shelves are preserved. New buildings pay the resulting native chest bill. Exact
per-style added slots (levels1/2/3) are Nordic486/1512/3078,
Medievaloak324/378/486 and Caledonia351/405/405. Each retained chest adds27;
the original control barrel adds its existing27 separately. Medievaloak level1
therefore grows from27 to351 physical slots. Vertical rack cells above lids
become air, while six enclosed Nordic corner cells are omitted. All339 authored
rack cells were checked:275 chests and64 air, no non-rack changes.
No world-wide blanket replacement is permitted. More storage can restore previously blocked
production/transport, whose realized rate has not been measured.

Fresh ledger background (typed read-only EconomyV2, survival filter,168h):
2026-10-16T14:58:40.873000+00:00–2026-10-23T14:58:40.874000+00:00, 2569records/30779operations/23players. No boundary/future exclusions.
tokens: minted62, burned0.
vault: minted688018.5783100035, burned1522320.98.
One unclassified origin/operation covers5000 Vault absolute amount. These aggregate totals are not ArcCivs income or buffer mint.

Calculation: [input](warehouse-stock-buffer-economy-assessment.json), [result](warehouse-stock-buffer-economy-assessment-result.json). Native clients, artifact delivery and observed production are separate evidence.
