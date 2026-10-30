package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

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
    internal var controlOccupied: (UUID, CivPosition) -> Boolean = { _, _ -> false }

    fun forgetFailure(buildingId: UUID) { controlFailures.remove(buildingId) }
    fun clear() { controlFailures.clear(); controlCursor = 0 }

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
        installing[buildingId]?.let { return it }
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
        rejectIf(building.control != point, "control-location-changed")
        val target = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("world-unavailable")
        rejectIf(point.y !in target.minHeight until target.maxHeight, "outside-world")
        rejectIf(!target.isChunkLoaded(point.x shr 4, point.z shr 4), "chunk-unloaded")
        val block = target.getBlockAt(point.x, point.y, point.z)
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
            if (!native.type.isAir) {
                rejectIf(native.type != Material.BARREL || !civsSameRelocationContainerState(native.blockData.asString, it.before), "world-changed")
                val holder = native.state as? org.bukkit.block.Barrel ?: throw CivsRejection("control-obstructed")
                holder.inventory.viewers.toList().forEach { viewer -> viewer.closeInventory() }
                rejectIf(holder.inventory.storageContents.any { item -> item != null && !item.type.isAir && item.amount > 0 }, "storage-busy")
            }
            native
        }
        // Closing an old inventory can invoke other plugins; recheck the destination before writing it.
        rejectIf(block.type != Material.BARREL && block.blockData.asString != receipt.before, "control-obstructed")
        val reused = block.type == Material.BARREL
        if (receipt.retire != null) rejectIf(!reused && !block.type.isAir, "control-obstructed")
        if (!reused) rejectIf(civsControlOccupied(plugin, city.worldId, point, controlOccupied), "worksite-blocked")
        if (!reused) block.setType(Material.BARREL, false)
        approach.forEach { (native, after) -> if (native.blockData.asString != after.asString) native.setBlockData(after, false) }
        if (approach.isNotEmpty()) CivsDiagnostics.event("approach_applied", "city" to city.id, "building" to building.id,
            "world" to city.worldId, "count" to approach.size, "positions" to receipt.approach!!.map { it.position })
        storage.refreshBuilding(city, building)
        CivsDiagnostics.event("control_installed", "city" to city.id, "building" to building.id, "world" to city.worldId,
            "block" to point, "reused" to reused)
        val barrier = if (retired == null) CompletableFuture.completedFuture(Unit) else {
            val native = requireNotNull(retiringBlock)
            if (!native.type.isAir) {
                rejectIf(native.type != Material.BARREL || !civsSameRelocationContainerState(native.blockData.asString, retired.before), "world-changed")
                val holder = native.state as? org.bukkit.block.Barrel ?: throw CivsRejection("control-obstructed")
                rejectIf(holder.inventory.storageContents.any { it != null && !it.type.isAir && it.amount > 0 }, "storage-busy")
                native.setType(Material.AIR, false)
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

    fun pulse() {
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
