package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.Difficulty
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.data.Ageable
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Monster
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import org.bukkit.util.VoxelShape
import org.mockbukkit.mockbukkit.block.BlockMock
import org.mockbukkit.mockbukkit.world.ChunkMock
import org.mockbukkit.mockbukkit.world.Coordinate
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.npc.PacketPlayerNpc
import ru.arc.paper.npc.PacketNpcProfile
import ru.arc.paper.npc.PacketNpcPose
import ru.arc.paper.npc.PaperPacketNpcs
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.function.Predicate
import java.util.logging.Handler
import java.util.logging.LogRecord

class CivsResidentsRuntimeTest : FunSpec({
    test("a built V4 prefix traverses two native landings and returns through the complete authored ladder without teleport jumps") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("resident-v4-two-level-traversal").also(paper.server::addWorld)
            val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 80, 0))
            val geometry = requireNotNull(CivsMinePlan.forBuilding(mine, 4))
            val secondFloor = geometry.floors[1]
            val prefix = geometry.steps.indexOfLast { it.floor == secondFloor.index && it.branch == "opening" } + 1
            (prefix in 1 until geometry.shaftEnd) shouldBe true
            for (x in -2..2) for (z in -2..2) world.loadChunk(x, z)
            for (step in geometry.steps.take(prefix)) {
                val point = CivsWorksiteLayouts.point(mine, step.point)
                world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(step.after ?: "minecraft:air")
            }
            val bottom = CivsWorksiteLayouts.point(mine, geometry.bottom)
            // Curated miner1 SHA c54e00bc1c0d4034: the complete authored column has
            // ladder rungs at local -5..-3, air at -2/-1, and a dirt cap at 0.
            for (localY in -5..0) {
                val point = bottom.copy(y = mine.anchor.y + localY)
                world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(when {
                    localY <= -3 -> "minecraft:ladder[facing=west,waterlogged=false]"
                    localY < 0 -> "minecraft:air"
                    else -> "minecraft:dirt"
                })
                if (localY < 0) {
                    val backing = CivsWorksiteLayouts.point(mine, geometry.backing).copy(y = point.y)
                    world.getBlockAt(backing.x, backing.y, backing.z).type = Material.COBBLESTONE
                }
            }
            val state = CivMinePlanState(4, prefix.toLong())
            val city = spatialCity(world.uid, listOf(mine), spatialWorker(mine, "IRON_PICKAXE")).copy(
                minePlans = mapOf(mine.id.toString() to state))
            fun at(point: CivPosition) = Location(world, point.x + .5, point.y.toDouble(), point.z + .5)
            fun passage(point: CitizenPoint) = citizenMineWorkRouteAllowed(mine, point, prefix.toLong(), state)
            fun feet(floor: CivsMinePlan.Floor) = CivsWorksiteLayouts.point(mine, floor.cells.first().copy(y = floor.feetY))
            val first = feet(geometry.floors[0])
            val second = feet(secondFloor)
            val top = bottom.copy(y = mine.anchor.y - 3)
            val future = feet(geometry.floors[2])
            passage(CitizenPoint(future.x + .5, future.y.toDouble(), future.z + .5)) shouldBe false
            var position = at(top)
            val start = position.clone()
            val observedDistances = mutableListOf<Double>()
            var walkedDistance = 0.0
            var minimumY = position.y
            var completedLegs = 0
            // Production capture/solver/movement runs against the faithful native-geometry
            // fixture. This driver never invokes the NPC lifecycle or recovery teleport.
            for (destination in listOf(first, second, first, top)) {
                var legs = 0
                while (position.distanceSquared(at(destination)) > .000001 && legs++ < 6) {
                    val waypoint = citizenMineWaypoint(city, world, position, destination) ?: destination
                    val target = at(waypoint)
                    val route = CitizenPacketRoute(world, position, target,
                        { p -> city.contains(p.x, p.z) && passage(CitizenPoint(p.x + .5, p.y.toDouble(), p.z + .5)) },
                        { _, to -> passage(to) }, { 1.0 })
                    val operationLimit = CitizenNavigationCapture.MAXIMUM_BLOCKS + CitizenNavigationSearch.MAXIMUM_NODES * 64
                    var operations = 0
                    while (route.searching && operations++ < operationLimit) route.expand()
                    withClue("from=$position waypoint=$waypoint destination=$destination prefix=$prefix " +
                        "nodes=${route.nodeCount} captured=${route.capturedBlocks} phase=${route.preparationPhase}") {
                        route.searching shouldBe false
                        route.failure shouldBe null
                    }
                    val frameLimit = 128 + 4 * kotlin.math.ceil(position.distance(target) / .172).toInt()
                    var frames = 0
                    while (!route.finished && route.failure == null && frames++ < frameLimit) {
                        val before = position
                        route.move(before, .8) { true }?.let { next ->
                            observedDistances += before.distance(next)
                            for (sample in 0..10) {
                                val point = before.clone().add(next.toVector().subtract(before.toVector()).multiply(sample / 10.0))
                                citizenPacketBodyClear(world, CitizenPoint(point.x, point.y, point.z), false) shouldBe true
                            }
                            passage(CitizenPoint(next.x, next.y, next.z)) shouldBe true
                            position = next
                            minimumY = minOf(minimumY, position.y)
                        }
                    }
                    withClue("from=$position target=$target destination=$destination prefix=$prefix frames=$frames " +
                        "nodes=${route.nodeCount} remaining=${route.remainingNodes}") {
                        route.failure shouldBe null
                        route.finished shouldBe true
                        position.distanceSquared(target) shouldBe 0.0
                    }
                    walkedDistance += route.forwardProgress
                    completedLegs++
                }
                position.distanceSquared(at(destination)) shouldBe 0.0
            }
            val teleportJumps = observedDistances.count { it > .215001 }
            withClue("moves=${observedDistances.size} legs=$completedLegs walked=$walkedDistance " +
                "minimumY=$minimumY teleportJumps=$teleportJumps") {
                observedDistances.isNotEmpty() shouldBe true
                teleportJumps shouldBe 0
                (minimumY <= second.y) shouldBe true
                (walkedDistance >= 2 * (start.y - second.y)) shouldBe true
                position.distanceSquared(start) shouldBe 0.0
            }
        }
    }

    test("a permitted loaded city spawns and completes physical field work without viewers then retires on revoke") {
        residentRuntime { h ->
            Bukkit.getOnlinePlayers().forEach { it.teleport(Location(h.world, 1_000.5, 64.0, .5)) }
            h.owner.pulse(1_001)
            h.owner.activeCount() shouldBe 0
            h.background = true
            h.owner.pulse(1_500)
            h.owner.activeCount() shouldBe 1
            h.spawns shouldBe 2
            val work = h.beginCare("watering")
            for (at in 3_000L..6_000L step 1_000L) h.owner.pulseFieldWork(at)
            h.deliveries shouldBe 1
            h.pendingDelivery.isDone shouldBe false
            h.city.workers.single().completedCycles shouldBe 0
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)
            h.commitWork()
            h.owner.pulse(7_000)
            h.city.workers.single().completedCycles shouldBe 1
            h.background = false
            h.owner.pulse(8_000)
            h.owner.activeCount() shouldBe 0
            h.owner.pulseFieldWork(9_000)
            h.deliveries shouldBe 1
        }
    }

    for ((farmerCount, ripensDuringCare) in listOf(1 to false, 2 to false, 1 to true))
        test("$farmerCount farmers harvest three nearby crops without rescanning between receipts (ripens during care: $ripensDuringCare)") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val world = CivsWorksiteWorldFixture("resident-multi-harvest-$farmerCount").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("ResidentMultiHarvest")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                val farm = spatialBuilding("medievaloak", "farmer", farmerCount, CivPosition(0, 63, 0)).copy(production = "WHEAT")
                val workers = List(farmerCount) { spatialWorker(farm, "IRON_HOE") }
                var city = spatialCity(world.uid, listOf(farm), workers.first()).copy(workers = workers)
                val plots = CivsWorksiteLayouts.field(farm)
                // All three are outside the old second farmer's stripe. Consecutive targets
                // are about three blocks apart, but more than sixteen scan entries apart.
                val mature = listOf(20, 40, 64).map { plots[it].let { soil -> soil.copy(y = soil.y + 1) } }
                for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                plots.forEach { soil ->
                    world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=0]")
                    world.getBlockAt(soil.x, soil.y + 1, soil.z).blockData =
                        (Material.WHEAT.createBlockData() as Ageable).also { it.age = 3 }
                }
                fun ripen() = mature.forEach { point -> world.getBlockAt(point.x, point.y, point.z).blockData =
                    (Material.WHEAT.createBlockData() as Ageable).also { it.age = it.maximumAge } }
                if (!ripensDuringCare) ripen()
                CivsWorksiteLayouts.irrigation(farm).forEach { world.getBlockAt(it.x, it.y, it.z).type = Material.WATER }
                farm.control!!.let { world.getBlockAt(it.x, it.y, it.z).type = Material.BARREL }
                val viewer = paper.addPlayer("HarvestViewer")
                viewer.teleport(Location(world, 9.5, 65.0, -19.5))
                val packets = mockk<PaperPacketNpcs>(relaxed = true)
                val actors = mutableMapOf<UUID, PacketPlayerNpc>()
                every { packets.spawnPlayer(any(), any()) } answers {
                    var actorAt = firstArg<Location>().clone()
                    var actorValid = true
                    var actorBodyYaw = actorAt.yaw
                    var actorHeadYaw = actorAt.yaw
                    var actorPitch = actorAt.pitch
                    val profile = secondArg<PacketNpcProfile>()
                    mockk<PacketPlayerNpc>(relaxed = true) {
                        every { uniqueId } returns profile.uuid
                        every { isValid } answers { actorValid }
                        every { this@mockk.world } returns world
                        every { location } answers { actorAt.clone().also { it.yaw = actorBodyYaw; it.pitch = actorPitch } }
                        every { bodyYaw } answers { actorBodyYaw }
                        every { bodyYaw = any() } answers { actorBodyYaw = firstArg() }
                        every { headYaw } answers { actorHeadYaw }
                        every { headYaw = any() } answers { actorHeadYaw = firstArg() }
                        every { pitch } answers { actorPitch }
                        every { pitch = any() } answers { actorPitch = firstArg() }
                        every { height } returns 1.8
                        every { eyeHeight } returns 1.62
                        every { scale } returns 1.0
                        every { pose } returns PacketNpcPose.STANDING
                        every { teleport(any()) } answers {
                            actorAt = firstArg<Location>().clone()
                            actorBodyYaw = actorAt.yaw; actorPitch = actorAt.pitch
                            Unit
                        }
                        every { remove() } answers { actorValid = false; Unit }
                    }.also { actors[profile.uuid] = it }
                }
                every { packets.closeAsync() } returns CompletableFuture.completedFuture(null)
                var paused = false
                var now = 1_000L
                val harvested = mutableListOf<CivPosition>()
                val queued = ArrayDeque<Triple<UUID, Long, CompletableFuture<Settlement>>>()
                var pending: Triple<CivInventoryTransaction, CivWorldWork, CompletableFuture<Settlement>>? = null
                var sealedAt = 0L
                val workingSince = mutableMapOf<Pair<UUID, Long>, Long>()
                val awaitingNext = mutableMapOf<UUID, Pair<Long, Int>>()
                var ripe = !ripensDuringCare
                var preempted = false
                var busyWorkingChecked = false
                var busyWorkingUntil = 0L
                var busyWorkingPlan: CivWorldWork? = null
                var busyWorkingPosition: Vector? = null
                CivsStorage(plugin) { emptyArray() }.use { storage ->
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        val runtime = CivsWorksites(plugin, access, { current, id -> storage.buildingAvailable(current, id) },
                            storage::isWorldLocked, { paused })
                        lateinit var residents: CivsCitizens
                        residents = CivsCitizens(plugin, { listOf(city) }, { cityId, workerId, _, _, _, _, at ->
                            cityId shouldBe city.id
                            CompletableFuture<Settlement>().also { queued += Triple(workerId, at, it) }
                        }, { _, _, _ -> }, workPlan = runtime::plan, workIdleReason = runtime::idleReason, packetOwner = packets)
                        fun placeAtWork(worker: CivWorker) {
                            val work = requireNotNull(runtime.plan(city, worker))
                            actors.getValue(worker.id).teleport(work.standing.let { Location(world, it.x + .5, it.y.toDouble(), it.z + .5) })
                        }
                        try {
                            repeat(12) { runtime.pulse(listOf(city)) }
                            workers.forEach { requireNotNull(runtime.plan(city, it)).cropCare shouldBe ripensDuringCare }
                            residents.pulse(now)
                            workers.forEach(::placeAtWork)
                            var steps = 0
                            while (harvested.size < 3 && steps++ < 120) {
                                now += 250
                                if (busyWorkingUntil != 0L && now >= busyWorkingUntil) {
                                    runtime.plan(city, city.workers.single()) shouldBe busyWorkingPlan
                                    actors.getValue(workers.single().id).location.toVector() shouldBe busyWorkingPosition
                                    queued.isEmpty() shouldBe true
                                    city.workers.single().completedCycles shouldBe 0L
                                    paused = false
                                    busyWorkingUntil = 0
                                    busyWorkingChecked = true
                                }
                                if (!ripe && now == 4_250L) {
                                    residents.status(workers.first().id) shouldBe "workers.status.watering"
                                    ripen()
                                    ripe = true
                                }
                                pending?.takeIf { now - sealedAt >= 500 }?.let { (receipt, work, completion) ->
                                    work.changes.forEach { cell ->
                                        world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.before
                                    }
                                    storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
                                    storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
                                    city = receipt.after
                                    storage.unlock(receipt.id)
                                    runtime.invalidate(work.workerId)
                                    paused = false
                                    pending = null
                                    harvested += requireNotNull(work.focus)
                                    completion.complete(city)
                                    val claimed = city.workers.mapNotNull { runtime.plan(city, it)?.takeUnless(CivWorldWork::cropCare)?.focus }.toSet()
                                    if (mature.any { it !in harvested && it !in claimed }) awaitingNext[work.workerId] = now to 0
                                }
                                // The production scheduler admits one worksite every five ticks.
                                runtime.pulse(listOf(city))
                                if (ripensDuringCare && ripe && !preempted) {
                                    val work = requireNotNull(runtime.plan(city, city.workers.single()))
                                    if (!work.cropCare) {
                                        placeAtWork(city.workers.single())
                                        preempted = true
                                    } else (now - 4_250 < ((plots.size + 15) / 16) * 250L) shouldBe true
                                }
                                for ((workerId, wait) in awaitingNext.toMap()) {
                                    // Another farmer may reserve the final ripe crop between two
                                    // receipts. Only currently unclaimed crops require an immediate harvest.
                                    val unclaimed = mature.any { point -> point !in harvested && city.workers.none { other ->
                                        other.id != workerId && runtime.plan(city, other)?.focus == point
                                    } }
                                    if (!unclaimed) { awaitingNext.remove(workerId); continue }
                                    val (committedAt, previousPulses) = wait
                                    val admittedPulses = previousPulses + if (paused) 0 else 1
                                    val worker = city.workers.first { it.id == workerId }
                                    val work = runtime.plan(city, worker)
                                    if (work != null) {
                                        work.cropCare shouldBe false
                                        (admittedPulses <= farmerCount) shouldBe true
                                        if (farmerCount == 1) (now - committedAt <= 250L) shouldBe true
                                        placeAtWork(worker) // This fixture tests observed work/receipts, not native navigation.
                                        awaitingNext.remove(workerId)
                                    } else withClue("next known harvest must not require another complete field scan") {
                                        (admittedPulses < farmerCount) shouldBe true
                                        awaitingNext[workerId] = committedAt to admittedPulses
                                    }
                                }
                                residents.pulseFieldWork(now)
                                if (now % 1_000 == 0L) residents.pulse(now)
                                if (farmerCount == 1 && !ripensDuringCare && !busyWorkingChecked && busyWorkingUntil == 0L &&
                                    residents.status(workers.single().id) == "workers.status.working-field") {
                                    busyWorkingPlan = requireNotNull(runtime.plan(city, city.workers.single()))
                                    busyWorkingPosition = actors.getValue(workers.single().id).location.toVector()
                                    busyWorkingUntil = now + 500
                                    paused = true
                                }
                                if (busyWorkingUntil != 0L) {
                                    runtime.plan(city, city.workers.single()) shouldBe busyWorkingPlan
                                    actors.getValue(workers.single().id).location.toVector() shouldBe busyWorkingPosition
                                    queued.isEmpty() shouldBe true
                                    residents.status(workers.single().id) shouldBe "workers.status.working-field"
                                }
                                for (worker in city.workers) {
                                    val work = runtime.plan(city, worker) ?: continue
                                    if (residents.status(worker.id) == "workers.status.working-field")
                                        workingSince.putIfAbsent(worker.id to worker.completedCycles, now)
                                    val unclaimed = mature.filter { ripe && it !in harvested }.filter { point ->
                                        city.workers.none { other -> other.id != worker.id && runtime.plan(city, other)?.focus == point }
                                    }
                                    if (unclaimed.isNotEmpty() && (!ripensDuringCare || preempted)) {
                                        work.cropCare shouldBe false
                                        (residents.status(worker.id) == "workers.status.searching-work") shouldBe false
                                    }
                                }
                                if (pending == null && queued.isNotEmpty()) {
                                    val (workerId, at, completion) = queued.removeFirst()
                                    val current = city.workers.first { it.id == workerId }
                                    val work = requireNotNull(runtime.plan(city, current))
                                    work.cropCare shouldBe false
                                    work.durationMillis shouldBe 1_500L
                                    work.outputs shouldBe mapOf("WHEAT" to 1L, "WHEAT_SEEDS" to 1L)
                                    work.inputs shouldBe emptyMap()
                                    (at - workingSince.getValue(workerId to current.completedCycles) >=
                                        CivsResidents.workDuration(current, work.durationMillis)) shouldBe true
                                    residents.requireWorldWorkArrival(city.id, workerId, work)
                                    runtime.validate(city, work)
                                    val id = UUID.randomUUID()
                                    storage.reserve(city, id)
                                    val plan = storage.prepareWork(city, farm.id, work.storageInputs(current), emptyMap(), work.changes)
                                    val after = CivsRules.finishWorldWork(city, work, at).copy(inventory = plan.inventoryAfter)
                                    val receipt = CivInventoryTransaction(id, city.id, city.revision, after, plan.changes,
                                        worldChanges = plan.worldChanges).validated()
                                    storage.seal(receipt, keepViewersOpen = true)
                                    paused = true
                                    sealedAt = now
                                    pending = Triple(receipt, work, completion)
                                    shouldThrow<CivsRejection> { runtime.validate(city, work) }.reason shouldBe "city-busy"
                                }
                            }
                            harvested.toSet() shouldBe mature.toSet()
                            harvested.size shouldBe 3
                            city.workers.sumOf(CivWorker::completedCycles) shouldBe 3L
                            city.inventory!!.buildingStock(farm.id) shouldBe emptyMap()
                            city.workers.sumOf { CivsEquipment.plainCount(it, "WHEAT") } shouldBe 3L
                            city.workers.sumOf { CivsEquipment.plainCount(it, "WHEAT_SEEDS") } shouldBe 3L
                            mature.forEach { point -> (world.getBlockAt(point.x, point.y, point.z).blockData as Ageable).age shouldBe 0 }
                            plots.filter { it.copy(y = it.y + 1) !in mature }.forEach { soil ->
                                (world.getBlockAt(soil.x, soil.y + 1, soil.z).blockData as Ageable).age shouldBe 3
                            }
                            if (farmerCount == 2) city.workers.all { it.completedCycles > 0 } shouldBe true
                            if (ripensDuringCare) preempted shouldBe true
                            if (farmerCount == 1 && !ripensDuringCare) busyWorkingChecked shouldBe true
                        } finally {
                            try { residents.closeAsync().join() } finally {
                                // Packet mock answers/call histories retain their world and actors.
                                // Release this fixture's mocks only, after all behavior assertions.
                                clearMocks(packets, *actors.values.toTypedArray())
                                tasks.close()
                            }
                        }
                    }
                }
            }
        }
    }

    test("a field route queued beyond ninety seconds keeps its identity and gets its full movement deadline after planning") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val actor = h.owner.collisionActors().single()
            val focus = CivPosition(23, 63, 0)
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(24, 64, 0),
                listOf(CivPlanBlock(focus, "minecraft:stone", "minecraft:air")),
                durationMillis = 1_500, status = "working-field", focus = focus)
            val messages = mutableListOf<String>()
            val handler = object : Handler() {
                override fun publish(record: LogRecord) { messages += record.message }
                override fun flush() = Unit
                override fun close() = Unit
            }
            h.logger.addHandler(handler)
            try {
                h.navigationNow = 2_000L
                h.holdNavigation = true
                h.owner.pulse(2_000)
                val beforeSearch = actor.location
                val route = requireNotNull(h.routeIdentity())
                val teleports = h.teleports
                for (tick in 1..512) {
                    if (h.pendingNavigationWorkers > 0) break
                    h.navigate(advanceMillis = 0)
                }
                h.pendingNavigationWorkers shouldBe 1
                repeat(60) {
                    // Hold the actual submitted planner task beyond both the old field
                    // deadline and ninety seconds, while native ticks and work pulses continue.
                    h.navigate(advanceMillis = 2_000)
                    h.owner.pulseFieldWork(requireNotNull(h.navigationNow))
                    (h.routeIdentity() === route) shouldBe true
                    actor.location.toVector() shouldBe beforeSearch.toVector()
                    actor.bodyYaw shouldBe beforeSearch.yaw
                    h.teleports shouldBe teleports
                    h.deliveries shouldBe 0
                }
                h.navigationNow shouldBe 122_000L
                h.pendingNavigationWorkers shouldBe 1
                h.releaseNavigation()
                var arrived = false
                for (tick in 1..512) {
                    val before = actor.location
                    h.navigate()
                    val after = actor.location
                    (before.distance(after) <= .172001) shouldBe true
                    (kotlin.math.abs(citizenAngleChange(before.yaw, after.yaw)) <= 8.0001f) shouldBe true
                    if (tick % 5 == 0) h.owner.pulseFieldWork(requireNotNull(h.navigationNow))
                    if (h.deliveries == 1) {
                        citizenCanReachBlock(actor, focus) shouldBe true
                        h.owner.requireWorldWorkArrival(h.city.id, h.workerId, requireNotNull(h.work))
                        arrived = true
                        break
                    }
                }
                withClue("status=${h.owner.status(h.workerId)} location=${actor.location} focus=$focus " +
                    "reachable=${citizenCanReachBlock(actor, focus)} deliveries=${h.deliveries} " +
                    "planner=${h.navigationState()} diagnostics=${messages.takeLast(4)}") {
                    arrived shouldBe true
                }
                messages.none { "citizen_route_recovered" in it || "ARCCIVS_NAVIGATION_FAILED" in it } shouldBe true
                h.spawns shouldBe 1
                h.steps.isNotEmpty() shouldBe true
                h.deliveries shouldBe 1
                h.pendingDelivery.isDone shouldBe false
                h.city.workers.single().completedCycles shouldBe 0L
                h.commitWork()
                h.owner.pulse(requireNotNull(h.navigationNow) + 1_000)
                h.city.workers.single().completedCycles shouldBe 1L
                h.deliveries shouldBe 1
            } finally { h.logger.removeHandler(handler) }
        }
    }

    for (journey in listOf("shelter", "empty courier return"))
    test("a transient planner rejection backs off then resumes a real $journey walk without teleporting") {
        residentRuntime { h ->
            val actor = h.owner.collisionActors().single()
            val sheltering = journey == "shelter"
            val destination = if (sheltering) {
                h.city.buildings.single().copy(id = UUID.randomUUID(), type = BuildingType.HOUSE,
                    schematicId = "house1", anchor = CivPosition(12, 64, 0), entrance = CivPosition(12, 64, 0),
                    bounds = CivBox(CivPosition(10, 64, -2), CivPosition(14, 68, 2)), control = null).also { home ->
                    h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings + home,
                        workers = h.city.workers.map { it.copy(homeId = home.id) }))
                    h.world.time = 13_000
                }
            } else {
                h.assignFarm().copy(type = BuildingType.WAREHOUSE, schematicId = "warehouse1", production = null).also { warehouse ->
                    h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings.map {
                        if (it.id == warehouse.id) warehouse else it
                    }))
                }
            }
            h.city.workers.single().cargo shouldBe null
            val travellingStatus = if (sheltering) "workers.status.sheltering" else "workers.status.moving-to-storage"
            val arrivedStatus = if (sheltering) "workers.status.sleeping" else "workers.status.waiting-orders"
            val maximumStep = if (sheltering) .118251 else .172001
            h.navigationNow = 2_000L
            h.navigationRejections = 1
            h.owner.pulse(2_000L)
            h.owner.status(h.workerId) shouldBe travellingStatus
            val failedRoute = requireNotNull(h.routeIdentity())
            val origin = requireNotNull(h.owner.residentFeet(h.workerId))
            val teleports = h.teleports
            for (tick in 1..64) {
                if (h.routeFailure() != null) break
                h.navigate(advanceMillis = 0)
            }
            h.navigationRejections shouldBe 0
            h.routeFailure() shouldBe "planner-busy"
            h.owner.pulse(2_000L) // The first observed preparation failure starts its five-second backoff.
            for (now in listOf(2_500L, 4_000L, 6_999L)) {
                h.navigationNow = now
                h.navigate(advanceMillis = 0)
                h.owner.pulse(now)
                (h.routeIdentity() === failedRoute) shouldBe true
                h.routeFailure() shouldBe "planner-busy"
                h.owner.residentFeet(h.workerId) shouldBe origin
                h.teleports shouldBe teleports
                h.deliveries shouldBe 0
            }
            h.navigationNow = 7_000L
            h.navigate(advanceMillis = 0)
            h.owner.pulse(7_000L)
            h.routeIdentity() shouldBe null
            h.owner.residentFeet(h.workerId) shouldBe origin
            h.teleports shouldBe teleports

            h.navigationNow = 7_001L
            h.owner.pulse(7_001L)
            val retry = requireNotNull(h.routeIdentity())
            (retry === failedRoute) shouldBe false
            h.teleports shouldBe teleports
            var arrived = false
            var walked = 0.0
            for (tick in 1..512) {
                val before = requireNotNull(h.owner.residentFeet(h.workerId))
                val yaw = actor.bodyYaw
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                val after = requireNotNull(h.owner.residentFeet(h.workerId))
                val distance = before.distance(after)
                (distance <= maximumStep) shouldBe true
                if (actor.pose != PacketNpcPose.SLEEPING)
                    (kotlin.math.abs(citizenAngleChange(yaw, actor.bodyYaw)) <= 8.0001f) shouldBe true
                walked += distance
                if (h.owner.status(h.workerId) == arrivedStatus) {
                    arrived = true
                    break
                }
            }
            withClue("journey=$journey status=${h.owner.status(h.workerId)} location=${actor.location} planner=${h.navigationState()}") {
                arrived shouldBe true
            }
            (walked > 1.0) shouldBe true
            val feet = requireNotNull(h.owner.residentFeet(h.workerId))
            if (sheltering) {
                actor.pose shouldBe PacketNpcPose.SLEEPING
                destination.bounds.contains(feet.blockX, feet.blockY, feet.blockZ) shouldBe true
                citizenPacketSupported(h.world, CitizenPoint(feet.x, feet.y, feet.z)) shouldBe true
            } else {
                val control = requireNotNull(destination.control)
                citizenAtStorage(h.world, CitizenPoint(feet.x, feet.y, feet.z), control) shouldBe true
                citizenCanReachBlock(actor, control) shouldBe true
            }
            h.spawns shouldBe 1
            h.pickups shouldBe 0
            h.unloads shouldBe 0
            h.deliveries shouldBe 0
            h.city.workers.single().cargo shouldBe null
            h.city.workers.single().completedCycles shouldBe 0L
        }
    }

    test("closing residents waits for accepted work on its owned navigation executor") {
        residentRuntime(useOwnedNavigationExecutor = true) { h ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val exited = CountDownLatch(1)
            try {
                h.ownedNavigationExecutor().execute {
                    entered.countDown()
                    try { release.await() } finally { exited.countDown() }
                }
                entered.await(2, TimeUnit.SECONDS) shouldBe true
                val retired = h.owner.closeAsync()
                h.owner.activeCount() shouldBe 0
                retired.isDone shouldBe false
                release.countDown()
                retired.get(2, TimeUnit.SECONDS)
                exited.await(2, TimeUnit.SECONDS) shouldBe true
                retired.isDone shouldBe true
            } finally { release.countDown() }
        }
    }

    test("monster defence recruits only nearby residents of its victim's city and limits visible melee to once a second") {
        residentRuntime { h ->
            val victim = h.owner.collisionActors().single()
            victim.teleport(Location(h.world, 4.5, 64.0, .5))
            val monster = h.monsterAt(6.5, .5)
            // Discovery occurs while only the victim exists. Later helpers must be recruited
            // by the actual pursuit callback, not become independent targets of the scan.
            h.owner.pulse(2_000)
            h.world.threatEntities.clear()
            val helperId = UUID.randomUUID()
            val distantId = UUID.randomUUID()
            val outsiderId = UUID.randomUUID()
            h.city = h.city.copy(workers = h.city.workers + listOf(
                CivWorker(helperId, "Helper", null), CivWorker(distantId, "Distant", null)))
            h.otherCities += h.city.copy(id = UUID.randomUUID(), workers = listOf(CivWorker(outsiderId, "Outsider", null)))
            h.owner.pulse(3_000)
            h.owner.pulse(4_000)
            val actors = h.owner.collisionActors().associateBy { it.uniqueId }
            actors.size shouldBe 4
            val helper = actors.getValue(helperId)
            val distant = actors.getValue(distantId)
            val outsider = actors.getValue(outsiderId)
            victim.teleport(Location(h.world, 4.5, 64.0, .5))
            helper.teleport(Location(h.world, 4.5, 64.0, 1.5))
            distant.teleport(Location(h.world, 18.5, 64.0, .5))
            outsider.teleport(Location(h.world, 4.5, 64.0, -1.5))
            val residents = h.city.workers
            h.navigate()
            h.owner.pulse(5_000)
            h.owner.status(h.workerId) shouldBe "workers.status.defending"
            h.owner.status(helperId) shouldBe "workers.status.defending"
            h.owner.status(distantId) shouldBe "workers.status.unassigned"
            h.owner.status(outsiderId) shouldBe "workers.status.unassigned"
            verify(exactly = 1) { victim.swingMainHand() }
            verify(exactly = 1) { helper.swingMainHand() }
            verify(exactly = 0) { distant.swingMainHand() }
            verify(exactly = 0) { outsider.swingMainHand() }
            verify(exactly = 2) { monster.damage(1.0) }
            for (at in 5_100L..5_900L step 100) h.owner.pulse(at)
            verify(exactly = 2) { monster.damage(any<Double>()) }
            h.owner.pulse(6_000)
            verify(exactly = 4) { monster.damage(1.0) }

            // A newly placed native wall blocks both residents' next strikes even though
            // the pursuit is already active and the monster stays within melee distance.
            for (y in 64..66) for (z in 0..1) h.world.getBlockAt(5, y, z).type = Material.STONE
            h.owner.pulse(7_000)
            verify(exactly = 4) { monster.damage(any<Double>()) }
            for (y in 64..66) for (z in 0..1) h.world.getBlockAt(5, y, z).type = Material.AIR
            h.owner.pulse(8_000)
            verify(exactly = 6) { monster.damage(1.0) }
            h.city.workers shouldBe residents
            h.deliveries shouldBe 0
            h.hits shouldBe 0
        }
    }

    test("wounded adults and children walk away from a pursuing monster without attacking or earning work credit") {
        for (child in listOf(false, true)) residentRuntime { h ->
            h.city = h.city.copy(workers = h.city.workers.map { if (child)
                it.copy(childMinutes = 1, parentIds = setOf(UUID.randomUUID(), UUID.randomUUID()))
                else it.copy(health = 7.0) })
            val victim = h.owner.collisionActors().single()
            val start = Location(h.world, 10.5, 64.0, .5)
            victim.teleport(start)
            val monster = h.monsterAt(12.5, .5)
            h.owner.pulse(2_000)
            h.navigate()
            h.owner.pulse(3_000)
            h.owner.status(h.workerId) shouldBe "workers.status.retreating"
            for (tick in 0 until 2_000) {
                val before = victim.location
                h.navigate()
                (before.distance(victim.location) <= .236501) shouldBe true
                if (victim.location.distanceSquared(start) >= 4.0 * 4.0) break
            }
            (victim.location.x < start.x - 3.0) shouldBe true
            (victim.location.distanceSquared(monster.location) > start.distanceSquared(monster.location)) shouldBe true
            verify(exactly = 0) { victim.swingMainHand() }
            verify(exactly = 0) { monster.damage(any<Double>()) }
            h.city.workers.single().completedCycles shouldBe 0
            h.city.workers.single().skills.orEmpty() shouldBe emptyMap()
            h.deliveries shouldBe 0
            h.hits shouldBe 0
            h.steps shouldBe emptyList()
        }
    }

    test("a processor collects supplies only at its barrel and cannot start work before the receipt") {
        residentRuntime { h ->
            val mill = h.assignFarm().copy(type = BuildingType.BLACKSMITH, production = null)
            h.city = h.city.copy(buildings = h.city.buildings.map { if (it.id == mill.id) mill else it })
            h.workReady = false
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 15.5, 64.0, .5))
            h.owner.pulse(2_000)
            h.supplyRequests shouldBe 0
            h.deliveries shouldBe 0
            actor.teleport(Location(h.world, 19.5, 64.0, .5))
            h.owner.pulse(3_000)
            h.supplyRequests shouldBe 1
            h.owner.requireStorageArrival(h.city.id, h.workerId, mill.id)
            h.owner.pulse(4_000)
            h.owner.status(h.workerId) shouldBe "workers.status.loading-cargo"
            h.supplyRequests shouldBe 1
            h.deliveries shouldBe 0
            actor.teleport(Location(h.world, 15.5, 64.0, .5))
            shouldThrow<CivsRejection> { h.owner.requireStorageArrival(h.city.id, h.workerId, mill.id) }
            actor.teleport(Location(h.world, 19.5, 64.0, .5))
            h.workReady = true
            h.pendingSupplies.complete(h.city)
            h.owner.pulse(5_000)
            h.owner.pulse(6_000)
            h.owner.status(h.workerId) shouldBe "workers.status.working"
            h.supplyRequests shouldBe 1
            h.deliveries shouldBe 0
        }
    }

    for (carried in listOf(false, true)) test("a builder renews exhausted work credit at the site when supplied carried=$carried") {
        residentRuntime { h ->
            val hut = h.assignFarm().copy(type = BuildingType.BUILDER_HUT, schematicId = "builder1", production = null)
            val target = hut.copy(id = UUID.randomUUID(), type = BuildingType.HOUSE, phase = BuildingPhase.CONSTRUCTING,
                entrance = CivPosition(30, 64, 0), control = CivPosition(30, 64, 0), anchor = CivPosition(30, 64, 0),
                bounds = CivBox(CivPosition(28, 64, -2), CivPosition(32, 68, 2)))
            val order = CivConstruction(UUID.randomUUID(), target.id, 1, 2, 0, ConstructionPhase.PLACING,
                Stock(), plannedBuilding = target, batches = CivConstructionBatches(2, 16), materials = mapOf("STONE" to 1L),
                payment = CivConstructionPayment(reservedBatch = 0), workCredit = 1)
            h.city = CivsRules.validate(h.city.copy(buildings = listOf(h.city.buildings.first(), hut, target), construction = order,
                workers = h.city.workers.map { if (carried) CivsEquipment.collectSupplies(it, mapOf("STONE" to 1L)) else it }))
            h.world.getBlockAt(30, 64, 0).type = Material.BARREL
            val actor = h.owner.collisionActors().single()
            val site = Location(h.world, 29.5, 64.0, .5)
            actor.teleport(site)
            h.owner.pulse(2_000)
            h.supplyRequests shouldBe 0
            h.city = CivsRules.completeBatch(h.city, h.city.owner, order.id)
            h.city.construction!!.phase shouldBe ConstructionPhase.CONSTRUCTING
            h.workReady = CivsEquipment.usableCount(h.city.workers.single(), "STONE") > 0
            h.owner.pulse(3_000)
            if (carried) repeat(20) { h.navigate() }
            else for (tick in 1..512) {
                h.navigate()
                val at = actor.location
                if (citizenAtStorage(h.world, CitizenPoint(at.x, at.y, at.z), hut.control!!)) break
            }
            h.supplyRequests shouldBe 0
            if (carried) {
                actor.location shouldBe site
                h.owner.workingResidentIds(h.city.id) shouldBe setOf(h.workerId)
            } else {
                citizenAtStorage(h.world, CitizenPoint(actor.location.x, actor.location.y, actor.location.z), hut.control!!) shouldBe true
                h.owner.pulse(4_000)
                h.supplyRequests shouldBe 1
                h.owner.requireStorageArrival(h.city.id, h.workerId, hut.id)
                h.owner.pulse(5_000)
                h.supplyRequests shouldBe 1
                h.owner.status(h.workerId) shouldBe "workers.status.loading-cargo"
                h.owner.constructionReady(h.city.id, order.id) shouldBe false
            }
            h.deliveries shouldBe 0
            h.city.workers.single().completedCycles shouldBe 0
        }
    }

    test("an accepted packet hit freezes work until durable injury and viewer retirement cannot heal it") {
        residentRuntime { h ->
            h.hit(7.0)
            h.owner.status(h.workerId) shouldBe "workers.status.injury-pending"
            h.visualHealth shouldBe 20.0
            h.hit(7.0)
            h.hits shouldBe 1
            h.commitHit(); h.owner.pulse(2_000)
            h.visualHealth shouldBe 13.0
            h.viewing = false; h.owner.pulse(3_000)
            h.owner.activeCount() shouldBe 0
            h.viewing = true; h.owner.pulse(4_000)
            h.owner.activeCount() shouldBe 1
            h.visualHealth shouldBe 13.0
        }
    }

    test("durable death removes the worker and never creates a replacement packet actor") {
        residentRuntime { h ->
            h.hit(20.0)
            h.owner.activeCount() shouldBe 1
            h.commitHit(); h.owner.pulse(2_000)
            h.owner.activeCount() shouldBe 0
            repeat(5) { h.owner.pulse(3_000L + it * 1_000) }
            h.spawns shouldBe 1
            h.city.workers shouldBe emptyList()
        }
    }

    test("unknown injury commit stays frozen until explicit canonical reconciliation") {
        residentRuntime { h ->
            h.hit(3.0)
            h.pending.completeExceptionally(IllegalStateException("unknown commit"))
            h.owner.pulse(2_000)
            h.owner.status(h.workerId) shouldBe "workers.status.injury-pending"
            h.visualHealth shouldBe 20.0
            h.owner.reconcileResidents()
            h.owner.pulse(3_000)
            h.visualHealth shouldBe 20.0
        }
    }

    test("a failed final mine approach selects another inside stance for the same block and delivers once without recovery") {
        residentRuntime { h ->
            val first = h.prepareMineWork(CivPosition(26, 64, 0))
            val alternative = first.copy(standing = CivPosition(22, 64, 0))
            val quarry = requireNotNull(h.city.building(first.buildingId))
            CivsMinePlan.workStandingAllowed(quarry, first.standing) shouldBe true
            CivsMinePlan.workStandingAllowed(quarry, alternative.standing) shouldBe true
            citizenStandable(h.world, first.standing) shouldBe true
            citizenStandable(h.world, alternative.standing) shouldBe true
            val actor = h.owner.collisionActors().single()
            val origin = actor.location
            h.onWorkPositionFailed = { city, worker, failed ->
                city.id shouldBe h.city.id
                worker.id shouldBe h.workerId
                failed shouldBe first
                h.work = alternative
                true
            }
            h.navigationNow = 2_000L
            h.owner.pulse(2_000)
            h.owner.status(h.workerId) shouldBe "workers.status.moving-to-work"
            for (tick in 1..1_000) {
                if (h.routeFailure() != null) break
                h.navigate(advanceMillis = 0)
            }
            withClue(h.navigationState()) { h.routeFailure() shouldBe "no-path" }
            actor.location.toVector() shouldBe origin.toVector()
            h.failedWorkPositions shouldBe emptyList()
            h.deliveries shouldBe 0

            h.owner.pulse(2_000)
            h.failedWorkPositions shouldBe listOf(first)
            h.owner.status(h.workerId) shouldBe "workers.status.searching-work"
            h.routeIdentity() shouldBe null
            actor.location.toVector() shouldBe origin.toVector()
            val selections = h.workPlans
            h.navigationNow = 2_001L
            h.owner.pulse(2_001)
            (h.workPlans > selections) shouldBe true
            h.work shouldBe alternative
            requireNotNull(h.routeIdentity())
            for (tick in 1..1_000) {
                val before = actor.location
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                (before.distance(actor.location) <= .172001) shouldBe true
                h.failedWorkPositions shouldBe listOf(first)
                if (h.deliveries == 1) break
            }
            withClue("at=${actor.location} ${h.navigationState()}") { h.deliveries shouldBe 1 }
            (actor.location.x > origin.x && actor.location.x < requireNotNull(first.focus).x) shouldBe true
            CivsMinePlan.workStandingAllowed(quarry,
                CivPosition(actor.location.blockX, actor.location.blockY, actor.location.blockZ)) shouldBe true
            citizenCanReachBlock(actor, requireNotNull(first.focus), CITIZEN_MINE_REACH) shouldBe true
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, alternative)
            h.owner.status(h.workerId) shouldBe "workers.status.digging-mine"
            h.spawns shouldBe 1
            h.pendingDelivery.isDone shouldBe false
            h.city.workers.single().completedCycles shouldBe 0L
            repeat(3) { h.owner.pulse(requireNotNull(h.navigationNow) + (it + 1) * 1_000) }
            h.deliveries shouldBe 1
            h.commitWork()
            h.owner.pulse(requireNotNull(h.navigationNow) + 4_000)
            h.city.workers.single().completedCycles shouldBe 1L
            h.deliveries shouldBe 1
            h.failedWorkPositions shouldBe listOf(first)
        }
    }

    test("a transient mine planner rejection preserves the selected standing position") {
        residentRuntime { h ->
            val work = h.prepareMineWork(CivPosition(22, 64, 0))
            val actor = h.owner.collisionActors().single()
            val origin = actor.location
            h.onWorkPositionFailed = { _, _, _ -> true }
            h.navigationNow = 2_000L
            h.navigationRejections = 1
            h.owner.pulse(2_000)
            for (tick in 1..1_000) {
                if (h.routeFailure() != null) break
                h.navigate(advanceMillis = 0)
            }
            h.navigationRejections shouldBe 0
            withClue(h.navigationState()) { h.routeFailure() shouldBe "planner-busy" }
            h.owner.pulse(2_000)
            h.failedWorkPositions shouldBe emptyList()
            h.work shouldBe work
            actor.location.toVector() shouldBe origin.toVector()
            h.deliveries shouldBe 0
            h.city.workers.single().completedCycles shouldBe 0L
        }
    }

    test("a player collision preserves the mine stance and work resumes after the player leaves") {
        residentRuntime { h ->
            val work = h.prepareMineWork(CivPosition(22, 64, 0))
            val actor = h.owner.collisionActors().single()
            val origin = actor.location
            var blockedSteps = 0
            h.owner.residentStepAllowed = { _, _, _ -> blockedSteps++; false }
            h.onWorkPositionFailed = { _, _, _ -> true }
            h.navigationNow = 2_000L
            h.owner.pulse(2_000)
            for (tick in 1..1_000) {
                h.navigate(advanceMillis = 0)
                if (tick % 20 == 0) h.owner.pulse(2_000)
                if (blockedSteps >= 100) break
            }
            withClue(h.navigationState()) { (blockedSteps >= 100) shouldBe true }
            actor.location.toVector() shouldBe origin.toVector()
            h.failedWorkPositions shouldBe emptyList()
            h.work shouldBe work
            h.deliveries shouldBe 0
            h.owner.residentStepAllowed = { _, _, _ -> true }
            for (tick in 1..1_000) {
                val before = actor.location
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                (before.distance(actor.location) <= .172001) shouldBe true
                if (h.deliveries == 1) break
            }
            withClue("at=${actor.location} ${h.navigationState()}") { h.deliveries shouldBe 1 }
            h.failedWorkPositions shouldBe emptyList()
            h.work shouldBe work
            citizenCanReachBlock(actor, requireNotNull(work.focus), CITIZEN_MINE_REACH) shouldBe true
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)
            h.pendingDelivery.isDone shouldBe false
            h.city.workers.single().completedCycles shouldBe 0L
        }
    }

    test("a mining receipt requires observed feet inside the authored gallery even when the block stays in reach") {
        residentRuntime { h ->
            val focus = CivPosition(27, 64, 3)
            val work = h.prepareMineWork(CivPosition(28, 64, 3), focus)
            val quarry = requireNotNull(h.city.building(work.buildingId))
            val actor = h.owner.collisionActors().single()
            val inside = Location(h.world, 28.5, 64.0, 3.5)
            val outside = Location(h.world, 29.5, 64.0, 3.5)
            CivsMinePlan.workStandingAllowed(quarry, CivPosition(28, 64, 3)) shouldBe true
            CivsMinePlan.workStandingAllowed(quarry, CivPosition(29, 64, 3)) shouldBe false
            actor.teleport(inside)
            h.owner.pulse(2_000)
            h.owner.status(h.workerId) shouldBe "workers.status.digging-mine"
            h.owner.pulse(3_500)
            h.deliveries shouldBe 1
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)
            actor.teleport(outside)
            citizenCanReachBlock(actor, focus, CITIZEN_MINE_REACH) shouldBe true
            shouldThrow<CivsRejection> { h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work) }
            val future = Location(h.world, 24.5, 64.0, 5.5)
            val geometry = requireNotNull(CivsMinePlan.forBuilding(quarry))
            CivsMinePlan.workStandingAllowed(quarry, CivPosition(24, 64, 5)) shouldBe true
            geometry.builtStanding(CivsMinePlan.localPoint(quarry, CivPosition(24, 64, 5)),
                requireNotNull(work.miningAccessStep)) shouldBe false
            actor.teleport(future)
            citizenCanReachBlock(actor, focus, CITIZEN_MINE_REACH) shouldBe true
            shouldThrow<CivsRejection> { h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work) }
            actor.teleport(inside)
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)
            h.deliveries shouldBe 1
            h.pendingDelivery.isDone shouldBe false
            h.city.workers.single().completedCycles shouldBe 0L
        }
    }

    test("a miner waits on its ladder while the next job is searched instead of climbing toward an idle surface walk") {
        residentRuntime { h ->
            val quarry = h.assignQuarry()
            val actor = h.owner.collisionActors().single()
            for (y in 57..64) h.world.getBlockAt(18, y, 0).type = Material.LADDER
            val start = Location(h.world, 18.5, 61.4, .5)
            actor.teleport(start)
            h.work = CivWorldWork(h.workerId, 0, quarry.id, CivPosition(18, 61, 0),
                listOf(CivPlanBlock(CivPosition(19, 62, 0), "minecraft:stone", "minecraft:air")), emptyMap(), emptyMap(),
                durationMillis = 2_000, focus = CivPosition(19, 62, 0), status = "digging-mine")
            h.owner.pulse(2_000)
            h.owner.status(h.workerId) shouldBe "workers.status.digging-mine"
            // This is the real cache gap after a commit / invalidation. Its surface anchor
            // is valid and reachable, so a misplaced idle walk would visibly climb the shaft.
            h.work = null
            h.idleReason = "searching-work"
            h.owner.pulse(3_000)
            repeat(160) { h.navigate() }
            actor.location.distanceSquared(start) shouldBe 0.0
            h.owner.status(h.workerId) shouldBe "workers.status.searching-work"
            h.deliveries shouldBe 0
            h.city.workers.single().completedCycles shouldBe 0
            h.work = CivWorldWork(h.workerId, 0, quarry.id, CivPosition(18, 57, 0),
                listOf(CivPlanBlock(CivPosition(18, 56, 0), "minecraft:stone", "minecraft:air")), emptyMap(), emptyMap(),
                durationMillis = 2_000, focus = CivPosition(18, 56, 0), status = "digging-mine")
            h.owner.pulse(4_000)
            repeat(1_000) {
                h.navigate()
                (actor.location.y <= start.y) shouldBe true
            }
            (actor.location.y < start.y - .1) shouldBe true
            h.owner.pulse(4_000)
            h.owner.status(h.workerId) shouldBe "workers.status.digging-mine"
            h.deliveries shouldBe 0
        }
    }

    test("a miner waiting for a tool walks from the hall to its quarry through an upward ingress step") {
        residentRuntime { h ->
            val quarry = h.assignQuarry()
            h.idleReason = "waiting-tool"
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, .5, 66.0, .5))
            h.world.getBlockAt(0, 65, 0).type = Material.STONE
            h.world.getBlockAt(1, 66, 0).type = Material.STONE
            h.world.getBlockAt(2, 65, 0).type = Material.STONE
            h.world.getBlockAt(3, 64, 0).type = Material.STONE
            h.owner.pulse(2_000)
            val control = requireNotNull(quarry.control)
            val destination = Location(h.world, control.x + .5, control.y.toDouble(), control.z + .5)
            // Drain the real 1ms search budget without advancing the recovery timeout.
            // Cold mock block reads may need more calls, but every movement must stay continuous.
            for (index in 0 until 2_000) {
                val before = actor.location
                h.navigate()
                if ((index + 1) % 20 == 0) h.owner.pulse(2_000L)
                (before.distance(actor.location) <= .215001) shouldBe true
                if (actor.location.distanceSquared(destination) <= 8.0 * 8.0) break
            }
            val at = actor.location
            (at.distanceSquared(destination) <= 8.0 * 8.0) shouldBe true
            (at.blockX != control.x || at.blockZ != control.z) shouldBe true
            h.owner.status(h.workerId) shouldBe "workers.status.waiting-tool"
            h.spawns shouldBe 1
            h.deliveries shouldBe 0
            h.city.workers.single().completedCycles shouldBe 0
        }
    }

    test("an unreachable waiting miner recovers beside its quarry without completing work or returning to the hall") {
        residentRuntime { h ->
            val quarry = h.assignQuarry()
            h.idleReason = "waiting-tool"
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, .5, 64.0, .5))
            for (x in -1..1) for (z in -1..1) if (x != 0 || z != 0)
                for (y in 64..66) h.world.getBlockAt(x, y, z).type = Material.STONE
            h.owner.pulse(2_000)
            var recovered = false
            repeat(80) { index ->
                val before = actor.location
                h.navigate()
                if ((index + 1) % 20 == 0) h.owner.pulse(2_000L + (index + 1) * 50)
                if (before.distance(actor.location) > 8.0) recovered = true
            }
            val control = requireNotNull(quarry.control)
            val at = actor.location
            recovered shouldBe true
            (at.distanceSquared(Location(h.world, control.x + .5, control.y.toDouble(), control.z + .5)) <= 8.0 * 8.0) shouldBe true
            (at.blockX != control.x || at.blockZ != control.z) shouldBe true
            h.owner.status(h.workerId) shouldBe "workers.status.waiting-tool"
            h.spawns shouldBe 1
            h.deliveries shouldBe 0
            h.city.workers.single().completedCycles shouldBe 0
            h.pendingDelivery.isDone shouldBe false
        }
    }

    test("three residents reserve separate full sleeping bodies and release their places at dawn") {
        residentRuntime { h ->
            // Home positions in chunk -1 need the same loaded one-chunk search
            // margin as a real route from those positions.
            for (x in -2..1) for (z in -2..1) h.world.loadChunk(x, z)
            val home = h.city.buildings.single().copy(bounds = CivBox(CivPosition(-4, 64, -4), CivPosition(4, 68, 4)))
            h.city = h.city.copy(buildings = listOf(home), workers = h.city.workers.map { it.copy(homeId = home.id) } +
                List(2) { CivWorker(UUID.randomUUID(), "Neighbour$it", null, homeId = home.id) })
            h.world.time = 13_000
            h.owner.pulse(2_000)
            h.owner.activeCount() shouldBe 3
            val actors = h.owner.collisionActors().toList()
            // Check the initial spawn frame before navigation can move a newcomer
            // out of a sleeper's body and conceal an invalid spawn admission.
            val alreadySleeping = actors.filter { it.pose == PacketNpcPose.SLEEPING }
            alreadySleeping.isNotEmpty() shouldBe true
            for (npc in actors.filter { it.pose != PacketNpcPose.SLEEPING }) {
                val at = npc.location
                val half = .3 * npc.scale
                val body = BoundingBox(at.x - half, at.y, at.z - half,
                    at.x + half, at.y + 1.8 * npc.scale, at.z + half)
                for (sleeper in alreadySleeping) withClue("spawn=${npc.location} sleeper=${sleeper.location}") {
                    body.overlaps(runtimeSleepingBody(sleeper)) shouldBe false
                }
            }
            var slept = false
            for (tick in 1..400) {
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(2_000 + tick * 50L)
                if (actors.all { it.pose == PacketNpcPose.SLEEPING }) { slept = true; break }
            }
            withClue(actors.joinToString { "${it.uniqueId} ${it.location} ${h.owner.status(it.uniqueId)} ${h.navigationState(it.uniqueId)}" }) {
                slept shouldBe true
            }
            val bodies = actors.map { npc ->
                val feet = requireNotNull(h.owner.residentFeet(npc.uniqueId))
                citizenPacketSupported(h.world, CitizenPoint(feet.x, feet.y, feet.z)) shouldBe true
                citizenPacketBodyClear(h.world, CitizenPoint(feet.x, feet.y, feet.z), false) shouldBe true
                val body = runtimeSleepingBody(npc)
                citizenSleepClear(h.world, body) shouldBe true
                home.bounds.contains(feet.blockX, feet.blockY, feet.blockZ) shouldBe true
                body
            }
            for (first in bodies.indices) for (second in first + 1 until bodies.size)
                bodies[first].overlaps(bodies[second]) shouldBe false
            actors.map { h.owner.residentFeet(it.uniqueId)?.toVector() }.toSet().size shouldBe 3
            val grounded = actors.associate { it.uniqueId to requireNotNull(h.owner.residentFeet(it.uniqueId)) }
            h.world.time = 1_000
            h.owner.pulse(30_000)
            for (npc in actors) {
                npc.pose shouldBe PacketNpcPose.STANDING
                npc.location.toVector() shouldBe grounded.getValue(npc.uniqueId).toVector()
            }
            h.deliveries shouldBe 0
        }
    }

    test("a sleeping place occupied during travel releases its finished route and chooses another place") {
        residentRuntime { h ->
            for (x in -2..2) for (z in -2..1) h.world.loadChunk(x, z)
            val home = h.city.buildings.single().copy(bounds = CivBox(CivPosition(-4, 64, -4), CivPosition(4, 68, 4)))
            val unavailable = home.copy(id = UUID.randomUUID(), type = BuildingType.HOUSE, control = null,
                entrance = CivPosition(30, 64, 0), bounds = CivBox(CivPosition(30, 64, 0), CivPosition(30, 64, 0)))
            val neighbour = CivWorker(UUID.randomUUID(), "Neighbour", null, homeId = unavailable.id)
            h.city = h.city.copy(buildings = listOf(home, unavailable), workers =
                h.city.workers.map { it.copy(homeId = home.id) } + neighbour)
            h.owner.pulse(2_000)
            val actors = h.owner.collisionActors()
            val resident = actors.single { it.uniqueId == h.workerId }
            val visitor = actors.single { it.uniqueId == neighbour.id }
            resident.teleport(Location(h.world, 12.5, 64.0, .5))
            visitor.teleport(Location(h.world, 20.5, 64.0, .5))
            h.world.time = 13_000
            h.owner.pulse(3_000)
            val reserved = Location(h.world, 1.5, 64.0, .5)
            // Another resident occupies the place after its reservation. The real
            // owner must revalidate the pose even when its route has completed.
            visitor.teleport(reserved)
            for (tick in 1..512) {
                h.navigate()
                if (resident.location.distanceSquared(reserved) < .000001) break
            }
            withClue("location=${resident.location} ${h.navigationState()}") {
                resident.location.toVector() shouldBe reserved.toVector()
            }
            // Let the route owner retire its final waypoint before the arrival pulse.
            h.navigate()
            h.owner.pulse(4_000)
            resident.pose shouldBe PacketNpcPose.STANDING
            var slept = false
            for (tick in 1..400) {
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(4_000 + tick * 50L)
                if (resident.pose == PacketNpcPose.SLEEPING) { slept = true; break }
            }
            withClue("location=${resident.location} ${h.navigationState()}") { slept shouldBe true }
            val at = visitor.location
            val half = .3 * visitor.scale
            val occupied = BoundingBox(at.x - half, at.y, at.z - half,
                at.x + half, at.y + 1.8 * visitor.scale, at.z + half)
            runtimeSleepingBody(resident).overlaps(occupied) shouldBe false
            visitor.location.toVector() shouldBe reserved.toVector()
            h.deliveries shouldBe 0
        }
    }

    test("eight homes without a sleeping place cannot starve the next resident") {
        residentRuntime { h ->
            for (x in -2..3) for (z in -2..1) h.world.loadChunk(x, z)
            h.city = h.city.copy(workers = h.city.workers +
                List(8) { CivWorker(UUID.randomUUID(), "Neighbour$it", null) })
            repeat(4) { h.owner.pulse(2_000 + it * 1_000L) }
            val actors = h.owner.collisionActors().toList()
            actors.size shouldBe 9
            val unavailable = h.city.buildings.first().copy(id = UUID.randomUUID(), type = BuildingType.HOUSE,
                entrance = CivPosition(40, 64, 0), control = null,
                bounds = CivBox(CivPosition(40, 64, 0), CivPosition(40, 64, 0)))
            val home = unavailable.copy(id = UUID.randomUUID(), entrance = CivPosition(22, 64, 0),
                bounds = CivBox(CivPosition(18, 64, -4), CivPosition(26, 68, 4)))
            val last = actors.last()
            h.city = h.city.copy(buildings = h.city.buildings + listOf(unavailable, home),
                workers = h.city.workers.map { it.copy(homeId = if (it.id == last.uniqueId) home.id else unavailable.id) })
            actors.dropLast(1).forEachIndexed { index, npc ->
                npc.teleport(Location(h.world, .5 + index * 2, 64.0, .5))
            }
            last.teleport(Location(h.world, 22.5, 64.0, .5))
            h.world.time = 13_000
            h.owner.pulse(7_000)
            last.pose shouldBe PacketNpcPose.STANDING
            h.owner.pulse(8_000)
            withClue("status=${h.owner.status(last.uniqueId)} location=${last.location}") {
                last.pose shouldBe PacketNpcPose.SLEEPING
            }
            actors.dropLast(1).all { it.pose == PacketNpcPose.STANDING } shouldBe true
            h.deliveries shouldBe 0
        }
    }

    test("one available sleeping place never stacks later residents and is reusable after retirement") {
        residentRuntime { h ->
            val home = h.city.buildings.single().copy(bounds = CivBox(CivPosition(-3, 64, -3), CivPosition(3, 65, 3)))
            h.city = h.city.copy(buildings = listOf(home), workers = h.city.workers.map { it.copy(homeId = home.id) } +
                List(2) { CivWorker(UUID.randomUUID(), "Neighbour$it", null, homeId = home.id) })
            h.owner.pulse(2_000)
            val actors = h.owner.collisionActors().toList()
            actors.size shouldBe 3
            val only = Location(h.world, 1.5, 64.0, .5)
            // A low ceiling leaves one standing/wake cell while the lying body still
            // fits below it. This supplies exactly one physically valid sleep admission.
            for (x in -3..3) for (z in -3..3) if (x != 1 || z != 0)
                h.world.getBlockAt(x, 65, z).type = Material.STONE
            actors.first { it.uniqueId == h.workerId }.teleport(only)
            actors.filter { it.uniqueId != h.workerId }.forEachIndexed { index, npc ->
                npc.teleport(Location(h.world, 7.5 + index * 2, 64.0, .5))
            }
            h.world.time = 13_000
            repeat(8) { h.owner.pulse(3_000 + it * 1_000L) }
            actors.count { it.pose == PacketNpcPose.SLEEPING } shouldBe 1
            actors.single { it.pose == PacketNpcPose.SLEEPING }.uniqueId shouldBe h.workerId
            actors.filter { it.uniqueId != h.workerId }.forEach {
                it.pose shouldBe PacketNpcPose.STANDING
                (it.location.x >= 7.5) shouldBe true
                h.owner.status(it.uniqueId) shouldBe "workers.status.sheltering"
            }
            h.city = h.city.copy(workers = h.city.workers.filterNot { it.id == h.workerId })
            h.owner.pulse(12_000)
            val next = h.owner.collisionActors().first()
            next.teleport(only)
            repeat(8) { h.owner.pulse(13_000 + it * 1_000L) }
            next.pose shouldBe PacketNpcPose.SLEEPING
            h.owner.collisionActors().count { it.pose == PacketNpcPose.SLEEPING } shouldBe 1
            h.deliveries shouldBe 0
        }
    }

    test("removing a sleeping resident's floor wakes it at logical feet and normal gravity settles it") {
        residentRuntime { h ->
            val actor = h.owner.collisionActors().single()
            val origin = Location(h.world, 1.5, 64.0, .5)
            actor.teleport(origin)
            h.world.time = 13_000
            h.owner.pulse(2_000)
            actor.pose shouldBe PacketNpcPose.SLEEPING
            h.owner.residentFeet(h.workerId) shouldBe origin
            h.world.getBlockAt(1, 63, 0).type = Material.AIR
            h.owner.pulse(3_000)
            actor.pose shouldBe PacketNpcPose.STANDING
            actor.location shouldBe origin
            repeat(30) { h.navigate() }
            actor.location.y shouldBe 63.0
            citizenPacketSupported(h.world, CitizenPoint(actor.location.x, actor.location.y, actor.location.z)) shouldBe true
            h.deliveries shouldBe 0
            h.city.workers.single().completedCycles shouldBe 0
        }
    }

    test("a new ceiling cannot force a sleeper to stand inside stone or work before a safe wake space opens") {
        residentRuntime { h ->
            val actor = h.owner.collisionActors().single()
            val origin = Location(h.world, 1.5, 64.0, .5)
            val lying = requireNotNull(citizenSleepPose(h.world, origin, 1.0)).bounds
            actor.teleport(origin)
            h.world.time = 13_000
            h.owner.pulse(2_000)
            actor.pose shouldBe PacketNpcPose.SLEEPING
            val sleepingPosition = actor.location
            for (x in -2..2) for (z in -2..2) h.world.getBlockAt(x, 65, z).type = Material.STONE
            citizenSleepClear(h.world, lying) shouldBe true
            citizenPacketBodyClear(h.world, CitizenPoint(origin.x, origin.y, origin.z), false) shouldBe false
            h.world.time = 0
            for (now in listOf(3_000L, 4_000L)) {
                h.owner.pulse(now)
                repeat(40) { h.navigate() }
                actor.pose shouldBe PacketNpcPose.SLEEPING
                actor.location shouldBe sleepingPosition
                h.owner.workingResidentIds(h.city.id) shouldBe emptySet()
                h.hits shouldBe 0
                (h.owner.status(h.workerId) == "workers.status.injury-pending") shouldBe false
            }
            // There is now a supported spot in the same home with a clear low-body sweep.
            h.world.getBlockAt(2, 65, 0).type = Material.AIR
            h.owner.pulse(5_000)
            actor.pose shouldBe PacketNpcPose.STANDING
            h.hits shouldBe 0
            actor.location.x shouldBe 2.5
            actor.location.y shouldBe origin.y
            citizenPacketBodyClear(h.world, CitizenPoint(actor.location.x, actor.location.y, actor.location.z), false) shouldBe true
            h.deliveries shouldBe 0
            h.city.workers.single().completedCycles shouldBe 0
            h.steps shouldBe emptyList()
        }
    }

    test("a valid thirty-block night walk continues beyond ten seconds and reaches home without recovery") {
        residentRuntime { h ->
            val home = h.city.buildings.single().copy(id = UUID.randomUUID(), type = BuildingType.HOUSE,
                schematicId = "house1", anchor = CivPosition(29, 64, 0), entrance = CivPosition(29, 64, 0),
                bounds = CivBox(CivPosition(27, 64, -2), CivPosition(31, 68, 2)), control = null)
            h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings + home,
                workers = h.city.workers.map { it.copy(homeId = home.id) }))
            for (x in -1..3) for (z in -1..1) h.world.loadChunk(x, z)
            h.world.time = 13_000
            val actor = h.owner.collisionActors().single()
            val origin = requireNotNull(h.owner.residentFeet(h.workerId))
            val messages = mutableListOf<String>()
            val handler = object : Handler() {
                override fun publish(record: LogRecord) { messages += record.message }
                override fun flush() = Unit
                override fun close() = Unit
            }
            h.logger.addHandler(handler)
            try {
                h.owner.pulse(2_000)
                var walking = false
                // Drain bounded A* admission without spending any simulated travel time.
                for (frame in 1..512) {
                    val before = requireNotNull(h.owner.residentFeet(h.workerId))
                    h.navigate()
                    val after = requireNotNull(h.owner.residentFeet(h.workerId))
                    (before.distance(after) <= .118251) shouldBe true
                    if (after.distanceSquared(origin) > .000001) { walking = true; break }
                }
                withClue("planner=${h.navigationState()} diagnostics=${messages.takeLast(4)}") { walking shouldBe true }
                var atTenSeconds: Location? = null
                var slept = false
                // Thirty seconds is bounded and below this route's ordinary 55-second budget.
                for (tick in 1..600) {
                    val before = requireNotNull(h.owner.residentFeet(h.workerId))
                    h.navigate()
                    if (tick % 20 == 0) h.owner.pulse(2_000L + tick * 50)
                    // Sleeping raises only the visible mesh; logical feet still prove a real arrival.
                    val after = requireNotNull(h.owner.residentFeet(h.workerId))
                    withClue("tick=$tick before=$before after=$after status=${h.owner.status(h.workerId)}") {
                        (before.distance(after) <= .118251) shouldBe true
                    }
                    if (tick == 200) atTenSeconds = after
                    if (tick == 220) {
                        h.owner.status(h.workerId) shouldBe "workers.status.sheltering"
                        actor.pose shouldBe PacketNpcPose.STANDING
                        home.bounds.contains(after.blockX, after.blockY, after.blockZ) shouldBe false
                        (after.distance(requireNotNull(atTenSeconds)) > .5) shouldBe true
                    }
                    if (actor.pose == PacketNpcPose.SLEEPING) {
                        (tick > 220) shouldBe true
                        slept = true
                        break
                    }
                }
                withClue("status=${h.owner.status(h.workerId)} location=${actor.location} " +
                    "planner=${h.navigationState()} diagnostics=${messages.takeLast(4)}") { slept shouldBe true }
                val feet = requireNotNull(h.owner.residentFeet(h.workerId))
                home.bounds.contains(feet.blockX, feet.blockY, feet.blockZ) shouldBe true
                citizenPacketSupported(h.world, CitizenPoint(feet.x, feet.y, feet.z)) shouldBe true
                h.owner.status(h.workerId) shouldBe "workers.status.sleeping"
                messages.none { "citizen_route_recovered" in it || "ARCCIVS_NAVIGATION_FAILED" in it } shouldBe true
                h.spawns shouldBe 1
                h.deliveries shouldBe 0
                h.city.workers.single().completedCycles shouldBe 0L
            } finally { h.logger.removeHandler(handler) }
        }
    }

    test("an enclosed builder waiting for materials recovers into its home at night without work credit") {
        residentRuntime { h ->
            val hut = h.assignFarm().copy(type = BuildingType.BUILDER_HUT, schematicId = "builder1", production = null)
            val target = hut.copy(id = UUID.randomUUID(), type = BuildingType.HOUSE, phase = BuildingPhase.CONSTRUCTING,
                entrance = CivPosition(30, 64, 0), control = CivPosition(30, 64, 0), anchor = CivPosition(30, 64, 0),
                bounds = CivBox(CivPosition(28, 64, -2), CivPosition(32, 68, 2)))
            val home = h.city.buildings.first()
            val order = CivConstruction(UUID.randomUUID(), target.id, 1, 2, 0, ConstructionPhase.WAITING_MATERIALS,
                Stock(), plannedBuilding = target, batches = CivConstructionBatches(2, 16), materials = mapOf("STONE" to 1L),
                payment = CivConstructionPayment(awaiting = mapOf("STONE" to 1L)), workCredit = 0)
            h.city = CivsRules.validate(h.city.copy(buildings = listOf(home, hut, target), construction = order,
                workers = h.city.workers.map { it.copy(homeId = home.id) }))
            h.world.time = 13_000
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 20.5, 68.0, .5))
            h.world.getBlockAt(20, 67, 0).type = Material.STONE
            for (x in 19..21) for (z in -1..1) if (x != 20 || z != 0)
                for (y in 68..70) h.world.getBlockAt(x, y, z).type = Material.STONE
            // A player occupying every recovery destination must keep the same failed route pending.
            h.owner.residentStepAllowed = { _, from, to -> from.distanceSquared(to) > .000001 }
            h.owner.pulse(2_000)
            repeat(200) { h.navigate() }
            h.owner.pulse(13_001)
            actor.location.x shouldBe 20.5
            h.owner.status(h.workerId) shouldBe "workers.status.sheltering"
            h.owner.residentStepAllowed = { _, _, _ -> true }
            h.owner.pulse(14_001)
            val recovered = actor.location
            home.bounds.contains(recovered.blockX, recovered.blockY, recovered.blockZ) shouldBe true
            h.owner.pulse(15_001)
            actor.pose shouldBe PacketNpcPose.SLEEPING
            h.owner.status(h.workerId) shouldBe "workers.status.sleeping"
            verify { actor.sleepingBed = null }
            val lying = actor.location
            (lying.y > recovered.y) shouldBe true
            h.owner.residentFeet(h.workerId) shouldBe recovered
            h.owner.residentFeet(h.workerId)?.add(0.0, 10.0, 0.0) // Caller mutation cannot alter the saved feet.
            h.owner.residentFeet(h.workerId) shouldBe recovered
            repeat(20) { h.navigate() }
            actor.location.y shouldBe lying.y // Gravity must not undo the pose-only floor clearance.
            // The pose is rechecked against the current world, not trusted until morning.
            for ((dx, dz) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1))
                h.world.getBlockAt(recovered.blockX + dx, recovered.blockY, recovered.blockZ + dz).type = Material.STONE
            h.owner.pulse(16_001)
            actor.pose shouldBe PacketNpcPose.STANDING
            actor.location.y shouldBe recovered.y
            h.owner.status(h.workerId) shouldBe "workers.status.sheltering"
            h.owner.residentFeet(h.workerId) shouldBe recovered
            h.city.construction shouldBe order
            h.city.workers.single().completedCycles shouldBe 0
            h.deliveries shouldBe 0
            h.pendingDelivery.isDone shouldBe false
            h.steps shouldBe emptyList()
            h.spawns shouldBe 1
        }
    }

    test("idle wandering stays slow and never wears roads even on a faster surface") {
        residentRuntime { h ->
            // Negative wander offsets cross chunk zero and need the route's extra loaded margin.
            for (x in -2..1) for (z in -2..1) h.world.loadChunk(x, z)
            h.surfaceMultiplier = 1.25
            val actor = h.owner.collisionActors().single()
            var walked = 0.0
            for (tick in 0 until 512) {
                // Coarse pulses retry a target rejected before this test loaded its margin.
                if (tick % 20 == 0) h.owner.pulse(2_000L + tick * 50)
                val before = actor.location
                h.navigate()
                val distance = before.distance(actor.location)
                (distance <= .118251) shouldBe true
                walked += distance
                if (walked >= .5) break
            }
            (walked >= .5) shouldBe true
            h.owner.status(h.workerId) shouldBe "workers.status.unassigned"
            h.steps shouldBe emptyList()
            h.deliveries shouldBe 0
        }
    }

    for (ground in listOf(Material.STONE, Material.FARMLAND, Material.DIRT_PATH))
    test("worker-bag pickup on $ground holds the real producer and requires a nearby observed courier") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val warehouse = farm.copy(id = UUID.randomUUID(), type = BuildingType.WAREHOUSE, schematicId = "warehouse1", production = null,
                anchor = CivPosition(10, 64, 0), entrance = CivPosition(10, 64, 0), control = CivPosition(10, 64, 0),
                bounds = CivBox(CivPosition(8, 64, -2), CivPosition(12, 68, 2)))
            h.world.getBlockAt(10, 64, 0).type = Material.BARREL
            val courierId = UUID.randomUUID()
            h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings + warehouse,
                workers = h.city.workers.map { it.copy(items = List(CivsEquipment.SLOTS) { slot ->
                    if (slot == CivsEquipment.BAG) CivResidentItem("COAL", 64) else null
                }) } + CivWorker(courierId, "Courier", warehouse.id, nextDeliveryAt = 1)))
            val producer = h.owner.collisionActors().single()
            h.world.getBlockAt(22, 63, 0).type = ground
            val standingY = if (ground == Material.STONE) 64.0 else 63.9375
            producer.teleport(Location(h.world, 22.5, standingY, .5))
            h.courierProvider = { city, worker -> if (worker.id == courierId)
                CivsLogistics.plan(city, worker, { emptyMap() }, h.owner.courierSources(city.id, worker.id)) { _, _ -> 64L }
                else null }
            h.onPickup = { cityId, workerId, cargo -> h.owner.requireCargoPickupArrival(cityId, workerId, cargo) }
            h.owner.pulse(2_000)
            val courier = h.owner.collisionActors().single { it.uniqueId == courierId }
            val cargo = requireNotNull(h.courierProvider!!.invoke(h.city, h.city.workers.last())).cargo
            cargo.sourceEntrance shouldBe CivPosition(22, 64, 0)
            shouldThrow<CivsRejection> { h.owner.requireCargoPickupArrival(h.city.id, courierId, cargo) }.reason shouldBe "route-unreachable"
            val sourceAt = producer.location
            repeat(20) { h.navigate() }
            producer.location shouldBe sourceAt
            (h.workerId in h.owner.workingResidentIds(h.city.id)) shouldBe false
            h.pickups shouldBe 0
            courier.teleport(Location(h.world, 21.5, 64.0, .5))
            h.owner.pulse(3_000); h.owner.pulse(4_000); h.owner.pulse(5_000)
            h.pickups shouldBe 1
            h.city.workers.last().cargo shouldBe null
            val actual = requireNotNull(h.pickedCargo)
            producer.teleport(sourceAt.clone().add(3.0, 0.0, 0.0))
            shouldThrow<CivsRejection> { h.owner.requireCargoPickupArrival(h.city.id, courierId, actual) }.reason shouldBe "route-unreachable"
            producer.teleport(sourceAt)
            h.city = CivsLogistics.pickup(h.city, courierId, actual)
            h.pendingPickup.complete(h.city)
            h.owner.pulse(6_000)
            h.city.workers.last().cargo!!.sourceWorkerId shouldBe h.workerId
            CivsEquipment.plainCount(h.city.workers.first(), "COAL") shouldBe 0
            CivsEquipment.plainCount(h.city.workers.last(), "COAL") shouldBe 64
            h.pickups shouldBe 1
        }
    }

    for (outcome in listOf("pickup", "night", "food", "combat", "assignment", "cycle", "expiry"))
    test("a new ripe crop preserves the accepted partial-stack rendezvous until $outcome") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val warehouse = farm.copy(id = UUID.randomUUID(), type = BuildingType.WAREHOUSE, schematicId = "warehouse1", production = null,
                anchor = CivPosition(10, 64, 0), entrance = CivPosition(10, 64, 0), control = CivPosition(10, 64, 0),
                bounds = CivBox(CivPosition(8, 64, -2), CivPosition(12, 68, 2)))
            h.world.getBlockAt(10, 64, 0).type = Material.BARREL
            val courierId = UUID.randomUUID()
            h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings + warehouse,
                workers = h.city.workers.map { it.copy(items = List(CivsEquipment.SLOTS) { slot -> when (slot) {
                    4 -> CivResidentItem("IRON_HOE", 1)
                    6 -> CivResidentItem("WHEAT", 24)
                    7 -> CivResidentItem("WHEAT_SEEDS", 43)
                    else -> null
                } }) } + CivWorker(courierId, "Courier", warehouse.id, nextDeliveryAt = 1)))
            h.idleReason = "waiting-growth"
            val producer = h.owner.collisionActors().single()
            producer.teleport(Location(h.world, 22.5, 64.0, .5))
            h.courierProvider = { city, worker -> if (worker.id == courierId)
                CivsLogistics.plan(city, worker, { emptyMap() }, h.owner.courierSources(city.id, worker.id)) { _, _ -> 64L }
                else null }
            h.onPickup = { cityId, workerId, cargo -> h.owner.requireCargoPickupArrival(cityId, workerId, cargo) }
            h.owner.pulse(2_000)
            h.owner.status(h.workerId) shouldBe "workers.status.waiting-courier"
            val sourceAt = producer.location
            val courier = h.owner.collisionActors().single { it.uniqueId == courierId }
            val courierAt = courier.location
            val selectedBefore = h.workPlans
            val focus = CivPosition(23, 64, 1)
            h.world.getBlockAt(focus.x, focus.y, focus.z).blockData =
                (Material.WHEAT.createBlockData() as Ageable).also { it.age = it.maximumAge }
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(24, 64, 1),
                listOf(CivPlanBlock(focus, "minecraft:wheat[age=7]", "minecraft:wheat[age=0]")),
                outputs = mapOf("WHEAT" to 1L, "WHEAT_SEEDS" to 1L), durationMillis = 30_000,
                status = "working-field", focus = focus)
            h.owner.pulse(3_000)
            h.owner.pulseFieldWork(3_050)
            h.workPlans shouldBe selectedBefore
            h.owner.status(h.workerId) shouldBe "workers.status.waiting-courier"
            h.owner.courierSources(h.city.id, courierId).keys shouldBe setOf(h.workerId)
            producer.location shouldBe sourceAt
            (h.workerId in h.owner.workingResidentIds(h.city.id)) shouldBe false
            if (outcome == "pickup") {
                var now = 3_000L
                for (frame in 0 until 384) {
                    h.navigate()
                    if (frame % 20 == 0) {
                        now += 1_000L
                        h.owner.pulse(now)
                        h.owner.pulseFieldWork(now + 50)
                    }
                    producer.location shouldBe sourceAt
                    if (h.pickups > 0) break
                }
                withClue("courier=${courier.location} ${h.navigationState(courierId)}") { h.pickups shouldBe 1 }
                (courier.location.distanceSquared(courierAt) > 4.0) shouldBe true
                val cargo = requireNotNull(h.pickedCargo)
                cargo.material shouldBe "WHEAT"
                cargo.amount shouldBe 24L
                CivsEquipment.plainCount(h.city.workers.first(), "WHEAT") shouldBe 24
                CivsEquipment.plainCount(h.city.workers.last(), "WHEAT") shouldBe 0
                h.city = CivsLogistics.pickup(h.city, courierId, cargo)
                h.pendingPickup.complete(h.city)
                h.owner.pulse(now + 1_000)
                CivsEquipment.plainCount(h.city.workers.first(), "WHEAT") shouldBe 0
                CivsEquipment.plainCount(h.city.workers.last(), "WHEAT") shouldBe 24
                h.city.workers.last().cargo!!.id shouldBe cargo.id
                h.pickups shouldBe 1
            } else {
                when (outcome) {
                    "night" -> h.world.time = 13_000
                    "food" -> h.foodSource = warehouse.id
                    "combat" -> h.monsterAt(24.5, .5)
                    "assignment" -> h.city = h.city.copy(workers = h.city.workers.map { if (it.id == h.workerId) it.copy(assignment = null) else it })
                    "cycle" -> h.city = h.city.copy(workers = h.city.workers.map { if (it.id == h.workerId) it.copy(completedCycles = 1) else it })
                }
                val now = if (outcome == "expiry") 92_000L else 4_000L
                h.owner.pulse(now)
                if (outcome == "combat") h.navigate()
                h.owner.pulse(now + 1_000)
                h.owner.courierSources(h.city.id, courierId) shouldBe emptyMap()
                h.pickups shouldBe 0
                h.city.workers.last().cargo shouldBe null
                CivsEquipment.plainCount(h.city.workers.first(), "WHEAT") shouldBe 24
            }
            (h.owner.status(h.workerId) == "workers.status.waiting-courier") shouldBe false
            h.deliveries shouldBe 0
        }
    }

    test("a working producer accumulates a stack but a full bag can offer its small surplus immediately") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 22.5, 64.0, .5))
            fun bag(amount: Int, full: Boolean = false) = List<CivResidentItem?>(CivsEquipment.SLOTS) { slot -> when {
                slot == 4 -> CivResidentItem("IRON_HOE", 1)
                slot == CivsEquipment.BAG -> CivResidentItem("COAL", amount)
                full && slot > CivsEquipment.BAG -> CivResidentItem("IRON_AXE", 1)
                else -> null
            } }
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(items = bag(1)) })
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(22, 64, 0),
                listOf(CivPlanBlock(CivPosition(23, 63, 0), "minecraft:stone", "minecraft:air")),
                outputs = mapOf("WHEAT" to 1L), durationMillis = 30_000, status = "working-field")
            h.owner.pulse(2_000)
            val courier = UUID.randomUUID()
            h.owner.courierSources(h.city.id, courier) shouldBe emptyMap()
            h.owner.status(h.workerId) shouldBe "workers.status.working-field"
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(items = bag(64)) })
            h.owner.courierSources(h.city.id, courier).keys shouldBe setOf(h.workerId)
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(items = bag(1, full = true)) })
            h.owner.courierSources(h.city.id, courier).keys shouldBe setOf(h.workerId)
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(items = bag(1, full = true).toMutableList().also { slots ->
                slots[CivsEquipment.BAG + 1] = CivResidentItem("OAK_SAPLING", 1)
            }) })
            h.work = h.work!!.copy(inputs = mapOf("OAK_SAPLING" to 1L), outputs = mapOf("OAK_LOG" to 4L))
            h.owner.pulse(3_000)
            h.owner.courierSources(h.city.id, courier) shouldBe emptyMap()
            CivsEquipment.plainCount(h.city.workers.single(), "OAK_SAPLING") shouldBe 1
            h.deliveries shouldBe 0
        }
    }

    test("expired worker rendezvous releases the producer and retries rarely after three failures") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val warehouse = farm.copy(id = UUID.randomUUID(), type = BuildingType.WAREHOUSE, schematicId = "warehouse1", production = null,
                anchor = CivPosition(10, 64, 0), entrance = CivPosition(10, 64, 0), control = CivPosition(10, 64, 0),
                bounds = CivBox(CivPosition(8, 64, -2), CivPosition(12, 68, 2)))
            h.world.getBlockAt(10, 64, 0).type = Material.BARREL
            val courierId = UUID.randomUUID()
            h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings + warehouse,
                workers = h.city.workers.map { it.copy(items = List(CivsEquipment.SLOTS) { slot ->
                    if (slot == CivsEquipment.BAG) CivResidentItem("COAL", 64) else null
                }) } + CivWorker(courierId, "Courier", warehouse.id, nextDeliveryAt = 1)))
            h.owner.collisionActors().single().teleport(Location(h.world, 22.5, 64.0, .5))
            h.courierProvider = { city, worker -> if (worker.id == courierId)
                CivsLogistics.plan(city, worker, { emptyMap() }, h.owner.courierSources(city.id, worker.id)) { _, _ -> 64L }
                else null }
            var start = 2_000L
            var expired = 0L
            repeat(3) { attempt ->
                h.owner.pulse(start)
                h.owner.courierSources(h.city.id, courierId).keys shouldBe setOf(h.workerId)
                expired = start + 90_000L
                h.owner.pulse(expired); h.owner.pulse(expired + 1)
                h.owner.courierSources(h.city.id, courierId) shouldBe emptyMap()
                h.owner.status(h.workerId) shouldBe "workers.status.waiting-inputs"
                start = expired + if (attempt == 0) 5_001L else 15_001L
            }
            h.owner.pulse(expired + 59_999L)
            h.owner.courierSources(h.city.id, courierId) shouldBe emptyMap()
            h.owner.pulse(expired + 60_001L)
            h.owner.courierSources(h.city.id, courierId).keys shouldBe setOf(h.workerId)
            h.pickups shouldBe 0
            CivsEquipment.plainCount(h.city.workers.first(), "COAL") shouldBe 64
            h.city.workers.last().cargo shouldBe null
        }
    }

    test("a courier unloads once then walks back to its warehouse and waits beside the real barrel") {
        residentRuntime { h ->
            val warehouse = h.assignFarm().copy(type = BuildingType.WAREHOUSE, schematicId = "warehouse1", production = null)
            val receiver = warehouse.copy(id = UUID.randomUUID(), type = BuildingType.SAWMILL, schematicId = "sawmill1",
                anchor = CivPosition(8, 64, 0), entrance = CivPosition(8, 64, 0), control = CivPosition(8, 64, 0),
                bounds = CivBox(CivPosition(6, 64, -2), CivPosition(10, 68, 2)))
            h.world.getBlockAt(8, 64, 0).type = Material.BARREL
            val cargo = CivCargo(UUID.randomUUID(), warehouse.id, warehouse.id, receiver.id, warehouse.entrance, "OAK_LOG", 8)
            h.city = CivsRules.validate(h.city.copy(buildings = listOf(h.city.buildings.first(), warehouse, receiver),
                workers = h.city.workers.map { it.copy(cargo = cargo) }))
            h.courier = CivCourierPlan(cargo, receiver.id, receiver.entrance, true)
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 9.5, 64.0, .5))
            h.owner.pulse(2_000)
            h.owner.pulse(3_000)
            h.unloads shouldBe 1
            h.city.workers.single().cargo shouldBe cargo
            h.city = h.city.copy(revision = h.city.revision + 1,
                workers = h.city.workers.map { it.copy(cargo = null, completedCycles = 1) })
            h.courier = null
            h.pendingUnload.complete(h.city)
            h.owner.pulse(4_000)
            h.owner.status(h.workerId) shouldBe "workers.status.moving-to-storage"
            var normalSteps = 0
            var normalDistance = 0.0
            var fasterDistance = 0.0
            val messages = mutableListOf<String>()
            val frames = mutableListOf<String>()
            val handler = object : Handler() {
                override fun publish(record: LogRecord) { messages += record.message }
                override fun flush() = Unit
                override fun close() = Unit
            }
            val storageAt = requireNotNull(warehouse.control).let { Location(h.world, it.x + .5, it.y.toDouble(), it.z + .5) }
            // Four times the direct walking time plus 128 planning/turning frames is
            // ample for this short open-floor trip and fails before runaway mock history.
            val frameLimit = 128 + 4 * kotlin.math.ceil(actor.location.distance(storageAt) / .172).toInt()
            h.logger.addHandler(handler)
            try {
                for (tick in 1..frameLimit) {
                    val before = actor.location
                    h.navigate()
                    if (tick % 20 == 0) {
                        h.owner.pulse(4_000)
                        frames += "$tick:${actor.location}:${h.owner.status(h.workerId)}"
                    }
                    val distance = before.distance(actor.location)
                    (distance <= .172 * h.surfaceMultiplier + .000001) shouldBe true
                    if (h.surfaceMultiplier == 1.0) {
                        normalDistance = maxOf(normalDistance, distance)
                        if (distance > .000001 && ++normalSteps == 5) h.surfaceMultiplier = 1.25
                    } else fasterDistance = maxOf(fasterDistance, distance)
                    if (h.owner.status(h.workerId) == "workers.status.waiting-orders") break
                }
            } finally { h.logger.removeHandler(handler) }
            withClue("limit=$frameLimit status=${h.owner.status(h.workerId)} location=${actor.location} " +
                "planner=${h.navigationState()} frames=${frames.takeLast(8)} diagnostics=${messages.takeLast(4)}") {
                h.owner.status(h.workerId) shouldBe "workers.status.waiting-orders"
            }
            (kotlin.math.abs(normalDistance - .172) < .000001) shouldBe true
            (kotlin.math.abs(fasterDistance - .215) < .000001) shouldBe true
            val atWarehouse = actor.location
            citizenAtStorage(h.world, CitizenPoint(atWarehouse.x, atWarehouse.y, atWarehouse.z), requireNotNull(warehouse.control)) shouldBe true
            citizenCanReachBlock(actor, requireNotNull(warehouse.control)) shouldBe true
            h.owner.status(h.workerId) shouldBe "workers.status.waiting-orders"
            h.steps.isNotEmpty() shouldBe true
            val workSteps = h.steps.size
            repeat(80) { tick ->
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(5_000L + tick * 50)
                actor.location shouldBe atWarehouse
            }
            h.steps.size shouldBe workSteps
            h.unloads shouldBe 1
            h.deliveries shouldBe 0
            h.city.workers.single().completedCycles shouldBe 1
            h.city.workers.single().cargo shouldBe null
            h.spawns shouldBe 1
        }
    }

    test("summoned farmer starts a supplied work plan at the barrel and keeps its delivery receipt fenced") {
        residentRuntime { h ->
            val farm = CivBuilding(UUID.randomUUID(), BuildingType.FARM, 1, CivPosition(20, 64, 0), 0,
                "farm1", CivBox(CivPosition(18, 64, -2), CivPosition(26, 68, 2)),
                CivPosition(20, 64, 0), BuildingPhase.ACTIVE, control = CivPosition(20, 64, 0), production = "WHEAT")
            for (z in -1..1) h.world.loadChunk(2, z) // Loaded search margin east of the farm's chunk.
            h.world.getBlockAt(20, 64, 0).type = Material.BARREL
            h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings + farm,
                workers = h.city.workers.map { it.copy(assignment = farm.id, nextDeliveryAt = 1) },
                inventory = CivInventory(CivStorageMode.PHYSICAL)))
            h.owner.pulse(2_000) // A waiting farmer may already have an idle route from the former position.
            h.owner.activeCount() shouldBe 1
            val actor = h.owner.collisionActors().single()
            val previous = actor.location
            h.owner.summon(h.city, farm.id) shouldBe true
            val summoned = actor.location
            (previous.distance(summoned) > 8.0) shouldBe true
            h.deliveries shouldBe 0

            // Publication of supplied seeds makes a new field plan available on the next pulse.
            h.city = h.city.copy(revision = h.city.revision + 1,
                inventory = requireNotNull(h.city.inventory).copy(buildingMaterials = mapOf(farm.id.toString() to mapOf("WHEAT_SEEDS" to 1L))))
            val focus = CivPosition(25, 63, 0)
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(24, 64, 0),
                listOf(CivPlanBlock(focus, "minecraft:stone", "minecraft:air")), focus = focus,
                inputs = mapOf("WHEAT_SEEDS" to 1L), durationMillis = 1_500, status = "working-field")
            h.owner.pulse(3_000)
            actor.location shouldBe summoned
            h.spawns shouldBe 1
            h.deliveries shouldBe 0
            for (tick in 1..160) {
                val before = actor.location
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(3_000L + tick * 50)
                // A fresh plan walks from the summon, without a stale route or recovery jump.
                (before.distance(actor.location) <= .215001) shouldBe true
                (actor.location.x >= summoned.x) shouldBe true
                if (h.deliveries != 0) break
            }
            h.deliveries shouldBe 1
            (actor.location.x > summoned.x) shouldBe true
            h.spawns shouldBe 1
            h.owner.status(h.workerId) shouldBe "workers.status.working-field"
            h.city.workers.single().completedCycles shouldBe 0
            h.city.inventory?.buildingStock(farm.id) shouldBe mapOf("WHEAT_SEEDS" to 1L)
            h.pendingDelivery.isDone shouldBe false
            h.owner.summon(h.city, farm.id) shouldBe false // Never abandon an admitted receipt.
            h.owner.pulse(12_000)
            h.deliveries shouldBe 1
        }
    }

    test("reachable work stays at the observed position faces the real block and rechecks LOS at receipt admission") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val actor = h.owner.collisionActors().single()
            val observed = Location(h.world, 22.5, 64.0, .5)
            actor.teleport(observed)
            val focus = CivPosition(23, 63, 0)
            val work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(24, 64, 0),
                listOf(CivPlanBlock(focus, "minecraft:stone", "minecraft:air")),
                durationMillis = 1_500, status = "working-field", focus = focus)
            h.work = work
            h.owner.pulse(2_000)
            h.owner.status(h.workerId) shouldBe "workers.status.working-field"
            shouldThrow<CivsRejection> { h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work) }
            repeat(20) { h.navigate() }
            actor.location.toVector() shouldBe observed.toVector()
            verify(atLeast = 1) { actor.swingMainHand() }
            verify(atLeast = 1) { actor.pitch = match { it > 45f } }
            h.owner.pulse(3_499)
            h.deliveries shouldBe 0
            h.owner.pulse(3_500)
            h.deliveries shouldBe 1
            actor.location.toVector() shouldBe observed.toVector()
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)

            // A city wait must not let an already admitted action work through a newly placed wall.
            h.world.getBlockAt(23, 64, 0).type = Material.STONE
            shouldThrow<CivsRejection> { h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work) }
            h.world.getBlockAt(23, 64, 0).type = Material.AIR
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)
            actor.teleport(Location(h.world, 27.5, 64.0, .5))
            shouldThrow<CivsRejection> { h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work) }
            h.pendingDelivery.isDone shouldBe false
            h.city.workers.single().completedCycles shouldBe 0
        }
    }

    test("field care stays at its crop and emits one contact cue only after its durable receipt") {
        for ((status, prop) in listOf("watering" to Material.WATER_BUCKET, "fertilizing" to Material.BONE_MEAL)) {
            residentRuntime { h ->
                val work = h.beginCare(status)
                val actor = h.owner.collisionActors().single()
                val observed = actor.location
                val items = h.city.workers.single().items
                h.displayedHand shouldBe prop
                h.owner.status(h.workerId) shouldBe "workers.status.$status"
                verify(atLeast = 1) { actor.swingMainHand() }
                repeat(20) {
                    val before = actor.location
                    h.navigate()
                    val after = actor.location
                    after.toVector() shouldBe observed.toVector()
                    (kotlin.math.abs(citizenAngleChange(before.yaw, after.yaw)) <= 8.0001f) shouldBe true
                    (kotlin.math.abs(after.pitch - before.pitch) <= 8.0001f) shouldBe true
                    h.deliveries shouldBe 0
                }
                verify(atLeast = 1) { actor.pitch = match { it > 0f } }
                h.world.particles shouldBe emptyList()
                h.world.sounds shouldBe emptyList()
                // The cycle counts observed work, never a single four-second gap between pulses.
                for (at in 3_000L..5_000L step 1_000L) {
                    h.owner.pulse(at)
                    h.deliveries shouldBe 0
                }
                h.owner.pulse(6_000)
                h.deliveries shouldBe 1
                h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)
                actor.location.toVector() shouldBe observed.toVector()
                h.owner.pulse(7_000) // A delayed receipt must not masquerade as a completed watering.
                h.city.workers.single().items shouldBe items
                h.displayedHand shouldBe prop
                h.world.particles shouldBe emptyList()
                h.world.sounds shouldBe emptyList()
                h.commitWork()
                h.owner.pulse(8_000)
                h.displayedHand shouldBe Material.IRON_HOE
                h.world.particles.single().let { (particle, count, at) ->
                    particle shouldBe if (status == "watering") Particle.SPLASH else Particle.HAPPY_VILLAGER
                    count shouldBe if (status == "watering") 8 else 6
                    at.x shouldBe requireNotNull(work.focus).x + .5
                    at.z shouldBe requireNotNull(work.focus).z + .5
                    (at.y > requireNotNull(work.focus).y && at.y < requireNotNull(work.focus).y + .5) shouldBe true
                }
                h.world.sounds.single() shouldBe Triple(
                    if (status == "watering") Sound.ITEM_BUCKET_EMPTY else Sound.ITEM_BONE_MEAL_USE, .35f, 1.1f)
                repeat(3) { h.owner.pulse(9_000L + it * 1_000) }
                h.world.particles.size shouldBe 1
                h.world.sounds.size shouldBe 1
            }
        }
    }

    test("failed injured and retired care restores the true hand without a success cue") {
        for (outcome in listOf("rejected", "injured", "retired")) {
            residentRuntime { h ->
                h.beginCare("watering")
                for (at in 3_000L..6_000L step 1_000L) h.owner.pulse(at)
                h.deliveries shouldBe 1
                h.displayedHand shouldBe Material.WATER_BUCKET
                when (outcome) {
                    "rejected" -> h.pendingDelivery.completeExceptionally(CivsRejection("stale-worksite"))
                    "injured" -> { h.hit(2.0); h.commitWork() }
                    else -> { h.viewing = false; h.owner.pulse(6_500); h.commitWork() }
                }
                h.owner.pulse(7_000)
                h.displayedHand shouldBe Material.IRON_HOE
                h.world.particles shouldBe emptyList()
                h.world.sounds shouldBe emptyList()
            }
        }
    }

    test("an out-of-reach worker approaches only until the actual block becomes reachable") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 21.5, 64.0, .5))
            val focus = CivPosition(25, 63, 0)
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(24, 64, 0),
                listOf(CivPlanBlock(focus, "minecraft:stone", "minecraft:air")),
                durationMillis = 1_500, status = "working-field", focus = focus)
            h.owner.pulse(2_000)
            h.deliveries shouldBe 0
            for (tick in 1..160) {
                val before = actor.location
                h.navigate()
                (before.distance(actor.location) <= .215001) shouldBe true
                if (tick % 20 == 0) h.owner.pulse(2_000L + tick * 50)
                if (h.deliveries != 0) break
            }
            h.deliveries shouldBe 1
            citizenCanReachBlock(actor, focus) shouldBe true
            (actor.location.x > 21.5 && actor.location.x < 24.0) shouldBe true
            (actor.location.distance(Location(h.world, 24.5, 64.0, .5)) > .65) shouldBe true
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, requireNotNull(h.work))
        }
    }

    test("a worker already at another side of its barrel does not route to the preferred side") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val actor = h.owner.collisionActors().single()
            val otherSide = Location(h.world, 19.5, 64.0, .5)
            actor.teleport(otherSide)
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(21, 64, 0), emptyList(),
                durationMillis = 1_500, status = "equipping-tool")
            h.owner.pulse(2_000)
            repeat(20) { h.navigate() }
            actor.location.toVector() shouldBe otherSide.toVector()
            h.owner.pulse(3_500)
            h.deliveries shouldBe 1
            h.owner.requireStorageArrival(h.city.id, h.workerId, farm.id)
            actor.location.toVector() shouldBe otherSide.toVector()
        }
    }

    test("storage approach stops at the first usable side between coarse work pulses") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 15.5, 64.0, .5))
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(21, 64, 0), emptyList(),
                durationMillis = 1_500, status = "equipping-tool")
            h.owner.pulse(2_000)
            val control = requireNotNull(farm.control)
            // The shared 1 ms search slice has no fixed operations-per-callback guarantee.
            // Hold coarse time still and observe the first usable side, without timeout recovery.
            for (tick in 1..2_000) {
                val before = actor.location
                h.navigate()
                val at = actor.location
                (before.distance(at) <= .215001) shouldBe true
                if (citizenAtStorage(h.world, CitizenPoint(at.x, at.y, at.z), control) &&
                    citizenCanReachBlock(actor, control)) break
            }
            val arrival = actor.location
            withClue("arrival=$arrival status=${h.owner.status(h.workerId)}") {
                citizenAtStorage(h.world, CitizenPoint(arrival.x, arrival.y, arrival.z), control) shouldBe true
                citizenCanReachBlock(actor, control) shouldBe true
            }
            (arrival.x < 20.0) shouldBe true
            repeat(40) { h.navigate() }
            actor.location shouldBe arrival
            h.deliveries shouldBe 0
            h.owner.pulse(3_000)
            h.owner.pulse(4_500)
            h.deliveries shouldBe 1
            h.owner.requireStorageArrival(h.city.id, h.workerId, farm.id)
            actor.location shouldBe arrival
        }
    }

    test("physical nudges preserve a pending receipt and stop at native walls") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 22.5, 64.0, .5))
            val focus = CivPosition(23, 63, 0)
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(24, 64, 0),
                listOf(CivPlanBlock(focus, "minecraft:stone", "minecraft:air")),
                durationMillis = 1_500, status = "working-field", focus = focus)
            h.owner.pulse(2_000)
            h.owner.pulse(3_500)
            h.deliveries shouldBe 1
            h.owner.nudgeResident(actor, Vector(.05, 0.0, 0.0)) shouldBe true
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, requireNotNull(h.work))
            h.owner.pulse(4_000)
            h.deliveries shouldBe 1
            h.pendingDelivery.isDone shouldBe false
            actor.teleport(Location(h.world, 22.7, 64.0, .5))
            h.world.getBlockAt(23, 64, 0).type = Material.STONE
            h.owner.nudgeResident(actor, Vector(.05, 0.0, 0.0)) shouldBe false
            actor.location.x shouldBe 22.7
        }
    }
})

