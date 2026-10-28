package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.civs.scenarios.SettlementScenario
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/** Portable component recreation from real city/journal files in one fixture world and JVM. */
class CivsResidentRestartJourneyTest : FunSpec({
    for (automatic in listOf(false, true))
    test("a restarted walking forester walks from fresh admission and durably finishes exactly once Auto=$automatic") {
        restartJourney(automatic) { journey ->
            journey.walkPartway()
            val before = journey.run.savedCity()
            journey.restart()
            journey.run.city shouldBe before
            journey.h.deliveries shouldBe 0
            journey.run.receipts() shouldBe emptyList()

            journey.finishWalkingAndWork()
            journey.assertOneCompletion(before)
            journey.rejectDuplicate()
            val completed = journey.run.city
            journey.restart()
            journey.run.city shouldBe completed
            journey.run.savedCity() shouldBe completed
            journey.run.receipts() shouldBe emptyList()
        }
    }

    for (automatic in listOf(false, true))
    test("recovery reconciles a real arrival receipt once and ignores the retired owner's late callback Auto=$automatic") {
        restartJourney(automatic) { journey ->
            journey.walkPartway()
            journey.restart()
            val before = journey.run.savedCity()
            journey.holdCallback = true
            journey.until("actual arrival and work callback") { journey.callback != null }
            journey.run.savedCity() shouldBe before
            journey.run.receipts() shouldBe emptyList()
            journey.run.persistOne() shouldBe true
            val receipt = journey.run.receipts().single().validated()
            journey.completedReceipt = receipt.after
            receipt.forestry shouldBe null
            receipt.worldChanges shouldBe journey.work!!.changes
            journey.work!!.changes.forEach { cell ->
                journey.h.world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.before
            }
            val retired = journey.h.owner
            val oldCommit = requireNotNull(journey.callback)
            journey.restart()

            oldCommit.isCompletedExceptionally shouldBe true
            journey.run.city shouldBe receipt.after
            journey.run.savedCity() shouldBe receipt.after
            journey.run.receipts() shouldBe emptyList()
            retired.activeCount() shouldBe 0
            shouldThrow<CivsRejection> {
                retired.requireWorldWorkArrival(before.id, journey.h.workerId, requireNotNull(journey.work))
            }.reason shouldBe "route-unreachable"
            journey.work!!.changes.forEach { cell ->
                journey.h.world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.after
            }

            // Deliver a late transport result to the old Citizens generation, not to receipt recovery.
            journey.held.complete(receipt.after) shouldBe true
            journey.run.advanceProductionTicks(40)
            repeat(30) { if (journey.run.pendingIo > 0 || journey.run.receipts().isNotEmpty()) journey.run.advanceProductionTicks(1) }
            retired.activeCount() shouldBe 0
            journey.assertOneCompletion(before)
            journey.rejectDuplicate()
        }
    }

    for (automatic in listOf(false, true))
    test("fresh navigation goes around an obstruction added while the walking owner was stopped Auto=$automatic") {
        restartJourney(automatic) { journey ->
            journey.walkPartway()
            val before = journey.run.savedCity()
            journey.h.viewing = false
            for (z in 1..12) for (y in 64..66) journey.h.world.getBlockAt(25, y, z).type = Material.STONE
            journey.restart()
            journey.finishWalkingAndWork()
            journey.positions.any { it.x in 24.0..27.0 && (it.z < .7 || it.z > 13.3) } shouldBe true
            journey.assertOneCompletion(before)
            journey.rejectDuplicate()
        }
    }
})

