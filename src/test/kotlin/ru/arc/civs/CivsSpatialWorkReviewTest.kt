package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.block.data.Ageable
import org.bukkit.inventory.ItemStack
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.LogRecord

class CivsSpatialWorkReviewTest : FunSpec({
    test("deep mine travel first reaches the authored shaft and then descends at its actual ladder") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("mine-legs").also(paper.server::addWorld)
            val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
            val city = spatialCity(world.uid, listOf(mine), spatialWorker(mine, "IRON_PICKAXE"))
            val layout = CivsWorksiteLayouts.site(mine)!!.getAsJsonObject("mine")
            val bottom = CivsWorksiteLayouts.point(mine, CivsWorksiteLayouts.vector(layout["ladderBottom"]))
            val floor = CivsWorksiteLayouts.point(mine, CivsWorksiteLayouts.vector(layout.getAsJsonArray("floors")[0].asJsonObject["landing"]))
            for (x in -2..2) for (z in -2..2) world.loadChunk(x, z)
            for (y in floor.y..65) world.getBlockAt(bottom.x, y, bottom.z).blockData =
                Bukkit.createBlockData("minecraft:ladder[facing=${layout["ladderFacing"].asString},waterlogged=false]")
            citizenMineWaypoint(city, world, Location(world, bottom.x + 4.5, 64.0, bottom.z + .5), floor) shouldBe bottom
            citizenMineWaypoint(city, world, Location(world, bottom.x + .5, bottom.y.toDouble(), bottom.z + .5), floor) shouldBe bottom.copy(y = floor.y)
            citizenMineWaypoint(city, world, Location(world, bottom.x + .5, floor.y.toDouble(), bottom.z + .5), bottom.copy(x = bottom.x + 5, y = 64)) shouldBe bottom
        }
    }

    test("a newly exposed cave opening is sealed with paid stone before deeper shaft excavation") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("mine-cave-seal").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("MineSeal")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
                val worker = spatialWorker(mine, "IRON_PICKAXE")
                val city = spatialCity(world.uid, listOf(mine), worker).copy(mineProgress = mapOf(mine.id.toString() to 2L))
                val layout = CivsWorksiteLayouts.site(mine)!!.getAsJsonObject("mine")
                val bottom = CivsWorksiteLayouts.point(mine, CivsWorksiteLayouts.vector(layout["ladderBottom"]))
                for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                for (y in bottom.y - 1..65) world.getBlockAt(bottom.x, y, bottom.z).blockData =
                    Bukkit.createBlockData("minecraft:ladder[facing=${layout["ladderFacing"].asString},waterlogged=false]")
                // Open the south side, leaving the west-facing ladder's east support intact.
                // A missing block behind an existing ladder is neither a stable native ladder
                // arrangement nor a visible placement face from inside its shaft.
                val opening = bottom.copy(z = bottom.z + 1, y = bottom.y - 1)
                world.getBlockAt(opening.x, opening.y, opening.z).type = Material.AIR
                CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                    val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                    repeat(12) { runtime.pulse(listOf(city)) }
                    val work = requireNotNull(runtime.plan(city, worker))
                    work.status shouldBe "securing-mine"
                    work.changes.single().position shouldBe opening
                    work.inputs shouldBe mapOf("COBBLESTONE" to 1L)
                    work.outputs shouldBe emptyMap()
                    work.durationMillis shouldBe 2_000L
                    (requireNotNull(work.miningStep) <= 3L) shouldBe true
                    world.getBlockAt(bottom.x, bottom.y - 2, bottom.z).type shouldBe Material.STONE
                }
                tasks.close()
            }
        }
    }

    test("all authored farm levels place irrigation without filled or empty bucket items") {
        for (style in listOf("medievaloak", "nordic", "caledonia")) for (level in 1..3) {
            failOnUnsupportedMockBukkitOperation {
                MockBukkitTestRuntime.open().use { paper ->
                    val world = CivsWorksiteWorldFixture("free-water-$style-$level").also(paper.server::addWorld)
                    val plugin = paper.createSimplePlugin("FreeIrrigation")
                    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                    val farm = spatialBuilding(style, "farmer", level, CivPosition(0, 63, 0))
                    val worker = spatialWorker(farm, "IRON_HOE")
                    val city = spatialCity(world.uid, listOf(farm), worker)
                    val irrigation = CivsWorksiteLayouts.irrigation(farm)
                    (irrigation.isNotEmpty()) shouldBe true
                    irrigation.forEach { point ->
                        for (x in point.x - 3..point.x + 3) for (z in point.z - 3..point.z + 3) {
                            world.loadChunk(x shr 4, z shr 4)
                            world.getBlockAt(x, point.y, z).type = Material.DIRT
                        }
                    }
                    CivsStorage(plugin) { emptyArray() }.use { storage ->
                        CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                            val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                            repeat(8) { runtime.pulse(listOf(city)) }
                            val work = requireNotNull(runtime.plan(city, worker))
                            work.inputs shouldBe emptyMap()
                            work.outputs shouldBe emptyMap()
                            work.changes.single().position shouldBe irrigation.first()
                            work.changes.single().after shouldBe "minecraft:water[level=0]"
                            runtime.validate(city, work)
                            val id = UUID.randomUUID()
                            storage.reserve(city, id)
                            val plan = storage.prepareWork(city, farm.id, work.storageInputs(worker), work.outputs, work.changes)
                            val after = CivsRules.finishWorldWork(city, work, 10_000).copy(inventory = plan.inventoryAfter)
                            val receipt = CivInventoryTransaction(id, city.id, city.revision, after, plan.changes,
                                worldChanges = plan.worldChanges).validated()
                            storage.seal(receipt, keepViewersOpen = true)
                            storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
                            storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
                            val placed = irrigation.first()
                            world.getBlockAt(placed.x, placed.y, placed.z).type shouldBe Material.WATER
                            CivsEquipment.plainCount(after.workers.single(), "WATER_BUCKET") shouldBe 0L
                            CivsEquipment.plainCount(after.workers.single(), "BUCKET") shouldBe 0L
                            storage.unlock(id)
                        }
                    }
                    tasks.close()
                }
            }
        }
    }

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

    test("dark oak forester requests a batch of saplings and forgets that request when production changes") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val world = CivsWorksiteWorldFixture("sapling-request", Material.DIRT).also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("ForestRequest")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                val forest = spatialBuilding("nordic", "lumberjack", 1, CivPosition(0, 63, 0)).copy(production = "DARK_OAK_LOG")
                val worker = spatialWorker(forest, "IRON_AXE")
                val city = spatialCity(world.uid, listOf(forest), worker)
                for (x in -3..3) for (z in -3..3) world.loadChunk(x, z)
                CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                    val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                    runtime.pulse(listOf(city))
                    runtime.plan(city, worker) shouldBe null
                    runtime.requirement(city, worker) shouldBe ("DARK_OAK_SAPLING" to 64L)
                    runtime.idleReason(city, worker) shouldBe "waiting-saplings"
                    val changed = city.copy(buildings = city.buildings.map { if (it.id == forest.id) it.copy(production = "OAK_LOG") else it })
                    runtime.requirement(changed, worker) shouldBe null
                    runtime.pulse(listOf(changed))
                    runtime.requirement(changed, worker) shouldBe ("OAK_SAPLING" to 64L)
                }
                tasks.close()
            }
        }
    }

    for ((production, material) in listOf("WHEAT" to Material.WHEAT, "CARROT" to Material.CARROTS,
        "POTATO" to Material.POTATOES, "BREAD" to Material.WHEAT)) {
        test("$production farmer waters and optionally fertilizes an unripe field through replayable receipts without repeated boosts or tool wear") {
            failOnUnsupportedMockBukkitOperation {
                MockBukkitTestRuntime.open().use { paper ->
                    val world = CivsWorksiteWorldFixture("care-${production.lowercase()}").also(paper.server::addWorld)
                    val plugin = paper.createSimplePlugin("CropCare")
                    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                    val farm = spatialBuilding("medievaloak", "farmer", 1, CivPosition(0, 63, 0)).copy(production = production)
                    val initial = spatialWorker(farm, "IRON_HOE")
                    var city = spatialCity(world.uid, listOf(farm), initial)
                    val plots = CivsWorksiteLayouts.field(farm)
                    for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                    plots.forEach { soil ->
                        world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=0]")
                        world.getBlockAt(soil.x, soil.y + 1, soil.z).blockData =
                            (material.createBlockData() as Ageable).also { it.age = 3 }
                    }
                    CivsWorksiteLayouts.irrigation(farm).forEach { world.getBlockAt(it.x, it.y, it.z).type = Material.WATER }
                    var locked: CivPosition? = null
                    CivsStorage(plugin) { emptyArray() }.use { storage ->
                        CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                            val runtime = CivsWorksites(plugin, access, { current, id -> storage.buildingAvailable(current, id) },
                                { _, point -> point == locked })
                            fun next(): CivWorldWork {
                                repeat(8) { runtime.pulse(listOf(city)) }
                                return requireNotNull(runtime.plan(city, city.workers.single()))
                            }
                            fun commit(work: CivWorldWork) {
                                runtime.validate(city, work)
                                val id = UUID.randomUUID()
                                storage.reserve(city, id)
                                val plan = storage.prepareWork(city, farm.id, work.storageInputs(city.workers.single()), work.outputs, work.changes)
                                val after = CivsRules.finishWorldWork(city, work, 20_000 + work.cycle * 5_000).copy(inventory = plan.inventoryAfter)
                                val receipt = CivInventoryTransaction(id, city.id, city.revision, after, plan.changes,
                                    worldChanges = plan.worldChanges).validated()
                                work.changes.forEach { cell ->
                                    world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.before
                                }
                                storage.seal(receipt, keepViewersOpen = true)
                                storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
                                storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
                                city = receipt.after
                                city.workers.single().skills shouldBe initial.skills
                                CivsEquipment.slots(city.workers.single())[4] shouldBe CivsEquipment.slots(initial)[4]
                                shouldThrow<CivsRejection> { CivsRules.finishWorldWork(city, work, 99_000) }.reason shouldBe "stale-delivery"
                                storage.unlock(id)
                                runtime.invalidate(work.workerId)
                            }
                            val watering = next()
                            watering.cropCare shouldBe true
                            watering.status shouldBe "watering"
                            watering.durationMillis shouldBe 4_000L
                            watering.inputs shouldBe emptyMap()
                            watering.outputs shouldBe emptyMap()
                            watering.equipmentAfter shouldBe null
                            val focus = requireNotNull(watering.focus)
                            val soil = focus.copy(y = focus.y - 1)
                            watering.changes.map(CivPlanBlock::position).toSet() shouldBe setOf(soil, focus)
                            commit(watering)
                            val crop = world.getBlockAt(focus.x, focus.y, focus.z)
                            (crop.blockData as Ageable).age shouldBe 4
                            world.getBlockAt(soil.x, soil.y, soil.z).blockData.asString shouldBe "minecraft:farmland[moisture=7]"
                            // Keep one target to observe repeated care at the same native stage.
                            plots.filter { it != soil }.forEach { world.getBlockAt(it.x, it.y + 1, it.z).type = Material.STONE }
                            val repeatedWatering = next()
                            repeatedWatering.focus shouldBe focus
                            repeatedWatering.changes shouldBe emptyList()
                            repeatedWatering.inputs shouldBe emptyMap()
                            repeatedWatering.outputs shouldBe emptyMap()
                            commit(repeatedWatering)
                            (crop.blockData as Ageable).age shouldBe 4

                            crop.blockData = (crop.blockData as Ageable).also { it.age = 5 }
                            val noFertilizer = next()
                            noFertilizer.status shouldBe "watering"
                            noFertilizer.inputs shouldBe emptyMap()
                            noFertilizer.changes shouldBe emptyList()
                            runtime.invalidate(initial.id)
                            val fromStorage = production == "WHEAT"
                            city = if (fromStorage) city.copy(inventory = requireNotNull(city.inventory).copy(
                                buildingMaterials = mapOf(farm.id.toString() to mapOf("BONE_MEAL" to 2L))))
                            else city.copy(workers = listOf(CivsEquipment.collectSupplies(city.workers.single(), mapOf("BONE_MEAL" to 2L))))
                            val fertilizing = next()
                            fertilizing.status shouldBe "fertilizing"
                            fertilizing.durationMillis shouldBe 4_000L
                            fertilizing.focus shouldBe focus
                            fertilizing.inputs shouldBe mapOf("BONE_MEAL" to 1L)
                            fertilizing.outputs shouldBe emptyMap()
                            fertilizing.equipmentAfter shouldBe null
                            fertilizing.storageInputs(city.workers.single()) shouldBe if (fromStorage) mapOf("BONE_MEAL" to 1L) else emptyMap()
                            commit(fertilizing)
                            (crop.blockData as Ageable).age shouldBe 6
                            if (fromStorage) storage.buildingAvailable(city, farm.id)["BONE_MEAL"] shouldBe 1L
                            else CivsEquipment.plainCount(city.workers.single(), "BONE_MEAL") shouldBe 1
                            val repeatedFertilizing = next()
                            repeatedFertilizing.status shouldBe "watering"
                            repeatedFertilizing.changes shouldBe emptyList()
                            repeatedFertilizing.inputs shouldBe emptyMap()
                            runtime.validate(city, repeatedFertilizing)
                            locked = focus
                            shouldThrow<CivsRejection> { runtime.validate(city, repeatedFertilizing) }.reason shouldBe "protected-area"
                            locked = null
                            val source = CivsWorksiteLayouts.irrigation(farm).first()
                            world.getBlockAt(source.x, source.y, source.z).type = Material.AIR
                            shouldThrow<CivsRejection> { runtime.validate(city, repeatedFertilizing) }.reason shouldBe "stale-worksite"
                            world.getBlockAt(source.x, source.y, source.z).type = Material.WATER
                            world.getBlockAt(soil.x, soil.y, soil.z).type = Material.DIRT
                            shouldThrow<CivsRejection> { runtime.validate(city, repeatedFertilizing) }.reason shouldBe "stale-worksite"
                            world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=7]")
                            crop.blockData = (crop.blockData as Ageable).also { it.age = it.maximumAge }
                            shouldThrow<CivsRejection> { runtime.validate(city, repeatedFertilizing) }.reason shouldBe "stale-worksite"
                            crop.type = Material.AIR
                            shouldThrow<CivsRejection> { runtime.validate(city, repeatedFertilizing) }.reason shouldBe "stale-worksite"
                        }
                    }
                    tasks.close()
                }
            }
        }
    }

    for (action in listOf("till", "plant", "harvest")) {
        test("farmer prioritizes $action over caring for an earlier unripe crop within its bounded scan") {
            failOnUnsupportedMockBukkitOperation {
                MockBukkitTestRuntime.open().use { paper ->
                    val world = CivsWorksiteWorldFixture("care-priority-$action").also(paper.server::addWorld)
                    val plugin = paper.createSimplePlugin("CarePriority")
                    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                    val farm = spatialBuilding("medievaloak", "farmer", 1, CivPosition(0, 63, 0)).copy(production = "WHEAT")
                    val worker = CivsEquipment.collectSupplies(spatialWorker(farm, "IRON_HOE"), mapOf("WHEAT_SEEDS" to 16L))
                    val city = spatialCity(world.uid, listOf(farm), worker)
                    val plots = CivsWorksiteLayouts.field(farm)
                    for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                    plots.forEach { soil ->
                        world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=7]")
                        world.getBlockAt(soil.x, soil.y + 1, soil.z).blockData =
                            (Material.WHEAT.createBlockData() as Ageable).also { it.age = 2 }
                    }
                    CivsWorksiteLayouts.irrigation(farm).forEach { world.getBlockAt(it.x, it.y, it.z).type = Material.WATER }
                    val soil = plots[1]
                    val crop = soil.copy(y = soil.y + 1)
                    if (action == "till") world.getBlockAt(soil.x, soil.y, soil.z).type = Material.DIRT
                    world.getBlockAt(crop.x, crop.y, crop.z).blockData = if (action == "harvest")
                        (Material.WHEAT.createBlockData() as Ageable).also { it.age = it.maximumAge }
                    else Material.AIR.createBlockData()
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                        repeat(8) { runtime.pulse(listOf(city)) }
                        val work = requireNotNull(runtime.plan(city, worker))
                        work.status shouldBe "working-field"
                        work.cropCare shouldBe false
                        work.changes.single().position shouldBe if (action == "till") soil else crop
                        if (action == "plant") work.inputs shouldBe mapOf("WHEAT_SEEDS" to 1L)
                        if (action == "harvest") work.outputs["WHEAT"] shouldBe 1L
                    }
                    tasks.close()
                }
            }
        }
    }

    for (style in listOf("medievaloak", "nordic")) {
        test("$style farmer care rotates through every assigned plot despite scanning sixteen plots at a time") {
            failOnUnsupportedMockBukkitOperation {
                MockBukkitTestRuntime.open().use { paper ->
                    val world = CivsWorksiteWorldFixture("care-rotation-$style").also(paper.server::addWorld)
                    val plugin = paper.createSimplePlugin("CareRotation")
                    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                    val farm = spatialBuilding(style, "farmer", 1, CivPosition(0, 63, 0)).copy(production = "WHEAT")
                    val worker = spatialWorker(farm, "IRON_HOE")
                    var city = spatialCity(world.uid, listOf(farm), worker)
                    val plots = CivsWorksiteLayouts.field(farm)
                    for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                    plots.forEach { soil ->
                        world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=7]")
                        world.getBlockAt(soil.x, soil.y + 1, soil.z).blockData =
                            (Material.WHEAT.createBlockData() as Ageable).also { it.age = 2 }
                    }
                    CivsWorksiteLayouts.irrigation(farm).forEach { world.getBlockAt(it.x, it.y, it.z).type = Material.WATER }
                    val visited = mutableSetOf<CivPosition>()
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                        repeat(plots.size) {
                            repeat(8) { runtime.pulse(listOf(city)) }
                            val work = requireNotNull(runtime.plan(city, city.workers.single()))
                            work.status shouldBe "watering"
                            work.changes shouldBe emptyList()
                            visited += requireNotNull(work.focus)
                            runtime.validate(city, work)
                            city = CivsRules.finishWorldWork(city, work, 20_000 + work.cycle * 5_000)
                            runtime.invalidate(worker.id)
                        }
                        visited shouldBe plots.map { it.copy(y = it.y + 1) }.toSet()
                    }
                    tasks.close()
                }
            }
        }
    }

    test("miner requests its complete preparation batch before the first excavation and uses it without per-block refills") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("mine-bootstrap").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("MineBootstrap")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
                var city = spatialCity(world.uid, listOf(mine), spatialWorker(mine, "IRON_PICKAXE", supplies = false))
                val layout = CivsWorksiteLayouts.site(mine)!!.getAsJsonObject("mine")
                val bottom = CivsWorksiteLayouts.point(mine, CivsWorksiteLayouts.vector(layout["ladderBottom"]))
                val facing = layout["ladderFacing"].asString
                for (y in bottom.y..65) world.getBlockAt(bottom.x, y, bottom.z).blockData =
                    Bukkit.createBlockData("minecraft:ladder[facing=$facing,waterlogged=false]")
                for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                val stock = mutableMapOf<String, Long>()
                CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                    val runtime = CivsWorksites(plugin, access, { _, _ -> stock }, { _, _ -> false })
                    runtime.pulse(listOf(city))
                    runtime.plan(city, city.workers.single()) shouldBe null
                    runtime.idleReason(city, city.workers.single()) shouldBe "waiting-ladder"
                    runtime.requirement(city, city.workers.single()) shouldBe ("LADDER" to 16L)
                    world.getBlockAt(bottom.x, bottom.y - 1, bottom.z).type shouldBe Material.STONE
                    city.mineProgress shouldBe null
                    city.workers.single().completedCycles shouldBe 0L
                    val moved = city.workers.single().copy(assignment = city.buildings.last().id)
                    runtime.requirement(city, moved) shouldBe null
                    val changed = city.copy(buildings = city.buildings.map { if (it.id == mine.id) it.copy(production = "STONE") else it })
                    runtime.requirement(changed, city.workers.single()) shouldBe null
                    runtime.requirement(city, city.workers.single().copy(completedCycles = 1)) shouldBe null
                    for ((material, amount) in CivsLogistics.QUARRY_SUPPLIES) {
                        city = city.copy(workers = listOf(CivsEquipment.collectSupplies(city.workers.single(), mapOf(material to amount))))
                        runtime.pulse(listOf(city))
                        if (material == "LADDER") {
                            runtime.plan(city, city.workers.single()) shouldBe null
                            runtime.requirement(city, city.workers.single()) shouldBe ("TORCH" to 8L)
                        }
                        if (material == "TORCH") {
                            runtime.plan(city, city.workers.single()) shouldBe null
                            runtime.requirement(city, city.workers.single()) shouldBe ("COBBLESTONE" to 32L)
                        }
                    }
                    val first = requireNotNull(runtime.plan(city, city.workers.single()))
                    first.inputs shouldBe emptyMap()
                    first.changes.single().after shouldBe "minecraft:air"
                    first.status shouldBe "digging-mine"
                    first.outputs shouldBe mapOf("COBBLESTONE" to 1L)
                    city = CivsRules.finishWorldWork(city, first, 20_000)
                    first.changes.forEach { cell -> world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
                        .setBlockData(Bukkit.createBlockData(cell.after), false) }
                    runtime.invalidate(first.workerId)
                    runtime.pulse(listOf(city))
                    val supplied = city.workers.single()
                    val ladderWork = requireNotNull(runtime.plan(city, city.workers.single()))
                    ladderWork.inputs shouldBe mapOf("LADDER" to 1L)
                    ladderWork.storageInputs(supplied) shouldBe emptyMap()
                    runtime.requirement(city, supplied) shouldBe null
                    city = CivsRules.finishWorldWork(city, ladderWork, 25_000)
                    ladderWork.changes.forEach { cell -> world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
                        .setBlockData(Bukkit.createBlockData(cell.after), false) }
                    CivsEquipment.plainCount(city.workers.single(), "LADDER") shouldBe 15
                    runtime.invalidate(ladderWork.workerId)
                    runtime.pulse(listOf(city))
                    requireNotNull(runtime.plan(city, city.workers.single())).changes.isNotEmpty() shouldBe true
                    runtime.requirement(city, city.workers.single()) shouldBe null
                }
                tasks.close()
            }
        }
    }

    test("quarry preparation debits only the missing carried batch through one replayable physical receipt") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("mine-pickup").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("MinePickup")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
                val worker = CivsEquipment.collectSupplies(spatialWorker(mine, "IRON_PICKAXE", supplies = false),
                    mapOf("COBBLESTONE" to 5L, "TORCH" to 2L))
                var city = spatialCity(world.uid, listOf(mine), worker).let {
                    it.copy(inventory = requireNotNull(it.inventory).copy(mode = CivStorageMode.PHYSICAL))
                }
                for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                val control = requireNotNull(mine.control)
                val container = world.getBlockAt(control.x, control.y, control.z).also { it.type = Material.BARREL }
                val barrel = (container.state as Barrel).inventory
                barrel.setItem(0, ItemStack(Material.COBBLESTONE, 40))
                barrel.setItem(1, ItemStack(Material.LADDER, 20))
                barrel.setItem(2, ItemStack(Material.TORCH, 12))
                CivsStorage(plugin) { arrayOf(container.state) }.use { storage ->
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        val runtime = CivsWorksites(plugin, access, { current, id -> storage.buildingAvailable(current, id) }, { _, _ -> false })
                        runtime.pulse(listOf(city))
                        val pickup = requireNotNull(runtime.plan(city, worker))
                        pickup.changes shouldBe emptyList()
                        pickup.outputs shouldBe emptyMap()
                        pickup.miningStep shouldBe null
                        pickup.storageInputs(worker) shouldBe mapOf("LADDER" to 16L, "TORCH" to 6L, "COBBLESTONE" to 27L)
                        citizenAtStorage(world, CitizenPoint(pickup.standing.x + .5, pickup.standing.y.toDouble(), pickup.standing.z + .5), control) shouldBe true
                        val id = UUID.randomUUID()
                        storage.reserve(city, id)
                        val plan = storage.prepareWork(city, mine.id, pickup.storageInputs(worker), pickup.outputs, pickup.changes)
                        val after = CivsRules.finishWorldWork(city, pickup, 20_000).copy(inventory = plan.inventoryAfter)
                        val receipt = CivInventoryTransaction(id, city.id, city.revision, after, plan.changes,
                            worldChanges = plan.worldChanges).validated()
                        storage.buildingAvailable(city, mine.id) shouldBe mapOf("COBBLESTONE" to 40L, "LADDER" to 20L, "TORCH" to 12L)
                        CivsEquipment.plainCount(worker, "LADDER") shouldBe 0
                        storage.seal(receipt, keepViewersOpen = true)
                        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
                        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
                        city = receipt.after
                        city.mineProgress shouldBe null
                        city.workers.single().skills shouldBe worker.skills
                        city.workers.single().completedCycles shouldBe 1L
                        CivsLogistics.QUARRY_SUPPLIES.forEach { (material, amount) ->
                            CivsEquipment.plainCount(city.workers.single(), material).toLong() shouldBe amount
                        }
                        storage.buildingAvailable(city, mine.id) shouldBe mapOf("COBBLESTONE" to 13L, "LADDER" to 4L, "TORCH" to 6L)
                        shouldThrow<CivsRejection> { CivsRules.finishWorldWork(city, pickup, 25_000) }.reason shouldBe "stale-delivery"
                    }
                }
                tasks.close()
            }
        }
    }

    test("a full resident bag blocks quarry pickup without loss and a completed mine needs no new batch") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("mine-pickup-full").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("MinePickupFull")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
                val worker = spatialWorker(mine, "IRON_PICKAXE", supplies = false).let { miner ->
                    CivsEquipment.replace(miner, CivsEquipment.slots(miner).mapIndexed { index, item ->
                        if (index >= CivsEquipment.BAG) CivResidentItem("DIRT", 64) else item
                    })
                }
                var city = spatialCity(world.uid, listOf(mine), worker)
                val stock = CivsLogistics.QUARRY_SUPPLIES.toMap()
                val messages = mutableListOf<String>()
                val handler = worksiteLogHandler(messages)
                plugin.logger.addHandler(handler)
                try {
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        val runtime = CivsWorksites(plugin, access, { _, _ -> stock }, { _, _ -> false })
                        runtime.pulse(listOf(city))
                        runtime.plan(city, worker) shouldBe null
                        runtime.idleReason(city, worker) shouldBe "resident-inventory-full"
                        messages.any { "worksite_blocked" in it && "resident-inventory-full" in it } shouldBe true
                        worker.items!!.drop(CivsEquipment.BAG).all { it == CivResidentItem("DIRT", 64) } shouldBe true
                        stock shouldBe CivsLogistics.QUARRY_SUPPLIES
                        val layout = CivsWorksiteLayouts.site(mine)!!.getAsJsonObject("mine")
                        val bottom = CivsWorksiteLayouts.vector(layout["ladderBottom"])
                        val floors = layout.getAsJsonArray("floors")
                        val steps = (bottom.y - floors.last().asJsonObject["feetY"].asInt) * 2 + floors.sumOf {
                            val cells = it.asJsonObject.getAsJsonArray("floorCells").size()
                            cells * 4 + (cells + 7) / 8
                        }
                        city = city.copy(mineProgress = mapOf(mine.id.toString() to steps.toLong()))
                        runtime.invalidate(worker.id)
                        runtime.pulse(listOf(city))
                        runtime.plan(city, worker) shouldBe null
                        runtime.idleReason(city, worker) shouldBe "mine-complete"
                        runtime.requirement(city, worker) shouldBe null
                    }
                } finally {
                    plugin.logger.removeHandler(handler)
                    tasks.close()
                }
            }
        }
    }

    test("native thin ladders are nonpassable but leave a supported resident body clear in every facing") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("native-ladder-shape").also(paper.server::addWorld)
                world.loadChunk(0, 0)
                val feet = CivPosition(0, 64, 0)
                for (facing in listOf("north", "south", "east", "west")) {
                    world.getBlockAt(0, 63, 0).type = Material.STONE
                    for (y in 64..65) world.getBlockAt(0, y, 0).blockData =
                        Bukkit.createBlockData("minecraft:ladder[facing=$facing,waterlogged=false]")
                    val block = world.getBlockAt(0, 64, 0)
                    val shape = block.collisionShape.boundingBoxes.single()
                    block.isPassable shouldBe false
                    minOf(shape.widthX, shape.widthZ) shouldBe 3.0 / 16.0
                    shape.height shouldBe 1.0
                    withClue("continuous $facing ladder") { citizenStandable(world, feet) shouldBe true }
                    world.getBlockAt(0, 64, 0).type = Material.AIR
                    withClue("supported feet below $facing ladder") { citizenStandable(world, feet) shouldBe true }
                    world.getBlockAt(0, 63, 0).type = Material.AIR
                    citizenStandable(world, feet) shouldBe false
                }
            }
        }
    }

    for (shaftMaterial in listOf(Material.AIR, Material.STONE)) {
        test("miner extends a continuous ladder into $shaftMaterial below an overhanging wall with paid support and ladder") {
            failOnUnsupportedMockBukkitOperation {
                MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                    val world = CivsWorksiteWorldFixture("mine-gap-${shaftMaterial.name.lowercase()}").also(paper.server::addWorld)
                    val plugin = paper.createSimplePlugin("MineGap")
                    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                    // This is the live wall/ladder arrangement, rotated into the schematic's
                    // local WEST facing. The unobserved lower shaft is tested as both air and stone.
                    val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 68, 0))
                    val initial = spatialWorker(mine, "IRON_PICKAXE").let { worker ->
                        CivsEquipment.replace(worker, CivsEquipment.slots(worker).toMutableList().also {
                            it[6] = CivResidentItem("COBBLESTONE", 64)
                            it[7] = CivResidentItem("LADDER", 64)
                            it[8] = CivResidentItem("TORCH", 17)
                        })
                    }
                    var city = spatialCity(world.uid, listOf(mine), initial)
                    val layout = CivsWorksiteLayouts.site(mine)!!.getAsJsonObject("mine")
                    val bottom = CivsWorksiteLayouts.point(mine, CivsWorksiteLayouts.vector(layout["ladderBottom"]))
                    val shaft = bottom.copy(y = bottom.y - 1)
                    val support = shaft.copy(x = shaft.x + 1)
                    support.y shouldBe 62
                    for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                    world.getBlockAt(support.x, support.y, support.z).type = Material.AIR
                    for (y in support.y + 1..67) world.getBlockAt(support.x, y, support.z).type = Material.COBBLESTONE
                    for (y in 63..65) world.getBlockAt(bottom.x, y, bottom.z).blockData =
                        Bukkit.createBlockData("minecraft:ladder[facing=west,waterlogged=false]")
                    for (y in 60..62) world.getBlockAt(shaft.x, y, shaft.z).type = shaftMaterial
                    for (dz in listOf(-1, 1)) {
                        world.getBlockAt(support.x, 63, support.z + dz).type = Material.DIRT
                        world.getBlockAt(support.x, 64, support.z + dz).type = Material.COBBLESTONE
                        world.getBlockAt(support.x, 65, support.z + dz).type = Material.AIR
                    }
                    world.getBlockAt(support.x + 1, 63, support.z).type = Material.AIR
                    for (y in 64..65) world.getBlockAt(support.x + 1, y, support.z).type = Material.STONE
                    val eye = Location(world, bottom.x + .5, bottom.y + 1.62, bottom.z + .5)
                    citizenCanReachBlockPoint(world, eye, support, Location(world, support.x + .5, support.y + .5, support.z + .5)) shouldBe false
                    var shaftLocked = false
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, point -> shaftLocked && point == shaft })
                        fun commit(work: CivWorldWork) {
                            runtime.validate(city, work)
                            work.durationMillis shouldBe 2_000
                            work.storageInputs(city.workers.single()) shouldBe emptyMap()
                            city = CivsRules.finishWorldWork(city, work, 20_000 + work.cycle * 5_000)
                            work.changes.forEach { cell -> world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
                                .setBlockData(Bukkit.createBlockData(cell.after), false) }
                            runtime.invalidate(initial.id)
                        }
                        if (shaftMaterial == Material.STONE) {
                            world.getBlockAt(shaft.x, shaft.y, shaft.z).type = Material.BEDROCK
                            runtime.pulse(listOf(city))
                            runtime.plan(city, city.workers.single()) shouldBe null
                            runtime.idleReason(city, city.workers.single()) shouldBe "worksite-unbreakable"
                            world.getBlockAt(shaft.x, shaft.y, shaft.z).type shouldBe Material.BEDROCK
                            world.getBlockAt(shaft.x, shaft.y, shaft.z).type = Material.STONE
                            shaftLocked = true
                            runtime.pulse(listOf(city))
                            runtime.plan(city, city.workers.single()) shouldBe null
                            runtime.idleReason(city, city.workers.single()) shouldBe "worksite-protected"
                            shaftLocked = false
                            runtime.pulse(listOf(city))
                            val excavation = requireNotNull(runtime.plan(city, city.workers.single()))
                            excavation.changes.single().position shouldBe shaft
                            excavation.changes.single().after shouldBe "minecraft:air"
                            excavation.inputs shouldBe emptyMap()
                            excavation.outputs shouldBe mapOf("COBBLESTONE" to 1L)
                            excavation.miningStep shouldBe 0L
                            commit(excavation)
                            CivsEquipment.plainCount(city.workers.single(), "COBBLESTONE") shouldBe 64
                            CivsEquipment.plainCount(city.workers.single(), "LADDER") shouldBe 64
                        }

                        val visible = requireNotNull(citizenReachableBlockPoint(world, eye, support))
                        visible.y shouldBe support.y + .001
                        (visible.distance(eye) <= CITIZEN_BLOCK_REACH) shouldBe true
                        runtime.pulse(listOf(city))
                        val wall = requireNotNull(runtime.plan(city, city.workers.single()))
                        wall.standing shouldBe if (shaftMaterial == Material.STONE) shaft else bottom
                        citizenCanReachBlock(world, Location(world, wall.standing.x + .5, wall.standing.y + 1.62, wall.standing.z + .5), support) shouldBe true
                        wall.focus shouldBe support
                        wall.changes.single().position shouldBe support
                        wall.changes.single().after shouldBe "minecraft:cobblestone"
                        wall.inputs shouldBe mapOf("COBBLESTONE" to 1L)
                        wall.outputs shouldBe emptyMap()
                        wall.status shouldBe "securing-mine"
                        // The shell repair precedes the authored cursor. The subsequent scan
                        // skips its now-sound support before committing the ladder at step 2.
                        wall.miningStep shouldBe 0L
                        commit(wall)
                        CivsEquipment.plainCount(city.workers.single(), "COBBLESTONE") shouldBe 63
                        CivsEquipment.plainCount(city.workers.single(), "LADDER") shouldBe 64
                        CivsEquipment.plainCount(city.workers.single(), "TORCH") shouldBe 17

                        runtime.pulse(listOf(city))
                        val ladder = requireNotNull(runtime.plan(city, city.workers.single()))
                        ladder.changes.single().position shouldBe shaft
                        ladder.changes.single().after shouldBe "minecraft:ladder[facing=west,waterlogged=false]"
                        ladder.inputs shouldBe mapOf("LADDER" to 1L)
                        ladder.outputs shouldBe emptyMap()
                        ladder.miningStep shouldBe 2L
                        commit(ladder)
                        CivsEquipment.plainCount(city.workers.single(), "COBBLESTONE") shouldBe 63
                        CivsEquipment.plainCount(city.workers.single(), "LADDER") shouldBe 63
                        CivsEquipment.plainCount(city.workers.single(), "TORCH") shouldBe 17
                        for (y in 63..65) world.getBlockAt(bottom.x, y, bottom.z).type shouldBe Material.LADDER
                        for (y in 63..67) world.getBlockAt(support.x, y, support.z).type shouldBe Material.COBBLESTONE
                    }
                    tasks.close()
                }
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

    test("a miner reaches the real block from two cells away when every adjacent standing cell is unavailable") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("mine-reach").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("MineReach")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
                val worker = spatialWorker(mine, "IRON_PICKAXE")
                val city = spatialCity(world.uid, listOf(mine), worker)
                val layout = CivsWorksiteLayouts.site(mine)!!.getAsJsonObject("mine")
                val target = CivsWorksiteLayouts.point(mine, CivsWorksiteLayouts.vector(layout["firstSubgroundPoint"]))
                val standing = target.copy(x = target.x - 2)
                for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                for (y in target.y..target.y + 2) world.getBlockAt(standing.x, y, standing.z).type = Material.AIR
                // A gap permits the ray, but offers no floor for any of the old adjacent probes.
                for (y in target.y - 4..target.y + 2) world.getBlockAt(target.x - 1, y, target.z).type = Material.AIR
                for ((dx, dz) in listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)) for (dy in listOf(0, 1, -1, -2)) {
                    citizenStandable(world, target.copy(x = target.x + dx, y = target.y + 1 + dy, z = target.z + dz)) shouldBe false
                }
                CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                    val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                    runtime.pulse(listOf(city))
                    val work = requireNotNull(runtime.plan(city, worker))
                    work.standing shouldBe standing
                    work.focus shouldBe target
                    citizenCanReachBlock(world, Location(world, standing.x + .5, standing.y + 1.62, standing.z + .5), target) shouldBe true
                    work.changes.single().position shouldBe target
                    work.changes.single().after shouldBe "minecraft:air"
                    work.durationMillis shouldBe 2_000
                    work.inputs shouldBe emptyMap()
                    work.outputs shouldBe mapOf("COBBLESTONE" to 1L)
                    world.getBlockAt(target.x, target.y, target.z).type shouldBe Material.STONE
                    worker.completedCycles shouldBe 0L

                    // Expanding the search radius must not turn a neighbour's footprint into a work position.
                    val protected = city.copy(buildings = city.buildings.map { building ->
                        if (building.type == BuildingType.TOWN_HALL) building.copy(bounds = CivBox(standing, standing)) else building
                    })
                    runtime.invalidate(worker.id)
                    repeat(4) { runtime.pulse(listOf(protected)) }
                    runtime.plan(protected, worker) shouldBe null
                }
                tasks.close()
            }
        }
    }

    test("work position search continues within its shared ray budget and reports an actual blocked ray only after exhaustion") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("mine-occluded").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("MineOccluded")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
                val worker = spatialWorker(mine, "IRON_PICKAXE")
                val city = spatialCity(world.uid, listOf(mine), worker)
                val layout = CivsWorksiteLayouts.site(mine)!!.getAsJsonObject("mine")
                val target = CivsWorksiteLayouts.point(mine, CivsWorksiteLayouts.vector(layout["firstSubgroundPoint"]))
                for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                // Supported outer positions surround a solid, three-block-high enclosure.
                for (dx in -2..2) for (dz in -2..2) if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz)) == 2) {
                    for (dy in 0..2) world.getBlockAt(target.x + dx, target.y + dy, target.z + dz).type = Material.AIR
                }
                val messages = mutableListOf<String>()
                val handler = worksiteLogHandler(messages)
                plugin.logger.addHandler(handler)
                try {
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                        runtime.pulse(listOf(city))
                        runtime.plan(city, worker) shouldBe null
                        runtime.idleReason(city, worker) shouldBe "searching-work"
                        (world.rayTraces in 1..8) shouldBe true
                        messages.none { "worksite_blocked" in it } shouldBe true
                        var pulses = 1
                        while (runtime.idleReason(city, worker) == "searching-work" && pulses++ < 32) {
                            val before = world.rayTraces
                            runtime.pulse(listOf(city))
                            (world.rayTraces - before <= 8) shouldBe true
                        }
                        runtime.plan(city, worker) shouldBe null
                        runtime.idleReason(city, worker) shouldBe "worksite-no-access"
                        val blocked = messages.single { "worksite_blocked" in it }
                        ("line-of-sight-blocked" in blocked) shouldBe true
                        (target.toString() in blocked) shouldBe true
                        ("STONE" in blocked) shouldBe true
                        repeat(4) { runtime.pulse(listOf(city)) }
                        messages.count { "worksite_blocked" in it } shouldBe 1

                        for (dy in 0..2) world.getBlockAt(target.x - 1, target.y + dy, target.z).type = Material.AIR
                        runtime.invalidate(worker.id)
                        runtime.pulse(listOf(city))
                        requireNotNull(runtime.plan(city, worker)).focus shouldBe target
                        messages.count { "worksite_recovered" in it } shouldBe 1
                        runtime.pulse(listOf(city))
                        messages.count { "worksite_recovered" in it } shouldBe 1
                    }
                } finally {
                    plugin.logger.removeHandler(handler)
                    tasks.close()
                }
            }
        }
    }

    test("a miner reports liquid unbreakable and locked target causes without mutating the world") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("mine-causes").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("MineCauses")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
                val worker = spatialWorker(mine, "IRON_PICKAXE")
                val city = spatialCity(world.uid, listOf(mine), worker)
                val layout = CivsWorksiteLayouts.site(mine)!!.getAsJsonObject("mine")
                val target = CivsWorksiteLayouts.point(mine, CivsWorksiteLayouts.vector(layout["firstSubgroundPoint"]))
                for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                val block = world.getBlockAt(target.x, target.y, target.z)
                block.type = Material.WATER
                var locked = false
                val messages = mutableListOf<String>()
                val handler = worksiteLogHandler(messages)
                plugin.logger.addHandler(handler)
                try {
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, point -> locked && point == target })
                        repeat(3) { runtime.pulse(listOf(city)) }
                        runtime.idleReason(city, worker) shouldBe "worksite-liquid"
                        messages.count { "worksite_blocked" in it } shouldBe 1
                        messages.any { "liquid" in it && "WATER" in it && target.toString() in it } shouldBe true
                        block.type shouldBe Material.WATER
                        paper.performTicks(100)
                        block.type = Material.BEDROCK
                        runtime.pulse(listOf(city))
                        runtime.idleReason(city, worker) shouldBe "worksite-unbreakable"
                        messages.any { "unbreakable" in it && "BEDROCK" in it } shouldBe true
                        paper.performTicks(100)
                        locked = true
                        runtime.pulse(listOf(city))
                        runtime.idleReason(city, worker) shouldBe "worksite-protected"
                        messages.any { "world-operation-locked" in it && target.toString() in it } shouldBe true
                        runtime.plan(city, worker) shouldBe null
                        world.rayTraces shouldBe 0
                        block.type shouldBe Material.BEDROCK
                        worker.completedCycles shouldBe 0L
                    }
                } finally {
                    plugin.logger.removeHandler(handler)
                    tasks.close()
                }
            }
        }
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
                    val control = requireNotNull(mine.control)
                    world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
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
                                work.storageInputs(worker).forEach { (key, amount) ->
                                    counts[key] = counts.getValue(key) - amount
                                    (counts.getValue(key) >= 0) shouldBe true
                                }
                                work.outputs.forEach { (key, amount) -> counts[key] = counts.getOrDefault(key, 0) + amount }
                                work.changes.forEach { cell -> world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
                                    .setBlockData(Bukkit.createBlockData(cell.after), false) }
                                city = CivsRules.finishWorldWork(city, work, 20_000 + actions * 5_000L)
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

