#!/usr/bin/env python3
"""Build a private, immutable ArcCivs v2 catalog from the pinned 14,830 inputs.

Only this recipe and its tests belong in Git. Inputs, schematics, per-cell
receipts, catalogs and renders are private derivatives. No server is contacted.
The output records every selected source, including explicit rejection reasons.
"""
from __future__ import annotations

import argparse
from array import array
from collections import Counter, deque
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys

NORMALIZER_SHA = "dac91d0cad598a3f1b08784da948c18df4409b427922d9d222b300e7579784da"
CONVERTER_SHA = "5bcfbe5ba577fae85927752c06bdf46b3ee7ccae46c63e02c963db8e3a3a35c4"
SPONGE_SHA = "9a16841e3967d6a96a1032bc4b6bcba426cba8c1a6c3c233980af7b9d9491ffc"
ROTATIONS_SHA = "9b98c8f3ff389a6410629578da9a57149bb5bee530e6fe1806504a4d38acd29c"
PLAN_SHA = "f05498403311c20f59047b02f5c9baede67af4a3e6cff7a8eac7894c1d935ac2"
REGISTRY_SHA = "db09c03da472ed39c537458fae186c38838c89466c1b93625475d67fdf37fab2"
NATIVE_FACTS_SHA = "8958e27b69248f53aa3015793d345207e979987fe0401574a362b9a21912050f"
SOURCE_PINS = {
    "minecolonies": {"commit": "4eb1b870b7cb2339b79f231033df5bbef27b2646", "archiveSha256": "d492ba291637ee329cfb07cb4f535741e039ff8f44d6fddf234d251defefe4fc"},
    "stylecolonies": {"commit": "abd31b44d99dc51276f705f2352e0fd5b99820e6", "archiveSha256": "b93832d29b24ed260167a3ccd68f181d74238b94a375cceeeeb6864522747309"},
}
RECIPE_VERSION = 2
FORGE_HEAT = {"minecraft:lava", "minecraft:fire", "minecraft:soul_fire"}
HUT_TYPES = {
    "townhall": "TOWN_HALL", "builder": "BUILDER_HUT", "citizen": "HOUSE",
    "lumberjack": "FORESTER", "miner": "QUARRY", "farmer": "FARM",
    "warehouse": "WAREHOUSE", "sawmill": "SAWMILL", "blacksmith": "BLACKSMITH",
    "stonemason": "STONEMASON", "library": "LIBRARY", "tavern": "TAVERN",
    "baker": "BAKERY", "guardtower": "GUARD_TOWER", "university": "UNIVERSITY",
    "smeltery": "SMELTERY", "crusher": "CRUSHER",
}


def digest(blob):
    return hashlib.sha256(blob).hexdigest()


