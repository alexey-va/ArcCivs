package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicPoint
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.logging.Logger

class CivsInitialPlanDeferralTest : FunSpec({
    test("a rejected unadopted perimeter lets the first hut reach ordinary native capture and SITE queue without credit") {
        InitialPlanHarness().use { h ->
            val before = h.city
            h.start(); h.rejectPlan()
            h.city shouldBe before
            h.saves shouldBe 0
            h.waiting shouldBe false
            h.time = 5_001; h.pulse()
            h.plans.size shouldBe 1
            h.searches.size shouldBe 1
            val design = h.hutDesign()
            h.searches.single().complete(design); h.drain()
            verify(exactly = 1) { h.world.captureAutonomousSite(before.id, before.owner, before.worldId,
                design.blueprint.building, design.blueprint.cells, design.terrain) }
            h.commits shouldBe 1
            h.city.building(design.blueprint.building.id)!!.phase shouldBe BuildingPhase.SITE
            h.city.constructionQueue shouldBe listOf(design.blueprint.building.id)
            h.city.construction shouldBe null
            h.city.autonomy shouldBe before.autonomy
            h.city.radius shouldBe before.radius
            h.city.stock shouldBe before.stock
            h.city.inventory shouldBe before.inventory
            h.city.workers shouldBe before.workers
        }
    }

    test("ordinary native rejection still prevents SITE admission during the deferred forecast") {
        InitialPlanHarness().use { h ->
            val before = h.city
            h.start(); h.rejectPlan()
            h.captureFailure = CivsRejection("protected-terrain")
            h.time = 5_001; h.pulse()
            h.searches.single().complete(h.hutDesign()); h.drain()
            verify(exactly = 1) { h.world.captureAutonomousSite(any(), any(), any(), any(), any(), any()) }
            h.city shouldBe before
            h.saves shouldBe 0
            h.development.progress(before.id)?.reason shouldBe "protected-terrain"
        }
    }

    test("the five minute deadline retries strict planning and adopts a later successful plan") {
        InitialPlanHarness().use { h ->
            val before = h.city
            h.start(); h.rejectPlan()
            h.time = 5_001; h.pulse()
            h.searches.single().completeExceptionally(CivsRejection("world-busy")); h.drain()
            h.time = 300_001; h.pulse()
            h.plans.size shouldBe 2
            h.searches.size shouldBe 1
            val plan = h.plan()
            h.plans.last().complete(plan); h.drain()
            h.saves shouldBe 1
            CivsSettlementPlans.current(h.city) shouldBe plan
            h.city.autonomy!!.layoutHalfWidth shouldBe plan.halfWidth
            h.city.buildings shouldBe before.buildings
            h.city.stock shouldBe before.stock
            h.city.inventory shouldBe before.inventory
            h.city.workers shouldBe before.workers
            h.city.constructionQueue.orEmpty() shouldBe emptyList()
            h.waiting shouldBe false
        }
    }

    for (failure in listOf(
        CivsRejection("autonomy-no-site", mapOf("stage" to "infrastructure")),
        CivsRejection("autonomy-layout-obstructed", mapOf("stage" to "dry-perimeter")),
        IllegalStateException("autonomy-layout-obstructed infrastructure"),
    )) test("other forecast failures cannot authorize ordinary search: ${failure.message}") {
        InitialPlanHarness().use { h ->
            val before = h.city
            h.start(); h.rejectPlan(failure)
            h.time = 5_001; h.pulse()
            h.searches shouldBe emptyList()
            h.plans.size shouldBe 1
            h.city shouldBe before
            h.saves shouldBe 0
        }
    }

    for (invalid in listOf(false, true))
    test("an existing saved plan cannot use founding deferral even when obsolete=$invalid") {
        InitialPlanHarness().use { h ->
            val saved = h.plan(includeHut = false).let { if (invalid) it.copy(version = 99) else it }
            h.city = h.city.copy(autonomy = h.city.autonomy!!.copy(plan = saved))
            h.start(); h.rejectPlan()
            h.time = 5_001; h.pulse()
            h.searches shouldBe emptyList()
            h.city.autonomy!!.plan shouldBe saved
            h.saves shouldBe 0
        }
    }

    for (change in listOf("owner", "layout"))
    test("a stale failed forecast cannot grant deferral after its $change changes") {
        InitialPlanHarness().use { h ->
            h.start()
            val captured = h.city
            h.change(change)
            h.rejectPlan()
            h.city = captured
            h.time = 5_001; h.start()
            h.searches shouldBe emptyList()
            h.city shouldBe captured
            h.saves shouldBe 0
        }
    }

    for (ending in listOf("pause", "reset", "cancel", "remove", "owner", "layout", "saved plan"))
    test("founding deferral is invalidated by $ending") {
        InitialPlanHarness().use { h ->
            h.start(); h.rejectPlan()
            when (ending) {
                "pause" -> h.development.paused(h.city.id)
                "reset" -> h.development.reset()
                "cancel" -> h.development.cancel()
                "remove" -> { h.present = false; h.time = 5_001; h.pulse(); h.present = true }
                "saved plan" -> h.city = h.city.copy(autonomy = h.city.autonomy!!.copy(plan = h.plan()))
                else -> h.change(ending)
            }
            h.time = 10_002; h.start()
            h.searches shouldBe emptyList()
            h.saves shouldBe 0
            h.plans.size shouldBe if (ending == "reset") 2 else 1
            verify(exactly = if (ending == "saved plan") 1 else 0) { h.placement.reserved(any(), any()) }
            verify(exactly = 0) { h.world.captureAutonomousSite(any(), any(), any(), any(), any(), any()) }
        }
    }
})

