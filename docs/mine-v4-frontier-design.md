# Mine V4: one access shaft and a built frontier

V4 retains the authored ladder's XZ on every rung. It removes V3's deliberate
three-block lateral transfer, which produced the separate lower ladder visible
in the reported screenshot. Existing V2/V3 step sequences remain immutable;
the new version does not reinterpret their saved ordinal as V4 progress.

## Geometry

Coordinates below are relative to the central opening: `d` is forward from the
ladder and `s` is lateral. The authored ladder is at `d=-3,s=0`; its backing is
at `d=-4,s=0`. That rear access wing has no outgoing primary tunnel.

- The central opening is continuously clear, 3 × 3, from the authored bottom
  down to the lowest tier. A paid 3 × 3 bottom closes it below the lowest feet.
- A two-high entrance landing at `bottomY-2` fits below the authored ceiling.
- Main tiers have feet at `bottomY-6-4n`: three clear blocks and one shared
  floor/ceiling block, with 3/4/5 tiers at building levels 1/2/3.
- Every tier has the same 9 × 9 landing ring, minus the 3 × 3 central opening
  and the fixed ladder/backing columns. Its 70 support cells are connected.
- Three primary tunnels extend from the ring through distance 20, width 3.
  At each side tunnel's distance 12, forward/backward branches run to ±8,
  also width 3. The network has 298 support cells per tier. The uncut pillars
  between the main arm and secondary branches remain nine blocks wide.

The straight, repeated industrial layout is intentional: each new tier is
legible from the same ladder, and branching never competes with that access.
This is procedural native block work, not a pasted schematic or room-only mine.

## Construction frontier

`CivsMinePlan.Step.approach` names a completed predecessor before that step:

1. Place the next backing and rung from the previous rung.
2. Grow each landing by breadth-first adjacent cells, each cleared and floored
   from its completed parent. Place its lights before opening the central slice.
3. Open central rows from bottom to top, from the completed current landing.
   Removing each lower row exposes the next row's underside for native reach
   probes. The shared ceiling is opened last.
4. Complete the shaft, every landing and bottom before `shaftEnd`, the first
   gallery step. Only then start branch work.
5. Grow primary tunnel rows from the preceding row. Grow secondary tunnels only
   after their parent side arm is finished, from its already-built junction.

`builtStanding(local, accessStep)` admits feet and head only after the inspected
prefix has opened them, plus bounded authored entrance access. It is not proof
of block ownership, support, line of sight, or connection to the actual worker.
Those remain native runtime checks. `entranceLanding.height` is 2; other floor
heights are 3. Callers must include the entrance when choosing a floor frontier.

Runtime must keep the observed access prefix separate from the durable next
receipt. A prerequisite may use already-observed matching geometry ahead of the
saved cursor without committing that cursor. Work navigation and temporary
blacklisting belong to the runtime, not this static plan.

## Existing mines

Retain each old version's completed prefix for escape routes, unioned with the
observed V4 prefix. Do not admit the final future V3/V4 mask. The reported V3
cursor 83 permits the actual lower rungs at world Y 59–61, the transfer at 61
and original ladder at 63; it does not permit the proposed disconnected stance
at Y 55. The regression uses the incident's anchor and rotation.

Old offset infrastructure may disappear only through ordinary paid, validated
new-plan operations when the worker has a safe route. A completed cursor does
not prove ownership of matching native stone or player cobblestone. There is
no bulk world cleanup, inventory grant, or migration drop in this plan.

## Evidence and reproduction

`CivsMineV4Test` checks all three styles and levels, continuous ladder alignment,
open central slices, aligned tiers, branch junctions, predecessor ordering and
the real V3 escape coordinates. V2/V3 sequence hashes were captured by invoking
the constructor in the published 0.1.52 JAR, not a duplicate generator.

The test writes actual plans to `build/mine-plan-v4-preview/`, including counts
for the separate economy assessment. Example preview:

```sh
python3 -B scripts/export-mine-preview.py build/mine-plan-v4-preview/medievaloak-l3-v4.json --out /private/tmp/arccivs-mine-v4/medievaloak-l3.atelier.json
node ../ruscrafting-ops/scripts/location-atelier/cli.mjs /private/tmp/arccivs-mine-v4/medievaloak-l3.atelier.json --out /private/tmp/arccivs-mine-v4/medievaloak-l3 --client-jar ../artifacts/.location-atelier-cache/minecraft-1.21.11-ba2df812c2d12e0219c489c4cd9a5e1f0760f5bd.jar
```

The viewer combines the exact export with the hash-verified original building
and synthetic stone, using vanilla block models. Tier, cross-section, ladder,
central opening and eye-level junction presets expose the relevant geometry.
Native collision, excavation hazards, player edits, navigation, migration and
ordinary-client feel still require integration/runtime verification.

Reference: MineColonies commit `d1573f78af389c66d3d673343e86ff3a32868561`,
`EntityAIStructureMiner` shaft-depth gate and work-order completion,
`BuildingMiner` shaft work orders, and `MinerLevel` reserved ladder-side node.
V4 follows the phase-ordering idea without copying upstream code or adding a
MineColonies dependency.