def canonical(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode()


def pinned(path, expected):
    blob = path.read_bytes()
    if digest(blob) != expected:
        raise ValueError(f"Pinned input changed: {path}")
    return blob


def immutable_write(path, blob):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and path.read_bytes() != blob:
        raise ValueError(f"Refusing to overwrite accepted bytes: {path}")
    path.write_bytes(blob)


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    sys.modules[name] = result
    spec.loader.exec_module(result)
    return result

_catalog_scene = module("civs_catalog_scene", Path(__file__).with_name("catalog_scene.py"))
AIR = _catalog_scene.AIR
SKIP = _catalog_scene.SKIP
HAZARDS = _catalog_scene.HAZARDS
VECTORS = _catalog_scene.VECTORS
Rejected = _catalog_scene.Rejected
WaterEscape = _catalog_scene.WaterEscape
name = _catalog_scene.name
properties = _catalog_scene.properties
state_key = _catalog_scene.state_key
Scene = _catalog_scene.Scene


def source_family(identifier, primary_state):
    """A functional role follows the authored hut, never a vaguely matching name."""
    _, style, path = identifier.split("/", 2)
    suffix = re.fullmatch(r"(.+?)([1-5])", path)
    role = HUT_TYPES.get(name(primary_state).removeprefix("minecolonies:blockhut"))
    if role and suffix:
        return style, suffix[1], role, int(suffix[2])
    return style, path, "DECORATION", 1


def style_key(style):
    return re.sub(r"[^a-z0-9_-]+", "-", style.lower()).strip("-")


def front_pad(scene, content_bounds, control):
    """A modest shared forecourt is outside retained source cells, never a room cut."""
    ground = scene.origin[1]
    # A source barrier means retained terrain, not authored clearing air. Make
    # that terrain contact deterministic and paid before proving any approach.
    # Explicit AIR preserves basements; every physical source cell is untouched.
    for i, pid in enumerate(list(scene.cells)):
        p = scene.position(i)
        if p[1] <= ground and name(scene.palette[pid]) == "minecraft:barrier":
            scene.set(p, "minecraft:dirt", "paid-source-terrain-placeholder")
    # Only padding columns need a floor. Natural source terrain remains unchanged.
    for z in range(scene.size[2]):
        for x in range(scene.size[0]):
            content_column = content_bounds[0][0] <= x <= content_bounds[1][0] and content_bounds[0][2] <= z <= content_bounds[1][2]
            if content_column:
                scene.set((x, 0, z), "minecraft:cobblestone", "paid-bottom-foundation")
            else:
                for y in range(ground):
                    if name(scene.at((x, y, z))) in SKIP:
                        scene.set((x, y, z), "minecraft:cobblestone", "paid-forecourt-foundation")
                if name(scene.at((x, ground, z))) in SKIP:
                    scene.set((x, ground, z), "minecraft:gravel", "shared-ground-forecourt")
    scene.set(control, "minecraft:structure_void", "shared-ground-control")
    seen, parent = scene.reachable()
    doors = [scene.position(i) for i, pid in enumerate(scene.cells)
             if name(scene.palette[pid]).endswith("_door") and "half=lower" in scene.palette[pid]]
    cells = scene.reached_cells(seen)
    accessible_doors = [p for p in doors if p in cells]
    return seen, parent, accessible_doors


def storage(scene, racks, normalizer):
    """Replace only source-proven racks. Decorative bookshelves are never guessed."""
    receipts = []
    # Stacks remain real containers. A closed lid uses a front-opening barrel;
    # removing upper racks would silently discard source-authored storage.
    for p, source in sorted(racks.items(), key=lambda item: (item[0][1], item[0][2], item[0][0])):
        x, y, z = p
        below = (x, y - 1, z)
        if not scene.support(below):
            if name(scene.at(below)) not in SKIP or y < 1:
                raise Rejected(f"container-support-needs-review:{p}:{scene.at(below)}")
            supports = []
            for by in range(y - 1, max(-1, y - 5), -1):
                q = (x, by, z)
                if scene.support(q):
                    break
                if name(scene.at(q)) not in SKIP:
                    raise Rejected(f"container-support-needs-review:{p}")
                supports.append(q)
            else:
                raise Rejected(f"container-support-span-needs-review:{p}")
            for q in reversed(supports):
                scene.set(q, "minecraft:oak_planks", "source-container-grounded-support")
        kind = "chest" if name(scene.at((x, y + 1, z))) in AIR else "barrel"
        facing = source["facing"]
        after = f"minecraft:chest[facing={facing},type=single,waterlogged=false]" if kind == "chest" else f"minecraft:barrel[facing={facing},open=false]"
        scene.set(p, after, "source-authored-storage")
        receipts.append({"position": list(p), "kind": kind, **source})
    seen, _ = scene.reachable()
    for receipt in receipts:
        p = tuple(receipt["position"])
        faces = scene.container_faces(p, seen)
        if not faces:
            raise Rejected(f"storage-route-from-entrance-needs-review:{list(p)}")
        facing = receipt["facing"] if receipt["facing"] in faces else next((side for side in VECTORS if side in faces),
            "up" if receipt["kind"] == "barrel" else receipt["facing"])
        receipt.update(facing=facing, reachableFaces=sorted(faces))
        scene.set(p, f"minecraft:chest[facing={facing},type=single,waterlogged=false]" if receipt["kind"] == "chest"
                  else f"minecraft:barrel[facing={facing},open=false]", "source-storage-facing-access")
    # Adjacent clear-lid chests retain their source positions/capacity, with
    # reciprocal vanilla halves and a common verified aisle.
    chests = {tuple(r["position"]): r for r in receipts if r["kind"] == "chest"}
    unpaired = set(chests)
    for p in sorted(chests):
        if p not in unpaired:
            continue
        for facing, (dx, dz) in VECTORS.items():
            q = (p[0] - dz, p[1], p[2] + dx)
            if q not in unpaired or facing not in chests[p]["reachableFaces"] or facing not in chests[q]["reachableFaces"]:
                continue
            for at, half in ((p, "left"), (q, "right")):
                scene.set(at, f"minecraft:chest[facing={facing},type={half},waterlogged=false]", "source-storage-reciprocal-pair")
                chests[at].update(facing=facing, chestHalf=half, pairedWith=list(q if at == p else p))
            unpaired.remove(p); unpaired.remove(q)
            break
    return receipts


def noncombustible_cube(state):
    block = name(state).removeprefix("minecraft:")
    return block in {"stone", "cobblestone", "mossy_cobblestone", "dirt", "coarse_dirt", "rooted_dirt", "grass_block",
                     "granite", "diorite", "andesite", "deepslate", "cobbled_deepslate", "tuff", "calcite", "basalt",
                     "smooth_basalt", "polished_basalt", "sandstone", "smooth_sandstone", "cut_sandstone",
                     "red_sandstone", "smooth_red_sandstone", "cut_red_sandstone", "obsidian", "crying_obsidian",
                     "netherrack", "end_stone", "blackstone", "polished_blackstone", "clay", "bricks", "glass",
                     "tinted_glass", "terracotta", "magma_block", "iron_block", "copper_block", "gold_block",
                     "diamond_block", "emerald_block", "netherite_block", "lapis_block", "redstone_block", "quartz_block"} or \
        block.endswith(("_bricks", "_tiles", "_concrete", "_terracotta", "_stained_glass", "_ore"))


def heat_check(scene):
    """Authored hearths stay inside protected construction; no new lava is added."""
    lava = {scene.position(i) for i, pid in enumerate(scene.cells) if name(scene.palette[pid]) == "minecraft:lava"}
    fire = {scene.position(i) for i, pid in enumerate(scene.cells) if name(scene.palette[pid]) in {"minecraft:fire", "minecraft:soul_fire"}}
    for x, y, z in lava:
        for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1), (0, -1, 0)):
            p = (x + dx, y + dy, z + dz)
            if p not in lava and not noncombustible_cube(scene.at(p)):
                raise Rejected(f"lava-hearth-containment-needs-review:{[x, y, z]}:{list(p)}")
    for x, y, z in fire:
        below = scene.at((x, y - 1, z))
        if not noncombustible_cube(below) and name(below) not in {"minecraft:soul_sand", "minecraft:soul_soil"}:
            raise Rejected(f"fire-hearth-support-needs-review:{[x, y, z]}")
    return {"lavaCells": len(lava), "fireCells": len(fire), "noncombustibleHearth": True,
            "requiresSettlementIgniteBurnAndSpreadProtection": bool(lava or fire)}


def fluid_check(scene):
    """Close only low spills through the new exterior padding, never a room."""
    exterior_banks = set()
    for _ in range(65):
        try:
            result = fluid_closure(scene)
            result["exteriorBankCells"] = len(exterior_banks)
            return result
        except WaterEscape as failure:
            p = failure.inside
            if p is None or not scene.inside(p) or not (p[0] in (0, scene.size[0] - 1) or p[2] in (0, scene.size[2] - 1)) or \
                    not scene.origin[1] < p[1] <= scene.origin[1] + 2 or \
                    p[2] >= scene.size[2] - 2 and abs(p[0] - scene.origin[0]) <= 2:
                raise Rejected(str(failure)) from failure
            cells = {(p[0], y, p[2]) for y in range(scene.origin[1] + 1, p[1] + 1)}
            if len(exterior_banks | cells) > 64 or any(name(scene.at(q)) not in AIR | {"minecraft:cobblestone"} for q in cells):
                raise Rejected(str(failure)) from failure
            for q in sorted(cells):
                scene.set(q, "minecraft:cobblestone", "paid-low-exterior-water-bank")
            exterior_banks.update(cells)
    raise Rejected("water-bank-repair-over-budget")


