package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executor

class CivsRoadFoundationRepairTest : FunSpec({
    test("retained hall approach gaps capture only continuous air and bill the observed 10 5 4 2 1 cells") {
        // Floor/base pairs observed on the lab hall's five centre columns; normalized X/Z.
        for ((floor, base, count) in listOf(Triple(68, 58, 10), Triple(67, 62, 5), Triple(66, 62, 4),
            Triple(65, 63, 2), Triple(65, 64, 1))) {
            FoundationFixture(floor, base).use { h ->
                val before = h.column()
                val work = h.capture()
                withClue("floor=$floor base=$base") {
                    work.changes.map { it.position.y } shouldBe (base + 1..floor).toList()
                    work.changes.dropLast(1).all { it.before == "minecraft:air" && it.after == "minecraft:cobblestone" } shouldBe true
                    work.changes.last() shouldBe CivPlanBlock(h.target.floor, "minecraft:air", h.target.after)
                    work.inputs shouldBe mapOf("COBBLESTONE" to count.toLong())
                    if (count > 1) work.standing.y shouldBe floor - 2
                    h.native.validateRoadUpkeep(h.city, work)
                    h.column() shouldBe before
                }
            }
        }
    }

    test("the selected depth 24 bill remains the requirement and ordinary partial pickups request the remaining eight") {
        FoundationFixture(68, 44).use { h ->
            var tick = 0
            val world = mockk<CivsWorld>()
            val maintenance = CivsRoadMaintenance(world) { tick }
            val captured = h.capture()
            captured.inputs shouldBe mapOf("COBBLESTONE" to 24L)
            every { world.captureRoadUpkeep(any(), any(), any()) } returns captured.copy(inputs = emptyMap())
            maintenance.publish(h.city, CivsRoadMaintenance.prepare(h.city, listOf(h.plan), proposeCuts = false))
            maintenance.plan(h.city, h.city.workers.single()) shouldBe captured
            maintenance.requirements(h.city) shouldBe mapOf(h.city.workers.single().id to captured.inputs)
            tick++
            maintenance.plan(h.city, h.city.workers.single()) shouldBe captured
            // The unchanged native pickup caller applies maxOf(required, buffer16), then subtracts carried items.
            val initial = captured.inputs.mapValues { (_, count) -> maxOf(count, 16L) }
            CivsEquipment.storageInputs(h.city.workers.single(), initial) shouldBe mapOf("COBBLESTONE" to 24L)
            val carrying16 = CivsEquipment.replace(h.city.workers.single(), MutableList<CivResidentItem?>(CivsEquipment.SLOTS) { null }
                .also { it[CivsEquipment.BAG] = CivResidentItem("COBBLESTONE", 16) })
            CivsEquipment.storageInputs(carrying16, initial) shouldBe mapOf("COBBLESTONE" to 8L)
            CivsEquipment.inputsReady(carrying16, captured.inputs, emptyMap()) shouldBe false
        }
    }

    test("the sealed vector rejects gaps ordering foreign columns nonair foundations and depth overflow") {
        FoundationFixture().use { h ->
            val work = h.capture()
            val bottom = work.changes.first()
            for (changes in listOf(work.changes.drop(1).drop(1).let { listOf(bottom) + it },
                work.changes.reversed(), listOf(bottom.copy(position = bottom.position.copy(x = 1))) + work.changes.drop(1),
                listOf(bottom.copy(before = "minecraft:dirt")) + work.changes.drop(1),
                listOf(bottom.copy(after = "minecraft:oak_planks")) + work.changes.drop(1), work.changes.dropLast(1),
                (44..67).map { CivPlanBlock(h.target.floor.copy(y = it), "minecraft:air", "minecraft:cobblestone") } + work.changes.last())) {
                shouldThrow<IllegalArgumentException> { CivsRoadMaintenance.validateChanges(h.city, h.target, changes) }
            }
            shouldThrow<IllegalArgumentException> {
                CivsRoadMaintenance.validateChanges(h.city.copy(inventory = null), h.target, work.changes)
            }
            shouldThrow<IllegalArgumentException> {
                CivsRoadMaintenance.validateChanges(h.city, h.target.copy(route = null), work.changes)
            }
        }
    }

    test("unpaid shortages and a newly blocked native foundation write neither storage nor world") {
        FoundationFixture().use { h ->
            val work = h.capture()
            val before = h.column()
            h.storage().use { storage ->
                h.stock(9)
                storage.reserve(h.city, UUID.randomUUID())
                shouldThrow<CivsRejection> {
                    storage.prepareWork(h.city, work.buildingId, work.storageInputs(h.city.workers.single()), emptyMap(), work.changes)
                }
                h.stockCount() shouldBe 9
                h.column() shouldBe before
            }
            shouldThrow<IllegalArgumentException> { CivsRoadMaintenance.complete(h.city, work, 3_000) }
            h.column() shouldBe before
            h.block(work.changes[2].position).type = Material.WATER
            h.native.captureRoadUpkeep(h.city, h.city.workers.single(), h.target) shouldBe null
            shouldThrow<CivsRejection> { h.native.validateRoadUpkeep(h.city, work) }.reason shouldBe "world-changed"
            h.stockCount() shouldBe 9
        }
    }

    test("authority includes every foundation cell and its actual solid base and retains stale and unloaded guards") {
        FoundationFixture().use { h ->
            val work = h.capture()
            val bottom = work.changes.first().position
            val base = bottom.copy(y = bottom.y - 1)
            val before = h.column()
            for (denied in listOf(bottom, work.changes[3].position, h.target.floor, base)) {
                h.denied = denied
                h.native.captureRoadUpkeep(h.city, h.city.workers.single(), h.target) shouldBe null
                shouldThrow<CivsRejection> { h.native.validateRoadUpkeep(h.city, work) }.reason shouldBe "lands-denied"
            }
            h.denied = null
            for (p in listOf(bottom, base)) {
                val registered = h.city.copy(inventory = CivInventory(CivStorageMode.PHYSICAL, containers = listOf(p)))
                shouldThrow<CivsRejection> { h.native.validateRoadUpkeep(registered, work) }.reason shouldBe "registered-container"
            }
            val protected = h.city.buildings.first().copy(id = UUID(0, 80), type = BuildingType.HOUSE,
                bounds = CivBox(base.copy(x = -1, z = -1), base.copy(x = 1, y = base.y + 1, z = 1)), anchor = base)
            shouldThrow<CivsRejection> { h.native.validateRoadUpkeep(h.city.copy(buildings = h.city.buildings + protected), work) }
                .reason shouldBe "building-overlap"
            shouldThrow<CivsRejection> { h.native.validateRoadUpkeep(h.city.copy(owner = UUID(0, 90)), work) }.reason shouldBe "stale-worksite"
            val moved = h.city.buildings.last().copy(phase = BuildingPhase.SITE)
            shouldThrow<CivsRejection> { h.native.validateRoadUpkeep(h.city.copy(buildings = h.city.buildings.dropLast(1) + moved), work) }
                .reason shouldBe "stale-worksite"
            h.block(base).type = Material.AIR
            shouldThrow<CivsRejection> { h.native.validateRoadUpkeep(h.city, work) }.reason shouldBe "autonomy-no-path"
            h.block(base).type = Material.GRASS_BLOCK
            h.world.unloadChunk(0, 0)
            h.native.captureRoadUpkeep(h.city, h.city.workers.single(), h.target) shouldBe null
            shouldThrow<CivsRejection> { h.native.validateRoadUpkeep(h.city, work) }.reason shouldBe "chunks-unloaded"
            h.world.loadChunk(0, 0)
            h.column() shouldBe before
        }
    }

    test("deep liquid and protected bases refuse capture without extending the bounded column or moving the worker") {
        FoundationFixture(68, 43).use { h -> h.native.captureRoadUpkeep(h.city, h.city.workers.single(), h.target) shouldBe null }
        for (material in listOf(Material.WATER, Material.CHEST, Material.BEDROCK, Material.OAK_SLAB)) {
            FoundationFixture().use { h ->
                h.block(h.target.floor.copy(y = 58)).type = material
                h.native.captureRoadUpkeep(h.city, h.city.workers.single(), h.target) shouldBe null
            }
        }
        FoundationFixture().use { h ->
            h.block(CivPosition(-1, 65, 0)).type = Material.AIR
            h.native.captureRoadUpkeep(h.city, h.city.workers.single(), h.target) shouldBe null
        }
    }

    test("a new foundation refuses a bottom stair base without a full supporting top face") {
        FoundationFixture().use { h ->
            val captured = h.capture()
            h.block(h.target.floor.copy(y = 58)).blockData = Bukkit.createBlockData(
                "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")
            CivsTerrainPolicy.fullTerraformSupport(h.block(h.target.floor.copy(y = 58))) shouldBe false
            h.native.captureRoadUpkeep(h.city, h.city.workers.single(), h.target) shouldBe null
            shouldThrow<CivsRejection> { h.native.validateRoadUpkeep(h.city, captured) }.reason shouldBe "autonomy-no-path"
        }
    }

    test("floor-only upkeep retains its existing bottom stair support exception and one item charge") {
        FoundationFixture(65, 64).use { h ->
            h.block(h.target.floor.copy(y = 64)).blockData = Bukkit.createBlockData(
                "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")
            val captured = h.capture()
            captured.changes.size shouldBe 1
            captured.inputs shouldBe mapOf("COBBLESTONE" to 1L)
            h.native.validateRoadUpkeep(h.city, captured)
        }
    }

    test("a partial paid foundation receipt restarts with exact changes and one physical debit then healthy capture stops") {
        FoundationFixture().use { h ->
            val work = h.capture()
            h.stock(24)
            val root = Files.createTempDirectory("road-foundation-receipt-")
            val id = UUID.randomUUID()
            val tx = h.storage().use { storage ->
                storage.reserve(h.city, id)
                val plan = storage.prepareWork(h.city, work.buildingId, work.storageInputs(h.city.workers.single()), emptyMap(), work.changes)
                val after = CivsRoadMaintenance.complete(h.city, work, 3_000, nativePaid = true)
                CivInventoryTransaction(id, h.city.id, h.city.revision, after, plan.changes, worldChanges = work.changes,
                    upkeep = CivRoadUpkeepReceipt(h.city.workers.single(), h.city.stock, work, 3_000)).validated()
            }
            CivsStore(root, Executor { it.run() }).use { store ->
                store.save(h.city).join()
                store.saveInventoryTransaction(tx).join() shouldBe tx
            }
            // Crash image: the native chest debit and first three bottom-up cells are on disk.
            h.stock(14)
            work.changes.take(3).forEach { h.block(it.position).setBlockData(Bukkit.createBlockData(it.after), false) }
            CivsStore(root, Executor { it.run() }).use { store ->
                val restored = store.loadInventoryTransactions().join().single()
                restored shouldBe tx
                restored.worldChanges shouldBe work.changes
                restored.upkeep!!.before(restored.after) shouldBe h.city
                h.storage().use { storage ->
                    storage.reserve(h.city, id)
                    storage.seal(restored)
                    repeat(2) {
                        storage.reconcile(restored) shouldBe CivInventoryApplyResult.Applied
                        h.stockCount() shouldBe 14
                        restored.worldChanges!!.forEach { h.block(it.position).blockData.asString shouldBe it.after }
                    }
                }
                store.save(restored.after).join()
                store.deleteInventoryTransaction(id).join() shouldBe true
                store.loadInventoryTransactions().join() shouldBe emptyList()
                h.city = restored.after
                repeat(3) { h.native.captureRoadUpkeep(h.city, h.city.workers.single(), h.target) shouldBe null }
                h.stockCount() shouldBe 14
                h.city.stock shouldBe Stock()
                h.city.workers.single().completedCycles shouldBe 1L
            }
        }
    }

    test("209 shape reference rejects new support vectors while old physical and legacy receipts stay valid") {
        FoundationFixture().use { h ->
            val work = h.capture()
            shouldThrow<IllegalArgumentException> { validateRoadChanges209(h.city, h.target, work.changes) }
            val old = work.copy(changes = listOf(work.changes.last()), inputs = mapOf("COBBLESTONE" to 1L))
            val shortFoundation = work.copy(changes = work.changes.takeLast(2))
            // Also fails within the old five-cell limit: its below-floor cell cannot be floor or cover.
            shouldThrow<IllegalArgumentException> { validateRoadChanges209(h.city, h.target, shortFoundation.changes) }
            validateRoadChanges209(h.city, h.target, old.changes)
            CivsRoadMaintenance.validateChanges(h.city, h.target, old.changes)
            val physicalAfter = CivsRoadMaintenance.complete(h.city, old, 3_000, nativePaid = true)
            val debit = CivInventorySlotChange(h.city.buildings[1].control!!, "BARREL", 0, CivPlainItem("COBBLESTONE", 2), CivPlainItem("COBBLESTONE", 1))
            CivRoadUpkeepReceipt(h.city.workers.single(), h.city.stock, old, 3_000).validated(physicalAfter, listOf(debit))
            val legacy = h.city.copy(inventory = null, stock = Stock(1_000, 1_000, 1_000))
            val legacyWork = old.copy(inputs = emptyMap())
            val legacyAfter = CivsRoadMaintenance.complete(legacy, legacyWork, 3_000)
            legacyAfter.stock.stone shouldBe 999L
            CivRoadUpkeepReceipt(legacy.workers.single(), legacy.stock, legacyWork, 3_000).validated(legacyAfter)
        }
    }
})

