package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import kotlin.math.abs

class CivsAutonomousTerraformTest : FunSpec({
    test("real 13 by 13 builder footprint receives supported cut and fill on a twelve block hillside") {
        val city = terraceCity()
        val building = terraceBuilder(city)
        val surface = terraceSurface { x, z -> 64 + (z.coerceAtLeast(0) / 3).coerceAtMost(12) + x.coerceIn(-6, 6) / 2 }
        val choices = CivsAutonomousTerraform.options(city, listOf(CivsAutonomousCandidate(building)), surface)
        choices.isNotEmpty() shouldBe true
        val draft = CivsAutonomousTerraform.draft(city, choices.first(), surface)
        val cells = terraceCapture(draft, surface)
        val plan = CivsAutonomousTerraform.materialize(draft, cells)
        (plan.cutCount > 0) shouldBe true
        (plan.fillCount > 0) shouldBe true
        plan.blocks.map { it.position }.distinct().size shouldBe plan.blocks.size
        plan.blocks.none { it.position.x == plan.route.first().x && it.position.z == plan.route.first().z } shouldBe true
        checkTerrace(plan, draft, cells)
        // Replaying the journal consumes each exact original once, including terrain below the schematic.
        val replay = cells.mapValues { it.value.data }.toMutableMap()
        plan.blocks.forEach { cell -> replay[cell.position] shouldBe cell.before; replay[cell.position] = cell.after }
        plan.blocks.forEach { cell -> replay[cell.position] shouldBe cell.after }
    }

    test("an eighteen block escarpment gets a graded connected approach instead of a slope rejection") {
        val city = terraceCity()
        val building = terraceBuilder(city, 82)
        val surface = terraceSurface { _, z -> if (z >= 10) 82 else 64 }
        val fixed = CivsAutonomousTerraform.options(city, listOf(CivsAutonomousCandidate(building)), surface, fixed = true)
        fixed.size shouldBe 1
        val draft = CivsAutonomousTerraform.draft(city, fixed.single(), surface)
        val cells = terraceCapture(draft, surface)
        val plan = CivsAutonomousTerraform.materialize(draft, cells)
        plan.route.first() shouldBe city.buildings.single().entrance
        plan.route.last() shouldBe building.entrance
        (plan.route.size > 18) shouldBe true
        (plan.fillCount > 0) shouldBe true
        checkTerrace(plan, draft, cells)
    }

    test("pinned legacy SITE preserves its anchor its existing marker and initially walkable entrance") {
        val original = terraceCity()
        val hall = terraceBuilder(original, 72).copy(type = BuildingType.TOWN_HALL, phase = BuildingPhase.SITE)
        val city = original.copy(buildings = listOf(hall))
        val marker = requireNotNull(hall.control)
        val surface = terraceSurface { x, z -> when {
            x == hall.entrance.x && z == hall.entrance.z -> 72
            x == marker.x && z == marker.z -> marker.y
            else -> 70 + x.coerceIn(-6, 6) / 2
        } }.let { it.copy(columns = it.columns + (civsTerrainKey(marker.x, marker.z) to
            CivTerrainSurfaceColumn(marker.y, natural = false))) }
        val options = CivsAutonomousTerraform.options(city, listOf(CivsAutonomousCandidate(hall)), surface, fixed = true)
        options.single().building.anchor shouldBe hall.anchor
        val draft = CivsAutonomousTerraform.draft(city, options.single(), surface)
        val cells = terraceCapture(draft, surface).toMutableMap()
        cells[marker] = CivTerraformCell(marker, "minecraft:barrel[facing=north,open=false]", false, true, false)
        val plan = CivsAutonomousTerraform.materialize(draft, cells)
        plan.route shouldBe listOf(hall.entrance)
        plan.blocks.none { it.position == marker } shouldBe true
        plan.blocks.none { it.position.x == hall.entrance.x && it.position.z == hall.entrance.z } shouldBe true
        plan.groundY shouldBe 72
    }

    test("a range exceeding the finite cut fill allowance is rejected rather than excavated unboundedly") {
        val city = terraceCity()
        val b = terraceBuilder(city)
        val surface = terraceSurface { x, _ -> 64 + (x + 6).coerceIn(0, 12) * 5 }
        CivsAutonomousTerraform.options(city, listOf(CivsAutonomousCandidate(b)), surface) shouldBe emptyList()
    }

    test("a continuous unavailable water barrier is not manufactured into a bridge") {
        val city = terraceCity().copy(radius = 24)
        val b = terraceBuilder(city).copy(bounds = CivBox(CivPosition(-6, 60, 19), CivPosition(6, 76, 24)))
        val surface = terraceSurface { _, _ -> 64 }.let { s -> s.copy(columns = s.columns.mapValues { (key, c) ->
            if (key.toInt() == 10) c.copy(natural = false) else c
        }) }
        val option = CivTerraformOption(b, 0)
        shouldThrow<CivsRejection> { CivsAutonomousTerraform.draft(city, option, surface) }.reason shouldBe "autonomy-no-path"
    }

    test("protected rock base may remain but protected cells in an excavation or fill cannot be consumed") {
        val city = terraceCity()
        val b = terraceBuilder(city)
        val p = CivPosition(2, 64, 22)
        val column = CivTerraformColumn(p.x, p.z, 64, 63, 66)
        val draft = CivTerraformDraft(b, listOf(column), listOf(city.buildings.single().entrance, b.entrance), 64)
        val surface = terraceSurface { _, _ -> 66 }
        val cells = terraceCapture(draft, surface).toMutableMap()
        val wall = p.copy(y = 65)
        cells[wall] = CivTerraformCell(wall, "minecraft:bricks", false, true, false)
        shouldThrow<CivsRejection> { CivsAutonomousTerraform.materialize(draft, cells) }.reason shouldBe "autonomy-terrain"
        cells[wall] = CivTerraformCell(wall, "minecraft:water[level=0]", false, false, false)
        shouldThrow<CivsRejection> { CivsAutonomousTerraform.materialize(draft, cells) }.reason shouldBe "autonomy-terrain"
        cells[wall] = CivTerraformCell(wall, "minecraft:bedrock", false, true, false)
        shouldThrow<CivsRejection> { CivsAutonomousTerraform.materialize(draft, cells) }.reason shouldBe "autonomy-terrain"
    }

    test("foundation must find solid support within twenty four blocks and cannot float over a deep void") {
        val city = terraceCity()
        val b = terraceBuilder(city)
        val column = CivTerraformColumn(2, 22, 64, 59, 64)
        val draft = CivTerraformDraft(b, listOf(column), listOf(b.entrance), 64)
        val cells = (35..66).associate { y -> CivPosition(2, y, 22).let {
            it to CivTerraformCell(it, "minecraft:air", true, false, true)
        } }.toMutableMap()
        shouldThrow<CivsRejection> { CivsAutonomousTerraform.materialize(draft, cells) }.reason shouldBe "autonomy-foundation"
        val base = CivPosition(2, 35, 22)
        cells[base] = CivTerraformCell(base, "minecraft:bedrock", false, true, false)
        val plan = CivsAutonomousTerraform.materialize(draft, cells)
        plan.blocks.none { it.position == base } shouldBe true
        plan.blocks.first().position shouldBe base.copy(y = 36)
        plan.blocks.last().position shouldBe base.copy(y = 64)
    }

    test("an obstructed source is rejected rather than clearing the worker standing column") {
        val city = terraceCity()
        val b = terraceBuilder(city)
        val entry = city.buildings.single().entrance
        val draft = CivTerraformDraft(b, listOf(CivTerraformColumn(entry.x, entry.z, 64, 64, 64, preserve = true)),
            listOf(entry), 64)
        val surface = terraceSurface { _, _ -> 64 }
        val cells = terraceCapture(draft, surface).toMutableMap()
        cells[entry] = CivTerraformCell(entry, "minecraft:stone", true, true, false)
        shouldThrow<CivsRejection> { CivsAutonomousTerraform.materialize(draft, cells) }.reason shouldBe "autonomy-no-path"
    }

    test("candidate effort and route grading remain deterministic including negative world columns") {
        val city = terraceCity()
        val b = terraceBuilder(city)
        val surface = terraceSurface { x, z -> 64 + ((x + z) / 5).coerceIn(-8, 8) }
        val options = CivsAutonomousTerraform.options(city, listOf(CivsAutonomousCandidate(b)), surface)
        options shouldBe CivsAutonomousTerraform.options(city, listOf(CivsAutonomousCandidate(b)), surface)
        val draft = CivsAutonomousTerraform.draft(city, options.first(), surface)
        draft shouldBe CivsAutonomousTerraform.draft(city, options.first(), surface)
        listOf(civsTerrainKey(-1, -1), civsTerrainKey(-1, 1), civsTerrainKey(1, -1), civsTerrainKey(1, 1))
            .distinct().size shouldBe 4
    }

    test("verified tree crown is cleared while the terrace height follows soil rather than the canopy") {
        val city = terraceCity()
        val b = terraceBuilder(city)
        val surface = terraceSurface { _, _ -> 64 }.let { s -> s.copy(columns = s.columns +
            (civsTerrainKey(2, 22) to CivTerrainSurfaceColumn(64, natural = true, topY = 74))) }
        val option = CivsAutonomousTerraform.options(city, listOf(CivsAutonomousCandidate(b)), surface).first()
        option.building.anchor.y shouldBe 64
        val draft = CivsAutonomousTerraform.draft(city, option, surface)
        draft.columns.single { it.x == 2 && it.z == 22 }.surfaceY shouldBe 74
        val cells = terraceCapture(draft, surface).toMutableMap()
        for (y in 65..74) {
            val p = CivPosition(2, y, 22)
            cells[p] = CivTerraformCell(p, if (y < 73) "minecraft:oak_log[axis=y]" else
                "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]", true, true, false)
        }
        val plan = CivsAutonomousTerraform.materialize(draft, cells)
        plan.cutCount shouldBe 10
        plan.blocks.all { it.after == "minecraft:air" } shouldBe true
        plan.blocks.filter { it.position.x == 2 && it.position.z == 22 }.map { it.position.y } shouldBe (74 downTo 65).toList()
    }

    test("a new graded road cannot raise the neighbouring forester entrance or its approach") {
        val original = terraceCity()
        val forester = original.buildings.single().copy(id = UUID.randomUUID(), type = BuildingType.FORESTER,
            anchor = CivPosition(2, 64, 8), entrance = CivPosition(2, 65, 8), phase = BuildingPhase.ACTIVE,
            bounds = CivBox(CivPosition(1, 64, 4), CivPosition(4, 71, 7)), control = CivPosition(2, 65, 6))
        val city = original.copy(buildings = original.buildings + forester)
        val b = terraceBuilder(city, 68)
        val access = CivAutonomousAccess()
        val protected = access.standing(city, b.id)
        val surface = terraceSurface { x, z -> protected[civsTerrainKey(x, z)]?.y?.minus(1)
            ?: (64 + (z.coerceAtLeast(0) / 3).coerceAtMost(4)) }
        val draft = CivsAutonomousTerraform.draft(city, CivTerraformOption(b, 0), surface, access)
        val plan = CivsAutonomousTerraform.materialize(draft, terraceCapture(draft, surface))
        plan.route.last() shouldBe b.entrance
        (plan.fillCount + plan.cutCount > 0) shouldBe true
        plan.blocks.none { civsTerrainKey(it.position.x, it.position.z) in protected } shouldBe true
        draft.columns.filter { civsTerrainKey(it.x, it.z) in protected }.all { it.preserve } shouldBe true
        plan.route.filter { civsTerrainKey(it.x, it.z) in protected }.forEach { point ->
            point shouldBe protected.getValue(civsTerrainKey(point.x, point.z))
        }
    }

    test("an established road can be crossed only at its existing grade without altering its support") {
        val original = terraceCity()
        val forester = original.buildings.single().copy(id = UUID.randomUUID(), type = BuildingType.FORESTER,
            anchor = CivPosition(44, 64, 10), entrance = CivPosition(44, 65, 10), phase = BuildingPhase.ACTIVE,
            bounds = CivBox(CivPosition(45, 64, 7), CivPosition(48, 71, 13)), control = CivPosition(46, 65, 10),
            rotation = 90)
        val city = original.copy(buildings = original.buildings + forester)
        val b = terraceBuilder(city, 68)
        // This retained spine crosses the entire finite search field, so a detour cannot avoid it.
        val road = (-48..44).map { CivPosition(it, 65, 10) }
        val access = CivAutonomousAccess(mapOf(forester.id to road))
        val protected = access.standing(city, b.id)
        val surface = terraceSurface { x, z -> protected[civsTerrainKey(x, z)]?.y?.minus(1)
            ?: if (z > 10) 68 else 64 }
        val draft = CivsAutonomousTerraform.draft(city, CivTerraformOption(b, 0), surface, access)
        val plan = CivsAutonomousTerraform.materialize(draft, terraceCapture(draft, surface))
        plan.route.filter { it.z == 10 }.isNotEmpty() shouldBe true
        plan.route.filter { it.z == 10 }.all { it.y == 65 } shouldBe true
        plan.blocks.none { civsTerrainKey(it.position.x, it.position.z) in protected } shouldBe true
        checkTerrace(plan, draft, terraceCapture(draft, surface))
    }

    test("a footprint cannot consume an older retained road even outside every existing building") {
        val city = terraceCity()
        val b = terraceBuilder(city)
        val road = (0..24).map { CivPosition(0, 65, it) }
        val access = CivAutonomousAccess(mapOf(city.buildings.single().id to road))
        shouldThrow<CivsRejection> {
            CivsAutonomousTerraform.draft(city, CivTerraformOption(b, 0), terraceSurface { _, _ -> 64 }, access)
        }.reason shouldBe "building-overlap"
    }
})

