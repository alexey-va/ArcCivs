package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.type.Door
import org.bukkit.block.data.type.TrapDoor
import org.bukkit.util.BoundingBox
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.Executor

class CivsDetachedDoorNavigationTest : FunSpec({
    for (bulk in listOf(false, true)) {
        val capture = if (bulk) "bulk" else "sparse"
        test("$capture search rejects crossing the observed open Auto4 door leaf") {
            withDoorWorld(bulk) { world, route ->
                val from = CitizenPoint(12584.52177, 66.0, 10341.30119)
                val target = CitizenPoint(12583.5, 66.0, 10341.5)
                citizenPacketBodyClear(world, from, false) shouldBe true
                citizenPacketStepClear(world, from, target, false) shouldBe false
                val result = route(from, target) { p ->
                    p.y == 66 && p.x in 12583..12584 && p.z == 10341
                }
                result.failure shouldBe "no-path"
                result.endpoint shouldBe null
                (CivPosition(12584, 66, 10341) in result.blockedPositions) shouldBe true
            }
        }

        test("$capture search walks the open doorway and admits ordinary closed passage") {
            withDoorWorld(bulk) { world, route ->
                val from = CitizenPoint(12584.5, 66.0, 10340.5)
                val target = CitizenPoint(12584.5, 66.0, 10342.5)
                val allowed: (CivPosition) -> Boolean = { p ->
                    p.y == 66 && p.x == 12584 && p.z in 10340..10342
                }
                val open = route(from, target, allowed)
                open.endpoint shouldBe target
                var at = Location(world, from.x, from.y, from.z)
                repeat(50) { open.move(at, .55) { true }?.let { at = it } }
                open.finished shouldBe true
                CitizenPoint(at.x, at.y, at.z) shouldBe target
            }
            withDoorWorld(bulk, open = false) { world, route ->
                val from = CitizenPoint(12584.5, 66.0, 10340.5)
                val target = CitizenPoint(12584.5, 66.0, 10342.5)
                val closed = route(from, target) { p ->
                    p.y == 66 && p.x == 12584 && p.z in 10340..10342
                }
                closed.failure shouldBe null
                closed.endpoint shouldBe target
                (world.getBlockAt(12584, 66, 10341).blockData as Door).isOpen shouldBe false
            }
        }

        test("$capture search cannot plan to open a closed trapdoor") {
            withDoorWorld(bulk, trapdoor = true) { world, route ->
                val from = CitizenPoint(12584.5, 66.0, 10340.5)
                val target = CitizenPoint(12584.5, 66.0, 10342.5)
                val blocked = route(from, target) { p ->
                    p.y == 66 && p.x == 12584 && p.z in 10340..10342
                }
                blocked.failure shouldBe "no-path"
                blocked.endpoint shouldBe null
                (world.getBlockAt(12584, 67, 10341).blockData as TrapDoor).isOpen shouldBe false
            }
        }
    }
})

private fun withDoorWorld(
    bulk: Boolean,
    open: Boolean = true,
    trapdoor: Boolean = false,
    checkRoute: (CivsWorksiteWorldFixture, (CitizenPoint, CitizenPoint, (CivPosition) -> Boolean) -> CitizenPacketRoute) -> Unit,
) {
    MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
        val world = CivsWorksiteWorldFixture("detached-native-door", Material.STONE) { data ->
            when (data) {
                is Door -> listOf(if (data.isOpen) BoundingBox(0.0, 0.0, 0.0, 3.0 / 16.0, 1.0, 1.0)
                    else BoundingBox(0.0, 0.0, 13.0 / 16.0, 1.0, 1.0, 1.0))
                is TrapDoor -> listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 3.0 / 16.0, 1.0))
                else -> null
            }
        }.also(paper.server::addWorld)
        for (x in 785..787) for (z in 645..647) world.loadChunk(x, z)
        for (x in 12583..12585) for (z in 10340..10342) world.getBlockAt(x, 65, z).type = Material.STONE
        if (trapdoor) world.getBlockAt(12584, 67, 10341).blockData =
            Bukkit.createBlockData("minecraft:iron_trapdoor[facing=north,half=bottom,open=false,powered=false,waterlogged=false]")
        else for ((y, half) in listOf(66 to "lower", 67 to "upper")) world.getBlockAt(12584, y, 10341).blockData =
            Bukkit.createBlockData("minecraft:iron_door[facing=north,half=$half,hinge=left,open=$open,powered=false]")

        val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("DetachedDoorNavigation")))
        val work = BudgetedTaskQueue(tasks, 1000, nanoTime = { 0L })
        val workers = ArrayDeque<Runnable>()
        val navigation = CivsResidentNavigation(tasks, work, Executor { workers.addLast(it) }, snapshotContextFree = { true })
        fun route(from: CitizenPoint, to: CitizenPoint, allowed: (CivPosition) -> Boolean): CitizenPacketRoute {
            val origin = Location(world, from.x, from.y, from.z)
            val target = Location(world, to.x, to.y, to.z)
            val result = if (bulk) navigation.request(world, origin, target, allowed, { _, _ -> true }, { 1.0 },
                null, CitizenSnapshotPolicy({ p, _ -> allowed(p) }, { _, _ -> true }))
            else navigation.request(world, origin, target, allowed, { _, _ -> true }, { 1.0 })
            repeat(300) {
                if (!result.searching) return result
                while (workers.isNotEmpty()) workers.removeFirst().run()
                paper.performTicks(1)
                navigation.pulse()
            }
            error("Unfinished detached search: ${result.preparationPhase}")
        }
        try {
            checkRoute(world, ::route)
        } finally {
            navigation.clear()
            work.close()
            tasks.close()
        }
    }
}
