package ru.arc.civs

import java.util.UUID

/** Owner-only acceptance metadata; unrelated settlement revisions are deliberately excluded. */
internal data class CivSupplyStorageKey(
    val cityId: UUID, val worldId: UUID, val ownerId: UUID, val mode: CivStorageMode,
    val buildingId: UUID, val type: BuildingType, val phase: BuildingPhase, val level: Int,
    val bounds: CivBox, val control: CivPosition?, val positions: Map<CivPosition, String?>,
    val virtualStock: Map<String, Long>, val generation: Long,
    val upgradingOrder: UUID? = null,
)

internal data class CivSupplyCapturedStore(
    val stock: Map<String, Long>, val capacity: CivSupplyCapacity, val key: CivSupplyStorageKey,
    val slots: Int = 0, val containers: Int = 0, val unavailable: Int = 0,
)

internal data class CivSupplyCaptureResult(val cityId: UUID, val stores: Map<UUID, CivSupplyCapturedStore>,
    /** Plain native holdings counted once per physical holder, never once per owning building. */
    val plainStock: Map<String, Long>? = null, val startedAtMillis: Long? = null, val complete: Boolean = false, val coverageRevision: Long? = null)

/** Detached slot values also detect native writers which do not emit inventory events. */
internal data class CivSupplyCapturedSlot(val material: String?, val amount: Int, val plain: Boolean, val stackSize: Int)
internal data class CivSupplyCapturedContainer(val type: String, val slots: List<CivSupplyCapturedSlot>,
    val acceptsInsert: Boolean = true)