/** Native sleeping model envelope from actual handle fields, not the allocated target. */
private fun runtimeSleepingBody(npc: PacketPlayerNpc): BoundingBox {
    val at = npc.location
    val s = npc.scale
    return when (npc.bodyYaw) {
        0f -> BoundingBox(at.x - 1.96 * s, at.y - .31 * s, at.z - .62 * s,
            at.x + .07 * s, at.y + .31 * s, at.z + .62 * s)
        90f -> BoundingBox(at.x - .62 * s, at.y - .31 * s, at.z - .07 * s,
            at.x + .62 * s, at.y + .31 * s, at.z + 1.96 * s)
        180f -> BoundingBox(at.x - .07 * s, at.y - .31 * s, at.z - .62 * s,
            at.x + 1.96 * s, at.y + .31 * s, at.z + .62 * s)
        270f -> BoundingBox(at.x - .62 * s, at.y - .31 * s, at.z - 1.96 * s,
            at.x + .62 * s, at.y + .31 * s, at.z + .07 * s)
        else -> error("Unexpected sleeping yaw ${npc.bodyYaw}")
    }
}

private fun residentRuntime(useOwnedNavigationExecutor: Boolean = false, block: (ResidentRuntime) -> Unit) {
    failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = ResidentRuntime(paper, useOwnedNavigationExecutor)
            try { block(fixture) } finally { fixture.close() }
        }
    }
}