def fluid_closure(scene):
    """Conservative finite flow closure using pinned native eligibility/face facts.

    This is an over-approximation, not a world simulation: every downhill route
    and every horizontal direction is considered (native slope preference can
    only remove possibilities). Existing non-source water above water follows
    FlowingFluid.spread's water-hole guard. Potential source conversion is kept.
    """
    heat = heat_check(scene)
    waters = {scene.position(i): scene.palette[pid] for i, pid in enumerate(scene.cells)
              if name(scene.palette[pid]) == "minecraft:water" or properties(scene.palette[pid]).get("waterlogged") == "true"}
    if waters and not scene.native:
        raise Rejected("water-native-facts-required")
    # Invisible terrain placeholders adjacent to authored below-ground water
    # need a paid physical bank. A missing under-water floor gets one solid cell.
    repairs = set()
    for x, y, z in waters:
        for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1), (0, -1, 0)):
            p = (x + dx, y + dy, z + dz)
            if scene.inside(p) and p[1] < scene.origin[1] and (name(scene.at(p)) == "minecraft:barrier" or
                    dy == -1 and name(scene.at(p)) in AIR):
                repairs.add(p)
    if len(repairs) > 256:
        raise Rejected("water-bank-repair-over-budget")
    for p in sorted(repairs):
        scene.set(p, "minecraft:cobblestone", "paid-source-water-bank")
    directions = [("EAST", (1, 0, 0), "WEST"), ("WEST", (-1, 0, 0), "EAST"),
                  ("SOUTH", (0, 0, 1), "NORTH"), ("NORTH", (0, 0, -1), "SOUTH")]

    def fact(p):
        state = scene.at(p)
        if name(state) == "minecraft:barrier" or name(state) == "minecraft:structure_void" and scene.decoration:
            state = "minecraft:air"  # These technical source states are not placed.
        if name(state) == "minecraft:structure_void":
            return {"canHoldAnyFluid": False, "blockedFluidFaces": ["DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST"]}
        return scene.facts(state) or {"canHoldAnyFluid": True, "blockedFluidFaces": []}

    def can_pass(p, q, direction, opposite):
        a, b = fact(p), fact(q)
        return direction not in a["blockedFluidFaces"] and opposite not in b["blockedFluidFaces"] and b["canHoldAnyFluid"]

    amounts = {p: 8 if properties(state).get("waterlogged") == "true" or int(properties(state).get("level", "0")) >= 8
               else 8 - int(properties(state).get("level", "0")) for p, state in waters.items()}
    sources = {p for p, state in waters.items() if name(state) != "minecraft:water" or properties(state).get("level", "0") == "0"}
    queue = deque(amounts)

    def offer(p, amount, source=False, from_point=None):
        if amount <= 0:
            return
        if not scene.inside(p):
            raise WaterEscape(p, from_point)
        if amount > amounts.get(p, 0) or source and p not in sources:
            amounts[p] = max(amount, amounts.get(p, 0))
            if source: sources.add(p)
            queue.append(p)

    visits = 0
    while queue:
        p = queue.popleft(); visits += 1
        if visits > len(scene.cells) * 20:
            raise Rejected("water-proof-over-budget")
        x, y, z = p; below = (x, y - 1, z)
        down = can_pass(p, below, "DOWN", "UP")
        if down:
            offer(below, 8, from_point=p)
        # Potential source conversion is deliberately conservative about support.
        neighbors = [(x + dx, y, z + dz) for _, (dx, _, dz), _ in directions]
        if p not in sources and (not down or below in sources) and sum(q in sources for q in neighbors) >= 2:
            offer(p, 8, source=True)
        if p in sources or not down:
            for direction, (dx, dy, dz), opposite in directions:
                q = (x + dx, y + dy, z + dz)
                if can_pass(p, q, direction, opposite):
                    offer(q, amounts[p] - 1, from_point=p)
        if p in sources:
            for q in neighbors:
                if q in amounts and q not in sources:
                    qb = (q[0], q[1] - 1, q[2])
                    around = [(q[0] + dx, q[1], q[2] + dz) for _, (dx, _, dz), _ in directions]
                    if (not can_pass(q, qb, "DOWN", "UP") or qb in sources) and sum(n in sources for n in around) >= 2:
                        offer(q, 8, source=True)
    scene.possible_water = set(amounts)
    return {"waterCells": len(waters), "possibleFlowCells": len(amounts), "paidBankCells": len(repairs),
            "flowRemainsInsideFrame": True, "nativeSimulation": False, "nativeFactsSha256": NATIVE_FACTS_SHA, "heat": heat}


def surface_count(scene):
    solids = {scene.position(i) for i, pid in enumerate(scene.cells) if name(scene.palette[pid]) not in SKIP}
    sides = ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1))
    return sum(any((x + dx, y + dy, z + dz) not in solids for dx, dy, dz in sides) for x, y, z in solids)


