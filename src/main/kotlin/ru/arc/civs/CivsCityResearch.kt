package ru.arc.civs

import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import java.util.UUID
import java.util.concurrent.CompletableFuture

internal class CivsCityResearch(
    private val authority: CivsService,
    private val records: CivsCityRecords,
    private val tasks: LifecycleTaskScope,
    private val storage: CivsStorage,
    private val researchSupply: (Settlement) -> CivSupplySnapshot? = { null },
) {
    private var researchCursor = 0

    fun researchCity(player: Player, cityId: UUID, mutation: Boolean): Settlement {
        authority.requireReady(player)
        val city = records.cities[cityId] ?: throw CivsRejection("no-city")
        if (city.autonomy != null) {
            rejectIf(player.world.uid != city.worldId || mutation && !player.hasPermission("arccivs.admin"), "not-authorized")
        } else rejectIf(!city.canManage(player.uniqueId), "not-authorized")
        rejectIf(city.id in records.unhealthy, "storage-unavailable")
        rejectIf(city.pendingDeletion != null, "deletion-pending")
        return city
    }

    fun research(player: Player, cityId: UUID): CivResearchView = CivsResearch.view(researchCity(player, cityId, false))
    fun industry(player: Player, cityId: UUID): CivIndustryView = CivsIndustry.view(researchCity(player, cityId, false))

    fun beginResearch(player: Player, cityId: UUID, research: CivResearchId): CompletableFuture<Unit> =
        authority.action("research_begin", player, "city" to cityId, "research" to research) {
            researchCity(player, cityId, true)
            authority.afterCityAvailable(cityId) {
                rejectIf(!player.isOnline, "player-offline")
                researchCity(player, cityId, true)
                authority.mutate(cityId) { current -> CivsResearch.begin(current, research, UUID.randomUUID(), authority.now()) }.thenApply { Unit }
            }
        }

    fun setResearchPaused(player: Player, cityId: UUID, projectId: UUID, paused: Boolean): CompletableFuture<Unit> =
        authority.action("research_pause", player, "city" to cityId, "project" to projectId, "paused" to paused) {
            researchCity(player, cityId, true)
            authority.afterCityAvailable(cityId) {
                rejectIf(!player.isOnline, "player-offline")
                researchCity(player, cityId, true)
                authority.mutate(cityId) { CivsResearch.setPaused(it, projectId, paused, authority.now()) }.thenApply { Unit }
            }
        }

    fun pulseResearch() {
        val candidates = records.cities.values.filter { city -> city.id !in records.busy && city.id !in records.unhealthy && city.pendingDeletion == null &&
            city.autonomy?.paused != true && CivsAutoStorageMigration.ready(city) && authority.settlementActive(city) && CivsResearch.university(city) != null &&
            (CivsResearch.canFund(city) || city.autonomy != null && city.research?.project == null &&
                CivResearchId.entries.any { CivsResearch.reason(city, it) == null }) }
        if (candidates.isEmpty()) return
        val city = candidates[Math.floorMod(researchCursor++, candidates.size)]
        val project = city.research?.project
        val future = runCatching {
            if (project == null) {
                val research = automaticResearch(city) ?: return
                authority.mutate(city.id) { CivsResearch.begin(it, research, UUID.randomUUID(), authority.now()) }
            } else if (CivsAutonomy.legacyStock(city)) authority.mutate(city.id) { CivsResearch.fund(it, project.id, authority.now()) }
            else authority.inventoryMutation(city.id, { current ->
                CivsResearch.project(current, project.id)
                rejectIf(!CivsResearch.canFund(current), "research-unfunded")
                val university = CivsResearch.university(current) ?: throw CivsRejection("university-required")
                storage.prepareWork(current, university.id, project.cost.materials, emptyMap(), emptyList())
            }, keepViewersOpen = true) { current, inventory ->
                CivsResearch.fund(current, project.id, authority.now(), nativePaid = true).copy(inventory = inventory)
            }
        }
        future.onSuccess { accepted -> accepted.whenCompleteSync(tasks) { saved, failure ->
            if (failure != null) {
                if (CivsDiagnostics.rejection(failure)?.reason !in setOf("materials-missing", "research-unfunded", "storage-unavailable", "chunk-unloaded"))
                    authority.log("advance research funding ${city.id}", failure, city.id)
            } else {
                val current = requireNotNull(saved).research?.project
                CivsDiagnostics.event(if (project == null) "research_started" else "research_funded", "city" to city.id,
                    "project" to current?.id, "research" to current?.research, "cost" to current?.cost,
                    "work_required" to current?.workRequired, "revision" to saved.revision)
            }
        } }.onFailure { failure ->
            if (CivsDiagnostics.rejection(failure)?.reason !in setOf("materials-missing", "research-unfunded", "storage-unavailable", "chunk-unloaded"))
                authority.log("prepare research funding ${city.id}", failure, city.id)
        }
    }

    private fun automaticResearch(city: Settlement): CivResearchId? {
        CivsResearch.automatic(city)?.let { return it }
        val snapshot = researchSupply(city) ?: return null
        val observation = snapshot.inputKey as? CivSupplyObservation ?: return null
        if (!CivsAutoSupplies.observed(city, snapshot) || !storage.supplyCaptureCurrent(city, observation.capture)) return null
        return CivsResearch.automatic(city, civsResearchAvailableMaterials(city, snapshot))
    }

    private fun rejectIf(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
}

/** A conservative admission estimate, never a debit: actual local funding still uses the inventory receipt. */
internal fun civsResearchAvailableMaterials(city: Settlement, snapshot: CivSupplySnapshot): Map<String, Long> {
    val university = CivsResearch.university(city)?.control ?: return emptyMap()
    val active = city.buildings.filter { it.phase == BuildingPhase.ACTIVE }.associateBy { it.id }
    val radius = snapshot.maximumTransferDistance.toDouble()
    fun reachable(a: CivPosition, b: CivPosition): Boolean {
        val dx = a.x.toDouble() - b.x; val dy = a.y.toDouble() - b.y; val dz = a.z.toDouble() - b.z
        return dx * dx + dy * dy + dz * dz <= radius * radius
    }
    val relays = snapshot.stores.values.filter { store -> active[store.id]?.type == BuildingType.WAREHOUSE &&
        city.workers.any { !CivsResidents.isChild(it) && it.assignment == store.id } && reachable(store.control, university) }
    val available = linkedMapOf<String, Long>()
    snapshot.stores.values.filter { store -> store.id in active && (store.control == university ||
        (store.type == BuildingType.WAREHOUSE || store.outputMaterial != null) &&
            relays.any { reachable(store.control, it.control) }) }.forEach { store ->
        store.stock.forEach { (material, count) ->
            val reserve = maxOf(store.desiredStock[material] ?: 0L, snapshot.stockTargets[store.id]?.get(material) ?: 0L)
            available.merge(material, (count - reserve).coerceAtLeast(0), Math::addExact)
        }
    }
    // Queued construction and workplace demands retain their existing priority over optional studies.
    snapshot.requests.forEach { request ->
        available[request.material] = ((available[request.material] ?: 0L) -
            (request.needed - request.inTransit).coerceAtLeast(0)).coerceAtLeast(0)
    }
    snapshot.craftJobs.values.forEach { job -> job.inputs.forEach { (material, count) ->
        val carried = city.workers.firstOrNull { it.id == job.workerId }?.let { CivsEquipment.plainCount(it, material).toLong() } ?: 0L
        available[material] = ((available[material] ?: 0L) - (count - carried).coerceAtLeast(0)).coerceAtLeast(0)
    } }
    return available.filterValues { it > 0 }
}
