package ru.arc.civs

import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.logging.Level

internal data class CivControlCandidate(val position: CivPosition, val standing: CivPosition, val existing: Boolean)

internal fun civsControlOccupied(plugin: Plugin, worldId: UUID, point: CivPosition,
    blocked: (UUID, CivPosition) -> Boolean): Boolean = blocked(worldId, point) || plugin.server.onlinePlayers.any {
    it.world.uid == worldId && it.boundingBox.overlaps(BoundingBox(point.x.toDouble(), point.y.toDouble(), point.z.toDouble(),
        point.x + 1.0, point.y + 1.0, point.z + 1.0))
}

/** One bounded relocation every five seconds; accepted inventory and pending-control receipts own recovery. */
internal class CivsControlRelocations(
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    private val world: CivsWorld,
    private val storage: CivsStorage,
    private val installations: CivsControlInstallations,
    private val candidates: (CivBuilding) -> CompletableFuture<List<CivControlCandidate>>,
    private val cityProvider: () -> Collection<Settlement>,
    private val available: (Settlement) -> Boolean,
    private val transfer: (Settlement, CivBuilding, CivPosition, CivPosition) -> CompletableFuture<Settlement>,
    private val mutate: (UUID, (Settlement) -> Settlement) -> CompletableFuture<Settlement>,
    private val clock: () -> Long,
    private val blocked: (UUID, CivPosition) -> Boolean = { _, _ -> false },
) : AutoCloseable {
    private val sources = linkedMapOf<CivBuilding, CompletableFuture<List<CivControlCandidate>>>()
    private val active = linkedSetOf<CompletableFuture<Settlement>>()
    private val failures = mutableMapOf<UUID, String>()
    private var cursor = 0
    private var after = 0L
    private var closed = false

    fun pulse() {
        checkCitizenMainThread()
        val now = clock()
        if (closed || now < after || active.isNotEmpty()) return
        after = now + 5_000L
        val choices = cityProvider().filter { it.autonomy != null && it.inventory?.mode == CivStorageMode.PHYSICAL &&
            it.pendingDeletion == null && available(it) }.flatMap { city ->
            city.buildings.filter { it.phase == BuildingPhase.ACTIVE && it.type in CivsWorkstations.TYPES &&
                it.control?.let { p -> it.bounds.contains(p.x, p.y, p.z) } == true &&
                (city.construction == null || city.pendingControls.orEmpty().any { pending -> pending.buildingId == it.id && pending.retire != null }) &&
                city.pendingControls.orEmpty().none { pending -> pending.buildingId == it.id && pending.retire == null }
            }.map { city to it }
        }
        if (choices.isEmpty()) return
        val recovering = choices.filter { (city, building) -> city.pendingControls.orEmpty().any { it.buildingId == building.id && it.retire != null } }
        val queue = recovering.ifEmpty { choices }
        val (city, building) = queue[Math.floorMod(cursor++, queue.size)]
        runCatching {
            val pending = city.pendingControls.orEmpty().firstOrNull { it.buildingId == building.id && it.retire != null }
            if (pending != null) resume(city, building, pending) else select(city, building)?.let { move(city, building, it) }
        }.onSuccess { operation -> if (operation != null) {
            active += operation
            operation.whenCompleteSync(tasks) { _, failure ->
                active -= operation
                if (failure == null) failures.remove(building.id) else report(city, building, failure)
            }
        } }.onFailure { report(city, building, it) }
    }

    private fun select(city: Settlement, building: CivBuilding): CivControlCandidate? {
        var source = sources[building]
        if (source == null) {
            if (sources.size >= 64) sources.entries.first().let { sources.remove(it.key); it.value.cancel(false) }
            source = candidates(building)
            sources[building] = source
        }
        if (!source.isDone) return null
        val points = try { source.getNow(emptyList()) } catch (failure: CompletionException) {
            sources.remove(building)
            throw failure
        }
        if (points.isEmpty()) return null
        if (points.any { it.position == building.control }) return null
        fun rotate(list: List<CivControlCandidate>): List<CivControlCandidate> = if (list.isEmpty()) list else list.indices.map {
            list[(Math.floorMod(cursor, list.size) + it) % list.size]
        }
        val existing = rotate(points.filter(CivControlCandidate::existing))
        val spare = rotate(points.filterNot(CivControlCandidate::existing))
        val preferred = existing.take(if (spare.isEmpty()) 8 else 4)
        return (preferred + spare.take(8 - preferred.size)).firstOrNull { usable(city, building, it) }
    }

    private fun usable(city: Settlement, building: CivBuilding, candidate: CivControlCandidate): Boolean {
        val native = plugin.server.getWorld(city.worldId) ?: return false
        val p = candidate.position
        if (!native.isChunkLoaded(p.x shr 4, p.z shr 4) || p == building.control ||
            civsControlOccupied(plugin, city.worldId, p, blocked)) return false
        val block = native.getBlockAt(p.x, p.y, p.z)
        if (!world.canModify(city.owner, block, Material.BARREL)) return false
        return if (candidate.existing) CivsWorkstations.valid(city, building, native,
            CivWorkstation(candidate.standing, p, "minecraft:barrel"))
        else block.type.isAir && CivsWorkstations.valid(city, building, native, CivWorkstation(candidate.standing)) &&
            citizenStandable(native, p) && citizenPacketBodyClear(native, p.point(), false)
    }

    private fun move(city: Settlement, building: CivBuilding, candidate: CivControlCandidate): CompletableFuture<Settlement> {
        val old = requireNotNull(building.control)
        return transfer(city, building, old, candidate.position).thenCompose { saved ->
            if (closed) return@thenCompose CompletableFuture.completedFuture(saved)
            val live = cityProvider().firstOrNull { it.id == city.id } ?: throw CivsRejection("no-city")
            val current = live.building(building.id) ?: throw CivsRejection("building-not-found")
            reject(live != saved || current != building || !available(live), "control-location-changed")
            reject(!usable(live, current, candidate), "worksite-blocked")
            val native = plugin.server.getWorld(live.worldId) ?: throw CivsRejection("world-unavailable")
            reject(!native.isChunkLoaded(old.x shr 4, old.z shr 4), "chunk-unloaded")
            val source = native.getBlockAt(old.x, old.y, old.z)
            reject(source.type != Material.BARREL, "control-obstructed")
            val contents = (source.state as? Barrel)?.inventory ?: throw CivsRejection("control-obstructed")
            reject(contents.storageContents.any { it != null && !it.type.isAir && it.amount > 0 }, "storage-busy")
            val placement = installations.prepare(live.id, live.worldId, live.owner, current.copy(control = candidate.position), false)
                .copy(retire = CivPlanBlock(old, source.blockData.asString, "minecraft:air"))
            val destination = native.getBlockAt(candidate.position.x, candidate.position.y, candidate.position.z)
            reject(if (candidate.existing) destination.type != Material.BARREL else !destination.type.isAir, "control-obstructed")
            reject(civsControlOccupied(plugin, live.worldId, candidate.position, blocked), "worksite-blocked")
            mutate(live.id) { fresh ->
                reject(fresh != live || fresh.construction != null || fresh.pendingControls.orEmpty().any { it.buildingId == building.id }, "control-location-changed")
                CivsRules.validate(fresh.copy(buildings = fresh.buildings.map { if (it.id == building.id) it.copy(control = candidate.position) else it },
                    inventory = fresh.inventory?.copy(containers = fresh.inventory.containers - old - candidate.position),
                    pendingControls = fresh.pendingControls.orEmpty() + placement, revision = fresh.revision + 1))
            }.thenCompose { installations.install(it.id, building.id) }
        }
    }

    private fun resume(city: Settlement, building: CivBuilding, pending: CivControlPlacement): CompletableFuture<Settlement> {
        val old = requireNotNull(pending.retire).position
        val native = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("world-unavailable")
        reject(!native.isChunkLoaded(old.x shr 4, old.z shr 4), "chunk-unloaded")
        val block = native.getBlockAt(old.x, old.y, old.z)
        if (block.type.isAir) return installations.install(city.id, building.id)
        reject(block.type != Material.BARREL || !civsSameRelocationContainerState(block.blockData.asString, pending.retire.before), "world-changed")
        return transfer(city, building, old, pending.position).thenCompose { installations.install(it.id, building.id) }
    }

    private fun report(city: Settlement, building: CivBuilding, failure: Throwable) {
        val reason = CivsDiagnostics.rejection(failure)?.reason ?: failure.javaClass.name
        if (failures.put(building.id, reason) != reason) CivsDiagnostics.record(Level.WARNING, city.id,
            "ArcCivs control relocation waiting building=${building.id} reason=$reason retry_ms=5000", plugin.logger, failure)
    }

    fun drained(): CompletableFuture<Unit> = CompletableFuture.allOf(*active.toTypedArray()).handle { _, _ -> Unit }
    override fun close() {
        checkCitizenMainThread()
        closed = true
        sources.values.forEach { it.cancel(false) }
        sources.clear()
    }
    private fun reject(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
}
