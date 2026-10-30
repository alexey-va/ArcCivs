package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.block.Chest
import org.bukkit.inventory.ItemStack
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

class CivsAutonomousFamilyFallbackTest : FunSpec({
    test("a new catalog search tries a compact capable family after the preferred envelope cannot fit") {
        InfrastructurePlacementScenario().use { s ->
            val preferred = "civs/v2/fixture/crusher/preferred/l1"
            val compact = "civs/v2/fixture/crusher/compact/l1"
            val id = s.familyCatalog(linkedMapOf(preferred to 50, compact to 0))
            val large = familyPlacementBlueprint(preferred, id, s.city.worldId, CivPosition(0, 0, 0), 0, 1, 50).building
            CivsSettlementLayout.candidates(s.city, large, large).isEmpty() shouldBe true
            CivsSettlementLayout.infill(s.city, large, large).isEmpty() shouldBe true
            val result = s.planner.search(s.city, BuildingType.CRUSHER, 1, id)
            spatialFailure(result) shouldBe "test-selected-infrastructure-draft"
            s.drafts.single().building.schematicId shouldBe compact
            s.drafts.single().building.id shouldBe id
            s.drafts.single().route.first() shouldBe s.city.buildings.single().entrance
            s.accessLoads shouldBe 1
            verify(exactly = 1) { s.world.autonomousHeightField(any(), any(), any()) }
        }
    }

    test("a compact family reuses the captured surface after every preferred terrain footprint is blocked") {
        InfrastructurePlacementScenario().use { s ->
            val preferred = "civs/v2/fixture/crusher/preferred/l1"
            val compact = "civs/v2/fixture/crusher/compact/l1"
            val id = s.familyCatalog(linkedMapOf(preferred to 3, compact to 0))
            val large = familyPlacementBlueprint(preferred, id, s.city.worldId, CivPosition(0, 0, 0), 0, 1, 3).building
            val grown = familyPlacementBlueprint(preferred, id, s.city.worldId, CivPosition(0, 0, 0), 0, 3, 3).building
            val choices = CivsSettlementLayout.candidates(s.city, large, grown) +
                CivsSettlementLayout.infill(s.city, large, grown)
            choices.isNotEmpty() shouldBe true
            s.keepFamilyPatch(compact, id, 0)
            CivsAutonomousTerraform.options(s.city, choices, s.surface).isEmpty() shouldBe true
            CivsSettlementLayout.infill(s.city, large, grown, eligible = { building ->
                CivsAutonomousTerraform.options(s.city, listOf(CivsAutonomousCandidate(building)), s.surface).isNotEmpty()
            }).isEmpty() shouldBe true
            val result = s.planner.search(s.city, BuildingType.CRUSHER, 1, id)
            spatialFailure(result) shouldBe "test-selected-infrastructure-draft"
            s.drafts.single().building.schematicId shouldBe compact
            s.drafts.single().building.id shouldBe id
            s.accessLoads shouldBe 1
            verify(exactly = 1) { s.world.autonomousHeightField(any(), any(), any()) }
        }
    }

    test("all families share one sixty four draft and native attempt budget") {
        InfrastructurePlacementScenario().use { s ->
            s.city = s.city.copy(autonomy = CivAutonomy(layoutHalfWidth = 32))
            val preferred = "civs/v2/fixture/crusher/preferred/l1"
            val compact = "civs/v2/fixture/crusher/compact/l1"
            val id = s.familyCatalog(linkedMapOf(preferred to 4, compact to 0))
            s.keepFamilyPatch(preferred, id, 4)
            val small = familyPlacementBlueprint(compact, id, s.city.worldId, CivPosition(0, 0, 0), 0, 1, 0).building
            val smallGrown = familyPlacementBlueprint(compact, id, s.city.worldId, CivPosition(0, 0, 0), 0, 3, 0).building
            val compactChoices = CivsSettlementLayout.infill(s.city, small, smallGrown, eligible = { building ->
                CivsAutonomousTerraform.options(s.city, listOf(CivsAutonomousCandidate(building)), s.surface).isNotEmpty()
            })
            val compactOptions = CivsAutonomousTerraform.options(s.city, compactChoices, s.surface).size
            withClue("compact choices=${compactChoices.size} options=$compactOptions dry columns=${s.surface.columns.count { it.value.natural }}") {
                (compactOptions >= CivsAutonomousPlacement.MAXIMUM_OPTIONS) shouldBe true
            }
            every { s.world.captureTerraform(any(), any(), any(), any()) } answers {
                s.drafts += arg<CivTerraformDraft>(1)
                CompletableFuture.failedFuture(CivsRejection("lands-denied"))
            }
            val result = s.planner.search(s.city, BuildingType.CRUSHER, 1, id)
            spatialFailure(result) shouldBe "autonomy-no-site"
            s.drafts.map { it.building.schematicId }.toSet() shouldBe setOf(preferred, compact)
            s.drafts.all { it.building.id == id } shouldBe true
            (s.drafts.size <= CivsAutonomousPlacement.MAXIMUM_OPTIONS) shouldBe true
            @Suppress("UNCHECKED_CAST")
            val terminal = (s.planner.debug(s.city.id)["last"] as List<Map<String, Any?>>).single()
            terminal["attempted"] shouldBe CivsAutonomousPlacement.MAXIMUM_OPTIONS
            terminal["catalog_source"] shouldBe compact
            terminal["families_tried"] shouldBe listOf(preferred, compact)
            s.accessLoads shouldBe 1
            verify(exactly = 1) { s.world.autonomousHeightField(any(), any(), any()) }
            s.planner.debug()["jobs"] shouldBe 0
        }
    }

    test("an accepted pinned catalog site never tries another family when its exact terrain is blocked") {
        InfrastructurePlacementScenario().use { s ->
            val preferred = "civs/v2/fixture/crusher/preferred/l1"
            val compact = "civs/v2/fixture/crusher/compact/l1"
            val id = s.familyCatalog(linkedMapOf(preferred to 0, compact to 0))
            val pinned = familyPlacementBlueprint(preferred, id, s.city.worldId, CivPosition(14, 64, 12), 0, 1, 0)
            s.city = CivsRules.validate(s.city.copy(buildings = s.city.buildings +
                pinned.building.copy(phase = BuildingPhase.SITE)))
            val before = s.city
            s.surface = s.surface.copy(columns = s.surface.columns.mapValues { (_, column) -> column.copy(natural = false) })
            val result = s.planner.prepareExisting(s.city, pinned)
            spatialFailure(result) shouldBe "autonomy-no-site"
            verify(exactly = 0) { s.blueprints.autonomousAlternatives(any(), any()) }
            verify(exactly = 0) { s.blueprints.prepareEntry(any(), any(), any(), any(), any(), any()) }
            s.drafts shouldBe emptyList()
            s.city shouldBe before
            s.city.building(id)?.schematicId shouldBe preferred
            s.city.building(id)?.bounds shouldBe pinned.building.bounds
        }
    }
})

