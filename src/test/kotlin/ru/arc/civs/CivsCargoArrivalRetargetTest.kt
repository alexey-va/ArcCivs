package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import org.bukkit.util.VoxelShape
import org.mockbukkit.mockbukkit.block.BlockMock
import java.util.UUID

class CivsCargoArrivalRetargetTest : FunSpec({
    for (blocked in listOf(false, true)) {
        test("a carried tavern shipment uses the true carpet surface when the bare side is occupied: blocked=$blocked") {
            residentRuntime(worldFactory = ::CarpetArrivalWorld, server = CivsWorksiteServerFixture()) { h ->
                h.navigationNow = 2_000
                h.world.time = 0
                for (x in -2..2) for (z in -2..2) h.world.loadChunk(x, z)
                val warehouse = h.assignFarm().copy(type = BuildingType.WAREHOUSE, schematicId = "warehouse1", production = null)
                val control = CivPosition(8, 64, 0)
                val north = control.copy(z = -1)
                val west = control.copy(x = 7)
                val tavern = CivBuilding(UUID(79, 1), BuildingType.TAVERN, 1, control, 0, "tavern1",
                    CivBox(CivPosition(5, 64, -2), CivPosition(10, 68, 2)), CivPosition(6, 64, -3),
                    BuildingPhase.ACTIVE, control)
                val keeper = CivWorker(UUID(79, 2), "Innkeeper", tavern.id, nextDeliveryAt = 1, hunger = 20)
                val cargo = CivCargo(UUID(79, 3), warehouse.id, warehouse.id, tavern.id, warehouse.entrance,
                    "BREAD", 28, inventoryBacked = true)
                val carrier = CivsEquipment.collectSupplies(h.city.workers.single().copy(cargo = cargo, hunger = 20),
                    mapOf(cargo.material to cargo.amount))
                h.city = CivsRules.validate(h.city.copy(buildings = listOf(h.city.buildings.first(), warehouse, tavern),
                    workers = listOf(carrier, keeper)))
                h.courierProvider = { _, worker -> worker.cargo?.let { CivCourierPlan(it, tavern.id, tavern.entrance, carrying = true) } }
                h.world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
                for ((x, z) in listOf(9 to -1, 9 to 0, 9 to 1, 8 to 1, 7 to 1))
                    for (y in 63..65) h.world.getBlockAt(x, y, z).type = Material.STONE
                h.owner.pulse(2_000)
                val actor = h.actor()
                val innkeeper = h.actor(keeper.id)
                val routes = h.owner.javaClass.getDeclaredField("routes").run { isAccessible = true; get(h.owner) as CivsCitizenRoutes }
                routes.cancelRoute(actor)
                routes.cancelRoute(innkeeper)
                val initial = Location(h.world, 8.5, 64.0625, -2.5)
                val expected = north.location(h.world).add(0.0, .0625, 0.0)
                actor.npc.teleport(initial)
                innkeeper.npc.teleport(west.location(h.world))
                actor.cycle = actor.plan.cycle().also(CitizenCycle::start)
                citizenAtStorage(h.world, initial.point(), control) shouldBe false
                citizenStandable(h.world, north) shouldBe true
                citizenPacketBodyClear(h.world, north.point(), false) shouldBe false
                citizenPacketBodyClear(h.world, expected.point(), false) shouldBe true
                citizenPacketSupported(h.world, expected.point()) shouldBe true
                val before = h.city
                h.dropoffProvider = { city, worker, shipment, destination ->
                    shipment shouldBe cargo
                    destination shouldBe tavern.id
                    h.owner.requireStorageArrival(city, worker, destination)
                    citizenPacketSupported(h.world, actor.npc.location.point()) shouldBe true
                    citizenPacketBodyClear(h.world, actor.npc.location.point(), false) shouldBe true
                    actor.npc.location.y shouldBe expected.y
                    h.pendingUnload
                }
                h.nativeCollisions().use { collisions ->
                    h.owner.residentStepAllowed = collisions::canResidentMove
                    if (blocked) {
                        h.playerAt(expected)
                        h.world.getBlockAt(7, 65, -1).type = Material.STONE
                    }
                    h.navigationNow = 3_000
                    h.owner.pulse(3_000)
                    if (blocked) {
                        actor.route shouldBe null
                        actor.cycle.reason shouldBe "storage-unavailable"
                        actor.npc.location.point() shouldBe initial.point()
                        h.unloads shouldBe 0
                    } else {
                        val route = withClue(h.navigationState(h.workerId)) { requireNotNull(actor.route) }
                        route.target.point() shouldBe expected.point()
                        h.unloads shouldBe 0
                        for (tick in 1..200) {
                            h.navigate()
                            if (tick % 5 == 0) h.owner.pulseFieldWork(requireNotNull(h.navigationNow))
                            if (h.unloads > 0) break
                        }
                        withClue(h.navigationState(h.workerId)) { h.unloads shouldBe 1 }
                        (route.motion.forwardProgress > .5) shouldBe true
                        (actor.npc.location.point().distanceSquared(initial.point()) > .25) shouldBe true
                        actor.cycle.phase shouldBe CitizenPhase.DELIVERING
                    }
                    innkeeper.npc.location.point() shouldBe west.point()
                    h.pendingUnload.isDone shouldBe false
                    h.city shouldBe before
                }
            }
        }
    }
    for (obstacle in listOf("resident", "player", "no-alternate-side")) {
        test("a finished physical delivery retries an occupied barrel side safely: $obstacle") {
            residentRuntime(worldFactory = ::CargoArrivalWorld, server = CivsWorksiteServerFixture()) { h ->
                h.navigationNow = 2_000
                h.world.time = 0
                for (x in -2..2) for (z in -2..2) h.world.loadChunk(x, z)
                val original = h.assignFarm()
                val warehouse = original.copy(type = BuildingType.WAREHOUSE, schematicId = "warehouse1", production = null)
                val control = CivPosition(8, 64, 0)
                val hut = CivBuilding(UUID(78, 1), BuildingType.BUILDER_HUT, 1, control, 0, "builder1",
                    CivBox(control.copy(x = 5, z = -2), control.copy(x = 10, y = 68, z = 2)),
                    control.copy(x = 6, z = -3), BuildingPhase.ACTIVE, control)
                val blocker = CivWorker(UUID(78, 2), "Builder", hut.id, nextDeliveryAt = 1, hunger = 20)
                val cargo = CivCargo(UUID(78, 3), warehouse.id, warehouse.id, hut.id, warehouse.entrance,
                    "COBBLESTONE", 4, inventoryBacked = true)
                val carrier = CivsEquipment.collectSupplies(h.city.workers.single().copy(cargo = cargo, hunger = 20),
                    mapOf(cargo.material to cargo.amount))
                h.city = CivsRules.validate(h.city.copy(buildings = listOf(h.city.buildings.first(), warehouse, hut),
                    workers = listOf(carrier, blocker)))
                h.courierProvider = { _, worker ->
                    worker.cargo?.let { CivCourierPlan(it, hut.id, hut.entrance, carrying = true) }
                }
                h.world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
                h.world.getBlockAt(9, 64, 0).type = Material.STONE
                val oldSide = control.copy(x = control.x - 1)
                val south = control.copy(z = 1)
                h.owner.pulse(2_000)
                val actor = h.actor()
                val other = h.actor(blocker.id)
                val routes = h.owner.javaClass.getDeclaredField("routes").run { isAccessible = true; get(h.owner) as CivsCitizenRoutes }
                routes.cancelRoute(actor)
                routes.cancelRoute(other)
                val oldTarget = oldSide.location(h.world)
                val origin = Location(h.world, 7.699321617356, 64.0, .552639271036)
                val blockerFeet = Location(h.world, 7.66, 64.0, .31)
                actor.npc.teleport(origin)
                other.npc.teleport(blockerFeet)
                actor.cycle.phase shouldBe CitizenPhase.RETURNING
                actor.plan.storage shouldBe control
                if (obstacle == "no-alternate-side") {
                    for (x in 7..9) for (z in -1..1) for (y in 64..65)
                        if (x != control.x || z != control.z)
                            if (x != oldSide.x || z != oldSide.z) h.world.getBlockAt(x, y, z).type = Material.STONE
                }
                citizenAtStorage(h.world, origin.point(), control) shouldBe true
                citizenCanReachBlock(actor.npc, control) shouldBe true
                if (obstacle != "no-alternate-side") {
                    citizenStandable(h.world, south) shouldBe true
                    citizenCanReachBlock(h.world, south.location(h.world).add(0.0, actor.npc.eyeHeight, 0.0), control) shouldBe true
                }
                val motion = mockk<CitizenPacketRoute>(relaxed = true) {
                    every { finished } returns true
                    every { failure } returns null
                    every { searching } returns false
                    every { continuation } returns false
                    every { waitingForCollision } returns false
                    every { remainingNodes } returns 0
                    every { nodeCount } returns 2
                }
                val finished = CitizenRoute(origin.point(), oldTarget, CitizenChunks.between(oldSide.x, oldSide.z, control.x, control.z),
                    2_000, 12_000, setOf(hut.id, warehouse.id), motion, null, control)
                actor.targets[control] = oldSide
                actor.route = finished
                val originalToken = actor.plan.token
                val before = h.city
                h.nativeCollisions().use { native ->
                    val player = if (obstacle == "player") h.playerAt(origin) else null
                    h.owner.residentStepAllowed = native::canResidentMove
                    val away = origin.toVector().subtract(blockerFeet.toVector()).normalize().multiply(.05)
                    val tangent = Vector(-away.z, 0.0, away.x)
                    listOf(away, tangent, tangent.clone().multiply(-1.0)).forEach {
                        h.owner.nudgeResident(actor.npc, it) shouldBe false
                    }
                    h.dropoffProvider = { city, worker, shipment, destination ->
                        shipment shouldBe cargo
                        destination shouldBe hut.id
                        h.owner.requireStorageArrival(city, worker, destination)
                        h.pendingUnload
                    }
                    h.navigate()
                    actor.collisionResident shouldBe blocker.id
                    actor.bodyContact?.token shouldBe originalToken
                    routes.arrivalBodyClear(actor) shouldBe false
                    finished.completion shouldBe null

                    var replaced = false
                    for (tick in 1..450) {
                        val prior = actor.npc.location
                        h.navigate()
                        val at = actor.npc.location
                        h.owner.passingBodies(actor.npc) shouldBe false
                        (prior.distanceSquared(at) <= .172001 * .172001) shouldBe true
                        if (!replaced && actor.route != null && actor.route !== finished) {
                            replaced = true
                            requireNotNull(actor.route).target.point() shouldBe south.point()
                            actor.targets[control] shouldBe oldSide
                            actor.bodyContact?.token shouldBe originalToken
                            actor.plan.token shouldBe originalToken
                            h.unloads shouldBe 0
                        }
                        if (tick % 10 == 0) {
                            h.owner.pulseFieldWork(requireNotNull(h.navigationNow))
                            h.clearRecordedCalls()
                        }
                        if (h.unloads > 0) break
                    }
                    if (obstacle == "resident") {
                        withClue(h.owner.debug(h.city.id, h.workerId)) {
                            replaced shouldBe true
                            h.unloads shouldBe 1
                            actor.cycle.phase shouldBe CitizenPhase.DELIVERING
                        }
                        repeat(3) { h.owner.pulse(requireNotNull(h.navigationNow) + (it + 1) * 1_000) }
                        h.unloads shouldBe 1
                    } else {
                        replaced shouldBe false
                        actor.route shouldBe finished
                        actor.npc.location.point() shouldBe origin.point()
                        h.unloads shouldBe 0
                        player?.location?.point() shouldBe if (player != null) origin.point() else null
                    }
                    other.npc.location.point() shouldBe blockerFeet.point()
                    h.pendingUnload.isDone shouldBe false
                    h.city shouldBe before
                    h.city.workers.single { it.id == h.workerId }.cargo shouldBe cargo
                    h.pickups shouldBe 0
                    h.deliveries shouldBe 0
                }
            }
        }
    }
})

