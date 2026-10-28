package ru.arc.civs

import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

/** Main-thread resident lifecycle; city authority keeps the durable mutation and tick-stage ordering. */
internal class CivsResidentLifeRuntime(
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    private val citizens: CivsCitizens,
    private val chunkLoading: CivsChunkLoading,
    private val viewerRange: Int,
    private val settlements: () -> Map<UUID, Settlement>,
    private val busy: Set<UUID>,
    private val unhealthy: Set<UUID>,
    private val settlementActive: (Settlement) -> Boolean,
    private val afterCityAvailable: (UUID, () -> CompletableFuture<Settlement>) -> CompletableFuture<Settlement>,
    private val afterPopulationAvailable: (UUID, () -> CompletableFuture<Unit>) -> CompletableFuture<Unit>,
    private val mutate: (UUID, (Settlement) -> Settlement) -> CompletableFuture<Settlement>,
    private val clock: () -> Long,
    private val foods: CivsResidentFoods = CivsResidentFoods.BREAD_ONLY,
) {
    private val cities get() = settlements()
    private val populationMinuteAt = mutableMapOf<UUID, Long>()
    private val populationPending = mutableSetOf<UUID>()

    fun pending(cityId: UUID): Boolean = cityId in populationPending
    fun nextMinuteAt(cityId: UUID): Long? = populationMinuteAt[cityId]
    fun resetMinuteClock() { populationMinuteAt.clear() }

    fun pulseImmigration() {
        cities.values.firstOrNull { city ->
            city.id !in busy && city.id !in unhealthy && CivsRules.immigrationDue(city, now()) &&
                (chunkLoading.active(city.id) || plugin.server.onlinePlayers.any { player -> player.world.uid == city.worldId &&
                    player.location.toVector().distanceSquared(org.bukkit.util.Vector(city.center.x.toDouble(), city.center.y.toDouble(), city.center.z.toDouble())) <= viewerRange * viewerRange })
        }?.let { city ->
            runCatching { mutate(city.id) {
                val id = UUID.randomUUID()
                CivsRules.immigrate(it, it.owner, id, CivsNames.name(it, id, CivsPopulation.nextImmigrantSex(it)), now())
            } }
                .onSuccess { future -> future.whenComplete { saved, failure ->
                    if (failure == null) CivsDiagnostics.event("resident_arrived", "city" to city.id, "population" to saved.workers.size,
                        "remaining" to saved.immigration?.remaining)
                } }.onFailure { log("admit resident ${city.id}", it, city.id) }
        }
    }

    private fun populationActive(city: Settlement): Boolean = city.autonomy?.paused != true && city.pendingDeletion == null && city.id !in unhealthy && settlementActive(city)

    /** Active-time only; one queued update per city prevents continuous work from starving its clock. */
    fun finishMeal(cityId: UUID, meal: CivResidentMeal): CompletableFuture<Settlement> =
        afterCityAvailable(cityId) {
            citizens.validateResidentMeal(cityId, meal)
            mutate(cityId) { current ->
                citizens.validateResidentMeal(cityId, meal)
                CivsResidentLife.finishMeal(current, meal, foods)
            }.also { receipt ->
                // Observability must not turn a durable success into a failed meal.
                receipt.thenAccept { committed ->
                    val worker = committed.workers.firstOrNull { it.id == meal.workerId }
                    CivsDiagnostics.event("resident_meal", "city" to cityId, "worker" to meal.workerId,
                        "building" to meal.buildingId, "source" to meal.source, "outcome" to "accepted",
                        "meals_before" to meal.mealsBefore, "meals_after" to worker?.life?.meals,
                        "hunger_after" to worker?.let(CivsResidents::hunger), "material" to meal.material,
                        "amount" to meal.amount, "nutrition" to meal.nutrition, "revision" to committed.revision)
                }
            }
        }

    fun finishRest(cityId: UUID, workerId: UUID, awakeMinute: Long): CompletableFuture<Settlement> =
        afterCityAvailable(cityId) {
            citizens.validateResidentRest(cityId, workerId)
            mutate(cityId) { current ->
                citizens.validateResidentRest(cityId, workerId)
                CivsResidentLife.finishRest(current, workerId, awakeMinute)
            }
        }

    fun finishLesson(cityId: UUID, proof: CivResidentLessonProof): CompletableFuture<Settlement> =
        afterCityAvailable(cityId) {
            citizens.validateResidentLesson(cityId, proof)
            mutate(cityId) { current ->
                citizens.validateResidentLesson(cityId, proof)
                CivsResidentLife.finishLesson(current, proof)
            }
        }

    fun pulsePopulation() {
        val active = cities.values.filter(::populationActive)
        populationMinuteAt.keys.retainAll(active.mapTo(hashSetOf(), Settlement::id))
        val at = now()
        active.forEach { populationMinuteAt.putIfAbsent(it.id, at + 60_000) }
        val city = active.firstOrNull { it.id !in populationPending && at >= populationMinuteAt.getValue(it.id) } ?: return
        populationPending += city.id
        runCatching { afterPopulationAvailable(city.id) {
            val current = cities[city.id]
            if (current == null || !populationActive(current) || now() < (populationMinuteAt[city.id] ?: Long.MAX_VALUE))
                return@afterPopulationAvailable CompletableFuture.completedFuture(Unit)
            val awake = citizens.awakeResidentIds(city.id)
            populationMinuteAt[city.id] = now() + 60_000 // Never replay minutes spent waiting for a save.
            mutate(city.id) { before ->
                val advanced = CivsPopulation.advance(before, awake, CivsNames::name)
                val next = if (before.autonomy != null) CivsAutonomy.activeMinute(advanced, CivsNames::name) else advanced
                if (next == before) before else next.copy(revision = before.revision + 1)
            }.thenApply { saved ->
                CivsDiagnostics.event("resident_life", "city" to city.id, "awake" to awake.size,
                    "population" to saved.workers.size, "children" to saved.workers.count(CivsResidents::isChild),
                    "hungry" to saved.workers.count { CivsResidents.hunger(it) <= 6 })
                Unit
            }
        } }.onSuccess { future -> future.whenCompleteSync(tasks) { _, failure ->
            populationPending -= city.id
            if (failure != null) log("advance resident life ${city.id}", failure, city.id)
        } }.onFailure { populationPending -= city.id; log("advance resident life ${city.id}", it, city.id) }
    }

    fun pulseAutoAssignments() {
        val city = cities.values.firstOrNull { city ->
            city.autonomy?.paused != true && city.id !in busy && city.id !in unhealthy && city.pendingDeletion == null &&
                CivsRules.needsAutoAssignment(city) { citizens.canReassign(city.id, it) } &&
                settlementActive(city)
        } ?: return
        runCatching { mutate(city.id) { current ->
            CivsRules.autoAssign(current, current.owner, now()) { citizens.canReassign(current.id, it) }
        } }.onSuccess { future ->
            future.whenCompleteSync(tasks) { saved, failure ->
                if (failure != null) log("auto assign residents ${city.id}", failure, city.id)
                else CivsDiagnostics.event("workers_auto_assigned", "city" to city.id,
                    "assigned" to requireNotNull(saved).workers.count { worker -> city.workers.firstOrNull { it.id == worker.id }?.assignment != worker.assignment })
            }
        }.onFailure { log("auto assign residents ${city.id}", it, city.id) }
    }

    private fun now() = clock()
    private fun log(context: String, error: Throwable, cityId: UUID) =
        CivsDiagnostics.record(Level.WARNING, cityId, "ArcCivs could not $context", plugin.logger, error)
}
