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

class CivsOptionalPlanBackoffTest : FunSpec({
    test("road admission blocks repeated planning before preparation and releases exactly one search") {
        OptionalPlanHarness(newPlot = true).use { h ->
            h.admitted = false
            h.start()
            for (at in listOf(5_001L, 10_002L, 15_003L)) { h.time = at; h.pulse() }
            h.waiting shouldBe true
            h.development.progress(h.city.id)?.reason shouldBe "city-busy"
            h.development.debug(h.city.id)["request"] shouldBe null
            h.upgrades shouldBe emptyList()
            h.committed shouldBe emptyList()
            verify(exactly = 0) { h.placement.plan(any()) }
            verify(exactly = 0) { h.placement.search(any(), any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { h.placement.infrastructure(any()) }
            h.admitted = true
            h.time = 20_004; h.pulse()
            h.time = 25_005; h.pulse()
            h.admissionChecks shouldBe 5
            verify(exactly = 1) { h.placement.search(any(), BuildingType.WAREHOUSE, 1, any(), any(), any(), any()) }
        }
    }

    test("preflight admission preserves the final commit admission after preparation") {
        OptionalPlanHarness().use { h ->
            h.start()
            h.finalAdmitted = false
            h.time = 5_001; h.pulse()
            h.upgrades.size shouldBe 1
            h.finalAdmissionChecks shouldBe 1
            h.committed shouldBe emptyList()
            h.development.progress(h.city.id)?.reason shouldBe "city-busy"
            h.admitted = false
            h.time = 10_002; h.pulse()
            h.upgrades.size shouldBe 1
            h.finalAdmissionChecks shouldBe 1
            h.admitted = true
            h.finalAdmitted = true
            h.time = 15_003; h.pulse()
            h.upgrades.size shouldBe 2
            h.finalAdmissionChecks shouldBe 2
            h.committed shouldBe listOf(requireNotNull(h.quarry).id)
        }
    }

    for (ending in listOf("search failure", "pause", "reset"))
    test("a planning $ending releases its road development handoff") {
        OptionalPlanHarness(newPlot = true).use { h ->
            h.start()
            h.time = 5_001; h.pulse()
            h.waiting shouldBe true
            when (ending) {
                "search failure" -> h.failSearch("autonomy-no-site")
                "pause" -> h.development.paused(h.city.id)
                else -> h.development.reset()
            }
            h.waiting shouldBe false
            h.development.debug(h.city.id)["request"] shouldBe null
            h.committed shouldBe emptyList()
        }
    }

    test("optional layout completion releases the road handoff before its ordinary goal retries") {
        OptionalPlanHarness().use { h ->
            h.start()
            h.finished shouldBe 1
            h.waiting shouldBe false
            h.upgrades shouldBe emptyList()
            CivsAutonomy.nextGoal(h.city)?.buildingId shouldBe requireNotNull(h.quarry).id
        }
    }

    test("a town without another ordinary goal releases a previously held road handoff") {
        OptionalPlanHarness(noOrdinary = true).use { h ->
            h.start()
            h.waiting = true
            val finished = h.finished
            h.time = 5_001; h.pulse()
            h.waiting shouldBe false
            h.finished shouldBe finished + 1
            verify(exactly = 1) { h.placement.infrastructure(any()) }
        }
    }

    for ((reason, retry) in listOf("autonomy-no-site" to 30_001L, "world-busy" to 5_001L))
    test("optional infrastructure $reason leaves a planless quarry upgrade eligible after its ordinary retry") {
        OptionalPlanHarness().use { h ->
            h.infrastructureFailure = reason
            val before = h.city
            val quarry = requireNotNull(h.quarry)
            CivsAutonomy.nextGoal(before)?.let { Triple(it.type, it.targetLevel, it.buildingId) } shouldBe
                Triple(BuildingType.QUARRY, 2, quarry.id)
            h.start()
            h.city shouldBe before
            h.time = retry - 5_001
            h.pulse()
            h.upgrades shouldBe emptyList()
            h.time = retry
            h.pulse()
            h.upgrades shouldBe listOf(quarry.id to 2)
            h.committed shouldBe listOf(quarry.id)
            h.city.constructionQueue shouldBe listOf(quarry.id)
            h.city.buildings shouldBe before.buildings
            h.city.stock shouldBe before.stock
            h.city.inventory shouldBe before.inventory
            h.city.autonomy shouldBe before.autonomy
            h.development.progress(before.id)?.stage shouldBe "queued"
            verify(exactly = 0) { h.placement.plan(any()) }
            verify(exactly = 1) { h.placement.infrastructure(any()) }
            verify(exactly = 0) { h.placement.search(any(), any(), any(), any(), any(), any(), any()) }
        }
    }

    test("an optional infrastructure backoff does not bypass an ordinary upgrade refusal and expires at the same deadline") {
        OptionalPlanHarness().use { h ->
            h.infrastructureFailure = "autonomy-no-site"
            h.upgradeFailure = "lands-denied"
            val before = h.city
            h.start()
            h.time = 30_001; h.pulse()
            h.upgrades shouldBe listOf(requireNotNull(h.quarry).id to 2)
            h.committed shouldBe emptyList()
            h.city shouldBe before
            h.development.progress(before.id)?.reason shouldBe "lands-denied"
            h.time = 300_001; h.pulse()
            verify(exactly = 0) { h.placement.plan(any()) }
            verify(exactly = 2) { h.placement.infrastructure(any()) }
            h.upgrades.size shouldBe 1
            h.committed shouldBe emptyList()
            h.city shouldBe before
        }
    }

    test("intervening building geometry invalidates the failed optional infrastructure check") {
        OptionalPlanHarness().use { h ->
            h.infrastructureFailure = "autonomy-no-site"
            h.start()
            val hall = h.city.buildings.first()
            fun moved(p: CivPosition) = p.copy(x = p.x + 1)
            h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings.map {
                if (it.id != hall.id) it else it.copy(anchor = moved(it.anchor), entrance = moved(it.entrance),
                    control = it.control?.let(::moved), bounds = CivBox(moved(it.bounds.min), moved(it.bounds.max)))
            }))
            h.development.pulseAccessRepair(); h.drain()
            val afterMove = h.city
            h.time = 30_001; h.pulse()
            verify(exactly = 0) { h.placement.plan(any()) }
            verify(exactly = 2) { h.placement.infrastructure(any()) }
            h.upgrades shouldBe emptyList()
            h.committed shouldBe emptyList()
            h.city shouldBe afterMove
        }
    }

    test("a missing new plot retries only its single-goal search when the unchanged refusal expires") {
        OptionalPlanHarness(newPlot = true).use { h ->
            CivsAutonomy.nextGoal(h.city)?.let { it.type to it.buildingId } shouldBe (BuildingType.WAREHOUSE to null)
            val before = h.city
            h.start(); h.failSearch("autonomy-no-site")
            h.time = 30_001; h.pulse()
            verify(exactly = 1) { h.placement.search(any(), BuildingType.WAREHOUSE, 1, any(), any(), any(), any()) }
            h.city shouldBe before
            h.time = 300_001; h.pulse()
            verify(exactly = 0) { h.placement.plan(any()) }
            verify(exactly = 2) { h.placement.search(any(), BuildingType.WAREHOUSE, 1, any(), any(), any(), any()) }
            h.upgrades shouldBe emptyList()
            h.committed shouldBe emptyList()
            h.city shouldBe before
        }
    }

    test("a town without an ordinary existing goal retains its normal infrastructure retry") {
        OptionalPlanHarness(noOrdinary = true).use { h ->
            CivsAutonomy.nextGoal(h.city) shouldBe null
            h.start()
            h.time = 30_001; h.pulse()
            verify(exactly = 0) { h.placement.plan(any()) }
            verify(exactly = 1) { h.placement.infrastructure(any()) }
            h.upgrades shouldBe emptyList()
            h.committed shouldBe emptyList()
        }
    }
})

