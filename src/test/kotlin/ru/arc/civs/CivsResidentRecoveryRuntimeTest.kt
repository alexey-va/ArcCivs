package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.LogRecord

class CivsResidentRecoveryRuntimeTest : FunSpec({
    test("a builder enclosed by a placed wall corner recovers without granting construction or meal credit") {
        residentRuntime(workerId = UUID(0, 104)) { h ->
            val order = enclosedWallBuilder(h)
            val npc = h.owner.collisionActors().single()
            val before = h.city
            h.owner.constructionReady(h.city.id, order.id) shouldBe true
            npc.teleport(Location(h.world, .948457, 64.0, .051543))
            h.world.getBlockAt(0, 64, -1).type = Material.STONE_BRICKS
            citizenResidentEscapeStep(npc) { false }?.step shouldBe null
            h.owner.constructionReady(h.city.id, order.id) shouldBe false

            h.navigate()

            withClue(h.owner.debug(h.city.id, h.workerId)) {
                citizenPacketBodyClear(h.world, CitizenPoint(npc.location.x, npc.location.y, npc.location.z), false) shouldBe true
                h.owner.constructionReady(h.city.id, order.id) shouldBe true
            }
            npc.location shouldBe Location(h.world, .5, 64.0, .5)
            h.city shouldBe before
            h.phase() shouldBe CitizenPhase.READY
            h.deliveries shouldBe 0
            h.unloads shouldBe 0
            h.city.workers.single().hunger shouldBe 0
            h.world.getBlockAt(0, 64, -1).type shouldBe Material.STONE_BRICKS
        }
    }

    test("an enclosed builder with no authorized safe destination stays blocked without any receipt") {
        residentRuntime { h ->
            val order = enclosedWallBuilder(h)
            val npc = h.owner.collisionActors().single()
            npc.teleport(Location(h.world, .948457, 64.0, .051543))
            h.world.getBlockAt(0, 64, -1).type = Material.STONE_BRICKS
            h.owner.residentStepAllowed = { _, _, _ -> false }
            val before = h.city
            val at = npc.location
            repeat(60) { h.navigate() }
            npc.location shouldBe at
            h.owner.constructionReady(h.city.id, order.id) shouldBe false
            h.city shouldBe before
            h.deliveries shouldBe 0
            h.unloads shouldBe 0
        }
    }

    for (hazard in listOf("solid", "unsupported", "unloaded", "player", "resident", "outside"))
    test("overlap recovery rejects a $hazard destination without moving or changing a receipt") {
        residentRuntime { h ->
            enclosedWallBuilder(h)
            val point = if (hazard == "outside") CivPosition(97, 64, 0) else CivPosition(18, 64, 12)
            val destination = Location(h.world, point.x + .5, 64.0, point.z + .5)
            val home = h.city.buildings.first().copy(entrance = point)
            h.city = h.city.copy(buildings = h.city.buildings.map { if (it.id == home.id) home else it })
            h.world.loadChunk(point.x shr 4, point.z shr 4)
            if (hazard == "resident") {
                val other = UUID(0, 107)
                h.city = h.city.copy(workers = h.city.workers + CivWorker(other, "Neighbour", null))
                h.owner.pulse(7_000)
                h.owner.collisionActors().first { it.uniqueId == other }.teleport(destination)
            }
            val collisions = h.nativeCollisions()
            try {
                val player = if (hazard == "player") h.playerAt(destination) else null
                val velocity = player?.velocity
                h.owner.residentStepAllowed = { npc, from, to ->
                    to.distanceSquared(destination) < .000001 && collisions.canResidentMove(npc, from, to)
                }
                when (hazard) {
                    "solid" -> h.world.getBlockAt(point.x, 64, point.z).type = Material.STONE
                    "unsupported" -> h.world.getBlockAt(point.x, 63, point.z).type = Material.AIR
                    "unloaded" -> h.world.unloadChunk(point.x shr 4, point.z shr 4)
                }
                val npc = h.owner.collisionActors().first { it.uniqueId == h.workerId }
                npc.teleport(Location(h.world, .948457, 64.0, .051543))
                h.world.getBlockAt(0, 64, -1).type = Material.STONE_BRICKS
                val before = h.city
                val origin = npc.location
                val chunks = h.world.loadedChunks.map { it.x to it.z }.toSet()
                repeat(40) { h.navigate() }
                npc.location shouldBe origin
                h.city shouldBe before
                h.deliveries shouldBe 0
                h.unloads shouldBe 0
                h.world.loadedChunks.map { it.x to it.z }.toSet() shouldBe chunks
                player?.velocity shouldBe velocity
            } finally { collisions.close() }
        }
    }

    test("an enclosed builder can use an existing safe home entrance without requiring a walking path") {
        residentRuntime { h ->
            enclosedWallBuilder(h, homeEntrance = CivPosition(18, 64, 12))
            val destination = Location(h.world, 18.5, 64.0, 12.5)
            h.owner.residentStepAllowed = { _, _, to -> to.distanceSquared(destination) < .000001 }
            val npc = h.owner.collisionActors().single()
            npc.teleport(Location(h.world, .948457, 64.0, .051543))
            h.world.getBlockAt(0, 64, -1).type = Material.STONE_BRICKS
            val before = h.city
            h.navigate()
            npc.location shouldBe destination
            h.city shouldBe before
            h.deliveries shouldBe 0
            h.owner.pulse(requireNotNull(h.navigationNow) + 1_000)
            (h.routeIdentity() != null) shouldBe true // An observed READY session returns to work after a distant rescue.
        }
    }

    test("a carrying courier times out across repeated denied-door routes and unloads exactly once after safe recovery") {
        residentRuntime { h ->
            val cargo = carryingCourier(h)
            val npc = h.owner.collisionActors().single()
            npc.teleport(Location(h.world, .5, 64.0, .5, -90f, 0f))
            val messages = mutableListOf<String>()
            val handler = object : Handler() {
                override fun publish(record: LogRecord) { messages += record.message }
                override fun flush() = Unit
                override fun close() = Unit
            }
            h.logger.addHandler(handler)
            try {
                h.owner.pulse(2_000)
                for (tick in 1..2_400) {
                    h.navigate()
                    // Repeated native reads grow while the body stays still; they must not postpone the deadline.
                    h.routeMotion(h.workerId)?.takeIf { it.failure == "passage-blocked" }?.let { motion ->
                        motion.javaClass.getDeclaredField("capturedBlocks").run {
                            isAccessible = true
                            setInt(motion, 40_000 + tick)
                        }
                    }
                    if (tick % 20 == 0) {
                        h.owner.pulse(requireNotNull(h.navigationNow))
                        h.clearRecordedCalls()
                    }
                    if (h.unloads > 0) break
                }
                withClue("debug=${h.owner.debug(h.city.id, h.workerId)} recent=${messages.takeLast(6)}") {
                    (messages.count { "ARCCIVS_NAVIGATION_FAILED" in it && "passage-blocked" in it } >= 2) shouldBe true
                    messages.any { "blocked_waypoint=10.5,64.0,0.5" in it } shouldBe true
                    h.unloads shouldBe 1
                }
                h.city.workers.single().cargo shouldBe cargo
                h.pendingUnload.isDone shouldBe false
                h.deliveries shouldBe 0
                // Recovery itself cannot commit a transfer or replay a still-pending receipt.
                repeat(4) { h.owner.pulse(requireNotNull(h.navigationNow) + it * 1_000) }
                h.unloads shouldBe 1
                h.city = h.city.copy(revision = h.city.revision + 1,
                    workers = h.city.workers.map { it.copy(cargo = null, completedCycles = 1) })
                h.courier = null
                h.pendingUnload.complete(h.city)
                repeat(4) { h.owner.pulse(requireNotNull(h.navigationNow) + 5_000 + it * 1_000) }
                h.unloads shouldBe 1
                h.city.workers.single().completedCycles shouldBe 1
            } finally { h.logger.removeHandler(handler) }
        }
    }

    test("a courier making real physical progress is not teleported after sixty monotonic seconds") {
        residentRuntime { h ->
            carryingCourier(h)
            h.world.getBlockAt(10, 64, 0).type = Material.AIR
            val npc = h.owner.collisionActors().single()
            h.owner.pulse(2_000)
            for (tick in 1..1_000) {
                val from = npc.location
                h.navigate()
                h.recoveryMillis += 950 // Slow server frames: monotonic time advances independently of game ticks.
                (from.distance(npc.location) <= .172001) shouldBe true
                if (tick % 20 == 0) { h.owner.pulse(requireNotNull(h.navigationNow)); h.clearRecordedCalls() }
                if (h.unloads > 0) break
            }
            (h.recoveryMillis > 60_000L) shouldBe true
            h.unloads shouldBe 1
            h.pendingUnload.isDone shouldBe false
        }
    }

    test("an owned asynchronous planner is fenced after a safe arrival timeout and cannot replay a late result") {
        residentRuntime { h ->
            val cargo = carryingCourier(h)
            h.holdNavigation = true
            val npc = h.owner.collisionActors().single()
            val at = npc.location
            h.owner.pulse(2_000)
            for (tick in 1..100) { h.navigate(0); if (h.pendingNavigationWorkers > 0) break }
            h.pendingNavigationWorkers shouldBe 1
            val stale = requireNotNull(h.routeMotion(h.workerId))
            repeat(29) {
                h.navigate(2_000)
                h.owner.pulse(requireNotNull(h.navigationNow))
            }
            npc.location.toVector() shouldBe at.toVector()
            h.city.workers.single().cargo shouldBe cargo
            h.unloads shouldBe 0
            h.pendingNavigationWorkers shouldBe 1
            h.navigate(2_000)
            (npc.location.distance(at) > 8.0) shouldBe true
            stale.finished shouldBe true
            h.routeIdentity() shouldBe null
            h.unloads shouldBe 0
            val rescued = npc.location
            h.releaseNavigation()
            repeat(10) { h.navigate(0) }
            npc.location shouldBe rescued
            h.routeIdentity() shouldBe null
            h.owner.pulse(requireNotNull(h.navigationNow) + 1_000)
            h.owner.pulse(requireNotNull(h.navigationNow) + 2_000)
            h.unloads shouldBe 1
            h.city.workers.single().cargo shouldBe cargo
        }
    }

    test("a new cargo at the same endpoint starts a fresh deadline across paused return retries") {
        residentRuntime { h ->
            val old = carryingCourier(h)
            h.owner.pulse(2_000)
            fun step(ticks: Int) = repeat(ticks) { tick ->
                h.navigate()
                if (tick % 20 == 19) { h.owner.pulse(requireNotNull(h.navigationNow)); h.clearRecordedCalls() }
            }
            step(800)
            h.unloads shouldBe 0
            val next = old.copy(id = UUID(0, 108))
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(cargo = next) })
            h.courier = requireNotNull(h.courier).copy(cargo = next)
            h.owner.pulse(requireNotNull(h.navigationNow))
            step(800)
            h.unloads shouldBe 0 // Old goal has aged eighty seconds; this shipment has only aged forty.
            step(1_000)
            h.unloads shouldBe 1
            h.city.workers.single().cargo shouldBe next
        }
    }

    test("a road worker can time out at a denied passage without earning repair credit from the teleport") {
        residentRuntime { h ->
            val work = h.assignRoadUpkeep()
            h.navigationNow = 2_000
            for (x in -1..7) for (z in -1..1) for (y in 64..66)
                if (x == -1 || x == 7 || z != 0 || y == 66) h.world.getBlockAt(x, y, z).type = Material.STONE
            h.world.getBlockAt(3, 64, 0).blockData = Bukkit.createBlockData("minecraft:oak_fence_gate[facing=east,in_wall=false,open=false,powered=false]")
            h.passageLocked = true
            val npc = h.owner.collisionActors().single()
            npc.teleport(Location(h.world, .5, 64.0, .5, -90f, 0f))
            h.owner.pulse(2_000)
            val before = h.city
            var recovered = false
            for (tick in 1..2_000) {
                val from = npc.location
                h.navigate()
                if (from.distance(npc.location) > 1.0) { recovered = true; break }
                if (tick % 20 == 0) { h.owner.pulse(requireNotNull(h.navigationNow)); h.clearRecordedCalls() }
            }
            withClue(h.owner.debug(h.city.id, h.workerId)) { recovered shouldBe true }
            (h.recoveryMillis >= 60_000L) shouldBe true
            npc.location shouldBe Location(h.world, work.standing.x + .5, 64.0, work.standing.z + .5, -90f, 0f)
            h.deliveries shouldBe 0
            h.city shouldBe before
            h.owner.pulse(requireNotNull(h.navigationNow) + 1_000)
            h.phase() shouldBe CitizenPhase.WORKING
            h.deliveries shouldBe 0
        }
    }

    test("a working resident's timed food detour recovers at the food barrel rather than its workplace") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val hall = h.city.buildings.first().copy(control = CivPosition(0, 64, 0))
            h.world.getBlockAt(0, 64, 0).type = Material.BARREL
            h.city = h.city.copy(buildings = h.city.buildings.map { if (it.id == hall.id) hall else it })
            for (x in -2..3) for (z in -2..1) h.world.loadChunk(x, z)
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(22, 64, 0),
                listOf(CivPlanBlock(CivPosition(23, 63, 0), "minecraft:stone", "minecraft:air")),
                durationMillis = 120_000, status = "working-field", focus = CivPosition(23, 63, 0))
            val npc = h.owner.collisionActors().single()
            npc.teleport(Location(h.world, 22.5, 64.0, .5))
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            h.phase() shouldBe CitizenPhase.WORKING
            h.foodSource = hall.id
            h.holdNavigation = true
            h.owner.pulse(3_000)
            for (tick in 1..100) { h.navigate(0); if (h.pendingNavigationWorkers > 0) break }
            h.pendingNavigationWorkers shouldBe 1
            val before = h.city
            h.recoveryMillis = 60_000
            h.navigate(0)
            withClue(h.owner.debug(h.city.id, h.workerId)) {
                (npc.location.distance(Location(h.world, .5, 64.0, .5)) <= 2.0) shouldBe true
            }
            shouldThrow<CivsRejection> { h.owner.requireStorageArrival(h.city.id, h.workerId, hall.id) }.reason shouldBe "route-unreachable"
            h.city shouldBe before
            h.deliveries shouldBe 0
            h.supplyRequests shouldBe 0
            h.owner.pulse(4_000)
            h.supplyRequests shouldBe 1
            h.owner.requireStorageArrival(h.city.id, h.workerId, hall.id)
            h.pendingSupplies.isDone shouldBe false
            h.owner.pulse(5_000)
            h.supplyRequests shouldBe 1
            h.deliveries shouldBe 0
        }
    }

    test("timed idle recovery keeps its route anchor when the only free endpoint lies outside the idle area") {
        residentRuntime { h ->
            val target = CivPosition(6, 64, 3)
            val anchor = CivPosition(0, 64, 0)
            val hall = h.city.buildings.first().copy(anchor = CivPosition(12, 64, 3), entrance = target,
                bounds = CivBox(CivPosition(10, 64, 1), CivPosition(14, 68, 5)))
            val home = hall.copy(id = UUID(0, 111), type = BuildingType.HOUSE, anchor = anchor, entrance = anchor,
                bounds = CivBox(CivPosition(-2, 64, -2), CivPosition(2, 68, 2)))
            h.city = h.city.copy(buildings = listOf(hall, home), workers = h.city.workers.map { it.copy(homeId = home.id) })
            for (x in -2..2) for (z in -2..2) h.world.loadChunk(x, z)
            h.navigationNow = 2_000
            h.holdNavigation = true
            h.owner.pulse(2_000)
            startRecoveryRoute(h, target, anchor)
            for (tick in 1..100) { h.navigate(0); if (h.pendingNavigationWorkers > 0) break }
            h.pendingNavigationWorkers shouldBe 1
            // The captured route is now waiting off-thread. Fresh admission must reject
            // every local endpoint except (7,64,4), which is just outside the eight-block area.
            for (x in 5..7) for (z in 2..4) for (y in 64..66) h.world.getBlockAt(x, y, z).type = Material.STONE
            h.world.getBlockAt(7, 64, 4).type = Material.AIR
            h.world.getBlockAt(7, 65, 4).type = Material.AIR
            citizenSafeIdlePosition(h.world, anchor, CitizenPoint(7.5, 64.0, 4.5)) shouldBe false
            val npc = h.owner.collisionActors().single()
            val at = npc.location
            val before = h.city
            h.recoveryMillis = 60_000
            h.navigate(0)
            npc.location shouldBe at
            h.city shouldBe before
            h.deliveries shouldBe 0
        }
    }
})

