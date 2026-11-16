package ru.arc.civs

import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Bukkit
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.api.*
import ru.arc.paper.chunk.PaperChunkKey
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

class CivsAuthoredVegetationRepairTest : FunSpec({
    test("current source projection uses Core rotation and source hash and rejects changed geometry") {
        val source = mockk<ArcSchematicLibrary>(relaxed = true)
        val captured = mockk<ArcSchematicLibrary>(relaxed = true)
        val service = mockk<ArcSchematicService>()
        every { service.open("arccivs-library", any()) } returns source
        every { service.open("arccivs-construction", any()) } returns captured
        val original = mockk<ArcSchematicSnapshot> {
            every { volume } returns 8L
            every { fileSha256 } returns "a".repeat(64)
        }
        val transformed = mockk<ArcSchematicSnapshot> {
            every { minimum } returns ArcSchematicPoint(-1, 0, 0)
            every { maximum } returns ArcSchematicPoint(0, 1, 1)
            every { blocks() } returns listOf(
                ArcSchematicBlock(ArcSchematicPoint(0, 1, 0), "minecraft:birch_log[axis=z]", false, false),
                ArcSchematicBlock(ArcSchematicPoint(-1, 1, 0), LEAF, false, false),
                ArcSchematicBlock(ArcSchematicPoint(0, 0, 0), "minecraft:barrel[facing=up,open=false]", false, false))
        }
        val building = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 3, CivPosition(20, 64, 20), 90,
            "fixture/current-hall-level3", CivBox(CivPosition(19, 64, 20), CivPosition(20, 65, 21)),
            CivPosition(21, 65, 20), BuildingPhase.ACTIVE)
        every { source.load(building.schematicId, ArcSchematicContentPolicy.IGNORE_ENTITIES_AND_BIOMES) } returns
            CompletableFuture.completedFuture(original)
        every { source.transform(original, ArcSchematicTransform(1, false)) } returns CompletableFuture.completedFuture(transformed)
        CivsBlueprints(service, Path.of("absent-tree-test-source"), Path.of("absent-tree-test-captured"),
            Executor { it.run() }, 50_000).use { blueprints ->
            val projected = blueprints.authoredVegetationRepair(building, building.bounds).join()
            projected.sha256 shouldBe "a".repeat(64)
            projected.cells shouldBe listOf(CivPlanBlock(CivPosition(20, 65, 20), "minecraft:air", "minecraft:birch_log[axis=z]"),
                CivPlanBlock(CivPosition(19, 65, 20), "minecraft:air", LEAF))
            val drift = building.copy(bounds = building.bounds.copy(max = building.bounds.max.copy(y = 66)))
            CivsDiagnostics.rejection(shouldThrow<CompletionException> {
                blueprints.authoredVegetationRepair(drift, drift.bounds).join()
            })?.reason shouldBe "vegetation-source-geometry"
            every { transformed.blocks() } returns listOf(ArcSchematicBlock(ArcSchematicPoint(0, 1, 0),
                LEAF.replace("waterlogged=false", "waterlogged=true"), false, false))
            CivsDiagnostics.rejection(shouldThrow<CompletionException> {
                blueprints.authoredVegetationRepair(building, building.bounds).join()
            })?.reason shouldBe "vegetation-repair-waterlogged"
            every { transformed.blocks() } returns List(2049) {
                ArcSchematicBlock(ArcSchematicPoint(0, 1, 0), "minecraft:birch_log[axis=z]", false, false)
            }
            CivsDiagnostics.rejection(shouldThrow<CompletionException> {
                blueprints.authoredVegetationRepair(building, building.bounds).join()
            })?.reason shouldBe "vegetation-repair-size"
            verify(exactly = 4) { source.load(building.schematicId, ArcSchematicContentPolicy.IGNORE_ENTITIES_AND_BIOMES) }
            verify(exactly = 0) { captured.load(any(), any()); captured.loadVerified(any(), any(), any()) }
        }
    }

    test("only AIR is restored after source and journal fences while all nonAIR guards remain unchanged") {
        VegetationRepairFixture().use { f ->
            val before = f.city
            f.journalWrite = CompletableFuture()
            f.sourceFence = CompletableFuture()
            val result = f.repair.request(before.id, f.hall.id, f.hall.bounds)
            f.ticks()
            result.isDone shouldBe false
            f.targets.forEach { f.state(it.position) shouldBe "minecraft:air" }
            val tx = f.written.single()
            tx.changes shouldBe emptyList()
            tx.worldChanges shouldBe f.targets
            f.journalWrite!!.complete(tx)
            f.ticks()
            f.targets.forEach { f.state(it.position) shouldBe "minecraft:air" }
            f.sourceFence.complete(Unit)
            f.await(result)["changed_cells"] shouldBe f.targets.size
            f.targets.forEach { f.state(it.position) shouldBe it.after }
            f.guards.forEach { (p, value) -> f.state(p) shouldBe value }
            f.city.copy(revision = before.revision) shouldBe before
            f.await(f.repair.request(f.city.id, f.hall.id, f.hall.bounds))["state"] shouldBe "current"
            f.written.size shouldBe 1
        }
    }

    test("619 source cells restore570 AIR in five bounded one-pass batches with no refill after later decay") {
        VegetationRepairFixture(large = true).use { f ->
            val before = f.city
            var removed: CivPosition? = null
            f.afterPublish = {
                if (removed == null && f.written.size == 2) {
                    removed = f.written.last().worldChanges!!.first { it.after.contains("_leaves[") }.position
                    f.put(removed!!, "minecraft:air")
                }
            }
            val result = f.await(f.repair.request(before.id, f.hall.id, f.hall.bounds))
            result["checked_cells"] shouldBe 619
            result["changed_cells"] shouldBe 570
            result["preserved_cells"] shouldBe 49
            f.written.map { it.worldChanges!!.size } shouldBe listOf(128, 128, 128, 128, 58)
            val written = f.written.flatMap { it.worldChanges!! }
            written.map { it.position }.distinct().size shouldBe 570
            written.take(135).all { !it.after.contains("_leaves[") } shouldBe true
            f.state(requireNotNull(removed)) shouldBe "minecraft:air"
            f.guards.forEach { (p, value) -> f.state(p) shouldBe value }
            f.city.copy(revision = before.revision) shouldBe before
            f.city.revision shouldBe before.revision + 5
        }
    }

    test("partial restart before or after city publication tolerates only natural leaf distance drift") {
        for (published in listOf(false, true)) VegetationRepairFixture().use { f ->
            val before = f.city
            val tx = f.transaction()
            f.recovered = listOf(tx)
            val leaf = f.targets.single { it.after == LEAF }
            f.put(leaf.position, LEAF.replace("distance=1", "distance=6"))
            f.put(f.targets.first().position, f.targets.first().after)
            if (published) { f.city = tx.after; f.storage.updateCity(f.city) }
            f.await(f.journal.bootstrap()); f.journal.recover(); f.ticks()
            f.city shouldBe tx.after
            f.city.copy(revision = before.revision) shouldBe before
            f.state(leaf.position) shouldBe LEAF.replace("distance=1", "distance=6")
            f.targets.filterNot { it == leaf }.forEach { f.state(it.position) shouldBe it.after }
            f.journal.pendingCount shouldBe 0
            f.verifications shouldBe 1
        }
        VegetationRepairFixture().use { f ->
            val tx = f.transaction()
            f.recovered = listOf(tx)
            val leaf = f.targets.single { it.after == LEAF }
            f.put(leaf.position, LEAF.replace("persistent=false", "persistent=true"))
            f.await(f.journal.bootstrap()); f.journal.recover(); f.ticks()
            f.journal.blockedCount shouldBe 1
            f.state(f.targets.first().position) shouldBe "minecraft:air"
        }
    }

    test("queued resident progress between repair batches is preserved through fresh city admission") {
        VegetationRepairFixture(large = true).use { f ->
            val before = f.city
            var residentAfter: CivWorker? = null
            var competed = false
            f.afterRetired = {
                if (!competed) {
                    competed = true
                    // The journal has released its city; a waiting ordinary pickup owns it before the
                    // repair's whenCompleteSync callback runs, exactly as in the live .312 history.
                    f.beginOrdinaryMutation { current ->
                        val worker = CivsEquipment.collectSupplies(current.workers.single(), mapOf("BREAD" to 4L))
                            .copy(completedCycles = current.workers.single().completedCycles + 1)
                        residentAfter = worker
                        current.copy(workers = listOf(worker))
                    }
                }
            }
            f.await(f.repair.request(before.id, f.hall.id, f.hall.bounds))["changed_cells"] shouldBe 570
            f.queuedAdmissions shouldBe 1
            f.written.map { it.worldChanges!!.size } shouldBe listOf(128, 128, 128, 128, 58)
            f.written.map { it.beforeRevision } shouldBe (listOf(0L, 2L, 3L, 4L, 5L).map { it + before.revision })
            f.written.forEach { tx -> tx.after.copy(revision = tx.beforeRevision) shouldBe f.beforeReceipts.getValue(tx.id) }
            f.city.workers shouldBe listOf(requireNotNull(residentAfter))
            f.city.revision shouldBe before.revision + 6
            f.targets.forEach { f.state(it.position) shouldBe it.after }
            f.guards.forEach { (p, value) -> f.state(p) shouldBe value }
            f.journal.pendingCount shouldBe 0
            f.journal.blockedCount shouldBe 0
        }
    }

    test("a changed target building while continuation waits rejects remaining cells before another receipt") {
        VegetationRepairFixture(large = true).use { f ->
            var competed = false
            f.afterRetired = {
                if (!competed) {
                    competed = true
                    f.beginOrdinaryMutation { current -> current.copy(buildings = listOf(f.hall.copy(rotation = 90))) }
                }
            }
            CivsDiagnostics.rejection(shouldThrow<CompletionException> {
                f.await(f.repair.request(f.city.id, f.hall.id, f.hall.bounds))
            })?.reason shouldBe "vegetation-stale-building"
            f.queuedAdmissions shouldBe 1
            f.written.size shouldBe 1
            f.targets.take(128).forEach { f.state(it.position) shouldBe it.after }
            f.targets.drop(128).forEach { f.state(it.position) shouldBe "minecraft:air" }
            f.journal.pendingCount shouldBe 0
            f.journal.blockedCount shouldBe 0
        }
    }

    test("source hash, source cells, city geometry and revision drift fail closed during recovery") {
        for (drift in listOf("hash", "source-cell", "geometry", "revision")) VegetationRepairFixture().use { f ->
            val tx = f.transaction()
            f.recovered = listOf(tx)
            when (drift) {
                "hash" -> f.source = f.source.copy(sha256 = "b".repeat(64))
                "source-cell" -> f.source = f.source.copy(cells = f.source.cells.mapIndexed { i, c ->
                    if (i == 0) c.copy(after = "minecraft:spruce_log[axis=y]") else c })
                "geometry" -> f.city = f.city.copy(buildings = listOf(f.hall.copy(rotation = 90)))
                "revision" -> f.city = f.city.copy(revision = tx.after.revision + 1)
            }
            f.await(f.journal.bootstrap()); f.journal.recover(); f.ticks()
            f.journal.blockedCount shouldBe 1
            f.targets.forEach { f.state(it.position) shouldBe "minecraft:air" }
        }
    }

    test("occupied and unloaded target chunks retain accepted work and retry without placing through a resident") {
        VegetationRepairFixture().use { f ->
            val tx = f.transaction()
            f.recovered = listOf(tx)
            f.occupied = true
            f.await(f.journal.bootstrap()); f.journal.recover(); f.ticks()
            f.journal.pendingCount shouldBe 1
            f.journal.blockedCount shouldBe 0
            f.targets.forEach { f.state(it.position) shouldBe "minecraft:air" }
            f.occupied = false
            f.world.unloadChunk(1, 0) shouldBe true
            f.journal.recover(); f.ticks()
            f.journal.pendingCount shouldBe 1
            f.journal.blockedCount shouldBe 0
            f.journal.pendingNativeChunks().single() shouldBe setOf(PaperChunkKey(f.world.uid, 0, 0), PaperChunkKey(f.world.uid, 1, 0))
            f.world.loadChunk(1, 0)
            f.journal.recover(); f.ticks()
            f.journal.pendingCount shouldBe 0
            f.targets.forEach { f.state(it.position) shouldBe it.after }
        }
    }

    test("typed receipt forbids nonAIR, foreign states, oversized batches and city credit and keeps exact audit after retirement") {
        VegetationRepairFixture().use { f ->
            val tx = f.transaction()
            fun changed(cells: List<CivPlanBlock>) = tx.copy(worldChanges = cells,
                authoredVegetation = tx.authoredVegetation!!.copy(cells = cells))
            shouldThrow<IllegalArgumentException> { changed(f.targets.map { it.copy(before = "minecraft:dirt") }).validated() }
            shouldThrow<IllegalArgumentException> { changed(f.targets.map { it.copy(after = "minecraft:diamond_block") }).validated() }
            shouldThrow<CivsRejection> { changed(f.targets.map {
                it.copy(after = it.after.replace("waterlogged=false", "waterlogged=true"))
            }).validated() }.reason shouldBe "vegetation-repair-waterlogged"
            shouldThrow<IllegalArgumentException> { changed(List(129) { f.targets.first().copy(position = CivPosition(it, 75, 5)) }).validated() }
            val credited = tx.copy(after = tx.after.copy(stock = tx.after.stock.copy(wood = tx.after.stock.wood + 1)))
            f.storage.reserve(f.city, credited.id)
            shouldThrow<IllegalArgumentException> { f.storage.seal(credited) }
            f.storage.unlock(credited.id)
            val root = Files.createTempDirectory("authored-vegetation-receipt")
            try {
                CivsStore(root, Executor { it.run() }).use { store ->
                    store.save(f.city).join()
                    store.saveInventoryTransaction(tx).join() shouldBe tx
                    store.loadInventoryTransactions().join() shouldBe listOf(tx)
                    store.completeInventoryTransaction(tx).join() shouldBe tx.after
                    store.deleteInventoryTransaction(tx.id).join() shouldBe true
                    store.deleteInventoryTransaction(tx.id).join() shouldBe false
                    val audit = JsonParser.parseString(Files.readString(root.resolve("authored-vegetation-repairs/${tx.id}.json"))).asJsonObject
                    audit["city"].asString shouldBe tx.cityId.toString()
                    audit.has("after") shouldBe false
                    audit.getAsJsonObject("receipt").getAsJsonArray("cells").size() shouldBe f.targets.size
                    store.loadInventoryTransactions().join() shouldBe emptyList()
                }
            } finally { root.toFile().deleteRecursively() }
        }
    }
})

