package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.verify
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.persistence.AtomicFileStore
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

class CivsCityPlansCacheTest : FunSpec({
    test("five retained journals are decoded once for repeated access and nonstructural city changes") {
        accessCacheFixture { h -> h.countReads {
            val first = h.load()
            first.routes.size shouldBe 5
            h.reads shouldBe 5
            h.city = h.city.copy(stock = Stock(wood = 12), revision = 42,
                workers = listOf(CivWorker(UUID(0, 91), "Worker", h.city.buildings[1].id, completedCycles = 8)),
                buildings = h.city.buildings.map { if (it.type == BuildingType.QUARRY) it.copy(production = "STONE") else it })
            repeat(3) { h.load().routes shouldBe first.routes }
            h.reads shouldBe 5
            verify { h.maintenance.publish(h.city, any()) }
            val raw = h.await(h.owner.loadAutonomousPlans(h.city))
            raw.size shouldBe 5
            raw.map { it.blocks.single().after }.toSet() shouldBe setOf("minecraft:stone")
            h.reads shouldBe 10
        } }
    }

    test("retained access observes authority spatial adopted-plan override and capture identity changes") {
        for (change in listOf("owner", "world", "center", "radius", "building", "adopted-plan", "override", "warehouse-capture")) {
        accessCacheFixture { h -> h.countReads {
            h.load()
            val forester = h.city.buildings[1]
            h.city = when (change) {
                "owner" -> h.city.copy(owner = UUID(0, 92))
                "world" -> h.city.copy(worldId = UUID(0, 93))
                "center" -> h.city.copy(center = h.city.center.copy(z = 1))
                "radius" -> h.city.copy(radius = 95)
                "building" -> h.city.copy(buildings = h.city.buildings.map {
                    if (it.id == forester.id) it.copy(control = it.entrance.copy(z = 1)) else it })
                "adopted-plan" -> h.city.copy(autonomy = h.city.autonomy!!.copy(plan = CivSettlementPlan(1, 1, 48,
                    emptyList(), emptyMap(), emptyList(), listOf(CivBox(CivPosition(45, 64, 0), CivPosition(45, 66, 2))),
                    listOf(0))))
                "warehouse-capture" -> h.city.copy(buildings = h.city.buildings.map {
                    if (it.type == BuildingType.WAREHOUSE) it.copy(warehouseCaptureId = UUID(0, 94)) else it })
                else -> h.city.copy(roadRouteOverrides = mapOf(forester.id.toString() to CivRoadRouteOverride(
                    h.plans[1].terrain!!.route, h.plans[1].terrain!!.route.mapIndexed { index, p ->
                        if (index == 2) p.copy(y = 64) else p })))
            }
            if (change == "warehouse-capture") shouldThrow<CompletionException> { h.load() }
            else {
                val access = h.load()
                h.reads shouldBe 10
                if (change == "override") access.routes.getValue(forester.id)[2].y shouldBe 64
                if (change == "adopted-plan") requireNotNull(CivsSettlementPlans.current(h.city))
            }
        } }
        }
    }

    test("capture save delete and reload attempts invalidate access even after failed acknowledgements") {
        for (attempt in listOf("save", "delete", "reload")) {
        accessCacheFixture { h -> h.countReads {
            h.load()
            val replacement = h.flattenedForesterPlan()
            when (attempt) {
                "save" -> {
                    every { anyConstructed<AtomicFileStore<Any>>().write(any()) } answers {
                        callOriginal()
                        throw IOException("lost acknowledgement after committed write")
                    }
                    shouldThrow<CompletionException> { h.store.savePlan(replacement).join() }
                }
                "delete" -> {
                    every { anyConstructed<AtomicFileStore<Any>>().deleteIfExists() } answers {
                        callOriginal()
                        throw IOException("lost acknowledgement after committed delete")
                    }
                    shouldThrow<CompletionException> { h.store.deletePlan(replacement.id).join() }
                }
                else -> {
                    h.write(replacement)
                    Files.createDirectories(h.root.resolve("cities"))
                    Files.writeString(h.root.resolve("cities/${h.city.id}.json"), "{")
                    shouldThrow<CompletionException> { h.store.load().join() }
                }
            }
            val access = h.load()
            if (attempt == "delete") access.routes.containsKey(replacement.building.id) shouldBe false
            else access.routes.getValue(replacement.building.id)[2].y shouldBe 64
        } }
        }
        val io = AccessProjectionExecutor()
        accessCacheFixture(storeExecutor = io) { h ->
            val initial = h.owner.loadAutonomousAccess(h.city)
            repeat(5) { io.runNext() }
            h.await(initial)
            val saving = h.store.savePlan(h.flattenedForesterPlan())
            val duringSave = h.owner.loadAutonomousAccess(h.city)
            repeat(5) { io.runLast() }
            h.await(duringSave).routes.getValue(h.city.buildings[1].id)[2].y shouldBe 65
            io.runNext()
            saving.join()
            val afterSave = h.owner.loadAutonomousAccess(h.city)
            repeat(5) { io.runNext() }
            h.await(afterSave).routes.getValue(h.city.buildings[1].id)[2].y shouldBe 64
        }
    }

    test("late retained-plan completions cannot republish after lifecycle or input changes") {
        for (change in listOf("clear", "remove", "capture", "adopted-plan")) {
        val queued = AccessProjectionExecutor()
        accessCacheFixture(queued) { h -> h.countReads {
            val pending = h.owner.loadAutonomousAccess(h.city)
            h.reads shouldBe 5
            when (change) {
                "clear" -> h.owner.clear()
                "remove" -> h.owner.remove(h.city.id)
                "capture" -> h.store.savePlan(h.flattenedForesterPlan()).join()
                else -> h.city = h.city.copy(autonomy = h.city.autonomy!!.copy(plan = CivSettlementPlan(1, 1, 48,
                    emptyList(), emptyMap(), emptyList(), listOf(CivBox(CivPosition(45, 64, 0), CivPosition(45, 66, 2))),
                    listOf(0))))
            }
            queued.runNext()
            h.await(pending)
            if (change in listOf("clear", "remove", "adopted-plan")) h.owner.forestryRoute(h.city.id) shouldBe emptySet()
            val before = h.reads
            val next = h.owner.loadAutonomousAccess(h.city)
            queued.runNext()
            val access = h.await(next)
            (h.reads > before) shouldBe true
            if (change == "capture") access.routes.getValue(h.city.buildings[1].id)[2].y shouldBe 64
        } }
        }
    }
})

