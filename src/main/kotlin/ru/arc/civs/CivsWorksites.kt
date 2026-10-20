package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.TileState
import org.bukkit.block.data.Ageable
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.UUID

/** One observed physical action, committed by the existing item/world receipt. No timed resource grant. */
data class CivWorldWork(
    val workerId: UUID, val cycle: Long, val buildingId: UUID, val standing: CivPosition,
    val changes: List<CivPlanBlock>, val inputs: Map<String, Long> = emptyMap(),
    val outputs: Map<String, Long> = emptyMap(), val durationMillis: Long = 3_000,
    val status: String, val miningStep: Long? = null,
    val equipmentAfter: List<CivResidentItem?>? = null,
    val focus: CivPosition? = null,
) {
    // Equipping a tool explicitly picks it up from storage; ordinary work can use carried supplies.
    fun storageInputs(worker: CivWorker): Map<String, Long> = if (equipmentAfter != null && changes.isEmpty()) inputs
        else CivsEquipment.storageInputs(worker, inputs)
}

/** Small live work cursor. Only real changed blocks and goods enter durable state, through CivsStorage. */
internal class CivsWorksites(private val plugin: Plugin, private val worldAccess: CivsWorld,
    private val stock: (Settlement, UUID) -> Map<String, Long>,
    private val locked: (UUID, CivPosition) -> Boolean) {
    private data class Cached(val building: CivBuilding, val worker: CivWorker, val work: CivWorldWork)
    private val cached = hashMapOf<UUID, Cached>()
    private val cursors = hashMapOf<UUID, Int>()
    private data class Need(val building: CivBuilding, val cycle: Long, val status: String,
        val material: String? = null, val amount: Long = 1, val point: CivPosition? = null,
        val reason: String? = null, val blockMaterial: String? = null)
    private val needs = hashMapOf<UUID, Need>()
    private data class WorkSpotSearch(val buildingId: UUID, val target: CivPosition, val allowOwn: Boolean,
        val ladder: Boolean, var next: Int = 0, var sawStanding: Boolean = false)
    private val workSpots = hashMapOf<UUID, WorkSpotSearch>()
    private data class ReportedNeed(val need: Need, val tick: Int)
    private val reportedNeeds = hashMapOf<UUID, ReportedNeed>()
    private var spotChecksRemaining = 0
    private var spotRaysRemaining = 0
    private var spotSearchPending = false
    private var cursor = 0
    private val mineCursors = hashMapOf<UUID, Triple<Int, Long, Int>>()

    fun plan(city: Settlement, worker: CivWorker): CivWorldWork? {
        val building = worker.assignment?.let(city::building) ?: return null
        return cached[worker.id]?.takeIf { it.worker.completedCycles == worker.completedCycles && it.building == building &&
            it.worker.items == worker.items && it.worker.inventory == worker.inventory }?.work
    }

    fun invalidate(workerId: UUID) { cached.remove(workerId); needs.remove(workerId); workSpots.remove(workerId) }

    /** One worker, 16 job candidates, 32 standing probes and eight short rays per shared pulse. */
    fun pulse(cities: Collection<Settlement>) {
        check(Bukkit.isPrimaryThread())
        val jobs = cities.flatMap { city -> city.workers.mapNotNull { worker ->
            val building = worker.assignment?.let(city::building)
            if (building?.type in TYPES && building?.phase == BuildingPhase.ACTIVE && city.pendingDeletion == null &&
                city.construction?.buildingId != building?.id) Triple(city, worker, requireNotNull(building)) else null
        } }
        val workerIds = jobs.mapTo(hashSetOf()) { it.second.id }
        cached.keys.retainAll(workerIds)
        needs.keys.retainAll(workerIds)
        workSpots.keys.retainAll(workerIds)
        reportedNeeds.keys.retainAll(workerIds)
        cursors.keys.retainAll(workerIds)
        mineCursors.keys.retainAll(jobs.mapTo(hashSetOf()) { it.third.id })
        if (jobs.isEmpty()) return
        val (city, worker, building) = jobs[Math.floorMod(cursor++, jobs.size)]
        if (plan(city, worker) != null) return
        val world = plugin.server.getWorld(city.worldId) ?: return
        if (world.time in 12_000L..23_000L || city.inventory == null) return
        spotChecksRemaining = 32
        spotRaysRemaining = 8
        spotSearchPending = false
        needs[worker.id] = Need(building, worker.completedCycles, if (building.type == BuildingType.QUARRY) "searching-work" else "waiting-growth")
        val work = equipTool(city, worker, building, world) ?: when (building.type) {
            BuildingType.FARM -> farm(city, worker, building, world)
            BuildingType.FORESTER -> forest(city, worker, building, world)
            BuildingType.QUARRY -> mine(city, worker, building, world)
            else -> null
        }
        if (work != null) {
            needs.remove(worker.id)
            workSpots.remove(worker.id)
            cached[worker.id] = Cached(building, worker, work)
        }
        reportWorksite(city, worker, building, work)
    }

    fun validate(city: Settlement, work: CivWorldWork) {
        val worker = city.workers.firstOrNull { it.id == work.workerId } ?: throw CivsRejection("worker-not-found")
        requireCivs(worker.assignment == work.buildingId && worker.completedCycles == work.cycle, "stale-delivery")
        requireCivs(plan(city, worker) == work, "stale-worksite")
        val building = city.building(work.buildingId) ?: throw CivsRejection("building-not-found")
        requireCivs(building.phase == BuildingPhase.ACTIVE && city.construction?.buildingId != building.id, "invalid-assignment")
        val world = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("world-unavailable")
        work.changes.forEach { cell ->
            val block = read(city, building, world, cell.position) ?: throw CivsRejection("protected-area")
            requireCivs(block.blockData.asString == cell.before, "stale-worksite")
            requireCivs(worldAccess.canModify(city.owner, block, Bukkit.createBlockData(cell.after).material), "lands-denied")
        }
    }

    fun idleReason(city: Settlement, worker: CivWorker): String {
        val building = worker.assignment?.let(city::building) ?: return "unassigned"
        val suffix = toolSuffix(building.type)
        return if (CivsEquipment.slots(worker)[4]?.material?.endsWith(suffix) != true) "waiting-tool"
        else currentNeed(city, worker)?.status ?: if (building.type == BuildingType.QUARRY) "searching-work" else "waiting-growth"
    }

    private fun currentNeed(city: Settlement, worker: CivWorker): Need? = needs[worker.id]?.takeIf {
        it.building == worker.assignment?.let(city::building) && it.cycle == worker.completedCycles
    }

    fun requirement(city: Settlement, worker: CivWorker): Pair<String, Long>? {
        val required = currentNeed(city, worker)?.let { need -> need.material?.let { it to need.amount } }
            ?: plan(city, worker)?.takeUnless { it.changes.isEmpty() && it.equipmentAfter != null }
                ?.inputs?.entries?.firstOrNull()?.let { it.key to it.value } ?: return null
        return required.first to maxOf(required.second, Material.matchMaterial(required.first)?.maxStackSize?.toLong() ?: 1)
    }

    private fun blocked(worker: CivWorker, status: String, material: String? = null, amount: Long = 1,
        point: CivPosition? = null, reason: String? = null, blockMaterial: String? = null): CivWorldWork? {
        needs[worker.id] = needs.getValue(worker.id).copy(status = status, material = material, amount = amount,
            point = point, reason = reason, blockMaterial = blockMaterial)
        return null
    }

    private fun reportWorksite(city: Settlement, worker: CivWorker, building: CivBuilding, work: CivWorldWork?) {
        val previous = reportedNeeds[worker.id]
        if (work != null) {
            if (reportedNeeds.remove(worker.id) != null) plugin.logger.info(CivsDiagnostics.line("worksite_recovered",
                "city" to city.id, "worker" to worker.id, "building" to building.id,
                "previous_reason" to previous?.need?.reason, "point" to work.focus, "standing" to work.standing))
            return
        }
        val need = needs[worker.id]?.takeIf { it.reason != null } ?: return
        val tick = Bukkit.getCurrentTick()
        if (previous != null && (previous.need.status == need.status && previous.need.point == need.point &&
                previous.need.reason == need.reason && previous.need.blockMaterial == need.blockMaterial || tick - previous.tick < 100)) return
        reportedNeeds[worker.id] = ReportedNeed(need, tick)
        plugin.logger.info(CivsDiagnostics.line("worksite_blocked", "city" to city.id, "worker" to worker.id,
            "building" to building.id, "cycle" to worker.completedCycles, "status" to need.status,
            "reason" to need.reason, "point" to need.point, "material" to (need.blockMaterial ?: "unknown")))
    }

    private fun equipTool(city: Settlement, worker: CivWorker, building: CivBuilding, world: World): CivWorldWork? {
        val slots = CivsEquipment.slots(worker)
        val suffix = toolSuffix(building.type)
        if (slots[4]?.material?.endsWith(suffix) == true) return null
        val stock = stock(city, building.id)
        val material = listOf("IRON", "COPPER", "STONE", "WOODEN", "DIAMOND", "NETHERITE", "GOLDEN")
            .map { it + suffix }.firstOrNull { (stock[it] ?: 0) > 0 } ?: return null
        val next = slots.toMutableList()
        next[4]?.let { previous ->
            val free = (CivsEquipment.BAG until CivsEquipment.SLOTS).firstOrNull { next[it] == null } ?: return null
            next[free] = previous
        }
        next[4] = CivResidentItem(material, 1)
        val standing = citizenStorageTarget(city, building, world) ?: return null
        return work(worker, building, standing, emptyList(), mapOf(material to 1), status = "waiting-tool", duration = 1_000)
            .copy(equipmentAfter = next)
    }

    private fun toolSuffix(type: BuildingType) = when (type) {
        BuildingType.FARM -> "_HOE"; BuildingType.FORESTER -> "_AXE"; else -> "_PICKAXE"
    }

    private fun farm(city: Settlement, worker: CivWorker, building: CivBuilding, world: World): CivWorldWork? {
        if (!tool(worker).type.name.endsWith("_HOE")) return null
        val selected = CivsProduction.material(building)
        val available by lazy { CivsEquipment.available(worker, stock(city, building.id)) }
        if (selected == "BREAD" && (available["WHEAT"] ?: 0) >= 3) {
            val standing = citizenStorageTarget(city, building, world) ?: return null
            return work(worker, building, standing, emptyList(), mapOf("WHEAT" to 3), mapOf("BREAD" to 1), "working-field")
        }
        val (crop, seed) = when (selected) {
            "CARROT" -> Material.CARROTS to "CARROT"
            "POTATO" -> Material.POTATOES to "POTATO"
            else -> Material.WHEAT to "WHEAT_SEEDS"
        }
        for (point in CivsWorksiteLayouts.irrigation(building)) {
            val block = read(city, building, world, point, worker = worker) ?: return null
            if (block.type == Material.WATER) continue
            if (block.type !in SOILS) return blocked(worker, "worksite-soil", point = point,
                reason = "irrigation-ground", blockMaterial = block.type.name)
            if ((available["WATER_BUCKET"] ?: 0) == 0L) return blocked(worker, "waiting-water", "WATER_BUCKET")
            val standing = beside(city, worker, building, world, point) ?: return null
            return work(worker, building, standing, listOf(change(block, Material.WATER.createBlockData().asString)),
                mapOf("WATER_BUCKET" to 1), mapOf("BUCKET" to 1), "working-field")
        }
        val workers = city.workers.filter { it.assignment == building.id }
        val index = workers.indexOfFirst { it.id == worker.id }
        val plots = CivsWorksiteLayouts.field(building).filterIndexed { i, _ -> i % workers.size == index }
        if (plots.isEmpty()) return null
        repeat(minOf(16, plots.size)) {
            val soilPoint = plots[Math.floorMod(cursors.getOrDefault(worker.id, 0), plots.size)]
            cursors[worker.id] = cursors.getOrDefault(worker.id, 0) + 1
            val soil = read(city, building, world, soilPoint) ?: return@repeat
            val top = read(city, building, world, soilPoint.copy(y = soilPoint.y + 1)) ?: return@repeat
            if (soil.type !in SOILS || top.type != crop && !top.type.isAir && top.type !in WEEDS) return@repeat
            val focus = if (soil.type == Material.FARMLAND) position(top) else soilPoint
            val standing = beside(city, worker, building, world, focus) ?: run {
                if (spotSearchPending) { cursors[worker.id] = cursors.getValue(worker.id) - 1; return null }
                return@repeat
            }
            if (soil.type != Material.FARMLAND) {
                if (top.type == crop) return@repeat
                return work(worker, building, standing,
                    listOf(change(soil, "minecraft:farmland[moisture=7]")) +
                        if (top.type in WEEDS) listOf(change(top, "minecraft:air")) else emptyList(), status = "working-field")
            }
            if (top.type == crop) {
                val age = top.blockData as? Ageable ?: return@repeat
                if (age.age != age.maximumAge) return@repeat
                val drops = drops(top, tool(worker)) .toMutableMap()
                val seedCount = drops[seed] ?: 0
                val replant = seedCount > 0 || (available[seed] ?: 0) > 0
                if (seedCount > 0) put(drops, seed, seedCount - 1)
                val inputs = if (replant && seedCount == 0L) mapOf(seed to 1L) else emptyMap()
                return work(worker, building, standing,
                    listOf(change(top, if (replant) crop.createBlockData().asString else "minecraft:air")), inputs, drops, "working-field")
            }
            if ((available[seed] ?: 0) > 0) return work(worker, building, standing,
                listOf(change(top, crop.createBlockData().asString)), mapOf(seed to 1), status = "working-field")
            blocked(worker, "waiting-seeds", seed)
        }
        return null
    }

    private fun forest(city: Settlement, worker: CivWorker, building: CivBuilding, world: World): CivWorldWork? {
        if (!tool(worker).type.name.endsWith("_AXE")) return null
        val layout = CivsWorksiteLayouts.site(building)?.getAsJsonObject("forester") ?: return null
        val box = layout.getAsJsonObject("searchBounds")
        val min = CivsWorksiteLayouts.vector(box["min"])
        val max = CivsWorksiteLayouts.vector(box["max"])
        val width = max.x - min.x + 1
        val depth = max.z - min.z + 1
        val timber = Material.valueOf(requireNotNull(CivsProduction.material(building)))
        val sapling = timber.name.removeSuffix("_LOG") + "_SAPLING"
        repeat(16) {
            val index = Math.floorMod(cursors.getOrDefault(worker.id, 0), width * depth)
            cursors[worker.id] = cursors.getOrDefault(worker.id, 0) + 1
            val column = CivsWorksiteLayouts.point(building, CivPosition(min.x + index % width, 0, min.z + index / width))
            if (!world.isChunkLoaded(column.x shr 4, column.z shr 4)) return@repeat
            // Surface queries stay in loaded columns; city buildings are never logging candidates.
            val surface = world.getHighestBlockYAt(column.x, column.z)
            val roots = (building.anchor.y - 4..minOf(surface, building.anchor.y + 5)).mapNotNull { y ->
                read(city, building, world, column.copy(y = y), allowOwn = false)?.takeIf { it.type == timber }
            }
            val root = roots.firstOrNull { base ->
                val below = read(city, building, world, CivPosition(base.x, base.y - 1, base.z), allowOwn = false)
                below?.type in TREE_SOILS
            }
            if (root != null) {
                val standing = beside(city, worker, building, world, position(root), allowOwn = false) ?: run {
                    if (spotSearchPending) { cursors[worker.id] = cursors.getValue(worker.id) - 1; return null }
                    return@repeat
                }
                val tree = ArrayDeque<Block>(); tree += root
                val logs = linkedMapOf<CivPosition, Block>()
                var leaves = false
                while (tree.isNotEmpty() && logs.size < 128) {
                    val log = tree.removeFirst()
                    val p = position(log)
                    if (p in logs || log.type != timber) continue
                    logs[p] = log
                    for (dx in -1..1) for (dy in 0..1) for (dz in -1..1) {
                        val q = CivPosition(p.x + dx, p.y + dy, p.z + dz)
                        if (q.y > root.y + 16) continue
                        val neighbor = read(city, building, world, q, allowOwn = false) ?: continue
                        if (neighbor.type.name == timber.name.removeSuffix("_LOG") + "_LEAVES" &&
                            (neighbor.blockData as? org.bukkit.block.data.type.Leaves)?.isPersistent == false) leaves = true
                        if (neighbor.type == timber && q !in logs) tree += neighbor
                    }
                }
                if (!leaves || tree.isNotEmpty()) return@repeat // Large trees remain intact rather than losing their inaccessible top.
                val outputs = linkedMapOf<String, Long>()
                logs.values.forEach { block -> drops(block, tool(worker)).forEach { (key, amount) -> outputs.merge(key, amount, Long::plus) } }
                val available = CivsEquipment.available(worker, stock(city, building.id))
                val roots = if (timber == Material.DARK_OAK_LOG) darkOakPlot(city, building, world, position(root), timber) else listOf(position(root))
                val replant = roots != null && (available[sapling] ?: 0) >= roots.size
                val changes = logs.values.sortedByDescending { it.y }.map { block ->
                    change(block, if (replant && position(block) in roots!!) Material.valueOf(sapling).createBlockData().asString else "minecraft:air")
                }
                return work(worker, building, standing, changes, if (replant) mapOf(sapling to roots!!.size.toLong()) else emptyMap(), outputs,
                    "felling-tree", duration = maxOf(6_000, logs.size * 1_000L))
            }
            // Fill genuine empty ground only, using a delivered sapling.
            val ground = read(city, building, world, column.copy(y = surface), allowOwn = false) ?: return@repeat
            if (ground.type !in TREE_SOILS) return@repeat
            val air = read(city, building, world, column.copy(y = surface + 1), allowOwn = false) ?: return@repeat
            if (!air.type.isAir) return@repeat
            val planting = if (timber == Material.DARK_OAK_LOG) darkOakPlot(city, building, world, position(air), Material.AIR)
                ?: return@repeat else listOf(position(air))
            if ((CivsEquipment.available(worker, stock(city, building.id))[sapling] ?: 0) < planting.size) {
                blocked(worker, "waiting-saplings", sapling, planting.size.toLong())
                return@repeat
            }
            val standing = beside(city, worker, building, world, position(air), allowOwn = false) ?: run {
                if (spotSearchPending) { cursors[worker.id] = cursors.getValue(worker.id) - 1; return null }
                return@repeat
            }
            return work(worker, building, standing, planting.map { p ->
                change(requireNotNull(read(city, building, world, p, allowOwn = false)), Material.valueOf(sapling).createBlockData().asString)
            }, mapOf(sapling to planting.size.toLong()), status = "felling-tree")
        }
        return null
    }

    /** Dark oak needs four paid saplings on a supported 2×2 square. */
    private fun darkOakPlot(city: Settlement, building: CivBuilding, world: World, root: CivPosition, material: Material): List<CivPosition>? {
        for (dx in listOf(0, -1)) for (dz in listOf(0, -1)) {
            val cells = (0..1).flatMap { x -> (0..1).map { z -> root.copy(x = root.x + dx + x, z = root.z + dz + z) } }
            if (cells.all { p -> read(city, building, world, p, allowOwn = false)?.type == material &&
                    read(city, building, world, p.copy(y = p.y - 1), allowOwn = false)?.type in TREE_SOILS }) return cells
        }
        return null
    }

    private fun mine(city: Settlement, worker: CivWorker, building: CivBuilding, world: World): CivWorldWork? {
        if (!tool(worker).type.name.endsWith("_PICKAXE")) return null
        val layout = CivsWorksiteLayouts.site(building)?.getAsJsonObject("mine")
            ?: return blocked(worker, "worksite-layout", point = building.anchor, reason = "mine-layout-missing")
        val bottom = CivsWorksiteLayouts.vector(layout["ladderBottom"])
        val floor = layout.getAsJsonArray("floors").last().asJsonObject["feetY"].asInt
        val facing = layout["ladderFacing"].asString
        val (dx, dz) = when (facing) { "north" -> 0 to 1; "south" -> 0 to -1; "east" -> -1 to 0; else -> 1 to 0 }
        // One permanent construction cursor per mine, shared by all assigned miners.
        val steps = buildList<Pair<CivPosition, String?>> {
            for (y in bottom.y - 1 downTo floor) {
                add(CivPosition(bottom.x + dx, y, bottom.z + dz) to "minecraft:cobblestone")
                add(CivPosition(bottom.x, y, bottom.z) to "minecraft:ladder[facing=$facing,waterlogged=false]")
            }
            for (level in layout.getAsJsonArray("floors")) {
                val cells = level.asJsonObject.getAsJsonArray("floorCells").map(CivsWorksiteLayouts::vector)
                // Landing and forward cells are reachable before their branches.
                val sorted = cells.sortedBy { kotlin.math.abs(it.x - bottom.x) + kotlin.math.abs(it.z - bottom.z) }
                for ((i, support) in sorted.withIndex()) {
                    for (height in 3 downTo 1) add(support.copy(y = support.y + height) to null)
                    add(support to "minecraft:cobblestone")
                    if (i % 8 == 0) add(support.copy(y = support.y + 1) to "minecraft:torch")
                }
            }
        }
        val progress = city.mineProgress.orEmpty()[building.id.toString()] ?: 0L
        val scan = mineCursors[building.id]?.takeIf { it.first == building.level && it.second == progress }
        val scanStart = (scan?.third ?: progress.toInt()).coerceAtMost(steps.size)
        val pending = steps.drop(scanStart)
        for ((offset, step) in pending.take(16).withIndex()) {
            val (local, after) = step
            val point = CivsWorksiteLayouts.point(building, local)
            val block = read(city, building, world, point, worker = worker) ?: return null
            val target = after?.let { Bukkit.createBlockData(it).also { data ->
                data.rotate(when (Math.floorMod(building.rotation / 90, 4)) {
                    1 -> org.bukkit.block.structure.StructureRotation.CLOCKWISE_90
                    2 -> org.bukkit.block.structure.StructureRotation.CLOCKWISE_180
                    3 -> org.bukkit.block.structure.StructureRotation.COUNTERCLOCKWISE_90
                    else -> org.bukkit.block.structure.StructureRotation.NONE
                })
            } }
            val next = scanStart.toLong() + offset + 1
            // Existing sound shaft walls already support a ladder; replacing them first can
            // deadlock a miner who must dig the first stone to obtain cobblestone.
            val shaftSupport = scanStart + offset < (bottom.y - floor) * 2 && local.y < bottom.y && local.x == bottom.x + dx && local.z == bottom.z + dz
            val soundWall = shaftSupport && target?.material == Material.COBBLESTONE &&
                block.type.isOccluding && !block.type.hasGravity() && block.type !in UNBREAKABLE
            if (soundWall || target?.matches(block.blockData) == true || target == null && block.type.isAir) {
                mineCursors[building.id] = Triple(building.level, progress, next.toInt())
                // Record skipped already-dug cells with the next actual work, without a save per empty cell.
                continue
            }
            if (block.isLiquid) return blocked(worker, "worksite-liquid", point = point,
                reason = "liquid", blockMaterial = block.type.name)
            if (block.type in UNBREAKABLE) return blocked(worker, "worksite-unbreakable", point = point,
                reason = "unbreakable", blockMaterial = block.type.name)
            val supportWork = target?.material?.takeIf { it in setOf(Material.COBBLESTONE, Material.LADDER, Material.TORCH) }
            // Excavation happens before placing the next ladder, even if supplies have
            // not arrived yet. Keep the same durable step until its ladder is installed.
            val excavating = target?.material == Material.LADDER && !block.type.isAir && block.type != Material.LADDER
            val costs = if (excavating) emptyMap() else supportWork?.let { mapOf(it.name to 1L) }.orEmpty()
            costs.keys.firstOrNull { (CivsEquipment.available(worker, stock(city, building.id))[it] ?: 0) < 1 }?.let { material ->
                return blocked(worker, "waiting-${material.lowercase()}", material)
            }
            val standing = beside(city, worker, building, world, point, ladder = true) ?: return null
            val outputs = if (block.type.isAir || block.type == Material.LADDER || block.type == Material.TORCH) emptyMap() else drops(block, tool(worker))
            return work(worker, building, standing, listOf(change(block, if (excavating) "minecraft:air" else target?.asString ?: "minecraft:air")), costs, outputs,
                "digging-mine", duration = 4_000, miningStep = if (excavating) next - 1 else next)
        }
        if (mineCursors[building.id]?.third == steps.size || scanStart == steps.size) blocked(worker, "mine-complete")
        return null
    }

    private fun requireCivs(condition: Boolean, reason: String) { if (!condition) throw CivsRejection(reason) }

    private fun read(city: Settlement, building: CivBuilding, world: World, point: CivPosition, allowOwn: Boolean = true,
        worker: CivWorker? = null): Block? {
        fun denied(status: String, reason: String, material: Material? = null): Block? {
            if (worker != null) blocked(worker, status, point = point, reason = reason, blockMaterial = material?.name)
            return null
        }
        if (!city.contains(point.x, point.z)) return denied("worksite-protected", "outside-city")
        if (point.y !in world.minHeight until world.maxHeight) return denied("worksite-protected", "outside-world-height")
        if (!world.isChunkLoaded(point.x shr 4, point.z shr 4)) return denied("worksite-unloaded", "chunk-unloaded")
        if (locked(city.worldId, point)) return denied("worksite-protected", "world-operation-locked")
        if (civsProtectedBuildings(city).any { (!allowOwn || it.id != building.id) &&
                (point.x in it.bounds.min.x..it.bounds.max.x && point.z in it.bounds.min.z..it.bounds.max.z ||
                    CivsWorksiteLayouts.isField(it, point.x, point.z)) }) return denied("worksite-protected", "other-building")
        val block = world.getBlockAt(point.x, point.y, point.z)
        if (block.getState(false) is TileState) return denied("worksite-obstacle", "block-entity", block.type)
        if (!world.worldBorder.isInside(block.location)) return denied("worksite-protected", "world-border", block.type)
        if (!worldAccess.canModify(city.owner, block)) return denied("worksite-protected", "protection-denied", block.type)
        return block
    }

    private fun beside(city: Settlement, worker: CivWorker, building: CivBuilding, world: World, target: CivPosition,
                       allowOwn: Boolean = true, ladder: Boolean = false): CivPosition? {
        val search = workSpots[worker.id]?.takeIf {
            it.buildingId == building.id && it.target == target && it.allowOwn == allowOwn && it.ladder == ladder
        } ?: WorkSpotSearch(building.id, target, allowOwn, ladder).also { workSpots[worker.id] = it }
        val offsets = if (ladder) LADDER_WORK_OFFSETS else WORK_OFFSETS
        fun pending(): CivPosition? {
            spotSearchPending = true
            blocked(worker, "searching-work")
            return null
        }
        while (search.next < offsets.size) {
            if (spotChecksRemaining == 0) return pending()
            spotChecksRemaining--
            val offset = offsets[search.next++]
            val coordinates = listOf(target.x.toLong() + offset.x, target.y.toLong() + offset.y, target.z.toLong() + offset.z)
            if (coordinates.any { it !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }) continue
            val p = CivPosition(coordinates[0].toInt(), coordinates[1].toInt(), coordinates[2].toInt())
            if (!city.contains(p.x, p.z) || !citizenControlClear(city, p) || locked(city.worldId, p) ||
                civsProtectedBuildings(city).any { (!allowOwn || it.id != building.id) &&
                    (p.x in it.bounds.min.x..it.bounds.max.x && p.z in it.bounds.min.z..it.bounds.max.z ||
                        CivsWorksiteLayouts.isField(it, p.x, p.z)) }) continue
            if (!citizenStandable(world, p)) continue
            if (offset.x == 0 && offset.z == 0 && world.getBlockAt(p.x, p.y, p.z).type != Material.LADDER) continue
            if (!citizenPacketBodyClear(world, CitizenPoint(p.x + .5, p.y.toDouble(), p.z + .5), false)) continue
            search.sawStanding = true
            if (spotRaysRemaining == 0) { search.next--; return pending() }
            spotRaysRemaining--
            if (citizenCanReachBlock(world, Location(world, p.x + .5, p.y + 1.62, p.z + .5), target)) {
                workSpots.remove(worker.id)
                return p
            }
        }
        workSpots.remove(worker.id)
        blocked(worker, "worksite-no-access", point = target,
            reason = if (search.sawStanding) "line-of-sight-blocked" else "no-supported-position",
            blockMaterial = world.getBlockAt(target.x, target.y, target.z).type.name)
        return null
    }

    private fun work(worker: CivWorker, building: CivBuilding, standing: CivPosition, changes: List<CivPlanBlock>,
        inputs: Map<String, Long> = emptyMap(), outputs: Map<String, Long> = emptyMap(), status: String,
        duration: Long = 3_000, miningStep: Long? = null): CivWorldWork {
        val slots = CivsEquipment.slots(worker)
        val hand = slots[4]?.stack()
        val meta = hand?.itemMeta as? org.bukkit.inventory.meta.Damageable
        val after = if (meta != null && changes.isNotEmpty() && !meta.isUnbreakable) {
            val unbreaking = hand.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.UNBREAKING).coerceAtLeast(0)
            val uses = changes.count { !Bukkit.createBlockData(it.before).material.isAir }
            val wear = (0 until uses).count { java.util.concurrent.ThreadLocalRandom.current().nextLong(unbreaking.toLong() + 1) == 0L }
            val damage = meta.damage + wear
            val worn = if (damage >= hand.type.maxDurability.toInt()) null else {
                meta.damage = damage
                hand.itemMeta = meta
                CivResidentItem.of(hand)
            }
            slots.toMutableList().also { it[4] = worn }
        } else null
        return CivWorldWork(worker.id, worker.completedCycles, building.id, standing, changes, inputs, outputs,
            if (status == "working-field") 1_500 else duration, status, miningStep, after,
            changes.minByOrNull { cell ->
                val dx = cell.position.x.toDouble() - standing.x
                val dy = cell.position.y.toDouble() - standing.y
                val dz = cell.position.z.toDouble() - standing.z
                dx * dx + dy * dy + dz * dz
            }?.position)
    }

    private fun drops(block: Block, tool: ItemStack): Map<String, Long> = block.getDrops(tool).filter { !it.type.isAir }
        .groupingBy { it.type.name }.fold(0L) { count, item -> count + item.amount }
    private fun tool(worker: CivWorker): ItemStack = CivsEquipment.slots(worker)[4]?.stack() ?: ItemStack(Material.AIR)
    private fun change(block: Block, after: String) = CivPlanBlock(position(block), block.blockData.asString, after)
    private fun position(block: Block) = CivPosition(block.x, block.y, block.z)
    private fun put(map: MutableMap<String, Long>, key: String, value: Long) { if (value > 0) map[key] = value else map.remove(key) }

    companion object {
        val TYPES = setOf(BuildingType.FARM, BuildingType.FORESTER, BuildingType.QUARRY)
        private val SOILS = setOf(Material.DIRT, Material.GRASS_BLOCK, Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.FARMLAND)
        private val TREE_SOILS = SOILS + Material.PODZOL + Material.MYCELIUM + Material.MOSS_BLOCK
        private val WEEDS = setOf(Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN)
        private val UNBREAKABLE = setOf(Material.BEDROCK, Material.BARRIER, Material.END_PORTAL_FRAME, Material.END_PORTAL, Material.NETHER_PORTAL)
        // Keep the four old adjacent sides first, then examine the rest of the two-block
        // ring. These 80 integer standing centres already satisfy the adult eye reach.
        private val WORK_OFFSETS = buildList {
            val adjacent = listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)
            val wider = (listOf(2 to 0, 0 to 2, -2 to 0, 0 to -2) +
                (-2..2).flatMap { x -> (-2..2).map { z -> x to z } })
                .distinct().filter { it != (0 to 0) && it !in adjacent }
            for (ring in listOf(adjacent, wider)) for (dy in listOf(0, 1, -1, -2)) for ((dx, dz) in ring)
                if (dx * dx + dz * dz + (dy + 1.12) * (dy + 1.12) <= CITIZEN_BLOCK_REACH * CITIZEN_BLOCK_REACH)
                    add(CivPosition(dx, dy, dz))
        }
        private val LADDER_WORK_OFFSETS = listOf(1, 0, -1, -2).map { CivPosition(0, it, 0) } + WORK_OFFSETS
    }
}
