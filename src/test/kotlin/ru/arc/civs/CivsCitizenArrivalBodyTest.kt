package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsCitizenArrivalBodyTest : FunSpec({
    for ((case, distance, blockerOffset) in listOf(Triple("centre", .73821356237, 0.0),
        Triple("offset", .9703200338972238, -.282320033895)))
    test("a builder reaches a clear adjacent stance when its full step meets the arrived builder case=$case") {
        val blockedId = UUID.fromString("462109b1-0313-4d22-962e-97c52d63a826")
        val blockerId = UUID.fromString("eab4f712-162d-440f-baa7-cc7bdcf0fbc2")
        residentRuntime(workerId = blockedId) { h ->
            val hut = h.assignFarm().copy(type = BuildingType.BUILDER_HUT, level = 2,
                schematicId = "builder2", production = null)
            val house = hut.copy(id = UUID.randomUUID(), type = BuildingType.HOUSE, level = 1,
                phase = BuildingPhase.CONSTRUCTING, anchor = CivPosition(30, 64, 0),
                entrance = CivPosition(30, 64, 0), control = CivPosition(30, 64, 0),
                bounds = CivBox(CivPosition(28, 64, -2), CivPosition(32, 68, 2)))
            val order = CivConstruction(UUID.randomUUID(), house.id, 1, 4, 0, ConstructionPhase.PLACING,
                Stock(), plannedBuilding = house, batches = CivConstructionBatches(4, 16), workCredit = 1,
                materials = emptyMap(), payment = CivConstructionPayment(reservedBatch = 0))
            for (x in -1..2) for (z in -2..1) h.world.loadChunk(x, z)
            h.city = CivsRules.validate(h.city.copy(buildings = listOf(h.city.buildings.first().copy(level = 2), hut, house),
                construction = order, workers = listOf(h.city.workers.single().copy(assignment = hut.id,
                    completedCycles = if (case == "centre") 156 else 161, nextDeliveryAt = 1),
                    CivWorker(blockerId, "OtherBuilder", hut.id, completedCycles = 681, nextDeliveryAt = 1))))
            if (case == "offset") {
                // Fresh .293 native selection: north grass and current full floors are clear; the south approach has a barrel.
                val stance = h.constructionTarget()
                for (x in stance.x - 1..stance.x + 1) {
                    h.world.getBlockAt(x, stance.y - 1, stance.z - 1).type = Material.GRASS_BLOCK
                    h.world.getBlockAt(x, stance.y - 1, stance.z).type =
                        if (x < stance.x + 1) Material.COARSE_DIRT else Material.GRASS_BLOCK
                }
                h.world.getBlockAt(stance.x - 1, stance.y, stance.z + 1).type = Material.BARREL
                h.world.getBlockAt(stance.x, stance.y - 1, stance.z + 1).type = Material.DIRT_PATH
            }
            h.world.time = 1_000
            if (case == "offset") h.recoveryMillis = 1_000
            h.holdNavigation = true
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            val actor = h.actor(blockedId)
            val other = h.actor(blockerId)
            val routes = h.owner.javaClass.getDeclaredField("routes").run {
                isAccessible = true; get(h.owner) as CivsCitizenRoutes
            }
            val collisions = h.owner.javaClass.getDeclaredField("collisions").run {
                isAccessible = true; get(h.owner) as CivsCitizenCollisions
            }
            val goal = h.constructionTarget()
            // Auto1's observed offset is translated to the fixture's validated exterior stance.
            val from = Location(h.world, goal.x + .5 - distance, goal.y.toDouble(), goal.z + .5).also { it.yaw = 270f }
            val occupied = Location(h.world, goal.x + .5 + blockerOffset, goal.y.toDouble(), goal.z + .5).also { it.yaw = 270f }
            routes.cancelRoute(actor)
            routes.cancelRoute(other)
            actor.npc.teleport(from)
            other.npc.teleport(occupied)
            repeat(12) { h.owner.pulseFieldWork(2_250L + it * 250) }
            other.cycle.phase shouldBe CitizenPhase.READY
            actor.cycle.phase shouldBe CitizenPhase.TO_WORK
            routes.cancelRoute(actor)
            h.navigationNow = 6_000
            routes.startRoute(actor, h.world, goal, 6_000) shouldBe true
            h.releaseNavigation()
            h.nativeCollisions().use { native ->
                h.owner.residentStepAllowed = native::canResidentMove
                repeat(160) { if (actor.route?.motion?.waitingForCollision != true) h.navigate() }
                val route = requireNotNull(actor.route)
                route.motion.searching shouldBe false
                route.motion.waitingForCollision shouldBe true
                val actual = actor.npc.location
                val proposals = mutableListOf<Location>()
                route.motion.move(actual, .8 * h.surfaceMultiplier, { start, end ->
                    proposals += end.clone()
                    collisions.routeMoveAllowed(actor, start, end)
                }, { destination -> citizenOpenRouteDoors(h.city, route.allowedBuildings, actual, destination,
                    { _, _ -> h.passageLocked }) }) shouldBe null
                proposals.size shouldBe 1
                (actual.distance(proposals.single()) > .05) shouldBe true
                val first = actual.clone().add(if (case == "centre") .05 else 0.0, 0.0, if (case == "centre") 0.0 else -.05)
                val second = first.clone().add(if (case == "centre") .05 else 0.0, 0.0, if (case == "centre") 0.0 else -.05)
                for ((start, end) in listOf(actual to first, first to second)) {
                    citizenResidentNudgeClear(actor.npc, start, end) shouldBe true
                    collisions.residentMoveAllowed(actor, start, end) shouldBe true
                    citizenPacketSupported(h.world, end.point()) shouldBe true
                }
                if (case == "centre") citizenAtSupportedEndpoint(h.world, second.point(), goal) shouldBe true
                else {
                    val centre = goal.location(h.world)
                    val west = centre.clone().add(-.625, 0.0, 0.0)
                    val north = centre.clone().add(0.0, 0.0, -.625)
                    val corner = actual.clone().add(0.0, 0.0, -.625)
                    collisions.residentMoveAllowed(actor, west, west) shouldBe false
                    collisions.residentMoveAllowed(actor, north, north) shouldBe true
                    collisions.residentMoveAllowed(actor, actual, north) shouldBe false
                    citizenAtSupportedEndpoint(h.world, north.point(), goal) shouldBe true
                    for ((start, end) in listOf(actual to corner, corner to north)) {
                        collisions.residentMoveAllowed(actor, start, end) shouldBe true
                        citizenFlatSupportClear(start.point(), end.point()) { citizenPacketSupported(h.world, it) } shouldBe true
                        citizenPacketStepClear(h.world, start.point(), end.point(), false) shouldBe true
                    }
                }
                val player = h.playerAt(second)
                collisions.residentMoveAllowed(actor, first, second) shouldBe false
                player.teleport(Location(h.world, 45.5, 64.0, 20.5))
                other.npc.teleport(second)
                collisions.residentMoveAllowed(actor, first, second) shouldBe false
                other.npc.teleport(occupied)
                val cell = h.world.getBlockAt(second.blockX, second.blockY, second.blockZ)
                val before = cell.blockData
                cell.type = Material.STONE
                citizenResidentNudgeClear(actor.npc, first, second) shouldBe false
                cell.setBlockData(before, false)
                h.observedWork(blockedId) shouldBe 0L
                h.deliveries shouldBe 0

                // The contact branch runs before trip validation, so stale work cannot borrow its physical step.
                for (invalid in listOf("order", "token")) {
                    val currentCity = h.city
                    val currentPlan = actor.plan
                    if (invalid == "order") h.city = h.city.copy(construction = order.copy(id = UUID.randomUUID()))
                    else actor.plan = actor.plan.copy(token = actor.plan.token.copy(completedCycles = actor.plan.token.completedCycles + 1))
                    repeat(20) { h.navigate() }
                    withClue("stale $invalid") { actor.npc.location shouldBe actual }
                    h.observedWork(blockedId) shouldBe 0L
                    h.deliveries shouldBe 0
                    h.city = currentCity
                    actor.plan = currentPlan
                    routes.cancelRoute(actor)
                    routes.startRoute(actor, h.world, goal, requireNotNull(h.navigationNow)) shouldBe true
                    repeat(160) { if (actor.route?.motion?.waitingForCollision != true) h.navigate() }
                    requireNotNull(actor.route).motion.waitingForCollision shouldBe true
                }

                if (case == "offset") {
                    h.holdNavigation = true
                    repeat(20) { if (actor.route?.previousMotion == null) h.navigate() }
                    val detour = requireNotNull(actor.route)
                    (detour.previousMotion != null) shouldBe true
                    detour.motion.searching shouldBe true
                    val now = requireNotNull(h.navigationNow)
                    withClue("detour navigation clock=$now recovery clock=${h.recoveryMillis}") {
                        (detour.timing.preparationElapsed(now) in 0L..1_000L) shouldBe true
                        (detour.preparationReportAfter > now) shouldBe true
                    }
                    h.releaseNavigation()
                }
                repeat(240) { tick ->
                    h.navigate()
                    if (tick % 5 == 0) h.owner.pulseFieldWork(requireNotNull(h.navigationNow))
                }
                withClue("actual=${actor.npc.location.point()} goal=$goal phase=${actor.cycle.phase} proposed=${proposals.single().point()}") {
                    routes.arrivedAt(actor, h.city, h.world, goal) shouldBe true
                }
                collisions.residentMoveAllowed(actor, actor.npc.location, actor.npc.location) shouldBe true
                (actor.cycle.phase in setOf(CitizenPhase.WORKING, CitizenPhase.READY)) shouldBe true
                other.npc.location shouldBe occupied
                h.deliveries shouldBe 0
            }
        }
    }

    test("an unmarked player overlap blocks a new mine arrival until clear without revoking its later accepted work receipt") {
        residentRuntime { h ->
            val work = h.prepareMineWork(CivPosition(23, 64, 0), focus = CivPosition(26, 64, 0))
            val npc = h.owner.collisionActors().single()
            h.holdNavigation = true
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            val actor = arrivalActor(h)
            h.phase() shouldBe CitizenPhase.TO_WORK
            actor.plan.worldWork shouldBe work
            actor.bodyContact shouldBe null
            requireNotNull(h.routeIdentity())

            // The actor is already at a valid authored stance when a player enters before any collision sweep.
            npc.teleport(Location(h.world, 23.5, 64.0, .5))
            citizenPacketBodyClear(h.world, npc.location.point(), false) shouldBe true
            citizenPacketSupported(h.world, npc.location.point()) shouldBe true
            citizenCanReachBlock(npc, requireNotNull(work.focus), CITIZEN_MINE_REACH) shouldBe true
            val player = h.playerAt(npc.location)
            val canonical = h.city
            h.nativeCollisions().use { collisions ->
                h.owner.residentStepAllowed = collisions::canResidentMove
                for (now in listOf(2_500L, 3_000L, 4_000L)) {
                    h.owner.pulse(now)
                    actor.bodyContact shouldBe null
                    h.phase() shouldBe CitizenPhase.TO_WORK
                    h.observedWork() shouldBe 0L
                    h.deliveries shouldBe 0
                    h.pickups shouldBe 0
                    h.unloads shouldBe 0
                    h.work shouldBe work
                    h.city shouldBe canonical
                }

                player.teleport(Location(h.world, 24.5, 68.0, 4.5))
                h.owner.pulse(4_500)
                h.phase() shouldBe CitizenPhase.WORKING
                h.routeIdentity() shouldBe null
                h.observedWork() shouldBe 0L
                h.work shouldBe work

                // Once arrival is accepted, later player occupancy cannot invalidate the same paid work receipt.
                player.teleport(npc.location)
                h.owner.pulse(5_000)
                h.phase() shouldBe CitizenPhase.WORKING
                h.observedWork() shouldBe 500L
                h.owner.pulse(6_000)
                h.phase() shouldBe CitizenPhase.DELIVERING
                h.deliveries shouldBe 1
                h.pendingDelivery.isDone shouldBe false
                h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)
                h.work shouldBe work
                h.city shouldBe canonical
            }
        }
    }

    test("an unmarked route-null food arrival waits for a clear body then retains its accepted pickup through later occupancy") {
        residentRuntime { h ->
            val quarry = h.assignQuarry()
            h.city = CivsRules.validate(h.city.copy(workers = h.city.workers.map { it.copy(hunger = 12) }))
            h.work = CivWorldWork(h.workerId, 0, quarry.id, CivPosition(21, 64, 0), emptyList(),
                durationMillis = 60_000, status = "mining")
            val food = CivResidentFoodSource(quarry.id, requireNotNull(quarry.control), CivPosition(21, 64, 0))
            h.physicalFoodSource = food
            val npc = h.owner.collisionActors().single()
            npc.teleport(Location(h.world, 21.5, 64.0, .5))
            civsCanReachContainer(npc.location, food.container) shouldBe true
            val actor = arrivalActor(h)
            actor.bodyContact shouldBe null
            val player = h.playerAt(npc.location)
            val canonical = h.city
            val receipt = CompletableFuture<Settlement>()
            var pickups = 0
            h.physicalFoodPickup = { selected ->
                selected shouldBe requireNotNull(actor.foodPickupSource)
                selected.copy(standing = food.standing) shouldBe food
                h.owner.requireStorageArrival(h.city.id, h.workerId, food.buildingId)
                pickups++
                receipt
            }
            h.nativeCollisions().use { collisions ->
                h.owner.residentStepAllowed = collisions::canResidentMove
                h.holdNavigation = true
                h.navigationNow = 2_000
                h.owner.pulse(2_000)
                actor.bodyContact shouldBe null
                actor.foodPickupSource?.copy(standing = food.standing) shouldBe food
                actor.supplyPickup shouldBe null
                pickups shouldBe 0
                h.observedWork() shouldBe 0L
                h.city shouldBe canonical

                player.teleport(Location(h.world, 24.5, 68.0, 4.5))
                h.owner.pulse(3_000)
                pickups shouldBe 1
                val accepted = requireNotNull(actor.supplyPickup)
                actor.route shouldBe null
                h.owner.requireStorageArrival(h.city.id, h.workerId, food.buildingId)
                player.teleport(npc.location)
                h.owner.pulse(3_500)
                (actor.supplyPickup === accepted) shouldBe true
                h.owner.requireStorageArrival(h.city.id, h.workerId, food.buildingId)
                pickups shouldBe 1
                receipt.isDone shouldBe false
                h.observedWork() shouldBe 0L
                h.deliveries shouldBe 0
                h.pickups shouldBe 0
                h.unloads shouldBe 0
                h.city shouldBe canonical
            }
        }
    }
})

private fun arrivalActor(h: ResidentRuntime): CitizenActor = h.owner.javaClass.getDeclaredField("actors").run {
    isAccessible = true
    requireNotNull((get(h.owner) as Map<*, *>)[h.workerId]) as CitizenActor
}
