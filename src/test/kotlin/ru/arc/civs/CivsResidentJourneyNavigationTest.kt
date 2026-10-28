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

    for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1))
    for (returning in listOf(false, true)) for (speed in listOf(.5, 1.0, 2.0))
    test("top slab to bottom slab stair and deck stays physically clear $dx,$dz returning=$returning speed=$speed") {
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
