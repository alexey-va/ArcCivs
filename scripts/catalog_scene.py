"""Native block-state normalization and geometry for the private catalog."""

from array import array

from collections import deque

import math

AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}

SKIP = AIR | {"minecraft:barrier", "minecraft:structure_void"}

HAZARDS = {"minecraft:tnt", "minecraft:command_block", "minecraft:chain_command_block",
           "minecraft:repeating_command_block", "minecraft:bedrock", "minecraft:spawner",
           "minecraft:trial_spawner", "minecraft:vault", "minecraft:nether_portal",
           "minecraft:end_portal", "minecraft:end_portal_frame", "minecraft:lava",
           "minecraft:fire", "minecraft:soul_fire"}

VECTORS = {"south": (0, 1), "west": (-1, 0), "north": (0, -1), "east": (1, 0)}

class Rejected(ValueError):
    pass

class WaterEscape(Rejected):
    def __init__(self, outside, inside):
        super().__init__(f"water-containment-needs-review:{list(outside)}")
        self.outside, self.inside = outside, inside

def name(state):
    return state.split("[", 1)[0]

def properties(state):
    return dict(part.split("=", 1) for part in state.partition("[")[2].rstrip("]").split(",") if "=" in part)

def state_key(state):
    props = properties(state)
    return name(state) + ("[" + ",".join(f"{k}={v}" for k, v in sorted(props.items())) + "]" if props else "")

