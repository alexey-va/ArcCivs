package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.logging.Logger

class CivsAutonomousInfrastructureStarvationTest : FunSpec({
    test("exhausted no-path growth after an adopted gate yields one missing wall during its retry backoff") {
        InfrastructureStarvationHarness().use { h ->
            val before = h.city
            h.start()
            h.infrastructure.size shouldBe 0
            h.failGrowth("autonomy-no-path")
            h.advance(30_001)
            h.growth.size shouldBe 1

            // Placement still owns the native proof. No wall is accepted before its design completes.
            h.infrastructure.size shouldBe 1
            h.city shouldBe before
            h.committed shouldBe emptyList()
            h.infrastructure.single().complete(h.design(h.wall))
            h.drain()
            h.committed shouldBe listOf(h.wall.id)
            h.city.buildings shouldBe before.buildings + h.wall.copy(phase = BuildingPhase.SITE)
            h.city.constructionQueue shouldBe listOf(h.wall.id)
            h.city.construction shouldBe null
            h.city.autonomy shouldBe before.autonomy
            h.city.stock shouldBe before.stock
            h.city.inventory shouldBe before.inventory
            h.city.workers shouldBe before.workers
            h.reserved shouldBe emptyList()

            h.advance(35_002)
            h.infrastructure.size shouldBe 1
            h.growth.size shouldBe 1
            h.committed shouldBe listOf(h.wall.id)
        }
    }

    test("terrain invalidation clears stale geometry but preserves one fresh infrastructure attempt after the retry floor") {
        InfrastructureStarvationHarness().use { h ->
            val before = h.city
            h.start()
            h.failGrowth("autonomy-no-path")
            (h.development.debug(h.city.id)["geometric_refusal"] as Map<*, *>)["cached"] shouldBe true
            h.advance(5_001)
            val farm = h.city.buildings.single { it.type == BuildingType.FARM }
            val bounds = CivsAutonomousGeometry.futureBounds(h.city, farm)
            val changed = bounds.max.copy(y = farm.entrance.y, z = (bounds.min.z + bounds.max.z) / 2)
            h.development.terrainChanged(h.city.worldId, changed)
            (h.development.debug(h.city.id)["geometric_refusal"] as Map<*, *>)["cached"] shouldBe false
            h.development.debug(h.city.id)["retry_at"] shouldBe 30_000L

            h.advance(10_002)
            h.growth.size shouldBe 1
            h.infrastructure.size shouldBe 0
            h.advance(30_001)
            h.infrastructure.size shouldBe 1
            h.growth.size shouldBe 1
            h.city shouldBe before
            h.city.stock shouldBe before.stock
            h.city.constructionQueue shouldBe before.constructionQueue
            h.committed shouldBe emptyList()
            h.infrastructure.single().isDone shouldBe false
        }
    }

    for (guard in listOf("owner", "world", "plan", "goal", "accepted order", "paused", "expired", "reset", "cancel"))
    test("failed growth cannot select infrastructure after $guard supersedes its scheduling turn") {
        InfrastructureStarvationHarness().use { h ->
            h.start()
            h.failGrowth("autonomy-no-path")
            val before = h.city
            when (guard) {
                "owner" -> h.city = h.city.copy(owner = UUID(215, 77))
                "world" -> h.city = h.city.copy(worldId = UUID(215, 78))
                "plan" -> h.city = h.city.copy(autonomy = h.city.autonomy!!.let {
                    it.copy(plan = it.plan!!.copy(revision = it.plan!!.revision + 1))
                })
                "goal" -> {
                    h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings.dropLast(1) +
                        h.house.copy(phase = BuildingPhase.ACTIVE) + h.city.buildings.last()))
                    CivsAutonomy.nextGoal(h.city)?.type shouldBe BuildingType.TOWN_HALL
                }
                "accepted order" -> h.city = CivsRules.enqueue(CivsRules.placeSite(h.city, h.city.owner, h.house),
                    h.city.owner, h.house.id)
                "paused" -> h.city = h.city.copy(autonomy = h.city.autonomy!!.copy(paused = true))
                "reset" -> h.development.reset()
                "cancel" -> h.development.cancel()
            }
            val current = h.city
            h.development.pulseAccessRepair(); h.drain()
            h.advance(if (guard == "expired") 300_001 else 30_001)
            h.infrastructure.size shouldBe 0
            h.committed shouldBe emptyList()
            h.city shouldBe current
            if (guard in listOf("expired", "reset")) h.growth.size shouldBe 2
            if (guard in listOf("accepted order", "paused")) {
                // Resuming or cancelling the intervening order must not revive the old scheduling turn.
                h.city = before
                h.development.pulseAccessRepair(); h.drain()
                h.advance(35_002)
                h.infrastructure.size shouldBe 0
                h.committed shouldBe emptyList()
            }
        }
    }

    test("current capacity growth keeps priority over the failed ordinary goal without a layout change") {
        InfrastructureStarvationHarness(hallLevel = 2).use { h ->
            val before = h.city
            h.start()
            h.failGrowth("autonomy-no-path")
            h.capacityPressure = true
            val capacity = requireNotNull(CivsAutonomy.capacityGoal(h.city) { it.type.maxLevel })
            capacity.type shouldBe BuildingType.WAREHOUSE
            capacity.targetLevel shouldBe 2
            h.advance(30_001)
            h.upgrades shouldBe listOf(requireNotNull(capacity.buildingId) to 2)
            h.infrastructure.size shouldBe 0
            h.growth.size shouldBe 1
            h.committed shouldBe emptyList()
            h.city shouldBe before
        }
    }

    test("a city-busy admission preserves the turn but an admitted infrastructure attempt consumes it") {
        InfrastructureStarvationHarness().use { h ->
            val before = h.city
            h.start()
            h.failGrowth("autonomy-no-path")
            h.admitted = false
            h.advance(30_001)
            h.infrastructure.size shouldBe 0
            h.growth.size shouldBe 1
            h.admitted = true
            h.advance(35_002)
            h.infrastructure.size shouldBe 1
            h.infrastructure.single().completeExceptionally(CivsRejection("world-busy"))
            h.drain()
            h.development.terrainChanged(h.city.worldId, h.city.center.copy(x = h.city.center.x + h.city.radius))
            h.advance(40_003)
            h.infrastructure.size shouldBe 1
            h.growth.size shouldBe 2
            h.committed shouldBe emptyList()
            h.city shouldBe before
        }
    }

    for (changed in listOf("plan", "owner", "world"))
    test("a current infrastructure design cannot cross a changed $changed at final admission") {
        InfrastructureStarvationHarness().use { h ->
            h.start()
            h.failGrowth("autonomy-no-path")
            h.advance(30_001)
            h.infrastructure.size shouldBe 1
            h.city = when (changed) {
                "plan" -> h.city.copy(autonomy = h.city.autonomy!!.let {
                    it.copy(plan = it.plan!!.copy(revision = it.plan!!.revision + 1))
                })
                "owner" -> h.city.copy(owner = UUID(215, 77))
                else -> h.city.copy(worldId = UUID(215, 78))
            }
            val current = h.city
            h.infrastructure.single().complete(h.design(h.wall))
            h.drain()
            h.city shouldBe current
            h.committed shouldBe emptyList()
            h.reserved shouldBe emptyList()
        }
    }

    test("ready first house and hall upgrade take priority over new infrastructure") {
        InfrastructureStarvationHarness().use { h ->
            val openTurn = h.city.copy(buildings = h.city.buildings.sortedBy { !it.type.isInfrastructure })
            val house = requireNotNull(CivsAutonomy.nextGoal(openTurn))
            house.type shouldBe BuildingType.HOUSE
            CivsAutonomy.infrastructureDue(openTurn, house) shouldBe false
            CivsAutonomy.infrastructureDue(openTurn, house.copy(missing = Stock(1))) shouldBe true
            h.start()
            h.infrastructure.size shouldBe 0
            h.growth.single().complete(h.grownPlan)
            h.drain()
            h.committed shouldBe emptyList()
            h.advance(5_001)
            h.reserved shouldBe listOf(h.house.id)
            h.committed shouldBe listOf(h.house.id)
            h.city.constructionQueue shouldBe listOf(h.house.id)
            h.infrastructure.size shouldBe 0

            // Simulate completion of the accepted resident order; this harness does not build blocks.
            h.city = CivsRules.validate(h.city.copy(constructionQueue = emptyList(),
                buildings = h.city.buildings.map { if (it.id == h.house.id) it.copy(phase = BuildingPhase.ACTIVE) else it }))
            h.development.pulseAccessRepair(); h.drain()
            h.advance(10_002)
            val hall = h.city.buildings.single { it.type == BuildingType.TOWN_HALL }
            h.upgrades shouldBe listOf(hall.id to 2)
            h.infrastructure.size shouldBe 0
            h.growth.size shouldBe 1
            h.committed shouldBe listOf(h.house.id)
        }
    }

    test("repeated world-busy is not evidence that ordinary growth should yield to a wall") {
        InfrastructureStarvationHarness().use { h ->
            val before = h.city
            h.start()
            h.failGrowth("world-busy")
            h.advance(30_001)
            h.growth.size shouldBe 2
            h.failGrowth("world-busy")
            h.advance(35_002)
            h.infrastructure.size shouldBe 0
            h.committed shouldBe emptyList()
            h.city shouldBe before
        }
    }

    for (guard in listOf("paused", "accepted order"))
    test("a growth refusal cannot schedule fallback after $guard supersedes its capture") {
        InfrastructureStarvationHarness().use { h ->
            h.start()
            h.city = if (guard == "paused") h.city.copy(autonomy = h.city.autonomy!!.copy(paused = true))
            else CivsRules.enqueue(CivsRules.placeSite(h.city, h.city.owner, h.house), h.city.owner, h.house.id)
            val accepted = h.city
            h.failGrowth("autonomy-no-path")
            h.advance(30_001)
            h.growth.size shouldBe 1
            h.infrastructure.size shouldBe 0
            h.committed shouldBe emptyList()
            h.city shouldBe accepted
        }
    }
})

