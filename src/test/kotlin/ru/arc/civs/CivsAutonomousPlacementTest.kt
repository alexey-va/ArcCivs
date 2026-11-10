package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
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
    test("the next paid civic site can use a smaller full Blacksmith family between existing roads") {
        InfrastructurePlacementScenario(32).use { s ->
            val (city, access) = civicFamilyTown()
            s.city = city
            s.access = access
            val protectedRoads = access.roadColumns(city)
            val flat = settlementPlanSurface(city)
            s.surface = flat.copy(columns = flat.columns.mapValues { (key, cell) -> cell.copy(dirtPath = key in protectedRoads) })

            // Relative frames and entrances of the installed Medieval Oak level I–III families.
            fun family(name: String, revision: String, bounds: CivBox): Pair<CivBuilding, CivBuilding> {
                val id = "civs/v2/medievaloak/craftsmanship-metallurgy-$name/$revision"
                val first = CivBuilding(UUID(146, 99), BuildingType.BLACKSMITH, 1, CivPosition(0, 0, 0), 0,
                    "$id/level1", bounds, CivPosition(0, 1, 0), BuildingPhase.CONSTRUCTING, CivPosition(1, 1, -1))
                return first to first.copy(level = 3, schematicId = "$id/level3")
            }
            val preferred = family("altblacksmith", "7353cff2441dd2f55db1",
                CivBox(CivPosition(-14, -1, -26), CivPosition(11, 21, -1)))
            val compact = family("blacksmith", "e8468c3965a2e160aa67",
                CivBox(CivPosition(-13, -2, -14), CivPosition(8, 13, -1)))
            val type = BuildingType.BLACKSMITH
            val templates = settlementPlanTemplates() + (type to preferred)
            val profile = CivsSettlementGrowth.profile(city)
            profile.last() shouldBe type
            val refusal = shouldThrow<CivsRejection> {
                CivsSettlementPlans.prepareGrowth(city, templates, s.surface, access, profile)
            }
            refusal.reason shouldBe "autonomy-no-site"
            refusal.values shouldBe mapOf("type" to type.name, "frontage_candidates" to "0", "frontage_options" to "0",
                "infill_candidates" to "0", "infill_options" to "0", "samples" to "", "draft_samples" to "")

            val snapshot = mockk<ArcSchematicSnapshot>()
            fun loaded(building: CivBuilding) = CompletableFuture.completedFuture(
                CivBlueprint(building, city.worldId, snapshot, emptyList(), emptyList(), byteArrayOf(1)))
            val families = listOf(preferred, compact).associateBy { it.first.schematicId }
            every { s.blueprints.autonomousSource(city.style, type) } returns preferred.first.schematicId
            every { s.blueprints.autonomousAlternatives(city.style, type) } returns listOf(compact.first.schematicId)
            every { s.blueprints.maximumLevel(any()) } returns 3
            every { s.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                val pair = templates.getValue(arg(1))
                loaded((if (arg<Int>(2) == 1) pair.first else pair.second).copy(id = arg(3)))
            }
            every { s.blueprints.prepareEntry(any(), any(), any(), any(), any(), any()) } answers {
                loaded(families.getValue(firstArg<String>()).first.copy(id = arg(1)))
            }
            every { s.blueprints.prepareUpgrade(any(), any(), any()) } answers {
                val first = firstArg<CivBuilding>()
                loaded(families.getValue(first.schematicId).second.copy(id = first.id))
            }

            val planned = s.planner.plan(city).join()
            val previous = requireNotNull(city.autonomy?.plan)
            val added = planned.plots.single { city.building(it.building.id) == null }
            added.building.schematicId shouldBe compact.first.schematicId
            added.envelope shouldBe CivsSettlementPlans.at(compact.second, added.building.anchor, added.building.rotation).bounds
            planned.plots.filter { it != added } shouldBe previous.plots
            planned.walls shouldBe previous.walls
            planned.gates shouldBe previous.gates
            planned.deferredPerimeter?.plotId shouldBe added.building.id
            access.routes.forEach { (owner, route) -> planned.routes[owner.toString()] shouldBe route }
            val route = planned.routes.getValue(added.building.id.toString())
            val draft = CivsAutonomousTerraform.draft(city, CivTerraformOption(added.building.copy(bounds = added.envelope), 0),
                s.surface, access, planned.walls, reservedRoute = route, protectedRoadColumns = protectedRoads)
            draft.columns.any { it.preserve && civsTerrainKey(it.x, it.z) in protectedRoads } shouldBe true
            draft.columns.none { !it.preserve && civsTerrainKey(it.x, it.z) in protectedRoads } shouldBe true
            verify(exactly = 1) { s.blueprints.autonomousAlternatives(city.style, type) }

            val typed = previous.copy(perimeterEntrances = mapOf(UUID(146, 600).toString() to CivPosition(0, 65, 30)))
            val pinned = city.copy(autonomy = requireNotNull(city.autonomy).copy(plan = typed))
            CivsSettlementPlans.current(pinned) shouldBe typed
            CivsSettlementPlans.deferCivicParcel(pinned, templates + (type to compact), s.surface, access, profile, refusal) shouldBe null
        }
    }

    for (type in listOf(BuildingType.CRUSHER, BuildingType.FARM))
    test("a reachable $type infill reaches native capture before sixty four gradeable frontage refusals exhaust the budget") {
        InfrastructurePlacementScenario(56).use { s ->
            val entry = "civs/v2/fixture/crusher/preferred/l1"
            val id = s.familyCatalog(linkedMapOf(entry to 0), type)
            val current = familyPlacementBlueprint(entry, id, s.city.worldId, CivPosition(0, 0, 0), 0, 1, 0).building.copy(type = type)
            val future = familyPlacementBlueprint(entry, id, s.city.worldId, CivPosition(0, 0, 0), 0, 3, 0).building.copy(type = type)
            val frontage = CivsSettlementLayout.candidates(s.city, current, future)
            (CivsAutonomousTerraform.options(s.city, frontage, s.surface).size >= 64) shouldBe true
            val front = frontage.mapTo(hashSetOf()) { it.building.anchor.copy(y = 0) to it.building.rotation }
            every { s.world.captureTerraform(any(), any(), any(), any()) } answers {
                val draft = arg<CivTerraformDraft>(1).also(s.drafts::add)
                CompletableFuture.failedFuture(CivsRejection(if ((draft.building.anchor.copy(y = 0) to draft.building.rotation) in front)
                    "lands-denied" else "test-selected-infrastructure-draft"))
            }
            spatialFailure(s.planner.search(s.city, type, 1, id)) shouldBe "test-selected-infrastructure-draft"
            ((s.drafts.last().building.anchor.copy(y = 0) to s.drafts.last().building.rotation) in front) shouldBe false
            @Suppress("UNCHECKED_CAST")
            val terminal = (s.planner.debug(s.city.id)["last"] as List<Map<String, Any?>>).single()
            terminal["candidate_source"] shouldBe "infill"
            (terminal["attempted"] as Int in 33..64) shouldBe true
            s.accessLoads shouldBe 1
            verify(exactly = 1) { s.world.autonomousHeightField(any(), any(), any()) }
        }
    }

    test("farm frontage infill and alternate families share exactly sixty four attempts") {
        InfrastructurePlacementScenario(56).use { s ->
            val preferred = "civs/v2/fixture/farm/preferred/l1"
            val compact = "civs/v2/fixture/farm/compact/l1"
            val id = s.familyCatalog(linkedMapOf(preferred to 0, compact to 0), BuildingType.FARM)
            every { s.world.captureTerraform(any(), any(), any(), any()) } answers {
                s.drafts += arg<CivTerraformDraft>(1)
                CompletableFuture.failedFuture(CivsRejection("lands-denied"))
            }
            spatialFailure(s.planner.search(s.city, BuildingType.FARM, 1, id)) shouldBe "autonomy-no-site"
            @Suppress("UNCHECKED_CAST")
            val terminal = (s.planner.debug(s.city.id)["last"] as List<Map<String, Any?>>).single()
            terminal["attempted"] shouldBe CivsAutonomousPlacement.MAXIMUM_OPTIONS
            terminal["families_tried"] shouldBe listOf(preferred, compact)
            val frame = CivsSettlementLayout.frame(s.city)
            for (entry in listOf(preferred, compact)) {
                val drafts = s.drafts.filter { it.building.schematicId == entry }
                drafts.any { CivsAutonomousGeometry.overlaps(it.building.bounds, frame.core) } shouldBe true
                drafts.any { !CivsAutonomousGeometry.overlaps(it.building.bounds, frame.core) } shouldBe true
            }
            (s.drafts.size <= CivsAutonomousPlacement.MAXIMUM_OPTIONS) shouldBe true
            s.accessLoads shouldBe 1
            verify(exactly = 1) { s.world.autonomousHeightField(any(), any(), any()) }
        }
    }

    for (reason in listOf("autonomy-no-path", "autonomy-search-budget", "chunks-unloaded"))
    test("a preferred family's $reason candidates cannot starve a reachable alternate") {
        InfrastructurePlacementScenario(56).use { s ->
            val preferred = "civs/v2/fixture/crusher/preferred/l1"
            val compact = "civs/v2/fixture/crusher/compact/l1"
            val id = s.familyCatalog(linkedMapOf(preferred to 0, compact to 0))
            val current = familyPlacementBlueprint(preferred, id, s.city.worldId, CivPosition(0, 0, 0), 0, 1, 0).building
            val future = familyPlacementBlueprint(preferred, id, s.city.worldId, CivPosition(0, 0, 0), 0, 3, 0).building
            (CivsAutonomousTerraform.options(s.city, CivsSettlementLayout.candidates(s.city, current, future), s.surface).size >= 64) shouldBe true
            every { s.world.captureTerraform(any(), any(), any(), any()) } answers {
                val draft = arg<CivTerraformDraft>(1).also(s.drafts::add)
                CompletableFuture.failedFuture(CivsRejection(if (draft.building.schematicId == preferred) reason
                    else "test-selected-infrastructure-draft"))
            }
            spatialFailure(s.planner.search(s.city, BuildingType.CRUSHER, 1, id)) shouldBe "test-selected-infrastructure-draft"
            s.drafts.last().building.schematicId shouldBe compact
            @Suppress("UNCHECKED_CAST")
            val terminal = (s.planner.debug(s.city.id)["last"] as List<Map<String, Any?>>).single()
            terminal["families_tried"] shouldBe listOf(preferred, compact)
            (terminal["attempted"] as Int in 33..64) shouldBe true
            (terminal["infill_candidates"] as Int > 0) shouldBe true
            s.accessLoads shouldBe 1
            verify(exactly = 1) { s.world.autonomousHeightField(any(), any(), any()) }
        }
    }

    for (infillCount in listOf(0, 1))
    test("a single family's $infillCount infill candidates leave its later frontage available") {
        InfrastructurePlacementScenario(56).use { s ->
            val entry = "civs/v2/fixture/crusher/preferred/l1"
            val id = s.familyCatalog(linkedMapOf(entry to 0))
            val current = familyPlacementBlueprint(entry, id, s.city.worldId, CivPosition(0, 0, 0), 0, 1, 0).building
            val future = familyPlacementBlueprint(entry, id, s.city.worldId, CivPosition(0, 0, 0), 0, 3, 0).building
            val frontage = CivsSettlementLayout.candidates(s.city, current, future)
            val options = CivsAutonomousTerraform.options(s.city, frontage, s.surface)
            (options.size >= 64) shouldBe true
            val target = options.drop(40).take(16).firstNotNullOfOrNull { option ->
                runCatching { CivsAutonomousTerraform.draft(s.city, option, s.surface, s.access,
                    CivsSettlementLayout.frame(s.city).wallObstacles).building }.getOrNull()
            } ?: error("Fixture needs a reachable late frontage")
            val seen = frontage.mapTo(hashSetOf()) { it.building.anchor to it.building.rotation }
            val infill = CivsSettlementLayout.infill(s.city, current, future, eligible = {
                (it.anchor to it.rotation) !in seen }).take(infillCount)
            infill.size shouldBe infillCount
            every { s.world.captureTerraform(any(), any(), any(), any()) } answers {
                val draft = arg<CivTerraformDraft>(1).also(s.drafts::add)
                CompletableFuture.failedFuture(CivsRejection(if (draft.building == target)
                    "test-selected-infrastructure-draft" else "lands-denied"))
            }
            mockkObject(CivsSettlementLayout)
            try {
                every { CivsSettlementLayout.infill(any(), any(), any(), any(), any(), any()) } returns infill
                spatialFailure(s.planner.search(s.city, BuildingType.CRUSHER, 1, id)) shouldBe "test-selected-infrastructure-draft"
                s.drafts.last().building shouldBe target
                @Suppress("UNCHECKED_CAST")
                val terminal = (s.planner.debug(s.city.id)["last"] as List<Map<String, Any?>>).single()
                terminal["candidate_source"] shouldBe "frontage"
                (terminal["attempted"] as Int in 41..64) shouldBe true
            } finally { unmockkObject(CivsSettlementLayout) }
        }
    }

    test("a direct surface failure does not consume an alternate family") {
        InfrastructurePlacementScenario().use { s ->
            val preferred = "civs/v2/fixture/crusher/preferred/l1"
            val compact = "civs/v2/fixture/crusher/compact/l1"
            val id = s.familyCatalog(linkedMapOf(preferred to 0, compact to 0))
            every { s.world.autonomousHeightField(any(), any(), any(), any()) } returns
                CompletableFuture.failedFuture(CivsRejection("chunks-unloaded"))
            spatialFailure(s.planner.search(s.city, BuildingType.CRUSHER, 1, id)) shouldBe "chunks-unloaded"
            s.drafts shouldBe emptyList()
            verify(exactly = 0) { s.blueprints.prepareEntry(compact, any(), any(), any(), any(), any()) }
        }
    }

    test("an explicit catalog entry retains its exact family through every native refusal") {
        InfrastructurePlacementScenario().use { s ->
            val preferred = "civs/v2/fixture/decoration/preferred/l1"
            val compact = "civs/v2/fixture/decoration/compact/l1"
            val id = s.familyCatalog(linkedMapOf(preferred to 0, compact to 0))
            every { s.blueprints.maximumLevel(any()) } returns 1
            every { s.blueprints.autonomousAlternatives(any(), BuildingType.DECORATION) } returns listOf(compact)
            every { s.blueprints.prepareEntry(any(), any(), any(), any(), any(), any()) } answers {
                val blueprint = familyPlacementBlueprint(firstArg(), arg(1), arg(2), arg(3), arg<Int?>(5) ?: 0, 1, 0)
                CompletableFuture.completedFuture(blueprint.copy(building = blueprint.building.copy(type = BuildingType.DECORATION)))
            }
            every { s.world.captureTerraform(any(), any(), any(), any()) } answers {
                s.drafts += arg<CivTerraformDraft>(1)
                CompletableFuture.failedFuture(CivsRejection("lands-denied"))
            }
            spatialFailure(s.planner.decoration(s.city, preferred, id)) shouldBe "autonomy-no-site"
            s.drafts.isNotEmpty() shouldBe true
            s.drafts.all { it.building.schematicId == preferred } shouldBe true
            verify(exactly = 0) { s.blueprints.autonomousAlternatives(any(), any()) }
            verify(exactly = 0) { s.blueprints.prepareEntry(compact, any(), any(), any(), any(), any()) }
        }
    }

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

    for ((refusal, expected) in listOf("lands-denied" to "autonomy-no-site", "autonomy-search-budget" to "autonomy-search-budget"))
    test("all families share one sixty four draft and native attempt budget after $refusal") {
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
                CompletableFuture.failedFuture(CivsRejection(refusal))
            }
            val result = s.planner.search(s.city, BuildingType.CRUSHER, 1, id)
            spatialFailure(result) shouldBe expected
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

    test("an incomplete perimeter never drafts isolated gates or walls ahead of ordinary paving") {
        InfrastructurePlacementScenario().use { s ->
            val route = (0..10).map { CivPosition(0, 65, it) }
            val frame = CivsSettlementLayout.frame(s.city)
            val gate = frame.gates.first()
            val blocked = civsTerrainKey(gate.center.x, gate.center.z)
            s.surface = s.surface.copy(columns = s.surface.columns.mapValues { (key, column) ->
                if (key == blocked) column.copy(natural = false) else column
            })
            val incomplete = CivsSettlementLayout.infrastructure(s.city, frame, s.surface, listOf(route))
            incomplete.perimeterClosed shouldBe false
            incomplete.pieces.any { it.type == BuildingType.WALL || it.type == BuildingType.GATE } shouldBe true
            incomplete.pieces.any { it.type == BuildingType.ROAD } shouldBe true
            val plan = CivSettlementPlan(CivsSettlementPlans.VERSION, 0, frame.halfWidth, emptyList(),
                mapOf(s.city.buildings.first().id.toString() to route), frame.wallObstacles,
                frame.gates.map { it.passage }, frame.gates.map { it.side })
            s.city = s.city.copy(autonomy = requireNotNull(s.city.autonomy).copy(plan = plan))
            CivsSettlementPlans.current(s.city) shouldBe plan

            spatialFailure(s.planner.infrastructure(s.city)) shouldBe "test-selected-infrastructure-draft"

            s.drafts.single().building.type shouldBe BuildingType.ROAD
        }
    }

    for (whole in listOf(false, true))
    test("a recovered ring uses its saved approaches only with every remaining piece proven, whole=$whole") {
        InfrastructurePlacementScenario().use { s ->
            val frame = CivsSettlementLayout.frame(s.city)
            val layout = CivsSettlementLayout.infrastructure(s.city, frame, s.surface, emptyList())
            layout.perimeterClosed shouldBe true
            val fortifications = layout.pieces.filter { it.type == BuildingType.GATE || it.type == BuildingType.WALL }
            val first = fortifications.filter { it.type == BuildingType.GATE }
                .minWith(compareBy<CivSettlementPiece> { it.bounds.min.x }.thenBy { it.bounds.min.z })
            val floors = first.blocks.groupBy { civsTerrainKey(it.position.x, it.position.z) }
                .mapValues { (_, cells) -> cells.minOf { it.position.y } }
            val building = CivsInfrastructure.building(first)
            val alternative = buildList {
                for (x in first.bounds.min.x..first.bounds.max.x) {
                    add(CivPosition(x, 65, first.bounds.min.z - 1))
                    add(CivPosition(x, 65, first.bounds.max.z + 1))
                }
                for (z in first.bounds.min.z..first.bounds.max.z) {
                    add(CivPosition(first.bounds.min.x - 1, 65, z))
                    add(CivPosition(first.bounds.max.x + 1, 65, z))
                }
            }.first { entrance -> entrance != first.entrance && runCatching {
                CivsAutonomousTerraform.draft(s.city, CivTerraformOption(building.copy(entrance = entrance), 0),
                    s.surface, s.access, frame.wallObstacles, floors)
            }.isSuccess }
            val witnesses = fortifications.associate { it.id.toString() to it.entrance } + (first.id.toString() to alternative)
            val plan = CivSettlementPlan(CivsSettlementPlans.VERSION, 1, frame.halfWidth, emptyList(), emptyMap(),
                frame.wallObstacles, frame.gates.map { it.passage }, frame.gates.map { it.side },
                perimeterEntrances = if (whole) witnesses else witnesses - fortifications.last().id.toString())
            s.city = s.city.copy(autonomy = requireNotNull(s.city.autonomy).copy(plan = plan))
            val result = spatialFailure(s.planner.infrastructure(s.city))
            if (whole) {
                result shouldBe "test-selected-infrastructure-draft"
                val draft = s.drafts.single()
                draft.building.id shouldBe first.id
                draft.building.entrance shouldBe alternative
                draft.route.last() shouldBe alternative
            } else {
                result shouldBe "autonomy-no-site"
                s.drafts shouldBe emptyList()
            }
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

    test("search exhausts unsafe frontage then captures a connected native infill without rewriting technical blocks") {
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
                s.world.getBlockAt(point.x, point.y, point.z).type = Material.BARRIER
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
                s.world.getBlockAt(point.x, point.y, point.z).type shouldBe Material.BARRIER
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
            stale.join().blocks.single { it.position == added }.before shouldBe addedBlock.blockData.asString
            oldTerrain.blocks.any { it.position == added } shouldBe false

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

    for (skipHeadroom in listOf(true, false))
    test("activating a road SITE keeps its paid spine grade with skipped headroom=$skipHeadroom") {
        spatialScenario { s ->
            val paid = (0..16).map { CivPosition(0, 65, it) }
            val forester = s.city.buildings.single().copy(id = UUID.randomUUID(), type = BuildingType.FORESTER,
                anchor = CivPosition(0, 64, 16), entrance = paid.last(), rotation = 90,
                bounds = CivBox(CivPosition(1, 64, 13), CivPosition(5, 70, 19)), control = CivPosition(2, 65, 16))
            val piece = CivSettlementPiece(UUID.randomUUID(), BuildingType.ROAD,
                CivBox(CivPosition(-1, 64, 8), CivPosition(1, 66, 12)), CivPosition(-2, 65, 10),
                buildList {
                    for (x in -1..1) for (z in 8..12) for (y in 64..66) add(CivSettlementBlock(CivPosition(x, y, z),
                        if (x == 0 && (y == 64 || skipHeadroom)) "minecraft:barrier"
                        else if (y == 64) "minecraft:cobblestone" else "minecraft:air"))
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
            design.blueprint.cells.filter { it.position.x == 0 && it.position.y == 64 }.all { it.isBarrier } shouldBe true
            val terrain = requireNotNull(design.terrain)
            terrain.blocks.none { it.position.x == 0 && it.position.z in 8..12 } shouldBe true
            val capture = s.worldAccess.capture(s.city.id, s.city.owner, s.city.worldId,
                design.blueprint.building, design.blueprint.cells, site, terrain)
            s.finish(capture)
            capture.join().blocks.none { it.position.x == 0 && it.position.y == 64 && it.position.z in 8..12 } shouldBe true
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
                mapOf(site.id.toString() to route), frame.wallObstacles, frame.gates.map { it.passage }, frame.gates.map { it.side })
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
                mapOf(site.id.toString() to route), frame.wallObstacles, frame.gates.map { it.passage }, frame.gates.map { it.side })
            s.city = s.city.copy(autonomy = CivAutonomy(plan = intent),
                buildings = s.city.buildings + occupied + site, constructionQueue = listOf(site.id))
            requireNotNull(CivsSettlementPlans.current(s.city))
            val barriers = if (!blocked) emptyList() else buildList {
                for (x in -3..3) for (z in -3..3) if (kotlin.math.abs(x) == 3 || kotlin.math.abs(z) == 3)
                    for (y in 65..67) add(CivPosition(x, y, z))
            }
            barriers.forEach { s.world.getBlockAt(it.x, it.y, it.z).type = Material.BARRIER }
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
            barriers.forEach { s.world.getBlockAt(it.x, it.y, it.z).type shouldBe Material.BARRIER }
        }
    }

    test("infrastructure selection ignores obsolete routes and reserved plots") {
        InfrastructurePlacementScenario().use { s ->
            val frame = CivsSettlementLayout.frame(s.city)
            val bad = CivSettlementPlan(99, 0, frame.halfWidth, emptyList(),
                mapOf(UUID.randomUUID().toString() to listOf(CivPosition(9999, 65, 9999))),
                frame.wallObstacles, frame.gates.map { it.passage }, frame.gates.map { it.side })
            s.city = s.city.copy(autonomy = s.city.autonomy!!.copy(plan = bad))
            spatialFailure(s.planner.infrastructure(s.city)) shouldBe "test-selected-infrastructure-draft"
            s.drafts.single().building.type shouldBe BuildingType.GATE
        }
    }

    test("native terrain rejects water steep ground and technical barriers while admitting an ordinary wall") {
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
            occupied.join() shouldBe Unit
            s.world.getBlockAt(p.x, 65, p.z).type shouldBe Material.OAK_PLANKS
            s.world.getBlockAt(p.x, 65, p.z).type = Material.BARRIER
            val forbidden = s.worldAccess.validateAutonomousSite(s.city, b)
            s.finish(forbidden)
            spatialFailure(forbidden) shouldBe "autonomy-terrain"
            s.world.getBlockAt(p.x, 65, p.z).type shouldBe Material.BARRIER
        }
    }

    test("a late filled barrel is refused by the final automatic capture without losing its items") {
        spatialScenario { s ->
            val blueprint = spatialBlueprint(UUID.randomUUID(), s.city.worldId, CivPosition(18, 64, 10), 0, 1)
            val b = blueprint.building
            val checked = s.worldAccess.validateAutonomousSite(s.city, b)
            s.finish(checked)
            checked.join() shouldBe Unit
            val p = b.bounds.min.copy(y = 65)
            (p == b.control) shouldBe false
            val block = s.world.getBlockAt(p.x, p.y, p.z).also { it.type = Material.BARREL }
            (block.state as Barrel).inventory.setItem(0, ItemStack(Material.DIAMOND, 1))
            val captured = s.worldAccess.captureAutonomousSite(s.city.id, s.city.owner, s.city.worldId, b, blueprint.cells)
            s.finish(captured)
            spatialFailure(captured) shouldBe "autonomy-terrain"
            block.type shouldBe Material.BARREL
            (block.state as Barrel).inventory.getItem(0) shouldBe ItemStack(Material.DIAMOND, 1)
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

    test("autonomous upgrade preserves its own walls but refuses another building overlap and a late filled cell") {
        spatialScenario { s ->
            val upgrade = s.ownedUpgrade()
            val old = s.city.buildings.single()
            val cells = spatialCells(upgrade)
            val accepted = s.worldAccess.captureSite(s.city.id, s.city.owner, s.city.worldId, upgrade, cells, old)
            s.finish(accepted)
            accepted.isCompletedExceptionally shouldBe false

            val outside = s.world.getBlockAt(upgrade.bounds.max.x, 65, -3)
            outside.type = Material.BRICKS
            val authorizedCity = s.city
            val neighbour = spatialBlueprint(UUID.randomUUID(), s.city.worldId, CivPosition(6, 64, 1), 0, 1)
                .building.copy(phase = BuildingPhase.ACTIVE)
            old.bounds.overlaps(neighbour.bounds) shouldBe false
            upgrade.bounds.overlaps(neighbour.bounds) shouldBe true
            s.city = s.city.copy(buildings = s.city.buildings + neighbour)
            val guardedCity = s.city
            val obstructed = s.worldAccess.captureSite(s.city.id, s.city.owner, s.city.worldId, upgrade, cells, old)
            s.finish(obstructed)
            spatialFailure(obstructed) shouldBe "building-overlap"
            outside.type shouldBe Material.BRICKS
            s.city shouldBe guardedCity
            s.city = authorizedCity
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

    test("a reserved fortification rejects a changed source before requesting native terrain") {
        InfrastructurePlacementScenario().use { s ->
            val template = civsSyntheticFortification()
            val plot = spatialFortificationPlot(template)
            s.city = s.city.copy(style = CivsFortifications.STYLE)
            every { s.blueprints.fortification(s.city.style) } returns CompletableFuture.completedFuture(template)
            val changed = plot.copy(fortification = requireNotNull(plot.fortification).copy(sourceSha256 = "0".repeat(64)))
            spatialFailure(s.planner.reserved(s.city, changed)) shouldBe "autonomy-catalog-changed"
            verify(exactly = 0) { s.world.autonomousHeightField(any(), any(), any(), any()) }
            verify(exactly = 0) { s.blueprints.prepareFortification(any(), any(), any(), any(), any()) }
        }
    }

    test("a verified fortification reservation uses the composite preparation after guarded terrain and route capture") {
        InfrastructurePlacementScenario().use { s ->
            val template = civsSyntheticFortification()
            val plot = spatialFortificationPlot(template)
            s.city = s.city.copy(style = CivsFortifications.STYLE, autonomy = CivAutonomy(layoutHalfWidth = 56))
            val frame = CivsSettlementLayout.frame(s.city)
            val plan = CivSettlementPlan(CivsSettlementPlans.VERSION, 0, 56,
                listOf(CivSettlementPlot(s.city.buildings.single(), s.city.buildings.single().bounds), plot),
                mapOf(plot.building.id.toString() to (0..20).map { CivPosition(it, 65, 0) }),
                frame.wallObstacles, frame.gates.map { it.passage }, frame.gates.map { it.side })
            CivsSettlementPlans.validate(s.city, plan)
            s.city = s.city.copy(autonomy = s.city.autonomy!!.copy(plan = plan))
            every { s.blueprints.fortification(s.city.style) } returns CompletableFuture.completedFuture(template)
            every { s.world.captureTerraform(any(), any(), any(), any()) } answers {
                val draft = arg<CivTerraformDraft>(1)
                CompletableFuture.completedFuture(CivTerraformPlan(draft.building.bounds, emptyList(), draft.route, draft.groundY, 0, 0))
            }
            every { s.world.autonomousRoute(any(), any(), any(), any(), any(), any()) } returns CompletableFuture.completedFuture(Unit)
            val placed = plot.building.copy(phase = BuildingPhase.CONSTRUCTING)
            val blueprint = CivBlueprint(placed, s.city.worldId, mockk(), emptyList(), emptyList(), template.sourceBytes)
            every { s.blueprints.prepareFortification(template, placed.id, s.city.worldId, placed.anchor, placed.rotation) } returns
                CompletableFuture.completedFuture(blueprint)
            val result = s.planner.reserved(s.city, plot).join()
            result.blueprint shouldBe blueprint
            result.terrain?.route?.last() shouldBe placed.entrance
            verify(exactly = 1) { s.blueprints.prepareFortification(template, placed.id, s.city.worldId, placed.anchor, placed.rotation) }
            verify(exactly = 0) { s.blueprints.prepareEntry(any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { s.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
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

private fun spatialFortificationPlot(template: CivFortificationTemplate): CivSettlementPlot {
    val anchor = CivPosition(30, 64, 0)
    val building = CivsSettlementPlans.at(template.building, anchor, 0, UUID(141, 1)).copy(phase = BuildingPhase.SITE)
    return CivSettlementPlot(building, building.bounds, CivSettlementFortification(
        template.sourceSha256, template.recipeSha256, template.proofAt(anchor, 0)))
}

/** Settled side streets leave a narrow central parcel. The accepted roads and yards are immovable. */
private fun civicFamilyTown(): Pair<Settlement, CivAutonomousAccess> {
    val base = settlementPlanCity().copy(radius = 56, style = "medievaloak", autonomy = CivAutonomy(layoutHalfWidth = 32),
        inventory = CivInventory(CivStorageMode.PHYSICAL),
        research = CivResearchState(setOf(CivResearchId.MATERIALS, CivResearchId.METALLURGY)))
    fun parcel(type: BuildingType, index: Int, x: Int, z: Int, entrance: CivPosition = CivPosition(x + 1, 65, z + 4)) =
        CivBuilding(UUID(146, index.toLong()), type, minOf(2, type.maxLevel), CivPosition(x, 64, z), 0,
            "civs/v2/fixture/settled-${type.name.lowercase()}/00000000000000000000/level${minOf(2, type.maxLevel)}",
            CivBox(CivPosition(x, 64, z), CivPosition(x + 3, 68, z + 3)), entrance, BuildingPhase.ACTIVE)
    val needed = CivsAutonomy.profile.filter { it !in setOf(BuildingType.TOWN_HALL, BuildingType.WAREHOUSE, BuildingType.BLACKSMITH) } +
        listOf(BuildingType.HOUSE, BuildingType.HOUSE)
    val sides = listOf(-28, -21, -14, -7, 7, 15, 23).flatMap { z -> listOf(-24 to z, 21 to z) }
    val ordinary = needed.zip(sides).mapIndexed { index, (type, at) ->
        val x = if (type in CivsSettlementLayout.exteriorTypes) (if (at.first < 0) -44 else 41) else at.first
        parcel(type, index + 1, x, at.second)
    }
    val hall = base.buildings.single().copy(level = 2,
        schematicId = "civs/v2/fixture/settled-hall/00000000000000000000/level2")
    val warehouse = parcel(BuildingType.WAREHOUSE, 50, 21, -2, CivPosition(20, 65, 0)).copy(rotation = 90)
    val roads = listOf(-14, 14).flatMap { x ->
        (-32..32).toList().chunked(23).map { columns ->
            val floor = columns.map { z -> CivPosition(x, 64, z) }
            val feet = floor.map { it.copy(y = it.y + 1) }
            CivSettlementPiece(UUID(146, 200L + 5 * x + columns.first()), BuildingType.ROAD,
                CivBox(floor.first(), feet.last()), feet.first(), floor.map { CivSettlementBlock(it, "minecraft:dirt_path") },
                transit = feet)
        }
    }
    val retainedRoute = (0..warehouse.entrance.x).map { x -> CivPosition(x, 65, 0) }
    val access = CivAutonomousAccess(routes = mapOf(warehouse.id to retainedRoute), infrastructure = roads)
    val buildings = listOf(hall, warehouse) + ordinary
    val frame = CivsSettlementLayout.frame(base)
    val plan = CivSettlementPlan(CivsSettlementPlans.VERSION, 1, frame.halfWidth,
        buildings.map { CivSettlementPlot(it.copy(phase = BuildingPhase.SITE), it.bounds) },
        access.routes.mapKeys { it.key.toString() }, frame.wallObstacles, frame.gates.map { it.passage },
        frame.gates.map { it.side })
    return base.copy(buildings = buildings + roads.map { CivsInfrastructure.building(it).copy(phase = BuildingPhase.ACTIVE) },
        autonomy = requireNotNull(base.autonomy).copy(plan = plan)) to access
}

/** Real catalog and terraform draft; stop at the native boundary so selection has no world side effects. */
private class InfrastructurePlacementScenario(halfWidth: Int = 24) : AutoCloseable {
    var city = spatialCity(UUID.randomUUID()).copy(autonomy = CivAutonomy(layoutHalfWidth = halfWidth))
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

private fun InfrastructurePlacementScenario.familyCatalog(families: Map<String, Int>, type: BuildingType = BuildingType.CRUSHER): UUID {
    val id = UUID.randomUUID()
    every { blueprints.autonomousSource(any(), type) } returns families.keys.first()
    every { blueprints.autonomousAlternatives(any(), type) } returns families.keys.drop(1)
    every { blueprints.maximumLevel(any()) } returns 3
    every { blueprints.prepareEntry(any(), any(), any(), any(), any(), any()) } answers {
        val entry = firstArg<String>()
        val blueprint = familyPlacementBlueprint(entry, arg(1), arg(2), arg(3), arg<Int?>(5) ?: 0,
            1, families.getValue(entry))
        CompletableFuture.completedFuture(blueprint.copy(building = blueprint.building.copy(type = type)))
    }
    every { blueprints.prepareUpgrade(any(), any(), any()) } answers {
        val building = firstArg<CivBuilding>()
        val blueprint = familyPlacementBlueprint(building.schematicId, building.id, arg(2),
            building.anchor, building.rotation, arg(1), families.getValue(building.schematicId))
        CompletableFuture.completedFuture(blueprint.copy(building = blueprint.building.copy(type = type)))
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
