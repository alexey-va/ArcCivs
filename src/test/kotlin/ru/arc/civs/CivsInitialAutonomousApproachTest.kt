package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsInitialAutonomousApproachTest : FunSpec({
    afterEach { clearAllMocks(currentThreadOnly = true) }

    test("an initial autonomous hall journals its freshly proved leaf-floor approach before writing and keeps its existing barrel") {
        serviceScenario(nativeGeometry = true, serverFixture = CivsWorksiteServerFixture()) { h ->
            val initial = InitialApproach(h)
            h.start()
            initial.pulse()

            h.saves.size shouldBe 1
            val saved = h.saves.single().first
            val receipt = saved.pendingControls.orEmpty().single()
            receipt.buildingId shouldBe initial.hall.id
            receipt.position shouldBe initial.hall.control
            receipt.approach shouldBe initial.fresh.blocks
            saved.workers shouldBe initial.city.workers
            saved.inventory shouldBe initial.city.inventory
            saved.autonomy shouldBe initial.city.autonomy
            initial.materials() shouldBe initial.startingItems
            initial.floor.type shouldBe Material.OAK_LEAVES
            initial.feet.type shouldBe Material.SHORT_GRASS
            initial.head.type shouldBe Material.OAK_LEAVES
            initial.body.type shouldBe Material.AIR
            verify(exactly = 1) {
                h.worldAdapter.captureManualFoundation(initial.city.id, initial.city.owner, initial.city.worldId,
                    initial.hall, any(), any(), initial.hall, true, false)
                h.worldAdapter.captureAutonomousSite(initial.city.id, initial.city.owner, initial.city.worldId,
                    initial.hall, any(), initial.fresh, initial.hall)
            }

            h.commit()
            initial.floor.type shouldBe Material.DIRT
            initial.feet.type shouldBe Material.AIR
            initial.head.type shouldBe Material.AIR
            initial.body.type shouldBe Material.AIR
            initial.control.type shouldBe Material.BARREL
            initial.materials() shouldBe initial.startingItems
            h.saves.size shouldBe 2
            h.commit()
            h.service.settlements().single().pendingControls.orEmpty() shouldBe emptyList()
            h.service.settlements().single().workers shouldBe initial.city.workers
            h.service.settlements().single().inventory shouldBe initial.city.inventory

            repeat(3) { initial.pulse() }
            h.saves.size shouldBe 2
            initial.materials() shouldBe initial.startingItems
            verify(exactly = 1) { h.worldAdapter.captureManualFoundation(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("held initial approach proof is single-flight and a changed protected block prevents every approach write") {
        serviceScenario(nativeGeometry = true, serverFixture = CivsWorksiteServerFixture()) { h ->
            val proof = CompletableFuture<CivTerraformPlan?>()
            try {
                val initial = InitialApproach(h, proof = proof)
                h.start()
                repeat(3) { initial.pulse() }
                verify(exactly = 1) { h.worldAdapter.captureManualFoundation(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
                h.saves shouldBe emptyList()
                initial.floor.type shouldBe Material.OAK_LEAVES
                initial.materials() shouldBe initial.startingItems

                proof.complete(initial.fresh)
                h.tick()
                h.saves.size shouldBe 1
                initial.head.type = Material.COPPER_ORE // A world change after proof and before durable admission.
                h.commit()
                initial.floor.type shouldBe Material.OAK_LEAVES
                initial.feet.type shouldBe Material.SHORT_GRASS
                initial.head.type shouldBe Material.COPPER_ORE
                initial.control.type shouldBe Material.BARREL
                initial.materials() shouldBe initial.startingItems
                h.service.settlements().single().pendingControls.orEmpty().single().approach shouldBe initial.fresh.blocks
                h.saves.size shouldBe 1
                verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
            } finally {
                proof.completeExceptionally(CivsRejection("test-finished"))
                h.tick()
            }
        }
    }

    test("fresh strict terrain refusal leaves an initial hall and all native items untouched") {
        serviceScenario(nativeGeometry = true, serverFixture = CivsWorksiteServerFixture()) { h ->
            val initial = InitialApproach(h, proof = CompletableFuture.failedFuture(CivsRejection("autonomy-terrain")))
            initial.head.type = Material.COPPER_ORE
            h.start()
            initial.pulse()
            verify(exactly = 1) { h.worldAdapter.captureManualFoundation(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { h.worldAdapter.captureAutonomousSite(any(), any(), any(), any(), any(), any(), any()) }
            h.saves shouldBe emptyList()
            initial.floor.type shouldBe Material.OAK_LEAVES
            initial.head.type shouldBe Material.COPPER_ORE
            initial.control.type shouldBe Material.BARREL
            initial.materials() shouldBe initial.startingItems
            h.service.settlements().single().pendingControls.orEmpty() shouldBe emptyList()
        }
    }

    test("an already usable initial entrance never recaptures or remints the starting supplies") {
        serviceScenario(nativeGeometry = true, serverFixture = CivsWorksiteServerFixture()) { h ->
            val initial = InitialApproach(h)
            initial.floor.type = Material.DIRT
            initial.feet.type = Material.AIR
            initial.head.type = Material.AIR
            h.start()
            repeat(3) { initial.pulse() }
            verify(exactly = 0) {
                h.store.loadPlan(initial.hall.id)
                h.worldAdapter.captureManualFoundation(any(), any(), any(), any(), any(), any(), any(), any(), any())
                h.worldAdapter.captureAutonomousSite(any(), any(), any(), any(), any(), any(), any())
                h.store.saveInventoryTransaction(any())
            }
            h.saves shouldBe emptyList()
            initial.materials() shouldBe initial.startingItems
            initial.control.type shouldBe Material.BARREL
        }
    }
})

/** Service admission and persistence fixture. Native terrain-policy guards remain covered by CivsWorldTest. */
private class InitialApproach(val h: ServiceScenario, proof: CompletableFuture<CivTerraformPlan?>? = null) {
    val hall = h.city.buildings.first().copy(level = 1, phase = BuildingPhase.SITE,
        anchor = CivPosition(0, 68, 0), bounds = CivBox(CivPosition(-2, 66, -4), CivPosition(2, 73, 0)),
        entrance = CivPosition(0, 69, 1), control = CivPosition(1, 69, 0))
    private val hut = hall.copy(id = UUID.randomUUID(), type = BuildingType.BUILDER_HUT,
        anchor = CivPosition(8, 68, 0), bounds = CivBox(CivPosition(6, 66, -4), CivPosition(10, 73, 0)),
        entrance = CivPosition(8, 69, 1), control = CivPosition(9, 69, 0))
    private val layout = CivSettlementPlan(CivsSettlementPlans.VERSION, 0, 74,
        listOf(hall, hut).map { CivSettlementPlot(it, it.bounds) },
        mapOf(hut.id.toString() to (0..8).map { CivPosition(it, 69, 1) }), emptyList(),
        listOf(CivBox(CivPosition(-30, 63, 0), CivPosition(-30, 67, 2))), listOf(0))
    val city = CivsRules.validate(h.city.copy(center = requireNotNull(hall.control), stock = Stock(), buildings = listOf(hall),
        inventory = CivInventory(CivStorageMode.PHYSICAL), immigration = null,
        workers = h.city.workers.take(2).mapIndexed { index, worker -> worker.copy(assignment = null, homeId = hall.id,
            sex = if (index == 0) CivSex.MALE else CivSex.FEMALE, hunger = 20) },
        settings = CivSettlementSettings(chunkLoading = true, immortalResidents = true, autoConstructionMaterials = false),
        autonomy = CivAutonomy(layoutHalfWidth = 74, plan = layout)))
    val floor = h.world.getBlockAt(0, 68, 1)
    val feet = h.world.getBlockAt(0, 69, 1)
    val head = h.world.getBlockAt(0, 70, 1)
    val control = h.world.getBlockAt(1, 69, 0)
    val body = h.world.getBlockAt(0, 68, -1)
    val startingItems = mapOf(Material.OAK_LOG to 96, Material.COBBLESTONE to 96, Material.BREAD to 24,
        Material.DIAMOND to 3)
    val fresh: CivTerraformPlan

    init {
        CivsSettlementPlans.validate(city, layout)
        for (x in -1..1) for (z in -1..1) h.world.loadChunk(x, z)
        for (y in 64..67) h.world.getBlockAt(0, y, 1).type = Material.DIRT
        floor.type = Material.OAK_LEAVES
        feet.type = Material.SHORT_GRASS
        head.type = Material.OAK_LEAVES
        control.type = Material.BARREL
        (control.state as Barrel).inventory.let { inventory ->
            inventory.addItem(ItemStack(Material.OAK_LOG, 64), ItemStack(Material.OAK_LOG, 32),
                ItemStack(Material.COBBLESTONE, 64), ItemStack(Material.COBBLESTONE, 32),
                ItemStack(Material.BREAD, 24), ItemStack(Material.DIAMOND, 3))
        }
        fresh = CivTerraformPlan(CivBox(CivPosition(0, 68, 1), CivPosition(0, 70, 1)), listOf(
            CivPlanBlock(CivPosition(0, 68, 1), floor.blockData.asString, "minecraft:dirt"),
            CivPlanBlock(CivPosition(0, 69, 1), feet.blockData.asString, "minecraft:air"),
            CivPlanBlock(CivPosition(0, 70, 1), head.blockData.asString, "minecraft:air")), emptyList(), 68, 2, 1)
        val oldTerrain = fresh.copy(blocks = listOf(CivPlanBlock(CivPosition(0, 68, 1), "minecraft:air", "minecraft:dirt")))
        val saved = CivPlacementPlan(hall.id, city.id, city.worldId, hall,
            listOf(CivPlanBlock(CivPosition(0, 68, -1), "minecraft:air", "minecraft:stone")),
            "a".repeat(64), oldTerrain, adminPlacement = false)
        val snapshot = mockk<ArcSchematicSnapshot>()
        every { snapshot.blocks() } returns listOf(ArcSchematicBlock(ArcSchematicPoint(0, 0, -1), "minecraft:stone", false, false))
        every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
        every { h.store.loadPlan(hall.id) } returns CompletableFuture.completedFuture(saved)
        every { h.store.loadSchematic(hall.id) } returns CompletableFuture.completedFuture(byteArrayOf(1))
        every { h.blueprints.recover(saved) } returns CompletableFuture.completedFuture(snapshot)
        every { h.worldAdapter.captureManualFoundation(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            (proof ?: CompletableFuture.completedFuture(fresh))
        every { h.worldAdapter.captureAutonomousSite(any(), any(), any(), any(), any(), any(), any()) } answers {
            CompletableFuture.completedFuture(saved.copy(building = arg(3), terrain = arg(5)))
        }
        h.paper.addPlayer("InitialApproachViewer").teleport(Location(h.world, .5, 69.0, 1.5))
    }

    fun pulse() {
        h.time += 5_000
        h.paper.performTicks(20)
    }

    fun materials(): Map<Material, Int> = (control.state as Barrel).inventory.storageContents.filterNotNull()
        .groupBy { it.type }.mapValues { (_, items) -> items.sumOf { it.amount } }
}
