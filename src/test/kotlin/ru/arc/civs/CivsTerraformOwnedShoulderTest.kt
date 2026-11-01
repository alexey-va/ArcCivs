package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsTerraformOwnedShoulderTest : FunSpec({
    for (atEntrance in listOf(false, true))
    test("an ordinary ${if (atEntrance) "approach" else "reserved route"} preserves the exact native grade of its paid road shoulder") {
        val fixture = OwnedShoulderFixture(atEntrance = atEntrance)
        val draft = fixture.draft()
        draft.route shouldBe fixture.route
        val x = if (atEntrance) 15 else 5
        val shoulder = draft.columns.single { it.x == x && it.z == 0 }
        shoulder.groundY shouldBe 64
        shoulder.floorY shouldBe 64
        shoulder.preserve shouldBe true
        fixture.surface.at(x, 0) shouldBe CivTerrainSurfaceColumn(64, false, dirtPath = true)
    }

    test("a dirt path without retained paid route context remains protected") {
        shouldThrow<CivsRejection> { OwnedShoulderFixture(owned = false).draft() }.reason shouldBe "autonomy-terrain"
    }

    for ((name, column) in listOf(
        "non-path" to CivTerrainSurfaceColumn(64, false),
        "covered-path" to CivTerrainSurfaceColumn(64, false, topY = 65, dirtPath = true),
        "different-requested-grade" to CivTerrainSurfaceColumn(65, false, dirtPath = true),
        "outside-owner-grade" to CivTerrainSurfaceColumn(66, false, dirtPath = true),
    )) test("an ordinary request cannot claim a $name shoulder") {
        shouldThrow<CivsRejection> { OwnedShoulderFixture(column).draft() }.reason shouldBe "autonomy-terrain"
    }

    test("paid shoulder context never authorizes an ordinary building pad over that path") {
        shouldThrow<CivsRejection> { OwnedShoulderFixture(pad = true).draft() }.reason shouldBe "autonomy-terrain"
    }
})

private class OwnedShoulderFixture(column: CivTerrainSurfaceColumn = CivTerrainSurfaceColumn(64, false, dirtPath = true),
    owned: Boolean = true, pad: Boolean = false, atEntrance: Boolean = false) {
    private val hall = CivBuilding(UUID(176, 1), BuildingType.TOWN_HALL, 1, CivPosition(0, 64, 0), 0, "fixture/hall",
        CivBox(CivPosition(-2, 64, -5), CivPosition(2, 70, -1)), CivPosition(0, 65, 0), BuildingPhase.ACTIVE)
    private val hut = CivBuilding(UUID(176, 2), BuildingType.BUILDER_HUT, 1, CivPosition(20, 64, 3), 180, "fixture/hut",
        CivBox(CivPosition(19, 64, 4), CivPosition(21, 70, 6)), CivPosition(20, 65, 3), BuildingPhase.ACTIVE)
    private val city = Settlement(UUID(176, 4), UUID(176, 5), "Owned shoulder", UUID(176, 6), hall.anchor, 32,
        "fixture", Stock(), listOf(hall, hut), emptyList(), autonomy = CivAutonomy())
    private val paidRoute = listOf(CivPosition(0, 65, 0), CivPosition(0, 65, 1)) +
        (1..20).map { CivPosition(it, 65, 1) } + listOf(CivPosition(20, 65, 2), hut.entrance)
    val route = if (pad) paidRoute.take(8) else (0..15).map { CivPosition(it, 65, 0) }
    private val building = CivBuilding(UUID(176, 3), BuildingType.FORESTER, 1,
        if (pad) CivPosition(6, 64, 1) else CivPosition(15, 64, 0), 0, "fixture/forester",
        if (pad) CivBox(CivPosition(5, 64, -2), CivPosition(7, 70, 0))
            else CivBox(CivPosition(14, 64, -5), CivPosition(16, 70, -1)),
        route.last(), BuildingPhase.SITE)
    val surface = CivTerrainSurface(-64, 320, buildMap {
        for (x in -8..24) for (z in -8..12) put(civsTerrainKey(x, z), CivTerrainSurfaceColumn(64, true))
        put(civsTerrainKey(if (atEntrance) 15 else 5, 0), column)
    })
    private val access = CivAutonomousAccess(if (owned) mapOf(hut.id to paidRoute) else emptyMap())
    fun draft(): CivTerraformDraft = CivsAutonomousTerraform.draft(city, CivTerraformOption(building, 0), surface,
        access, reservedRoute = route)
}
