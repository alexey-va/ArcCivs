package ru.arc.civs.scenarios

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.util.BoundingBox
import ru.arc.civs.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletionException

/** Explicit local one-producer replay, with self delivery or an actual warehouse actor.
 * The service retains the complete private cohort; only the exercised Citizens actors are admitted.
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
            val lowerCaptures = System.getenv("CIVS_SCENARIO_NATIVE_OVERLAYS")?.let { path ->
                JsonParser.parseString(Files.readString(Path.of(path))).asJsonArray.map { it.asJsonObject }
            }.orEmpty()
            val collisionFacts = System.getenv("CIVS_SCENARIO_NATIVE_COLLISION_CAPTURES")?.let { path ->
                capturedStateCollisionFacts(JsonParser.parseString(Files.readString(Path.of(path))).asJsonArray.map { it.asJsonObject },
                    source.id, source.worldId)
            }.orEmpty()
            val bounds = CivBox(CivPosition(-1936, 56, -2800), CivPosition(-1777, 96, -2657))
            val highestRecorded = requireRecordedAirAbove(mapRoot, bounds.max.y)
            println("Actual forestry fixture: bounds=$bounds highestRecordedNonAirY=$highestRecorded publicTopAirVerified=true")
            val capture = RecordedNativeMap.fromPublicRle(mapRoot, facts,
                // Include every staffed worksite and its admission margin, retaining only observed cells.
                bounds,
                nativeLowerCaptures = lowerCaptures, nativeWorldId = source.worldId)
            val nativeRoot = CivPosition(-1884, 64, -2688)
            val lowerStanding = CivPosition(-1887, 63, -2687)
            val portable = RecordedNativeMap.fromResourceRle("/scenarios/auto2-forest-roots-surface.json",
                "e21bd41bb477a33fe64a3a80072bff1a366e8755c9889646b586a19c7cd8f291")
            for (point in listOf(nativeRoot, lowerStanding, lowerStanding.copy(y = lowerStanding.y - 1),
                lowerStanding.copy(y = lowerStanding.y + 1))) {
                capture.states.getValue(point) shouldBe portable.states.getValue(point)
            }

            residentRuntime(workerId = resident.id, server = CivsWorksiteServerFixture(),
                worldFactory = { CapturedResidentWorld(capture, nativeStateFacts = collisionFacts) }) { h ->
                // Retire the origin-only fixture actor before the captured-world production clock starts.
                h.viewing = false
                h.owner.pulse(1_001)
                h.owner.activeCount() shouldBe 0
                val courierId = source.workers.singleOrNull { withCourier &&
                    it.assignment?.let(source::building)?.type == BuildingType.WAREHOUSE }?.id
                val activeIds = setOfNotNull(resident.id, courierId)
                activeIds.size shouldBe if (withCourier) 2 else 1
                // Bound only the real NPC actor surface. Keep canonical service ordering, vacancies and family state.
                CivsCitizens::class.java.getDeclaredField("maxActive").also { it.isAccessible = true }
                    .setInt(h.owner, activeIds.size)
                fun actorView(current: Settlement) = current.copy(workers = current.workers.sortedBy { if (it.id in activeIds) 0 else 1 })
                h.city = source.copy(worldId = h.world.uid)
                val nativeWorld = h.world as CapturedResidentWorld
                nativeWorld.strict = true
                h.steps.clear()
                SettlementScenario.attachResidents(h, h.city, emptyList(), initialAt = capturedAt).use { run ->
                    h.city = actorView(run.city)
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
                                h.city = actorView(saved)
                                if (saved.workers.single { it.id == resident.id }.completedCycles > beforeCycles) nativeCommitted = true
                            }
                        }
                    }
                    h.pickupProvider = { cityId, workerId, cargo ->
                        run.pickupCargo(cityId, workerId, cargo).whenComplete { saved, failure ->
                            if (failure != null) failures += failure else if (saved != null) {
                                h.city = actorView(saved)
                                if (nativeCommitted && cargo.sourceWorkerId == resident.id) pickedAfterWork += cargo.id
                            }
                        }
                    }
                    h.dropoffProvider = { cityId, workerId, cargo, destination ->
                        run.dropoffCargo(cityId, workerId, cargo, destination).whenComplete { saved, failure ->
                            if (failure != null) failures += failure else if (saved != null) {
                                h.city = actorView(saved)
                                if (cargo.id in pickedAfterWork && droppedAfterWork.add(cargo.id)) transferredWood += cargo.amount
                            }
                        }
                    }
                    h.viewing = true // Admit actors only after their actual service callbacks are connected.
                    var ticks = 0
                    while (ticks < 4_800 && (run.city.workers.single { it.id == resident.id }.completedCycles == beforeCycles ||
                            withCourier && droppedAfterWork.isEmpty() ||
                            run.receipts().isNotEmpty() || run.pendingIo > 0)) {
                        h.city = actorView(run.city)
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
                            " resident=${residentJourneyDiagnostic(h, run, courierId)}" +
                            " nativeReadFailures=${nativeWorld.rejectedNativeReads} lastNativeFailure=${nativeWorld.lastRejectedNativeRead}")
                    }
                    h.city = actorView(run.city)
                    val work = requireNotNull(deliveredWork) { "No observed native forestry callback after $ticks ticks\n${h.navigationState()}\n${run.diagnostics()}" }
                    check(observedMillis >= requiredWorkMillis) { "Work interval $observedMillis is shorter than $requiredWorkMillis" }
                    h.steps.isNotEmpty() shouldBe true
                    val walked = h.steps.sumOf { (from, to) -> from.distance(to) }
                    // Admission follows the actual staffed building; arrival and observed work are the contract.
                    check(work.standing in listOf(lowerStanding, CivPosition(-1887, 63, -2686), CivPosition(-1886, 63, -2685)))
                    if (withCourier) {
                        check(droppedAfterWork.isNotEmpty()) { "No actual producer-to-warehouse handoff after $ticks ticks; pickups=${h.pickups} dropoffs=${h.unloads}" +
                            " actors=$maximumActors courier=${h.navigationState(requireNotNull(courierId))} supply=${run.supplyDebug(requireNotNull(courierId))}" +
                            " resident=${residentJourneyDiagnostic(h, run, courierId)}" +
                            " nativeReadFailures=${nativeWorld.rejectedNativeReads} lastNativeFailure=${nativeWorld.lastRejectedNativeRead}\n${run.diagnostics()}" }
                        maximumActors shouldBe 2
                    } else {
                        run.city.workers.single { it.id == resident.id }.completedCycles shouldBe beforeCycles + 1
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

private fun residentJourneyDiagnostic(h: ResidentRuntime, run: SettlementScenario, workerId: UUID): Map<String, Any?> {
    val observed = h.owner.debug(run.city.id, workerId)
    val actor = (observed["entries"] as List<*>).firstOrNull() as? Map<*, *>
    return mapOf("admission" to observed["admission"], "hunger" to run.city.workers.single { it.id == workerId }.hunger,
        "actor" to actor?.filterKeys { it in setOf("status", "phase", "pause_reason", "actual", "plan", "route", "meal", "courier") })
}

/** Exact state colliders only. These later observations never contribute world cells to the earlier public map. */
internal fun capturedStateCollisionFacts(captures: List<JsonObject>, city: UUID, world: UUID): Map<String, List<BoundingBox>> {
    val result = linkedMapOf<String, List<BoundingBox>>()
    for (capture in captures) {
        check(capture["state"]?.asString == "captured" && capture["source"]?.asString == "fresh-native-command" &&
            capture["focus_origin"]?.asString == "operator-request" && capture["city"]?.asString == city.toString() &&
            capture["world"]?.asString == world.toString() && capture["captured_at_ms"].asLong > 0 &&
            capture["unavailable_count"].asInt == 0 && capture["required_load_level"]?.asString == "ENTITY_TICKING" &&
            capture["collision_coordinates"]?.asString == "block-local" &&
            capture.getAsJsonArray("collision_fields").map { it.asString } ==
                listOf("min_x", "min_y", "min_z", "max_x", "max_y", "max_z")) { "Unverified supplemental native collision capture" }
        check(capture.getAsJsonArray("chunks").all { it.asJsonObject.let { chunk ->
            chunk["loaded"].asBoolean && chunk["load_level"].asString == "ENTITY_TICKING" } })
        val voxels = capture.getAsJsonArray("voxels")
        check(capture["voxel_count"].asInt == 891 && voxels.size() == 891 &&
            voxels.all { it.asJsonArray.size() == 5 && it.asJsonArray[4].asBoolean }) { "Incomplete supplemental native collision capture" }
        val palette = capture.getAsJsonArray("palette")
        for (index in voxels.map { it.asJsonArray[3].asInt }.toSet()) {
            val shape = palette[index].asJsonObject
            val state = shape["block_data"].asString
            check(state.isNotBlank())
            val boxes = requireNotNull(shape.getAsJsonArray("collision")) { "Missing exact supplemental native collider for $state" }.map { encoded ->
                val values = encoded.asJsonArray.map { it.asDouble }
                check(values.size == 6 && values.all(Double::isFinite) && (0..2).all { values[it] <= values[it + 3] })
                BoundingBox(values[0], values[1], values[2], values[3], values[4], values[5])
            }
            val previous = result.putIfAbsent(state, boxes)
            check(previous == null || previous.toSet() == boxes.toSet()) { "Conflicting supplemental native colliders for $state" }
        }
    }
    return result
}