private class ResidentRuntime(private val paper: MockBukkitTestRuntime, useOwnedNavigationExecutor: Boolean) : AutoCloseable {
    private val plugin = paper.createSimplePlugin("ResidentsTest")
    private val navigationTasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    private val navigationWork = BudgetedTaskQueue(navigationTasks, nanoTime = { 0L })
    private val navigationWorkers = ArrayDeque<Runnable>()
    val logger get() = plugin.logger
    val world = ResidentRuntimeWorld().also(paper.server::addWorld)
    private val packets = mockk<PaperPacketNpcs>(relaxed = true)
    val workerId = UUID.randomUUID()
    var viewing = true
    var background = false
    var spawns = 0
    var teleports = 0
        private set
    var navigationNow: Long? = null
    var holdNavigation = false
    var navigationRejections = 0
    val pendingNavigationWorkers: Int get() = navigationWorkers.size
    var hits = 0
    var deliveries = 0
    var unloads = 0
    var supplyRequests = 0
    var workReady = true
    var foodSource: UUID? = null
    var workPlans = 0
    val failedWorkPositions = mutableListOf<CivWorldWork>()
    var onWorkPositionFailed: (Settlement, CivWorker, CivWorldWork) -> Boolean = { _, _, _ -> false }
    var surfaceMultiplier = 1.0
    val pendingSupplies = CompletableFuture<Settlement>()
    var courier: CivCourierPlan? = null
    var courierProvider: ((Settlement, CivWorker) -> CivCourierPlan?)? = null
    var pickups = 0
    var pickedCargo: CivCargo? = null
    var onPickup: (UUID, UUID, CivCargo) -> Unit = { _, _, _ -> }
    val pendingPickup = CompletableFuture<Settlement>()
    val steps = mutableListOf<Pair<Location, Location>>()
    var displayedHand = Material.AIR
    var work: CivWorldWork? = null
    var idleReason = "waiting-inputs"
    val pendingDelivery = CompletableFuture<Settlement>()
    val pendingUnload = CompletableFuture<Settlement>()
    val pending = CompletableFuture<Settlement>()
    private var damage = 0.0
    var city: Settlement
    val otherCities = mutableListOf<Settlement>()
    val owner: CivsCitizens
    val visualHealth: Double get() = owner.hologramSnapshots().single().health

