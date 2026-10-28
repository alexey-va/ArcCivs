package ru.arc.civs.scenarios

import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import ru.arc.civs.*
import java.util.concurrent.CompletionException

class Auto2WorksitesScenarioTest : FunSpec({
    test("Auto2 shaped forest crash recovery quarry work and paid road preserve one food and neighbouring work zones") {
        val recipe = auto2Recipe()
        val capture = recipe.capture
        SettlementScenario.run("auto2-worksite-receipts", AUTO2_CENTER, capture, { world -> auto2Town(world, recipe) }) {
            val roofAndPaths = snapshot(guardedCells(town))
            val quarryBefore = snapshot(capture.states.keys)
            val forest = nextWork("forester")
            forest.status shouldBe "felling-tree"
            forest.forestry!!.job.thinning shouldBe true
            val treeBefore = snapshot(forest.changes.map { it.position })
            val before = city

            val interrupted = observedCompletion(forest)
            pendingIo shouldBe 1
            receipts() shouldBe emptyList()
            unchanged(treeBefore)
            savedCity() shouldBe before
            persistOne() shouldBe true
            val receipt = receipts().single().validated()
            receipt.forestry shouldBe null
            receipt.worldChanges shouldBe forest.changes
            receipt.after.stock shouldBe Stock()
            CivsEquipment.plainCount(receipt.after.workers.single { it.id == forest.workerId }, "OAK_LOG") shouldBe 4
            unchanged(treeBefore)
            savedCity() shouldBe before

            // Stop after the genuine journal write and before its queued native callback.
            restart()
            interrupted.isCompletedExceptionally shouldBe true
            city shouldBe receipt.after
            savedCity() shouldBe receipt.after
            forest.changes.forEach { state(it.position) shouldBe it.after }
            unchanged(roofAndPaths)
            unchanged(quarryBefore)
            val recovered = city
            restart()
            city shouldBe recovered
            receipts() shouldBe emptyList()

            val quarry = nextWork("miner")
            quarry.autonomousVisual shouldBe false
            quarry.miningPlanVersion shouldBe CivsMinePlan.VERSION
            val beforeQuarry = city
            val pendingQuarry = observedCompletion(quarry)
            pendingIo shouldBe 1
            receipts() shouldBe emptyList()
            savedCity() shouldBe beforeQuarry
            quarry.changes.forEach { state(it.position) shouldBe it.before }
            val quarried = await(pendingQuarry)
            quarry.changes.forEach { state(it.position) shouldBe it.after }
            quarried.stock shouldBe beforeQuarry.stock
            quarried.workers.filterNot { it.id == quarry.workerId } shouldBe beforeQuarry.workers.filterNot { it.id == quarry.workerId }
            quarried.workers.single { it.id == quarry.workerId }.completedCycles shouldBe
                beforeQuarry.workers.single { it.id == quarry.workerId }.completedCycles + 1
            quarried.buildings shouldBe beforeQuarry.buildings
            quarried.revision shouldBe beforeQuarry.revision + 1
            quarried.minePlans!!.getValue(quarry.buildingId.toString()).v5Proof shouldBe
                CivMineV5Proof(AUTO2_PAID_MINE_PREFIX)
            unchanged(roofAndPaths)

            val road = nextWork("builder")
            road.status shouldBe "repairing-road"
            road.upkeep!!.source shouldBe town.buildings.getValue("road")
            val builderBefore = city.workers.single { it.id == road.workerId }
            val beforeRoad = city
            val repaired = complete(road)
            repaired.stock shouldBe Stock()
            CivsEquipment.plainCount(repaired.workers.single { it.id == road.workerId }, "COBBLESTONE") shouldBe
                CivsEquipment.plainCount(builderBefore, "COBBLESTONE") - 1
            val foodControl = requireNotNull(town.buildings.getValue("hall").control)
            val food = world.getBlockAt(foodControl.x, foodControl.y, foodControl.z).state as org.bukkit.block.Container
            food.inventory.getItem(0)!!.amount shouldBe 1
            repaired.workers.single { it.id == road.workerId }.skills shouldBe builderBefore.skills
            state(town.buildings.getValue("road").anchor) shouldBe "minecraft:dirt_path"
            receipts() shouldBe emptyList()
            unchanged(roofAndPaths)
            repaired.buildings shouldBe before.buildings

            val duplicate = observedCompletion(road, builderBefore.nextDeliveryAt)
            shouldThrow<CompletionException> { await(duplicate) }.let {
                CivsDiagnostics.rejection(it)!!.reason shouldBe "stale-delivery"
            }
            city shouldBe repaired
            savedCity() shouldBe repaired
        }
    }

    test("a Lands outage between forest planning and completion refuses native and economic writes") {
        val recipe = auto2Recipe()
        SettlementScenario.run("auto2-lands-fence", AUTO2_CENTER, recipe.capture, { world -> auto2Town(world, recipe) }) {
            val work = nextWork("forester")
            val cells = snapshot(work.changes.map { it.position } + guardedCells(town))
            val before = city
            val lands = paper.createSimplePlugin("Lands")
            paper.server.pluginManager.disablePlugin(lands)

            shouldThrow<CompletionException> { await(observedCompletion(work)) }.let {
                CivsDiagnostics.rejection(it)!!.reason shouldBe "protected-area"
            }
            unchanged(cells)
            city shouldBe before
            savedCity() shouldBe before
            receipts() shouldBe emptyList()
            pendingIo shouldBe 0
        }
    }
})