private fun accessCacheFixture(executor: Executor = Executor(Runnable::run),
    storeExecutor: Executor = Executor(Runnable::run), block: (AccessCacheFixture) -> Unit) {
    MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
        AccessCacheFixture(paper, executor, storeExecutor).use(block)
    }
}

private class AccessCacheFixture(private val paper: MockBukkitTestRuntime, executor: Executor, storeExecutor: Executor) : AutoCloseable {
    val root = Files.createTempDirectory("arc-civs-access-cache-")
    private val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("AccessProjectionCache")))
    val store = CivsStore(root, storeExecutor)
    var reads = 0
    var city: Settlement
    val plans: List<CivPlacementPlan>
    val maintenance = mockk<CivsRoadMaintenance>(relaxed = true)
    val owner: CivsCityPlans

    init {
        val types = listOf(BuildingType.TOWN_HALL, BuildingType.FORESTER, BuildingType.QUARRY, BuildingType.FARM, BuildingType.WAREHOUSE)
        val buildings = types.mapIndexed { index, type ->
            val x = index * 10
            CivBuilding(UUID(0, index + 1L), type, 1, CivPosition(x, 64, 2), 0, "fixture/${type.name.lowercase()}1",
                CivBox(CivPosition(x - 2, 64, 1), CivPosition(x + 2, 68, 4)), CivPosition(x, 64, 0), BuildingPhase.ACTIVE)
        }
        city = Settlement(UUID(0, 81), UUID(0, 82), "Cache", UUID(0, 83), CivPosition(0, 64, 0),
            96, "fixture", Stock(), buildings, emptyList(), autonomy = CivAutonomy())
        plans = buildings.mapIndexed { index, building ->
            val route = (0..index * 10).map { x -> CivPosition(x, if (index == 1 && x == 2) 65 else 64, 0) }
            CivPlacementPlan(building.id, city.id, city.worldId, building.copy(phase = BuildingPhase.CONSTRUCTING),
                listOf(CivPlanBlock(building.anchor, "minecraft:air", "minecraft:stone")), terrain = CivTerraformPlan(
                    CivBox(CivPosition(-2, 63, -2), CivPosition(42, 66, 4)), emptyList(), route, 63, 0, 0))
        }
        plans.forEach(::write)
        val authority = mockk<CivsService>()
        every { authority.tracked<Any>() } answers { CompletableFuture<Any>() }
        val records = CivsCityRecords({ mapOf(city.id to city) }, emptySet(), emptySet(), { false }, { false }, { null }, { 0L }, { 0 })
        owner = CivsCityPlans(authority, records, tasks, store, executor, maintenance)
    }

    fun flattenedForesterPlan() = plans[1].copy(terrain = plans[1].terrain!!.copy(
        route = plans[1].terrain!!.route.map { it.copy(y = 64) }))

    fun write(plan: CivPlacementPlan) {
        val file = root.resolve("plans/${plan.id}.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, Gson().toJson(mapOf("schemaVersion" to CivsStore.SCHEMA_VERSION, "plan" to plan)))
    }

    fun countReads(block: () -> Unit) = mockkConstructor(AtomicFileStore::class) {
        every { anyConstructed<AtomicFileStore<Any>>().loadOrNull() } answers { reads++; callOriginal() }
        block()
    }

    fun load() = await(owner.loadAutonomousAccess(city))
    fun <T> await(future: CompletableFuture<T>): T {
        repeat(10) { if (future.isDone) return future.join(); paper.performTicks(1) }
        error("Retained access did not complete on its owner")
    }

    override fun close() { tasks.close(); store.close(); root.toFile().deleteRecursively() }
}

private class AccessProjectionExecutor : Executor {
    private val pending = ArrayDeque<Runnable>()
    override fun execute(command: Runnable) { pending.addLast(command) }
    fun runNext() = pending.removeFirst().run()
    fun runLast() = pending.removeLast().run()
}
