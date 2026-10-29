package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsRegisteredRoadStairsTest : FunSpec({
    test("construction backfill shares the remaining eight-column budget and never selects damaged-road work") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = CivsWorksiteWorldFixture("construction-road-backfill").also(paper.server::addWorld)
            for (x in -1..2) for (z in -1..1) world.loadChunk(x, z)
            val p = CivPosition(0, 64, 0)
            val hall = CivBuilding(UUID(0, 1), BuildingType.TOWN_HALL, 1, CivPosition(-30, 63, -30), 0, "fixture/hall",
                CivBox(CivPosition(-30, 63, -30), CivPosition(-28, 68, -28)), CivPosition(-29, 64, -27), BuildingPhase.ACTIVE)
            val hut = hall.copy(id = UUID(0, 2), type = BuildingType.BUILDER_HUT, anchor = CivPosition(-30, 63, 30),
                bounds = CivBox(CivPosition(-30, 63, 30), CivPosition(-28, 68, 32)), entrance = CivPosition(-29, 64, 33))
            val roads = (0..8).map { index ->
                val floor = p.copy(x = index * 4)
                hall.copy(id = UUID(0, index.toLong() + 10), type = BuildingType.ROAD, anchor = floor,
                    bounds = CivBox(floor.copy(x = floor.x - 1, y = 63), floor.copy(x = floor.x + 1, y = 66)),
                    entrance = floor.copy(y = 65), transit = listOf(floor.copy(x = floor.x - 1),
                        floor.copy(y = 65), floor.copy(x = floor.x + 1, y = 65)))
            }
            val worker = CivWorker(UUID(0, 4), "Builder", hut.id)
            val idle = Settlement(UUID(0, 5), UUID(0, 6), "Road backfill", world.uid, p, 64, "nordic", Stock(),
                listOf(hall, hut) + roads, listOf(worker), inventory = CivInventory(CivStorageMode.PHYSICAL), autonomy = CivAutonomy())
            val planned = hall.copy(level = 2, phase = BuildingPhase.CONSTRUCTING)
            val city = idle.copy(construction = CivConstruction(UUID(0, 7), hall.id, 2, 100, 20,
                ConstructionPhase.WAITING_MATERIALS, Stock(), plannedBuilding = planned))
            val state = "minecraft:spruce_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"
            val plans = roads.map { road -> CivPlacementPlan(road.id, city.id, world.uid, road,
                listOf(CivPlanBlock(road.anchor, "minecraft:air", state))) }
            val network = CivsRoadMaintenance.prepare(city, plans)
            network.targets.size shouldBe 9
            network.targets.forEach { target ->
                world.getBlockAt(target.floor.x, target.floor.y, target.floor.z).blockData = Bukkit.createBlockData(target.after)
            }
            val damaged = network.targets.last()
            val damagedBlock = world.getBlockAt(damaged.floor.x, damaged.floor.y, damaged.floor.z)
            damagedBlock.type = Material.DIRT
            val plugin = paper.createSimplePlugin("ConstructionRoadBackfill")
            LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
                CivsWorld(plugin, tasks) { listOf(city) }.use { native ->
                    (native.captureRoadUpkeep(city, worker, damaged) != null) shouldBe true
                    val registered = mutableListOf<CivPosition>()
                    var tick = 0
                    val maintenance = CivsRoadMaintenance(native, registerSurface = { _, at -> registered += at }) { tick }
                    maintenance.publish(city, network)
                    CivsRoadMaintenance.eligible(city) shouldBe false
                    maintenance.plan(idle, worker) shouldBe null
                    registered.size shouldBe 8
                    maintenance.backfillSurfaces(city)
                    registered.size shouldBe 8 // The earlier resident scan used this tick's entire budget.
                    tick++
                    maintenance.backfillSurfaces(city)
                    registered.size shouldBe 15 // One damaged candidate plus seven healthy columns, all read-only.
                    maintenance.requirements(idle) shouldBe emptyMap()
                    maintenance.requirements(city) shouldBe emptyMap()
                    CivsRoadMaintenance.eligible(city) shouldBe false
                    damagedBlock.type shouldBe Material.DIRT
                    city.workers shouldBe listOf(worker)
                    city.stock shouldBe Stock()
                    city.revision shouldBe 0L
                }
            }
        }
    }

    test("Trails-recognized style stairs receive the same route cost as paved ground") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("registered-road-cost")
            val city = pathsCity().copy(worldId = world.uid)
            val trip = CivsPaths().begin(city)
            val ground = world.getBlockAt(1, 64, 1)
            for (material in listOf(Material.COBBLESTONE, Material.OAK_STAIRS, Material.SPRUCE_STAIRS, Material.DARK_OAK_STAIRS)) {
                ground.type = material
                trip.cost(ground) { 1.25 } shouldBe 1.0
            }
            // Unsupported provider reads leave ordinary outdoor routing in effect.
            trip.cost(ground) { 1.0 } shouldBe CivsPaths.ORDINARY_COST
            ground.type = Material.SPRUCE_PLANKS
            trip.cost(ground) { 1.0 } shouldBe CivsPaths.ORDINARY_COST
        }
    }

    test("the existing upkeep probe backfills only a healthy accepted wooden step without creating paid work") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = CivsWorksiteWorldFixture("registered-road-backfill").also(paper.server::addWorld)
            world.loadChunk(0, 0)
            world.loadChunk(-1, 0)
            val p = CivPosition(0, 64, 0)
            val hall = CivBuilding(UUID(0, 1), BuildingType.TOWN_HALL, 1, CivPosition(-30, 63, -30), 0, "fixture/hall",
                CivBox(CivPosition(-30, 63, -30), CivPosition(-28, 68, -28)), CivPosition(-29, 64, -27), BuildingPhase.ACTIVE)
            val hut = hall.copy(id = UUID(0, 2), type = BuildingType.BUILDER_HUT, anchor = CivPosition(-30, 63, 30),
                bounds = CivBox(CivPosition(-30, 63, 30), CivPosition(-28, 68, 32)), entrance = CivPosition(-29, 64, 33))
            val floors = listOf(p.copy(x = -1, y = 63), p, p.copy(x = 1))
            val road = hall.copy(id = UUID(0, 3), type = BuildingType.ROAD, anchor = p,
                bounds = CivBox(CivPosition(-1, 63, 0), CivPosition(1, 66, 0)), entrance = p.copy(y = 65),
                transit = floors.map { it.copy(y = it.y + 1) })
            val worker = CivWorker(UUID(0, 4), "Builder", hut.id)
            val city = Settlement(UUID(0, 5), UUID(0, 6), "Registered road", world.uid, p, 64, "nordic", Stock(),
                listOf(hall, hut, road), listOf(worker), inventory = CivInventory(CivStorageMode.PHYSICAL), autonomy = CivAutonomy())
            val state = "minecraft:spruce_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"
            val plan = CivPlacementPlan(road.id, city.id, world.uid, road, listOf(CivPlanBlock(p, "minecraft:air", state)))
            val network = CivsRoadMaintenance.prepare(city, listOf(plan))
            val target = network.targets.single()
            target.validated(city)
            world.getBlockAt(p.x, p.y, p.z).blockData = Bukkit.createBlockData(state)
            val plugin = paper.createSimplePlugin("RegisteredRoadBackfill")
            LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
                CivsWorld(plugin, tasks) { listOf(city) }.use { native ->
                    val registered = mutableListOf<Pair<UUID, CivPosition>>()
                    var tick = 0
                    val maintenance = CivsRoadMaintenance(native, registerSurface = { id, floor -> registered += id to floor }) { tick }
                    maintenance.publish(city, network)
                    maintenance.plan(city, worker) shouldBe null
                    registered shouldBe listOf(city.id to p)
                    maintenance.requirements(city) shouldBe emptyMap()
                    tick++
                    maintenance.plan(city, worker) shouldBe null // A missing or pending provider may retry on the next ordinary probe.
                    registered shouldBe listOf(city.id to p, city.id to p)
                    registered.clear()
                    val floor = world.getBlockAt(p.x, p.y, p.z)
                    for (invalid in listOf("minecraft:stone_bricks",
                        state.replace("half=bottom", "half=top"), state.replace("waterlogged=false", "waterlogged=true"),
                        state.replace("facing=east", "facing=west"))) {
                        floor.blockData = Bukkit.createBlockData(invalid)
                        native.captureRoadUpkeep(city, worker, target) { id, at -> registered += id to at } shouldBe null
                        registered shouldBe emptyList()
                    }
                    floor.blockData = Bukkit.createBlockData(state)
                    native.captureRoadUpkeep(city.copy(buildings = city.buildings - road), worker, target) { id, at ->
                        registered += id to at
                    } shouldBe null
                    registered shouldBe emptyList()
                    floor.blockData.asString shouldBe state
                    city.stock shouldBe Stock()
                    city.workers shouldBe listOf(worker)
                    city.revision shouldBe 0L
                }
            }
        }
    }
})