class CivsAutonomousPlacementTest : FunSpec({
    test("a one-level plan board reserves its actual catalog volume without requesting a nonexistent upgrade") {
        for (style in CivsBlueprints.STYLES) {
            val city = spatialCity(UUID.randomUUID()).copy(style = style)
            val board = city.buildings.single().copy(type = BuildingType.PLAN_BOARD,
                schematicId = CivsBlueprints.schematicId(style, BuildingType.PLAN_BOARD, 1))
            val reserved = CivsAutonomousGeometry.futureBounds(city, board)
            reserved.contains(board.bounds.min.x, board.bounds.min.y, board.bounds.min.z) shouldBe true
            reserved.contains(board.bounds.max.x, board.bounds.max.y, board.bounds.max.z) shouldBe true
        }
    }

    for (gatesBuilt in listOf(false, true))
    test("infrastructure drafts ${if (gatesBuilt) "walls" else "gates"} before pending road paving") {
        InfrastructurePlacementScenario().use { s ->
            val route = (0..10).map { CivPosition(0, 65, it) }
            val seed = CivsSettlementLayout.infrastructure(s.city, CivsSettlementLayout.frame(s.city), s.surface, listOf(route))
            seed.pieces.any { it.type == BuildingType.ROAD } shouldBe true
            val gates = seed.pieces.filter { it.type == BuildingType.GATE }
            gates.size shouldBe 3
            if (gatesBuilt) s.city = s.city.copy(buildings = s.city.buildings + gates.map {
                CivsInfrastructure.building(it).copy(phase = BuildingPhase.ACTIVE)
            })
            s.access = CivAutonomousAccess(mapOf(s.city.buildings.first().id to route), if (gatesBuilt) gates else emptyList())
            val result = s.planner.infrastructure(s.city)
            spatialFailure(result) shouldBe "test-selected-infrastructure-draft"
            s.drafts.single().building.type shouldBe if (gatesBuilt) BuildingType.WALL else BuildingType.GATE
            s.drafts.single().route.first() shouldBe s.city.buildings.first().entrance
            s.drafts.single().route.last() shouldBe s.drafts.single().building.entrance
        }
    }

    for (type in listOf(BuildingType.ROAD, BuildingType.GATE, BuildingType.WALL))
    test("a completed $type approach remains protected without requesting a new paving generation") {
        InfrastructurePlacementScenario().use { s ->
            val route = (0..10).map { CivPosition(0, 65, it) }
            val seed = CivsSettlementLayout.infrastructure(s.city, CivsSettlementLayout.frame(s.city), s.surface, listOf(route))
            seed.rejected shouldBe emptyMap()
            seed.perimeterClosed shouldBe true
            s.city = s.city.copy(buildings = s.city.buildings + seed.pieces.map {
                CivsInfrastructure.building(it).copy(phase = BuildingPhase.ACTIVE)
            })
            val piece = seed.pieces.first { it.type == type }
            val retained = (2 downTo 0).map { offset ->
                if (type == BuildingType.WALL) piece.entrance.copy(z = piece.entrance.z + offset)
                else piece.entrance.copy(x = piece.entrance.x - offset)
            }
            s.access = CivAutonomousAccess(mapOf(s.city.buildings.first().id to route, piece.id to retained), seed.pieces)
            val standing = s.access.standing(s.city, UUID.randomUUID())
            retained.forEach { standing[civsTerrainKey(it.x, it.z)] shouldBe it }
            piece.transit.forEach { standing[civsTerrainKey(it.x, it.z)] shouldBe it }
            val result = s.planner.infrastructure(s.city)
            spatialFailure(result) shouldBe "autonomy-layout-complete"
            s.drafts shouldBe emptyList()
        }
    }

    test("candidate rings preserve full upgrade volume and each existing approach") {
        val city = spatialCity(UUID.randomUUID())
        val small = spatialBlueprint(UUID.randomUUID(), city.worldId, CivPosition(0, 0, 0), 0, 1).building
        val large = small.copy(level = 3, bounds = CivBox(CivPosition(-8, 0, -12), CivPosition(8, 10, -1)))
        val choices = CivsAutonomousGeometry.candidates(city, small, large)
        choices.isNotEmpty() shouldBe true
        (choices.size <= 64) shouldBe true
        choices shouldBe CivsAutonomousGeometry.candidates(city, small, large)
        choices.forEach { choice ->
            val b = choice.building
            civsBoxVolume(b.bounds) shouldBe civsBoxVolume(large.bounds)
            CivsAutonomousGeometry.approach(city.buildings.single()).none {
                CivsAutonomousGeometry.horizontalContains(b.bounds, it)
            } shouldBe true
            CivsAutonomousGeometry.overlaps(b.bounds, city.buildings.single().bounds, 2) shouldBe false
        }
    }

    test("candidate footprints reserve retained roads beyond the existing building envelopes") {
        val city = spatialCity(UUID.randomUUID())
        val local = spatialBlueprint(UUID.randomUUID(), city.worldId, CivPosition(0, 0, 0), 0, 1).building
        val choices = CivsAutonomousGeometry.candidates(city, local, local)
        val first = choices.first().building.bounds
        val retained = CivPosition(first.min.x, 65, first.min.z)
        val access = CivAutonomousAccess(mapOf(city.buildings.single().id to listOf(retained)))
        val reserved = CivsAutonomousGeometry.candidates(city, local, local, access)
        reserved.isNotEmpty() shouldBe true
        (reserved.size < choices.size) shouldBe true
        reserved.none { CivsAutonomousGeometry.horizontalContains(it.building.bounds, retained) } shouldBe true
    }

    test("search waits for its retained road snapshot and cancellation cancels the pending load") {
        spatialScenario { s ->
            s.access = CompletableFuture()
            val search = s.planner.search(s.city, BuildingType.HOUSE, 1, UUID.randomUUID())
            s.scheduler.tick()
            search.isDone shouldBe false
            @Suppress("UNCHECKED_CAST")
            val entries = s.planner.debug()["entries"] as List<Map<String, Any?>>
            entries.single()["phase"] shouldBe "access"
            search.cancel(false)
            s.access.isCancelled shouldBe true
        }
    }

    test("search chooses and fully captures a connected native plot without changing a block") {
        spatialScenario { s ->
            val future = s.planner.search(s.city, BuildingType.HOUSE, 1, UUID.randomUUID())
            s.finish(future)
            val design = future.join()
            val selected = design.blueprint
            selected.building.anchor.y shouldBe 64
            val capture = s.worldAccess.captureAutonomousSite(s.city.id, s.city.owner, s.city.worldId,
                selected.building, selected.cells, design.terrain)
            s.finish(capture)
            capture.join().building shouldBe selected.building
            val point = selected.building.control!!
            s.world.getBlockAt(point.x, point.y, point.z).type shouldBe Material.AIR
            selected.cells.forEach { cell ->
                val p = cell.position
                s.world.getBlockAt(p.x, p.y, p.z).type shouldBe if (p.y == 64) Material.GRASS_BLOCK else Material.AIR
            }
            s.planner.debug()["jobs"] shouldBe 0
        }
    }

    test("search exhausts unsafe frontage then captures a connected native infill without rewriting ore") {
        spatialScenario { s ->
            s.city = s.city.copy(autonomy = CivAutonomy(layoutHalfWidth = 24))
            val id = UUID.randomUUID()
            val current = spatialBlueprint(id, s.city.worldId, CivPosition(0, 0, 0), 0, 1).building
            val future = spatialBlueprint(id, s.city.worldId, CivPosition(0, 0, 0), 0, 3).building
            val frontage = CivsSettlementLayout.candidates(s.city, current, future)
            check(frontage.isNotEmpty())
            fun obstruction(candidate: CivBuilding, yard: CivBuilding): CivPosition? =
                (candidate.bounds.min.x..candidate.bounds.max.x).asSequence().flatMap { x ->
                    (candidate.bounds.min.z..candidate.bounds.max.z).asSequence().map { z -> CivPosition(x, 64, z) }
                }.firstOrNull { !CivsAutonomousGeometry.horizontalContains(yard.bounds, it) &&
                    CivsAutonomousGeometry.approach(yard).none { p -> p.x == it.x && p.z == it.z } &&
                    CivsAutonomousGeometry.approach(s.city.buildings.single()).none { p -> p.x == it.x && p.z == it.z } }
            val yard = CivsSettlementLayout.infill(s.city, current, future).first { candidate ->
                frontage.all { obstruction(it.building, candidate.building) != null }
            }.building
            val protected = mutableSetOf<CivPosition>()
            for (candidate in frontage) {
                val point = requireNotNull(obstruction(candidate.building, yard))
                protected += point
                s.world.getBlockAt(point.x, point.y, point.z).type = Material.COPPER_ORE
            }
            val search = s.planner.search(s.city, BuildingType.HOUSE, 1, id)
            s.finish(search)
            val design = search.join()
            @Suppress("UNCHECKED_CAST")
            val terminal = (s.planner.debug(s.city.id)["last"] as List<Map<String, Any?>>).single()
            terminal["candidate_source"] shouldBe "infill"
            terminal["frontage_candidates"] shouldBe frontage.size
            (terminal["infill_candidates"] as Int > 0) shouldBe true
            (terminal["candidate"] as Int <= 64) shouldBe true
            val terrain = requireNotNull(design.terrain)
            terrain.route.first() shouldBe s.city.buildings.single().entrance
            terrain.route.last() shouldBe design.blueprint.building.entrance
            val captured = s.worldAccess.captureAutonomousSite(s.city.id, s.city.owner, s.city.worldId,
                design.blueprint.building, design.blueprint.cells, terrain)
            s.finish(captured)
            captured.isCompletedExceptionally shouldBe false
            protected.forEach { point ->
                s.world.getBlockAt(point.x, point.y, point.z).type shouldBe Material.COPPER_ORE
                terrain.blocks.none { it.position == point } shouldBe true
            }
        }
    }

    test("preparing an existing site preserves its pinned geometry and refreshes changed terrain") {
        spatialScenario { s ->
            val blueprint = spatialBlueprint(UUID.randomUUID(), s.city.worldId, CivPosition(18, 64, 10), 90, 1)
            val site = blueprint.building.copy(phase = BuildingPhase.SITE)
            s.city = s.city.copy(autonomy = CivAutonomy(), buildings = s.city.buildings + site)
            val control = site.control!!
            s.world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
            val changed = site.bounds.min.copy(y = 65)
            val mound = s.world.getBlockAt(changed.x, changed.y, changed.z)
            mound.type = Material.DIRT
            s.world.getBlockAt(changed.x, 66, changed.z).type = Material.GRASS_BLOCK
            val original = mound.blockData.asString

            val first = s.planner.prepareExisting(s.city, blueprint)
            s.finish(first)
            val oldTerrain = first.join().terrain!!
            oldTerrain.blocks.single { it.position == changed }.before shouldBe original

            mound.type = Material.STONE
            val current = mound.blockData.asString
            val recaptured = s.worldAccess.capture(s.city.id, s.city.owner, s.city.worldId,
                blueprint.building, blueprint.cells, site, oldTerrain)
            s.finish(recaptured)
            recaptured.join().terrain!!.blocks.single { it.position == changed }.before shouldBe current
            oldTerrain.blocks.single { it.position == changed }.before shouldBe original
            mound.type shouldBe Material.STONE

            val added = changed.copy(y = 67)
            val addedBlock = s.world.getBlockAt(added.x, added.y, added.z)
            addedBlock.type = Material.GRASS_BLOCK
            oldTerrain.blocks.any { it.position == added } shouldBe false
            val stale = s.worldAccess.capture(s.city.id, s.city.owner, s.city.worldId,
                blueprint.building, blueprint.cells, site, oldTerrain)
            s.finish(stale)
            spatialFailure(stale) shouldBe "autonomy-terrain"

            val refreshed = s.planner.prepareExisting(s.city, blueprint)
            s.finish(refreshed)
            val design = refreshed.join()
            design.blueprint.building.anchor shouldBe site.anchor
            design.blueprint.building.rotation shouldBe site.rotation
            design.blueprint.building.control shouldBe site.control
            design.blueprint.building.entrance shouldBe site.entrance
            design.blueprint.building.bounds shouldBe site.bounds
            val terrain = design.terrain!!
            terrain.groundY shouldBe site.anchor.y
            terrain.blocks.single { it.position == changed }.before shouldBe current
            terrain.blocks.single { it.position == changed }.after shouldBe "minecraft:air"
            terrain.blocks.single { it.position == added }.before shouldBe addedBlock.blockData.asString
            terrain.blocks.any { it.position == control } shouldBe false
            oldTerrain.blocks.single { it.position == changed }.before shouldBe original

            val captured = s.worldAccess.capture(s.city.id, s.city.owner, s.city.worldId,
                design.blueprint.building, design.blueprint.cells, site, terrain)
            s.finish(captured)
            captured.join().terrain shouldBe terrain
            s.city.building(site.id) shouldBe site
            s.world.getBlockAt(control.x, control.y, control.z).type shouldBe Material.BARREL
            mound.type shouldBe Material.STONE
            addedBlock.type shouldBe Material.GRASS_BLOCK
        }
    }

    test("activating a road SITE keeps skipped paid spine grades and captures only new shoulders") {
        spatialScenario { s ->
            val paid = (0..16).map { CivPosition(0, 65, it) }
            val forester = s.city.buildings.single().copy(id = UUID.randomUUID(), type = BuildingType.FORESTER,
                anchor = CivPosition(0, 64, 16), entrance = paid.last(), rotation = 90,
                bounds = CivBox(CivPosition(1, 64, 13), CivPosition(5, 70, 19)), control = CivPosition(2, 65, 16))
            val piece = CivSettlementPiece(UUID.randomUUID(), BuildingType.ROAD,
                CivBox(CivPosition(-1, 64, 8), CivPosition(1, 66, 12)), CivPosition(-2, 65, 10),
                buildList {
                    for (x in -1..1) for (z in 8..12) for (y in 64..66) add(CivSettlementBlock(CivPosition(x, y, z),
                        if (x == 0) "minecraft:barrier" else if (y == 64) "minecraft:cobblestone" else "minecraft:air"))
                }, (-1..1).flatMap { x -> (8..12).map { z -> CivPosition(x, 65, z) } })
            val building = CivsInfrastructure.building(piece)
            val cells = piece.blocks.map { cell -> ArcSchematicBlock(
                ArcSchematicPoint(cell.position.x, cell.position.y, cell.position.z), cell.after,
                cell.after == "minecraft:air", cell.after == "minecraft:barrier") }
            val blueprint = CivBlueprint(building, s.city.worldId, mockk<ArcSchematicSnapshot>(), cells, cells, byteArrayOf(1))
            val site = building.copy(phase = BuildingPhase.SITE)
            s.city = s.city.copy(autonomy = CivAutonomy(), buildings = s.city.buildings + forester + site)
            s.access = CompletableFuture.completedFuture(CivAutonomousAccess(mapOf(forester.id to paid)))
            paid.drop(1).forEach { p -> s.world.getBlockAt(p.x, p.y - 1, p.z).type = Material.DIRT_PATH }
            val activation = s.planner.prepareExisting(s.city, blueprint)
            s.finish(activation)
            val design = activation.join()
            design.blueprint.building.transit shouldBe piece.transit
            design.blueprint.cells.filter { it.position.x == 0 }.all { it.isBarrier } shouldBe true
            val terrain = requireNotNull(design.terrain)
            terrain.blocks.none { it.position.x == 0 && it.position.z in 8..12 } shouldBe true
            val capture = s.worldAccess.capture(s.city.id, s.city.owner, s.city.worldId,
                design.blueprint.building, design.blueprint.cells, site, terrain)
            s.finish(capture)
            capture.join().blocks.none { it.position.x == 0 && it.position.z in 8..12 } shouldBe true
            paid.drop(1).forEach { p -> s.world.getBlockAt(p.x, p.y - 1, p.z).type shouldBe Material.DIRT_PATH }
        }
    }

    for (version in listOf(CivsSettlementPlans.VERSION, 99))
    test("existing SITE activation ${if (version == CivsSettlementPlans.VERSION) "retains its adopted route" else "ignores obsolete intent"}") {
        spatialScenario { s ->
            val blueprint = spatialBlueprint(UUID.randomUUID(), s.city.worldId, CivPosition(18, 64, 10), 90, 1)
            val site = blueprint.building.copy(phase = BuildingPhase.SITE)
            val route = (0..15).map { CivPosition(0, 65, it) } +
                (1..18).map { CivPosition(it, 65, 15) } + (14 downTo 10).map { CivPosition(18, 65, it) }
            val frame = CivsSettlementLayout.frame(s.city)
            val intent = CivSettlementPlan(version, 0, frame.halfWidth,
                (s.city.buildings + site).map { CivSettlementPlot(it, it.bounds) },
                mapOf(site.id.toString() to route), frame.wallObstacles, frame.gates.map { it.passage })
            s.city = s.city.copy(autonomy = CivAutonomy(plan = intent), buildings = s.city.buildings + site)
            val control = requireNotNull(site.control)
            s.world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
            (s.world.getBlockAt(control.x, control.y, control.z).state as Barrel).inventory.setItem(0, ItemStack(Material.DIAMOND, 2))
            val activation = s.planner.prepareExisting(s.city, blueprint)
            s.finish(activation)
            val design = activation.join()
            (design.terrain!!.route == route) shouldBe (version == CivsSettlementPlans.VERSION)
            design.terrain.route.last() shouldBe site.entrance
            design.blueprint.building shouldBe blueprint.building
            (s.world.getBlockAt(control.x, control.y, control.z).state as Barrel).inventory.getItem(0) shouldBe ItemStack(Material.DIAMOND, 2)
        }
    }

    for (blocked in listOf(false, true))
    test("an existing gate SITE refreshes one stale adopted approach without moving or bypassing terrain blocked=$blocked") {
        spatialScenario { s ->
            val bounds = CivBox(CivPosition(17, 64, 10), CivPosition(19, 67, 12))
            val piece = CivSettlementPiece(UUID.randomUUID(), BuildingType.GATE, bounds, CivPosition(18, 65, 9),
                buildList {
                    for (x in bounds.min.x..bounds.max.x) for (z in bounds.min.z..bounds.max.z)
                        for (y in bounds.min.y..bounds.max.y) add(CivSettlementBlock(CivPosition(x, y, z),
                            if (y == bounds.min.y) "minecraft:cobblestone" else "minecraft:air"))
                }, (10..12).map { CivPosition(18, 65, it) })
            val building = CivsInfrastructure.building(piece)
            val cells = piece.blocks.map { cell -> ArcSchematicBlock(
                ArcSchematicPoint(cell.position.x, cell.position.y, cell.position.z), cell.after,
                cell.after == "minecraft:air", false) }
            val blueprint = CivBlueprint(building, s.city.worldId, mockk<ArcSchematicSnapshot>(), cells, cells, byteArrayOf(1))
            val site = building.copy(phase = BuildingPhase.SITE)
            val occupied = spatialBlueprint(UUID.randomUUID(), s.city.worldId, CivPosition(0, 64, 9), 0, 1)
                .building.copy(phase = BuildingPhase.ACTIVE)
            val route = (0..9).map { CivPosition(0, 65, it) } + (1..18).map { CivPosition(it, 65, 9) }
            val frame = CivsSettlementLayout.frame(s.city)
            val intent = CivSettlementPlan(CivsSettlementPlans.VERSION, 0, frame.halfWidth,
                (s.city.buildings + occupied).map { CivSettlementPlot(it, it.bounds) },
                mapOf(site.id.toString() to route), frame.wallObstacles, frame.gates.map { it.passage })
            s.city = s.city.copy(autonomy = CivAutonomy(plan = intent),
                buildings = s.city.buildings + occupied + site, constructionQueue = listOf(site.id))
            requireNotNull(CivsSettlementPlans.current(s.city))
            val barriers = if (!blocked) emptyList() else buildList {
                for (x in -3..3) for (z in -3..3) if (kotlin.math.abs(x) == 3 || kotlin.math.abs(z) == 3)
                    for (y in 65..67) add(CivPosition(x, y, z))
            }
            barriers.forEach { s.world.getBlockAt(it.x, it.y, it.z).type = Material.STONE_BRICKS }
            val before = s.city
            val activation = s.planner.prepareExisting(s.city, blueprint)
            s.finish(activation)
            if (blocked) spatialFailure(activation) shouldBe "autonomy-no-site"
            else {
                val design = activation.join()
                design.blueprint.building shouldBe building
                val fresh = requireNotNull(design.terrain).route
                (fresh == route) shouldBe false
                fresh.first() shouldBe s.city.buildings.first().entrance
                fresh.last() shouldBe site.entrance
                fresh.none { CivsAutonomousGeometry.horizontalContains(occupied.bounds, it) } shouldBe true
                (fresh.size <= CivsAutonomousTerraform.MAXIMUM_ROUTE_LENGTH) shouldBe true
            }
            @Suppress("UNCHECKED_CAST")
            val terminal = (s.planner.debug(s.city.id)["last"] as List<Map<String, Any?>>).single()
            terminal["route_refreshed"] shouldBe true
            terminal["attempted"] shouldBe 2
            s.city shouldBe before
            s.city.building(site.id) shouldBe site
            s.city.construction shouldBe null
            s.city.constructionQueue shouldBe listOf(site.id)
            barriers.forEach { s.world.getBlockAt(it.x, it.y, it.z).type shouldBe Material.STONE_BRICKS }
        }
    }

    test("infrastructure selection ignores obsolete routes and reserved plots") {
        InfrastructurePlacementScenario().use { s ->
            val frame = CivsSettlementLayout.frame(s.city)
            val bad = CivSettlementPlan(99, 0, frame.halfWidth, emptyList(),
                mapOf(UUID.randomUUID().toString() to listOf(CivPosition(9999, 65, 9999))),
                frame.wallObstacles, frame.gates.map { it.passage })
            s.city = s.city.copy(autonomy = s.city.autonomy!!.copy(plan = bad))
            spatialFailure(s.planner.infrastructure(s.city)) shouldBe "test-selected-infrastructure-draft"
            s.drafts.single().building.type shouldBe BuildingType.GATE
        }
    }

    test("native terrain rejects water steep ground and a player wall without clearing them") {
        spatialScenario { s ->
            val b = spatialBlueprint(UUID.randomUUID(), s.city.worldId, CivPosition(18, 64, 10), 0, 1).building
            val p = b.bounds.min
            s.world.getBlockAt(p.x, 64, p.z).type = Material.WATER
            val wet = s.worldAccess.autonomousSurface(s.city, b)
            s.finish(wet)
            spatialFailure(wet) shouldBe "autonomy-terrain"
            s.world.getBlockAt(p.x, 64, p.z).type = Material.GRASS_BLOCK
            s.world.getBlockAt(p.x, 65, p.z).type = Material.DIRT
            s.world.getBlockAt(p.x, 66, p.z).type = Material.GRASS_BLOCK
            val steep = s.worldAccess.autonomousSurface(s.city, b)
            s.finish(steep)
            spatialFailure(steep) shouldBe "autonomy-slope"
            s.world.getBlockAt(p.x, 65, p.z).type = Material.AIR
            s.world.getBlockAt(p.x, 66, p.z).type = Material.AIR
            s.world.getBlockAt(p.x, 65, p.z).type = Material.OAK_PLANKS
            val occupied = s.worldAccess.validateAutonomousSite(s.city, b)
            s.finish(occupied)
            spatialFailure(occupied) shouldBe "autonomy-terrain"
            s.world.getBlockAt(p.x, 65, p.z).type shouldBe Material.OAK_PLANKS
        }
    }

    test("a late foreign block is refused by the final automatic capture") {
        spatialScenario { s ->
            val blueprint = spatialBlueprint(UUID.randomUUID(), s.city.worldId, CivPosition(18, 64, 10), 0, 1)
            val b = blueprint.building
            val checked = s.worldAccess.validateAutonomousSite(s.city, b)
            s.finish(checked)
            checked.join() shouldBe Unit
            val p = b.bounds.min.copy(y = 65)
            s.world.getBlockAt(p.x, p.y, p.z).type = Material.BRICKS
            val captured = s.worldAccess.captureAutonomousSite(s.city.id, s.city.owner, s.city.worldId, b, blueprint.cells)
            s.finish(captured)
            spatialFailure(captured) shouldBe "autonomy-terrain"
            s.world.getBlockAt(p.x, p.y, p.z).type shouldBe Material.BRICKS
        }
    }

    test("a foreign barrel appearing at the new control marker cannot be adopted") {
        spatialScenario { s ->
            val blueprint = spatialBlueprint(UUID.randomUUID(), s.city.worldId, CivPosition(18, 64, 10), 0, 1)
            val point = blueprint.building.control!!
            val block = s.world.getBlockAt(point.x, point.y, point.z)
            block.type = Material.BARREL
            (block.state as Barrel).inventory.setItem(0, ItemStack(Material.DIAMOND, 1))
            val captured = s.worldAccess.captureAutonomousSite(s.city.id, s.city.owner, s.city.worldId,
                blueprint.building, blueprint.cells)
            s.finish(captured)
            spatialFailure(captured) shouldBe "autonomy-terrain"
            block.type shouldBe Material.BARREL
            (block.state as Barrel).inventory.getItem(0)?.type shouldBe Material.DIAMOND
        }
    }

    test("autonomous upgrade preserves its own walls but refuses foreign expansion and a late filled cell") {
        spatialScenario { s ->
            val upgrade = s.ownedUpgrade()
            val old = s.city.buildings.single()
            val cells = spatialCells(upgrade)
            val accepted = s.worldAccess.captureSite(s.city.id, s.city.owner, s.city.worldId, upgrade, cells, old)
            s.finish(accepted)
            accepted.isCompletedExceptionally shouldBe false

            val outside = s.world.getBlockAt(upgrade.bounds.max.x, 65, -3)
            outside.type = Material.BRICKS
            val obstructed = s.worldAccess.captureSite(s.city.id, s.city.owner, s.city.worldId, upgrade, cells, old)
            s.finish(obstructed)
            spatialFailure(obstructed) shouldBe "autonomy-terrain"
            outside.type shouldBe Material.BRICKS
            outside.type = Material.AIR

            val inside = s.world.getBlockAt(0, 65, -4)
            inside.type = Material.CHEST
            (inside.state as Chest).inventory.setItem(0, ItemStack(Material.DIAMOND, 1))
            val drifted = s.worldAccess.revalidate(accepted.join(), s.city.owner)
            s.finish(drifted)
            spatialFailure(drifted) shouldBe "container-not-empty"
            (inside.state as Chest).inventory.getItem(0) shouldBe ItemStack(Material.DIAMOND, 1)
        }
    }

    test("autonomous upgrade retains an unchanged filled owned chest with its native metadata") {
        spatialScenario { s ->
            val upgrade = s.ownedUpgrade()
            val old = s.city.buildings.single()
            val point = ArcSchematicPoint(0, 65, -4)
            val state = "minecraft:chest[facing=south,type=single,waterlogged=false]"
            val block = s.world.getBlockAt(point.x, point.y, point.z).also { it.type = Material.CHEST }
            val key = org.bukkit.NamespacedKey("arccivs", "retained_spatial")
            (block.state as Chest).also {
                it.blockData = org.bukkit.Bukkit.createBlockData(state)
                it.persistentDataContainer.set(key, org.bukkit.persistence.PersistentDataType.STRING, "native-holder")
                it.update(true, false) shouldBe true
            }
            val item = ItemStack(Material.DIAMOND, 3).apply { editMeta {
                it.displayName(net.kyori.adventure.text.Component.text("Upgrade keepsake"))
                it.persistentDataContainer.set(key, org.bukkit.persistence.PersistentDataType.STRING, "exact-item")
            } }
            (block.state as Chest).blockInventory.setItem(0, item)
            val cells = spatialCells(upgrade).map { cell -> if (cell.position == point)
                ArcSchematicBlock(point, state, false, false) else cell }
            val captured = s.worldAccess.capture(s.city.id, s.city.owner, s.city.worldId, upgrade, cells, old)
            s.finish(captured)
            val plan = captured.join()
            plan.blocks.single { it.position == CivPosition(point.x, point.y, point.z) } shouldBe
                CivPlanBlock(CivPosition(point.x, point.y, point.z), state, state)
            CivsMaterials.required(plan)["CHEST"] shouldBe null
            val checked = s.worldAccess.revalidate(plan, s.city.owner)
            s.finish(checked)
            checked.join() shouldBe Unit
            (block.state as Chest).blockInventory.getItem(0) shouldBe item
            (block.state as Chest).persistentDataContainer.get(key, org.bukkit.persistence.PersistentDataType.STRING) shouldBe "native-holder"
        }
    }

    for (after in listOf("minecraft:air", "minecraft:chest[facing=east,type=single,waterlogged=false]"))
    test("autonomous upgrade only proposes a filled owned chest changing to $after until evacuation") {
        spatialScenario { s ->
            val upgrade = s.ownedUpgrade()
            val old = s.city.buildings.single()
            val point = ArcSchematicPoint(0, 65, -4)
            val before = "minecraft:chest[facing=south,type=single,waterlogged=false]"
            val block = s.world.getBlockAt(point.x, point.y, point.z).also { it.type = Material.CHEST }
            (block.state as Chest).also {
                it.blockData = org.bukkit.Bukkit.createBlockData(before)
                it.update(true, false) shouldBe true
            }
            val item = ItemStack(Material.DIAMOND, 3)
            (block.state as Chest).blockInventory.setItem(0, item)
            val cells = spatialCells(upgrade).map { cell -> if (cell.position == point)
                ArcSchematicBlock(point, after, after == "minecraft:air", false) else cell }
            val proposal = s.worldAccess.captureSite(s.city.id, s.city.owner, s.city.worldId, upgrade, cells, old)
            s.finish(proposal)
            proposal.join().blocks.single { it.position == CivPosition(point.x, point.y, point.z) } shouldBe
                CivPlanBlock(CivPosition(point.x, point.y, point.z), before, after)
            val captured = s.worldAccess.capture(s.city.id, s.city.owner, s.city.worldId, upgrade, cells, old)
            s.finish(captured)
            spatialFailure(captured) shouldBe "container-not-empty"
            val checked = s.worldAccess.revalidate(proposal.join(), s.city.owner)
            s.finish(checked)
            spatialFailure(checked) shouldBe "container-not-empty"
            block.blockData.asString shouldBe before
            (block.state as Chest).blockInventory.getItem(0) shouldBe item
        }
    }

    test("route proof rejects a bounded partial path before a wall enclosing the only entrance") {
        spatialScenario { s ->
            val b = spatialBlueprint(UUID.randomUUID(), s.city.worldId, CivPosition(0, 64, 18), 180, 1).building
            val target = b.entrance
            for (dx in -1..1) for (dz in -1..1) if (dx != 0 || dz != 0)
                for (y in 65..68) s.world.getBlockAt(target.x + dx, y, target.z + dz).type = Material.BRICKS
            val route = s.worldAccess.autonomousRoute(s.city, b, s.city.buildings.single().entrance, Executor(Runnable::run))
            s.finish(route)
            (spatialFailure(route) in setOf("autonomy-no-path", "autonomy-search-budget")) shouldBe true
        }
    }

    test("large terrain validation yields to the next tick and close cancels it") {
        spatialScenario { s ->
            val b = spatialBlueprint(UUID.randomUUID(), s.city.worldId, CivPosition(18, 64, 10), 0, 1).building.copy(
                bounds = CivBox(CivPosition(10, 64, 4), CivPosition(29, 84, 23)))
            val validation = s.worldAccess.validateAutonomousSite(s.city, b)
            s.scheduler.tick()
            validation.isDone shouldBe false
            s.worldAccess.close()
            validation.isCancelled shouldBe true
        }
    }
})

