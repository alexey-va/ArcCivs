package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.excludeRecords
import io.mockk.mockk
import org.bukkit.Chunk
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.Lightable
import org.bukkit.block.data.type.Gate
import org.bukkit.util.BoundingBox
import org.bukkit.util.VoxelShape
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.npc.PacketPlayerNpc
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class CivsResidentNavigationTest : FunSpec({
    test("preparation keeps its context while bounded native capture is making progress") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("retained-slow-capture").also(paper.server::addWorld)
            for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("RetainedCapture")))
            val work = BudgetedTaskQueue(tasks, maximumSteps = 8, nanoTime = { 0L })
            val workers = ArrayDeque<Runnable>()
            val owner = CivsResidentNavigation(tasks, work, Executor { workers.addLast(it) },
                CivsNavigationSettings(searchNodesPerBatch = 1, closePreparationTicks = 4))
            val route = owner.request(world, Location(world, .5, 64.0, .5), Location(world, 3.5, 64.0, .5),
                { it.y == 64 && it.z == 0 && it.x in 0..3 }, { _, _ -> true }, { 1.0 })
            var ticks = 0
            try {
                while (route.searching && ticks++ < 200) {
                    while (workers.isNotEmpty()) workers.removeFirst().run()
                    paper.performTicks(1)
                    owner.pulse()
                }
                (ticks > 4) shouldBe true
                route.searching shouldBe false
                route.failure shouldBe null
                route.endpoint shouldBe CitizenPoint(3.5, 64.0, .5)
            } finally { owner.clear(); work.close(); tasks.close() }
        }
    }

    for (parallel in listOf(1, 9))
    test("$parallel close reachable routes prepare within their native tick latency bound") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("close-reachable-$parallel").also(paper.server::addWorld)
            for (x in -2..2) for (z in -2..2) world.loadChunk(x, z)
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("ReachableRouteBudget$parallel")))
            val work = BudgetedTaskQueue(tasks, maximumSteps = 512, maximumNanos = 4_000_000L, nanoTime = { 0L })
            val workers = ArrayDeque<Runnable>()
            val owner = CivsResidentNavigation(tasks, work, Executor { workers.addLast(it) })
            var tick = 0L
            var otherLaneSteps = 0
            work.drainAvailable { tick }
            repeat(2) { work.submit({ true }) { otherLaneSteps++; false } }
            val targets = List(parallel) { index -> when (index % 3) {
                0 -> Location(world, 3.5, 64.0, .5)
                1 -> Location(world, -2.5, 64.0, .5)
                else -> Location(world, .5, 64.0, 3.5)
            } }
            val routes = targets.map { target -> owner.request(world, Location(world, .5, 64.0, .5), target,
                { it.y == 64 }, { _, _ -> true }, { CivsPaths.ORDINARY_COST }) }
            val maximumTicks = if (parallel == 1) 10 else 20
            try {
                while (routes.any { it.searching } && tick < maximumTicks) {
                    while (workers.isNotEmpty()) workers.removeFirst().run()
                    tick++
                    paper.performTicks(1)
                    owner.pulse()
                }
                println("navigation-latency reachable parallel=$parallel operationsPerTick=512 ticks=$tick nodes=${routes.map { it.nodeCount }} blocks=${routes.map { it.capturedBlocks }}")
                withClue("ticks=$tick states=${routes.map { listOf(it.failure, it.nodeCount, it.capturedBlocks) }}") {
                    routes.forEachIndexed { index, route ->
                        route.searching shouldBe false
                        route.failure shouldBe null
                        val target = targets[index]
                        route.endpoint shouldBe CitizenPoint(target.x, target.y, target.z)
                    }
                    (otherLaneSteps > 0) shouldBe true
                }
            } finally {
                owner.clear()
                work.close()
                tasks.close()
            }
        }
    }

    for (parallel in listOf(1, 9))
    test("a close sealed destination returns only a safe prefix quickly with $parallel mixed routes") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("bounded-close-routes-$parallel").also(paper.server::addWorld)
            for (x in -6..6) for (z in -6..6) world.loadChunk(x, z)
            // The endpoint itself has real support/headroom, but every incoming side is closed.
            for (x in 2..4) for (z in -1..1) if (x != 3 || z != 0)
                for (y in 64..67) world.getBlockAt(x, y, z).type = Material.STONE
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("CloseRouteBudget$parallel")))
            val settings = CivsNavigationSettings()
            // Keep the single sealed goal as a 512-operation stress case; the mixed traffic
            // fixture uses the shipped shared-queue settings without relaxing its tick bound.
            val operationsPerTick = if (parallel == 1) 512 else settings.nativeOperationsPerTick
            val work = BudgetedTaskQueue(tasks, maximumSteps = operationsPerTick,
                maximumNanos = (settings.nativeBudgetMillis * 1_000_000).toLong(), nanoTime = { 0L })
            val workers = ArrayDeque<Runnable>()
            val owner = CivsResidentNavigation(tasks, work, Executor { workers.addLast(it) })
            var tick = 0L
            var otherLaneSteps = 0
            work.drainAvailable { tick }
            repeat(2) { work.submit({ true }) { otherLaneSteps++; false } }
            val targets = List(parallel) { index ->
                if (index == 0) Location(world, 3.5, 64.0, .5)
                else Location(world, -4.5, 64.0, (index - 1) * 2.0 + .5)
            }
            val routes = targets.map { target -> owner.request(world,
                Location(world, .35232563679, 64.0, .64767436321), target,
                { it.y == 64 }, { _, _ -> true }, { 1.0 }) }
            val completedAt = hashMapOf<CitizenPacketRoute, Long>()
            val maximumTicks = if (parallel == 1) 10 else 20
            try {
                while (routes.any { it.searching } && tick < maximumTicks) {
                    // A detached slice completes between server ticks, never inside native capture.
                    while (workers.isNotEmpty()) workers.removeFirst().run()
                    tick++
                    paper.performTicks(1)
                    owner.pulse()
                    routes.filter { !it.searching }.forEach { completedAt.putIfAbsent(it, tick) }
                }
                println("navigation-latency sealed parallel=$parallel operationsPerTick=$operationsPerTick ticks=$tick completed=${routes.map { completedAt[it] }} nodes=${routes.map { it.nodeCount }} blocks=${routes.map { it.capturedBlocks }}")
                withClue("ticks=$tick states=${routes.map { listOf(it.failure, it.nodeCount, it.capturedBlocks) }}") {
                    routes.all { !it.searching } shouldBe true
                    routes.first().failure shouldBe null
                    routes.first().continuationReason shouldBe "no-path"
                    routes.first().endpoint shouldBe CitizenPoint(1.5, 64.0, .5)
                    (routes.first().capturedBlocks < 256) shouldBe true
                    routes.drop(1).forEachIndexed { index, route ->
                        route.failure shouldBe null
                        val target = targets[index + 1]
                        route.endpoint shouldBe CitizenPoint(target.x, target.y, target.z)
                    }
                    completedAt.values.all { it <= maximumTicks } shouldBe true
                    (otherLaneSteps > 0) shouldBe true
                }
            } finally {
                owner.clear()
                work.close()
                tasks.close()
            }
        }
    }

    test("close goal preflight preserves an upper step and a descending enclosed ladder") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            for (ladder in listOf(false, true)) {
                val world = CivsWorksiteWorldFixture("goal-entry-$ladder").also(paper.server::addWorld)
                for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
                val target = Location(world, 3.5, 64.0, .5)
                val from = if (ladder) {
                    for (x in 2..4) for (z in -1..1) for (y in 64..68)
                        world.getBlockAt(x, y, z).type = Material.STONE
                    for (y in 64..68) world.getBlockAt(3, y, 0).blockData =
                        Bukkit.createBlockData("minecraft:ladder[facing=south,waterlogged=false]")
                    Location(world, 3.5, 67.0, .5)
                } else {
                    for (x in 0..2) world.getBlockAt(x, 64, 0).type = Material.STONE
                    for (x in 0..3) for (z in listOf(-1, 1)) for (y in 64..67)
                        world.getBlockAt(x, y, z).type = Material.STONE
                    Location(world, .5, 65.0, .5)
                }
                val route = CitizenPacketRoute(world, from, target,
                    { it.x in 0..3 && it.z == 0 && it.y in 64..68 }, { _, _ -> true }, { 1.0 })
                route.prepare()
                withClue("ladder=$ladder nodes=${route.nodeCount}") {
                    route.failure shouldBe null
                    route.endpoint shouldBe CitizenPoint(target.x, target.y, target.z)
                }
            }
        }
    }

    test("a close route widens its corridor to reach the far end of a U-shaped obstruction") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("close-wide-detour").also(paper.server::addWorld)
            for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
            for (z in -1..6) for (y in 64..67) world.getBlockAt(2, y, z).type = Material.STONE
            val start = Location(world, .5, 64.0, .5)
            val target = Location(world, 3.5, 64.0, .5)
            val route = CitizenPacketRoute(world, start, target,
                { it.x in -2..5 && it.z in -1..8 && it.y == 64 }, { _, _ -> true }, { 1.0 })
            route.prepare()
            route.failure shouldBe null
            var at = start
            var usedFarOpening = false
            repeat(240) {
                route.move(at, 1.0) { true }?.let { next ->
                    if (next.z >= 7.0) usedFarOpening = true
                    at = next
                }
            }
            usedFarOpening shouldBe true
            route.finished shouldBe true
            at.distanceSquared(target) shouldBe 0.0
        }
    }

    for (waitingForWorker in listOf(false, true))
    test("preparation ${if (waitingForWorker) "retires a stalled worker once" else "retains progressing native capture"}") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("preparation-deadline-$waitingForWorker").also(paper.server::addWorld)
            for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("PreparationDeadline$waitingForWorker")))
            val work = BudgetedTaskQueue(tasks, maximumSteps = 1, nanoTime = { 0L })
            val workers = ArrayDeque<Runnable>()
            val owner = CivsResidentNavigation(tasks, work, Executor { workers.addLast(it) },
                CivsNavigationSettings(closePreparationTicks = 6))
            var tick = 0L
            work.drainAvailable { tick }
            val route = owner.request(world, Location(world, .5, 64.0, .5), Location(world, 3.5, 64.0, .5),
                { it.y == 64 }, { _, _ -> true }, { 1.0 })
            try {
                repeat(6) {
                    if (!waitingForWorker) while (workers.isNotEmpty()) workers.removeFirst().run()
                    tick++
                    paper.performTicks(1)
                    owner.pulse()
                }
                if (!waitingForWorker) {
                    route.failure shouldBe null
                    route.searching shouldBe true
                    val captured = route.capturedBlocks
                    repeat(3) { tick++; paper.performTicks(1); owner.pulse() }
                    (route.capturedBlocks > captured) shouldBe true
                    return@use
                }
                route.failure shouldBe "preparation-budget"
                route.searching shouldBe false
                route.endpoint shouldBe null
                owner.debug()["jobs"] shouldBe 0
                val captured = route.capturedBlocks
                while (workers.isNotEmpty()) workers.removeFirst().run()
                repeat(3) { tick++; paper.performTicks(1); owner.pulse() }
                route.failure shouldBe "preparation-budget"
                route.capturedBlocks shouldBe captured
                owner.debug()["jobs"] shouldBe 0
            } finally {
                owner.clear()
                work.close()
                tasks.close()
            }
        }
    }

    test("optional neighbour prefetch cannot discard a complete path at the capture limit") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("optional-prefetch-cap").also(paper.server::addWorld)
            for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
            val route = CitizenPacketRoute(world, Location(world, .5, 64.0, .5), Location(world, 2.5, 64.0, .5),
                { it.y == 64 }, { _, _ -> true }, { 1.0 }, settings = CivsNavigationSettings(maximumCapturedBlocks = 256))
            route.prepare()
            route.capturedBlocks shouldBe 256
            route.failure shouldBe null
            route.endpoint shouldBe CitizenPoint(2.5, 64.0, .5)
        }
    }

    test("configured node capture and admission limits govern actual route work") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("configured-navigation-budgets").also(paper.server::addWorld)
            for (x in -6..6) for (z in -6..6) world.loadChunk(x, z)
            val from = Location(world, .5, 64.0, .5)
            val to = Location(world, 90.5, 64.0, .5)
            for ((settings, failure) in listOf(
                CivsNavigationSettings(maximumNodes = 64) to "search-budget",
                CivsNavigationSettings(maximumCapturedBlocks = 256) to "capture-budget",
            )) {
                val route = CitizenPacketRoute(world, from, to, { it.y == 64 }, { _, _ -> true }, { 1.0 }, settings = settings)
                route.prepare()
                if (route.continuation) {
                    route.failure shouldBe null
                    val endpoint = requireNotNull(route.endpoint)
                    (from.distance(to) - kotlin.math.sqrt(endpoint.distanceSquared(CitizenPoint(to.x, to.y, to.z))) >= 2.0) shouldBe true
                    (endpoint != CitizenPoint(to.x, to.y, to.z)) shouldBe true
                } else {
                    route.failure shouldBe failure
                    route.endpoint shouldBe null
                }
                if (failure == "search-budget") route.nodeCount shouldBe 65
                else route.capturedBlocks shouldBe 256
            }
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("ConfiguredNavigationAdmission")))
            val work = BudgetedTaskQueue(tasks, maximumSteps = 1, nanoTime = { 0L })
            val workers = ArrayDeque<Runnable>()
            val owner = CivsResidentNavigation(tasks, work, Executor { workers.addLast(it) },
                CivsNavigationSettings(maximumJobs = 2))
            fun request() = owner.request(world, from, to, { it.y == 64 }, { _, _ -> true }, { 1.0 })
            try {
                val first = request()
                request().searching shouldBe true
                request().failure shouldBe "planner-busy"
                owner.debug()["maximum_jobs"] shouldBe 2
                first.cancel()
                request().searching shouldBe true
                owner.debug()["jobs"] shouldBe 2
            } finally {
                owner.clear()
                work.close()
                tasks.close()
            }
        }
    }

    test("native capture shares one tick allowance fairly and bounded admission releases cancelled jobs") {
        MockBukkitTestRuntime.open().use { paper ->
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("NavigationBudget")))
            val work = BudgetedTaskQueue(tasks, maximumSteps = 1, nanoTime = { 0L })
            var tick = 0L
            work.drainAvailable { tick }
            val owner = CivsResidentNavigation(tasks, work, Executor(Runnable::run))
            val terrain = ResidentRouteTerrain()
            fun request() = owner.request(terrain.world, Location(terrain.world, .5, 64.0, .5),
                Location(terrain.world, 5.5, 64.0, .5), terrain::allowed, { _, _ -> true }, { 1.0 })
            try {
                val routes = List(CivsResidentNavigation.MAXIMUM_JOBS) { request() }
                routes.sumOf { it.capturedBlocks } shouldBe 0
                request().failure shouldBe "planner-busy"
                owner.pulse()
                routes.sumOf { it.capturedBlocks } shouldBe 1
                owner.pulse()
                routes.sumOf { it.capturedBlocks } shouldBe 1
                // Each real scheduler tick spends just one operation across all residents.
                while (routes.any { it.capturedBlocks == 0 } && tick < routes.size) {
                    tick++
                    paper.performTicks(1)
                    owner.pulse()
                }
                routes.all { it.capturedBlocks == 1 } shouldBe true
                routes.first().cancel()
                val replacement = request()
                replacement.failure shouldBe null
                replacement.searching shouldBe true
                owner.clear()
                val captured = terrain.blockReads
                tick++
                paper.performTicks(1)
                owner.pulse()
                terrain.blockReads shouldBe captured
                routes.all { !it.searching && !it.navigating } shouldBe true
                replacement.navigating shouldBe false
            } finally {
                owner.clear()
                work.close()
                tasks.close()
            }
        }
    }

    test("a real worker only sees detached terrain and a cancelled completed slice cannot apply") {
        MockBukkitTestRuntime.open().use { paper ->
            val main = Thread.currentThread()
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("NavigationThreads")))
            val work = BudgetedTaskQueue(tasks, nanoTime = { 0L })
            var tick = 0L
            work.drainAvailable { tick }
            val worker = BoundedAsyncExecutor("NavigationTest", 1, 48, false)
            val workerThreads = ConcurrentHashMap.newKeySet<Thread>()
            val executor = Executor { command -> worker.execute { workerThreads += Thread.currentThread(); command.run() } }
            val owner = CivsResidentNavigation(tasks, work, executor)
            val terrain = ResidentRouteTerrain()
            var permissions = 0
            fun request(targetX: Double) = owner.request(terrain.world, Location(terrain.world, .5, 64.0, .5),
                Location(terrain.world, targetX, 64.0, .5),
                { point -> check(Thread.currentThread() === main); permissions++; terrain.allowed(point) },
                { _, _ -> check(Thread.currentThread() === main); permissions++; true },
                { check(Thread.currentThread() === main); permissions++; 1.0 })
            try {
                val cancelled = request(5.5)
                // Test-only barrier: make the result complete before cancellation, without sleeps.
                worker.submit { Unit }.get(5, TimeUnit.SECONDS)
                cancelled.cancel()
                owner.pulse()
                cancelled.nodeCount shouldBe 0
                cancelled.capturedBlocks shouldBe 0
                cancelled.endpoint shouldBe null
                terrain.blockReads shouldBe 0

                val route = request(3.5)
                while (route.searching && tick < CitizenNavigationSearch.MAXIMUM_NODES) {
                    worker.submit { Unit }.get(5, TimeUnit.SECONDS)
                    owner.pulse()
                    tick++
                    paper.performTicks(1)
                }
                withClue("phase=${route.preparationPhase} nodes=${route.nodeCount} captured=${route.capturedBlocks}") {
                    route.searching shouldBe false
                    route.failure shouldBe null
                }
                route.endpoint shouldBe CitizenPoint(3.5, 64.0, .5)
                (route.searchNanos > 0) shouldBe true
                (permissions > 0) shouldBe true
                (workerThreads.isNotEmpty() && main !in workerThreads) shouldBe true
                cancelled.preparationPhase shouldBe "cancelled"
                cancelled.endpoint shouldBe null
            } finally {
                owner.clear()
                work.close()
                tasks.close()
                worker.closeAsync().get(5, TimeUnit.SECONDS)
            }
        }
    }

    test("a fractional ladder start descends onto its current rung and turns out through a low landing") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("fractional-ladder").also(paper.server::addWorld)
            for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
            for (x in -1..3) for (z in -1..1) for (y in 64..68) world.getBlockAt(x, y, z).type = Material.STONE
            for (y in 64..67) world.getBlockAt(0, y, 0).type = Material.AIR
            for (y in 64..65) world.getBlockAt(0, y, 0).blockData =
                Bukkit.createBlockData("minecraft:ladder[facing=south,waterlogged=false]")
            for (x in 1..2) for (y in 64..65) world.getBlockAt(x, y, 0).type = Material.AIR
            var at = Location(world, .57, 65.2, .55)
            citizenPacketBodyClear(world, CitizenPoint(at.x, at.y, at.z), false) shouldBe true
            val route = CitizenPacketRoute(world, at, Location(world, 2.5, 64.0, .5),
                { p -> p.x in 0..2 && p.z == 0 && p.y in 64..66 }, { _, _ -> true }, { 1.0 })
            route.prepare()
            route.failure shouldBe null
            route.searching shouldBe false
            repeat(80) {
                route.move(at, 1.0) { true }?.let { next ->
                    for (sample in 0..10) {
                        val p = at.clone().add(next.toVector().subtract(at.toVector()).multiply(sample / 10.0))
                        citizenPacketBodyClear(world, CitizenPoint(p.x, p.y, p.z), false) shouldBe true
                    }
                    at = next
                }
            }
            route.failure shouldBe null
            route.finished shouldBe true
            at.distanceSquared(Location(world, 2.5, 64.0, .5)) shouldBe 0.0
        }
    }

    test("an interrupted step can settle in its own column before walking under a low ceiling") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            for (x in 1..3) terrain.blocks[CivPosition(x, 66, 0)] = Material.STONE
            var at = Location(terrain.world, .5, 65.0, .5)
            val route = CitizenPacketRoute(terrain.world, at, Location(terrain.world, 3.5, 64.0, .5),
                { p -> p.x in 0..3 && p.z == 0 && p.y in 64..65 }, { _, _ -> true }, { 1.0 })
            route.prepare()
            route.failure shouldBe null
            repeat(80) { route.move(at, 1.0) { true }?.let { at = it } }
            route.finished shouldBe true
            at.distanceSquared(Location(terrain.world, 3.5, 64.0, .5)) shouldBe 0.0
        }
    }

    test("exact vertical endpoints preserve the incoming yaw and bounded pitch on ascent descent and settling") {
        MockBukkitTestRuntime.open().use {
            for ((startY, targetY) in listOf(64.0 to 65.0, 65.0 to 64.0, 64.1875 to 64.0)) {
                val terrain = ResidentRouteTerrain()
                if (startY % 1.0 == 0.0) for (y in 64..66)
                    terrain.blocks[CivPosition(0, y, 0)] = Material.LADDER
                for (yaw in listOf(47f, 179f, -179f, 539f, -539f)) {
                    var at = Location(terrain.world, .5, startY, .5, yaw, 24f)
                    val target = Location(terrain.world, .5, targetY, .5)
                    val route = CitizenPacketRoute(terrain.world, at, target,
                        { it.x == 0 && it.z == 0 && it.y in 64..66 }, { _, _ -> true }, { 1.0 })
                    route.prepare()
                    route.searching shouldBe false
                    route.failure shouldBe null
                    var moves = 0
                    repeat(16) { tick ->
                        route.move(at, 1.0) { true }?.let { next ->
                            withClue("fromY=$startY targetY=$targetY yaw=$yaw tick=$tick from=$at next=$next") {
                                next.x shouldBe at.x
                                next.z shouldBe at.z
                                next.yaw shouldBe yaw
                                next.pitch shouldBe maxOf(0f, at.pitch - CITIZEN_TURN_PER_TICK)
                                (at.distance(next) in .000001.. .215001) shouldBe true
                                citizenPacketStepClear(terrain.world, CitizenPoint(at.x, at.y, at.z),
                                    CitizenPoint(next.x, next.y, next.z), false) shouldBe true
                            }
                            at = next
                            moves++
                        }
                    }
                    (moves > 0) shouldBe true
                    route.failure shouldBe null
                    route.finished shouldBe true
                    at.distanceSquared(target) shouldBe 0.0
                    at.yaw shouldBe yaw
                    if (startY % 1.0 != 0.0) at.pitch shouldBe 16f
                }
            }
        }
    }

    test("sleep clearance rotates the full native body and rejects walls and fire beyond standing feet") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            val feet = Location(terrain.world, .5, 64.0, .5)
            val open = requireNotNull(citizenSleepPose(terrain.world, feet, 1.0))
            open.position.yaw shouldBe 0f
            val neighbour = requireNotNull(citizenSleepPose(terrain.world, feet.clone().add(0.0, 0.0, 1.0), 1.0) {
                !it.overlaps(open.bounds)
            })
            neighbour.position.yaw shouldBe 90f
            neighbour.bounds.overlaps(open.bounds) shouldBe false
            citizenSleepPose(terrain.world, feet, 1.0) { !it.overlaps(open.bounds) } shouldBe null
            (open.bounds.minY > feet.y) shouldBe true
            // The native armour head inflates its 8px cube by 1px on every face.
            val helmetRadius = 5.0 / 16.0 * .9375
            (open.bounds.minY < open.position.y - helmetRadius) shouldBe true
            (open.bounds.maxY > open.position.y + helmetRadius) shouldBe true
            val bobbingArmWidth = (5 + 4 * kotlin.math.cos(.1) + 11 * kotlin.math.sin(.1)) / 16 * .9375
            (open.bounds.minZ < open.position.z - bobbingArmWidth) shouldBe true
            (open.bounds.maxZ > open.position.z + bobbingArmWidth) shouldBe true
            (open.bounds.minX < -1.4) shouldBe true
            terrain.blocks[CivPosition(-1, 64, 0)] = Material.STONE
            citizenPacketBodyClear(terrain.world, CitizenPoint(.5, 64.0, .5), false) shouldBe true
            val turned = requireNotNull(citizenSleepPose(terrain.world, feet, 1.0))
            (turned.position.yaw != 0f) shouldBe true
            citizenSleepClear(terrain.world, open.bounds) shouldBe false
            terrain.blocks[CivPosition(-1, 64, 0)] = Material.CAMPFIRE
            citizenSleepClear(terrain.world, open.bounds) shouldBe false
            for ((x, z) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1))
                terrain.blocks[CivPosition(x, 64, z)] = Material.STONE
            citizenSleepPose(terrain.world, feet, 1.0) shouldBe null
            citizenPacketBodyClear(terrain.world, CitizenPoint(.5, 64.0, .5), false) shouldBe true
        }
    }

    test("walking keeps the full per-tick distance across straight and diagonal cell centres") {
        MockBukkitTestRuntime.open().use {
            for (diagonal in listOf(false, true)) for (speed in listOf(.55, 1.0, 1.3)) {
                val terrain = ResidentRouteTerrain()
                var at = Location(terrain.world, .5, 64.0, .5, if (diagonal) -45f else -90f, 0f)
                val route = CitizenPacketRoute(terrain.world, at,
                    Location(terrain.world, 20.5, 64.0, if (diagonal) 20.5 else .5),
                    { p -> p.x in 0..20 && p.z in 0..20 && p.y == 64 }, { _, _ -> true }, { 1.0 })
                route.prepare()
                route.searching shouldBe false
                route.failure shouldBe null
                repeat(60) { tick ->
                    val next = requireNotNull(route.move(at, speed) { true })
                    withClue("diagonal=$diagonal speed=$speed tick=$tick from=$at to=$next") {
                        (kotlin.math.abs(at.distance(next) - .215 * speed) < .000001) shouldBe true
                    }
                    at = next
                }
            }
        }
    }

    test("speed changes collision waits and cancellation never bank movement for a later tick") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            var at = Location(terrain.world, .5, 64.0, .5, -90f, 0f)
            val route = CitizenPacketRoute(terrain.world, at, Location(terrain.world, 20.5, 64.0, .5),
                { p -> p.x in 0..20 && p.z == 0 && p.y == 64 }, { _, _ -> true }, { 1.0 })
            route.prepare()
            repeat(4) { at = requireNotNull(route.move(at, 1.0) { true }) }
            // The next step crosses a waypoint. A player blocking that tick must leave its
            // full budget unspent rather than allow a jump when the player moves away.
            val blockedAt = at.clone()
            repeat(7) {
                route.move(at, 1.0, { _, _ -> false }) { true } shouldBe null
                route.waitingForCollision shouldBe true
                at shouldBe blockedAt
            }
            for (speed in listOf(.55, 1.3, 1.0, .55, 1.3, 1.0)) {
                val next = requireNotNull(route.move(at, speed) { true })
                (kotlin.math.abs(at.distance(next) - .215 * speed) < .000001) shouldBe true
                at = next
            }
            route.cancel()
            route.move(at, 1.3) { true } shouldBe null
            route.navigating shouldBe false
        }
    }

    test("the first diagonal on a fractional-height path is checked from the actual off-centre position") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            for (x in 0..4) for (z in 0..2) terrain.blocks[CivPosition(x, 63, z)] = Material.DIRT_PATH
            for (y in 64..65) terrain.blocks[CivPosition(1, y, -1)] = Material.STONE
            var at = Location(terrain.world, .69, 63.9375, .31)
            citizenPacketBodyClear(terrain.world, CitizenPoint(at.x, at.y, at.z), false) shouldBe true
            val origin = CitizenPoint(at.x, at.y, at.z)
            val plannedStarts = mutableListOf<CitizenPoint>()
            val route = CitizenPacketRoute(terrain.world, at, Location(terrain.world, 4.5, 64.0, .5),
                { p -> p.x in 0..4 && p.z in 0..2 && p.y in 63..64 },
                { from, _ -> plannedStarts += from; true }, { 1.0 })
            route.prepare()
            plannedStarts.first() shouldBe origin
            route.failure shouldBe null
            repeat(120) { route.move(at, 1.0) { true }?.let { at = it } }
            route.failure shouldBe null
            route.finished shouldBe true
            at.x shouldBe 4.5
            at.y shouldBe 63.9375
        }
    }

    test("packet movement walks around an occupied body-width corner and rejects an inserted wall") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            for (z in -2..2) for (y in 64..65) terrain.blocks[CivPosition(2, y, z)] = Material.STONE
            val route = terrain.route()
            route.prepare()
            route.failure shouldBe null
            route.searching shouldBe false
            var at = Location(terrain.world, .5, 64.0, .5)
            var detoured = false
            repeat(200) {
                route.move(at, 1.0) { true }?.let { next ->
                    for (sample in 0..10) {
                        val point = at.clone().add(next.toVector().subtract(at.toVector()).multiply(sample / 10.0))
                        citizenPacketBodyClear(terrain.world, CitizenPoint(point.x, point.y, point.z), false) shouldBe true
                    }
                    if (kotlin.math.abs(next.z - .5) >= 2.5) detoured = true
                    at = next
                }
            }
            route.finished shouldBe true
            detoured shouldBe true
            at.distanceSquared(Location(terrain.world, 5.5, 64.0, .5)) shouldBe 0.0

            val changed = ResidentRouteTerrain()
            val second = changed.route()
            second.prepare()
            val next = requireNotNull(second.destination)
            changed.blocks[CivPosition(next.blockX, 64, next.blockZ)] = Material.STONE
            second.move(Location(changed.world, .5, 64.0, .5, -90f, 0f), 1.0) { true } shouldBe null
            second.failure shouldBe "movement-blocked"
        }
    }

    test("a closed native gate is planned as a passage and opened before strict packet movement") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("route-gate").also(paper.server::addWorld)
            for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
            val gate = world.getBlockAt(0, 64, 0)
            val closed = "minecraft:oak_fence_gate[facing=north,in_wall=false,open=false,powered=false]"
            gate.setBlockData(Bukkit.createBlockData(closed), false)
            var at = Location(world, .5, 64.0, -1.5)
            val target = Location(world, .5, 64.0, 2.5)
            val city = pathsCity().copy(worldId = world.uid)
            val route = CitizenPacketRoute(world, at, target,
                { p -> p.x == 0 && p.z in -2..2 && p.y == 64 }, { _, _ -> true }, { 1.0 })
            route.prepare()
            route.failure shouldBe null
            gate.blockData.asString shouldBe closed
            citizenPacketBodyClear(world, CitizenPoint(.5, 64.0, .5), true) shouldBe true
            citizenPacketBodyClear(world, CitizenPoint(.5, 64.0, .5), false) shouldBe false
            repeat(80) {
                route.move(at, 1.0) { destination ->
                    citizenOpenRouteDoors(city, emptySet(), at, destination) { _, _ -> false }
                }?.let { next ->
                    citizenPacketStepClear(world, CitizenPoint(at.x, at.y, at.z),
                        CitizenPoint(next.x, next.y, next.z), false) shouldBe true
                    at = next
                }
            }
            route.failure shouldBe null
            route.finished shouldBe true
            at.distanceSquared(target) shouldBe 0.0
            (gate.blockData as Gate).isOpen shouldBe true
        }
    }

    test("native and detached sweeps include tall fence shapes below the feet but admit openable gates") {
        MockBukkitTestRuntime.open().use {
            for (material in listOf(Material.OAK_FENCE, Material.OAK_FENCE_GATE)) {
                val terrain = ResidentRouteTerrain()
                for (x in 0..1) for (z in 0..1) terrain.blocks[CivPosition(x, 64, z)] = Material.STONE
                val fence = CivPosition(1, 64, 0)
                terrain.blocks[fence] = material
                terrain.boxes[fence] = listOf(BoundingBox(0.0, 0.0, .375, 1.0, 1.5, .625))
                val from = CitizenPoint(.5, 65.0, .5)
                val to = CitizenPoint(1.5, 65.0, 1.5)
                val overlapping = CitizenPoint(1.5, 65.0, .5)
                citizenPacketBodyClear(terrain.world, from, false) shouldBe true
                citizenPacketBodyClear(terrain.world, to, false) shouldBe true
                citizenPacketBodyClear(terrain.world, overlapping, false) shouldBe false
                citizenPacketBodyClear(terrain.world, overlapping, true) shouldBe (material == Material.OAK_FENCE_GATE)
                citizenPacketStepClear(terrain.world, from, to, false) shouldBe false
                val route = CitizenPacketRoute(terrain.world, Location(terrain.world, from.x, from.y, from.z),
                    Location(terrain.world, to.x, to.y, to.z),
                    { p -> p.x == p.z && p.x in 0..1 && p.y == 65 }, { _, _ -> true }, { 1.0 })
                route.prepare()
                route.failure shouldBe if (material == Material.OAK_FENCE_GATE) null else "no-path"
                if (material == Material.OAK_FENCE_GATE) {
                    terrain.boxes[fence] = emptyList()
                    citizenPacketBodyClear(terrain.world, overlapping, false) shouldBe true
                    citizenPacketStepClear(terrain.world, from, to, false) shouldBe true
                }
            }
        }
    }

    test("a passage cannot be chosen as a foothold that opening would remove") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            terrain.blocks[CivPosition(0, 64, 0)] = Material.STONE
            val gate = CivPosition(1, 64, 0)
            terrain.blocks[gate] = Material.OAK_FENCE_GATE
            terrain.boxes[gate] = listOf(BoundingBox(0.0, 0.0, .375, 1.0, 1.5, .625))
            val top = CitizenPoint(1.5, 65.5, .5)
            citizenPacketBodyClear(terrain.world, top, false) shouldBe true
            citizenPacketSupported(terrain.world, top) shouldBe false
            val route = CitizenPacketRoute(terrain.world, Location(terrain.world, .5, 65.0, .5),
                Location(terrain.world, top.x, top.y, top.z),
                { p -> p.x in 0..1 && p.z == 0 && p.y in 65..66 }, { _, _ -> true }, { 1.0 })
            route.prepare()
            route.failure shouldBe "no-path"
        }
    }

    test("the extra shape row does not turn liquid or heat below a supported slab into a body collision") {
        MockBukkitTestRuntime.open().use {
            for (material in listOf(Material.WATER, Material.CAMPFIRE)) {
                val terrain = ResidentRouteTerrain()
                for (x in 0..2) {
                    terrain.blocks[CivPosition(x, 64, 0)] = Material.OAK_SLAB
                    terrain.boxes[CivPosition(x, 64, 0)] = listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0))
                }
                terrain.blocks[CivPosition(1, 63, 0)] = material
                val lower = terrain.world.getBlockAt(1, 63, 0)
                every { lower.isLiquid } returns (material == Material.WATER)
                val from = CitizenPoint(.5, 64.5, .5)
                val to = CitizenPoint(2.5, 64.5, .5)
                citizenPacketBodyClear(terrain.world, CitizenPoint(1.5, 64.5, .5), false) shouldBe true
                citizenPacketStepClear(terrain.world, from, to, false) shouldBe true
                val route = CitizenPacketRoute(terrain.world, Location(terrain.world, from.x, from.y, from.z),
                    Location(terrain.world, to.x, to.y, to.z),
                    { p -> p.x in 0..2 && p.z == 0 && p.y == 64 }, { _, _ -> true }, { 1.0 })
                route.prepare()
                route.failure shouldBe null
            }
        }
    }

    test("a rounded doorway corner cannot skip the thin open door between its sampled endpoints") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            for (x in -72..-67) for (z in -49..-46) terrain.blocks[CivPosition(x, 70, z)] = Material.OAK_PLANKS
            for (y in 71..72) {
                terrain.blocks[CivPosition(-69, y, -48)] = Material.OAK_PLANKS
                val door = CivPosition(-69, y, -49)
                terrain.blocks[door] = Material.OAK_DOOR
                // Saved Spawn doorway: east-facing, right-hinged, open, with a 3/16 panel.
                terrain.boxes[door] = listOf(BoundingBox(0.0, 0.0, 13.0 / 16.0, 1.0, 1.0, 1.0))
            }
            val fourth = CitizenPoint(-67.67777777777778, 71.0, -48.45458333333333)
            val fifth = CitizenPoint(-67.77777777777779, 71.0, -48.48864583333333)
            citizenPacketBodyClear(terrain.world, fourth, false) shouldBe true
            citizenPacketBodyClear(terrain.world, fifth, false) shouldBe true
            citizenPacketStepClear(terrain.world, fourth, fifth, false) shouldBe false

            var at = Location(terrain.world, -67.5, 71.0, -46.5, 180f, 0f)
            val target = Location(terrain.world, -71.5, 71.0, -48.5)
            val route = CitizenPacketRoute(terrain.world, at, target,
                { p -> p.y == 71 && (p.x == -68 && p.z in -49..-47 || p.z == -49 && p.x in -72..-68) },
                { _, _ -> true }, { 1.0 })
            route.prepare()
            route.failure shouldBe null
            repeat(160) { tick ->
                route.move(at, .55) { true }?.let { next ->
                    (at.distance(next) <= .118251) shouldBe true
                    at = next
                }
                withClue("tick=$tick at=$at") { route.failure shouldBe null }
            }
            route.finished shouldBe true
            at.distanceSquared(target) shouldBe 0.0
        }
    }

    test("one-block ascent and descent keep every intermediate body outside solid support") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            terrain.blocks[CivPosition(1, 64, 0)] = Material.STONE
            val route = CitizenPacketRoute(terrain.world, Location(terrain.world, .5, 64.0, .5),
                Location(terrain.world, 2.5, 64.0, .5),
                { point -> point.x in 0..2 && point.z == 0 && point.y in 64..65 }, { _, _ -> true }, { 1.0 })
            route.prepare()
            route.failure shouldBe null
            var at = Location(terrain.world, .5, 64.0, .5)
            var climbed = false
            repeat(80) {
                route.move(at, 1.0) { true }?.let { next ->
                    citizenPacketBodyClear(terrain.world, CitizenPoint(next.x, next.y, next.z), false) shouldBe true
                    if (next.y >= 65.0) climbed = true
                    at = next
                }
            }
            climbed shouldBe true
            route.finished shouldBe true
            route.failure shouldBe null
            at.distanceSquared(Location(terrain.world, 2.5, 64.0, .5)) shouldBe 0.0
        }
    }

    test("idle travel from a lowered path clears the adjacent full-height corner before descending") {
        MockBukkitTestRuntime.open().use {
            // Saved Test-city columns at (-58,-46), (-58,-45), (-57,-46), (-57,-45).
            // Both observed starts are still at Y70 above the path's Y69.9375 top.
            val terrain = ResidentRouteTerrain()
            for (x in -58..-57) for (z in -46..-45) terrain.blocks[CivPosition(x, 68, z)] = Material.DIRT
            terrain.blocks[CivPosition(-58, 69, -46)] = Material.DIRT_PATH
            terrain.blocks[CivPosition(-57, 69, -46)] = Material.DIRT_PATH
            terrain.blocks[CivPosition(-58, 69, -45)] = Material.MOSS_BLOCK
            val origins = listOf(-57.68169344829578 to -45.31830655170422,
                -57.63475363298664 to -45.36524636701336)
            val targets = listOf(Location(terrain.world, -56.5, 69.0, -44.5),
                Location(terrain.world, -57.5, 70.0, -44.5))
            for ((x, z) in origins) for (target in targets) for (speed in listOf(.55, .8, 1.3)) {
                var at = Location(terrain.world, x, 70.0, z, -119f, 0f)
                val route = CitizenPacketRoute(terrain.world, at, target,
                    { it.x in -58..-57 && it.z in -46..-45 && it.y in 69..70 }, { _, _ -> true }, { 1.0 })
                route.prepare()
                route.failure shouldBe null
                withClue("origin=$x,$z target=$target speed=$speed") {
                    repeat(100) {
                        route.move(at, speed) { true }?.let { next ->
                            val horizontal = next.clone().also { it.y = at.y }
                            (at.distance(horizontal) <= .215 * speed + .000001) shouldBe true
                            (next.y <= at.y + .000001) shouldBe true
                            for (sample in 0..10) {
                                val p = at.clone().add(next.toVector().subtract(at.toVector()).multiply(sample / 10.0))
                                citizenPacketBodyClear(terrain.world, CitizenPoint(p.x, p.y, p.z), false) shouldBe true
                            }
                            at = next
                        }
                        route.failure shouldBe null
                    }
                    route.finished shouldBe true
                    at.distanceSquared(target) shouldBe 0.0
                    (route.forwardProgress > .5) shouldBe true
                }
            }
        }
    }

    for ((dx, dz) in listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)) for (returning in listOf(false, true))
    test("a lowered path connects the farm stair and deck at direction $dx,$dz returning=$returning") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            val stair = CivPosition(dx, 64, dz)
            terrain.blocks[CivPosition(0, 63, 0)] = Material.DIRT_PATH
            terrain.blocks[stair] = Material.OAK_STAIRS
            // The observed medievaloak farm has a bottom straight stair above a
            // 15/16 dirt path. These are the vanilla shape boxes, rotated with its rise.
            terrain.boxes[stair] = listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0), when {
                dx > 0 -> BoundingBox(.5, .5, 0.0, 1.0, 1.0, 1.0)
                dx < 0 -> BoundingBox(0.0, .5, 0.0, .5, 1.0, 1.0)
                dz > 0 -> BoundingBox(0.0, .5, .5, 1.0, 1.0, 1.0)
                else -> BoundingBox(0.0, .5, 0.0, 1.0, 1.0, .5)
            })
            terrain.blocks[CivPosition(dx * 2, 64, dz * 2)] = Material.OAK_PLANKS
            val path = Location(terrain.world, .5, 63.9375, .5)
            val deck = Location(terrain.world, dx * 2 + .5, 65.0, dz * 2 + .5)
            var at = (if (returning) deck else path).clone().also {
                val sign = if (returning) -1 else 1
                it.yaw = Math.toDegrees(kotlin.math.atan2(-dx.toDouble() * sign, dz.toDouble() * sign)).toFloat()
            }
            val target = if (returning) path else deck
            val route = CitizenPacketRoute(terrain.world, at, target,
                { p -> (0..2).any { p.x == dx * it && p.z == dz * it } && p.y in 63..65 },
                { _, _ -> true }, { 1.0 })
            route.prepare()
            route.searching shouldBe false
            route.failure shouldBe null
            var moved = 0
            repeat(100) {
                route.move(at, 1.0) { true }?.let { next ->
                    val walking = next.clone().also { it.y = at.y }
                    (at.distance(walking) <= .215001) shouldBe true
                    assertNativeStairFrame(terrain.world, at, next)
                    at = next
                    moved++
                }
            }
            (moved > 0) shouldBe true
            route.failure shouldBe null
            route.finished shouldBe true
            at.distanceSquared(target) shouldBe 0.0
        }
    }

    test("bottom stairs spend the same horizontal stride as flat travel in every facing and direction") {
        MockBukkitTestRuntime.open().use {
            for ((dx, dz) in listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1))
                for (speed in listOf(1.0, 1.25)) for (returning in listOf(false, true)) {
                    val sign = if (returning) -1 else 1
                    fun trajectory(stairs: Boolean): List<Double> {
                        val terrain = ResidentRouteTerrain()
                        if (stairs) {
                            for (index in 1..3) {
                                val stair = CivPosition(dx * index, 63 + index, dz * index)
                                terrain.blocks[stair] = Material.OAK_STAIRS
                                terrain.boxes[stair] = listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0), when {
                                    dx > 0 -> BoundingBox(.5, .5, 0.0, 1.0, 1.0, 1.0)
                                    dx < 0 -> BoundingBox(0.0, .5, 0.0, .5, 1.0, 1.0)
                                    dz > 0 -> BoundingBox(0.0, .5, .5, 1.0, 1.0, 1.0)
                                    else -> BoundingBox(0.0, .5, 0.0, 1.0, 1.0, .5)
                                })
                            }
                            terrain.blocks[CivPosition(dx * 4, 66, dz * 4)] = Material.OAK_PLANKS
                        }
                        val lower = Location(terrain.world, .5, 64.0, .5)
                        val upper = Location(terrain.world, dx * 4 + .5, if (stairs) 67.0 else 64.0, dz * 4 + .5)
                        var at = (if (returning) upper else lower).clone().also {
                            it.yaw = Math.toDegrees(kotlin.math.atan2(-dx.toDouble() * sign, dz.toDouble() * sign)).toFloat()
                        }
                        val target = if (returning) lower else upper
                        val route = CitizenPacketRoute(terrain.world, at, target,
                            { p -> (0..4).any { p.x == dx * it && p.z == dz * it } && p.y in 64..67 },
                            { _, _ -> true }, { 1.0 })
                        route.prepare()
                        route.failure shouldBe null
                        val strides = mutableListOf<Double>()
                        val contacts = linkedSetOf<Double>()
                        repeat(100) { tick ->
                            if (!route.finished) {
                                val remaining = ((target.x - at.x) * dx + (target.z - at.z) * dz) * sign
                                val next = requireNotNull(route.move(at, speed) { true }) {
                                    "stairs=$stairs direction=$dx,$dz speed=$speed returning=$returning tick=$tick failure=${route.failure}"
                                }
                                val advance = ((next.x - at.x) * dx + (next.z - at.z) * dz) * sign
                                withClue("stairs=$stairs direction=$dx,$dz speed=$speed returning=$returning tick=$tick $at -> $next") {
                                    (kotlin.math.abs(advance - minOf(.215 * speed, remaining)) <= .000001) shouldBe true
                                    (kotlin.math.abs(next.y - at.y) <= .562501) shouldBe true
                                    citizenPacketBodyClear(terrain.world, next.point(), false) shouldBe true
                                    // Native player stepping lifts the body, then sweeps horizontally.
                                    citizenPacketStepClear(terrain.world, at.point(), next.point(), false) shouldBe true
                                }
                                for (height in listOf(64.5, 65.5, 66.5))
                                    if (kotlin.math.abs(next.y - height) < .000001 && touchesNativeFloor(terrain.world, next))
                                        contacts += height
                                strides += advance
                                at = next
                            }
                        }
                        route.finished shouldBe true
                        route.failure shouldBe null
                        at.distanceSquared(target) shouldBe 0.0
                        if (stairs) contacts.toList() shouldBe
                            if (returning) listOf(66.5, 65.5, 64.5) else listOf(64.5, 65.5, 66.5)
                        return strides
                    }
                    val flat = trajectory(false)
                    val stairs = trajectory(true)
                    stairs.size shouldBe flat.size
                    stairs.zip(flat).forEach { (actual, expected) ->
                        (kotlin.math.abs(actual - expected) <= .000001) shouldBe true
                    }
                }
        }
    }

    test("working motion keeps the provider boost when stair contact precedes its centre column") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("StairMotion")
            var contactedAirFrames = 0
            for ((dx, dz) in listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1))
                for (returning in listOf(false, true)) for (offset in listOf(0.0, .1)) {
                val sign = if (returning) -1 else 1
                fun trajectory(stairs: Boolean): List<Double> {
                    val terrain = ResidentRouteTerrain()
                    for (index in 0..4) terrain.blocks[CivPosition(dx * index, 63, dz * index)] = Material.COBBLESTONE
                    if (stairs) {
                        for (index in 1..3) {
                            val stair = CivPosition(dx * index, 63 + index, dz * index)
                            terrain.blocks[stair] = Material.OAK_STAIRS
                            terrain.boxes[stair] = listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0), when {
                                dx > 0 -> BoundingBox(.5, .5, 0.0, 1.0, 1.0, 1.0)
                                dx < 0 -> BoundingBox(0.0, .5, 0.0, .5, 1.0, 1.0)
                                dz > 0 -> BoundingBox(0.0, .5, .5, 1.0, 1.0, 1.0)
                                else -> BoundingBox(0.0, .5, 0.0, 1.0, 1.0, .5)
                            })
                        }
                        terrain.blocks[CivPosition(dx * 4, 66, dz * 4)] = Material.COBBLESTONE
                    }
                    val lower = Location(terrain.world, .5, 64.0, .5)
                    val upper = Location(terrain.world, dx * 4 + .5, if (stairs) 67.0 else 64.0, dz * 4 + .5)
                    var at = (if (returning) upper else lower).clone().also {
                        it.x -= dx * offset * sign
                        it.z -= dz * offset * sign
                        it.yaw = Math.toDegrees(kotlin.math.atan2(-dx.toDouble() * sign, dz.toDouble() * sign)).toFloat()
                    }
                    val target = if (returning) lower else upper
                    val route = CitizenPacketRoute(terrain.world, at, target,
                        { p -> (0..4).any { p.x == dx * it && p.z == dz * it } && p.y in 64..67 },
                        { _, _ -> true }, { 1.0 })
                    route.prepare()
                    val city = pathsCity().copy(worldId = terrain.world.uid)
                    val runtime = CitizenRuntime(plugin) { listOf(city) }
                    val npc = mockk<PacketPlayerNpc>(relaxed = true) {
                        every { isValid } returns true
                        every { world } returns terrain.world
                        every { location } answers { at.clone() }
                        every { bodyYaw } answers { at.yaw }
                        every { teleport(any()) } answers { at = firstArg<Location>().clone() }
                        every { bodyYaw = any() } answers { at.yaw = firstArg() }
                        every { pitch = any() } answers { at.pitch = firstArg() }
                    }
                    val worker = UUID.randomUUID()
                    val actor = CitizenActor(city.id, worker, city.worldId, npc,
                        CitizenPlan(CitizenToken(null, 0, 1, null), CivPosition(0, 64, 0), null, BuildingType.QUARRY, 0, null))
                    actor.route = CitizenRoute(at.point(), target, CitizenChunks.between(at.blockX, at.blockZ, target.blockX, target.blockZ),
                        0, 90_000, emptySet(), route, null, CivPosition(target.blockX, target.blockY, target.blockZ))
                    runtime.actors[worker] = actor
                    val audience = mockk<CivsCitizenAudience>(relaxed = true) {
                        every { activeAt(any(), any(), any(), any()) } returns true
                    }
                    val work = mockk<CivsCitizenWork>(relaxed = true) { every { workingTravel(actor) } returns true }
                    val collisions = mockk<CivsCitizenCollisions>(relaxed = true) {
                        every { routeMoveAllowed(actor, any(), any()) } returns true
                    }
                    val routes = mockk<CivsCitizenRoutes>(relaxed = true)
                    val logistics = mockk<CivsCitizenLogistics>(relaxed = true)
                    val motion = CivsCitizenMotion(runtime, { collisions }, { routes },
                        { work }, { logistics }, { audience }, mockk(relaxed = true), CivsPaths(),
                        mockk(relaxed = true), mockk(relaxed = true), { _, _, _, _, _ -> },
                        { ground -> if (ground.type in setOf(Material.COBBLESTONE, Material.OAK_STAIRS)) 1.25 else 1.0 },
                        { _, _ -> false }, { runtime.navigationTick * 50 })
                    val strides = mutableListOf<Double>()
                    repeat(100) { tick ->
                        if (!route.finished) {
                            val before = at.clone()
                            val centreAir = terrain.world.getBlockAt(at.blockX, citizenSupportY(at.y), at.blockZ).type.isAir
                            val remaining = ((target.x - at.x) * dx + (target.z - at.z) * dz) * sign
                            motion.activateNavigating()
                            val advance = ((at.x - before.x) * dx + (at.z - before.z) * dz) * sign
                            withClue("stairs=$stairs direction=$dx,$dz returning=$returning offset=$offset tick=$tick $before -> $at failure=${route.failure}") {
                                route.waitingForCollision shouldBe false
                                (kotlin.math.abs(advance - minOf(.215, remaining)) <= .000001) shouldBe true
                            }
                            if (centreAir && touchesNativeFloor(terrain.world, before)) contactedAirFrames++
                            strides += advance
                        }
                    }
                    route.finished shouldBe true
                    route.failure shouldBe null
                    return strides
                }
                val stairs = trajectory(true)
                val flat = trajectory(false)
                stairs.size shouldBe flat.size
                stairs.zip(flat).forEach { (actual, expected) ->
                    (kotlin.math.abs(actual - expected) <= .000001) shouldBe true
                }
            }
            (contactedAirFrames > 0) shouldBe true
        }
    }

    test("stair surface speed uses actual admitted floors and rejects nearby wet or changed support") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            terrain.blocks[CivPosition(0, 63, 0)] = Material.COBBLESTONE
            val stair = CivPosition(1, 64, 0)
            terrain.blocks[stair] = Material.OAK_STAIRS
            terrain.boxes[stair] = listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0),
                BoundingBox(.5, .5, 0.0, 1.0, 1.0, 1.0))
            fun route(start: Location) = CitizenPacketRoute(terrain.world, start, Location(terrain.world, 1.5, 65.0, .5),
                { it.x in 0..1 && it.z == 0 && it.y in 64..65 }, { _, _ -> true }, { 1.0 }).also { it.prepare() }
            val start = Location(terrain.world, .5, 64.0, .5, -90f, 0f)
            val accepted = route(start)
            val contact = requireNotNull(accepted.move(start, 1.0) { true })
            terrain.world.getBlockAt(contact.blockX, citizenSupportY(contact.y), contact.blockZ).type shouldBe Material.AIR
            citizenMotionSurface(contact, accepted).type shouldBe Material.OAK_STAIRS
            // Neither another prepared route nor a position nudged off the admitted profile authorises this floor.
            citizenMotionSurface(contact, route(start)).type shouldBe Material.AIR
            citizenMotionSurface(contact.clone().add(0.0, 0.0, .05), accepted).type shouldBe Material.AIR
            citizenMotionSurface(contact.clone().add(0.0, .25, 0.0), accepted).type shouldBe Material.AIR
            val nativeData = terrain.world.getBlockAt(stair.x, stair.y, stair.z).blockData as org.bukkit.block.data.type.Stairs
            nativeData.isWaterlogged = true
            citizenMotionSurface(contact, accepted).type shouldBe Material.AIR
            nativeData.isWaterlogged = false
            terrain.boxes[stair] = listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
            citizenMotionSurface(contact, accepted).type shouldBe Material.AIR
            accepted.move(contact, 1.0) { true } shouldBe null
            accepted.failure shouldBe "path-changed"

            // Before tread contact the admitted lift still stands over its old road, never a future stair's speed.
            terrain.boxes[stair] = listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0),
                BoundingBox(.5, .5, 0.0, 1.0, 1.0, 1.0))
            val offset = start.clone().also { it.x = .4 }
            val approach = route(offset)
            val lifted = requireNotNull(approach.move(offset, 1.0) { true })
            terrain.world.getBlockAt(lifted.blockX, citizenSupportY(lifted.y), lifted.blockZ).type shouldBe Material.AIR
            citizenMotionSurface(lifted, approach).type shouldBe Material.COBBLESTONE
            approach.stairProfileSupports(lifted.point()).size.let { it in 1..4 } shouldBe true
        }
    }

    test("native stair stride retains entity ceiling protection and ordinary-block boundaries") {
        MockBukkitTestRuntime.open().use {
            for (obstruction in listOf("entity", "ceiling", "protected", "ordinary-block", "wet-stair")) {
                val terrain = ResidentRouteTerrain()
                val stair = CivPosition(1, 64, 0)
                terrain.blocks[stair] = Material.OAK_STAIRS
                terrain.boxes[stair] = listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0),
                    BoundingBox(.5, .5, 0.0, 1.0, 1.0, 1.0))
                val start = Location(terrain.world, .5, 64.0, .5, -90f, 0f)
                var permitted = true
                val route = CitizenPacketRoute(terrain.world, start, Location(terrain.world, 1.5, 65.0, .5),
                    { it.x in 0..1 && it.z == 0 && it.y in 64..65 }, { _, _ -> permitted }, { 1.0 })
                route.prepare()
                when (obstruction) {
                    "ceiling" -> terrain.blocks[CivPosition(0, 66, 0)] = Material.STONE
                    "protected" -> permitted = false
                    "ordinary-block" -> {
                        terrain.blocks[stair] = Material.STONE
                        terrain.boxes.remove(stair)
                    }
                    "wet-stair" -> (terrain.world.getBlockAt(1, 64, 0).blockData as org.bukkit.block.data.type.Stairs).isWaterlogged = true
                }
                val next = route.move(start, 1.0, { _, _ -> obstruction != "entity" }) { true }
                withClue("obstruction=$obstruction next=$next failure=${route.failure}") {
                    when (obstruction) {
                        "entity" -> {
                            next shouldBe null
                            route.waitingForCollision shouldBe true
                            route.failure shouldBe null
                            // Clearing a body does not bank the obstructed frame's stride.
                            val resumed = requireNotNull(route.move(start, 1.0) { true })
                            (kotlin.math.abs(resumed.x - start.x - .215) <= .000001) shouldBe true
                            assertNativeStairFrame(terrain.world, start, resumed)
                        }
                        "ceiling", "protected" -> {
                            next shouldBe null
                            route.failure shouldBe "movement-blocked"
                        }
                        "ordinary-block" -> {
                            val lifted = requireNotNull(next)
                            lifted.x shouldBe start.x
                            lifted.z shouldBe start.z
                            (kotlin.math.abs(lifted.y - start.y - .215) <= .000001) shouldBe true
                            citizenPacketBodyClear(terrain.world, lifted.point(), false) shouldBe true
                        }
                        "wet-stair" -> {
                            val unchanged = requireNotNull(next)
                            (unchanged.x - start.x < .215 - .000001) shouldBe true
                            citizenPacketBodyClear(terrain.world, unchanged.point(), false) shouldBe true
                        }
                    }
                }
            }
        }
    }

    test("stair movement touches every half tread in both directions at every walking speed") {
        MockBukkitTestRuntime.open().use {
            for ((dx, dz) in listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1))
                for (speed in listOf(.55, .8, 1.0, 1.3)) for (returning in listOf(false, true))
                    for (loweredPath in listOf(false, true)) {
                        val terrain = ResidentRouteTerrain()
                        if (loweredPath) terrain.blocks[CivPosition(0, 63, 0)] = Material.DIRT_PATH
                        for (index in 1..3) {
                            val stair = CivPosition(dx * index, 63 + index, dz * index)
                            terrain.blocks[stair] = Material.OAK_STAIRS
                            terrain.boxes[stair] = listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0), when {
                                dx > 0 -> BoundingBox(.5, .5, 0.0, 1.0, 1.0, 1.0)
                                dx < 0 -> BoundingBox(0.0, .5, 0.0, .5, 1.0, 1.0)
                                dz > 0 -> BoundingBox(0.0, .5, .5, 1.0, 1.0, 1.0)
                                else -> BoundingBox(0.0, .5, 0.0, 1.0, 1.0, .5)
                            })
                        }
                        terrain.blocks[CivPosition(dx * 4, 66, dz * 4)] = Material.OAK_PLANKS
                        val lower = Location(terrain.world, .5, if (loweredPath) 63.9375 else 64.0, .5)
                        val upper = Location(terrain.world, dx * 4 + .5, 67.0, dz * 4 + .5)
                        val sign = if (returning) -1 else 1
                        var at = (if (returning) upper else lower).clone().also {
                            it.yaw = Math.toDegrees(kotlin.math.atan2(-dx.toDouble() * sign, dz.toDouble() * sign)).toFloat()
                        }
                        val target = if (returning) lower else upper
                        val route = CitizenPacketRoute(terrain.world, at, target,
                            { p -> (0..4).any { p.x == dx * it && p.z == dz * it } && p.y in 63..67 },
                            { _, _ -> true }, { 1.0 })
                        route.prepare()
                        val contacts = linkedSetOf<Double>()
                        val verticalOnly = mutableListOf<String>()
                        fun wire(point: Location) = point.clone().also {
                            it.x = kotlin.math.round(it.x * 4096) / 4096
                            it.y = kotlin.math.round(it.y * 4096) / 4096
                            it.z = kotlin.math.round(it.z * 4096) / 4096
                        }
                        repeat(250) { tick ->
                            route.move(at, speed) { true }?.let { next ->
                                val horizontal = next.clone().also { it.y = at.y }
                                (at.distance(horizontal) <= .215 * speed + .000001) shouldBe true
                                if (returning && next.y < at.y - .000001 && at.distanceSquared(horizontal) < .00000001)
                                    verticalOnly += "$tick: $at -> $next"
                                val a = wire(at)
                                val b = wire(next)
                                withClue("direction=$dx,$dz speed=$speed returning=$returning path=$loweredPath from=$a to=$b") {
                                    assertNativeStairFrame(terrain.world, a, b)
                                }
                                for (height in listOf(64.5, 65.5, 66.5))
                                    if (kotlin.math.abs(next.y - height) < .000001 &&
                                        touchesNativeFloor(terrain.world, next)) contacts += height
                                at = next
                            }
                        }
                        withClue("direction=$dx,$dz speed=$speed returning=$returning path=$loweredPath at=$at contacts=$contacts vertical=$verticalOnly") {
                            route.failure shouldBe null
                            route.finished shouldBe true
                            at.distanceSquared(target) shouldBe 0.0
                            contacts.toList() shouldBe if (returning) listOf(66.5, 65.5, 64.5) else listOf(64.5, 65.5, 66.5)
                            verticalOnly shouldBe emptyList()
                        }
                    }
        }
    }

    test("short native corner stairs follow their half-height surface and validate shape changes") {
        MockBukkitTestRuntime.open().use {
            for (returning in listOf(false, true)) for (inner in listOf(false, true)) {
                val terrain = ResidentRouteTerrain()
                val stair = CivPosition(1, 64, 1)
                terrain.blocks[stair] = Material.OAK_STAIRS
                terrain.boxes[stair] = listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0)) + if (inner)
                    listOf(BoundingBox(0.0, .5, .5, 1.0, 1.0, 1.0), BoundingBox(.5, .5, 0.0, 1.0, 1.0, .5))
                else listOf(BoundingBox(.5, .5, .5, 1.0, 1.0, 1.0))
                val lower = Location(terrain.world, .5, 64.0, .5)
                val upper = Location(terrain.world, 1.5, 65.0, 1.5)
                var at = (if (returning) upper else lower).clone().also { it.yaw = if (returning) 135f else -45f }
                val route = CitizenPacketRoute(terrain.world, at, if (returning) lower else upper,
                    { it.x in 0..1 && it.z in 0..1 && it.y in 64..65 }, { _, _ -> true }, { 1.0 })
                route.prepare()
                var halfContact = false
                repeat(100) {
                    route.move(at, .8) { true }?.let { next ->
                        assertNativeStairFrame(terrain.world, at, next)
                        if (kotlin.math.abs(next.y - 64.5) < .000001) halfContact = true
                        at = next
                    }
                }
                route.failure shouldBe null
                route.finished shouldBe true
                halfContact shouldBe true
            }
            val terrain = ResidentRouteTerrain()
            val stair = CivPosition(1, 64, 0)
            terrain.blocks[stair] = Material.OAK_STAIRS
            terrain.boxes[stair] = listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0),
                BoundingBox(.5, .5, 0.0, 1.0, 1.0, 1.0))
            val start = Location(terrain.world, .5, 64.0, .5, -90f, 0f)
            val route = CitizenPacketRoute(terrain.world, start, Location(terrain.world, 1.5, 65.0, .5),
                { it.x in 0..1 && it.z == 0 && it.y in 64..65 }, { _, _ -> true }, { 1.0 })
            route.prepare()
            val at = requireNotNull(route.move(start, .8) { true })
            terrain.boxes[stair] = listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
            route.move(at, .8) { true } shouldBe null
            route.failure shouldBe "path-changed"
        }
    }

    test("descending native stair shapes keep forward movement at the ordinary horizontal packet stride") {
        MockBukkitTestRuntime.open().use {
            for ((dx, dz) in listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)) for (speed in listOf(.55, .8, 1.0, 1.3)) {
                val terrain = ResidentRouteTerrain()
                terrain.blocks[CivPosition(0, 67, 0)] = Material.OAK_PLANKS
                for (step in 1..3) {
                    val stair = CivPosition(dx * step, 67 - step, dz * step)
                    terrain.blocks[stair] = Material.OAK_STAIRS
                    // Bottom straight stair, with its upper half facing the higher landing.
                    terrain.boxes[stair] = listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0), when {
                        dx > 0 -> BoundingBox(0.0, .5, 0.0, .5, 1.0, 1.0)
                        dx < 0 -> BoundingBox(.5, .5, 0.0, 1.0, 1.0, 1.0)
                        dz > 0 -> BoundingBox(0.0, .5, 0.0, 1.0, 1.0, .5)
                        else -> BoundingBox(0.0, .5, .5, 1.0, 1.0, 1.0)
                    })
                }
                var at = Location(terrain.world, .5, 68.0, .5,
                    Math.toDegrees(kotlin.math.atan2(-dx.toDouble(), dz.toDouble())).toFloat(), 0f)
                val target = Location(terrain.world, dx * 4 + .5, 64.0, dz * 4 + .5)
                val route = CitizenPacketRoute(terrain.world, at, target,
                    { p -> (0..4).any { p.x == dx * it && p.z == dz * it } && p.y in 64..68 },
                    { _, _ -> true }, { 1.0 })
                route.prepare()
                route.failure shouldBe null
                val verticalOnly = mutableListOf<String>()
                var descended = 0
                repeat(200) { tick ->
                    if (!route.finished) {
                        val next = requireNotNull(route.move(at, speed) { true }) {
                            "direction=$dx,$dz speed=$speed tick=$tick failure=${route.failure}"
                        }
                        val walking = next.clone().also { it.y = at.y }
                        (at.distance(walking) <= .215 * speed + .000001) shouldBe true
                        (at.y - next.y <= 1.000001) shouldBe true
                        if (at.y > 67.000001) for (sample in 0..10) {
                            // The initial full-block drop still follows its clear gravity chord.
                            val point = at.clone().add(next.toVector().subtract(at.toVector()).multiply(sample / 10.0))
                            citizenPacketBodyClear(terrain.world, point.point(), false) shouldBe true
                        } else assertNativeStairFrame(terrain.world, at, next)
                        if (next.y < at.y - .000001) {
                            descended++
                            if ((target.x - at.x) * dx + (target.z - at.z) * dz > .000001 &&
                                (next.x - at.x) * dx + (next.z - at.z) * dz <= .000001)
                                verticalOnly += "$tick: ${at.x},${at.y},${at.z} -> ${next.x},${next.y},${next.z}"
                        }
                        at = next
                    }
                }
                withClue("direction=$dx,$dz speed=$speed verticalOnly=$verticalOnly") {
                    route.failure shouldBe null
                    route.finished shouldBe true
                    at.distanceSquared(target) shouldBe 0.0
                    (descended > 0) shouldBe true
                    verticalOnly shouldBe emptyList()
                }
            }
        }
    }

    test("full-block downhill packet frames preserve horizontal stride through native gravity and quantization") {
        MockBukkitTestRuntime.open().use {
            for ((dx, dz) in listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1))
                for (speed in listOf(.55, 1.3)) for (surface in listOf(Material.STONE, Material.DIRT_PATH)) {
                    val terrain = ResidentRouteTerrain()
                    val inset = if (surface == Material.DIRT_PATH) .0625 else 0.0
                    for (column in 0..5) {
                        val top = 66 - minOf(column, 3)
                        for (y in 63..top) terrain.blocks[CivPosition(dx * column, y, dz * column)] =
                            if (y == top) surface else Material.STONE
                    }
                    var at = Location(terrain.world, .5, 67.0 - inset, .5,
                        Math.toDegrees(kotlin.math.atan2(-dx.toDouble(), dz.toDouble())).toFloat(), 0f)
                    val target = Location(terrain.world, dx * 5 + .5, 64.0 - inset, dz * 5 + .5)
                    val route = CitizenPacketRoute(terrain.world, at, target,
                        { p -> (0..5).any { p.x == dx * it && p.z == dz * it } && p.y in 63..67 },
                        { _, _ -> true }, { 1.0 })
                    route.prepare()
                    route.failure shouldBe null
                    fun frame(at: Location) = at.clone().also {
                        // PacketPlayerNpc keeps precise XYZ and quantizes absolute wire endpoints.
                        it.x = kotlin.math.round(it.x * 4096.0) / 4096.0
                        it.y = kotlin.math.round(it.y * 4096.0) / 4096.0
                        it.z = kotlin.math.round(it.z * 4096.0) / 4096.0
                    }
                    var previousFall = 0.0
                    var fallingFrames = 0
                    repeat(100) { tick ->
                        if (!route.finished) {
                            val before = frame(at)
                            val next = requireNotNull(route.move(at, speed) { true }) {
                                "surface=$surface direction=$dx,$dz speed=$speed tick=$tick failure=${route.failure}"
                            }
                            val after = frame(next)
                            val forward = (after.x - before.x) * dx + (after.z - before.z) * dz
                            val remaining = (target.x - at.x) * dx + (target.z - at.z) * dz
                            val fall = (before.y - after.y).coerceAtLeast(0.0)
                            withClue("surface=$surface direction=$dx,$dz speed=$speed tick=$tick $before -> $after") {
                                // A straight open route spends its horizontal stride even during a full-block fall.
                                (kotlin.math.abs(forward - minOf(.215 * speed, remaining)) <= 1.0 / 2048) shouldBe true
                                (fall <= (previousFall + .08) * .98 + 1.0 / 1024) shouldBe true
                                (kotlin.math.abs(citizenAngleChange(at.yaw, next.yaw)) <= CITIZEN_TURN_PER_TICK) shouldBe true
                                for (sample in 0..16) {
                                    val point = before.clone().add(after.toVector().subtract(before.toVector()).multiply(sample / 16.0))
                                    citizenPacketBodyClear(terrain.world, CitizenPoint(point.x, point.y, point.z), false) shouldBe true
                                }
                            }
                            if (fall > 1.0 / 4096) fallingFrames++
                            previousFall = fall
                            at = next
                        }
                    }
                    route.failure shouldBe null
                    route.finished shouldBe true
                    at.distanceSquared(target) shouldBe 0.0
                    citizenPacketSupported(terrain.world, CitizenPoint(at.x, at.y, at.z)) shouldBe true
                    (fallingFrames > 3) shouldBe true
                }
        }
    }

    test("a downhill resident settles while yielding and follows a walled corner without cutting its packet chord") {
        MockBukkitTestRuntime.open().use {
            for (corner in listOf(false, true)) {
                val terrain = ResidentRouteTerrain()
                terrain.blocks[CivPosition(0, 64, 0)] = Material.STONE
                if (corner) {
                    for (z in -1..4) for (y in 64..66) terrain.blocks[CivPosition(2, y, z)] = Material.STONE
                    for (y in 64..66) terrain.blocks[CivPosition(0, y, 1)] = Material.STONE
                }
                var at = Location(terrain.world, .5, 65.0, .5, -90f, 0f)
                val target = if (corner) Location(terrain.world, 1.5, 64.0, 3.5)
                    else Location(terrain.world, 5.5, 64.0, .5)
                val route = CitizenPacketRoute(terrain.world, at, target,
                    { p -> p.y in 64..65 && if (corner) p.x in 0..1 && p.z == 0 || p.x == 1 && p.z in 0..3
                        else p.x in 0..5 && p.z == 0 }, { _, _ -> true }, { 1.0 })
                route.prepare()
                route.failure shouldBe null
                var yielded = false
                var fellWhileTurning = false
                repeat(120) { tick ->
                    if (!route.finished) {
                        if (!corner && !yielded && at.y in 64.0001..64.9999) {
                            val stopped = at.clone()
                            repeat(10) {
                                route.move(at, 1.3, { a, b -> a.x == b.x && a.z == b.z }) { true }?.let { at = it }
                                at.x shouldBe stopped.x
                                at.z shouldBe stopped.z
                                citizenPacketBodyClear(terrain.world, CitizenPoint(at.x, at.y, at.z), false) shouldBe true
                            }
                            at.y shouldBe 64.0
                            route.finished shouldBe false
                            route.waitingForCollision shouldBe true
                            yielded = true
                        }
                        val next = requireNotNull(route.move(at, 1.3) { true }) { "corner=$corner tick=$tick failure=${route.failure}" }
                        withClue("corner=$corner tick=$tick $at -> $next") {
                            val walking = next.clone().also { it.y = at.y }
                            (at.distance(walking) <= .279501) shouldBe true
                            (kotlin.math.abs(citizenAngleChange(at.yaw, next.yaw)) <= CITIZEN_TURN_PER_TICK) shouldBe true
                            for (sample in 0..16) {
                                val point = at.clone().add(next.toVector().subtract(at.toVector()).multiply(sample / 16.0))
                                citizenPacketBodyClear(terrain.world, CitizenPoint(point.x, point.y, point.z), false) shouldBe true
                            }
                        }
                        if (route.turning && next.y < at.y) fellWhileTurning = true
                        at = next
                    }
                }
                route.failure shouldBe null
                route.finished shouldBe true
                at.distanceSquared(target) shouldBe 0.0
                if (corner) fellWhileTurning shouldBe true else yielded shouldBe true
            }
        }
    }

    test("a lowered path transition still respects a swept head obstacle and a protected destination") {
        MockBukkitTestRuntime.open().use {
            for (obstacle in listOf("headroom", "protected")) {
                val terrain = ResidentRouteTerrain()
                terrain.blocks[CivPosition(0, 63, 0)] = Material.DIRT_PATH
                terrain.blocks[CivPosition(1, 64, 0)] = Material.STONE
                if (obstacle == "headroom") terrain.blocks[CivPosition(0, 66, 0)] = Material.STONE
                val from = CitizenPoint(.5, 63.9375, .5)
                val to = CitizenPoint(1.5, 65.0, .5)
                citizenPacketBodyClear(terrain.world, from, false) shouldBe true
                citizenPacketBodyClear(terrain.world, to, false) shouldBe true
                val route = CitizenPacketRoute(terrain.world,
                    Location(terrain.world, from.x, from.y, from.z), Location(terrain.world, to.x, to.y, to.z),
                    { p -> p.z == 0 && p.x in 0..1 && p.y in 63..65 && (obstacle != "protected" || p.x != 1) },
                    { _, _ -> true }, { 1.0 })
                route.prepare()
                withClue(obstacle) { route.failure shouldBe "no-path" }
            }
        }
    }

    test("rounded observed path feet settle onto native support before crossing the taller edge in either direction") {
        MockBukkitTestRuntime.open().use {
            for (returning in listOf(false, true)) {
                val terrain = ResidentRouteTerrain()
                terrain.blocks[CivPosition(0, 63, 0)] = Material.DIRT_PATH
                terrain.blocks[CivPosition(1, 64, 0)] = Material.STONE
                val low = Location(terrain.world, .5, 63.94, .5)
                val high = Location(terrain.world, 1.5, 65.0, .5)
                var at = (if (returning) high else low).clone()
                val target = if (returning) low else high
                val route = CitizenPacketRoute(terrain.world, at, target,
                    { p -> p.x in 0..1 && p.z == 0 && p.y in 63..65 }, { _, _ -> true }, { 1.0 })
                route.prepare()
                route.failure shouldBe null
                repeat(100) { route.move(at, 1.0) { true }?.let { at = it } }
                route.failure shouldBe null
                route.finished shouldBe true
                at.x shouldBe target.x
                at.z shouldBe target.z
                at.y shouldBe if (returning) 63.9375 else 65.0
                citizenPacketSupported(terrain.world, CitizenPoint(at.x, at.y, at.z)) shouldBe true
            }
        }
    }

    for (height in listOf(1.5, 2.0)) for (returning in listOf(false, true))
    test("a $height block ledge remains unreachable returning=$returning") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            terrain.blocks[CivPosition(1, 64, 0)] = Material.STONE
            terrain.blocks[CivPosition(1, 65, 0)] = if (height == 1.5) Material.OAK_SLAB else Material.STONE
            if (height == 1.5) terrain.boxes[CivPosition(1, 65, 0)] =
                listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0))
            val low = Location(terrain.world, .5, 64.0, .5)
            val high = Location(terrain.world, 1.5, 64.0 + height, .5)
            val route = CitizenPacketRoute(terrain.world, if (returning) high else low, if (returning) low else high,
                { p -> p.x in 0..1 && p.z == 0 && p.y in 64..66 }, { _, _ -> true }, { 1.0 })
            route.prepare()
            route.failure shouldBe "no-path"
        }
    }

    for (material in listOf(Material.CAMPFIRE, Material.SOUL_CAMPFIRE)) {
        test("work and social ingress routes walk around a lit $material without touching its footprint") {
            MockBukkitTestRuntime.open().use {
                val terrain = ResidentRouteTerrain()
                val fire = CivPosition(2, 64, 0)
                terrain.blocks[fire] = material
                val seat = citizenCampSeats(fire, UUID(0, 1)).first { it.x == 4 && it.y == 64 && it.z == 0 }
                for (target in listOf(CivPosition(5, 64, 0), seat)) {
                    // Long idle ingress shares the ordinary route gate with work travel.
                    var at = Location(terrain.world, -9.5, 64.0, .5)
                    val route = CitizenPacketRoute(terrain.world, at,
                        Location(terrain.world, target.x + .5, target.y.toDouble(), target.z + .5),
                        { p -> p.x in -10..8 && p.z in -5..5 && p.y in 64..65 }, { _, _ -> true }, { 1.0 })
                    route.prepare()
                    route.failure shouldBe null
                    route.searching shouldBe false
                    repeat(200) {
                        route.move(at, 1.0) { true }?.let { next ->
                            (next.x + .299 > fire.x && next.x - .299 < fire.x + 1 &&
                                next.z + .299 > fire.z && next.z - .299 < fire.z + 1) shouldBe false
                            (at.distance(next) <= .215001) shouldBe true
                            at = next
                        }
                    }
                    route.finished shouldBe true
                    at.distanceSquared(Location(terrain.world, target.x + .5, target.y.toDouble(), target.z + .5)) shouldBe 0.0
                }
            }
        }

        test("lighting a planned $material foothold stops movement before the resident enters it") {
            MockBukkitTestRuntime.open().use {
                val terrain = ResidentRouteTerrain()
                terrain.blocks[CivPosition(2, 64, 0)] = material
                val fire = terrain.world.getBlockAt(2, 64, 0).blockData as Lightable
                fire.isLit = false
                val route = terrain.route()
                route.prepare()
                route.failure shouldBe null
                fire.isLit = true // Shapes remain identical; execution must recheck the heat.
                var at = Location(terrain.world, .5, 64.0, .5)
                repeat(80) { route.move(at, 1.0) { true }?.let { at = it } }
                route.failure shouldBe "movement-blocked"
                (at.x + .299 <= 2.0) shouldBe true
                citizenPacketBodyClear(terrain.world, CitizenPoint(at.x, at.y, at.z), false) shouldBe true
            }
        }

        test("a resident already above a lit $material can walk off it but cannot walk back") {
            MockBukkitTestRuntime.open().use {
                val terrain = ResidentRouteTerrain()
                terrain.blocks[CivPosition(2, 64, 0)] = material
                val exposed = CitizenPoint(2.5, 64.4375, .5)
                citizenPacketSupported(terrain.world, exposed) shouldBe true
                citizenPacketBodyClear(terrain.world, exposed, false) shouldBe true
                citizenPacketCampfireClear(terrain.world, exposed) shouldBe false
                val route = CitizenPacketRoute(terrain.world, Location(terrain.world, exposed.x, exposed.y, exposed.z),
                    Location(terrain.world, 5.5, 64.0, .5), terrain::allowed, { _, _ -> true }, { 1.0 })
                route.prepare()
                route.failure shouldBe null
                var at = Location(terrain.world, exposed.x, exposed.y, exposed.z)
                repeat(80) { route.move(at, 1.0) { true }?.let { at = it } }
                route.finished shouldBe true
                at.distanceSquared(Location(terrain.world, 5.5, 64.0, .5)) shouldBe 0.0
                citizenPacketStepClear(terrain.world, CitizenPoint(3.5, exposed.y, .5), exposed, false) shouldBe false
            }
        }
    }

    test("campfire heat covers body-side and support rows and prevents a scaled nudge into new heat") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            terrain.blocks[CivPosition(2, 65, 0)] = Material.CAMPFIRE
            citizenPacketCampfireClear(terrain.world, CitizenPoint(1.75, 64.0, .5)) shouldBe false
            citizenPacketCampfireClear(terrain.world, CitizenPoint(1.7, 64.0, .5)) shouldBe true
            terrain.blocks.remove(CivPosition(2, 65, 0))
            terrain.blocks[CivPosition(2, 63, 0)] = Material.SOUL_CAMPFIRE
            val from = Location(terrain.world, 1.69, 64.0, .5)
            val to = from.clone().add(.05, 0.0, 0.0)
            citizenPacketBodyClear(terrain.world, CitizenPoint(to.x, to.y, to.z), false) shouldBe true
            val npc = mockk<PacketPlayerNpc> {
                every { isValid } returns true
                every { world } returns terrain.world
                every { scale } returns 1.0
                every { height } returns 1.8
            }
            citizenResidentNudgeClear(npc, from, to) shouldBe false
            every { npc.scale } returns .5
            every { npc.height } returns .9
            citizenResidentNudgeClear(npc, from, to) shouldBe true
        }
    }

    test("idle route crosses authored log floor beams without allowing an outside tree route") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            for (x in 0..5) terrain.blocks[CivPosition(x, 63, 0)] = Material.OAK_PLANKS
            // medievaloak/townhall1 has log beams across its plank floor, including under
            // an interior door. A full-width beam must not partition a resident's room.
            terrain.blocks[CivPosition(2, 63, 0)] = Material.OAK_LOG
            val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(0, 63, 0), 0,
                "civs/minecolonies/medievaloak/fundamentals/townhall1",
                CivBox(CivPosition(0, 63, -1), CivPosition(5, 66, 1)), CivPosition(0, 64, 0), BuildingPhase.ACTIVE)
            fun route(buildings: List<CivBuilding>) = CitizenPacketRoute(terrain.world,
                Location(terrain.world, .5, 64.0, .5), Location(terrain.world, 5.5, 64.0, .5),
                { it.x in 0..5 && it.y == 64 && it.z == 0 && citizenIdleTerrain(terrain.world, it, buildings) },
                { _, _ -> true }, { 1.0 })
            val outside = route(emptyList())
            outside.prepare()
            outside.failure shouldBe null
            outside.continuationReason shouldBe "no-path"
            outside.endpoint shouldBe CitizenPoint(1.5, 64.0, .5)

            val indoors = route(listOf(hall))
            indoors.prepare()
            indoors.failure shouldBe null
            var at = Location(terrain.world, .5, 64.0, .5)
            repeat(80) { indoors.move(at, 1.0) { true }?.let { at = it } }
            indoors.finished shouldBe true
            at.distanceSquared(Location(terrain.world, 5.5, 64.0, .5)) shouldBe 0.0
        }
    }

    test("temporary player collision preserves the waypoint and resumes when the passage clears") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            val route = terrain.route()
            route.prepare()
            val waypoint = requireNotNull(route.destination)
            var at = Location(terrain.world, .5, 64.0, .5, -90f, 0f)
            repeat(10) {
                route.move(at, 1.0, { _, _ -> false }) { true } shouldBe null
                route.destination shouldBe waypoint
                route.waitingForCollision shouldBe true
                route.failure shouldBe null
                route.navigating shouldBe true
            }
            repeat(80) { route.move(at, 1.0, { _, _ -> true }) { true }?.let { at = it } }
            route.waitingForCollision shouldBe false
            route.finished shouldBe true
            route.failure shouldBe null
            at.distanceSquared(Location(terrain.world, 5.5, 64.0, .5)) shouldBe 0.0
        }
    }

    test("five nearby destinations share a walked trunk while a distant parallel trip keeps its short route") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            val city = pathsCity().copy(worldId = terrain.world.uid)
            val paths = CivsPaths()
            fun walk(fromZ: Int, toZ: Int): Set<CivPosition> {
                val trip = paths.begin(city)
                var at = Location(terrain.world, .5, 64.0, fromZ + .5)
                val route = CitizenPacketRoute(terrain.world, at, Location(terrain.world, 16.5, 64.0, toZ + .5),
                    { p -> p.x in 0..16 && p.z in -4..6 && p.y == 64 }, { _, _ -> true },
                    { block -> trip.cost(block) { 1.0 } })
                route.prepare()
                route.failure shouldBe null
                route.searching shouldBe false
                val walked = linkedSetOf<CivPosition>()
                repeat(400) {
                    trip.observe(CitizenPoint(at.x, at.y, at.z))
                    route.move(at, 1.0) { true }?.let { next ->
                        walked += CivPosition(next.blockX, citizenSupportY(next.y), next.blockZ)
                        trip.observe(CitizenPoint(next.x, next.y, next.z))
                        at = next
                    }
                }
                route.finished shouldBe true
                paths.complete(city, trip)
                repeat(20) { paths.pulse() }
                return walked
            }
            walk(0, 0)
            val nearby = listOf(-2, -1, 0, 1, 2).map { walk(0, it) }
            nearby.all { it.count { p -> p.z == 0 && p.x in 2..12 } >= 9 } shouldBe true
            paths.remembered(city.id).none { it.x in 2..10 && it.z in setOf(-2, -1, 1, 2) } shouldBe true
            walk(6, 6).none { it.z == 0 } shouldBe true
        }
    }

    test("corridor preference cannot cross a protected footprint or climb an outdoor log") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            val city = pathsCity().copy(worldId = terrain.world.uid)
            val paths = CivsPaths()
            val learned = paths.begin(city)
            for (x in 0..5) learned.observe(CitizenPoint(x + .5, 64.0, .5))
            paths.complete(city, learned)
            paths.pulse()
            val preferred = paths.begin(city)
            val house = city.buildings.first().copy(id = UUID.randomUUID(), type = BuildingType.HOUSE,
                bounds = CivBox(CivPosition(2, 63, -1), CivPosition(3, 66, 1)))
            val protectedCity = city.copy(buildings = city.buildings + house)
            val detour = CitizenPacketRoute(terrain.world, Location(terrain.world, .5, 64.0, .5),
                Location(terrain.world, 5.5, 64.0, .5),
                { p -> terrain.allowed(p) && citizenRouteCellAllowed(protectedCity, emptySet(), p.x, p.z) },
                { _, _ -> true }, { block -> preferred.cost(block) { 1.0 } })
            detour.prepare()
            detour.failure shouldBe null
            var at = Location(terrain.world, .5, 64.0, .5)
            repeat(160) { detour.move(at, 1.0) { true }?.let { next ->
                citizenRouteCellAllowed(protectedCity, emptySet(), next.blockX, next.blockZ) shouldBe true
                at = next
            } }
            detour.finished shouldBe true

            terrain.blocks[CivPosition(2, 63, 0)] = Material.OAK_LOG
            val tree = CitizenPacketRoute(terrain.world, Location(terrain.world, .5, 64.0, .5),
                Location(terrain.world, 5.5, 64.0, .5), { p -> p.x in 0..5 && p.y == 64 && p.z == 0 },
                { _, _ -> true }, { block -> preferred.cost(block) { 1.0 } })
            tree.prepare()
            tree.failure shouldBe null
            tree.continuationReason shouldBe "no-path"
            tree.endpoint shouldBe CitizenPoint(1.5, 64.0, .5)
        }
    }

    test("existing roads beat a modest learned grass shortcut but not an excessive detour") {
        MockBukkitTestRuntime.open().use {
            for (material in listOf(Material.DIRT_PATH, Material.COBBLESTONE)) {
                fun routeOnRoad(detour: Int): Set<CivPosition> {
                    val terrain = ResidentRouteTerrain()
                    val city = pathsCity().copy(worldId = terrain.world.uid)
                    val paths = CivsPaths()
                    val shortcut = paths.begin(city)
                    for (x in 0..16) shortcut.observe(CitizenPoint(x + .5, 64.0, .5))
                    paths.complete(city, shortcut)
                    repeat(2) { paths.pulse() }
                    for (x in 0..16) terrain.blocks[CivPosition(x, 63, detour)] = material
                    for (z in 0..detour) for (x in listOf(0, 16)) terrain.blocks[CivPosition(x, 63, z)] = material
                    val trip = paths.begin(city)
                    var at = Location(terrain.world, .5, if (material == Material.DIRT_PATH) 63.9375 else 64.0, .5)
                    val route = CitizenPacketRoute(terrain.world, at, Location(terrain.world, 16.5, at.y, .5),
                        { p -> p.x in 0..16 && p.z in 0..detour && p.y in 63..64 }, { _, _ -> true },
                        { ground -> trip.cost(ground) { if (it.type == Material.COBBLESTONE) 1.3 else 1.0 } })
                    route.prepare()
                    route.failure shouldBe null
                    route.searching shouldBe false
                    val walked = linkedSetOf<CivPosition>()
                    repeat(600) {
                        route.move(at, 1.0) { true }?.let { next ->
                            walked += CivPosition(next.blockX, citizenSupportY(next.y), next.blockZ)
                            at = next
                        }
                    }
                    route.finished shouldBe true
                    return walked
                }
                val modest = routeOnRoad(3)
                withClue("$material should reuse the existing road despite the learned direct shortcut") {
                    modest.count { it.x in 2..14 && it.z == 3 } shouldBe 13
                }
                val excessive = routeOnRoad(10)
                withClue("$material should not turn a short walk into a large road detour") {
                    excessive.none { it.z == 10 } shouldBe true
                    excessive.count { it.x in 2..14 && it.z == 0 } shouldBe 13
                }
            }
        }
    }

    test("a path at fifteen sixteenths height reaches its endpoint and emits every actual cell crossing") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            for (x in 0..5) terrain.blocks[CivPosition(x, 63, 0)] = Material.DIRT_PATH
            val trip = CivsPaths().begin(pathsCity().copy(worldId = terrain.world.uid))
            val first = terrain.world.getBlockAt(0, 63, 0)
            trip.cost(first) { 5.0 } shouldBe 1.0 // Explicit path wins over a provider's recognized surface.
            val route = CitizenPacketRoute(terrain.world, Location(terrain.world, .95, 63.9375, .5),
                Location(terrain.world, 5.5, 64.0, .5), { p -> p.x in 0..5 && p.z == 0 && p.y in 63..64 },
                { _, _ -> true }, { block -> trip.cost(block) { 1.0 } })
            route.prepare()
            route.failure shouldBe null
            route.searching shouldBe false
            var at = Location(terrain.world, .95, 63.9375, .5)
            val steps = CitizenFootsteps()
            val departed = mutableListOf<CivPosition>()
            repeat(80) {
                val before = at
                route.move(at, 1.0) { true }?.let { next ->
                    steps.prime(before, citizenPacketSupported(terrain.world, CitizenPoint(before.x, before.y, before.z)))
                    steps.observe(next, citizenPacketSupported(terrain.world, CitizenPoint(next.x, next.y, next.z)))?.let { (from, to) ->
                        if (from.blockX != to.blockX || from.blockZ != to.blockZ)
                            departed += CivPosition(from.blockX, citizenSupportY(from.y), from.blockZ)
                    }
                    at = next
                }
            }
            route.finished shouldBe true
            at.y shouldBe 63.9375
            at.x shouldBe 5.5
            departed shouldBe (0..4).map { CivPosition(it, 63, 0) }
        }
    }

    test("route movement takes the short turn after accumulated rotations in either direction") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            for (sign in listOf(1, -1)) {
                var at = Location(terrain.world, .5, 64.0, 3.5, sign * 539f, 0f)
                val route = CitizenPacketRoute(terrain.world, at, Location(terrain.world, .5, 64.0, .5),
                    { p -> p.x == 0 && p.z in 0..3 && p.y == 64 }, { _, _ -> true }, { 1.0 })
                route.prepare()
                route.searching shouldBe false
                route.failure shouldBe null
                val waypoint = requireNotNull(route.destination)
                (waypoint.z < at.z) shouldBe true
                // A small observed nudge makes the next actual leg -179 / +179 degrees.
                // Anticipation can aim farther along that leg, but must still cross
                // the accumulated +/-540 boundary in the short, matching direction.
                at.x -= sign * kotlin.math.tan(Math.toRadians(1.0)) * (at.z - waypoint.z)
                val first = requireNotNull(route.move(at, 1.0) { true })
                withClue("sign=$sign from=${at.yaw} next=${first.yaw}") {
                    ((first.yaw - at.yaw) * sign in 1f..2.001f) shouldBe true
                }
                (kotlin.math.abs(at.distance(first) - .215) < .000001) shouldBe true
                var turned = kotlin.math.abs(first.yaw - at.yaw)
                at = first
                for (tick in 0 until 32) {
                    if (route.finished) break
                    val next = requireNotNull(route.move(at, 1.0) { true })
                    (at.distance(next) <= .215001) shouldBe true
                    turned += kotlin.math.abs(next.yaw - at.yaw)
                    at = next
                }
                route.failure shouldBe null
                route.finished shouldBe true
                at.x shouldBe .5
                at.z shouldBe .5
                (turned <= 3.001f) shouldBe true
            }
        }
    }

    test("a flat path corner is rounded within its corridor without stopping accelerating or crossing its walls") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            fun corridor(x: Int, z: Int) = x in 0..5 && z == 0 || x == 5 && z in 0..5
            for (x in -1..6) for (z in -1..6) {
                if (corridor(x, z)) terrain.blocks[CivPosition(x, 63, z)] = Material.DIRT_PATH
                else for (y in 64..65) terrain.blocks[CivPosition(x, y, z)] = Material.STONE
            }
            var at = Location(terrain.world, .5, 63.9375, .5, -90f, 0f)
            val route = CitizenPacketRoute(terrain.world, at, Location(terrain.world, 5.5, 63.9375, 5.5),
                { corridor(it.x, it.z) && it.y in 63..64 }, { _, _ -> true }, { 1.0 })
            route.prepare()
            route.failure shouldBe null
            var rounded = false
            var turns = 0f
            repeat(160) { tick ->
                val progress = route.forwardProgress
                route.move(at, .8 * 1.25) { true }?.let { next ->
                    val delta = kotlin.math.abs(citizenAngleChange(at.yaw, next.yaw))
                    (delta <= CITIZEN_TURN_PER_TICK + .001) shouldBe true
                    turns += delta
                    (at.distance(next) <= .215001) shouldBe true
                    (route.forwardProgress - progress <= at.distance(next) + .000001) shouldBe true
                    citizenPacketStepClear(terrain.world, CitizenPoint(at.x, at.y, at.z),
                        CitizenPoint(next.x, next.y, next.z), false) shouldBe true
                    citizenPacketSupported(terrain.world, CitizenPoint(next.x, next.y, next.z)) shouldBe true
                    terrain.world.getBlockAt(next.blockX, citizenSupportY(next.y), next.blockZ).type shouldBe Material.DIRT_PATH
                    val departure = minOf(kotlin.math.abs(next.z - .5), kotlin.math.abs(next.x - 5.5))
                    (departure <= .151) shouldBe true
                    if (departure > .015) rounded = true
                    if (at.x > 4.8 && at.z < 1.2) withClue("tick=$tick from=$at next=$next turning=${route.turning}") {
                        (at.distance(next) > .15) shouldBe true
                    }
                    at = next
                }
            }
            rounded shouldBe true
            route.failure shouldBe null
            route.finished shouldBe true
            (turns < 110f) shouldBe true
            at.x shouldBe 5.5
            at.z shouldBe 5.5
        }
    }

    test("nudges and repeated walking over the same ordered segment cannot earn recovery progress") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            var at = Location(terrain.world, .5, 64.0, .5, -90f, 0f)
            val route = CitizenPacketRoute(terrain.world, at, Location(terrain.world, 8.5, 64.0, .5),
                { it.x in -3..8 && it.z == 0 && it.y == 64 }, { _, _ -> true }, { 1.0 })
            route.prepare()
            route.failure shouldBe null
            repeat(3) { at = requireNotNull(route.move(at, 1.0) { true }) }
            (route.forwardProgress > .64) shouldBe true
            val highWater = at.clone()
            val earned = route.forwardProgress
            for (distance in listOf(.5, 1.0, 2.0)) {
                at = highWater.clone().subtract(distance, 0.0, 0.0)
                repeat(16) {
                    val remaining = highWater.x - at.x
                    if (remaining > .000001) at = requireNotNull(route.move(at, minOf(.215, remaining) / .215) { true })
                }
                (at.distance(highWater) < .000001) shouldBe true
                withClue("back-and-forth distance=$distance progress=${route.forwardProgress}") {
                    (kotlin.math.abs(route.forwardProgress - earned) < .000001) shouldBe true
                }
            }
            // A forward nudge raises the watermark without counting as accepted walking.
            val nudged = at.clone().add(.25, 0.0, 0.0)
            route.move(nudged, 1.0, { _, _ -> false }) { true } shouldBe null
            route.forwardProgress shouldBe earned
            repeat(2) {
                val remaining = nudged.x - at.x
                at = requireNotNull(route.move(at, minOf(.215, remaining) / .215) { true })
            }
            (kotlin.math.abs(route.forwardProgress - earned) < .000001) shouldBe true
            at = requireNotNull(route.move(at, .1 / .215) { true })
            (kotlin.math.abs(route.forwardProgress - earned - .1) < .000001) shouldBe true

            // Neither a newly planned path nor a different observed origin contributes credit.
            for (origin in listOf(at, at.clone().add(2.0, 0.0, 0.0))) {
                val replanned = route.replan(origin, emptyList())
                replanned.prepare()
                replanned.failure shouldBe null
                replanned.forwardProgress shouldBe 0.0
                replanned.move(origin, 1.0, { _, _ -> false }) { true }
                replanned.forwardProgress shouldBe 0.0
            }
        }
    }

    test("a validated detour earns forward path progress while initially walking away from the final goal") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            fun corridor(x: Int, z: Int) = x in 0..4 && z in listOf(0, 2) || x == 4 && z in 0..2
            for (x in -1..5) for (z in -1..3) if (!corridor(x, z))
                for (y in 64..65) terrain.blocks[CivPosition(x, y, z)] = Material.STONE
            var at = Location(terrain.world, .5, 64.0, .5, -90f, 0f)
            val goal = Location(terrain.world, .5, 64.0, 2.5)
            val initialDistance = at.distance(goal)
            val route = CitizenPacketRoute(terrain.world, at, goal,
                { corridor(it.x, it.z) && it.y == 64 }, { _, _ -> true }, { 1.0 })
            route.prepare()
            route.failure shouldBe null
            repeat(3) { at = requireNotNull(route.move(at, 1.0) { true }) }
            (at.distance(goal) > initialDistance) shouldBe true
            (route.forwardProgress > .64) shouldBe true
            repeat(160) { route.move(at, 1.0) { true }?.let { next ->
                (at.distance(next) <= .215001) shouldBe true
                (kotlin.math.abs(citizenAngleChange(at.yaw, next.yaw)) <= CITIZEN_TURN_PER_TICK + .001) shouldBe true
                citizenPacketStepClear(terrain.world, CitizenPoint(at.x, at.y, at.z),
                    CitizenPoint(next.x, next.y, next.z), false) shouldBe true
                at = next
            } }
            route.failure shouldBe null
            route.finished shouldBe true
            (at.distance(goal) < .000001) shouldBe true
            (route.forwardProgress > 9.0) shouldBe true
        }
    }

    test("an opposite-facing resident turns on its support before rising onto the next step") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            for (x in 1..3) terrain.blocks[CivPosition(x, 64, 0)] = Material.STONE
            var at = Location(terrain.world, .5, 64.0, .5, 90f, 0f)
            val route = CitizenPacketRoute(terrain.world, at, Location(terrain.world, 3.5, 65.0, .5),
                { it.x in 0..3 && it.z == 0 && it.y in 64..65 }, { _, _ -> true }, { 1.0 })
            route.prepare()
            route.failure shouldBe null
            var stationaryTurns = 0
            var advanced = false
            repeat(80) {
                route.move(at, 1.0) { true }?.let { next ->
                    (kotlin.math.abs(citizenAngleChange(at.yaw, next.yaw)) <= CITIZEN_TURN_PER_TICK + .001) shouldBe true
                    if (!advanced && next.distanceSquared(at) > .000001) {
                        (kotlin.math.abs(citizenAngleChange(at.yaw, -90f)) <= 65f) shouldBe true
                        advanced = true
                    } else if (!advanced) {
                        citizenPacketSupported(terrain.world, CitizenPoint(next.x, next.y, next.z)) shouldBe true
                        next.y shouldBe 64.0
                        stationaryTurns++
                    }
                    citizenPacketStepClear(terrain.world, CitizenPoint(at.x, at.y, at.z),
                        CitizenPoint(next.x, next.y, next.z), false) shouldBe true
                    at = next
                }
            }
            stationaryTurns shouldBe 15
            advanced shouldBe true
            route.failure shouldBe null
            route.finished shouldBe true
            at.x shouldBe 3.5
            at.y shouldBe 65.0
        }
    }

    test("work facing route corners and a nudged replan share bounded packet-shaped body and head frames") {
        MockBukkitTestRuntime.open().use {
            for (sign in listOf(1, -1)) {
                val terrain = ResidentRouteTerrain()
                fun corridor(x: Int, z: Int) = x == 0 && z in 0..5 || z == 0 && x in 0..5
                for (x in -1..6) for (z in -1..6) {
                    if (corridor(x, z)) terrain.blocks[CivPosition(x, 63, z)] = Material.DIRT_PATH
                    else for (y in 64..65) terrain.blocks[CivPosition(x, y, z)] = Material.STONE
                }
                var position = Location(terrain.world, .5, 63.9375, 5.5, sign * 539f, 0f)
                var body = position.yaw
                var head = body
                var pitch = 0f
                val npc = mockk<PacketPlayerNpc> {
                    every { location } answers { position.clone().also { it.yaw = body; it.pitch = pitch } }
                    every { bodyYaw } answers { body }
                    every { headYaw } answers { head }
                    every { this@mockk.pitch } answers { pitch }
                    every { bodyYaw = any() } answers { body = firstArg() }
                    every { headYaw = any() } answers { head = firstArg() }
                    every { this@mockk.pitch = any() } answers { pitch = firstArg() }
                    every { teleport(any()) } answers { position = firstArg<Location>().clone(); body = position.yaw; pitch = position.pitch }
                }
                fun quantized(yaw: Float) = kotlin.math.floor(yaw * 256.0 / 360.0).toInt().toByte() * 360f / 256f
                var previousBody = body
                var previousHead = head
                var previousPitch = pitch
                var total = 0f
                fun frame() {
                    val change = kotlin.math.abs(citizenAngleChange(previousBody, body))
                    (change <= CITIZEN_TURN_PER_TICK + .001) shouldBe true
                    (kotlin.math.abs(citizenAngleChange(quantized(previousBody), quantized(body))) <= 9.5f) shouldBe true
                    (kotlin.math.abs(citizenAngleChange(quantized(previousHead), quantized(head))) <= 9.5f) shouldBe true
                    (kotlin.math.abs(pitch - previousPitch) <= CITIZEN_TURN_PER_TICK + .001) shouldBe true
                    (kotlin.math.abs(quantized(pitch) - quantized(previousPitch)) <= 9.5f) shouldBe true
                    total += change
                    previousBody = body
                    previousHead = head
                    previousPitch = pitch
                }
                repeat(6) { citizenFace(npc, -sign * 179f, 48f); frame() }
                // An exact settling endpoint must not reset either packet-shaped
                // angle before the following horizontal route takes ownership.
                val grounded = npc.location
                npc.teleport(grounded.clone().add(0.0, .125, 0.0))
                val settling = CitizenPacketRoute(terrain.world, npc.location, grounded,
                    { corridor(it.x, it.z) && it.y in 63..64 }, { _, _ -> true }, { 1.0 })
                settling.prepare()
                val settled = requireNotNull(settling.move(npc.location, .8 * 1.25) { true })
                npc.teleport(settled)
                npc.headYaw = settled.yaw
                settling.finished shouldBe true
                npc.bodyYaw shouldBe previousBody
                npc.headYaw shouldBe previousHead
                frame()
                var route = CitizenPacketRoute(terrain.world, npc.location, Location(terrain.world, 5.5, 63.9375, .5),
                    { corridor(it.x, it.z) && it.y in 63..64 }, { _, _ -> true }, { 1.0 })
                route.prepare()
                var replanned = false
                repeat(180) {
                    val before = npc.location
                    route.move(before, .8 * 1.25) { true }?.let { next ->
                        npc.teleport(next)
                        npc.headYaw = next.yaw
                        (before.distance(npc.location) <= .215001) shouldBe true
                    }
                    frame()
                    if (!replanned && npc.location.z < 3.4) {
                        val pushed = npc.location.add(.05, 0.0, 0.0)
                        npc.teleport(pushed)
                        route = route.replan(npc.location, emptyList()).also { route.cancel() }
                        route.prepare()
                        route.failure shouldBe null
                        replanned = true
                    }
                }
                replanned shouldBe true
                route.finished shouldBe true
                route.failure shouldBe null
                pitch shouldBe 0f
                repeat(24) { citizenFace(npc, 180f, 20f); frame() }
                (total < 210f) shouldBe true
                (kotlin.math.abs(citizenAngleChange(body, 180f)) < .001) shouldBe true
            }
        }
    }

    test("bounded collision replans detour around a person and preserve a narrow waiting route when no detour exists") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            val person = BoundingBox(1.9, 64.0, .2, 2.5, 65.8, .8)
            for (narrow in listOf(false, true)) {
                var at = Location(terrain.world, .5, 64.0, .5, -90f, 0f)
                val route = CitizenPacketRoute(terrain.world, at, Location(terrain.world, 5.5, 64.0, .5),
                    { it.x in 0..5 && it.y == 64 && if (narrow) it.z == 0 else it.z in -2..2 },
                    { _, _ -> true }, { 1.0 })
                route.prepare()
                val detour = route.replan(at, listOf(person))
                detour.prepare()
                if (narrow) {
                    detour.failure shouldBe "no-path"
                    route.navigating shouldBe true
                    repeat(80) { route.move(at, 1.0) { true }?.let { at = it } }
                    route.finished shouldBe true
                } else {
                    detour.failure shouldBe null
                    var avoided = false
                    repeat(100) { detour.move(at, 1.0) { true }?.let { next ->
                        citizenSlideBody(at.toVector(), next.toVector(), .299, 1.798, .299, listOf(person)) shouldBe null
                        if (kotlin.math.abs(next.z - .5) > .8) avoided = true
                        at = next
                    } }
                    detour.finished shouldBe true
                    avoided shouldBe true
                }
                at.x shouldBe 5.5
                at.z shouldBe .5
            }
        }
    }

    test("entrance arrival waits for support instead of cancelling within tolerance before gravity moves outside it") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            val stair = CivPosition(-1844, 70, -2696)
            terrain.blocks[stair] = Material.OAK_STAIRS
            terrain.boxes[stair] = listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0),
                BoundingBox(.5, .5, 0.0, 1.0, 1.0, 1.0))
            val entrance = stair.copy(y = 71)
            val airborne = CitizenPoint(-1843.984, 70.65, -2695.5)
            citizenNearEndpoint(airborne, entrance) shouldBe true
            citizenAtSupportedEndpoint(terrain.world, airborne, entrance) shouldBe false
            val fallen = airborne.copy(y = 70.53584064)
            (kotlin.math.abs(kotlin.math.sqrt(fallen.distanceSquared(CitizenPoint(entrance.x + .5, entrance.y.toDouble(), entrance.z + .5))) - .6705966831677087) < .000001) shouldBe true
            citizenAtSupportedEndpoint(terrain.world, fallen, entrance) shouldBe false
            citizenAtSupportedEndpoint(terrain.world, CitizenPoint(entrance.x + .5, entrance.y.toDouble(), entrance.z + .5), entrance) shouldBe true
            // Native half-tread contacts remain valid when already within the same unchanged tolerance.
            citizenAtSupportedEndpoint(terrain.world, CitizenPoint(-1843.8, 70.5, -2695.5), entrance) shouldBe true
        }
    }

    for (speed in listOf(.55, .8, 1.0, 1.3)) test("a wide built diagonal corridor removes grid zigzags and keeps overall heading at speed $speed") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            fun built(x: Int, z: Int) = x in -1..7 && z in -1..7 && kotlin.math.abs(x - z) <= 1
            var at = Location(terrain.world, .5, 64.0, .5, -45f, 0f)
            val target = Location(terrain.world, 6.5, 64.0, 6.5)
            val route = CitizenPacketRoute(terrain.world, at, target,
                { p -> p.y == 64 && p.x in -1..7 && p.z in -1..7 }, { _, _ -> true },
                { block -> if (built(block.x, block.z)) 1.0 else 6.0 })
            val grid = (0 until 6).flatMap { n -> listOf(CitizenPoint(n + 1.5, 64.0, n + .5),
                CitizenPoint(n + 1.5, 64.0, n + 1.5)) }
            route.acceptSearch(CitizenSearchResult(emptyList(), grid.size,
                grid.map { CitizenPlannedStep(it, CitizenRouteAction.WALK) }))
            var walked = 0.0
            repeat(200) {
                route.move(at, speed) { true }?.let { next ->
                    (next.distance(at) <= .215 * speed + .000001) shouldBe true
                    citizenPacketStepClear(terrain.world, CitizenPoint(at.x, at.y, at.z),
                        CitizenPoint(next.x, next.y, next.z), false) shouldBe true
                    (kotlin.math.abs(next.x - next.z) < .000001) shouldBe true
                    (kotlin.math.abs(citizenAngleChange(next.yaw, -45f)) < .01f) shouldBe true
                    built(next.blockX, next.blockZ) shouldBe true
                    walked += at.distance(next)
                    at = next
                }
            }
            route.failure shouldBe null
            route.finished shouldBe true
            at.distanceSquared(target) shouldBe 0.0
            (kotlin.math.abs(walked - kotlin.math.sqrt(72.0)) < .000001) shouldBe true
        }
    }

    test("a flat shortcut cannot graze a barrel control corner that later packet steps would reject") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            val control = CivPosition(20, 64, 0)
            terrain.blocks[control] = Material.BARREL
            val farm = CivBuilding(UUID.randomUUID(), BuildingType.FARM, 1, control, 0, "farm1",
                CivBox(CivPosition(18, 64, -2), CivPosition(26, 68, 2)), control, BuildingPhase.ACTIVE,
                control = control, production = "WHEAT")
            val city = pathsCity().copy(worldId = terrain.world.uid, buildings = listOf(farm))
            var at = Location(terrain.world, 20.5, 64.0, -.5, -90f, 0f)
            val target = Location(terrain.world, 24.5, 64.0, .5)
            // The 4:1 chord touches the shared policy's expanded barrel corner at (21.3,-.3).
            citizenControlStepClear(city, CitizenPoint(at.x, at.y, at.z), CitizenPoint(target.x, target.y, target.z)) shouldBe false
            val route = CitizenPacketRoute(terrain.world, at, target,
                { p -> p.y == 64 && citizenControlClear(city, p) },
                { from, to -> citizenControlStepClear(city, from, to) }, { 1.0 })
            val grid = (21..24).map { CitizenPoint(it + .5, 64.0, -.5) } + CitizenPoint(24.5, 64.0, .5)
            route.acceptSearch(CitizenSearchResult(emptyList(), grid.size,
                grid.map { CitizenPlannedStep(it, CitizenRouteAction.WALK) }))
            repeat(120) {
                route.move(at, .8) { true }?.let { next ->
                    citizenControlStepClear(city, CitizenPoint(at.x, at.y, at.z), CitizenPoint(next.x, next.y, next.z)) shouldBe true
                    (at.distance(next) <= .172001) shouldBe true
                    at = next
                }
            }
            route.failure shouldBe null
            route.finished shouldBe true
            at.distanceSquared(target) shouldBe 0.0
        }
    }

    test("flat smoothing follows the built L corridor instead of cutting across its lawn") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            fun built(x: Int, z: Int) = z == 0 && x in 0..4 || x == 4 && z in 0..4
            var at = Location(terrain.world, .5, 64.0, .5, -90f, 0f)
            val target = Location(terrain.world, 4.5, 64.0, 4.5)
            val route = CitizenPacketRoute(terrain.world, at, target,
                { p -> p.y == 64 && p.x in 0..4 && p.z in 0..4 }, { _, _ -> true },
                { block -> if (built(block.x, block.z)) 1.0 else 6.0 })
            val grid = (1..4).map { CitizenPoint(it + .5, 64.0, .5) } +
                (1..4).map { CitizenPoint(4.5, 64.0, it + .5) }
            route.acceptSearch(CitizenSearchResult(emptyList(), grid.size,
                grid.map { CitizenPlannedStep(it, CitizenRouteAction.WALK) }))
            repeat(180) {
                route.move(at, .8) { true }?.let { next ->
                    built(next.blockX, next.blockZ) shouldBe true
                    (next.distance(at) <= .172001) shouldBe true
                    at = next
                }
            }
            route.failure shouldBe null
            route.finished shouldBe true
            at.distanceSquared(target) shouldBe 0.0
        }
    }

    test("heading takes the short turn across wraparound and terrain cannot load a missing chunk") {
        citizenTurn(179f, -179f, 24f) shouldBe 181f
        citizenTurn(0f, 120f, 24f) shouldBe 24f
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            terrain.loaded = false
            citizenPacketBodyClear(terrain.world, CitizenPoint(.5, 64.0, .5), false) shouldBe false
            terrain.blockReads shouldBe 0
        }
    }
})