    init {
        for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
        val viewer = paper.addPlayer("ResidentViewer")
        viewer.teleport(Location(world, .5, 64.0, .5))
        val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(0, 64, 0), 0,
            "townhall1", CivBox(CivPosition(-2, 64, -2), CivPosition(2, 68, 2)), CivPosition(0, 64, 0), BuildingPhase.ACTIVE)
        city = CivsRules.validate(Settlement(UUID.randomUUID(), viewer.uniqueId, "Town", world.uid, CivPosition(0, 64, 0),
            96, "nordic", Stock(), listOf(hall), listOf(CivWorker(workerId, "Resident", null))))
        every { packets.spawnPlayer(any(), any()) } answers {
            spawns++
            var position = firstArg<Location>().clone()
            var npcBodyYaw = position.yaw
            var npcHeadYaw = position.yaw
            var npcPitch = position.pitch
            val profile = secondArg<PacketNpcProfile>()
            var valid = true
            var npcScale = 1.0
            var npcPose = PacketNpcPose.STANDING
            mockk<PacketPlayerNpc>(relaxed = true) {
                every { uniqueId } returns profile.uuid
                every { isValid } answers { valid }
                every { location } answers { position.clone().also { it.yaw = npcBodyYaw; it.pitch = npcPitch } }
                every { bodyYaw } answers { npcBodyYaw }
                every { bodyYaw = any() } answers { npcBodyYaw = firstArg() }
                every { headYaw } answers { npcHeadYaw }
                every { headYaw = any() } answers { npcHeadYaw = firstArg() }
                every { pitch } answers { npcPitch }
                every { pitch = any() } answers { npcPitch = firstArg() }
                every { world } returns this@ResidentRuntime.world
                every { height } answers { when (npcPose) {
                    PacketNpcPose.STANDING -> 1.8 * npcScale
                    PacketNpcPose.CROUCHING -> 1.5 * npcScale
                    PacketNpcPose.SLEEPING -> .2
                } }
                every { eyeHeight } answers { when (npcPose) {
                    PacketNpcPose.STANDING -> 1.62 * npcScale
                    PacketNpcPose.CROUCHING -> 1.27 * npcScale
                    PacketNpcPose.SLEEPING -> .2
                } }
                every { scale } answers { npcScale }
                every { scale = any() } answers { npcScale = firstArg() }
                every { pose } answers { npcPose }
                every { pose = any() } answers { npcPose = firstArg() }
                every { teleport(any()) } answers {
                    teleports++
                    position = firstArg<Location>().clone()
                    npcBodyYaw = position.yaw; npcPitch = position.pitch
                    Unit
                }
                every { setEquipment(EquipmentSlot.HAND, any()) } answers { displayedHand = secondArg<ItemStack>().type; Unit }
                every { remove() } answers { valid = false; Unit }
            }
        }
        every { packets.closeAsync() } returns CompletableFuture.completedFuture(null)
        owner = CivsCitizens(plugin, { if (viewing) listOf(city) + otherCities else emptyList() },
            { _, _, _, _, _, _, _ ->
                check(work != null) { "idle resident cannot deliver" }
                deliveries++
                pendingDelivery
            },
            { _, _, _ -> }, hurt = { _, _, hit -> hits++; damage = hit; pending },
            footstep = { _, _, _, from, to -> steps += from.clone() to to.clone() },
            surfaceSpeed = { surfaceMultiplier },
            courierPlan = { city, worker -> courierProvider?.invoke(city, worker) ?: courier },
            pickupCargo = { city, worker, cargo ->
                onPickup(city, worker, cargo)
                pickups++
                pickedCargo = cargo
                pendingPickup
            }, dropoffCargo = { _, _, cargo, target ->
                cargo shouldBe requireNotNull(courier).cargo
                target shouldBe requireNotNull(courier).destinationBuildingId
                unloads++
                pendingUnload
            },
            workPlan = { _, _ -> workPlans++; work }, workIdleReason = { _, _ -> idleReason }, packetOwner = packets,
            residentSupplySource = { _, worker -> foodSource.takeIf { worker.id == workerId } },
            workInputsReady = { _, _ -> workReady }, prepareSupplies = { _, _, _ ->
                supplyRequests++
                pendingSupplies
            }, backgroundActive = { background }, navigationTasks = navigationTasks, navigationWork = navigationWork,
            navigationExecutor = if (useOwnedNavigationExecutor) null else Executor { task ->
                if (navigationRejections > 0) {
                    navigationRejections--
                    throw RejectedExecutionException("Transient navigation fixture admission failure")
                }
                if (holdNavigation) navigationWorkers += task else task.run()
            },
            navigationClock = { navigationNow ?: System.currentTimeMillis() },
            workPositionFailed = { city, worker, failed ->
                failedWorkPositions += failed
                onWorkPositionFailed(city, worker, failed)
            })
        owner.pulse(1_000)
        owner.activeCount() shouldBe 1
    }

    fun monsterAt(x: Double, z: Double): Monster {
        world.difficulty = Difficulty.NORMAL
        val at = Location(world, x, 64.0, z)
        return mockk<Monster>(relaxed = true) {
            every { uniqueId } returns UUID.randomUUID()
            every { isValid } returns true
            every { isDead } returns false
            every { hasAI() } returns true
            every { type } returns EntityType.ZOMBIE
            every { world } returns this@ResidentRuntime.world
            every { location } answers { at.clone() }
            every { eyeLocation } answers { at.clone().add(0.0, 1.62, 0.0) }
            every { target } returns null
        }.also(world.threatEntities::add)
    }

    fun hit(amount: Double) = owner.damage(workerId, amount)
    fun navigate(advanceMillis: Long = 50L) {
        navigationNow = navigationNow?.plus(advanceMillis)
        paper.performTicks(1)
        owner.activateNavigating()
    }
    fun releaseNavigation() {
        holdNavigation = false
        while (navigationWorkers.isNotEmpty()) navigationWorkers.removeFirst().run()
    }
    override fun close() {
        try { owner.closeAsync().join() } finally {
            navigationWork.close()
            navigationTasks.close()
        }
    }
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).run {
        isAccessible = true
        get(target)
    }
    /** Read-only identity distinguishes an accepted pending route from a silent replacement. */
    fun routeIdentity(worker: UUID = workerId): Any? =
        (field(owner, "actors") as Map<*, *>)[worker]?.let { field(it, "route") }
    fun routeFailure(worker: UUID = workerId): String? =
        routeIdentity(worker)?.let { (field(it, "motion") as CitizenPacketRoute).failure }
    fun ownedNavigationExecutor(): Executor =
        requireNotNull(field(requireNotNull(field(owner, "navigation")), "ownedExecutor") as? Executor)
    /** Read-only failure context; runtime behavior never depends on private test access. */
    fun navigationState(worker: UUID = workerId): String = runCatching {
        val actor = (field(owner, "actors") as Map<*, *>)[worker] ?: return@runCatching "no actor"
        val route = field(actor, "route")
        val motion = route?.let { field(it, "motion") as CitizenPacketRoute }
        val queue = field(requireNotNull(field(owner, "navigation")), "jobs") as Map<*, *>
        val shelter = field(actor, "shelter") as? CivPosition
        val feet = owner.residentFeet(worker)
        val chunksReady = if (shelter == null || feet == null) null else
            CitizenChunks.between(feet.blockX, feet.blockZ, shelter.x, shelter.z).ready(world)
        "cycle=${field(actor, "cycle")} shelter=$shelter shelterChunksReady=$chunksReady sleepPose=${field(actor, "shelterPose")} " +
            "shelterCursor=${field(actor, "shelterSearchIndex")} queue=${queue.size} nodes=${motion?.nodeCount} " +
            "searching=${motion?.searching} failure=${motion?.failure} remaining=${motion?.remainingNodes}"
    }.getOrElse { "unavailable: ${it.javaClass.simpleName}" }

    fun beginCare(status: String): CivWorldWork {
        val farm = assignFarm()
        city = city.copy(workers = city.workers.map { worker -> worker.copy(items =
            MutableList<CivResidentItem?>(CivsEquipment.SLOTS) { null }.also { slots ->
                slots[4] = CivResidentItem("IRON_HOE", 1)
                if (status == "fertilizing") slots[6] = CivResidentItem("BONE_MEAL", 1)
            }) })
        owner.collisionActors().single().teleport(Location(world, 22.5, 64.0, .5))
        val focus = CivPosition(23, 64, 0)
        world.getBlockAt(focus.x, focus.y, focus.z).type = Material.WHEAT
        work = CivWorldWork(workerId, 0, farm.id, CivPosition(24, 64, 0),
            if (status == "fertilizing") listOf(CivPlanBlock(focus, "minecraft:wheat[age=5]", "minecraft:wheat[age=6]"))
                else emptyList(),
            inputs = if (status == "fertilizing") mapOf("BONE_MEAL" to 1L) else emptyMap(),
            durationMillis = 4_000, status = status, focus = focus)
        owner.pulse(2_000)
        return requireNotNull(work)
    }
    fun commitWork() {
        val completed = requireNotNull(work)
        city = city.copy(revision = city.revision + 1, workers = city.workers.map { worker ->
            CivsEquipment.consumeInputs(worker, completed.inputs).copy(completedCycles = worker.completedCycles + 1)
        })
        work = null
        idleReason = "waiting-growth"
        pendingDelivery.complete(city)
    }
    fun assignFarm(): CivBuilding {
        val farm = CivBuilding(UUID.randomUUID(), BuildingType.FARM, 1, CivPosition(20, 64, 0), 0,
            "farm1", CivBox(CivPosition(18, 64, -2), CivPosition(26, 68, 2)), CivPosition(20, 64, 0),
            BuildingPhase.ACTIVE, control = CivPosition(20, 64, 0), production = "WHEAT")
        for (z in -1..1) world.loadChunk(2, z)
        world.getBlockAt(20, 64, 0).type = Material.BARREL
        city = CivsRules.validate(city.copy(buildings = city.buildings + farm,
            workers = city.workers.map { it.copy(assignment = farm.id, nextDeliveryAt = 1) },
            inventory = CivInventory(CivStorageMode.PHYSICAL)))
        return farm
    }
    fun assignQuarry(): CivBuilding = assignFarm().copy(type = BuildingType.QUARRY, schematicId = "miner1", production = "COBBLESTONE").also { quarry ->
        city = CivsRules.validate(city.copy(buildings = city.buildings.map { if (it.id == quarry.id) quarry else it }))
    }
    fun prepareMineWork(standing: CivPosition, focus: CivPosition = CivPosition(25, 64, 0)): CivWorldWork {
        val previous = assignQuarry()
        requireNotNull(previous.control).let { world.getBlockAt(it.x, it.y, it.z).type = Material.AIR }
        // This anchor puts the authored first gallery on the fixture's supported y=64 plane.
        val quarry = spatialBuilding("medievaloak", "miner", 1, CivPosition(20, 75, 8))
            .copy(id = previous.id, production = "COBBLESTONE")
        city = CivsRules.validate(city.copy(buildings = city.buildings.map { if (it.id == quarry.id) quarry else it }))
        for (x in 18..27) for (z in -1..1) for (y in 64..66)
            if (x == 18 || x == 27 || z != 0 || y == 66) world.getBlockAt(x, y, z).type = Material.STONE
        for (y in focus.y..focus.y + 2) world.getBlockAt(focus.x, y, focus.z).type = Material.STONE
        owner.collisionActors().single().teleport(Location(world, 20.5, 64.0, .5))
        val geometry = requireNotNull(CivsMinePlan.forBuilding(quarry))
        val localFocus = CivsMinePlan.localPoint(quarry, focus)
        val step = geometry.steps.indexOfFirst { it.point == localFocus && it.after == null }
        check(step >= 0) { "Missing canonical mine step for $localFocus" }
        return CivWorldWork(workerId, 0, quarry.id, standing,
            listOf(CivPlanBlock(focus, "minecraft:stone", "minecraft:air")), durationMillis = 1_500,
            status = "digging-mine", miningStep = step.toLong(), focus = focus, miningPlanVersion = geometry.version,
            // Controller-only fixture: the shaft/landings are already inspected;
            // the separate faithful-prefix traversal above proves their geometry.
            miningAccessStep = maxOf(step, geometry.shaftEnd).toLong())
            .also { work = it }
    }
    fun commitHit() {
        city = CivsRules.hurtResident(city, city.owner, workerId, damage)
        pending.complete(city)
    }
}

