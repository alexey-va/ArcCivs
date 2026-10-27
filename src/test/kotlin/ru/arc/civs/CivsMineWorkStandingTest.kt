package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.Ageable
import org.bukkit.block.structure.StructureRotation
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID
import java.nio.file.Files
import java.util.concurrent.Executor

class CivsMineWorkStandingTest : FunSpec({
    test("mine standing space contains its shaft and galleries but never the reported outside cave or paid shell") {
        val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(-85, 68, -40), 270)
        val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
        CivsMinePlan.workStandingAllowed(mine, CivPosition(-96, 60, -51)) shouldBe false
        CivsMinePlan.workStandingAllowed(mine, CivPosition(-96, 62, -49)) shouldBe false
        CivsMinePlan.workStandingAllowed(mine, CivPosition(-93, 63, -47)) shouldBe true
        CivsMinePlan.workStandingAllowed(mine, CivPosition(-93, 61, -47)) shouldBe true
        citizenMineWorkRouteAllowed(mine, CitizenPoint(-95.5, 60.0, -50.5), plan.steps.size.toLong()) shouldBe false
        citizenMineWorkRouteAllowed(mine, CitizenPoint(-92.5, 61.0, -46.5), plan.steps.size.toLong()) shouldBe true
        citizenMineWorkRouteAllowed(mine, CitizenPoint(-85.5, 68.0, -40.5), 0) shouldBe true
        plan.containsWorkStanding(plan.backing) shouldBe false
        for (rotation in listOf(0, 90, 180, 270)) {
            val rotated = mine.copy(rotation = rotation)
            val support = plan.floors.first().cells.first { (it.x to it.z) !in plan.shaftColumns }
            CivsMinePlan.workStandingAllowed(rotated, CivsWorksiteLayouts.point(rotated, support)) shouldBe false
            CivsMinePlan.workStandingAllowed(rotated, CivsWorksiteLayouts.point(rotated, support.copy(y = support.y + 1))) shouldBe true
            CivsMinePlan.workStandingAllowed(rotated, CivsWorksiteLayouts.point(rotated, support.copy(y = support.y + 3))) shouldBe false
            CivsMinePlan.workStandingAllowed(rotated, CivsWorksiteLayouts.point(rotated, requireNotNull(plan.generatorStanding))) shouldBe true
        }
    }

    test("the rotated current entrance pays its missing footing from a completed inside stance") {
        withReportedMine { _, world, mine, city, worker, runtime ->
            val work = nextMineWork(runtime, city, worker)
            val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
            val firstFloor = geometry.steps.first { it.after == "minecraft:cobblestone" && !it.shaftSupport }
            work.focus shouldBe CivsWorksiteLayouts.point(mine, firstFloor.point)
            work.status shouldBe "digging-mine"
            work.inputs shouldBe mapOf("COBBLESTONE" to 1L)
            work.miningStep shouldBe city.minePlans!!.getValue(mine.id.toString()).nextStep + 1
            geometry.builtStanding(CivsMinePlan.localPoint(mine, work.standing),
                requireNotNull(work.miningAccessStep), work.miningAccessSections.orEmpty()) shouldBe true
            (work.standing != CivPosition(-96, 60, -51)) shouldBe true
            citizenStandable(world, work.standing) shouldBe true
            runtime.validate(city, work)
            // Selecting a stance cannot repair terrain or spend the worker's supplies.
            world.getBlockAt(-96, 62, -49).type shouldBe Material.AIR
            CivsEquipment.plainCount(worker, "COBBLESTONE") shouldBe 32L
        }
    }

    test("a failed inside stance survives elapsed time while alternatives of the same action are still being tried") {
        withReportedMine { advanceTicks, _, mine, city, worker, runtime ->
            val first = nextMineWork(runtime, city, worker)
            runtime.rejectStanding(city, worker, first) shouldBe true
            runtime.rejectStanding(city, worker, first) shouldBe false
            runtime.invalidate(worker.id) // Ordinary cache invalidation must preserve the failed stance.
            val second = nextMineWork(runtime, city, worker)
            (second.standing != first.standing) shouldBe true
            second.focus shouldBe first.focus
            second.miningStep shouldBe first.miningStep
            second.changes shouldBe first.changes
            second.inputs shouldBe first.inputs
            requireNotNull(CivsMinePlan.forBuilding(mine)).builtStanding(CivsMinePlan.localPoint(mine, second.standing),
                requireNotNull(second.miningAccessStep), second.miningAccessSections.orEmpty()) shouldBe true
            runtime.validate(city, second)
            advanceTicks(1_201)
            runtime.invalidate(worker.id)
            (nextMineWork(runtime, city, worker).standing != first.standing) shouldBe true
            CivsEquipment.plainCount(worker, "COBBLESTONE") shouldBe 32L
        }
    }

    test("an existing reachable miner stance wins without admitting a resident's outside-cave position") {
        withReportedMine(nanoTime = { 0L }) { _, world, mine, city, worker, runtime ->
            val first = nextMineWork(runtime, city, worker)
            runtime.rejectStanding(city, worker, first) shouldBe true
            val alternate = nextMineWork(runtime, city, worker)
            (alternate.standing != first.standing) shouldBe true
            alternate.focus shouldBe first.focus
            runtime.pulse(emptyList())
            val standing = alternate.standing
            runtime.residentFeet = { Location(world, standing.x + .5, standing.y.toDouble(), standing.z + .5) }
            val current = nextMineWork(runtime, city, worker)
            current.standing shouldBe standing
            current.changes shouldBe first.changes
            runtime.validate(city, current)

            runtime.pulse(emptyList())
            val cave = CivPosition(-96, 60, -51)
            runtime.residentFeet = { Location(world, cave.x + .5, cave.y.toDouble(), cave.z + .5) }
            val inside = nextMineWork(runtime, city, worker)
            (inside.standing != cave) shouldBe true
            CivsMinePlan.workStandingAllowed(mine, inside.standing) shouldBe true
            runtime.validate(city, inside)
            CivsEquipment.plainCount(worker, "COBBLESTONE") shouldBe 32L
        }
    }

    for (outside in listOf(false, true)) test("remote miner recovery ${if (outside) "rejects the outside cave" else "retains the exact paid cut inside the built mine"}") {
        withReportedMine(nanoTime = { 0L }) { _, world, mine, city, worker, runtime ->
            val first = nextMineWork(runtime, city, worker)
            val feet = if (outside) CivPosition(-96, 60, -51) else first.standing
            runtime.residentFeet = { Location(world, feet.x + .5, feet.y.toDouble(), feet.z + .5) }
            runtime.rejectStanding(city, worker, first) shouldBe true
            val recovered = nextMineWork(runtime, city, worker)
            recovered.remoteWork shouldBe !outside
            CivsMinePlan.forBuilding(mine)!!.builtStanding(CivsMinePlan.localPoint(mine, recovered.standing),
                requireNotNull(recovered.miningAccessStep), recovered.miningAccessSections.orEmpty()) shouldBe true
            recovered.changes shouldBe first.changes
            recovered.inputs shouldBe first.inputs
            recovered.miningStep shouldBe first.miningStep
            runtime.validate(city, recovered)
            CivsEquipment.plainCount(worker, "COBBLESTONE") shouldBe 32L
            first.changes.forEach { cell ->
                world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.before
            }
        }
    }

    test("a farmer's first rejected approach can use the same bounded remote mode without changing its harvest") {
        withReportedMine(nanoTime = { 0L }) { _, world, _, original, _, runtime ->
            val farm = spatialBuilding("medievaloak", "farmer", 1, CivPosition(-25, 63, 15)).copy(production = "WHEAT")
            val farmer = spatialWorker(farm, "IRON_HOE")
            val city = original.copy(buildings = original.buildings + farm, workers = listOf(farmer))
            for (x in -4..1) for (z in -2..3) world.loadChunk(x, z)
            CivsWorksiteLayouts.field(farm).forEach { soil ->
                world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=7]")
                world.getBlockAt(soil.x, soil.y + 1, soil.z).blockData =
                    (Material.WHEAT.createBlockData() as Ageable).also { it.age = it.maximumAge }
            }
            CivsWorksiteLayouts.irrigation(farm).forEach { world.getBlockAt(it.x, it.y, it.z).type = Material.WATER }
            val first = nextMineWork(runtime, city, farmer)
            val focus = requireNotNull(citizenWorkFocus(first))
            val feet = listOf(6 to 0, -6 to 0, 0 to 6, 0 to -6).map { (dx, dz) ->
                Location(world, focus.x + dx + .5, 64.0, focus.z + dz + .5)
            }.first { at ->
                citizenStandable(world, CivPosition(at.blockX, 64, at.blockZ)) &&
                    citizenPacketBodyClear(world, CitizenPoint(at.x, at.y, at.z), false) &&
                    citizenCanReachBlock(world, at.clone().add(0.0, 1.62, 0.0), focus, CITIZEN_REMOTE_WORK_REACH)
            }
            runtime.residentFeet = { feet }
            runtime.rejectStanding(city, farmer, first) shouldBe true
            val recovered = nextMineWork(runtime, city, farmer)
            recovered.remoteWork shouldBe true
            recovered.copy(standing = first.standing, remoteWork = false) shouldBe first
            runtime.validate(city, recovered)
            city.workers.single() shouldBe farmer
            first.changes.forEach { cell ->
                world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.before
            }
        }
    }

    test("a farmer admits its first harvest remotely when the ordinary stance ring has no support") {
        withReportedMine(nanoTime = { 0L }) { _, world, _, original, _, runtime ->
            val farm = spatialBuilding("medievaloak", "farmer", 1, CivPosition(-25, 63, 15)).copy(production = "WHEAT")
            val farmer = spatialWorker(farm, "IRON_HOE")
            val city = original.copy(buildings = original.buildings + farm, workers = listOf(farmer))
            for (x in -4..1) for (z in -2..3) world.loadChunk(x, z)
            val soil = CivsWorksiteLayouts.field(farm).first()
            val focus = soil.copy(y = soil.y + 1)
            for (x in soil.x - 2..soil.x + 2) for (z in soil.z - 2..soil.z + 2)
                if (x != soil.x || z != soil.z) for (y in soil.y - 2..soil.y) world.getBlockAt(x, y, z).type = Material.AIR
            world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=7]")
            val crop = (Material.WHEAT.createBlockData() as Ageable).also { it.age = it.maximumAge }
            world.getBlockAt(focus.x, focus.y, focus.z).blockData = crop
            CivsWorksiteLayouts.irrigation(farm).forEach { world.getBlockAt(it.x, it.y, it.z).type = Material.WATER }
            val feet = Location(world, soil.x - 5.5, focus.y.toDouble(), soil.z + .5)
            world.getBlockAt(feet.blockX, feet.blockY - 1, feet.blockZ).type = Material.STONE
            for (y in feet.blockY..feet.blockY + 1) world.getBlockAt(feet.blockX, y, feet.blockZ).type = Material.AIR
            citizenPacketSupported(world, CitizenPoint(feet.x, feet.y, feet.z)) shouldBe true
            citizenPacketBodyClear(world, CitizenPoint(feet.x, feet.y, feet.z), false) shouldBe true
            runtime.residentFeet = { feet }
            citizenCanReachBlock(world, feet.clone().add(0.0, 1.62, 0.0), focus, CITIZEN_BLOCK_REACH) shouldBe false
            val work = nextMineWork(runtime, city, farmer)
            work.remoteWork shouldBe true
            work.focus shouldBe focus
            work.standing shouldBe CivPosition(feet.blockX, feet.blockY, feet.blockZ)
            work.outputs shouldBe mapOf("WHEAT" to 1L, "WHEAT_SEEDS" to 1L)
            work.inputs shouldBe emptyMap()
            runtime.validate(city, work)
            world.getBlockAt(focus.x, focus.y, focus.z).blockData.asString shouldBe crop.asString
            city.workers.single() shouldBe farmer
        }
    }

    test("a mine admits a first remote repair only from its own completed gallery after nearby footing is lost") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("mine-initial-remote").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("MineInitialRemote")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                try {
                    val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 68, 0))
                    val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
                    val worker = spatialWorker(mine, "IRON_PICKAXE")
                    val prefix = geometry.steps.size.toLong()
                    val city = spatialCity(world.uid, listOf(mine), worker).copy(minePlans =
                        mapOf(mine.id.toString() to CivMinePlanState(CivsMinePlan.VERSION, prefix)))
                    for (x in -3..3) for (z in -3..3) world.loadChunk(x, z)
                    seedMineNaturalCover(world, mine)
                    for (step in geometry.steps) {
                        val point = CivsWorksiteLayouts.point(mine, step.point)
                        world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(step.after ?: "minecraft:air")
                    }
                    val top = CivsWorksiteLayouts.point(mine, geometry.bottom)
                    for (y in top.y..top.y + 3) world.getBlockAt(top.x, y, top.z).blockData =
                        Bukkit.createBlockData("minecraft:ladder[facing=${geometry.facing},waterlogged=false]")
                    val localFace = requireNotNull(geometry.generator)
                    val localStanding = requireNotNull(geometry.generatorStanding)
                    val dx = localFace.x - localStanding.x
                    val dz = localFace.z - localStanding.z
                    val face = CivsWorksiteLayouts.point(mine, localFace)
                    val inside = CivsWorksiteLayouts.point(mine,
                        localStanding.copy(x = localStanding.x - dx * 6, z = localStanding.z - dz * 6))
                    for (x in face.x - 5..face.x + 5) for (z in face.z - 5..face.z + 5)
                        for (y in inside.y - 5 until inside.y) world.getBlockAt(x, y, z).type = Material.AIR
                    val feet = Location(world, inside.x + .5, inside.y.toDouble(), inside.z + .5)
                    citizenStandable(world, inside) shouldBe true
                    geometry.builtStanding(CivsMinePlan.localPoint(mine, inside), prefix) shouldBe true
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, nanoTime = { 0L })
                        runtime.residentFeet = { feet }
                        repeat(64) { runtime.pulse(listOf(city)) }
                        val expectedRepair = CivsWorksiteLayouts.point(mine, localStanding.copy(y = localStanding.y - 1))
                        val eye = feet.clone().add(0.0, 1.62, 0.0)
                        val centre = Location(world, expectedRepair.x + .5, expectedRepair.y + .5, expectedRepair.z + .5)
                        val hit = world.rayTraceBlocks(eye, centre.toVector().subtract(eye.toVector()), eye.distance(centre),
                            org.bukkit.FluidCollisionMode.ALWAYS, true)
                        val work = withClue("feet=$feet expectedRepair=$expectedRepair " +
                            "support=${citizenPacketSupported(world, CitizenPoint(feet.x, feet.y, feet.z))} " +
                            "body=${citizenPacketBodyClear(world, CitizenPoint(feet.x, feet.y, feet.z), false)} " +
                            "remoteRay=${citizenCanReachBlock(world, eye, expectedRepair, CITIZEN_REMOTE_WORK_REACH)} " +
                            "hit=${hit?.hitBlock?.location}/${hit?.hitBlock?.type} state=${runtime.debug(setOf(worker.id))}") {
                            requireNotNull(runtime.plan(city, worker))
                        }
                        work.remoteWork shouldBe true
                        work.standing shouldBe inside
                        work.status shouldBe "securing-mine"
                        work.inputs shouldBe mapOf("COBBLESTONE" to 1L)
                        work.miningStep shouldBe prefix
                        val focus = requireNotNull(work.focus)
                        citizenCanReachBlock(world, feet.clone().add(0.0, 1.62, 0.0), focus, CITIZEN_MINE_REACH) shouldBe false
                        runtime.validate(city, work)
                        work.changes.forEach { cell ->
                            world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.before
                        }
                        CivsEquipment.plainCount(worker, "COBBLESTONE") shouldBe 32L
                        // A supported cave outside the completed passage is never a fallback stance.
                        val outside = inside.copy(x = inside.x - dz * 8, z = inside.z + dx * 8)
                        geometry.builtStanding(CivsMinePlan.localPoint(mine, outside), prefix) shouldBe false
                        world.getBlockAt(outside.x, outside.y - 1, outside.z).type = Material.STONE
                        for (y in outside.y..outside.y + 1) world.getBlockAt(outside.x, y, outside.z).type = Material.AIR
                        runtime.pulse(emptyList())
                        runtime.residentFeet = { Location(world, outside.x + .5, outside.y.toDouble(), outside.z + .5) }
                        repeat(128) { runtime.pulse(listOf(city)) }
                        runtime.plan(city, worker) shouldBe null
                        CivsEquipment.plainCount(worker, "COBBLESTONE") shouldBe 32L
                    }
                } finally { tasks.close() }
            }
        }
    }

    test("idle admissions share one native budget instead of spending a complete service slot per worker") {
        withReportedMine(nanoTime = { 0L }) { advanceTicks, world, _, original, worker, runtime ->
            nextMineWork(runtime, original, worker)
            val city = original.copy(workers = (0..8).map { worker.copy(id = UUID.randomUUID()) })
            repeat(8) {
                advanceTicks(5)
                val before = world.rayTraces
                runtime.pulse(listOf(city))
                (world.rayTraces - before <= 8) shouldBe true
            }
            city.workers.forEach { selected ->
                val work = withClue("worker=${selected.id} ${runtime.debug()}") { requireNotNull(runtime.plan(city, selected)) }
                work.inputs shouldBe mapOf("COBBLESTONE" to 1L)
                runtime.validate(city, work)
                CivsEquipment.plainCount(selected, "COBBLESTONE") shouldBe 32L
            }
        }
    }

    for (rayBudget in listOf(1, 8)) test("a cached farm cannot starve mine discovery after $rayBudget cached miners consume the shared rays") {
        withReportedMine(settings = CivsWorksiteSettings(rayChecksPerPulse = rayBudget), nanoTime = { 0L }) {
                advanceTicks, world, _, original, worker, runtime ->
            val farm = spatialBuilding("medievaloak", "farmer", 1, CivPosition(-25, 63, 15)).copy(production = "WHEAT")
            val farmer = spatialWorker(farm, "IRON_HOE")
            val miners = List(rayBudget) { worker.copy(id = UUID.randomUUID()) }
            val warm = original.copy(buildings = original.buildings + farm, workers = miners + farmer)
            for (x in -4..1) for (z in -2..3) world.loadChunk(x, z)
            CivsWorksiteLayouts.field(farm).forEach { soil ->
                world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=7]")
                world.getBlockAt(soil.x, soil.y + 1, soil.z).blockData =
                    (Material.WHEAT.createBlockData() as Ageable).also { it.age = it.maximumAge }
            }
            CivsWorksiteLayouts.irrigation(farm).forEach { world.getBlockAt(it.x, it.y, it.z).type = Material.WATER }
            // End a full number of rotations so discovery starts after the cached miners.
            repeat(128 * warm.workers.size) { runtime.pulse(listOf(warm)) }
            miners.forEach { requireNotNull(runtime.plan(warm, it)) }
            val farmWork = requireNotNull(runtime.plan(warm, farmer))
            farmWork.status shouldBe "working-field"
            farmWork.cropCare shouldBe false
            val searching = worker.copy(id = UUID.randomUUID())
            val city = warm.copy(workers = miners + searching + farmer)
            fun pulse() {
                advanceTicks(5)
                val before = world.rayTraces
                runtime.pulse(listOf(city))
                (world.rayTraces - before <= rayBudget) shouldBe true
            }
            pulse()
            runtime.plan(city, searching) shouldBe null
            runtime.plan(city, farmer) shouldBe farmWork
            val budget = runtime.debug()["remainingPulseBudget"] as Map<*, *>
            budget["rays"] shouldBe 0
            budget["lastAdmittedWorker"] shouldBe farmer.id.toString()
            repeat(64 * city.workers.size) { if (runtime.plan(city, searching) == null) pulse() }
            val selected = withClue(runtime.debug(setOf(searching.id))) { requireNotNull(runtime.plan(city, searching)) }
            runtime.validate(city, selected)
            selected.inputs shouldBe mapOf("COBBLESTONE" to 1L)
            CivsEquipment.plainCount(searching, "COBBLESTONE") shouldBe 32L
        }
    }

    test("completed gallery lighting can use another inside stance without leaving its passages") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("mine-generator-standing").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("MineGeneratorStanding")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                try {
                    val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 68, 0))
                    val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
                    val worker = spatialWorker(mine, "IRON_PICKAXE")
                    val city = spatialCity(world.uid, listOf(mine), worker).copy(minePlans =
                        mapOf(mine.id.toString() to CivMinePlanState(CivsMinePlan.VERSION, geometry.steps.size.toLong())))
                    for (x in -3..3) for (z in -3..3) world.loadChunk(x, z)
                    seedMineNaturalCover(world, mine)
                    for (step in geometry.steps) {
                        val point = CivsWorksiteLayouts.point(mine, step.point)
                        world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(step.after ?: "minecraft:air")
                    }
                    val top = CivsWorksiteLayouts.point(mine, geometry.bottom)
                    for (y in top.y..top.y + 3) world.getBlockAt(top.x, y, top.z).blockData =
                        Bukkit.createBlockData("minecraft:ladder[facing=${geometry.facing},waterlogged=false]")
                    val generatorStanding = requireNotNull(geometry.generatorStanding)
                    val light = CivsWorksiteLayouts.point(mine, geometry.floors.last().torches.first {
                        kotlin.math.abs(it.x - generatorStanding.x) + kotlin.math.abs(it.z - generatorStanding.z) == 1
                    })
                    // This real paid lighting action precedes the generator's random output roll.
                    world.getBlockAt(light.x, light.y, light.z).type = Material.AIR
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                        val first = nextMineWork(runtime, city, worker)
                        first.miningGenerator shouldBe false
                        first.status shouldBe "securing-mine"
                        first.focus shouldBe light
                        first.inputs shouldBe mapOf("TORCH" to 1L)
                        CivsMinePlan.workStandingAllowed(mine, first.standing) shouldBe true
                        runtime.validate(city, first)
                        runtime.rejectStanding(city, worker, first) shouldBe true
                        val second = nextMineWork(runtime, city, worker)
                        second.miningGenerator shouldBe false
                        second.focus shouldBe first.focus
                        second.inputs shouldBe first.inputs
                        (second.standing != first.standing) shouldBe true
                        CivsMinePlan.workStandingAllowed(mine, second.standing) shouldBe true
                        runtime.validate(city, second)
                        world.getBlockAt(light.x, light.y, light.z).type shouldBe Material.AIR
                        CivsEquipment.plainCount(worker, "TORCH") shouldBe 8L
                        // A neighbouring safety target must not erase an unreachable stance
                        // for the same worker/cycle/step. Reproduce the reported A→B→A loop.
                        val otherLight = CivsWorksiteLayouts.point(mine, geometry.floors.last().torches.first {
                            it != CivsMinePlan.localPoint(mine, light) &&
                                kotlin.math.abs(it.x - generatorStanding.x) + kotlin.math.abs(it.z - generatorStanding.z) == 1
                        })
                        world.getBlockAt(light.x, light.y, light.z).type = Material.TORCH
                        world.getBlockAt(otherLight.x, otherLight.y, otherLight.z).type = Material.AIR
                        runtime.invalidate(worker.id)
                        val neighbour = nextMineWork(runtime, city, worker)
                        neighbour.focus shouldBe otherLight
                        (neighbour.standing != first.standing) shouldBe true
                        world.getBlockAt(otherLight.x, otherLight.y, otherLight.z).type = Material.TORCH
                        world.getBlockAt(light.x, light.y, light.z).type = Material.AIR
                        runtime.invalidate(worker.id)
                        val returned = nextMineWork(runtime, city, worker)
                        returned.focus shouldBe first.focus
                        (returned.standing != first.standing) shouldBe true
                        returned.inputs shouldBe first.inputs
                        CivsEquipment.plainCount(worker, "TORCH") shouldBe 8L
                    }
                } finally { tasks.close() }
            }
        }
    }

    test("reported V3 step83 cannot choose the future lower cave stance while its ladder is unfinished") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("reported-v3-step83").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("ReportedStep83")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                try {
                    val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(-85, 68, -40), 270)
                    val old = requireNotNull(CivsMinePlan.forBuilding(mine, 3))
                    val current = requireNotNull(CivsMinePlan.forBuilding(mine))
                    val worker = spatialWorker(mine, "IRON_PICKAXE")
                    var city = spatialCity(world.uid, listOf(mine), worker).copy(center = CivPosition(-25, 64, -20),
                        minePlans = mapOf(mine.id.toString() to CivMinePlanState(3, 83)))
                    for (x in -7..-5) for (z in -4..-2) world.loadChunk(x, z)
                    seedMineNaturalCover(world, mine)
                    val top = CivsWorksiteLayouts.point(mine, old.bottom)
                    for (y in top.y..top.y + 3) world.getBlockAt(top.x, y, top.z).blockData =
                        Bukkit.createBlockData("minecraft:ladder[facing=south,waterlogged=false]")
                    for (step in old.steps.take(83)) {
                        val point = CivsWorksiteLayouts.point(mine, step.point)
                        world.getBlockAt(point.x, point.y, point.z).blockData =
                            Bukkit.createBlockData(step.after ?: "minecraft:air").also {
                                if (step.after != null) it.rotate(StructureRotation.COUNTERCLOCKWISE_90)
                            }
                    }
                    val reported = CivPosition(-96, 55, -47)
                    world.getBlockAt(reported.x, reported.y - 1, reported.z).type = Material.COBBLESTONE
                    for (y in reported.y..reported.y + 1) world.getBlockAt(reported.x, y, reported.z).type = Material.AIR
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        // Geometry and paid replay must not depend on a CI machine completing a 2ms slice.
                        var runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, nanoTime = { 0L })
                        val work = nextMineWork(runtime, city, worker)
                        work.miningPlanVersion shouldBe CivsMinePlan.VERSION
                        (work.standing != reported) shouldBe true
                        current.builtStanding(CivsMinePlan.localPoint(mine, work.standing),
                            requireNotNull(work.miningAccessStep), work.miningAccessSections.orEmpty()) shouldBe true
                        for (y in work.standing.y..top.y) world.getBlockAt(top.x, y, top.z).type shouldBe Material.LADDER
                        runtime.validate(city, work)
                        city.minePlans!!.getValue(mine.id.toString()) shouldBe CivMinePlanState(3, 83)
                        CivsEquipment.plainCount(worker, "COBBLESTONE") shouldBe 32L
                        CivsStorage(plugin) { emptyArray() }.use { storage ->
                            var removedLining = false
                            var restarted = false
                            val paidLining = hashSetOf<CivPosition>()
                            val directory = Files.createTempDirectory("mine-v4-paid-lining-restart")
                            CivsStore(directory, Executor { it.run() }).use { it.save(city).join() }
                            repeat(40) { action ->
                                val currentWorker = city.workers.single()
                                val next = nextMineWork(runtime, city, currentWorker)
                                runtime.validate(city, next)
                                val change = next.changes.single()
                                if (next.status == "securing-mine" && change.after == "minecraft:cobblestone")
                                    paidLining += change.position
                                if (change.position in paidLining && change.before == "minecraft:cobblestone" &&
                                    change.after == "minecraft:air") {
                                    next.outputs shouldBe mapOf("COBBLESTONE" to 1L)
                                    removedLining = true
                                }
                                val id = UUID.randomUUID()
                                storage.reserve(city, id)
                                val native = storage.prepareWork(city, mine.id, next.storageInputs(currentWorker), emptyMap(), next.changes)
                                val after = CivsRules.finishWorldWork(city, next, 100_000 + action * 5_000L)
                                    .copy(inventory = native.inventoryAfter)
                                val receipt = CivInventoryTransaction(id, city.id, city.revision, after, native.changes,
                                    worldChanges = native.worldChanges).validated()
                                storage.seal(receipt, keepViewersOpen = true)
                                storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
                                storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
                                storage.unlock(id)
                                city = receipt.after
                                CivsStore(directory, Executor { it.run() }).use { it.save(city).join() }
                                if (!restarted && paidLining.isNotEmpty() && !removedLining) {
                                    restarted = true
                                    city = CivsStore(directory, Executor { it.run() }).use { it.load().join().single() }
                                    runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, nanoTime = { 0L })
                                }
                                city.minePlans!!.getValue(mine.id.toString()).v3Step shouldBe 83L
                                runtime.invalidate(currentWorker.id)
                            }
                            restarted shouldBe true
                            removedLining shouldBe true
                            (city.minePlans!!.getValue(mine.id.toString()).nextStep > 22) shouldBe true
                        }
                    }
                } finally { tasks.close() }
            }
        }
    }

    test("the V4 entrance floor uses a durable paid native receipt before excavation continues") {
        withReportedMine { _, world, mine, original, worker, runtime ->
            val work = nextMineWork(runtime, original, worker)
            val footing = requireNotNull(work.focus)
            work.inputs shouldBe mapOf("COBBLESTONE" to 1L)
            work.outputs shouldBe emptyMap()
            runtime.validate(original, work)
            val plugin = Bukkit.getPluginManager().getPlugin("MineWorkStanding")!!
            CivsStorage(plugin) { emptyArray() }.use { storage ->
                val id = UUID.randomUUID()
                storage.reserve(original, id)
                val native = storage.prepareWork(original, mine.id, work.storageInputs(worker), emptyMap(), work.changes)
                val after = CivsRules.finishWorldWork(original, work, 100_000).copy(inventory = native.inventoryAfter)
                val receipt = CivInventoryTransaction(id, original.id, original.revision, after, native.changes,
                    worldChanges = native.worldChanges).validated()
                storage.seal(receipt, keepViewersOpen = true)
                storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
                storage.unlock(id)
                world.getBlockAt(footing.x, footing.y, footing.z).type shouldBe Material.COBBLESTONE
                CivsEquipment.plainCount(receipt.after.workers.single(), "COBBLESTONE") shouldBe
                    CivsEquipment.plainCount(worker, "COBBLESTONE") - 1
                receipt.after.minePlans!!.getValue(mine.id.toString()).nextStep shouldBe work.miningStep
                runtime.invalidate(worker.id)
                val next = nextMineWork(runtime, receipt.after, receipt.after.workers.single())
                citizenStandable(world, next.standing) shouldBe true
                runtime.validate(receipt.after, next)
            }
        }
    }

})