private fun spatialWorker(building: CivBuilding, material: String, supplies: Boolean = true) = CivWorker(UUID.randomUUID(), "Рабочий", building.id,
    nextDeliveryAt = 1,
    items = MutableList<CivResidentItem?>(CivsEquipment.SLOTS) { null }.also {
        it[4] = CivResidentItem(material, 1)
        if (supplies && building.type == BuildingType.QUARRY) CivsLogistics.QUARRY_SUPPLIES.entries.forEachIndexed { index, (supply, amount) ->
            it[CivsEquipment.BAG + index] = CivResidentItem(supply, amount.toInt())
        }
    })

private fun spatialCity(worldId: UUID, buildings: List<CivBuilding>, worker: CivWorker): Settlement {
    val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 3, CivPosition(50, 63, 5), 0, "fixture/hall",
        CivBox(CivPosition(46, 63, 1), CivPosition(54, 70, 5)), CivPosition(50, 64, 6), BuildingPhase.ACTIVE,
        control = CivPosition(50, 64, 3))
    return Settlement(UUID.randomUUID(), UUID.randomUUID(), "Spatial", worldId, CivPosition(0, 64, 0), 80, "test", Stock(),
        buildings + hall, listOf(worker), inventory = CivInventory(CivStorageMode.VIRTUAL))
}

private fun worksiteLogHandler(messages: MutableList<String>): Handler = object : Handler() {
    override fun publish(record: LogRecord) { messages += record.message }
    override fun flush() = Unit
    override fun close() = Unit
}
