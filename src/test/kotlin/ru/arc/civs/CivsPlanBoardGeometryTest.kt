package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.UUID

class CivsPlanBoardGeometryTest : FunSpec({
    test("authored board has one normal control and the same body for every style") {
        val blocks = CivsPlanBoardBlueprint.blocks()
        blocks.values.count { it == "minecraft:structure_void" } shouldBe 1
        blocks[CivPosition(3, 1, 2)] shouldBe "minecraft:structure_void"
        blocks[CivPosition(6, 1, 2)] shouldBe "minecraft:air"
        blocks.values.groupingBy { it.substringBefore('[') }.eachCount() shouldBe mapOf(
            "minecraft:stone_bricks" to 6, "minecraft:stripped_spruce_log" to 30,
            "minecraft:smooth_sandstone" to 36, "minecraft:dark_oak_stairs" to 22,
            "minecraft:dark_oak_slab" to 11, "minecraft:structure_void" to 1, "minecraft:air" to 131)
        blocks[CivPosition(3, 2, 2)] shouldBe "minecraft:air"
        val legacy = CivsPlanBoardBlueprint.blocks(legacy = true)
        legacy[CivPosition(6, 1, 2)] shouldBe "minecraft:structure_void"
        legacy.containsKey(CivPosition(3, 0, 2)) shouldBe false
        legacy.values.count { it == "minecraft:air" } shouldBe 55
        CivsPlanBoardBlueprint.LEGACY_SCHEMATIC_ID shouldBe "civs/builtin/plan_board/v1"
        CivsPlanBoardBlueprint.origin.z shouldBe CivsPlanBoardBlueprint.bounds.max.z + 1
    }

    test("legacy refit centres the large physical face on its unchanged barrel") {
        val id = CivsPlanBoardBlueprint.REFIT_V1
        val body = CivsPlanBoardBlueprint.body(id)
        val solids = body.filterValues { it != "minecraft:air" }
        val control = solids.entries.single { it.value == "minecraft:structure_void" }.key
        control shouldBe CivPosition(6, 1, 2)
        (solids.keys.maxOf { it.x } - solids.keys.minOf { it.x } + 1) shouldBe 11
        (solids.keys.maxOf { it.x } + solids.keys.minOf { it.x }) / 2 shouldBe control.x
        CivsPlanBoardBlueprint.blocks(id)[control] shouldBe "minecraft:structure_void"
        civsContainsBox(CivsPlanBoardBlueprint.bounds(id), CivsPlanBoardBlueprint.legacyBounds) shouldBe true
    }

    test("exact union preserves disconnected parallel paths at negative coordinates") {
        val cells = (0L..20L).flatMap { z -> listOf(CivPlanBoardCell(-4, -z), CivPlanBoardCell(-2, -z)) }.toSet()
        val rectangles = CivsPlanBoardGeometry.rectangles(cells)
        rectangles.size shouldBe 2
        boardRectangleCells(rectangles) shouldBe cells
        (CivPlanBoardCell(-3, -10) in boardRectangleCells(rectangles)) shouldBe false
    }

    test("real Auto071 keeps every retained road cell and all107 registered objects within one scene") {
        val snapshot = planBoardAutoFixture()
        snapshot.buildings.size shouldBe 107
        val layout = CivsPlanBoardGeometry.layout(snapshot)
        val expected = (snapshot.routes.values.flatten() + snapshot.buildings.filter {
            it.type == BuildingType.ROAD || it.type == BuildingType.GATE
        }.flatMap { it.transit.orEmpty() }).mapTo(hashSetOf()) { CivPlanBoardCell(it.x.toLong(), it.z.toLong()) }
        layout.roadCells shouldBe expected
        boardRectangleCells(layout.roadRectangles) shouldBe expected
        layout.entries.size shouldBe 11
        layout.rasters.isEmpty() shouldBe true
        (layout.displayParts <= 256) shouldBe true
        expected.size shouldBe 1035
    }

    test("an ACTIVE body and same UUID planned upgrade retain both styles") {
        val original = planBoardAutoFixture()
        val hall = original.buildings.first { it.type == BuildingType.TOWN_HALL }
        val planned = hall.copy(level = hall.level + 1, phase = BuildingPhase.CONSTRUCTING,
            bounds = hall.bounds.copy(max = hall.bounds.max.copy(x = hall.bounds.max.x + 2)))
        val layout = CivsPlanBoardGeometry.layout(original.copy(plannedBuildings = listOf(planned)))
        layout.patches.count { it.key == "built-${hall.id}" } shouldBe 1
        layout.patches.count { it.key.startsWith("planned-${hall.id}-") } shouldBe 4
        layout.entries.count { it.id == hall.id } shouldBe 1
    }

    test("normal and portrait plans preserve cardinal directions without reflection") {
        val source = planBoardAutoFixture()
        val points = listOf(CivPosition(0, 64, 0), CivPosition(0, 64, -4), CivPosition(4, 64, 0))
        val markers = points.mapIndexed { index, p ->
            CivBuilding(UUID(86, index.toLong()), BuildingType.HOUSE, 1, p, 0, "fixture",
                CivBox(p, p), p, BuildingPhase.ACTIVE)
        }
        for (portrait in listOf(false, true)) {
            val stretch = if (portrait) CivPosition(0, 64, 30) else CivPosition(30, 64, 0)
            val layout = CivsPlanBoardGeometry.layout(source.copy(buildings = markers +
                markers.first().copy(id = UUID(86, 3), type = BuildingType.WALL, bounds = CivBox(stretch, stretch)),
                plannedBuildings = emptyList(), routes = emptyMap()))
            layout.northLeft shouldBe portrait
            val origin = layout.entries.single { it.id == markers[0].id }
            val north = layout.entries.single { it.id == markers[1].id }
            val east = layout.entries.single { it.id == markers[2].id }
            if (portrait) {
                (north.x < origin.x) shouldBe true
                north.y shouldBe origin.y
                (east.y > origin.y) shouldBe true
                east.x shouldBe origin.x
            } else {
                (north.y > origin.y) shouldBe true
                north.x shouldBe origin.x
                (east.x > origin.x) shouldBe true
                east.y shouldBe origin.y
            }
        }
    }

    test("dense28 offline stress retains every planned contour without changing gameplay caps") {
        val layout = CivsPlanBoardGeometry.layout(planBoardDenseFixture())
        layout.entries.size shouldBe 28
        layout.patches.count { it.key.startsWith("planned-") } shouldBe 14 * 4
        (layout.displayParts <= 256) shouldBe true
        CivsRules.MAX_BUILDINGS shouldBe 16
    }

    test("legal winding routes use bounded exact bitmap rows instead of merging gaps or truncating") {
        val snapshot = planBoardRasterFixture()
        snapshot.routes.values.all { it.size <= CivsAutonomousTerraform.MAXIMUM_ROUTE_LENGTH } shouldBe true
        val layout = CivsPlanBoardGeometry.layout(snapshot)
        layout.rasters.size shouldBe 16
        (layout.displayParts <= 256) shouldBe true
        layout.rasters.sumOf { raster -> raster.rows.sumOf { row -> row.count { it == '█' } / 4 } } shouldBe
            snapshot.routes.values.sumOf { it.size }
        layout.rasters.forEach { raster -> raster.rows.forEach { row ->
            var cells = 0; var offset = 0
            while (offset < row.length) {
                val token = if (row[offset] == '█') "████" else "         "
                row.substring(offset, offset + token.length) shouldBe token
                cells++; offset += token.length
            }
            cells shouldBe raster.columns
        } }
        layout.entries.size shouldBe 16
    }

    test("portrait bitmap rows preserve exact route coordinates with north left and east up") {
        val snapshot = planBoardPortraitRasterFixture()
        val layout = CivsPlanBoardGeometry.layout(snapshot)
        layout.northLeft shouldBe true
        layout.rasters.size shouldBe 16
        for ((id, route) in snapshot.routes) {
            val raster = layout.rasters.single { it.key == "route-$id" }
            val minZ = route.minOf { it.z }; val maxX = route.maxOf { it.x }
            val reconstructed = mutableSetOf<CivPlanBoardCell>()
            raster.rows.forEachIndexed { rowIndex, row ->
                var column = 0; var offset = 0
                while (offset < row.length) {
                    if (row[offset] == '█') {
                        reconstructed += CivPlanBoardCell((maxX - rowIndex).toLong(), (minZ + column).toLong())
                        offset += 4
                    } else offset += 9
                    column++
                }
            }
            reconstructed shouldBe route.mapTo(hashSetOf()) { CivPlanBoardCell(it.x.toLong(), it.z.toLong()) }
        }
    }

    test("maximum supported native routes retain the largest mask and longest line") {
        val snapshot = planBoardLargestRouteFixture()
        val layout = CivsPlanBoardGeometry.layout(snapshot)
        snapshot.routes.values.maxOf { it.size } shouldBe CivsAutonomousTerraform.MAXIMUM_ROUTE_LENGTH
        val largestMask = layout.rasters.single { it.key == "route-${UUID(84, 0)}" }
        largestMask.columns shouldBe 128
        largestMask.rows.size shouldBe 129
        largestMask.rows.sumOf { it.toByteArray(Charsets.UTF_8).size } shouldBe 149_376
        largestMask.rows.sumOf { it.count { c -> c == '█' } / 4 } shouldBe 256
        val longestLine = layout.rasters.single { it.key == "route-${UUID(84, 1)}" }
        longestLine.columns shouldBe 256
        longestLine.rows.size shouldBe 1
        longestLine.rows.single() shouldBe "████".repeat(256)
        (layout.rasters.sumOf { raster -> raster.rows.sumOf(String::length) + raster.rows.size - 1 } <=
            CivsPlanBoardGeometry.MAX_RASTER_TEXT_CHARS) shouldBe true
        (layout.displayParts <= 256) shouldBe true
    }

    test("an oversized total raster payload fails with source context instead of cropping the plan") {
        val source = planBoardLargestRouteFixture()
        val second = UUID(84, 1)
        val snapshot = source.copy(routes = source.routes + (second to source.routes.getValue(UUID(84, 0))
            .map { it.copy(x = it.x + 200) }))
        val failure = shouldThrow<IllegalArgumentException> { CivsPlanBoardGeometry.layout(snapshot) }
        failure.message.orEmpty() shouldContain "raster text exceeds ${CivsPlanBoardGeometry.MAX_RASTER_TEXT_CHARS}"
        failure.message.orEmpty() shouldContain "route-$second"
    }

    test("a full city with queued upgrades retains all geometry before optional labels") {
        val base = planBoardRasterFixture()
        val ordinary = base.buildings.mapIndexed { index, building ->
            if (index == 15) building.copy(type = BuildingType.PLAN_BOARD) else building
        }
        val walls = (0 until 96).map { index ->
            val p = CivPosition(300 + index * 2, 64, 50)
            CivBuilding(UUID(85, index.toLong()), BuildingType.WALL, 1, p, 0, "fixture",
                CivBox(p, p.copy(y = 67)), p.copy(z = 51), BuildingPhase.ACTIVE)
        }
        val snapshot = base.copy(buildings = ordinary + walls,
            plannedBuildings = ordinary.take(15).map { it.copy(level = 2, phase = BuildingPhase.CONSTRUCTING) },
            routes = base.routes + walls.associate { it.id to listOf(it.entrance) })
        val layout = CivsPlanBoardGeometry.layout(snapshot)
        layout.showNumbers shouldBe false
        layout.displayParts shouldBe 250
        layout.patches.count { it.key.startsWith("built-") } shouldBe 112
        layout.patches.count { it.key.startsWith("planned-") } shouldBe 15
        layout.rasters.size shouldBe 112
        layout.roadCells shouldBe snapshot.routes.values.flatten().mapTo(hashSetOf()) {
            CivPlanBoardCell(it.x.toLong(), it.z.toLong())
        }
    }

    test("display corners fit integer-rotated physical blocks through every quarter turn") {
        val board = planBoardFixtureBuilding()
        listOf(0, 90, 180, 270).forEach { rotation ->
            for (cell in listOf(CivPosition(1, 1, 1), CivPosition(5, 3, 1), CivPosition(6, 1, 2))) {
                val offset = CivsBlueprintGeometry.rotate(ru.arc.paper.api.ArcSchematicPoint(
                    cell.x - CivsPlanBoardBlueprint.origin.x, cell.y, cell.z - CivsPlanBoardBlueprint.origin.z), rotation / 90)
                val p = CivsPlanBoardRenderer.localToWorld(board.copy(rotation = rotation), cell.x + .5, cell.y + .5, cell.z + .5)
                p shouldBe listOf(board.anchor.x + offset.x + .5, board.anchor.y + offset.y + .5, board.anchor.z + offset.z + .5)
            }
        }
    }

    test("adopted future footprints and complete layout remain separate from accepted roads and fit the backing") {
        val source = planBoardAutoFixture()
        val house = source.buildings.first { it.type == BuildingType.HOUSE }.copy(id = UUID(93, 1))
        val layout = CivsPlanBoardGeometry.layout(source.copy(plan = CivSettlementPlan(1, 0, 56, listOf(CivSettlementPlot(house, house.bounds)), emptyMap(),
            listOf(CivBox(CivPosition(-20, 64, -20), CivPosition(20, 64, -18))), emptyList())))
        layout.entries.single { it.id == house.id }.future shouldBe true
        layout.roadCells shouldBe CivsPlanBoardGeometry.layout(source).roadCells
        (layout.patches.any { it.key.startsWith("future-") } || layout.rasters.any { it.key.startsWith("future-") }) shouldBe true
        layout.patches.forEach { patch ->
            (patch.x >= -.840001 && patch.x + patch.width <= 4.720001) shouldBe true
            (patch.y >= 2.159999 && patch.y + patch.height <= 5.840001) shouldBe true
        }
        (layout.displayParts <= 256) shouldBe true
    }
})

