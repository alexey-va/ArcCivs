package ru.arc.civs

import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentHashMap

/** Versioned, schematic-local geometry. V2/V3 orders stay pinned for saved receipts. */
internal class CivsMinePlan private constructor(layout: JsonObject, val version: Int) {
    data class Step(val point: CivPosition, val after: String?, val stage: String,
        val floor: Int? = null, val branch: String? = null, val shaftSupport: Boolean = false,
        /** Already built predecessor; native support, reach and route checks remain mandatory. */
        val approach: CivPosition? = null)
    data class Floor(val index: Int, val feetY: Int, val cells: List<CivPosition>,
        val torches: Set<CivPosition>, val sections: List<Pair<String, Int>>, val height: Int = 3)

    val bottom = CivsWorksiteLayouts.vector(layout["ladderBottom"])
    val facing = layout["ladderFacing"].asString
    private val forward = when (facing) { "north" -> 0 to -1; "south" -> 0 to 1; "east" -> 1 to 0; else -> -1 to 0 }
    val backing = bottom.copy(x = bottom.x - forward.first, z = bottom.z - forward.second)
    val center = CivsWorksiteLayouts.vector(layout["shaftCenter"]).copy(y = bottom.y)
    val transferFeetY = bottom.y - 2
    val extensionLadder = when (version) {
        2 -> bottom
        3 -> bottom.copy(x = bottom.x - forward.second * 3, y = transferFeetY, z = bottom.z + forward.first * 3)
        else -> bottom.copy(y = transferFeetY)
    }
    val extensionBacking = extensionLadder.copy(x = extensionLadder.x - forward.first,
        z = extensionLadder.z - forward.second)
    val openingColumns = (-1..1).flatMap { x -> (-1..1).map { z -> center.x + x to center.z + z } }.toSet()
    private val accessColumns = layout.getAsJsonArray("shaftFootprint").mapTo(linkedSetOf()) {
        it.asJsonArray.let { p -> p[0].asInt to p[1].asInt }
    }
    private val extensionColumns = (-1..1).flatMap { x -> (-1..1).map { z -> extensionLadder.x + x to extensionLadder.z + z } }.toSet()
    val shaftColumns = when (version) {
        2 -> accessColumns
        3 -> accessColumns + openingColumns + extensionColumns
        else -> accessColumns + openingColumns
    }
    private val oldFloors = layout.getAsJsonArray("floors").map { element -> element.asJsonObject.let { floor ->
        Floor(floor["index"].asInt, floor["feetY"].asInt,
            floor.getAsJsonArray("floorCells").map(CivsWorksiteLayouts::vector),
            floor.getAsJsonArray("torchCells").mapTo(linkedSetOf(), CivsWorksiteLayouts::vector),
            floor.getAsJsonArray("workSections").map { it.asJsonObject.let { part -> part["name"].asString to part["cells"].asInt } })
    } }
    val floors = when (version) {
        2 -> oldFloors
        3 -> alignedFloors(oldFloors.size + 2)
        else -> networkFloors(oldFloors.size + 2)
    }
    /** Entry vestibule is two high, below the untouched authored ceiling. */
    val entranceLanding = if (version >= 4) Floor(0, transferFeetY,
        landingOrder(transferFeetY).map { it.first }, landingLights(transferFeetY),
        listOf("entrance" to landingOrder(transferFeetY).size), height = 2) else null
    val transferFloor = when (version) {
        2 -> emptySet()
        3 -> (accessColumns + extensionColumns)
            .filter { it != (extensionLadder.x to extensionLadder.z) && it != (extensionBacking.x to extensionBacking.z) }
            .mapTo(linkedSetOf()) { (x, z) -> CivPosition(x, transferFeetY - 1, z) }
        else -> requireNotNull(entranceLanding).cells.toSet()
    }
    val lowest = floors.last().feetY
    val generator = if (version >= 3) center.copy(x = center.x + forward.first * 21,
        y = lowest + 1, z = center.z + forward.second * 21) else null
    val generatorStanding = generator?.copy(x = generator.x - forward.first, y = lowest, z = generator.z - forward.second)
    private val ladder = "minecraft:ladder[facing=$facing,waterlogged=false]"
    private val platforms = floors.flatMap { it.cells }.filterTo(hashSetOf()) { (it.x to it.z) in shaftColumns } + transferFloor

