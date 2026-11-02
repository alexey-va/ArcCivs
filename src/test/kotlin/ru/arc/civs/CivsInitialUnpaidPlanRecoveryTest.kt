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

class CivsCompatibleUnpaidPlanRecoveryTest : FunSpec({
    test("a compatible horizontal forester replacement keeps the accepted unpaid outline and paid access") {
        initialRecoveryScenario(targetType = BuildingType.FORESTER, permuteUnpaidHouses = true) { s ->
            s.city.radius shouldBe 128
            val paidBefore = s.paidPlans.toList()
            val plan = s.forecastCompatibleExterior().join()
            val selected = requireNotNull(s.selected)
            val earthworks = requireNotNull(s.forecastTerrain)
            earthworks.blocks.isNotEmpty() shouldBe true
            earthworks.route.first() shouldBe s.before.buildings.first { it.type == BuildingType.TOWN_HALL }.entrance
            civsContainsBox(earthworks.bounds, selected.bounds) shouldBe false
            ((selected.anchor.x to selected.anchor.z) == (s.oldTarget.anchor.x to s.oldTarget.anchor.z)) shouldBe false
            selected.id shouldBe s.oldTarget.id
            selected.schematicId shouldBe s.oldTarget.schematicId
            plan.plots.single { it.building.id == selected.id }.building shouldBe selected.copy(phase = BuildingPhase.SITE)
            plan.plots.filter { it.building.id != selected.id } shouldBe s.oldPlan.plots.filter { it.building.id != selected.id }
            (s.oldPlan.routes - selected.id.toString()).forEach { (id, route) -> plan.routes[id] shouldBe route }
            s.acceptedAccess.routes.forEach { (id, route) -> plan.routes[id.toString()] shouldBe route }
            plan.routes[selected.id.toString()] shouldBe s.selectedRoute
            plan.walls shouldBe s.oldPlan.walls
            plan.gates shouldBe s.oldPlan.gates
            plan.gateSides shouldBe s.oldPlan.gateSides
            plan.plots.size shouldBe 34
            plan.routes.size shouldBe 33
            CivsVillageSiteScoring.completeProfile(plan) shouldBe true
            plan.revision shouldBe s.oldPlan.revision + 1
            s.forecasts shouldBe 1
            s.city shouldBe s.before
            s.paidPlans shouldBe paidBefore
            s.saves shouldBe emptyList()
            s.reserveItems() shouldBe s.beforeItems
        }
    }

    for (stage in listOf("dry-perimeter", "other-budget"))
    test("recovery continuation advances only the exhausted dry perimeter forecast stage=$stage") {
        initialRecoveryScenario(continuation = true) { s ->
            val declined = s.rejectFirstForecast("autonomy-search-budget", stage)
            s.saves shouldBe emptyList()
            s.city shouldBe s.before
            s.time += 300_001; s.subject.pulse(); s.scheduler.tick()
            s.finish { s.saves.size == 1 && s.subject.debug(s.city.id)["request"] == null }
            val next = s.forecastDesigns.last()
            (next == declined) shouldBe (stage != "dry-perimeter")
            s.forecasts shouldBe 2
            s.forecastDesigns shouldBe listOf(declined, next)
            (s.searchDebug()["attempted"] as Int <= 64) shouldBe true
            CivsVillageSiteScoring.completeProfile(requireNotNull(s.city.autonomy?.plan)) shouldBe true
            s.city.copy(revision = s.before.revision, autonomy = s.before.autonomy) shouldBe s.before
            s.constructionAdmissions shouldBe 0
            s.reserveItems() shouldBe s.beforeItems
        }
    }
})

