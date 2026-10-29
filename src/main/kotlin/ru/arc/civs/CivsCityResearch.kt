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
                val research = CivResearchId.entries.first { CivsResearch.reason(city, it) == null }
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

    private fun rejectIf(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
}
