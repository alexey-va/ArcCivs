package ru.arc.civs

import java.util.UUID

/** One low, posted enclosure for the two reviewed rack-only annexes, inside their paid frame. */
internal object CivsWarehouseRefit {
    const val MAX_CHANGES = 384
    private const val LEFT = "civs/v2/farthest-frontier/craftsmanship-masonry-leftwarehouse/98bde3ab878856d62340/level"
    private const val RIGHT = "civs/v2/farthest-frontier/craftsmanship-masonry-rightwarehouse/83d8dec00947c5c36ed9/level"
    private val air = setOf("minecraft:air", "minecraft:cave_air", "minecraft:void_air")

    fun supported(building: CivBuilding): Boolean = building.type == BuildingType.WAREHOUSE &&
        building.level in 1..3 && building.schematicId in setOf(LEFT + building.level, RIGHT + building.level) &&
        building.bounds.max.y.toLong() - building.bounds.min.y == 4L &&
        listOf(building.bounds.max.x.toLong() - building.bounds.min.x + 1,
            building.bounds.max.z.toLong() - building.bounds.min.z + 1).sorted() == listOf(7L, 23L)

    fun validReference(building: CivBuilding): Boolean = building.warehouseCaptureId == null ||
        supported(building) && building.phase == BuildingPhase.ACTIVE && building.warehouseCaptureId != building.id

    fun retainedPlanId(building: CivBuilding): UUID = building.warehouseCaptureId ?: CivsGateRefit.retainedPlanId(building)
    fun captured(plan: CivPlacementPlan): Boolean = supported(plan.building) && plan.building.warehouseCaptureId == plan.id
    fun matchesCapture(building: CivBuilding, plan: CivPlacementPlan?): Boolean = plan != null && captured(plan) &&
        building.warehouseCaptureId == plan.id &&
        plan.building.copy(control = building.control, production = building.production) == building

    /** Pure profile. Routes and lids remove only their exact body cells; no ground or holder is replaced. */
    fun shape(building: CivBuilding, before: List<CivPlanBlock>, routes: List<List<CivPosition>>,
        containers: List<CivRelocatedContainer>): List<CivPlanBlock> {
        require(supported(building) && validReference(building))
        val box = building.bounds
        val actual = before.associate { it.position to it.before }
        require(before.size == 805 && actual.size == before.size && before.all {
            it.before == it.after && box.contains(it.position.x, it.position.y, it.position.z)
        })
        require(routes.size in 1..32 && routes.all { route -> route.size in 1..64 && route.zipWithNext().all { (a, b) ->
            kotlin.math.abs(a.x.toLong() - b.x) + kotlin.math.abs(a.z.toLong() - b.z) == 1L &&
                kotlin.math.abs(a.y.toLong() - b.y) <= 1L
        } } && routes.flatten().distinct().size <= 512)
        require(routes.flatten().filter { box.contains(it.x, it.y, it.z) }.all { it.y + 1 < box.max.y })
        require(containers.size in 1..32 && containers.map { it.position }.distinct().size == containers.size)
        require(containers.map { it.position }.toSet() == before.filter {
            it.before.substringBefore('[').removePrefix("minecraft:").uppercase() in CIVS_STORAGE_TYPES
        }.mapTo(hashSetOf()) { it.position })
        val protected = hashSetOf<CivPosition>()
        routes.flatten().forEach { p -> for (dy in -1..1) protected += p.copy(y = p.y + dy) }
        containers.forEach { holder ->
            require(box.contains(holder.position.x, holder.position.y, holder.position.z) &&
                holder.position.y in box.min.y + 1..box.max.y - 2 &&
                holder.type in CIVS_STORAGE_TYPES && holder.blockData == actual[holder.position] &&
                civsRetainContainerState(holder.blockData) && holder.before.size == STORAGE_SLOTS && holder.before == holder.after)
            holder.before.forEach { it?.validated() }
            protected += holder.position
            if (holder.type != "BARREL") protected += holder.position.copy(y = holder.position.y + 1)
        }
        building.control?.let { protected += it }
        val alongX = box.max.x - box.min.x == 22
        val entrance = building.entrance
        val entry = if (alongX) entrance.x - box.min.x else entrance.z - box.min.z
        val front = if (alongX) entrance.z else entrance.x
        val minimum = if (alongX) box.min.z else box.min.x
        val maximum = if (alongX) box.max.z else box.max.x
        require(entry in 3..19 && front in setOf(minimum - 1, maximum + 1) && entrance.y == box.min.y + 1)
        val lowFront = front < minimum
        fun point(u: Int, y: Int, v: Int): CivPosition {
            val across = if (lowFront) minimum + v else maximum - v
            return if (alongX) CivPosition(box.min.x + u, box.min.y + y, across)
                else CivPosition(across, box.min.y + y, box.min.z + u)
        }
        val posts = sortedSetOf(0, entry - 2, entry + 2, 22)
        posts.toList().zipWithNext().forEach { (a, b) -> if (b - a > 7) posts += (a + b) / 2 }
        val additions = linkedMapOf<CivPosition, String>()
        fun add(u: Int, y: Int, v: Int, state: String) {
            val p = point(u, y, v)
            if (p in protected) return
            val old = actual.getValue(p)
            require(old in air || old == state) { "Warehouse enclosure intersects a retained native block" }
            additions[p] = state
        }
        // Stone feet carry continuous timber headers; a three-wide entry remains open below its header.
        for (u in 0..22) for (v in 0..6) if (u in setOf(0, 22) || v in setOf(0, 6)) {
            val post = u in posts && v in setOf(0, 6)
            if (post) require((1..3).none { point(u, it, v) in protected }) { "Warehouse post intersects protected access" }
            val entryBody = v == 0 && u in entry - 1..entry + 1
            if (!entryBody) {
                add(u, 1, v, "minecraft:stone_bricks")
                add(u, 2, v, if (post) "minecraft:dark_oak_log[axis=y]" else "minecraft:glass")
            }
            add(u, 3, v, "minecraft:dark_oak_log[axis=${if (post) "y" else if (v in setOf(0, 6)) {
                if (alongX) "x" else "z"
            } else if (alongX) "z" else "x"}]")
        }
        for (u in 0..22) for (v in 0..6) {
            val facing = if (alongX) {
                if ((v == 0) == lowFront) "south" else "north"
            } else if ((v == 0) == lowFront) "east" else "west"
            add(u, 4, v, if (v in setOf(0, 6))
                "minecraft:dark_oak_stairs[facing=$facing,half=bottom,shape=straight,waterlogged=false]"
            else "minecraft:dark_oak_slab[type=top,waterlogged=false]")
        }
        val result = before.map { it.copy(after = additions[it.position] ?: it.before) }
        require(result.count { it.before != it.after } <= MAX_CHANGES)
        require(result.filter { it.before != it.after }.all { it.before in air && it.position.y > box.min.y && it.position !in protected })
        return result
    }

    fun proposal(city: Settlement, source: CivPlacementPlan, before: List<CivPlanBlock>,
        routes: List<List<CivPosition>>, containers: List<CivRelocatedContainer>, captureId: UUID): CivPlacementPlan {
        val current = city.building(source.building.id) ?: error("Warehouse is missing")
        require(supported(current) && current.phase == BuildingPhase.ACTIVE && city.autonomy != null &&
            city.pendingDeletion == null && city.construction?.buildingId != current.id &&
            current.id !in city.constructionQueue.orEmpty() && city.pendingControls.orEmpty().none { it.buildingId == current.id })
        require(source.cityId == city.id && source.worldId == city.worldId && source.id == retainedPlanId(current) &&
            supported(source.building) && source.building.level <= current.level &&
            source.building.schematicId.substringBeforeLast('/') == current.schematicId.substringBeforeLast('/') &&
            source.building.bounds == current.bounds && source.building.anchor == current.anchor &&
            source.building.rotation == current.rotation && source.building.entrance == current.entrance &&
            captureId != source.id && captureId != current.id && !source.adminPlacement)
        val target = current.copy(warehouseCaptureId = captureId)
        val cells = shape(current, before, routes, containers).filterNot { it.position == current.control }
        return source.copy(id = captureId, building = target, blocks = cells, capturedSha256 = null)
    }

    fun schematic(plan: CivPlacementPlan, dataVersion: Int): ByteArray {
        require(captured(plan))
        val cells = plan.blocks.associate { it.position to it.after }.toMutableMap()
        plan.building.control?.let { cells[it] = "minecraft:structure_void" }
        return civsGeneratedSchematic(plan.building.bounds, plan.building.anchor, cells, dataVersion)
    }

    fun proof(receipt: CivWarehouseRefitReceipt): String = CivsGateRefit.sha256(buildString {
        append(receipt.beforeBuilding); append(receipt.source.id); append(receipt.source.capturedSha256)
        receipt.beforeCells.forEach { append(it) }
        receipt.routes.forEach { append(it) }
        receipt.containers.forEach { append(it) }
        receipt.replacement.blocks.forEach { append(it) }
    }.toByteArray(Charsets.UTF_8))
}