/** A stone plane with no tile entities. Only pinned MockBukkit's missing terrain queries are supplied. */
private class ResidentRuntimeWorld : WorldMock(Material.STONE, 63) {
    val threatEntities = mutableListOf<Entity>()
    val particles = mutableListOf<Triple<Particle, Int, Location>>()
    val sounds = mutableListOf<Triple<Sound, Float, Float>>()
    private val cells = mutableMapOf<Coordinate, BlockMock>()
    private val chunkFixtures = mutableMapOf<Pair<Int, Int>, ChunkMock>()
    init { setName("resident-runtime") }

    override fun getNearbyEntities(location: Location, x: Double, y: Double, z: Double,
        filter: Predicate<in Entity>?): Collection<Entity> = threatEntities.filter { entity ->
        val at = entity.location
        kotlin.math.abs(at.x - location.x) <= x && kotlin.math.abs(at.y - location.y) <= y &&
            kotlin.math.abs(at.z - location.z) <= z && filter?.test(entity) != false
    }

    override fun spawnParticle(particle: Particle, location: Location, count: Int,
        offsetX: Double, offsetY: Double, offsetZ: Double, extra: Double) {
        particles += Triple(particle, count, location.clone())
    }

    override fun playSound(location: Location, sound: Sound, category: SoundCategory, volume: Float, pitch: Float) {
        sounds += Triple(sound, volume, pitch)
    }

