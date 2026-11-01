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

internal data class CivControlCandidate(val position: CivPosition, val standing: CivPosition, val existing: Boolean,
    val retirePrevious: Boolean = true, val route: List<CivPosition> = emptyList())

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
    private class Proof(val cityId: UUID, val building: CivBuilding, val candidates: List<CivControlCandidate>) {
        var index = 0
        var route = CivsControlRouteProof(candidates.first().route)
        val candidate get() = candidates[index]
    }
    private var proof: Proof? = null

    fun pulse() {
        checkCitizenMainThread()
        val now = clock()
        if (closed || active.isNotEmpty()) return
        proof?.let { pending -> prove(pending); return }
        if (now < after) return
        after = now + 5_000L
        val choices = cityProvider().filter { it.autonomy != null && it.inventory?.mode == CivStorageMode.PHYSICAL &&
            it.pendingDeletion == null && available(it) }.flatMap { city ->
            city.buildings.filter { it.phase == BuildingPhase.ACTIVE && CivsBuildingControls.supported(it.type) &&
                it.control?.let { p -> it.bounds.contains(p.x, p.y, p.z) } == true &&
                city.construction?.buildingId != it.id &&
                city.pendingControls.orEmpty().none { pending -> pending.buildingId == it.id && pending.retire == null }
            }.map { city to it }
        }
        if (choices.isEmpty()) return
        val recovering = choices.filter { (city, building) -> city.pendingControls.orEmpty().any { it.buildingId == building.id && it.retire != null } }
        val queue = recovering.ifEmpty { choices }
        val (city, building) = queue[Math.floorMod(cursor++, queue.size)]
        runCatching {
            val pending = city.pendingControls.orEmpty().firstOrNull { it.buildingId == building.id && it.retire != null }
            if (pending != null) track(city, building, resume(city, building, pending))
            else select(city, building)?.let { proof = Proof(city.id, building, it) }
        }.onFailure { report(city, building, it) }
    }

    private fun track(city: Settlement, building: CivBuilding, operation: CompletableFuture<Settlement>) {
        active += operation
        operation.whenCompleteSync(tasks) { _, failure ->
            active -= operation
            if (failure == null) failures.remove(building.id) else report(city, building, failure)
        }
    }

    private fun prove(pending: Proof) {
        val city = cityProvider().firstOrNull { it.id == pending.cityId }
        if (city == null || city.building(pending.building.id) != pending.building || !available(city)) { proof = null; return }
        runCatching {
            val native = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("world-unavailable")
            when (pending.route.advance(native)) {
                null -> return
                true -> {
                    proof = null
                    reject(!usable(city, pending.building, pending.candidate), "worksite-blocked")
                    track(city, pending.building, move(city, pending.building, pending.candidate))
                }
                false -> {
                    if (++pending.index < pending.candidates.size)
                        pending.route = CivsControlRouteProof(pending.candidate.route)
                    else { proof = null; throw CivsRejection("control-interior-route-blocked") }
                }
            }
        }.onFailure { proof = null; report(city, pending.building, it) }
    }

    private fun select(city: Settlement, building: CivBuilding): List<CivControlCandidate>? {
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
        if (points.isEmpty()) throw CivsRejection("control-no-interior")
        if (points.any { it.position == building.control }) return null
        // Stable source order makes the same permanent location win after every construction/restart.
        return points.take(8).filter { usable(city, building, it) }.takeIf { it.isNotEmpty() }
            ?: throw CivsRejection("control-interior-blocked")
    }

    private fun usable(city: Settlement, building: CivBuilding, candidate: CivControlCandidate): Boolean {
        val native = plugin.server.getWorld(city.worldId) ?: return false
        val p = candidate.position
        if (!native.isChunkLoaded(p.x shr 4, p.z shr 4) || p == building.control ||
            civsControlOccupied(plugin, city.worldId, p, blocked)) return false
        val block = native.getBlockAt(p.x, p.y, p.z)
        if (!world.canModify(city.owner, block, Material.BARREL)) return false
        val feet = CivsControlRouteProof.standing(native, candidate.standing) ?: return false
        return if (candidate.existing) CivsWorkstations.valid(city, building, native,
            CivWorkstation(candidate.standing, p, "minecraft:barrel"), feet)
        else block.type.isAir && CivsWorkstations.valid(city, building, native, CivWorkstation(candidate.standing), feet) &&
            citizenStandable(native, p) && citizenPacketBodyClear(native, p.point(), false)
    }

    private fun move(city: Settlement, building: CivBuilding, candidate: CivControlCandidate): CompletableFuture<Settlement> {
        val old = requireNotNull(building.control)
        reject(!usable(city, building, candidate), "worksite-blocked")
        val native = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("world-unavailable")
        reject(!native.isChunkLoaded(old.x shr 4, old.z shr 4), "chunk-unloaded")
        val source = native.getBlockAt(old.x, old.y, old.z)
        reject(source.type != Material.BARREL, "control-obstructed")
        val placement = installations.prepare(city.id, city.worldId, city.owner, building.copy(control = candidate.position), false)
            .copy(retire = if (candidate.retirePrevious) CivPlanBlock(old, source.blockData.asString, "minecraft:air") else null)
        val destination = native.getBlockAt(candidate.position.x, candidate.position.y, candidate.position.z)
        reject(if (candidate.existing) destination.type != Material.BARREL else !destination.type.isAir, "control-obstructed")
        reject(civsControlOccupied(plugin, city.worldId, candidate.position, blocked), "worksite-blocked")
        return mutate(city.id) { fresh ->
            reject(fresh != city || fresh.construction?.buildingId == building.id ||
                fresh.pendingControls.orEmpty().any { it.buildingId == building.id }, "control-location-changed")
            CivsRules.validate(fresh.copy(
                // This durable intent protects both cells before the empty receiver is created.
                buildings = if (candidate.retirePrevious) fresh.buildings else fresh.buildings.map {
                    if (it.id == building.id) it.copy(control = candidate.position) else it },
                inventory = if (candidate.retirePrevious) fresh.inventory else
                    fresh.inventory?.copy(containers = fresh.inventory.containers - candidate.position),
                pendingControls = fresh.pendingControls.orEmpty() + placement, revision = fresh.revision + 1))
        }.thenCompose { saved ->
            if (closed) CompletableFuture.completedFuture(saved)
            else if (placement.retire != null) resume(saved, requireNotNull(saved.building(building.id)), placement)
            else installations.install(saved.id, building.id)
        }
    }

    private fun resume(city: Settlement, building: CivBuilding, pending: CivControlPlacement): CompletableFuture<Settlement> {
        val old = requireNotNull(pending.retire).position
        val native = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("world-unavailable")
        reject(!native.isChunkLoaded(old.x shr 4, old.z shr 4), "chunk-unloaded")
        val block = native.getBlockAt(old.x, old.y, old.z)
        if (block.type.isAir) {
            reject(building.control != pending.position, "world-changed")
            return installations.install(city.id, building.id)
        }
        reject(block.type != Material.BARREL || !civsSameRelocationContainerState(block.blockData.asString, pending.retire.before), "world-changed")
        if (building.control == old) return installations.install(city.id, building.id).thenCompose { staged ->
            if (closed) CompletableFuture.completedFuture(staged)
            else transfer(staged, requireNotNull(staged.building(building.id)), old, pending.position).thenCompose { evacuated ->
                if (closed) CompletableFuture.completedFuture(evacuated)
                else publish(evacuated, pending).thenCompose { installations.install(it.id, building.id) }
            }
        }
        reject(building.control != pending.position, "control-location-changed")
        // Receipts saved by the earlier implementation already published the new control.
        return transfer(city, building, old, pending.position).thenCompose { installations.install(it.id, building.id) }
    }

    private fun publish(city: Settlement, receipt: CivControlPlacement): CompletableFuture<Settlement> {
        val retired = requireNotNull(receipt.retire)
        val native = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("world-unavailable")
        reject(listOf(retired.position, receipt.position).any { !native.isChunkLoaded(it.x shr 4, it.z shr 4) }, "chunk-unloaded")
        val source = native.getBlockAt(retired.position.x, retired.position.y, retired.position.z)
        reject(source.type != Material.BARREL || !civsSameRelocationContainerState(source.blockData.asString, retired.before), "world-changed")
        val contents = (source.state as? Barrel)?.inventory ?: throw CivsRejection("control-obstructed")
        reject(contents.storageContents.any { it != null && !it.type.isAir && it.amount > 0 }, "storage-busy")
        reject(native.getBlockAt(receipt.position.x, receipt.position.y, receipt.position.z).type != Material.BARREL, "control-obstructed")
        return mutate(city.id) { fresh ->
            reject(fresh != city || receipt !in fresh.pendingControls.orEmpty() ||
                fresh.building(receipt.buildingId)?.control != retired.position, "control-location-changed")
            CivsRules.validate(fresh.copy(buildings = fresh.buildings.map {
                if (it.id == receipt.buildingId) it.copy(control = receipt.position) else it },
                inventory = fresh.inventory?.copy(containers = fresh.inventory.containers - retired.position - receipt.position),
                revision = fresh.revision + 1))
        }
    }

    private fun report(city: Settlement, building: CivBuilding, failure: Throwable) {
        val reason = CivsDiagnostics.rejection(failure)?.reason ?: failure.javaClass.name
        if (failures.put(building.id, reason) != reason) CivsDiagnostics.record(Level.WARNING, city.id,
            "ArcCivs control relocation waiting building=${building.id} schematic=${building.schematicId} reason=$reason retry_ms=5000", plugin.logger, failure)
    }

    fun drained(): CompletableFuture<Unit> = CompletableFuture.allOf(*active.toTypedArray()).handle { _, _ -> Unit }
    override fun close() {
        checkCitizenMainThread()
        closed = true
        proof = null
        sources.values.forEach { it.cancel(false) }
        sources.clear()
    }
    private fun reject(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
}