private class InitialPlanHarness : AutoCloseable {
    var city = settlementPlanCity().let { base -> CivsAutonomy.found(base.id, base.owner, "Compact founding", base.worldId,
        base.center, 96, base.style, base.buildings.single(), 1_000) }
        // The native founding reserve has already been materialized before autonomous development is admitted.
        .copy(inventory = CivInventory(CivStorageMode.PHYSICAL))
    var present = true
    var time = 0L
    var saves = 0
    var commits = 0
    var waiting = false
    var captureFailure: Throwable? = null
    private val scheduler = TestTaskScheduler()
    private val tasks = LifecycleTaskScope(scheduler)
    val placement = mockk<CivsAutonomousPlacement>()
    val world = mockk<CivsWorld>()
    val plans = mutableListOf<CompletableFuture<CivSettlementPlan>>()
    val searches = mutableListOf<CompletableFuture<CivAutonomousDesign>>()
    private val blueprints = mockk<CivsBlueprints>()
    private val plugin = mockk<Plugin>()
    private val mutation: (UUID, (Settlement) -> Settlement) -> CompletableFuture<Settlement> = { _, transform ->
        runCatching { transform(city) }.fold({ city = it; saves++; CompletableFuture.completedFuture(it) },
            { CompletableFuture.failedFuture(it) })
    }
    init {
        city.autonomy!!.layoutHalfWidth shouldBe 32
        city.buildings.single().phase shouldBe BuildingPhase.SITE
        CivsAutoStorageMigration.ready(city) shouldBe true
        CivsAutonomy.nextGoal(city)?.type shouldBe BuildingType.BUILDER_HUT
        every { plugin.logger } returns Logger.getLogger("CivsInitialPlanDeferralTest")
        every { blueprints.maximumLevel(any()) } answers { firstArg<CivBuilding>().type.maxLevel }
        every { placement.plan(any()) } answers { CompletableFuture<CivSettlementPlan>().also(plans::add) }
        every { placement.search(any(), any(), any(), any(), any(), any(), any()) } answers {
            CompletableFuture<CivAutonomousDesign>().also(searches::add)
        }
        every { placement.reserved(any(), any()) } answers { CompletableFuture() }
        every { placement.debug(any()) } returns emptyMap()
        every { world.captureAutonomousSite(any(), any(), any(), any(), any(), any()) } answers {
            captureFailure?.let { CompletableFuture.failedFuture<CivPlacementPlan>(it) } ?: CompletableFuture.completedFuture(
                CivPlacementPlan(arg<CivBuilding>(3).id, firstArg<UUID>(), arg<UUID>(2), arg<CivBuilding>(3), emptyList()))
        }
    }
    val development = CivsAutonomousDevelopment(plugin, tasks, world, blueprints, placement, Executor(Runnable::run),
        { if (present) mapOf(city.id to city) else emptyMap() }, mutableSetOf(), emptySet(), { false }, { true }, { false },
        { CompletableFuture.completedFuture(emptyList()) }, { true }, { _, action -> action() }, mutation, mutation,
        { current, design ->
            current shouldBe city
            commits++
            world.captureAutonomousSite(current.id, current.owner, current.worldId, design.blueprint.building,
                design.blueprint.cells, design.terrain).thenApply {
                city = CivsRules.enqueue(CivsRules.placeSite(current, current.owner, design.blueprint.building),
                    current.owner, design.blueprint.building.id)
                Unit
            }
        }, {}, { time }, developmentFinished = { waiting = false }, developmentAdmission = { waiting = true; true })

    fun start() { development.pulseAccessRepair(); drain(); pulse() }
    fun pulse() { development.pulse(); drain() }
    fun rejectPlan(failure: Throwable = CivsRejection("autonomy-layout-obstructed", mapOf("stage" to "infrastructure"))) {
        plans.last().completeExceptionally(failure); drain()
    }
    fun change(what: String) {
        city = if (what == "owner") city.copy(owner = UUID(209, 90))
            else city.copy(buildings = city.buildings.map { it.copy(schematicId = "fixture/changed-hall") })
    }
    fun hutDesign(): CivAutonomousDesign {
        val hut = CivsSettlementPlans.at(settlementPlanTemplates().getValue(BuildingType.BUILDER_HUT).first,
            CivPosition(20, 64, 20), 0, UUID(209, 1)).copy(phase = BuildingPhase.SITE)
        val cells = listOf(ArcSchematicBlock(ArcSchematicPoint(20, 64, 20), "minecraft:oak_planks", false, false))
        return CivAutonomousDesign(CivBlueprint(hut, city.worldId, mockk(relaxed = true), cells, cells, byteArrayOf(1)))
    }
    fun plan(includeHut: Boolean = true): CivSettlementPlan {
        val frame = CivsSettlementLayout.frame(city)
        val buildings = city.buildings + if (includeHut) listOf(hutDesign().blueprint.building) else emptyList()
        return CivSettlementPlan(CivsSettlementPlans.VERSION, 0, frame.halfWidth,
            buildings.map { CivSettlementPlot(it.copy(phase = BuildingPhase.SITE), it.bounds) }, emptyMap(),
            frame.wallObstacles, frame.gates.map { it.passage }, frame.gates.map { it.side })
    }
    fun drain() {
        var rounds = 0
        while (scheduler.pendingCount() > 0) { check(rounds++ < 20); scheduler.executeAll() }
    }
    override fun close() { development.cancel(); tasks.close() }
}