class Scene:
    def __init__(self, size, palette, cells, origin, registry):
        self.size, self.palette, self.cells = list(size), list(palette), array("H", cells)
        self.origin, self.registry, self.changes = list(origin), registry, []
        self.native = registry.get("_native", {})
        self.fact_cache = {}
        self.decoration = False
        self.possible_water = set()

    def inside(self, p):
        return all(0 <= p[a] < self.size[a] for a in range(3))

    def index(self, p):
        x, y, z = p
        return (y * self.size[2] + z) * self.size[0] + x

    def position(self, i):
        sx, _, sz = self.size
        return i % sx, i // (sx * sz), i // sx % sz

    def at(self, p):
        return self.palette[self.cells[self.index(p)]] if self.inside(p) else "outside"

    def set(self, p, state, reason):
        if not self.inside(p):
            raise Rejected("repair-outside-shared-frame")
        before = self.at(p)
        if before == state:
            return
        if state not in self.palette:
            self.palette.append(state)
        self.cells[self.index(p)] = self.palette.index(state)
        self.changes.append({"position": list(p), "before": before, "after": state, "reason": reason})

    def facts(self, state):
        if state not in self.fact_cache:
            self.fact_cache[state] = self.native.get(state_key(state))
        return self.fact_cache[state]

    def boxes(self, state, doors=False):
        block = name(state)
        if block in SKIP or (doors and (block.endswith("_door") and not block.endswith("_trapdoor") or block.endswith("_fence_gate"))):
            return []
        fact = self.facts(state)
        if fact is not None:
            return fact["boxes"]
        return [[0, 0, 0, 1, 1, 1]] if self.registry["blocks"].get(block, {}).get("boundingBox") != "empty" else []

    def body_clear(self, p):
        x, y, z = p
        body = [x + .2, y + .00001, z + .2, x + .8, y + 1.8, z + .8]
        for bx in range(math.floor(body[0]), math.floor(body[3]) + 1):
            for bz in range(math.floor(body[2]), math.floor(body[5]) + 1):
                for by in range(math.floor(y) - 1, math.ceil(y + 1.8)):
                    state = self.at((bx, by, bz)); block = name(state)
                    boxes = self.boxes(state, doors=True)
                    if block == "outside":
                        boxes = [[0, 0, 0, 1, 1, 1]] if by <= self.origin[1] else []
                    elif (bx, by, bz) in self.possible_water or block in HAZARDS | {"minecraft:water", "minecraft:powder_snow", "minecraft:sweet_berry_bush"}:
                        boxes = [[0, 0, 0, 1, 1, 1]]
                    elif block == "minecraft:structure_void" and not self.decoration:
                        boxes = [[0, 0, 0, 1, 1, 1]]  # Physical control barrel.
                    for box in boxes:
                        offset = (bx, by, bz)
                        if all(body[a] < box[a + 3] + offset[a] - .00001 and body[a + 3] > box[a] + offset[a] + .00001 for a in range(3)):
                            return False
        return True

    def foot_heights(self, x, z, around):
        if x < 0 or z < 0 or x >= self.size[0] or z >= self.size[2]:
            return [self.origin[1] + 1] if abs(self.origin[1] + 1 - around) <= 1 else []
        heights = set()
        for by in range(math.floor(around) - 2, math.floor(around) + 2):
            state = self.at((x, by, z))
            if name(state) in HAZARDS | SKIP | {"minecraft:water", "minecraft:ladder"}:
                continue
            for box in self.boxes(state):
                top = by + box[4]
                if around - 1 <= top <= around + 1 and box[0] <= .5 <= box[3] and box[2] <= .5 <= box[5]:
                    heights.add(top)
        if name(self.at((x, math.floor(around), z))) == "minecraft:ladder":
            heights.add(around)
        # A path's -1/16 feet, or the full floor at a shaft lip, can step down
        # at most one block into an actual ladder cell. Body/edge checks follow.
        for by in range(math.ceil(around - 1), math.floor(around) + 1):
            if name(self.at((x, by, z))) == "minecraft:ladder":
                heights.add(by)
        return sorted(heights, key=lambda value: (abs(value - around), -value))

    def empty(self, p, doors=False):
        state = self.at(p)
        block = name(state)
        if block == "outside":
            return p[1] > self.origin[1]
        if block == "minecraft:structure_void":
            return self.decoration  # A functional control becomes a real barrel.
        if p in self.possible_water:
            return False
        if block in SKIP:
            return True
        if block in HAZARDS or block in {"minecraft:water", "minecraft:powder_snow", "minecraft:sweet_berry_bush"}:
            return False
        if block == "minecraft:ladder":
            return True
        if block.endswith("_door") and not block.endswith("_trapdoor"):
            return doors
        if block.endswith("_fence_gate"):
            return doors
        if block.endswith(("_fence", "_wall", "_pane", "_trapdoor")) or block in {"minecraft:iron_bars", "minecraft:scaffolding"}:
            return False
        return self.registry["blocks"].get(block, {}).get("boundingBox") == "empty"

    def support(self, p):
        state = self.at(p)
        block = name(state)
        if block in {"outside", "minecraft:barrier"}:
            return p[1] == self.origin[1]
        if block in HAZARDS | SKIP or block in {"minecraft:water", "minecraft:cactus", "minecraft:magma_block", "minecraft:ladder"}:
            return False
        fact = self.facts(state)
        if fact is not None:
            return any(box[4] == 1 and box[0] <= .5 <= box[3] and box[2] <= .5 <= box[5] for box in fact["boxes"])
        if block.endswith("_trapdoor"):
            props = properties(state)
            return props.get("half") == "top" and props.get("open") == "false"
        if block.endswith(("_door", "_fence", "_fence_gate", "_wall", "_pane")) or block in {"minecraft:iron_bars", "minecraft:scaffolding", "minecraft:chain"}:
            return False
        return self.registry["blocks"].get(block, {}).get("boundingBox") == "block"

    def stand(self, p, doors=False):
        x, y, z = p
        if self.native:
            return self.body_clear(p) and (y in self.foot_heights(x, z, y) or
                name(self.at((x, math.floor(y), z))) == "minecraft:ladder")
        return self.empty(p, doors) and self.empty((x, y + 1, z), doors) and (
            self.support((x, y - 1, z)) or name(self.at(p)) == "minecraft:ladder")

    def reachable(self):
        start = (self.origin[0], self.origin[1] + 1, self.origin[2])
        pending, seen, parent = deque([start]), {start}, {}
        while pending:
            x, y, z = pending.popleft()
            for dy in (-1, 1):
                p = (x, y + dy, z)
                if (name(self.at((x, math.floor(y), z))) == "minecraft:ladder" or name(self.at((p[0], math.floor(p[1]), p[2]))) == "minecraft:ladder") and p not in seen and self.stand(p, doors=True):
                    seen.add(p); parent[p] = (x, y, z); pending.append(p)
            for dx, dz in VECTORS.values():
                for ny in (self.foot_heights(x + dx, z + dz, y) if self.native else (y, y + 1, y - 1)):
                    p = (x + dx, ny, z + dz)
                    if not (-1 <= p[0] <= self.size[0] and -1 <= p[2] <= self.size[2] and 0 <= ny < self.size[1]):
                        continue
                    if p in seen or not self.stand(p, doors=True):
                        continue
                    # A one-block ascent also needs clearance above the old feet.
                    if self.native and (not self.body_clear((x, max(y, ny), z)) or
                                        not self.body_clear((x + dx / 2, max(y, ny), z + dz / 2))):
                        continue
                    if not self.native and ny > y and not self.empty((x, y + 2, z), doors=True):
                        continue
                    seen.add(p); parent[p] = (x, y, z); pending.append(p)
        return seen, parent

    def relative(self, point):
        return [point[a] - self.origin[a] for a in range(3)]

    @staticmethod
    def reached_cells(reachable):
        # Only native-valid graph endpoints; this does not create a route edge.
        return {(x, round(y), z) for x, y, z in reachable if abs(y - round(y)) <= .125}

    def visible(self, start, end, target):
        """Conservative voxel ray: opaque partial blocks may reject, never disappear."""
        delta = [end[a] - start[a] for a in range(3)]
        p = [math.floor(v) for v in start]
        step = [1 if d > 0 else -1 for d in delta]
        edge = [((p[a] + (1 if step[a] > 0 else 0) - start[a]) / delta[a])
                if delta[a] else math.inf for a in range(3)]
        stride = [abs(1 / d) if d else math.inf for d in delta]
        for _ in range(64):  # At most four metres, hence fewer than 24 voxel faces.
            if tuple(p) != target:
                state = self.at(tuple(p))
                for box in ([] if state == "outside" and p[1] > self.origin[1] else self.boxes(state, doors=True)):
                    enter, leave = 0.0, 1.0
                    for axis in range(3):
                        lo, hi = p[axis] + box[axis], p[axis] + box[axis + 3]
                        if delta[axis]:
                            ts = sorted(((lo - start[axis]) / delta[axis], (hi - start[axis]) / delta[axis]))
                            enter, leave = max(enter, ts[0]), min(leave, ts[1])
                        elif not lo < start[axis] < hi:
                            enter = 2
                    if enter < leave and leave > 0 and enter < 1:
                        return False
            at = min(edge)
            if at >= 1:
                return True
            for axis in range(3):
                if edge[axis] == at:
                    p[axis] += step[axis]
                    edge[axis] += stride[axis]
        raise ValueError("Container ray exceeded its geometric bound")

    def container_faces(self, p, reachable):
        """Player reach from a proven standing cell; no adjacent-cell requirement."""
        x, y, z = p
        candidates = [q for q in reachable if abs(q[0] - x) <= 4 and abs(q[1] - y) <= 4 and abs(q[2] - z) <= 4]
        faces = set()
        for facing, (dx, dz) in VECTORS.items():
            target = (x + .5 + dx * .501, y + .5, z + .5 + dz * .501)
            for q in candidates:
                eye = (q[0] + .5, q[1] + 1.62, q[2] + .5)
                if (eye[0] - x - .5) * dx + (eye[2] - z - .5) * dz <= .5:
                    continue
                if sum((eye[a] - target[a]) ** 2 for a in range(3)) <= 4.0 ** 2 and self.visible(eye, target, p):
                    faces.add(facing)
                    break
        target = (x + .5, y + 1.001, z + .5)
        if self.empty((x, y + 1, z), doors=True):
            for q in candidates:
                eye = (q[0] + .5, q[1] + 1.62, q[2] + .5)
                if eye[1] > target[1] and sum((eye[a] - target[a]) ** 2 for a in range(3)) <= 4.0 ** 2 and self.visible(eye, target, p):
                    faces.add("up")
                    break
        return faces