private fun nextMineWork(runtime: CivsWorksites, city: Settlement, worker: CivWorker): CivWorldWork {
    repeat(64) { runtime.pulse(listOf(city)) }
    return withClue("idle=${runtime.idleReason(city, worker)} state=${runtime.debug(setOf(worker.id))}") {
        requireNotNull(runtime.plan(city, worker))
    }
}

private fun withReportedMine(
    settings: CivsWorksiteSettings = CivsWorksiteSettings(),
    nanoTime: () -> Long = System::nanoTime,
    action: ((Int) -> Unit, CivsWorksiteWorldFixture, CivBuilding, Settlement, CivWorker, CivsWorksites) -> Unit,
) {
    failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("mine-work-standing").also(paper.server::addWorld)
            val plugin = paper.createSimplePlugin("MineWorkStanding")
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            try {
                val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(-85, 68, -40), 270)
                val worker = spatialWorker(mine, "IRON_PICKAXE")
                val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
                val prefix = geometry.steps.indexOfFirst { it.after == "minecraft:cobblestone" && !it.shaftSupport }
                var city = spatialCity(world.uid, listOf(mine), worker).copy(center = CivPosition(-25, 64, -20),
                    minePlans = mapOf(mine.id.toString() to CivMinePlanState(CivsMinePlan.VERSION, prefix.toLong())))
                for (x in -7..-5) for (z in -4..-2) world.loadChunk(x, z)
                seedMineNaturalCover(world, mine)
                val top = CivsWorksiteLayouts.point(mine, geometry.bottom)
                for (y in top.y..top.y + 3) world.getBlockAt(top.x, y, top.z).blockData =
                    Bukkit.createBlockData("minecraft:ladder[facing=south,waterlogged=false]")
                for (step in geometry.steps.take(prefix)) {
                    val p = CivsWorksiteLayouts.point(mine, step.point)
                    world.getBlockAt(p.x, p.y, p.z).blockData = Bukkit.createBlockData(step.after ?: "minecraft:air").also {
                        if (step.after != null) it.rotate(StructureRotation.COUNTERCLOCKWISE_90)
                    }
                }
                city = city.copy(minePlans = mapOf(mine.id.toString() to
                    seedMinePaidSpine(world, mine, city.minePlans!!.getValue(mine.id.toString()))))
                val support = CivsWorksiteLayouts.point(mine, geometry.steps[prefix].point)
                world.getBlockAt(support.x, support.y, support.z).type = Material.AIR
                // These real native cave cells are not proof of a built lower route.
                world.getBlockAt(-96, 62, -49).type = Material.AIR
                world.getBlockAt(-96, 59, -51).type = Material.GRASS_BLOCK
                for (y in 60..61) world.getBlockAt(-96, y, -51).type = Material.AIR
                CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                    val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false },
                        nanoTime = nanoTime, settings = settings)
                    action({ paper.performTicks(it.toLong()) }, world, mine, city, worker, runtime)
                }
            } finally { tasks.close() }
        }
    }
}
