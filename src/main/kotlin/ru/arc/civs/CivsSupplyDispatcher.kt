package ru.arc.civs

import org.bukkit.Bukkit
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** Actual unreserved room. Empty native slots are shared by all materials; virtual room is units. */
internal data class CivSupplyCapacity(val emptySlots: Int, val partialRoom: Map<String, Long>, val virtualRoom: Long? = null)
internal data class CivSupplyStore(val id: UUID, val entrance: CivPosition, val control: CivPosition,
    val stock: Map<String, Long>, val desiredStock: Map<String, Long>, val outputMaterial: String?, val capacity: CivSupplyCapacity,
    val type: BuildingType? = null)
internal data class CivSupplyCourier(val workerId: UUID, val warehouseId: UUID, val cycle: Long,
    val position: CivPosition, val bagRoom: Map<String, Long>)
internal data class CivSupplyWorkerSource(val workerId: UUID, val buildingId: UUID,
    val meeting: CivPosition, val excess: Map<String, Long>)

/**
 * Owner-captured observations, not a second inventory authority. inputKey belongs only to owner
 * validation and is removed before worker submission. retained includes durable carried cargo;
 * requests.inTransit already counts that cargo. All capacity values precede dispatch reservations.
 */
internal data class CivSupplySnapshot(val cityId: UUID, val inputKey: Any,
    val stores: Map<UUID, CivSupplyStore>, val requests: List<CivSupplyRequest>,
    val couriers: List<CivSupplyCourier>, val workerSources: List<CivSupplyWorkerSource>,
    val retained: Map<UUID, CivCourierPlan>, val stackSizes: Map<String, Int>,
    val constructionTargets: Map<UUID, UUID> = emptyMap(),
    val craftWorkers: List<CivCraftWorker> = emptyList(), val craftJobs: Map<UUID, CivCraftJob> = emptyMap(),
    val craftEpoch: UUID = UUID.randomUUID(), val constructionId: UUID? = null,
    val stockTargets: Map<UUID, Map<String, Long>> = emptyMap(), val autonomousStock: Boolean = false) {
    internal fun detached() = copy(inputKey = Unit,
        stores = stores.mapValues { (_, value) -> value.copy(stock = value.stock.toMap(), desiredStock = value.desiredStock.toMap(),
            capacity = value.capacity.copy(partialRoom = value.capacity.partialRoom.toMap())) },
        requests = requests.toList(), couriers = couriers.map { it.copy(bagRoom = it.bagRoom.toMap()) },
        workerSources = workerSources.map { it.copy(excess = it.excess.toMap()) }, retained = retained.toMap(),
        stackSizes = stackSizes.toMap(), constructionTargets = constructionTargets.toMap(),
        craftWorkers = craftWorkers.map { it.copy(carried = it.carried.toMap()) },
        craftJobs = craftJobs.mapValues { it.value.copy(inputs = it.value.inputs.toMap()) },
        stockTargets = stockTargets.mapValues { it.value.toMap() })
}

/**
 * Owner-thread scheduling, acceptance and temporary reservations. Capture returns a resumable
 * owner-only supplier: null means one bounded step finished, a snapshot means capture completed.
 * Validation runs on the owner after pure computation, against relevant inputs rather than the
 * city's unrelated revision. Physical pickup/dropoff remain the existing durable receipts.
 */
