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
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executor
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

    test("the live rotated mine reaches its underground shaft through the west staircase") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("mine-side-shaft-legs").also(paper.server::addWorld)
            val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(-85, 68, -40), 270)
                .copy(control = CivPosition(-93, 69, -40))
            val city = spatialCity(world.uid, listOf(mine), spatialWorker(mine, "DIAMOND_PICKAXE"))
            val bottom = CivPosition(-93, 63, -47)
            val work = CivPosition(-94, 61, -48)
            for (x in -7..-5) for (z in -4..-2) world.loadChunk(x, z)
            // Corridor sampled from Spawn on 2026-10-22 at 11:46–11:52 UTC. These
            // are observed feet/support points, not a fabricated ladder to the surface.
            val stairs = listOf(
                Triple(-94, 68, -41), Triple(-95, 68, -41), Triple(-96, 68, -41),
                Triple(-97, 68, -41), Triple(-98, 68, -41), Triple(-99, 67, -40),
                Triple(-99, 67, -41), Triple(-99, 66, -42), Triple(-100, 65, -43),
                Triple(-99, 64, -44), Triple(-99, 64, -45), Triple(-98, 64, -45),
                Triple(-97, 64, -46), Triple(-96, 64, -46), Triple(-95, 64, -47),
                Triple(-94, 64, -47))
            val paths = setOf(Triple(-95, 68, -41), Triple(-97, 68, -41),
                Triple(-99, 67, -41), Triple(-99, 64, -45), Triple(-98, 64, -45))
            for ((x, y, z) in stairs) {
                world.getBlockAt(x, y, z).type = if (Triple(x, y, z) in paths) Material.DIRT_PATH else Material.COBBLESTONE
                if (y == 64) world.getBlockAt(x, 68, z).type = Material.DIRT
            }
            for (y in 57..65) world.getBlockAt(bottom.x, y, bottom.z).blockData =
                Bukkit.createBlockData("minecraft:ladder[facing=south,waterlogged=false]")
            world.getBlockAt(bottom.x, 68, bottom.z).type = Material.DIRT
            // The reported cave-side stance is supported and clear, but the diagonal
            // from the ladder crosses its solid backing and the western shaft wall.
            world.getBlockAt(-94, 60, -48).type = Material.COBBLESTONE
            world.getBlockAt(-94, 61, -48).type = Material.AIR
            world.getBlockAt(-94, 62, -48).type = Material.AIR
            world.getBlockAt(-94, 61, -47).type = Material.COBBLESTONE
            world.getBlockAt(-93, 61, -48).type = Material.COBBLESTONE
            CivsMinePlan.shaftContains(mine, work) shouldBe true
            citizenMineWaypoint(city, world, Location(world, -93.812, 69.0, -40.5), work) shouldBe bottom
            citizenMineWaypoint(city, world, Location(world, -92.5, 63.0, -46.5), work) shouldBe bottom.copy(y = 61)
            citizenMineWaypoint(city, world, Location(world, -92.5, 61.0, -46.5), work) shouldBe null
            val corridor = stairs.mapTo(hashSetOf()) { (x, _, z) -> x to z } + (bottom.x to bottom.z)
            var at = Location(world, -93.812, 69.0, -40.5)
            val reached = mutableListOf<Location>()
            for (end in listOf(bottom, bottom.copy(y = 61))) {
                val destination = Location(world, end.x + .5, end.y.toDouble(), end.z + .5)
                val route = CitizenPacketRoute(world, at, destination,
                    { p -> (p.x to p.z) in corridor && p.y in 61..69 }, { _, _ -> true }, { 2.25 })
                repeat(20_000) { if (route.searching) route.expand() }
                withClue("to=$end nodes=${route.nodeCount} failure=${route.failure}") {
                    route.searching shouldBe false
                    route.failure shouldBe null
                }
                var previousFall = 0.0
                repeat(800) {
                    route.move(at, .172) { true }?.let { next ->
                        withClue("from=$at next=$next") {
                            // Gravity is independent of walking; the speed argument is a stride multiplier.
                            val walking = next.clone().also { if (it.y < at.y) it.y = at.y }
                            (at.distance(walking) <= .215 * .172 + .000001) shouldBe true
                            val fall = (at.y - next.y).coerceAtLeast(0.0)
                            (fall <= maxOf(.215 * .172, minOf(1.0, (previousFall + .08) * .98)) + .000001) shouldBe true
                            for (sample in 0..16) {
                                val point = at.clone().add(next.toVector().subtract(at.toVector()).multiply(sample / 16.0))
                                citizenPacketBodyClear(world, CitizenPoint(point.x, point.y, point.z), false) shouldBe true
                            }
                            previousFall = fall
                        }
                        at = next
                        reached += next
                    }
                }
                withClue("to=$end actual=$at failure=${route.failure}") { route.finished shouldBe true }
                at.toVector() shouldBe destination.toVector()
            }
            reached.any { it.x < -99.0 && it.y in 65.0..67.0 } shouldBe true
            citizenPacketStepClear(world, CitizenPoint(-92.5, 61.0, -46.5), CitizenPoint(-93.5, 61.0, -47.5), false) shouldBe false
        }
    }

    test("the rotated mine cuts its connected frontier instead of sealing the detached western cave") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("mine-detached-western-cave").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("MineDetachedCave")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                try {
                    val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(-85, 68, -40), 270)
                    val worker = spatialWorker(mine, "IRON_PICKAXE")
                    val city = spatialCity(world.uid, listOf(mine), worker).copy(center = mine.anchor,
                        mineProgress = mapOf(mine.id.toString() to 214L),
                        minePlans = mapOf(mine.id.toString() to CivMinePlanState(CivsMinePlan.VERSION, requireNotNull(CivsMinePlan.forBuilding(mine)).steps.indexOfFirst {
                            CivsWorksiteLayouts.point(mine, it.point) == CivPosition(-92, 62, -47) && it.after == null
                        }.toLong(), 214)))
                    for (x in -7..-5) for (z in -4..-2) world.loadChunk(x, z)
                    for (y in 57..65) world.getBlockAt(-93, y, -47).blockData =
                        Bukkit.createBlockData("minecraft:ladder[facing=south,waterlogged=false]")
                    // Observed Spawn geometry, 2026-10-22: both routes into this
                    // supported cave stance cross an intact wall/backing block.
                    for (y in 61..62) world.getBlockAt(-94, y, -48).type = Material.AIR
                    for (y in 60..62) world.getBlockAt(-93, y, -48).type = Material.COBBLESTONE
                    world.getBlockAt(-94, 60, -48).type = Material.COBBLESTONE
                    world.getBlockAt(-94, 61, -47).type = Material.COBBLESTONE
                    // Reconstruct the earlier missing shell; the later live snapshot
                    // already contained cobblestone here and is not its preimage.
                    world.getBlockAt(-94, 62, -49).type = Material.AIR
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                        repeat(64) { runtime.pulse(listOf(city)) }
                        val work = requireNotNull(runtime.plan(city, worker))
                        work.status shouldBe "digging-mine"
                        work.changes.single().position shouldBe CivPosition(-92, 62, -47)
                        work.changes.single().after shouldBe "minecraft:air"
                        work.inputs shouldBe emptyMap()
                        work.standing.x shouldBe -93
                        work.standing.z shouldBe -47
                        citizenStandable(world, work.standing) shouldBe true
                        runtime.validate(city, work)
                        world.getBlockAt(-94, 62, -49).type shouldBe Material.AIR
                    }
                } finally { tasks.close() }
            }
        }
    }

    for (restart in listOf(false, true)) for (prerequisite in listOf(false, true))
        for (farFace in if (prerequisite) listOf(false) else listOf(false, true)) {
        test("an actual shaft cut seals its connected cave with restart $restart prerequisite $prerequisite and far face $farFace") {
            failOnUnsupportedMockBukkitOperation {
                MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                    val world = CivsWorksiteWorldFixture("mine-post-cut-$restart-$prerequisite-$farFace").also(paper.server::addWorld)
                    val plugin = paper.createSimplePlugin("MinePostCut")
                    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                    val directory = Files.createTempDirectory("arccivs-mine-post-cut-")
                    try {
                        val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
                        val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
                        val key = mine.id.toString()
                        val index = if (prerequisite) 0 else geometry.steps.indexOfFirst { it.after == null &&
                            it.point == geometry.bottom.copy(y = geometry.bottom.y - 1, z = geometry.bottom.z + 1) }
                        val bottom = CivsWorksiteLayouts.point(mine, geometry.bottom)
                        val target = if (prerequisite) bottom.copy(y = bottom.y - 1)
                            else CivsWorksiteLayouts.point(mine, geometry.steps[index].point)
                        val cavity = if (prerequisite) target.copy(z = target.z + 1) else target.copy(x = target.x + 1)
                        val wall = if (farFace) cavity.copy(x = cavity.x + 1) else cavity.copy(z = cavity.z + 1)
                        var city = spatialCity(world.uid, listOf(mine), spatialWorker(mine, "IRON_PICKAXE"))
                            .copy(minePlans = mapOf(key to CivMinePlanState(CivsMinePlan.VERSION, index.toLong())))
                        for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                        for (y in bottom.y - (if (farFace) 3 else 2)..65) world.getBlockAt(bottom.x, y, bottom.z).blockData =
                            Bukkit.createBlockData("minecraft:ladder[facing=${geometry.facing},waterlogged=false]")
                        world.getBlockAt(target.x, target.y, target.z).type = Material.STONE
                        if (prerequisite) {
                            val backing = CivsWorksiteLayouts.point(mine, geometry.steps[index].point)
                            world.getBlockAt(backing.x, backing.y, backing.z).type = Material.AIR
                        } else {
                                    // Already-done cells following this cut must not let
                            // the volatile skip cursor bypass its newly exposed shell.
                            geometry.steps.drop(index + 1).takeWhile { it.point.y == geometry.steps[index].point.y }
                                .filter { it.after == null }.forEach { step ->
                                    val p = CivsWorksiteLayouts.point(mine, step.point)
                                    world.getBlockAt(p.x, p.y, p.z).type = Material.AIR
                                }
                            world.getBlockAt(cavity.x, cavity.y + 1, cavity.z).type = Material.AIR
                        }
                        world.getBlockAt(cavity.x, cavity.y, cavity.z).type = Material.AIR
                        world.getBlockAt(wall.x, wall.y, wall.z).type = Material.AIR
                        CivsStore(directory, Executor { it.run() }).use { it.save(city).join() }
                        CivsStorage(plugin) { emptyArray() }.use { storage ->
                            CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                                fun runtime() = CivsWorksites(plugin, access,
                                    { current, id -> storage.buildingAvailable(current, id) }, { _, _ -> false })
                                var runtime = runtime()
                                fun nextWork(): CivWorldWork {
                                    repeat(256) {
                                        runtime.pulse(listOf(city))
                                        runtime.plan(city, city.workers.single())?.let { return it }
                                    }
                                    error("post-cut work blocked: ${runtime.idleReason(city, city.workers.single())}")
                                }
                                fun commit(work: CivWorldWork) {
                                    runtime.validate(city, work)
                                    val id = UUID.randomUUID()
                                    storage.reserve(city, id)
                                    val plan = storage.prepareWork(city, mine.id, work.storageInputs(city.workers.single()), emptyMap(), work.changes)
                                    val after = CivsRules.finishWorldWork(city, work, 20_000 + work.cycle * 5_000).copy(inventory = plan.inventoryAfter)
                                    val receipt = CivInventoryTransaction(id, city.id, city.revision, after, plan.changes,
                                        worldChanges = plan.worldChanges).validated()
                                    storage.seal(receipt, keepViewersOpen = true)
                                    storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
                                    storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
                                    city = receipt.after
                                    storage.unlock(id)
                                    runtime.invalidate(work.workerId)
                                }
                                val cut = nextWork()
                                cut.status shouldBe "digging-mine"
                                cut.changes.single().position shouldBe target
                                cut.changes.single().after shouldBe "minecraft:air"
                                cut.inputs shouldBe emptyMap()
                                cut.outputs shouldBe mapOf("COBBLESTONE" to 1L)
                                cut.standing.x shouldBe bottom.x
                                cut.standing.z shouldBe bottom.z
                                commit(cut)
                                var committed = if (prerequisite) index.toLong() else index + 1L
                                city.minePlans!!.getValue(key).nextStep shouldBe committed
                                if (restart) {
                                    CivsStore(directory, Executor { it.run() }).use { it.save(city).join() }
                                    city = CivsStore(directory, Executor { it.run() }).use { it.load().join().single() }
                                    runtime = runtime()
                                }
                                var seal = nextWork()
                                if (prerequisite) {
                                    // V4 uses the same fixed column. Finish its paid backing
                                    // and rung before the new lower stance can see the side
                                    // cave through the still-solid head row. No deeper cut
                                    // may overtake that sealing action.
                                    repeat(2) {
                                        if (seal.status != "securing-mine") {
                                            seal.status shouldBe "digging-mine"
                                            (seal.changes.single().after != "minecraft:air") shouldBe true
                                            commit(seal)
                                            committed = requireNotNull(seal.miningStep)
                                            seal = nextWork()
                                        }
                                    }
                                }
                                if (farFace) {
                                    // The far face is hidden behind the intact ladder
                                    // backing. Only the next ordered lower cut supplies
                                    // two-block clearance from the central ladder.
                                    var lowerCut = false
                                    // V3 has more columns before this lower cut. Every
                                    // intervening action still passes the real receipt
                                    // and native standing checks before exposing the wall.
                                    repeat(32) {
                                        if (seal.status != "securing-mine") {
                                            seal.status shouldBe "digging-mine"
                                            val change = seal.changes.single()
                                            change.after shouldBe "minecraft:air"
                                            if (change.position == target.copy(y = target.y - 1)) lowerCut = true
                                            world.getBlockAt(wall.x, wall.y, wall.z).type shouldBe Material.AIR
                                            commit(seal)
                                            committed = requireNotNull(seal.miningStep)
                                            if (restart) {
                                                CivsStore(directory, Executor { it.run() }).use { it.save(city).join() }
                                                city = CivsStore(directory, Executor { it.run() }).use { it.load().join().single() }
                                                runtime = runtime()
                                            }
                                            seal = nextWork()
                                        }
                                    }
                                    lowerCut shouldBe true
                                }
                                val stone = CivsEquipment.plainCount(city.workers.single(), "COBBLESTONE")
                                seal.status shouldBe "securing-mine"
                                seal.changes.single().position shouldBe wall
                                seal.inputs shouldBe mapOf("COBBLESTONE" to 1L)
                                seal.outputs shouldBe emptyMap()
                                seal.miningStep shouldBe committed
                                if (farFace) {
                                    seal.standing shouldBe target.copy(y = target.y - 1)
                                    citizenPacketStepClear(world,
                                        CitizenPoint(bottom.x + .5, seal.standing.y.toDouble(), bottom.z + .5),
                                        CitizenPoint(seal.standing.x + .5, seal.standing.y.toDouble(), seal.standing.z + .5), false) shouldBe true
                                } else {
                                    // The head is still solid: use the central ladder,
                                    // never the supported but inaccessible cave floor.
                                    seal.standing.x shouldBe bottom.x
                                    seal.standing.z shouldBe bottom.z
                                }
                                citizenStandable(world, seal.standing) shouldBe true
                                commit(seal)
                                city.minePlans!!.getValue(key).nextStep shouldBe committed
                                CivsEquipment.plainCount(city.workers.single(), "COBBLESTONE") shouldBe stone - 1
                                city.inventory!!.buildingStock(mine.id) shouldBe emptyMap()
                                nextWork().changes.single().position.let { (it != target && it != wall) shouldBe true }
                            }
                        }
                    } finally {
                        tasks.close()
                        directory.toFile().deleteRecursively()
                    }
                }
            }
        }
    }

    for (skipped in listOf(false, true)) {
        test("an unreachable shell is sealed or blocks before leaving its lower row with skipped cells $skipped") {
            failOnUnsupportedMockBukkitOperation {
                MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                    val world = CivsWorksiteWorldFixture("mine-shell-expiry-$skipped").also(paper.server::addWorld)
                    val plugin = paper.createSimplePlugin("MineShellExpiry")
                    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                    try {
                        val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
                        val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
                        val first = geometry.steps.indexOfFirst { it.after == null &&
                            it.point == geometry.bottom.copy(y = geometry.bottom.y - 1, z = geometry.bottom.z + 1) }
                        val local = geometry.steps[first].point
                        val lowerRowEnd = geometry.steps.indexOfLast { it.stage == "shaft" && it.after == null &&
                            it.point.y == local.y - 1 && (it.point.x to it.point.z) !in geometry.openingColumns } + 1
                        val progress = if (skipped) first + 1L else lowerRowEnd.toLong()
                        val worker = spatialWorker(mine, "IRON_PICKAXE")
                        var city = spatialCity(world.uid, listOf(mine), worker).copy(minePlans =
                            mapOf(mine.id.toString() to CivMinePlanState(CivsMinePlan.VERSION, progress)))
                        val bottom = CivsWorksiteLayouts.point(mine, geometry.bottom)
                        val target = CivsWorksiteLayouts.point(mine, local)
                        val wall = target.copy(x = target.x + 2)
                        for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                        for (y in bottom.y - 6..65) world.getBlockAt(bottom.x, y, bottom.z).blockData =
                            Bukkit.createBlockData("minecraft:ladder[facing=${geometry.facing},waterlogged=false]")
                        // Both ordered rows are open, but this deeper cave has no
                        // floor within any legal work stance. The intact backing
                        // still hides its far wall from the central ladder.
                        for (y in target.y - 5..target.y) for ((x, z) in geometry.shaftColumns) {
                            if (x == geometry.bottom.x && z == geometry.bottom.z ||
                                x == geometry.backing.x && z == geometry.backing.z) continue
                            val p = CivsWorksiteLayouts.point(mine, CivPosition(x, y - mine.anchor.y, z))
                            world.getBlockAt(p.x, p.y, p.z).type = Material.AIR
                        }
                        world.getBlockAt(wall.x, wall.y, wall.z).type = Material.AIR
                        CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                            val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                            var refused = false
                            var sealed = false
                            repeat(256) {
                                if (!refused && !sealed) {
                                    runtime.pulse(listOf(city))
                                    val current = city.workers.single()
                                    val work = runtime.plan(city, current)
                                    if (work != null) {
                                        val change = work.changes.single()
                                        // The new transfer can first pay for a safe
                                        // supported approach. It may never excavate or
                                        // leave this lower row with the cave unsealed.
                                        withClue("unsealed wall $wall, cursor ${city.minePlans}, work $work") {
                                            change.before shouldBe "minecraft:air"
                                        }
                                        (change.after in setOf("minecraft:cobblestone",
                                            "minecraft:ladder[facing=${geometry.facing},waterlogged=false]")) shouldBe true
                                        work.inputs.values.sum() shouldBe 1L
                                        work.outputs shouldBe emptyMap()
                                        (requireNotNull(work.miningStep) <= lowerRowEnd) shouldBe true
                                        runtime.validate(city, work)
                                        world.getBlockAt(change.position.x, change.position.y, change.position.z)
                                            .blockData = Bukkit.createBlockData(change.after)
                                        city = CivsRules.finishWorldWork(city, work, 20_000 + current.completedCycles * 5_000)
                                        runtime.invalidate(current.id)
                                        sealed = change.position == wall
                                    } else refused = runtime.idleReason(city, current) == "worksite-no-access"
                                }
                            }
                            (refused || sealed) shouldBe true
                            (city.minePlans!!.getValue(mine.id.toString()).nextStep <= lowerRowEnd) shouldBe true
                            world.getBlockAt(wall.x, wall.y, wall.z).type shouldBe if (sealed) Material.COBBLESTONE else Material.AIR
                        }
                    } finally { tasks.close() }
                }
            }
        }
    }

    test("a completed mine still seals the generator gallery before extracting anything") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("mine-final-shell").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("MineFinalShell")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                try {
                    val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
                    val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
                    val worker = spatialWorker(mine, "IRON_PICKAXE")
                    val city = spatialCity(world.uid, listOf(mine), worker).copy(minePlans =
                        mapOf(mine.id.toString() to CivMinePlanState(CivsMinePlan.VERSION, geometry.steps.size.toLong())))
                    val support = CivsWorksiteLayouts.point(mine, requireNotNull(geometry.generatorStanding).let { it.copy(y = it.y - 1) })
                    val wall = support.copy(x = support.x - 1, y = support.y + 1)
                    val face = CivsWorksiteLayouts.point(mine, requireNotNull(geometry.generator))
                    world.getBlockAt(face.x, face.y, face.z).type = Material.COBBLESTONE
                    for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                    val top = CivsWorksiteLayouts.point(mine, geometry.bottom)
                    for (y in support.y + 1..top.y + 2) world.getBlockAt(top.x, y, top.z).blockData =
                        Bukkit.createBlockData("minecraft:ladder[facing=${geometry.facing},waterlogged=false]")
                    world.getBlockAt(support.x, support.y, support.z).type = Material.COBBLESTONE
                    for (y in support.y + 1..support.y + 3) world.getBlockAt(support.x, y, support.z).type = Material.AIR
                    world.getBlockAt(wall.x, wall.y, wall.z).type = Material.AIR
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                        repeat(64) { runtime.pulse(listOf(city)) }
                        val repair = requireNotNull(runtime.plan(city, worker))
                        repair.status shouldBe "securing-mine"
                        repair.changes.single().position shouldBe wall
                        repair.inputs shouldBe mapOf("COBBLESTONE" to 1L)
                        repair.miningStep shouldBe geometry.steps.size.toLong()
                    }
                } finally { tasks.close() }
            }
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
                // The widened south column stays open; seal the cave at the outer
                // 3x3 perimeter instead of rebuilding the old one-block inner wall.
                for (y in bottom.y - 1..bottom.y) world.getBlockAt(bottom.x, y, bottom.z + 1).type = Material.AIR
                val opening = bottom.copy(z = bottom.z + 2, y = bottom.y - 1)
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
                    work.miningPlanVersion shouldBe CivsMinePlan.VERSION
                    world.getBlockAt(bottom.x, bottom.y - 1, bottom.z + 1).type shouldBe Material.AIR
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
                            val plan = storage.prepareWork(city, farm.id, work.storageInputs(worker), emptyMap(), work.changes)
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
                                repeat(16) { runtime.pulse(listOf(city)) }
                                return requireNotNull(runtime.plan(city, city.workers.single()))
                            }
                            fun commit(work: CivWorldWork) {
                                runtime.validate(city, work)
                                val id = UUID.randomUUID()
                                storage.reserve(city, id)
                                val plan = storage.prepareWork(city, farm.id, work.storageInputs(city.workers.single()), emptyMap(), work.changes)
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
                            repeat(8) { runtime.pulse(listOf(city)) }
                            runtime.plan(city, city.workers.single()) shouldBe null
                            (crop.blockData as Ageable).age shouldBe 4

                            crop.blockData = (crop.blockData as Ageable).also { it.age = 5 }
                            repeat(8) { runtime.pulse(listOf(city)) }
                            runtime.plan(city, city.workers.single()) shouldBe null
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
                            repeat(8) { runtime.pulse(listOf(city)) }
                            runtime.plan(city, city.workers.single()) shouldBe null
                            world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=0]")
                            val repeatedFertilizing = next()
                            repeatedFertilizing.status shouldBe "watering"
                            repeatedFertilizing.changes.map(CivPlanBlock::position) shouldBe listOf(soil)
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

    test("farmer harvests mature wheat beyond the first sixteen cells before starting earlier care") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val world = CivsWorksiteWorldFixture("full-field-harvest").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("FullFieldHarvest")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                val farm = spatialBuilding("medievaloak", "farmer", 2, CivPosition(0, 63, 0)).copy(production = "WHEAT")
                val worker = spatialWorker(farm, "IRON_HOE")
                var city = spatialCity(world.uid, listOf(farm), worker)
                val plots = CivsWorksiteLayouts.field(farm)
                for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                plots.forEach { soil ->
                    world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=0]")
                    world.getBlockAt(soil.x, soil.y + 1, soil.z).blockData =
                        (Material.WHEAT.createBlockData() as Ageable).also { it.age = 3 }
                }
                CivsWorksiteLayouts.irrigation(farm).forEach { world.getBlockAt(it.x, it.y, it.z).type = Material.WATER }
                val mature = plots[20].let { it.copy(y = it.y + 1) }
                world.getBlockAt(mature.x, mature.y, mature.z).blockData =
                    (Material.WHEAT.createBlockData() as Ageable).also { it.age = it.maximumAge }
                CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                    val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                    runtime.pulse(listOf(city))
                    runtime.plan(city, worker) shouldBe null
                    repeat(8) { runtime.pulse(listOf(city)) }
                    val work = requireNotNull(runtime.plan(city, worker))
                    work.status shouldBe "working-field"
                    work.cropCare shouldBe false
                    work.changes.single().position shouldBe mature
                    work.outputs["WHEAT"] shouldBe 1L
                    work.outputs["WHEAT_SEEDS"] shouldBe 1L
                    val coworker = spatialWorker(farm, "IRON_HOE")
                    city = city.copy(workers = listOf(worker, coworker))
                    repeat(12) { runtime.pulse(listOf(city)) }
                    requireNotNull(runtime.plan(city, coworker)).cropCare shouldBe true
                    // A cached plan loses its reservation when the real worker can no
                    // longer use it, even while that worker remains an admitted farm job.
                    val unarmed = CivsEquipment.replace(worker, CivsEquipment.slots(worker).toMutableList().also { it[4] = null })
                    city = city.copy(workers = listOf(unarmed, coworker))
                    runtime.plan(city, unarmed) shouldBe null
                    repeat(2) { runtime.pulse(listOf(city)) }
                    val replacement = requireNotNull(runtime.plan(city, coworker))
                    replacement.cropCare shouldBe false
                    replacement.focus shouldBe mature
                    runtime.validate(city, replacement)
                }
                tasks.close()
            }
        }
    }

    test("a paused farm retains its sixteen-cell scan and chosen target but cannot execute it") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val world = CivsWorksiteWorldFixture("paused-field-search").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("PausedFieldSearch")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                val farm = spatialBuilding("medievaloak", "farmer", 1, CivPosition(0, 63, 0)).copy(production = "WHEAT")
                val worker = spatialWorker(farm, "IRON_HOE")
                val city = spatialCity(world.uid, listOf(farm), worker)
                val plots = CivsWorksiteLayouts.field(farm)
                for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                plots.forEach { soil ->
                    world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=7]")
                    world.getBlockAt(soil.x, soil.y + 1, soil.z).blockData =
                        (Material.WHEAT.createBlockData() as Ageable).also { it.age = 2 }
                }
                CivsWorksiteLayouts.irrigation(farm).forEach { world.getBlockAt(it.x, it.y, it.z).type = Material.WATER }
                val mature = plots[20].let { it.copy(y = it.y + 1) }
                world.getBlockAt(mature.x, mature.y, mature.z).blockData =
                    (Material.WHEAT.createBlockData() as Ageable).also { it.age = it.maximumAge }
                var paused = false
                CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                    val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, { paused })
                    runtime.idleReason(city, worker) shouldBe "searching-work"
                    runtime.pulse(listOf(city))
                    runtime.plan(city, worker) shouldBe null
                    runtime.idleReason(city, worker) shouldBe "searching-work"
                    paused = true
                    repeat(4) { runtime.pulse(listOf(city)) }
                    runtime.plan(city, worker) shouldBe null
                    paused = false
                    runtime.pulse(listOf(city)) // Resume at cell sixteen, not at the beginning of the field.
                    val work = requireNotNull(runtime.plan(city, worker))
                    work.focus shouldBe mature
                    paused = true
                    runtime.pulse(listOf(city))
                    runtime.plan(city, worker) shouldBe work
                    shouldThrow<CivsRejection> { runtime.validate(city, work) }.reason shouldBe "city-busy"
                    paused = false
                    runtime.plan(city, worker) shouldBe work
                    runtime.validate(city, work)
                    runtime.pulse(listOf(city.copy(workers = emptyList())))
                    runtime.plan(city, worker) shouldBe null
                }
                tasks.close()
            }
        }
    }

    test("farm reports growth after a complete scan and keeps its confirmed idle reason through city writes") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val world = CivsWorksiteWorldFixture("complete-field-search").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("CompleteFieldSearch")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                val farm = spatialBuilding("medievaloak", "farmer", 1, CivPosition(0, 63, 0)).copy(production = "WHEAT")
                val worker = spatialWorker(farm, "IRON_HOE")
                val city = spatialCity(world.uid, listOf(farm), worker)
                val plots = CivsWorksiteLayouts.field(farm)
                for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                plots.forEach { soil ->
                    world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=7]")
                    world.getBlockAt(soil.x, soil.y + 1, soil.z).blockData =
                        (Material.WHEAT.createBlockData() as Ageable).also { it.age = 2 }
                }
                CivsWorksiteLayouts.irrigation(farm).forEach { world.getBlockAt(it.x, it.y, it.z).type = Material.WATER }
                var paused = false
                CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                    val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, { paused })
                    repeat(4) {
                        runtime.pulse(listOf(city))
                        runtime.plan(city, worker) shouldBe null
                        runtime.idleReason(city, worker) shouldBe "searching-work"
                    }
                    runtime.pulse(listOf(city))
                    runtime.idleReason(city, worker) shouldBe "waiting-growth"
                    repeat(12) {
                        paused = true
                        runtime.pulse(listOf(city))
                        runtime.idleReason(city, worker) shouldBe "waiting-growth"
                        runtime.plan(city, worker) shouldBe null
                        paused = false
                        runtime.pulse(listOf(city))
                        runtime.idleReason(city, worker) shouldBe "waiting-growth"
                    }
                    val empty = plots.first().let { it.copy(y = it.y + 1) }
                    world.getBlockAt(empty.x, empty.y, empty.z).type = Material.AIR
                    runtime.invalidate(worker.id)
                    repeat(8) { if (runtime.idleReason(city, worker) != "waiting-seeds") runtime.pulse(listOf(city)) }
                    runtime.plan(city, worker) shouldBe null
                    runtime.idleReason(city, worker) shouldBe "waiting-seeds"
                    runtime.requirement(city, worker) shouldBe ("WHEAT_SEEDS" to 64L)
                    paused = true
                    runtime.idleReason(city, worker) shouldBe "waiting-seeds"
                    paused = false
                    val ripe = plots[1].let { it.copy(y = it.y + 1) }
                    world.getBlockAt(ripe.x, ripe.y, ripe.z).blockData =
                        (Material.WHEAT.createBlockData() as Ageable).also { it.age = it.maximumAge }
                    repeat(8) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
                    val harvest = requireNotNull(runtime.plan(city, worker))
                    harvest.status shouldBe "working-field"
                    harvest.focus shouldBe ripe
                }
                tasks.close()
            }
        }
    }

    for ((name, storm, covered, age, moisture, watering) in listOf(
        FarmWeatherCase("exposed-rain", true, false, 2, 0, false),
        FarmWeatherCase("exposed-rain-checkpoint", true, false, 3, 7, false),
        FarmWeatherCase("covered-rain", true, true, 2, 0, true),
        FarmWeatherCase("healthy-wet-soil", false, false, 2, 7, false),
        FarmWeatherCase("dry-checkpoint", false, false, 3, 7, true),
    )) test("farmer care respects $name without empty watering receipts") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val world = CivsWorksiteWorldFixture("care-$name").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("FarmWeather")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                val farm = spatialBuilding("medievaloak", "farmer", 1, CivPosition(0, 63, 0)).copy(production = "WHEAT")
                val worker = spatialWorker(farm, "IRON_HOE")
                val city = spatialCity(world.uid, listOf(farm), worker)
                val plots = CivsWorksiteLayouts.field(farm)
                for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                plots.forEach { soil ->
                    world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=7]")
                    world.getBlockAt(soil.x, soil.y + 1, soil.z).type = Material.STONE
                }
                val soil = plots.first()
                val crop = soil.copy(y = soil.y + 1)
                world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=$moisture]")
                world.getBlockAt(crop.x, crop.y, crop.z).blockData =
                    (Material.WHEAT.createBlockData() as Ageable).also { it.age = age }
                if (covered) world.getBlockAt(crop.x, crop.y + 3, crop.z).type = Material.STONE
                CivsWorksiteLayouts.irrigation(farm).forEach { world.getBlockAt(it.x, it.y, it.z).type = Material.WATER }
                world.setStorm(storm)
                CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                    val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                    repeat(16) { runtime.pulse(listOf(city)) }
                    val work = runtime.plan(city, worker)
                    if (watering) {
                        val wateringWork = requireNotNull(work)
                        wateringWork.status shouldBe "watering"
                        wateringWork.changes.isNotEmpty() shouldBe true
                        wateringWork.outputs shouldBe emptyMap()
                        wateringWork.inputs shouldBe emptyMap()
                        runtime.validate(city, wateringWork)
                        if (!covered) {
                            world.setStorm(true)
                            shouldThrow<CivsRejection> { runtime.validate(city, wateringWork) }.reason shouldBe "stale-worksite"
                        }
                    } else work shouldBe null
                }
                tasks.close()
            }
        }
    }

    for (style in listOf("medievaloak", "nordic")) {
        test("$style farmer care rotates through every actionable assigned plot after a bounded full field scan") {
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
                        world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=0]")
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
                            work.changes.size shouldBe 1
                            visited += requireNotNull(work.focus)
                            runtime.validate(city, work)
                            work.changes.forEach { cell ->
                                world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData = Bukkit.createBlockData(cell.after)
                            }
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
                    repeat(16) { if (runtime.plan(city, city.workers.single()) == null) runtime.pulse(listOf(city)) }
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
                        pickup.status shouldBe "collecting-supplies"
                        pickup.durationMillis shouldBe 1_000L
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

    test("a full resident bag blocks quarry pickup without loss and a completed cursor cannot replace a changed generator") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("mine-pickup-full").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("MinePickupFull")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
                for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
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
                        val steps = requireNotNull(CivsMinePlan.forBuilding(mine)).steps.size
                        city = city.copy(minePlans = mapOf(mine.id.toString() to CivMinePlanState(CivsMinePlan.VERSION, steps.toLong())))
                        val face = CivsWorksiteLayouts.point(mine, requireNotNull(CivsMinePlan.forBuilding(mine)!!.generator))
                        world.getBlockAt(face.x, face.y, face.z).type = Material.DIRT
                        runtime.invalidate(worker.id)
                        runtime.pulse(listOf(city))
                        runtime.plan(city, worker) shouldBe null
                        runtime.idleReason(city, worker) shouldBe "worksite-obstacle"
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

    fun seedV4MinePrefix(world: CivsWorksiteWorldFixture, mine: CivBuilding, prefix: Long) {
        val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
        require(mine.schematicId == "civs/minecolonies/medievaloak/fundamentals/miner1")
        require(prefix in 0..geometry.steps.size.toLong())
        fun data(spec: String) = Bukkit.createBlockData(spec).also { block ->
            if (!block.material.isAir) block.rotate(when (Math.floorMod(mine.rotation / 90, 4)) {
                1 -> org.bukkit.block.structure.StructureRotation.CLOCKWISE_90
                2 -> org.bukkit.block.structure.StructureRotation.CLOCKWISE_180
                3 -> org.bukkit.block.structure.StructureRotation.COUNTERCLOCKWISE_90
                else -> org.bukkit.block.structure.StructureRotation.NONE
            })
        }
        for (step in geometry.steps.take(prefix.toInt())) {
            val point = CivsWorksiteLayouts.point(mine, step.point)
            world.getBlockAt(point.x, point.y, point.z).blockData = data(step.after ?: "minecraft:air")
        }
        val bottom = CivsWorksiteLayouts.point(mine, geometry.bottom)
        // Hash-pinned medievaloak miner1 supplies exactly the three upper rungs
        // at local -5..-3, two air rows above them and the dirt cap at local 0.
        for (dy in 0..5) {
            val point = bottom.copy(y = bottom.y + dy)
            val block = world.getBlockAt(point.x, point.y, point.z)
            when {
                dy <= 2 -> block.blockData = data("minecraft:ladder[facing=${geometry.facing},waterlogged=false]")
                dy < 5 -> block.type = Material.AIR
                else -> block.type = Material.DIRT
            }
            if (dy < 5) {
                val backing = CivsWorksiteLayouts.point(mine, geometry.backing).copy(y = point.y)
                world.getBlockAt(backing.x, backing.y, backing.z).type = Material.COBBLESTONE
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
                            repeat(12) { runtime.pulse(listOf(city)) }
                            runtime.plan(city, city.workers.single()) shouldBe null
                            runtime.idleReason(city, city.workers.single()) shouldBe "worksite-unbreakable"
                            world.getBlockAt(shaft.x, shaft.y, shaft.z).type shouldBe Material.BEDROCK
                            world.getBlockAt(shaft.x, shaft.y, shaft.z).type = Material.STONE
                            shaftLocked = true
                            repeat(12) { runtime.pulse(listOf(city)) }
                            runtime.plan(city, city.workers.single()) shouldBe null
                            runtime.idleReason(city, city.workers.single()) shouldBe "worksite-protected"
                            shaftLocked = false
                            repeat(12) { runtime.pulse(listOf(city)) }
                            val excavation = requireNotNull(runtime.plan(city, city.workers.single()))
                            excavation.changes.single().position shouldBe shaft
                            excavation.changes.single().after shouldBe "minecraft:air"
                            excavation.inputs shouldBe emptyMap()
                            excavation.outputs shouldBe mapOf("COBBLESTONE" to 1L)
                            excavation.miningStep shouldBe 0L
                            commit(excavation)
                            CivsEquipment.plainCount(city.workers.single(), "COBBLESTONE") shouldBe 65
                            CivsEquipment.plainCount(city.workers.single(), "LADDER") shouldBe 64
                        }

                        val visible = requireNotNull(citizenReachableBlockPoint(world, eye, support))
                        visible.y shouldBe support.y + .001
                        (visible.distance(eye) <= CITIZEN_BLOCK_REACH) shouldBe true
                        repeat(12) { runtime.pulse(listOf(city)) }
                        val wall = requireNotNull(runtime.plan(city, city.workers.single()))
                        wall.standing shouldBe bottom
                        citizenCanReachBlock(world, Location(world, wall.standing.x + .5, wall.standing.y + 1.62, wall.standing.z + .5), support) shouldBe true
                        wall.focus shouldBe support
                        wall.changes.single().position shouldBe support
                        wall.changes.single().after shouldBe "minecraft:cobblestone"
                        wall.inputs shouldBe mapOf("COBBLESTONE" to 1L)
                        wall.outputs shouldBe emptyMap()
                        wall.status shouldBe "digging-mine"
                        // The backing is an explicit paid shaft step; inner shell
                        // repair no longer fills the future wide clearance columns.
                        wall.miningStep shouldBe 1L
                        commit(wall)
                        CivsEquipment.plainCount(city.workers.single(), "COBBLESTONE") shouldBe
                            if (shaftMaterial == Material.STONE) 64 else 63
                        CivsEquipment.plainCount(city.workers.single(), "LADDER") shouldBe 64
                        CivsEquipment.plainCount(city.workers.single(), "TORCH") shouldBe 17

                        repeat(12) { runtime.pulse(listOf(city)) }
                        val ladder = requireNotNull(runtime.plan(city, city.workers.single()))
                        ladder.changes.single().position shouldBe shaft
                        ladder.changes.single().after shouldBe "minecraft:ladder[facing=west,waterlogged=false]"
                        ladder.inputs shouldBe mapOf("LADDER" to 1L)
                        ladder.outputs shouldBe emptyMap()
                        ladder.miningStep shouldBe 2L
                        commit(ladder)
                        CivsEquipment.plainCount(city.workers.single(), "COBBLESTONE") shouldBe
                            if (shaftMaterial == Material.STONE) 64 else 63
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
                val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
                // Use gallery feet level so every tested stance also has authored headroom.
                val local = geometry.floors.first().let { floor -> floor.cells[floor.sections.first().second + 9] }.let { it.copy(y = it.y + 1) }
                val step = geometry.steps.indexOfFirst { it.stage == "gallery" && it.point == local && it.after == null }
                val city = spatialCity(world.uid, listOf(mine), worker).copy(
                    minePlans = mapOf(mine.id.toString() to CivMinePlanState(CivsMinePlan.VERSION, step.toLong())))
                val target = CivsWorksiteLayouts.point(mine, local)
                val standing = target.copy(x = target.x + 2)
                CivsMinePlan.workStandingAllowed(mine, standing) shouldBe true
                for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                seedV4MinePrefix(world, mine, step.toLong())
                geometry.builtStanding(CivsMinePlan.localPoint(mine, standing), step.toLong()) shouldBe true
                for (y in target.y..target.y + 2) world.getBlockAt(standing.x, y, standing.z).type = Material.AIR
                // A gap permits the ray, but offers no floor for any of the old adjacent probes.
                for (y in target.y - 4..target.y + 2) world.getBlockAt(target.x + 1, y, target.z).type = Material.AIR
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
                        if (building.type == BuildingType.TOWN_HALL) building.copy(bounds = CivBox(
                            standing.copy(x = target.x + 1, z = target.z - 4),
                            standing.copy(x = target.x + 4, z = target.z + 4))) else building
                    })
                    runtime.invalidate(worker.id)
                    repeat(4) { runtime.pulse(listOf(protected)) }
                    runtime.plan(protected, worker) shouldBe null
                }
                tasks.close()
            }
        }
    }

    test("a persisted mine floor clears fallen gravel again without advancing its cursor or duplicating drops") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("mine-fallen-gravel").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("MineFallenGravel")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(-85, 68, -40)).copy(
                    rotation = 270, bounds = CivBox(CivPosition(-102, 63, -50), CivPosition(-86, 82, -33)),
                    entrance = CivPosition(-85, 69, -40), control = CivPosition(-93, 69, -40))
                val floor = CivPosition(-89, 56, -40)
                val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
                val floorStep = geometry.steps.indexOfFirst {
                    it.after == "minecraft:cobblestone" && CivsWorksiteLayouts.point(mine, it.point) == floor
                }.also { (it >= 0) shouldBe true }.toLong()
                var city = spatialCity(world.uid, listOf(mine), spatialWorker(mine, "IRON_PICKAXE"))
                    .copy(radius = 200, minePlans = mapOf(mine.id.toString() to CivMinePlanState(CivsMinePlan.VERSION, floorStep)))
                val lower = floor.copy(y = 57)
                val upper = floor.copy(y = 58)
                for (x in -7..-5) for (z in -4..-2) world.loadChunk(x, z)
                seedV4MinePrefix(world, mine, floorStep)
                // The saved production slice has a clear previous gallery and two fallen
                // gravel blocks above this already-excavated, still-unpaid floor step.
                for (y in 57..59) world.getBlockAt(-90, y, -40).type = Material.AIR
                world.getBlockAt(-90, 56, -40).type = Material.COBBLESTONE
                world.getBlockAt(-89, 59, -40).type = Material.AIR
                world.getBlockAt(lower.x, lower.y, lower.z).type = Material.GRAVEL
                world.getBlockAt(upper.x, upper.y, upper.z).type = Material.GRAVEL
                var locked = true
                CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                    val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, point -> locked && point == lower })
                    val workerId = city.workers.single().id
                    val originalStone = CivsEquipment.plainCount(city.workers.single(), "COBBLESTONE")
                    val received = mutableMapOf<String, Long>()
                    fun nextWork(): CivWorldWork {
                        repeat(64) {
                            runtime.pulse(listOf(city))
                            runtime.plan(city, city.workers.single())?.let { return it }
                        }
                        error("Mine failed to resume: ${runtime.idleReason(city, city.workers.single())}")
                    }
                    fun commit(work: CivWorldWork) {
                        runtime.validate(city, work)
                        geometry.builtStanding(CivsMinePlan.localPoint(mine, work.standing),
                            city.minePlans!!.getValue(mine.id.toString()).nextStep) shouldBe true
                        work.changes.forEach { cell ->
                            val block = world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
                            block.blockData.asString shouldBe cell.before
                            block.setBlockData(Bukkit.createBlockData(cell.after), false)
                        }
                        city = CivsRules.finishWorldWork(city, work, 20_000 + city.workers.single().completedCycles * 5_000)
                        work.outputs.forEach { (material, amount) -> received.merge(material, amount, Long::plus) }
                        shouldThrow<CivsRejection> { CivsRules.finishWorldWork(city, work, 30_000) }.reason shouldBe "stale-delivery"
                        runtime.invalidate(workerId)
                    }
                    try {
                        repeat(16) { runtime.pulse(listOf(city)) }
                        runtime.plan(city, city.workers.single()) shouldBe null
                        runtime.idleReason(city, city.workers.single()) shouldBe "worksite-protected"
                        city.workers.single().completedCycles shouldBe 0L
                        world.getBlockAt(lower.x, lower.y, lower.z).type shouldBe Material.GRAVEL
                        locked = false
                        val first = nextWork()
                        first.changes.single().position shouldBe lower
                        first.changes.single().after shouldBe "minecraft:air"
                        first.inputs shouldBe emptyMap()
                        first.outputs shouldBe mapOf("GRAVEL" to 1L)
                        first.miningStep shouldBe floorStep
                        // This AIR effect belongs to a COBBLESTONE floor step. A final
                        // check must use the falling block's actual exposed side.
                        val wall = lower.copy(x = lower.x + 1)
                        world.getBlockAt(wall.x, wall.y, wall.z).type shouldBe Material.STONE
                        world.getBlockAt(wall.x, wall.y, wall.z).type = Material.LAVA
                        shouldThrow<CivsRejection> { runtime.validate(city, first) }.reason shouldBe "stale-worksite"
                        city.workers.single().completedCycles shouldBe 0L
                        world.getBlockAt(lower.x, lower.y, lower.z).type shouldBe Material.GRAVEL
                        world.getBlockAt(wall.x, wall.y, wall.z).type = Material.STONE
                        citizenCanReachBlock(world, Location(world, first.standing.x + .5, first.standing.y + 1.62,
                            first.standing.z + .5), lower, CITIZEN_MINE_REACH) shouldBe true
                        commit(first)
                        city.minePlans!!.getValue(mine.id.toString()).nextStep shouldBe floorStep
                        // Model the native fall after clearing: the existing upper block
                        // occupies the same lower cell, rather than inventing another item.
                        world.getBlockAt(upper.x, upper.y, upper.z).type = Material.AIR
                        world.getBlockAt(lower.x, lower.y, lower.z).type = Material.GRAVEL
                        val fallenAgain = nextWork()
                        fallenAgain.changes.single().position shouldBe lower
                        fallenAgain.outputs shouldBe mapOf("GRAVEL" to 1L)
                        fallenAgain.miningStep shouldBe floorStep
                        commit(fallenAgain)
                        received["GRAVEL"] shouldBe 2L
                        city.minePlans!!.getValue(mine.id.toString()).nextStep shouldBe floorStep
                        var floorFinished = false
                        for (attempt in 0 until 8) {
                            val work = nextWork()
                            work.outputs.getOrDefault("GRAVEL", 0) shouldBe 0L
                            val paysFloor = work.changes.single().position == floor
                            work.miningStep shouldBe if (paysFloor) floorStep + 1 else floorStep
                            if (paysFloor) work.inputs shouldBe mapOf("COBBLESTONE" to 1L)
                            commit(work)
                            if (paysFloor) { floorFinished = true; break }
                        }
                        floorFinished shouldBe true
                        received["GRAVEL"] shouldBe 2L
                        city.minePlans!!.getValue(mine.id.toString()).nextStep shouldBe floorStep + 1
                        world.getBlockAt(floor.x, floor.y, floor.z).type shouldBe Material.COBBLESTONE
                        CivsEquipment.plainCount(city.workers.single(), "COBBLESTONE") shouldBe originalStone
                    } finally { tasks.close() }
                }
            }
        }
    }

    test("gravel arriving after a quarry floor was selected replaces the obstructed job before any paid receipt") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("mine-late-gravel").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("MineLateGravel")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(-85, 68, -40)).copy(
                    rotation = 270, bounds = CivBox(CivPosition(-102, 63, -50), CivPosition(-86, 82, -33)),
                    entrance = CivPosition(-85, 69, -40), control = CivPosition(-93, 69, -40))
                val floor = CivPosition(-93, 56, -36)
                val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
                val floorStep = geometry.steps.indexOfFirst {
                    it.after == "minecraft:cobblestone" && CivsWorksiteLayouts.point(mine, it.point) == floor
                }.also { (it >= 0) shouldBe true }.toLong()
                var city = spatialCity(world.uid, listOf(mine), spatialWorker(mine, "IRON_PICKAXE"))
                    .copy(radius = 200, minePlans = mapOf(mine.id.toString() to CivMinePlanState(CivsMinePlan.VERSION, floorStep)))
                val fallen = floor.copy(y = 57)
                for (x in -7..-5) for (z in -4..-2) world.loadChunk(x, z)
                seedV4MinePrefix(world, mine, floorStep)
                var paused = false
                CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                    val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, { paused })
                    val workerId = city.workers.single().id
                    val originalStone = CivsEquipment.plainCount(city.workers.single(), "COBBLESTONE")
                    fun nextWork(): CivWorldWork {
                        repeat(64) {
                            val before = world.rayTraces
                            runtime.pulse(listOf(city))
                            (world.rayTraces - before <= 8) shouldBe true
                            runtime.plan(city, city.workers.single())?.let { return it }
                        }
                        error("Mine failed to replan: ${runtime.idleReason(city, city.workers.single())}")
                    }
                    fun commit(work: CivWorldWork) {
                        runtime.validate(city, work)
                        geometry.builtStanding(CivsMinePlan.localPoint(mine, work.standing),
                            city.minePlans!!.getValue(mine.id.toString()).nextStep) shouldBe true
                        work.changes.forEach { cell ->
                            world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
                                .setBlockData(Bukkit.createBlockData(cell.after), false)
                        }
                        city = CivsRules.finishWorldWork(city, work, 20_000 + city.workers.single().completedCycles * 5_000)
                        runtime.invalidate(workerId)
                    }
                    try {
                        val paidFloor = nextWork()
                        paidFloor.changes.single().position shouldBe floor
                        paidFloor.inputs shouldBe mapOf("COBBLESTONE" to 1L)
                        geometry.builtStanding(CivsMinePlan.localPoint(mine, paidFloor.standing), floorStep) shouldBe true
                        // The falling block was in flight when the floor ray was admitted.
                        world.getBlockAt(fallen.x, fallen.y, fallen.z).type = Material.GRAVEL
                        citizenCanReachBlock(world, Location(world, paidFloor.standing.x + .5,
                            paidFloor.standing.y + 1.62, paidFloor.standing.z + .5), floor, CITIZEN_MINE_REACH) shouldBe false
                        paused = true
                        repeat(4) { runtime.pulse(listOf(city)) }
                        runtime.plan(city, city.workers.single()) shouldBe paidFloor
                        paused = false
                        val clearance = nextWork()
                        clearance.changes.single().position shouldBe fallen
                        clearance.changes.single().after shouldBe "minecraft:air"
                        clearance.inputs shouldBe emptyMap()
                        clearance.outputs shouldBe mapOf("GRAVEL" to 1L)
                        clearance.miningStep shouldBe floorStep
                        city.workers.single().completedCycles shouldBe 0L
                        CivsEquipment.plainCount(city.workers.single(), "COBBLESTONE") shouldBe originalStone
                        commit(clearance)
                        city.minePlans!!.getValue(mine.id.toString()).nextStep shouldBe floorStep
                        val resumed = nextWork()
                        resumed.changes.single().position shouldBe floor
                        resumed.inputs shouldBe mapOf("COBBLESTONE" to 1L)
                        commit(resumed)
                        city.workers.single().completedCycles shouldBe 2L
                        city.minePlans!!.getValue(mine.id.toString()).nextStep shouldBe floorStep + 1
                        world.getBlockAt(floor.x, floor.y, floor.z).type shouldBe Material.COBBLESTONE
                        CivsEquipment.plainCount(city.workers.single(), "COBBLESTONE") shouldBe originalStone
                    } finally { tasks.close() }
                }
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
                val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
                // Use gallery feet level so the ray probes retain two authored body rows.
                val local = geometry.floors.first().let { floor -> floor.cells[floor.sections.first().second + 9] }.let { it.copy(y = it.y + 1) }
                val step = geometry.steps.indexOfFirst { it.stage == "gallery" && it.point == local && it.after == null }
                val city = spatialCity(world.uid, listOf(mine), worker).copy(
                    minePlans = mapOf(mine.id.toString() to CivMinePlanState(CivsMinePlan.VERSION, step.toLong())))
                val target = CivsWorksiteLayouts.point(mine, local)
                for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                seedV4MinePrefix(world, mine, step.toLong())
                // Reproduce a new obstruction in the built gallery, rather than
                // borrowing empty future cells as unsupported work positions.
                for (dx in -1..1) for (dz in -1..1) if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz)) == 1)
                    for (dy in 0..2) world.getBlockAt(target.x + dx, target.y + dy, target.z + dz).type = Material.STONE
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

                        for (dy in 0..2) world.getBlockAt(target.x + 1, target.y + dy, target.z).type = Material.AIR
                        runtime.invalidate(worker.id)
                        runtime.pulse(listOf(city))
                        val recovered = requireNotNull(runtime.plan(city, worker))
                        recovered.focus shouldBe target
                        geometry.builtStanding(CivsMinePlan.localPoint(mine, recovered.standing), step.toLong()) shouldBe true
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

    for ((style, legacy) in listOf("nordic" to false, "medievaloak" to false, "caledonia" to false, "medievaloak" to true)) {
        val scenario = if (legacy) "$style legacy miner physically migrates V3 step83 and completes its first V4 level"
            else "$style miner physically completes three then four then five aligned floors"
        test(scenario) {
            failOnUnsupportedMockBukkitOperation {
                MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                    val world = CivsWorksiteWorldFixture("mine-$style-${if (legacy) "legacy" else "fresh"}").also(paper.server::addWorld)
                    val plugin = paper.createSimplePlugin("SpatialMine")
                    val messages = mutableListOf<String>()
                    plugin.logger.addHandler(worksiteLogHandler(messages))
                    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                    val rotation = if (legacy) 270 else mapOf("nordic" to 0, "medievaloak" to 90, "caledonia" to 180).getValue(style)
                    var mine = spatialBuilding(style, "miner", 1, if (legacy) CivPosition(-85, 68, -40) else CivPosition(0, 63, 0), rotation)
                    if (legacy) mine = mine.copy(control = CivPosition(-93, 69, -40))
                    var city = spatialCity(world.uid, listOf(mine), spatialWorker(mine, "NETHERITE_PICKAXE"))
                    val oldPlan = if (legacy) requireNotNull(CivsMinePlan.forBuilding(mine, 3)) else null
                    val oldPrefix = oldPlan?.completed(83).orEmpty()
                    val oldNative = if (legacy) requireNotNull(oldPlan!!.legacyCompleted(214)) + oldPrefix else emptyMap()
                    val oldRungs = oldPrefix.filter { (point, after) ->
                        after?.startsWith("minecraft:ladder[") == true &&
                            point.x == oldPlan!!.extensionLadder.x && point.z == oldPlan.extensionLadder.z
                    }.keys
                    if (legacy) {
                        city = CivsRules.validate(city.copy(center = CivPosition(-25, 64, -20), radius = 200,
                            mineProgress = mapOf(mine.id.toString() to 214L),
                            minePlans = mapOf(mine.id.toString() to CivMinePlanState(3, 83, legacyStep = 214))))
                        // Confirmed retained subset, not a reconstruction of the whole live world.
                        for ((local, after) in oldNative) {
                            val point = CivsWorksiteLayouts.point(mine, local)
                            world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(after ?: "minecraft:air").also {
                                if (after != null) it.rotate(org.bukkit.block.structure.StructureRotation.COUNTERCLOCKWISE_90)
                            }
                        }
                        (oldRungs.isNotEmpty()) shouldBe true
                        for (local in oldRungs) {
                            val point = CivsWorksiteLayouts.point(mine, local)
                            world.getBlockAt(point.x, point.y, point.z).type shouldBe Material.LADDER
                        }
                        world.getBlockAt(-89, 62, -40).type shouldBe Material.STONE
                        world.getBlockAt(-88, 62, -40).type = Material.AIR
                    }
                    val layout = CivsWorksiteLayouts.site(mine)!!.getAsJsonObject("mine")
                    val bottom = CivsWorksiteLayouts.point(mine, CivsWorksiteLayouts.vector(layout["ladderBottom"]))
                    val facing = layout["ladderFacing"].asString
                    // The schematic supplies this upper ladder; untouched stone starts immediately below it.
                    for (y in bottom.y..65) world.getBlockAt(bottom.x, y, bottom.z).blockData =
                        Bukkit.createBlockData("minecraft:ladder[facing=$facing,waterlogged=false]").also {
                            it.rotate(when (rotation) {
                                90 -> org.bukkit.block.structure.StructureRotation.CLOCKWISE_90
                                180 -> org.bukkit.block.structure.StructureRotation.CLOCKWISE_180
                                270 -> org.bukkit.block.structure.StructureRotation.COUNTERCLOCKWISE_90
                                else -> org.bukkit.block.structure.StructureRotation.NONE
                            })
                        }
                    val chunkX = mine.anchor.x shr 4
                    val chunkZ = mine.anchor.z shr 4
                    for (x in chunkX - 3..chunkX + 3) for (z in chunkZ - 3..chunkZ + 2) world.loadChunk(x, z)
                    val control = requireNotNull(mine.control)
                    if (legacy) {
                        // Curated miner1 (c54e00bc...) has oak flooring at local (0,0,-9..-7)
                        // beside its heart (0,1,-8), supplying native access above this fixture's stone plane.
                        for (dx in -1..1) world.getBlockAt(control.x + dx, control.y - 1, control.z).type = Material.OAK_PLANKS
                    }
                    world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
                    if (legacy) {
                        val standing = requireNotNull(citizenStorageTarget(city, mine, world))
                        citizenCanReachBlock(world, Location(world, standing.x + .5, standing.y + 1.62,
                            standing.z + .5), control) shouldBe true
                    }
                    val counts = mutableMapOf("COBBLESTONE" to 1_000L, "LADDER" to 1_000L, "TORCH" to 1_000L,
                        "IRON_PICKAXE" to 100L)
                    if (style == "nordic") {
                        val floor = requireNotNull(CivsMinePlan.forBuilding(mine)).floors.first()
                        val leftMouth = floor.cells[floor.sections.take(2).sumOf { it.second }]
                        // A real cave has an empty future left-arm floor. It must be paid
                        // when that arm starts, never while the main corridor is unfinished.
                        for (height in 0..3) {
                            val p = CivsWorksiteLayouts.point(mine, leftMouth.copy(y = leftMouth.y + height))
                            world.getBlockAt(p.x, p.y, p.z).type = Material.AIR
                        }
                    }
                    var actions = 0
                    var placedTorches = 0L
                    var boundaryCut = false
                    var boundarySealed = false
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        val runtime = CivsWorksites(plugin, access, { _, _ -> counts }, { _, _ -> false })
                        for (unlocked in 1..(if (legacy) 1 else 3)) {
                            if (unlocked > 1) {
                                val next = mine.copy(level = unlocked, schematicId = mine.schematicId.dropLast(1) + unlocked,
                                    phase = BuildingPhase.CONSTRUCTING)
                                val required = CivsRules.workRequired(BuildingType.QUARRY, unlocked)
                                val order = CivConstruction(UUID.randomUUID(), mine.id, unlocked, required, required,
                                    ConstructionPhase.PLACING, CivsRules.cost(BuildingType.QUARRY, unlocked), plannedBuilding = next)
                                city = CivsRules.completeConstruction(city.copy(construction = order), city.owner, order.id)
                                city.mineProgress.orEmpty()[mine.id.toString()] shouldBe null
                                city.minePlans.orEmpty()[mine.id.toString()] shouldBe null
                                mine = requireNotNull(city.building(mine.id))
                            }
                            val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
                            val shaftColumns = geometry.shaftColumns.mapTo(hashSetOf()) { (x, z) ->
                                CivsWorksiteLayouts.point(mine, CivPosition(x, 0, z)).let { it.x to it.z }
                            }
                            fun assertWideShaft() {
                                val platforms = geometry.floors.flatMap { it.cells }.toSet()
                                for (y in geometry.lowest until geometry.bottom.y) for ((x, z) in geometry.shaftColumns) {
                                    val local = CivPosition(x, y, z)
                                    val p = CivsWorksiteLayouts.point(mine, local)
                                    val type = world.getBlockAt(p.x, p.y, p.z).type
                                    withClue("$style L$unlocked shaft $local=$type, persisted ${city.minePlans}") {
                                        when {
                                            local in geometry.transferFloor -> type shouldBe Material.COBBLESTONE
                                            y >= geometry.transferFeetY && x == geometry.bottom.x && z == geometry.bottom.z -> type shouldBe Material.LADDER
                                            y >= geometry.transferFeetY && x == geometry.backing.x && z == geometry.backing.z -> type.isOccluding shouldBe true
                                            y <= geometry.transferFeetY && x == geometry.extensionLadder.x && z == geometry.extensionLadder.z -> type shouldBe Material.LADDER
                                            y <= geometry.transferFeetY && x == geometry.extensionBacking.x && z == geometry.extensionBacking.z -> type.isOccluding shouldBe true
                                            local in platforms -> type shouldBe Material.COBBLESTONE
                                            else -> (type.isAir || type == Material.TORCH) shouldBe true
                                        }
                                    }
                                }
                            }
                            val galleryOrder = buildMap<CivPosition, Int> {
                                for (floor in geometry.floors) {
                                    var index = 0
                                    for ((branch, part) in floor.sections.withIndex()) {
                                        if (branch > 0) for (support in floor.cells.subList(index, index + part.second)) for (height in 0..3)
                                            put(CivsWorksiteLayouts.point(mine, support.copy(y = support.y + height)), floor.index * 16 + branch)
                                        index += part.second
                                    }
                                }
                            }
                            var lastGallery = -1
                            var finished = false
                            var emptyPulses = 0
                            while (actions < 20_000 && emptyPulses < geometry.steps.size + 32) {
                                if (city.minePlans?.get(mine.id.toString())?.nextStep == geometry.steps.size.toLong()) {
                                    finished = true
                                    break
                                }
                                runtime.pulse(listOf(city))
                                val worker = city.workers.single()
                                val work = runtime.plan(city, worker)
                                if (work == null) {
                                    val reason = runtime.idleReason(city, worker)
                                    if (reason != "searching-work") break
                                    emptyPulses++
                                    continue
                                }
                                emptyPulses = 0
                                withClue("$style action ${actions + 1}, mine step ${work.miningStep}, standing ${work.standing}") {
                                    citizenStandable(world, work.standing) shouldBe true
                                }
                                runtime.validate(city, work)
                                work.miningGenerator shouldBe false
                                if (legacy) {
                                    work.changes.singleOrNull()?.let { change ->
                                        if (change.position == CivPosition(-89, 62, -40) && change.after == "minecraft:air") {
                                            change.before shouldBe "minecraft:stone"
                                            work.miningStep shouldBe 216L
                                            world.getBlockAt(-88, 62, -40).type shouldBe Material.AIR
                                            boundaryCut = true
                                        }
                                        if (change.position == CivPosition(-88, 62, -40)) {
                                            boundaryCut shouldBe true
                                            change.before shouldBe "minecraft:air"
                                            change.after shouldBe "minecraft:cobblestone"
                                            work.status shouldBe "securing-mine"
                                            work.inputs shouldBe mapOf("COBBLESTONE" to 1L)
                                            work.miningStep shouldBe 216L
                                            boundarySealed = true
                                        }
                                    }
                                }
                                work.changes.singleOrNull()?.position?.takeIf {
                                    (it.x to it.z) !in shaftColumns
                                }?.let { point -> galleryOrder[point]?.let { phase ->
                                    withClue("$style gallery phase $phase after $lastGallery at $point") {
                                        (phase >= lastGallery) shouldBe true
                                    }
                                    lastGallery = phase
                                } }
                                if (work.changes.isNotEmpty()) placedTorches += work.inputs.getOrDefault("TORCH", 0)
                                work.storageInputs(worker).forEach { (key, amount) ->
                                    counts[key] = counts.getValue(key) - amount
                                    (counts.getValue(key) >= 0) shouldBe true
                                }
                                work.outputs.forEach { (key, amount) -> counts[key] = counts.getOrDefault(key, 0) + amount }
                                work.changes.forEach { cell -> world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
                                    .setBlockData(Bukkit.createBlockData(cell.after), false) }
                                city = CivsRules.finishWorldWork(city, work, 20_000 + actions * 5_000L)
                                // This fixture owns only geometry; simulate immediate collection
                                // into its stock map after the real work put output into the bag.
                                city = city.copy(workers = city.workers.map { CivsEquipment.consumeInputs(it, work.outputs) })
                                runtime.invalidate(worker.id)
                                actions++
                            }
                            val committedStep = city.minePlans?.get(mine.id.toString())?.nextStep?.toInt() ?: 0
                            withClue("$style L$unlocked actions=$actions idle=${runtime.idleReason(city, city.workers.single())} progress=${city.minePlans} step=${geometry.steps.getOrNull(committedStep)} diagnostics=${messages.takeLast(2)}") {
                                finished shouldBe true
                            }
                            assertWideShaft()
                            if (legacy) {
                                boundaryCut shouldBe true
                                boundarySealed shouldBe true
                                city.minePlans!!.getValue(mine.id.toString()) shouldBe CivMinePlanState(4,
                                    geometry.steps.size.toLong(), legacyStep = 214, v3Step = 83)
                                for (local in oldRungs) {
                                    val point = CivsWorksiteLayouts.point(mine, local)
                                    (world.getBlockAt(point.x, point.y, point.z).type != Material.LADDER) shouldBe true
                                }
                                val authoredRungs = (0..2).mapTo(hashSetOf()) { bottom.copy(y = bottom.y + it) }
                                val expectedRungs = geometry.completed(geometry.steps.size.toLong())!!.filterValues {
                                    it?.startsWith("minecraft:ladder[") == true
                                }.keys.mapTo(hashSetOf()) { CivsWorksiteLayouts.point(mine, it) } + authoredRungs
                                val nativeCells = (geometry.steps.map { it.point } + oldNative.keys)
                                    .mapTo(hashSetOf()) { CivsWorksiteLayouts.point(mine, it) } + authoredRungs
                                nativeCells.filterTo(hashSetOf()) { point ->
                                    world.getBlockAt(point.x, point.y, point.z).type == Material.LADDER
                                } shouldBe expectedRungs
                                expectedRungs.all { it.x == bottom.x && it.z == bottom.z } shouldBe true
                                for (point in expectedRungs)
                                    world.getBlockAt(point.x, point.y, point.z).blockData.asString shouldBe
                                        "minecraft:ladder[facing=south,waterlogged=false]"
                            }
                            for (floor in geometry.floors) {
                                for (cell in floor.cells) {
                                    val support = CivsWorksiteLayouts.point(mine, cell)
                                    withClue("$style L$unlocked after $actions actions, persisted ${city.minePlans}; support $support") {
                                        world.getBlockAt(support.x, support.y, support.z).type shouldBe Material.COBBLESTONE
                                        for (dy in 1..3) {
                                            val type = world.getBlockAt(support.x, support.y + dy, support.z).type
                                            (type.isAir || dy == 1 && type == Material.TORCH) shouldBe true
                                        }
                                    }
                                }
                            }
                        }
                        // Fixed side lights are preserved by each level's independent scan.
                        if (!legacy) placedTorches shouldBe 260L
                    }
                    tasks.close()
                }
            }
        }
    }
})

