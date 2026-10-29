package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsCitizenCourierHandoffTest : FunSpec({
    test("a native courier grabs on fast arrival and starts returning only after its exact durable completion") {
        residentRuntime { h ->
            val (source, courierId) = h.courierSource(material = "WHEAT", amount = 1)
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(items = null) })
            h.idleReason = "searching-work"
            h.owner.pulse(2_000)
            val offered = requireNotNull(CivsLogistics.plan(h.city, h.city.workers.last()) {
                if (it == source.id) mapOf("WHEAT" to 32L) else emptyMap()
            })
            h.courierProvider = { _, worker -> if (worker.id != courierId) null else
                worker.cargo?.let { offered.copy(cargo = it, carrying = true) } ?: offered }
            h.onPickup = { cityId, workerId, cargo ->
                cargo shouldBe offered.cargo
                h.owner.requireStorageArrival(cityId, workerId, source.id)
            }
            h.owner.pulse(3_000)
            val courier = h.owner.collisionActors().single { it.uniqueId == courierId }
            courier.teleport(Location(h.world, 21.5, 64.0, .5))
            val atSource = courier.location
            val before = h.city
            h.owner.pulseFieldWork(3_250)
            h.pickups shouldBe 1
            h.phase(courierId) shouldBe CitizenPhase.PICKING_UP
            h.pickedCargo shouldBe offered.cargo
            h.city shouldBe before
            repeat(5) { h.owner.pulseFieldWork(3_500L + it * 250) }
            h.owner.pulse(5_000)
            h.pickups shouldBe 1
            courier.location shouldBe atSource
            h.city shouldBe before

            // Model publication can precede journal retirement; it cannot release the pending ticket.
            h.city = CivsLogistics.pickup(h.city, courierId, offered.cargo)
            val published = h.city
            h.owner.pulseFieldWork(5_250)
            h.owner.pulseFieldWork(5_500)
            h.phase(courierId) shouldBe CitizenPhase.PICKING_UP
            h.routeIdentity(courierId) shouldBe null
            courier.location shouldBe atSource
            h.pickups shouldBe 1

            h.pendingPickup.complete(published)
            h.owner.pulseFieldWork(5_750)
            h.phase(courierId) shouldBe CitizenPhase.RETURNING
            (h.routeIdentity(courierId) != null) shouldBe true
            h.city shouldBe published
            val carried = h.city.workers.single { it.id == courierId }
            carried.cargo shouldBe offered.cargo.copy(inventoryBacked = true)
            CivsEquipment.plainCount(carried, offered.cargo.material) shouldBe offered.cargo.amount
            carried.completedCycles shouldBe before.workers.single { it.id == courierId }.completedCycles
            h.navigationNow = 5_750
            var moved = false
            for (tick in 1..200) {
                h.navigate()
                if (tick % 5 == 0) h.owner.pulseFieldWork(requireNotNull(h.navigationNow))
                if (courier.location.distance(atSource) >= .2) { moved = true; break }
            }
            withClue(h.navigationState(courierId)) { moved shouldBe true }
            h.pickups shouldBe 1
            h.unloads shouldBe 0
            h.deliveries shouldBe 0
            h.city shouldBe published
        }
    }

    test("real couriers share the same eight fast handoff slots while idle warehouses remain excluded") {
        residentRuntime { h ->
            val (source, idleWarehouseWorker) = h.courierSource(material = "OAK_LOG", amount = 1)
            val warehouse = h.city.buildings.single { it.type == BuildingType.WAREHOUSE }
            val ids = (100L..108L).map { UUID(0, it) }
            h.city = h.city.copy(buildings = h.city.buildings.map { building ->
                if (building.id == source.id) building.copy(type = BuildingType.FORESTER, production = "OAK_LOG") else building
            }, workers = h.city.workers.map { it.copy(items = null) } + ids.map { CivWorker(it, "Courier", warehouse.id, nextDeliveryAt = 1) })
            h.idleReason = "searching-work"
            val plans = ids.associateWith { id ->
                val cargo = CivCargo(CivsLogistics.shipmentId(h.city.id, id, 0, source.id, warehouse.id, "OAK_LOG"),
                    warehouse.id, source.id, warehouse.id, source.entrance, "OAK_LOG", 1)
                CivCourierPlan(cargo, warehouse.id, warehouse.entrance, false)
            }
            val pending = ids.associateWith { CompletableFuture<Settlement>() }
            val requested = mutableListOf<UUID>()
            var offered = false
            h.courierProvider = { _, worker -> plans[worker.id].takeIf { offered } }
            h.onPickup = { cityId, workerId, cargo ->
                cargo shouldBe plans.getValue(workerId).cargo
                h.owner.requireStorageArrival(cityId, workerId, source.id)
                requested += workerId
            }
            h.pickupProvider = { _, workerId, _ -> pending.getValue(workerId) }
            repeat(6) { h.owner.pulse(2_000L + it * 1_000) }
            val couriers = h.owner.collisionActors().filter { it.uniqueId in ids }
            couriers.size shouldBe 9
            h.pickups shouldBe 0
            // Idle residents may already have wandered near the source during admission.
            // Give the nine existing actors distinct supported warehouse positions before offering work.
            val warehousePositions = listOf(8 to -1, 9 to -1, 10 to -1, 8 to 0, 9 to 0,
                8 to 1, 9 to 1, 10 to 1, 7 to 0)
            couriers.forEachIndexed { index, npc ->
                val (x, z) = warehousePositions[index]
                h.world.getBlockAt(x, 63, z).type = Material.STONE
                npc.teleport(Location(h.world, x + .5, 64.0, z + .5))
                val dx = npc.location.x - source.entrance.x - .5
                val dz = npc.location.z - source.entrance.z - .5
                (dx * dx + dz * dz > 25.0) shouldBe true
                citizenPacketBodyClear(h.world, npc.location.point(), false) shouldBe true
                // The loaded one-chunk planner margin includes z=-2 for actors whose feet are at z=-.5.
                val chunks = CitizenChunks.between(x, z, source.entrance.x, source.entrance.z)
                for (chunkX in chunks.minX..chunks.maxX) for (chunkZ in chunks.minZ..chunks.maxZ)
                    h.world.loadChunk(chunkX, chunkZ)
                chunks.ready(h.world) shouldBe true
            }
            val spawnsBeforeOffer = h.spawns
            offered = true
            h.owner.pulse(8_000)
            h.pickups shouldBe 0
            h.spawns shouldBe spawnsBeforeOffer
            couriers.forEach { npc ->
                (h.owner.collisionActors().single { it.uniqueId == npc.uniqueId } === npc) shouldBe true
                h.phase(npc.uniqueId) shouldBe CitizenPhase.TO_WORK
            }
            val positions = listOf(19.5 to -.5, 20.5 to -.5, 21.5 to -.5, 19.5 to .5, 21.5 to .5,
                19.5 to 1.5, 20.5 to 1.5, 21.5 to 1.5, 18.85 to .5)
            couriers.forEachIndexed { index, npc ->
                val (x, z) = positions[index]
                npc.teleport(Location(h.world, x, 64.0, z))
                citizenPacketBodyClear(h.world, npc.location.point(), false) shouldBe true
                citizenAtStorage(h.world, npc.location.point(), requireNotNull(source.control)) shouldBe true
                citizenCanReachBlock(npc, source.control!!) shouldBe true
                couriers.take(index).none { other -> citizenStandingBounds(other.location, other.scale)
                    .overlaps(citizenStandingBounds(npc.location, npc.scale)) } shouldBe true
            }
            h.owner.pulseFieldWork(8_250)
            requested.size shouldBe 8
            requested.distinct().size shouldBe 8
            h.owner.pulseFieldWork(8_500)
            requested.toSet() shouldBe ids.toSet()
            requested.size shouldBe 9
            repeat(4) { h.owner.pulseFieldWork(8_750L + it * 250) }
            h.owner.pulse(10_000)
            requested.size shouldBe 9
            (idleWarehouseWorker in requested) shouldBe false
            h.city.workers.filter { it.id in ids }.all { it.cargo == null } shouldBe true
            h.unloads shouldBe 0
            h.deliveries shouldBe 0
        }
    }

    test("an ordinary field producer still earns its full observed work interval") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(21, 64, 0),
                listOf(CivPlanBlock(CivPosition(23, 63, 0), "minecraft:stone", "minecraft:air")),
                durationMillis = 1_000, status = "working-field")
            h.owner.collisionActors().single().teleport(Location(h.world, 21.5, 64.0, .5))
            h.owner.pulse(2_000)
            h.phase() shouldBe CitizenPhase.WORKING
            for (now in listOf(2_250L, 2_500L, 2_750L)) {
                h.owner.pulseFieldWork(now)
                h.deliveries shouldBe 0
                h.phase() shouldBe CitizenPhase.WORKING
            }
            h.owner.pulseFieldWork(3_000)
            h.deliveries shouldBe 1
            h.pickups shouldBe 0
            h.city.workers.single().completedCycles shouldBe 0
        }
    }
})
