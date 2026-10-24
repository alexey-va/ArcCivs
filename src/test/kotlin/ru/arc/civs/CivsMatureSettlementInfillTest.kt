package ru.arc.civs

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class CivsMatureSettlementInfillTest : FunSpec({
    test("the saved mature-town lava frontage stays forbidden and a bounded uneven inner yard connects") {
        val fixture = matureInfillFixture()
        val city = fixture.city
        val house = matureInfillHouse()
        val frame = CivsSettlementLayout.frame(city)
        frame.halfWidth shouldBe 37
        val frontage = CivsSettlementLayout.candidates(city, house, house.copy(level = 3))
        frontage.map { it.building.entrance.x to it.building.entrance.z }.toSet() shouldBe
            setOf(-395 to -488, -395 to -484)
        fixture.recorded.size shouldBe 323
        fixture.recorded.values.count { !it.natural } shouldBe 8
        val rejected = mutableListOf<Pair<String, CivPosition>>()
        CivsAutonomousTerraform.options(city, frontage, fixture.surface, rejected = { why, at ->
            rejected += why to at
        }) shouldBe emptyList()
        rejected.size shouldBe 2
        rejected.all { (reason, at) -> reason == "autonomy-terrain" && fixture.recorded[civsTerrainKey(at.x, at.z)]?.natural == false } shouldBe true

        val yards = CivsSettlementLayout.infill(city, house, house.copy(level = 3))
        yards shouldBe CivsSettlementLayout.infill(city, house, house.copy(level = 3))
        check(yards.isNotEmpty() && yards.size <= 64) { "No bounded mature-town infill: ${yards.size}" }
        val options = CivsAutonomousTerraform.options(city, yards, fixture.surface).take(64)
        val failures = mutableListOf<String>()
        val draft = options.asSequence().mapNotNull { option ->
            try { CivsAutonomousTerraform.draft(city, option, fixture.surface, additionalObstacles = frame.wallObstacles) }
            catch (failure: CivsRejection) { failures += failure.reason; null }
        }.firstOrNull() ?: error("No connected infill: ${yards.size} candidates, ${options.size} options, $failures")
        CivsSettlementLayout.acceptsRoute(frame, draft.route) shouldBe true
        draft.route.last() shouldBe draft.building.entrance
        (draft.route.first() in city.buildings.map { it.entrance }) shouldBe true
        draft.route.zipWithNext().all { (a, b) -> abs(a.x - b.x) + abs(a.z - b.z) == 1 && abs(a.y - b.y) <= 1 } shouldBe true
        val occupied = city.buildings.map { CivsAutonomousGeometry.futureBounds(city, it) }
        occupied.none { CivsAutonomousGeometry.overlaps(it, draft.building.bounds, 2) } shouldBe true
        city.buildings.flatMap(CivsAutonomousGeometry::approach).none {
            CivsAutonomousGeometry.horizontalContains(draft.building.bounds, it)
        } shouldBe true
        frame.wallObstacles.none { CivsAutonomousGeometry.overlaps(it, draft.building.bounds, 2) } shouldBe true
        draft.columns.none { fixture.recorded[civsTerrainKey(it.x, it.z)]?.natural == false } shouldBe true
        // Only the 323 incident columns are recorded. This surrounding solid hillside is a
        // declared test continuation, proving bounded alternative selection, not live admission.
        val cells = draft.columns.flatMap { column ->
            val natural = requireNotNull(fixture.surface.at(column.x, column.z))
            (column.floorY - CivsAutonomousTerraform.MAXIMUM_CHANGE..maxOf(column.surfaceY, column.groundY) + 2).map { y ->
                val point = CivPosition(column.x, y, column.z)
                val solid = y <= natural.groundY
                point to CivTerraformCell(point, if (solid) "minecraft:stone" else "minecraft:air", true, solid, !solid)
            }
        }.toMap()
        val terrain = CivsAutonomousTerraform.materialize(draft, cells)
        (terrain.blocks.isNotEmpty()) shouldBe true
        (terrain.cutCount + terrain.fillCount <= CivsAutonomousTerraform.MAXIMUM_CHANGED_CELLS) shouldBe true
        terrain.blocks.none { fixture.recorded[civsTerrainKey(it.position.x, it.position.z)]?.natural == false } shouldBe true
        CivsSettlementLayout.frame(city) shouldBe frame
        println("mature-infill: frontage=${frontage.size}, yards=${yards.size}, options=${options.size}, " +
            "entrance=${draft.building.entrance}, rotation=${draft.building.rotation}, route=${draft.route.size}, " +
            "cut=${terrain.cutCount}, fill=${terrain.fillCount}")
    }

    test("infill still reserves retained roads and all unsafe yards return no grading option") {
        val fixture = matureInfillFixture()
        val city = fixture.city
        val house = matureInfillHouse()
        val unreserved = CivsSettlementLayout.infill(city, house, house.copy(level = 3))
        check(unreserved.isNotEmpty())
        val occupied = unreserved.first().building.bounds.min.copy(y = 78)
        val access = CivAutonomousAccess(mapOf(city.buildings.first().id to listOf(occupied)))
        val reserved = CivsSettlementLayout.infill(city, house, house.copy(level = 3), access)
        check(reserved.isNotEmpty())
        reserved.none { CivsAutonomousGeometry.horizontalContains(it.building.bounds, occupied) } shouldBe true
        val denied = fixture.surface.copy(columns = fixture.surface.columns.mapValues { (_, value) -> value.copy(natural = false) })
        CivsAutonomousTerraform.options(city, reserved, denied) shouldBe emptyList()
        CivsSettlementLayout.infill(city, house.copy(type = BuildingType.FARM), house.copy(type = BuildingType.FARM)) shouldBe emptyList()
    }
})