/** Storage-specific capture/version owner. Native references exist only inside the supplied owner-thread read. */
internal class CivsSupplyCaptures(
    private val index: CivsContainerIndex,
    private val layout: (Settlement) -> Map<UUID, Map<CivPosition, String?>>,
    private val read: (Settlement, CivPosition, String?) -> CivSupplyCapturedContainer?,
) {
    private class Store(var key: CivSupplyStorageKey) {
        var contents: Map<CivPosition, CivSupplyCapturedContainer?>? = null
        var capturedGeneration: Long? = null
        var capturedAt: Long? = null
    }
    private val cities = mutableMapOf<UUID, Map<UUID, Store>>()
    private val revisions = mutableMapOf<UUID, Long>()
    private val plainRevisions = mutableMapOf<UUID, Long>()
    private val registrations = mutableMapOf<UUID, List<CivPosition>>()
    private var generation = 0L

    /** Captured metadata only; a dirty generation is reported rather than recaptured. */
    internal fun debug(cityId: UUID?): Map<String, Any?> {
        check(org.bukkit.Bukkit.isPrimaryThread())
        val now = System.currentTimeMillis()
        fun <T> sample(values: Collection<T>, describe: (T) -> Any?): Map<String, Any?> = mapOf(
            "total" to values.size, "truncated" to (values.size > 8), "entries" to values.take(8).map(describe))
        val selected = cities.entries.filter { cityId == null || it.key == cityId }
        return sample(selected) { (id, stores) -> mapOf("city" to id.toString(), "revision" to revisions[id],
            "stores" to sample(stores.entries) { (building, store) -> mapOf("building" to building.toString(),
                "mode" to store.key.mode.name, "type" to store.key.type.name, "phase" to store.key.phase.name,
                "generation" to store.key.generation, "capturedGeneration" to store.capturedGeneration,
                "stale" to (store.capturedGeneration != store.key.generation),
                "captureAgeMillis" to store.capturedAt?.let { (now - it).coerceAtLeast(0) },
                "indexedContainers" to store.key.positions.size, "capturedContainers" to store.contents?.size,
                "unavailableContainers" to store.contents?.values?.count { it == null },
                "virtualMaterialKinds" to store.key.virtualStock.size) })
        } + mapOf("source" to "cached-supply-capture", "generation" to generation,
            "storeCount" to selected.sumOf { it.value.size }, "freshNativeRead" to false)
    }

    fun start(city: Settlement): CivsSupplyCapture {
        val keys = update(city)
        return CivsSupplyCapture(city, keys, read, { key, contents ->
            val current = cities[city.id]?.get(key.buildingId)
            if (current == null || current.key != key) key else {
                if (current.contents?.let { it != contents } == true) dirty(city.id, current)
                current.contents = contents.toMap()
                current.capturedGeneration = current.key.generation
                current.capturedAt = System.currentTimeMillis()
                current.key
            }
        }, { index.covered(city) }, { plainRevisions[city.id] })
    }

    fun current(city: Settlement, result: CivSupplyCaptureResult, buildingIds: Set<UUID>?,
        requireComplete: Boolean = false): Boolean {
        if (city.id != result.cityId) return false
        val keys = update(city)
        if (requireComplete && (!result.complete || result.coverageRevision != plainRevisions[city.id] || !index.covered(city))) return false
        return (buildingIds ?: result.stores.keys).all { id ->
            result.stores[id]?.key?.let { it == keys[id] } == true
        }
    }

    /** Metadata/index reads only; no inventory scans are added to city publication. */
    fun update(city: Settlement): Map<UUID, CivSupplyStorageKey> {
        val inventory = city.inventory?.validated()
        val mode = inventory?.mode ?: if (city.autonomy != null) CivStorageMode.PHYSICAL else
            run { if (city.id in cities) remove(city.id); return emptyMap() }
        val registered = city.inventory?.containers.orEmpty()
        if (registrations[city.id] != registered) {
            registrations[city.id] = registered.toList()
            dirtyPlain(city.id)
        }
        val positions = if (mode == CivStorageMode.PHYSICAL) layout(city) else emptyMap()
        val upgrade = if (mode == CivStorageMode.PHYSICAL) civsStorageUpgrade(city) else null
        val previous = cities[city.id].orEmpty()
        val buildings = if (city.autonomy != null) city.buildings.filter { it.phase == BuildingPhase.ACTIVE } else city.buildings
        val next = buildings.associate { building ->
            val old = previous[building.id]
            val key = CivSupplyStorageKey(city.id, city.worldId, city.owner, mode,
                building.id, building.type, building.phase, building.level, building.bounds, building.control,
                positions[building.id].orEmpty().toMap(),
                if (mode == CivStorageMode.VIRTUAL) requireNotNull(inventory).buildingStock(building.id).toMap() else emptyMap(),
                old?.key?.generation ?: 0L, upgrade?.takeIf { it.buildingId == building.id }?.id)
            val store = if (old != null && old.key == key) old else Store(key).also { dirty(city.id, it) }
            building.id to store
        }
        if (previous.keys != next.keys && previous.isNotEmpty()) revisions[city.id] = ++generation
        cities[city.id] = next
        return next.mapValues { it.value.key }
    }

    fun revision(cityId: UUID): Long = revisions[cityId] ?: 0L

    fun invalidate(cityId: UUID, buildingIds: Set<UUID>? = null) {
        val stores = cities[cityId] ?: return
        dirtyPlain(cityId)
        (buildingIds ?: stores.keys).forEach { stores[it]?.let { store -> dirty(cityId, store) } }
    }

    fun changed(worldId: UUID, position: CivPosition) {
        index.citiesAt(worldId, position).forEach { cityId ->
            dirtyPlain(cityId)
            val stores = cities[cityId].orEmpty()
            val controls = stores.values.mapNotNullTo(mutableSetOf()) { it.key.control }
            stores.values.filter { store ->
                val key = store.key
                key.mode == CivStorageMode.PHYSICAL && (position in key.positions || key.control == position || position !in controls &&
                    key.type in setOf(BuildingType.WAREHOUSE, BuildingType.BUILDER_HUT) && key.phase == BuildingPhase.ACTIVE &&
                    key.bounds.contains(position.x, position.y, position.z))
            }.forEach { dirty(cityId, it) }
        }
    }

    fun chunkChanged(worldId: UUID, x: Int, z: Int) {
        index.citiesInChunk(worldId, x, z).forEach { cityId ->
            dirtyPlain(cityId)
            cities[cityId].orEmpty().values.filter { store ->
                val key = store.key
                key.mode == CivStorageMode.PHYSICAL && (key.control?.let { (it.x shr 4) == x && (it.z shr 4) == z } == true ||
                    key.positions.keys.any { (it.x shr 4) == x && (it.z shr 4) == z } ||
                    key.type in setOf(BuildingType.WAREHOUSE, BuildingType.BUILDER_HUT) && key.phase == BuildingPhase.ACTIVE &&
                    x in (key.bounds.min.x shr 4)..(key.bounds.max.x shr 4) && z in (key.bounds.min.z shr 4)..(key.bounds.max.z shr 4))
            }.forEach { dirty(cityId, it) }
        }
    }

    fun remove(cityId: UUID) {
        cities.remove(cityId); revisions.remove(cityId); plainRevisions.remove(cityId); registrations.remove(cityId)
    }
    fun clear() { cities.clear(); revisions.clear(); plainRevisions.clear(); registrations.clear() }

    private fun dirtyPlain(cityId: UUID) { plainRevisions[cityId] = ++generation }

    private fun dirty(cityId: UUID, store: Store) {
        store.key = store.key.copy(generation = ++generation)
        revisions[cityId] = generation
    }
}

/**
 * One city-wide cursor, intended for the shared BudgetedTaskQueue. A step reads at most one
 * authorized 27-slot chest half/barrel; only immutable numbers/coordinates survive the read.
 * No locks are acquired. Event generations reject a capture changed between its native reads.
 */