    init {
        require(layout["planVersion"].asInt == 2 && accessColumns.size == 9 && version in 2..VERSION)
        require(floors.all { floor -> floor.sections.sumOf { it.second } == floor.cells.size })
    }

    val steps: List<Step> = if (version == 2) buildList {
        val extended = hashSetOf<Int>()
        fun extend(rung: Int) {
            if (rung < lowest || !extended.add(rung)) return
            add(Step(backing.copy(y = rung), "minecraft:cobblestone", "shaft", shaftSupport = true))
            add(Step(bottom.copy(y = rung), ladder, "shaft"))
        }
        for (y in bottom.y - 1 downTo lowest) {
            val floor = floors.firstOrNull { it.feetY == y }
            extend(y)
            // One lower stance exposes the backing corners beneath either the
            // authored roof or an existing landing. Finish a landing before descending.
            if (floor == null) extend(y - 1)
            for ((x, z) in shaftColumns.sortedBy { kotlin.math.abs(it.first - bottom.x) + kotlin.math.abs(it.second - bottom.z) }) {
                val point = CivPosition(x, y, z)
                if ((x == bottom.x && z == bottom.z) || (x == backing.x && z == backing.z) || point in platforms) continue
                add(Step(point, null, "shaft"))
            }
            floor?.let {
                floor.cells.filter { it in platforms }.forEach {
                    add(Step(it, "minecraft:cobblestone", "shaft", floor.index, "landing"))
                }
                floor.torches.filter { (it.x to it.z) in shaftColumns }.forEach {
                    add(Step(it, "minecraft:torch", "shaft", floor.index, "landing"))
                }
                extend(y - 1)
            }
        }
        for (floor in floors) {
            var cursor = 0
            for ((branch, size) in floor.sections) {
                for (support in floor.cells.subList(cursor, cursor + size)) {
                    for (height in 3 downTo 1) add(Step(support.copy(y = support.y + height), null, "gallery", floor.index, branch))
                    add(Step(support, "minecraft:cobblestone", "gallery", floor.index, branch))
                    val torch = support.copy(y = support.y + 1)
                    if (torch in floor.torches) add(Step(torch, "minecraft:torch", "gallery", floor.index, branch))
                }
                cursor += size
            }
        }
    } else if (version == 3) buildList {
        val oldRungs = hashSetOf<Int>()
        val newRungs = hashSetOf<Int>()
        fun extendOld(rung: Int) {
            if (rung !in transferFeetY until bottom.y || !oldRungs.add(rung)) return
            add(Step(backing.copy(y = rung), "minecraft:cobblestone", "shaft", shaftSupport = true))
            add(Step(bottom.copy(y = rung), ladder, "shaft"))
        }
        fun extendNew(rung: Int) {
            if (rung !in lowest..transferFeetY || !newRungs.add(rung)) return
            add(Step(extensionBacking.copy(y = rung), "minecraft:cobblestone", "shaft", shaftSupport = true))
            add(Step(extensionLadder.copy(y = rung), ladder, "shaft"))
        }
        fun ordered(columns: Set<Pair<Int, Int>>, origin: CivPosition) = columns.sortedWith(compareBy<Pair<Int, Int>> {
            kotlin.math.abs(it.first - origin.x) + kotlin.math.abs(it.second - origin.z)
        }.thenBy { it.first }.thenBy { it.second })
        fun clearRow(y: Int, columns: Set<Pair<Int, Int>>, origin: CivPosition) {
            for ((x, z) in ordered(columns, origin)) {
                val point = CivPosition(x, y, z)
                if (point in platforms ||
                    y in oldRungs && (x == bottom.x && z == bottom.z || x == backing.x && z == backing.z) ||
                    y <= transferFeetY && x == extensionBacking.x && z == extensionBacking.z ||
                    y in newRungs && x == extensionLadder.x && z == extensionLadder.z) continue
                add(Step(point, null, "shaft"))
            }
        }
        // Build the two-high transfer from the authored ladder before extending
        // the lower shaft in a corner. Its floor is exactly the first tier's ceiling.
        for (y in bottom.y - 1 downTo transferFeetY) {
            extendOld(y)
            extendOld(y - 1)
            clearRow(y, shaftColumns, bottom)
        }
        // The cell directly below the retained upper backing has no exposed
        // face yet. Finish it from the first gallery, after its underside opens.
        val backingCap = backing.copy(y = transferFeetY - 1)
        transferFloor.filter { it != backingCap }.forEach {
            add(Step(it, "minecraft:cobblestone", "shaft", branch = "transfer"))
        }
        extendNew(transferFeetY)
        clearRow(bottom.y, openingColumns, bottom)
        var previousFeet = transferFeetY
        var previousOpening = transferFeetY
        for (floor in floors) {
            for (y in previousFeet - 1 downTo floor.feetY) {
                extendNew(y)
                if (y != floor.feetY) extendNew(y - 1)
                clearRow(y, extensionColumns, extensionLadder)
            }
            // Finish each usable tier before opening the next central slice.
            // Workers can reach every 3x3 opening cell from this supported ring.
            var cursor = 0
            for ((branch, size) in floor.sections) {
                for (support in floor.cells.subList(cursor, cursor + size)) {
                    for (height in 3 downTo 1) add(Step(support.copy(y = support.y + height), null, "gallery", floor.index, branch))
                    add(Step(support, "minecraft:cobblestone", "gallery", floor.index, branch))
                    val torch = support.copy(y = support.y + 1)
                    if (torch in floor.torches) add(Step(torch, "minecraft:torch", "gallery", floor.index, branch))
                }
                cursor += size
            }
            if (floor.index == 1) add(Step(backingCap, "minecraft:cobblestone", "shaft", branch = "transfer"))
            for (y in previousOpening - 1 downTo floor.feetY) clearRow(y, openingColumns, center)
            previousFeet = floor.feetY
            previousOpening = floor.feetY
        }
        openingColumns.forEach { (x, z) -> add(Step(CivPosition(x, lowest - 1, z),
            "minecraft:cobblestone", "shaft", floors.last().index, "bottom")) }
        add(Step(requireNotNull(generator), "minecraft:cobblestone", "gallery", floors.last().index, "generator"))
    } else buildNetworkSteps()

