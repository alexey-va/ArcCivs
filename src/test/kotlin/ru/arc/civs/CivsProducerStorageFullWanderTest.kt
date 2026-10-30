package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsProducerStorageFullWanderTest : FunSpec({
    for (resume in listOf("retry", "capacity"))
    test("a definite full output store permits local walking and resumes $resume without work credit") {
        residentRuntime(workerId = UUID(0, 20)) { h ->
            // A barrel detour may cross chunk zero. Its subsequent real work route
            // needs the same loaded one-chunk margin as the leisure route's corridor.
            for (x in -2..3) for (z in -2..2) h.world.loadChunk(x, z)
            val workplace = h.assignFarm()
            val npc = h.owner.collisionActors().single()
            npc.teleport(Location(h.world, 25.5, 64.0, .5))
            val focus = CivPosition(26, 63, 0)
            val work = CivWorldWork(h.workerId, 0, workplace.id, CivPosition(27, 64, 0),
                listOf(CivPlanBlock(focus, "minecraft:stone", "minecraft:air")),
                outputs = mapOf("WHEAT" to 1L), durationMillis = 6_000, status = "working-field", focus = focus)
            h.work = work
            h.owner.pulse(2_000)
            h.owner.pulse(2_500)
            h.phase() shouldBe CitizenPhase.WORKING
            h.observedWork() shouldBe 500L
            val actor = producerWanderActor(h)
            val acceptedCycle = actor.cycle
            val acceptedPlan = actor.plan
            val originalItems = h.city.workers.single().items
            val fullBag = List(CivsEquipment.SLOTS) { slot ->
                if (slot >= CivsEquipment.BAG) CivResidentItem("COBBLESTONE", 64) else null
            }
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(items = fullBag) })
            val fullCity = h.city
            val pendingRetry = CompletableFuture<Settlement>()
            var attempts = 0
            h.owner.returnResidentOutputs = { cityId, workerId, buildingId ->
                buildingId shouldBe workplace.id
                h.owner.requireStorageArrival(cityId, workerId, buildingId)
                attempts++
                if (attempts == 1) CompletableFuture.failedFuture(CivsRejection("storage-full")) else pendingRetry
            }
            h.navigationNow = 3_500
            h.owner.pulse(3_500)
            for (tick in 1..300) {
                if (attempts == 1) break
                h.navigate()
                if (tick % 5 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
            }
            withClue(h.navigationState()) { attempts shouldBe 1 }
            h.navigate()
            h.owner.pulse(requireNotNull(h.navigationNow))
            actor.outputReturn shouldBe null
            actor.supplyFailure shouldBe (workplace.id to "storage-full")
            val retryAt = actor.supplyRetryAt
            val pausedAt = npc.location
            val roadSteps = h.steps.size
            var walked = 0.0
            for (tick in 1..80) {
                val before = npc.location
                h.navigate()
                if (tick % 5 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                val distance = before.distance(npc.location)
                (distance <= .118251) shouldBe true
                walked += distance
                citizenWithinIdleArea(workplace.entrance, npc.location.point()) shouldBe true
                (producerWanderActor(h) === actor) shouldBe true
                (actor.cycle === acceptedCycle) shouldBe true
                actor.plan shouldBe acceptedPlan
                h.observedWork() shouldBe 500L
                h.city shouldBe fullCity
                h.deliveries shouldBe 0
                attempts shouldBe 1
                if (npc.location.distance(pausedAt) >= 1.0 && actor.route?.idleAnchor != null) break
            }
            withClue("walked=$walked at=${npc.location} ${h.navigationState()}") {
                (walked >= 1.0) shouldBe true
                (npc.location.distance(pausedAt) >= 1.0) shouldBe true
                actor.route?.idleAnchor shouldBe workplace.entrance
            }
            h.steps.size shouldBe roadSteps
            val leisureRoute = actor.route
            val beforeResume = "phase=${actor.cycle.phase} reason=${actor.cycle.reason} detour=${actor.workDetour} " +
                "at=${npc.location} work=${actor.plan.workplace} now=${h.navigationNow} retry=$retryAt"
            if (resume == "capacity") {
                (requireNotNull(h.navigationNow) < retryAt) shouldBe true
                h.city = h.city.copy(workers = h.city.workers.map { it.copy(items = originalItems) })
                h.owner.pulse(requireNotNull(h.navigationNow))
                actor.supplyFailure shouldBe null
                actor.outputReturn shouldBe null
                (actor.route === leisureRoute) shouldBe false
                actor.route?.idleAnchor shouldBe null
                attempts shouldBe 1
            } else {
                h.navigationNow = retryAt
                h.owner.pulse(retryAt)
                (actor.route === leisureRoute) shouldBe false
                actor.route?.idleAnchor shouldBe null
                for (tick in 1..600) {
                    if (attempts == 2) break
                    h.navigate()
                    if (tick % 5 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                }
                withClue(h.navigationState()) { attempts shouldBe 2 }
                actor.supplyPickup?.returnOutputs shouldBe true
                h.city shouldBe fullCity
            }
            (producerWanderActor(h) === actor) shouldBe true
            (actor.cycle === acceptedCycle) shouldBe true
            actor.plan shouldBe acceptedPlan
            withClue("$beforeResume afterPhase=${actor.cycle.phase} afterReason=${actor.cycle.reason} " +
                "afterDetour=${actor.workDetour} ${h.navigationState()}") { h.observedWork() shouldBe 500L }
            h.city.workers.single().completedCycles shouldBe 0L
            h.deliveries shouldBe 0
            h.pickups shouldBe 0
            h.unloads shouldBe 0
        }
    }
})

private fun producerWanderActor(h: ResidentRuntime): CitizenActor =
    h.owner.javaClass.getDeclaredField("actors").run {
        isAccessible = true
        requireNotNull((get(h.owner) as Map<*, *>)[h.workerId]) as CitizenActor
    }
