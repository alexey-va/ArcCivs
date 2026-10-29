package ru.arc.civs

import org.bukkit.plugin.Plugin
import org.bukkit.Bukkit
import java.util.logging.Level
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Owning-thread resident identities, lifecycle fences and aggregate native-work budgets. */
internal class CitizenRuntime(val plugin: Plugin, val cityProvider: () -> Collection<Settlement>) {
    val actors = linkedMapOf<UUID, CitizenActor>()
    val statuses = mutableMapOf<UUID, String>()
    val closed = AtomicBoolean()
    val pendingHarm = mutableSetOf<UUID>()
    var residentLifeEnabled = false
    var residentObservedAt = 0L
    var navigationTick = 0L
    var navigationAt = 0L
    var navigationCadenceMillis = 0L
    var wanderBudget = 0
    var campBudget = 0
    var collectionBudget = 0
    var recoveryBudget = 0
    var constructionBudget = 0
}

internal fun checkCitizenMainThread() = check(Bukkit.isPrimaryThread()) { "ArcCivs resident access must be on the server thread" }

internal fun CitizenRuntime.logFailure(actor: CitizenActor, phase: String, error: Throwable) {
    if (actor.reportedFailure) return
    actor.reportedFailure = true
    CivsDiagnostics.record(plugin.logger, Level.WARNING, actor.cityId,
        "ArcCivs NPC failure city=${actor.cityId} worker=${actor.workerId} phase=$phase cycle=${actor.plan.token.completedCycles}", error)
}

internal fun CitizenPoint.coordinates() = "$x,$y,$z"
