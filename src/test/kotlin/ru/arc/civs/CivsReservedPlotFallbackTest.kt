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

    test("late unpaid recovery only applies while the original city and request are current") {
        val hallWithControl = base.buildings.single().copy(control = base.center)
        val town = base.copy(buildings = listOf(hallWithControl))
        val adopted = plan.copy(plots = plan.plots.map { plot ->
            if (plot.building.id == hallWithControl.id) plot.copy(building = plot.building.copy(control = town.center)) else plot
        })
        val accepted = adopted.copy(revision = adopted.revision + 1)
        for (change in listOf("unchanged", "hall", "plan", "building", "construction", "queue", "control", "deletion", "pause", "shutdown")) {
            withClue("City change while recovery forecast is pending: $change") {
                ReservedFallbackHarness(town, adopted, BuildingType.BUILDER_HUT).use { h ->
                    val before = h.city
                    val hall = before.buildings.single()
                    val plot = h.plots.single()
                    val building = plot.building
                    val route = adopted.routes.getValue(building.id.toString())
                    val replacement = h.design(plot).copy(terrain = CivTerraformPlan(
                        building.bounds, emptyList(), route, building.anchor.y, 0, 0))
                    h.replies[building.id] = CompletableFuture.failedFuture(CivsRejection("autonomy-no-site"))
                    h.start()
                    h.attempted shouldBe listOf(building.id)
                    h.recoveries shouldBe 1
                    h.pendingRecovery.complete(replacement) shouldBe true
                    h.drain()
                    h.forecastDesigns shouldBe listOf(replacement)
                    h.pendingForecast.isDone shouldBe false
                    (h.development.debug(before.id)["request"] != null) shouldBe true
                    h.city shouldBe before
                    h.saveAttempts shouldBe 0

                    val current = when (change) {
                        "unchanged" -> before
                        "hall" -> {
                            fun shift(point: CivPosition) = point.copy(x = point.x + 1)
                            val shifted = hall.copy(anchor = shift(hall.anchor),
                                bounds = CivBox(shift(hall.bounds.min), shift(hall.bounds.max)),
                                entrance = shift(hall.entrance), control = hall.control?.let(::shift))
                            before.copy(buildings = listOf(shifted))
                        }
                        "plan" -> before.copy(autonomy = before.autonomy!!.copy(
                            plan = adopted.copy(revision = accepted.revision + 1)))
                        "building" -> before.copy(buildings = before.buildings + building)
                        "construction" -> {
                            val constructing = hall.copy(phase = BuildingPhase.CONSTRUCTING)
                            before.copy(buildings = listOf(constructing), construction = CivConstruction(
                                UUID(164, 10), hall.id, 1, 10, 0, ConstructionPhase.CONSTRUCTING, Stock(),
                                plannedBuilding = constructing, batches = CivConstructionBatches(10, 16, true),
                                materials = emptyMap(), payment = CivConstructionPayment()))
                        }
                        "queue" -> before.copy(constructionQueue = listOf(hall.id))
                        "control" -> before.copy(pendingControls = listOf(
                            CivControlPlacement(hall.id, before.center, "minecraft:air")))
                        "deletion" -> before.copy(pendingDeletion = CivDeletion(null))
                        "pause" -> before.copy(autonomy = before.autonomy!!.copy(paused = true))
                        "shutdown" -> {
                            h.shutdown()
                            h.development.debug(before.id)["request"] shouldBe null
                            before
                        }
                        else -> error("Unknown city change $change")
                    }
                    h.city = CivsRules.validate(current)
                    h.pendingForecast.complete(accepted) shouldBe true
                    h.drain()

                    h.development.debug(before.id)["request"] shouldBe null
                    h.committed shouldBe emptyList()
                    if (change == "unchanged") {
                        val saved = before.copy(revision = before.revision + 1, autonomy = before.autonomy!!.copy(plan = accepted))
                        h.city shouldBe saved
                        h.saves shouldBe listOf(saved)
                        h.development.progress(before.id) shouldBe null
                    } else {
                        h.city shouldBe current
                        h.saves shouldBe emptyList()
                        val expectedReason = if (change == "shutdown") null else "stale-development"
                        h.development.progress(before.id)?.reason shouldBe expectedReason
                        if (change == "shutdown") h.saveAttempts shouldBe 0
                    }
                }
            }
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
        buildings = base.buildings.map { it.copy(phase = if (type == BuildingType.FARM && it.type == BuildingType.TOWN_HALL) BuildingPhase.ACTIVE else BuildingPhase.SITE) } +
            CivsAutonomy.baseProfile.takeWhile { it != type }.filterNot { it == BuildingType.TOWN_HALL }.map { previous ->
                plan.plots.first { it.building.type == previous }.building.copy(phase = BuildingPhase.ACTIVE)
            } + listOfNotNull(establishedDepot), autonomy = base.autonomy!!.copy(plan = plan, layoutHalfWidth = plan.halfWidth))
    val paidPlans = city.buildings.filter { it.phase == BuildingPhase.ACTIVE }.map { building ->
        // A completed founding hall has no road from an earlier depot.
        if (building.type == BuildingType.TOWN_HALL)
            return@map CivPlacementPlan(building.id, city.id, city.worldId, building, emptyList())
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
    val pendingRecovery = CompletableFuture<CivAutonomousDesign>()
    val pendingForecast = CompletableFuture<CivSettlementPlan>()
    val forecastDesigns = mutableListOf<CivAutonomousDesign>()
    val saves = mutableListOf<Settlement>()
    var saveAttempts = 0
        private set
    private var closed = false
    private val mutation: (UUID, (Settlement) -> Settlement) -> CompletableFuture<Settlement> = { _, transform ->
        saveAttempts++
        runCatching { transform(city) }.fold({ next ->
            city = next
            saves += next
            CompletableFuture.completedFuture(next)
        }, { CompletableFuture.failedFuture(it) })
    }
    init {
        if (type == BuildingType.FARM) {
            val hall = city.buildings.single { it.type == BuildingType.TOWN_HALL }
            hall.phase shouldBe BuildingPhase.ACTIVE
            val next = requireNotNull(CivsAutonomy.nextGoal(city))
            next.type shouldBe BuildingType.FARM
            next.buildingId shouldBe null
            paidPlans.single { it.building.id == hall.id }.terrain shouldBe null
            (hall.id in CivAutonomousAccess.from(city, paidPlans).routes) shouldBe false
        }
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
        every { placement.plan(any(), any(), false) } answers {
            forecastDesigns += arg<CivAutonomousDesign>(1)
            pendingForecast
        }
        every { placement.debug(any()) } returns emptyMap()
    }
    val development = CivsAutonomousDevelopment(plugin, tasks, mockk(), blueprints, placement, Executor(Runnable::run),
        { mapOf(city.id to city) }, mutableSetOf(), emptySet(), { false }, { true }, { closed },
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
    fun shutdown() {
        closed = true
        development.cancel()
    }

    override fun close() { shutdown(); tasks.close() }
}