def dining_seats(scene):
    """Recognize paired source chairs facing a table, not arbitrary roof stairs."""
    reachable, _ = scene.reachable()
    candidates = {}
    for i, pid in enumerate(scene.cells):
        state = scene.palette[pid]; props = properties(state); p = scene.position(i)
        if name(state).endswith("_stairs") and props.get("half") == "bottom" and props.get("shape") == "straight" and \
                p[1] <= scene.origin[1] + 8 and scene.support((p[0], p[1] - 1, p[2])) and \
                scene.empty((p[0], p[1] + 1, p[2]), doors=True) and scene.empty((p[0], p[1] + 2, p[2]), doors=True):
            approaches = [(p[0] + dx, p[1], p[2] + dz) for dx, dz in VECTORS.values()
                          if (p[0] + dx, p[1], p[2] + dz) in reachable]
            if approaches:
                candidates[p] = (props["facing"], approaches[0])
    proofs = []
    for p, (back, approach) in sorted(candidates.items(), key=lambda item: (item[0][1], item[0][2], item[0][0])):
        dx, dz = VECTORS[back]; dx, dz = -dx, -dz
        opposite = tuple(VECTORS)[(tuple(VECTORS).index(back) + 2) % 4]
        for distance in (2, 3, 4):
            partner = (p[0] + dx * distance, p[1], p[2] + dz * distance)
            if partner not in candidates or candidates[partner][0] != opposite:
                continue
            between = [(p[0] + dx * step, p[1], p[2] + dz * step) for step in range(1, distance)]
            table = [q for q in between if (name(scene.at(q)).endswith("_slab") and properties(scene.at(q)).get("type") == "top") or
                     (name(scene.at(q)).endswith("_stairs") and properties(scene.at(q)).get("half") == "top") or
                     name(scene.at((q[0], q[1] + 1, q[2]))).endswith(("_carpet", "_pressure_plate"))]
            if table:
                proofs.append({"seat": list(p), "approach": list(approach), "table": list(table[0]),
                               "sourceChairState": scene.at(p), "oppositeChair": list(partner)})
                break
        if len(proofs) == 6:
            break
    return proofs


def reopen_authored_mine_ladder(scene, bottom):
    """Repair only a short natural cap above the same source ladder and backing."""
    state = scene.at(bottom)
    facing = properties(state).get("facing")
    if name(state) != "minecraft:ladder" or facing not in VECTORS:
        return False
    x, _, z = bottom; top = bottom[1]
    while top + 1 < scene.size[1] and scene.at((x, top + 1, z)) == state:
        top += 1
    dx, dz = VECTORS[facing]
    previous = array("H", scene.cells); changes = len(scene.changes); soil = 0
    authored_backing = {tuple(c["position"]) for c in scene.changes}
    natural_cap = {"minecraft:dirt", "minecraft:coarse_dirt", "minecraft:rooted_dirt", "minecraft:grass_block",
                   "minecraft:dirt_path", "minecraft:podzol", "minecraft:mycelium", "minecraft:mud"}
    for y in range(top + 1, min(top + 5, scene.size[1] - 1)):
        p = (x, y, z); back = (x - dx, y, z - dz)
        before = name(scene.at(p)); fact = scene.facts(scene.at(back))
        if before not in AIR | natural_cap or back in authored_backing or not fact or not fact["fullSolid"]:
            break
        soil += int(before in natural_cap)
        if soil > 1:
            break
        scene.set(p, state, "authored-mine-ladder-through-short-natural-cap")
        seen, _ = scene.reachable()
        if bottom in scene.reached_cells(seen):
            return True
    scene.cells = previous; del scene.changes[changes:]
    return False


def worksite(scene, kind, level, generator):
    bounds = {"min": scene.relative((0, 0, 0)), "max": scene.relative([n - 1 for n in scene.size])}
    site = {"type": kind, "level": level, "schematicBounds": bounds, "entrance": [0, 1, 0]}
    if kind == "FORESTER":
        site["forester"] = {"workEntry": [0, 1, 0], "searchBounds": {
            "min": [bounds["min"][0] - 12, -4, bounds["min"][2] - 12],
            "max": [bounds["max"][0] + 12, 24, bounds["max"][2] + 12]}}
    elif kind == "FARM":
        # The current owner already prepares a 9x9 field beyond a hut's maximum
        # footprint. Keep that bounded contract for new families. Authored crops
        # inside the preserved building are scenery, not an invented output.
        cx, cz = bounds["max"][0] + 7, (bounds["min"][2] + bounds["max"][2]) // 2
        site["farm"] = {"soilCells": [[x, 0, z] for z in range(cz - 4, cz + 5)
            for x in range(cx - 4, cx + 5) if (x, z) != (cx, cz)],
            "irrigationCells": [[cx, 0, cz]], "irrigationExisting": False,
            "workEntry": [cx - 5, 1, cz], "gateCells": []}
    elif kind == "QUARRY":
        ladders = [(scene.position(i), state) for i, pid in enumerate(scene.cells)
                   if name(state := scene.palette[pid]) == "minecraft:ladder"]
        if not ladders:
            raise Rejected("mine-authored-ladder-needs-review")
        lowest = min(p[1] for p, _ in ladders)
        candidates = [(p, state) for p, state in ladders if p[1] == lowest]
        reachable, _ = scene.reachable()
        reached = scene.reached_cells(reachable)
        if len(candidates) == 1 and candidates[0][0] not in reached and reopen_authored_mine_ladder(scene, candidates[0][0]):
            reachable, _ = scene.reachable(); reached = scene.reached_cells(reachable)
        candidates = [(p, state) for p, state in candidates if p in reached]
        if len(candidates) != 1 or lowest > 12:
            raise Rejected("mine-bottom-access-needs-review")
        bottom, state = candidates[0]
        facing = re.search(r"facing=(north|south|east|west)", state)[1]
        # A higher level's basement can make the common frame deeper than L1.
        # Extend only through that empty padding and our paid foundation, never
        # through an authored floor or another room. Supply exact ladder backing.
        dx, dz = VECTORS[facing]
        for y in range(lowest - 1, -1, -1):
            p = (bottom[0], y, bottom[2])
            if name(scene.at(p)) not in SKIP and y != 0:
                raise Rejected(f"mine-extension-authored-obstacle:{list(p)}")
            back = (p[0] - dx, y, p[2] - dz)
            if not scene.support(back):
                if name(scene.at(back)) not in SKIP:
                    raise Rejected(f"mine-extension-backing-needs-review:{list(back)}")
                scene.set(back, "minecraft:cobblestone", "authored-mine-ladder-padding-backing")
            scene.set(p, state, "authored-mine-ladder-through-padding")
        bottom = (bottom[0], 0, bottom[2])
        local = tuple(scene.relative(bottom)); dx, dz = VECTORS[facing]
        generator.MINE["catalog"] = (local, facing, (local[0] + dx * 3, local[2] + dz * 3))
        site["mine"] = generator.mine("catalog", level)
    return site


