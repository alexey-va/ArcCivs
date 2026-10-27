package ru.arc.civs

import com.google.gson.JsonParser
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.floor

class CivsMineAccessRegressionTest : FunSpec({
    test("the observed floor-three ladder reaches the outer landing and climbs without crossing its paid railing") {
        val mine = spatialBuilding("caledonia", "miner", 1, CivPosition(-1844, 70, -2696), 180)
        val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
        CivsWorksiteLayouts.point(mine, plan.bottom) shouldBe CivPosition(-1841, 65, -2686)
        val terrain = recordedMineLanding()
        val origin = CitizenPoint(-1840.5, 51.0, -2685.5)
        val target = CitizenPoint(-1844.5, 51.0, -2688.5)
        for (completedOnly in listOf(false, true)) {
            val result = searchRecordedMineLanding(terrain, origin, target) { point ->
                !completedOnly || citizenMineWorkRouteAllowed(mine, point, 1818)
            }
            withClue("completedOnly=$completedOnly result=$result") {
                result.failure shouldBe null
                result.path?.last()?.point shouldBe target
            }
        }
        val exit = searchRecordedMineLanding(terrain, origin, origin.copy(y = 55.0)) { true }
        withClue(exit) { exit.failure shouldBe null }
    }

    test("a paid V5 railing keeps the central well out of ordinary work approaches") {
        val mine = spatialBuilding("caledonia", "miner", 1, CivPosition(-1844, 70, -2696), 180)
        val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
        val railing = "3/shaft/railing"
        plan.sectionCount(railing, 1818, emptyMap()) shouldBe plan.sections.getValue(railing).size.toLong()
        val inside = CivsMinePlan.localPoint(mine, CivPosition(-1845, 51, -2687))
        val outside = CivsMinePlan.localPoint(mine, CivPosition(-1845, 51, -2689))
        plan.builtStanding(inside, plan.sections.getValue(railing).first().toLong()) shouldBe true
        plan.builtStanding(inside, 1818) shouldBe false
        plan.builtStanding(outside, 1818) shouldBe true
        val previous = requireNotNull(CivsMinePlan.forBuilding(mine, 4))
        previous.builtStanding(inside, previous.steps.size.toLong()) shouldBe true
    }

    test("a meal or depot departure retains its assigned completed mine below the schematic bounds") {
        val mine = spatialBuilding("caledonia", "miner", 1, CivPosition(-1844, 70, -2696), 180)
        val worker = spatialWorker(mine, "IRON_PICKAXE")
        val base = spatialCity(java.util.UUID.randomUUID(), listOf(mine), worker)
        val hall = base.buildings.single { it.type == BuildingType.TOWN_HALL }.copy(
            anchor = CivPosition(-1882, 67, -2759), entrance = CivPosition(-1881, 67, -2759),
            bounds = CivBox(CivPosition(-1886, 64, -2763), CivPosition(-1878, 75, -2755)), control = null)
        val city = base.copy(center = mine.anchor, radius = 200, buildings = listOf(mine, hall),
            minePlans = mapOf(mine.id.toString() to CivMinePlanState(CivsMinePlan.VERSION, 1818)))
        val plan = CitizenPlan(CitizenToken(mine.id, worker.completedCycles, worker.nextDeliveryAt, null),
            requireNotNull(mine.control), CivPosition(-1845, 51, -2687), BuildingType.QUARRY, 2_000, null)
        val from = CivPosition(-1841, 51, -2686)
        mine.bounds.contains(from.x, from.y, from.z) shouldBe false
        val allowed = citizenRouteBuildings(city, plan, hall.entrance, from)
        (mine.id in allowed) shouldBe true
        citizenRoutePointAllowed(city, allowed, from.copy(y = 52)) shouldBe true
        val cave = from.copy(x = from.x - 30)
        (mine.id in citizenRouteBuildings(city, plan, hall.entrance, cave)) shouldBe false
    }
})

/** Exact native colliders captured on Spawn at 2026-10-27T21:02:24Z; no inferred passage outside the capture. */
private fun recordedMineLanding(): Map<CivPosition, CitizenTerrainBlock> {
    val fixture = JsonParser.parseString(requireNotNull(CivsMineAccessRegressionTest::class.java
        .getResource("/mine-regina-floor3-native.json")).readText()).asJsonObject
    val minimum = fixture.getAsJsonObject("bounds").getAsJsonObject("min")
    val palette = fixture.getAsJsonArray("palette").map { value ->
        val shape = value.asJsonObject
        CitizenTerrainBlock(true, shape["liquid"].asBoolean, shape["door"].asBoolean,
            shape["climbable"].asBoolean, shape["hot"].asBoolean, true, 1.0,
            shape.getAsJsonArray("collision").map { encoded -> encoded.asJsonArray.let {
                CitizenTerrainBox(it[0].asDouble, it[1].asDouble, it[2].asDouble,
                    it[3].asDouble, it[4].asDouble, it[5].asDouble)
            } })
    }
    return fixture.getAsJsonArray("voxels").associate { value -> value.asJsonArray.let {
        CivPosition(minimum["x"].asInt + it[0].asInt, minimum["y"].asInt + it[1].asInt,
            minimum["z"].asInt + it[2].asInt) to palette[it[3].asInt]
    } }
}

private fun searchRecordedMineLanding(terrain: Map<CivPosition, CitizenTerrainBlock>, origin: CitizenPoint,
    target: CitizenPoint, allowed: (CitizenPoint) -> Boolean): CitizenSearchResult {
    val search = CitizenNavigationSearch(origin, target, -64, 320, emptyList())
    var result = search.advance(emptyList())
    while (result.probes.isNotEmpty()) result = search.advance(result.probes.map { probe ->
        val y = floor(probe.node.y).toInt()
        val blocks = buildMap {
            for (x in probe.node.x - 1..probe.node.x + 1) for (z in probe.node.z - 1..probe.node.z + 1)
                for (by in y - 3..y + 3) {
                    val point = CivPosition(x, by, z)
                    put(point, (terrain[point] ?: CitizenTerrainBlock.UNAVAILABLE).copy(
                        allowed = allowed(CitizenPoint(x + .5, by.toDouble(), z + .5))))
                }
        }
        CitizenSearchCapture(probe, blocks, citizenSearchCandidates(probe, blocks, -64, 320).map {
            it.copy(permitted = allowed(it.node.point()))
        })
    })
    return result
}