internal fun planBoardAutoFixture(): CivPlanBoardSnapshot = requireNotNull(CivsPlanBoardGeometryTest::class.java
    .getResourceAsStream("/plan-board-auto071.json")).bufferedReader().use {
    Gson().fromJson(it, CivPlanBoardSnapshot::class.java)
}

internal fun planBoardFixtureBuilding(): CivBuilding = CivBuilding(UUID(83, 1), BuildingType.PLAN_BOARD, 1,
    CivPosition(3, 0, 3), 0, CivsPlanBoardBlueprint.SCHEMATIC_ID, CivsPlanBoardBlueprint.bounds,
    CivPosition(3, 1, 3), BuildingPhase.ACTIVE, control = CivPosition(3, 1, 2))

internal fun planBoardDenseFixture(): CivPlanBoardSnapshot {
    val source = planBoardAutoFixture()
    val buildings = (0 until 28).map { index ->
        val x = (index % 7) * 16; val z = (index / 7) * 16
        CivBuilding(UUID(83, index.toLong()), BuildingType.HOUSE, 1, CivPosition(x, 64, z), 0, "fixture",
            CivBox(CivPosition(x, 64, z), CivPosition(x + 11, 72, z + 10)), CivPosition(x + 5, 65, z + 11),
            if (index % 2 == 0) BuildingPhase.ACTIVE else BuildingPhase.SITE)
    }
    return source.copy(name = "Плотный план · 28 участков", layoutKey = "dense28-offline-stress", center = CivPosition(50, 64, 30),
        buildings = buildings.filter { it.phase == BuildingPhase.ACTIVE },
        plannedBuildings = buildings.filter { it.phase != BuildingPhase.ACTIVE }, routes = emptyMap())
}

