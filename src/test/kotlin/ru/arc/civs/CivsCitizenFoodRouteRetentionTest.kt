package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.util.BoundingBox
import org.bukkit.util.VoxelShape
import org.mockbukkit.mockbukkit.block.BlockMock
import org.mockbukkit.mockbukkit.world.Coordinate
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsCitizenFoodRouteRetentionTest : FunSpec({
    test("repeated physical food routes recover past a native door leaf before one ordinary pickup") {
        residentRuntime(workerId = UUID(0, 20), worldFactory = ::FoodDoorWorld) { h ->
            for (x in -3..2) for (z in -2..2) h.world.loadChunk(x, z)
            val barrel = CivPosition(8, 64, 0)
            val standing = CivPosition(9, 64, 0)
            val tavern = CivBuilding(UUID.randomUUID(), BuildingType.TAVERN, 1, barrel, 0, "food-door-fixture",
                CivBox(CivPosition(7, 63, -1), CivPosition(13, 68, 3)), standing, BuildingPhase.ACTIVE, control = barrel)
            val quarry = h.assignQuarry().copy(anchor = CivPosition(-20, 64, 0),
                entrance = CivPosition(-20, 64, 0), control = CivPosition(-20, 64, 0),
                bounds = CivBox(CivPosition(-22, 64, -2), CivPosition(-18, 68, 2)))
            h.city = h.city.copy(buildings = h.city.buildings.map { if (it.id == quarry.id) quarry else it } + tavern,
                workers = h.city.workers.map { it.copy(hunger = 0) })
            h.world.getBlockAt(-20, 64, 0).type = Material.BARREL
            h.work = CivWorldWork(h.workerId, 0, quarry.id, CivPosition(-19, 64, 0), emptyList(),
                durationMillis = 60_000, status = "mining")
            h.world.getBlockAt(barrel.x, barrel.y, barrel.z).type = Material.BARREL
            // The 2026-11-09 Auto4 capture translated to the fixture. Keep the open leaf's
            // real collision plane, the east barrel stance and the neighbouring obstructions.
            for ((x, z) in listOf(7 to 0, 8 to -1, 8 to 1, 9 to -1, 10 to -1, 11 to -1,
                11 to 0, 11 to 2, 12 to 1)) for (y in 64..65) h.world.getBlockAt(x, y, z).type = Material.STONE
            for ((y, half) in listOf(64 to "lower", 65 to "upper")) {
                h.world.getBlockAt(11, y, 1).blockData =
                    Bukkit.createBlockData("minecraft:iron_door[facing=north,half=$half,hinge=left,open=true,powered=false]")
            }
            val source = CivResidentFoodSource(tavern.id, barrel, standing)
            val npc = h.owner.collisionActors().single()
            npc.teleport(requireNotNull(h.work).standing.location(h.world))
            h.owner.pulse(1_500)
            h.phase() shouldBe CitizenPhase.WORKING
            h.physicalFoodSource = source
            val blockedFeet = Location(h.world, 11.521774978872, 64.0, 1.301188908015)
            npc.teleport(blockedFeet)
            citizenPacketBodyClear(h.world, blockedFeet.point(), false) shouldBe true
            citizenPacketStepClear(h.world, blockedFeet.point(), blockedFeet.clone().add(-.1, 0.0, 0.0).point(), false) shouldBe false
            civsCanReachContainer(blockedFeet, barrel) shouldBe false
            civsCanReachContainer(standing.location(h.world), barrel) shouldBe true
            val receipt = CompletableFuture<Settlement>()
            val pickups = mutableListOf<CivResidentFoodSource>()
            h.physicalFoodPickup = { selected ->
                h.owner.requireStorageArrival(h.city.id, h.workerId, tavern.id)
                civsCanReachContainer(npc.location, barrel) shouldBe true
                pickups += selected
                receipt
            }
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            h.owner.collisionActors().single() shouldBe npc
            val failures = mutableMapOf<CitizenRoute, String>()
            val permittedPoints = mutableSetOf<CivPosition>()
            var holderDenied = true
            h.foodRecoveryAllowed = { point ->
                permittedPoints += point
                !holderDenied || point != barrel
            }
            val before = h.city
            for (tick in 1..2_000) {
                h.navigate()
                val route = h.routeIdentity() as? CitizenRoute
                if (route?.authored == standing) route.completion?.cancelReason?.let { failures[route] = it }
                if (tick % 10 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                if (tick == 1_500) {
                    withClue("terminal food failures=${failures.values.groupingBy { it }.eachCount()}") {
                        (failures.values.count { it == "movement-blocked" || it == "no-path" } >= 2) shouldBe true
                        (barrel in permittedPoints) shouldBe true
                    }
                    npc.location.point() shouldBe blockedFeet.point()
                    pickups shouldBe emptyList()
                    holderDenied = false
                }
                if (civsCanReachContainer(npc.location, barrel)) break
            }
            holderDenied shouldBe false
            (standing in permittedPoints && standing.copy(y = standing.y - 1) in permittedPoints) shouldBe true
            withClue("failures=${failures.size} feet=${npc.location} ${h.navigationState()}") {
                npc.location.point() shouldBe standing.point()
            }
            pickups shouldBe emptyList()
            h.city shouldBe before
            h.owner.pulse(requireNotNull(h.navigationNow) + 1_000)
            pickups shouldBe listOf(source)
            h.owner.pulse(requireNotNull(h.navigationNow) + 2_000)
            pickups shouldBe listOf(source)
            h.observedWork() shouldBe 0L
        }
    }

    test("a stationary food route may exhaust two captures before one ordinary pickup") {
        residentRuntime(workerId = UUID(0, 20)) { h ->
            for (x in -3..2) for (z in -3..2) h.world.loadChunk(x, z)
            val quarry = h.assignQuarry()
            h.work = CivWorldWork(h.workerId, 0, quarry.id, CivPosition(21, 64, 0), emptyList(),
                inputs = mapOf("IRON_PICKAXE" to 1L), durationMillis = 1_000, status = "waiting-tool")
            val barrel = CivPosition(13, 64, 0)
            val standing = CivPosition(12, 64, 0)
            val tavern = CivBuilding(UUID.randomUUID(), BuildingType.TAVERN, 1, barrel, 0, "food-budget-fixture",
                CivBox(CivPosition(12, 63, -2), CivPosition(16, 68, 2)), standing, BuildingPhase.ACTIVE, control = barrel)
            h.city = h.city.copy(buildings = h.city.buildings + tavern, workers = h.city.workers.map { it.copy(hunger = 0) })
            h.world.getBlockAt(barrel.x, barrel.y, barrel.z).type = Material.BARREL
            for (z in -24..24) for (y in 64..66) h.world.getBlockAt(11, y, z).type = Material.STONE
            for ((x, z) in listOf(13 to -1, 13 to 1, 14 to 0)) for (y in 64..66)
                h.world.getBlockAt(x, y, z).type = Material.STONE
            val source = CivResidentFoodSource(tavern.id, barrel, standing)
            val npc = h.owner.collisionActors().single()
            val origin = Location(h.world, 10.5, 64.0, .5)
            npc.teleport(origin)
            civsCanReachContainer(origin, barrel) shouldBe false
            civsCanReachContainer(standing.location(h.world), barrel) shouldBe true
            h.physicalFoodSource = source
            h.foodRecoveryAllowed = { true }
            h.holdNavigation = true
            val receipt = CompletableFuture<Settlement>()
            val pickups = mutableListOf<CivResidentFoodSource>()
            h.physicalFoodPickup = { selected ->
                h.owner.requireStorageArrival(h.city.id, h.workerId, tavern.id)
                civsCanReachContainer(npc.location, barrel) shouldBe true
                pickups += selected
                receipt
            }
            fun pulse(at: Long) {
                h.navigationNow = at
                h.recoveryMillis = at
                h.owner.pulse(at)
            }
            fun failCapture(at: Long) {
                val route = requireNotNull(h.actor().route)
                route.authored shouldBe standing
                val settings = CivsNavigationSettings(maximumCapturedBlocks = 256)
                val motion = CitizenPacketRoute(h.world, npc.location, route.target,
                    { true }, { _, _ -> true }, { 1.0 }, settings = settings)
                repeat(4_096) { if (motion.searching) motion.expand() }
                withClue("phase=${motion.preparationPhase} nodes=${motion.nodeCount} captured=${motion.capturedBlocks}") {
                    motion.failure shouldBe "capture-budget"
                    motion.capturedBlocks shouldBe settings.maximumCapturedBlocks
                    motion.continuation shouldBe false
                }
                route.motion.cancel()
                route.motion = motion
                h.navigationNow = at
                h.recoveryMillis = at
                h.navigate(0)
                route.completion?.cancelReason shouldBe "capture-budget"
                pulse(at)
            }
            val before = h.city
            pulse(2_000)
            failCapture(10_000)
            npc.location.point() shouldBe origin.point()
            pickups shouldBe emptyList()
            pulse(16_000)
            // The ordinary retry starts promptly but spends over 30 seconds preparing.
            failCapture(82_000)
            npc.location.point() shouldBe standing.point()
            pickups shouldBe emptyList()
            h.city shouldBe before
            pulse(83_000)
            pulse(84_000)
            pickups shouldBe listOf(source)
            h.city shouldBe before
        }
    }

    test("physical food pickup waits for body transit to finish and its accepted receipt survives later player occupancy") {
        residentRuntime(workerId = UUID(0, 20)) { h ->
            for (x in -2..3) for (z in -2..2) h.world.loadChunk(x, z)
            val originalQuarry = h.assignQuarry()
            val quarry = originalQuarry.copy(anchor = CivPosition(20, 64, 10), entrance = CivPosition(20, 64, 10),
                control = CivPosition(20, 64, 10), bounds = CivBox(CivPosition(18, 64, 8), CivPosition(26, 68, 12)))
            h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings.map {
                if (it.id == quarry.id) quarry else it
            }))
            // Keep the ordinary work control valid and outside the tested corridor, which contains only the food barrel.
            h.world.getBlockAt(20, 64, 0).type = Material.AIR
            h.world.getBlockAt(20, 64, 10).type = Material.BARREL
            val hall = h.city.buildings.first { it.type == BuildingType.TOWN_HALL }.copy(control = CivPosition(0, 64, 0))
            h.world.getBlockAt(0, 64, 0).type = Material.BARREL
            h.city = h.city.copy(buildings = h.city.buildings.map { if (it.id == hall.id) hall else it },
                workers = h.city.workers.map { it.copy(hunger = 12) })
            h.work = CivWorldWork(h.workerId, 0, quarry.id, CivPosition(21, 64, 0), emptyList(),
                durationMillis = 60_000, status = "mining")
            for (x in -96..96) for (z in listOf(-1, 1)) for (y in 64..66)
                h.world.getBlockAt(x, y, z).type = Material.STONE
            val food = CivResidentFoodSource(hall.id, requireNotNull(hall.control), CivPosition(1, 64, 0))
            h.physicalFoodSource = food
            val npc = h.owner.collisionActors().single()
            npc.teleport(Location(h.world, 21.5, 64.0, .5))
            // Contact straddles the container reach boundary, exercising both navigation and coarse food fastpaths.
            val player = h.playerAt(Location(h.world, 3.8, 64.0, .5))
            val receipt = CompletableFuture<Settlement>()
            var pickups = 0
            fun overlapping(): Boolean {
                val at = npc.location
                return BoundingBox(at.x - .3, at.y, at.z - .3, at.x + .3, at.y + 1.8, at.z + .3)
                    .overlaps(player.boundingBox)
            }
            h.physicalFoodPickup = { selected ->
                selected shouldBe food
                civsCanReachContainer(npc.location, food.container) shouldBe true
                overlapping() shouldBe false
                pickups++
                receipt
            }
            h.nativeCollisions().use { collisions ->
                h.owner.residentStepAllowed = collisions::canResidentMove
                h.navigationNow = 2_000
                h.owner.pulse(2_000)
                val actor = h.owner.javaClass.getDeclaredField("actors").run {
                    isAccessible = true
                    requireNotNull((get(h.owner) as Map<*, *>)[h.workerId]) as CitizenActor
                }
                actor.plan.idleReason shouldBe null
                val canonical = h.city
                var contact = false
                var reachedWhileOverlapping = false
                for (tick in 1..1_000) {
                    val previous = npc.location
                    h.navigate()
                    if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                    withClue("tick=$tick feet=${npc.location} ${h.navigationState()}") {
                        (previous.distance(npc.location) <= .215001) shouldBe true
                        citizenPacketSupported(h.world, CitizenPoint(npc.location.x, npc.location.y, npc.location.z)) shouldBe true
                        if (overlapping()) {
                            contact = true
                            reachedWhileOverlapping = reachedWhileOverlapping || civsCanReachContainer(npc.location, food.container)
                            h.owner.passingBodies(npc) shouldBe true
                            actor.supplyPickup shouldBe null
                            pickups shouldBe 0
                        }
                    }
                    if (pickups > 0) break
                }
                withClue("feet=${npc.location} ${h.navigationState()}") { pickups shouldBe 1 }
                contact shouldBe true
                reachedWhileOverlapping shouldBe true
                overlapping() shouldBe false
                val accepted = requireNotNull(actor.supplyPickup)
                val acceptedAt = npc.location
                player.teleport(acceptedAt)
                repeat(3) {
                    h.owner.pulse(requireNotNull(h.navigationNow) + (it + 1) * 1_000)
                    npc.location shouldBe acceptedAt
                    (actor.supplyPickup === accepted) shouldBe true
                    pickups shouldBe 1
                }
                h.city shouldBe canonical
                h.deliveries shouldBe 0
                h.pickups shouldBe 0
                h.unloads shouldBe 0
                h.observedWork() shouldBe 0L
            }
        }
    }

    test("an idle quarry food route survives work cache changes until its physical pickup receipt") {
        residentRuntime(workerId = UUID(0, 20)) { h ->
            for (x in -2..3) for (z in -2..2) h.world.loadChunk(x, z)
            val quarry = h.assignQuarry()
            val hall = h.city.buildings.first { it.type == BuildingType.TOWN_HALL }.copy(control = CivPosition(0, 64, 0))
            h.world.getBlockAt(0, 64, 0).type = Material.BARREL
            h.city = h.city.copy(buildings = h.city.buildings.map { if (it.id == hall.id) hall else it },
                workers = h.city.workers.map { it.copy(hunger = 12) })
            val waiting = CivWorldWork(h.workerId, 0, quarry.id, CivPosition(21, 64, 0), emptyList(),
                inputs = mapOf("IRON_PICKAXE" to 1L), status = "waiting-tool", durationMillis = 1_000)
            h.work = waiting
            val food = CivResidentFoodSource(hall.id, requireNotNull(hall.control), CivPosition(1, 64, 0))
            val foodReceipt = CompletableFuture<Settlement>()
            var foodPickups = 0
            h.physicalFoodSource = food
            h.physicalFoodPickup = { selected ->
                selected shouldBe food
                h.owner.requireStorageArrival(h.city.id, h.workerId, hall.id)
                foodPickups++
                foodReceipt
            }
            val npc = h.owner.collisionActors().single()
            npc.teleport(Location(h.world, 21.5, 64.0, .5))
            h.holdNavigation = true
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            val actor = h.owner.javaClass.getDeclaredField("actors").run {
                isAccessible = true
                requireNotNull((get(h.owner) as Map<*, *>)[h.workerId]) as CitizenActor
            }
            h.phase() shouldBe CitizenPhase.IDLE
            actor.workDetour shouldBe null // IDLE did not earn an accepted production interval.
            actor.foodSource shouldBe hall.id
            val plan = actor.plan
            val cycle = actor.cycle
            val route = requireNotNull(h.routeIdentity())
            val searches = h.workPlans
            val position = npc.location
            for (pulse in 1..6) {
                h.work = if (pulse % 2 == 0) waiting.copy(standing = CivPosition(22 + pulse, 64, 0)) else null
                h.idleReason = "searching-work"
                h.navigationNow = 2_000L + pulse * 1_000
                h.owner.pulse(requireNotNull(h.navigationNow))
                withClue("food route replaced at pulse=$pulse ${h.navigationState()}") {
                    (h.routeIdentity() === route) shouldBe true
                    (actor.cycle === cycle) shouldBe true
                    actor.plan shouldBe plan
                }
                h.phase() shouldBe CitizenPhase.IDLE
                h.workPlans shouldBe searches
                npc.location shouldBe position
                foodPickups shouldBe 0
                h.owner.status(h.workerId) shouldBe "workers.status.route-searching"
            }
            // The ordinary arrival gate admits exactly one native pickup; planning waits for its receipt.
            npc.teleport(Location(h.world, 1.5, 64.0, .5))
            h.owner.pulse(9_000)
            foodPickups shouldBe 1
            actor.supplyPickup?.returnOutputs shouldBe false
            h.workPlans shouldBe searches
            h.owner.pulse(10_000)
            foodPickups shouldBe 1
            h.workPlans shouldBe searches
            val before = h.city.workers.single()
            h.city = h.city.copy(workers = listOf(CivsEquipment.collectSupplies(before, mapOf("BREAD" to 2L))))
            h.physicalFoodSource = null
            foodReceipt.complete(h.city)
            h.owner.pulse(11_000)
            actor.foodSource shouldBe null
            actor.supplyPickup shouldBe null
            actor.plan.worldWork shouldBe h.work
            (h.workPlans > searches) shouldBe true
            (h.routeIdentity() === route) shouldBe false
            CivsEquipment.usableCount(h.city.workers.single(), "BREAD") shouldBe 2
            h.city.workers.single().completedCycles shouldBe before.completedCycles
            h.city.workers.single().nextDeliveryAt shouldBe before.nextDeliveryAt
            h.deliveries shouldBe 0
            h.pickups shouldBe 0
            h.unloads shouldBe 0
            h.observedWork() shouldBe 0L
        }
    }
})

private class FoodDoorWorld : ResidentRuntimeWorld() {
    private val openDoors = mutableMapOf<Coordinate, BlockMock>()

    override fun getBlockAt(point: Coordinate): BlockMock {
        val block = super.getBlockAt(point)
        if (block.type != Material.IRON_DOOR) return block
        return openDoors.getOrPut(point) {
            val panel = BoundingBox(0.0, 0.0, 0.0, .1875, 1.0, 1.0)
            object : BlockMock(Material.IRON_DOOR, block.location) {
                override fun isPassable(): Boolean = false
                override fun getBoundingBox(): BoundingBox = panel.clone().shift(location)
                override fun getCollisionShape(): VoxelShape = object : VoxelShape {
                    override fun getBoundingBoxes(): Collection<BoundingBox> = listOf(panel)
                    override fun overlaps(other: BoundingBox): Boolean = panel.overlaps(other)
                }
            }.also { it.blockData = block.blockData.clone() }
        }
    }
}
