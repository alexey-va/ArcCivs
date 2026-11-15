package ru.arc.civs

import com.google.gson.JsonArray
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.Material
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.chunk.PaperChunkKey
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class CivsMineOpeningTest : FunSpec({
    test("restart recovery loads the air-only side of a mouth across the x15 x16 chunk boundary") {
        for (published in listOf(false, true)) MineOpeningFixture().use { f ->
            fun shifted(p: CivPosition) = p.copy(x = p.x + 13)
            val mine = f.mine.copy(anchor = shifted(f.mine.anchor),
                bounds = CivBox(shifted(f.mine.bounds.min), shifted(f.mine.bounds.max)),
                entrance = shifted(f.mine.entrance), control = f.mine.control?.let(::shifted))
            f.city = CivsRules.validate(f.city.copy(buildings = f.city.buildings.map { if (it.id == mine.id) mine else it }))
            val before = f.city
            val positions = CivsMineOpening.positions(mine)
            positions.forEach { f.put(it, "minecraft:air") }
            val cut = positions.first { (it.x and 15) == 15 && it.y == 86 }
            f.put(cut, "minecraft:dirt")
            val cells = listOf(CivPlanBlock(cut, "minecraft:dirt", "minecraft:air"))
            val tx = CivInventoryTransaction(UUID.randomUUID(), before.id, before.revision,
                before.copy(revision = before.revision + 1), emptyList(), worldChanges = cells,
                mineOpening = CivMineOpeningReceipt(mine, cells)).validated()
            f.recovered = listOf(tx)
            if (published) f.city = tx.after
            f.storage.updateCity(f.city)
            val writtenChunk = PaperChunkKey(f.world.uid, cut.x shr 4, cut.z shr 4)
            val airChunk = PaperChunkKey(f.world.uid, (cut.x + 1) shr 4, cut.z shr 4)
            writtenChunk.x + 1 shouldBe airChunk.x
            f.world.unloadChunk(airChunk.x, airChunk.z) shouldBe true
            f.await(f.journal.bootstrap())
            f.journal.recover()
            f.ticks()
            f.quarantined shouldBe true
            f.journal.pendingCount shouldBe 1
            f.journal.blockedCount shouldBe 0
            f.state(cut) shouldBe "minecraft:dirt"
            f.world.isChunkLoaded(airChunk.x, airChunk.z) shouldBe false
            // These are the real journal-owned keys consumed by the service's quarantine loader.
            val demand = f.journal.pendingNativeChunks().single()
            demand shouldBe setOf(writtenChunk, airChunk)
            demand.forEach { f.world.loadChunk(it.x, it.z) }
            f.journal.recover()
            f.ticks()
            positions.forEach { f.state(it) shouldBe "minecraft:air" }
            f.city shouldBe tx.after
            f.city.copy(revision = before.revision) shouldBe before
            f.journal.pendingCount shouldBe 0
            f.journal.pendingNativeChunks() shouldBe emptyList()
            f.quarantined shouldBe false
        }
    }

    test("the tallest catalog mine clears all 144 cells with the typed receipt while ordinary receipts stay bounded at 128") {
        MineOpeningFixture(tall = true).use { f ->
            val before = f.city
            f.positions.size shouldBe 144
            val tx = f.transaction()
            shouldThrow<IllegalArgumentException> { tx.copy(mineOpening = null).validated() }
            f.await(f.maintenance.request(f.city.id, f.mine.id))["changed_cells"] shouldBe 144
            f.positions.forEach { f.state(it) shouldBe "minecraft:air" }
            f.surroundings.forEach { (point, data) -> f.state(point) shouldBe data }
            f.city.copy(revision = before.revision) shouldBe before
        }
    }

    test("Auto2 dirt above the completed V7 mouth is cleared only after the exact journal is durable") {
        MineOpeningFixture().use { f ->
            val before = f.city
            val saved = before.minePlans!!.getValue(f.mine.id.toString())
            f.geometry.sectionCount("0/shaft/opening", saved.nextStep, saved.sectionSteps.orEmpty()) shouldBe 27L
            f.positions.size shouldBe 81
            f.positions.map { it.y }.toSet() shouldBe (83..91).toSet()
            f.cap.all { it.y == 86 && it !in f.oldMouth } shouldBe true
            val fence = CompletableFuture<CivInventoryTransaction>()
            f.journalWrite = fence
            val repairing = f.maintenance.request(f.city.id, f.mine.id)
            f.ticks()
            repairing.isDone shouldBe false
            f.cap.forEach { f.state(it) shouldBe "minecraft:dirt" }
            f.city shouldBe before
            val tx = requireNotNull(f.written)
            tx.mineOpening!!.cells.map { it.position }.toSet() shouldBe f.cap.toSet()
            fence.complete(tx)
            f.await(repairing)["changed_cells"] shouldBe 9
            f.positions.forEach { f.state(it) shouldBe "minecraft:air" }
            f.city.copy(revision = before.revision) shouldBe before
            f.surroundings.forEach { (point, data) -> f.state(point) shouldBe data }
            f.await(f.maintenance.request(f.city.id, f.mine.id))["state"] shouldBe "current"
            f.city.revision shouldBe before.revision + 1
            f.writes shouldBe 1
        }
    }

    test("partial restart recovery completes the same cap without changing mining or inventory state") {
        for (published in listOf(false, true)) MineOpeningFixture().use { f ->
            val before = f.city
            val tx = f.transaction()
            f.recovered = listOf(tx)
            f.put(f.cap.first(), "minecraft:air")
            if (published) { f.city = tx.after; f.storage.updateCity(f.city) }
            f.await(f.journal.bootstrap())
            f.journal.recover()
            f.ticks()
            f.positions.forEach { f.state(it) shouldBe "minecraft:air" }
            f.city shouldBe tx.after
            f.city.copy(revision = before.revision) shouldBe before
            f.surroundings.forEach { (point, data) -> f.state(point) shouldBe data }
            f.journal.pendingCount shouldBe 0
            f.journal.recover()
            f.ticks()
            f.city.revision shouldBe tx.after.revision
        }
    }

    test("foreign construction, containers and liquids stop the entire capture without touching other dirt") {
        for (obstruction in listOf("minecraft:cobblestone", "minecraft:barrel[facing=up,open=false]",
            "minecraft:ladder[facing=north,waterlogged=false]", "minecraft:water[level=0]")) MineOpeningFixture().use { f ->
            val before = f.city
            f.put(f.cap.first(), obstruction)
            val failed = shouldThrow<CompletionException> { f.await(f.maintenance.request(f.city.id, f.mine.id)) }
            CivsDiagnostics.rejection(failed)?.reason shouldBe "mine-opening-obstructed"
            f.state(f.cap.first()) shouldBe obstruction
            f.cap.drop(1).forEach { f.state(it) shouldBe "minecraft:dirt" }
            f.city shouldBe before
            f.writes shouldBe 0
        }
    }

    test("an exact retained cobblestone cap is allowed but a later native drift blocks every remaining cut") {
        MineOpeningFixture().use { f ->
            f.cap.forEach { f.put(it, "minecraft:cobblestone") }
            f.initial = f.cap.associateWith { "minecraft:cobblestone" }
            f.await(f.maintenance.request(f.city.id, f.mine.id))["changed_cells"] shouldBe 9
            f.positions.forEach { f.state(it) shouldBe "minecraft:air" }
        }
        MineOpeningFixture().use { f ->
            val tx = f.transaction()
            f.recovered = listOf(tx)
            f.put(f.cap.first(), "minecraft:bricks")
            f.await(f.journal.bootstrap())
            f.journal.recover()
            f.ticks()
            f.state(f.cap.first()) shouldBe "minecraft:bricks"
            f.cap.drop(1).forEach { f.state(it) shouldBe "minecraft:dirt" }
            f.city.revision shouldBe tx.beforeRevision
            f.journal.blockedCount shouldBe 1
        }
    }

    test("automatic reconciliation is independent of a sleeping miner and retries an occupied mouth") {
        MineOpeningFixture().use { f ->
            f.occupied = true
            f.maintenance.pulse()
            f.ticks()
            f.writes shouldBe 0
            f.cap.forEach { f.state(it) shouldBe "minecraft:dirt" }
            f.occupied = false
            f.clock += 31_000
            f.maintenance.pulse()
            f.ticks()
            f.positions.forEach { f.state(it) shouldBe "minecraft:air" }
            f.writes shouldBe 1
            f.clock += 31_000
            f.maintenance.pulse()
            f.ticks()
            f.writes shouldBe 1
        }
    }
})

