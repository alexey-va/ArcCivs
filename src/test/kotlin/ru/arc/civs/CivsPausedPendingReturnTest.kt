package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import java.util.UUID

class CivsPausedPendingReturnTest : FunSpec({
    test("a returning courier cannot replace a paused accepted stock return even while the depot is full") {
        pausedPendingReturn { h, actor, _ ->
            val accepted = actor.plan
            val cycle = actor.cycle
            val original = h.city.workers.first { it.id == h.workerId }
            val before = h.city
            for (now in listOf(2_500L, 3_000L, 3_500L)) {
                h.owner.pulse(now)
                (actor.plan === accepted) shouldBe true
                (actor.cycle === cycle) shouldBe true
                actor.plan.worldWork shouldBe null
                h.owner.autonomousVisualWork(h.city.id, h.workerId) shouldBe null
                h.city shouldBe before
            }
            val routes = pendingReturnField(h.owner, "routes") as CivsCitizenRoutes
            routes.pause(actor, "storage-full", 4_000)
            h.city = h.city.copy(stock = Stock(food = CivsRules.capacity(h.city)))
            CivsAutonomousLogistics.pendingRoom(h.city, requireNotNull(original.pendingStock)) shouldBe false
            val full = h.city
            h.owner.pulse(4_000)
            (actor.plan === accepted) shouldBe true
            actor.plan.pendingStockDelivery shouldBe original.pendingStock
            h.city shouldBe full
            h.city.workers.first { it.id == h.workerId } shouldBe original
            h.deliveries shouldBe 0
            h.pickups shouldBe 0
            h.unloads shouldBe 0
            h.observedWork() shouldBe 0L
            h.autonomousObservedMillis() shouldBe 0L
        }
    }

    for (change in listOf("drained", "queue-changed", "cycle-changed", "schedule-changed", "depot-moved", "depot-unavailable"))
    test("a paused stock return releases its accepted plan when canonical $change") {
        pausedPendingReturn { h, actor, warehouse ->
            val accepted = actor.plan
            h.city = CivsRules.validate(h.city.copy(
                workers = h.city.workers.map { worker -> if (worker.id != h.workerId) {
                    // A retired warehouse cannot retain an assigned courier in a valid canonical snapshot.
                    if (change == "depot-unavailable" && worker.assignment == warehouse.id) worker.copy(assignment = null) else worker
                } else when (change) {
                    "drained" -> worker.copy(pendingStock = null)
                    "queue-changed" -> worker.copy(pendingStock = Stock(food = 320))
                    "cycle-changed" -> worker.copy(completedCycles = worker.completedCycles + 1)
                    "schedule-changed" -> worker.copy(nextDeliveryAt = worker.nextDeliveryAt + 1)
                    else -> worker
                } },
                buildings = h.city.buildings.map { building -> if (building.id != warehouse.id) building else when (change) {
                    "depot-moved" -> building.copy(entrance = building.entrance.copy(z = -2))
                    "depot-unavailable" -> building.copy(phase = BuildingPhase.SITE)
                    else -> building
                } }))
            val canonical = h.city
            val plans = pendingReturnField(h.owner, "plans") as CivsCitizenPlanning
            val next = plans.currentPlan(actor, canonical, canonical.workers.first { it.id == h.workerId })
            (next === accepted) shouldBe false
            h.owner.pulse(2_500)
            (actor.plan === accepted) shouldBe false
            h.city shouldBe canonical
            h.deliveries shouldBe 0
            h.pickups shouldBe 0
            h.unloads shouldBe 0
            h.observedWork() shouldBe 0L
        }
    }
})

private fun pausedPendingReturn(block: (ResidentRuntime, CitizenActor, CivBuilding) -> Unit) {
    residentRuntime(workerId = UUID(0, 20)) { h ->
        val farm = h.assignCanonicalAutoFarm()
        val warehouse = farm.copy(id = UUID(0, 44), type = BuildingType.WAREHOUSE,
            schematicId = "warehouse1", production = null, anchor = CivPosition(10, 64, 0),
            entrance = CivPosition(10, 64, -1), control = CivPosition(10, 64, 0),
            bounds = CivBox(CivPosition(8, 64, -2), CivPosition(12, 68, 2)))
        h.world.getBlockAt(10, 64, 0).type = Material.BARREL
        h.city = CivsRules.validate(h.city.copy(stock = Stock(), buildings = h.city.buildings + warehouse,
            workers = h.city.workers.map { it.copy(pendingStock = Stock(food = 384), nextDeliveryAt = 999_999) }))
        val work = h.autoCrop(farm, 0, 5_000)
        h.owner.collisionActors().single().teleport(Location(h.world,
            work.standing.x + .5, work.standing.y.toDouble(), work.standing.z + .5))
        h.holdNavigation = true
        h.navigationNow = 2_000
        h.owner.pulse(2_000)
        val actor = requireNotNull((pendingReturnField(h.owner, "actors") as Map<*, *>)[h.workerId]) as CitizenActor
        actor.cycle.phase shouldBe CitizenPhase.RETURNING
        actor.plan.pendingStockDelivery shouldBe Stock(food = 384)
        val routes = pendingReturnField(h.owner, "routes") as CivsCitizenRoutes
        routes.pause(actor, "route-unreachable", 2_000)
        actor.cycle.phase shouldBe CitizenPhase.PAUSED
        h.city = CivsRules.validate(h.city.copy(workers = h.city.workers +
            CivWorker(UUID(0, 43), "Replacement courier", warehouse.id, nextDeliveryAt = 1)))
        CivsAutonomousLogistics.staffed(h.city) shouldBe true
        block(h, actor, warehouse)
    }
}

private fun pendingReturnField(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).run {
    isAccessible = true
    get(target)
}