    /** In V4 every ladder, landing and central slice precedes the first branch. */
    val shaftEnd: Int = steps.indexOfFirst { it.stage == "gallery" }.takeIf { it >= 0 } ?: steps.size

    // Occupied shell/floor cells are never worker space. Native support and full-body
    // clearance still decide whether an authored passage is usable at this moment.
    private val workPassage = buildMap<CivPosition, Boolean> {
        steps.forEach { step -> put(step.point, step.after == null ||
            step.after.startsWith("minecraft:ladder[") || step.after == "minecraft:torch") }
        // The existing schematic supplies the upper ladder/access, before procedural cuts.
        for (y in bottom.y..bottom.y + 3) for ((x, z) in accessColumns)
            if (x != backing.x || z != backing.z) put(CivPosition(x, y, z), true)
    }.filterValues { it }.keys

    fun containsWorkStanding(local: CivPosition): Boolean =
        local in workPassage && local.copy(y = local.y + 1) in workPassage

    private val passageHistory = steps.withIndex().groupBy({ it.value.point }, { it.index + 1 to it.value.after })

    /**
     * Prefix admission, not a collision/ownership claim. In particular V3 may have a paid
     * prerequisite footing outside its ordinal; its real support is checked by the caller.
     */
    fun builtStanding(local: CivPosition, nextStep: Long): Boolean {
        if (nextStep !in 0..steps.size.toLong() || !containsWorkStanding(local)) return false
        fun open(point: CivPosition): Boolean {
            val change = passageHistory[point]?.lastOrNull { it.first <= nextStep }
            if (change != null) return change.second == null || change.second!!.startsWith("minecraft:ladder[") ||
                change.second == "minecraft:torch"
            return point.y in bottom.y..bottom.y + 3 && (point.x to point.z) in accessColumns &&
                (point.x != backing.x || point.z != backing.z)
        }
        return open(local) && open(local.copy(y = local.y + 1))
    }

    private fun relative(d: Int, s: Int, y: Int) = CivPosition(center.x + forward.first * d - forward.second * s,
        y, center.z + forward.second * d + forward.first * s)