private fun startRecoveryRoute(h: ResidentRuntime, target: CivPosition, anchor: CivPosition) {
    val actors = h.owner.javaClass.getDeclaredField("actors").run { isAccessible = true; get(h.owner) as Map<*, *> }
    val actor = requireNotNull(actors[h.workerId])
    h.owner.javaClass.declaredMethods.single { it.name == "cancelRoute" }.run {
        isAccessible = true; invoke(h.owner, actor, null)
    }
    h.owner.javaClass.declaredMethods.single { it.name == "startRoute" }.run {
        isAccessible = true; invoke(h.owner, actor, h.world, target, requireNotNull(h.navigationNow), anchor)
    }
}

private fun carryingCourier(h: ResidentRuntime): CivCargo {
    h.navigationNow = 2_000
    h.recoveryMillis = 0
    val warehouse = h.assignFarm().copy(type = BuildingType.WAREHOUSE, schematicId = "warehouse1", production = null)
    for (x in -2..3) for (z in -2..1) h.world.loadChunk(x, z)
    val source = h.city.buildings.first()
    val cargo = CivCargo(UUID(0, 106), warehouse.id, source.id, warehouse.id, source.entrance, "OAK_LOG", 8)
    h.city = CivsRules.validate(h.city.copy(buildings = listOf(source, warehouse), workers = h.city.workers.map { it.copy(cargo = cargo) }))
    h.courier = CivCourierPlan(cargo, warehouse.id, warehouse.entrance, true)
    for (x in -1..23) for (z in -1..1) for (y in 64..66)
        if (x == -1 || x == 23 || z != 0 || y == 66) h.world.getBlockAt(x, y, z).type = Material.STONE
    for (x in 18..22) for (z in listOf(-1, 1)) for (y in 64..65)
        h.world.getBlockAt(x, y, z).type = Material.AIR
    h.world.getBlockAt(10, 64, 0).blockData = Bukkit.createBlockData("minecraft:oak_fence_gate[facing=east,in_wall=false,open=false,powered=false]")
    h.passageLocked = true
    h.owner.collisionActors().single().teleport(Location(h.world, .5, 64.0, .5, -90f, 0f))
    return cargo
}