private val AUTO2_CENTER = CivPosition(-1868, 70, -2730)
private const val AUTO2_PAID_MINE_VERSION = 5
private const val AUTO2_PAID_MINE_PREFIX = 1818L

private data class Auto2Recipe(val capture: RecordedNativeMap, val sourceHashes: Map<String, String>)

private fun auto2Recipe(): Auto2Recipe {
    val fixture = JsonParser.parseString(requireNotNull(Auto2WorksitesScenarioTest::class.java
        .getResource("/scenarios/auto2-worksites.json")).readText()).asJsonObject
    fixture["version"].asInt shouldBe 1
    val native = fixture.getAsJsonObject("nativeMap")
    val capture = RecordedNativeMap(native["resource"].asString, native["sha256"].asString)
    capture.states.size shouldBe native["cells"].asInt
    val blueprints = fixture.getAsJsonObject("blueprints").entrySet().associate { (_, value) ->
        val blueprint = value.asJsonObject
        blueprint["id"].asString to blueprint["sourceSha256"].asString
    }
    return Auto2Recipe(capture, blueprints)
}

private fun ScenarioTown.auto2Town(world: CivsWorksiteWorldFixture, recipe: Auto2Recipe) {
    val hall = support("hall", BuildingType.TOWN_HALL, CivPosition(-1880, 63, -2750))
    val hut = support("hut", BuildingType.BUILDER_HUT, CivPosition(-1890, 63, -2744))
    val house = support("house", BuildingType.HOUSE, CivPosition(-1883, 63, -2685))
    val mill = blueprint("mill", "lumberjack", CivPosition(-1880, 63, -2704), 180)
    val mine = blueprint("mine", "miner", CivPosition(-1844, 70, -2696), 180)
    recipe.sourceHashes.forEach { (id, hash) ->
        val building = buildings.values.single { it.schematicId == id }
        CivsWorksiteLayouts.site(building)!!["schematicSha256"].asString shouldBe hash
    }
    resident("forester", mill)
    resident("miner", mine)
    resident("builder", hut)
    road("road", CivPosition(-1872, 63, -2708))
    // The 891 observed cells come from the historical V5 mine. The controlled paid
    // continuation must use that same order; V6 proves/deepens its own spine at runtime.
    minePlans = mapOf(mine.id.toString() to CivMinePlanState(AUTO2_PAID_MINE_VERSION, AUTO2_PAID_MINE_PREFIX))
    for (x in ((center.x - 128) shr 4)..((center.x + 128) shr 4))
        for (z in ((center.z - 128) shr 4)..((center.z + 128) shr 4)) world.loadChunk(x, z)
    seedMineNaturalCover(world, mine)
    val plan = requireNotNull(CivsMinePlan.forBuilding(mine, AUTO2_PAID_MINE_VERSION))
    for (index in 0 until AUTO2_PAID_MINE_PREFIX.toInt()) {
        val point = CivsWorksiteLayouts.point(mine, plan.steps[index].point)
        world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(plan.expectedAfter(index, mine.rotation))
    }
    // The source schematic supplies the upper ladder; it is outside the underground plan journal.
    val bottom = CivsWorksiteLayouts.point(mine, plan.bottom)
    val facing = listOf("north", "east", "south", "west").let { it[(it.indexOf(plan.facing) + 2) % 4] }
    for (dy in 0..4) {
        world.getBlockAt(bottom.x, bottom.y + dy, bottom.z).blockData = Bukkit.createBlockData(
            if (dy <= 2) "minecraft:ladder[facing=$facing,waterlogged=false]" else "minecraft:air")
        val backing = CivsWorksiteLayouts.point(mine, plan.backing).copy(y = bottom.y + dy)
        world.getBlockAt(backing.x, backing.y, backing.z).type = Material.COBBLESTONE
    }
    recipe.capture.seed(world)
    recipe.capture.states.forEach { (point, expected) -> world.getBlockAt(point.x, point.y, point.z).blockData.asString shouldBe expected }
    for (x in -1900..-1898) for (y in 64..67)
        world.getBlockAt(x, y, -2706).blockData = Bukkit.createBlockData("minecraft:oak_log[axis=y]")
    for (x in -1901..-1897) world.getBlockAt(x, 68, -2706).blockData =
        Bukkit.createBlockData("minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]")
    // Crafted timber in another building and existing path columns are not forestry inputs.
    world.getBlockAt(house.anchor.x + 1, house.anchor.y + 4, house.anchor.z + 1).type = Material.OAK_LOG
    world.getBlockAt(-1871, 63, -2708).type = Material.DIRT_PATH
    world.getBlockAt(-1870, 63, -2708).type = Material.COBBLESTONE
    // Distinguish supporting fixtures from the real worksite profiles in failure dumps.
    hall.schematicId shouldBe "scenario/hall"
    buildings["mill"] = mill.copy(control = spatialBuilding("caledonia", "lumberjack", 1, mill.anchor, 180).control)
    buildings["mine"] = mine.copy(control = spatialBuilding("caledonia", "miner", 1, mine.anchor, 180).control)
    for (name in listOf("hall", "hut")) buildings[name] = buildings.getValue(name).let {
        it.copy(control = it.anchor.copy(x = it.anchor.x + 1, y = it.anchor.y + 1, z = it.anchor.z + 1))
    }
    for (name in listOf("hall", "hut", "mill", "mine")) requireNotNull(buildings.getValue(name).control).let {
        world.getBlockAt(it.x, it.y, it.z).type = Material.BARREL
    }
    val foodControl = requireNotNull(buildings.getValue("hall").control)
    (world.getBlockAt(foodControl.x, foodControl.y, foodControl.z).state as org.bukkit.block.Container).inventory
        .setItem(0, org.bukkit.inventory.ItemStack(Material.BREAD))
    sourceCity = build().copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
        workers = workers.map { (role, worker) -> worker.copy(items = List(CivsEquipment.SLOTS) { slot -> when {
            slot == 4 && role == "forester" -> CivResidentItem("IRON_AXE", 1)
            slot == 4 && role == "miner" -> CivResidentItem("IRON_PICKAXE", 1)
            slot == CivsEquipment.BAG && role == "forester" -> CivResidentItem("OAK_SAPLING", 4)
            slot == CivsEquipment.BAG && role in listOf("miner", "builder") -> CivResidentItem("COBBLESTONE", 64)
            slot == CivsEquipment.BAG + 1 && role == "miner" -> CivResidentItem("LADDER", 64)
            slot == CivsEquipment.BAG + 2 && role == "miner" -> CivResidentItem("TORCH", 64)
            else -> null
        } }) })
}

private fun guardedCells(town: ScenarioTown): List<CivPosition> = listOf(
    town.buildings.getValue("house").anchor.let { it.copy(x = it.x + 1, y = it.y + 4, z = it.z + 1) },
    CivPosition(-1871, 63, -2708), CivPosition(-1870, 63, -2708),
)
