package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.withClue
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Block
import org.mockbukkit.mockbukkit.block.BlockMock
import org.bukkit.util.BoundingBox
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.security.MessageDigest
import java.util.concurrent.Executor

class CivsNavigationCaptureCostTest : FunSpec({
    test("empty air skips surface cost but keeps fresh permissions on cold and warm capture") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            var nativeBlockLookups = 0
            val world = object : CivsWorksiteWorldFixture("air-capture-cost") {
                override fun getBlockAt(x: Int, y: Int, z: Int): BlockMock {
                    nativeBlockLookups++
                    return super.getBlockAt(x, y, z)
                }
            }.also(paper.server::addWorld)
            world.loadChunk(0, 0)
            var allowed = true
            var permissionCalls = 0
            var costCalls = 0
            val route = CitizenPacketRoute(world, Location(world, .5, 64.0, .5), Location(world, 2.5, 64.0, .5),
                { permissionCalls++; allowed }, { _, _ -> true }, { block ->
                    check(!block.type.isAir) { "Surface cost requested for unsupported air" }
                    costCalls++; 2.25
                })
            val shapes = CivsNavigationTerrain(256, 6000)
            val air = CivPosition(0, 65, 0)
            nativeBlockLookups = 0
            route.captureBlock(air, shapes).allowed shouldBe true
            allowed = false
            route.captureBlock(air, shapes).allowed shouldBe false
            permissionCalls shouldBe 2
            costCalls shouldBe 0
            route.nativeShapeReads shouldBe 1
            nativeBlockLookups shouldBe 4 // One captured block and one bounded above-type lookup per air cell.
            val ground = CivPosition(0, 63, 0)
            route.captureBlock(ground, shapes).cost shouldBe 2.25
            route.captureBlock(ground, shapes).cost shouldBe 2.25
            costCalls shouldBe 2
            nativeBlockLookups shouldBe 6
        }
    }

    test("air below a suspended ladder retains its fresh support cost") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("ladder-air-capture-cost").also(paper.server::addWorld)
            world.loadChunk(0, 0)
            world.getBlockAt(1, 65, 0).blockData = paper.server.createBlockData("minecraft:ladder[facing=north,waterlogged=false]")
            var costs = 0
            val route = CitizenPacketRoute(world, Location(world, .5, 65.0, .5), Location(world, 1.5, 66.0, .5),
                { true }, { _, _ -> true }, { costs++; 2.25 })
            val shapes = CivsNavigationTerrain(256, 6000)
            repeat(2) { route.captureBlock(CivPosition(1, 64, 0), shapes).cost shouldBe 2.25 }
            costs shouldBe 2
        }
    }

    for (kind in listOf("flat", "partial-stairs", "ladder"))
    test("cold and warm $kind routes keep paths and permission denials while measuring surface calls") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("cost-bench-$kind", collisionForData = { data ->
                when (data.material) {
                    Material.OAK_SLAB -> listOf(BoundingBox(0.0, .5, 0.0, 1.0, 1.0, 1.0))
                    Material.OAK_STAIRS -> listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0),
                        BoundingBox(.5, .5, 0.0, 1.0, 1.0, 1.0))
                    else -> null
                }
            }).also(paper.server::addWorld)
            for (x in -1..4) for (z in -1..1) world.loadChunk(x, z)
            val from: Location
            val target: Location
            when (kind) {
                "partial-stairs" -> {
                    world.getBlockAt(1, 63, 0).type = Material.DIRT_PATH
                    world.getBlockAt(2, 64, 0).blockData = paper.server.createBlockData("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]")
                    world.getBlockAt(3, 64, 0).type = Material.STONE
                    world.getBlockAt(4, 64, 0).blockData = paper.server.createBlockData("minecraft:oak_slab[type=top,waterlogged=false]")
                    from = Location(world, .5, 64.0, .5)
                    target = Location(world, 7.5, 64.0, .5)
                }
                "ladder" -> {
                    world.getBlockAt(0, 64, 0).type = Material.STONE
                    for (y in 65..67) world.getBlockAt(1, y, 0).blockData =
                        paper.server.createBlockData("minecraft:ladder[facing=north,waterlogged=false]")
                    world.getBlockAt(2, 66, 0).type = Material.STONE
                    from = Location(world, .5, 65.0, .5)
                    target = Location(world, 2.5, 67.0, .5)
                }
                else -> {
                    from = Location(world, .5, 64.0, .5)
                    target = Location(world, 48.5, 64.0, .5)
                }
            }
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("CostBench${kind.replace("-", "")}")))
            var tick = 0L
            val work = BudgetedTaskQueue(tasks, maximumSteps = 10000, maximumNanos = 4_000_000L, nanoTime = { 0L })
            work.drainAvailable { tick }
            val workers = ArrayDeque<Runnable>()
            val owner = CivsResidentNavigation(tasks, work, Executor { workers.addLast(it) })
            var costCalls = 0
            var airCosts = 0
            var permissionCalls = 0
            var stepCalls = 0
            var denied = false
            fun allowed(point: CivPosition): Boolean {
                permissionCalls++
                if (denied && point.x == 1) return false
                return point.x in 0..target.blockX && point.z == 0 &&
                    point.y in (minOf(from.blockY, target.blockY) - 1)..(if (kind == "partial-stairs") 65 else maxOf(from.blockY, target.blockY))
            }
            fun cost(block: Block): Double {
                costCalls++
                if (block.type.isAir) airCosts++
                return 2.25
            }
            fun finish(label: String): CostCaptureBench {
                val costsBefore = costCalls; val airBefore = airCosts
                val allowedBefore = permissionCalls; val stepsBefore = stepCalls; val tickBefore = tick
                val route = owner.request(world, from, target, ::allowed, { _, _ -> stepCalls++; true }, ::cost)
                while (route.searching && tick - tickBefore < 400) {
                    while (workers.isNotEmpty()) workers.removeFirst().run()
                    tick++
                    paper.performTicks(1)
                    owner.pulse()
                }
                withClue("$kind $label phase=${route.preparationPhase} nodes=${route.nodeCount}") { route.searching shouldBe false }
                val waypoints = mutableListOf<CitizenPoint>()
                if (route.failure == null) {
                    var at = from.clone()
                    repeat(1200) {
                        route.destination?.let { next -> CitizenPoint(next.x, next.y, next.z) }?.let { next ->
                            if (waypoints.lastOrNull() != next) waypoints += next
                        }
                        route.move(at, 1.0) { true }?.let { at = it }
                    }
                    withClue("$kind $label final=${at.toVector()} route=${route.failure}") { route.finished shouldBe true }
                    at.distanceSquared(target) shouldBe 0.0
                }
                val signature = MessageDigest.getInstance("SHA-256").digest(waypoints.toString().toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val sample = CostCaptureBench(route.failure, route.nodeCount, route.capturedBlocks, route.nativeShapeReads,
                    costCalls - costsBefore, airCosts - airBefore, permissionCalls - allowedBefore,
                    stepCalls - stepsBefore, tick - tickBefore, signature)
                println("navigation-cost-bench kind=$kind pass=$label $sample")
                return sample
            }
            try {
                val cold = finish("cold")
                val warm = finish("warm")
                cold.failure shouldBe null
                warm.failure shouldBe null
                warm.nodes shouldBe cold.nodes
                warm.captured shouldBe cold.captured
                warm.costCalls shouldBe cold.costCalls
                warm.permissionCalls shouldBe cold.permissionCalls
                warm.stepCalls shouldBe cold.stepCalls
                warm.ticks shouldBe cold.ticks
                warm.pathSha256 shouldBe cold.pathSha256
                warm.nativeShapeReads shouldBe 0
                denied = true
                finish("permission-denied").failure shouldBe "no-path"
                // Flat/partial routes have no climbable node whose support coordinate is air.
                if (kind != "ladder") warm.airCostCalls shouldBe 0
                else (warm.airCostCalls > 0) shouldBe true
            } finally { owner.clear(); work.close(); tasks.close() }
        }
    }
})

private data class CostCaptureBench(val failure: String?, val nodes: Int, val captured: Int,
    val nativeShapeReads: Int, val costCalls: Int, val airCostCalls: Int, val permissionCalls: Int,
    val stepCalls: Int, val ticks: Long, val pathSha256: String)
