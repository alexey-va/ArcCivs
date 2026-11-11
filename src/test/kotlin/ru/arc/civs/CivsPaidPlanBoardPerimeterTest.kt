package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsPaidPlanBoardPerimeterTest : FunSpec({
    test("a buildable noticeboard defers an obstructed perimeter while retaining a paid gate and roads") {
        val type = BuildingType.PLAN_BOARD
        val seed = settlementPlanCity()
        val hall = seed.buildings.single().copy(level = 2)
        val base = seed.copy(style = "nordic", radius = 56, buildings = listOf(hall), autonomy = CivAutonomy(layoutHalfWidth = 32))
        val flat = settlementPlanSurface(base)
        val board = planBoardFixtureBuilding().let { authored ->
            fun relative(p: CivPosition) = CivPosition(p.x - authored.anchor.x, p.y - authored.anchor.y, p.z - authored.anchor.z)
            authored.copy(anchor = CivPosition(0, 0, 0), phase = BuildingPhase.CONSTRUCTING,
                bounds = CivBox(relative(authored.bounds.min), relative(authored.bounds.max)),
                entrance = relative(authored.entrance), control = relative(requireNotNull(authored.control)))
        }
        val templates = settlementPlanTemplates() + (type to (board to board))
        val frame = CivsSettlementLayout.frame(base)
        val gate = CivsSettlementLayout.infrastructure(base, frame, flat, emptyList()).pieces.first { it.type == BuildingType.GATE }
        val gateBuilding = CivsInfrastructure.building(gate)
        fun parcel(type: BuildingType, x: Int, z: Int, entrance: CivPosition = CivPosition(x + 1, 65, z + 4)) =
            CivBuilding(UUID(801, type.ordinal.toLong()), type, 1, CivPosition(x, 64, z), 0, "fixture/${type.name}",
                CivBox(CivPosition(x, 64, z), CivPosition(x + 3, 68, z + 3)), entrance, BuildingPhase.ACTIVE)
        val warehouse = parcel(BuildingType.WAREHOUSE, 21, -2, CivPosition(20, 65, 0)).copy(rotation = 90)
        val actual = listOf(hall, parcel(BuildingType.BUILDER_HUT, -23, -10), parcel(BuildingType.FORESTER, -44, 6),
            parcel(BuildingType.QUARRY, 42, 6), parcel(BuildingType.FARM, 0, 42), warehouse,
            parcel(BuildingType.HOUSE, -22, 10).copy(level = 2))
        val warehouseRoad = (0..20).map { x -> CivPosition(x, 65, 0) }
        val prior = CivSettlementPlan(CivsSettlementPlans.VERSION, 1, frame.halfWidth,
            actual.map { CivSettlementPlot(it.copy(phase = BuildingPhase.SITE), it.bounds) },
            mapOf(warehouse.id.toString() to warehouseRoad), frame.wallObstacles,
            frame.gates.map { it.passage }, frame.gates.map { it.side })
        val city = base.copy(buildings = actual + gateBuilding.copy(phase = BuildingPhase.ACTIVE),
            inventory = CivInventory(CivStorageMode.PHYSICAL),
            autonomy = requireNotNull(base.autonomy).copy(plan = prior))
        val gateReceipt = CivPlacementPlan(gate.id, city.id, city.worldId, gateBuilding,
            gate.blocks.map { CivPlanBlock(it.position, "minecraft:air", it.after) }, "a".repeat(64))
        val roadReceipts = prior.routes.map { (id, route) ->
            val building = requireNotNull(city.building(UUID.fromString(id))).copy(phase = BuildingPhase.CONSTRUCTING)
            val paidRoad = route.map { feet -> CivPlanBlock(feet.copy(y = feet.y - 1), "minecraft:grass_block[snowy=false]", "minecraft:dirt_path") }
            CivPlacementPlan(building.id, city.id, city.worldId, building,
                listOf(CivPlanBlock(building.bounds.min, "minecraft:air", "minecraft:spruce_planks")), "b".repeat(64),
                CivTerraformPlan(city.center.let { CivBox(it.copy(x = -city.radius, z = -city.radius),
                    it.copy(x = city.radius, z = city.radius)) }, paidRoad, route, 64, 0, 0))
        }
        val access = CivAutonomousAccess.from(city, roadReceipts + gateReceipt)
        access.infrastructure.single() shouldBe gate
        access.routes.mapKeys { it.key.toString() } shouldBe prior.routes
        CivsAutonomy.nextGoal(city)?.type shouldBe type
        val profile = CivsSettlementGrowth.profile(city)
        val paidRoadColumns = access.roadColumns(city)
        val protected = prior.walls.asSequence().flatMap { edge ->
            (edge.min.x..edge.max.x).asSequence().flatMap { x -> (edge.min.z..edge.max.z).asSequence().map { z -> civsTerrainKey(x, z) } }
        }.first { it !in paidRoadColumns }
        val surface = flat.copy(columns = flat.columns.mapValues { (key, cell) ->
            cell.copy(natural = key != protected, dirtPath = key in paidRoadColumns)
        })
        val infrastructure = CivsSettlementLayout.infrastructure(city, frame, surface, access.routes.values.toList(),
            access.infrastructure, retainedRoutes = access.routes.values, retainedAccess = access)
        infrastructure.perimeterClosed shouldBe false
        infrastructure.rejected["protected-terrain"] shouldBe 1
        infrastructure.pieces.single { it.id == gate.id } shouldBe gate

        val grown = CivsSettlementPlans.prepareGrowth(city, templates, surface, access, profile)
        val refusal = CivsRejection("autonomy-layout-obstructed", infrastructure.rejected.mapValues { it.value.toString() } +
            mapOf("stage" to "infrastructure", "closed" to "false"))
        CivsSettlementPlans.deferCivicParcel(city, templates, surface, access, profile, refusal) shouldBe grown
        CivsSettlementPlans.validate(city, grown)
        val plot = grown.plots.single { city.building(it.building.id) == null }
        plot.building.type shouldBe type
        plot.building.schematicId shouldBe CivsPlanBoardBlueprint.SCHEMATIC_ID
        grown.deferredPerimeter shouldBe CivDeferredPerimeter(CivsSettlementPlans.CIVIC_PERIMETER, plot.building.id, plot.building.anchor)
        grown.gates shouldBe prior.gates
        grown.walls shouldBe prior.walls
        access.routes.forEach { (id, route) -> grown.routes[id.toString()] shouldBe route }
        CivsAutonomousGeometry.overlaps(plot.envelope, gate.bounds) shouldBe false
        val route = grown.routes.getValue(plot.building.id.toString())
        val draft = CivsAutonomousTerraform.draft(city, CivTerraformOption(plot.building.copy(bounds = plot.envelope), 0),
            surface, access, grown.walls, reservedRoute = route, protectedRoadColumns = paidRoadColumns)
        draft.columns.any { it.preserve && civsTerrainKey(it.x, it.z) in paidRoadColumns } shouldBe true
        val cells = buildMap {
            for (column in draft.columns) for (y in column.supportY..(maxOf(column.surfaceY, column.groundY) + 2)) {
                val p = CivPosition(column.x, y, column.z)
                val paid = civsTerrainKey(p.x, p.z) in paidRoadColumns
                val solid = y <= column.groundY
                val material = if (!solid) "minecraft:air" else if (paid) "minecraft:dirt_path" else "minecraft:grass_block[snowy=false]"
                put(p, CivTerraformCell(p, material, safe = !paid, solid = solid, passable = !solid))
            }
        }
        val materialized = CivsAutonomousTerraform.materialize(draft, cells)
        materialized.route shouldBe route
        materialized.blocks.none { civsTerrainKey(it.position.x, it.position.z) in paidRoadColumns } shouldBe true
        materialized.blocks.none { gate.bounds.contains(it.position.x, it.position.y, it.position.z) } shouldBe true
        shouldThrow<CivsRejection> {
            CivsSettlementPlans.prepareGrowth(city, templates, surface, access.copy(infrastructure = emptyList()), profile)
        }.reason shouldBe "autonomy-layout-obstructed"
    }
})