class CivsDevelopedUnpaidPlanRecoveryTest : FunSpec({
    for (target in listOf(BuildingType.FORESTER, BuildingType.FARM))
    test("developed unpaid recovery preserves the actual town while replacing its blocked $target") {
        initialRecoveryScenario(targetType = target, preferOldTerrace = target == BuildingType.FARM,
            draftRefusal = target == BuildingType.FORESTER, stalePaidIntent = target == BuildingType.FARM) { s ->
            val paidBefore = s.paidPlans.toList()
            s.before.buildings.any { it.type == BuildingType.BUILDER_HUT && it.phase == BuildingPhase.ACTIVE } shouldBe true
            s.before.building(s.oldTarget.id) shouldBe null
            s.start(); s.finish { s.subject.debug(s.city.id)["request"] == null }
            s.saves.size shouldBe 1
            val selected = requireNotNull(s.selected)
            val plan = requireNotNull(s.city.autonomy?.plan)
            selected.id shouldBe s.oldTarget.id
            plan.plots.single { it.building.id == selected.id }.building shouldBe selected.copy(phase = BuildingPhase.SITE)
            plan.routes[selected.id.toString()] shouldBe s.selectedRoute
            plan.plots.size shouldBe 34
            plan.routes.size shouldBe 33
            CivsVillageSiteScoring.completeProfile(plan) shouldBe true
            plan.revision shouldBe s.oldPlan.revision + 1
            plan.halfWidth shouldBe s.oldPlan.halfWidth
            s.forecasts shouldBe 1
            (s.searchDebug()["attempted"] as Int <= 64) shouldBe true
            s.volumeCaptures.last() shouldBe selected
            s.acceptedAccess.routes.forEach { (id, route) -> plan.routes[id.toString()] shouldBe route }
            if (target == BuildingType.FARM) {
                s.searchDebug()["preferred_terraces_attempted"] shouldBe 3
                if (selected.anchor.x == s.oldTarget.anchor.x && selected.anchor.z == s.oldTarget.anchor.z &&
                    selected.rotation == s.oldTarget.rotation && selected.schematicId == s.oldTarget.schematicId) {
                    selected.anchor shouldBe s.oldTarget.anchor.copy(y = s.oldTarget.anchor.y + 4)
                    plan.plots.filter { it.building.id != selected.id } shouldBe s.oldPlan.plots.filter { it.building.id != selected.id }
                    val unpaid = s.oldPlan.routes.keys - s.acceptedAccess.routes.keys.map(UUID::toString).toSet() - selected.id.toString()
                    plan.routes.filterKeys { it in unpaid } shouldBe s.oldPlan.routes.filterKeys { it in unpaid }
                    plan.walls shouldBe s.oldPlan.walls
                    plan.gates shouldBe s.oldPlan.gates
                    s.preferredDrafts.single().access shouldBe s.acceptedAccess
                } else {
                    s.unreservedDrafts.last().building.anchor shouldBe selected.anchor
                    s.unreservedDrafts.last().access shouldBe s.acceptedAccess
                }
            } else {
                // The reserved draft fails before any native terrain callback, without a native-terrain marker.
                s.reservedCaptures shouldBe 0
                s.unreservedDrafts.isNotEmpty() shouldBe true
            }
            s.paidPlans shouldBe paidBefore
            s.city.copy(revision = s.before.revision, autonomy = s.before.autonomy) shouldBe s.before
            s.constructionAdmissions shouldBe 0
            s.reserveItems() shouldBe s.beforeItems
        }
    }

    test("developed unpaid recovery retains paid infrastructure while adding complete planner gate connections") {
        initialRecoveryScenario(preferOldTerrace = true, paidInfrastructure = true) { s ->
            val paidBefore = s.paidPlans.toList()
            s.acceptedAccess.infrastructure.single().type shouldBe BuildingType.ROAD
            s.start(); s.finish { s.subject.debug(s.city.id)["request"] == null }
            s.saves.size shouldBe 1
            val selected = requireNotNull(s.selected)
            val plan = requireNotNull(s.city.autonomy?.plan)
            selected.anchor shouldBe s.oldTarget.anchor.copy(y = s.oldTarget.anchor.y + 4)
            selected.rotation shouldBe s.oldTarget.rotation
            selected.schematicId shouldBe s.oldTarget.schematicId
            plan.plots.filter { it.building.id != selected.id } shouldBe s.oldPlan.plots.filter { it.building.id != selected.id }
            (s.oldPlan.routes - selected.id.toString()).forEach { (id, route) -> plan.routes[id] shouldBe route }
            (plan.routes.keys - s.oldPlan.routes.keys).size shouldBe s.oldPlan.gates.size
            plan.routes[selected.id.toString()] shouldBe s.selectedRoute
            plan.plots.size shouldBe 34
            plan.routes.size shouldBe s.oldPlan.routes.size + s.oldPlan.gates.size
            CivsVillageSiteScoring.completeProfile(plan) shouldBe true
            plan.walls shouldBe s.oldPlan.walls
            plan.gates shouldBe s.oldPlan.gates
            s.preferredDrafts.single().access shouldBe s.acceptedAccess
            s.forecasts shouldBe 1
            s.paidPlans shouldBe paidBefore
            s.city.copy(revision = s.before.revision, autonomy = s.before.autonomy) shouldBe s.before
            s.constructionAdmissions shouldBe 0
            s.reserveItems() shouldBe s.beforeItems
        }
    }

    test("developed unpaid recovery keeps all accepted state when the complete forecast refuses") {
        initialRecoveryScenario(targetType = BuildingType.FARM, preferOldTerrace = true) { s ->
            s.failure = "no-full-forecast"
            s.start(); s.finish { s.subject.debug(s.city.id)["request"] == null }
            s.forecasts shouldBe 1
            s.volumeCaptures.isNotEmpty() shouldBe true
            s.saves shouldBe emptyList()
            s.city shouldBe s.before
            s.constructionAdmissions shouldBe 0
            s.reserveItems() shouldBe s.beforeItems
            val declined = requireNotNull(s.selected)
            s.time += 30_001; s.subject.pulse(); s.scheduler.tick()
            s.finish { s.subject.debug(s.city.id)["request"] == null }
            // The complete forecast refused this candidate; the next one retains fresh native proof.
            s.forecasts shouldBe 2
            (requireNotNull(s.selected) == declined) shouldBe false
            s.volumeCaptures.contains(requireNotNull(s.selected)) shouldBe true
            s.saves shouldBe emptyList()
            s.city shouldBe s.before
            s.constructionAdmissions shouldBe 0
            s.reserveItems() shouldBe s.beforeItems
        }
    }

    test("developed unpaid recovery fences a changed active building while its forecast is pending") {
        initialRecoveryScenario(targetType = BuildingType.FARM, preferOldTerrace = true) { s ->
            s.holdForecast = true
            s.start(); s.finish { s.forecasts == 1 }
            val current = s.city.copy(buildings = s.city.buildings.map { building ->
                if (building.type != BuildingType.BUILDER_HUT) building else building.copy(level = building.level + 1)
            })
            s.city = current
            s.forecast.complete(s.surface)
            s.finish { s.subject.debug(s.city.id)["request"] == null }
            s.saves shouldBe emptyList()
            s.city shouldBe current
            s.constructionAdmissions shouldBe 0
            s.reserveItems() shouldBe s.beforeItems
        }
    }
})

