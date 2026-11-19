package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import java.util.UUID

class CivsCitizenWanderExitTest : FunSpec({
    test("a farmer at the edge of a hay bale steps down onto the path before wandering") {
        residentRuntime(workerId = UUID.fromString("1ab10d1f-0176-4dab-aa06-f2c040403f96")) { h ->
            val farm = h.assignFarm()
            h.world.loadChunk(1, 0)
            // Auto4 after a full-barrel return. The toe overlaps hay, 17/16 above the adjacent dirt path.
            val hay = h.world.getBlockAt(20, 64, 1)
            hay.type = Material.HAY_BLOCK
            h.world.getBlockAt(21, 63, 1).type = Material.DIRT_PATH
            val start = CitizenPoint(21.156, 65.0, 1.5)
            val landing = CitizenPoint(21.5, 63.9375, 1.5)
            val walk = CitizenWanderWalk(farm, false, CivRouteTerritory(h.city), CitizenToken(farm.id, 0, 0, null),
                0, 4, farm, CivPosition(21, 65, 1), CivsPaths.RetainedRoads.EMPTY)
            fun choose() = citizenWanderPath(h.world, start, walk, h.city.buildings, CitizenWander(h.workerId), 0f, .3) { true }
            val path = choose()
            path.take(1) shouldBe listOf(landing)
            (path.size > 1) shouldBe true

            val from = Location(h.world, start.x, start.y, start.z)
            val route = CitizenPacketRoute(h.world, from, Location(h.world, landing.x, landing.y, landing.z),
                walk::permits, { a, b -> walk.permitsStep(a, b, .3) }, { 1.0 })
            route.readyWanderStep()
            var actual = from
            var movements = 0
            repeat(40) {
                route.move(actual, .55) { true }?.let { next ->
                    citizenPacketBodyClear(h.world, next.point(), false) shouldBe true
                    if (actual.point() != next.point()) movements++
                    actual = next
                }
            }
            route.finished shouldBe true
            actual.point() shouldBe landing
            (movements > 1) shouldBe true

            h.world.getBlockAt(21, 65, 1).type = Material.STONE
            choose() shouldBe emptyList()
            h.world.getBlockAt(21, 65, 1).type = Material.AIR
            hay.type = Material.AIR
            choose() shouldBe emptyList()
        }
    }

    test("an unassigned resident can move away from the entrance of her own Auto3 house") {
        residentRuntime(workerId = UUID.fromString("eb3bb491-61ac-46de-8992-94dd5e7486e3")) { h ->
            val hall = h.city.buildings.single()
            val entrance = CivPosition(27, 64, 13)
            val home = hall.copy(id = UUID(0, 41), type = BuildingType.HOUSE, anchor = entrance, entrance = entrance,
                bounds = CivBox(CivPosition(11, 62, 6), CivPosition(26, 75, 19)), control = null)
            h.city = h.city.copy(buildings = listOf(hall, home), workers = h.city.workers.map { it.copy(homeId = home.id) })
            for (x in 0..2) for (z in -1..1) h.world.loadChunk(x, z)
            // The east and south edges are obstructed. North and west are both beyond eight blocks from the door.
            for ((x, z) in listOf(21 to 10, 20 to 11)) for (y in 64..66) h.world.getBlockAt(x, y, z).type = Material.STONE
            val actor = h.actor()
            val origin = Location(h.world, 20.5, 64.0, 10.5)
            actor.npc.teleport(origin)
            val before = h.world.chunkSnapshotCaptures
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            val walked = mutableSetOf<Pair<Int, Int>>()
            for (tick in 1..120) {
                h.navigate()
                if (tick % 10 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                val at = actor.npc.location
                walked += at.blockX to at.blockZ
                if (origin.distance(at) > 2) break
            }
            withClue("walked=$walked") {
                walked.any { (x, z) -> x < 20 || z < 10 } shouldBe true
                (actor.npc.location.distance(origin) > 2) shouldBe true
                h.world.chunkSnapshotCaptures shouldBe before
                h.pendingNavigationWorkers shouldBe 0
            }
        }
    }

    for (blocked in listOf(false, true)) {
        val scenario = if (blocked) "can walk locally while a private neighbour seals the detour"
            else "walks away from the exit first and resumes on the public road"
        test("an idle resident inside the Auto3 house $scenario") {
            residentRuntime(workerId = UUID(0, 42)) { h ->
                // The measured 24-edge floor route relative to the Auto3 forester's feet.
                // Its west turn around the interior hedge is sqrt(109) blocks from the entrance.
                val floor = buildSet {
                    for (x in 14..20) add(x to 10)
                    for (z in 7..10) add(14 to z)
                    for (x in 14..16) add(x to 7)
                    for (x in 16..23) add(x to 6)
                    add(23 to 7)
                    for (z in 7..10) add(24 to z)
                    for (x in 24..30) add(x to 10)
                }
                val hall = h.city.buildings.single()
                val door = CivPosition(24, 64, 10)
                val house = hall.copy(id = UUID(0, 43), type = BuildingType.HOUSE, anchor = CivPosition(15, 64, 10),
                    bounds = CivBox(CivPosition(8, 62, 3), CivPosition(23, 70, 16)), entrance = door,
                    control = CivPosition(16, 64, 9))
                val job = CivPosition(39, 64, 20)
                val forester = hall.copy(id = UUID(0, 44), type = BuildingType.FORESTER, anchor = job, entrance = job,
                    control = job, bounds = CivBox(job, job))
                val privateCell = CivPosition(14, 64, 8)
                val neighbour = house.copy(id = UUID(0, 45), bounds = CivBox(privateCell, privateCell.copy(y = 68)),
                    entrance = privateCell, anchor = privateCell, control = null)
                h.city = h.city.copy(buildings = listOfNotNull(hall, house, forester, neighbour.takeIf { blocked }),
                    workers = h.city.workers.map { it.copy(assignment = forester.id) })
                for (x in -1..3) for (z in -1..2) h.world.loadChunk(x, z)
                for (x in 7..31) for (z in 2..17) if (x to z !in floor)
                    for (y in 64..66) h.world.getBlockAt(x, y, z).type = Material.STONE
                for (x in 25..30) h.world.getBlockAt(x, 63, 10).type = Material.DIRT_PATH

                val actor = h.actor()
                val origin = Location(h.world, 20.5, 64.0, 10.5)
                actor.npc.teleport(origin)
                h.idleReason = "waiting-orders"
                h.navigationNow = 3_000
                h.owner.pulse(3_000)
                h.navigationNow = 3_500
                h.owner.pulse(3_500)
                actor.plan.idleReason shouldBe "waiting-orders"
                val exit = requireNotNull(actor.route)
                exit.motion.preparationPhase shouldBe "local"
                exit.allowedBuildings shouldBe setOf(house.id)

                val visited = mutableSetOf<Pair<Int, Int>>()
                for (tick in 1..1_000) {
                    if (tick % 10 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                    h.navigate()
                    val at = actor.npc.location
                    visited += at.blockX to at.blockZ
                    if ((25 to 10) in visited) break
                }
                withClue("visited=$visited ${h.navigationState()}") {
                    visited.all { it in floor } shouldBe true
                    if (blocked) {
                        (19 to 10 in visited) shouldBe true
                        (14 to 8 in visited) shouldBe false
                        (25 to 10 in visited) shouldBe false
                    } else {
                        visited.containsAll(listOf(19 to 10, 14 to 7, 25 to 10)) shouldBe true
                    }
                    h.pendingNavigationWorkers shouldBe 0
                    h.deliveries shouldBe 0
                }
            }
        }
    }
})
