package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsIndustrialStationJourneyTest : FunSpec({
    test("a physical furnace session returns to its barrel before one guarded commit and an expired adult retires after that cycle") {
        residentRuntime(workerId = UUID(0, 71)) { h ->
            var selections = 0
            var courierSelections = 0
            val (building, station) = prepareIndustrialStation(h) { selections++ }
            h.courierProvider = { _, _ -> courierSelections++; null }
            val actor = industrialStationActor(h)
            val cycle = actor.cycle
            val token = actor.plan.token
            val required = actor.plan.workMillis
            val items = h.city.workers.single().items
            val inventory = h.city.inventory
            val stock = h.city.stock
            var commits = 0
            h.workerDelivery = { workerId, proof ->
                // The public callback carries the base identity; WorkerCommits re-derives the recipe token.
                proof shouldBe token.copy(industryRecipe = null)
                h.owner.requireSelectedWorkDelivery(h.city.id, workerId, actor.plan.token)
                actor.cycle.phase shouldBe CitizenPhase.DELIVERING
                (actor.cycle === cycle) shouldBe true
                (h.observedWork() >= required) shouldBe true
                citizenAtStorage(h.world, actor.npc.location.point(), requireNotNull(building.control)) shouldBe true
                (actor.npc.location.point().distanceSquared(station.standing.point()) > 4.0 * 4.0) shouldBe true
                h.city.workers.single().items shouldBe items
                h.city = CivsRules.deliver(h.city, h.city.owner, workerId, building.id,
                    proof.completedCycles, proof.nextDeliveryAt, requireNotNull(h.navigationNow), outputHandled = true)
                commits++
                CompletableFuture.completedFuture(h.city)
            }
            var atMachine = false
            var returned = false
            var expired = false
            for (step in 1..500) {
                if (commits == 1) break
                advanceIndustrialStation(h)
                (industrialStationActor(h) === actor) shouldBe true
                (actor.cycle === cycle) shouldBe true
                actor.plan.token shouldBe token
                when (actor.cycle.phase) {
                    CitizenPhase.WORKING -> {
                        atMachine = true
                        citizenAtStorage(h.world, actor.npc.location.point(), requireNotNull(building.control)) shouldBe false
                        actor.plan.workstation shouldBe station
                        commits shouldBe 0
                        if (!expired && h.observedWork() >= 1_000) {
                            val lifetime = CivsResidentLife.lifespanMinutes(h.city.workers.single())
                            h.city = h.city.copy(workers = h.city.workers.map { it.copy(
                                life = CivResidentLife(awakeMinutes = lifetime, adultMinutes = lifetime)) })
                            expired = true
                        }
                        h.city.workers.single().completedCycles shouldBe 0L
                    }
                    CitizenPhase.RETURNING -> {
                        returned = true
                        (h.observedWork() >= required) shouldBe true
                        h.city.workers.single().items shouldBe items
                        commits shouldBe 0
                    }
                    else -> Unit
                }
            }
            withClue(h.navigationState()) {
                atMachine shouldBe true
                returned shouldBe true
                expired shouldBe true
                commits shouldBe 1
            }
            h.city.workers.single().completedCycles shouldBe 1L
            CivsEquipment.plainCount(h.city.workers.single(), "COBBLESTONE") shouldBe 0
            CivsEquipment.plainCount(h.city.workers.single(), "CHARCOAL") shouldBe 0
            CivsEquipment.plainCount(h.city.workers.single(), "STONE") shouldBe 0
            h.city.inventory shouldBe inventory
            h.city.stock shouldBe stock
            val selectedBeforeRetirement = selections
            val couriersBeforeRetirement = courierSelections
            repeat(3) {
                h.navigationNow = requireNotNull(h.navigationNow) + 1_000
                h.owner.pulse(requireNotNull(h.navigationNow))
            }
            val retiring = industrialStationActor(h)
            retiring.plan.idleReason shouldBe "old-age-retirement"
            retiring.plan.worldWork shouldBe null
            retiring.plan.courier shouldBe null
            h.phase() shouldBe CitizenPhase.IDLE
            h.owner.canReassign(h.city.id, h.workerId) shouldBe true
            selections shouldBe selectedBeforeRetirement
            courierSelections shouldBe couriersBeforeRetirement
            h.deliveries shouldBe 1
            commits shouldBe 1
        }
    }

    test("a removed furnace stops observed work and restoration resumes the same accepted cycle") {
        residentRuntime(workerId = UUID(0, 72)) { h ->
            var selections = 0
            val (building, station) = prepareIndustrialStation(h) { selections++ }
            val actor = industrialStationActor(h)
            val cycle = actor.cycle
            val token = actor.plan.token
            val items = h.city.workers.single().items
            val before = h.city
            var commits = 0
            val pending = CompletableFuture<Settlement>()
            h.workerDelivery = { workerId, proof ->
                proof shouldBe token.copy(industryRecipe = null)
                h.owner.requireSelectedWorkDelivery(h.city.id, workerId, actor.plan.token)
                citizenAtStorage(h.world, actor.npc.location.point(), requireNotNull(building.control)) shouldBe true
                (h.observedWork() >= actor.plan.workMillis) shouldBe true
                commits++
                pending
            }
            for (step in 1..100) {
                if (actor.cycle.phase == CitizenPhase.WORKING && h.observedWork() >= 1_000) break
                advanceIndustrialStation(h)
            }
            withClue(h.navigationState()) { actor.cycle.phase shouldBe CitizenPhase.WORKING }
            val observed = h.observedWork()
            (observed >= 1_000) shouldBe true
            val focus = requireNotNull(station.focus)
            h.world.getBlockAt(focus.x, focus.y, focus.z).type = Material.AIR
            repeat(10) {
                advanceIndustrialStation(h)
                (industrialStationActor(h) === actor) shouldBe true
                (actor.cycle === cycle) shouldBe true
                actor.plan.token shouldBe token
                actor.plan.workstation shouldBe station
                h.observedWork() shouldBe observed
                h.city shouldBe before
                commits shouldBe 0
            }
            h.owner.status(h.workerId) shouldBe "workers.status.workstation-unavailable"
            h.world.getBlockAt(focus.x, focus.y, focus.z).type = Material.FURNACE
            for (step in 1..500) {
                if (commits == 1) break
                advanceIndustrialStation(h)
                (actor.cycle === cycle) shouldBe true
                actor.plan.token shouldBe token
            }
            withClue(h.navigationState()) { commits shouldBe 1 }
            selections shouldBe 1
            h.observedWork() shouldBe actor.plan.workMillis
            h.city shouldBe before
            h.city.workers.single().items shouldBe items
            h.city.workers.single().completedCycles shouldBe 0L
            h.deliveries shouldBe 1
        }
    }
})

