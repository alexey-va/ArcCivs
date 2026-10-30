package ru.arc.civs

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.spyk
import org.bukkit.Material
import org.bukkit.block.Chest
import org.bukkit.inventory.ItemStack
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

class CivsPaidWaterFoundationTest : FunSpec({
    test("saved foundation proof requires complete paid full blocks and forbids free earthworks or ordinary buildings") {
        paidWaterScenario { s ->
            val plan = s.capture()
            civsValidateWaterFoundations(plan)
            val f = s.foundations.single()
            for (invalid in listOf("minecraft:air", "minecraft:sand", "minecraft:gravel", "minecraft:oak_slab[type=bottom,waterlogged=false]",
                "minecraft:chest[facing=north,type=single,waterlogged=false]", "minecraft:water[level=0]")) {
                shouldThrow<CivsRejection> { civsValidateWaterFoundations(plan.copy(blocks = plan.blocks.map { cell ->
                    if (cell.position.y == 56) cell.copy(after = invalid) else cell
                })) }.reason shouldBe "invalid-plan"
            }
            shouldThrow<CivsRejection> { civsValidateWaterFoundations(plan.copy(blocks = plan.blocks.filterNot { it.position.y == 56 })) }
            shouldThrow<CivsRejection> { civsValidateWaterFoundations(plan.copy(terrain = plan.terrain!!.copy(
                blocks = listOf(CivPlanBlock(CivPosition(0, 54, 0), "minecraft:air", "minecraft:stone"))))) }
            shouldThrow<CivsRejection> { civsValidateWaterFoundations(plan.copy(building = plan.building.copy(type = BuildingType.HOUSE))) }
            shouldThrow<CivsRejection> { civsValidateWaterFoundations(plan.copy(terrain = plan.terrain!!.copy(
                waterFoundations = listOf(f.copy(bottomY = 49))))) }
        }
    }

    test("seven water cells stay authored billable blocks and native capture never fills or drops them") {
        paidWaterScenario { s ->
            val plan = s.capture()
            plan.terrain!!.waterFoundations shouldBe s.foundations
            plan.terrain.blocks shouldBe emptyList()
            CivsMaterials.required(plan) shouldBe mapOf("COBBLESTONE" to 7L, "STONE_BRICKS" to 4L)
            plan.blocks.filter { s.foundations.single().contains(it.position) }.size shouldBe 7
            for (y in 56..62) s.world.getBlockAt(0, y, 0).type shouldBe Material.WATER
            s.world.getBlockAt(0, 55, 0).type shouldBe Material.GRAVEL
        }
    }

    test("native admission rejects changed depth lava a constructed bed and a filled container") {
        val changes: List<(PaidWaterScenario) -> Unit> = listOf(
            { it.world.getBlockAt(0, 55, 0).type = Material.WATER },
            { it.world.getBlockAt(0, 60, 0).type = Material.LAVA },
            { it.world.getBlockAt(0, 55, 0).type = Material.STONE_BRICKS },
            { it.world.getBlockAt(0, 63, 0).type = Material.WATER },
            { s -> s.world.getBlockAt(0, 60, 0).also { block -> block.type = Material.CHEST
                (block.state as Chest).inventory.setItem(0, ItemStack(Material.DIAMOND)) } },
        )
        changes.forEach { change -> paidWaterScenario { s ->
            change(s)
            shouldThrow<CompletionException> { s.capture() }
            s.world.getBlockAt(0, 56, 0).type shouldBe Material.WATER
        } }
        paidWaterScenario { s ->
            s.denied += CivPosition(0, 60, 0)
            shouldThrow<CompletionException> { s.capture() }.cause!!.let { (it as CivsRejection).reason } shouldBe "lands-denied"
        }
    }

    test("batch revalidation probes only the affected bed and refuses a changed immutable target") {
        paidWaterScenario(width = 2) { s ->
            val plan = s.capture()
            s.world.getBlockAt(1, 55, 0).type = Material.STONE_BRICKS
            val first = plan.copy(blocks = plan.blocks.filter { it.position.x == 0 && it.position.y == 56 })
            s.revalidate(first)
            shouldThrow<CompletionException> { s.revalidate(plan.copy(blocks = plan.blocks.filter { it.position.x == 1 && it.position.y == 56 })) }
            s.world.getBlockAt(0, 56, 0).type = Material.OAK_PLANKS
            shouldThrow<CompletionException> { s.revalidate(first) }.cause!!.let { (it as CivsRejection).reason } shouldBe "world-changed"
        }
    }

    test("the native write guard accepts only the paid before or after with an intact permitted bed") {
        paidWaterScenario { s ->
            val plan = s.capture()
            val cell = plan.blocks.single { it.position.y == 56 }
            val block = s.world.getBlockAt(0, 56, 0)
            s.subject.permitsWaterFoundationMutation(plan, cell, block) shouldBe true
            block.type = Material.COBBLESTONE
            s.subject.permitsWaterFoundationMutation(plan, cell, block) shouldBe true
            for (changed in listOf(Material.OAK_PLANKS, Material.LAVA, Material.CHEST)) {
                block.type = changed
                s.subject.permitsWaterFoundationMutation(plan, cell, block) shouldBe false
            }
            block.type = Material.WATER
            s.world.getBlockAt(0, 55, 0).type = Material.STONE_BRICKS
            s.subject.permitsWaterFoundationMutation(plan, cell, block) shouldBe false
            s.world.getBlockAt(0, 55, 0).type = Material.GRAVEL
            s.denied += cell.position
            s.subject.permitsWaterFoundationMutation(plan, cell, block) shouldBe false
        }
    }

    test("durable decoding keeps legacy records and rejects corrupted water foundation metadata") {
        paidWaterScenario { s ->
            val root = Files.createTempDirectory("civs-paid-water-")
            try {
                val plan = s.capture().copy(capturedSha256 = "0".repeat(64))
                CivsStore(root, Executor(Runnable::run)).use { store ->
                    store.savePlan(plan).join()
                    store.loadPlan(plan.id).join() shouldBe plan
                }
                val path = root.resolve("plans/${plan.id}.json")
                val document = JsonParser.parseString(Files.readString(path)).asJsonObject
                document.getAsJsonObject("plan").getAsJsonObject("terrain").getAsJsonArray("waterFoundations")
                    .get(0).asJsonObject.addProperty("bottomY", 40)
                Files.writeString(path, Gson().toJson(document))
                CivsStore(root, Executor(Runnable::run)).use { store -> shouldThrow<CompletionException> { store.loadPlan(plan.id).join() } }
                val legacy = plan.copy(id = UUID.randomUUID(), blocks = plan.blocks.map { it.copy(before = "minecraft:air") },
                    terrain = plan.terrain!!.copy(waterFoundations = null))
                CivsStore(root, Executor(Runnable::run)).use { store ->
                    store.savePlan(legacy).join()
                    val saved = JsonParser.parseString(Files.readString(root.resolve("plans/${legacy.id}.json"))).asJsonObject
                    saved.getAsJsonObject("plan").getAsJsonObject("terrain").has("waterFoundations") shouldBe false
                    store.loadPlan(legacy.id).join() shouldBe legacy
                }
            } finally { root.toFile().deleteRecursively() }
        }
    }

    test("paid water items are debited once and the reserved batch survives pause reload and retry") {
        paidWaterScenario { s ->
            val plan = s.capture()
            val required = CivsMaterials.required(plan.copy(blocks = plan.blocks.filter { s.foundations.single().contains(it.position) }))
            required shouldBe mapOf("COBBLESTONE" to 7L)
            val hut = s.hall.copy(id = UUID.randomUUID(), type = BuildingType.BUILDER_HUT,
                anchor = CivPosition(-10, 63, 0), bounds = CivBox(CivPosition(-10, 63, 0), CivPosition(-9, 67, 0)),
                entrance = CivPosition(-10, 64, -1))
            val worker = CivWorker(UUID.randomUUID(), "Builder", hut.id, nextDeliveryAt = 1,
                items = List(CivsEquipment.SLOTS) { if (it == CivsEquipment.BAG) CivResidentItem("COBBLESTONE", 14) else null })
            val order = CivConstruction(plan.id, plan.building.id, 1, 1, 0, ConstructionPhase.READY_TO_PLACE, Stock(),
                plannedBuilding = plan.building, batches = CivConstructionBatches(1, 16), materials = required,
                payment = CivConstructionPayment())
            val city = s.city.copy(buildings = s.city.buildings + hut + plan.building, workers = listOf(worker),
                inventory = CivInventory(CivStorageMode.PHYSICAL), construction = order)
            val paid = CivsRules.payBatch(city, city.owner, order.id, 0, required)
            paid.workers.single().items!![CivsEquipment.BAG]!!.amount shouldBe 7
            paid.construction!!.payment!!.reservedBatch shouldBe 0
            shouldThrow<CivsRejection> { CivsRules.payBatch(paid, paid.owner, order.id, 0, required) }.reason shouldBe "stale-construction"
            val paused = CivsRules.pauseConstruction(paid, paid.owner, order.id, "chunk-unloaded")
            val restored = Gson().fromJson(Gson().toJson(paused), Settlement::class.java)
            val retry = CivsRules.retryConstruction(restored, restored.owner, order.id)
            retry.construction!!.payment!!.reservedBatch shouldBe 0
            retry.workers.single().items!![CivsEquipment.BAG]!!.amount shouldBe 7
        }
    }

    test("terraform keeps paid water columns out of its changes and preserves only verified nearby dirt paths") {
        paidWaterScenario { s ->
            val city = s.city.copy(buildings = listOf(s.hall.copy(anchor = CivPosition(-3, 63, 0),
                bounds = CivBox(CivPosition(-3, 63, 0), CivPosition(-3, 65, 0)), entrance = CivPosition(-2, 64, 0))))
            val surface = CivTerrainSurface(-64, 320, buildMap {
                for (x in -8..8) for (z in -8..8) put(civsTerrainKey(x, z), CivTerrainSurfaceColumn(63, true))
                put(civsTerrainKey(0, 0), CivTerrainSurfaceColumn(62, false, waterTopY = 62, waterBottomY = 55))
            })
            val route = listOf(CivPosition(-2, 64, 0), CivPosition(-1, 64, 0))
            val draft = CivsAutonomousTerraform.draft(city, CivTerraformOption(s.building, 0), surface,
                infrastructureFloors = mapOf(civsTerrainKey(0, 0) to 62), reservedRoute = route, waterFoundations = s.foundations)
            draft.columns.none { it.x == 0 && it.z == 0 } shouldBe true
            draft.waterFoundations shouldBe s.foundations
            val stale = surface.copy(columns = surface.columns + (civsTerrainKey(0, 0) to
                CivTerrainSurfaceColumn(62, false, waterTopY = 62, waterBottomY = 54)))
            shouldThrow<CivsRejection> { CivsAutonomousTerraform.draft(city, CivTerraformOption(s.building, 0), stale,
                infrastructureFloors = mapOf(civsTerrainKey(0, 0) to 62), reservedRoute = route, waterFoundations = s.foundations) }
            val gateId = UUID.randomUUID()
            val gate = s.building.copy(id = gateId, type = BuildingType.GATE, anchor = CivPosition(0, 63, 1),
                schematicId = "civs/infrastructure/v1/gate/$gateId", bounds = CivBox(CivPosition(0, 63, 1), CivPosition(0, 67, 1)),
                entrance = CivPosition(-1, 64, 1), transit = listOf(CivPosition(0, 64, 1)))
            val pathSurface = surface.copy(columns = surface.columns + (civsTerrainKey(0, 1) to CivTerrainSurfaceColumn(63, false, dirtPath = true)))
            val retained = route + CivPosition(0, 64, 0)
            val access = CivAutonomousAccess(mapOf(city.buildings.single().id to retained))
            val gateDraft = CivsAutonomousTerraform.draft(city, CivTerraformOption(gate, 0), pathSurface, access,
                infrastructureFloors = mapOf(civsTerrainKey(0, 1) to 64), reservedRoute = route + CivPosition(-1, 64, 1))
            gateDraft.columns.single { it.x == 0 && it.z == 1 }.let { column -> column.preserve shouldBe true; column.groundY shouldBe 63 }
            val artificial = pathSurface.copy(columns = pathSurface.columns + (civsTerrainKey(0, 1) to CivTerrainSurfaceColumn(63, false)))
            shouldThrow<CivsRejection> { CivsAutonomousTerraform.draft(city, CivTerraformOption(gate, 0), artificial, access,
                infrastructureFloors = mapOf(civsTerrainKey(0, 1) to 64), reservedRoute = route + CivPosition(-1, 64, 1)) }
        }
    }
})