    /** A breadth-first landing grows only from a completed neighbour, starting at the ladder. */
    private fun landingOrder(feetY: Int): List<Pair<CivPosition, CivPosition>> {
        val available = (-4..4).flatMap { d -> (-4..4).map { s -> relative(d, s, feetY) } }
            .filterTo(linkedSetOf()) { p -> (p.x to p.z) !in openingColumns &&
                (p.x != bottom.x || p.z != bottom.z) && (p.x != backing.x || p.z != backing.z) }
        val start = bottom.copy(y = feetY)
        val queue = ArrayDeque<CivPosition>().apply { add(start) }
        val visited = hashSetOf(start)
        val directions = listOf(forward, forward.second to -forward.first,
            -forward.second to forward.first, -forward.first to -forward.second)
        return buildList {
            while (queue.isNotEmpty()) {
                val parent = queue.removeFirst()
                for ((dx, dz) in directions) {
                    val next = parent.copy(x = parent.x + dx, z = parent.z + dz)
                    if (next !in available || !visited.add(next)) continue
                    add(next.copy(y = feetY - 1) to parent)
                    queue.add(next)
                }
            }
            check(size == available.size) { "Mine landing is disconnected from its authored ladder" }
        }
    }

    private fun landingLights(feetY: Int): Set<CivPosition> {
        val points = mutableSetOf(-3 to -3, -3 to 3, 3 to -3, 3 to 3)
        for ((d, s) in listOf(1 to 0, 0 to -1, 0 to 1)) for (side in listOf(-1, 1))
            points += d * 3 - s * side to s * 3 + d * side
        return points.mapTo(linkedSetOf()) { (d, s) -> relative(d, s, feetY) }
    }

    private fun networkSections(): List<Pair<String, List<Pair<Int, Int>>>> {
        fun arm(d: Int, s: Int) = (5..20).flatMap { distance -> listOf(0, -1, 1).map { width ->
            d * distance - s * width to s * distance + d * width
        } }
        fun spur(side: Int, direction: Int) = (2..8).flatMap { distance -> listOf(0, -1, 1).map { width ->
            direction * distance to side * 12 + width
        } }
        return listOf("main" to arm(1, 0), "left" to arm(0, -1),
            "left-forward" to spur(-1, 1), "left-back" to spur(-1, -1),
            "right" to arm(0, 1), "right-forward" to spur(1, 1), "right-back" to spur(1, -1))
    }

    private fun networkFloors(count: Int): List<Floor> = (1..count).map { index ->
        val feet = bottom.y - 6 - (index - 1) * 4
        val landing = landingOrder(feet).map { it.first }
        val branches = networkSections()
        val lights = landingLights(feet).toMutableSet()
        for ((d, s) in listOf(1 to 0, 0 to -1, 0 to 1))
            for (distance in listOf(7, 12, 17, 20)) for (side in listOf(-1, 1))
                lights += relative(d * distance - s * side, s * distance + d * side, feet)
        for (side in listOf(-1, 1)) for (direction in listOf(-1, 1))
            for (distance in listOf(4, 8)) for (width in listOf(-1, 1))
                lights += relative(direction * distance, side * 12 + width, feet)
        Floor(index, feet, landing + branches.flatMap { (_, cells) -> cells.map { (d, s) -> relative(d, s, feet - 1) } },
            lights, listOf("landing" to landing.size) + branches.map { it.first to it.second.size })
    }