/** Reduced Auto-2 scheduling structure from the 2026-11-05 capture; coordinates and terrain proof are synthetic. */
private class InfrastructureStarvationHarness(hallLevel: Int = 1) : AutoCloseable {
    private val templates = settlementPlanTemplates()
    private val base = settlementPlanCity().let { it.copy(inventory = CivInventory(CivStorageMode.PHYSICAL),
        buildings = it.buildings.map { hall -> hall.copy(level = hallLevel) }, autonomy = CivAutonomy(layoutHalfWidth = 56)) }
    private val ordinary = base.buildings + listOf(
        BuildingType.BUILDER_HUT to CivPosition(18, 64, 0),
        BuildingType.FORESTER to CivPosition(-28, 64, 24),
        BuildingType.QUARRY to CivPosition(28, 64, 24),
        BuildingType.FARM to CivPosition(0, 64, 40),
        BuildingType.WAREHOUSE to CivPosition(-18, 64, 0),
    ).mapIndexed { index, (type, anchor) ->
        CivsSettlementPlans.at(templates.getValue(type).first, anchor, 0, UUID(215, 10L + index))
            .copy(phase = BuildingPhase.ACTIVE)
    }
    private val gateId = UUID(215, 20)
    private val gateBounds = CivBox(CivPosition(-2, 64, 54), CivPosition(2, 69, 58))
    private val gate = CivBuilding(gateId, BuildingType.GATE, 1, gateBounds.min, 0,
        "${CivsInfrastructureRebuild.PREFIX}gate/$hallLevel/$gateId", gateBounds, CivPosition(0, 65, 53), BuildingPhase.ACTIVE,
        transit = CivsInfrastructureRebuild.openColumns(gateBounds, true).map { (x, z) -> CivPosition(x, 65, z) })
    private val walls = buildList {
        for (range in listOf(-57..-35, -34..-12, -11..11, 12..34, 35..57))
            add(CivBox(CivPosition(range.first, 64, -57), CivPosition(range.last, 64, -55)))
        for (range in listOf(-57..-30, -29..-3, 3..29, 30..57))
            add(CivBox(CivPosition(range.first, 64, 55), CivPosition(range.last, 64, 57)))
        for (x in listOf(-56, 56)) for (range in listOf(-54..0, 1..54))
            add(CivBox(CivPosition(x - 1, 64, range.first), CivPosition(x + 1, 64, range.last)))
    }
    private val adopted = CivSettlementPlan(CivsSettlementPlans.VERSION, 3, 56,
        ordinary.map { CivSettlementPlot(it.copy(phase = BuildingPhase.SITE), it.bounds) },
        ordinary.drop(1).associate { it.id.toString() to route(it.entrance) }, walls,
        listOf(gateBounds.copy(max = gateBounds.max.copy(y = 64))), listOf(2))
    var city = CivsRules.validate(base.copy(buildings = ordinary + gate,
        autonomy = base.autonomy!!.copy(plan = adopted)))
    val house = CivsSettlementPlans.at(templates.getValue(BuildingType.HOUSE).first,
        CivPosition(18, 64, 32), 0, UUID(215, 21)).copy(phase = BuildingPhase.SITE)
    val grownPlan = adopted.copy(revision = 4,
        plots = adopted.plots + CivSettlementPlot(house, house.bounds),
        routes = adopted.routes + (house.id.toString() to route(house.entrance)))
    private val wallId = UUID(215, 22)
    val wall = CivBuilding(wallId, BuildingType.WALL, 1, CivPosition(3, 64, 55), 0,
        "${CivsInfrastructureRebuild.PREFIX}wall/1/$wallId",
        CivBox(CivPosition(3, 64, 55), CivPosition(16, 68, 57)), CivPosition(3, 65, 54), BuildingPhase.CONSTRUCTING)
    val growth = mutableListOf<CompletableFuture<CivSettlementPlan>>()
    val infrastructure = mutableListOf<CompletableFuture<CivAutonomousDesign>>()
    val reserved = mutableListOf<UUID>()
    val committed = mutableListOf<UUID>()
    val upgrades = mutableListOf<Pair<UUID, Int>>()
    var capacityPressure = false
    var admitted = true
    private var time = 0L
    private val scheduler = TestTaskScheduler()
    private val tasks = LifecycleTaskScope(scheduler)
    private val placement = mockk<CivsAutonomousPlacement>()
    private val blueprints = mockk<CivsBlueprints>()
    private val plugin = mockk<Plugin>()
    private val mutation: (UUID, (Settlement) -> Settlement) -> CompletableFuture<Settlement> = { _, change ->
        runCatching { change(city) }.fold({ city = it; CompletableFuture.completedFuture(it) }, { CompletableFuture.failedFuture(it) })
    }
    init {
        every { plugin.logger } returns Logger.getLogger("CivsAutonomousInfrastructureStarvationTest")
        every { blueprints.maximumLevel(any()) } answers { firstArg<CivBuilding>().type.maxLevel }
        every { blueprints.prepareUpgrade(any(), any(), any()) } answers {
            upgrades += firstArg<CivBuilding>().id to secondArg<Int>()
            CompletableFuture<CivBlueprint>()
        }
        every { placement.plan(any()) } answers { CompletableFuture<CivSettlementPlan>().also(growth::add) }
        every { placement.infrastructure(any()) } answers { CompletableFuture<CivAutonomousDesign>().also(infrastructure::add) }
        every { placement.reserved(any(), any()) } answers {
            val plot = secondArg<CivSettlementPlot>()
            reserved += plot.building.id
            CompletableFuture.completedFuture(design(plot.building))
        }
        every { placement.debug(any()) } returns emptyMap()
    }
    val development = CivsAutonomousDevelopment(plugin, tasks, mockk(), blueprints, placement, Executor(Runnable::run),
        { mapOf(city.id to city) }, mutableSetOf(), emptySet(), { false }, { true }, { false },
        { CompletableFuture.completedFuture(emptyList()) }, { true }, { _, action -> action() }, mutation, mutation,
        { current, design ->
            val building = design.blueprint.building
            city = CivsRules.enqueue(CivsRules.placeSite(current, current.owner, building), current.owner, building.id)
            committed += building.id
            CompletableFuture.completedFuture(Unit)
        }, {}, { time }, storageCapacityPressure = { capacityPressure }, developmentAdmission = { admitted })

