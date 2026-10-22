package ru.arc.civs

import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentHashMap

/** The pinned mine geometry and its explicit V2 order. All coordinates here are schematic-local. */
internal class CivsMinePlan private constructor(layout: JsonObject) {
    data class Step(val point: CivPosition, val after: String?, val stage: String,
        val floor: Int? = null, val branch: String? = null, val shaftSupport: Boolean = false)
    data class Floor(val index: Int, val feetY: Int, val cells: List<CivPosition>,
        val torches: Set<CivPosition>, val sections: List<Pair<String, Int>>)

    val bottom = CivsWorksiteLayouts.vector(layout["ladderBottom"])
    val facing = layout["ladderFacing"].asString
    private val forward = when (facing) { "north" -> 0 to -1; "south" -> 0 to 1; "east" -> 1 to 0; else -> -1 to 0 }
    val backing = bottom.copy(x = bottom.x - forward.first, z = bottom.z - forward.second)
    val shaftColumns = layout.getAsJsonArray("shaftFootprint").mapTo(linkedSetOf()) {
        it.asJsonArray.let { p -> p[0].asInt to p[1].asInt }
    }
    val floors = layout.getAsJsonArray("floors").map { element -> element.asJsonObject.let { floor ->
        Floor(floor["index"].asInt, floor["feetY"].asInt,
            floor.getAsJsonArray("floorCells").map(CivsWorksiteLayouts::vector),
            floor.getAsJsonArray("torchCells").mapTo(linkedSetOf(), CivsWorksiteLayouts::vector),
            floor.getAsJsonArray("workSections").map { it.asJsonObject.let { part -> part["name"].asString to part["cells"].asInt } })
    } }
    val lowest = floors.last().feetY
    private val ladder = "minecraft:ladder[facing=$facing,waterlogged=false]"
    private val platforms = floors.flatMap { it.cells }.filterTo(hashSetOf()) { (it.x to it.z) in shaftColumns }

    init {
        require(layout["planVersion"].asInt == VERSION && shaftColumns.size == 9)
        require(floors.all { floor -> floor.sections.sumOf { it.second } == floor.cells.size })
    }

    val steps: List<Step> = buildList {
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
    }

    /** Only used to interpret the old saved prefix; never used as the new construction order. */
    private val legacySteps: List<Pair<CivPosition, String?>> = buildList {
        for (y in bottom.y - 1 downTo lowest) {
            add(backing.copy(y = y) to "minecraft:cobblestone")
            add(bottom.copy(y = y) to ladder)
        }
        for (floor in floors) {
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

    fun containsShaft(local: CivPosition): Boolean = local.y in lowest..bottom.y && (local.x to local.z) in shaftColumns

    companion object {
        const val VERSION = 2
        private val plans = ConcurrentHashMap<String, CivsMinePlan>()
        fun forBuilding(building: CivBuilding): CivsMinePlan? {
            plans[building.schematicId]?.let { return it }
            val layout = CivsWorksiteLayouts.site(building)?.getAsJsonObject("mine") ?: return null
            return plans.computeIfAbsent(building.schematicId) { CivsMinePlan(layout) }
        }

        fun shaftContains(building: CivBuilding, world: CivPosition): Boolean {
            val plan = forBuilding(building) ?: return false
            return plan.containsShaft(localPoint(building, world))
        }

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