/** Dropping recorded upper AIR reduces decode memory without changing the highest-surface observation. */
private fun requireRecordedAirAbove(root: Path, maximumY: Int): Int {
    val descriptor = JsonParser.parseString(Files.readString(root.resolve("terrain.json"))).asJsonObject
    var highest = Int.MIN_VALUE
    for (encoded in descriptor.getAsJsonArray("chunks")) {
        val chunk = encoded.asJsonObject
        val data = JsonParser.parseString(Files.readString(root.resolve("chunk-${chunk["x"].asInt}-${chunk["z"].asInt}.json"))).asJsonObject
        val palette = data.getAsJsonArray("palette").map { it.asString }
        val runs = data.getAsJsonArray("runs")
        check(runs.size() % 2 == 0)
        var index = 0
        for (run in 0 until runs.size() step 2) {
            val count = runs[run].asInt
            check(count > 0 && index.toLong() + count <= 256L * data["height"].asInt)
            if (palette[runs[run + 1].asInt] !in setOf("minecraft:air", "minecraft:cave_air", "minecraft:void_air"))
                highest = maxOf(highest, data["minY"].asInt + (index + count - 1) / 256)
            index += count
        }
        check(index == 256 * data["height"].asInt)
    }
    check(highest < maximumY - 1) { "Public native surface reaches Y$highest; the strict Y$maximumY crop omits required headroom" }
    return highest
}