    fun start() {
        CivsSettlementPlans.current(city) shouldBe adopted
        adopted.plots.size shouldBe 6
        adopted.walls.size shouldBe 13
        city.buildings.last() shouldBe gate
        CivsInfrastructureRebuild.needsRefit(city, gate) shouldBe false
        CivsAutonomy.nextGoal(city)?.type shouldBe BuildingType.HOUSE
        CivsSettlementPlans.next(city, BuildingType.HOUSE) shouldBe null
        CivsAutonomy.infrastructureDue(city, CivsAutonomy.nextGoal(city)) shouldBe false
        // Check the small fixture can enter the same domain queue without granting materials or work.
        CivsRules.enqueue(CivsRules.placeSite(city, city.owner, wall), city.owner, wall.id)
            .constructionQueue shouldBe listOf(wall.id)
        development.pulseAccessRepair(); drain()
        development.accessReady(city) shouldBe true
        development.pulse(); drain()
        growth.size shouldBe 1
    }
    fun failGrowth(reason: String) { growth.last().completeExceptionally(CivsRejection(reason)); drain() }
    fun advance(at: Long) { time = at; development.pulse(); drain() }
    fun design(building: CivBuilding): CivAutonomousDesign = CivAutonomousDesign(mockk<CivBlueprint>().also {
        every { it.building } returns building
    })
    private fun route(destination: CivPosition): List<CivPosition> = buildList {
        var point = base.buildings.single().entrance
        add(point)
        while (point.x != destination.x) { point = point.copy(x = point.x + (destination.x - point.x).coerceIn(-1, 1)); add(point) }
        while (point.z != destination.z) { point = point.copy(z = point.z + (destination.z - point.z).coerceIn(-1, 1)); add(point) }
    }
    fun drain() {
        var rounds = 0
        while (scheduler.pendingCount() > 0) {
            check(rounds++ < 32) { "Infrastructure starvation callbacks did not drain" }
            scheduler.executeAll()
        }
    }
    override fun close() { development.cancel(); tasks.close() }
}
