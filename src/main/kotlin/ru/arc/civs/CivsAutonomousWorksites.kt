package ru.arc.civs

import java.util.concurrent.ConcurrentHashMap

/** Native scenery belongs to the paid Auto worksite; economic sessions retain their own receipt. */
internal object CivsAutonomousWorksites {
    val TYPES = setOf(BuildingType.FARM, BuildingType.QUARRY)

    fun supports(city: Settlement, building: CivBuilding): Boolean =
        city.autonomy != null && city.inventory == null && building.type in TYPES &&
            CivsWorksiteLayouts.site(building)?.let { site ->
                if (building.type == BuildingType.FARM) site.has("farm") else CivsMinePlan.forBuilding(building) != null
            } == true

    fun visual(city: Settlement, building: CivBuilding, work: CivWorldWork): CivWorldWork =
        if (!supports(city, building)) work else work.copy(inputs = emptyMap(), outputs = emptyMap(),
            equipmentAfter = null, autonomousVisual = true).also { validate(city, it) }

    /** Natural growth may continue after a sealed visual write. An exact untouched preimage still wins. */
    fun nativeAfterMatches(cell: CivPlanBlock, actual: String): Boolean {
        if (actual == cell.after) return true
        if (actual == cell.before) return false
        val expectedCrop = cropState.matchEntire(cell.after)
        val actualCrop = cropState.matchEntire(actual)
        if (expectedCrop != null && actualCrop != null)
            return expectedCrop.groupValues[1] == actualCrop.groupValues[1] &&
                actualCrop.groupValues[2].toInt() >= expectedCrop.groupValues[2].toInt()
        return cell.after == "minecraft:farmland[moisture=7]" && farmlandState.matches(actual)
    }

    /** Pure receipt boundary. Live access, native before states and occupancy remain the existing owners' checks. */
    fun validate(city: Settlement, work: CivWorldWork) {
        val building = city.building(work.buildingId) ?: throw CivsRejection("building-not-found")
        val worker = city.workers.firstOrNull { it.id == work.workerId } ?: throw CivsRejection("worker-not-found")
        requireWork(supports(city, building) && city.autonomy?.paused == false && city.pendingDeletion == null &&
            building.phase == BuildingPhase.ACTIVE && city.construction?.buildingId != building.id)
        requireWork(worker.assignment == building.id && worker.completedCycles == work.cycle && worker.cargo == null)
        requireWork(!CivsResidents.isChild(worker))
        requireWork(work.autonomousVisual && work.inputs.isEmpty() && work.outputs.isEmpty() && work.equipmentAfter == null &&
            work.forestry == null && work.upkeep == null && work.changes.size in 1..128 && work.durationMillis > 0)
        requireWork(work.changes.map { it.position }.distinct().size == work.changes.size)
        val domain = domain(building)
        requireWork(work.changes.all { cell ->
            city.contains(cell.position.x, cell.position.z) && local(building, cell.position) in domain.cells &&
                cell.before != cell.after
        })
        if (building.type == BuildingType.FARM) {
            requireWork(work.miningStep == null && work.miningPlanVersion == null && !work.miningGenerator)
            requireWork(work.status in setOf("working-field", "watering", "fertilizing"))
            requireWork(work.changes.all { fieldChangeAllowed(building, work.status, it) })
        } else {
            val plan = CivsMinePlan.forBuilding(building) ?: throw CivsRejection("worksite-layout")
            requireWork(work.status in setOf("digging-mine", "securing-mine") && work.miningPlanVersion == plan.version &&
                work.miningStep?.let { it in 0L..plan.steps.size.toLong() } == true)
            val allowed = setOf("AIR", "COBBLESTONE", "LADDER", "TORCH") + plan.structuralMaterials
            requireWork(work.changes.all { cell -> material(cell.after) in allowed ||
                work.miningGenerator && material(cell.after) == "STONE" && local(building, cell.position) == plan.generator })
            requireWork(work.changes.none { material(it.after) == "AIR" && material(it.before) in
                setOf("WATER", "LAVA", "BEDROCK", "BARRIER", "END_PORTAL_FRAME", "END_PORTAL", "NETHER_PORTAL") })
        }
    }