class CivsInitialUnpaidPlanRecoveryTest : FunSpec({
    test("recovery continuation forecasts the next native proven choice after a complete layout refusal") {
        initialRecoveryScenario(continuation = true) { s ->
            val declined = s.rejectFirstForecast("autonomy-layout-obstructed")
            s.subject.progress(s.city.id)?.reason shouldBe "autonomy-layout-obstructed"
            s.city shouldBe s.before
            s.saves shouldBe emptyList()
            s.nativeProofs shouldBe listOf(declined)
            s.forecasts shouldBe 1
            s.reserveItems() shouldBe s.beforeItems

            // The refusal belongs to the declined candidate, not the next proven choice.
            s.time += 30_001; s.subject.pulse(); s.scheduler.tick()
            s.finish { s.saves.size == 1 && s.subject.debug(s.city.id)["request"] == null }
            s.developmentWaiting shouldBe false
            val replacement = s.forecastDesigns.last()
            (replacement == declined) shouldBe false
            s.forecastDesigns shouldBe listOf(declined, replacement)
            // A skipped choice is proved again; no native acceptance is cached across retries.
            s.nativeProofs.takeLast(2) shouldBe listOf(declined, replacement)
            s.forecasts shouldBe 2
            (s.searchDebug()["attempted"] as Int <= 64) shouldBe true
            s.searchDebug()["preferred_terraces_attempted"] shouldBe 3
            val plan = requireNotNull(s.city.autonomy?.plan)
            plan.plots.size shouldBe 34
            plan.routes.size shouldBe 33
            CivsVillageSiteScoring.completeProfile(plan) shouldBe true
            plan.revision shouldBe s.oldPlan.revision + 1
            plan.plots.single { it.building.id == s.oldHut.id }.building shouldBe replacement.copy(phase = BuildingPhase.SITE)
            s.city.copy(revision = s.before.revision, autonomy = s.before.autonomy) shouldBe s.before
            s.constructionAdmissions shouldBe 0
            s.reserveItems() shouldBe s.beforeItems
        }
    }

    test("recovery continuation does not advance after a transient forecast failure") {
        initialRecoveryScenario(continuation = true) { s ->
            val candidate = s.rejectFirstForecast("world-busy")
            s.city shouldBe s.before
            s.saves shouldBe emptyList()
            s.time += 300_001; s.subject.pulse(); s.scheduler.tick()
            s.finish { s.saves.size == 1 && s.subject.debug(s.city.id)["request"] == null }
            s.forecastDesigns shouldBe listOf(candidate, candidate)
            s.nativeProofs shouldBe listOf(candidate, candidate)
            s.forecasts shouldBe 2
            CivsVillageSiteScoring.completeProfile(requireNotNull(s.city.autonomy?.plan)) shouldBe true
            s.city.copy(revision = s.before.revision, autonomy = s.before.autonomy) shouldBe s.before
            s.constructionAdmissions shouldBe 0
            s.reserveItems() shouldBe s.beforeItems
        }
    }

    test("recovery continuation discards declined choices when the owner context changes") {
        initialRecoveryScenario(continuation = true) { s ->
            val candidate = s.rejectFirstForecast("autonomy-layout-obstructed")
            val current = s.city.copy(owner = UUID(169, 9))
            s.city = current
            s.time += 300_001; s.subject.pulse(); s.scheduler.tick()
            s.finish { s.saves.size == 1 && s.subject.debug(s.city.id)["request"] == null }
            s.forecastDesigns shouldBe listOf(candidate, candidate)
            s.nativeProofs shouldBe listOf(candidate, candidate)
            s.forecasts shouldBe 2
            s.city.copy(revision = current.revision, autonomy = current.autonomy) shouldBe current
            s.constructionAdmissions shouldBe 0
            s.reserveItems() shouldBe s.beforeItems
        }
    }

    test("recovery continuation reruns native guards within sixty four attempts and resets on exhaustion") {
        initialRecoveryScenario(continuation = true) { s ->
            val candidate = s.rejectFirstForecast("autonomy-layout-obstructed")
            s.failure = "native-quota"
            s.time += 300_001; s.subject.pulse(); s.scheduler.tick()
            s.finish { s.subject.debug(s.city.id)["request"] == null }
            s.searchDebug()["attempted"] shouldBe 64
            s.searchDebug()["preferred_terraces_attempted"] shouldBe 3
            s.nativeProofs shouldBe listOf(candidate)
            s.forecasts shouldBe 1
            s.saves shouldBe emptyList()
            s.city shouldBe s.before
            s.reserveItems() shouldBe s.beforeItems

            s.failure = null
            s.time += 300_001; s.subject.pulse(); s.scheduler.tick()
            s.finish { s.saves.size == 1 && s.subject.debug(s.city.id)["request"] == null }
            s.forecastDesigns shouldBe listOf(candidate, candidate)
            s.nativeProofs shouldBe listOf(candidate, candidate)
            s.forecasts shouldBe 2
            s.city.copy(revision = s.before.revision, autonomy = s.before.autonomy) shouldBe s.before
            s.constructionAdmissions shouldBe 0
            s.reserveItems() shouldBe s.beforeItems
        }
    }

    test("unpaid road shoulder grade conflicts still admit the coherent raised old hut") {
        initialRecoveryScenario(v2 = true, preferOldTerrace = true, shoulderConflicts = true) { s ->
            s.shoulderGrades.size shouldBe 1
            s.start(); s.finish { s.saves.size == 1 && s.subject.debug(s.city.id)["request"] == null }
            val plan = requireNotNull(s.city.autonomy?.plan)
            val selected = requireNotNull(s.selected)
            val draft = s.preferredDrafts.single()
            selected.anchor shouldBe s.oldHut.anchor.copy(y = s.oldHut.anchor.y + 4)
            selected.rotation shouldBe s.oldHut.rotation
            selected.schematicId shouldBe s.defaultFamily
            plan.plots.filter { it.building.id != s.oldHut.id } shouldBe s.oldPlan.plots.filter { it.building.id != s.oldHut.id }
            plan.routes - s.oldHut.id.toString() shouldBe s.oldPlan.routes - s.oldHut.id.toString()
            plan.walls shouldBe s.oldPlan.walls
            plan.gates shouldBe s.oldPlan.gates
            plan.gateSides shouldBe s.oldPlan.gateSides
            plan.plots.single { it.building.id == s.oldHut.id }.envelope.min.y shouldBe s.oldHut.bounds.min.y + 4
            draft.columns.none { civsTerrainKey(it.x, it.z) in s.shoulderGrades } shouldBe true
            draft.access shouldBe CivAutonomousAccess()
            draft.columns.filter { it.preserve }.map { CivPosition(it.x, it.groundY + 1, it.z) }
                .toSet() shouldBe setOf(s.before.buildings.single().entrance)
            s.unreservedDrafts shouldBe emptyList()
            s.forecasts shouldBe 1
            s.volumeCaptures shouldBe listOf(selected)
            plan.plots.size shouldBe 34
            plan.routes.size shouldBe 33
            CivsVillageSiteScoring.completeProfile(plan) shouldBe true
            s.city.copy(revision = s.before.revision, autonomy = s.before.autonomy) shouldBe s.before
            s.constructionAdmissions shouldBe 0
            s.reserveItems() shouldBe s.beforeItems
        }
    }

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
            val mandatoryCrossings = draft.route.mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }.intersect(unpaidRoads)
            draft.columns.mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }.intersect(unpaidRoads) shouldBe mandatoryCrossings
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
            val declined = if (failure == "no-full-forecast") requireNotNull(s.selected) else null
            s.time += 30_001; s.subject.pulse(); s.scheduler.tick()
            s.finish { s.subject.debug(s.city.id)["request"] == null }
            // Exhausted searches retain their TTL; a refused full forecast advances to a distinct candidate.
            s.forecasts shouldBe if (failure == "no-full-forecast") 2 else 0
            if (declined != null) {
                (requireNotNull(s.selected) == declined) shouldBe false
                s.volumeCaptures.contains(requireNotNull(s.selected)) shouldBe true
            }
            s.saves shouldBe emptyList()
            s.city shouldBe s.before
            s.constructionAdmissions shouldBe 0
            s.reserveItems() shouldBe s.beforeItems
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
    halfWidth: Int, legacyWidth: Boolean, private val preferOldTerrace: Boolean, shoulderConflicts: Boolean,
    private val continuation: Boolean, private val targetType: BuildingType,
    private val draftRefusal: Boolean, stalePaidIntent: Boolean, paidInfrastructure: Boolean,
    permuteUnpaidHouses: Boolean) : AutoCloseable {
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
    val shoulderGrades = linkedMapOf<Long, Int>()
    private val initialPlan = CivsSettlementPlans.prepare(base.copy(autonomy = base.autonomy!!.copy(layoutHalfWidth = halfWidth)),
        templates, surface, CivAutonomousAccess()).let { if (shoulderConflicts) withShoulderConflicts(it) else it }.let { plan ->
        if (!permuteUnpaidHouses) plan else {
            // Both assignments are legitimate unpaid intent; a fresh default packing would silently swap them back.
            val houses = plan.plots.filter { it.building.type == BuildingType.HOUSE }.take(2).map { it.building.id }
            val swapped = mapOf(houses[0] to houses[1], houses[1] to houses[0])
            plan.copy(plots = plan.plots.map { plot -> swapped[plot.building.id]?.let { plot.copy(building = plot.building.copy(id = it)) } ?: plot },
                routes = plan.routes.mapKeys { (id, _) -> swapped[UUID.fromString(id)]?.toString() ?: id })
        }
    }
    private val paidRoad = if (!paidInfrastructure) null else CivsInfrastructure.building(CivSettlementPiece(UUID(172, 1),
        BuildingType.ROAD, CivBox(CivPosition(0, 64, 0), CivPosition(0, 66, 0)), CivPosition(0, 65, 0),
        listOf(CivSettlementBlock(CivPosition(0, 64, 0), "minecraft:dirt_path"),
            CivSettlementBlock(CivPosition(0, 65, 0), "minecraft:air"), CivSettlementBlock(CivPosition(0, 66, 0), "minecraft:air")),
        listOf(CivPosition(0, 65, 0)))).copy(phase = BuildingPhase.ACTIVE)
    private val actualBuildings = base.buildings + listOfNotNull(paidRoad) + CivsAutonomy.baseProfile.takeWhile { it != targetType }
        .filterNot { it == BuildingType.TOWN_HALL }.map { type ->
            initialPlan.plots.first { it.building.type == type }.building.copy(phase = BuildingPhase.ACTIVE)
        }
    val oldPlan = if (!stalePaidIntent) initialPlan else initialPlan.copy(routes = initialPlan.routes.mapValues { (id, route) ->
        if (actualBuildings.any { it.id.toString() == id && it.phase == BuildingPhase.ACTIVE }) route.map { it.copy(y = it.y + 1) }
        else route
    })
    val before = CivsRules.validate(base.copy(buildings = actualBuildings, autonomy = base.autonomy!!.copy(plan = oldPlan,
        layoutHalfWidth = if (legacyWidth) null else oldPlan.halfWidth)))
    var city = before
    val oldHut = oldPlan.plots.single { it.building.type == BuildingType.BUILDER_HUT }.building
    val oldTarget = oldPlan.plots.first { it.building.type == targetType }.building
    val paidPlans = before.buildings.filter { it.phase == BuildingPhase.ACTIVE }.map { building ->
        val route = if (building.id == paidRoad?.id) listOf(building.entrance) else initialPlan.routes.getValue(building.id.toString())
        val blocks = if (building.id != paidRoad?.id) emptyList() else listOf(
            CivPlanBlock(CivPosition(0, 64, 0), "minecraft:dirt", "minecraft:dirt_path"),
            CivPlanBlock(CivPosition(0, 65, 0), "minecraft:air", "minecraft:air"),
            CivPlanBlock(CivPosition(0, 66, 0), "minecraft:air", "minecraft:air"))
        CivPlacementPlan(building.id, before.id, before.worldId, building, blocks, terrain =
            CivTerraformPlan(building.bounds, emptyList(), route, building.anchor.y, 0, 0))
    }
    val acceptedAccess = CivAutonomousAccess.from(before, paidPlans)
    var time = 10_000L
    var closed = false
    var failure: String? = null
    var holdForecast = false
    val forecast = CompletableFuture<CivTerrainSurface>()
    val saves = mutableListOf<Settlement>()
    var constructionAdmissions = 0
    var developmentWaiting = false
    var reservedCaptures = 0
    var unreservedSurfaces = 0
    var forecasts = 0
    val forecastDesigns = mutableListOf<CivBuilding>()
    val nativeProofs = mutableListOf<CivBuilding>()
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
    var forecastTerrain: CivTerraformPlan? = null
    val ore = oldTarget.bounds.min.copy(y = oldTarget.bounds.min.y + 1)
    private fun nativeSurface(blockReservedAlternatives: Boolean = false): CivTerrainSurface {
        val raised = if (!preferOldTerrace) surface else surface.copy(
            columns = surface.columns + (civsTerrainKey(ore.x, ore.z) to CivTerrainSurfaceColumn(ore.y + 1, true)))
        if (!draftRefusal && !blockReservedAlternatives) return raised
        // These fixtures exercise relocation after every existing reservation is refused.
        // Later-slot success is covered separately; the full forecast keeps its original captured surface.
        val targets = listOfNotNull(oldTarget.takeIf { draftRefusal }) + if (blockReservedAlternatives)
            oldPlan.plots.filter { it.building.type == targetType && it.building.id != oldTarget.id &&
                before.building(it.building.id) == null }.map { it.building } else emptyList()
        val blocked = targets.associate { building ->
            val point = CivsAutonomousGeometry.approach(building).first {
                !CivsAutonomousGeometry.horizontalContains(building.bounds, it) &&
                    civsTerrainKey(it.x, it.z) !in acceptedAccess.standing(before, building.id)
            }
            val key = civsTerrainKey(point.x, point.z)
            key to raised.columns.getValue(key).copy(natural = false)
        }
        return raised.copy(columns = raised.columns + blocked)
    }
    private val control = requireNotNull(base.buildings.single().control)
    val beforeItems: List<ItemStack?>
    private val planner = CivsAutonomousPlacement(blueprints, world, executor,
        loadAccess = { actual ->
            actual.buildings shouldBe city.buildings
            recovering = actual.autonomy?.plan == null
            CompletableFuture.completedFuture(acceptedAccess)
        })
    val subject = CivsAutonomousDevelopment(plugin, tasks, world, blueprints, planner, executor,
        settlements = { mapOf(city.id to city) }, busy = mutableSetOf(), unhealthy = emptySet(),
        isPlacing = { false }, ready = { true }, isClosed = { closed },
        loadAutonomousPlans = { CompletableFuture.completedFuture(paidPlans) }, settlementActive = { true },
        afterCityAvailable = { _, action -> action() },
        mutate = { _, transform -> save(transform) }, mutateWhenAvailable = { _, transform -> save(transform) },
        commitAutonomousDesign = { actual, design ->
            constructionAdmissions++
            world.captureAutonomousSite(actual.id, actual.owner, actual.worldId,
                design.blueprint.building, design.blueprint.cells, design.terrain).thenCompose {
                CompletableFuture.failedFuture<Unit>(CivsRejection("city-busy"))
            }
        },
        pulseConstruction = {}, clock = { time }, developmentFinished = { developmentWaiting = false },
        developmentAdmission = { developmentWaiting = true; true })

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
                if (continuation) forecastDesigns += requireNotNull(selected)
                if (holdForecast) forecast else CompletableFuture.completedFuture(
                    if (failure == "no-full-forecast") blockedSurface() else nativeSurface())
            } else {
                if (recovering) unreservedSurfaces++
                CompletableFuture.completedFuture(if (failure == "surface-only" ||
                    failure == "no-choice" && recovering) blockedSurface() else nativeSurface(blockReservedAlternatives = !recovering))
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
                if (continuation) {
                    if (recovering && !preferred && rejectedAlternative == null) rejectedAlternative = draft.building
                    draft.building == rejectedAlternative
                } else recovering && unreservedDrafts.size == 1 && !preferred) {
                if (!continuation && recovering && !preferred && unreservedDrafts.size == 1) rejectedAlternative = draft.building
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
            val rejectedFirstPayload = !preferOldTerrace && if (continuation) {
                if (rejectedPayload == null) rejectedPayload = building
                building == rejectedPayload
            } else volumeCaptures.size == 1
            if (overwritesOre || rejectedFirstPayload || failure == "payload-only") {
                rejectedPayload = building
                CompletableFuture.failedFuture(CivsRejection("autonomy-terrain", mapOf("block" to "COAL_ORE",
                    "x" to building.bounds.min.x.toString(), "y" to building.bounds.min.y.toString(),
                    "z" to building.bounds.min.z.toString())))
            } else {
                selected = building
                if (continuation) nativeProofs += building
                CompletableFuture.completedFuture(mockk<CivPlacementPlan>(relaxed = true))
            }
        }
    }

    fun start() {
        subject.pulseAccessRepair(); scheduler.tick()
        subject.accessReady(city) shouldBe true
        subject.pulse(); scheduler.tick()
    }
    fun rejectFirstForecast(reason: String, stage: String? = null): CivBuilding {
        holdForecast = true
        start(); finish { forecasts == 1 }
        val candidate = requireNotNull(selected)
        holdForecast = false
        // The existing asynchronous forecast boundary supplies a controlled refusal for A.
        // Subsequent choices still run the real complete-profile planner on the flat fixture.
        forecast.completeExceptionally(CivsRejection(reason,
            if (stage != null) mapOf("stage" to stage) else if (reason == "autonomy-layout-obstructed")
                mapOf("stage" to "no-dry-perimeter") else emptyMap()))
        finish { subject.debug(city.id)["request"] == null }
        return candidate
    }
    /** Real draft/materialization and full forecast; this is detached fixture evidence, not a native World replay. */
    fun forecastCompatibleExterior(): CompletableFuture<CivSettlementPlan> {
        val shadow = city.copy(autonomy = city.autonomy!!.copy(plan = null, layoutHalfWidth = oldPlan.halfWidth))
        val remaining = oldPlan.plots.filter { it.building.id != oldTarget.id }.map { it.envelope }
        val obstacles = remaining + oldPlan.walls
        val roads = oldPlan.routes.filterKeys { it != oldTarget.id.toString() && city.building(UUID.fromString(it)) == null }
            .values.flatten().groupBy { civsTerrainKey(it.x, it.z) }
        fun conflicts(building: CivBuilding, envelope: CivBox, terrain: CivTerraformPlan? = null): List<String> = buildList {
            (obstacles + oldPlan.gates).filter { CivsAutonomousGeometry.overlaps(envelope, it) }
                .forEach { add("future-envelope:$it") }
            CivsAutonomousGeometry.approach(building).filter { point ->
                obstacles.any { CivsAutonomousGeometry.horizontalContains(it, point) }
            }.forEach { add("apron:$it") }
            roads.values.flatten().filter { CivsAutonomousGeometry.horizontalContains(envelope, it) }
                .forEach { add("unpaid-road-in-envelope:$it") }
            terrain?.blocks.orEmpty().filter { cell -> obstacles.any {
                CivsAutonomousGeometry.horizontalContains(it, cell.position)
            } }.forEach { add("earthwork:$it") }
            terrain?.route.orEmpty().filter { point -> obstacles.any {
                CivsAutonomousGeometry.horizontalContains(it, point)
            } || roads[civsTerrainKey(point.x, point.z)].orEmpty().any { it.y != point.y } }
                .forEach { add("route:$it") }
        }
        val pair = templates.getValue(oldTarget.type)
        fun futureEnvelope(building: CivBuilding): CivBox {
            val current = CivsAutonomousGeometry.futureBounds(city, building)
            val future = CivsSettlementPlans.at(pair.second, building.anchor, building.rotation, oldTarget.id).bounds
            return CivBox(CivPosition(minOf(current.min.x, future.min.x), minOf(current.min.y, future.min.y), minOf(current.min.z, future.min.z)),
                CivPosition(maxOf(current.max.x, future.max.x), maxOf(current.max.y, future.max.y), maxOf(current.max.z, future.max.z)))
        }
        val candidates = CivsSettlementLayout.candidates(shadow, pair.first.copy(id = oldTarget.id),
            pair.second.copy(id = oldTarget.id), acceptedAccess, CivsSettlementLayout.frame(shadow))
        val options = CivsAutonomousTerraform.options(shadow, candidates, surface).take(CivsAutonomousPlacement.MAXIMUM_OPTIONS)
        val refused = mutableListOf<String>()
        val chosen = options.firstNotNullOfOrNull { option ->
            if (option.building.anchor.x == oldTarget.anchor.x && option.building.anchor.z == oldTarget.anchor.z)
                return@firstNotNullOfOrNull null
            val prepared = blueprint(oldTarget.type, 1, oldTarget.id, city.worldId, option.building.anchor, option.building.rotation)
            val envelope = futureEnvelope(prepared.building)
            val before = conflicts(prepared.building, envelope)
            if (before.isNotEmpty()) {
                if (refused.size < 4) refused += "${prepared.building.anchor}:${before.take(2)}"
                return@firstNotNullOfOrNull null
            }
            val draft = try { CivsAutonomousTerraform.draft(shadow, option.copy(building = prepared.building.copy(bounds = envelope)),
                surface, acceptedAccess, obstacles) }
            catch (failure: CivsRejection) {
                if (refused.size < 4) refused += "${prepared.building.anchor}:${failure.reason}:${failure.values}"
                return@firstNotNullOfOrNull null
            }
            val terrain = nativeTerrain(draft, protectedOre = false)
            val after = conflicts(prepared.building, envelope, terrain)
            if (after.isNotEmpty()) {
                if (refused.size < 4) refused += "${prepared.building.anchor}:${after.take(2)}"
                return@firstNotNullOfOrNull null
            }
            // Establish the fixture's full building/road/cell contract BEFORE invoking the production forecast.
            conflicts(prepared.building, envelope, terrain) shouldBe emptyList()
            selected = prepared.building; selectedDraft = draft.building; selectedRoute = draft.route; forecastTerrain = terrain
            CivAutonomousDesign(prepared, terrain)
        }
        check(chosen != null) { "No compatible exterior fixture among ${options.size} ordinary options: $refused" }
        return planner.plan(civsPlanBoardCapture(city), chosen)
    }
    fun finish(done: () -> Boolean) {
        repeat(100) { if (!done()) scheduler.tick() }
        done() shouldBe true
    }
    fun reserveItems(): List<ItemStack?> = (nativeWorld.getBlockAt(control.x, control.y, control.z).state as Barrel)
        .inventory.contents.map { it?.clone() }
    fun searchDebug(): Map<*, *> = (planner.debug(city.id)["last"] as List<*>).last() as Map<*, *>
    /** A legitimate unpaid road bump sits beside the future hut route, outside its mandatory corridor. */
    private fun withShoulderConflicts(plan: CivSettlementPlan): CivSettlementPlan {
        val hut = plan.plots.single { it.building.type == BuildingType.BUILDER_HUT }
        val others = plan.plots.filter { it.building.id != hut.building.id && base.building(it.building.id) == null }
        val shadow = base.copy(autonomy = base.autonomy!!.copy(plan = null, layoutHalfWidth = plan.halfWidth))
        val virtual = shadow.copy(buildings = base.buildings + others.map { it.building.copy(bounds = it.envelope, phase = BuildingPhase.SITE) })
        val access = CivAutonomousAccess(routes = (plan.routes - hut.building.id.toString()).mapKeys { UUID.fromString(it.key) })
        val option = CivsAutonomousTerraform.options(shadow, listOf(CivsAutonomousCandidate(hut.building.copy(bounds = hut.envelope))), surface)
            .single { it.building.anchor.y == hut.building.anchor.y + 4 }
        val planned = CivsAutonomousTerraform.draft(virtual, option, surface, access, plan.walls)
        val actual = CivsAutonomousTerraform.draft(shadow, option, surface, CivAutonomousAccess(),
            plan.walls + others.map { it.envelope }, reservedRoute = planned.route)
        val hinted = access.standing(virtual, hut.building.id)
        val plannedKeys = planned.columns.mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }
        val mandatory = (planned.route + CivsAutonomousGeometry.approach(option.building)).mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }
        val entrances = others.mapTo(hashSetOf()) { civsTerrainKey(it.building.entrance.x, it.building.entrance.z) }
        actual.columns.filter { column ->
            val key = civsTerrainKey(column.x, column.z)
            key in hinted && key !in plannedKeys && key !in mandatory && key !in entrances &&
                !CivsAutonomousGeometry.horizontalContains(option.building.bounds, CivPosition(column.x, column.groundY, column.z))
        }.take(1).forEach { column ->
            val key = civsTerrainKey(column.x, column.z)
            hinted.getValue(key).y - 1 shouldBe column.groundY
            shoulderGrades[key] = hinted.getValue(key).y
        }
        check(shoulderGrades.size == 1) { "Fixture needs one offcenterline unpaid road shoulder, found ${shoulderGrades.size}" }
        val changed = plan.copy(routes = plan.routes.mapValues { (id, route) -> if (id == hut.building.id.toString()) route else route.map { feet ->
            shoulderGrades[civsTerrainKey(feet.x, feet.z)]?.let { feet.copy(y = it + 1) } ?: feet
        } })
        CivsSettlementPlans.validate(base, changed)
        return changed
    }
    private fun sameOldSlot(building: CivBuilding) = building.anchor.x == oldTarget.anchor.x && building.anchor.z == oldTarget.anchor.z &&
        building.rotation == oldTarget.rotation && building.schematicId == oldTarget.schematicId
    private fun nativeTerrain(draft: CivTerraformDraft, protectedOre: Boolean = true): CivTerraformPlan {
        val cells = buildMap {
            draft.columns.forEach { column ->
                val top = if (protectedOre && column.x == ore.x && column.z == ore.z) ore.y + 1 else 64
                for (y in column.supportY..maxOf(column.surfaceY, column.groundY) + 2) {
                    val position = CivPosition(column.x, y, column.z)
                    val isOre = protectedOre && position == ore
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
    preferOldTerrace: Boolean = false, shoulderConflicts: Boolean = false, continuation: Boolean = false,
    targetType: BuildingType = BuildingType.BUILDER_HUT, draftRefusal: Boolean = false, stalePaidIntent: Boolean = false,
    paidInfrastructure: Boolean = false,
    permuteUnpaidHouses: Boolean = false,
    action: (InitialRecoveryScenario) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open().use { paper -> InitialRecoveryScenario(paper, v2, halfWidth, legacyWidth, preferOldTerrace,
        shoulderConflicts, continuation, targetType, draftRefusal, stalePaidIntent, paidInfrastructure, permuteUnpaidHouses).use(action) }
}
