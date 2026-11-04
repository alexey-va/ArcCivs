package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.logging.Logger

class CivsReservedPlotFallbackTest : FunSpec({
    val base by lazy { settlementPlanCity().copy(radius = 128) }
    val plan by lazy { CivsSettlementPlans.prepare(base, settlementPlanTemplates(),
        settlementPlanSurface(base.copy(radius = 127)), CivAutonomousAccess()) }

    for (type in listOf(BuildingType.FORESTER, BuildingType.FARM))
    test("a refused first $type reservation queues the valid second without changing adopted intent") {
        ReservedFallbackHarness(base, plan, type, withReachableDepot = type == BuildingType.FORESTER).use { h ->
            h.replies[h.plots[0].building.id] = CompletableFuture.failedFuture(CivsRejection("autonomy-no-site"))
            val before = h.city
            val paid = h.paidPlans.toList()
            h.start()
            h.attempted shouldBe h.plots.take(2).map { it.building.id }
            h.committed shouldBe listOf(h.plots[1].building.id)
            withClue("Reserved $type: progress=${h.development.progress(h.city.id)}; debug=${h.development.debug(h.city.id)}; " +
                "attempted=${h.attempted}; committed=${h.committed}; queue=${h.city.constructionQueue}; target=${h.plots[1].building}; " +
                "route=${plan.routes[h.plots[1].building.id.toString()]}; sources=" +
                h.city.buildings.filter { CivsRules.isDepot(it) || it.type == BuildingType.TOWN_HALL }
                    .map { listOf(it.id, it.type, it.phase, it.entrance) }) {
                h.city.constructionQueue shouldBe listOf(h.plots[1].building.id)
            }
            h.city.building(h.plots[1].building.id) shouldBe h.plots[1].building.copy(phase = BuildingPhase.SITE)
            h.city.buildings.filterNot { it.id == h.plots[1].building.id } shouldBe before.buildings
            h.city.autonomy shouldBe before.autonomy
            h.city.stock shouldBe before.stock
            h.city.inventory shouldBe before.inventory
            h.paidPlans shouldBe paid
            h.recoveries shouldBe 0
            h.development.progress(h.city.id)?.stage shouldBe "queued"
        }
    }

    for (failure in listOf(CivsRejection("world-busy"), CivsRejection("lands-denied"),
        CivsRejection("autonomy-terrain"), CancellationException("Cancelled reserved proof")))
    test("a ${failure.message} refusal never fans out across reserved plots") {
        ReservedFallbackHarness(base, plan, BuildingType.FORESTER).use { h ->
            h.replies[h.plots[0].building.id] = CompletableFuture.failedFuture(failure)
            val before = h.city
            h.start()
            h.attempted shouldBe listOf(h.plots[0].building.id)
            h.city shouldBe before
            h.committed shouldBe emptyList()
            h.recoveries shouldBe 0
        }
    }

    for (lateSuccess in listOf(false, true))
    test("changed adopted intent fences a late reserved ${if (lateSuccess) "success" else "refusal"}") {
        ReservedFallbackHarness(base, plan, BuildingType.FORESTER).use { h ->
            val pending = CompletableFuture<CivAutonomousDesign>()
            h.replies[h.plots[0].building.id] = pending
            h.start()
            h.city = h.city.copy(autonomy = h.city.autonomy!!.copy(plan = plan.copy(revision = plan.revision + 1)))
            val current = h.city
            if (lateSuccess) pending.complete(h.design(h.plots[0]))
            else pending.completeExceptionally(CivsRejection("autonomy-no-site"))
            h.drain()
            h.attempted shouldBe listOf(h.plots[0].building.id)
            h.city shouldBe current
            h.committed shouldBe emptyList()
            h.recoveries shouldBe 0
            h.development.progress(h.city.id)?.reason shouldBe "stale-development"
        }
    }

    test("a cancelled request cannot start the next reservation after its late refusal") {
        ReservedFallbackHarness(base, plan, BuildingType.FORESTER).use { h ->
            val pending = CompletableFuture<CivAutonomousDesign>()
            h.replies[h.plots[0].building.id] = pending
            h.start()
            h.development.cancel()
            pending.completeExceptionally(CivsRejection("autonomy-no-site"))
            h.drain()
            h.attempted shouldBe listOf(h.plots[0].building.id)
            h.committed shouldBe emptyList()
            h.recoveries shouldBe 0
        }
    }

    test("all refused FARM reservations enter the existing recovery for the first slot only after exhaustion") {
        ReservedFallbackHarness(base, plan, BuildingType.FARM).use { h ->
            h.plots.forEach { h.replies[it.building.id] = CompletableFuture.failedFuture(CivsRejection("autonomy-no-site")) }
            val before = h.city
            h.start()
            h.attempted shouldBe h.plots.map { it.building.id }
            h.recoveries shouldBe 1
            h.recoveredTarget shouldBe h.plots[0].building.id
            h.city shouldBe before
            h.committed shouldBe emptyList()
            h.development.progress(h.city.id)?.stage shouldBe "planning"
        }
    }
})