internal class CivsSupplyDispatcher(
    private val tasks: LifecycleTaskScope,
    nativeWork: BudgetedTaskQueue?,
    private val capture: (UUID, Map<UUID, CivCourierPlan>) -> (() -> CivSupplySnapshot?),
    private val validate: (CivSupplySnapshot, Map<UUID, CivCourierPlan>) -> Boolean,
    executor: Executor? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private class Job(val generation: Long, var step: (() -> CivSupplySnapshot?)?) {
        val cancelled = AtomicBoolean()
        var native: CompletableFuture<Unit>? = null
        var snapshot: CivSupplySnapshot? = null
        var result: CompletableFuture<CivSupplyAllocation>? = null
        fun cancel() {
            cancelled.set(true); native?.cancel(false); result?.cancel(false)
            step = null; snapshot = null
        }
    }
    private data class CaptureSummary(val generation: Long, val at: Long, val stores: Int,
        val requests: Int, val couriers: Int, val workerSources: Int)
    private class City {
        var generation = 0L
        var requested = true
        var after = Long.MIN_VALUE
        var failure: String? = null
        var job: Job? = null
        var plans = emptyMap<UUID, CivCourierPlan>()
        var crafts = emptyMap<UUID, CivCraftJob>()
        var craftObservation: CivSupplySnapshot? = null
        var captured: CaptureSummary? = null
        var acceptedGeneration: Long? = null
        val ages = hashMapOf<CivSupplyTask, Long>()
        val lastAssigned = hashMapOf<UUID, Long>()
        val blockedWorkers = hashMapOf<UUID, Long>()
        val blockedTrips = hashMapOf<CivSupplyTrip, Long>()
    }
    private val owner = Thread.currentThread()
    private val epoch = tasks.token()
    private val ownsWork = nativeWork == null
    private val nativeWork = nativeWork ?: BudgetedTaskQueue(tasks, 64, 1_000_000L, System::nanoTime, MAXIMUM_JOBS)
    private val cities = linkedMapOf<UUID, City>()
    private val ownedExecutor = if (executor == null) BoundedAsyncExecutor("ArcCivs-supply", 1, MAXIMUM_JOBS) else null
    private val executor = executor ?: requireNotNull(ownedExecutor)
    private var closed = false
    private var terminal = CompletableFuture.completedFuture(Unit)

    /** Explicit relevant-input/periodic updates coalesce. Reading a cached plan never starts work. */
    fun request(cityId: UUID): Boolean {
        checkOwner()
        if (closed || !tasks.isCurrent(epoch)) return false
        cities.getOrPut(cityId, ::City).requested = true
        return true
    }

    fun plan(cityId: UUID, workerId: UUID): CivCourierPlan? { checkOwner(); return cities[cityId]?.plans?.get(workerId) }
    fun retained(cityId: UUID): Map<UUID, CivCourierPlan> { checkOwner(); return cities[cityId]?.plans?.toMap().orEmpty() }
    fun craftJobs(cityId: UUID): Map<UUID, CivCraftJob> { checkOwner(); return cities[cityId]?.crafts?.toMap().orEmpty() }
    fun craftObservation(cityId: UUID): CivSupplySnapshot? { checkOwner(); return cities[cityId]?.craftObservation }
    fun releaseCraft(cityId: UUID, workerId: UUID, jobId: UUID) {
        checkOwner()
        val city = cities[cityId] ?: return
        if (city.crafts[workerId]?.id != jobId) return
        city.crafts = city.crafts - workerId
        city.generation++; city.job?.cancel(); city.job = null
        city.requested = true; city.after = Long.MIN_VALUE
    }
    fun failure(cityId: UUID): String? { checkOwner(); return cities[cityId]?.failure }
    fun cityIds(): Set<UUID> { checkOwner(); return cities.keys.toSet() }

    /** Owner observations only: no capture, acceptance, future joins or reservation changes. */
    internal fun debug(cityId: UUID?, workerId: UUID? = null): Map<String, Any?> {
        checkOwner()
        val now = clock()
        fun point(p: CivPosition) = "${p.x},${p.y},${p.z}"
        fun <T> sample(values: Collection<T>, describe: (T) -> Any?): Map<String, Any?> = mapOf(
            "total" to values.size, "truncated" to (values.size > 8), "entries" to values.take(8).map(describe))
        val selected = cities.entries.filter { cityId == null || it.key == cityId }
        fun selectedPlans(city: City) = city.plans.entries.filter {
            workerId == null || it.key == workerId || it.value.cargo.sourceWorkerId == workerId }
        return sample(selected) { (id, city) ->
            val plans = selectedPlans(city)
            val job = city.job
            val snapshot = job?.snapshot
            mapOf("city" to id.toString(), "generation" to city.generation,
                "requested" to city.requested, "failure" to city.failure,
                "retryAfterMillis" to if (city.after > now) city.after - now else 0L,
                "acceptedGeneration" to city.acceptedGeneration,
                "capture" to city.captured?.let { mapOf("generation" to it.generation,
                    "ageMillis" to (now - it.at).coerceAtLeast(0), "generationStale" to (it.generation != city.generation),
                    "stores" to it.stores, "requests" to it.requests, "couriers" to it.couriers,
                    "workerSources" to it.workerSources) },
                "job" to job?.let { mapOf("generation" to it.generation,
                    "phase" to when { it.step != null -> "capturing"; it.result != null -> "allocating"; else -> "captured" },
                    "cancelled" to it.cancelled.get(), "nativeDone" to it.native?.isDone,
                    "allocationDone" to it.result?.isDone, "snapshotPresent" to (it.snapshot != null)) },
                "assignments" to sample(plans) { (worker, plan) ->
                    val cargo = plan.cargo
                    mapOf("worker" to worker.toString(), "shipment" to cargo.id.toString(),
                        "carrying" to plan.carrying, "inventoryBacked" to cargo.inventoryBacked, "stockResource" to cargo.stockResource,
                        "material" to cargo.material, "amount" to cargo.amount,
                        "warehouse" to cargo.warehouseId.toString(), "sourceBuilding" to cargo.sourceBuildingId.toString(),
                        "sourceWorker" to cargo.sourceWorkerId?.toString(), "sourceEntrance" to point(cargo.sourceEntrance),
                        "targetBuilding" to cargo.targetBuildingId.toString(), "destinationBuilding" to plan.destinationBuildingId.toString(),
                        "destinationEntrance" to point(plan.destinationEntrance),
                        "assignedAgeMillis" to city.lastAssigned[worker]?.let { (now - it).coerceAtLeast(0) }) },
                "reservations" to mapOf("waitingPickup" to plans.count { !it.value.carrying },
                    "carrying" to plans.count { it.value.carrying }, "units" to plans.sumOf { it.value.cargo.amount }),
                "crafts" to sample(city.crafts.values.filter { workerId == null || it.workerId == workerId }) { craft ->
                    val carried = city.craftObservation?.craftWorkers?.firstOrNull { it.workerId == craft.workerId }?.carried.orEmpty()
                    val missing = craft.inputs.mapValues { (material, count) -> (count - (carried[material] ?: 0L)).coerceAtLeast(0) }
                        .filterValues { it > 0 }
                    mapOf("worker" to craft.workerId.toString(), "jobId" to craft.id.toString(), "cycle" to craft.cycle,
                        "building" to craft.buildingId.toString(), "recipe" to craft.recipeId, "recipeVersion" to craft.version,
                        "inputs" to craft.inputs, "output" to mapOf(craft.material to craft.amount),
                        "demand" to craft.demandMaterial, "target" to craft.targetBuildingId.toString(),
                        "construction" to craft.constructionId?.toString(), "epoch" to city.craftObservation?.craftEpoch?.toString(),
                        "missingCarriedAtCapture" to missing,
                        "statusAtCapture" to if (missing.isEmpty()) "crafting" else "waiting-materials") },
                "capturedRequests" to snapshot?.let { sample(it.requests) { request -> mapOf(
                    "building" to request.buildingId.toString(), "material" to request.material,
                    "needed" to request.needed, "inTransit" to request.inTransit, "construction" to request.construction) } },
                "capturedWorkerSources" to snapshot?.let { sample(it.workerSources.filter {
                    workerId == null || it.workerId == workerId }) { source -> mapOf("worker" to source.workerId.toString(),
                    "building" to source.buildingId.toString(), "meeting" to point(source.meeting),
                    "excess" to sample(source.excess.entries) { mapOf("material" to it.key, "amount" to it.value) }) } },
                "waitingTasks" to city.ages.size,
                "blockedWorkers" to sample(city.blockedWorkers.entries.filter { workerId == null || it.key == workerId }) {
                    mapOf("worker" to it.key.toString(), "remainingMillis" to (it.value - now).coerceAtLeast(0)) },
                "blockedTrips" to sample(city.blockedTrips.entries.filter { workerId == null || it.key.worker == workerId }) {
                    mapOf("source" to it.key.source.toString(), "sourceWorker" to it.key.worker?.toString(),
                        "target" to it.key.target.toString(), "material" to it.key.material,
                        "remainingMillis" to (it.value - now).coerceAtLeast(0)) })
        } + mapOf("source" to "owner-cache", "closed" to closed,
            "pendingJobs" to selected.count { it.value.job != null },
            "assignmentCount" to selected.sumOf { selectedPlans(it.value).size })
    }

    /** Release only before pickup or after a definitive receipt outcome; unknown commits stay reserved. */
    fun release(cityId: UUID, workerId: UUID, failed: Boolean = false) {
        checkOwner()
        val city = cities[cityId] ?: return
        val old = city.plans[workerId]
        if (failed && old != null && !old.carrying) {
            city.blockedWorkers[workerId] = clock() + REFRESH_MILLIS
            city.blockedTrips[CivSupplyTrip.of(old)] = clock() + REFRESH_MILLIS
            CivsDiagnostics.event("supply_dispatch_released", "city" to cityId, "worker" to workerId,
                "shipment" to old.cargo.id, "reason" to "route-or-transfer-failed")
        }
        city.plans = city.plans - workerId
        city.generation++
        city.job?.cancel(); city.job = null
        city.requested = true; city.after = Long.MIN_VALUE
    }

    fun remove(cityId: UUID) { checkOwner(); cities.remove(cityId)?.job?.cancel() }

    /** Retire every city generation during reload without replacing the shared work resources. */
    fun clear() {
        checkOwner()
        cities.values.forEach { it.job?.cancel() }
        cities.clear()
    }

    /** No incomplete future is awaited, and every native cursor shares the injected Core allowance. */
    fun pulse() {
        checkOwner()
        if (closed) return
        if (!tasks.isCurrent(epoch)) { close(); return }
        val now = clock()
        for ((id, city) in cities.toMap()) poll(id, city, now)
        var running = cities.values.count { it.job != null }
        for (id in cities.keys.toList()) {
            if (running >= MAXIMUM_JOBS) break
            val city = cities.getValue(id)
            if (!city.requested || city.job != null || now < city.after) continue
            start(id, city, now)
            if (city.job != null) running++
            // Cached city state does not occupy admission; dirty cities rotate even beyond 48.
            cities.remove(id); cities[id] = city
        }
        nativeWork.drainAvailable { Bukkit.getCurrentTick().toLong() }
        for ((id, city) in cities.toMap()) poll(id, city, now)
    }

    private fun start(id: UUID, city: City, now: Long) {
        city.requested = false
        city.blockedWorkers.entries.removeIf { it.value <= now }
        city.blockedTrips.entries.removeIf { it.value <= now }
        try {
            val job = Job(city.generation, capture(id, city.plans.toMap()))
            city.job = job
            job.native = nativeWork.submit({ current(id, city, job) }) {
                val snapshot = requireNotNull(job.step).invoke()
                if (snapshot == null) false else {
                    require(snapshot.cityId == id) { "Supply capture returned another city" }
                    city.captured = CaptureSummary(job.generation, clock(), snapshot.stores.size,
                        snapshot.requests.size, snapshot.couriers.size, snapshot.workerSources.size)
                    job.snapshot = snapshot; job.step = null; true
                }
            }
        } catch (failure: RuntimeException) { fail(id, city, failure, now) }
    }

    private fun poll(id: UUID, city: City, now: Long) {
        val job = city.job ?: return
        if (!current(id, city, job)) { job.cancel(); city.job = null; return }
        try {
            if (job.native?.isDone == true) {
                requireNotNull(job.native).join() // Already complete; never waits on the owner.
                job.native = null
                val data = requireNotNull(job.snapshot).detached()
                val ages = city.ages.toMap()
                val lastAssigned = city.lastAssigned.toMap()
                val workers = city.blockedWorkers.keys.toSet()
                val trips = city.blockedTrips.keys.toSet()
                val cancelled = job.cancelled
                job.result = CompletableFuture.supplyAsync({
                    allocateCivSupplies(data, ages, lastAssigned, workers, trips, now, cancelled)
                }, executor)
            }
            val future = job.result
            if (future?.isDone != true) return
            val result = future.join()
            val snapshot = requireNotNull(job.snapshot)
            if (validate(snapshot, result.plans)) {
                if (!current(id, city, job)) return
                if (city.plans != result.plans) {
                    val retained = result.plans.count { (worker, plan) -> city.plans[worker] == plan }
                    CivsDiagnostics.event("supply_dispatch_accepted", "city" to id, "assignments" to result.plans.size,
                        "retained" to retained, "new" to result.plans.size - retained,
                        "sources" to snapshot.stores.size + snapshot.workerSources.size, "requests" to snapshot.requests.size)
                }
                for ((worker, plan) in result.plans) if (city.plans[worker]?.cargo?.id != plan.cargo.id)
                    city.lastAssigned[worker] = now
                city.plans = result.plans.toMap()
                city.crafts = result.crafts.toMap()
                city.craftObservation = snapshot.takeIf { it.craftWorkers.isNotEmpty() }
                city.acceptedGeneration = job.generation
                city.ages.keys.retainAll(result.seen)
                for (key in result.seen) city.ages.putIfAbsent(key, now)
                result.served.forEach(city.ages::remove)
                city.lastAssigned.keys.retainAll(snapshot.couriers.mapTo(hashSetOf()) { it.workerId } + snapshot.retained.keys)
                failure(id, city, null)
            } else {
                failure(id, city, "stale-input")
                city.requested = true
            }
            job.cancel(); city.job = null; city.after = now + COALESCE_MILLIS
        } catch (failure: RuntimeException) { fail(id, city, failure, now) }
    }

    private fun current(id: UUID, city: City, job: Job) = !closed && tasks.isCurrent(epoch) &&
        cities[id] === city && city.job === job && city.generation == job.generation && !job.cancelled.get()

    private fun fail(id: UUID, city: City, failure: RuntimeException, now: Long) {
        var cause: Throwable = failure
        while (cause is CompletionException && cause.cause != null) cause = requireNotNull(cause.cause)
        failure(id, city, when (cause) {
            is CancellationException -> "cancelled"
            is RejectedExecutionException -> "planner-busy"
            is CivSupplyBudget -> "allocation-budget"
            is CivsRejection -> cause.reason
            else -> "planner-error"
        })
        city.job?.cancel(); city.job = null; city.requested = true
        city.after = now + if (cause is CivsRejection && cause.reason == "city-busy") COALESCE_MILLIS else REFRESH_MILLIS
    }

    private fun failure(id: UUID, city: City, reason: String?) {
        if (city.failure == reason) return
        CivsDiagnostics.event(if (reason == null) "supply_dispatch_recovered" else "supply_dispatch_failure",
            "city" to id, "reason" to (reason ?: city.failure))
        city.failure = reason
    }

    override fun close() {
        checkOwner()
        if (closed) return
        closed = true
        clear()
        if (ownsWork) nativeWork.close()
        terminal = ownedExecutor?.closeAsync() ?: CompletableFuture.completedFuture(Unit)
    }
    fun drained(): CompletableFuture<Unit> = terminal.copy()
    private fun checkOwner() = check(Thread.currentThread() === owner) { "Supply dispatcher requires its owning thread" }

    companion object {
        const val MAXIMUM_JOBS = 48
        const val COALESCE_MILLIS = 250L
        const val REFRESH_MILLIS = 5_000L
    }
}

