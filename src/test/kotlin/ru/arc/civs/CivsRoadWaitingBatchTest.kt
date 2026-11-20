package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.UUID

class CivsRoadWaitingBatchTest : FunSpec({
    test("one builder pays a captured repair while the 188th house row lacks one spruce log") {
        val h = WaitingHouse()
        val worker = h.city.workers.first()
        citizenPlan(h.city, worker)?.idleReason shouldBe "waiting-materials"
        h.observe()
        val plan = requireNotNull(h.planning.plan(h.city, worker))
        val work = requireNotNull(plan.worldWork)
        plan.token.constructionId shouldBe null
        plan.workplace shouldBe work.standing
        plan.suppliesNeeded shouldBe false
        val cycle = plan.cycle().also { it.start() }
        cycle.phase shouldBe CitizenPhase.TO_WORK
        plan.travelTarget(cycle.phase) shouldBe work.standing
        plan.idleReason shouldBe null
        work.upkeep?.waitingBatch shouldBe CivRoadWaitingBatch(h.order.id, 188)
        h.city.workers.drop(1).forEach { dedicated ->
            h.planning.plan(h.city, dedicated)?.token?.constructionId shouldBe h.order.id
            h.supplies.workInputsReady(h.city, dedicated) shouldBe false
        }
        h.maintenance.requirements(h.city) shouldBe emptyMap()
        h.maintenance.admitDevelopment(h.city) shouldBe true
        h.maintenance.plan(h.city, worker) shouldBe work
        h.supplies.workInputsReady(h.city, worker) shouldBe true
        val after = CivsRoadMaintenance.complete(h.city, work, 3_000)
        val receipt = CivRoadUpkeepReceipt(worker, h.city.stock, work, 3_000)
        val tx = CivInventoryTransaction(UUID(0, 20), h.city.id, h.city.revision, after, emptyList(),
            worldChanges = work.changes, upkeep = receipt).validated()
        Gson().fromJson(Gson().toJson(tx), CivInventoryTransaction::class.java).validated() shouldBe tx
        after.construction shouldBe h.order
        work.inputs shouldBe mapOf("COBBLESTONE" to 1L)
        after.workers.map { CivsEquipment.usableCount(it, "COBBLESTONE") } shouldBe listOf(3, 1, 3)
        after.workers.drop(1) shouldBe h.city.workers.drop(1)
        fun demand(state: Settlement) = CivsLogistics.requests(state) { emptyMap() }
            .single { it.construction && it.material == "COBBLESTONE" }.needed
        demand(h.city) shouldBe 33L
        demand(after) shouldBe 34L
        shouldThrow<IllegalArgumentException> { CivsRoadMaintenance.complete(after, work, 3_001) }
    }

    test("an occupied builder cannot claim the job and an admitted free worker keeps it") {
        val h = WaitingHouse()
        val (rich, dedicated, free) = h.city.workers
        h.observe { it != rich.id }
        val work = requireNotNull(h.maintenance.plan(h.city, free))
        h.maintenance.plan(h.city, rich) shouldBe null
        h.maintenance.plan(h.city, dedicated) shouldBe null
        val before = h.probes
        repeat(12) {
            h.observe { error("already selected") }
            h.maintenance.waitingInputsReady(h.city, free) shouldBe true
        }
        h.probes shouldBe before
        h.maintenance.plan(h.city, free) shouldBe work
    }

    test("the exact waiting citizen can change jobs after a meal or a supply pickup finishes") {
        MockBukkitTestRuntime.open().use { paper ->
            val h = WaitingHouse()
            val worker = h.city.workers.first()
            val runtime = CitizenRuntime(paper.createSimplePlugin("RoadAvailability")) { listOf(h.city) }
            val actor = CitizenActor(h.city.id, worker.id, h.city.worldId, mockk(), requireNotNull(citizenPlan(h.city, worker)))
            runtime.actors[worker.id] = actor
            val logistics = mockk<CivsCitizenLogistics> { every { collectedPending(actor) } returns false }
            val life = mockk<CivsCitizenLife> { every { residentLifePending(actor) } returns false }
            val observations = CivsCitizenObservations(runtime, { mockk() }, { logistics }, { mockk() }, { life },
                mockk(), mockk(), 8, 48, { 0L }, { _, _ -> emptyList() })
            observations.canReassign(h.city.id, UUID(0, 200)) shouldBe true
            observations.canReassign(h.city.id, UUID(0, 200), h.order.id) shouldBe false
            observations.canReassign(h.city.id, worker.id) shouldBe false
            observations.canReassign(h.city.id, worker.id, UUID(0, 100)) shouldBe false
            actor.mealVisit = mockk()
            observations.canReassign(h.city.id, worker.id, h.order.id) shouldBe false
            actor.mealVisit = null
            actor.supplyPickup = CitizenSupplyPickup(UUID(0, 101), requireNotNull(worker.assignment), false)
            observations.canReassign(h.city.id, worker.id, h.order.id) shouldBe false
            actor.supplyPickup = null
            observations.canReassign(h.city.id, worker.id, h.order.id) shouldBe true
        }
    }

    test("observed healthy columns leave this order's pass and unloaded ones can be observed later") {
        val healthy = WaitingHouse()
        healthy.healthy = true
        repeat(15) { healthy.observe() }
        healthy.probes shouldBe 1
        healthy.maintenance.plan(healthy.city, healthy.city.workers.first()) shouldBe null

        val cold = WaitingHouse()
        cold.unavailable = true
        cold.observe()
        cold.maintenance.plan(cold.city, cold.city.workers.first()) shouldBe null
        cold.unavailable = false
        cold.observe()
        requireNotNull(cold.maintenance.plan(cold.city, cold.city.workers.first()))
    }

    test("shared bill, paid or changed row, reserved goods and a lone builder cannot fund the repair") {
        val h = WaitingHouse()
        h.observe()
        val worker = h.city.workers.first()
        val work = requireNotNull(h.maintenance.plan(h.city, worker))
        val supplied = h.city.workers.toMutableList().also { workers ->
            workers[1] = CivsEquipment.replace(workers[1], CivsEquipment.slots(workers[1]).toMutableList().also {
                it[CivsEquipment.BAG + 1] = CivResidentItem("SPRUCE_LOG", 1)
            })
        }
        val delivered = h.city.copy(workers = supplied)
        val paid = CivsConstructionRules.payBatch(delivered, delivered.owner, h.order.id, 188, mapOf("SPRUCE_LOG" to 1L))
        paid.construction?.payment?.reservedBatch shouldBe 188
        val shared = h.order.copy(payment = CivConstructionPayment(awaiting = mapOf("SPRUCE_LOG" to 1L, "COBBLESTONE" to 16L)))
        val reserved = worker.copy(cargo = CivCargo(UUID(0, 77), h.order.buildingId, h.target.source.id,
            requireNotNull(worker.assignment), h.target.floor, "COBBLESTONE", 4, inventoryBacked = true))
        CivsEquipment.usableCount(reserved, "COBBLESTONE") shouldBe 0
        for (state in listOf(delivered, paid, h.city.copy(construction = shared),
                h.city.copy(workers = listOf(reserved) + h.city.workers.drop(1)),
                h.city.copy(workers = listOf(worker)), h.city.copy(construction = h.order.copy(workDone = 189)),
                h.city.copy(construction = h.order.copy(id = UUID(0, 99))), h.city.copy(construction = null),
                h.city.copy(constructionQueue = listOf(h.target.source.id)))) {
            shouldThrow<IllegalArgumentException> { CivsRoadMaintenance.complete(state, work, 3_000, nativePaid = true) }
        }
        val receipt = CivRoadUpkeepReceipt(worker, h.city.stock, work, 3_000)
        val tx = CivInventoryTransaction(UUID(0, 23), h.city.id, h.city.revision,
            CivsRoadMaintenance.complete(h.city, work, 3_000), emptyList(), worldChanges = work.changes, upkeep = receipt).validated()
        val commits = CivsWorkerCommits(mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(),
            h.maintenance, mockk(), mockk()) { mapOf(h.city.id to delivered) }
        commits.validateUpkeepNative(tx) shouldBe CivInventoryApplyResult.Conflict(mapOf("reason" to "stale-city"))
        h.maintenance.plan(delivered, worker) shouldBe null
        h.supplies.workInputsReady(delivered, worker) shouldBe true
        h.planning.plan(delivered, worker)?.token?.constructionId shouldBe h.order.id
    }

    test("resident selection and receipt recovery share the one order capture and reject a changed approach") {
        val h = WaitingHouse()
        h.observe()
        val worker = h.city.workers.first()
        val work = requireNotNull(h.maintenance.plan(h.city, worker))
        val after = CivsRoadMaintenance.complete(h.city, work, 3_000)
        val tx = CivInventoryTransaction(UUID(0, 22), h.city.id, h.city.revision, after, emptyList(),
            worldChanges = work.changes, upkeep = CivRoadUpkeepReceipt(worker, h.city.stock, work, 3_000)).validated()
        val store = mockk<CivsStore>()
        val tasks = LifecycleTaskScope(TestTaskScheduler())
        val read = CompletableFuture<CivPlacementPlan?>()
        every { store.loadPlan(h.order.id) } returns read
        val runtime = CivsConstructionRuntime(mockk(), tasks, mockk(), mockk(), store, Executor(Runnable::run),
            mockk(), mockk(), mockk(), mockk(), { mapOf(h.city.id to h.city) }, emptySet(), emptySet(), { true },
            { false }, { true }, mockk(), mockk(), mockk(), mockk(), {}, { 0L })
        try {
            repeat(3) { runtime.site(h.city.id, h.order.id) shouldBe null }
            val pending = runtime.verifyRoadSite(tx)
            pending.isDone shouldBe false
            read.complete(h.housePlan)
            pending.join()
            runtime.site(h.city.id, h.order.id)?.orderId shouldBe h.order.id
            runtime.verifyRoadSite(tx).join()
            verify(exactly = 1) { store.loadPlan(h.order.id) }

            runtime.resetSites()
            val terrain = requireNotNull(h.housePlan.terrain)
            every { store.loadPlan(h.order.id) } returns CompletableFuture.completedFuture(
                h.housePlan.copy(terrain = terrain.copy(route = listOf(work.standing))))
            shouldThrow<CompletionException> { runtime.verifyRoadSite(tx).join() }.cause!!.javaClass shouldBe IllegalArgumentException::class.java
            verify(exactly = 2) { store.loadPlan(h.order.id) }
        } finally {
            tasks.close()
        }
    }

    test("the current paid footprint excludes the floor and every standing or changed column") {
        val h = WaitingHouse()
        val p = h.target.floor
        val feet = p.copy(x = p.x + 1, y = p.y + 1)
        val changed = CivPlanBlock(p, "minecraft:grass_block", "minecraft:dirt_path")
        val other = changed.copy(position = feet)
        val housePlan = h.housePlan
        val terrain = requireNotNull(housePlan.terrain)
        val captures = listOf(
            housePlan.copy(blocks = listOf(changed)),
            housePlan.copy(terrain = terrain.copy(blocks = listOf(other))),
            housePlan.copy(terrain = terrain.copy(route = listOf(p.copy(x = p.x - 1, y = p.y + 1)))),
            housePlan.copy(building = housePlan.building.copy(bounds = CivBox(p, feet))),
        )
        for (capture in captures) {
            h.site = civsConstructionSite(capture)
            h.observe()
            h.maintenance.plan(h.city, h.city.workers.first()) shouldBe null
        }
        h.site = null
        h.observe()
        h.maintenance.plan(h.city, h.city.workers.first()) shouldBe null
        h.site = civsConstructionSite(housePlan)
        h.observe()
        val work = requireNotNull(h.maintenance.plan(h.city, h.city.workers.first()))
        h.site = civsConstructionSite(housePlan.copy(terrain = terrain.copy(blocks = listOf(changed))))
        h.maintenance.plan(h.city, h.city.workers.first()) shouldBe null
        h.site = civsConstructionSite(housePlan)
        h.before = "minecraft:dirt"
        h.observe()
        h.maintenance.plan(h.city, h.city.workers.first()) shouldBe null
        work.changes.single().before shouldBe "minecraft:coarse_dirt"
    }
})

