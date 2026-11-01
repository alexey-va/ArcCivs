package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.block.Barrel
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
import java.util.concurrent.Executor

class CivsInitialUnpaidPlanRecoveryTest : FunSpec({
    test("a protected old hut body recovers on its raised terrace with the other unpaid outline intact") {
        initialRecoveryScenario(v2 = true, preferOldTerrace = true) { s ->
            s.oldHut.bounds.contains(s.ore.x, s.ore.y, s.ore.z) shouldBe true
            s.start(); s.finish { s.saves.size == 1 && s.subject.debug(s.city.id)["request"] == null }
            val plan = requireNotNull(s.city.autonomy?.plan)
            val selected = requireNotNull(s.selected)
            val draft = s.preferredDrafts.single()
            s.reservedCaptures shouldBe 1
            s.nativeOreRejections shouldBe 1
            s.unreservedDrafts shouldBe emptyList()
            selected.id shouldBe s.oldHut.id
            selected.anchor shouldBe s.oldHut.anchor.copy(y = s.oldHut.anchor.y + 4)
            selected.rotation shouldBe s.oldHut.rotation
            selected.schematicId shouldBe s.oldHut.schematicId
            selected.schematicId shouldBe s.defaultFamily
            selected.bounds.contains(s.ore.x, s.ore.y, s.ore.z) shouldBe false
            plan.plots.filter { it.building.id != s.oldHut.id } shouldBe s.oldPlan.plots.filter { it.building.id != s.oldHut.id }
            plan.routes - s.oldHut.id.toString() shouldBe s.oldPlan.routes - s.oldHut.id.toString()
            plan.walls shouldBe s.oldPlan.walls
            plan.gates shouldBe s.oldPlan.gates
            plan.gateSides shouldBe s.oldPlan.gateSides
            val plot = plan.plots.single { it.building.id == s.oldHut.id }
            plot.building shouldBe selected.copy(phase = BuildingPhase.SITE)
            plot.envelope.min.y shouldBe s.oldPlan.plots.single { it.building.id == s.oldHut.id }.envelope.min.y + 4
            plan.routes[s.oldHut.id.toString()] shouldBe draft.route
            draft.route.first() shouldBe s.before.buildings.single().entrance
            draft.route.last() shouldBe selected.entrance
            draft.access shouldBe CivAutonomousAccess()
            draft.columns.filter { it.preserve }.map { CivPosition(it.x, it.groundY + 1, it.z) }
                .toSet() shouldBe setOf(s.before.buildings.single().entrance)
            val unpaidRoads = (s.oldPlan.routes - s.oldHut.id.toString()).values.flatten()
                .mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) } - civsTerrainKey(draft.route.first().x, draft.route.first().z)
            draft.columns.any { civsTerrainKey(it.x, it.z) in unpaidRoads } shouldBe true
            draft.columns.filter { civsTerrainKey(it.x, it.z) in unpaidRoads }.all { !it.preserve } shouldBe true
            s.volumeCaptures shouldBe listOf(selected)
            s.forecasts shouldBe 1
            plan.plots.size shouldBe 34
            plan.routes.size shouldBe 33
            CivsVillageSiteScoring.completeProfile(plan) shouldBe true
            plan.revision shouldBe s.oldPlan.revision + 1
            s.city.copy(revision = s.before.revision, autonomy = s.before.autonomy) shouldBe s.before
            s.constructionAdmissions shouldBe 0
            s.reserveItems() shouldBe s.beforeItems
            s.time += 5_001; s.subject.pulse(); s.scheduler.tick()
            s.saves.size shouldBe 1
            s.forecasts shouldBe 1
            s.reserveItems() shouldBe s.beforeItems
        }
    }

    test("old slot terraces consume the same sixty four candidate quota as ordinary fallback") {
        initialRecoveryScenario { s ->
            s.failure = "native-quota"
            s.start(); s.finish { s.subject.debug(s.city.id)["request"] == null }
            s.searchDebug()["attempted"] shouldBe 64
            s.searchDebug()["preferred_terraces_attempted"] shouldBe 3
            (s.preferredDrafts.size + s.unreservedDrafts.size <= 64) shouldBe true
            s.preferredDrafts.isNotEmpty() shouldBe true
            s.unreservedDrafts.isNotEmpty() shouldBe true
            s.volumeCaptures shouldBe emptyList()
            s.forecasts shouldBe 0
            s.saves shouldBe emptyList()
            s.city shouldBe s.before
            s.reserveItems() shouldBe s.beforeItems
        }
    }

    test("a native blocked unpaid hut selects an ordinary alternative and adopts only its complete forecast") {
        initialRecoveryScenario { s ->
            s.start()
            s.finish { s.saves.size == 1 }
            val recovered = s.city
            val plan = requireNotNull(recovered.autonomy?.plan)
            val selected = requireNotNull(s.selected)
            s.reservedCaptures shouldBe 1
            s.unreservedDrafts.size shouldBe 3
            s.unreservedDrafts.first().building shouldBe s.rejectedAlternative
            s.unreservedDrafts.last().building shouldBe s.selectedDraft
            s.volumeCaptures.size shouldBe 2
            s.volumeCaptures.first() shouldBe s.rejectedPayload
            s.volumeCaptures.last() shouldBe selected
            s.forecasts shouldBe 1
            plan.plots.size shouldBe 34
            plan.routes.size shouldBe 33
            CivsVillageSiteScoring.completeProfile(plan) shouldBe true
            plan.revision shouldBe s.oldPlan.revision + 1
            plan.halfWidth shouldBe s.oldPlan.halfWidth
            plan.plots.single { it.building.id == s.oldHut.id }.building shouldBe selected.copy(phase = BuildingPhase.SITE)
            plan.routes[s.oldHut.id.toString()] shouldBe s.selectedRoute
            ((selected.anchor to selected.rotation) == (s.oldHut.anchor to s.oldHut.rotation)) shouldBe false
            recovered.copy(revision = s.before.revision, autonomy = s.before.autonomy) shouldBe s.before
            s.constructionAdmissions shouldBe 0
            s.reserveItems() shouldBe s.beforeItems

            // The replacement is an ordinary reservation now. Repeated pulses cannot adopt it a second time.
            s.time += 5_001
            s.subject.pulse(); s.scheduler.tick()
            s.saves.size shouldBe 1
            s.forecasts shouldBe 1
            s.city shouldBe recovered
            s.reserveItems() shouldBe s.beforeItems
        }
    }

    test("worker immigration hunger and settings changes survive an asynchronous forecast") {
        initialRecoveryScenario { s ->
            s.holdForecast = true
            s.start(); s.finish { s.forecasts == 1 }
            val current = s.city.copy(revision = s.city.revision + 1,
                workers = s.city.workers.map { it.copy(hunger = 17) } + CivWorker(UUID(164, 9), "Arrived", null),
                settings = requireNotNull(s.city.settings).copy(chunkLoading = false))
            s.city = current
            s.forecast.complete(s.surface); s.finish { s.saves.size == 1 }
            s.city.copy(revision = current.revision, autonomy = current.autonomy) shouldBe current
            s.city.workers shouldBe current.workers
            s.city.settings shouldBe current.settings
            s.city.buildings shouldBe s.before.buildings
            s.reserveItems() shouldBe s.beforeItems
        }
    }

    test("ordinary admission recaptures the exact adopted v2 family instead of the catalog default") {
        initialRecoveryScenario(v2 = true) { s ->
            s.start(); s.finish { s.saves.size == 1 && s.subject.debug(s.city.id)["request"] == null }
            val adopted = requireNotNull(s.city.autonomy?.plan).plots.single { it.building.id == s.oldHut.id }.building
            adopted.schematicId shouldBe s.alternateFamily
            s.oldHut.schematicId shouldBe s.defaultFamily
            s.volumeCaptures.last().schematicId shouldBe s.alternateFamily
            s.time += 5_001
            s.subject.pulse(); s.scheduler.tick()
            s.finish { s.constructionAdmissions == 1 }
            s.volumeCaptures.size shouldBe 3
            s.volumeCaptures.last().schematicId shouldBe s.alternateFamily
            s.volumeCells.last().size.toLong() shouldBe civsBoxVolume(adopted.bounds)
            s.volumeCells.last().first().blockData shouldBe "minecraft:oak_planks"
            s.forecasts shouldBe 1
            s.saves.size shouldBe 1
            s.reserveItems() shouldBe s.beforeItems
        }
    }

    test("a legacy seventy two block plan with no width hint retains its adopted width") {
        initialRecoveryScenario(halfWidth = 72, legacyWidth = true) { s ->
            s.before.autonomy?.layoutHalfWidth shouldBe null
            s.oldPlan.halfWidth shouldBe 72
            s.start(); s.finish { s.saves.size == 1 }
            s.city.autonomy?.plan?.halfWidth shouldBe 72
            s.city.autonomy?.layoutHalfWidth shouldBe null
            s.city.copy(revision = s.before.revision, autonomy = s.before.autonomy) shouldBe s.before
            s.forecasts shouldBe 1
            s.reserveItems() shouldBe s.beforeItems
        }
    }

    for (failure in listOf("no-choice", "payload-only", "no-full-forecast"))
    test("failed $failure recovery keeps the original unpaid plan and every item") {
        initialRecoveryScenario { s ->
            s.failure = failure
            s.start(); s.finish { s.subject.debug(s.city.id)["request"] == null }
            s.saves shouldBe emptyList()
            s.city shouldBe s.before
            s.unreservedSurfaces shouldBe 1
            s.forecasts shouldBe if (failure == "no-full-forecast") 1 else 0
            (s.unreservedDrafts.size <= CivsAutonomousPlacement.MAXIMUM_OPTIONS) shouldBe true
            if (failure == "payload-only") s.volumeCaptures.isNotEmpty() shouldBe true
            s.reserveItems() shouldBe s.beforeItems
            s.time += 30_001; s.subject.pulse(); s.scheduler.tick()
            // Ordinary retries remain guarded; the existing five-minute plan backoff prevents another full recovery.
            s.forecasts shouldBe if (failure == "no-full-forecast") 1 else 0
            s.saves shouldBe emptyList()
        }
    }

    for (change in listOf("hall", "plan", "building", "construction", "queue", "control", "deletion", "pause", "shutdown"))
    test("a $change change while the forecast is pending prevents adoption") {
        initialRecoveryScenario { s ->
            s.holdForecast = true
            s.start(); s.finish { s.forecasts == 1 }
            val current = when (change) {
                "hall" -> s.city.copy(buildings = s.city.buildings.map { hall -> hall.copy(
                    anchor = hall.anchor.copy(x = hall.anchor.x + 1),
                    bounds = CivBox(hall.bounds.min.copy(x = hall.bounds.min.x + 1),
                        hall.bounds.max.copy(x = hall.bounds.max.x + 1)),
                    entrance = hall.entrance.copy(x = hall.entrance.x + 1),
                    control = hall.control?.let { it.copy(x = it.x + 1) }) })
                "plan" -> s.city.copy(autonomy = s.city.autonomy!!.copy(plan = s.oldPlan.copy(revision = 9)))
                "building" -> s.city.copy(buildings = s.city.buildings + s.oldHut)
                "construction" -> s.city.buildings.single().copy(phase = BuildingPhase.CONSTRUCTING).let { hall ->
                    s.city.copy(buildings = listOf(hall), construction = CivConstruction(UUID(164, 10), hall.id, 1,
                        10, 0, ConstructionPhase.CONSTRUCTING, Stock(), plannedBuilding = hall,
                        batches = CivConstructionBatches(10, 16, true)))
                }
                "queue" -> s.city.copy(constructionQueue = listOf(s.city.buildings.single().id))
                "control" -> s.city.copy(pendingControls = listOf(CivControlPlacement(s.city.buildings.single().id,
                    requireNotNull(s.city.buildings.single().control), "minecraft:air")))
                "deletion" -> s.city.copy(pendingDeletion = CivDeletion(null))
                "pause" -> s.city.copy(autonomy = s.city.autonomy!!.copy(paused = true))
                else -> s.city.also { s.closed = true; s.subject.cancel() }
            }
            s.city = current
            s.forecast.complete(s.surface)
            repeat(10) { s.scheduler.tick() }
            s.saves shouldBe emptyList()
            s.city shouldBe current
            s.constructionAdmissions shouldBe 0
            s.reserveItems() shouldBe s.beforeItems
        }
    }

    test("a nonnative reserved terrain failure never invalidates unpaid intent") {
        initialRecoveryScenario { s ->
            s.failure = "surface-only"
            s.start(); s.finish { s.subject.debug(s.city.id)["request"] == null }
            s.unreservedDrafts shouldBe emptyList()
            s.forecasts shouldBe 0
            s.saves shouldBe emptyList()
            s.city shouldBe s.before
        }
    }
})