internal data class CivSupplyTask(val source: UUID?, val worker: UUID?, val target: UUID, val material: String, val construction: Boolean)
internal data class CivSupplyTrip(val source: UUID, val worker: UUID?, val target: UUID, val material: String) {
    companion object { fun of(plan: CivCourierPlan) = CivSupplyTrip(plan.cargo.sourceBuildingId, plan.cargo.sourceWorkerId,
        plan.destinationBuildingId, plan.cargo.material) }
}
internal data class CivSupplyAllocation(val plans: Map<UUID, CivCourierPlan>, val seen: Set<CivSupplyTask>, val served: Set<CivSupplyTask>,
    val crafts: Map<UUID, CivCraftJob> = emptyMap())
private class CivSupplyBudget : RuntimeException()

/** Shared slot arithmetic: reserving a partial new stack leaves room only for that material. */
internal class CivSupplyRoom(capacity: CivSupplyCapacity, private val stacks: Map<String, Int>) {
    private var empty = capacity.emptySlots.coerceAtLeast(0)
    private val partial = capacity.partialRoom.mapValues { it.value.coerceAtLeast(0) }.toMutableMap()
    private var virtual = capacity.virtualRoom?.coerceAtLeast(0)
    /** Only a complete plain material removal proves which captured slots become empty.
     * Partial debits deliberately receive no optimistic slot credit. */
    fun removeAll(material: String, stock: Long) {
        if (stock <= 0) return
        if (virtual != null) { virtual = Math.addExact(requireNotNull(virtual), stock); return }
        val room = partial.remove(material) ?: 0L
        empty = Math.addExact(empty, ((stock + room) / stack(material)).toInt())
    }
    fun fits(material: String): Long = virtual ?: ((partial[material] ?: 0) + empty.toLong() * stack(material))
    fun take(material: String, amount: Long) {
        if (virtual != null) { virtual = (requireNotNull(virtual) - amount).coerceAtLeast(0); return }
        val room = partial[material] ?: 0
        val used = minOf(amount, room)
        partial[material] = room - used
        val remaining = amount - used
        if (remaining <= 0) return
        val stack = stack(material)
        val slots = ((remaining - 1) / stack + 1).coerceAtMost(empty.toLong()).toInt()
        empty -= slots
        partial[material] = (slots.toLong() * stack - remaining).coerceAtLeast(0)
    }
    fun block() { empty = 0; partial.clear(); if (virtual != null) virtual = 0 }
    private fun stack(material: String) = (stacks[material] ?: 1).coerceAtLeast(1)
}

