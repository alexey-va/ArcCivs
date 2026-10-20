package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsPathsTest : FunSpec({
    test("successful trips form a continuous trunk and nearby parallel trips do not form extra roads") {
        val city = pathsCity()
        val paths = CivsPaths()
        val main = paths.begin(city)
        val parallel = paths.begin(city) // Both routes were planned before either was learned.
        for (x in 0..20) {
            main.observe(CitizenPoint(x + .5, 64.0, .5))
            parallel.observe(CitizenPoint(x + .5, 64.0, 1.5))
        }
        paths.remembered(city.id) shouldBe emptySet()
        paths.complete(city, main)
        paths.complete(city, parallel)
        paths.pulse()
        paths.remembered(city.id).size shouldBe CivsPaths.CELLS_PER_TICK
        repeat(4) { paths.pulse() }
        paths.remembered(city.id) shouldBe (0..20).mapTo(linkedSetOf()) { CivPosition(it, 63, 0) }

        val branch = paths.begin(city)
        for (z in 0..6) branch.observe(CitizenPoint(12.5, 64.0, z + .5))
        paths.complete(city, branch)
        paths.pulse()
        paths.remembered(city.id).filter { it.z != 0 }.toSet() shouldBe
            (3..6).mapTo(hashSetOf()) { CivPosition(12, 63, it) }
        // Complete is idempotent; an abandoned trip never enters the learning queue.
        paths.complete(city, branch)
        val abandoned = paths.begin(city)
        abandoned.observe(CitizenPoint(1.5, 64.0, 20.5))
        abandoned.observe(CitizenPoint(2.5, 64.0, 20.5))
        repeat(4) { paths.pulse() }
        paths.remembered(city.id).none { it.z == 20 } shouldBe true
    }

    test("geometry changes, unloaded chunks and worlds retire remembered or pending corridors") {
        val city = pathsCity()
        val paths = CivsPaths()
        fun observed(current: Settlement = city) = paths.begin(current).also { trip ->
            for (x in 0..20) trip.observe(CitizenPoint(x + .5, 64.0, .5))
        }
        paths.complete(city, observed())
        repeat(3) { paths.pulse() }
        val stale = observed()
        val house = city.buildings.first().copy(id = UUID.randomUUID(), type = BuildingType.HOUSE,
            bounds = CivBox(CivPosition(8, 63, -1), CivPosition(11, 66, 1)))
        val changed = city.copy(buildings = city.buildings + house)
        paths.retain(mapOf(city.id to changed))
        paths.complete(changed, stale)
        paths.pulse()
        paths.remembered(city.id) shouldBe emptySet()
        paths.complete(changed, observed(changed))
        repeat(3) { paths.pulse() }
        paths.remembered(city.id).none { it.x in 8..11 } shouldBe true
        paths.invalidateBlock(city.worldId, CivPosition(5, 64, 0))
        paths.remembered(city.id).none { it.x in 4..6 } shouldBe true
        paths.invalidateChunk(city.worldId, 0, 0)
        paths.remembered(city.id) shouldBe emptySet()
        paths.complete(changed, observed(changed))
        paths.removeWorld(city.worldId)
        repeat(3) { paths.pulse() }
        paths.remembered(city.id) shouldBe emptySet()
    }

    test("trace, city and corridor memory have fixed ceilings") {
        val city = pathsCity().copy(radius = 1_024)
        val paths = CivsPaths()
        val oversized = paths.begin(city)
        for (x in 0..CivsPaths.MAX_TRIP_CELLS) oversized.observe(CitizenPoint(x + .5, 64.0, .5))
        paths.complete(city, oversized)
        repeat(20) { paths.pulse() }
        paths.remembered(city.id) shouldBe emptySet()

        for (z in listOf(0, 4, 8)) {
            val trip = paths.begin(city)
            for (x in 0 until CivsPaths.MAX_TRIP_CELLS) trip.observe(CitizenPoint(x + .5, 64.0, z + .5))
            paths.complete(city, trip)
        }
        repeat(60) { paths.pulse() }
        paths.remembered(city.id).size shouldBe CivsPaths.MAX_CELLS_PER_CITY
        repeat(CivsPaths.MAX_CITIES) { paths.begin(city.copy(id = UUID.randomUUID())) }
        paths.remembered(city.id) shouldBe emptySet()
        paths.clear()
    }

    test("full blocks paths slabs and negative heights identify their real support cell") {
        listOf(65.0, 64.9375, 64.5).map(::citizenSupportY) shouldBe listOf(64, 64, 64)
        citizenSupportY(-1.0625) shouldBe -2
    }
})

internal fun pathsCity(): Settlement {
    val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(-12, 63, -12), 0, "test",
        CivBox(CivPosition(-13, 63, -13), CivPosition(-10, 67, -10)), CivPosition(-11, 64, -9), BuildingPhase.ACTIVE)
    return Settlement(UUID.randomUUID(), UUID.randomUUID(), "Paths", UUID.randomUUID(), CivPosition(0, 64, 0),
        64, "test", Stock(), listOf(hall), emptyList())
}