private fun restartJourney(automatic: Boolean, test: (RestartJourney) -> Unit) = residentRuntime(server = CivsWorksiteServerFixture(),
    worldFactory = { object : ResidentRuntimeWorld() {
        override fun getHighestBlockYAt(x: Int, z: Int): Int = getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING)
        override fun getHighestBlockYAt(x: Int, z: Int, heightMap: HeightMap): Int = (80 downTo 63).first { y ->
            val block = getBlockAt(x, y, z)
            when (heightMap) {
                HeightMap.WORLD_SURFACE -> !block.type.isAir
                HeightMap.MOTION_BLOCKING -> block.type.isSolid || block.isLiquid
                HeightMap.MOTION_BLOCKING_NO_LEAVES -> (block.type.isSolid || block.isLiquid) && !block.type.name.endsWith("_LEAVES")
                else -> error("Unspecified restart fixture height map $heightMap")
            }
        }
    } }) { h ->
    h.viewing = false
    h.owner.pulse(1_001)
    h.owner.activeCount() shouldBe 0
    // Unlike Paper's player view, the pinned mock has no top inventory until one is opened.
    h.paper.server.onlinePlayers.forEach { it.openInventory(Bukkit.createInventory(null, 9)) }
    for (x in -4..4) for (z in -4..4) h.world.loadChunk(x, z)
    val mill = spatialBuilding("nordic", "lumberjack", 1, CivPosition(30, 63, 14)).copy(production = "OAK_LOG")
    val hall = h.city.buildings.single { it.type == BuildingType.TOWN_HALL }.copy(control = CivPosition(-1, 64, -1))
    for (building in listOf(hall, mill)) requireNotNull(building.control).let {
        h.world.getBlockAt(it.x, it.y, it.z).type = Material.BARREL
    }
    h.city = CivsRules.validate(h.city.copy(autonomy = if (automatic) CivAutonomy() else null,
        inventory = CivInventory(CivStorageMode.PHYSICAL), stock = Stock(),
        immigration = CivImmigration(0, Long.MAX_VALUE),
        buildings = listOf(hall, mill),
        workers = h.city.workers.map { it.copy(assignment = mill.id, nextDeliveryAt = 1,
            items = List(CivsEquipment.SLOTS) { slot -> when (slot) {
                4 -> CivResidentItem("IRON_AXE", 1)
                CivsEquipment.BAG -> CivResidentItem("OAK_SAPLING", 4)
                else -> null
            } }) }))
    h.world.getBlockAt(16, 63, 0).type = Material.DIRT
    for (y in 64..66) h.world.getBlockAt(16, y, 0).blockData = Bukkit.createBlockData("minecraft:oak_log[axis=y]")
    h.world.getBlockAt(16, 67, 0).blockData = Bukkit.createBlockData("minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]")
    SettlementScenario.attachResidents(h, h.city, emptyList(), initialAt = 10_000).use { run ->
        val journey = RestartJourney(h, run)
        try { test(journey) } catch (failure: Throwable) {
            throw AssertionError("${h.owner.debug(h.city.id, h.workerId)}\n${run.diagnostics()}", failure)
        }
    }
}

private class RestartJourney(val h: ResidentRuntime, val run: SettlementScenario) {
    private val initial = run.city
    val positions = mutableListOf<Location>()
    var work: CivWorldWork? = null
    var completedReceipt: Settlement? = null
    private var token: CitizenToken? = null
    var callback: CompletableFuture<Settlement>? = null
    var holdCallback = false
    val held = CompletableFuture<Settlement>()
    private var actor: ru.arc.paper.npc.PacketPlayerNpc? = null
    private var previous: Location? = null
    private var walked = 0.0

    init {
        h.city = run.city
        h.workProvider = { city, worker -> if (worker.completedCycles == 0L) run.workPlan(city, worker) else null }
        h.workIdleProvider = { city, worker -> if (worker.completedCycles == 0L) run.workIdleReason(city, worker) else "waiting-growth" }
        h.onWorkPositionFailed = run::rejectedStanding
        h.workerDelivery = { worker, delivered ->
            work = requireNotNull(run.workPlan(run.city, run.city.workers.single { it.id == worker }))
            token = delivered
            h.owner.requireWorldWorkArrival(run.city.id, worker, requireNotNull(work))
            (h.observedWork(worker) >= CivsResidents.workDuration(run.city.workers.single { it.id == h.workerId }, work!!.durationMillis)) shouldBe true
            callback = run.residentDelivery(worker, delivered, run.now).whenComplete { saved, _ -> if (saved != null) h.city = saved }
            if (holdCallback) held else requireNotNull(callback)
        }
        h.viewing = true
    }

    fun walkPartway() {
        until("walking before completion") { walked >= 3.0 && h.phase() == CitizenPhase.TO_WORK }
        walked shouldBe positions.zipWithNext().sumOf { (a, b) -> a.distance(b) }
        h.deliveries shouldBe 0
        run.city.workers.single { it.id == h.workerId }.completedCycles shouldBe 0L
        run.savedCity() shouldBe run.city
        run.receipts() shouldBe emptyList()
    }