private class PaidWaterScenario(paper: MockBukkitTestRuntime, width: Int) : AutoCloseable {
    val world = CivsWorksiteWorldFixture("paid-water").also(paper.server::addWorld)
    private val id = UUID.randomUUID()
    val building = CivBuilding(id, BuildingType.WALL, 1, CivPosition(0, 56, 0), 0, "civs/infrastructure/v1/wall/$id",
        CivBox(CivPosition(0, 56, 0), CivPosition(width - 1, 66, 0)), CivPosition(-1, 64, 0), BuildingPhase.CONSTRUCTING)
    val foundations = (0 until width).map { CivWaterFoundation(it, 0, 62, 62, 55) }
    val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(-20, 63, 0), 0, "test/hall",
        CivBox(CivPosition(-20, 63, 0), CivPosition(-19, 67, 0)), CivPosition(-20, 64, -1), BuildingPhase.ACTIVE)
    val city = Settlement(UUID.randomUUID(), UUID.randomUUID(), "Paid water", world.uid, CivPosition(0, 64, 0), 96,
        "fixture", Stock(), listOf(hall), emptyList(), autonomy = CivAutonomy())
    val denied = mutableSetOf<CivPosition>()
    private val scheduler = TestTaskScheduler()
    private val tasks = LifecycleTaskScope(scheduler, true)
    val subject = spyk(CivsWorld(paper.createSimplePlugin("PaidWaterTest"), tasks, { 0L }) { listOf(city) })
    private val terrain = CivTerraformPlan(building.bounds, emptyList(), listOf(building.entrance), 62, 0, 0, foundations)
    init {
        for (x in -2..1) for (z in -1..1) world.loadChunk(x, z)
        for (x in 0 until width) {
            world.getBlockAt(x, 55, 0).type = Material.GRAVEL
            for (y in 56..63) world.getBlockAt(x, y, 0).type = if (y <= 62) Material.WATER else Material.AIR
        }
        every { subject.canModify(any(), any(), any()) } answers {
            val block = arg<org.bukkit.block.Block>(1)
            CivPosition(block.x, block.y, block.z) !in denied
        }
    }
    fun capture(): CivPlacementPlan {
        val cells = (56..66).flatMap { y -> (building.bounds.min.x..building.bounds.max.x).map { x ->
            ArcSchematicBlock(ArcSchematicPoint(x, y, 0), if (y <= 62) "minecraft:cobblestone" else "minecraft:stone_bricks", false, false)
        } }
        return finish(subject.captureAutonomousSite(city.id, city.owner, city.worldId, building, cells, terrain))
    }
    fun revalidate(plan: CivPlacementPlan) = finish(subject.revalidate(plan, city.owner))
    private fun <T> finish(future: CompletableFuture<T>): T {
        scheduler.tick(4)
        check(future.isDone) { "Bounded paid foundation scan did not finish" }
        return future.join()
    }
    override fun close() { subject.close(); tasks.close() }
}

private fun paidWaterScenario(width: Int = 1, action: (PaidWaterScenario) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open().use { paper -> PaidWaterScenario(paper, width).use(action) }
}
