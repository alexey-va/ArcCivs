package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.block.data.Ageable
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.core.LifecycleTaskScope
import java.util.UUID

class CivsPhysicalWorkTest : FunSpec({
    test("the authored farm tills plants and harvests only mature real crops without changing its water and hay center") {
        ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open().use { paper ->
            val world = CivsWorksiteWorldFixture("farm").also(paper.server::addWorld)
            val plugin = paper.createSimplePlugin("PhysicalWork")
            val tasks = LifecycleTaskScope(ru.arc.core.BukkitTaskScheduler(plugin))
            val anchor = CivPosition(0, 63, 0)
            val farm = CivBuilding(UUID.randomUUID(), BuildingType.FARM, 1, anchor, 0,
                "civs/minecolonies/medievaloak/agriculture/horticulture/farmer1",
                CivBox(CivPosition(-4, 63, -22), CivPosition(18, 80, -1)), CivPosition(0, 64, 0),
                BuildingPhase.ACTIVE, control = CivPosition(0, 64, -3), production = "WHEAT")
            val worker = CivWorker(UUID.randomUUID(), "Фермер", farm.id, nextDeliveryAt = 1,
                inventory = mapOf("IRON_HOE" to 1, "WHEAT_SEEDS" to 64))
            val city = Settlement(UUID.randomUUID(), UUID.randomUUID(), "Farm", world.uid, CivPosition(0, 64, 0),
                48, "medievaloak", Stock(), listOf(farm, farm.copy(id = UUID.randomUUID(), type = BuildingType.TOWN_HALL,
                    schematicId = "test", bounds = CivBox(CivPosition(-10, 63, 1), CivPosition(-4, 70, 6)),
                    entrance = CivPosition(-5, 64, 0), control = CivPosition(-5, 64, 2), production = null)), listOf(worker), inventory = CivInventory(CivStorageMode.VIRTUAL))
            val plots = CivsWorksiteLayouts.field(farm)
            plots.size shouldBe 72
            val water = CivsWorksiteLayouts.irrigation(farm)
            for (x in -8..22) for (z in -24..4) {
                world.loadChunk(x shr 4, z shr 4)
                world.getBlockAt(x, 64, z).type = Material.DIRT
            }
            water.forEach { world.getBlockAt(it.x, it.y, it.z).type = Material.WATER }
            val counts = mutableMapOf<String, Long>()
            CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                // This is a bounded-admission crop scenario, not a benchmark of MockBukkit/JIT wall time.
                val runtime = CivsWorksites(plugin, access, { _, _ -> counts }, { _, _ -> false }, nanoTime = { 0L })
                // Initial work follows the bounded whole-field scan for mature crops.
                val till = (1..8).firstNotNullOfOrNull {
                    runtime.pulse(listOf(city))
                    runtime.plan(city, worker)
                } ?: error("Initial farm work blocked: ${runtime.idleReason(city, worker)}")
                // Giving an item while work is in flight must not let its old tool/bag image overwrite that gift.
                val equippedDuringWork = worker.copy(inventory = worker.inventory.orEmpty() + ("BREAD" to 1L))
                val changedCity = city.copy(workers = listOf(equippedDuringWork))
                runtime.plan(changedCity, equippedDuringWork) shouldBe null
                shouldThrow<CivsRejection> { runtime.validate(changedCity, till) }
                till.outputs shouldBe emptyMap()
                till.changes.single().after shouldBe "minecraft:farmland[moisture=7]"
                val soil = till.changes.single().position
                plots.contains(soil) shouldBe true
                till.changes.forEach { cell -> world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).setBlockData(org.bukkit.Bukkit.createBlockData(cell.after), false) }
                runtime.invalidate(worker.id)
                // All other plots are occupied, leaving exactly this already-tilled plot available for planting.
                plots.filter { it != soil }.forEach { p -> world.getBlockAt(p.x, p.y + 1, p.z).type = Material.STONE }
                repeat(8) { runtime.pulse(listOf(city)) }
                val plant = requireNotNull(runtime.plan(city, worker))
                plant.inputs shouldBe mapOf("WHEAT_SEEDS" to 1L)
                plant.storageInputs(worker) shouldBe emptyMap()
                val plantedCity = CivsRules.finishWorldWork(city, plant, 10_000)
                CivsEquipment.plainCount(plantedCity.workers.single(), "WHEAT_SEEDS") shouldBe 63
                shouldThrow<CivsRejection> { CivsRules.finishWorldWork(plantedCity, plant, 10_001) }
                runtime.requirement(city, worker) shouldBe ("WHEAT_SEEDS" to 64L)
                val p = plant.changes.single().position
                world.getBlockAt(p.x, p.y, p.z).setBlockData(org.bukkit.Bukkit.createBlockData(plant.changes.single().after), false)
                runtime.invalidate(worker.id)
                repeat(8) { runtime.pulse(listOf(city)) }
                val wetCare = requireNotNull(runtime.plan(city, worker))
                wetCare.status shouldBe "watering"
                wetCare.inputs shouldBe emptyMap()
                wetCare.outputs shouldBe emptyMap()
                wetCare.changes shouldBe listOf(CivPlanBlock(p, "minecraft:wheat[age=0]", "minecraft:wheat[age=1]"))
                world.getBlockAt(soil.x, soil.y, soil.z).setBlockData(
                    org.bukkit.Bukkit.createBlockData("minecraft:farmland[moisture=3]"), false)
                runtime.invalidate(worker.id)
                repeat(8) { runtime.pulse(listOf(city)) }
                val care = requireNotNull(runtime.plan(city, worker))
                care.status shouldBe "watering"
                care.outputs shouldBe emptyMap()
                care.changes.map(CivPlanBlock::position).toSet() shouldBe setOf(soil, p)
                care.changes.first { it.position == soil }.after shouldBe "minecraft:farmland[moisture=7]"
                care.changes.first { it.position == p }.after shouldBe "minecraft:wheat[age=1]"
                val crop = world.getBlockAt(p.x, p.y, p.z)
                crop.blockData = (crop.blockData as Ageable).also { it.age = it.maximumAge }
                shouldThrow<CivsRejection> { runtime.validate(city, care) }.reason shouldBe "stale-worksite"
                runtime.invalidate(worker.id)
                repeat(8) { runtime.pulse(listOf(city)) }
                val harvest = requireNotNull(runtime.plan(city, worker))
                ((harvest.outputs["WHEAT"] ?: 0L) > 0L) shouldBe true
                harvest.changes.single().position shouldBe p
                water.forEach { world.getBlockAt(it.x, it.y, it.z).type shouldBe Material.WATER }
                val changed = CivsRules.finishWorldWork(city, harvest, 20_000)
                changed.workers.single().completedCycles shouldBe 1
                CivsEquipment.plainCount(changed.workers.single(), "WHEAT").toLong() shouldBe harvest.outputs.getValue("WHEAT")
                changed.inventory!!.buildingStock(farm.id) shouldBe emptyMap()
                shouldThrow<CivsRejection> { CivsRules.finishWorldWork(changed, harvest, 20_001) }
            }
            tasks.close()
        }
        }
    }

    test("new construction rows keep both halves together while old persisted row cursors remain unchanged") {
        val lower = CivPosition(0, 64, 0)
        val upper = lower.copy(y = 65)
        val door = "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]"
        val building = CivBuilding(UUID.randomUUID(), BuildingType.HOUSE, 1, lower, 0, "test",
            CivBox(lower, upper), lower, BuildingPhase.ACTIVE)
        val plan = CivPlacementPlan(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), building,
            listOf(CivPlanBlock(lower, "minecraft:air", door), CivPlanBlock(upper, "minecraft:air", door.replace("lower", "upper"))))
        civsMaterialBatches(plan).size shouldBe 2
        civsMaterialBatches(plan, atomicPairs = true) shouldBe listOf(CivBuildBatch(lower, upper))
        CivsMaterials.required(plan) shouldBe mapOf("OAK_DOOR" to 1L)
        civsPairedPosition(lower, "minecraft:oak_trapdoor[half=bottom]") shouldBe null
    }

    test("transit routes avoid foreign horizontal footprints and sleeping residents choose their house before the hall") {
        val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(0, 63, 0), 0, "test",
            CivBox(CivPosition(0, 63, 0), CivPosition(6, 70, 6)), CivPosition(0, 64, -1), BuildingPhase.ACTIVE, control = CivPosition(2, 64, 2))
        val home = hall.copy(id = UUID.randomUUID(), type = BuildingType.HOUSE, anchor = CivPosition(12, 63, 0),
            bounds = CivBox(CivPosition(12, 63, 0), CivPosition(18, 70, 6)), control = CivPosition(14, 64, 2))
        val worker = CivWorker(UUID.randomUUID(), "Житель", null)
        val city = Settlement(UUID.randomUUID(), UUID.randomUUID(), "Town", UUID.randomUUID(), CivPosition(0, 64, 0),
            48, "test", Stock(), listOf(hall, home), listOf(worker))
        citizenRouteCellAllowed(city, emptySet(), 3, 3) shouldBe false
        citizenRouteCellAllowed(city, setOf(hall.id), 3, 3) shouldBe true
        citizenRouteCellAllowed(city, setOf(hall.id), 14, 3) shouldBe false
        val sleep = requireNotNull(citizenShelterTarget(city, worker) { true })
        home.bounds.contains(sleep.x, sleep.y, sleep.z) shouldBe true
        val fallback = requireNotNull(citizenShelterTarget(city.copy(buildings = listOf(hall)), worker) { true })
        hall.bounds.contains(fallback.x, fallback.y, fallback.z) shouldBe true
    }
})
