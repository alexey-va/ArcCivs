package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import org.bukkit.Bukkit
import org.bukkit.Location
import java.util.UUID

class CivsResidentPulseDistributionTest : FunSpec({
    test("the service uses every spare tick and keeps the original five-tick handoffs and deadlines") {
        serviceScenario(empty = true) { h ->
            h.start()
            val groups = mutableListOf<Int>()
            val handoffs = mutableListOf<Int>()
            val deadlines = mutableListOf<Int>()
            var tick = 0
            val viewers = listOf(CitizenViewer(h.player.world.uid, h.player.location.point()))
            every { h.citizens.advanceNavigationFrame() } returns viewers
            every { h.citizens.pulseResidentGroup(any(), refEq(viewers)) } answers { groups += tick }
            every { h.citizens.pulseFieldWork(any()) } answers { handoffs += tick }
            every { h.citizens.pulse(any()) } answers { deadlines += tick }
            repeat(40) {
                tick++
                h.paper.performTicks(1)
            }
            groups shouldBe (1..40).filterNot { it in handoffs }
            handoffs.size shouldBe 8
            handoffs.zipWithNext { a, b -> b - a }.toSet() shouldBe setOf(5)
            deadlines.size shouldBe 2
            handoffs.containsAll(deadlines) shouldBe true
        }
    }

    test("every resident receives exactly one ordinary plan and equipment update across the sixteen ticks") {
        residentRuntime { h ->
            val residents = waitingResidents(h, 29)
            val planned = mutableListOf<UUID>()
            val equipped = mutableListOf<Pair<UUID, String>>()
            h.courierProvider = { _, worker -> planned += worker.id; null }
            for (worker in residents) {
                val npc = h.actor(worker.id).npc
                every { npc.sceneLabel = any() } answers { equipped += worker.id to firstArg<String>() }
            }
            fun actions(run: () -> Unit): List<UUID> {
                planned.clear()
                equipped.clear()
                run()
                planned shouldBe equipped.map { it.first }
                return planned.toList()
            }
            fun round(start: Long): List<List<UUID>> {
                val groups = mutableListOf<List<UUID>>()
                for (tick in 1..20) {
                    val at = start + 50 * tick
                    if (tick % 5 == 0) actions { h.owner.pulseFieldWork(at) } shouldBe emptyList()
                    else groups += actions { h.owner.pulseResidentGroup(at) }
                }
                actions { h.owner.pulse(start + 1_000) } shouldBe emptyList()
                return groups
            }
            val first = round(20_000L)
            first.map { it.size }.sorted() shouldBe List(3) { 1 } + List(13) { 2 }
            first.flatten().toSet() shouldBe residents.map { it.id }.toSet()
            round(21_000L).map { it.toSet() } shouldBe first.map { it.toSet() }

            val alreadyUpdated = actions { h.owner.pulseResidentGroup(22_050L) }
            val remainder = actions { h.owner.pulse(23_000L) }
            alreadyUpdated.size shouldBe first.first().size
            remainder.size shouldBe residents.size - alreadyUpdated.size
            (alreadyUpdated + remainder).toSet() shouldBe residents.map { it.id }.toSet()
            actions { h.owner.pulse(24_000L) }.size shouldBe residents.size
        }
    }

    test("small rosters keep their original spaced updates and can walk on their first ordinary turn") {
        for (count in 1..4) residentRuntime { h ->
            val residents = waitingResidents(h, count, BuildingType.BUILDER_HUT)
            for (worker in residents) {
                val actor = h.actor(worker.id)
                actor.route?.motion?.cancel()
                actor.route = null
                actor.wander.finish(9_999L, arrived = true, resumeImmediately = true)
            }
            val turns = mutableListOf<Int>()
            for (tick in (1..20).filter { it % 5 != 0 }) {
                val at = 10_000L + 50 * tick
                val before = residents.count { h.actor(it.id).route != null || !h.actor(it.id).wander.ready(at) }
                h.owner.pulseResidentGroup(at)
                val after = residents.count { h.actor(it.id).route != null || !h.actor(it.id).wander.ready(at) }
                if (before != after) turns += tick
            }
            turns shouldBe listOf(4, 9, 14, 19).take(count)
        }
    }

    test("each of sixteen groups can walk and look for company without exceeding the shared budgets") {
        residentRuntime { h ->
            val residents = waitingResidents(h, 32, BuildingType.BUILDER_HUT)
            h.paper.server.onlinePlayers.single().teleport(Location(h.world, 20.5, 64.0, .5))
            val searched = mutableSetOf<UUID>()
            val campSearches = IntArray(16)
            val walks = IntArray(16)
            repeat(16) { round ->
                // An empty fire search cools down for 100 server ticks; start each round with real fresh demand.
                h.paper.performTicks(101)
                val now = 20_000L + 6_000L * round
                for (worker in residents) {
                    val actor = h.actor(worker.id)
                    actor.route?.motion?.cancel()
                    actor.route = null
                    actor.wander.finish(now - 1, arrived = true, resumeImmediately = true)
                }
                val tick = Bukkit.getCurrentTick()
                fun ready() = residents.filter { h.actor(it.id).social.available(tick, 0, false) }.map { it.id }.toSet()
                fun walkers(at: Long) = residents.filter { worker ->
                    val actor = h.actor(worker.id)
                    actor.route != null || !actor.wander.ready(at)
                }.map { it.id }.toSet()
                val before = ready()
                for ((group, tickInRound) in (1..19).filter { it % 5 != 0 }.withIndex()) {
                    val at = now + 50L * tickInRound
                    val waiting = ready()
                    val walking = walkers(at)
                    h.owner.pulseResidentGroup(at)
                    val admitted = waiting - ready()
                    searched += admitted
                    campSearches[group] += admitted.size
                    walks[group] += (walkers(at) - walking).size
                }
                (before - ready()).size shouldBe 2
                walkers(now + 950).size shouldBe 8
                h.owner.pulse(now + 1_000)
                h.clearRecordedCalls()
            }
            searched shouldBe residents.map { it.id }.toSet()
            campSearches.toList() shouldBe List(16) { 2 }
            walks.toList() shouldBe List(16) { 8 }
        }
    }

    test("a group uses the current roster and cancels a retired actor before its turn") {
        residentRuntime { h ->
            val residents = waitingResidents(h)
            val updates = mutableListOf<Pair<UUID, String>>()
            h.courierProvider = { _, worker -> updates += worker.id to worker.name; null }
            h.owner.pulseResidentGroup(10_200L)
            val early = updates.map { it.first }.toSet()
            val later = residents.filter { it.id !in early }
            val retired = later[0]
            val renamed = later[1]
            val oldNpc = h.actor(retired.id).npc
            val previous = h.city
            h.city = previous.copy(workers = previous.workers.asReversed().filter { it.id != retired.id }
                .map { if (it.id == renamed.id) it.copy(name = "Updated") else it })
            h.owner.retireRemovedResidents(previous, h.city)
            oldNpc.isValid shouldBe false
            h.owner.activeCount() shouldBe 7
            h.owner.pulseResidentGroup(10_450L)
            h.owner.pulseResidentGroup(10_700L)
            h.owner.pulse(11_000L)
            updates.map { it.first }.toSet() shouldBe h.city.workers.map { it.id }.toSet()
            updates.size shouldBe 7
            updates.single { it.first == renamed.id }.second shouldBe "Updated"
            val current = h.city
            h.owner.retireRemovedResidents(current, null)
            h.owner.activeCount() shouldBe 0
            updates.clear()
            h.owner.pulseResidentGroup(11_200L)
            updates shouldBe emptyList()
        }
    }
})

private fun waitingResidents(h: ResidentRuntime, count: Int = 8, type: BuildingType = BuildingType.WAREHOUSE): List<CivWorker> {
    h.world.time = 6_000L
    val workplace = h.assignFarm().copy(type = type, schematicId = if (type == BuildingType.BUILDER_HUT) "builder1" else "warehouse1",
        production = null)
    val first = h.city.workers.single()
    val residents = listOf(first) + (2..count).map { first.copy(id = UUID.randomUUID(), name = "Courier $it") }
    h.city = h.city.copy(buildings = h.city.buildings.map { if (it.id == workplace.id) workplace else it }, workers = residents)
    for ((index, worker) in residents.withIndex()) {
        val point = Location(h.world, 14.5 + 3 * (index % 4), 64.0, -4.5 + 3 * (index / 4))
        h.owner.requestAdmissionAt(h.city.id, worker.id, point)
    }
    for (second in 2..count / 2 + 4) {
        h.owner.pulse(second * 1_000L)
        h.clearRecordedCalls()
    }
    h.owner.activeCount() shouldBe residents.size
    return residents
}