/**
 * Pure deterministic city-wide greedy allocation. Existing construction priority is preserved;
 * age rotates ordinary requests/collections, and least recently assigned couriers choose first.
 * ponytail: one pass per at-most-48 courier, not an unproven global route optimiser.
 */
internal fun allocateCivSupplies(snapshot: CivSupplySnapshot, ages: Map<CivSupplyTask, Long> = emptyMap(),
    lastAssigned: Map<UUID, Long> = emptyMap(), blockedWorkers: Set<UUID> = emptySet(),
    blockedTrips: Set<CivSupplyTrip> = emptySet(), now: Long = 0, cancelled: AtomicBoolean = AtomicBoolean()): CivSupplyAllocation {
    require(snapshot.inputKey === Unit) { "Owner validation metadata must not enter the allocator" }
    if (snapshot.couriers.size > 48 || snapshot.workerSources.size > 48) throw CivSupplyBudget()
    val crafts = CivsCarpentry.allocate(snapshot, cancelled)
    val stores = snapshot.stores.mapValues { (id, store) -> store.copy(desiredStock = store.desiredStock + crafts.required[id].orEmpty()) }
    val stock = stores.mapValues { it.value.stock.toMutableMap() }
    val sources = snapshot.workerSources.associateBy { it.workerId }
    val excess = sources.mapValues { it.value.excess.toMutableMap() }
    crafts.jobs.values.forEach { job -> job.inputs.forEach { (material, count) ->
        val newlyReserved = (count - (snapshot.craftJobs[job.workerId]?.inputs?.get(material) ?: 0)).coerceAtLeast(0)
        excess[job.workerId]?.let { it[material] = ((it[material] ?: 0) - newlyReserved).coerceAtLeast(0) }
    } }
    val rooms = stores.mapValues { CivSupplyRoom(it.value.capacity, snapshot.stackSizes) }
    crafts.jobs.values.forEach { rooms[it.buildingId]?.take(it.material, it.amount) }
    // Inputs can occupy the output's future slot: the carpenter must physically pick them
    // all up before working. They still compete with each other for actual native slots.
    val actualRooms = stores.mapValues { CivSupplyRoom(it.value.capacity, snapshot.stackSizes) }
    fun craftInput(building: UUID, material: String) = (crafts.required[building]?.get(material) ?: 0L) > 0
    fun roomFor(building: UUID, material: String): Long = minOf(actualRooms[building]?.fits(material) ?: 0L,
        if (craftInput(building, material)) Long.MAX_VALUE else rooms[building]?.fits(material) ?: 0L)
    val couriers = snapshot.couriers.associateBy { it.workerId }
    val busySources = hashSetOf<UUID>()
    fun task(request: CivSupplyRequest) = CivSupplyTask(null, null, request.buildingId, request.material, request.construction)
    val requests = (snapshot.requests + crafts.requests).associateBy(::task)
    val demand = requests.mapValues { (_, value) -> (value.needed - value.inTransit).coerceAtLeast(0) }.toMutableMap()
    val seen = requests.filterValues { it.needed > it.inTransit }.keys.toMutableSet()
    val served = hashSetOf<CivSupplyTask>()
    val assigned = linkedMapOf<UUID, CivCourierPlan>()
    val rejectedRetained = hashSetOf<UUID>()
    var checks = 0
    fun checkBudget() {
        if (cancelled.get()) throw CancellationException("Supply calculation retired")
        if (++checks > 100_000) throw CivSupplyBudget()
    }
    fun deliveryTask(plan: CivCourierPlan): CivSupplyTask? = requests.keys.firstOrNull {
        it.material == plan.cargo.material && (it.target == plan.destinationBuildingId ||
            it.construction && plan.destinationBuildingId in snapshot.constructionTargets.values)
    }
    fun remaining(plan: CivCourierPlan): Long {
        val cargo = plan.cargo
        return if (cargo.sourceWorkerId != null) excess[cargo.sourceWorkerId]?.get(cargo.material) ?: 0
        else {
            val count = stock[cargo.sourceBuildingId]?.get(cargo.material) ?: 0
            val reserve = if (cargo.sourceBuildingId == cargo.warehouseId) 0 else
                stores[cargo.sourceBuildingId]?.desiredStock?.get(cargo.material) ?: 0
            (count - reserve).coerceAtLeast(0)
        }
    }
    fun reserve(worker: UUID, plan: CivCourierPlan, key: CivSupplyTask?, carried: Boolean = false) {
        assigned[worker] = plan
        val cargo = plan.cargo
        val futureRoom = rooms[plan.destinationBuildingId].takeUnless { craftInput(plan.destinationBuildingId, cargo.material) }
        for (room in listOfNotNull(actualRooms[plan.destinationBuildingId], futureRoom)) {
            if (carried && room.fits(cargo.material) < cargo.amount) room.block()
            else room.take(cargo.material, cargo.amount)
        }
        if (carried) return
        val quantities = if (cargo.sourceWorkerId != null) excess[cargo.sourceWorkerId] else stock[cargo.sourceBuildingId]
        quantities?.set(cargo.material, (quantities[cargo.material] ?: 0) - cargo.amount)
        cargo.sourceWorkerId?.let(busySources::add)
        if (key != null && key in demand) demand[key] = (demand.getValue(key) - cargo.amount).coerceAtLeast(0)
    }
    // Durable carried cargo wins capacity even when another player has filled its destination.
    val retained = snapshot.retained.entries.sortedWith(compareByDescending<Map.Entry<UUID, CivCourierPlan>> { it.value.carrying }
        .thenBy { it.key.toString() })
    for ((worker, plan) in retained) {
        checkBudget()
        if (plan.carrying) { reserve(worker, plan, null, true); continue }
        // A rejected old reservation must first disappear at the owner. Do not silently reuse
        // its stable shipment ID with a smaller amount in this result. Observed worker movement is harmless.
        rejectedRetained += worker
        val courier = couriers[worker] ?: continue
        val cargo = plan.cargo
        val key = if (cargo.sourceBuildingId == cargo.warehouseId) deliveryTask(plan) else null
        if (courier.warehouseId != cargo.warehouseId || cargo.amount !in 1..CivsLogistics.MAX_CARGO ||
            cargo.id != CivsLogistics.shipmentId(snapshot.cityId, worker, courier.cycle, cargo.sourceBuildingId,
                cargo.targetBuildingId, cargo.material, cargo.sourceWorkerId) ||
            remaining(plan) < cargo.amount || (courier.bagRoom[cargo.material] ?: 0) < cargo.amount ||
            roomFor(plan.destinationBuildingId, cargo.material) < cargo.amount ||
            cargo.sourceWorkerId?.let { sources[it]?.buildingId != cargo.sourceBuildingId } == true ||
            cargo.sourceWorkerId in busySources || key != null && (demand[key] ?: 0) < cargo.amount ||
            cargo.sourceBuildingId == cargo.warehouseId && key == null) continue
        reserve(worker, plan, key)
        rejectedRetained.remove(worker)
    }
    data class Candidate(val plan: CivCourierPlan, val key: CivSupplyTask, val distance: Double)
    val ordered = snapshot.couriers.sortedWith(compareBy<CivSupplyCourier> { lastAssigned[it.workerId] ?: Long.MIN_VALUE }
        .thenBy { it.cycle }.thenBy { it.workerId.toString() })
    fun distance(a: CivPosition, b: CivPosition): Double = sqrt((a.x.toDouble() - b.x).let { it * it } +
        (a.y.toDouble() - b.y).let { it * it } + (a.z.toDouble() - b.z).let { it * it })
    for (courier in ordered) {
        checkBudget()
        if (courier.workerId in assigned || courier.workerId in blockedWorkers || courier.workerId in rejectedRetained) continue
        val warehouse = stores[courier.warehouseId] ?: continue
        val candidates = mutableListOf<Candidate>()
        fun offer(source: CivSupplyStore, target: CivSupplyStore, material: String, quantity: Long,
                  key: CivSupplyTask, producer: CivSupplyWorkerSource? = null) {
            checkBudget()
            val from = producer?.meeting ?: source.control
            if (source.id == target.id || distance(from, target.control) > CivsRules.MAX_WORK_ROUTE_DISTANCE) return
            if (producer != null && (producer.workerId in busySources || producer.workerId == courier.workerId)) return
            seen += key
            val amount = minOf(CivsLogistics.MAX_CARGO, quantity, courier.bagRoom[material] ?: 0,
                roomFor(target.id, material))
            if (amount <= 0) return
            val cargo = CivCargo(CivsLogistics.shipmentId(snapshot.cityId, courier.workerId, courier.cycle,
                source.id, target.id, material, producer?.workerId), warehouse.id, source.id, target.id,
                producer?.meeting ?: source.entrance, material, amount, sourceWorkerId = producer?.workerId,
                stockResource = if (snapshot.autonomousStock) Resource.valueOf(material) else null)
            val plan = CivCourierPlan(cargo, target.id, target.entrance, false)
            if (CivSupplyTrip.of(plan) in blockedTrips) return
            candidates += Candidate(plan, key, distance(courier.position, from) + distance(from, target.control))
        }
        for ((key, needed) in demand) {
            val targetId = if (key.construction) snapshot.constructionTargets[warehouse.id] ?: key.target else key.target
            val target = stores[targetId] ?: continue
            offer(warehouse, target, key.material, minOf(needed, stock[warehouse.id]?.get(key.material) ?: 0), key)
        }
        for (source in stores.values) {
            if (source.outputMaterial == null) continue
            // A workshop retains its inputs; every other plain excess can leave,
            // including incidental dirt/stone rather than only its advertised output.
            for ((material, available) in stock[source.id].orEmpty()) {
                val count = (available - (source.desiredStock[material] ?: 0)).coerceAtLeast(0)
                if (count > 0) offer(source, warehouse, material, count,
                    CivSupplyTask(source.id, null, warehouse.id, material, false))
            }
        }
        for (producer in sources.values) {
            val source = stores[producer.buildingId] ?: continue
            for ((material, count) in excess[producer.workerId].orEmpty()) if (count > 0)
                offer(source, warehouse, material, count, CivSupplyTask(source.id, producer.workerId, warehouse.id, material, false), producer)
        }
        val selected = candidates.minWithOrNull(compareBy<Candidate> { if (it.key.construction) 0 else 1 }
            .thenBy { ages[it.key] ?: now }.thenBy { it.distance }.thenBy { it.plan.cargo.id.toString() }) ?: continue
        reserve(courier.workerId, selected.plan, selected.key)
        served += selected.key
    }
    return CivSupplyAllocation(assigned.toMap(), seen.toSet(), served.toSet(), crafts.jobs)
}
