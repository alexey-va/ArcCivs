package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.util.UUID

class CivsAutonomousAccessTest : FunSpec({
    test("one access context preserves retained precedence and protected geometry across repeated cells") {
        val base = accessCity()
        val hall = base.buildings.first().copy(control = CivPosition(16, 65, 6))
        val source = base.buildings.last()
        val route = (4..8).map { CivPosition(12, 65, it) }.toMutableList()
        val plan = accessAdoptedPlan(mapOf(hall.id.toString() to route))
        val city = base.copy(buildings = listOf(hall, source), autonomy = CivAutonomy(plan = plan))
        CivsSettlementPlans.current(city) shouldBe plan
        val access = CivAutonomousAccess(reservedFortificationPorts = listOf(
            CivBox(CivPosition(40, 60, 5), CivPosition(42, 70, 7))))
        val expected = mapOf(CivPosition(11, 64, 6) to false, CivPosition(10, 64, 6) to true,
            CivPosition(16, 64, 6) to false, CivPosition(0, 64, -2) to false,
            CivPosition(0, 64, 10) to true, CivPosition(40, 64, 6) to false, CivPosition(97, 64, 6) to false)
        mockkObject(CivsSettlementPlans)
        try {
            val context = CivAutonomousAccessContext(city)
            repeat(20) {
                expected.mapValues { (floor, _) -> access.forecourtClear(city, source, floor, context) } shouldBe expected
            }
            (civsTerrainKey(12, 6) in access.roadColumns(city, context)) shouldBe true
            val retained = access.copy(routes = mapOf(hall.id to listOf(CivPosition(20, 65, 6))))
            retained.forecourtClear(city, source, CivPosition(11, 64, 6), context) shouldBe true
            // The prepared fallback keeps its detached geometry if its caller-owned input is later changed.
            route.clear()
            access.forecourtClear(city, source, CivPosition(11, 64, 6), context) shouldBe false
            verify(exactly = 1) { CivsSettlementPlans.current(city) }
        } finally { unmockkObject(CivsSettlementPlans) }
    }

    test("invalid adopted intent is checked once and a context cannot cross city snapshots") {
        val base = accessCity()
        val source = base.buildings.last()
        val hall = base.buildings.first()
        val plan = accessAdoptedPlan(mapOf(hall.id.toString() to listOf(CivPosition(12, 65, 6))))
        val invalid = base.copy(autonomy = CivAutonomy(plan = plan.copy(version = -1)))
        val point = CivPosition(11, 64, 6)
        val access = CivAutonomousAccess()
        mockkObject(CivsSettlementPlans)
        try {
            val context = CivAutonomousAccessContext(invalid)
            repeat(20) { access.forecourtClear(invalid, source, point, context) shouldBe true }
            access.roadColumns(invalid, context) shouldBe emptySet()
            verify(exactly = 1) { CivsSettlementPlans.current(invalid) }

            val current = invalid.copy(revision = invalid.revision + 1, autonomy = CivAutonomy(plan = plan))
            shouldThrow<IllegalArgumentException> { access.forecourtClear(current, source, point, context) }
            shouldThrow<IllegalArgumentException> { access.entrancePath(current, source, listOf(source.entrance), context) }
            shouldThrow<IllegalArgumentException> { access.standing(current, source.id, context) }
            shouldThrow<IllegalArgumentException> { access.roadColumns(current, context) }
            val fresh = CivAutonomousAccessContext(current)
            access.forecourtClear(current, source, point, fresh) shouldBe false
            (civsTerrainKey(12, 6) in access.roadColumns(current, fresh)) shouldBe true
            verify(exactly = 1) { CivsSettlementPlans.current(current) }
        } finally { unmockkObject(CivsSettlementPlans) }
    }

    test("retained roads are detached bounded snapshots matched to the completed building") {
        val city = accessCity()
        val building = city.buildings.last()
        val route = (0..8).map { CivPosition(0, 65, it) }.toMutableList()
        val plan = accessPlan(city, building, route)
        val access = CivAutonomousAccess.from(city, listOf(plan))
        access.routes[building.id] shouldBe route
        route.clear()
        access.routes.getValue(building.id).size shouldBe 9
        access.standing(city, UUID.randomUUID())[civsTerrainKey(0, 5)] shouldBe CivPosition(0, 65, 5)
    }

    test("older entrance and spine grades win over a conflicting later retained road") {
        val original = accessCity()
        val forester = original.buildings.last()
        val quarry = forester.copy(id = UUID.randomUUID(), type = BuildingType.QUARRY,
            entrance = CivPosition(5, 66, 7), anchor = CivPosition(5, 65, 7))
        val city = original.copy(buildings = original.buildings + quarry)
        val oldRoute = (0..8).map { CivPosition(0, 65, it) }
        val lateRoute = (0..7).map { CivPosition(0, if (it >= 5) 66 else 65, it) } +
            (1..5).map { CivPosition(it, 66, 7) }
        val access = CivAutonomousAccess.from(city, listOf(accessPlan(city, quarry, lateRoute),
            accessPlan(city, forester, oldRoute)))
        access.routes.size shouldBe 2
        val standing = access.standing(city, UUID.randomUUID())
        standing[civsTerrainKey(0, 5)] shouldBe CivPosition(0, 65, 5)
        standing[civsTerrainKey(0, 7)] shouldBe CivPosition(0, 65, 7)
        standing[civsTerrainKey(5, 7)] shouldBe quarry.entrance
        access.standing(city, quarry.id).containsKey(civsTerrainKey(5, 7)) shouldBe false
    }

    test("foreign stale uncompleted and discontinuous journals cannot reserve a road") {
        val city = accessCity()
        val building = city.buildings.last()
        val plan = accessPlan(city, building, (0..8).map { CivPosition(0, 65, it) })
        listOf(plan.copy(cityId = UUID.randomUUID()), plan.copy(worldId = UUID.randomUUID()),
            plan.copy(building = building.copy(anchor = building.anchor.copy(y = 66))),
            plan.copy(terrain = plan.terrain!!.copy(route = listOf(CivPosition(0, 65, 0), building.entrance))),
            plan.copy(terrain = plan.terrain!!.copy(route = List(257) { building.entrance }))).forEach { invalid ->
            CivAutonomousAccess.from(city, listOf(invalid)).routes shouldBe emptyMap()
        }
        CivAutonomousAccess.from(city.copy(buildings = city.buildings.map { it.copy(phase = BuildingPhase.SITE) }),
            listOf(plan)).routes shouldBe emptyMap()
    }

    test("a pinned unprepared hall site preserves its actual entrance without inventing a flat terrace") {
        val original = accessCity()
        val hall = original.buildings.first().copy(phase = BuildingPhase.SITE)
        val city = original.copy(buildings = listOf(hall))
        CivAutonomousAccess().standing(city, UUID.randomUUID()).values.toList() shouldBe listOf(hall.entrance)
    }
})