internal class CivsSupplyCapture(
    private val city: Settlement,
    private val keys: Map<UUID, CivSupplyStorageKey>,
    private val read: (Settlement, CivPosition, String?) -> CivSupplyCapturedContainer?,
    private val finish: (CivSupplyStorageKey, Map<CivPosition, CivSupplyCapturedContainer?>) -> CivSupplyStorageKey,
    private val coverage: () -> Boolean = { false },
    private val plainRevision: () -> Long? = { null },
) {
    private class Totals {
        val stock = linkedMapOf<String, Long>()
        val partialRoom = linkedMapOf<String, Long>()
        val contents = linkedMapOf<CivPosition, CivSupplyCapturedContainer?>()
        var emptySlots = 0
        var outputEmptySlots = 0
    }
    private val owner = Thread.currentThread()
    private val totals = keys.mapValues { Totals() }
    private val startedAtMillis = System.currentTimeMillis()
    private val plainStock = linkedMapOf<String, Long>()
    private val completeAtStart = coverage()
    private val plainRevisionAtStart = plainRevision()
    private var plainHoldersAvailable = true
    private val plainPositions = keys.values.filter { !it.type.isInfrastructure }.flatMapTo(linkedSetOf()) { it.positions.keys }
        .also { positions ->
            keys.values.mapNotNullTo(positions) { it.control }
            positions += city.inventory?.containers.orEmpty()
        }
    private val containers = linkedMapOf<CivPosition, Pair<String?, MutableList<UUID>>>().also { entries ->
        keys.forEach { (buildingId, key) -> key.positions.forEach { (position, type) ->
            entries.getOrPut(position) { type to mutableListOf() }.second += buildingId
        } }
        city.inventory?.takeIf { it.mode == CivStorageMode.PHYSICAL }?.containers.orEmpty()
            .forEach { position -> entries.putIfAbsent(position, null to mutableListOf()) }
    }.entries.iterator()
    var result: CivSupplyCaptureResult? = null
        private set

    fun step(): Boolean {
        check(Thread.currentThread() === owner) { "Supply capture requires its owning thread" }
        if (result != null) return true
        if (containers.hasNext()) {
            val (position, entry) = containers.next()
            val captured = read(city, position, entry.first)
            if (position in plainPositions) {
                if (captured == null) plainHoldersAvailable = false
                captured?.slots?.filter { it.plain && it.material != null }?.forEach { slot ->
                    plainStock.merge(requireNotNull(slot.material), slot.amount.toLong(), Math::addExact)
                }
            }
            entry.second.forEach { buildingId ->
                val sum = totals.getValue(buildingId)
                sum.contents[position] = captured
                val acceptsInsert = captured?.acceptsInsert == true
                val empty = captured?.takeIf { it.acceptsInsert }?.slots?.count { it.material == null } ?: 0
                sum.outputEmptySlots += (empty - productionOutputHeadroom(city.building(buildingId)?.type)).coerceAtLeast(0)
                captured?.slots?.forEach { slot ->
                    if (slot.material == null) { if (acceptsInsert) sum.emptySlots++ }
                    else if (slot.plain) {
                        sum.stock.merge(slot.material, slot.amount.toLong(), Math::addExact)
                        val room = (slot.stackSize - slot.amount).coerceAtLeast(0).toLong()
                        if (acceptsInsert && room > 0) sum.partialRoom.merge(slot.material, room, Math::addExact)
                    }
                }
            }
            if (containers.hasNext()) return false
        }
        result = CivSupplyCaptureResult(city.id, keys.mapValues { (buildingId, key) ->
            val sum = totals.getValue(buildingId)
            val virtual = key.mode == CivStorageMode.VIRTUAL
            val stock = if (virtual) key.virtualStock else sum.stock.toMap()
            val capacity = if (virtual) CivSupplyCapacity(0, emptyMap(),
                (27L * 64L * key.level - stock.values.sum()).coerceAtLeast(0))
                else CivSupplyCapacity(sum.emptySlots, sum.partialRoom.toMap(), outputEmptySlots =
                    sum.outputEmptySlots.takeIf { productionOutputHeadroom(city.building(buildingId)?.type) > 0 })
            CivSupplyCapturedStore(stock, capacity, finish(key, sum.contents),
                sum.contents.values.sumOf { it?.slots?.size ?: 0 }, sum.contents.size, sum.contents.values.count { it == null })
        }, plainStock.toMap().takeIf { keys.values.all { it.mode == CivStorageMode.PHYSICAL } }, startedAtMillis,
            completeAtStart && plainHoldersAvailable && coverage() && plainRevisionAtStart == plainRevision(), plainRevisionAtStart)
        return true
    }
}
