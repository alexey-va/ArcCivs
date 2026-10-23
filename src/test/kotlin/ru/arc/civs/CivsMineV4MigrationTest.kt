package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsMineV4MigrationTest : FunSpec({
    test("serialized V3 step 83 and its prepared receipt remain readable without a V4 provenance field") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use {
            val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
            val plan = requireNotNull(CivsMinePlan.forBuilding(mine, 3))
            plan.steps.size shouldBe 3561
            plan.steps[82].point shouldBe CivPosition(7, -9, -11)
            plan.steps[82].after shouldBe "minecraft:ladder[facing=west,waterlogged=false]"
            val worker = spatialWorker(mine, "IRON_PICKAXE").copy(completedCycles = 49)
            val key = mine.id.toString()
            val state = CivMinePlanState(3, 83, 214, 281)
            val city = spatialCity(UUID.randomUUID(), listOf(mine), worker).copy(
                mineProgress = mapOf(key to 214L), minePlans = mapOf(key to state))
            val json = Gson().toJson(city)
            json.contains("\"v3Step\"") shouldBe false

            val restored = CivsRules.validate(Gson().fromJson(json, Settlement::class.java))

            restored shouldBe city
            restored.minePlans!!.getValue(key) shouldBe state
            restored.minePlans!!.getValue(key).v3Step shouldBe null
            val historical = plan.steps[82]
            val cell = CivPlanBlock(CivsWorksiteLayouts.point(mine, historical.point), "minecraft:air",
                requireNotNull(historical.after))
            val receipt = CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision,
                city.copy(revision = city.revision + 1), emptyList(), worldChanges = listOf(cell)).validated()
            val restoredReceipt = Gson().fromJson(Gson().toJson(receipt), CivInventoryTransaction::class.java).validated()
            restoredReceipt.after.minePlans shouldBe city.minePlans
            restoredReceipt.after.mineProgress shouldBe city.mineProgress
            restoredReceipt.after.workers.single().items shouldBe worker.items
            restoredReceipt.worldChanges shouldBe listOf(cell)
        }
    }

    test("the first physical V4 receipt stores its own cursor preserves V3 V2 and legacy provenance and applies once") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("mine-v4-migration-receipt").also(paper.server::addWorld)
            val plugin = paper.createSimplePlugin("MineV4MigrationReceipt")
            val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
            val v3 = requireNotNull(CivsMinePlan.forBuilding(mine, 3))
            val v4 = requireNotNull(CivsMinePlan.forBuilding(mine, 4))
            val oldPrefix = v3.steps.take(83).associateBy { it.point }
            val index = v4.steps.indexOfFirst { it.after == "minecraft:cobblestone" &&
                oldPrefix[it.point]?.after != "minecraft:cobblestone" }
            (index >= 0) shouldBe true
            val step = v4.steps[index]
            val next = index + 1L
            (next != 83L) shouldBe true
            val point = CivsWorksiteLayouts.point(mine, step.point)
            world.loadChunk(point.x shr 4, point.z shr 4)
            world.getBlockAt(point.x, point.y, point.z).type = Material.AIR
            val worker = spatialWorker(mine, "IRON_PICKAXE").copy(completedCycles = 49)
            val key = mine.id.toString()
            val oldState = CivMinePlanState(3, 83, 214, 281)
            val city = spatialCity(world.uid, listOf(mine), worker).copy(
                mineProgress = mapOf(key to 214L), minePlans = mapOf(key to oldState))
            val beforeStone = CivsEquipment.plainCount(worker, "COBBLESTONE")
            val beforeTorches = CivsEquipment.plainCount(worker, "TORCH")
            val beforeLadders = CivsEquipment.plainCount(worker, "LADDER")
            // Controlled physical planner output: this covers receipt/cursor conservation,
            // while the worksite tests own native prefix, standing and route admission.
            val work = CivWorldWork(worker.id, 49, mine.id,
                CivsWorksiteLayouts.point(mine, requireNotNull(step.approach)),
                listOf(CivPlanBlock(point, "minecraft:air", "minecraft:cobblestone")),
                inputs = mapOf("COBBLESTONE" to 1L), status = "securing-mine", miningStep = next,
                miningPlanVersion = 4, focus = point)
            CivsStorage(plugin) { emptyArray() }.use { storage ->
                val id = UUID.randomUUID()
                storage.reserve(city, id)
                val native = storage.prepareWork(city, mine.id, work.storageInputs(worker), emptyMap(), work.changes)
                val after = CivsRules.finishWorldWork(city, work, 100_000).copy(inventory = native.inventoryAfter)
                val receipt = CivInventoryTransaction(id, city.id, city.revision, after, native.changes,
                    worldChanges = native.worldChanges).validated()
                val restored = Gson().fromJson(Gson().toJson(receipt), CivInventoryTransaction::class.java).validated()

                city.minePlans shouldBe mapOf(key to oldState)
                city.workers.single().completedCycles shouldBe 49L
                CivsEquipment.plainCount(city.workers.single(), "COBBLESTONE") shouldBe beforeStone
                world.getBlockAt(point.x, point.y, point.z).type shouldBe Material.AIR
                restored.after.minePlans shouldBe mapOf(key to CivMinePlanState(4, next, 214, 281, 83))
                restored.after.mineProgress shouldBe city.mineProgress
                restored.after.workers.single().completedCycles shouldBe 50L
                CivsEquipment.plainCount(restored.after.workers.single(), "COBBLESTONE") shouldBe beforeStone - 1
                CivsEquipment.plainCount(restored.after.workers.single(), "TORCH") shouldBe beforeTorches
                CivsEquipment.plainCount(restored.after.workers.single(), "LADDER") shouldBe beforeLadders
                restored.after.stock shouldBe city.stock
                restored.after.inventory!!.buildingStock(mine.id) shouldBe emptyMap()

                storage.seal(restored, keepViewersOpen = true)
                storage.reconcile(restored) shouldBe CivInventoryApplyResult.Applied
                storage.reconcile(restored) shouldBe CivInventoryApplyResult.Applied
                world.getBlockAt(point.x, point.y, point.z).type shouldBe Material.COBBLESTONE
                CivsEquipment.plainCount(restored.after.workers.single(), "COBBLESTONE") shouldBe beforeStone - 1
                shouldThrow<CivsRejection> { CivsRules.finishWorldWork(restored.after, work, 100_001) }
                    .reason shouldBe "stale-delivery"
                val current = work.copy(cycle = 50)
                for (stale in listOf(current.copy(miningPlanVersion = 3, miningStep = 84),
                    current.copy(miningStep = next - 1), current.copy(miningPlanVersion = CivsMinePlan.VERSION + 1),
                    current.copy(changes = emptyList()))) {
                    shouldThrow<CivsRejection> { CivsRules.finishWorldWork(restored.after, stale, 100_001) }
                        .reason shouldBe "stale-worksite"
                }
                shouldThrow<CivsRejection> { CivsRules.finishWorldWork(city,
                    work.copy(miningPlanVersion = 3, miningStep = 82), 100_001) }.reason shouldBe "stale-worksite"
                restored.after.minePlans shouldBe mapOf(key to CivMinePlanState(4, next, 214, 281, 83))
                restored.after.workers.single().completedCycles shouldBe 50L
                storage.unlock(id)
            }
        }
    }

    test("invalid V3 cursor and provenance prefixes are rejected rather than reinterpreted as V4 ordinals") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use {
            val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
            val size = requireNotNull(CivsMinePlan.forBuilding(mine, 3)).steps.size.toLong()
            val key = mine.id.toString()
            val city = spatialCity(UUID.randomUUID(), listOf(mine), spatialWorker(mine, "IRON_PICKAXE"))
                .copy(mineProgress = mapOf(key to 214L))
            for (invalid in listOf(CivMinePlanState(3, -1, 214, 281), CivMinePlanState(3, size + 1, 214, 281),
                CivMinePlanState(3, 83, 214, 281, 83), CivMinePlanState(4, 1, 214, 281, -1),
                CivMinePlanState(4, 1, 214, 281, size + 1))) {
                val json = Gson().toJson(city.copy(minePlans = mapOf(key to invalid)))
                shouldThrow<CivsRejection> { CivsRules.validate(Gson().fromJson(json, Settlement::class.java)) }
                    .reason shouldBe "invalid-state"
            }
        }
    }
})