/** Synchronous driver for the same capture/solver protocol as production; this is not a tick count. */
private fun CitizenPacketRoute.prepare() {
    val maximumOperations = CitizenNavigationCapture.MAXIMUM_BLOCKS + CitizenNavigationSearch.MAXIMUM_NODES * 64
    var operations = 0
    while (searching && operations++ < maximumOperations) expand()
    withClue("phase=$preparationPhase nodes=$nodeCount captured=$capturedBlocks operations=$operations failure=$failure") {
        searching shouldBe false
    }
}

/** Native stepping sweeps the raised body; packet interpolation is not the player's collision path. */
private fun assertNativeStairFrame(world: World, from: Location, to: Location) {
    (kotlin.math.abs(to.y - from.y) <= .562501) shouldBe true
    citizenPacketBodyClear(world, from.point(), false) shouldBe true
    citizenPacketBodyClear(world, to.point(), false) shouldBe true
    citizenPacketStepClear(world, from.point(), to.point(), false) shouldBe true
}

/** A player's toes may reach a tread before its centre enters the stair's block. */
private fun touchesNativeFloor(world: World, at: Location): Boolean {
    for (x in kotlin.math.floor(at.x - .299).toInt()..kotlin.math.floor(at.x + .299).toInt())
        for (z in kotlin.math.floor(at.z - .299).toInt()..kotlin.math.floor(at.z + .299).toInt()) {
            val y = citizenSupportY(at.y)
            if (world.getBlockAt(x, y, z).collisionShape.boundingBoxes.any { box ->
                    kotlin.math.abs(y + box.maxY - at.y) <= .000001 &&
                        x + box.minX < at.x + .299 && x + box.maxX > at.x - .299 &&
                        z + box.minZ < at.z + .299 && z + box.maxZ > at.z - .299
                }) return true
        }
    return false
}