private const val LEAF = "minecraft:birch_leaves[distance=1,persistent=false,waterlogged=false]"

private class VegetationRepairFixture(large: Boolean = false) : AutoCloseable {
    val paper = MockBukkitTestRuntime.open(CivsWorksiteServerFixture())
    val world = CivsWorksiteWorldFixture("authored-tree-repair").also(paper.server::addWorld)
    private val plugin = paper.createSimplePlugin("AuthoredTreeRepair")
    private val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 3, CivPosition(20, 64, 10), 0,
        "fixture/current-authored-hall3", CivBox(CivPosition(12, 64, 0), CivPosition(31, 90, 15)),
        CivPosition(20, 65, 16), BuildingPhase.ACTIVE, control = CivPosition(20, 65, 10))
    private val cities = linkedMapOf<UUID, Settlement>()
    var city: Settlement
        get() = cities.values.single()
        set(value) { cities[value.id] = value }
    private val busy = hashSetOf<UUID>()
    private val unhealthy = hashSetOf<UUID>()
    private val store = mockk<CivsStore>().also { it.stubCheckpointAcknowledgements() }
    val storage = CivsStorage(plugin) { emptyArray() }
    private val access = CivsWorld(plugin, tasks, nanoTime = { 0L }) { cities.values }
    val journal: CivsInventoryJournal
    val repair: CivsAuthoredVegetationRepair
    var source: CivAuthoredVegetationSource
    val targets: List<CivPlanBlock>
    val guards: Map<CivPosition, String>
    var journalWrite: CompletableFuture<CivInventoryTransaction>? = null
    var sourceFence = CompletableFuture.completedFuture(Unit)
    val written = mutableListOf<CivInventoryTransaction>()
    val beforeReceipts = mutableMapOf<UUID, Settlement>()
    private val waiting = ArrayDeque<() -> Unit>()
    var queuedAdmissions = 0
    var recovered = emptyList<CivInventoryTransaction>()
    var occupied = false
    var verifications = 0
    var afterPublish: () -> Unit = {}
    var afterRetired: () -> Unit = {}

    init {
      try {
        val worker = CivWorker(UUID.randomUUID(), "Tree guard", null, completedCycles = 7)
        city = CivsRules.validate(spatialCity(world.uid, emptyList(), worker).copy(center = hall.anchor,
            buildings = listOf(hall), inventory = CivInventory(CivStorageMode.PHYSICAL)))
        for (x in -1..3) for (z in -1..2) world.loadChunk(x, z)
        val cells = if (large) List(619) { i ->
            val point = CivPosition(13 + i % 17, 75 + i / (17 * 14), i / 17 % 14)
            CivPlanBlock(point, "minecraft:air", if (i < 135) "minecraft:birch_log[axis=y]"
                else LEAF.replace("distance=1", "distance=${1 + i % 7}").replace("persistent=false", "persistent=${i >= 570}"))
        } else listOf(
            CivPlanBlock(CivPosition(15, 75, 5), "minecraft:air", "minecraft:birch_log[axis=y]"),
            CivPlanBlock(CivPosition(16, 75, 5), "minecraft:air", LEAF),
            CivPlanBlock(CivPosition(17, 75, 5), "minecraft:air", LEAF.replace("distance=1", "distance=2")),
            CivPlanBlock(CivPosition(18, 75, 5), "minecraft:air", LEAF.replace("persistent=false", "persistent=true")),
            CivPlanBlock(CivPosition(19, 75, 5), "minecraft:air", "minecraft:birch_log[axis=y]"))
        source = CivAuthoredVegetationSource("a".repeat(64), cells)
        targets = CivAuthoredVegetationReceipt.ordered(cells.take(if (large) 570 else 3))
        guards = if (large) cells.drop(570).associate { it.position to it.after } else mapOf(
            cells[3].position to cells[3].after.replace("distance=1", "distance=7"), cells[4].position to "minecraft:stone")
        cells.forEach { put(it.position, "minecraft:air") }
        guards.forEach(::put)
        storage.replaceCities(listOf(city))
        every { store.saveInventoryTransaction(any()) } answers {
            val tx = firstArg<CivInventoryTransaction>()
            written += tx
            beforeReceipts[tx.id] = city
            journalWrite ?: CompletableFuture.completedFuture(firstArg())
        }
        every { store.save(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
        every { store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
        every { store.loadInventoryTransactions() } answers { CompletableFuture.completedFuture(recovered) }
        every { store.loadConsumedItems() } returns CompletableFuture.completedFuture(CivConsumedItemIndex.EMPTY)
        journal = CivsInventoryJournal(plugin, tasks, store, storage, { cities }, busy, unhealthy, { false },
            { saved -> city = saved; storage.updateCity(saved); afterPublish(); CompletableFuture.completedFuture(Unit) },
            { _, _ -> error("Authored restoration cannot create a second city mutation") }, {}, {}, { _, _, _ -> null }, {},
            { _, _ -> afterRetired(); city }, {}, { 0L }, verifyAuthoredVegetationSource = { tx ->
                verifications++
                sourceFence.thenCompose { repair.verifySource(tx) }
            })
        val authority = mockk<CivsService>(relaxed = true)
        every { authority.ready() } returns true
        every { authority.tracked<Map<String, Any?>>() } answers { CompletableFuture() }
        every { authority.reserveCity(any()) } answers { busy += firstArg<UUID>(); Unit }
        every { authority.releaseCity(any()) } answers { busy -= firstArg<UUID>(); Unit }
        every { authority.afterCityAvailable<Settlement>(any(), any()) } answers {
            firstArg<UUID>() shouldBe city.id
            val action = secondArg<() -> CompletableFuture<Settlement>>()
            if (city.id !in busy && waiting.isEmpty()) action()
            else {
                queuedAdmissions++
                val result = CompletableFuture<Settlement>()
                waiting.addLast {
                    runCatching { action() }.getOrElse { CompletableFuture.failedFuture(it) }.whenComplete { value, failure ->
                        if (failure == null) result.complete(value) else result.completeExceptionally(failure)
                    }
                }
                result
            }
        }
        val records = CivsCityRecords({ cities }, busy, unhealthy, { false }, { false }, { null }, { 0 }, { 0 })
        repair = CivsAuthoredVegetationRepair(authority, records, tasks, { building, _ ->
            building.schematicId shouldBe hall.schematicId
            CompletableFuture.completedFuture(source)
        }, access, storage, journal, { false }, { _, _ -> occupied })
        storage.validateAuthoredVegetationNative = repair::validateNative
        tasks.runTimer(1, 1) { if (city.id !in busy && waiting.isNotEmpty()) waiting.removeFirst().invoke() }
      } catch (failure: Throwable) {
        access.close(); storage.close(); tasks.close(); paper.close()
        throw failure
      }
    }

    fun transaction(): CivInventoryTransaction = CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision,
        city.copy(revision = city.revision + 1), emptyList(), worldChanges = targets,
        authoredVegetation = CivAuthoredVegetationReceipt(hall, hall.bounds, source.sha256, targets)).validated()
    fun beginOrdinaryMutation(transform: (Settlement) -> Settlement) {
        check(city.id !in busy)
        busy += city.id
        tasks.runLater(3) {
            city = CivsRules.validate(transform(city).copy(revision = city.revision + 1))
            storage.updateCity(city)
            busy -= city.id
        }
    }
    fun put(p: CivPosition, state: String) = world.getBlockAt(p.x, p.y, p.z).setBlockData(Bukkit.createBlockData(state), false)
    fun state(p: CivPosition) = world.getBlockAt(p.x, p.y, p.z).blockData.asString
    fun ticks() { paper.performTicks(30) }
    fun <T> await(future: CompletableFuture<T>): T {
        var elapsed = 0
        var atThirty = ""
        while (!future.isDone && elapsed < 200) {
            paper.performTicks(1)
            elapsed++
            if (elapsed == 30) atThirty = "batches=${written.map { it.worldChanges!!.size }}, revision=${city.revision}, pending=${journal.pendingCount}, blocked=${journal.blockedCount}, busy=${city.id in busy}"
        }
        if (elapsed >= 30) println("Authored vegetation wait: ticks=$elapsed, at30=[$atThirty], batches=${written.map { it.worldChanges!!.size }}, pending=${journal.pendingCount}, blocked=${journal.blockedCount}")
        withClue("ticks=$elapsed, at30=[$atThirty], revision=${city.revision}, pending=${journal.pendingCount}, blocked=${journal.blockedCount}") {
            future.isDone shouldBe true
        }
        return future.join()
    }
    override fun close() { access.close(); storage.close(); tasks.close(); paper.close() }
}