private fun terraceCity(): Settlement {
    val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(0, 64, 0), 0, "fixture",
        CivBox(CivPosition(-4, 64, -8), CivPosition(4, 70, -1)), CivPosition(0, 65, 0), BuildingPhase.SITE,
        CivPosition(0, 65, -2))
    return Settlement(UUID.randomUUID(), UUID.randomUUID(), "Terrace", UUID.randomUUID(), CivPosition(0, 64, 0),
        48, "fixture", Stock(), listOf(hall), emptyList(), autonomy = CivAutonomy())
}

/** Caledonia builder family header: 13 x 17 x 13, offset (-6,-4,-13), turned to face the town hall. */
private fun terraceBuilder(city: Settlement, ground: Int = 64): CivBuilding = CivBuilding(UUID.randomUUID(),
    BuildingType.BUILDER_HUT, 1, CivPosition(0, ground, 18), 180, "fixture",
    CivBox(CivPosition(-6, ground - 4, 19), CivPosition(6, ground + 12, 31)), CivPosition(0, ground + 1, 18),
    BuildingPhase.CONSTRUCTING, CivPosition(0, ground + 1, 22))

private fun terraceSurface(height: (Int, Int) -> Int): CivTerrainSurface = CivTerrainSurface(-64, 320,
    buildMap { for (x in -48..48) for (z in -48..48) put(civsTerrainKey(x, z), CivTerrainSurfaceColumn(height(x, z), true)) })