/** Bounded reference of .209 (7fbadc5) validateChanges, before foundation vectors existed. */
private fun validateRoadChanges209(city: Settlement, upkeep: CivRoadUpkeep, changes: List<CivPlanBlock>) {
    require(changes.size in 1..CivsRoadMaintenance.MAX_CLEARANCE + 1 && changes.map { it.position }.distinct().size == changes.size)
    require(changes.all { it.before != it.after })
    val floor = changes.firstOrNull()?.takeIf { it.position == upkeep.floor }
    require(floor == null || floor.after == upkeep.after)
    val cover = changes.filter { it.position != upkeep.floor }
    val expected = CivsRoadMaintenance.covers(upkeep.floor, (1..CivsRoadMaintenance.MAX_CLEARANCE).map { dy ->
        cover.firstOrNull { it.position == upkeep.floor.copy(y = upkeep.floor.y + dy) }?.before ?: "minecraft:air"
    }, CivsRoadMaintenance.ownedPavement(city, upkeep))
    require(expected != null && changes == listOfNotNull(floor) + expected)
}

internal class FoundationFixture(floorY: Int = 68, baseY: Int = 58) : AutoCloseable {
    private val paper = MockBukkitTestRuntime.open()
    val world = CivsWorksiteWorldFixture("road-foundation", ground = Material.AIR, collisionForData = { data ->
        if (CivsRoadMaintenance.surface(data.asString)) roadUpkeepBoxes(data).map { it.at(CivPosition(0, 0, 0)) } else null
    }).also(paper.server::addWorld)
    private val plugin = paper.createSimplePlugin("RoadFoundation")
    lateinit var city: Settlement
    val plan: CivPlacementPlan
    val target: CivRoadUpkeep
    var denied: CivPosition? = null
    val native = CivsRoadWorld(plugin, { listOf(city) }, { _, block, _ -> CivPosition(block.x, block.y, block.z) != denied }, { false })
    init {
        for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
        val hall = CivBuilding(UUID(0, 1), BuildingType.TOWN_HALL, 1, CivPosition(-60, 63, -60), 0, "fixture/hall",
            CivBox(CivPosition(-60, 63, -60), CivPosition(-58, 68, -58)), CivPosition(-59, 64, -57), BuildingPhase.ACTIVE)
        val hut = hall.copy(id = UUID(0, 2), type = BuildingType.BUILDER_HUT, anchor = CivPosition(-10, 63, 0),
            bounds = CivBox(CivPosition(-11, 63, -1), CivPosition(-9, 68, 1)), entrance = CivPosition(-10, 64, -2),
            control = CivPosition(-10, 64, 0), schematicId = "fixture/hut")
        val source = hall.copy(id = UUID(0, 3), type = BuildingType.HOUSE, anchor = CivPosition(0, 63, 20),
            bounds = CivBox(CivPosition(0, 63, 20), CivPosition(5, 69, 25)), entrance = CivPosition(0, floorY + 1, 0),
            schematicId = CivsBlueprints.schematicId("caledonia", BuildingType.HOUSE, 1))
        city = CivsRules.validate(Settlement(UUID(0, 10), UUID(0, 11), "Foundations", world.uid, CivPosition(0, 64, 0), 96,
            "caledonia", Stock(), listOf(hall, hut, source), listOf(CivWorker(UUID(0, 13), "Builder", hut.id, nextDeliveryAt = 1)),
            autonomy = CivAutonomy(), inventory = CivInventory(CivStorageMode.PHYSICAL)))
        plan = CivPlacementPlan(source.id, city.id, city.worldId, source, emptyList(), terrain = CivTerraformPlan(
            CivBox(CivPosition(0, floorY, 0), CivPosition(0, floorY + 2, 0)), emptyList(), listOf(source.entrance), source.anchor.y, 0, 0))
        target = CivsRoadMaintenance.prepare(city, listOf(plan), proposeCuts = false).targets.single { it.floor == CivPosition(0, floorY, 0) }
        block(CivPosition(0, baseY, 0)).type = Material.GRASS_BLOCK
        val standingY = if (floorY - baseY > 1) floorY - 2 else floorY + 1
        block(CivPosition(-1, standingY - 1, 0)).type = Material.GRASS_BLOCK
        block(hut.control!!).type = Material.BARREL
    }
    fun block(p: CivPosition) = world.getBlockAt(p.x, p.y, p.z)
    fun column() = (43..target.floor.y + 4).associateWith { block(target.floor.copy(y = it)).blockData.asString }
    fun capture(): CivWorldWork = requireNotNull(native.captureRoadUpkeep(city, city.workers.single(), target)).let {
        it.copy(inputs = CivsRoadMaintenance.inputs(city, it))
    }
    fun stock(count: Int) { (block(city.buildings[1].control!!).state as Barrel).inventory.setItem(0, ItemStack(Material.COBBLESTONE, count)) }
    fun stockCount(): Int = (block(city.buildings[1].control!!).state as Barrel).inventory.getItem(0)?.amount ?: 0
    fun storage() = CivsStorage(plugin) { emptyArray() }.also { storage ->
        storage.replaceCities(listOf(city))
        storage.foodCanModify = { _, _ -> true }
        storage.validateUpkeepNative = { tx ->
            try { native.validateRoadUpkeep(requireNotNull(tx.upkeep).before(tx.after), tx.upkeep.work); null }
            catch (error: CivsRejection) { CivInventoryApplyResult.Conflict(mapOf("reason" to error.reason)) }
        }
    }
    override fun close() { paper.close() }
}