internal fun spatialBuilding(style: String, role: String, level: Int, anchor: CivPosition, rotation: Int = 0): CivBuilding {
    val type = when (role) { "farmer" -> BuildingType.FARM; "lumberjack" -> BuildingType.FORESTER; else -> BuildingType.QUARRY }
    val folder = if (role == "farmer") "agriculture/horticulture" else "fundamentals"
    val blank = CivBuilding(UUID.randomUUID(), type, level, anchor, rotation, "civs/minecolonies/$style/$folder/$role$level",
        CivBox(anchor, anchor), anchor.copy(y = anchor.y + 1), BuildingPhase.ACTIVE, control = anchor.copy(y = anchor.y + 1, z = anchor.z - 3))
    val box = CivsWorksiteLayouts.site(blank)!!.getAsJsonObject("schematicBounds")
    val a = CivsWorksiteLayouts.point(blank, CivsWorksiteLayouts.vector(box["min"]))
    val b = CivsWorksiteLayouts.point(blank, CivsWorksiteLayouts.vector(box["max"]))
    return blank.copy(bounds = CivBox(CivPosition(minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z)),
        CivPosition(maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z))))
}

internal fun spatialWorker(building: CivBuilding, material: String, supplies: Boolean = true) = CivWorker(UUID.randomUUID(), "Рабочий", building.id,
    nextDeliveryAt = 1,
    items = MutableList<CivResidentItem?>(CivsEquipment.SLOTS) { null }.also {
        it[4] = CivResidentItem(material, 1)
        if (supplies && building.type == BuildingType.QUARRY) CivsLogistics.QUARRY_SUPPLIES.entries.forEachIndexed { index, (supply, amount) ->
            it[CivsEquipment.BAG + index] = CivResidentItem(supply, amount.toInt())
        }
    })

internal fun spatialCity(worldId: UUID, buildings: List<CivBuilding>, worker: CivWorker): Settlement {
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

private data class FarmWeatherCase(val name: String, val storm: Boolean, val covered: Boolean, val age: Int, val moisture: Int, val watering: Boolean)