    override fun getBlockAt(x: Int, y: Int, z: Int): BlockMock = getBlockAt(Coordinate(x, y, z))
    override fun getBlockAt(point: Coordinate): BlockMock = cells.getOrPut(point) {
        object : BlockMock(if (point.y <= 63) Material.STONE else Material.AIR,
            Location(this, point.x.toDouble(), point.y.toDouble(), point.z.toDouble())) {
            override fun isPassable(): Boolean = type.isAir
            override fun getBoundingBox(): BoundingBox = if (type == Material.LADDER)
                BoundingBox(x.toDouble(), y.toDouble(), z + 13.0 / 16.0, x + 1.0, y + 1.0, z + 1.0)
            else BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + when (type) {
                Material.AIR, Material.CAVE_AIR, Material.VOID_AIR -> 0.0
                Material.FARMLAND, Material.DIRT_PATH -> .9375
                else -> 1.0
            }, z + 1.0)
            override fun getCollisionShape(): VoxelShape = when {
                type.isAir -> EMPTY_SHAPE
                type == Material.LADDER -> LADDER_SHAPE
                type == Material.FARMLAND || type == Material.DIRT_PATH -> LOWERED_SHAPE
                else -> FULL_SHAPE
            }
        }
    }

    /** Pinned WorldMock omits this native API. The fixture adds the default north-facing ladder shape. */
    override fun rayTraceBlocks(start: Location, direction: Vector, maxDistance: Double,
        fluidCollisionMode: FluidCollisionMode, ignorePassableBlocks: Boolean): RayTraceResult? {
        val end = start.clone().add(direction.clone().normalize().multiply(maxDistance))
        var nearest: RayTraceResult? = null
        for (x in minOf(start.blockX, end.blockX)..maxOf(start.blockX, end.blockX))
            for (y in minOf(start.blockY, end.blockY)..maxOf(start.blockY, end.blockY))
                for (z in minOf(start.blockZ, end.blockZ)..maxOf(start.blockZ, end.blockZ)) {
                    val block = getBlockAt(x, y, z)
                    if (block.type.isAir) continue
                    val hit = block.boundingBox.rayTrace(start.toVector(), direction, maxDistance) ?: continue
                    if (nearest == null || hit.hitPosition.distanceSquared(start.toVector()) < nearest.hitPosition.distanceSquared(start.toVector()))
                        nearest = RayTraceResult(hit.hitPosition, block, hit.hitBlockFace)
                }
        return nearest
    }

    override fun getChunkAt(x: Int, z: Int, generate: Boolean): ChunkMock {
        // Preserve native generation/loaded bookkeeping without recording every hot
        // load-level lookup in MockK during bounded snapshot capture.
        super.getChunkAt(x, z, generate)
        return chunkFixtures.getOrPut(x to z) {
            object : ChunkMock(this@ResidentRuntimeWorld, x, z) {
                override fun getTileEntities(filter: Predicate<in Block>, useSnapshot: Boolean): Collection<BlockState> = emptyList()
            }
        }
    }

    companion object {
        private fun shape(boxes: List<BoundingBox>) = object : VoxelShape {
            override fun getBoundingBoxes(): Collection<BoundingBox> = boxes
            override fun overlaps(other: BoundingBox): Boolean = boxes.any { it.overlaps(other) }
        }
        private val EMPTY_SHAPE = shape(emptyList())
        private val FULL_SHAPE = shape(listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)))
        private val LOWERED_SHAPE = shape(listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .9375, 1.0)))
        private val LADDER_SHAPE = shape(listOf(BoundingBox(0.0, 0.0, 13.0 / 16.0, 1.0, 1.0, 1.0)))
    }
}