/** Portable corner translated to (0,64,0); explicit synthetic terrain and identities, not a native snapshot. */
private fun enclosedWallBuilder(h: ResidentRuntime, homeEntrance: CivPosition = CivPosition(18, 64, 9)): CivConstruction {
    for (x in -2..3) for (z in -2..2) h.world.loadChunk(x, z)
    val hall = h.city.buildings.first().copy(anchor = CivPosition(18, 64, 9),
        entrance = homeEntrance, control = null,
        bounds = CivBox(CivPosition(16, 64, 7), CivPosition(20, 68, 11)))
    val hut = hall.copy(id = UUID(0, 101), type = BuildingType.BUILDER_HUT, schematicId = "builder1",
        anchor = CivPosition(18, 64, -1), entrance = CivPosition(18, 64, -1),
        bounds = CivBox(CivPosition(16, 64, -3), CivPosition(20, 68, 1)))
    val wall = hall.copy(id = UUID(0, 102), type = BuildingType.WALL, schematicId = "wall1", phase = BuildingPhase.CONSTRUCTING,
        anchor = CivPosition(0, 64, -3), entrance = CivPosition(0, 64, 0),
        bounds = CivBox(CivPosition(-2, 64, -4), CivPosition(2, 68, -1)))
    val order = CivConstruction(UUID(0, 103), wall.id, 1, 195, 91,
        ConstructionPhase.PLACING, Stock(), plannedBuilding = wall, batches = CivConstructionBatches(195, 16), workCredit = 37)
    h.city = h.city.copy(center = CivPosition(0, 64, 0), buildings = listOf(hall, hut, wall),
        autonomy = CivAutonomy(), inventory = null, stock = Stock(96, 96, 24), construction = order,
        workers = h.city.workers.map { it.copy(assignment = hut.id, nextDeliveryAt = 1, hunger = 0, homeId = hall.id) })
    h.background = true
    h.navigationNow = 2_000
    h.recoveryMillis = 0
    h.owner.collisionActors().single().teleport(Location(h.world, .5, 64.0, .5))
    h.owner.pulse(2_000)
    for (at in 3_000L..6_000L step 1_000L) h.owner.pulse(at)
    h.navigationNow = 6_000
    h.phase() shouldBe CitizenPhase.READY
    return order
}