private class WaitingHouse {
    private val base = roadAdmissionCity()
    private val route = (0..4).map { CivPosition(0, 64, it) }
    private val source = base.buildings.last().copy(type = BuildingType.HOUSE, transit = null, entrance = route.last(),
        anchor = CivPosition(0, 63, 5), bounds = CivBox(CivPosition(0, 63, 5), CivPosition(3, 69, 8)))
    private val building = source.copy(id = UUID(0, 10), phase = BuildingPhase.CONSTRUCTING, anchor = CivPosition(21, 64, -21),
        entrance = CivPosition(21, 65, -22), bounds = CivBox(CivPosition(19, 63, -21), CivPosition(25, 82, -14)))
    val order = CivConstruction(UUID(0, 11), building.id, 1, 629, 188, ConstructionPhase.WAITING_MATERIALS,
        Stock(), plannedBuilding = building, batches = CivConstructionBatches(629, 16, true),
        materials = mapOf("COBBLESTONE" to 41, "SPRUCE_LOG" to 1),
        payment = CivConstructionPayment(awaiting = mapOf("SPRUCE_LOG" to 1)), blocksPlaced = 665, blocksRequired = 1573)
    val city = CivsRules.validate(base.copy(buildings = base.buildings.dropLast(1).map {
        it.copy(level = 3, control = it.anchor)
    } + source + building, construction = order, workers = listOf(4, 1, 3).mapIndexed { index, amount ->
        val worker = base.workers.single().copy(id = UUID(0, 40L + index), name = "Builder ${index + 1}")
        CivsEquipment.replace(worker, List(CivsEquipment.SLOTS) { if (it == CivsEquipment.BAG) CivResidentItem("COBBLESTONE", amount) else null })
    }))
    private val floor = route[1].copy(y = 63)
    private val terrain = CivTerraformPlan(source.bounds, listOf(CivPlanBlock(floor, "minecraft:dirt", "minecraft:coarse_dirt")), route, 63, 0, 0)
    private val paid = CivPlacementPlan(source.id, city.id, city.worldId, source, emptyList(), capturedSha256 = "a".repeat(64), terrain = terrain)
    val housePlan = CivPlacementPlan(order.id, city.id, city.worldId, building, emptyList(),
        terrain = terrain.copy(bounds = building.bounds, blocks = emptyList(), route = listOf(building.entrance)))
    var site: CivConstructionSite? = civsConstructionSite(housePlan)
    private val network = CivsRoadMaintenance.prepare(city, listOf(paid), proposeCuts = false, restrictTo = setOf(civsTerrainKey(floor.x, floor.z)))
    val target = network.targets.single()
    private var tick = 0
    var before = "minecraft:coarse_dirt"
    var healthy = false
    var unavailable = false
    var probes = 0
    private val native = mockk<CivsWorld>()
    val maintenance = CivsRoadMaintenance(native, constructionSite = { _, _ -> site }) { tick }
    val supplies = CivsResidentSupplies(mockk(), mockk(), { mockk() }, mockk(), mockk(), CivsResidentFoods.BREAD_ONLY,
        native, maintenance, mockk(), mockk(), mockk(), mockk(), mockk())
    private val workRuntime = mockk<CivsCitizenWork> { every { constructionWorkplace(any(), any()) } returns building.entrance }
    val planning = CivsCitizenPlanning(CitizenRuntime(mockk()) { listOf(city) }, { mockk() }, { mockk() }, { mockk() },
        { workRuntime }, { mockk() }, { mockk() }, { mockk() }, { _, _ -> null }, maintenance::plan, { _, _ -> "no-work" },
        supplies::productionReady, supplies::workInputsReady, { _, _ -> null }, null, { _, _ -> })

    init {
        every { native.captureWaitingRoadRepair(any(), any(), any(), any(), any(), any(), any()) } answers {
            probes++
            when {
                unavailable -> null
                healthy -> {
                    lastArg<() -> Unit>().invoke()
                    null
                }
                else -> {
                    val target = thirdArg<CivRoadUpkeep>()
                    roadAdmissionWork(city.copy(workers = listOf(secondArg())), target).copy(
                        changes = listOf(CivPlanBlock(target.floor, before, target.after)))
                }
            }
        }
        maintenance.publish(city, network)
    }

    fun observe(available: (UUID) -> Boolean = { true }) {
        tick++
        maintenance.backfillSurfaces(city, available)
        tick++
    }
}
