package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

/** The cap stays inside this unworked hall, where other accepted construction cannot write. */
private fun campControlCap(city: Settlement, hall: CivBuilding): CivPosition? {
    val control = hall.control ?: return null
    if (control.y == Int.MAX_VALUE || city.autonomy == null || city.inventory?.mode != CivStorageMode.PHYSICAL || city.center != control ||
        hall.type != BuildingType.TOWN_HALL || hall.level != 1 || hall.phase != BuildingPhase.SITE ||
        city.construction?.buildingId == hall.id || hall.id in city.constructionQueue.orEmpty()) return null
    return control.copy(y = control.y + 1).takeIf { hall.bounds.contains(it.x, it.y, it.z) }
}

/** This exact shape extends the existing receipt without changing the serialized fields. */
internal fun civsCampControlApproach(city: Settlement, hall: CivBuilding, receipt: CivControlPlacement): CivPlanBlock? {
    val cap = campControlCap(city, hall) ?: return null
    if (receipt.retire != null || receipt.position != hall.control ||
        receipt.before.substringBefore('[') != "minecraft:barrel") return null
    return receipt.approach?.singleOrNull()?.takeIf { it.position == cap && it.after == "minecraft:air" }
}

/** One-time native control grants and restart recovery through the existing pendingControls receipt. */
internal class CivsControlInstallations(
    private val plugin: Plugin,
    private val world: CivsWorld,
    private val storage: CivsStorage,
    private val chunks: CivsChunkLoading,
    private val cityProvider: () -> Map<UUID, Settlement>,
    private val busy: Set<UUID>,
    private val unhealthy: Set<UUID>,
    private val ready: () -> Boolean,
    private val mutate: (UUID, (Settlement) -> Settlement) -> CompletableFuture<Settlement>,
) {
    private val cities get() = cityProvider()
    private val controlFailures = mutableSetOf<UUID>()
    private val installing = mutableMapOf<UUID, CompletableFuture<Settlement>>()
    private var controlCursor = 0
    private var initialApproachCursor = 0
    private var nextInitialApproachAt = 0L
    private val initialApproachRequests = mutableSetOf<UUID>()
    private val initialApproachRetryAt = mutableMapOf<UUID, Long>()
    private val initialApproachFailures = mutableSetOf<UUID>()
    internal var controlOccupied: (UUID, CivPosition) -> Boolean = { _, _ -> false }
    internal var campApproachAllowed: (Settlement) -> Boolean = { false }

    fun forgetFailure(buildingId: UUID) { controlFailures.remove(buildingId) }
    fun clear() {
        controlFailures.clear(); controlCursor = 0
        initialApproachRequests.clear(); initialApproachRetryAt.clear(); initialApproachFailures.clear()
        initialApproachCursor = 0; nextInitialApproachAt = 0L
    }

    /** Missing controls after their one-time grant require repair, never a repeating free grant. */
    fun requirePresent(worldId: UUID, building: CivBuilding) {
        if ((building.type.isInfrastructure || building.type == BuildingType.DECORATION) && building.control == null) return
        val point = building.control ?: throw CivsRejection("control-missing")
        val target = plugin.server.getWorld(worldId) ?: throw CivsRejection("world-unavailable")
        rejectIf(point.y !in target.minHeight until target.maxHeight, "outside-world")
        rejectIf(!target.isChunkLoaded(point.x shr 4, point.z shr 4), "chunk-unloaded")
        if (target.getBlockAt(point.x, point.y, point.z).type != Material.BARREL) throw CivsRejection("control-position-missing",
            mapOf("x" to point.x.toString(), "y" to point.y.toString(), "z" to point.z.toString()))
    }

    fun prepare(cityId: UUID, worldId: UUID, owner: UUID, building: CivBuilding,
        prepareApproach: Boolean = true): CivControlPlacement {
        val point = building.control ?: throw CivsRejection("control-missing")
        val target = plugin.server.getWorld(worldId) ?: throw CivsRejection("world-unavailable")
        rejectIf(point.y !in target.minHeight until target.maxHeight, "outside-world")
        rejectIf(!target.isChunkLoaded(point.x shr 4, point.z shr 4), "chunk-unloaded")
        val block = target.getBlockAt(point.x, point.y, point.z)
        rejectIf(block.type != Material.BARREL && (block.type == Material.BEDROCK || block.state is org.bukkit.block.TileState), "control-obstructed")
        rejectIf(!world.canModify(owner, block, Material.BARREL), "lands-denied")
        val approach = (if (prepareApproach) (-1..1).toList() else emptyList()).mapNotNull { dy ->
            val position = building.entrance.copy(y = Math.addExact(building.entrance.y, dy))
            // Old non-curated layouts may use an approach inside their paste; never rewrite captured design cells.
            if (position == point || building.bounds.contains(position.x, position.y, position.z)) return@mapNotNull null
            rejectIf(position.y !in target.minHeight until target.maxHeight, "outside-world")
            rejectIf(!target.isChunkLoaded(position.x shr 4, position.z shr 4), "chunk-unloaded")
            val cell = target.getBlockAt(position.x, position.y, position.z)
            if (if (dy == -1) cell.type.isSolid else cell.isPassable && !cell.isLiquid) return@mapNotNull null
            val after = if (dy == -1) Material.DIRT else Material.AIR
            checkApproachCell(cityId, owner, building, cell, after)
            CivPlanBlock(position, cell.blockData.asString, after.createBlockData().asString)
        }.takeIf { it.isNotEmpty() }
        if (approach != null) CivsDiagnostics.event("approach_prepared", "city" to cityId, "building" to building.id,
            "world" to worldId, "count" to approach.size, "positions" to approach.map { it.position })
        return CivControlPlacement(building.id, point, block.blockData.asString, approach)
    }

    /** Only the unbuilt founding hall can need an entrance before its first builder exists. */
    fun initialAutonomousHall(city: Settlement): CivBuilding? {
        if (city.autonomy?.paused != false || city.pendingDeletion != null || city.construction != null ||
            city.constructionQueue.orEmpty().isNotEmpty() || city.pendingControls.orEmpty().isNotEmpty() ||
            !CivsAutoStorageMigration.ready(city)) return null
        val hall = city.buildings.singleOrNull()?.takeIf { it.type == BuildingType.TOWN_HALL && it.level == 1 &&
            it.phase == BuildingPhase.SITE && it.control != null && city.center == it.control &&
            !CivsAutonomousGeometry.horizontalContains(it.bounds, it.entrance) } ?: return null
        return hall.takeIf { city.autonomy?.plan == null || CivsSettlementPlans.current(city)?.plots?.any { it.building == hall } == true }
    }

    fun initialApproachNeeded(city: Settlement, hall: CivBuilding): Boolean {
        val target = plugin.server.getWorld(city.worldId) ?: return false
        val entry = hall.entrance
        if (entry.y.toLong() - 1 < target.minHeight || entry.y.toLong() + 1 >= target.maxHeight ||
            !target.isChunkLoaded(entry.x shr 4, entry.z shr 4)) return false
        val floor = target.getBlockAt(entry.x, entry.y - 1, entry.z)
        return !standingFloor(floor) || !CivsTerrainPolicy.passableTerraform(target.getBlockAt(entry.x, entry.y, entry.z)) ||
            !CivsTerrainPolicy.passableTerraform(target.getBlockAt(entry.x, entry.y + 1, entry.z))
    }

    /** Pure selection runs with the detached proof; the durable receipt retains its existing three-cell contract. */
    fun provedInitialApproach(building: CivBuilding, terrain: CivTerraformPlan?): List<CivPlanBlock> {
        rejectIf(terrain?.route?.isNotEmpty() == true, "invalid-plan")
        val entry = building.entrance
        val changes = terrain?.blocks.orEmpty().filter { cell ->
            cell.position.x == entry.x && cell.position.z == entry.z &&
                cell.position.y.toLong() in (entry.y.toLong() - 1)..(entry.y.toLong() + 1) && cell.before != cell.after
        }
        rejectIf(changes.size > 3 || changes.map { it.position }.distinct().size != changes.size || changes.any {
            it.after != if (it.position.y < entry.y) "minecraft:dirt" else "minecraft:air"
        }, "invalid-plan")
        return changes
    }

    /** A strict native foundation/body proof, rather than creator permission, owns every proposed change here. */
    fun prepareProvedInitialApproach(cityId: UUID, worldId: UUID, owner: UUID, building: CivBuilding,
        changes: List<CivPlanBlock>): CivControlPlacement {
        rejectIf(building.type != BuildingType.TOWN_HALL || building.level != 1 || building.phase != BuildingPhase.SITE ||
            CivsAutonomousGeometry.horizontalContains(building.bounds, building.entrance), "invalid-building")
        val receipt = prepare(cityId, worldId, owner, building, prepareApproach = false)
        val target = plugin.server.getWorld(worldId) ?: throw CivsRejection("world-unavailable")
        val entry = building.entrance
        rejectIf(changes.size > 3 || changes.map { it.position }.distinct().size != changes.size, "invalid-plan")
        for (dy in -1..1) {
            val point = entry.copy(y = Math.addExact(entry.y, dy))
            rejectIf(point.y !in target.minHeight until target.maxHeight, "outside-world")
            rejectIf(!target.isChunkLoaded(point.x shr 4, point.z shr 4), "chunk-unloaded")
            val native = target.getBlockAt(point.x, point.y, point.z)
            val proved = changes.firstOrNull { it.position == point }
            if (proved == null) {
                rejectIf(if (dy == -1) !standingFloor(native) else !CivsTerrainPolicy.passableTerraform(native), "autonomy-no-path")
            } else {
                val after = if (dy == -1) "minecraft:dirt" else "minecraft:air"
                rejectIf(proved.after != after || proved.before == proved.after || point == building.control, "invalid-plan")
                rejectIf(native.blockData.asString != proved.before && native.blockData.asString != proved.after, "world-changed")
                checkApproachCell(cityId, owner, building, native, if (dy == -1) Material.DIRT else Material.AIR)
            }
        }
        rejectIf(changes.any { it.position.x != entry.x || it.position.z != entry.z ||
            it.position.y.toLong() !in (entry.y.toLong() - 1)..(entry.y.toLong() + 1) }, "invalid-plan")
        return receipt.copy(approach = changes.takeIf { it.isNotEmpty() })
    }

    private fun campCapAllowed(city: Settlement): Boolean = city.autonomy?.paused == false &&
        city.pendingDeletion == null && CivsAutoStorageMigration.ready(city) && campApproachAllowed(city)

    private fun repairCampCap(city: Settlement, hall: CivBuilding): CompletableFuture<Unit> {
        val cap = campControlCap(city, hall) ?: return CompletableFuture.completedFuture(Unit)
        val control = requireNotNull(hall.control)
        val native = plugin.server.getWorld(city.worldId) ?: return CompletableFuture.completedFuture(Unit)
        if (cap.y.toLong() + 1 >= native.maxHeight || control.y < native.minHeight ||
            !citizenChunkReady(native, cap.x shr 4, cap.z shr 4)) return CompletableFuture.completedFuture(Unit)
        val block = native.getBlockAt(cap.x, cap.y, cap.z)
        if (!campCapSafe(city, hall, native, block) || citizenCollectedHomeTarget(city, hall, native, control) != null)
            return CompletableFuture.completedFuture(Unit)
        checkApproachCell(city.id, city.owner, hall, block, Material.AIR)
        val receipt = prepare(city.id, city.worldId, city.owner, hall, prepareApproach = false).copy(
            approach = listOf(CivPlanBlock(cap, block.blockData.asString, "minecraft:air")))
        return mutate(city.id) { current ->
            rejectIf(current != city || !campCapAllowed(current), "stale-building")
            CivsRules.validate(current.copy(pendingControls = current.pendingControls.orEmpty() + receipt,
                revision = Math.addExact(current.revision, 1)))
        }.thenCompose { install(city.id, hall.id).thenApply { Unit } }
    }

    /** With a cardinal stance at cap Y, the centre ray crosses only its own column, the cap and the barrel. */
    private fun campCapSafe(city: Settlement, hall: CivBuilding, native: World, capBlock: Block): Boolean {
        val control = requireNotNull(hall.control)
        val cap = CivPosition(capBlock.x, capBlock.y, capBlock.z)
        val above = cap.copy(y = cap.y + 1)
        if (!campCapAllowed(city) || capBlock.type !in CivsTerrainPolicy.TREE_GROUND ||
            !CivsTerrainPolicy.naturalTerraform(capBlock) || above.y >= native.maxHeight ||
            !citizenChunkReady(native, cap.x shr 4, cap.z shr 4) ||
            !citizenStorageReady(native, control) || !world.canModify(city.owner, native.getBlockAt(control.x, control.y, control.z))) return false
        if (listOf(control, cap, above).any { storage.isWorldLocked(city.worldId, it) } ||
            listOf(cap, above).any { civsControlOccupied(plugin, city.worldId, it, controlOccupied) }) return false
        val upper = native.getBlockAt(above.x, above.y, above.z)
        if (!upper.type.isAir && (upper.type !in CivsTerrainPolicy.TREE_GROUND || !CivsTerrainPolicy.naturalTerraform(upper))) return false
        val aim = control.location(native).add(0.0, .5, 0.0)
        return listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).any { (dx, dz) ->
            val feet = cap.copy(x = cap.x + dx, z = cap.z + dz)
            if (!city.contains(feet.x, feet.z) || !citizenControlClear(city, feet) ||
                civsProtectedBuildings(city).any { it.id != hall.id && it.bounds.contains(feet.x, feet.y, feet.z) } ||
                (-1..1).any { storage.isWorldLocked(city.worldId, feet.copy(y = feet.y + it)) } ||
                !citizenStandable(native, feet) || !citizenPacketBodyClear(native, feet.point(), false)) return@any false
            val eye = feet.location(native).add(0.0, 1.5, 0.0)
            val delta = aim.toVector().subtract(eye.toVector())
            val distance = delta.length()
            if (distance > 3.0 || !citizenBlockTraceReady(native, eye, control)) return@any false
            val hit = native.rayTraceBlocks(eye, delta.normalize(), distance, FluidCollisionMode.ALWAYS, true)?.hitBlock
            hit != null && hit.x == cap.x && hit.y == cap.y && hit.z == cap.z
        }
    }

    private fun standingFloor(block: org.bukkit.block.Block): Boolean =
        CivsTerrainPolicy.naturalTerraform(block) && !CivsTerrainPolicy.naturalLeaves(block.blockData) &&
            CivsTerrainPolicy.routeTerraformSupport(block)

    private fun checkApproachCell(cityId: UUID, owner: UUID, building: CivBuilding, block: org.bukkit.block.Block, after: Material) {
        val point = CivPosition(block.x, block.y, block.z)
        val values = mapOf("block" to block.type.name, "x" to block.x.toString(), "y" to block.y.toString(),
            "z" to block.z.toString(), "world" to block.world.uid.toString(), "world_name" to block.world.name)
        fun reject(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason, values) }
        reject(!block.world.worldBorder.isInside(org.bukkit.Location(block.world, point.x + .5, point.y + .5, point.z + .5)), "outside-border")
        val city = cities[cityId]
        reject(city != null && (city.owner != owner || city.worldId != block.world.uid), "not-authorized")
        reject(city != null && !city.contains(point.x, point.z), "outside-city")
        reject(cities.values.any { it.id != cityId && it.worldId == block.world.uid && it.contains(point.x, point.z) }, "city-overlap")
        reject(cities.values.filter { it.worldId == block.world.uid }.any { other ->
            civsProtectedBuildings(other).any { (other.id != cityId || it.id != building.id) &&
                (it.bounds.contains(point.x, point.y, point.z) || it.control == point) } ||
                other.pendingControls.orEmpty().any { (other.id != cityId || it.buildingId != building.id) &&
                    it.approach.orEmpty().any { cell -> cell.position == point } }
        }, "building-overlap")
        reject(block.type == Material.BEDROCK || block.state is org.bukkit.block.TileState, "protected-terrain")
        reject(cities.values.any { it.worldId == block.world.uid && point in it.inventory?.containers.orEmpty() }, "registered-container")
        reject(!world.canModify(owner, block, after), "lands-denied")
    }

    fun install(cityId: UUID, buildingId: UUID): CompletableFuture<Settlement> {
        installing[buildingId]?.takeUnless { it.isDone }?.let { return it }
        val result = installOnce(cityId, buildingId)
        installing[buildingId] = result
        result.whenComplete { _, _ -> installing.remove(buildingId, result) }
        return result
    }

    private fun installOnce(cityId: UUID, buildingId: UUID): CompletableFuture<Settlement> {
        rejectIf(!ready(), "not-ready")
        val city = cities[cityId] ?: throw CivsRejection("no-city")
        val receipt = city.pendingControls.orEmpty().firstOrNull { it.buildingId == buildingId }
            ?: return CompletableFuture.completedFuture(city)
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        rejectIf(cityId in busy || cityId in unhealthy, "city-busy")
        val point = receipt.position
        val campCap = civsCampControlApproach(city, building, receipt)
        if (campCap != null) rejectIf(!campCapAllowed(city), "city-busy")
        // Until exact-slot evacuation is durable, the old barrel remains the authoritative control.
        val staging = receipt.retire != null && receipt.retire.position == building.control
        rejectIf(building.control != point && !staging, "control-location-changed")
        val target = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("world-unavailable")
        rejectIf(point.y !in target.minHeight until target.maxHeight, "outside-world")
        rejectIf(!target.isChunkLoaded(point.x shr 4, point.z shr 4), "chunk-unloaded")
        val block = target.getBlockAt(point.x, point.y, point.z)
        if (campCap != null) rejectIf(block.type != Material.BARREL, "control-position-missing")
        rejectIf(block.type != Material.BARREL && block.blockData.asString != receipt.before, "control-obstructed")
        rejectIf(block.type != Material.BARREL && (block.type == Material.BEDROCK || block.state is org.bukkit.block.TileState), "control-obstructed")
        rejectIf(!world.canModify(city.owner, block, Material.BARREL), "lands-denied")
        // Validate every before/after image before the first native write, including the barrel grant.
        val approach = receipt.approach.orEmpty().map { cell ->
            val position = cell.position
            rejectIf(position.y !in target.minHeight until target.maxHeight, "outside-world")
            rejectIf(!target.isChunkLoaded(position.x shr 4, position.z shr 4), "chunk-unloaded")
            val native = target.getBlockAt(position.x, position.y, position.z)
            val current = native.blockData.asString
            if (current != cell.before && current != cell.after) throw CivsRejection("world-changed", mapOf(
                "block" to native.type.name, "x" to position.x.toString(), "y" to position.y.toString(),
                "z" to position.z.toString(), "world" to city.worldId.toString(), "world_name" to target.name))
            val after = Bukkit.createBlockData(cell.after)
            checkApproachCell(city.id, city.owner, building, native, after.material)
            native to after
        }
        val retired = receipt.retire
        val retiringBlock = retired?.let {
            val old = it.position
            rejectIf(old.y !in target.minHeight until target.maxHeight, "outside-world")
            rejectIf(!target.isChunkLoaded(old.x shr 4, old.z shr 4), "chunk-unloaded")
            val native = target.getBlockAt(old.x, old.y, old.z)
            rejectIf(!world.canModify(city.owner, native, Material.AIR), "lands-denied")
            rejectIf(civsControlOccupied(plugin, city.worldId, old, controlOccupied), "worksite-blocked")
            rejectIf(staging && native.type.isAir, "world-changed")
            if (!native.type.isAir) {
                rejectIf(native.type != Material.BARREL || !civsSameRelocationContainerState(native.blockData.asString, it.before), "world-changed")
                val holder = native.state as? org.bukkit.block.Barrel ?: throw CivsRejection("control-obstructed")
                holder.inventory.viewers.toList().forEach { viewer -> viewer.closeInventory() }
                if (!staging) rejectIf(holder.inventory.storageContents.any { item -> item != null && !item.type.isAir && item.amount > 0 }, "storage-busy")
            }
            native
        }
        // Closing an old inventory can invoke other plugins; recheck the destination before writing it.
        rejectIf(block.type != Material.BARREL && block.blockData.asString != receipt.before, "control-obstructed")
        val reused = block.type == Material.BARREL
        if (receipt.retire != null) rejectIf(!reused && !block.type.isAir, "control-obstructed")
        if (!reused) rejectIf(civsControlOccupied(plugin, city.worldId, point, controlOccupied), "worksite-blocked")
        // Inventory-close callbacks may change terrain or permissions after the first readback.
        receipt.approach.orEmpty().zip(approach).forEach { (cell, prepared) ->
            val (native, after) = prepared
            rejectIf(native.blockData.asString !in setOf(cell.before, cell.after), "world-changed")
            checkApproachCell(city.id, city.owner, building, native, after.material)
            if (cell == campCap && native.blockData.asString != cell.after)
                rejectIf(!campCapSafe(city, building, target, native), "worksite-blocked")
            if (cell == civsControlSupport(point, cell.before))
                rejectIf(civsControlOccupied(plugin, city.worldId, cell.position, controlOccupied), "worksite-blocked")
        }
        val changed = mutableListOf<CivPosition>()
        if (!reused) { block.setType(Material.BARREL, false); changed += point }
        approach.forEach { (native, after) -> if (native.blockData.asString != after.asString) {
            native.setBlockData(after, false); changed += CivPosition(native.x, native.y, native.z)
        } }
        if (changed.isNotEmpty()) world.terrainChanged(city.worldId, changed)
        if (approach.isNotEmpty()) CivsDiagnostics.event("approach_applied", "city" to city.id, "building" to building.id,
            "world" to city.worldId, "count" to approach.size, "positions" to receipt.approach!!.map { it.position })
        storage.refreshBuilding(city, building)
        CivsDiagnostics.event("control_installed", "city" to city.id, "building" to building.id, "world" to city.worldId,
            "block" to point, "reused" to reused)
        if (staging) return storage.persistControlStaging(city, receipt).thenApply {
            rejectIf(!ready(), "not-ready")
            cities[cityId] ?: throw CivsRejection("no-city")
        }
        val barrier = if (retired == null) CompletableFuture.completedFuture(Unit) else {
            val native = requireNotNull(retiringBlock)
            if (!native.type.isAir) {
                rejectIf(native.type != Material.BARREL || !civsSameRelocationContainerState(native.blockData.asString, retired.before), "world-changed")
                val holder = native.state as? org.bukkit.block.Barrel ?: throw CivsRejection("control-obstructed")
                rejectIf(holder.inventory.storageContents.any { it != null && !it.type.isAir && it.amount > 0 }, "storage-busy")
                native.setType(Material.AIR, false)
                world.terrainChanged(city.worldId, listOf(retired.position))
                storage.refreshBuilding(city, building)
            }
            storage.persistControlRetirement(city, receipt)
        }
        return barrier.thenCompose {
            rejectIf(!ready(), "not-ready")
            retired?.let {
                rejectIf(!target.isChunkLoaded(it.position.x shr 4, it.position.z shr 4), "chunk-unloaded")
                val native = target.getBlockAt(it.position.x, it.position.y, it.position.z)
                rejectIf(!native.type.isAir, "world-changed")
                rejectIf(!world.canModify(city.owner, native, Material.AIR), "lands-denied")
            }
            mutate(cityId) { current ->
                rejectIf(receipt !in current.pendingControls.orEmpty() || current.building(buildingId)?.control != receipt.position, "control-location-changed")
                CivsRules.validate(current.copy(pendingControls = current.pendingControls.orEmpty() - receipt, revision = current.revision + 1))
            }
        }.whenComplete { _, failure ->
            if (failure == null) controlFailures -= buildingId
        }
    }

    fun pulse(clock: () -> Long = System::currentTimeMillis,
        initialApproachAllowed: (Settlement) -> Boolean = { false },
        repairInitialApproach: (Settlement) -> CompletableFuture<Unit> = { CompletableFuture.completedFuture(Unit) }) {
        val nearby = cities.values.filter { city -> chunks.active(city.id) ||
            plugin.server.onlinePlayers.any { it.world.uid == city.worldId && city.contains(it.location.blockX, it.location.blockZ) } &&
                (city.autonomy != null || plugin.server.onlinePlayers.any { city.canManage(it.uniqueId) }) }
        // Existing paid orders finish with their original receipt before conversion to actual item storage.
        nearby.firstOrNull { it.autonomy == null && it.inventory == null && it.construction == null && it.id !in busy && it.id !in unhealthy }?.let { old ->
            runCatching { mutate(old.id) { city ->
                val buildings = city.buildings.map { if (it.type == BuildingType.DECORATION || it.type.isInfrastructure) it
                    else it.copy(control = it.control ?: it.anchor) }
                val controls = buildings.filter { it.control != null && city.building(it.id)?.control == null }.map {
                    prepare(city.id, city.worldId, city.owner, it)
                }
                CivsRules.validate(city.copy(stock = Stock(), inventory = CivInventory.starting(city.stock),
                    buildings = buildings, pendingControls = city.pendingControls.orEmpty() + controls, revision = city.revision + 1))
            } }.onFailure { log("migrate city materials ${old.id}", it, old.id) }
        }
        val now = clock()
        if (now >= nextInitialApproachAt) {
            nextInitialApproachAt = now + 5_000
            initialApproachRetryAt.keys.retainAll(cities.keys)
            initialApproachFailures.retainAll(cities.keys)
            val initial: List<Pair<Settlement, CivBuilding?>> = nearby.filter { it.id !in busy && it.id !in unhealthy &&
                it.id !in initialApproachRequests && now >= (initialApproachRetryAt[it.id] ?: 0L) }.mapNotNull { city ->
                if (initialApproachAllowed(city) && initialAutonomousHall(city)?.let { initialApproachNeeded(city, it) } == true)
                    city to null
                else if (city.pendingControls.orEmpty().isEmpty() && campCapAllowed(city))
                    city.buildings.firstOrNull { campControlCap(city, it) != null }?.let { city to it }
                else null
            }
            if (initial.isNotEmpty()) {
                val (city, camp) = initial[Math.floorMod(initialApproachCursor++, initial.size)]
                initialApproachRequests += city.id
                runCatching { if (camp == null) repairInitialApproach(city) else repairCampCap(city, camp) }
                    .getOrElse { CompletableFuture.failedFuture(it) }
                    .whenComplete { _, failure ->
                        initialApproachRequests -= city.id
                        if (failure == null) {
                            initialApproachRetryAt.remove(city.id)
                            initialApproachFailures.remove(city.id)
                        } else {
                            initialApproachRetryAt[city.id] = clock() + 30_000
                            if (initialApproachFailures.add(city.id)) log("prepare initial hall approach ${city.id}", failure, city.id)
                        }
                    }
            }
        }
        // The relocation owner retries its item transfer/retirement as one bounded operation every five seconds.
        val controls = nearby.filter { it.id !in busy && it.id !in unhealthy }.flatMap { city ->
            city.pendingControls.orEmpty().filter { it.retire == null }.map { city to it }
        }
        if (controls.isEmpty()) return
        repeat(minOf(16, controls.size)) {
            val (city, pending) = controls[Math.floorMod(controlCursor++, controls.size)]
            if (city.id in busy) return@repeat
            runCatching { install(city.id, pending.buildingId) }.onSuccess { future ->
                future.whenComplete { _, failure ->
                    if (failure != null && controlFailures.add(pending.buildingId)) log("install control ${pending.buildingId}", failure, city.id)
                }
            }.onFailure {
                if (controlFailures.add(pending.buildingId)) log("install control ${pending.buildingId}", it, city.id)
            }
        }
    }

    private fun rejectIf(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
    private fun log(context: String, error: Throwable, cityId: UUID? = null) =
        CivsDiagnostics.record(Level.WARNING, cityId, "ArcCivs could not $context", plugin.logger, error)
}