/** Real catalog and terraform draft; stop at the native boundary so selection has no world side effects. */
private class InfrastructurePlacementScenario : AutoCloseable {
    var city = spatialCity(UUID.randomUUID()).copy(autonomy = CivAutonomy(layoutHalfWidth = 24))
    var access = CivAutonomousAccess()
    var surface = CivTerrainSurface(-64, 320, buildMap {
        for (x in -96..96) for (z in -96..96) put(civsTerrainKey(x, z), CivTerrainSurfaceColumn(64, true))
    })
    val drafts = mutableListOf<CivTerraformDraft>()
    val world = mockk<CivsWorld>()
    val blueprints = mockk<CivsBlueprints>()
    var accessLoads = 0
    private val tasks = LifecycleTaskScope(TestTaskScheduler())
    val planner = CivsAutonomousPlacement(blueprints, world, Executor(Runnable::run),
        loadAccess = { accessLoads++; CompletableFuture.completedFuture(access) })
    init {
        every { blueprints.autonomousSource(any(), any()) } returns null
        // The same dry fixture answers both ordinary scans and explicit infrastructure water-survey columns.
        every { world.autonomousHeightField(any(), any(), any(), any()) } answers { CompletableFuture.completedFuture(surface) }
        every { world.captureTerraform(any(), any(), any(), any()) } answers {
            drafts += arg<CivTerraformDraft>(1)
            CompletableFuture.failedFuture(CivsRejection("test-selected-infrastructure-draft"))
        }
    }
    override fun close() { planner.close(); tasks.close() }
}