private fun terraceCapture(draft: CivTerraformDraft, surface: CivTerrainSurface): Map<CivPosition, CivTerraformCell> = buildMap {
    draft.columns.forEach { c ->
        val top = requireNotNull(surface.at(c.x, c.z)).groundY
        for (y in maxOf(surface.minHeight, c.floorY - CivsAutonomousTerraform.MAXIMUM_CHANGE)..maxOf(c.groundY, c.surfaceY) + 2) {
            val p = CivPosition(c.x, y, c.z)
            val data = when { y == top -> "minecraft:grass_block[snowy=false]"; y < top -> "minecraft:stone"; else -> "minecraft:air" }
            put(p, CivTerraformCell(p, data, true, y <= top, y > top))
        }
    }
}

/** Independent occupancy oracle on journal-after states, without relying on planner score or traversal. */
private fun checkTerrace(plan: CivTerraformPlan, draft: CivTerraformDraft, cells: Map<CivPosition, CivTerraformCell>) {
    val overlay = plan.blocks.associate { it.position to it.after }
    fun solid(p: CivPosition) = overlay[p]?.let { it != "minecraft:air" } ?: cells.getValue(p).solid
    fun passable(p: CivPosition) = overlay[p]?.let { it == "minecraft:air" } ?: cells.getValue(p).passable
    plan.route.map { civsTerrainKey(it.x, it.z) }.distinct().size shouldBe plan.route.size
    plan.route.zipWithNext().forEach { (a, b) ->
        (abs(a.x - b.x) + abs(a.z - b.z)) shouldBe 1
        (abs(a.y - b.y) <= 1) shouldBe true
    }
    plan.route.forEach { feet ->
        solid(feet.copy(y = feet.y - 1)) shouldBe true
        passable(feet) shouldBe true
        passable(feet.copy(y = feet.y + 1)) shouldBe true
    }
    val b = draft.building.bounds
    for (x in b.min.x..b.max.x) for (z in b.min.z..b.max.z) {
        solid(CivPosition(x, b.min.y - 1, z)) shouldBe true
        solid(CivPosition(x, draft.groundY, z)) shouldBe true
        passable(CivPosition(x, draft.groundY + 1, z)) shouldBe true
        passable(CivPosition(x, draft.groundY + 2, z)) shouldBe true
    }
}
