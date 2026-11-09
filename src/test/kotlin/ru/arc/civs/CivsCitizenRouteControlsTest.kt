package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import java.util.UUID

class CivsCitizenRouteControlsTest : FunSpec({
    test("a real route retains its control geometry once and keeps exact swept furniture boundaries") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val active = h.city.buildings.first().copy(control = CivPosition(0, 64, 0))
            val planned = CivBuilding(UUID.randomUUID(), BuildingType.HOUSE, 1, CivPosition(12, 64, 8),
                0, "house1", CivBox(CivPosition(10, 64, 6), CivPosition(14, 68, 10)),
                CivPosition(12, 64, 8), BuildingPhase.CONSTRUCTING, control = CivPosition(12, 64, 8))
            val order = CivConstruction(UUID.randomUUID(), planned.id, 1, 2, 0,
                ConstructionPhase.CONSTRUCTING, Stock(), plannedBuilding = planned)
            val buildings = listOf(active, farm)
            h.city = h.city.copy(buildings = buildings)
            h.world.getBlockAt(0, 64, 0).type = Material.BARREL
            h.owner.pulse(2_000)
            h.actor().npc.teleport(Location(h.world, 21.5, 64.0, .5))
            var reads = 0
            val observed = object : AbstractList<CivBuilding>() {
                override val size = buildings.size
                override fun get(index: Int): CivBuilding {
                    reads++
                    return buildings[index]
                }
            }
            h.city = h.city.copy(buildings = observed, construction = order)
            h.foodSource = active.id
            h.navigationNow = 3_000
            h.holdNavigation = true
            h.owner.pulse(3_000)
            val policy = requireNotNull(h.actor().route?.motion?.snapshotPolicy) { h.navigationState() }
            val controls = listOf(requireNotNull(active.control), requireNotNull(planned.control), requireNotNull(farm.control))

            for (p in controls) {
                fun at(dx: Double, dy: Double, dz: Double) = CitizenPoint(p.x + dx, p.y + dy, p.z + dz)
                val centre = at(.5, 0.0, .5)
                val near = at(-1.0, 0.0, .5)
                val far = at(2.0, 0.0, .5)
                policy.stepAllowed(near, far) shouldBe false
                policy.stepAllowed(centre, far) shouldBe true
                policy.stepAllowed(centre, centre) shouldBe false
                policy.stepAllowed(near, near) shouldBe true
                for ((from, to) in listOf(
                    near to at(-.3, 0.0, .5), far to at(1.3, 0.0, .5),
                    near to at(1.0, 0.0, -1.0), at(-1.0, 0.0, -1.0) to at(2.0, 0.0, 2.0),
                    at(-1.0, -1.8, .5) to at(2.0, -1.8, .5),
                    at(-1.0, -1.800001, .5) to at(2.0, -1.800001, .5),
                    at(-1.0, 1.01, .5) to at(2.0, 1.01, .5),
                    at(-1.0, 1.010001, .5) to at(2.0, 1.010001, .5),
                    at(-1.0, 0.0, -.3) to at(2.0, 0.0, -.3),
                    at(-1.0, 0.0, -.300001) to at(2.0, 0.0, -.300001),
                )) {
                    withClue("from=$from to=$to") {
                        val expected = originalControlStepClear(controls, from, to)
                        citizenControlStepClear(h.city, from, to) shouldBe expected
                        policy.stepAllowed(from, to) shouldBe expected
                    }
                }
                for (dy in -1..2) citizenControlClear(h.city, p.copy(y = p.y + dy)) shouldBe (dy !in 0..1)
            }

            val activeControl = controls.first()
            policy.allowed(activeControl.copy(y = activeControl.y - 1)) { Material.AIR } shouldBe true
            policy.allowed(activeControl) { Material.AIR } shouldBe false
            policy.allowed(activeControl.copy(y = activeControl.y + 1)) { Material.AIR } shouldBe false
            policy.allowed(activeControl.copy(y = activeControl.y + 2)) { Material.AIR } shouldBe true

            val before = reads
            repeat(128) {
                policy.stepAllowed(CitizenPoint(3.5, 64.0, 3.5), CitizenPoint(4.5, 64.0, 3.5)) shouldBe true
            }
            withClue("active snapshot edges reread the source building list") { reads - before shouldBe 0 }

            val nextControl = activeControl.copy(z = -2)
            val nextCity = h.city.copy(buildings = buildings.map {
                if (it.id == active.id) it.copy(control = nextControl) else it
            })
            val nextFeet = nextControl.point()
            policy.stepAllowed(nextFeet, nextFeet) shouldBe true
            citizenControlStepClear(nextCity, nextFeet, nextFeet) shouldBe false
        }
    }
})

private fun originalControlStepClear(controls: List<CivPosition>, from: CitizenPoint, to: CitizenPoint): Boolean {
    val origin = Vector(from.x, from.y, from.z)
    val destination = Vector(to.x, to.y, to.z)
    val delta = destination.clone().subtract(origin)
    val distance = delta.length()
    if (distance > 0) delta.normalize()
    return controls.none { p ->
        if (maxOf(from.x, to.x) < p.x - .3 || minOf(from.x, to.x) > p.x + 1.3 ||
            maxOf(from.y, to.y) < p.y - 1.8 || minOf(from.y, to.y) > p.y + 1.01 ||
            maxOf(from.z, to.z) < p.z - .3 || minOf(from.z, to.z) > p.z + 1.3) return@none false
        val obstacle = BoundingBox(p.x - .3, p.y - 1.8, p.z - .3, p.x + 1.3, p.y + 1.01, p.z + 1.3)
        obstacle.contains(destination) || !obstacle.contains(origin) && distance > 0 &&
            obstacle.rayTrace(origin, delta, distance) != null
    }
}