private fun InfrastructurePlacementScenario.familyCatalog(families: Map<String, Int>): UUID {
    val id = UUID.randomUUID()
    every { blueprints.autonomousSource(any(), BuildingType.CRUSHER) } returns families.keys.first()
    every { blueprints.autonomousAlternatives(any(), BuildingType.CRUSHER) } returns families.keys.drop(1)
    every { blueprints.maximumLevel(any()) } returns 3
    every { blueprints.prepareEntry(any(), any(), any(), any(), any(), any()) } answers {
        val entry = firstArg<String>()
        CompletableFuture.completedFuture(familyPlacementBlueprint(entry, arg(1), arg(2), arg(3), arg<Int?>(5) ?: 0,
            1, families.getValue(entry)))
    }
    every { blueprints.prepareUpgrade(any(), any(), any()) } answers {
        val building = firstArg<CivBuilding>()
        CompletableFuture.completedFuture(familyPlacementBlueprint(building.schematicId, building.id, arg(2),
            building.anchor, building.rotation, arg(1), families.getValue(building.schematicId)))
    }
    return id
}

/** Catalog metadata only; these scenarios deliberately stop before native capture accepts any cell. */
private fun familyPlacementBlueprint(entry: String, id: UUID, world: UUID, anchor: CivPosition, rotation: Int,
    level: Int, padding: Int): CivBlueprint {
    val blueprint = spatialBlueprint(id, world, anchor, rotation, level)
    val corners = listOf(-2 - padding to -4 - padding, 2 + padding to -4 - padding,
        -2 - padding to -1, 2 + padding to -1).map { (x, z) ->
        CivsBlueprintGeometry.rotate(ArcSchematicPoint(x, 0, z), rotation / 90).let {
            CivPosition(anchor.x + it.x, anchor.y, anchor.z + it.z)
        }
    }
    val bounds = CivBox(CivPosition(corners.minOf { it.x }, blueprint.building.bounds.min.y, corners.minOf { it.z }),
        CivPosition(corners.maxOf { it.x }, blueprint.building.bounds.max.y, corners.maxOf { it.z }))
    return blueprint.copy(building = blueprint.building.copy(type = BuildingType.CRUSHER, schematicId = entry,
        bounds = bounds))
}

