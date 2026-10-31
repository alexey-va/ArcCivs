package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.bukkit.Chunk
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot
import ru.arc.paper.chunk.PaperChunkKey
import ru.arc.paper.chunk.PaperChunkTicketAcquireResult
import ru.arc.paper.chunk.PaperChunkTicketAddResult
import ru.arc.paper.chunk.PaperChunkTicketBackend
import ru.arc.paper.chunk.PaperChunkTicketRegistry
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.logging.Logger

class CivsVillageSiteScoringTest : FunSpec({
    test("candidate IDs and bounded terrain centres repeat across negative world coordinates") {
        val reference = villageReference(UUID.randomUUID()).copy(center = CivPosition(-1883, 67, -2760))
        val candidates = CivsVillageSiteScoring.candidates(reference, "Auto-")
        candidates shouldBe CivsVillageSiteScoring.candidates(reference, "Auto-")
        candidates.distinct().size shouldBe CivsVillageSiteScoring.MAXIMUM_CANDIDATES
        candidates.all { point ->
            val x = point.x.toLong() - reference.center.x
            val z = point.z.toLong() - reference.center.z
            x * x + z * z in (700L * 700)..(4096L * 4096)
        } shouldBe true
        val ids = (1..5).map { CivsVillageSiteScoring.id(reference.id, "Auto-", it) }
        ids.distinct().size shouldBe 5
        ids shouldBe (1..5).map { CivsVillageSiteScoring.id(reference.id, "Auto-", it) }
        (ids.first() != CivsVillageSiteScoring.id(reference.id, "Other-", 1)) shouldBe true
    }

    test("ranking prefers connected flat dry land with trees and rejects water cliffs and missing chunks") {
        val flat = villageSurface(CivPosition(0, 63, 0), 12)
        val eligible = CivsVillageSiteScoring.score(flat)
        eligible.rejection(full = true) shouldBe null
        eligible.connectedColumns shouldBe flat.columns.size
        val wet = flat.copy(columns = flat.columns.mapValues { (key, column) ->
            column.copy(natural = key.toInt() > 0)
        })
        CivsVillageSiteScoring.score(wet).rejection(full = true) shouldBe "village-wet-or-obstructed"
        val cliff = flat.copy(columns = flat.columns.mapValues { (key, column) ->
            column.copy(groundY = if ((key shr 32).toInt() > 0) 90 else 63)
        })
        (eligible.score > CivsVillageSiteScoring.score(cliff).score) shouldBe true
        (CivsVillageSiteScoring.score(cliff).rejection(full = true) != null) shouldBe true
        val unavailable = flat.copy(columns = flat.columns.mapValues { (_, column) -> column.copy(loaded = false) })
        CivsVillageSiteScoring.score(unavailable).dryColumns shouldBe 0
        CivsVillageSiteScoring.score(unavailable).rejection(full = true) shouldBe "village-wet-or-obstructed"
        val treeless = flat.copy(columns = flat.columns.mapValues { (_, column) -> column.copy(topY = column.groundY) })
        CivsVillageSiteScoring.score(treeless).rejection(full = true) shouldBe "village-no-trees"
    }

    test("a narrow protected cut splits an otherwise dry village and missing profile plots are refused") {
        val flat = villageSurface(CivPosition(0, 63, 0), 12)
        val split = flat.copy(columns = flat.columns.mapValues { (key, column) ->
            column.copy(natural = (key shr 32).toInt() != 0)
        })
        CivsVillageSiteScoring.score(split).rejection(full = true) shouldBe "village-disconnected"
        val city = villageReference(UUID.randomUUID())
        val full = villageLayout(city)
        CivsVillageSiteScoring.completeProfile(full) shouldBe true
        CivsVillageSiteScoring.completeProfile(full.copy(plots = full.plots.filter {
            it.building.type != BuildingType.FORESTER
        })) shouldBe false
    }
})