def contact_ground(size, palette, cells, primary, placement, registry):
    """Prove an exterior contact plane before using the hut's default Y guess."""
    if placement["groundPolicy"] != "source-default-anchor-minus-one" or placement.get("groundTagPositions"):
        return None
    sx, _, sz = size
    scene = Scene(size, palette, cells, primary, registry)
    for candidate in sorted(placement.get("boundaryFloorCandidates", []), key=lambda row: row["y"]):
        y = candidate["y"]
        if not 0 <= y < primary[1]:
            continue  # An upper roof is not a substitute for the hut's ground.
        plane, boundary = [], []
        for z in range(sz):
            for x in range(sx):
                p = (x, y, z); state = scene.at(p)
                if any(name(scene.at((x, y + dy, z))) in SKIP - AIR for dy in (1, 2)):
                    continue
                if name(state) != "minecraft:barrier" and not scene.support(p):
                    continue
                if not scene.body_clear((x, y + 1, z)):
                    continue
                plane.append(p)
                if x in (0, sx - 1) or z in (0, sz - 1):
                    boundary.append({"position": list(p), "state": state})
        if len(boundary) >= max(3, (sx + sz - 2) // 2) and len(plane) >= max(6, sx * sz // 12):
            return {"y": y, "surfaceColumns": len(plane), "boundaryColumns": len(boundary),
                    "boundaryEvidence": boundary, "previousGroundY": placement["sourceGroundY"],
                    "policy": "native-exposed-source-contact-plane"}
    return None


def load_source(library, row, plan, normalizer, registry, furniture=None):
    relative = Path(row["sourceRelativePath"])
    if relative.is_absolute() or ".." in relative.parts:
        raise ValueError("Unsafe source identity")
    raw = pinned(library / "sources" / row["repository"] / relative, row["sha256"])
    root, size, raw_palette, raw_cells, primary = normalizer.lib.decode(raw)
    converted = pinned(library / "converted-v2" / (row["id"] + ".schem"), plan["inputSchematicSha256"])
    converted_size, palette, cells = normalizer.read_converted(converted)
    if converted_size != size:
        raise ValueError("Converted dimensions changed")
    furniture_changes = []
    furniture_records = []
    if furniture is not None:
        adapter, recipe = furniture
        palette, cells, furniture_changes = adapter.apply(size, palette, cells, row["id"],
            plan["inputSchematicSha256"], recipe, registry["_native"])
        furniture_row = recipe.get((row["id"], plan["inputSchematicSha256"]))
        if furniture_row and furniture_row["status"] != "blocked":
            furniture_records = furniture_row.get("furniture", [])
    used = {palette[p] for p in cells}
    if not any(name(state) not in SKIP for state in used):
        raise Rejected("source-has-no-physical-geometry")
    if len(cells) > 50_000:
        raise Rejected("source-over-volume-budget")
    if any(name(state) in HAZARDS - FORGE_HEAT for state in used):
        raise Rejected("hazardous-source-block")
    heart = normalizer.preserve_primary_marker(size, palette, cells, root, primary)
    try:
        placement = normalizer.choose_placement(size, palette, cells, root, primary, registry)
    except ValueError as failure:
        if str(failure).startswith("Conflicting source groundlevel heights"):
            raise Rejected("source-ground-tags-needs-review") from failure
        raise
    if proof := contact_ground(size, palette, cells, primary, placement, registry):
        placement = normalizer.choose_placement(size, palette, cells, root, primary, registry, ground_override=proof["y"])
        placement.update(groundPolicy=proof["policy"], groundEvidence=proof)
    if not -1 <= placement["sourceGroundY"] < size[1]:
        raise Rejected("ground-plane-needs-review")
    racks = {}
    for i, pid in enumerate(raw_cells):
        block, props = normalizer.lib.parts(raw_palette[pid])
        converted_block = name(palette[cells[i]])
        if (block == "minecolonies:blockminecoloniesrack" and props.get("variant") != "blockrackair") or converted_block in {"minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel"}:
            p = (i % size[0], i // (size[0] * size[2]), i // size[0] % size[2])
            facing = props.get("facing", "south")
            racks[p] = {"sourcePosition": list(p), "sourceState": raw_palette[pid],
                        "sourceConvertedState": palette[cells[i]], "facing": facing if facing in VECTORS else "south"}
    return {"size": size, "palette": palette, "cells": cells, "primary": primary, "placement": placement,
            "heart": heart, "racks": racks, "row": row, "plan": plan,
            "furnitureChanges": furniture_changes, "furnitureRecords": furniture_records}


def curate_family(inputs, normalizer, rotations, registry, out, recipe_hash, generator):
    reference = max(inputs, key=lambda r: r["runtimeLevel"])
    # Ground alignment avoids burying L1 when the L3 hut moves to an upper floor.
    pivots = [[r["primary"][0], r["placement"]["sourceGroundY"], r["primary"][2]] for r in inputs]
    minimum = [min(-p[a] for p in pivots) for a in range(3)]
    maximum = [max(r["size"][a] - 1 - p[a] for r, p in zip(inputs, pivots)) for a in range(3)]
    base_size = [maximum[a] - minimum[a] + 1 for a in range(3)]
    if base_size[0] * base_size[1] * base_size[2] > 50_000:
        raise Rejected("shared-frame-over-volume-budget")
    reference_pivot = pivots[inputs.index(reference)]
    placement = dict(reference["placement"])
    placement["sourceApproach"] = [placement["sourceApproach"][a] - reference_pivot[a] - minimum[a] for a in range(3)]
    q = placement["quarterTurnsClockwise"]
    revision = digest(canonical({"recipe": recipe_hash, "inputs": [[r["row"]["id"], r["row"]["sha256"]] for r in inputs]}))[:20]
    results, payloads = [], []
    for source, pivot in zip(inputs, pivots):
        size, palette, cells = source["size"], list(source["palette"]), source["cells"]
        shift = [-pivot[a] - minimum[a] for a in range(3)]
        if "minecraft:air" not in palette:
            palette.append("minecraft:air")
        padded = array("H", [palette.index("minecraft:air")]) * (base_size[0] * base_size[1] * base_size[2])
        for i, pid in enumerate(cells):
            x, y, z = i % size[0], i // (size[0] * size[2]), i // size[0] % size[2]
            padded[((y + shift[1]) * base_size[2] + z + shift[2]) * base_size[0] + x + shift[0]] = pid
        turned_size, turned_origin, palette, turned = normalizer.normalize(base_size, palette, padded, placement, rotations)
        # One block round the source and two at the front supply an honest small forecourt.
        target_size = [turned_size[0] + 2, max(turned_size[1] + 1, turned_origin[1] + 4), turned_size[2] + 3]
        origin = [turned_origin[0] + 1, turned_origin[1] + 1, target_size[2]]
        if not 1 <= origin[0] < target_size[0] - 1 or not 0 <= origin[1] < target_size[1] - 2:
            raise Rejected("shared-entrance-needs-review")
        target = array("H", [palette.index("minecraft:air")]) * (target_size[0] * target_size[1] * target_size[2])
        for i, pid in enumerate(turned):
            x, y, z = i % turned_size[0], i // (turned_size[0] * turned_size[2]), i // turned_size[0] % turned_size[2]
            target[((y + 1) * target_size[2] + z + 1) * target_size[0] + x + 1] = pid
        scene = Scene(target_size, palette, target, origin, registry)
        scene.decoration = source["runtimeType"] == "DECORATION"
        for i, pid in enumerate(list(scene.cells)):
            if name(scene.palette[pid]) == "minecraft:structure_void":
                scene.set(scene.position(i), "minecraft:air", "retired-source-technical-hut")
        control = (origin[0] + (1 if origin[0] + 1 < target_size[0] else -1), origin[1] + 1, target_size[2] - 1)
        bounds = ([1, 1, 1], [turned_size[0], turned_size[1], turned_size[2]])
        seen, parent, doors = front_pad(scene, bounds, control)
        ground_doors = [scene.position(i) for i, pid in enumerate(scene.cells) if name(scene.palette[pid]).endswith("_door") and
                        "half=lower" in scene.palette[pid] and scene.position(i)[1] <= scene.origin[1] + 2]
        if source["runtimeType"] != "DECORATION" and ground_doors and not any(p in scene.reached_cells(seen) for p in ground_doors):
            raise Rejected("shared-front-to-door-needs-review")
        racks = {}
        for p, proof in source["racks"].items():
            transformed = normalizer.rotate_position([p[a] + shift[a] for a in range(3)], base_size, q)
            transformed = [n + 1 for n in transformed]
            facing = tuple(VECTORS)[(tuple(VECTORS).index(proof["facing"]) + q) % 4]
            racks[tuple(transformed)] = {**proof, "facing": facing}
        stored = storage(scene, racks, normalizer)
        seen, parent = scene.reachable()
        if source["runtimeType"] == "WAREHOUSE" and not any(r["kind"] == "chest" for r in stored):
            raise Rejected("warehouse-needs-usable-chests")
        fluids = fluid_check(scene)
        seen, parent = scene.reachable()
        if source["runtimeType"] != "DECORATION" and ground_doors and not any(p in scene.reached_cells(seen) for p in ground_doors):
            raise Rejected("fluid-safe-front-to-door-needs-review")
        for rack in stored:
            if not scene.container_faces(tuple(rack["position"]), seen):
                raise Rejected(f"fluid-safe-storage-route-needs-review:{rack['position']}")
        before_worksite = len(scene.changes)
        site = worksite(scene, source["runtimeType"], source["runtimeLevel"], generator)
        if len(scene.changes) != before_worksite:
            fluids = fluid_check(scene)
            seen, _ = scene.reachable()
            for rack in stored:
                if not scene.container_faces(tuple(rack["position"]), seen):
                    raise Rejected(f"fluid-safe-storage-route-needs-review:{rack['position']}")
            if source["runtimeType"] == "QUARRY":
                bottom = tuple(site["mine"]["ladderBottom"][a] + scene.origin[a] for a in range(3))
                if bottom not in scene.reached_cells(seen):
                    raise Rejected("mine-final-access-needs-review")
        dining = dining_seats(scene) if source["runtimeType"] == "TAVERN" else []
        if dining:
            site["tavern"] = {"dining": [scene.relative(row["seat"]) for row in dining]}
        if source.get("furnitureRecords"):
            furniture = []
            for record in source["furnitureRecords"]:
                def placed(point):
                    turned = normalizer.rotate_position([point[a] + shift[a] for a in range(3)], base_size, q)
                    return scene.relative([v + 1 for v in turned])
                def current(point):
                    local = placed(point)
                    return scene.at([local[a] + scene.origin[a] for a in range(3)])
                value = {**record, "point": placed(record["point"]), "sourceId": source["row"]["id"],
                    "sourceSha256": source["plan"]["inputSchematicSha256"],
                    "currentVanillaBlockState": current(record["point"]),
                    "sourceVanillaBlockState": record["currentVanillaBlockState"]}
                if record.get("facing") in VECTORS:
                    value["facing"] = tuple(VECTORS)[(tuple(VECTORS).index(record["facing"]) + q) % 4]
                value["vanillaBlocks"] = [{**block, "point": placed(block["point"]), "state": current(block["point"])}
                    for block in record.get("vanillaBlocks", [])]
                furniture.append(value)
            site.setdefault("tavern", {})["furniture"] = furniture
        surface = surface_count(scene)
        if not 0 < surface <= 6_000 or not 0 < len(scene.cells) <= 50_000:
            raise Rejected("preview-budget-exceeded")
        style, family, kind, source_level = source_family(source["row"]["id"], source["heart"]["sourceState"])
        slug = re.sub(r"[^a-z0-9_-]+", "-", family.lower()).strip("-")[:100]
        style_slug = style_key(style)
        identifier = f"civs/v2/{style_slug}/{slug}/{revision}/level{source['runtimeLevel']}"
        blob = normalizer.lib.encode(scene.size, scene.origin, scene.palette, scene.cells, "ArcCivs v2 " + source["row"]["id"])
        normalizer.lib.verify_output(blob, scene.size, scene.origin, scene.palette, scene.cells)
        rs, rp, rc = normalizer.read_converted(blob)
        if rs != scene.size or any(rp[a] != scene.palette[b] for a, b in zip(rc, scene.cells)):
            raise ValueError("Full-cell readback differs")
        entry = {"sourceId": source["row"]["id"], "sourceSha256": source["row"]["sha256"],
                 "convertedSha256": source["plan"]["inputSchematicSha256"], "repository": source["row"]["repository"],
                 "style": style_slug, "sourceStyle": style, "family": family, "type": source["runtimeType"], "level": source["runtimeLevel"],
                 "sourceLevel": source_level, "maxSupportedLevel": reference["runtimeLevel"], "referenceLevel": reference["runtimeLevel"],
                 "revision": revision, "label": family.split("/")[-1], "verdict": "READY", "reasons": [],
                 "schematicId": identifier, "sha256": digest(blob), "size": scene.size, "origin": scene.origin,
                 "control": list(control), "volume": len(scene.cells), "surface": surface,
                 "storage": {"chests": sum(r["kind"] == "chest" for r in stored), "barrels": sum(r["kind"] == "barrel" for r in stored)},
                 "worksite": site, "dining": [row["seat"] for row in dining], "checks": ["source-hashes", "shared-max-level-frame", "ground-origin", "front-door-route", "one-control", "storage-provenance", "fluid-boundary", "full-cell-readback"]}
        site.update(schematicId=identifier, schematicSha256=entry["sha256"], family=family)
        receipt = {"entry": entry, "sourcePrimary": source["primary"], "sourceGroundY": source["placement"]["sourceGroundY"],
                   "sourceGroundPolicy": source["placement"]["groundPolicy"], "sourceGroundEvidence": source["placement"].get("groundEvidence"),
                   "sourceFront": placement["sourceFront"], "sourceQuarterTurns": q, "sourceFrameTranslation": shift,
                   "changes": scene.changes, "storage": stored, "fluids": fluids, "reachableDoors": [list(p) for p in doors], "diningSeats": dining,
                   "nativeRuntimeAcceptance": False}
        if source.get("furnitureChanges"):
            receipt["sourceFurnitureChanges"] = source["furnitureChanges"]
        scene_doc = None
        if source.get("exportScene"):
            scene_doc = {"version": 1, "size": scene.size, "materials": [{"block": state} for state in scene.palette],
                         "blocks": [[*scene.position(i), pid] for i, pid in enumerate(scene.cells) if name(scene.palette[pid]) not in SKIP]}
        payloads.append((identifier, blob, receipt, scene_doc, f"{style_slug}-{slug}-level{source['runtimeLevel']}"))
        results.append(entry)
    if reference["runtimeType"] == "QUARRY":
        if len({tuple(e["worksite"]["mine"]["ladderBottom"]) + (e["worksite"]["mine"]["ladderFacing"],) for e in results}) != 1:
            raise Rejected("mine-family-shaft-drift-needs-review")
    upgrades = {str(e["level"]): e["schematicId"] for e in results}
    for entry in results:
        entry["worksite"]["upgradeSchematics"] = upgrades
        entry["worksite"]["maxSupportedLevel"] = reference["runtimeLevel"]
    # No earlier level leaks into the published tree if a later one is rejected.
    for identifier, blob, receipt, scene_doc, label in payloads:
        immutable_write(out / (identifier + ".schem"), blob)
        immutable_write(out / "receipts" / (identifier.removeprefix("civs/v2/") + ".json"), canonical(receipt))
        if scene_doc is not None:
            immutable_write(out / "scenes" / f"{label}.blocks.json", canonical(scene_doc))
    return results


def code_inputs(script_path=Path(__file__)):
    return {
        "scriptSha256": digest(script_path.read_bytes()),
        "sceneGeometrySha256": digest(script_path.with_name("catalog_scene.py").read_bytes()),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--atelier", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--style", action="append", help="Exact source style; repeat for an independent batch")
    parser.add_argument("--styles-file", type=Path, help="JSON list of exact source style directories")
    parser.add_argument("--family", help="Exact source family for a representative slice")
    parser.add_argument("--scenes", action="store_true")
    parser.add_argument("--previous-catalog", type=Path, help="Retain prior accepted v2 family metadata for existing buildings")
    parser.add_argument("--native-facts", type=Path, required=True, help="Pinned offline Purpur 1.21.11 collision/fluid facts")
    parser.add_argument("--tavern-furniture", type=Path, help="Private exact-source reviewed furniture recipe")
    args = parser.parse_args()
    if args.styles_file:
        values = json.loads(args.styles_file.read_text())
        if not isinstance(values, list) or not all(isinstance(v, str) for v in values):
            raise ValueError("Style batch must be a JSON string list")
        args.style = (args.style or []) + values
    for file, sha in (("normalize-blueprint-library.py", NORMALIZER_SHA), ("convert-blueprint-library.py", CONVERTER_SHA), ("sponge.py", SPONGE_SHA)):
        pinned(args.atelier / file, sha)
    sys.path.insert(0, str(args.atelier))
    normalizer = module("civs_catalog_normalizer", args.atelier / "normalize-blueprint-library.py")
    generator = module("civs_catalog_worksites", Path(__file__).with_name("generate-worksites.py"))
    rotations = json.loads(pinned(args.library / "native-validation/rotation-lookup/rotation-map.json", ROTATIONS_SHA))
    plans = {r["id"]: r for r in map(json.loads, pinned(args.library / "normalized-main-v4/placement-plan.jsonl", PLAN_SHA).splitlines())}
    registry_blob = pinned(args.library / "registry-preflight-1.21.11.json", REGISTRY_SHA)
    registry = json.loads(registry_blob)
    native_blob = pinned(args.native_facts, NATIVE_FACTS_SHA)
    native = json.loads(native_blob)
    if native["provenance"]["targetMinecraft"] != "1.21.11" or len(native["states"]) != 9312:
        raise ValueError("Native geometry input differs")
    registry["_native"] = {state_key(state): facts for state, facts in native["states"].items()}
    inventory_blob = (args.library / "inventory/files.jsonl").read_bytes()
    inventory = list(map(json.loads, inventory_blob.splitlines()))
    if len(inventory) != 14830 or len({r["id"] for r in inventory}) != 14830 or set(plans) != {r["id"] for r in inventory}:
        raise ValueError("Expected exact 14,830-source inventory")
    selected, groups, entries = [], {}, []
    for row in inventory:
        plan = plans[row["id"]]
        style, family, kind, level = source_family(row["id"], plan["primaryHeart"]["sourceState"])
        if args.style and style not in args.style or args.family and family != args.family:
            continue
        selected.append(row)
        if kind != "DECORATION" and level > 3:
            pinned(args.library / "sources" / row["repository"] / row["sourceRelativePath"], row["sha256"])
            pinned(args.library / "converted-v2" / (row["id"] + ".schem"), plan["inputSchematicSha256"])
            entries.append({"sourceId": row["id"], "sourceSha256": row["sha256"], "convertedSha256": plan["inputSchematicSha256"],
                "repository": row["repository"], "style": style_key(style), "sourceStyle": style, "family": family, "type": kind, "sourceLevel": level,
                "level": None, "verdict": "METADATA_ONLY", "reasons": ["source-level-above-runtime-maximum-3"]})
        else:
            groups.setdefault((style, family, kind), []).append((row, plan, level))
    recipe_inputs = {**code_inputs(),
        "worksiteGeneratorSha256": digest(Path(__file__).with_name("generate-worksites.py").read_bytes()),
        "normalizerSha256": NORMALIZER_SHA, "converterSha256": CONVERTER_SHA, "spongeSha256": SPONGE_SHA,
        "registrySha256": REGISTRY_SHA, "rotationsSha256": ROTATIONS_SHA, "nativeFactsSha256": NATIVE_FACTS_SHA}
    furniture = None
    if args.tavern_furniture:
        adapter_path = Path(__file__).with_name("prepare-tavern-furniture.py")
        adapter = module("civs_tavern_furniture", adapter_path)
        furniture = adapter, adapter.load_recipe(args.tavern_furniture)
        recipe_inputs.update(furnitureRecipeSha256=digest(args.tavern_furniture.read_bytes()),
            furnitureAdapterSha256=digest(adapter_path.read_bytes()))
    recipe_hash = digest(canonical(recipe_inputs))
    for (style, family, kind), rows in sorted(groups.items()):
        try:
            source = []
            for row, plan, level in sorted(rows, key=lambda r: r[2]):
                value = load_source(args.library, row, plan, normalizer, registry, furniture)
                value.update(runtimeType=kind, runtimeLevel=level, exportScene=args.scenes)
                source.append(value)
            if kind != "DECORATION" and {r[2] for r in rows} != set(range(1, max(r[2] for r in rows) + 1)):
                raise Rejected("non-contiguous-source-levels")
            results = curate_family(source, normalizer, rotations, registry, args.out, recipe_hash, generator)
            entries.extend(results)
            print(f"READY {style}/{family}: {len(results)}", flush=True)
        except Rejected as failure:
            for row, plan, level in rows:
                entries.append({"sourceId": row["id"], "sourceSha256": row["sha256"], "convertedSha256": plan["inputSchematicSha256"],
                    "repository": row["repository"], "style": style_key(style), "sourceStyle": style, "family": family, "type": kind, "level": level,
                    "sourceLevel": level, "verdict": "REJECTED", "reasons": [str(failure).split(":", 1)[0]],
                    "diagnostic": str(failure)[:2000]})
            print(f"REJECTED {style}/{family}: {failure}", flush=True)
    if len(entries) != len(selected) or len({e["sourceId"] for e in entries}) != len(selected):
        raise ValueError("Catalog completeness differs from selected sources")
    ids = [entry["schematicId"] for entry in entries if entry["verdict"] == "READY"]
    if len(ids) != len(set(ids)):
        raise ValueError("Duplicate curated output identity")
    counts = Counter(e["verdict"] for e in entries)
    document = {"schemaVersion": 2, "recipeVersion": RECIPE_VERSION, "recipeSha256": recipe_hash,
                "sourceInventorySha256": digest(inventory_blob), "placementPlanSha256": PLAN_SHA,
                "recipeInputs": recipe_inputs,
                "sourceRepositories": SOURCE_PINS,
                "registrySha256": digest(registry_blob), "nativeRotationsSha256": ROTATIONS_SHA,
                "totalSources": len(entries), "completeInventory": len(entries) == 14830,
                "styles": sorted({e["style"] for e in entries}), "counts": dict(counts),
                "privateAssetsOnly": True, "nativeRuntimeAcceptance": False,
                "entries": sorted(entries, key=lambda e: e["sourceId"])}
    if args.previous_catalog:
        previous = json.loads(args.previous_catalog.read_text())
        if previous["schemaVersion"] != 2:
            raise ValueError("Previous catalog version differs")
        current_ids = {e["schematicId"] for e in entries if e["verdict"] == "READY"}
        retained = {e["schematicId"]: e for e in previous.get("retainedEntries", []) + previous["entries"]
                    if e["verdict"] == "READY" and e["schematicId"] not in current_ids}
        document["retainedEntries"] = sorted(retained.values(), key=lambda e: e["schematicId"])
    immutable_write(args.out / "civs/v2/catalog.json", canonical(document))
    print(json.dumps({"out": str(args.out), "sources": len(entries), **counts}, ensure_ascii=False))


if __name__ == "__main__":
    main()