    private fun buildNetworkSteps(): List<Step> = buildList {
        fun descend(from: Int, to: Int) {
            for (y in from - 1 downTo to) {
                val approach = bottom.copy(y = y + 1)
                add(Step(backing.copy(y = y), "minecraft:cobblestone", "shaft", shaftSupport = true, approach = approach))
                add(Step(bottom.copy(y = y), ladder, "shaft", approach = approach))
            }
        }
        fun finishCell(support: CivPosition, floor: Floor, branch: String, approach: CivPosition, stage: String) {
            for (height in floor.height downTo 1)
                add(Step(support.copy(y = support.y + height), null, stage, floor.index, branch, approach = approach))
            add(Step(support, "minecraft:cobblestone", stage, floor.index, branch, approach = approach))
            val torch = support.copy(y = support.y + 1)
            if (torch in floor.torches)
                add(Step(torch, "minecraft:torch", stage, floor.index, branch, approach = approach))
        }
        fun finishLanding(floor: Floor) {
            for ((support, parent) in landingOrder(floor.feetY))
                finishCell(support, floor, if (floor.index == 0) "entrance" else "landing", parent, "shaft")
        }
        fun openCentral(from: Int, to: Int, floor: Floor) {
            fun nearest(x: Int, z: Int) = floor.cells.take(floor.sections.first().second)
                .map { it.copy(y = floor.feetY) }.minWith(compareBy<CivPosition> {
                    kotlin.math.abs(it.x - x) + kotlin.math.abs(it.z - z)
                }.thenBy { it.x }.thenBy { it.z })
            for (y in to..from) for ((x, z) in openingColumns.sortedWith(compareBy<Pair<Int, Int>> {
                kotlin.math.abs(it.first - bottom.x) + kotlin.math.abs(it.second - bottom.z)
            }.thenBy { it.first }.thenBy { it.second })) {
                val point = CivPosition(x, y, z)
                // Clear the lower rows first so the next row's underside is exposed.
                // Native face probes reach it from the completed current landing.
                add(Step(point, null, "shaft", floor.index, "opening", approach = nearest(x, z)))
            }
        }
        val entry = requireNotNull(entranceLanding)
        descend(bottom.y, entry.feetY)
        finishLanding(entry)
        openCentral(bottom.y, entry.feetY, entry)
        var previousLanding = entry
        for (floor in floors) {
            descend(previousLanding.feetY, floor.feetY)
            finishLanding(floor)
            openCentral(previousLanding.feetY - 1, floor.feetY, floor)
            previousLanding = floor
        }
        for ((x, z) in openingColumns) {
            val support = CivPosition(x, lowest - 1, z)
            val nearest = floors.last().cells.take(floors.last().sections.first().second).minBy {
                kotlin.math.abs(it.x - x) + kotlin.math.abs(it.z - z)
            }
            add(Step(support, "minecraft:cobblestone", "shaft", floors.last().index, "bottom",
                approach = nearest.copy(y = lowest)))
        }
        for (floor in floors) {
            var cursor = floor.sections.first().second
            for ((branch, size) in floor.sections.drop(1)) {
                val cells = floor.cells.subList(cursor, cursor + size)
                for ((offset, support) in cells.withIndex()) {
                    val distance = offset / 3
                    val centerCell = cells[distance * 3]
                    val previous = if (distance > 0) cells[(distance - 1) * 3] else when (branch) {
                        "main" -> relative(4, 0, support.y)
                        "left" -> relative(0, -4, support.y)
                        "right" -> relative(0, 4, support.y)
                        "left-forward" -> relative(1, -12, support.y)
                        "left-back" -> relative(-1, -12, support.y)
                        "right-forward" -> relative(1, 12, support.y)
                        else -> relative(-1, 12, support.y)
                    }
                    val approach = (if (offset % 3 == 0) previous else centerCell).copy(y = floor.feetY)
                    finishCell(support, floor, branch, approach, "gallery")
                }
                cursor += size
            }
        }
        add(Step(requireNotNull(generator), "minecraft:cobblestone", "gallery", floors.last().index,
            "generator", approach = requireNotNull(generatorStanding)))
    }

    private fun alignedFloors(count: Int): List<Floor> {
        fun point(d: Int, s: Int, y: Int) = CivPosition(center.x + forward.first * d - forward.second * s,
            y, center.z + forward.second * d + forward.first * s)
        val ring = (-4..4).flatMap { d -> (-4..4).map { s -> d to s } }
            .filter { (d, s) -> (kotlin.math.abs(d) > 1 || kotlin.math.abs(s) > 1) &&
                point(d, s, extensionLadder.y).let { it != extensionLadder && it != extensionBacking } }
            .sortedWith(compareBy<Pair<Int, Int>> { (d, s) ->
                val p = point(d, s, bottom.y)
                kotlin.math.abs(p.x - extensionLadder.x) + kotlin.math.abs(p.z - extensionLadder.z)
            }.thenBy { it.first }.thenBy { it.second })
        val arms = listOf("main" to (1 to 0), "left" to (0 to -1), "right" to (0 to 1), "back" to (-1 to 0))
        val sections = listOf("landing" to ring) + arms.map { (name, direction) ->
            name to (5..20).flatMap { distance -> listOf(0, -1, 1).map { width ->
                direction.first * distance - direction.second * width to direction.second * distance + direction.first * width
            } }
        }
        val lights = listOf(-3 to -3, -2 to 3, 3 to -3, 3 to 3).toMutableSet()
        for ((_, direction) in arms) for (side in listOf(-1, 1))
            lights += direction.first * 3 - direction.second * side to direction.second * 3 + direction.first * side
        for ((_, direction) in arms) for (distance in listOf(7, 12, 17, 20)) for (side in listOf(-1, 1))
            lights += direction.first * distance - direction.second * side to direction.second * distance + direction.first * side
        return (1..count).map { index ->
            val feet = bottom.y - 6 - (index - 1) * 4
            Floor(index, feet, sections.flatMap { (_, cells) -> cells.map { (d, s) -> point(d, s, feet - 1) } },
                lights.mapTo(linkedSetOf()) { (d, s) -> point(d, s, feet) }, sections.map { it.first to it.second.size })
        }
    }

