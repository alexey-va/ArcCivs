package ru.arc.civs.scenarios

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.civs.*
import java.nio.file.Files
import java.nio.file.Path

/** Real Citizens movement, observed work and Service receipts over the captured Auto2 surface.
 * Below Y56 only the declared mine envelope is modeled natural stone plus exact frozen paid V5 cells;
 * the recorded 891 cells override that continuation. This is not hidden player-world evidence. */
class Auto2ResidentMiningScenarioTest : FunSpec({
    val cases = listOf(5 to System.getenv("CIVS_SCENARIO_MINING_CITY_STATE"),
        6 to System.getenv("CIVS_SCENARIO_MINING_CURRENT_CITY_STATE"))
    for ((version, statePath) in cases) test("actual Auto2 miner from V$version reaches native work and earns ${if (version == 6) 2 else 1} stone deliveries through Service")
        .config(enabled = statePath != null) {
            val source = Gson().fromJson(Files.readString(Path.of(requireNotNull(statePath))), Settlement::class.java)
            val resident = source.workers.single { it.assignment?.let(source::building)?.type == BuildingType.QUARRY }
            val mine = requireNotNull(source.building(requireNotNull(resident.assignment)))
            val before = source.minePlans!!.getValue(mine.id.toString())
            before.version shouldBe version
            val predecessor = requireNotNull(CivsMineProgress.v5State(before))
            predecessor.nextStep shouldBe 1818
            val frozenPlan = requireNotNull(CivsMinePlan.forBuilding(mine, predecessor.version))
            val currentPlan = requireNotNull(CivsMinePlan.forBuilding(mine))
            if (version == 6) println("Current canonical spine boundary: " + currentPlan.sections.getValue("spine")
                .mapIndexed { count, ordinal -> Triple(count + 1, ordinal, currentPlan.steps[ordinal]) }
                .filter { it.first in 35..52 }.joinToString { (count, ordinal, step) ->
                    "$count/$ordinal ${CivsWorksiteLayouts.point(mine, step.point)} after=${step.after} approach=${step.approach}"
                })
            val root = Path.of(requireNotNull(System.getenv("CIVS_SCENARIO_PUBLIC_MAP")))
            val facts = Path.of(requireNotNull(System.getenv("CIVS_SCENARIO_NATIVE_FACTS")))
            val capturedAt = JsonParser.parseString(Files.readString(root.resolve("terrain.json")))
                .asJsonObject["capturedAt"].asLong
            var startedAt = capturedAt
            val blueprint = privateBlueprint(requireNotNull(System.getenv("CIVS_SCENARIO_SCHEMATICS_ROOT")),
                mine.schematicId, "38b4a7dba7245e08db6929f30178d04f35af2d12fb8c803e64babd9ada3dd634")
            blueprint.volume shouldBe 8303L
            val nativeLowerCaptures = System.getenv("CIVS_SCENARIO_NATIVE_OVERLAYS")?.let { path ->
                JsonParser.parseString(Files.readString(Path.of(path))).asJsonArray.map { it.asJsonObject }
            }.orEmpty()
            val capture = RecordedNativeMap.fromPublicRle(root, facts,
                CivBox(CivPosition(-1912, 56, -2795), CivPosition(-1798, 183, -2657)),
                nativeLowerCaptures = nativeLowerCaptures, nativeWorldId = source.worldId)
            val extraCollisions = listOfNotNull(
                System.getenv("CIVS_SCENARIO_NATIVE_COLLISION_CAPTURE")?.let { path ->
                    RecordedNativeMap.fromFile(Path.of(path), requireNotNull(System.getenv("CIVS_SCENARIO_NATIVE_COLLISION_SHA256")))
                    JsonParser.parseString(Files.readString(Path.of(path))).asJsonObject
                },
                System.getenv("CIVS_SCENARIO_NATIVE_FARM_CAPTURE")?.let { path ->
                    RecordedNativeMap.fromFile(Path.of(path), requireNotNull(System.getenv("CIVS_SCENARIO_NATIVE_FARM_SHA256")))
                    JsonParser.parseString(Files.readString(Path.of(path))).asJsonObject
                }).toMutableList()
            val modeledColumns = (frozenPlan.positions + currentPlan.positions).flatMap { local ->
                val p = CivsWorksiteLayouts.point(mine, local)
                (-4..4).flatMap { dx -> (-4..4).map { dz -> p.x + dx to p.z + dz } }
            }.toSet()
            val continuation = linkedMapOf<CivPosition, String>()
            val minY = mine.anchor.y + minOf(frozenPlan.lowest, currentPlan.lowest) - 4
            for ((x, z) in modeledColumns) for (y in minY..55)
                continuation[CivPosition(x, y, z)] = "minecraft:stone"
            for ((index, step) in frozenPlan.steps.withIndex())
                if (frozenPlan.completed(index, predecessor.nextStep, predecessor.sectionSteps.orEmpty())) {
                    val p = CivsWorksiteLayouts.point(mine, step.point)
                    if (p.y < 56) continuation[p] = frozenPlan.expectedAfter(index, mine.rotation)
                }
            val native = RecordedNativeMap("/mine-regina-floor3-native.json",
                "9f29038a0e6495a53066411c378a5615232fbaef9434068c6597cfd8a3131bbc")
            native.states.filterKeys { it.y < 56 }.forEach { (p, state) -> continuation[p] = state }
            if (version == 6) {
                // Canonical paid V6 cells postdate the old floor-3 capture; preserve their exact proof.
                for ((index, step) in currentPlan.steps.withIndex())
                    if (currentPlan.completed(index, before.nextStep, before.sectionSteps.orEmpty())) {
                        val p = CivsWorksiteLayouts.point(mine, step.point)
                        if (p.y < 56) continuation[p] = currentPlan.expectedAfter(index, mine.rotation)
                    }
                before.seals.orEmpty().forEach { (encoded, state) ->
                    val xyz = encoded.split(',').map(String::toInt)
                    val p = CivsWorksiteLayouts.point(mine, CivPosition(xyz[0], xyz[1], xyz[2]))
                    if (p.y < 56) continuation[p] = state
                }
                val lower = RecordedNativeMap.fromFile(Path.of(requireNotNull(System.getenv("CIVS_SCENARIO_CURRENT_MINE_CAPTURE"))),
                    requireNotNull(System.getenv("CIVS_SCENARIO_CURRENT_MINE_SHA256")))
                lower.states.filterKeys { it.y < 56 }.forEach { (p, state) -> continuation[p] = state }
                val currentObservation = JsonParser.parseString(Files.readString(Path.of(
                    requireNotNull(System.getenv("CIVS_SCENARIO_CURRENT_MINE_CAPTURE"))))).asJsonObject
                extraCollisions += currentObservation
                startedAt = currentObservation["captured_at_ms"].asLong
            }

            residentRuntime(workerId = resident.id, server = CivsWorksiteServerFixture(),
                worldFactory = { CapturedResidentWorld(capture,
                    nativeStateFacts = capturedStateCollisionFacts(extraCollisions, source.id, source.worldId),
                    declaredContinuation = continuation) }) { h ->
                h.viewing = false
                h.owner.pulse(1_001)
                h.owner.activeCount() shouldBe 0
                // One active actor; the owner and Service retain all canonical workers, roles and family links.
                CivsCitizens::class.java.getDeclaredField("maxActive").also { it.isAccessible = true }.setInt(h.owner, 1)
                val canonical = source.copy(worldId = h.world.uid)
                fun actorView(city: Settlement) = city.copy(workers = city.workers.sortedBy { if (it.id == resident.id) 0 else 1 })
                h.city = actorView(canonical)
                val world = h.world as CapturedResidentWorld
                world.strict = true
                h.steps.clear()
                SettlementScenario.attachResidents(h, canonical, emptyList(), initialAt = startedAt).use { run ->
                    h.city = actorView(run.city)
                    println("Mine replay epoch: version=$version revision=${source.revision} publicAt=$capturedAt startedAt=$startedAt differenceMillis=${startedAt - capturedAt}")
                    h.workProvider = run::workPlan
                    h.workIdleProvider = run::workIdleReason
                    h.onWorkPositionFailed = run::rejectedStanding
                    val failures = mutableListOf<Throwable>()
                    val nativeCommits = mutableListOf<Settlement>()
                    val nativeWorks = mutableListOf<CivWorldWork>()
                    var observedNativeMillis = 0L
                    val beforeStone = CivsAutonomousLogistics.held(run.city, Resource.STONE)
                    val beforeCycles = resident.completedCycles
                    val requiredCycles = if (version == 6) 2L else 1L
                    h.workerDelivery = { workerId, token ->
                        val current = run.city
                        val worker = current.workers.single { it.id == workerId }
                        val nativeWork = h.owner.autonomousVisualWork(current.id, workerId)
                        nativeWork?.let { work ->
                            h.owner.requireWorldWorkArrival(current.id, workerId, work)
                            check(h.observedWork(workerId) >= CivsResidents.workDuration(worker, work.durationMillis))
                            observedNativeMillis += h.observedWork(workerId)
                            nativeWorks += work
                        }
                        run.residentDelivery(workerId, token, run.now).whenComplete { saved, failure ->
                            if (failure != null) failures += failure else if (saved != null) {
                                if (nativeWork != null) {
                                    saved.minePlans!!.getValue(mine.id.toString()) shouldBe CivsMineProgress.after(mine,
                                        current.minePlans?.get(mine.id.toString()), current.mineProgress?.get(mine.id.toString()), nativeWork)
                                    saved.workers.single { it.id == workerId }.completedCycles shouldBe worker.completedCycles
                                    saved.stock.stone shouldBe current.stock.stone
                                    nativeWork.changes.forEach { cell -> run.state(cell.position) shouldBe cell.after }
                                    nativeCommits += saved
                                }
                                h.city = actorView(saved)
                            }
                        }
                    }
                    h.viewing = true
                    var ticks = 0
                    // 60s of observed production needs 30 ordinary 2s native actions. Allow 40s of
                    // real navigation per action (20min total), without skipping scheduler ticks or work.
                    val maximumTicks = 24_000
                    while (ticks < maximumTicks && (run.city.workers.single { it.id == resident.id }.completedCycles < beforeCycles + requiredCycles ||
                            run.receipts().isNotEmpty() || run.pendingIo > 0)) {
                        h.city = actorView(run.city)
                        run.advanceProductionTicks(1)
                        check(failures.isEmpty()) { "Actual mine callback failed: ${failures.map { CivsDiagnostics.rejection(it)?.reason ?: it }}" }
                        check(world.rejectedNativeReads.isEmpty()) { "Unknown native reads: ${world.rejectedNativeReads}" }
                        ticks++
                        if (version == 6 && ticks == 1000) println("Current mine repair frontier: ${mineRepairTrace(run, resident, mine)}")
                        if (ticks % 20 == 0) h.clearRecordedCalls()
                        if (ticks % 1_000 == 0) println("Actual mine progress ticks=$ticks native=${nativeWorks.size}" +
                            " plan=${run.city.minePlans} nav=${h.navigationState()} actor=${mineActorTrace(h)} work=${run.worksiteDebug(resident.id)}" +
                            " missing=${world.rejectedNativeReads}")
                    }
                    check(world.rejectedNativeReads.isEmpty()) { "Unknown native reads: ${world.rejectedNativeReads}" }
                    check(nativeWorks.isNotEmpty()) { "No actual mine callback after $ticks ticks; ${h.navigationState()}\n${run.diagnostics()}" }
                    val walked = h.steps.sumOf { (from, to) -> from.distance(to) }
                    check(walked > 5.0) { "No actual quarry travel: $walked" }
                    check(nativeCommits.isNotEmpty()) { "No committed native mine state" }
                    check(run.city.workers.single { it.id == resident.id }.completedCycles == beforeCycles + requiredCycles) {
                        "No economic mine delivery after $ticks ticks; native=${nativeWorks.size} observed=$observedNativeMillis " +
                            "actor=${mineActorTrace(h)} nav=${h.navigationState()}\n${run.diagnostics()}"
                    }
                    CivsAutonomousLogistics.held(run.city, Resource.STONE) shouldBe beforeStone + CivsRules.deliveryAmount(mine) * requiredCycles
                    check(observedNativeMillis > 0)
                    run.receipts() shouldBe emptyList()
                    run.savedCity() shouldBe run.city
                    println("Actual mine GREEN ticks=$ticks walked=$walked native=${nativeWorks.size}" +
                        " nativeCommits=${nativeCommits.size} observedMillis=$observedNativeMillis stone=$beforeStone->" +
                        CivsAutonomousLogistics.held(run.city, Resource.STONE))
                }
            }
        }
})