private class ReservedFallbackHarness(base: Settlement, plan: CivSettlementPlan, type: BuildingType,
    withReachableDepot: Boolean = false) : AutoCloseable {
    val plots = plan.plots.filter { it.building.type == type }
    // A later reserved resource site may rely on the town's already built adopted warehouse.
    private val establishedDepot = if (!withReachableDepot) null else plan.plots.first { plot ->
        plot.building.type == BuildingType.WAREHOUSE && plots.take(2).all { target ->
            CivsRules.workRouteWithinReach(base.copy(buildings = base.buildings + plot.building.copy(phase = BuildingPhase.ACTIVE)),
                target.building.entrance)
        }
    }.building.copy(phase = BuildingPhase.ACTIVE)
    var city = base.copy(inventory = CivInventory(CivStorageMode.PHYSICAL),
        buildings = base.buildings.map { it.copy(phase = BuildingPhase.SITE) } +
            CivsAutonomy.baseProfile.takeWhile { it != type }.filterNot { it == BuildingType.TOWN_HALL }.map { previous ->
                plan.plots.first { it.building.type == previous }.building.copy(phase = BuildingPhase.ACTIVE)
            } + listOfNotNull(establishedDepot), autonomy = base.autonomy!!.copy(plan = plan, layoutHalfWidth = plan.halfWidth))
    val paidPlans = city.buildings.filter { it.phase == BuildingPhase.ACTIVE }.map { building ->
        CivPlacementPlan(building.id, city.id, city.worldId, building, emptyList(), terrain = CivTerraformPlan(
            building.bounds, emptyList(), plan.routes.getValue(building.id.toString()), building.anchor.y, 0, 0))
    }
    val scheduler = TestTaskScheduler()
    private val tasks = LifecycleTaskScope(scheduler)
    private val placement = mockk<CivsAutonomousPlacement>()
    private val blueprints = mockk<CivsBlueprints>()
    private val plugin = mockk<Plugin>()
    val replies = mutableMapOf<UUID, CompletableFuture<CivAutonomousDesign>>()
    val attempted = mutableListOf<UUID>()
    val committed = mutableListOf<UUID>()
    var recoveries = 0
    var recoveredTarget: UUID? = null
    private val pendingRecovery = CompletableFuture<CivAutonomousDesign>()
    private val mutation: (UUID, (Settlement) -> Settlement) -> CompletableFuture<Settlement> = { _, transform ->
        runCatching { transform(city) }.fold({ city = it; CompletableFuture.completedFuture(it) }, { CompletableFuture.failedFuture(it) })
    }
    init {
        establishedDepot?.let { depot ->
            val adopted = plan.plots.single { it.building.id == depot.id }.building
            city.building(depot.id) shouldBe adopted.copy(phase = BuildingPhase.ACTIVE)
            paidPlans.single { it.building.id == depot.id }.terrain!!.route shouldBe plan.routes.getValue(depot.id.toString())
            plots.take(2).all { CivsRules.workRouteWithinReach(city, it.building.entrance) } shouldBe true
        }
        every { plugin.logger } returns Logger.getLogger("CivsReservedPlotFallbackTest")
        every { blueprints.maximumLevel(any()) } answers { firstArg<CivBuilding>().type.maxLevel }
        every { placement.reserved(any(), any()) } answers {
            val plot = arg<CivSettlementPlot>(1)
            attempted += plot.building.id
            replies[plot.building.id] ?: CompletableFuture.completedFuture(design(plot))
        }
        every { placement.search(any(), any(), any(), any(), any(), any(), any()) } answers {
            recoveries++
            recoveredTarget = arg(3)
            arg<Boolean>(4) shouldBe true
            arg<CivSettlementPlan>(5) shouldBe plan
            pendingRecovery
        }
        every { placement.debug(any()) } returns emptyMap()
    }
    val development = CivsAutonomousDevelopment(plugin, tasks, mockk(), blueprints, placement, Executor(Runnable::run),
        { mapOf(city.id to city) }, mutableSetOf(), emptySet(), { false }, { true }, { false },
        { CompletableFuture.completedFuture(paidPlans) }, { true }, { _, action -> action() }, mutation, mutation,
        { current, design ->
            val building = design.blueprint.building
            committed += building.id
            city = CivsRules.enqueue(CivsRules.placeSite(current, current.owner, building), current.owner, building.id)
            CompletableFuture.completedFuture(Unit)
        }, {}, { 10_000L })

    fun design(plot: CivSettlementPlot): CivAutonomousDesign {
        val blueprint = mockk<CivBlueprint>()
        every { blueprint.building } returns plot.building
        return CivAutonomousDesign(blueprint)
    }
    fun start() {
        CivsRules.validate(city)
        CivsAutonomy.nextGoal(city, blueprints::maximumLevel)?.type shouldBe plots[0].building.type
        development.pulseAccessRepair(); drain()
        development.accessReady(city) shouldBe true
        development.pulse(); drain()
    }
    fun drain() {
        var rounds = 0
        while (scheduler.pendingCount() > 0) {
            check(rounds++ < 32) { "Reserved fallback callbacks did not drain" }
            scheduler.executeAll()
        }
    }
    override fun close() { development.cancel(); tasks.close() }
}