private class OptionalPlanHarness(newPlot: Boolean = false, noOrdinary: Boolean = false) : AutoCloseable {
    private val types = if (noOrdinary) CivsAutonomy.completeProfile else
        CivsAutonomy.profile + List(2) { BuildingType.HOUSE } + if (newPlot) emptyList() else listOf(BuildingType.QUARRY)
    var city = CivsRules.validate(Settlement(UUID(177, 1), UUID(177, 2), "Legacy optional plan", UUID(177, 3),
        CivPosition(0, 64, 0), 128, "fixture", Stock(), types.mapIndexed { index, type ->
            val x = index % 5 * 16 - 32; val z = index / 5 * 16 - 40
            val level = if (!newPlot && !noOrdinary && index == types.lastIndex) 1 else type.maxLevel
            CivBuilding(UUID(177, 100L + index), type, level, CivPosition(x, 64, z), 0, "fixture/$type/$level",
                CivBox(CivPosition(x - 3, 62, z - 7), CivPosition(x + 3, 76, z - 1)),
                CivPosition(x, 65, z), BuildingPhase.ACTIVE, CivPosition(x + 1, 65, z - 1))
        }, emptyList(), inventory = CivInventory(CivStorageMode.PHYSICAL), autonomy = CivAutonomy(layoutHalfWidth = 56),
        research = CivResearchState(CivResearchId.entries.toSet())))
    val quarry get() = city.buildings.firstOrNull { it.type == BuildingType.QUARRY && it.level == 1 }
    var time = 0L
    var upgradeFailure: String? = null
    var infrastructureFailure = "autonomy-layout-complete"
    var admitted = true
    var finalAdmitted = true
    var admissionChecks = 0
    var finalAdmissionChecks = 0
    var waiting = false
    var finished = 0
    val upgrades = mutableListOf<Pair<UUID, Int>>()
    val committed = mutableListOf<UUID>()
    private val scheduler = TestTaskScheduler()
    private val tasks = LifecycleTaskScope(scheduler)
    val placement = mockk<CivsAutonomousPlacement>()
    private val searched = CompletableFuture<CivAutonomousDesign>()
    private val blueprints = mockk<CivsBlueprints>()
    private val plugin = mockk<Plugin>()
    private val mutation: (UUID, (Settlement) -> Settlement) -> CompletableFuture<Settlement> = { _, change ->
        runCatching { change(city) }.fold({ city = it; CompletableFuture.completedFuture(it) }, { CompletableFuture.failedFuture(it) })
    }
    init {
        every { plugin.logger } returns Logger.getLogger("CivsOptionalPlanBackoffTest")
        every { blueprints.maximumLevel(any()) } answers { firstArg<CivBuilding>().type.maxLevel }
        every { blueprints.smallDecorations(any()) } returns emptyList()
        every { blueprints.prepareUpgrade(any(), any(), any()) } answers {
            val building = firstArg<CivBuilding>(); val level = secondArg<Int>()
            upgrades += building.id to level
            val failure = upgradeFailure
            if (failure != null) CompletableFuture.failedFuture(CivsRejection(failure))
            else CompletableFuture.completedFuture(mockk<CivBlueprint>().also { blueprint ->
                every { blueprint.building } returns building.copy(level = level, phase = BuildingPhase.CONSTRUCTING)
            })
        }
        every { placement.plan(any()) } returns CompletableFuture.failedFuture(CivsRejection("unexpected-full-plan"))
        every { placement.search(any(), any(), any(), any(), any(), any(), any()) } returns searched
        every { placement.infrastructure(any()) } answers { CompletableFuture.failedFuture(CivsRejection(infrastructureFailure)) }
        every { placement.debug(any()) } returns emptyMap()
    }
    val development = CivsAutonomousDevelopment(plugin, tasks, mockk(), blueprints, placement, Executor(Runnable::run),
        { mapOf(city.id to city) }, mutableSetOf(), emptySet(), { false }, { true }, { false },
        { CompletableFuture.completedFuture(emptyList()) }, { true }, { _, action -> action() }, mutation, mutation,
        { current, design ->
            finalAdmissionChecks++
            if (!finalAdmitted) CompletableFuture.failedFuture(CivsRejection("city-busy"))
            else {
                val id = design.blueprint.building.id
                committed += id
                city = CivsRules.enqueue(current, current.owner, id)
                CompletableFuture.completedFuture(Unit)
            }
        }, {}, { time }, developmentFinished = { waiting = false; finished++ },
        developmentAdmission = { waiting = true; admissionChecks++; admitted })
    fun start() { development.pulseAccessRepair(); drain(); pulse() }
    fun pulse() { development.pulse(); drain() }
    fun failSearch(reason: String) { searched.completeExceptionally(CivsRejection(reason)); drain() }
    fun drain() {
        var count = 0
        while (scheduler.pendingCount() > 0) { check(count++ < 20); scheduler.executeAll() }
    }
    override fun close() { development.cancel(); tasks.close() }
}
