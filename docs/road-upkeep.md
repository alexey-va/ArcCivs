# Builder upkeep of accepted autonomous roads

The regular builder now has a small maintenance job after accepted construction
and its queue. It inspects retained approaches and completed ROAD centerlines,
walks to a real work position, and replaces an eligible floor cell with the
existing cobblestone road finish. Dry bottom stairs follow the saved grade.
Existing plan files, building origins, paid construction receipts and route
heights remain unchanged. This is new work, not replay of an old construction.

Only supported natural/approved road surfaces qualify. Foreign construction,
containers, fluids, protected cells and unknown/deep support holes are skipped.
Building exclusions use the existing complete reserved footprint through L3,
including farm fields and irrigation, rather than testing only non-air walls
or the currently built level. The same exclusion is checked again at native
application and recovery. Approaches stop outside that reserved territory;
upkeep never places road flooring inside a building or its upgrade space.
The repair does not move the quarry, flatten its terrace, create new routes,
broaden paths, or close unknown excavations. An unambiguous stair direction is
required; a full cube is retained where an intersection cannot safely use one
stair. Healthy cells do not create work or consume supplies.

Each changed floor cell costs **one internal STONE**. Short/tall grass and
ordinary/large ferns immediately above it are removed through exact AIR changes
in the same receipt, without drops or another charge; paired vegetation is
checked completely. Flowers and other cover are preserved by skipping that
floor. No later physics update may silently clear an unjournalled plant.
Main construction wins, and the
ordinary next autonomous building goal keeps its stone reserve. There is no
WOOD/FOOD charge, currency, player XP, resident training, native item drop or
refund. Actual inventory/world receipts persist the exact stock debit, original
worker and changed block before applying the native operation. Repeated or
recovered application uses the same receipt and cannot grant a second cycle or
debit a second unit. The scan cursor is transient; committed repair recovery
does not depend on it.

## Resource assessment

The checked [input](road-upkeep-economy-assessment.json) and
[result](road-upkeep-economy-assessment-result.json) keep currencies, city stock
and real world blocks separate. The low/base/high assumptions are 1/4/6 active
cities, 0.25/1/4 active hours/day and 16/64/256 changed cells per city-day. They
produce internal STONE sinks of 16/64/256 per city-day and 112/1,792/10,752 per
network-week. One real cobble or stair floor block replaces each changed cell;
it is world output from the existing administrative stock-to-world bridge.

The settlement radius itself does not protect events. Publicly accessible
approaches outside building footprints may still be mined; this task does not
silently add protection. The read-only Spawn disk quote at 18:14:32 UTC on
25 October 2026 was 0.05 Vault/COBBLESTONE in Blocks.yml. Its SHA256 is
`66f548fcc63969a32443e4f9a5e4ab97438d44ac25f623467997a0126946c4e4`.
This is not an in-memory price or observed sale. No stair resale value is
invented. In the alternative adversarial case where every repaired flat cell is
mined and sold at that quote, the sensitivities are 0.80/3.20/12.80 Vault per
city-day and 5.60/89.60/537.60 per network-week. These cannot be added to retained
road blocks from the same cells.

A three-second single-cell action gives a loose ceiling of 1,200 cells/hour,
or 60 nominal Vault/hour under the flat-cobble interception assumption, before
walking, sleep, cooldown, bounded native work and finite surplus stock. Existing
one-minute quarry deliveries can replace at most 180/360/540 STONE per quarry
worker-hour at L1/L2/L3 before those constraints: at most 9/18/27 nominal
Vault/hour under the same interception assumption. Ore cycles replace stone
cycles and are not additive. Arbitrary administrative stock grants are not
ordinary sustainable income.

Direct Vault, tokens, player XP and resident job XP remain separately zero.
There is no road-speed stack; the existing Trails surface rule remains the
only multiplier. Fresh Spawn disk reads at 18:29:39 UTC show COARSE_DIRT 1.2,
DIRT_PATH 1.3 and COBBLESTONE alternatives 1.25/1.28. Replacing grass with
registered cobble has a loose all-travel factor up to 1.28; against coarse dirt
the maximum ratio is 1.066667, and against dirt path 0.984615. These are material
and arithmetic bounds, not measured speed or production. Stationary work and
sleep remain unchanged. Suppressing NPC wear within building reserves does not
introduce another multiplier. Recovered availability is not a measured production increase.
Current SELL and future SELL-off/contracts remain distinct scenarios. This
finite paid upkeep sink is approved without changing prices, caps or payouts.

## Evidence boundary

Source/package tests, CI, on-disk delivery, runtime activation, actual repair
receipts and native-client appearance are separate. The owner's current
instruction permits delivery and explicitly defers restarts; a delivered JAR
alone cannot prove that Auto2 has repaired a road.
