package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import java.util.UUID

class CivsLabCargoRouteRecoveryTest : FunSpec({
    for ((carrying, outcome) in listOf(false to "pickup", true to "dropoff", false to "outside-lab",
        false to "protected", true to "occupied", false to "unloaded", false to "obsolete")) {
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
                val cargo = CivCargo(UUID(79, 2), warehouse.id, warehouse.id, hut.id, warehouse.entrance,
                    "COBBLESTONE", 4, inventoryBacked = true)
                val worker = h.city.workers.single().copy(hunger = 20).let {
                    if (carrying) CivsEquipment.collectSupplies(it.copy(cargo = cargo), mapOf(cargo.material to cargo.amount)) else it
                }
                h.city = CivsRules.validate(h.city.copy(buildings = listOf(h.city.buildings.first(), warehouse, hut),
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
                if (outcome == "outside-lab") {
                    actor.cycle.phase shouldBe CitizenPhase.PAUSED
                    actor.npc.location.point() shouldBe origin.point()
                    h.pickups shouldBe 0
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
                    }
                    h.owner.pulse(8_004)
                    if (outcome !in setOf("pickup", "dropoff")) {
                        actor.npc.location.point() shouldBe origin.point()
                        h.pickups shouldBe 0
                        h.unloads shouldBe 0
                        h.city shouldBe before
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
})