private class CarpetArrivalWorld : ResidentRuntimeWorld() {
    private val carpets = mutableMapOf<Pair<Int, Int>, BlockMock>()

    override fun getBlockAt(x: Int, y: Int, z: Int): BlockMock {
        if (y != 64 || !(z == -1 && x in 7..8 || z == -2 && x == 8)) return super.getBlockAt(x, y, z)
        return carpets.getOrPut(x to z) {
            super.getBlockAt(x, y, z).type = Material.BLACK_CARPET
            val shape = BoundingBox(0.0, 0.0, 0.0, 1.0, .0625, 1.0)
            object : BlockMock(Material.BLACK_CARPET, Location(this, x.toDouble(), y.toDouble(), z.toDouble())) {
                override fun isPassable(): Boolean = false
                override fun getBoundingBox(): BoundingBox = shape.clone().shift(x.toDouble(), y.toDouble(), z.toDouble())
                override fun getCollisionShape(): VoxelShape = object : VoxelShape {
                    override fun getBoundingBoxes(): Collection<BoundingBox> = listOf(shape.clone())
                    override fun overlaps(other: BoundingBox): Boolean = shape.overlaps(other)
                }
            }
        }
    }
}

/** Thin furniture behind the west side prevents the short sideways nudge. A diagonal exit remains open. */
private class CargoArrivalWorld : ResidentRuntimeWorld() {
    private val corner by lazy {
        super.getBlockAt(7, 64, 0).type = Material.COBBLESTONE_WALL
        val box = BoundingBox(0.0, 0.0, 0.0, .358, 1.5, .321)
        object : BlockMock(Material.COBBLESTONE_WALL, Location(this, 7.0, 64.0, 0.0)) {
            override fun getBoundingBox(): BoundingBox = box.clone().shift(7.0, 64.0, 0.0)
            override fun getCollisionShape(): VoxelShape = object : VoxelShape {
                override fun getBoundingBoxes(): Collection<BoundingBox> = listOf(box.clone())
                override fun overlaps(other: BoundingBox): Boolean = box.overlaps(other)
            }
        }
    }

    override fun getBlockAt(x: Int, y: Int, z: Int): BlockMock =
        if (x == 7 && y == 64 && z == 0) corner else super.getBlockAt(x, y, z)
}