class CivsVillageSitesTest : FunSpec({
    test("explicit preparation has two outstanding loads and cancellation admits no stale lease") {
        villageScenario { s ->
            s.holdLoads = true
            val result = s.subject.search(s.reference, 5, "Auto-")
            s.subject.search(s.reference, 5, "Auto-") shouldBe result
            s.tick(30)
            s.loads.size shouldBe 2
            s.subject.status()["pending_loads"] shouldBe 2
            (s.subject.status()["styles"] as Map<*, *>).values.toSet().size shouldBe 5
            s.subject.cancel("test-cancel")
            s.tick(3)
            result.join().reason shouldBe "test-cancel"
            result.join().created shouldBe emptyList()
            s.loads.forEach { (key, future) ->
                s.world.loadChunk(key.x, key.z)
                future.complete(mockk<Chunk>())
            }
            s.tick(20)
            s.loads.size shouldBe 2
            s.registry.activeLeaseCount shouldBe 0
            s.admissions shouldBe emptyList()
        }
    }

    test("a failed full growth proof creates no town and returns bounded rejection evidence") {
        villageScenario { s ->
            every { CivsSettlementPlans.prepare(any(), any(), any(), any(), any(), any(), any()) } throws
                CivsRejection("autonomy-no-site")
            val result = s.subject.search(s.reference, 1, "Auto-")
            s.until { result.isDone }
            result.join().complete shouldBe false
            result.join().rejected["autonomy-no-site"] shouldBe CivsVillageSiteScoring.MAXIMUM_SHORTLIST
            result.join().created shouldBe emptyList()
            s.admissions shouldBe emptyList()
            s.cities shouldBe listOf(s.reference)
            s.registry.activeLeaseCount shouldBe 0
        }
    }

    test("storage rejection stops founding and a retry retains the exact requested city identity") {
        villageScenario { s ->
            s.founding = { CompletableFuture.failedFuture(CivsRejection("storage-unavailable")) }
            val failed = s.subject.search(s.reference, 1, "Auto-")
            s.until { failed.isDone }
            failed.join().reason shouldBe "storage-unavailable"
            failed.join().created shouldBe emptyList()
            val first = s.admissions.single().city
            s.registry.activeLeaseCount shouldBe 0
            s.founding = { admission -> s.cities += admission.city; CompletableFuture.completedFuture(admission.city) }
            val retry = s.subject.search(s.reference, 1, "Auto-")
            s.until { retry.isDone }
            retry.join().complete shouldBe true
            retry.join().created.single().city shouldBe first.id
            s.cities.count { it.id == first.id } shouldBe 1
            s.cities.last().inventory!!.materials shouldBe mapOf("OAK_LOG" to 96L, "COBBLESTONE" to 96L, "BREAD" to 24L)
            s.cities.last().settings shouldBe CivSettlementSettings(true, true, false)
        }
    }

    test("cancellation during an entered commit waits for canonical publication and releases its leases once") {
        villageScenario { s ->
            val commit = CompletableFuture<Settlement>()
            s.founding = { commit }
            val result = s.subject.search(s.reference, 1, "Auto-")
            s.until { s.admissions.isNotEmpty() }
            val admission = s.admissions.single()
            val retained = s.registry.activeLeaseCount
            (retained > 0) shouldBe true
            admission.current() shouldBe true
            s.subject.cancel("operator-cancel")
            s.tick(10)
            admission.current() shouldBe false
            commit.isCancelled shouldBe false
            result.isDone shouldBe false
            s.registry.activeLeaseCount shouldBe retained
            s.cities += admission.city
            commit.complete(admission.city)
            s.until { result.isDone }
            result.join().created.single().city shouldBe admission.city.id
            result.join().reason shouldBe "operator-cancel"
            s.registry.activeLeaseCount shouldBe 0
            // A coarse sample chunk can be acquired again for the final full-area proof.
            // Every native acquisition must be balanced, including those separate lease lifetimes.
            s.removed.groupingBy { it }.eachCount() shouldBe s.added.groupingBy { it }.eachCount()
            val repeated = s.subject.search(s.reference, 1, "Auto-")
            s.until { repeated.isDone }
            repeated.join().created.single().existing shouldBe true
            s.admissions.size shouldBe 1
            s.cities.count { it.id == admission.city.id } shouldBe 1
        }
    }

    test("a colliding existing name is preserved before terrain or a founding grant is requested") {
        villageScenario { s ->
            val other = s.reference.copy(id = UUID.randomUUID(), name = "Auto-1", center = CivPosition(2000, 63, 0))
            s.cities += other
            val result = s.subject.search(s.reference, 1, "Auto-")
            s.until { result.isDone }
            result.join().reason shouldBe "city-name-conflict"
            s.loads shouldBe emptyList()
            s.admissions shouldBe emptyList()
            s.cities.last() shouldBe other
        }
    }
})

