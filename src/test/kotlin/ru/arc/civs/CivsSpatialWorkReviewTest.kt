package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID

class CivsSpatialWorkReviewTest : FunSpec({
    test("a neighbouring forester never plants in the authored field outside the farm hut bounds") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val world = CivsWorksiteWorldFixture("field-reservation").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("SpatialField")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                val farm = spatialBuilding("nordic", "farmer", 1, CivPosition(0, 63, 0))
                val forest = spatialBuilding("nordic", "lumberjack", 1, CivPosition(20, 63, 0)).copy(production = "OAK_LOG")
                val worker = spatialWorker(forest, "IRON_AXE")
                val city = spatialCity(world.uid, listOf(farm, forest), worker)
                val plot = CivsWorksiteLayouts.field(farm).first()
                farm.bounds.contains(plot.x, plot.y, plot.z) shouldBe false
                forest.bounds.contains(plot.x, plot.y, plot.z) shouldBe false
                world.getBlockAt(plot.x, plot.y, plot.z).type = Material.DIRT
                for (x in -2..3) for (z in -2..1) world.loadChunk(x, z)
                CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                    val runtime = CivsWorksites(plugin, access, { _, _ -> mapOf("OAK_SAPLING" to 8L) }, { _, _ -> false })
                    // One complete pass through the exact 42 x 38 search envelope.
                    repeat(100) { runtime.pulse(listOf(city)) }
                    runtime.plan(city, worker) shouldBe null
                    world.getBlockAt(plot.x, plot.y + 1, plot.z).type shouldBe Material.AIR
                }
                tasks.close()
            }
        }
    }

    test("the actual quarry metadata keeps its below-foundation work route inside its own allowed footprint") {
        for (style in listOf("nordic", "medievaloak", "caledonia")) {
            val mine = spatialBuilding(style, "miner", 3, CivPosition(0, 63, 0))
            val worker = spatialWorker(mine, "NETHERITE_PICKAXE")
            val city = spatialCity(UUID.randomUUID(), listOf(mine), worker)
            val first = CivsWorksiteLayouts.site(mine)!!.getAsJsonObject("mine")["firstSubgroundPoint"]
            val below = CivsWorksiteLayouts.point(mine, CivsWorksiteLayouts.vector(first))
            mine.bounds.contains(below.x, below.y, below.z) shouldBe false
            mine.bounds.contains(mine.entrance.x, mine.entrance.y, mine.entrance.z) shouldBe false
            val plan = CitizenPlan(CitizenToken(mine.id, 0, 0, null), mine.entrance, below, BuildingType.QUARRY, 4_000, null)
            val allowed = citizenRouteBuildings(city, plan, below, mine.entrance)
            withClue(style) { citizenRouteCellAllowed(city, allowed, below.x, below.z) shouldBe true }
        }
    }

    test("arrival accepts the actual centered endpoint and rejects a nearby different cell") {
        val destination = CivPosition(-7, 64, 11)
        citizenNearEndpoint(CitizenPoint(-6.5, 64.0, 11.5), destination) shouldBe true
        citizenNearEndpoint(CitizenPoint(-7.5, 64.0, 11.5), destination) shouldBe false
        citizenNearEndpoint(CitizenPoint(-6.5, 65.0, 11.5), destination) shouldBe false
    }

    for (style in listOf("nordic", "medievaloak", "caledonia")) {
        test("$style miner upgrades through all three authored floors without losing its shaft cursor") {
            failOnUnsupportedMockBukkitOperation {
                MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                    val world = CivsWorksiteWorldFixture("mine-$style").also(paper.server::addWorld)
                    val plugin = paper.createSimplePlugin("SpatialMine")
                    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                    var mine = spatialBuilding(style, "miner", 1, CivPosition(0, 63, 0))
                    var city = spatialCity(world.uid, listOf(mine), spatialWorker(mine, "NETHERITE_PICKAXE"))
                    val layout = CivsWorksiteLayouts.site(mine)!!.getAsJsonObject("mine")
                    val bottom = CivsWorksiteLayouts.point(mine, CivsWorksiteLayouts.vector(layout["ladderBottom"]))
                    val facing = layout["ladderFacing"].asString
                    // The schematic supplies this upper ladder; untouched stone starts immediately below it.
                    for (y in bottom.y..65) world.getBlockAt(bottom.x, y, bottom.z).blockData =
                        Bukkit.createBlockData("minecraft:ladder[facing=$facing,waterlogged=false]")
                    for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                    val counts = mutableMapOf("COBBLESTONE" to 1_000L, "LADDER" to 1_000L, "TORCH" to 1_000L)
                    var actions = 0
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        val runtime = CivsWorksites(plugin, access, { _, _ -> counts }, { _, _ -> false })
                        for (unlocked in 1..3) {
                            if (unlocked > 1) {
                                val next = mine.copy(level = unlocked, schematicId = mine.schematicId.dropLast(1) + unlocked,
                                    phase = BuildingPhase.CONSTRUCTING)
                                val required = CivsRules.workRequired(BuildingType.QUARRY, unlocked)
                                val order = CivConstruction(UUID.randomUUID(), mine.id, unlocked, required, required,
                                    ConstructionPhase.PLACING, CivsRules.cost(BuildingType.QUARRY, unlocked), plannedBuilding = next)
                                city = CivsRules.completeConstruction(city.copy(construction = order), city.owner, order.id)
                                city.mineProgress.orEmpty()[mine.id.toString()] shouldBe null
                                mine = requireNotNull(city.building(mine.id))
                            }
                            val currentLayout = CivsWorksiteLayouts.site(mine)!!.getAsJsonObject("mine")
                            var emptyPulses = 0
                            while (actions < 1_000 && emptyPulses < 20) {
                                runtime.pulse(listOf(city))
                                val worker = city.workers.single()
                                val work = runtime.plan(city, worker)
                                if (work == null) { emptyPulses++; continue }
                                emptyPulses = 0
                                withClue("$style action ${actions + 1}, mine step ${work.miningStep}, standing ${work.standing}") {
                                    citizenStandable(world, work.standing) shouldBe true
                                }
                                runtime.validate(city, work)
                                work.inputs.forEach { (key, amount) ->
                                    counts[key] = counts.getValue(key) - amount
                                    (counts.getValue(key) >= 0) shouldBe true
                                }
                                work.outputs.forEach { (key, amount) -> counts[key] = counts.getOrDefault(key, 0) + amount }
                                work.changes.forEach { cell -> world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
                                    .setBlockData(Bukkit.createBlockData(cell.after), false) }
                                city = city.copy(workers = listOf(worker.copy(completedCycles = worker.completedCycles + 1,
                                    items = work.equipmentAfter ?: worker.items)),
                                    mineProgress = mapOf(mine.id.toString() to requireNotNull(work.miningStep)))
                                runtime.invalidate(worker.id)
                                actions++
                            }
                            for (level in currentLayout.getAsJsonArray("floors")) {
                                for (cell in level.asJsonObject.getAsJsonArray("floorCells")) {
                                    val support = CivsWorksiteLayouts.point(mine, CivsWorksiteLayouts.vector(cell))
                                    withClue("$style L$unlocked after $actions actions, persisted ${city.mineProgress}; support $support") {
                                        world.getBlockAt(support.x, support.y, support.z).type shouldBe Material.COBBLESTONE
                                        for (dy in 1..3) {
                                            val type = world.getBlockAt(support.x, support.y + dy, support.z).type
                                            (type.isAir || dy == 1 && type == Material.TORCH) shouldBe true
                                        }
                                    }
                                }
                            }
                        }
                    }
                    tasks.close()
                }
            }
        }
    }
})