    private fun fieldChangeAllowed(building: CivBuilding, status: String, cell: CivPlanBlock): Boolean {
        val point = local(building, cell.position)
        val farm = requireNotNull(CivsWorksiteLayouts.site(building)?.getAsJsonObject("farm"))
        fun contains(key: String, target: CivPosition) = farm.getAsJsonArray(key).any { CivsWorksiteLayouts.vector(it) == target }
        val before = material(cell.before)
        if (contains("irrigationCells", point)) return status == "working-field" && before in SOILS && cell.after == "minecraft:water[level=0]"
        if (contains("soilCells", point)) return before in SOILS && cell.after == "minecraft:farmland[moisture=7]"
        if (!contains("soilCells", point.copy(y = point.y - 1))) return false
        val crop = when (CivsProduction.material(building)) { "CARROT" -> "carrots"; "POTATO" -> "potatoes"; else -> "wheat" }
        return if (status == "watering" || status == "fertilizing") (0..6).any { age ->
            cell.before == "minecraft:$crop[age=$age]" && cell.after == "minecraft:$crop[age=${age + 1}]"
        }
        else cell.after == "minecraft:$crop[age=0]" && (before == "AIR" || before in WEEDS || cell.before == "minecraft:$crop[age=7]") ||
            cell.after == "minecraft:air" && before in WEEDS
    }

    /** Exact column runs include the existing mine safety shell, without protecting the empty gaps between branches. */
    fun protectionBounds(building: CivBuilding): List<CivBox> = domain(building).boxes.map { box ->
        val a = CivsWorksiteLayouts.point(building, box.min)
        val b = CivsWorksiteLayouts.point(building, box.max)
        CivBox(CivPosition(minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z)),
            CivPosition(maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z)))
    }

    private data class Domain(val cells: Set<CivPosition>, val boxes: List<CivBox>)
    private val domains = ConcurrentHashMap<Pair<String, Int>, Domain>()

    private fun domain(building: CivBuilding): Domain = domains.computeIfAbsent(building.schematicId to CivsMinePlan.VERSION) {
        val cells = linkedSetOf<CivPosition>()
        if (building.type == BuildingType.FARM) {
            val farm = CivsWorksiteLayouts.site(building)?.getAsJsonObject("farm")
            for (key in listOf("soilCells", "irrigationCells")) farm?.getAsJsonArray(key)?.forEach { value ->
                val soil = CivsWorksiteLayouts.vector(value)
                cells += soil
                cells += soil.copy(y = soil.y + 1)
            }
        } else if (building.type == BuildingType.QUARRY) {
            val plan = CivsMinePlan.forBuilding(building) ?: throw CivsRejection("worksite-layout")
            cells += plan.steps.map { it.point }
            val sides = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
            // Same possible native shell as CivsWorksites.mineFrontier; fluid-only neighbours are never written.
            for ((x, z) in plan.shaftColumns) for (y in plan.lowest..plan.bottom.y) {
                cells += CivPosition(x, y, z)
                for ((dx, dz) in sides) cells += CivPosition(x + dx, y, z + dz)
            }
            for (floor in listOfNotNull(plan.entranceLanding) + plan.floors) {
                val columns = floor.cells.mapTo(hashSetOf()) { it.x to it.z }
                for (support in floor.cells) {
                    for (height in 0..floor.height + 1) cells += support.copy(y = support.y + height)
                    for ((dx, dz) in sides) {
                        val x = support.x + dx; val z = support.z + dz
                        if ((x to z) in plan.shaftColumns || (x to z) in columns) continue
                        for (height in 1..floor.height) cells += CivPosition(x, support.y + height, z)
                    }
                }
            }
        }
        requireWork(cells.isNotEmpty())
        val boxes = cells.groupBy { it.x to it.z }.flatMap { (column, values) ->
            val ys = values.map { it.y }.sorted()
            buildList {
                var low = ys.first(); var high = low
                for (y in ys.drop(1)) {
                    if (y == high + 1) high = y else {
                        add(CivBox(CivPosition(column.first, low, column.second), CivPosition(column.first, high, column.second)))
                        low = y; high = y
                    }
                }
                add(CivBox(CivPosition(column.first, low, column.second), CivPosition(column.first, high, column.second)))
            }
        }
        Domain(cells, boxes)
    }

    private fun local(building: CivBuilding, point: CivPosition): CivPosition = CivsMinePlan.localPoint(building, point)
    private fun material(state: String): String = state.substringBefore('[').removePrefix("minecraft:").uppercase()
    private val SOILS = setOf("DIRT", "GRASS_BLOCK", "COARSE_DIRT", "ROOTED_DIRT", "FARMLAND")
    private val WEEDS = setOf("SHORT_GRASS", "TALL_GRASS", "FERN", "LARGE_FERN")
    private val cropState = Regex("minecraft:(wheat|carrots|potatoes)\\[age=([0-7])]")
    private val farmlandState = Regex("minecraft:farmland\\[moisture=[0-7]]")
    private fun requireWork(condition: Boolean) { if (!condition) throw CivsRejection("stale-worksite") }
}
