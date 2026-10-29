package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import java.util.UUID

class CivsCourierYieldTest : FunSpec({
    for (ownedYield in listOf(true, false))
        test("a walked courier retains its return hint only for native owned yielding=$ownedYield") {
        residentRuntime(workerId = UUID(0, 300)) { h ->
            val (source, courierId) = h.courierSource(material = "WHEAT", amount = 1)
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(items = null) })
            h.idleReason = "searching-work"
            val offered = requireNotNull(CivsLogistics.plan(h.city, h.city.workers.last()) {
                if (it == source.id) mapOf("WHEAT" to 32L) else emptyMap()
            })
            var offersEnabled = false
            h.courierProvider = { _, worker -> if (worker.id != courierId || !offersEnabled) null else
                worker.cargo?.let { offered.copy(cargo = it, carrying = true) } ?: offered }
            h.onPickup = { city, worker, _ -> h.owner.requireStorageArrival(city, worker, source.id) }
            h.owner.pulse(2_000)
            val courier = h.owner.collisionActors().single { it.uniqueId == courierId }
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
            val actor = runtime.actors.getValue(courierId)
            requireNotNull(actor.courierInboundPrefix)
            val route = requireNotNull(actor.route)
            val progress = route.motion.forwardProgress
            val before = courier.location
            if (ownedYield) {
                // The existing traffic fixture supplies a real higher-priority
                // neighbour; admission and all25 native side steps remain real.
                h.requestYieldAsReturning(h.workerId, courierId, before.clone().add(.8, 0.0, 0.0))
                val yielding = withClue(h.owner.debug(h.city.id, null)) { requireNotNull(actor.residentYield) }
                var ticks = 0
                while (actor.residentYield != null && ticks++ < 100) {
                    val previous = courier.location
                    h.navigate()
                    (previous.distance(courier.location) <= .050001) shouldBe true
                    (actor.route === route) shouldBe true
                    route.motion.forwardProgress shouldBe progress
                    h.pickups shouldBe 0
                    h.unloads shouldBe 0
                }
                actor.residentYield shouldBe null
                yielding.steps shouldBe 25
                (before.distance(courier.location) > 1.2) shouldBe true
                // The passer leaves after the physical yield, without publishing
                // a cargo or work acknowledgement for either resident.
                h.owner.collisionActors().single { it.uniqueId == h.workerId }
                    .teleport(Location(h.world, .5, 64.0, 10.5))
            } else {
                courier.teleport(before.clone().add(0.0, 0.0, 1.25))
                citizenPacketSupported(h.world, courier.location.point()) shouldBe true
            }
            for (tick in 1..800) {
                h.navigate()
                if (tick % 5 == 0) h.owner.pulseFieldWork(requireNotNull(h.navigationNow))
                if (h.pickups == 1) break
            }
            withClue(h.navigationState(courierId)) { h.pickups shouldBe 1 }
            (actor.courierReturnHint != null) shouldBe ownedYield
            val hint = actor.courierReturnHint
            val sourceFeet = courier.location
            h.city = CivsLogistics.pickup(h.city, courierId, offered.cargo)
            val now = requireNotNull(h.navigationNow)
            h.owner.pulseFieldWork(now + 250)
            h.phase(courierId) shouldBe CitizenPhase.PICKING_UP
            actor.courierReturnHint shouldBe hint
            actor.route shouldBe null
            courier.location shouldBe sourceFeet
            h.pendingPickup.isDone shouldBe false
            h.pendingPickup.complete(h.city)
            h.owner.pulseFieldWork(now + 500)
            h.phase(courierId) shouldBe CitizenPhase.RETURNING
            actor.courierReturnHint shouldBe null
            requireNotNull(actor.route).motion.hasCourierCorridorHint shouldBe ownedYield
            h.pickups shouldBe 1
            h.unloads shouldBe 0
        }
    }
})
