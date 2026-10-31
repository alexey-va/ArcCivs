package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.logging.Logger

class CivsAutonomousPlanExtensionTest : FunSpec({
    val old by lazy { legacyGrowthCity() }

    test("missing late service extends the old plan instead of requesting an absent reserved site and backs off on failure") {
        ExtensionHarness(old).use { h ->
            h.startExtension()
            verify(exactly = 1) { h.placement.plan(any()) }
            verify(exactly = 0) { h.placement.reserved(any(), any()) }
            val before = h.city
            h.planned.completeExceptionally(CivsRejection("autonomy-no-site"))
            h.drain()
            h.city shouldBe before
            h.saves shouldBe 0
            h.development.progress(before.id)?.reason shouldBe "autonomy-no-site"
            h.time += 30_001
            h.development.pulse(); h.drain()
            verify(exactly = 1) { h.placement.plan(any()) }
            verify(exactly = 0) { h.placement.reserved(any(), any()) }
        }
    }

    test("FIFO admitted while an extension is computing prevents publication of its late result") {
        ExtensionHarness(old).use { h ->
            h.startExtension()
            verify(exactly = 1) { h.placement.plan(any()) }
            val queued = CivsSettlementPlans.at(settlementPlanTemplates().getValue(BuildingType.HOUSE).first,
                CivPosition(40, 64, 40), 180, UUID(140, 999))
            h.city = CivsRules.placeSite(h.city, h.city.owner, queued)
            h.city = CivsRules.enqueue(h.city, h.city.owner, queued.id)
            val before = h.city
            h.planned.complete(requireNotNull(old.autonomy?.plan).copy(revision = 99))
            h.drain()
            h.city shouldBe before
            h.city.constructionQueue shouldBe listOf(queued.id)
            h.saves shouldBe 0
            h.development.progress(before.id)?.reason shouldBe "stale-development"
        }
    }
})

private class ExtensionHarness(old: Settlement) : AutoCloseable {
    private val templates = settlementPlanTemplates()
    var city = old.copy(inventory = CivInventory(CivStorageMode.PHYSICAL),
        buildings = requireNotNull(old.autonomy?.plan).plots.map { plot ->
            CivsSettlementPlans.at(templates.getValue(plot.building.type).second, plot.building.anchor,
                plot.building.rotation, plot.building.id).copy(phase = BuildingPhase.ACTIVE)
        }, research = CivResearchState(CivResearchId.entries.toSet()))
    var time = 0L
    var saves = 0
    val scheduler = TestTaskScheduler()
    private val tasks = LifecycleTaskScope(scheduler)
    val placement = mockk<CivsAutonomousPlacement>()
    val planned = CompletableFuture<CivSettlementPlan>()
    private val blueprints = mockk<CivsBlueprints>()
    private val plugin = mockk<Plugin>()
    private val mutation: (UUID, (Settlement) -> Settlement) -> CompletableFuture<Settlement> = { _, transform ->
        runCatching { transform(city) }.fold({ next ->
            city = next; saves++; CompletableFuture.completedFuture(next)
        }, { CompletableFuture.failedFuture(it) })
    }
    init {
        every { plugin.logger } returns Logger.getLogger("CivsPlanExtensionTest")
        every { blueprints.maximumLevel(any()) } answers { firstArg<CivBuilding>().type.maxLevel }
        every { placement.infrastructure(any()) } returns CompletableFuture.failedFuture(CivsRejection("autonomy-layout-complete"))
        every { placement.plan(any()) } returns planned
        every { placement.debug(any()) } returns emptyMap()
    }
    val development = CivsAutonomousDevelopment(plugin, tasks, mockk(), blueprints, placement, Executor(Runnable::run),
        { mapOf(city.id to city) }, mutableSetOf(), emptySet(), { false }, { true }, { false },
        { CompletableFuture.completedFuture(emptyList()) }, { true }, { _, action -> action() }, mutation, mutation,
        { _, _ -> error("A detached plan cannot commit a construction design") }, {}, { time })

    fun startExtension() {
        CivsRules.validate(city)
        CivsAutonomy.nextGoal(city)?.type shouldBe BuildingType.WAREHOUSE
        development.pulseAccessRepair(); drain()
        development.accessReady(city) shouldBe true
        development.pulse(); drain() // Settle the existing perimeter check first.
        time += 5_001
        development.pulse(); drain()
    }
    fun drain() {
        var rounds = 0
        while (scheduler.pendingCount() > 0) {
            check(rounds++ < 20) { "Extension callbacks did not drain" }
            scheduler.executeAll()
        }
    }
    override fun close() { development.cancel(); tasks.close() }
}