class CivsProspectiveVillageSurfaceTest : FunSpec({
    test("an unpublished prospective town shares the scan budget and never loads unavailable terrain") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val world = CivsWorksiteWorldFixture("village-prospective").also(paper.server::addWorld)
                world.loadChunk(0, 0)
                val scheduler = TestTaskScheduler()
                val tasks = LifecycleTaskScope(scheduler)
                val cities = mutableListOf<Settlement>()
                CivsWorld(paper.createSimplePlugin("VillageSurfaceTest"), tasks, nanoTime = { 0 }) { cities }.use { access ->
                    val city = villageReference(world.uid)
                    val result = access.prospectiveHeightField(city, city.buildings.single())
                    scheduler.tick()
                    result.isDone shouldBe false
                    scheduler.tick(40)
                    result.join().columns.size shouldBe 255 * 255
                    result.join().at(32, 0)!!.loaded shouldBe false
                    world.isChunkLoaded(2, 0) shouldBe false
                    cities shouldBe emptyList()
                }
                tasks.close()
            }
        }
    }
})

private fun villageSurface(center: CivPosition, radius: Int) = CivTerrainSurface(-64, 320, buildMap {
    for (x in -radius..radius) for (z in -radius..radius) put(civsTerrainKey(center.x + x, center.z + z),
        CivTerrainSurfaceColumn(63, true, topY = if ((x + z) % 11 == 0) 67 else 63))
})

private fun villageReference(world: UUID): Settlement {
    val hall = villageBlueprint("civs/v2/caledonia/town_hall/fixture/level1", UUID.randomUUID(), world,
        CivPosition(0, 63, 0), BuildingType.TOWN_HALL).building
    return CivsAutonomy.found(UUID.randomUUID(), UUID.randomUUID(), "Auto2", world, hall.control!!,
        128, "caledonia", hall, 1_000)
}

private fun villageBlueprint(source: String, id: UUID, world: UUID, anchor: CivPosition,
    type: BuildingType): CivBlueprint {
    val bounds = CivBox(anchor.copy(x = anchor.x - 1, z = anchor.z - 1),
        anchor.copy(x = anchor.x + 1, y = anchor.y + 2, z = anchor.z + 1))
    val control = anchor.copy(y = anchor.y + 1)
    val building = CivBuilding(id, type, 1, anchor, 0, source, bounds,
        anchor.copy(y = anchor.y + 1, z = anchor.z + 2), BuildingPhase.CONSTRUCTING, control)
    val cells = buildList {
        for (x in bounds.min.x..bounds.max.x) for (y in bounds.min.y..bounds.max.y) for (z in bounds.min.z..bounds.max.z) {
            val data = if (CivPosition(x, y, z) == control) "minecraft:structure_void" else "minecraft:air"
            add(ArcSchematicBlock(ArcSchematicPoint(x, y, z), data, data == "minecraft:air", false))
        }
    }
    val snapshot = mockk<ArcSchematicSnapshot>()
    every { snapshot.fileSha256 } returns "a".repeat(64)
    return CivBlueprint(building, world, snapshot, cells, cells, byteArrayOf(1))
}

private fun villageLayout(city: Settlement): CivSettlementPlan {
    var next = 0
    val plots = CivsAutonomy.completeProfile.map { type ->
        val hall = city.buildings.single()
        val building = if (type == BuildingType.TOWN_HALL) hall else {
            val anchor = city.center.copy(x = city.center.x - 60 + next % 10 * 12,
                y = 63, z = city.center.z - 48 + next / 10 * 12)
            next++
            villageBlueprint("civs/v2/${city.style}/${type.name.lowercase()}/fixture/level1", UUID.randomUUID(),
                city.worldId, anchor, type).building.copy(phase = BuildingPhase.SITE)
        }
        CivSettlementPlot(building, building.bounds)
    }
    return CivSettlementPlan(1, 0, 72, plots, emptyMap(), emptyList(),
        listOf(CivBox(city.center.copy(x = city.center.x + 72), city.center.copy(x = city.center.x + 72))), listOf(1))
}

