package ru.arc.civs

import org.bukkit.Bukkit
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
)

/** Small live work cursor. Only real changed blocks and goods enter durable state, through CivsStorage. */
internal class CivsWorksites(private val plugin: Plugin, private val worldAccess: CivsWorld,
    private val stock: (Settlement, UUID) -> Map<String, Long>,
    private val locked: (UUID, CivPosition) -> Boolean) {
    private data class Cached(val building: CivBuilding, val worker: CivWorker, val work: CivWorldWork)
    private val cached = hashMapOf<UUID, Cached>()
    private val cursors = hashMapOf<UUID, Int>()
    private var cursor = 0
    private val mineCursors = hashMapOf<UUID, Triple<Int, Long, Int>>()

    fun plan(city: Settlement, worker: CivWorker): CivWorldWork? {
        val building = worker.assignment?.let(city::building) ?: return null
        return cached[worker.id]?.takeIf { it.worker.completedCycles == worker.completedCycles && it.building == building &&
            it.worker.items == worker.items && it.worker.inventory == worker.inventory }?.work
    }

    fun invalidate(workerId: UUID) { cached.remove(workerId) }

    /** At most one worker and 16 terrain candidates per pulse, shared across the whole runtime. */
    fun pulse(cities: Collection<Settlement>) {
        check(Bukkit.isPrimaryThread())
        val jobs = cities.flatMap { city -> city.workers.mapNotNull { worker ->
            val building = worker.assignment?.let(city::building)
            if (building?.type in TYPES && building?.phase == BuildingPhase.ACTIVE && city.pendingDeletion == null &&
                city.construction?.buildingId != building?.id) Triple(city, worker, requireNotNull(building)) else null
        } }
        val workerIds = jobs.mapTo(hashSetOf()) { it.second.id }
        cached.keys.retainAll(workerIds)
        cursors.keys.retainAll(workerIds)
        mineCursors.keys.retainAll(jobs.mapTo(hashSetOf()) { it.third.id })
        if (jobs.isEmpty()) return
        val (city, worker, building) = jobs[Math.floorMod(cursor++, jobs.size)]
        if (plan(city, worker) != null) return
        val world = plugin.server.getWorld(city.worldId) ?: return
        if (world.time in 12_000L..23_000L || city.inventory == null) return
        val work = equipTool(city, worker, building, world) ?: when (building.type) {
            BuildingType.FARM -> farm(city, worker, building, world)
            BuildingType.FORESTER -> forest(city, worker, building, world)
            BuildingType.QUARRY -> mine(city, worker, building, world)
            else -> null
        }
        if (work != null) cached[worker.id] = Cached(building, worker, work)
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
        return if (CivsEquipment.slots(worker)[4]?.material?.endsWith(suffix) != true) "waiting-tool" else "waiting-growth"
    }

    private fun equipTool(city: Settlement, worker: CivWorker, building: CivBuilding, world: World): CivWorldWork? {
        val slots = CivsEquipment.slots(worker)
        val suffix = toolSuffix(building.type)
        if (slots[4]?.material?.endsWith(suffix) == true) return null
        val stock = stock(city, building.id)
        val material = listOf("IRON", "STONE", "WOODEN", "DIAMOND", "NETHERITE", "GOLDEN")
            .map { it + suffix }.firstOrNull { (stock[it] ?: 0) > 0 } ?: return null
        val next = slots.toMutableList()
        next[4]?.let { previous ->
            val free = (CivsEquipment.BAG until CivsEquipment.SLOTS).firstOrNull { next[it] == null } ?: return null
            next[free] = previous
        }
        next[4] = CivResidentItem(material, 1)
        val standing = beside(city, building, world, requireNotNull(building.control)) ?: return null
        return work(worker, building, standing, emptyList(), mapOf(material to 1), status = "waiting-tool", duration = 1_000)
            .copy(equipmentAfter = next)
    }

    private fun toolSuffix(type: BuildingType) = when (type) {
        BuildingType.FARM -> "_HOE"; BuildingType.FORESTER -> "_AXE"; else -> "_PICKAXE"
    }

    private fun farm(city: Settlement, worker: CivWorker, building: CivBuilding, world: World): CivWorldWork? {
        if (!tool(worker).type.name.endsWith("_HOE")) return null
        val selected = CivsProduction.material(building)
        val available by lazy { stock(city, building.id) }
        if (selected == "BREAD" && (available["WHEAT"] ?: 0) >= 3) {
            val standing = beside(city, building, world, requireNotNull(building.control)) ?: return null
            return work(worker, building, standing, emptyList(), mapOf("WHEAT" to 3), mapOf("BREAD" to 1), "working-field")
        }
        val (crop, seed) = when (selected) {
            "CARROT" -> Material.CARROTS to "CARROT"
            "POTATO" -> Material.POTATOES to "POTATO"
            else -> Material.WHEAT to "WHEAT_SEEDS"
        }
        for (point in CivsWorksiteLayouts.irrigation(building)) {
            val block = read(city, building, world, point) ?: return null
            if (block.type == Material.WATER) continue
            if (block.type !in SOILS || (available["WATER_BUCKET"] ?: 0) == 0L) return null
            val standing = beside(city, building, world, point) ?: return null
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
            val standing = beside(city, building, world, soilPoint) ?: return@repeat
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
                val standing = beside(city, building, world, position(root), allowOwn = false) ?: return@repeat
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
                val available = stock(city, building.id)
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
            if (ground.type !in TREE_SOILS || (stock(city, building.id)[sapling] ?: 0) <= 0) return@repeat
            val air = read(city, building, world, column.copy(y = surface + 1), allowOwn = false) ?: return@repeat
            if (!air.type.isAir) return@repeat
            val planting = if (timber == Material.DARK_OAK_LOG) darkOakPlot(city, building, world, position(air), Material.AIR)
                ?: return@repeat else listOf(position(air))
            if ((stock(city, building.id)[sapling] ?: 0) < planting.size) return@repeat
            val standing = beside(city, building, world, position(air), allowOwn = false) ?: return@repeat
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
        val layout = CivsWorksiteLayouts.site(building)?.getAsJsonObject("mine") ?: return null
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
            val block = read(city, building, world, point) ?: return null
            val target = after?.let { Bukkit.createBlockData(it).also { data ->
                data.rotate(when (Math.floorMod(building.rotation / 90, 4)) {
                    1 -> org.bukkit.block.structure.StructureRotation.CLOCKWISE_90
                    2 -> org.bukkit.block.structure.StructureRotation.CLOCKWISE_180
                    3 -> org.bukkit.block.structure.StructureRotation.COUNTERCLOCKWISE_90
                    else -> org.bukkit.block.structure.StructureRotation.NONE
                })
            } }
            val next = scanStart.toLong() + offset + 1
            if (target?.matches(block.blockData) == true || target == null && block.type.isAir) {
                mineCursors[building.id] = Triple(building.level, progress, next.toInt())
                // Record skipped already-dug cells with the next actual work, without a save per empty cell.
                continue
            }
            if (block.isLiquid || block.type in UNBREAKABLE || block.type.name.endsWith("_ORE") && tool(worker).type == Material.AIR) return null
            val supportWork = target?.material?.takeIf { it in setOf(Material.COBBLESTONE, Material.LADDER, Material.TORCH) }
            val costs = supportWork?.let { mapOf(it.name to 1L) }.orEmpty()
            if (costs.any { (key, amount) -> (stock(city, building.id)[key] ?: 0) < amount }) return null
            val near = if (local.x == bottom.x && local.z == bottom.z)
                CivsWorksiteLayouts.point(building, local.copy(y = local.y + 1)) else point
            val standing = beside(city, building, world, near, ladder = true) ?: return null
            val outputs = if (block.type.isAir || block.type == Material.LADDER || block.type == Material.TORCH) emptyMap() else drops(block, tool(worker))
            return work(worker, building, standing, listOf(change(block, target?.asString ?: "minecraft:air")), costs, outputs,
                "digging-mine", duration = 4_000, miningStep = next)
        }
        return null
    }

    private fun requireCivs(condition: Boolean, reason: String) { if (!condition) throw CivsRejection(reason) }

    private fun read(city: Settlement, building: CivBuilding, world: World, point: CivPosition, allowOwn: Boolean = true): Block? {
        if (!city.contains(point.x, point.z) || point.y !in world.minHeight until world.maxHeight ||
            !world.isChunkLoaded(point.x shr 4, point.z shr 4) || locked(city.worldId, point) ||
            civsProtectedBuildings(city).any { (!allowOwn || it.id != building.id) && (point.x in it.bounds.min.x..it.bounds.max.x && point.z in it.bounds.min.z..it.bounds.max.z ||
                    CivsWorksiteLayouts.isField(it, point.x, point.z)) }) return null
        val block = world.getBlockAt(point.x, point.y, point.z)
        if (block.getState(false) is TileState || !world.worldBorder.isInside(block.location) || !worldAccess.canModify(city.owner, block)) return null
        return block
    }

    private fun beside(city: Settlement, building: CivBuilding, world: World, target: CivPosition,
                       allowOwn: Boolean = true, ladder: Boolean = false): CivPosition? {
        for (dy in listOf(0, 1, -1, -2)) for ((dx, dz) in listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)) {
            val p = CivPosition(target.x + dx, target.y + dy, target.z + dz)
            if (!city.contains(p.x, p.z) || civsProtectedBuildings(city).any { (!allowOwn || it.id != building.id) &&
                    (p.x in it.bounds.min.x..it.bounds.max.x && p.z in it.bounds.min.z..it.bounds.max.z ||
                        CivsWorksiteLayouts.isField(it, p.x, p.z)) }) continue
            if (citizenStandable(world, p)) return p
        }
        if (ladder && world.isChunkLoaded(target.x shr 4, target.z shr 4) &&
            world.getBlockAt(target.x, target.y, target.z).type == Material.LADDER) return target
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
            duration, status, miningStep, after)
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
    }
}
