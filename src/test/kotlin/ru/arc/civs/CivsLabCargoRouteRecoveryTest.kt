package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.util.BoundingBox
import org.bukkit.util.VoxelShape
import org.mockbukkit.mockbukkit.block.BlockMock
import java.util.UUID

class CivsLabCargoRouteRecoveryTest : FunSpec({
    for ((carrying, outcome) in listOf(false to "pickup", true to "dropoff", false to "outside-lab",
        false to "protected", true to "occupied", false to "unloaded", false to "obsolete",
        false to "virtual", true to "unbacked-return", false to "stale-destination")) {
        test("lab physical courier recovery after a walking retry: $outcome") {
            residentRuntime(server = CivsWorksiteServerFixture()) { h ->
                h.navigationNow = 2_000
                h.world.time = 0
                val original = h.assignFarm()
                val warehouse = original.copy(type = BuildingType.WAREHOUSE, schematicId = "warehouse1", production = null)
                val destination = CivPosition(33, 64, 0)
                val hut = CivBuilding(UUID(79, 1), BuildingType.BUILDER_HUT, 1, destination, 0, "builder1",
                    CivBox(destination.copy(x = 31, z = -2), destination.copy(x = 36, y = 68, z = 2)),
                    destination.copy(z = -3), BuildingPhase.ACTIVE, destination)
                val material = if (carrying) "COBBLESTONE" else "GRAVEL"
                val shipment = CivsLogistics.shipmentId(h.city.id, h.workerId, h.city.workers.single().completedCycles,
                    warehouse.id, hut.id, material)
                val cargo = CivCargo(shipment, warehouse.id, warehouse.id, hut.id, warehouse.entrance,
                    material, if (carrying) 4L else 64L, inventoryBacked = carrying && outcome != "unbacked-return")
                val worker = h.city.workers.single().copy(hunger = 20).let {
                    if (carrying) CivsEquipment.collectSupplies(it.copy(cargo = cargo), mapOf(cargo.material to cargo.amount)) else it
                }
                val mode = if (outcome == "virtual") CivStorageMode.VIRTUAL else CivStorageMode.PHYSICAL
                h.city = CivsRules.validate(h.city.copy(inventory = CivInventory(mode), buildings = listOf(h.city.buildings.first(), warehouse, hut),
                    workers = listOf(worker)))
                h.courier = CivCourierPlan(cargo, hut.id, hut.entrance, carrying)
                for (x in -1..3) for (z in -1..1) h.world.loadChunk(x, z)
                h.world.getBlockAt(destination.x, destination.y, destination.z).type = Material.BARREL
                val control = if (carrying) destination else requireNotNull(warehouse.control)
                val building = if (carrying) hut else warehouse
                h.labCourierRecoveryAllowed = { points -> outcome != "outside-lab" &&
                    (outcome != "protected" || points.all { it == control }) }
                val origin = Location(h.world, control.x - 11.5, 64.0, .5)
                for (z in -3..3) for (y in 64..66) h.world.getBlockAt(origin.blockX + 1, y, z).type = Material.STONE
                val standing = control.copy(x = control.x + 1).location(h.world)
                h.pickupProvider = { city, id, exact ->
                    exact shouldBe cargo
                    h.owner.requireStorageArrival(city, id, warehouse.id)
                    h.pendingPickup
                }
                h.dropoffProvider = { city, id, exact, target ->
                    exact shouldBe cargo
                    target shouldBe hut.id
                    h.owner.requireStorageArrival(city, id, target)
                    h.pendingUnload
                }
                h.owner.pulse(2_000)
                val actor = h.actor()
                actor.route?.motion?.cancel()
                actor.npc.teleport(origin)
                val phase = if (carrying) CitizenPhase.RETURNING else CitizenPhase.TO_WORK
                val originalToken = actor.plan.token
                val before = h.city

                fun attempt(at: Long): CitizenRoute {
                    val motion = CitizenPacketRoute(h.world, origin, standing, { true }, { _, _ -> true }, { 1.0 })
                    return CitizenRoute(origin.point(), standing,
                        CitizenChunks.between(origin.blockX, origin.blockZ, standing.blockX, standing.blockZ), at,
                        12_000, setOf(warehouse.id, hut.id), motion, null, control)
                }
                fun fail(route: CitizenRoute) {
                    route.motion.planningFailed("chunk-unloaded")
                    route.completion = CitizenNavigationCompletion("chunk-unloaded")
                }

                val first = attempt(2_900)
                fail(first)
                actor.route = first
                h.owner.pulse(3_000)
                if (outcome in setOf("outside-lab", "virtual", "unbacked-return")) {
                    actor.cycle.phase shouldBe CitizenPhase.PAUSED
                    actor.npc.location.point() shouldBe origin.point()
                    h.pickups shouldBe 0
                    h.unloads shouldBe 0
                    return@residentRuntime
                }
                actor.cycle.phase shouldBe phase
                actor.route shouldBe first
                actor.npc.location.point() shouldBe origin.point()
                h.owner.pulse(8_001)
                actor.route shouldBe null
                actor.cycle.phase shouldBe phase

                val second = attempt(8_002)
                actor.route = second
                h.recoveryMillis = 59_999
                h.owner.pulse(8_003)
                actor.route shouldBe second
                actor.npc.location.point() shouldBe origin.point()
                h.recoveryMillis = 60_001
                h.navigate(0)
                actor.route shouldBe second
                actor.npc.location.point() shouldBe origin.point()
                fail(second)

                h.nativeCollisions().use { native ->
                    h.owner.residentStepAllowed = native::canResidentMove
                    when (outcome) {
                        "occupied" -> {
                            for (x in control.x - 1..control.x + 1) for (z in -1..1) for (y in 64..66) {
                                if (x == control.x && z == control.z || x == standing.blockX && z == standing.blockZ) continue
                                h.world.getBlockAt(x, y, z).type = Material.STONE
                            }
                            h.playerAt(standing)
                        }
                        "unloaded" -> h.world.unloadChunk(control.x shr 4, control.z shr 4) shouldBe true
                        "obsolete" -> h.courier = null
                        "stale-destination" -> h.city = h.city.copy(buildings = h.city.buildings.map {
                            if (it.id == hut.id) hut.copy(entrance = hut.entrance.copy(z = -4)) else it
                        })
                    }
                    val current = h.city
                    h.owner.pulse(8_004)
                    if (outcome !in setOf("pickup", "dropoff")) {
                        actor.npc.location.point() shouldBe origin.point()
                        h.pickups shouldBe 0
                        h.unloads shouldBe 0
                        h.city shouldBe current
                        return@residentRuntime
                    }
                    actor.route shouldBe null
                    actor.plan.token shouldBe originalToken
                    actor.cycle.phase shouldBe phase
                    h.city shouldBe before
                    h.pickups shouldBe 0
                    h.unloads shouldBe 0
                    citizenAtStorage(h.world, actor.npc.location.point(), control) shouldBe true
                    citizenCanReachBlock(actor.npc, requireNotNull(building.control)) shouldBe true

                    repeat(4) { h.owner.pulse(9_000L + it * 1_000) }
                    h.pickups shouldBe if (carrying) 0 else 1
                    h.unloads shouldBe if (carrying) 1 else 0
                    h.city shouldBe before // The still-pending journal alone owns durable cargo.
                }
            }
        }
    }

    for (outcome in listOf("dropoff", "outside-lab", "outside-lab-integer", "occupied", "protected-floor", "protected-origin", "stale", "moved", "same-attempt", "chunk-unloaded-dropoff")) {
        val chunkFailure = outcome == "chunk-unloaded-dropoff"
        test(if (chunkFailure) "lab nearby carried cargo recovers onto native carpet after repeated chunk-unloaded"
            else "lab nearby carried cargo after terminal capture exhaustion: $outcome") {
            val carpeted = outcome != "outside-lab-integer"
            residentRuntime(worldFactory = { CargoRecoveryCarpetWorld(carpeted) }, server = CivsWorksiteServerFixture()) { h ->
                h.navigationNow = 2_000
                h.world.time = 0
                val warehouse = h.assignFarm().copy(type = BuildingType.WAREHOUSE, schematicId = "warehouse1", production = null)
                val control = CivPosition(33, 64, 0)
                val standing = control.copy(z = -1)
                val destination = CivBuilding(UUID(79, 2), BuildingType.TAVERN, 1, control, 0, "tavern1",
                    CivBox(CivPosition(31, 64, -2), CivPosition(36, 68, 2)), control.copy(z = -3), BuildingPhase.ACTIVE, control)
                val cargo = CivCargo(UUID(79, 3), warehouse.id, warehouse.id, destination.id, warehouse.entrance,
                    "BREAD", 28, inventoryBacked = true)
                val carrier = CivsEquipment.collectSupplies(h.city.workers.single().copy(cargo = cargo, hunger = 20),
                    mapOf(cargo.material to cargo.amount))
                h.city = CivsRules.validate(h.city.copy(inventory = CivInventory(CivStorageMode.PHYSICAL),
                    buildings = listOf(h.city.buildings.first(), warehouse, destination), workers = listOf(carrier)))
                h.courier = CivCourierPlan(cargo, destination.id, destination.entrance, carrying = true)
                for (x in -1..3) for (z in -2..1) h.world.loadChunk(x, z)
                h.world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
                for (x in 32..34) for (z in -1..1) {
                    if (x == control.x && z in -1..0) continue
                    h.world.getBlockAt(x, 64, z).type = Material.ACACIA_STAIRS
                    h.world.getBlockAt(x, 65, z).type = Material.GLASS_PANE
                }
                val origin = standing.copy(x = 35).location(h.world)
                val landing = standing.location(h.world).add(0.0, if (carpeted) .0625 else 0.0, 0.0)
                val approved = mutableSetOf<CivPosition>()
                val floor = standing.copy(y = 63)
                val originHead = CivPosition(35, 65, -1)
                h.labCourierRecoveryAllowed = { points ->
                    approved.addAll(points)
                    !outcome.startsWith("outside-lab") && (outcome != "protected-floor" || floor !in points) &&
                        (outcome != "protected-origin" || originHead !in points)
                }
                h.owner.pulse(2_000)
                val actor = h.actor()
                actor.route?.motion?.cancel()
                actor.npc.teleport(origin)
                actor.arrivalWait = null
                val token = actor.plan.token
                val before = h.city
                h.dropoffProvider = { city, worker, shipment, building ->
                    shipment shouldBe cargo
                    building shouldBe destination.id
                    h.owner.requireStorageArrival(city, worker, building)
                    actor.npc.location.point() shouldBe landing.point()
                    h.pendingUnload
                }
                fun attempt(at: Long): CitizenRoute {
                    val from = actor.npc.location
                    val motion = CitizenPacketRoute(h.world, from, landing, { true }, { _, _ -> true }, { 1.0 })
                    return CitizenRoute(from.point(), landing,
                        CitizenChunks.between(from.blockX, from.blockZ, landing.blockX, landing.blockZ), at,
                        13_000, setOf(destination.id), motion, null, control)
                }
                fun exhaust(route: CitizenRoute) {
                    val reason = if (chunkFailure) "chunk-unloaded" else "capture-budget"
                    route.motion.planningFailed(reason)
                    route.completion = CitizenNavigationCompletion(reason)
                }

                citizenAtStorage(h.world, origin.point(), control) shouldBe false
                citizenPacketBodyClear(h.world, standing.point(), false) shouldBe !carpeted
                citizenPacketBodyClear(h.world, landing.point(), false) shouldBe true
                citizenPacketSupported(h.world, landing.point(), allowClimbable = false) shouldBe true
                val first = attempt(2_900)
                first.chunks.ready(h.world) shouldBe true
                exhaust(first)
                actor.route = first
                h.owner.pulse(3_000)
                actor.npc.location.point() shouldBe origin.point()
                h.unloads shouldBe 0
                if (outcome.startsWith("outside-lab")) {
                    actor.cycle.phase shouldBe CitizenPhase.PAUSED
                    if (!carpeted) {
                        h.owner.pulse(8_001)
                        actor.cycle.phase shouldBe CitizenPhase.RETURNING
                        actor.route?.motion?.cancel()
                        val retry = attempt(8_002)
                        exhaust(retry)
                        actor.route = retry
                        h.recoveryMillis = 60_001
                        h.owner.pulse(8_004)
                        actor.npc.location.point() shouldBe landing.point()
                        h.unloads shouldBe 0
                        h.city shouldBe before
                    }
                    return@residentRuntime
                }
                actor.route shouldBe first
                actor.cycle.phase shouldBe CitizenPhase.RETURNING
                h.owner.pulse(8_001)
                actor.route shouldBe null
                if (outcome == "moved") actor.npc.teleport(origin.clone().add(.125, 0.0, 0.0))
                val secondOrigin = actor.npc.location.point()
                val retry = attempt(if (outcome == "same-attempt") 2_900 else 8_002)
                actor.route = retry
                h.recoveryMillis = 59_999
                h.owner.pulse(8_003)
                h.recoveryMillis = 60_001
                h.navigate(0)
                actor.route shouldBe retry
                actor.npc.location.point() shouldBe secondOrigin
                h.unloads shouldBe 0
                exhaust(retry)
                h.nativeCollisions().use { native ->
                    h.owner.residentStepAllowed = native::canResidentMove
                    if (outcome == "occupied") h.playerAt(landing)
                    if (outcome == "stale") h.city = h.city.copy(buildings = h.city.buildings.map {
                        if (it.id == destination.id) it.copy(entrance = it.entrance.copy(z = -4)) else it
                    })
                    val current = h.city
                    h.owner.pulse(8_004)
                    h.unloads shouldBe 0
                    h.city shouldBe current
                    actor.plan.token shouldBe token
                    if (outcome == "dropoff" || chunkFailure) {
                        actor.npc.location.point() shouldBe landing.point()
                        actor.route shouldBe null
                        actor.cycle.phase shouldBe CitizenPhase.RETURNING
                        approved.containsAll(listOf(control, standing, floor)) shouldBe true
                        if (!chunkFailure) approved.contains(originHead) shouldBe true
                        repeat(4) { h.owner.pulse(9_000L + it * 1_000) }
                        h.unloads shouldBe 1
                        h.city shouldBe before
                    } else actor.npc.location.point() shouldBe secondOrigin
                }
            }
        }
    }
})

private class CargoRecoveryCarpetWorld(private val carpeted: Boolean) : ResidentRuntimeWorld() {
    private val carpet by lazy {
        val shape = BoundingBox(0.0, 0.0, 0.0, 1.0, .0625, 1.0)
        object : BlockMock(Material.BLACK_CARPET, Location(this, 33.0, 64.0, -1.0)) {
            override fun isPassable(): Boolean = false
            override fun getBoundingBox(): BoundingBox = shape.clone().shift(33.0, 64.0, -1.0)
            override fun getCollisionShape(): VoxelShape = object : VoxelShape {
                override fun getBoundingBoxes(): Collection<BoundingBox> = listOf(shape.clone())
                override fun overlaps(other: BoundingBox): Boolean = shape.overlaps(other)
            }
        }
    }

    override fun getBlockAt(x: Int, y: Int, z: Int): BlockMock =
        if (carpeted && x == 33 && y == 64 && z == -1) carpet else super.getBlockAt(x, y, z)
}
