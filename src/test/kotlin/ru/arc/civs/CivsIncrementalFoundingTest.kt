package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import org.bukkit.Material
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.logging.Logger
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler

class CivsIncrementalDevelopmentTest : FunSpec({
    test("a planless first goal reaches ordinary SITE queue admission without forecasting or resource credit") {
        var city = settlementPlanCity().copy(inventory = CivInventory(CivStorageMode.PHYSICAL))
        val before = city
        val placement = mockk<CivsAutonomousPlacement>()
        val blueprints = mockk<CivsBlueprints>()
        val plugin = mockk<org.bukkit.plugin.Plugin>()
        every { plugin.logger } returns Logger.getLogger("IncrementalDevelopmentTest")
        every { blueprints.maximumLevel(any()) } answers { firstArg<CivBuilding>().type.maxLevel }
        every { placement.plan(any()) } throws AssertionError("No complete forecast for planless development")
        every { placement.debug(any()) } returns emptyMap()
        val pending = CompletableFuture<CivAutonomousDesign>()
        every { placement.search(any(), BuildingType.BUILDER_HUT, 1, any()) } returns pending
        val scheduler = TestTaskScheduler()
        val tasks = LifecycleTaskScope(scheduler)
        var commits = 0
        val mutation: (UUID, (Settlement) -> Settlement) -> CompletableFuture<Settlement> = { _, transform ->
            city = transform(city); CompletableFuture.completedFuture(city)
        }
        val development = CivsAutonomousDevelopment(plugin, tasks, mockk(), blueprints, placement, Executor(Runnable::run),
            { mapOf(city.id to city) }, mutableSetOf(), emptySet(), { false }, { true }, { false },
            { CompletableFuture.completedFuture(emptyList()) }, { true }, { _, action -> action() }, mutation, mutation,
            { current, design ->
                current shouldBe city
                commits++
                city = CivsRules.enqueue(CivsRules.placeSite(current, current.owner, design.blueprint.building),
                    current.owner, design.blueprint.building.id)
                CompletableFuture.completedFuture(Unit)
            }, {}, { 1_000L })
        fun drain() { repeat(10) { if (scheduler.pendingCount() > 0) scheduler.executeAll() } }
        try {
            development.pulseAccessRepair(); drain()
            development.pulse(); drain()
            verify(exactly = 0) { placement.plan(any()) }
            verify(exactly = 1) { placement.search(before, BuildingType.BUILDER_HUT, 1, any()) }
            city shouldBe before
            commits shouldBe 0
            val hut = CivsSettlementPlans.at(settlementPlanTemplates().getValue(BuildingType.BUILDER_HUT).first,
                CivPosition(20, 64, 20), 0, UUID.randomUUID()).copy(phase = BuildingPhase.SITE)
            pending.complete(CivAutonomousDesign(CivBlueprint(hut, city.worldId,
                mockk(relaxed = true), emptyList(), emptyList(), byteArrayOf())))
            drain()
            commits shouldBe 1
            city.building(hut.id)!!.phase shouldBe BuildingPhase.SITE
            city.constructionQueue shouldBe listOf(hut.id)
            city.construction shouldBe null
            city.stock shouldBe before.stock
            city.inventory shouldBe before.inventory
            city.workers shouldBe before.workers
            city.autonomy!!.plan shouldBe null
            verify(exactly = 0) { placement.plan(any()) }
        } finally { development.cancel(); tasks.close() }
    }
})

