package ru.arc.civs

import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Barrel
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.Chest
import org.bukkit.block.DoubleChest
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.plugin.Plugin
import ru.arc.civs.CivStorageSource as Source
import ru.arc.civs.CivsStorageItems.isEmpty
import ru.arc.civs.CivsStorageItems.plain
import java.util.UUID

internal data class CivStorageKey(val worldId: UUID, val position: CivPosition)

/** Loaded-container membership and capture epochs; no receipt locks or native item writes. */
internal class CivsStorageContainers(
    private val plugin: Plugin,
    tileEntities: (Chunk) -> Array<BlockState>,
    private val frozenPositions: (UUID) -> Map<CivPosition, String?>?,
    private val checkReady: () -> Unit,
    private val canModify: (Settlement, CivPosition) -> Boolean,
) {
    private val index = CivsContainerIndex(plugin.server, tileEntities)
    private val supplyCaptures = CivsSupplyCaptures(index, { city ->
        val positions = index.positions(city)
        city.buildings.associate { it.id to selectBuildingPositions(city, it.id, positions) }
    }, ::captureSupplyContainer)
    private val autonomousCaptures = mutableMapOf<UUID, CivSupplyCaptureResult>()

    fun positions(city: Settlement): Map<CivPosition, String?> = frozenPositions(city.id) ?: index.positions(city)
    fun indexedPositions(city: Settlement): Map<CivPosition, String?> = index.positions(city)
    fun replaceCities(cities: Collection<Settlement>) { supplyCaptures.clear(); autonomousCaptures.clear(); index.replace(cities) }
    fun updateCity(city: Settlement) { index.update(city); supplyCaptures.update(city) }
    fun removeCity(cityId: UUID) { supplyCaptures.remove(cityId); autonomousCaptures.remove(cityId); index.remove(cityId) }
    fun refreshBuilding(city: Settlement, building: CivBuilding?) {
        supplyCaptures.invalidate(city.id, building?.let { setOf(it.id) })
        index.refresh(city, building)
    }
    fun startSupplyCapture(city: Settlement): CivsSupplyCapture = supplyCaptures.start(city)
    fun supplyCaptureCurrent(city: Settlement, result: CivSupplyCaptureResult, buildingIds: Set<UUID>? = null): Boolean =
        supplyCaptures.current(city, result, buildingIds)
    fun supplyRevision(cityId: UUID): Long = supplyCaptures.revision(cityId)
    fun invalidateSupply(cityId: UUID, buildingIds: Set<UUID>?) = supplyCaptures.invalidate(cityId, buildingIds)
    fun debug(cityId: UUID?): Map<String, Any?> = mapOf("containerIndex" to index.debug(cityId), "supplyCaptures" to supplyCaptures.debug(cityId))
    fun clear() { index.clear(); supplyCaptures.clear(); autonomousCaptures.clear() }

    internal fun rememberAutonomousCapacity(city: Settlement, result: CivSupplyCaptureResult) {
        if (city.autonomy?.storage == CivsAutonomousStorage.stores(result) && supplyCaptureCurrent(city, result))
            autonomousCaptures[city.id] = result
        else autonomousCaptures.remove(city.id)
    }

    internal fun autonomousCapacityCurrent(city: Settlement, buildingId: UUID? = null): Boolean {
        if (!CivsAutonomousStorage.observed(city)) return true
        val result = autonomousCaptures[city.id] ?: return false
        return city.autonomy?.storage == CivsAutonomousStorage.stores(result) &&
            supplyCaptureCurrent(city, result, buildingId?.let(::setOf))
    }

    fun sources(city: Settlement, preferredBuildingId: UUID? = null): Sequence<Source> {
        val positions = positions(city)
        val preferred = preferredBuildingId?.let { id ->
            (city.building(id) ?: throw CivsRejection("building-not-found")).control
        }
        // The claim freezes source membership across journal IO. New event-indexed containers join the next operation.
        val ordered = sequence {
            if (preferred != null && preferred in positions) yield(preferred to positions[preferred])
            positions.forEach { (position, type) -> if (position != preferred) yield(position to type) }
        }
        return resolveSources(city, ordered)
    }

    fun buildingPositions(city: Settlement, buildingId: UUID, controlOnly: Boolean = false): Map<CivPosition, String?> {
        val positions = positions(city)
        return selectBuildingPositions(city, buildingId, positions, controlOnly)
    }

    private fun selectBuildingPositions(city: Settlement, buildingId: UUID, positions: Map<CivPosition, String?>,
        controlOnly: Boolean = false): Map<CivPosition, String?> {
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        return linkedMapOf<CivPosition, String?>().also { selected ->
            building.control?.takeIf { it in positions }?.let { selected[it] = positions[it] }
            if (!controlOnly && building.phase == BuildingPhase.ACTIVE) {
                val foreignControls = city.buildings.filter { it.id != buildingId }.mapNotNull(CivBuilding::control).toSet()
                positions.forEach { (position, type) ->
                    if (position !in foreignControls && building.bounds.contains(position.x, position.y, position.z))
                        selected.putIfAbsent(position, type)
                }
            }
        }
    }

    fun localSources(city: Settlement, buildingId: UUID): Sequence<Source> =
        resolveSources(city, buildingPositions(city, buildingId).asSequence().map { it.key to it.value })

    fun resolveSources(city: Settlement, positions: Sequence<Pair<CivPosition, String?>>): Sequence<Source> =
        positions.mapNotNull { (position, type) ->
            try { resolve(city, position, type) } catch (failure: CivsRejection) {
                if (failure.reason == "storage-unavailable") null else throw failure
            }
        }

    fun control(city: Settlement, building: CivBuilding): Source {
        reject(city.building(building.id) != building, "not-authorized")
        val position = building.control ?: throw CivsRejection("storage-container-invalid")
        return resolve(city, position, "BARREL")
    }

    fun resolve(city: Settlement, position: CivPosition, expectedType: String?): Source {
        val control = city.buildings.any { it.control == position }
        reject(!storagePositionAllowed(city, position, if (control) "BARREL" else expectedType ?: "CHEST"), "not-authorized")
        val values = mapOf("x" to position.x.toString(), "y" to position.y.toString(), "z" to position.z.toString(), "world" to city.worldId.toString())
        val world = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("storage-unavailable", values)
        val located = values + ("world_name" to world.name)
        if (position.y !in world.minHeight until world.maxHeight)
            throw CivsRejection("storage-container-invalid", located + ("reason" to "outside-world"))
        if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) throw CivsRejection("storage-unavailable", located)
        val block = world.getBlockAt(position.x, position.y, position.z)
        val state = block.state
        val inventory = when (state) {
            is Chest -> state.blockInventory // Exactly one 27-slot half, even beside a foreign chest.
            is Barrel -> state.inventory
            else -> throw CivsRejection("storage-container-invalid", located + ("block" to block.type.name))
        }
        if (block.type.name !in CIVS_STORAGE_TYPES || expectedType != null && block.type.name != expectedType || control && block.type != Material.BARREL)
            throw CivsRejection("storage-container-invalid", located + ("block" to block.type.name))
        require(inventory.size == STORAGE_SLOTS)
        return Source(position, block.type.name, inventory)
    }

    private fun captureSupplyContainer(city: Settlement, position: CivPosition, expectedType: String?): CivSupplyCapturedContainer? {
        checkReady()
        if (city.autonomy != null && !canModify(city, position)) return null
        val source = try { resolve(city, position, expectedType) } catch (failure: CivsRejection) {
            if (failure.reason in setOf("storage-unavailable", "storage-container-invalid")) return null else throw failure
        }
        val contents = source.inventory.storageContents
        require(contents.size == STORAGE_SLOTS)
        return CivSupplyCapturedContainer(source.type, contents.map { item ->
            if (isEmpty(item)) CivSupplyCapturedSlot(null, 0, false, 0) else {
                val native = requireNotNull(item)
                CivSupplyCapturedSlot(native.type.name, native.amount, plain(native) != null, native.maxStackSize)
            }
        })
    }

    fun placed(event: BlockPlaceEvent) {
        index.changed(event.blockPlaced)
        if (event.blockPlaced.type.name in CIVS_STORAGE_TYPES || event.blockReplacedState.type.name in CIVS_STORAGE_TYPES)
            supplyChanged(event.blockPlaced)
    }
    fun removed(block: Block) { if (block.type.name in CIVS_STORAGE_TYPES) supplyChanged(block); index.removed(block) }
    fun chunkLoaded(chunk: Chunk) { index.load(chunk); supplyChunkChanged(chunk) }
    fun chunkUnloaded(chunk: Chunk) { supplyChunkChanged(chunk); index.unload(chunk) }
    fun worldLoaded(world: World) { world.loadedChunks.forEach { index.load(it); supplyChunkChanged(it) } }
    fun worldUnloaded(world: World) { world.loadedChunks.forEach(::supplyChunkChanged); index.unloadWorld(world.uid) }
    fun supplyChanged(inventory: Inventory) {
        storageHolderPositions(inventory.holder).forEach { supplyCaptures.changed(it.worldId, it.position) }
    }
    private fun supplyChanged(block: Block) = supplyCaptures.changed(block.world.uid, CivPosition(block.x, block.y, block.z))
    private fun supplyChunkChanged(chunk: Chunk) = supplyCaptures.chunkChanged(chunk.world.uid, chunk.x, chunk.z)
    private fun reject(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
}

internal fun storageHolderPositions(holder: InventoryHolder?): List<CivStorageKey> = when (holder) {
    is DoubleChest -> storageHolderPositions(holder.getLeftSide(false)) + storageHolderPositions(holder.getRightSide(false))
    is BlockState -> listOf(CivStorageKey(holder.world.uid, CivPosition(holder.x, holder.y, holder.z)))
    else -> emptyList()
}