internal fun planBoardRasterFixture(): CivPlanBoardSnapshot {
    val source = planBoardAutoFixture()
    val routes = (0 until 16).associate { index ->
        UUID(84, index.toLong()) to buildList {
            for (row in 0 until 18) {
                val xs = if (row % 2 == 0) 0..11 else 11 downTo 0
                xs.forEach { x -> add(CivPosition(index * 16 + x, 64, row * 2)) }
                if (row != 17) add(CivPosition(index * 16 + if (row % 2 == 0) 11 else 0, 64, row * 2 + 1))
            }
        }
    }
    val buildings = routes.map { (id, route) ->
        val end = route.last()
        CivBuilding(id, BuildingType.HOUSE, 1, end.copy(y = 63), 180, "fixture",
            CivBox(end.copy(z = end.z + 1), end.copy(x = end.x + 11, y = 72, z = end.z + 8)), end,
            BuildingPhase.ACTIVE)
    }
    return source.copy(name = "Извилистые дорожки", layoutKey = "valid-winding-path-stress", center = CivPosition(128, 64, 20),
        buildings = buildings, plannedBuildings = emptyList(), routes = routes)
}

internal fun planBoardPortraitRasterFixture(): CivPlanBoardSnapshot {
    val source = planBoardRasterFixture()
    fun swap(p: CivPosition) = p.copy(x = p.z, z = p.x)
    return source.copy(name = "Длинное поселение · север слева", layoutKey = "portrait-winding-path-stress",
        buildings = source.buildings.map { building ->
            building.copy(anchor = swap(building.anchor), entrance = swap(building.entrance),
                bounds = CivBox(swap(building.bounds.min), swap(building.bounds.max)))
        }, routes = source.routes.mapValues { (_, route) -> route.map(::swap) })
}

internal fun planBoardLargestRouteFixture(): CivPlanBoardSnapshot {
    val source = planBoardRasterFixture()
    val routes = source.routes + mapOf(UUID(84, 0) to buildList {
        (0 until 128).forEach { add(CivPosition(300 + it, 64, 0)) }
        (1..128).forEach { add(CivPosition(427, 64, it)) }
    }, UUID(84, 1) to (0 until 256).map { CivPosition(600 + it, 64, 0) })
    return source.copy(name = "Предельные пути · 256 шагов", layoutKey = "maximum-native-route-stress", routes = routes,
        buildings = source.buildings.map { building ->
            val end = routes.getValue(building.id).last()
            building.copy(anchor = end.copy(y = 63), entrance = end,
                bounds = CivBox(end.copy(z = end.z + 1), end.copy(x = end.x + 11, y = 72, z = end.z + 8)))
        })
}

private fun boardRectangleCells(rectangles: List<CivPlanBoardRect>) = rectangles.flatMap { rect ->
    (rect.z0 until rect.z1).flatMap { z -> (rect.x0 until rect.x1).map { x -> CivPlanBoardCell(x, z) } }
}.toSet()
