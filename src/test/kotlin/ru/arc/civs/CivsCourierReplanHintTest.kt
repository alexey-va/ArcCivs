package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack
import java.util.UUID

class CivsCourierReplanHintTest : FunSpec({
    for (proof in listOf("observed", "missing-prefix", "external-teleport"))
        test("a zero-progress source replan retains only its actual inbound proof=$proof through exact cargo delivery") {
        residentRuntime(workerId = UUID(0, 300)) { h ->
            val (source, courierId) = h.courierSource(material = "WHEAT", amount = 1)
            val warehouse = requireNotNull(h.city.building(h.city.workers.last().assignment!!))
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(items = null) })
            h.idleReason = "searching-work"
            val sourceControl = requireNotNull(source.control)
            val depotControl = requireNotNull(warehouse.control)
            val sourceBin = (h.world.getBlockAt(sourceControl.x, sourceControl.y, sourceControl.z).state as Barrel).inventory
            val depotBin = (h.world.getBlockAt(depotControl.x, depotControl.y, depotControl.z).state as Barrel).inventory
            sourceBin.setItem(0, ItemStack(Material.WHEAT, 17))
            val offered = requireNotNull(CivsLogistics.plan(h.city, h.city.workers.last()) {
                if (it == source.id) mapOf("WHEAT" to 17L) else emptyMap()
            })
            offered.cargo.amount shouldBe 17L
            var offersEnabled = false
            h.courierProvider = { _, worker -> if (worker.id != courierId || !offersEnabled) null else
                worker.cargo?.let { offered.copy(cargo = it, carrying = true) } ?: offered }
            h.onPickup = { cityId, workerId, cargo ->
                cargo shouldBe offered.cargo
                h.owner.requireStorageArrival(cityId, workerId, source.id)
            }
            h.dropoffProvider = { cityId, workerId, cargo, target ->
                cargo shouldBe offered.cargo.copy(inventoryBacked = true)
                target shouldBe warehouse.id
                h.owner.requireStorageArrival(cityId, workerId, target)
                h.pendingUnload
            }
            h.owner.pulse(2_000)
            val courier = h.owner.collisionActors().single { it.uniqueId == courierId }
            // Initial actor placement only. All source travel and return motion below remain native.
            courier.teleport(Location(h.world, 11.5, 64.0, .5))
            citizenPacketSupported(h.world, courier.location.point()) shouldBe true
            offersEnabled = true
            h.navigationNow = 3_000
            h.owner.pulse(3_000)
            for (tick in 1..300) {
                h.navigate()
                if (tick % 5 == 0) h.owner.pulseFieldWork(requireNotNull(h.navigationNow))
                if ((h.routeProgress(courierId) ?: 0.0) >= 3.0) break
            }
            withClue(h.navigationState(courierId)) { ((h.routeProgress(courierId) ?: 0.0) >= 3.0) shouldBe true }
            val runtime = CivsCitizens::class.java.getDeclaredField("runtime").also { it.isAccessible = true }
                .get(h.owner) as CitizenRuntime
            val routes = CivsCitizens::class.java.getDeclaredField("routes").also { it.isAccessible = true }
                .get(h.owner) as CivsCitizenRoutes
            val actor = runtime.actors.getValue(courierId)
            val originalRoute = requireNotNull(actor.route)
            val trip = requireNotNull(originalRoute.trip)
            val prefix = requireNotNull(actor.courierInboundPrefix)
            val walked = trip.walked.toSet()
            val progress = originalRoute.motion.forwardProgress
            h.requestYieldAsReturning(h.workerId, courierId, courier.location.clone().add(.8, 0.0, 0.0))
            val yielding = withClue(h.owner.debug(h.city.id, null)) { requireNotNull(actor.residentYield) }
            var yieldingTicks = 0
            while (actor.residentYield != null && yieldingTicks++ < 100) {
                val previous = courier.location
                h.navigate()
                (previous.distance(courier.location) <= .050001) shouldBe true
                actor.route shouldBe originalRoute
                originalRoute.motion.forwardProgress shouldBe progress
                h.pickups shouldBe 0
            }
            actor.residentYield shouldBe null
            yielding.steps shouldBe 25
            trip.walked shouldBe walked
            actor.courierInboundPrefix shouldBe prefix
            h.owner.collisionActors().single { it.uniqueId == h.workerId }
                .teleport(Location(h.world, .5, 64.0, 10.5))
            if (proof == "external-teleport") courier.teleport(courier.location.clone().add(0.0, 0.0, 1.25))
            // Withhold only the coarse work pulse: observe the native source arrival before its pickup handoff.
            for (tick in 1..800) {
                h.navigate()
                if (routes.arrivedAt(actor, h.city, h.world, requireNotNull(actor.plan.workplace))) break
            }
            routes.arrivedAt(actor, h.city, h.world, requireNotNull(actor.plan.workplace)) shouldBe true
            citizenAtStorage(h.world, courier.location.point(), sourceControl) shouldBe true
            citizenCanReachBlock(courier, sourceControl) shouldBe true
            actor.cycle.phase shouldBe CitizenPhase.TO_WORK
            h.pickups shouldBe 0
            val sourceFeet = courier.location
            val sameRoute = requireNotNull(actor.route)
            sameRoute.trip shouldBe trip
            if (proof == "missing-prefix") actor.courierInboundPrefix = null
            routes.replanNavigation(actor, sameRoute, "no-progress", requireNotNull(h.navigationNow)) shouldBe true
            sameRoute.motion.forwardProgress shouldBe 0.0
            sameRoute.trip shouldBe trip
            (actor.route === sameRoute) shouldBe true
            val beforePickup = h.city
            h.owner.pulseFieldWork(requireNotNull(h.navigationNow) + 250)
            h.pickups shouldBe 1
            h.phase(courierId) shouldBe CitizenPhase.PICKING_UP
            val expectedHint = proof == "observed"
            withClue("proof=$proof priorProgress=$progress replannedProgress=${sameRoute.motion.forwardProgress}") {
                (actor.courierReturnHint != null) shouldBe expectedHint
            }
            actor.route shouldBe null
            courier.location shouldBe sourceFeet
            h.city shouldBe beforePickup
            sourceBin.getItem(0) shouldBe ItemStack(Material.WHEAT, 17)
            depotBin.isEmpty shouldBe true
            val hint = actor.courierReturnHint
            repeat(5) { h.navigate(); h.owner.pulseFieldWork(requireNotNull(h.navigationNow)) }
            h.pendingPickup.isDone shouldBe false
            h.phase(courierId) shouldBe CitizenPhase.PICKING_UP
            actor.courierReturnHint shouldBe hint
            courier.location shouldBe sourceFeet

            CivsStorage(h.paper.createSimplePlugin("CourierReplanReceipt")) { emptyArray() }.use { storage ->
                val pickupId = UUID.randomUUID()
                storage.reserve(h.city, pickupId)
                val pickup = storage.prepareCargoPickup(h.city, offered.cargo)
                val picked = CivsLogistics.pickup(h.city, courierId, offered.cargo).copy(inventory = pickup.inventoryAfter)
                val pickupReceipt = CivInventoryTransaction(pickupId, h.city.id, h.city.revision, picked, pickup.changes).validated()
                storage.seal(pickupReceipt)
                storage.apply(pickupReceipt) shouldBe CivInventoryApplyResult.Applied
                storage.unlock(pickupId)
                sourceBin.isEmpty shouldBe true
                h.city = picked
                h.pendingPickup.complete(picked)
                h.owner.pulseFieldWork(requireNotNull(h.navigationNow) + 250)
                h.phase(courierId) shouldBe CitizenPhase.RETURNING
                requireNotNull(actor.route).motion.hasCourierCorridorHint shouldBe expectedHint
                actor.courierReturnHint shouldBe null
                val loadedWorker = h.city.workers.single { it.id == courierId }
                for (tick in 1..1_000) {
                    h.navigate()
                    if (tick % 5 == 0) h.owner.pulseFieldWork(requireNotNull(h.navigationNow))
                    if (h.unloads == 1) break
                }
                withClue(h.navigationState(courierId)) { h.unloads shouldBe 1 }
                citizenAtStorage(h.world, courier.location.point(), depotControl) shouldBe true
                citizenCanReachBlock(courier, depotControl) shouldBe true
                h.city.workers.single { it.id == courierId } shouldBe loadedWorker
                depotBin.isEmpty shouldBe true
                val cargo = requireNotNull(loadedWorker.cargo)
                val dropId = UUID.randomUUID()
                storage.reserve(h.city, dropId)
                val drop = storage.prepareCargoDropoff(h.city, cargo, warehouse.id)
                val delivered = CivsLogistics.unload(h.city, courierId, cargo.id, requireNotNull(h.navigationNow))
                    .copy(inventory = drop.inventoryAfter)
                val receipt = CivInventoryTransaction(dropId, h.city.id, h.city.revision, delivered, drop.changes).validated()
                receipt.changes.map { it.container }.toSet() shouldBe setOf(depotControl)
                storage.seal(receipt)
                storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
                storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
                storage.unlock(dropId)
                depotBin.getItem(0) shouldBe ItemStack(Material.WHEAT, 17)
                sourceBin.isEmpty shouldBe true
                delivered.workers.single { it.id == courierId }.cargo shouldBe null
                CivsEquipment.materials(delivered.workers.single { it.id == courierId })["WHEAT"] shouldBe null
                offersEnabled = false
                h.city = delivered
                h.pendingUnload.complete(delivered)
                h.owner.pulseFieldWork(requireNotNull(h.navigationNow) + 250)
                h.pickups shouldBe 1
                h.unloads shouldBe 1
            }
        }
    }
})