class CivsIncrementalFoundingTest : FunSpec({
    test("a planless native hall enters the ordinary durable SITE control receipt without construction credit") {
        incrementalFounding { h, f ->
            val result = f.owner.foundAutomatic(f.admission)
            h.tickUntil("initial captured record") { h.saves.isNotEmpty() }
            result.isDone shouldBe false
            h.service.settlements() shouldBe emptyList()
            val saved = h.saves.single().first
            saved.buildings shouldBe f.city.buildings
            saved.autonomy!!.plan shouldBe null
            saved.workers shouldBe emptyList()
            saved.construction shouldBe null
            saved.constructionQueue.orEmpty() shouldBe emptyList()
            saved.inventory shouldBe f.city.inventory
            saved.stock shouldBe f.city.stock
            saved.pendingControls.orEmpty().single().buildingId shouldBe f.hall.id
            val control = requireNotNull(f.hall.control)
            h.world.getBlockAt(control.x, control.y, control.z).type shouldBe Material.AIR
            verify(exactly = 1) { h.worldAdapter.captureAutonomousSite(f.city.id, f.city.owner, f.city.worldId,
                f.hall, f.blueprint.cells, null, null) }
            verify(exactly = 1) { h.store.savePlan(match { it.id == f.hall.id && !it.adminPlacement }) }
            h.commit()
            h.await(result).id shouldBe f.city.id
            h.world.getBlockAt(control.x, control.y, control.z).type shouldBe Material.BARREL
            if (h.saves.last().second.isDone == false) h.commit()
            val current = h.service.settlements().single()
            current.buildings.single().phase shouldBe BuildingPhase.SITE
            current.construction shouldBe null
            current.workers shouldBe emptyList()
            current.inventory shouldBe f.city.inventory
            current.pendingControls.orEmpty() shouldBe emptyList()
            val saves = h.saves.size
            f.owner.foundAutomatic(f.admission).join() shouldBe current
            h.saves.size shouldBe saves
            verify(exactly = 1) { h.worldAdapter.captureAutonomousSite(any(), any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    for (failure in listOf("native-denied", "permission-changed"))
    test("planless founding preserves the native and current authority fence for $failure") {
        incrementalFounding { h, f ->
            val pending = CompletableFuture<CivPlacementPlan>()
            every { h.worldAdapter.captureAutonomousSite(any(), any(), any(), any(), any(), any(), any()) } returns pending
            val result = f.owner.foundAutomatic(f.admission)
            h.saves shouldBe emptyList()
            if (failure == "native-denied") pending.completeExceptionally(CivsRejection("lands-denied"))
            else { f.current = false; pending.complete(f.plan) }
            val error = shouldThrow<CompletionException> { h.await(result) }
            CivsDiagnostics.rejection(error)?.reason shouldBe if (failure == "native-denied") "lands-denied" else "not-ready"
            h.saves shouldBe emptyList()
            h.service.settlements() shouldBe emptyList()
            verify(exactly = 0) { h.store.savePlan(any()); h.store.saveInventoryTransaction(any()) }
        }
    }

    for (invalidVersion in listOf(false, true))
    test("a supplied incomplete or malformed plan is still rejected before native capture invalidVersion=$invalidVersion") {
        incrementalFounding { h, f ->
            val gate = CivBox(f.city.center.copy(x = 30), f.city.center.copy(x = 30, y = 67))
            val layout = CivSettlementPlan(if (invalidVersion) -1 else CivsSettlementPlans.VERSION, 0, 72,
                emptyList(), emptyMap(), emptyList(), listOf(gate), listOf(1))
            val admission = f.admission.copy(city = f.city.copy(autonomy = f.city.autonomy!!.copy(plan = layout)))
            if (invalidVersion) shouldThrow<IllegalArgumentException> { f.owner.foundAutomatic(admission) }
            else shouldThrow<CivsRejection> { f.owner.foundAutomatic(admission) }.reason shouldBe "autonomy-no-site"
            h.saves shouldBe emptyList()
            h.service.settlements() shouldBe emptyList()
            verify(exactly = 0) { h.worldAdapter.captureAutonomousSite(any(), any(), any(), any(), any(), any(), any()) }
        }
    }
})

private fun incrementalFounding(test: (ServiceScenario, IncrementalFounding) -> Unit) {
    mockkConstructor(CivsChunkPermissions::class)
    try {
        every { anyConstructed<CivsChunkPermissions>().allowed(any(), any()) } returns true
        serviceScenario(empty = true, nativeGeometry = true, serverFixture = CivsWorksiteServerFixture()) { h ->
            val f = IncrementalFounding(h)
            h.start()
            test(h, f)
        }
    } finally { unmockkConstructor(CivsChunkPermissions::class) }
}

private class IncrementalFounding(h: ServiceScenario) {
    val blueprint = h.previewBlueprint().let { it.copy(building = it.building.copy(phase = BuildingPhase.SITE)) }
    val hall = blueprint.building
    val city = CivsAutonomy.found(UUID.randomUUID(), h.owner, "Incremental", h.world.uid,
        requireNotNull(hall.control), 96, "nordic", hall, h.time).copy(
        settings = CivSettlementSettings(chunkLoading = true, immortalResidents = true, autoConstructionMaterials = false),
        autonomy = CivAutonomy(layoutHalfWidth = 72))
    val plan = CivPlacementPlan(hall.id, city.id, city.worldId, hall, emptyList())
    var current = true
    val admission = CivVillageSiteAdmission(city, blueprint, null) { current }
    val owner = CivsService::class.java.getDeclaredField("buildingRequests").apply { isAccessible = true }
        .get(h.service) as CivsBuildingRequests

    init {
        every { blueprint.snapshot.fileSha256 } returns "a".repeat(64)
        every { h.worldAdapter.scanWorld(h.world.uid) } returns h.world
        every { h.worldAdapter.captureAutonomousSite(any(), any(), any(), any(), any(), any(), any()) } returns
            CompletableFuture.completedFuture(plan)
        every { h.store.saveSchematic(any(), any()) } returns CompletableFuture.completedFuture(Unit)
        every { h.store.savePlan(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
        h.world.getBlockAt(hall.entrance.x, hall.entrance.y - 1, hall.entrance.z).type = Material.DIRT
    }
}