private data class MatureInfillFixture(val city: Settlement, val surface: CivTerrainSurface,
    val recorded: Map<Long, CivTerrainSurfaceColumn>)

private fun matureInfillFixture(): MatureInfillFixture {
    val input = checkNotNull(CivsMatureSettlementInfillTest::class.java.getResourceAsStream("/autonomy-mature-infill.json"))
    val json = input.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
    fun point(value: JsonObject) = CivPosition(value["x"].asInt, value["y"].asInt, value["z"].asInt)
    val buildings = json.getAsJsonArray("buildings").mapIndexed { index, value ->
        val b = value.asJsonObject
        val box = b.getAsJsonObject("bounds")
        CivBuilding(UUID(0, index + 1L), BuildingType.valueOf(b["type"].asString), b["level"].asInt,
            point(b.getAsJsonObject("anchor")), b["rotation"].asInt, b["schematicId"].asString,
            CivBox(point(box.getAsJsonObject("min")), point(box.getAsJsonObject("max"))),
            point(b.getAsJsonObject("entrance")), BuildingPhase.ACTIVE, point(b.getAsJsonObject("control")))
    }
    val city = Settlement(UUID(1, 1), UUID(1, 2), "Mature infill regression", UUID(1, 3),
        point(json.getAsJsonObject("center")), json["radius"].asInt, json["style"].asString,
        Stock(), buildings, emptyList(), autonomy = CivAutonomy(layoutHalfWidth = json["layoutHalfWidth"].asInt))
    val recorded = json.getAsJsonArray("columns").associate { value ->
        val row = value.asJsonArray
        civsTerrainKey(row[0].asInt, row[2].asInt) to CivTerrainSurfaceColumn(row[1].asInt,
            row[3].asString !in setOf("minecraft:lava", "minecraft:copper_ore"))
    }
    val columns = buildMap {
        for (x in city.center.x - 127..city.center.x + 127) for (z in city.center.z - 127..city.center.z + 127) {
            val y = 76 + (3 * sin((x - city.center.x) / 17.0) + 2 * sin((z - city.center.z) / 13.0)).roundToInt()
            put(civsTerrainKey(x, z), CivTerrainSurfaceColumn(y, true))
        }
        // Completed approaches already own these grades; do not invent an incompatible source.
        CivAutonomousAccess().standing(city, UUID(2, 1)).forEach { (key, feet) -> put(key, CivTerrainSurfaceColumn(feet.y - 1, true)) }
        putAll(recorded)
    }
    return MatureInfillFixture(city, CivTerrainSurface(-64, 320, columns), recorded)
}

private fun matureInfillHouse() = CivBuilding(UUID(2, 1), BuildingType.HOUSE, 1, CivPosition(0, 0, 0), 0,
    "civs/minecolonies/caledonia/fundamentals/residence1", CivBox(CivPosition(-7, 0, -19), CivPosition(5, 16, -1)),
    CivPosition(0, 1, 0), BuildingPhase.CONSTRUCTING, CivPosition(-4, 2, -13))
