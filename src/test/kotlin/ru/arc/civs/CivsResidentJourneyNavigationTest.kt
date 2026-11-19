package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.util.BoundingBox
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.Executor

class CivsResidentJourneyNavigationTest : FunSpec({
    test("the early approach to a distant gate climbs its slab and follows the turn past a blind alley") {
        residentRuntime(server = CivsWorksiteServerFixture()) { h ->
            val gate = CivPosition(24, 64, 0)
            fun street(x: Int, z: Int) = x == 0 && z in 0..4 || x in 0..24 && z == 4 ||
                x == 24 && z in 0..4 || x in 0..5 && z == 0
            for (x in -1..25) for (z in -1..5) {
                if (!street(x, z)) for (y in 64..66) h.world.getBlockAt(x, y, z).type = Material.STONE
            }
            h.world.getBlockAt(0, 64, 1).blockData = Bukkit.createBlockData("minecraft:oak_slab[type=bottom,waterlogged=false]")
            h.world.getBlockAt(gate.x, gate.y, gate.z).blockData =
                Bukkit.createBlockData("minecraft:oak_fence_gate[facing=north,in_wall=false,open=true,powered=false]")
            val allowed: (CivPosition) -> Boolean = { street(it.x, it.z) && it.y in 64..65 }
            var at = Location(h.world, .5, 64.0, .5)
            val route = CitizenPacketRoute(h.world, at, gate.location(h.world), allowed, { _, _ -> true }, { 1.0 },
                settings = CivsNavigationSettings(searchNodesPerBatch = 4),
                snapshotPolicy = CitizenSnapshotPolicy({ p, _ -> allowed(p) }, { _, _ -> true }, earlyMovement = true))
            var earlyClimb = false
            var extendedWhileWalking = false
            var passedTurn = false
            for (tick in 1..2_000) {
                val previousSteps = route.remainingNodes
                repeat(16) { if (route.searching) route.expand() }
                if (route.searching && route.forwardProgress > 0 && route.remainingNodes > previousSteps) extendedWhileWalking = true
                route.move(at, 1.0) { true }?.let { at = it }
                if (route.searching && at.y > 64.0) earlyClimb = true
                if (at.blockZ == 4) passedTurn = true
                if (!passedTurn) (at.x < 1.0) shouldBe true
                if (route.finished || route.failure != null) break
            }
            route.failure shouldBe null
            earlyClimb shouldBe true
            extendedWhileWalking shouldBe true
            passedTurn shouldBe true
            at.point() shouldBe gate.point()
        }
    }

    for (pastJunction in listOf(false, true)) test("a final branch replaces only unused early steps pastJunction=$pastJunction") {
        residentRuntime { h ->
            var at = Location(h.world, .5, 64.0, .5, -90f, 0f)
            val origin = at.point()
            fun step(x: Int, z: Int) = CitizenPlannedStep(CitizenPoint(x + .5, 64.0, z + .5), CitizenRouteAction.WALK)
            val offered = (1..6).map { step(it, 0) }
            val terminal = offered.take(2) + listOf(step(2, 1), step(3, 1), step(4, 1))
            val end = terminal.last().point
            val route = CitizenPacketRoute(h.world, at, Location(h.world, end.x, end.y, end.z),
                { true }, { _, _ -> true }, { CivsPaths.ORDINARY_COST })
            route.recordSearch(CitizenSearchResult(emptyList(), 10, earlyWalk = CitizenEarlyWalk(origin, offered.take(3))))
            route.move(at, 1.0) { true }?.let { at = it }
            route.recordSearch(CitizenSearchResult(emptyList(), 14, earlyWalk = CitizenEarlyWalk(offered[2].point, offered.drop(3))))
            if (pastJunction) while (at.x < 3.0) at = requireNotNull(route.move(at, 1.0) { true })
            val progress = route.forwardProgress
            route.acceptSearch(CitizenSearchResult(emptyList(), 30, path = terminal))
            route.forwardProgress shouldBe progress
            if (pastJunction) {
                route.failure shouldBe "path-changed"
                route.endpoint shouldBe null
            } else {
                route.failure shouldBe null
                for (tick in 1..80) {
                    val before = at
                    route.move(at, 1.0) { true }?.let { at = it }
                    (at.x >= before.x && at.distance(before) <= .215001) shouldBe true
                    if (route.finished) break
                }
                at.point() shouldBe end
            }
        }
    }

    for (direction in listOf(1, -1))
    test("a resident stops before an unloaded chunk mid-journey and replans after reload direction=$direction") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            var rejectUnloadedReads = false
            val world = object : CivsWorksiteWorldFixture("mid-walk-chunk-$direction") {
                override fun getBlockAt(x: Int, y: Int, z: Int): org.mockbukkit.mockbukkit.block.BlockMock {
                    if (rejectUnloadedReads) check(isChunkLoaded(x shr 4, z shr 4)) { "Native read in unloaded chunk at $x,$y,$z" }
                    return super.getBlockAt(x, y, z)
                }
            }.also(paper.server::addWorld)
            for (x in -2..2) for (z in -1..1) world.loadChunk(x, z)
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("MidWalkChunk")))
            val queue = BudgetedTaskQueue(tasks, maximumSteps = 512, nanoTime = { 0L })
            val workers = ArrayDeque<Runnable>()
            val owner = CivsResidentNavigation(tasks, queue, Executor { workers.addLast(it) })
            fun prepared(route: CitizenPacketRoute): CitizenPacketRoute {
                repeat(100) {
                    while (workers.isNotEmpty()) workers.removeFirst().run()
                    paper.performTicks(1); owner.pulse()
                    if (!route.searching) return route
                }
                error("Unfinished route: ${owner.debug()}")
            }
            var at = Location(world, direction * 12.0 + .5, 64.0, .5,
                if (direction == 1) -90f else 90f, 0f)
            val target = Location(world, direction * 20.0 + .5, 64.0, .5)
            try {
                val route = prepared(owner.request(world, at, target,
                    { it.y == 64 && it.z == 0 && it.x in -21..21 }, { _, _ -> true }, { 1.0 }))
                route.failure shouldBe null
                repeat(7) { route.move(at, 1.0) { true }?.let { at = it } }
                val beforeUnload = at.clone()
                val unloadedX = if (direction == 1) 1 else -2
                world.unloadChunk(unloadedX, 0) shouldBe true
                owner.removeChunk(world.uid, unloadedX, 0)
                rejectUnloadedReads = true
                repeat(40) { route.move(at, 1.0) { true }?.let { next ->
                    check(world.isChunkLoaded(next.blockX shr 4, next.blockZ shr 4))
                    at = next
                } }
                route.failure shouldBe "path-changed"
                route.finished shouldBe false
                (at.distance(beforeUnload) < 3.0) shouldBe true
                val stopped = at.clone()
                repeat(10) { route.move(at, 1.0) { true } shouldBe null }
                at shouldBe stopped
                world.loadChunk(unloadedX, 0)
                val resumed = prepared(owner.replan(route, at))
                resumed.failure shouldBe null
                repeat(160) { resumed.move(at, 1.0) { true }?.let { next ->
                    (at.distance(next) <= .215001) shouldBe true
                    citizenPacketBodyClear(world, CitizenPoint(next.x, next.y, next.z), false) shouldBe true
                    at = next
                } }
                resumed.finished shouldBe true
                at.distanceSquared(target) shouldBe 0.0
                owner.debug()["jobs"] shouldBe 0
            } finally { owner.clear(); queue.close(); tasks.close() }
        }
    }

    for ((direction, speed) in listOf((1 to 0) to .5, (-1 to 0) to 1.0, (0 to 1) to 2.0, (0 to -1) to .5))
    for (returning in listOf(false, true)) test("slab and stair journey stays clear direction=$direction returning=$returning speed=$speed") {
        val (dx, dz) = direction
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("slab-stair-journey", collisionForData = { data ->
                when {
                    data.material == Material.OAK_SLAB -> if ("type=top" in data.asString)
                        listOf(BoundingBox(0.0, .5, 0.0, 1.0, 1.0, 1.0))
                        else listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0))
                    data.material == Material.OAK_STAIRS -> listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0), when {
                        dx > 0 -> BoundingBox(.5, .5, 0.0, 1.0, 1.0, 1.0)
                        dx < 0 -> BoundingBox(0.0, .5, 0.0, .5, 1.0, 1.0)
                        dz > 0 -> BoundingBox(0.0, .5, .5, 1.0, 1.0, 1.0)
                        else -> BoundingBox(0.0, .5, 0.0, 1.0, 1.0, .5)
                    })
                    else -> null
                }
            }).also(paper.server::addWorld)
            for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
            world.getBlockAt(0, 63, 0).blockData = Bukkit.createBlockData("minecraft:oak_slab[type=top,waterlogged=false]")
            world.getBlockAt(dx, 64, dz).blockData = Bukkit.createBlockData("minecraft:oak_slab[type=bottom,waterlogged=false]")
            val facing = when { dx > 0 -> "east"; dx < 0 -> "west"; dz > 0 -> "south"; else -> "north" }
            world.getBlockAt(dx * 2, 64, dz * 2).blockData = Bukkit.createBlockData(
                "minecraft:oak_stairs[facing=$facing,half=bottom,shape=straight,waterlogged=false]")
            world.getBlockAt(dx * 3, 64, dz * 3).type = Material.OAK_PLANKS
            val low = Location(world, .5, 64.0, .5)
            val high = Location(world, dx * 3 + .5, 65.0, dz * 3 + .5)
            var at = (if (returning) high else low).clone().also {
                val sign = if (returning) -1 else 1
                it.yaw = Math.toDegrees(kotlin.math.atan2(-dx.toDouble() * sign, dz.toDouble() * sign)).toFloat()
            }
            val target = if (returning) low else high
            val route = CitizenPacketRoute(world, at, target,
                { p -> (0..3).any { p.x == dx * it && p.z == dz * it } && p.y in 64..65 },
                { _, _ -> true }, { 1.0 })
            repeat(10_000) { if (route.searching) route.expand() }
            route.searching shouldBe false
            route.failure shouldBe null
            repeat(200) { route.move(at, speed) { true }?.let { next ->
                val horizontal = next.clone().also { if (it.y < at.y) it.y = at.y }
                (at.distance(horizontal) <= .215 * speed + .000001) shouldBe true
                for (sample in 0..16) {
                    val point = at.clone().add(next.toVector().subtract(at.toVector()).multiply(sample / 16.0))
                    citizenPacketBodyClear(world, CitizenPoint(point.x, point.y, point.z), false) shouldBe true
                }
                at = next
            } }
            route.failure shouldBe null
            route.finished shouldBe true
            at.distanceSquared(target) shouldBe 0.0
        }
    }
})