    /** Only used to interpret the old saved prefix; never used as the new construction order. */
    private val legacySteps: List<Pair<CivPosition, String?>> = buildList {
        for (y in bottom.y - 1 downTo oldFloors.last().feetY) {
            add(backing.copy(y = y) to "minecraft:cobblestone")
            add(bottom.copy(y = y) to ladder)
        }
        for (floor in oldFloors) {
            fun depth(p: CivPosition) = (p.x - bottom.x) * forward.first + (p.z - bottom.z) * forward.second
            fun side(p: CivPosition) = -(p.x - bottom.x) * forward.second + (p.z - bottom.z) * forward.first
            val ordered = floor.cells.sortedWith(compareBy<CivPosition> { kotlin.math.abs(depth(it)) + kotlin.math.abs(side(it)) }
                .thenBy(::depth).thenBy(::side))
            for ((index, support) in ordered.withIndex()) {
                for (height in 3 downTo 1) add(support.copy(y = support.y + height) to null)
                add(support to "minecraft:cobblestone")
                if (index % 8 == 0) add(support.copy(y = support.y + 1) to "minecraft:torch")
            }
        }
    }

    fun legacyCompleted(nextStep: Long): Map<CivPosition, String?>? =
        if (nextStep in 0..legacySteps.size.toLong()) legacySteps.take(nextStep.toInt()).toMap() else null

    fun completed(nextStep: Long): Map<CivPosition, String?>? =
        if (nextStep in 0..steps.size.toLong()) steps.take(nextStep.toInt()).associate { it.point to it.after } else null

    fun containsShaft(local: CivPosition): Boolean = local.y in lowest..bottom.y && (local.x to local.z) in shaftColumns

    /** A far empty wall may wait only for its own next clearance row, never a later gallery. */
    fun lowerClearanceEnd(exposed: CivPosition): Int {
        if (exposed.y <= lowest || (exposed.x to exposed.z) !in shaftColumns) return 0
        val opening = (exposed.x to exposed.z) in openingColumns
        return steps.indexOfLast { it.stage == "shaft" && it.after == null && it.point.y == exposed.y - 1 &&
            ((it.point.x to it.point.z) in openingColumns) == opening } + 1
    }

    fun ladderAt(localY: Int): CivPosition =
        (if (version == 2 || localY > transferFeetY) bottom else extensionLadder).copy(y = localY)

    fun ladderForBacking(local: CivPosition): CivPosition =
        (if (local.x == extensionBacking.x && local.z == extensionBacking.z) extensionLadder else bottom).copy(y = local.y)

    companion object {
        const val VERSION = 4
        private val plans = ConcurrentHashMap<String, CivsMinePlan>()
        fun forBuilding(building: CivBuilding, version: Int = VERSION): CivsMinePlan? {
            if (version !in 2..VERSION) return null
            val key = "${building.schematicId}:$version"
            plans[key]?.let { return it }
            val layout = CivsWorksiteLayouts.site(building)?.getAsJsonObject("mine") ?: return null
            return plans.computeIfAbsent(key) { CivsMinePlan(layout, version) }
        }

        fun shaftContains(building: CivBuilding, world: CivPosition): Boolean {
            val plan = forBuilding(building) ?: return false
            return plan.containsShaft(localPoint(building, world))
        }

        fun workStandingAllowed(building: CivBuilding, world: CivPosition): Boolean =
            building.type == BuildingType.QUARRY && forBuilding(building)?.containsWorkStanding(localPoint(building, world)) == true

        /** Mine routes/cuts may pass below buildings, retaining three untouched foundation layers. */
        fun belowFoundation(building: CivBuilding, y: Int, headroom: Int = 0): Boolean =
            building.type != BuildingType.QUARRY && y.toLong() + headroom < building.bounds.min.y.toLong() - 3

        fun localPoint(building: CivBuilding, world: CivPosition): CivPosition {
            val dx = world.x - building.anchor.x
            val dz = world.z - building.anchor.z
            val (x, z) = when (Math.floorMod(building.rotation / 90, 4)) {
                0 -> dx to dz; 1 -> dz to -dx; 2 -> -dx to -dz; else -> -dz to dx
            }
            return CivPosition(x, world.y - building.anchor.y, z)
        }
    }
}