/** Keep one real detached terrace and its connected lane dry; all other columns stay protected. */
private fun InfrastructurePlacementScenario.keepFamilyPatch(entry: String, id: UUID, padding: Int) {
    val current = familyPlacementBlueprint(entry, id, city.worldId, CivPosition(0, 0, 0), 0, 1, padding).building
    val grown = familyPlacementBlueprint(entry, id, city.worldId, CivPosition(0, 0, 0), 0, 3, padding).building
    val choices = CivsSettlementLayout.candidates(city, current, grown).ifEmpty {
        CivsSettlementLayout.infill(city, current, grown)
    }
    val patch = CivsAutonomousTerraform.options(city, choices, surface).asSequence().mapNotNull { option ->
        runCatching { CivsAutonomousTerraform.draft(city, option, surface, access,
            CivsSettlementLayout.frame(city).wallObstacles) }.getOrNull()
    }.first()
    val dry = patch.columns.mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }
    surface = surface.copy(columns = surface.columns.mapValues { (key, value) -> value.copy(natural = key in dry) })
    CivsAutonomousTerraform.options(city, choices, surface).isNotEmpty() shouldBe true
}

private class AutonomousSpatialScenario(paper: MockBukkitTestRuntime) : AutoCloseable {
    val world = CivsWorksiteWorldFixture("autonomous-placement").also(paper.server::addWorld)
    var city = spatialCity(world.uid)
    val scheduler = TestTaskScheduler()
    private val tasks = LifecycleTaskScope(scheduler, true)
    val worldAccess = CivsWorld(paper.createSimplePlugin("CivsAutonomousPlacementTest"), tasks, { 0L }) { listOf(city) }
    private val blueprints = mockk<CivsBlueprints>()
    var access = CompletableFuture.completedFuture(CivAutonomousAccess())
    val planner = CivsAutonomousPlacement(blueprints, worldAccess, Executor(Runnable::run), loadAccess = { access })
    init {
        every { blueprints.autonomousSource(any(), any()) } returns null
        for (x in -3..3) for (z in -3..3) world.loadChunk(x, z)
        for (x in -40..40) for (z in -40..40) {
            world.getBlockAt(x, 63, z).type = Material.DIRT
            world.getBlockAt(x, 64, z).type = Material.GRASS_BLOCK
        }
        every { blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            CompletableFuture.completedFuture(spatialBlueprint(arg(3), arg(4), arg(5), arg<Int?>(7) ?: 0, arg(2)))
        }
    }
    fun ownedUpgrade(): CivBuilding {
        city = city.copy(autonomy = CivAutonomy())
        val old = city.buildings.single()
        for (x in old.bounds.min.x..old.bounds.max.x) for (y in old.bounds.min.y..old.bounds.max.y)
            for (z in old.bounds.min.z..old.bounds.max.z) world.getBlockAt(x, y, z).type = Material.OAK_PLANKS
        val marker = requireNotNull(old.control)
        world.getBlockAt(marker.x, marker.y, marker.z).type = Material.BARREL
        return old.copy(level = 2, phase = BuildingPhase.CONSTRUCTING,
            bounds = old.bounds.copy(max = old.bounds.max.copy(x = old.bounds.max.x + 1)))
    }
    fun finish(future: CompletableFuture<*>) {
        repeat(300) { if (!future.isDone) scheduler.tick() }
        future.isDone shouldBe true
    }
    override fun close() { planner.close(); worldAccess.close(); tasks.close() }
}