/** Explicit geometric unit fixture. It does not model a live server or claim native client movement. */
private class ResidentRouteTerrain {
    private val ownerThread = Thread.currentThread()
    val blocks = mutableMapOf<CivPosition, Material>()
    val boxes = mutableMapOf<CivPosition, List<BoundingBox>>()
    var loaded = true
    var blockReads = 0
    val world = mockk<World>()
    private val chunk = mockk<Chunk>()
    private val native = mutableMapOf<CivPosition, Block>()
    private val blockDataByMaterial = mutableMapOf<Material, org.bukkit.block.data.BlockData>()
    init {
        every { world.uid } returns UUID.randomUUID()
        every { world.minHeight } returns 0
        every { world.maxHeight } returns 256
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } answers { loaded }
        every { world.getChunkAt(any<Int>(), any<Int>(), false) } returns chunk
        every { chunk.loadLevel } returns Chunk.LoadLevel.ENTITY_TICKING
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            check(Thread.currentThread() === ownerThread) { "Native geometry read outside the owner thread" }
            blockReads++
            val point = CivPosition(firstArg(), secondArg(), thirdArg())
            native.getOrPut(point) {
                val shape = mockk<VoxelShape> {
                    every { boundingBoxes } answers {
                        boxes[point] ?: if (material(point).isSolid) listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, when (material(point)) {
                            Material.DIRT_PATH -> .9375
                            Material.CAMPFIRE, Material.SOUL_CAMPFIRE -> .4375
                            else -> 1.0
                        }, 1.0)) else emptyList()
                    }
                }
                val block = mockk<Block> {
                    every { x } returns point.x
                    every { y } returns point.y
                    every { z } returns point.z
                    every { type } answers { material(point) }
                    every { isLiquid } returns false
                    every { blockData } answers { blockDataByMaterial.getOrPut(material(point)) { material(point).createBlockData() } }
                    every { collisionShape } returns shape
                }
                // Geometry has explicit assertions/counters. Retaining every probe across
                // 48 routes only grows MockK's call history and is not part of this oracle.
                excludeRecords {
                    block.type; block.isLiquid; block.blockData; block.collisionShape
                    block.x; block.y; block.z
                    shape.boundingBoxes
                }
                block
            }
        }
        excludeRecords {
            world.uid; world.minHeight; world.maxHeight
            world.isChunkLoaded(any<Int>(), any<Int>())
            world.getChunkAt(any<Int>(), any<Int>(), false)
            world.getBlockAt(any<Int>(), any<Int>(), any<Int>())
            chunk.loadLevel
        }
    }
    private fun material(point: CivPosition) = blocks[point] ?: if (point.y <= 63) Material.STONE else Material.AIR
    fun allowed(point: CivPosition) = point.x in -3..8 && point.z in -5..5 && point.y in 64..65
    fun route() = CitizenPacketRoute(world, Location(world, .5, 64.0, .5), Location(world, 5.5, 64.0, .5),
        ::allowed, { _, _ -> true }, { 1.0 })
}
