package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsAutonomousAccessTest : FunSpec({
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