private fun accessCity(): Settlement {
    val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(0, 64, 0), 0, "fixture",
        CivBox(CivPosition(-4, 64, -8), CivPosition(4, 70, -1)), CivPosition(0, 65, 0), BuildingPhase.ACTIVE)
    val forester = hall.copy(id = UUID.randomUUID(), type = BuildingType.FORESTER, anchor = CivPosition(0, 64, 8),
        bounds = CivBox(CivPosition(-4, 64, 9), CivPosition(4, 70, 16)), entrance = CivPosition(0, 65, 8), rotation = 180)
    return Settlement(UUID.randomUUID(), UUID.randomUUID(), "Access", UUID.randomUUID(), CivPosition(0, 64, 0),
        96, "fixture", Stock(), listOf(hall, forester), emptyList(), autonomy = CivAutonomy())
}

private fun accessPlan(city: Settlement, building: CivBuilding, route: List<CivPosition>): CivPlacementPlan =
    CivPlacementPlan(building.id, city.id, city.worldId, building, emptyList(), terrain = CivTerraformPlan(
        CivBox(CivPosition(-10, 63, -10), CivPosition(10, 68, 20)), emptyList(), route, 64, 0, 0))

private fun accessAdoptedPlan(routes: Map<String, List<CivPosition>>) = CivSettlementPlan(1, 0, 48,
    emptyList(), routes, emptyList(), listOf(CivBox(CivPosition(40, 64, 40), CivPosition(44, 64, 44))), listOf(0))
