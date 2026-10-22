package ru.arc.civs

import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentHashMap

/** Versioned, schematic-local geometry. The pinned V2 order remains readable for saved receipts. */
internal class CivsMinePlan private constructor(layout: JsonObject, val version: Int) {
    data class Step(val point: CivPosition, val after: String?, val stage: String,
        val floor: Int? = null, val branch: String? = null, val shaftSupport: Boolean = false)
    data class Floor(val index: Int, val feetY: Int, val cells: List<CivPosition>,
        val torches: Set<CivPosition>, val sections: List<Pair<String, Int>>)

    val bottom = CivsWorksiteLayouts.vector(layout["ladderBottom"])
    val facing = layout["ladderFacing"].asString
    private val forward = when (facing) { "north" -> 0 to -1; "south" -> 0 to 1; "east" -> 1 to 0; else -> -1 to 0 }
    val backing = bottom.copy(x = bottom.x - forward.first, z = bottom.z - forward.second)
    val center = CivsWorksiteLayouts.vector(layout["shaftCenter"]).copy(y = bottom.y)
    val transferFeetY = bottom.y - 2
    val extensionLadder = if (version == 2) bottom else bottom.copy(
        x = bottom.x - forward.second * 3, y = transferFeetY, z = bottom.z + forward.first * 3)
    val extensionBacking = extensionLadder.copy(x = extensionLadder.x - forward.first,
        z = extensionLadder.z - forward.second)
    val openingColumns = (-1..1).flatMap { x -> (-1..1).map { z -> center.x + x to center.z + z } }.toSet()
    private val accessColumns = layout.getAsJsonArray("shaftFootprint").mapTo(linkedSetOf()) {
        it.asJsonArray.let { p -> p[0].asInt to p[1].asInt }
    }
    private val extensionColumns = (-1..1).flatMap { x -> (-1..1).map { z -> extensionLadder.x + x to extensionLadder.z + z } }.toSet()
    val shaftColumns = if (version == 2) accessColumns else accessColumns + openingColumns + extensionColumns
    val transferFloor = if (version == 2) emptySet() else (accessColumns + extensionColumns)
        .filter { it != (extensionLadder.x to extensionLadder.z) && it != (extensionBacking.x to extensionBacking.z) }
        .mapTo(linkedSetOf()) { (x, z) -> CivPosition(x, transferFeetY - 1, z) }
    private val oldFloors = layout.getAsJsonArray("floors").map { element -> element.asJsonObject.let { floor ->
        Floor(floor["index"].asInt, floor["feetY"].asInt,
            floor.getAsJsonArray("floorCells").map(CivsWorksiteLayouts::vector),
            floor.getAsJsonArray("torchCells").mapTo(linkedSetOf(), CivsWorksiteLayouts::vector),
            floor.getAsJsonArray("workSections").map { it.asJsonObject.let { part -> part["name"].asString to part["cells"].asInt } })
    } }
    val floors = if (version == 2) oldFloors else alignedFloors(oldFloors.size + 2)
    val lowest = floors.last().feetY
    val generator = if (version == VERSION) center.copy(x = center.x + forward.first * 21,
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
    } else buildList {
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
    }

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
        const val VERSION = 3
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