/** Native Auto2 geometry/cap observed 2026-11-15 21:02:11 UTC; no schematic payload is embedded. */
private class MineOpeningFixture(tall: Boolean = false) : AutoCloseable {
    val paper = MockBukkitTestRuntime.open(CivsWorksiteServerFixture())
    val world = CivsWorksiteWorldFixture("mine-opening").also(paper.server::addWorld)
    private val plugin = paper.createSimplePlugin("MineOpeningTest")
    private val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    private val id = if (tall) "fixture/fortress/native-mine-opening1" else "fixture/medievaloak/native-mine-opening3"
    val mine: CivBuilding
    val geometry: CivsMinePlan
    val positions: List<CivPosition>
    val oldMouth: Set<CivPosition>
    val cap: List<CivPosition>
    val surroundings: Map<CivPosition, String>
    private val cities = linkedMapOf<UUID, Settlement>()
    var city: Settlement
        get() = cities.values.single()
        set(value) { cities[value.id] = value }
    private val busy = hashSetOf<UUID>()
    private val unhealthy = hashSetOf<UUID>()
    val quarantined get() = city.id in unhealthy
    private val store = mockk<CivsStore>()
    val storage = CivsStorage(plugin) { emptyArray() }
    private val access = CivsWorld(plugin, tasks) { cities.values }
    val journal: CivsInventoryJournal
    val maintenance: CivsMineMaintenance
    var journalWrite: CompletableFuture<CivInventoryTransaction>? = null
    var written: CivInventoryTransaction? = null
    var recovered = emptyList<CivInventoryTransaction>()
    var writes = 0
    var occupied = false
    var initial = emptyMap<CivPosition, String>()
    var clock = 1_000L