private class VillageScenario(paper: MockBukkitTestRuntime) : AutoCloseable {
    private val loaded = hashSetOf<Pair<Int, Int>>()
    private val server = mockk<Server>()
    val plugin = mockk<Plugin>()
    val world = mockk<World>()
    private val worldId = UUID.randomUUID()
    init {
        every { plugin.server } returns server
        every { plugin.logger } returns Logger.getLogger("VillageSearchTest")
        every { server.getWorld(worldId) } returns world
        every { world.uid } returns worldId
        every { world.name } returns "arc_civs_test"
        every { world.isChunkLoaded(any(), any()) } answers { firstArg<Int>() to arg<Int>(1) in loaded }
        every { world.loadChunk(any<Int>(), any<Int>()) } answers { loaded += firstArg<Int>() to arg<Int>(1); Unit }
        // The surrounding Paper fixture supplies the owning-thread contract; no chunk geometry is needed by this loader test.
        paper.server.isPrimaryThread shouldBe true
    }
    val reference = villageReference(world.uid)
    val cities = mutableListOf(reference)
    val scheduler = TestTaskScheduler()
    val tasks = LifecycleTaskScope(scheduler)
    val blueprints = mockk<CivsBlueprints>()
    val access = mockk<CivsWorld>()
    val admissions = mutableListOf<CivVillageSiteAdmission>()
    val loads = mutableListOf<Pair<PaperChunkKey, CompletableFuture<Chunk?>>>()
    val added = mutableListOf<PaperChunkKey>()
    val removed = mutableListOf<PaperChunkKey>()
    var holdLoads = false
    var founding: (CivVillageSiteAdmission) -> CompletableFuture<Settlement> = { admission ->
        cities += admission.city
        CompletableFuture.completedFuture(admission.city)
    }
    val registry = PaperChunkTicketRegistry(object : PaperChunkTicketBackend {
        override fun add(key: PaperChunkKey): PaperChunkTicketAddResult { added += key; return PaperChunkTicketAddResult.ADDED }
        override fun remove(key: PaperChunkKey): Boolean { removed += key; return true }
    })
    val settings = CivsSettings(Path.of("/tmp/village-test"), setOf(world.name), 128, 500, 50_000, 4,
        120, 64, 96, 6144)
    val subject: CivsVillageSites

    init {
        val styles = listOf("nordic", "medievaloak", "birch", "original", "medievaldarkoak")
        every { blueprints.availableStyles(any()) } returns styles
        every { blueprints.autonomousSource(any(), any()) } answers {
            if (arg<BuildingType>(1) == BuildingType.PLAN_BOARD) null
            else "civs/v2/${firstArg<String>()}/${arg<BuildingType>(1).name.lowercase()}/fixture/level1"
        }
        every { blueprints.maximumLevel(any()) } returns 1
        every { blueprints.prepareEntry(any(), any(), any(), any(), any(), any()) } answers {
            val source = firstArg<String>()
            CompletableFuture.completedFuture(villageBlueprint(source, arg(1), arg(2), arg(3),
                BuildingType.valueOf(source.split('/')[3].uppercase())))
        }
        every { blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            CompletableFuture.completedFuture(villageBlueprint(CivsPlanBoardBlueprint.SCHEMATIC_ID,
                arg(3), arg(4), arg(5), arg(1)))
        }
        every { blueprints.fortification(any()) } returns CompletableFuture.completedFuture(null)
        every { access.prospectiveHeightField(any(), any(), any()) } answers {
            val city = firstArg<Settlement>()
            val samples = arg<List<CivPosition>?>(2)
            val surface = if (samples == null) villageSurface(city.center, 80) else CivTerrainSurface(-64, 320,
                samples.associate { civsTerrainKey(it.x, it.z) to CivTerrainSurfaceColumn(63, true) })
            CompletableFuture.completedFuture(surface)
        }
        every { access.captureManualFoundation(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            CompletableFuture.completedFuture(null)
        every { CivsSettlementPlans.prepare(any(), any(), any(), any(), any(), any(), any()) } answers {
            villageLayout(firstArg())
        }
        subject = CivsVillageSites(plugin, tasks, settings, blueprints, access, Executor(Runnable::run), { cities },
            { true }, { worldId, center ->
                if (cities.any { it.worldId == worldId && CivsService.areasOverlap(center, 192, it.center,
                        it.radius + (3 - it.level()) * 32) }) throw CivsRejection("city-overlap")
            }, { key -> (registry.acquire(key) as PaperChunkTicketAcquireResult.Acquired).lease }, { admission ->
                admissions += admission
                founding(admission)
            }, load = { target, x, z ->
                val future = CompletableFuture<Chunk?>()
                loads += PaperChunkKey(target.uid, x, z) to future
                if (!holdLoads) {
                    target.loadChunk(x, z)
                    future.complete(mockk<Chunk>())
                }
                future
            }, nanoTime = { 0 }, clock = { 1_000 })
    }

    fun tick(count: Int) { scheduler.tick(count.toLong()) }
    fun until(done: () -> Boolean) {
        repeat(12_000) { if (done()) return; scheduler.tick() }
        error("Village search did not finish: ${subject.status()}")
    }
    override fun close() { subject.close(); registry.close(); tasks.close() }
}

private fun villageScenario(action: (VillageScenario) -> Unit) = failOnUnsupportedMockBukkitOperation {
    mockkObject(CivsSettlementPlans)
    try { MockBukkitTestRuntime.open().use { paper -> VillageScenario(paper).use(action) } }
    finally { unmockkObject(CivsSettlementPlans) }
}
