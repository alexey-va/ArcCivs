package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsCitizenToolPriorityTest : FunSpec({
    test("a full producer bag unloads before preserving its wrong hand item and prioritising the admitted tool pickup") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("producer-tool-priority").also(paper.server::addWorld)
            for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
            val plugin = paper.createSimplePlugin("ProducerToolPriority")
            val forest = spatialBuilding("caledonia", "lumberjack", 1, CivPosition(0, 63, 0))
            val control = requireNotNull(forest.control)
            val worker = spatialWorker(forest, "IRON_PICKAXE").copy(items = List(CivsEquipment.SLOTS) { slot ->
                when { slot == 4 -> CivResidentItem("IRON_PICKAXE", 1)
                    slot >= CivsEquipment.BAG -> CivResidentItem("OAK_LOG", 64)
                    else -> null }
            })
            val city = spatialCity(world.uid, listOf(forest), worker).copy(inventory = CivInventory(CivStorageMode.PHYSICAL))
            val block = world.getBlockAt(control.x, control.y, control.z).also { it.type = Material.BARREL }
            val local = (block.state as Barrel).inventory
            local.setItem(0, ItemStack(Material.IRON_AXE))
            repeat(23) { local.setItem(it + 1, ItemStack(Material.STONE, 64)) }
            CivsStorage(plugin) { emptyArray() }.use { storage ->
                val context = CivsWorksiteContext(plugin, mockk(), { current, id -> storage.buildingAvailable(current, id) },
                    { _, _ -> false }, { emptySet() }, { 0L })
                context.equipTool(city, worker, forest, world, "_AXE") shouldBe null
                val runtime = CitizenRuntime(plugin) { listOf(city) }
                val routes = mockk<CivsCitizenRoutes>(relaxed = true)
                val leisure = mockk<CivsCitizenLeisure>(relaxed = true)
                val logistics = mockk<CivsCitizenLogistics>(relaxed = true)
                val paths = CivsPaths()
                lateinit var work: CivsCitizenWork
                val couriers = CivsCitizenCouriers(runtime, { routes }, { leisure }, { work }, { logistics }, paths,
                    CivsResidentFoods.BREAD_ONLY, { _, _ -> null }, CivsNavigationSettings())
                work = CivsCitizenWork(runtime, { routes }, { mockk() }, { couriers }, { leisure }, { logistics },
                    { mockk() }, { mockk() }, { mockk() }, mockk(), paths, { _, _ -> null }, { _, _ -> true },
                    { _, _ -> null }, { _, _ -> }, { _, _ -> })
                val plan = CitizenPlan(CitizenToken(forest.id, 0, 1, null), control, control,
                    BuildingType.FORESTER, 0, "waiting-tool")
                val actor = CitizenActor(city.id, worker.id, world.uid, mockk(relaxed = true), plan)
                val returnOutputs = CivsCitizenWork::class.java.getDeclaredMethod("returnProducerOutputs",
                    CitizenActor::class.java, Settlement::class.java, CivWorker::class.java, World::class.java,
                    java.lang.Long.TYPE).also { it.isAccessible = true }
                couriers.sourceOffersCargo(actor, city, worker) shouldBe true
                returnOutputs.invoke(work, actor, city, worker, world, 1_000L) shouldBe true
                verify(exactly = 1) { logistics.travelForSupplies(actor, city, world, control, 1_000L) }

                // One real native return frees the bag slot needed to retain the old held pickaxe.
                val id = UUID.randomUUID()
                storage.reserve(city, id)
                val returned = storage.prepareResidentReturn(city, worker.id, forest.id)
                CivsEquipment.plainCount(returned.worker, "OAK_LOG") shouldBe
                    CivsEquipment.plainCount(worker, "OAK_LOG") - 64
                val after = city.copy(workers = listOf(returned.worker), inventory = returned.plan.inventoryAfter,
                    revision = city.revision + 1)
                val tx = CivInventoryTransaction(id, city.id, city.revision, after, returned.plan.changes,
                    residentTransfer = returned.plan.residentTransfer)
                storage.seal(tx)
                storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
                local.getItem(24) shouldBe ItemStack(Material.OAK_LOG, 64)
                val tool = requireNotNull(context.equipTool(after, returned.worker, forest, world, "_AXE"))
                tool.equipmentAfter!![4] shouldBe CivResidentItem("IRON_AXE", 1)
                tool.equipmentAfter!![CivsEquipment.BAG] shouldBe CivResidentItem("IRON_PICKAXE", 1)
                actor.plan = plan.copy(idleReason = null, workMillis = tool.durationMillis, worldWork = tool,
                    workplace = tool.standing)
                actor.cycle = actor.plan.cycle()
                couriers.sourceOffersCargo(actor, after, returned.worker) shouldBe true
                returnOutputs.invoke(work, actor, after, returned.worker, world, 2_000L) shouldBe false
                actor.outputReturn shouldBe null
                verify(exactly = 0) { logistics.travelForSupplies(actor, after, world, control, 2_000L) }
                returned.worker.completedCycles shouldBe worker.completedCycles
                local.getItem(0) shouldBe ItemStack(Material.IRON_AXE)
            }
        }
    }
})
