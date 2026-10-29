package ru.arc.civs

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.paper.network.BackendTransfer
import ru.arc.paper.network.BackendTransferResult
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

internal class CivsCityMembership(
    private val authority: CivsService,
    private val records: CivsCityRecords,
    private val buildingRequestsOwner: () -> CivsBuildingRequests,
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    private val store: CivsStore,
    private val executor: Executor,
    private val backendTransfer: BackendTransfer?,
) {
    private val buildingRequests get() = buildingRequestsOwner()
    private val remoteCities = mutableMapOf<UUID, CivsNetworkCity>()
    private val lookups = mutableMapOf<UUID, UUID>()
    private val memberNamesPending = mutableSetOf<UUID>()
    private var recoveredMemberNames = emptyMap<UUID, String>()

    fun recoveredNames(names: Map<UUID, String>) { recoveredMemberNames = names }
    fun forget(playerId: UUID) { lookups.remove(playerId); remoteCities.remove(playerId) }
    fun forgetCity(city: Settlement) {
        remoteCities.entries.removeIf { it.value.city.id == city.id }
    }
    fun clear() { remoteCities.clear(); lookups.clear() }

    fun remoteCity(player: Player): CivsNetworkCity? = remoteCities[player.uniqueId]
    fun memberName(city: Settlement, memberId: UUID): String =
        plugin.server.getPlayer(memberId)?.name ?: city.memberNames?.get(memberId.toString())
        ?: recoveredMemberNames[memberId] ?: memberId.toString().take(8)
    fun refreshCity(player: Player): CompletableFuture<Unit> = authority.action("lookup", player) {
        authority.requireReady(player)
        val request = UUID.randomUUID()
        lookups[player.uniqueId] = request
        remoteCities.remove(player.uniqueId)
        val result = authority.tracked<Unit>()
        store.lookup(player.uniqueId).whenCompleteSync(tasks) { record, failure ->
            if (lookups[player.uniqueId] != request || !player.isOnline) {
                result.completeExceptionally(CivsRejection("lookup-expired"))
            } else {
                lookups.remove(player.uniqueId)
                when {
                    failure != null -> {
                        authority.log("read network membership ${player.uniqueId}", failure)
                        result.completeExceptionally(CivsRejection("network-unavailable"))
                    }
                    record != null && record.server != store.homeServer() -> {
                        remoteCities[player.uniqueId] = record
                        result.complete(Unit)
                    }
                    record != null && authority.city(player)?.id != record.city.id -> {
                        authority.invalidateReadiness("storage-unavailable")
                        authority.updateHealth()
                        result.completeExceptionally(CivsRejection("storage-unavailable"))
                    }
                    else -> result.complete(Unit)
                }
            }
        }
        result
    }
    fun transferHome(player: Player): CompletableFuture<Unit> = authority.action("transfer", player) {
        authority.requireReady(player)
        val destination = remoteCity(player)?.server ?: throw CivsRejection("no-city")
        val outcome = backendTransfer?.connect(player, destination) ?: BackendTransferResult.TRANSFER_CLOSED
        CivsDiagnostics.event("transfer", *CivsDiagnostics.actor(player), "destination" to destination.value,
            "outcome" to if (outcome == BackendTransferResult.SENT) "requested" else outcome.name.lowercase())
        rejectIf(outcome != BackendTransferResult.SENT, "transfer-failed")
        CompletableFuture.completedFuture(Unit)
    }

    fun addMember(player: Player, name: String): CompletableFuture<Unit> = authority.cityAction("add_member", player, "member_name" to name) { city ->
        val member = plugin.server.getPlayerExact(name.trim()) ?: throw CivsRejection("player-offline")
        rejectIf(records.cities.values.any { it.autonomy == null && it.id != city.id && it.canManage(member.uniqueId) } || buildingRequests.foundingPending(member.uniqueId), "already-member")
        authority.mutate(city.id) { before ->
            val added = CivsRules.addMember(before, player.uniqueId, member.uniqueId)
            val named = CivsMembership.remember(added, mapOf(player.uniqueId to player.name, member.uniqueId to member.name))
            if (added == before && named != before) named.copy(revision = before.revision + 1) else named
        }.thenApply { Unit }
    }
    fun removeMember(player: Player, memberId: UUID): CompletableFuture<Unit> = authority.cityAction("remove_member", player, "member" to memberId) { city ->
        authority.mutate(city.id) { CivsMembership.remember(CivsRules.removeMember(it, player.uniqueId, memberId), emptyMap()) }.thenApply { Unit }
    }

    private fun rememberedMemberNames(city: Settlement): Settlement = CivsMembership.remember(city,
        (city.members + city.owner).mapNotNull { id ->
            (plugin.server.getPlayer(id)?.name ?: city.memberNames?.get(id.toString())
                ?: recoveredMemberNames[id])?.let { id to it }
        }.toMap())

    /** Recovered playerdata was read on the store executor; live names override it on Paper. */
    fun pulseMemberNames() {
        val city = records.cities.values.firstOrNull { it.id !in memberNamesPending && it.id !in records.unhealthy &&
            it.pendingDeletion == null && rememberedMemberNames(it) != it } ?: return
        memberNamesPending += city.id
        runCatching { authority.afterCityAvailable(city.id) {
            if (records.cities[city.id]?.pendingDeletion != null || city.id !in records.cities)
                return@afterCityAvailable CompletableFuture.completedFuture(Unit)
            authority.mutate(city.id) { before ->
                rememberedMemberNames(before).let { if (it == before) before else it.copy(revision = before.revision + 1) }
            }.thenApply { Unit }
        } }.onSuccess { future -> future.whenCompleteSync(tasks) { _, failure ->
            memberNamesPending -= city.id
            if (failure != null) authority.log("remember city member names ${city.id}", failure, city.id)
        } }.onFailure { memberNamesPending -= city.id; authority.log("remember city member names ${city.id}", it, city.id) }
    }

    private fun rejectIf(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
}
