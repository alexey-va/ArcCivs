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
    test("an unassigned resident escapes the pit when no idle step is available") {
        residentRuntime { h ->
            val origin = dryPit(h)
            val npc = h.actor().npc
            advancePitFixture(h) { npc.location.y > origin.y }
            npc.location.shouldBePitRim(origin)
        }
    }

    for (protectedPit in listOf(true, false))
    test(if (protectedPit) "a failed work route exits a protected pit and resumes observed work before delivery"
        else "a road worker climbs out on a hook before walking to work and earning credit") {
        residentRuntime { h ->
            val work = h.assignRoadUpkeep()
            val origin = if (protectedPit) {
                // The town hall permits ordinary departure, but its walls are not public hook anchors.
                val hall = h.city.buildings.first()
                h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings.map {
                    if (it.id == hall.id) it.copy(bounds = CivBox(
                        it.bounds.min.copy(y = 61), it.bounds.max.copy(z = 5))) else it
                }))
                dryPit(h, x = 0)
            } else dryPit(h)
            val npc = h.actor().npc
            if (protectedPit) {
                var attempted: CitizenPacketRoute? = null
                advancePitFixture(h) {
                    attempted = h.routeMotion(h.workerId) ?: attempted
                    npc.location.y > origin.y
                }
                requireNotNull(attempted).failure shouldBe "no-path"
                npc.location.shouldBePitRim(origin)
            } else climbFromPit(h, origin) {
                h.observedWork() shouldBe 0L
                h.deliveries shouldBe 0
            }
            h.observedWork() shouldBe 0L
            h.deliveries shouldBe 0

            advancePitFixture(h) { h.deliveries > 0 }
            (h.observedWork() >= work.durationMillis) shouldBe true
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)
            pulseWhileReceiptPending(h)
            h.deliveries shouldBe 1
        }
    }

    test("a carrying courier unloads the original cargo only after walking from the pit rim") {
        residentRuntime { h ->
            val warehouse = h.assignFarm().copy(type = BuildingType.WAREHOUSE, schematicId = "warehouse1", production = null)
            val hall = h.city.buildings.first()
            val cargo = CivCargo(UUID(0, 106), warehouse.id, hall.id, warehouse.id, hall.entrance, "OAK_LOG", 8,
                inventoryBacked = true)
            val loaded = CivsEquipment.collectSupplies(h.city.workers.single(), mapOf(cargo.material to cargo.amount)).copy(cargo = cargo)
            h.city = CivsRules.validate(h.city.copy(buildings = listOf(hall, warehouse), workers = listOf(loaded)))
            val trip = CivCourierPlan(cargo, warehouse.id, warehouse.entrance, true)
            h.courier = trip
            val origin = dryPit(h)
            val npc = h.actor().npc

            climbFromPit(h, origin) { h.unloads shouldBe 0 }
            h.actor().plan.courier shouldBe trip

            advancePitFixture(h) { h.unloads > 0 }
            citizenCanReachBlock(npc, requireNotNull(warehouse.control)) shouldBe true
            pulseWhileReceiptPending(h)
            h.unloads shouldBe 1
            h.city.workers.single() shouldBe loaded
        }
    }

    test("a hungry worker reaches the same bread barrel on foot after leaving the pit") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val hall = h.city.buildings.first().copy(control = CivPosition(0, 64, 0))
            h.world.getBlockAt(0, 64, 0).type = Material.BARREL
            h.city = h.city.copy(buildings = listOf(hall, farm), workers = h.city.workers.map { it.copy(hunger = 10) })
            val work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(22, 64, 0),
                listOf(CivPlanBlock(CivPosition(23, 63, 0), "minecraft:stone", "minecraft:air")),
                durationMillis = 120_000, status = "working-field", focus = CivPosition(23, 63, 0))
            h.work = work
            val npc = h.actor().npc
            npc.teleport(work.standing.location(h.world))
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            h.phase() shouldBe CitizenPhase.WORKING
            val origin = dryPit(h)
            h.navigationNow = 3_000
            val source = CivResidentFoodSource(hall.id, requireNotNull(hall.control), CivPosition(1, 64, 0))
            h.physicalFoodSource = source
            val pickups = mutableListOf<CivResidentFoodSource>()
            h.physicalFoodPickup = {
                civsCanReachContainer(npc.location, it.container, true) shouldBe true
                pickups += it
                h.pendingSupplies
            }

            climbFromPit(h, origin) { pickups shouldBe emptyList() }
            h.actor().foodPickupSource?.container shouldBe source.container
            h.actor().workDetour shouldBe h.actor().plan.token

            advancePitFixture(h) { pickups.isNotEmpty() }
            pulseWhileReceiptPending(h)
            pickups.single().copy(standing = source.standing) shouldBe source
            CivsResidents.hunger(h.city.workers.single()) shouldBe 10
        }
    }

    for (condition in listOf("ladder", "walking-step", "unloaded", "occupied-rim"))
    test("dry pit recovery leaves $condition to ordinary movement") {
        residentRuntime { h ->
            val origin = dryPit(h, x = if (condition == "unloaded") 15 else 6)
            when (condition) {
                "ladder" -> for (y in 61..64) h.world.getBlockAt(origin.blockX, y, origin.blockZ).type = Material.LADDER
                "walking-step" -> for (step in 1..2) for (y in 61 + step..63)
                    h.world.getBlockAt(origin.blockX + step, y, origin.blockZ).type = Material.AIR
                "unloaded" -> h.world.unloadChunk(1, 0)
                "occupied-rim" -> {
                    // Only the occupied eastern lip is safe ground.
                    for (dx in -1..1) for (dz in -1..1) if (dz != 0 || dx == -1)
                        h.world.getBlockAt(origin.blockX + dx, 63, origin.blockZ + dz).type = Material.MAGMA_BLOCK
                    h.playerAt(origin.clone().add(1.0, 3.0, 0.0))
                }
            }
            val npc = h.actor().npc
            var previousY = origin.y
            advancePitFixture(h) {
                val y = npc.location.y
                (y - previousY < 2.0) shouldBe true
                previousY = y
                false
            }
            if (condition == "unloaded") h.world.isChunkLoaded(1, 0) shouldBe false
            if (condition == "unloaded" || condition == "occupied-rim")
                npc.location.toVector() shouldBe origin.toVector()
        }
    }

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

    test("a courier that has walked away from a body contact can recover from a separate stalled passage") {
        residentRuntime { h ->
            val cargo = carryingCourier(h)
            val npc = h.owner.collisionActors().single()
            val player = h.playerAt(Location(h.world, 3.5, 64.0, .5))
            h.nativeCollisions().use { collisions ->
                var denials = 0
                h.owner.residentStepAllowed = { actor, from, to ->
                    collisions.canResidentMove(actor, from, to).also { if (!it && from != to) denials++ }
                }
                h.owner.pulse(2_000)
                for (tick in 1..250) {
                    h.navigate()
                    if (denials > 0) break
                }
                (denials > 0) shouldBe true
                val contact = npc.location
                h.unloads shouldBe 0
                player.teleport(Location(h.world, 3.5, 64.0, 3.5))
                for (tick in 1..2_000) {
                    h.navigate()
                    if (tick % 20 == 0) {
                        h.owner.pulse(requireNotNull(h.navigationNow))
                        h.clearRecordedCalls()
                    }
                    if (h.unloads > 0) break
                    if (tick <= 800) (npc.location.x < 11.0) shouldBe true
                }
                withClue("contact=$contact feet=${npc.location} ${h.navigationState()}") {
                    h.unloads shouldBe 1
                }
                h.pendingUnload.isDone shouldBe false
                h.city.workers.single().cargo shouldBe cargo
                h.deliveries shouldBe 0
                h.world.getBlockAt(10, 64, 0).type shouldBe Material.OAK_FENCE_GATE
            }
        }
    }

    test("a courier making real physical progress is not teleported after sixty monotonic seconds") {
        residentRuntime { h ->
            val cargo = carryingCourier(h)
            h.world.getBlockAt(10, 64, 0).type = Material.AIR
            val actor = h.actor()
            val npc = actor.npc
            h.owner.pulse(2_000)
            var walkingStartedAt: Long? = null
            for (tick in 1..1_000) {
                val from = npc.location
                h.navigate()
                val step = from.distance(npc.location)
                if (walkingStartedAt == null && step > 0.0 && (actor.route?.motion?.forwardProgress ?: 0.0) > 0.0)
                    walkingStartedAt = h.recoveryMillis
                // Simulate slow frames once navigation has accepted a real step.
                if (walkingStartedAt != null) h.recoveryMillis += 950
                if (tick % 20 == 0) { h.owner.pulse(requireNotNull(h.navigationNow)); h.clearRecordedCalls() }
                withClue("tick=$tick recovery=${h.recoveryMillis} from=${from.point()} feet=${npc.location.point()}") {
                    (step <= .172001 && from.distance(npc.location) <= .172001) shouldBe true
                }
                if (h.unloads > 0) break
            }
            (h.recoveryMillis - requireNotNull(walkingStartedAt) > 60_000L) shouldBe true
            h.unloads shouldBe 1
            h.city.workers.single().cargo shouldBe cargo
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

    test("a player who enters the origin during route preparation prevents timed recovery while overlapping") {
        residentRuntime { h ->
            val cargo = carryingCourier(h)
            val npc = h.owner.collisionActors().single()
            val at = npc.location
            h.holdNavigation = true
            h.owner.pulse(2_000)
            for (tick in 1..100) { h.navigate(0); if (h.pendingNavigationWorkers > 0) break }
            h.pendingNavigationWorkers shouldBe 1
            val player = h.playerAt(at)
            h.nativeCollisions().use { collisions ->
                h.owner.residentStepAllowed = collisions::canResidentMove
                h.navigate(60_000)
                npc.location shouldBe at
                player.location shouldBe at
                h.city.workers.single().cargo shouldBe cargo
                h.unloads shouldBe 0
                player.teleport(at.clone().add(0.0, 0.0, 3.0))
                h.owner.pulse(requireNotNull(h.navigationNow) + 1_000)
                h.navigate(0)
                (npc.location.distance(at) > 8.0) shouldBe true
                h.unloads shouldBe 0
                h.city.workers.single().cargo shouldBe cargo
            }
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

private fun dryPit(h: ResidentRuntime, x: Int = 6): Location {
    h.navigationNow = 2_000
    h.world.time = 1_000
    h.owner.residentLifeEnabled = false
    val feet = Location(h.world, x + .5, 61.0, 4.5)
    for (y in 61..63) h.world.getBlockAt(x, y, 4).type = Material.AIR
    val npc = h.owner.collisionActors().single()
    npc.teleport(feet)
    citizenPacketSupported(h.world, feet.point(), allowClimbable = false) shouldBe true
    citizenResidentEscapeStep(npc) { true } shouldBe null
    return feet
}

private fun Location.shouldBePitRim(origin: Location) {
    y shouldBe origin.y + 3.0
    (distanceSquared(origin) <= 11.0) shouldBe true
    citizenPacketSupported(requireNotNull(world), point(), allowClimbable = false) shouldBe true
}

private fun climbFromPit(h: ResidentRuntime, origin: Location, beforeArrival: () -> Unit) {
    val actor = h.actor()
    var previous = origin
    var pulled = false
    advancePitFixture(h) {
        beforeArrival()
        val at = actor.npc.location
        (at.distanceSquared(previous) <= .15 * .15) shouldBe true
        if (actor.traversal == CitizenRouteAction.GRAPPLE && at.y > previous.y) pulled = true
        previous = at
        pulled && actor.traversal == null && at.y >= origin.y + 3.0
    }
    pulled shouldBe true
    actor.traversal shouldBe null
    actor.npc.location.shouldBePitRim(origin)
}

private fun pulseWhileReceiptPending(h: ResidentRuntime) {
    repeat(2) {
        h.navigate(1_000)
        h.owner.pulse(requireNotNull(h.navigationNow))
    }
}

private fun advancePitFixture(h: ResidentRuntime, done: () -> Boolean) {
    h.owner.pulse(requireNotNull(h.navigationNow))
    for (tick in 1..240) {
        if (done()) return
        h.navigate()
        if (tick % 20 == 0) {
            h.owner.pulse(requireNotNull(h.navigationNow))
            h.clearRecordedCalls()
        }
    }
    done()
}

private fun startRecoveryRoute(h: ResidentRuntime, target: CivPosition, anchor: CivPosition) {
    val actors = h.owner.javaClass.getDeclaredField("actors").run { isAccessible = true; get(h.owner) as Map<*, *> }
    val actor = requireNotNull(actors[h.workerId]) as CitizenActor
    val routes = h.owner.javaClass.getDeclaredField("routes").run { isAccessible = true; get(h.owner) as CivsCitizenRoutes }
    routes.cancelRoute(actor)
    routes.startRoute(actor, h.world, target, requireNotNull(h.navigationNow), anchor)
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
