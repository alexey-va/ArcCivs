package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsInitialCampAccessTest : FunSpec({
    test("a sealed SITE camp journals one cap cut during hut work and makes existing bread reachable") {
        for (afterImage in listOf(false, true)) campAccessScenario { h, storage ->
            val camp = CampAccess(h)
            h.start()
            val before = h.service.settlements().single()
            citizenStandable(h.world, camp.standing) shouldBe true
            civsCanReachContainer(camp.feet, camp.control) shouldBe false
            storage.capturePlanningStorage(before)
            storage.residentFoodSource(before, before.workers.single(), camp.feet) shouldBe null
            val originalSlots = camp.slots()

            camp.pulse()
            val staged = h.saves.filter { (saved, _) -> saved.pendingControls.orEmpty().any { it.buildingId == camp.hall.id } }
            staged.size shouldBe 1
            val (saved, durable) = staged.single()
            val receipt = saved.pendingControls.orEmpty().single()
            receipt.position shouldBe camp.control
            receipt.retire shouldBe null
            receipt.approach shouldBe listOf(CivPlanBlock(camp.cap, "minecraft:stone", "minecraft:air"))
            saved.construction shouldBe before.construction
            saved.workers shouldBe before.workers
            saved.inventory shouldBe before.inventory
            camp.capBlock.type shouldBe Material.STONE
            camp.slots() shouldBe originalSlots
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }

            // An already-applied native after-image may survive before the durable receipt is cleared.
            if (afterImage) camp.capBlock.type = Material.AIR
            durable.complete(saved)
            h.tick()
            camp.capBlock.type shouldBe Material.AIR
            camp.slots() shouldBe originalSlots
            h.saves.last().first.pendingControls.orEmpty() shouldBe emptyList()
            h.commit()
            verify(exactly = if (afterImage) 0 else 1) {
                h.worldAdapter.terrainChanged(camp.city.worldId, listOf(camp.cap))
            }
            camp.pulse()
            h.saves.count { (next, _) -> next.pendingControls.orEmpty().any { it.buildingId == camp.hall.id } } shouldBe 1
            val accessible = h.service.settlements().single()
            accessible.construction shouldBe before.construction
            val worker = accessible.workers.single()
            storage.capturePlanningStorage(accessible)
            val source = requireNotNull(storage.residentFoodSource(accessible, worker, camp.feet))
            source shouldBe CivResidentFoodSource(camp.hall.id, camp.control, camp.standing)
            civsCanReachContainer(camp.feet, camp.control) shouldBe true
            every { h.citizens.residentCollectionFeet(worker.id, false) } returns camp.feet
            val journal = mutableListOf<CivInventoryTransaction>()
            every { h.store.saveInventoryTransaction(any()) } answers {
                CompletableFuture.completedFuture(firstArg<CivInventoryTransaction>().also(journal::add))
            }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            val priorSaves = h.saves.size
            val pickup = h.service.pickupResidentFood(camp.city.id, worker.id, source)
            h.tickUntil("ordinary food pickup save") { pickup.isDone || h.saves.size > priorSaves }
            if (pickup.isCompletedExceptionally) pickup.join()
            h.saves.size shouldBe priorSaves + 1
            h.commit()
            val carried = h.await(pickup)
            journal.single().collected?.food?.material shouldBe "BREAD"
            CivsEquipment.plainCount(carried.workers.single(), "BREAD") shouldBe 4
            camp.slots() shouldBe originalSlots.toMutableList().also { it[0] = ItemStack(Material.BREAD, 20) }
            carried.construction shouldBe before.construction
            carried.stock shouldBe before.stock
        }
    }

    test("a denied camp cap does not create a pending repair or disturb the original barrel") {
        campAccessScenario { h, _ ->
            val camp = CampAccess(h)
            every { h.worldAdapter.canModify(camp.city.owner, camp.capBlock, Material.AIR) } returns false
            h.start()
            camp.pulse()
            h.saves.none { (saved, _) -> saved.pendingControls.orEmpty().any { it.buildingId == camp.hall.id } } shouldBe true
            camp.capBlock.type shouldBe Material.STONE
            camp.slots()[0] shouldBe ItemStack(Material.BREAD, 24)
        }
    }
})