private fun spatialBuilding(style: String, role: String, level: Int, anchor: CivPosition): CivBuilding {
    val type = when (role) { "farmer" -> BuildingType.FARM; "lumberjack" -> BuildingType.FORESTER; else -> BuildingType.QUARRY }
    val folder = if (role == "farmer") "agriculture/horticulture" else "fundamentals"
    val blank = CivBuilding(UUID.randomUUID(), type, level, anchor, 0, "civs/minecolonies/$style/$folder/$role$level",
        CivBox(anchor, anchor), anchor.copy(y = anchor.y + 1), BuildingPhase.ACTIVE, control = anchor.copy(y = anchor.y + 1, z = anchor.z - 3))
    val box = CivsWorksiteLayouts.site(blank)!!.getAsJsonObject("schematicBounds")
    return blank.copy(bounds = CivBox(CivsWorksiteLayouts.point(blank, CivsWorksiteLayouts.vector(box["min"])),
        CivsWorksiteLayouts.point(blank, CivsWorksiteLayouts.vector(box["max"]))))
}

private fun spatialWorker(building: CivBuilding, material: String) = CivWorker(UUID.randomUUID(), "Рабочий", building.id,
    nextDeliveryAt = 1,
    items = MutableList<CivResidentItem?>(CivsEquipment.SLOTS) { null }.also { it[4] = CivResidentItem(material, 1) })

private fun spatialCity(worldId: UUID, buildings: List<CivBuilding>, worker: CivWorker): Settlement {
    val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 3, CivPosition(50, 63, 5), 0, "fixture/hall",
        CivBox(CivPosition(46, 63, 1), CivPosition(54, 70, 5)), CivPosition(50, 64, 6), BuildingPhase.ACTIVE,
        control = CivPosition(50, 64, 3))
    return Settlement(UUID.randomUUID(), UUID.randomUUID(), "Spatial", worldId, CivPosition(0, 64, 0), 80, "test", Stock(),
        buildings + hall, listOf(worker), inventory = CivInventory(CivStorageMode.VIRTUAL))
}