/** Read the existing observed session; this never changes the actor, clock or paid cursor. */
private fun mineActorTrace(h: ResidentRuntime): String {
    val actors = CivsCitizens::class.java.getDeclaredField("actors").also { it.isAccessible = true }.get(h.owner) as Map<*, *>
    val actor = actors[h.workerId] ?: return "absent"
    val session = actor.javaClass.getDeclaredField("autonomousVisualSession").also { it.isAccessible = true }.get(actor)
        as? CivAutonomousVisualSession
    val at = h.owner.collisionActors().single { it.uniqueId == h.workerId }.location
    return "at=${at.x},${at.y},${at.z}, nativeSession=${session?.workedMillis}/${session?.requiredMillis}, cycleObserved=${h.observedWork(h.workerId)}"
}

private fun mineRepairTrace(run: SettlementScenario, worker: CivWorker, mine: CivBuilding): String {
    val runtime = SettlementScenario::class.java.getDeclaredField("worksites").also { it.isAccessible = true }.get(run)
    val owner = CivsWorksites::class.java.getDeclaredField("mine").also { it.isAccessible = true }.get(runtime)
    val scans = CivsMineWorksite::class.java.getDeclaredField("mineSafety").also { it.isAccessible = true }.get(owner) as Map<*, *>
    val scan = scans[worker.id] ?: return "none"
    val frontier = scan.javaClass.getDeclaredField("frontier").also { it.isAccessible = true }.get(scan) as List<*>
    val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
    return frontier.joinToString { raw ->
        val task = requireNotNull(raw)
        fun field(name: String) = task.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(task)
        val exposed = field("exposed") as CivPosition
        val block = field("block") as CivPosition
        val p = CivsWorksiteLayouts.point(mine, block)
        "exposed=$exposed wall=$p/${run.state(p)} material=${field("material")} lowerEnd=${plan.lowerClearanceEnd(exposed)}"
    }
}
