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
    val acceptsInsert: Boolean = true) {
    val stock: Map<String, Long>
    val partialRoom: Map<String, Long>
    val emptySlots: Int

    init {
        val stock = linkedMapOf<String, Long>()
        val room = linkedMapOf<String, Long>()
        var empty = 0
        for (slot in slots) {
            val material = slot.material
            if (material == null) {
                if (acceptsInsert) empty++
            } else if (slot.plain) {
                stock.merge(material, slot.amount.toLong(), Math::addExact)
                val remaining = (slot.stackSize - slot.amount).coerceAtLeast(0).toLong()
                if (acceptsInsert && remaining > 0) room.merge(material, remaining, Math::addExact)
            }
        }
        this.stock = stock.toMap()
        partialRoom = room.toMap()
        emptySlots = empty
    }
}

/** Storage-specific capture/version owner. Native references exist only inside the supplied owner-thread read. */
internal class CivsSupplyCaptures(
    private val index: CivsContainerIndex,
    private val layout: (Settlement, List<CivBuilding>, Map<CivPosition, String?>) -> Map<UUID, Map<CivPosition, String?>>,
    private val read: (Settlement, CivPosition, String?) -> CivSupplyCapturedContainer?,
    private val canReuse: (Settlement, CivPosition, CivSupplyCapturedContainer) -> Boolean,
    private val clock: () -> Long,
) {
    private class Geometry(val world: UUID, val owner: UUID, val center: CivPosition, val radius: Int,
        val buildings: List<CivBuilding>, val registered: List<CivPosition>, val mode: CivStorageMode?, val autonomous: Boolean,
        val upgradeId: UUID?, val upgradingBuilding: UUID?) {
        var indexedPositions: Map<CivPosition, String?> = emptyMap()
        var virtualMaterials: Map<String, Map<String, Long>> = emptyMap()

        fun matches(city: Settlement): Boolean {
            if (world != city.worldId || owner != city.owner || center != city.center || radius != city.radius ||
                buildings != city.buildings || registered != city.inventory?.containers.orEmpty() ||
                mode != city.inventory?.mode || autonomous != (city.autonomy != null)) return false
            val upgrade = civsStorageUpgrade(city)
            return upgradeId == upgrade?.id && upgradingBuilding == upgrade?.buildingId
        }
    }
    private data class Holder(val type: String?, val value: CivSupplyCapturedContainer, val readAt: Long)

    private class Store(var key: CivSupplyStorageKey) {
        var contents: Map<CivPosition, CivSupplyCapturedContainer?>? = null
        var capturedGeneration: Long? = null
        var capturedAt: Long? = null
        var stock: Map<String, Long>? = null
    }
    private val cities = mutableMapOf<UUID, MutableMap<UUID, Store>>()
    private val revisions = mutableMapOf<UUID, Long>()
    private val plainRevisions = mutableMapOf<UUID, Long>()
    private val registrations = mutableMapOf<UUID, List<CivPosition>>()
    private val geometries = mutableMapOf<UUID, Geometry>()
    private val holders = mutableMapOf<UUID, MutableMap<CivPosition, Holder>>()
    private val unfinishedControls = mutableMapOf<UUID, Map<UUID, CivPosition>>()
    private var generation = 0L

    /** Captured metadata only; a dirty generation is reported rather than recaptured. */
    internal fun debug(cityId: UUID?): Map<String, Any?> {
        check(org.bukkit.Bukkit.isPrimaryThread())
        val now = clock()
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

    fun start(city: Settlement, forceNative: Boolean = false): CivsSupplyCapture {
        update(city)
        val keys = cities[city.id].orEmpty().mapValues { it.value.key }
        val geometry = geometries[city.id]
        val cache = holders.getOrPut(city.id, ::mutableMapOf)
        val reads = hashMapOf<CivPosition, Long>()
        fun sameGeometry() = geometry != null && geometries[city.id] === geometry
        return CivsSupplyCapture(city, keys, { current, position, type ->
            val now = clock()
            val retained = if (!forceNative && sameGeometry()) cache[position]?.takeIf {
                it.type == type && fresh(it.readAt, now) && canReuse(current, position, it.value)
            } else null
            if (retained != null) {
                reads[position] = retained.readAt
                retained.value
            } else {
                val revision = plainRevisions[city.id]
                val value = read(current, position, type)
                val readAt = clock()
                reads[position] = readAt
                if (sameGeometry() && plainRevisions[city.id] == revision) {
                    if (value == null) cache.remove(position)
                    else cache[position] = Holder(type, value, readAt)
                }
                value
            }
        }, { key, contents ->
            val current = cities[city.id]?.get(key.buildingId)
            if (current == null || current.key != key) key else {
                if (current.contents?.let { it != contents } == true) dirty(city.id, current)
                current.contents = contents.toMap()
                current.capturedGeneration = current.key.generation
                current.capturedAt = contents.keys.minOfOrNull { reads.getValue(it) } ?: clock()
                current.key
            }
        }, { index.covered(city) }, { plainRevisions[city.id] },
            extraPositions = unfinishedControls[city.id].orEmpty().values,
            oldestReadAt = { positions -> positions.mapNotNull(reads::get).minOrNull() },
            publish = { result -> result.stores.forEach { (id, captured) ->
                cities[city.id]?.get(id)?.takeIf { it.key == captured.key }?.stock =
                    captured.stock.takeIf { captured.unavailable == 0 }
            } },
            clock = clock)
    }

    fun planningBuildingStock(city: Settlement, buildingId: UUID): Map<String, Long>? {
        if (city.pendingDeletion != null || city.building(buildingId) == null) return null
        city.inventory?.takeIf { it.mode == CivStorageMode.VIRTUAL }?.let { return it.buildingStock(buildingId) }
        if (geometries[city.id]?.matches(city) != true) return null
        val store = cities[city.id]?.get(buildingId) ?: return null
        return store.stock.takeIf { store.capturedGeneration == store.key.generation && fresh(store.capturedAt) }
    }

    fun planningContainer(city: Settlement, buildingId: UUID, position: CivPosition): CivSupplyCapturedContainer? {
        if (city.pendingDeletion != null || geometries[city.id]?.matches(city) != true) return null
        val indexed = cities[city.id]?.get(buildingId)?.key?.positions
        val expectedType = when {
            indexed?.containsKey(position) == true -> indexed[position]
            unfinishedControls[city.id]?.get(buildingId) == position -> "BARREL"
            else -> return null
        }
        val holder = holders[city.id]?.get(position)?.takeIf { fresh(it.readAt) } ?: return null
        return holder.value.takeIf { (expectedType == null || it.type == expectedType) &&
            storagePositionAllowed(city, position, it.type) &&
            it.acceptsInsert == civsNativeContainerAcceptsInsert(city, position) }
    }

    private fun fresh(readAt: Long?, now: Long = clock()): Boolean =
        readAt != null && now - readAt in 0 until HOLDER_TTL_MILLIS

    fun current(city: Settlement, result: CivSupplyCaptureResult, buildingIds: Set<UUID>?,
        requireComplete: Boolean = false): Boolean {
        if (city.id != result.cityId) return false
        update(city)
        if (requireComplete && (!result.complete || result.coverageRevision != plainRevisions[city.id] || !index.covered(city) ||
                result.startedAtMillis?.let { !fresh(it) } == true)) return false
        return (buildingIds ?: result.stores.keys).all { id ->
            val captured = result.stores[id]?.key ?: return@all false
            val live = cities[city.id]?.get(id) ?: return@all false
            captured == live.key && (captured.mode == CivStorageMode.VIRTUAL ||
                live.capturedGeneration == captured.generation && fresh(live.capturedAt))
        }
    }

    /** Metadata/index reads only; no inventory scans are added to city publication. */
    fun update(city: Settlement) {
        val inventory = city.inventory?.validated()
        val mode = inventory?.mode ?: if (city.autonomy != null) CivStorageMode.PHYSICAL else
            run { if (city.id in cities) remove(city.id); return }
        val indexed = if (mode == CivStorageMode.PHYSICAL) index.positions(city) else emptyMap()
        val oldGeometry = geometries[city.id]
        val geometryChanged = oldGeometry?.matches(city) != true
        val virtualMaterials = inventory?.buildingMaterials.orEmpty()
        if (!geometryChanged) {
            val geometry = requireNotNull(oldGeometry)
            if (mode == CivStorageMode.PHYSICAL) {
                if (geometry.indexedPositions !== indexed) updateMembership(city, geometry, indexed)
                return
            }
            if (geometry.virtualMaterials == virtualMaterials) return
        }
        val registered = city.inventory?.containers.orEmpty()
        if (registrations[city.id] != registered) {
            registrations[city.id] = registered.toList()
            dirtyPlain(city.id)
        }
        val upgrade = civsStorageUpgrade(city)
        val geometry = if (geometryChanged) {
            if (oldGeometry != null && (oldGeometry.world != city.worldId || oldGeometry.owner != city.owner ||
                    oldGeometry.autonomous != (city.autonomy != null) || oldGeometry.mode != city.inventory?.mode)) holders.remove(city.id)
            Geometry(city.worldId, city.owner, city.center, city.radius, city.buildings,
                registered, city.inventory?.mode, city.autonomy != null, upgrade?.id, upgrade?.buildingId)
        } else requireNotNull(oldGeometry)
        val positions = if (mode == CivStorageMode.PHYSICAL) layout(city, city.buildings, indexed) else emptyMap()
        val extra = if (city.autonomy != null && mode == CivStorageMode.PHYSICAL) city.buildings.mapNotNull { building ->
            building.control?.takeIf { building.phase != BuildingPhase.ACTIVE && it in positions[building.id].orEmpty() }
                ?.let { building.id to it }
        }.toMap() else emptyMap()
        val extraChanged = unfinishedControls[city.id].orEmpty() != extra
        unfinishedControls[city.id] = extra
        val previous = cities[city.id].orEmpty()
        val buildings = if (city.autonomy != null) city.buildings.filter { it.phase == BuildingPhase.ACTIVE } else city.buildings
        val next = buildings.associateTo(linkedMapOf()) { building ->
            val old = previous[building.id]
            val key = CivSupplyStorageKey(city.id, city.worldId, city.owner, mode,
                building.id, building.type, building.phase, building.level, building.bounds, building.control,
                positions[building.id].orEmpty().toMap(),
                if (mode == CivStorageMode.VIRTUAL) requireNotNull(inventory).buildingStock(building.id).toMap() else emptyMap(),
                old?.key?.generation ?: 0L, upgrade?.takeIf { mode == CivStorageMode.PHYSICAL && it.buildingId == building.id }?.id)
            val store = if (old != null && old.key == key) old else Store(key).also { dirty(city.id, it) }
            building.id to store
        }
        if (previous.keys != next.keys && previous.isNotEmpty() || extraChanged) revisions[city.id] = ++generation
        if (geometryChanged || extraChanged || next.keys != previous.keys || next.any { (id, store) -> previous[id] !== store }) {
            val retained = (registered + extra.values).toMutableSet()
            next.values.forEach { retained += it.key.positions.keys }
            holders[city.id]?.keys?.retainAll(retained)
        }
        cities[city.id] = next
        geometry.indexedPositions = indexed
        geometry.virtualMaterials = virtualMaterials
        geometries[city.id] = geometry
    }

    /** Authored controls stay indexed across chunk changes; only active stores can gain or lose native holders. */
    private fun updateMembership(city: Settlement, geometry: Geometry, indexed: Map<CivPosition, String?>) {
        val previous = geometry.indexedPositions
        val changed = buildSet {
            previous.forEach { (point, type) -> if (point !in indexed || indexed[point] != type) add(point) }
            indexed.keys.forEach { if (it !in previous) add(it) }
        }
        if (changed.isNotEmpty()) {
            holders[city.id]?.keys?.removeAll(changed)
            val affected = city.buildings.filter { building ->
                building.phase == BuildingPhase.ACTIVE && changed.any { building.bounds.contains(it.x, it.y, it.z) }
            }
            if (affected.isNotEmpty()) {
                val stores = cities.getValue(city.id)
                layout(city, affected, indexed).forEach { (id, positions) ->
                    val old = stores.getValue(id)
                    if (old.key.positions != positions)
                        stores[id] = Store(old.key.copy(positions = positions.toMap())).also { dirty(city.id, it) }
                }
            }
        }
        geometry.indexedPositions = indexed
    }

    fun revision(cityId: UUID): Long = revisions[cityId] ?: 0L

    fun invalidate(cityId: UUID, buildingIds: Set<UUID>? = null) {
        val stores = cities[cityId] ?: return
        dirtyPlain(cityId)
        if (buildingIds == null) {
            holders[cityId]?.clear()
            stores.values.forEach { dirty(cityId, it) }
        } else {
            val positions = buildingIds.flatMapTo(hashSetOf()) { stores[it]?.key?.positions?.keys.orEmpty() }
            buildingIds.mapNotNullTo(positions) { unfinishedControls[cityId]?.get(it) }
            holders[cityId]?.keys?.removeAll(positions)
            stores.values.filter { store -> store.key.buildingId in buildingIds || store.key.positions.keys.any { it in positions } }
                .forEach { dirty(cityId, it) }
        }
    }

    fun changed(worldId: UUID, position: CivPosition) {
        index.citiesAt(worldId, position).forEach { cityId ->
            dirtyPlain(cityId)
            val invalidated = holders[cityId]?.remove(position) != null
            if (invalidated || position in unfinishedControls[cityId].orEmpty().values ||
                position in registrations[cityId].orEmpty()) revisions[cityId] = ++generation
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
            if (holders[cityId]?.keys?.removeIf { (it.x shr 4) == x && (it.z shr 4) == z } == true ||
                unfinishedControls[cityId].orEmpty().values.any { (it.x shr 4) == x && (it.z shr 4) == z } ||
                registrations[cityId].orEmpty().any { (it.x shr 4) == x && (it.z shr 4) == z }) revisions[cityId] = ++generation
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
        geometries.remove(cityId); holders.remove(cityId); unfinishedControls.remove(cityId)
    }
    fun clear() {
        cities.clear(); revisions.clear(); plainRevisions.clear(); registrations.clear()
        geometries.clear(); holders.clear(); unfinishedControls.clear()
    }

    private fun dirtyPlain(cityId: UUID) { plainRevisions[cityId] = ++generation }

    private fun dirty(cityId: UUID, store: Store) {
        store.key = store.key.copy(generation = ++generation)
        revisions[cityId] = generation
    }

    private companion object { const val HOLDER_TTL_MILLIS = 5_000L }
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
    extraPositions: Collection<CivPosition> = emptyList(),
    private val oldestReadAt: (Set<CivPosition>) -> Long? = { null },
    private val publish: (CivSupplyCaptureResult) -> Unit = {},
    clock: () -> Long = System::currentTimeMillis,
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
    private val startedAtMillis = clock()
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
        extraPositions.forEach { position -> entries.putIfAbsent(position, "BARREL" to mutableListOf()) }
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
                captured?.stock?.forEach { (material, amount) -> plainStock.merge(material, amount, Math::addExact) }
            }
            entry.second.forEach { buildingId ->
                val sum = totals.getValue(buildingId)
                sum.contents[position] = captured
                if (captured == null) return@forEach
                sum.emptySlots += captured.emptySlots
                sum.outputEmptySlots += (captured.emptySlots -
                    productionOutputHeadroom(city.building(buildingId)?.type)).coerceAtLeast(0)
                captured.stock.forEach { (material, amount) -> sum.stock.merge(material, amount, Math::addExact) }
                captured.partialRoom.forEach { (material, amount) -> sum.partialRoom.merge(material, amount, Math::addExact) }
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
        }, plainStock.toMap().takeIf { keys.values.all { it.mode == CivStorageMode.PHYSICAL } },
            minOf(startedAtMillis, oldestReadAt(plainPositions) ?: startedAtMillis),
            completeAtStart && plainHoldersAvailable && coverage() && plainRevisionAtStart == plainRevision(), plainRevisionAtStart)
            .also(publish)
        return true
    }
}