private fun prepareIndustrialStation(h: ResidentRuntime, selected: () -> Unit): Pair<CivBuilding, CivWorkstation> {
    for (x in -2..3) for (z in -2..2) h.world.loadChunk(x, z)
    val building = h.assignFarm().copy(type = BuildingType.SMELTERY, schematicId = "smeltery1", production = "STONE",
        bounds = CivBox(CivPosition(18, 63, -3), CivPosition(32, 68, 3)))
    val station = CivWorkstation(CivPosition(29, 64, 0), CivPosition(30, 64, 0), "minecraft:furnace")
    val focus = requireNotNull(station.focus)
    h.world.getBlockAt(focus.x, focus.y, focus.z).type = Material.FURNACE
    h.city = h.city.copy(buildings = h.city.buildings.map { if (it.id == building.id) building else it },
        workers = h.city.workers.map { it.copy(items = List(CivsEquipment.SLOTS) { slot -> when (slot) {
            4 -> CivResidentItem("IRON_PICKAXE", 1)
            7 -> CivResidentItem("COBBLESTONE", 8)
            8 -> CivResidentItem("CHARCOAL", 1)
            else -> null
        } }) })
    h.workstationProvider = { _, _ -> selected(); station }
    h.recreateResidents()
    h.navigationNow = 2_000
    h.owner.pulse(2_000)
    CivsWorkstations.valid(h.city, building, h.world, station) shouldBe true
    industrialStationActor(h).plan.workplace shouldBe station.standing
    industrialStationActor(h).plan.completeAtWorkplace shouldBe false
    return building to station
}

/** Real packet navigation between actions; one-second owner observations while standing at the machine. */
private fun advanceIndustrialStation(h: ResidentRuntime) {
    if (h.phase() == CitizenPhase.WORKING) h.navigationNow = requireNotNull(h.navigationNow) + 1_000
    else repeat(5) {
        val npc = h.owner.collisionActors().single()
        val before = npc.location
        h.navigate()
        (before.distance(npc.location) <= .172001) shouldBe true
    }
    h.owner.pulse(requireNotNull(h.navigationNow))
}

private fun industrialStationActor(h: ResidentRuntime): CitizenActor =
    h.owner.javaClass.getDeclaredField("actors").run {
        isAccessible = true
        requireNotNull((get(h.owner) as Map<*, *>)[h.workerId]) as CitizenActor
    }
