# Research and industry

The first release adds a university, crusher and multifunction smeltery. The
existing blacksmith completes the metal chain. It keeps the 16-building limit:
the standard Auto profile has eleven buildings, these three additions and a
blacksmith bring it to fifteen, and a plan board can occupy the last plot.
Existing optional buildings may legitimately leave no room; development must
report that condition without moving or deleting buildings.

## Player loop

Build a university, fund a project from real local supplies, and assign one
researcher. Completed observed work sessions unlock practical production:

1. **Materials**, three sessions: unlock the smeltery and crusher, their first
   two levels, and Auto's separate ore-production selection.
2. **Metallurgy**, five sessions after Materials: ore and charcoal unlock iron
   smelting; Auto may build the existing blacksmith.
3. **Glasswork**, five sessions after Materials: sand and charcoal unlock glass.
4. **Engineering**, eight sessions after both branches: university level two,
   iron and glass unlock level-three industry buildings and the blacksmith's
   bucket/shears recipes. Auto then pursues its level-three town hall, followed only by level-three university, smeltery and crusher. Older buildings and the blacksmith retain their level-two Auto target.

The smeltery combines charcoal, building-stone, iron and glass processing in
one furnace building. There is no separate kiln in this release. Exact recipes,
research prices and availability come from the domain catalogue; UI values are
read from that catalogue rather than maintained in a second price list.
Previously available personal buildings and recipes are not retroactively
locked. Research changes capabilities, never production multipliers.

## Two material contracts

Personal settlements consume actual plain ingredients from their existing
physical or explicit virtual item stores. Inputs, native output slots and the
worker's cycle are committed through the existing inventory journal. Physical
mines still obtain actual ore from world work; research does not issue ore.

Auto keeps its original WOOD/STONE/FOOD stock and seven closed products:
RAW_IRON, CHARCOAL, GRAVEL, SAND, GLASS, IRON_INGOT and IRON_TOOL_KIT. They are
settlement resources, never inventory items or withdrawal entitlements. Ore
selection replaces a quarry's stone cycle instead of adding a second reward.
Charcoal has a wood-paid bootstrap recipe, so no free starting fuel is needed.
The planner manufactures the finite deficit of its current goal, preserving
food, construction and safe worker-reassignment rules.

## Persistence and authority

One persistent project per city records its UUID, stable research ID, exact
accepted cost, required/completed sessions, funding and pause state. One
effective researcher advances it; a completed observed 60-second session and
worker-cycle change commit together. No elapsed-time or offline catch-up credit
is allowed. Restart, pause, worker death and missing workplaces preserve paid
progress. Duplicate or stale completions cannot repeat costs or progress.
Unknown writes stay under the existing recovery authority. Research and recipe
selection must invalidate an incompatible in-flight session.

Every research screen and mutation carries an explicit city UUID. Personal
management uses normal membership checks; public Auto visitors inspect, while
the existing Auto administrator authority controls changes. The interface
shows current project/progress first, then the reason for waiting, materials
and the capability being unlocked. It uses existing native dialogs and fonts.
Routine persistence is not a player-facing blocker or repeated notification.

## Private schematic preparation

`scripts/curate-industries.py` reuses `curate-workshops.py` and its hash-pinned
normalizer. It selects levels one through three in nordic, medievaloak and
caledonia, under these source families:

```
education/university
craftsmanship/metallurgy/smeltery
craftsmanship/masonry/crusher
```

The source `minecolonies/...` IDs become `civs/minecolonies/...`. The recorded
primary anchor is accepted only when its original block is the matching
MineColonies hut heart. The actual converted cell is checked before marking it;
the source offset alone is not proof. The shared pipeline reconstructs exact
v4 cells, aligns upgrade frames around that heart, fills added frame cells with
explicit clearing air, and places the reviewed common approach outside the south
face. Nordic smeltery uses level two's authored gate front for all three levels:
its level-one side approach is enclosed by later upgrades. A ten-step route
with full support and two-block headroom reaches a cell beside the control;
levels two and three retain their original gate, handled by the existing native
gate-opening policy. This changes orientation, without carving any source cells.
Every output must have exactly one control marker and identical family
frames across all three levels. Schematics, source packs and previews stay
private under task artifacts; only the curation script belongs in public Git.

Source/readback checks, offline vanilla rendering, server delivery, world
placement and native-client acceptance are separate evidence. The offline
preview hides barrier skip cells and displays the unique control marker as its
runtime barrel. Flat surrounding ground is a preview fixture. None of these
preview substitutions changes the delivered schematic.

## Primary references and deliberate adaptation

MineColonies' [university](https://minecolonies.com/wiki/buildings/university/)
and [research system](https://minecolonies.com/wiki/systems/research/) motivate
prerequisites, paid projects and explicit building unlocks. Its
[crusher](https://minecolonies.com/wiki/buildings/crusher/) supplies the
cobblestone → gravel → sand chain, while the
[smeltery](https://minecolonies.com/wiki/buildings/smeltery/) supplies ore → ingot.
The [brick yard / stone smeltery](https://minecolonies.com/wiki/buildings/stonesmeltery/)
is the reference for charcoal and building-material firing. ArcCivs deliberately
combines those furnace roles into SMELTERY and uses its own bounded recipes,
research costs, active-session timing and permission model. It does not import
MineColonies' fortune bonuses, offline progress or a new mod runtime.
