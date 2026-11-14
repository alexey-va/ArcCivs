package ru.arc.civs

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsTownHallApproachTest : FunSpec({
    test("paid routes leaving a town hall share a three lane forecourt and supported signed grades in four rotations") {
        val path = "minecraft:dirt_path"
        val grass = "minecraft:grass_block[snowy=false]"
        val siteId = "fixture/hall"
        val site = JsonParser.parseString("""{
            "schematicId":"fixture/hall","level":3,"type":"TOWN_HALL",
            "schematicBounds":{"min":[-5,-2,-10],"max":[5,12,-1]},
            "forecourt":["minecraft:dirt_path","minecraft:dirt_path"]
        }""").asJsonObject

        // Feet are ordered away from the Hall. Row pairs are the independent expected floor Y and finish.
        data class Profile(val name: String, val feet: List<Int>, val rows: List<Pair<Int, String>>)
        val flat = 68 to path
        val profiles = listOf(
            Profile("flat", listOf(69, 69, 69, 69), List(4) { flat }),
            Profile("outward rise after a plateau", listOf(69, 69, 69, 69, 70, 70),
                List(4) { flat } + listOf(69 to "out", 69 to path)),
            Profile("two consecutive drops", listOf(69, 68, 67, 67),
                listOf(flat, 68 to "in", 67 to "in", 66 to path)),
            Profile("two drops between offset routes", listOf(69, 69, 69, 68, 68, 68, 67, 67),
                listOf(flat, flat, flat, 68 to "in", 67 to path, 67 to path, 67 to "in", 66 to path)),
            Profile("five drops beside a parallel route", listOf(69, 68, 67, 66, 65, 64, 64),
                listOf(flat, 68 to "in", 67 to "in", 66 to "in", 65 to "in", 64 to "in", 63 to path))
        )
        val inward = listOf("north", "east", "south", "west")
        val gson = Gson()
        CivsWorksiteLayouts.installCatalog(mapOf(siteId to site))
        try {
            val failures = mutableListOf<String>()
            for (turn in 0..3) for (profile in profiles) {
                fun rotate(p: CivPosition) = when (turn) {
                    0 -> p
                    1 -> CivPosition(-p.z, p.y, p.x)
                    2 -> CivPosition(-p.x, p.y, -p.z)
                    else -> CivPosition(p.z, p.y, -p.x)
                }
                fun bounds(a: CivPosition, b: CivPosition): CivBox {
                    val first = rotate(a)
                    val last = rotate(b)
                    return CivBox(CivPosition(minOf(first.x, last.x), first.y, minOf(first.z, last.z)),
                        CivPosition(maxOf(first.x, last.x), last.y, maxOf(first.z, last.z)))
                }
                val entrance = CivPosition(0, 69, 0)
                val hall = CivBuilding(UUID(0, 1), BuildingType.TOWN_HALL, 3, entrance.copy(y = 68), turn * 90,
                    siteId, bounds(CivPosition(-5, 66, -10), CivPosition(5, 80, -1)), entrance, BuildingPhase.ACTIVE)
                val end = profile.feet.lastIndex + 3
                val roadFeet = profile.feet.last()
                fun feet(depth: Int) = profile.feet[minOf(depth, profile.feet.lastIndex)]
                val spine = if (profile.name == "two drops between offset routes") {
                    // These paid streets leave the stance sideways. No paid centreline runs down the desired flight.
                    listOf(entrance, CivPosition(-1, 69, 0), CivPosition(-2, 69, 0)) +
                        (1..end).map { CivPosition(-2, feet(it), it) }
                } else (0..end).map { CivPosition(0, feet(it), it) }
                val west = spine + (spine.last().x - 1 downTo -9).map { CivPosition(it, roadFeet, end) }
                val east = spine + (spine.last().x + 1..9).map { CivPosition(it, roadFeet, end) }
                val side = when (profile.name) {
                    "two consecutive drops" -> listOf(entrance, CivPosition(1, 69, 0), CivPosition(2, 68, 0)) +
                        (3..10).map { CivPosition(it, roadFeet, 0) }
                    "five drops beside a parallel route" -> listOf(entrance, CivPosition(1, 68, 0), CivPosition(2, 68, 0)) +
                        (1..end + 2).map { CivPosition(2, feet(maxOf(1, it)), it) }
                    "two drops between offset routes" -> listOf(entrance, CivPosition(0, 69, 1),
                        CivPosition(1, 68, 1), CivPosition(2, 67, 1), CivPosition(3, 67, 1)) +
                        (2..end + 2).map { CivPosition(3, 67, it) }
                    else -> (0..10).map { CivPosition(it, 69, 0) }
                }
                val localRoutes = listOf(west, east, side)
                val routes = localRoutes.map { route -> route.map(::rotate) }
                val destinations = localRoutes.mapIndexed { i, route ->
                    val door = route.last()
                    hall.copy(id = UUID(0, 10L + i), type = BuildingType.HOUSE, schematicId = "fixture/source-$i",
                        rotation = (turn * 90 + 180) % 360, anchor = rotate(door.copy(y = door.y - 1)), entrance = rotate(door),
                        bounds = bounds(door.copy(x = door.x - 1, y = door.y - 1, z = door.z + 1),
                            door.copy(x = door.x + 1, y = door.y + 5, z = door.z + 4)))
                }
                val city = Settlement(UUID(0, 20), UUID(0, 21), "Town", UUID(0, 22), hall.anchor, 72, "nordic",
                    Stock(), listOf(hall) + destinations, emptyList(), autonomy = CivAutonomy())
                val plans = listOf(CivPlacementPlan(hall.id, city.id, city.worldId, hall, emptyList(),
                    capturedSha256 = "a".repeat(64))) + destinations.zip(routes).map { (source, route) ->
                    CivPlacementPlan(source.id, city.id, city.worldId, source, emptyList(), capturedSha256 = "b".repeat(64),
                        terrain = CivTerraformPlan(bounds(CivPosition(-12, 60, -10), CivPosition(14, 90, 18)),
                            emptyList(), route, source.anchor.y, 0, 0))
                }
                val before = gson.toJson(city to plans)
                val targets = CivsRoadMaintenance.prepare(city, plans).targets.groupBy { it.floor.x to it.floor.z }
                val expected = buildMap {
                    for (lane in -1..1) for (depth in -2..-1) put(rotate(CivPosition(lane, 68, depth)), path)
                    for ((depth, row) in profile.rows.withIndex()) {
                        val (floorY, finish) = row
                        val facing = when (finish) { "in" -> inward[turn]; "out" -> inward[(turn + 2) % 4]; else -> null }
                        val surface = facing?.let { "minecraft:spruce_stairs[facing=$it,half=bottom,shape=straight,waterlogged=false]" } ?: finish
                        for (lane in -1..1) put(rotate(CivPosition(lane, floorY, depth)), surface)
                        if (facing != null) for (lane in listOf(-2, 2)) put(rotate(CivPosition(lane, floorY, depth)), grass)
                    }
                }
                if (profile.name == "five drops beside a parallel route") {
                    for (d in listOf(2, 4)) {
                        val shoulder = rotate(CivPosition(3,feet(d)-1,d))
                        val fits = targets[shoulder.x to shoulder.z].orEmpty().filter { it.hall != null }
                        withClue("paid outer shoulder $shoulder in rotation $turn") {
                            fits.map { it.floor to it.after } shouldBe listOf(shoulder to grass)
                        }
                        val upkeep = fits.single()
                        val original = "minecraft:spruce_stairs[facing=${inward[turn]},half=bottom,shape=straight,waterlogged=false]"
                        val change = CivPlanBlock(shoulder,original,grass)
                        CivsRoadMaintenance.validateChanges(city,upkeep,listOf(change))
                        val stone = original.replace("spruce_stairs","cobblestone_stairs")
                        check(runCatching { CivsRoadMaintenance.validateChanges(city,upkeep,listOf(change.copy(before=stone))) }.isFailure)
                        check(runCatching { CivsRoadMaintenance.validateChanges(city,upkeep,listOf(change.copy(before="minecraft:air"))) }.isFailure)
                        check(runCatching { CivsRoadMaintenance.validateChanges(city,upkeep,listOf(
                            CivPlanBlock(shoulder.copy(y=shoulder.y-1),"minecraft:air","minecraft:dirt"),change)) }.isFailure)
                    }
                }
                val wrong = expected.mapNotNull { (floor, finish) ->
                    val actual = targets[floor.x to floor.z].orEmpty().map { it.floor to it.after }
                    if (actual == listOf(floor to finish)) null else "$floor expected=$finish actual=$actual"
                }
                if (wrong.isNotEmpty()) failures += "${profile.name}, rotation=${turn * 90}, ${wrong.size} columns: ${wrong.take(2)}"
                gson.toJson(city to plans) shouldBe before
            }
            withClue(failures.joinToString("\n")) { failures.size shouldBe 0 }
        } finally {
            CivsWorksiteLayouts.installCatalog(emptyMap())
        }
    }
})
