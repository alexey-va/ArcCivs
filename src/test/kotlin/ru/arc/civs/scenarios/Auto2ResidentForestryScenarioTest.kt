package ru.arc.civs.scenarios

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.civs.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletionException

/** Explicit local one-producer replay, with self delivery or an actual warehouse actor.
 * Private items are never source fixtures or CI downloads. */
class Auto2ResidentForestryScenarioTest : FunSpec({
    val statePath = System.getenv("CIVS_SCENARIO_CITY_STATE")
    for (withCourier in listOf(false, true)) test("actual resident forestry with warehouse courier=$withCourier preserves native receipts and stock")
        .config(enabled = statePath != null) {
            val source = Gson().fromJson(Files.readString(Path.of(requireNotNull(statePath))), Settlement::class.java)
            val resident = source.workers.single { it.assignment?.let(source::building)?.type == BuildingType.FORESTER }
            val mapRoot = Path.of(requireNotNull(System.getenv("CIVS_SCENARIO_CITY_MAP")) {
                "CIVS_SCENARIO_CITY_MAP is required for the explicitly enabled resident replay"
            })
            val facts = Path.of(requireNotNull(System.getenv("CIVS_SCENARIO_NATIVE_FACTS")) {
                "CIVS_SCENARIO_NATIVE_FACTS is required for the explicitly enabled resident replay"
            })
            val capturedAt = JsonParser.parseString(Files.readString(mapRoot.resolve("terrain.json")))
                .asJsonObject.get("capturedAt").asLong
            val capture = RecordedNativeMap.fromPublicRle(mapRoot, facts,
                CivBox(CivPosition(if (withCourier) -1920 else -1904, 56, -2784), CivPosition(-1832, 183, -2657)))
            val nativeRoot = CivPosition(-1884, 64, -2688)
            val lowerStanding = CivPosition(-1887, 63, -2687)
            val portable = RecordedNativeMap.fromResourceRle("/scenarios/auto2-forest-roots-surface.json",
                "e21bd41bb477a33fe64a3a80072bff1a366e8755c9889646b586a19c7cd8f291")
            for (point in listOf(nativeRoot, lowerStanding, lowerStanding.copy(y = lowerStanding.y - 1),
                lowerStanding.copy(y = lowerStanding.y + 1))) {
                capture.states.getValue(point) shouldBe portable.states.getValue(point)
            }

            residentRuntime(workerId = resident.id, server = CivsWorksiteServerFixture(),
                worldFactory = { CapturedResidentWorld(capture) }) { h ->
                // Retire the origin-only fixture actor before the captured-world production clock starts.
                h.viewing = false
                h.owner.pulse(1_001)
                h.owner.activeCount() shouldBe 0
                // Project only the exercised roles and complete family links. Keep their actual homes/items/stock.
                val selected = source.workers.filter { it.id == resident.id || withCourier &&
                    it.assignment?.let(source::building)?.type == BuildingType.WAREHOUSE }
                val selectedIds = selected.mapTo(hashSetOf()) { it.id }
                val courierId = selected.singleOrNull { it.assignment?.let(source::building)?.type == BuildingType.WAREHOUSE }?.id
                selected.size shouldBe if (withCourier) 2 else 1
                h.city = source.copy(worldId = h.world.uid,
                    workers = selected,
                    families = source.families?.filter { it.firstParentId in selectedIds && it.secondParentId in selectedIds }
                        ?.takeIf { it.isNotEmpty() })
                val nativeWorld = h.world as CapturedResidentWorld
                nativeWorld.strict = true
                h.steps.clear()
                SettlementScenario.attachResidents(h, h.city, emptyList(), initialAt = capturedAt).use { run ->
                    h.city = run.city
                    h.workProvider = run::workPlan
                    h.workIdleProvider = run::workIdleReason
                    h.onWorkPositionFailed = run::rejectedStanding
                    h.courierProvider = run::courierPlan
                    val failures = mutableListOf<Throwable>()
                    val deliveries = mutableListOf<CitizenToken>()
                    val forestryReceipts = linkedMapOf<UUID, CivInventoryTransaction>()
                    var deliveredWork: CivWorldWork? = null
                    var observedMillis = 0L
                    var requiredWorkMillis = 0L
                    var beforeCoverage: Map<*, *>? = null
                    var nativeCommitted = false
                    val pickedAfterWork = hashSetOf<UUID>()
                    val droppedAfterWork = hashSetOf<UUID>()
                    var transferredWood = 0L
                    var maximumActors = 0
                    val beforeWood = CivsAutonomousLogistics.held(run.city, Resource.WOOD)
                    val beforeCycles = resident.completedCycles
                    val mill = requireNotNull(run.city.building(resident.assignment!!))
                    val guarded = capture.states.filter { (p, state) ->
                        state.startsWith("minecraft:dirt_path") ||
                            p.x in mill.bounds.min.x..mill.bounds.max.x && p.z in mill.bounds.min.z..mill.bounds.max.z &&
                            p.y in mill.bounds.max.y - 2..mill.bounds.max.y
                    }
                    h.workerDelivery = { workerId, token ->
                        deliveries += token
                        val current = run.city
                        val worker = current.workers.single { it.id == workerId }
                        if (h.owner.pendingStockDelivery(current.id, workerId) == null) {
                            val work = requireNotNull(run.workPlan(current, worker)) { "Resident callback has no native work" }
                            h.owner.requireWorldWorkArrival(current.id, workerId, work)
                            check(h.observedWork(workerId) >= CivsResidents.workDuration(worker, work.durationMillis))
                            if (deliveredWork == null) {
                                work.status shouldBe "felling-tree"
                                work.focus shouldBe nativeRoot
                                deliveredWork = work
                                observedMillis = h.observedWork(workerId)
                                requiredWorkMillis = CivsResidents.workDuration(worker, work.durationMillis)
                                beforeCoverage = forestCoverage(run, workerId)
                            }
                        }
                        run.residentDelivery(workerId, token, run.now).whenComplete { saved, failure ->
                            if (failure != null) failures += failure else if (saved != null) {
                                h.city = saved
                                if (saved.workers.single { it.id == resident.id }.completedCycles > beforeCycles) nativeCommitted = true
                            }
                        }
                    }
                    h.pickupProvider = { cityId, workerId, cargo ->
                        run.pickupCargo(cityId, workerId, cargo).whenComplete { saved, failure ->
                            if (failure != null) failures += failure else if (saved != null) {
                                h.city = saved
                                if (nativeCommitted && cargo.sourceWorkerId == resident.id) pickedAfterWork += cargo.id
                            }
                        }
                    }
                    h.dropoffProvider = { cityId, workerId, cargo, destination ->
                        run.dropoffCargo(cityId, workerId, cargo, destination).whenComplete { saved, failure ->
                            if (failure != null) failures += failure else if (saved != null) {
                                h.city = saved
                                if (cargo.id in pickedAfterWork && droppedAfterWork.add(cargo.id)) transferredWood += cargo.amount
                            }
                        }
                    }
                    h.viewing = true // Admit actors only after their actual service callbacks are connected.
                    var ticks = 0
                    while (ticks < 4_800 && (run.city.workers.single { it.id == resident.id }.completedCycles == beforeCycles ||
                            withCourier && droppedAfterWork.isEmpty() ||
                            run.receipts().isNotEmpty() || run.pendingIo > 0)) {
                        h.city = run.city
                        run.advanceProductionTicks(1)
                        maximumActors = maxOf(maximumActors, h.owner.activeCount())
                        run.receipts().filter { it.forestry?.work?.workerId == resident.id }.forEach { forestryReceipts[it.id] = it.validated() }
                        check(failures.isEmpty()) { "Actual resident delivery failed: ${failures.map { CivsDiagnostics.rejection(it)?.reason ?: it.javaClass.simpleName }}" }
                        ticks++
                        // Keep the long replay's mock call journal bounded; actor answers/state and h.steps survive.
                        if (withCourier && ticks % 20 == 0) h.clearRecordedCalls()
                        if (withCourier && ticks % 1_000 == 0) println("Actual forestry progress: ticks=$ticks" +
                            " nativeCommitted=$nativeCommitted pickups=${h.pickups} dropoffs=${h.unloads} handoffs=${droppedAfterWork.size}" +
                            " courier=${h.navigationState(requireNotNull(courierId))} supply=${run.supplyDebug(requireNotNull(courierId))}" +
                            " admission=${h.owner.debug(run.city.id, courierId)["admission"]}" +
                            " nativeReadFailures=${nativeWorld.rejectedNativeReads}")
                    }
                    h.city = run.city
                    val work = requireNotNull(deliveredWork) { "No observed native forestry callback after $ticks ticks\n${h.navigationState()}\n${run.diagnostics()}" }
                    check(observedMillis >= requiredWorkMillis) { "Work interval $observedMillis is shorter than $requiredWorkMillis" }
                    h.steps.isNotEmpty() shouldBe true
                    val walked = h.steps.sumOf { (from, to) -> from.distance(to) }
                    check(walked >= 30.0) { "Expected the actual depot-to-forest walk, observed $walked blocks" }
                    if (withCourier) {
                        check(work.standing in listOf(lowerStanding, CivPosition(-1887, 63, -2686), CivPosition(-1886, 63, -2685)))
                        check(droppedAfterWork.isNotEmpty()) { "No actual producer-to-warehouse handoff after $ticks ticks; pickups=${h.pickups} dropoffs=${h.unloads}" +
                            " actors=$maximumActors courier=${h.navigationState(requireNotNull(courierId))} supply=${run.supplyDebug(requireNotNull(courierId))}" +
                            " admission=${h.owner.debug(run.city.id, courierId)["admission"]}" +
                            " nativeReadFailures=${nativeWorld.rejectedNativeReads}\n${run.diagnostics()}" }
                        maximumActors shouldBe 2
                    } else {
                        work.standing shouldBe lowerStanding
                        run.city.workers.single().completedCycles shouldBe beforeCycles + 1
                        forestryReceipts.size shouldBe 1
                        forestryReceipts.values.single().forestry!!.work shouldBe work
                    }
                    val expectedWood = forestryReceipts.values.sumOf { receipt ->
                        val accepted = receipt.forestry!!.work
                        val progress = accepted.forestry
                        check(progress?.job?.seedDeferred != true) { "This funded snapshot must not use a deferred seed kit" }
                        val finished = progress != null && progress.next == progress.job.cells.size &&
                            progress.job.harvestedLogs + progress.harvestedLogs > 0
                        (if (finished) CivsRules.deliveryAmount(mill) else 0L) - (if (accepted.inputs.isNotEmpty()) 1L else 0L)
                    }
                    CivsAutonomousLogistics.held(run.city, Resource.WOOD) shouldBe beforeWood + expectedWood
                    val nativeAfter = linkedMapOf<CivPosition, String>()
                    forestryReceipts.values.forEach { receipt -> receipt.forestry!!.work.changes.forEach { nativeAfter[it.position] = it.after } }
                    nativeAfter.forEach { (point, state) -> run.state(point) shouldBe state }
                    check(nativeWorld.rejectedNativeReads.isEmpty()) { "Incomplete strict native fixture: ${nativeWorld.rejectedNativeReads}" }
                    run.unchanged(guarded)
                    check(run.savedCity() == run.city) { "Published city does not match the durable city file" }
                    run.receipts().isEmpty() shouldBe true
                    val retained = requireNotNull(forestCoverage(run, resident.id))
                    val admitted = requireNotNull(beforeCoverage)
                    retained["columns"] shouldBe admitted["columns"]
                    check((retained["column"] as Int) >= (admitted["column"] as Int)) { "The forestry zone restarted after its receipt" }

                    val creditedWood = CivsAutonomousLogistics.held(run.city, Resource.WOOD)
                    val original = deliveries.last()
                    val duplicate = run.residentDelivery(resident.id, original, run.now)
                    var duplicateFailure: Throwable? = null
                    try { run.await(duplicate) } catch (failure: CompletionException) { duplicateFailure = failure }
                    check(duplicateFailure != null) { "The completed resident token was admitted twice" }
                    CivsAutonomousLogistics.held(run.city, Resource.WOOD) shouldBe creditedWood
                    nativeAfter.forEach { (point, state) -> run.state(point) shouldBe state }
                    run.unchanged(guarded)
                    println("Actual forestry replay: courier=$withCourier ticks=$ticks walked=$walked observedMs=$observedMillis receipts=${forestryReceipts.size}" +
                        " woodDelta=${creditedWood - beforeWood} handoffs=${droppedAfterWork.size} transferredWood=$transferredWood" +
                        " standing=${work.standing} field=${retained["column"]}/${retained["columns"]}")
                }
            }
        }
})

private fun forestCoverage(run: SettlementScenario, workerId: UUID): Map<*, *>? =
    ((run.worksiteDebug(workerId)["entries"] as List<*>).single() as Map<*, *>)["forestSearch"] as? Map<*, *>
