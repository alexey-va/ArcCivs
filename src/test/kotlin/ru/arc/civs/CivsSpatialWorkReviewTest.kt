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
import java.util.logging.Handler
import java.util.logging.LogRecord

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

    test("miner excavates native stone before requesting its next ladder and never waits for crop growth") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("mine-bootstrap").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("MineBootstrap")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
                var city = spatialCity(world.uid, listOf(mine), spatialWorker(mine, "IRON_PICKAXE"))
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
                    val first = requireNotNull(runtime.plan(city, city.workers.single()))
                    first.inputs shouldBe emptyMap()
                    first.changes.single().after shouldBe "minecraft:air"
                    first.status shouldBe "digging-mine"
                    first.outputs["COBBLESTONE"] shouldBe 1L
                    first.changes.forEach { cell -> world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
                        .setBlockData(Bukkit.createBlockData(cell.after), false) }
                    val worker = city.workers.single()
                    city = city.copy(workers = listOf(worker.copy(completedCycles = 1, items = first.equipmentAfter)),
                        mineProgress = mapOf(mine.id.toString() to requireNotNull(first.miningStep)))
                    runtime.invalidate(worker.id)
                    runtime.pulse(listOf(city))
                    runtime.plan(city, city.workers.single()) shouldBe null
                    runtime.idleReason(city, city.workers.single()) shouldBe "waiting-ladder"
                    runtime.requirement(city, city.workers.single()) shouldBe ("LADDER" to 64L)
                    val moved = city.workers.single().copy(assignment = city.buildings.last().id)
                    runtime.requirement(city, moved) shouldBe null
                    val changed = city.copy(buildings = city.buildings.map { if (it.id == mine.id) it.copy(production = "STONE") else it })
                    runtime.requirement(changed, city.workers.single()) shouldBe null
                    runtime.requirement(city, city.workers.single().copy(completedCycles = 2)) shouldBe null
                    val supplied = city.workers.single().let { miner ->
                        CivsEquipment.replace(miner, CivsEquipment.slots(miner).toMutableList().also { it[6] = CivResidentItem("LADDER", 64) })
                    }
                    city = city.copy(workers = listOf(supplied))
                    runtime.pulse(listOf(city))
                    val ladderWork = requireNotNull(runtime.plan(city, city.workers.single()))
                    ladderWork.inputs shouldBe mapOf("LADDER" to 1L)
                    ladderWork.storageInputs(supplied) shouldBe emptyMap()
                    CivsEquipment.plainCount(CivsRules.finishWorldWork(city, ladderWork, 20_000).workers.single(), "LADDER") shouldBe 63
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
                    work.durationMillis shouldBe 4_000
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
                // Sixteen supported outer positions surround a solid, three-block-high cross.
                for (dx in -2..2) for (dz in -2..2) if (kotlin.math.abs(dx) + kotlin.math.abs(dz) > 1) {
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
                        while (runtime.idleReason(city, worker) == "searching-work" && pulses++ < 8) {
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

private fun worksiteLogHandler(messages: MutableList<String>): Handler = object : Handler() {
    override fun publish(record: LogRecord) { messages += record.message }
    override fun flush() = Unit
    override fun close() = Unit
}
