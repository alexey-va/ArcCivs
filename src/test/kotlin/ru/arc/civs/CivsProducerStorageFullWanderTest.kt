package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsProducerStorageFullWanderTest : FunSpec({
    for (resume in listOf("retry", "capacity", "receipt"))
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
            val retryAt = requireNotNull(actor.outputFullPause).retryAt
            actor.supplyRetryAt shouldBe 0L
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
                actor.outputFullPause shouldBe null
                actor.outputReturn shouldBe null
                (actor.route === leisureRoute) shouldBe false
                actor.route?.idleAnchor shouldBe null
                attempts shouldBe 1
            } else {
                h.navigationNow = retryAt
                h.owner.pulse(retryAt)
                (actor.route === leisureRoute) shouldBe true
                actor.route?.idleAnchor shouldBe workplace.entrance
                h.owner.status(h.workerId) shouldBe "workers.status.storage-full"
                for (tick in 1..600) {
                    if (attempts == 2) break
                    h.navigate()
                    if (tick % 5 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                }
                withClue(h.navigationState()) { attempts shouldBe 2 }
                actor.supplyPickup?.returnOutputs shouldBe true
                h.city shouldBe fullCity
                if (resume == "receipt") {
                    h.city = h.city.copy(workers = h.city.workers.map { it.copy(items = originalItems) })
                    pendingRetry.complete(h.city)
                    h.owner.pulse(requireNotNull(h.navigationNow))
                    actor.outputFullPause shouldBe null
                    actor.outputReturn shouldBe null
                    actor.supplyPickup shouldBe null
                    actor.route?.idleAnchor shouldBe null
                }
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

    test("a partial searching miner keeps its full-store pause through food success and a retry boundary") {
        residentRuntime(workerId = UUID(0, 20)) { h ->
            for (x in -2..3) for (z in -2..2) h.world.loadChunk(x, z)
            val quarry = h.assignQuarry().copy(level = 2,
                schematicId = "civs/minecolonies/caledonia/fundamentals/miner2")
            val bag = List<CivResidentItem?>(CivsEquipment.SLOTS) { slot -> when (slot) {
                4 -> CivResidentItem("IRON_PICKAXE", 1)
                7 -> CivResidentItem("LADDER", 14)
                8 -> CivResidentItem("TORCH", 7)
                9 -> CivResidentItem("COBBLESTONE", 33)
                10 -> CivResidentItem("DARK_OAK_LOG", 32)
                11 -> CivResidentItem("DARK_OAK_PLANKS", 32)
                12 -> CivResidentItem("DARK_OAK_FENCE", 16)
                else -> null
            } }
            h.city = h.city.copy(buildings = h.city.buildings.map { if (it.id == quarry.id) quarry else it },
                workers = h.city.workers.map { it.copy(items = bag, completedCycles = 6_278) })
            CivsLogistics.workerExcess(h.city, h.city.workers.single()) shouldBe mapOf("COBBLESTONE" to 1L)
            h.idleReason = "waiting-orders"
            val npc = h.owner.collisionActors().single()
            npc.teleport(Location(h.world, 19.5, 64.0, .5))
            var attempts = 0
            h.owner.returnResidentOutputs = { city, worker, source ->
                h.owner.requireStorageArrival(city, worker, source)
                attempts++
                CompletableFuture.failedFuture(CivsRejection("storage-full"))
            }
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            attempts shouldBe 1
            val actor = producerWanderActor(h)
            val acceptedPlan = actor.plan
            val acceptedCycle = actor.cycle
            h.owner.pulse(3_000)
            val pause = requireNotNull(actor.outputFullPause)
            h.idleReason = "searching-work"
            val focus = CivPosition(26, 61, 0)
            h.work = CivWorldWork(h.workerId, 6_278, quarry.id, CivPosition(25, 64, 0),
                listOf(CivPlanBlock(focus, "minecraft:air", "minecraft:ladder[facing=west,waterlogged=false]")),
                inputs = mapOf("LADDER" to 1L), status = "digging-mine", miningStep = 4, miningPlanVersion = 7)
            val searches = h.workPlans
            // A real food/supply completion clears the shared supply failure, but owns no output receipt.
            npc.teleport(Location(h.world, 19.5, 64.0, .5))
            h.foodSource = quarry.id
            h.owner.pulse(4_000)
            h.supplyRequests shouldBe 1
            actor.supplyPickup?.returnOutputs shouldBe false
            actor.outputFullPause shouldBe pause
            h.foodSource = null
            h.pendingSupplies.complete(h.city)
            h.holdNavigation = true
            h.navigationNow = 4_500
            h.owner.pulse(4_500)
            actor.supplyFailure shouldBe null
            actor.outputFullPause shouldBe pause
            for (pulse in 1..8) {
                if (actor.route?.idleAnchor != null) break
                h.navigationNow = 4_500L + pulse * 250
                h.owner.pulse(requireNotNull(h.navigationNow))
            }
            val localWalk = requireNotNull(actor.route)
            localWalk.idleAnchor shouldBe quarry.entrance
            h.navigationNow = pause.retryAt + 250
            h.owner.pulse(requireNotNull(h.navigationNow))
            (actor.route === localWalk) shouldBe true
            h.owner.status(h.workerId) shouldBe "workers.status.storage-full"
            (actor.cycle === acceptedCycle) shouldBe true
            actor.plan shouldBe acceptedPlan
            h.workPlans shouldBe searches
            attempts shouldBe 1
            val from = npc.location
            h.releaseNavigation()
            for (tick in 1..100) {
                h.navigate()
                if (tick % 5 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                citizenWithinIdleArea(quarry.entrance, npc.location.point()) shouldBe true
                if (npc.location.distance(from) >= .5) break
            }
            withClue(h.navigationState()) { (npc.location.distance(from) >= .5) shouldBe true }
            attempts shouldBe 1
            h.steps shouldBe emptyList()
            h.city.workers.single().items shouldBe bag
            // Actual excess disappeared (for example, a courier took it); admission may now search again.
            val retained = bag.toMutableList().also { it[9] = CivResidentItem("COBBLESTONE", 32) }
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(items = retained) })
            h.owner.pulse(requireNotNull(h.navigationNow))
            actor.outputFullPause shouldBe null
            actor.route?.idleAnchor shouldBe null
            actor.plan.worldWork shouldBe h.work
            h.phase() shouldBe CitizenPhase.TO_WORK
            (h.workPlans > searches) shouldBe true
            h.city.workers.single().completedCycles shouldBe 6_278L
            h.deliveries shouldBe 0
            h.pickups shouldBe 0
            h.unloads shouldBe 0
        }
    }

    test("a retained waiting-tool mine plan cannot bypass a definitely rejected output transfer") {
        residentRuntime(workerId = UUID(0, 20)) { h ->
            for (x in -2..3) for (z in -2..2) h.world.loadChunk(x, z)
            val quarry = h.assignQuarry()
            h.city = h.city.copy(workers = h.city.workers.map { worker -> worker.copy(items = List(CivsEquipment.SLOTS) { slot ->
                when (slot) {
                    9 -> CivResidentItem("COBBLESTONE", 64)
                    10 -> CivResidentItem("COBBLESTONE", 32)
                    else -> null
                }
            }) })
            val actor = producerWanderActor(h)
            // Reproduce the retained tool plan present when an earlier output request resolves.
            val waiting = CivWorldWork(h.workerId, 0, quarry.id, CivPosition(21, 64, 0), emptyList(), status = "waiting-tool")
            actor.plan = requireNotNull(citizenPlan(h.city, h.city.workers.single())).copy(
                workplace = waiting.standing, worldWork = waiting, idleReason = null)
            actor.cycle = actor.plan.cycle()
            val cycle = actor.cycle
            val plan = actor.plan
            actor.npc.teleport(Location(h.world, 19.5, 64.0, .5))
            actor.outputReturn = quarry.id
            h.owner.returnResidentOutputs = { city, worker, source ->
                h.owner.requireStorageArrival(city, worker, source)
                CompletableFuture.failedFuture(CivsRejection("storage-full"))
            }
            val logistics = h.owner.javaClass.getDeclaredField("logistics").run {
                isAccessible = true
                get(h.owner) as CivsCitizenLogistics
            }
            logistics.beginSupplies(actor, quarry.id, 2_000, resumeWork = false)
            h.navigationNow = 3_000
            h.owner.pulse(3_000)
            for (pulse in 1..8) {
                if (actor.route?.idleAnchor != null) break
                h.navigationNow = 3_000L + pulse * 250
                h.owner.pulse(requireNotNull(h.navigationNow))
            }
            actor.outputFullPause?.buildingId shouldBe quarry.id
            h.owner.status(h.workerId) shouldBe "workers.status.storage-full"
            (actor.cycle === cycle) shouldBe true
            actor.plan shouldBe plan
            h.workPlans shouldBe 0
            actor.route?.idleAnchor shouldBe quarry.entrance
            h.observedWork() shouldBe 0L
            h.deliveries shouldBe 0
            h.city.workers.single().items?.filterNotNull()?.sumOf { it.amount } shouldBe 96
        }
    }
})

private fun producerWanderActor(h: ResidentRuntime): CitizenActor =
    h.owner.javaClass.getDeclaredField("actors").run {
        isAccessible = true
        requireNotNull((get(h.owner) as Map<*, *>)[h.workerId]) as CitizenActor
    }