/** Real development/placement/terraform/full34 planner, with the native read boundary controlled explicitly. */
private class InitialRecoveryScenario(val paper: MockBukkitTestRuntime, private val v2: Boolean,
    halfWidth: Int, legacyWidth: Boolean, private val preferOldTerrace: Boolean) : AutoCloseable {
    val scheduler = TestTaskScheduler()
    private val tasks = LifecycleTaskScope(scheduler, true)
    private val plugin = paper.createSimplePlugin("InitialUnpaidRecoveryTest")
    private val nativeWorld = paper.addSimpleWorld("initial-unpaid-recovery")
    private val world = mockk<CivsWorld>()
    private val blueprints = mockk<CivsBlueprints>()
    private val executor = Executor(Runnable::run)
    val defaultFamily = "civs/v2/fixture/builder-default/l1"
    val alternateFamily = "civs/v2/fixture/builder-alternate/l1"
    private val templates = settlementPlanTemplates().mapValues { (type, pair) ->
        if (v2 && type == BuildingType.BUILDER_HUT) pair.first.copy(schematicId = defaultFamily) to
            pair.second.copy(schematicId = defaultFamily) else pair
    }
    private val base = settlementPlanCity().let { original ->
        val marker = CivPosition(1, 65, -1)
        original.copy(worldId = nativeWorld.uid, center = marker, radius = 128,
            buildings = listOf(original.buildings.single().copy(phase = BuildingPhase.SITE, control = marker)),
            workers = listOf(CivWorker(UUID(164, 1), "Initial", null, hunger = 20)),
            inventory = CivInventory(CivStorageMode.PHYSICAL), immigration = CivImmigration(4, 120_000),
            settings = CivSettlementSettings(immortalResidents = true))
    }
    val surface = settlementPlanSurface(base.copy(radius = 127))
    val oldPlan = CivsSettlementPlans.prepare(base.copy(autonomy = base.autonomy!!.copy(layoutHalfWidth = halfWidth)),
        templates, surface, CivAutonomousAccess())
    val before = CivsRules.validate(base.copy(autonomy = base.autonomy!!.copy(plan = oldPlan,
        layoutHalfWidth = if (legacyWidth) null else oldPlan.halfWidth)))
    var city = before
    val oldHut = oldPlan.plots.single { it.building.type == BuildingType.BUILDER_HUT }.building
    var time = 10_000L
    var closed = false
    var failure: String? = null
    var holdForecast = false
    val forecast = CompletableFuture<CivTerrainSurface>()
    val saves = mutableListOf<Settlement>()
    var constructionAdmissions = 0
    var reservedCaptures = 0
    var unreservedSurfaces = 0
    var forecasts = 0
    var nativeOreRejections = 0
    private var recovering = false
    val preferredDrafts = mutableListOf<CivTerraformDraft>()
    val unreservedDrafts = mutableListOf<CivTerraformDraft>()
    var rejectedAlternative: CivBuilding? = null
    var rejectedPayload: CivBuilding? = null
    val volumeCaptures = mutableListOf<CivBuilding>()
    val volumeCells = mutableListOf<List<ArcSchematicBlock>>()
    var selected: CivBuilding? = null
    var selectedDraft: CivBuilding? = null
    var selectedRoute = emptyList<CivPosition>()
    val ore = oldHut.bounds.min.copy(y = oldHut.bounds.min.y + 1)
    private fun nativeSurface(): CivTerrainSurface = if (!preferOldTerrace) surface else surface.copy(
        columns = surface.columns + (civsTerrainKey(ore.x, ore.z) to CivTerrainSurfaceColumn(ore.y + 1, true)))
    private val control = requireNotNull(base.buildings.single().control)
    val beforeItems: List<ItemStack?>
    private val planner = CivsAutonomousPlacement(blueprints, world, executor,
        loadAccess = { actual ->
            actual.buildings shouldBe city.buildings
            recovering = actual.autonomy?.plan == null
            CompletableFuture.completedFuture(CivAutonomousAccess())
        })
    val subject = CivsAutonomousDevelopment(plugin, tasks, world, blueprints, planner, executor,
        settlements = { mapOf(city.id to city) }, busy = mutableSetOf(), unhealthy = emptySet(),
        isPlacing = { false }, ready = { true }, isClosed = { closed },
        loadAutonomousPlans = { CompletableFuture.completedFuture(emptyList()) }, settlementActive = { true },
        afterCityAvailable = { _, action -> action() },
        mutate = { _, transform -> save(transform) }, mutateWhenAvailable = { _, transform -> save(transform) },
        commitAutonomousDesign = { actual, design ->
            constructionAdmissions++
            world.captureAutonomousSite(actual.id, actual.owner, actual.worldId,
                design.blueprint.building, design.blueprint.cells, design.terrain).thenCompose {
                CompletableFuture.failedFuture<Unit>(CivsRejection("city-busy"))
            }
        },
        pulseConstruction = {}, clock = { time })

    init {
        nativeWorld.loadChunk(control.x shr 4, control.z shr 4)
        val block = nativeWorld.getBlockAt(control.x, control.y, control.z).also { it.type = Material.BARREL }
        (block.state as Barrel).also { holder ->
            // Placed inventory is live; update() would replace it with this state's empty snapshot inventory.
            listOf(ItemStack(Material.OAK_LOG, 64), ItemStack(Material.COBBLESTONE, 64),
                ItemStack(Material.BREAD, 64), ItemStack(Material.IRON_INGOT, 24)).forEachIndexed(holder.inventory::setItem)
        }
        beforeItems = reserveItems()
        beforeItems.sumOf { it?.amount ?: 0 } shouldBe 216
        every { blueprints.autonomousSource(any(), any()) } returns null
        if (v2) every { blueprints.autonomousSource(any(), BuildingType.BUILDER_HUT) } returns alternateFamily
        every { blueprints.autonomousAlternatives(any(), any()) } returns emptyList()
        every { blueprints.maximumLevel(any()) } answers { firstArg<CivBuilding>().type.maxLevel }
        every { blueprints.fortification(any()) } returns CompletableFuture.completedFuture(null)
        every { blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            CompletableFuture.completedFuture(blueprint(arg(1), arg(2), arg(3), arg(4), arg(5), arg<Int?>(7) ?: 0))
        }
        every { blueprints.prepareEntry(any(), any(), any(), any(), any(), any()) } answers {
            CompletableFuture.completedFuture(blueprint(BuildingType.BUILDER_HUT, 1, arg(1), arg(2), arg(3),
                arg<Int?>(5) ?: 0, firstArg()))
        }
        every { blueprints.prepareUpgrade(any(), any(), any()) } answers {
            val building = firstArg<CivBuilding>()
            CompletableFuture.completedFuture(blueprint(building.type, arg(1), building.id, arg(2),
                building.anchor, building.rotation, building.schematicId))
        }
        every { world.autonomousHeightField(any(), any(), any(), any()) } answers {
            val actual = firstArg<Settlement>()
            actual.buildings shouldBe city.buildings
            actual.autonomy?.plan shouldBe city.autonomy?.plan
            if (arg<CivBuilding>(1).type == BuildingType.TOWN_HALL) {
                forecasts++
                if (holdForecast) forecast else CompletableFuture.completedFuture(
                    if (failure == "no-full-forecast") blockedSurface() else nativeSurface())
            } else {
                if (recovering) unreservedSurfaces++
                CompletableFuture.completedFuture(if (failure == "surface-only" ||
                    failure == "no-choice" && recovering) blockedSurface() else nativeSurface())
            }
        }
        every { world.captureTerraform(any(), any(), any(), any()) } answers {
            val actual = firstArg<Settlement>()
            actual.buildings shouldBe city.buildings
            actual.autonomy?.plan shouldBe city.autonomy?.plan
            val draft = arg<CivTerraformDraft>(1)
            val preferred = recovering && sameOldSlot(draft.building)
            if (!recovering) reservedCaptures++ else if (preferred) preferredDrafts += draft else unreservedDrafts += draft
            if (preferOldTerrace) {
                try {
                    val terrain = nativeTerrain(draft)
                    selectedDraft = draft.building
                    selectedRoute = draft.route
                    CompletableFuture.completedFuture(terrain)
                } catch (rejection: CivsRejection) {
                    rejection.reason shouldBe "autonomy-terrain"
                    rejection.values["block"] shouldBe "minecraft:coal_ore"
                    nativeOreRejections++
                    CompletableFuture.failedFuture(rejection)
                }
            } else if (sameOldSlot(draft.building) || failure == "native-quota" ||
                recovering && unreservedDrafts.size == 1 && !preferred) {
                if (recovering && !preferred && unreservedDrafts.size == 1) rejectedAlternative = draft.building
                CompletableFuture.failedFuture(CivsRejection("autonomy-terrain", mapOf(
                    "x" to draft.building.bounds.min.x.toString(), "y" to (draft.building.bounds.min.y - 1).toString(),
                    "z" to draft.building.bounds.min.z.toString(), "block" to "minecraft:coal_ore", "stage" to "support")))
            } else {
                selectedDraft = draft.building
                selectedRoute = draft.route
                CompletableFuture.completedFuture(CivTerraformPlan(draft.building.bounds, emptyList(), draft.route,
                    draft.groundY, 0, 0))
            }
        }
        every { world.autonomousRoute(any(), any(), any(), any(), any(), any()) } answers {
            val actual = firstArg<Settlement>()
            actual.buildings shouldBe city.buildings
            actual.autonomy?.plan shouldBe city.autonomy?.plan
            CompletableFuture.completedFuture(Unit)
        }
        every { world.captureAutonomousSite(any(), any(), any(), any(), any(), any()) } answers {
            firstArg<UUID>() shouldBe city.id
            arg<UUID>(1) shouldBe city.owner
            arg<UUID>(2) shouldBe city.worldId
            val building = arg<CivBuilding>(3)
            val cells = arg<List<ArcSchematicBlock>>(4)
            cells.size.toLong() shouldBe civsBoxVolume(building.bounds)
            volumeCaptures += building
            volumeCells += cells
            val overwritesOre = preferOldTerrace && cells.any { it.position == ArcSchematicPoint(ore.x, ore.y, ore.z) &&
                it.blockData != "minecraft:coal_ore" }
            if (overwritesOre || (!preferOldTerrace && volumeCaptures.size == 1) || failure == "payload-only") {
                rejectedPayload = building
                CompletableFuture.failedFuture(CivsRejection("autonomy-terrain", mapOf("block" to "COAL_ORE",
                    "x" to building.bounds.min.x.toString(), "y" to building.bounds.min.y.toString(),
                    "z" to building.bounds.min.z.toString())))
            } else {
                selected = building
                CompletableFuture.completedFuture(mockk<CivPlacementPlan>(relaxed = true))
            }
        }
    }

    fun start() {
        subject.pulseAccessRepair(); scheduler.tick()
        subject.accessReady(city) shouldBe true
        subject.pulse(); scheduler.tick()
    }
    fun finish(done: () -> Boolean) {
        repeat(100) { if (!done()) scheduler.tick() }
        done() shouldBe true
    }
    fun reserveItems(): List<ItemStack?> = (nativeWorld.getBlockAt(control.x, control.y, control.z).state as Barrel)
        .inventory.contents.map { it?.clone() }
    fun searchDebug(): Map<*, *> = (planner.debug(city.id)["last"] as List<*>).last() as Map<*, *>
    private fun sameOldSlot(building: CivBuilding) = building.anchor.x == oldHut.anchor.x && building.anchor.z == oldHut.anchor.z &&
        building.rotation == oldHut.rotation && building.schematicId == oldHut.schematicId
    private fun nativeTerrain(draft: CivTerraformDraft): CivTerraformPlan {
        val cells = buildMap {
            draft.columns.forEach { column ->
                val top = if (column.x == ore.x && column.z == ore.z) ore.y + 1 else 64
                for (y in column.supportY..maxOf(column.surfaceY, column.groundY) + 2) {
                    val position = CivPosition(column.x, y, column.z)
                    val isOre = position == ore
                    val solid = y <= top
                    put(position, CivTerraformCell(position, if (isOre) "minecraft:coal_ore" else if (solid) "minecraft:dirt" else "minecraft:air",
                        safe = !isOre, solid = solid, passable = !solid, supportSafe = !isOre))
                }
            }
        }
        return CivsAutonomousTerraform.materialize(draft, cells)
    }
    private fun blockedSurface() = surface.copy(columns = surface.columns.mapValues { (_, c) -> c.copy(natural = false) })
    private fun save(transform: (Settlement) -> Settlement): CompletableFuture<Settlement> = try {
        val next = transform(city)
        city = next; saves += next
        CompletableFuture.completedFuture(next)
    } catch (failure: Throwable) { CompletableFuture.failedFuture(failure) }

    private fun blueprint(type: BuildingType, level: Int, id: UUID, worldId: UUID, anchor: CivPosition,
        rotation: Int, family: String? = null): CivBlueprint {
        val pair = templates.getValue(type)
        val source = if (level == 1) pair.first else pair.second
        fun point(p: CivPosition) = CivsBlueprintGeometry.rotate(ArcSchematicPoint(p.x, p.y, p.z), rotation / 90)
            .let { CivPosition(anchor.x + it.x, anchor.y + it.y, anchor.z + it.z) }
        val corners = listOf(source.bounds.min, source.bounds.max,
            source.bounds.min.copy(z = source.bounds.max.z), source.bounds.max.copy(z = source.bounds.min.z)).map(::point)
        val building = source.copy(id = id, level = level, anchor = anchor, rotation = rotation,
            schematicId = family ?: source.schematicId,
            bounds = CivBox(CivPosition(corners.minOf { it.x }, corners.minOf { it.y }, corners.minOf { it.z }),
                CivPosition(corners.maxOf { it.x }, corners.maxOf { it.y }, corners.maxOf { it.z })), entrance = point(source.entrance))
        val cells = buildList {
            for (x in building.bounds.min.x..building.bounds.max.x) for (y in building.bounds.min.y..building.bounds.max.y)
                for (z in building.bounds.min.z..building.bounds.max.z) {
                    val data = if (y != building.bounds.min.y) "minecraft:air" else if (building.schematicId == alternateFamily)
                        "minecraft:oak_planks" else "minecraft:cobblestone"
                    add(ArcSchematicBlock(ArcSchematicPoint(x, y, z), data, data == "minecraft:air", false))
                }
        }
        return CivBlueprint(building, worldId, mockk<ArcSchematicSnapshot>(relaxed = true), cells, cells, byteArrayOf(1))
    }
    override fun close() { subject.cancel(); planner.close(); tasks.close() }
}

private fun initialRecoveryScenario(v2: Boolean = false, halfWidth: Int = 74, legacyWidth: Boolean = false,
    preferOldTerrace: Boolean = false,
    action: (InitialRecoveryScenario) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open().use { paper -> InitialRecoveryScenario(paper, v2, halfWidth, legacyWidth, preferOldTerrace).use(action) }
}