    init {
        // Matching catalog family 7c8aacbdbc2ae99ad768 shifts public metadata by (+2,-1,-2).
        // Tall family df6e4d12c01027e336a9 has bottom (-4,-13,-6), center (-7,-14,-6).
        val public = spatialBuilding("medievaloak", "miner", 3, CivPosition(0, 75, 0))
        val site = requireNotNull(CivsWorksiteLayouts.site(public)).deepCopy()
        val layout = site.getAsJsonObject("mine")
        val shift = if (tall) CivPosition(-11, -8, 2) else CivPosition(2, -1, -2)
        for (key in listOf("ladderBottom", "shaftCenter", "firstSubgroundPoint")) {
            val p = CivsWorksiteLayouts.vector(layout[key])
            layout.add(key, JsonArray().apply { add(p.x + shift.x); add(p.y + shift.y); add(p.z + shift.z) })
        }
        layout.add("shaftFootprint", JsonArray().apply { layout.getAsJsonArray("shaftFootprint").forEach { cell ->
            add(JsonArray().apply { add(cell.asJsonArray[0].asInt + shift.x); add(cell.asJsonArray[1].asInt + shift.z) })
        } })
        CivsWorksiteLayouts.installCatalog(mapOf(id to site))
        mine = if (tall) public.copy(level = 1, schematicId = id, anchor = CivPosition(0, 90, 0), rotation = 0,
            bounds = CivBox(CivPosition(-11, 77, -11), CivPosition(6, 103, -1)),
            entrance = CivPosition(0, 91, 0), control = CivPosition(0, 91, -2)) else public.copy(schematicId = id, anchor = CivPosition(12313, 91, 8358), rotation = 90,
            bounds = CivBox(CivPosition(12314, 85, 8352), CivPosition(12333, 105, 8371)),
            entrance = CivPosition(12313, 92, 8358), control = CivPosition(12325, 88, 8368))
        geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
        positions = CivsMineOpening.positions(mine)
        cap = if (tall) positions else (12322..12324).flatMap { x -> (8363..8365).map { z -> CivPosition(x, 86, z) } }
        surroundings = if (tall) mapOf(CivPosition(-7, 91, -6) to "minecraft:cobblestone_slab[type=top,waterlogged=false]",
            CivPosition(-9, 90, -6) to "minecraft:cobblestone", CivPosition(-4, 90, -6) to "minecraft:ladder[facing=west,waterlogged=false]")
            else mapOf(CivPosition(12323, 92, 8364) to "minecraft:cobblestone_slab[type=top,waterlogged=false]",
                CivPosition(12321, 86, 8364) to "minecraft:cobblestone", CivPosition(12323, 86, 8367) to "minecraft:ladder[facing=north,waterlogged=false]")
        oldMouth = geometry.sections.getValue("0/shaft/opening").mapTo(hashSetOf()) {
            CivsWorksiteLayouts.point(mine, geometry.steps[it].point)
        }
        val worker = spatialWorker(mine, "IRON_PICKAXE").copy(hunger = 0)
        val template = spatialCity(world.uid, listOf(mine), worker)
        fun moved(p: CivPosition) = CivPosition(p.x + mine.anchor.x, p.y + mine.anchor.y - 64, p.z + mine.anchor.z)
        val buildings = template.buildings.map { b -> if (b.id == mine.id) b else b.copy(anchor = moved(b.anchor),
            bounds = CivBox(moved(b.bounds.min), moved(b.bounds.max)), entrance = moved(b.entrance), control = b.control?.let(::moved)) }
        city = CivsRules.validate(template.copy(center = mine.anchor, buildings = buildings,
            inventory = CivInventory(CivStorageMode.PHYSICAL), minePlans = mapOf(mine.id.toString() to
                CivMinePlanState(7, geometry.sections.getValue("0/shaft/railing").last().toLong() + 1))))
        for (x in (mine.anchor.x shr 4) - 4..(mine.anchor.x shr 4) + 4)
            for (z in (mine.anchor.z shr 4) - 4..(mine.anchor.z shr 4) + 4) world.loadChunk(x, z)
        positions.forEach { put(it, "minecraft:air") }
        cap.forEach { put(it, "minecraft:dirt") }
        surroundings.forEach(::put)
        storage.replaceCities(listOf(city))
        every { store.saveInventoryTransaction(any()) } answers {
            written = firstArg(); writes++; journalWrite ?: CompletableFuture.completedFuture(firstArg())
        }
        every { store.save(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
        every { store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
        every { store.loadInventoryTransactions() } answers { CompletableFuture.completedFuture(recovered) }
        every { store.loadConsumedItems() } returns CompletableFuture.completedFuture(CivConsumedItemIndex.EMPTY)
        journal = CivsInventoryJournal(plugin, tasks, store, storage, { cities }, busy, unhealthy, { false },
            { saved -> city = saved; storage.updateCity(saved); CompletableFuture.completedFuture(Unit) },
            { _, _ -> error("Mine maintenance must not create a second city mutation") }, {}, {}, { _, _, _ -> null }, {},
            { _, _ -> city }, {}, { clock })
        val authority = mockk<CivsService>(relaxed = true)
        every { authority.ready() } returns true
        every { authority.now() } answers { clock }
        every { authority.tracked<Map<String, Any?>>() } answers { CompletableFuture() }
        every { authority.reserveCity(any()) } answers { busy += firstArg<UUID>(); Unit }
        every { authority.releaseCity(any()) } answers { busy -= firstArg<UUID>(); Unit }
        val records = CivsCityRecords({ cities }, busy, unhealthy, { false }, { false }, { null }, { 0 }, { 0 })
        maintenance = CivsMineMaintenance(authority, records, tasks, access, storage, journal,
            { _, _, point, before -> initial[point] == before }, { false }, { _, _ -> occupied })
        storage.validateMineOpeningNative = maintenance::validateNative
    }

    fun transaction(): CivInventoryTransaction {
        val cells = cap.map { CivPlanBlock(it, state(it), "minecraft:air") }
        return CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision, city.copy(revision = city.revision + 1),
            emptyList(), worldChanges = cells, mineOpening = CivMineOpeningReceipt(mine, cells)).validated()
    }
    fun put(p: CivPosition, data: String) = world.getBlockAt(p.x, p.y, p.z).setBlockData(Bukkit.createBlockData(data), false)
    fun state(p: CivPosition) = world.getBlockAt(p.x, p.y, p.z).blockData.asString
    fun ticks() { paper.performTicks(12) }
    fun <T> await(future: CompletableFuture<T>): T { ticks(); future.isDone shouldBe true; return future.join() }
    override fun close() { access.close(); storage.close(); tasks.close(); CivsWorksiteLayouts.installCatalog(emptyMap()); paper.close() }
}
