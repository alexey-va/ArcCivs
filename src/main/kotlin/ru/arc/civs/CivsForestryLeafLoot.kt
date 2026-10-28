package ru.arc.civs

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.LeavesDecayEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** The original leaf and exact native Item UUID, never a new loot roll. */
data class CivForestryLeafSource(val entityId: UUID, val leaf: CivPosition,
    val treeId: UUID? = null, val crownRoot: CivPosition? = null)

/** Explicit authority for the one ground-to-workplace collected-transfer branch. */
data class CivForestryLeafLootProof(val cityId: UUID, val ownerId: UUID, val worldId: UUID,
    val buildingId: UUID, val sources: List<CivForestryLeafSource>)

/** Tags actual decay drops; the existing inventory journal owns their transfer and consumed UUIDs. */
internal class CivsForestryLeafLoot(
    private val cities: () -> Map<UUID, Settlement>,
    private val active: () -> Boolean,
    private val canModify: (Settlement, CivPosition) -> Boolean,
    private val available: (UUID) -> Boolean,
    private val collect: (UUID, UUID, List<UUID>) -> CompletableFuture<Settlement>,
) : Listener {
    private data class Cell(val world: UUID, val point: CivPosition)
    private data class Decay(val proof: CivForestryLeafLootProof, val tick: Int, val state: String)
    private data class Chunk(val world: UUID, val x: Int, val z: Int)
    private val decay = linkedMapOf<Cell, Decay>()
    private val pending = linkedSetOf<Pair<UUID, UUID>>()
    private val inFlight = hashMapOf<UUID, CompletableFuture<Settlement>>()
    private var chunks = emptyList<Chunk>()
    private var chunkNext = 0
    private val entityOffsets = hashMapOf<Chunk, Int>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDecay(event: LeavesDecayEvent) {
        if (!active() || !event.block.type.name.endsWith("_LEAVES")) return
        val block = event.block
        val point = point(block)
        val tick = Bukkit.getCurrentTick()
        decay.entries.removeIf { it.value.tick != tick }
        for (city in cities().values) {
            if (city.worldId != block.world.uid || city.pendingDeletion != null || city.inventory?.mode != CivStorageMode.PHYSICAL) continue
            for (building in city.buildings) {
                if (!eligible(city, building)) continue
                val source = source(city, building, point, UUID(0, 0)) ?: continue
                if (!canModify(city, point)) continue
                // A tick-local block observation cannot grow beyond the native event budget.
                if (decay.size == MAX_PENDING) decay.remove(decay.keys.first())
                decay[Cell(city.worldId, point)] = Decay(CivForestryLeafLootProof(city.id, city.owner,
                    city.worldId, building.id, listOf(source)), tick, block.blockData.asString)
                return
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSpawn(event: ItemSpawnEvent) {
        if (!active()) return
        val item = event.entity
        if (!plainLeafLoot(item) || item.owner != null || item.thrower != null) return
        val point = point(item)
        val observed = decay[Cell(item.world.uid, point)]?.takeIf { it.tick == Bukkit.getCurrentTick() } ?: return
        // Native LeavesBlock drops synchronously before removing its accepted leaf.
        if (item.world.getBlockAt(point.x, point.y, point.z).blockData.asString != observed.state) return
        val proof = observed.proof.copy(sources = listOf(observed.proof.sources.single().copy(entityId = item.uniqueId)))
        val city = cities()[proof.cityId] ?: return
        if (!current(proof, city) || !canModify(city, point)) return
        mark(item, proof)
        remember(item)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onAdded(event: EntityAddToWorldEvent) {
        (event.entity as? Item)?.takeIf { marked(it) }?.let(::remember)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onMerge(event: ItemMergeEvent) {
        if (marked(event.entity) || marked(event.target)) event.isCancelled = true
    }

    /** One loaded entity chunk and at most sixteen queued items per service pulse. */
    fun pulse() {
        check(Bukkit.isPrimaryThread())
        if (!active()) return
        inFlight.entries.removeIf { it.value.isDone }
        val tick = Bukkit.getCurrentTick()
        decay.entries.removeIf { it.value.tick != tick }
        if (tick % 20 == 0 || chunks.isEmpty()) refreshChunks()
        scanChunk()
        var admitted = false
        repeat(minOf(16, pending.size)) {
            if (admitted) return@repeat
            val key = pending.first()
            pending.remove(key)
            val item = Bukkit.getWorld(key.first)?.getEntity(key.second) as? Item ?: return@repeat
            val proof = proof(item)
            val city = proof?.let { cities()[it.cityId] }
            if (proof == null || city == null || proof.worldId != item.world.uid || !current(proof, city) ||
                !city.contains(item.location.blockX, item.location.blockZ) || !plainLeafLoot(item) || item.owner != null || item.thrower != null) {
                unmark(item)
                return@repeat
            }
            item.setWillAge(false)
            pending += key
            val worker = city.workers.firstOrNull { it.assignment == proof.buildingId && !CivsResidents.isChild(it) }
                ?: return@repeat
            if (city.id in inFlight || !available(city.id) || !civsGroundItemAvailable(item, worker.id)) return@repeat
            val batch = (sequenceOf(item.uniqueId) + pending.asSequence().take(16).filter { it.first == city.worldId }.mapNotNull { entry ->
                (Bukkit.getWorld(entry.first)?.getEntity(entry.second) as? Item)?.takeIf { candidate ->
                    proof(candidate)?.let { it.copy(sources = emptyList()) == proof.copy(sources = emptyList()) } == true &&
                        civsGroundItemAvailable(candidate, worker.id)
                }?.uniqueId
            }).distinct().take(8).toList()
            if (batch.isEmpty()) return@repeat
            admitted = true
            try {
                inFlight[city.id] = collect(city.id, worker.id, batch)
            } catch (_: CivsRejection) { /* Native source and its persistent mark remain pending. */ }
        }
    }

    private fun remember(item: Item) {
        // Overflow discards only this transient hint. Persistent tags are rediscovered by the loaded-zone scan.
        if (pending.size == MAX_PENDING) pending.remove(pending.first())
        pending += item.world.uid to item.uniqueId
    }

    private fun refreshChunks() {
        chunks = buildSet {
            cities().values.forEach { city -> city.buildings.filter { eligible(city, it) }.forEach { building ->
                val bounds = searchBounds(building) ?: return@forEach
                val a = CivsWorksiteLayouts.point(building, bounds.min)
                val b = CivsWorksiteLayouts.point(building, bounds.max)
                for (x in ((minOf(a.x, b.x) - CivsForestry.MAX_RADIUS) shr 4)..((maxOf(a.x, b.x) + CivsForestry.MAX_RADIUS) shr 4))
                    for (z in ((minOf(a.z, b.z) - CivsForestry.MAX_RADIUS) shr 4)..((maxOf(a.z, b.z) + CivsForestry.MAX_RADIUS) shr 4))
                        add(Chunk(city.worldId, x, z))
            } }
        }.toList()
        entityOffsets.keys.retainAll(chunks.toSet())
        if (chunkNext >= chunks.size) chunkNext = 0
    }

    private fun scanChunk() {
        if (chunks.isEmpty()) return
        val key = chunks[Math.floorMod(chunkNext++, chunks.size)]
        val world = Bukkit.getWorld(key.world) ?: return
        if (!world.isChunkLoaded(key.x, key.z)) return
        // ponytail: getEntities is one indivisible native chunk read; only sixteen returned entries are inspected.
        val entities = world.getChunkAt(key.x, key.z).entities
        if (entities.isEmpty()) return
        val from = (entityOffsets[key] ?: 0) % entities.size
        repeat(minOf(16, entities.size)) { offset ->
            (entities[(from + offset) % entities.size] as? Item)?.takeIf { marked(it) }?.let(::remember)
        }
        entityOffsets[key] = (from + 16) % entities.size
    }

    companion object {
        private const val MAX_PENDING = 4096
        private val KEY = NamespacedKey("arccivs", "forestry_leaf_source")
        private fun point(block: Block) = CivPosition(block.x, block.y, block.z)
        private fun point(item: Item) = item.location.let { CivPosition(it.blockX, it.blockY, it.blockZ) }
        private fun marked(item: Item) = item.persistentDataContainer.has(KEY, PersistentDataType.STRING)
        fun leafLoot(material: String): Boolean = material in setOf("APPLE", "STICK", "AZALEA", "FLOWERING_AZALEA") ||
            material.endsWith("_SAPLING") || material == "MANGROVE_PROPAGULE"
        private fun plainLeafLoot(item: Item) = item.itemStack.let { stack -> leafLoot(stack.type.name) &&
            stack.amount in 1..64 && stack.isSimilar(org.bukkit.inventory.ItemStack(stack.type)) }
        private fun eligible(city: Settlement, building: CivBuilding) = city.pendingDeletion == null &&
            city.inventory?.mode == CivStorageMode.PHYSICAL && building.type == BuildingType.FORESTER && building.phase == BuildingPhase.ACTIVE
        private fun searchBounds(building: CivBuilding): CivBox? = CivsWorksiteLayouts.site(building)?.getAsJsonObject("forester")
            ?.getAsJsonObject("searchBounds")?.let { CivBox(CivsWorksiteLayouts.vector(it["min"]), CivsWorksiteLayouts.vector(it["max"])) }
        private fun inZone(building: CivBuilding, point: CivPosition): Boolean {
            val bounds = searchBounds(building) ?: return false
            val local = CivsMinePlan.localPoint(building, point)
            return local.x in bounds.min.x..bounds.max.x && local.z in bounds.min.z..bounds.max.z
        }
        private fun source(city: Settlement, building: CivBuilding, point: CivPosition, entity: UUID): CivForestryLeafSource? {
            if (!city.contains(point.x, point.z)) return null
            if (inZone(building, point)) return CivForestryLeafSource(entity, point)
            val tree = city.forestryJobs?.get(building.id.toString()) ?: return null
            if (tree.cells.none { tree.position(it) == point && CivsForestry.naturalLeaf(tree.state(it), tree.timber) }) return null
            return CivForestryLeafSource(entity, point, tree.id, tree.root)
        }
        fun sourceAllowed(city: Settlement, building: CivBuilding, source: CivForestryLeafSource): Boolean =
            city.contains(source.leaf.x, source.leaf.z) && source.leaf.y in -4096..4096 && if (source.treeId == null && source.crownRoot == null)
                inZone(building, source.leaf) else source.treeId != null && source.crownRoot?.let { root ->
                    root.y in -4096..4096 && inZone(building, root) && kotlin.math.abs(source.leaf.x.toLong() - root.x) <= CivsForestry.MAX_RADIUS &&
                        kotlin.math.abs(source.leaf.z.toLong() - root.z) <= CivsForestry.MAX_RADIUS &&
                        source.leaf.y.toLong() in root.y.toLong()..root.y.toLong() + CivsForestry.MAX_HEIGHT
                } == true
        fun current(proof: CivForestryLeafLootProof, city: Settlement): Boolean = proof.cityId == city.id && proof.ownerId == city.owner &&
            proof.worldId == city.worldId && proof.sources.size in 1..8 && city.building(proof.buildingId)?.let { building ->
                eligible(city, building) && proof.sources.all { sourceAllowed(city, building, it) }
            } == true
        fun destinationOwned(city: Settlement, building: CivBuilding, position: CivPosition, type: String): Boolean =
            type in CIVS_STORAGE_TYPES && (if (position == building.control) type == "BARREL" else
                building.bounds.contains(position.x, position.y, position.z)) && city.buildings.none { it.id != building.id && it.control == position }
        fun validateTransfer(city: Settlement, transfer: CivCollectedTransfer) {
            val proof = requireNotNull(transfer.forestryLeafLoot)
            require(current(proof, city) && transfer.food == null && transfer.homeId == null && transfer.ground.size in 1..8 &&
                transfer.containers.size in 1..108 && proof.sources.size == transfer.ground.size &&
                proof.sources.map { it.entityId }.toSet() == transfer.ground.map { it.entityId }.toSet())
            val worker = city.workers.single { it.id == transfer.workerId }
            require(worker.assignment == proof.buildingId && !CivsResidents.isChild(worker) && transfer.before == CivsEquipment.slots(worker))
            require(transfer.ground.all { it.after == null && it.before.data == null && leafLoot(it.before.material) })
            val building = requireNotNull(city.building(proof.buildingId))
            require(transfer.containers.all { destinationOwned(city, building, it.container, it.containerType) })
        }
        fun mark(item: Item, proof: CivForestryLeafLootProof) {
            val source = proof.sources.single()
            require(source.entityId == item.uniqueId)
            val values = listOf("1", proof.cityId, proof.ownerId, proof.worldId, proof.buildingId, source.entityId,
                source.leaf.x, source.leaf.y, source.leaf.z, source.treeId ?: "", source.crownRoot?.x ?: "",
                source.crownRoot?.y ?: "", source.crownRoot?.z ?: "")
            item.persistentDataContainer.set(KEY, PersistentDataType.STRING, values.joinToString(";"))
            item.setWillAge(false)
        }
        fun proof(item: Item): CivForestryLeafLootProof? = runCatching {
            val value = item.persistentDataContainer.get(KEY, PersistentDataType.STRING) ?: return null
            require(value.length <= 512)
            val fields = value.split(';')
            require(fields.size == 13 && fields[0] == "1" && UUID.fromString(fields[5]) == item.uniqueId)
            val source = CivForestryLeafSource(item.uniqueId, CivPosition(fields[6].toInt(), fields[7].toInt(), fields[8].toInt()),
                fields[9].takeIf(String::isNotEmpty)?.let(UUID::fromString),
                if (fields[10].isEmpty() && fields[11].isEmpty() && fields[12].isEmpty()) null
                else CivPosition(fields[10].toInt(), fields[11].toInt(), fields[12].toInt()))
            CivForestryLeafLootProof(UUID.fromString(fields[1]), UUID.fromString(fields[2]), UUID.fromString(fields[3]),
                UUID.fromString(fields[4]), listOf(source))
        }.getOrNull()
        private fun unmark(item: Item) { item.persistentDataContainer.remove(KEY); item.setWillAge(true) }
    }
}