/** A no-item-change receipt in the existing journal; publication only selects the immutable new capture. */
data class CivWarehouseRefitReceipt(
    val beforeBuilding: CivBuilding,
    val source: CivPlacementPlan,
    val replacement: CivPlacementPlan,
    val beforeCells: List<CivPlanBlock>,
    val routes: List<List<CivPosition>>,
    val containers: List<CivRelocatedContainer>,
) {
    fun before(after: Settlement): Settlement = after.copy(buildings = after.buildings.map {
        if (it.id == beforeBuilding.id) beforeBuilding else it
    }, revision = after.revision - 1)
    fun after(before: Settlement): Settlement = before.copy(buildings = before.buildings.map {
        if (it.id == beforeBuilding.id) replacement.building else it
    }, revision = Math.addExact(before.revision, 1))
    fun changes(): List<CivPlanBlock> = replacement.blocks.filter { it.before != it.after }
    fun validated(after: Settlement) {
        require(beforeBuilding != null && source != null && replacement != null && beforeCells != null && routes != null && containers != null)
        val before = before(after)
        CivsRules.validate(before)
        require(before.building(beforeBuilding.id) == beforeBuilding && after(before) == after &&
            source.capturedSha256?.matches(Regex("[0-9a-f]{64}")) == true &&
            replacement.capturedSha256?.matches(Regex("[0-9a-f]{64}")) == true)
        require(CivsWarehouseRefit.proposal(before, source, beforeCells, routes, containers, replacement.id) == replacement.copy(capturedSha256 = null))
        require(changes().size in 1..CivsWarehouseRefit.MAX_CHANGES)
    }
    fun detached(): CivWarehouseRefitReceipt {
        fun plan(p: CivPlacementPlan) = p.copy(building = p.building.copy(transit = p.building.transit?.toList()),
            blocks = p.blocks.toList(), terrain = p.terrain?.let { it.copy(blocks = it.blocks.toList(), route = it.route.toList(),
                waterFoundations = it.waterFoundations?.toList()) })
        return copy(source = plan(source), replacement = plan(replacement), beforeCells = beforeCells.toList(),
            routes = routes.map { it.toList() }, containers = containers.map { it.copy(before = it.before.toList(), after = it.after.toList()) })
    }
}