    fun restart() {
        val retired = h.owner
        val oldRoute = h.routeIdentity()
        h.viewing = false
        run.restartResidents(h)
        (h.owner === retired) shouldBe false
        retired.activeCount() shouldBe 0
        h.owner.activeCount() shouldBe 0
        h.city = run.city
        actor = null
        previous = null
        walked = 0.0
        positions.clear()
        h.viewing = true
        h.routeIdentity() shouldBe null
        if (oldRoute != null) (h.routeIdentity() === oldRoute) shouldBe false
    }

    fun finishWalkingAndWork() {
        until("one durable completion") { callback?.isDone == true && run.pendingIo == 0 && run.receipts().isEmpty() }
        requireNotNull(callback).join()
        (walked > 8.0) shouldBe true
        (positions.size > 40) shouldBe true
        h.deliveries shouldBe 1
    }

    fun assertOneCompletion(before: Settlement) {
        val after = run.city
        val accepted = requireNotNull(work)
        accepted.forestry!!.next shouldBe accepted.forestry.job.cells.size
        after.workers.single { it.id == h.workerId }.completedCycles shouldBe before.workers.single { it.id == h.workerId }.completedCycles + 1
        after.stock shouldBe Stock()
        after.workers.single { it.id == h.workerId }.pendingStock shouldBe null
        after.inventory shouldBe before.inventory
        val credited = completedReceipt ?: after
        val beforeWorker = before.workers.single { it.id == h.workerId }
        val afterWorker = credited.workers.single { it.id == h.workerId }
        (accepted.inputs.keys + accepted.outputs.keys).forEach { material ->
            CivsEquipment.plainCount(afterWorker, material).toLong() shouldBe
                CivsEquipment.plainCount(beforeWorker, material).toLong() - (accepted.inputs[material] ?: 0L) +
                    (accepted.outputs[material] ?: 0L)
        }
        (accepted.outputs["OAK_LOG"] ?: 0L) shouldBe 3L
        credited.revision shouldBe before.revision + 1
        val control = requireNotNull(after.building(accepted.buildingId)?.control)
        val holder = h.world.getBlockAt(control.x, control.y, control.z).state as org.bukkit.block.Container
        val storedLogs = holder.inventory.storageContents.filterNotNull().filter { it.type == Material.OAK_LOG }.sumOf { it.amount }
        (CivsEquipment.plainCount(after.workers.single { it.id == h.workerId }, "OAK_LOG") + storedLogs) shouldBe 3
        run.savedCity() shouldBe after
        run.receipts() shouldBe emptyList()
    }

    fun rejectDuplicate() {
        val before = run.city
        val duplicate = run.residentDelivery(h.workerId, requireNotNull(token), run.now)
        val failure = shouldThrow<CompletionException> { run.await(duplicate) }
        (CivsDiagnostics.rejection(failure) != null) shouldBe true
        run.city shouldBe before
        run.savedCity() shouldBe before
        run.receipts() shouldBe emptyList()
    }

    fun until(description: String, done: () -> Boolean) {
        repeat(1_600) {
            if (done()) return
            h.city = run.city
            run.advanceProductionTicks(1)
            h.city = run.city
            if (callback == null) {
                run.city.workers.single { it.id == h.workerId }.completedCycles shouldBe initial.workers.single().completedCycles
                run.city.stock shouldBe initial.stock
            }
            h.owner.collisionActors().singleOrNull()?.let { current ->
                if (actor == null) actor = current else (current === actor) shouldBe true
                val at = current.location
                previous?.let { from ->
                    withClue("$description: $from -> $at") { (from.distance(at) <= .215001) shouldBe true }
                    for (sample in 0..8) {
                        val p = from.clone().add(at.toVector().subtract(from.toVector()).multiply(sample / 8.0))
                        citizenPacketBodyClear(h.world, CitizenPoint(p.x, p.y, p.z), false) shouldBe true
                    }
                    walked += from.distance(at)
                }
                previous = at
                positions += at
            }
            if (it % 20 == 0) h.clearRecordedCalls()
        }
        error("$description did not finish; ${h.navigationState()}\n${run.diagnostics()}")
    }
}