private fun campAccessScenario(body: (ServiceScenario, CivsStorage) -> Unit) {
    lateinit var storage: CivsStorage
    serviceScenario(nativeGeometry = true, serverFixture = CivsWorksiteServerFixture(), storageFactory = { plugin ->
        CivsStorage(plugin) { emptyArray() }.also {
            storage = it
            // The existing queued-food fixture also isolates the native region flush here.
            it.persistCollectedNative = { _, _ -> CompletableFuture.completedFuture(Unit) }
        }
    }) { h -> body(h, storage) }
}

/** The LAB geometry translated down to fit MockBukkit's default 128-block ceiling. */
private class CampAccess(private val h: ServiceScenario) {
    val control = CivPosition(1, 69, 0)
    val cap = control.copy(y = control.y + 1)
    val standing = cap.copy(z = cap.z + 1)
    val feet = standing.location(h.world)
    val hall = h.city.buildings.first().copy(level = 1, phase = BuildingPhase.SITE,
        anchor = CivPosition(0, 68, 0), bounds = CivBox(CivPosition(-2, 66, -4), CivPosition(2, 73, 0)),
        entrance = CivPosition(0, 69, 1), control = control)
    private val hut = h.city.buildings.last().copy(type = BuildingType.BUILDER_HUT, level = 1, phase = BuildingPhase.CONSTRUCTING,
        control = CivPosition(10, 64, 0))
    private val order = CivConstruction(UUID.randomUUID(), hut.id, 1, 2, 1, ConstructionPhase.CONSTRUCTING, Stock(),
        plannedBuilding = hut, batches = CivConstructionBatches(2, 1, atomicPairs = true),
        materials = mapOf("COBBLESTONE" to 2L), payment = CivConstructionPayment(), blocksRequired = 2, blocksPlaced = 1)
    val city = CivsRules.validate(h.city.copy(center = control, stock = Stock(), buildings = listOf(hall, hut),
        workers = listOf(h.city.workers.first().copy(assignment = hut.id, nextDeliveryAt = 1, homeId = hall.id, hunger = 8)),
        construction = order,
        inventory = CivInventory(CivStorageMode.PHYSICAL), autonomy = CivAutonomy(),
        settings = CivSettlementSettings(chunkLoading = true, immortalResidents = true, autoConstructionMaterials = false)))
    val capBlock get() = h.world.getBlockAt(cap.x, cap.y, cap.z)
    private val barrel get() = (h.world.getBlockAt(control.x, control.y, control.z).state as Barrel).inventory

    init {
        for (x in -1..1) for (z in -1..1) h.world.loadChunk(x, z)
        for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to -1)) for (y in 68..71) {
            h.world.getBlockAt(control.x + dx, y, control.z + dz).type = Material.STONE
        }
        h.world.getBlockAt(control.x, 68, control.z).type = Material.STONE
        capBlock.type = Material.STONE
        h.world.getBlockAt(cap.x, cap.y + 1, cap.z).type = Material.STONE
        h.world.getBlockAt(standing.x, standing.y - 1, standing.z).type = Material.COARSE_DIRT
        h.world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
        barrel.setItem(0, ItemStack(Material.BREAD, 24))
        barrel.setItem(1, ItemStack(Material.OAK_LOG, 37))
        h.world.getBlockAt(10, 64, 0).type = Material.BARREL
        ((h.world.getBlockAt(10, 64, 0).state as Barrel).inventory).setItem(0, ItemStack(Material.COBBLESTONE, 2))
        val rows = listOf(CivPlanBlock(CivPosition(8, 64, 0), "minecraft:air", "minecraft:cobblestone"),
            CivPlanBlock(CivPosition(9, 64, 0), "minecraft:air", "minecraft:cobblestone"))
        h.world.getBlockAt(8, 64, 0).type = Material.COBBLESTONE
        every { h.store.loadPlan(order.id) } returns CompletableFuture.completedFuture(
            CivPlacementPlan(order.id, city.id, city.worldId, hut, rows))
        every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
        h.paper.addPlayer("CampAccessViewer").teleport(feet)
    }

    fun pulse() { h.time += 5_000; h.paper.performTicks(20) }
    fun slots(): List<ItemStack?> = barrel.storageContents.map { it?.clone() }
}