private fun spatialScenario(block: (AutonomousSpatialScenario) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open().use { paper -> AutonomousSpatialScenario(paper).use(block) }
}

private fun spatialCity(world: UUID): Settlement {
    val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(0, 64, 0), 0, "fixture",
        CivBox(CivPosition(-3, 64, -7), CivPosition(3, 68, -1)), CivPosition(0, 65, 0), BuildingPhase.ACTIVE,
        CivPosition(0, 65, -2))
    return Settlement(UUID.randomUUID(), UUID.randomUUID(), "Spatial", world, CivPosition(0, 64, 0), 96,
        "fixture", Stock(), listOf(hall), emptyList())
}

private fun spatialBlueprint(id: UUID, world: UUID, anchor: CivPosition, rotation: Int, level: Int): CivBlueprint {
    fun point(x: Int, y: Int, z: Int): CivPosition = CivsBlueprintGeometry.rotate(ArcSchematicPoint(x, y, z), rotation / 90)
        .let { CivPosition(anchor.x + it.x, anchor.y + it.y, anchor.z + it.z) }
    val corners = listOf(point(-2, 0, -4), point(2, 4 + level, -1))
    val bounds = CivBox(CivPosition(corners.minOf { it.x }, corners.minOf { it.y }, corners.minOf { it.z }),
        CivPosition(corners.maxOf { it.x }, corners.maxOf { it.y }, corners.maxOf { it.z }))
    val control = point(0, 1, -2)
    val building = CivBuilding(id, BuildingType.HOUSE, level, anchor, rotation, "fixture", bounds, point(0, 1, 0),
        BuildingPhase.CONSTRUCTING, control)
    val cells = spatialCells(building)
    return CivBlueprint(building, world, mockk<ArcSchematicSnapshot>(), cells, cells, byteArrayOf(1))
}

private fun spatialCells(building: CivBuilding): List<ArcSchematicBlock> {
    val bounds = building.bounds
    return buildList {
        for (x in bounds.min.x..bounds.max.x) for (y in bounds.min.y..bounds.max.y) for (z in bounds.min.z..bounds.max.z) {
            val at = CivPosition(x, y, z)
            val data = if (at == building.control) "minecraft:structure_void" else if (y == bounds.min.y) "minecraft:oak_planks" else "minecraft:air"
            add(ArcSchematicBlock(ArcSchematicPoint(x, y, z), data, data == "minecraft:air", false))
        }
    }
}

private fun spatialFailure(future: CompletableFuture<*>): String {
    future.isDone shouldBe true
    return try { future.join(); error("Expected failed placement") } catch (failure: CompletionException) {
        val rejection = generateSequence(failure as Throwable) { it.cause }
            .filterIsInstance<CivsRejection>().firstOrNull()
        rejection?.reason ?: throw AssertionError("Expected CivsRejection, got ${failure.cause}", failure)
    }
}
