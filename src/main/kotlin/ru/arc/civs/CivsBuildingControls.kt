package ru.arc.civs

import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot
import kotlin.math.abs

/** The authored marker is a construction anchor; active buildings use an accessible interior barrel. */
internal object CivsBuildingControls {
    fun supported(type: BuildingType): Boolean = !type.isInfrastructure &&
        type != BuildingType.DECORATION && type != BuildingType.PLAN_BOARD

    private val furniture = setOf("barrel", "chest", "trapped_chest", "hopper", "furnace", "blast_furnace",
        "smoker", "crafting_table", "smithing_table", "fletching_table", "stonecutter", "grindstone",
        "anvil", "chipped_anvil", "damaged_anvil", "lectern", "bookshelf", "chiseled_bookshelf")
    private val directions = listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)

    /** Detached source geometry only. It never edits captured plans, walls, furniture or native contents. */
    fun authored(building: CivBuilding, snapshot: ArcSchematicSnapshot): List<CivControlCandidate> =
        inspectSource(building, snapshot).candidates

    internal data class Source(val geometry: String, val candidates: List<CivControlCandidate>)
    internal fun inspectSource(building: CivBuilding, snapshot: ArcSchematicSnapshot): Source {
        if (!supported(building.type)) return Source("DEDICATED", emptyList())
        // Barrier cells retain native terrain; they are conditional route space, never authored walls to replace.
        val interior = CivsAuthoredInterior(building, snapshot, furniture, retainedGround = true)
        val old = building.control?.let { point ->
            CivsBlueprintGeometry.rotate(ArcSchematicPoint(point.x - building.anchor.x,
                point.y - building.anchor.y, point.z - building.anchor.z), -building.rotation / 90).let {
                it.copy(x = it.x + snapshot.origin.x, y = it.y + snapshot.origin.y, z = it.z + snapshot.origin.z)
            }
        }
        // A previously selected authored barrel is furniture. A later correction must retain it and its contents.
        val retire = old == null || old.x !in snapshot.minimum.x..snapshot.maximum.x ||
            old.y !in snapshot.minimum.y..snapshot.maximum.y || old.z !in snapshot.minimum.z..snapshot.maximum.z ||
            interior.name(old) in setOf("air", "cave_air", "void_air", "structure_void", "barrier")
        fun awayFromFront(p: ArcSchematicPoint): Boolean = p.x > snapshot.minimum.x && p.x < snapshot.maximum.x &&
            p.z > snapshot.minimum.z && p.z < snapshot.maximum.z &&
            abs(p.x - interior.entrance.x) + abs(p.z - interior.entrance.z) >= 4
        val space = ControlSpace(interior, ::awayFromFront)
        fun standing(p: ArcSchematicPoint): Boolean = awayFromFront(p) &&
            interior.operatingFloor(p) && space.accepts(p) &&
            !interior.name(p).endsWith("_door") && !interior.name(p).endsWith("_fence_gate")
        val order = compareBy<ArcSchematicPoint>({ if (interior.world(it) == building.control) 0 else 1 }, { abs(it.y - interior.entrance.y) },
            { abs(it.x - (snapshot.minimum.x + snapshot.maximum.x) / 2) +
                abs(it.z - (snapshot.minimum.z + snapshot.maximum.z) / 2) }, { it.x }, { it.z })
        val existing = interior.cells.asSequence().filter { cell ->
            interior.name(cell.position) == "barrel" || interior.name(cell.position) == "structure_void" &&
                interior.world(cell.position) == building.control
        }
            .map { it.position }.filter { awayFromFront(it) && space.accepts(it) }
            .sortedWith(order).flatMap { point ->
                directions.asSequence().flatMap { (dx, dz) -> sequenceOf(0, -1, 1).map { dy ->
                    point.copy(x = point.x + dx, y = point.y + dy, z = point.z + dz)
                } }.filter(::standing).mapNotNull { stance ->
                    interior.routeTo(stance)?.let { route ->
                        CivControlCandidate(interior.world(point), interior.world(stance), true, retire, route)
                    }
                }
            }.distinctBy(CivControlCandidate::position).take(16).toList()
        val work = CivsWorkstations.authored(building, snapshot).mapNotNull(CivWorkstation::focus).distinct()
        val possible = interior.reachable.asSequence().filter { p -> standing(p) && interior.name(p) in setOf("air", "barrier") &&
            interior.name(p.copy(y = p.y - 1)) != "farmland" &&
            work.none { focus -> interior.world(p).let {
                abs(it.y.toLong() - focus.y) <= 1 && abs(it.x.toLong() - focus.x) + abs(it.z.toLong() - focus.z) < 2
            } } }.sortedWith(compareBy<ArcSchematicPoint>({ if (interior.world(it) == building.control) 0 else 1 },
                { abs(it.y - interior.entrance.y) },
                { -interior.wallScore(it) }).then(order)).take(64)
        val spare = possible.mapNotNull { point ->
            val stance = interior.neighbors(point).firstOrNull { it != point && standing(it) }
                ?: return@mapNotNull null
            val route = interior.routeTo(stance, point) ?: return@mapNotNull null
            CivControlCandidate(interior.world(point), interior.world(stance), false, retire, route)
        }.take(16).toList()
        return Source(space.geometry, (existing + spare).take(CivsWorkstations.MAXIMUM_POINTS))
    }

    /** Roof eaves are outside. Doors seal room boundaries here, while the separate walk graph can use them. */
    private class ControlSpace(private val interior: CivsAuthoredInterior, placementFloor: (ArcSchematicPoint) -> Boolean) {
        private val source = interior.snapshot
        private val width = source.sizeX
        private val length = source.sizeZ
        private val area = width * length
        private val planes = hashMapOf<Int, Plane>()
        // An inaccessible attic cannot choose the mode for the actual working floor. This graph
        // includes conditional KEEP_NATIVE support; the unchanged native route proof verifies it.
        private val floors = interior.reachable.filter { interior.operatingFloor(it) && placementFloor(it) }.toHashSet()
        // A reachable one-cell pocket cannot hold both a barrel and an ordinary stance. It must
        // not hide the actual sheltered working area elsewhere in a tower or workshop.
        private val hasRooms = floors.any { point ->
            plane(point.y).rooms[index(point)] && interior.neighbors(point).any { next ->
                inside(next) && placementFloor(next) && plane(next.y).rooms[index(next)] &&
                    (next in floors || interior.name(next) in setOf("barrel", "structure_void"))
            }
        }
        private val coverDepth = floors.groupBy { it.y }.mapValues { (_, points) ->
            points.maxOfOrNull { plane(it.y).cover[index(it)] } ?: 0
        }
        private val hasCover = coverDepth.values.any { it >= 2 }
        private val floorDepth = floors.groupBy { it.y }.mapValues { (_, points) ->
            points.maxOfOrNull { plane(it.y).floor[index(it)] } ?: 0
        }
        private val openWork = !interior.building.type.isResidence &&
            interior.building.type !in setOf(BuildingType.TOWN_HALL, BuildingType.LIBRARY, BuildingType.UNIVERSITY)
        private val inherentlyOpen by lazy {
            interior.cells.asSequence().map { it.position.copy(y = it.position.y + 1) }.filter {
                it.y < source.maximum.y && authoredFloor(it) && open(it) && open(it.copy(y = it.y + 1))
            }.none { plane(it.y).rooms[index(it)] || plane(it.y).cover[index(it)] >= 2 }
        }
        val geometry: String get() = when {
            hasRooms -> "ROOM"; hasCover -> "SHELTERED_WORK_CENTRE"
            openWork && inherentlyOpen -> "OPEN_WORK_FLOOR"; else -> "NO_INTERIOR"
        }

        fun accepts(p: ArcSchematicPoint): Boolean {
            if (!inside(p) || p.y >= source.maximum.y || !open(p.copy(y = p.y + 1))) return false
            val at = plane(p.y); val index = index(p)
            return when {
                hasRooms -> at.rooms[index]
                hasCover -> at.cover[index] >= maxOf(2, (coverDepth[p.y] ?: 0) - 1)
                // Inherently open machine/storage platforms have no room to invent. Use the
                // connected authored floor's central band, never a house's surrounding yard.
                else -> openWork && inherentlyOpen && at.floor[index] >= maxOf(2, (floorDepth[p.y] ?: 0) - 1)
            }
        }

        private data class Plane(val rooms: BooleanArray, val cover: IntArray, val floor: IntArray)
        private fun index(p: ArcSchematicPoint) = (p.z - source.minimum.z) * width + p.x - source.minimum.x
        private fun point(index: Int, y: Int) = ArcSchematicPoint(source.minimum.x + index % width, y,
            source.minimum.z + index / width)
        private fun edge(i: Int) = i % width == 0 || i % width == width - 1 || i / width == 0 || i / width == length - 1
        private fun neighbors(i: Int): List<Int> = buildList(4) {
            if (i % width > 0) add(i - 1)
            if (i % width < width - 1) add(i + 1)
            if (i >= width) add(i - width)
            if (i + width < area) add(i + width)
        }
        private fun inside(p: ArcSchematicPoint) = p.x in source.minimum.x..source.maximum.x &&
            p.y in source.minimum.y..source.maximum.y && p.z in source.minimum.z..source.maximum.z
        private fun open(p: ArcSchematicPoint): Boolean = inside(p) && interior.name(p).let { name ->
            name in nonStructural || name in furniture ||
                listOf("_leaves", "_torch", "_sign", "_sapling", "_carpet", "_rail", "_button", "_pressure_plate").any(name::endsWith)
        }
        private fun authoredFloor(p: ArcSchematicPoint): Boolean {
            val below = p.copy(y = p.y - 1)
            if (!inside(below)) return false
            val name = interior.name(below)
            if (name == "barrier") return below.y == source.origin.y
            return name !in nonStructural && name !in furniture &&
                listOf("shulker_box", "_bed", "_fence", "_fence_gate", "_wall", "_pane", "_leaves", "_door", "_trapdoor",
                    "_torch", "_sign", "_sapling", "_carpet", "_rail", "_button", "_pressure_plate").none(name::endsWith)
        }

        /** Each horizontal layer is visited a bounded number of times, entirely on the source executor. */
        private fun plane(y: Int): Plane = planes.getOrPut(y) {
            val passable = BooleanArray(area) { open(point(it, y + 1)) }
            fun distances(domain: BooleanArray, seeds: (Int) -> Boolean): IntArray {
                val distance = IntArray(area) { -1 }; val queue = ArrayDeque<Int>()
                for (i in 0 until area) if (domain[i] && seeds(i)) { distance[i] = 0; queue.addLast(i) }
                while (queue.isNotEmpty()) {
                    val i = queue.removeFirst()
                    for (next in neighbors(i)) if (domain[next] && distance[next] < 0) {
                        distance[next] = distance[i] + 1; queue.addLast(next)
                    }
                }
                return distance
            }
            // A one/two-wide framed opening is a doorway even when the author omitted a door
            // block. Sealing it classifies the room only; the real walk graph remains unchanged.
            fun framed(i: Int, dx: Int, dz: Int): Boolean {
                val p = point(i, y + 1)
                fun wall(sign: Int): Int? = (1..2).firstOrNull { distance ->
                    val next = p.copy(x = p.x + dx * sign * distance, z = p.z + dz * sign * distance)
                    inside(next) && !passable[index(next)]
                }
                val left = wall(-1) ?: return false; val right = wall(1) ?: return false
                return left + right <= 3
            }
            val roomSpace = BooleanArray(area) { passable[it] &&
                !(interior.sheltered(point(it, y)) && (framed(it, 1, 0) || framed(it, 0, 1))) }
            // An open courtyard is outside even when a perimeter wall encloses its head layer.
            // Its uncovered columns also expose adjacent eaves; a lone beam cannot make a room.
            val exterior = distances(roomSpace) { edge(it) || !interior.sheltered(point(it, y)) }
            val rooms = BooleanArray(area); val seen = BooleanArray(area)
            for (i in 0 until area) if (roomSpace[i] && exterior[i] < 0 && !seen[i]) {
                val component = mutableListOf<Int>(); val queue = ArrayDeque<Int>(); queue.add(i); seen[i] = true
                while (queue.isNotEmpty()) {
                    val at = queue.removeFirst(); component.add(at)
                    for (next in neighbors(at)) if (roomSpace[next] && exterior[next] < 0 && !seen[next]) {
                        seen[next] = true; queue.addLast(next)
                    }
                }
                if (component.size >= 4) component.forEach { rooms[it] = true }
            }
            val cover = distances(passable) { edge(it) || !interior.sheltered(point(it, y)) }
            // Fully enclosed components have no exterior distance; room admission above owns them.
            val floor = BooleanArray(area) { passable[it] && authoredFloor(point(it, y)) && open(point(it, y)) }
            val depth = distances(floor) { edge(it) || neighbors(it).any { next -> !floor[next] } }
            Plane(rooms, cover, IntArray(area) { if (floor[it]) depth[it] + 1 else 0 })
        }
    }

    private val nonStructural = setOf("air", "cave_air", "void_air", "structure_void", "barrier", "water", "lava",
        "fire", "soul_fire", "torch", "ladder", "chain", "lantern", "soul_lantern", "rail", "lever", "tripwire",
        "tripwire_hook", "redstone_wire", "snow", "vine", "grass", "short_grass", "tall_grass", "fern", "large_fern", "dead_bush", "cobweb")
}
