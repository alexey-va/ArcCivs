package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsSettlementFortificationsTest : FunSpec({
    val template by lazy { civsSyntheticFortification() }
    val base by lazy { settlementPlanCity().let { city -> city.copy(radius = 128, style = CivsFortifications.STYLE,
        buildings = city.buildings.map { it.copy(schematicId = "civs/v2/fixture/town-hall/level1") }) } }
    val templates by lazy { settlementPlanTemplates().mapValues { (type, pair) ->
        pair.first.copy(schematicId = "civs/v2/fixture/$type/level1") to
            pair.second.copy(schematicId = "civs/v2/fixture/$type/level${type.maxLevel}")
    } }
    val surface by lazy { settlementPlanSurface(base) }

    test("only an active matching captured payload supplies runtime fortification evidence") {
        val plot = fortificationPlot(template, CivPosition(50, 64, -35), 0, UUID(141, 400))
        val city = fortificationPlanCity(base, listOf(plot)).let { it.copy(buildings = it.buildings + plot.building.copy(phase = BuildingPhase.ACTIVE)) }
        val captured = CivPlacementPlan(plot.building.id, city.id, city.worldId,
            plot.building.copy(phase = BuildingPhase.CONSTRUCTING), template.cells.filterNot { it.isBarrier }.map { cell ->
                val p = cell.position; val a = plot.building.anchor
                CivPlanBlock(CivPosition(a.x + p.x, a.y + p.y, a.z + p.z), "minecraft:air", cell.blockData)
            }, capturedSha256 = template.sourceSha256)
        CivAutonomousAccess.from(city, listOf(captured)).acceptedFortifications shouldBe setOf(plot.building.id)
        for (wrong in listOf(captured.copy(capturedSha256 = "0".repeat(64)), captured.copy(id = UUID(141, 401)),
            captured.copy(cityId = UUID(141, 402)), captured.copy(worldId = UUID(141, 403)),
            captured.copy(building = captured.building.copy(anchor = captured.building.anchor.copy(x = 51))),
            captured.copy(building = captured.building.copy(control = captured.building.anchor)))) {
            CivAutonomousAccess.from(city, listOf(wrong)).acceptedFortifications shouldBe emptySet()
        }
        val site = city.copy(buildings = city.buildings.map { if (it.id == plot.building.id) it.copy(phase = BuildingPhase.SITE) else it })
        CivAutonomousAccess.from(site, listOf(captured)).acceptedFortifications shouldBe emptySet()
        CivAutonomousAccess.from(site, listOf(captured)).reservedFortificationPorts.size shouldBe 2
        val forged = plot.copy(fortification = plot.fortification!!.copy(recipeSha256 = "0".repeat(64)))
        val oldPlan = city.autonomy!!.plan!!
        val invalid = city.copy(autonomy = city.autonomy.copy(plan = oldPlan.copy(plots =
            oldPlan.plots.map { if (it.building.id == plot.building.id) forged else it })))
        CivAutonomousAccess.from(invalid, listOf(captured)).acceptedFortifications shouldBe emptySet()
    }

    test("detached fortification proof is immutable and remains bound to canonical geometry after restart") {
        val plot = fortificationPlot(template, CivPosition(40, 64, -35), 90, UUID(141, 410))
        val city = fortificationPlanCity(base, listOf(plot))
        val saved = Gson().fromJson(Gson().toJson(city), Settlement::class.java)
        CivsSettlementPlans.current(saved) shouldBe city.autonomy!!.plan
        val detached = requireNotNull(CivsSettlementPlans.detached(saved.autonomy!!.plan)).plots.last()
        CivsSettlementPlans.matchesFortification(detached, template) shouldBe true
        shouldThrow<UnsupportedOperationException> { (detached.fortification!!.proof.groundBarrier as MutableSet<CivPosition>).clear() }
        shouldThrow<UnsupportedOperationException> { (detached.fortification!!.proof.wallWalkFloors as MutableSet<CivPosition>).clear() }
        shouldThrow<UnsupportedOperationException> { (detached.fortification!!.proof.wallEndPorts.first().floor as MutableList<CivPosition>).clear() }
        CivsSettlementPlans.matchesFortification(detached.copy(building = detached.building.copy(entrance = base.center)), template) shouldBe false
        CivsSettlementPlans.matchesFortification(detached.copy(fortification = detached.fortification!!.copy(sourceSha256 = "0".repeat(64))), template) shouldBe false
    }

    test("optional bastions wait for functional goals and cannot make an unfinished mask eligible for infrastructure") {
        val ordinary = CivsAutonomy.completeProfile.mapIndexed { index, type ->
            val x = index % 6 * 12 - 32; val z = index / 6 * 12 - 32
            CivsSettlementPlans.at(templates.getValue(type).second, CivPosition(x, 64, z), 0,
                UUID(141, 1_000 + index.toLong())).copy(phase = BuildingPhase.ACTIVE)
        }
        val plot = fortificationPlot(template, CivPosition(67, 64, -45), 0, UUID(141, 420))
        val city = fortificationPlanCity(base.copy(buildings = ordinary, research = CivResearchState(CivResearchId.entries.toSet())), listOf(plot))
        val forester = ordinary.first { it.type == BuildingType.FORESTER }.copy(level = 2)
        val unfinished = city.copy(buildings = ordinary.map { if (it.id == forester.id) forester else it })
        CivsAutonomy.nextGoal(unfinished)?.let { it.type to it.buildingId } shouldBe (BuildingType.FORESTER to forester.id)
        CivsAutonomy.infrastructureDue(unfinished, CivsAutonomy.nextGoal(unfinished)) shouldBe false
        CivsAutonomy.nextGoal(city)?.let { it.type to it.buildingId } shouldBe (BuildingType.DECORATION to null)
        CivsAutonomy.infrastructureDue(city, CivsAutonomy.nextGoal(city)) shouldBe false
        val queued = CivsRules.enqueue(CivsRules.placeSite(city, city.owner, plot.building), city.owner, plot.building.id)
        queued.constructionQueue shouldBe listOf(plot.building.id)
        CivsAutonomy.pendingFortifications(queued).map { it.building.id } shouldBe listOf(plot.building.id)
        val active = city.copy(buildings = ordinary + plot.building.copy(phase = BuildingPhase.ACTIVE))
        CivsAutonomy.nextGoal(active) shouldBe null
        CivsAutonomy.infrastructureDue(active, null) shouldBe true
        city.buildings shouldBe ordinary
        city.stock shouldBe base.stock
    }

    test("a new detached plan reserves at most four native bastions with disjoint paid walls and exact clear port seams") {
        val plan = fortificationPlanDiagnostics { CivsSettlementPlans.prepare(base, templates, surface, CivAutonomousAccess(), fortification = template,
            inspect = layoutFixtureDiagnostics("fortified", base, templates, surface)) }
        val forts = plan.plots.filter { it.fortification != null }
        plan.plots.count { it.building.type != BuildingType.DECORATION } shouldBe 34
        (forts.size in 1..4) shouldBe true
        forts.all { CivsSettlementPlans.matchesFortification(it, template) } shouldBe true
        val projected = base.copy(autonomy = base.autonomy!!.copy(plan = plan, layoutHalfWidth = plan.halfWidth),
            buildings = plan.plots.map { it.building.copy(bounds = it.envelope) })
        val frame = CivsSettlementLayout.frame(projected)
        val routes = plan.routes.values.toList()
        val ids = forts.mapTo(hashSetOf()) { it.building.id }
        val reservedPorts = forts.flatMap { CivsSettlementPlans.portReservations(it.fortification!!.proof) }
        routes.flatMap { CivsAutonomousTerraform.roadProfile(it).values }.none { point ->
            reservedPorts.any { CivsAutonomousGeometry.horizontalContains(it, point) }
        } shouldBe true
        val pending = CivsSettlementLayout.infrastructure(projected, frame, surface, routes)
        pending.perimeterClosed shouldBe false
        pending.rejected.keys shouldBe setOf("fortification-not-active")
        val forecast = CivsSettlementLayout.infrastructure(projected, frame, surface, routes, forecastFortifications = ids)
        forecast.rejected shouldBe emptyMap()
        forecast.perimeterClosed shouldBe true
        (forecast.pieces.size <= 128) shouldBe true
        forecast.pieces.all { piece -> forts.none { CivsAutonomousGeometry.overlaps(piece.bounds, it.envelope) } } shouldBe true
        forecast.pieces.withIndex().all { (index, a) -> forecast.pieces.drop(index + 1).none {
            CivsAutonomousGeometry.overlaps(a.bounds, it.bounds)
        } } shouldBe true
        val states = forecast.pieces.flatMap { it.blocks }.associate { it.position to it.after }
        for (fort in forts) for (port in fort.fortification!!.proof.wallEndPorts) for (point in port.ground) {
            val outside = point.copy(x = point.x + port.outward.x, z = point.z + port.outward.z)
            (states[outside.copy(y = outside.y + 7)]?.substringBefore('[') in
                setOf("minecraft:stone_bricks", "minecraft:dark_oak_log", "minecraft:cobblestone")) shouldBe true
            states[outside.copy(y = outside.y + 8)] shouldBe "minecraft:air"
            states[outside.copy(y = outside.y + 9)] shouldBe "minecraft:air"
        }
        val proofs = forts.map { it.fortification!!.proof }
        CivsSettlementLayout.fortificationWalkConnected(forecast.pieces, proofs) shouldBe true
        proofs.flatMap { it.wallWalkFloors }.none { it.x == 0 } shouldBe true
        forecast.pieces.any { piece -> piece.blocks.any { it.position.x == 0 } } shouldBe true
        val separated = forecast.pieces.map { it.copy(blocks = it.blocks.filterNot { cell -> cell.position.x == 0 }) }
        CivsSettlementLayout.fortificationWalkConnected(separated, proofs) shouldBe false
        val active = projected.copy(buildings = projected.buildings.map { if (it.id in ids) it.copy(phase = BuildingPhase.ACTIVE) else it })
        CivsSettlementLayout.infrastructure(active, frame, surface, routes).perimeterClosed shouldBe false
        val accepted = CivsSettlementLayout.infrastructure(active, frame, surface, routes, acceptedFortifications = ids)
        accepted.rejected shouldBe emptyMap()
        accepted.perimeterClosed shouldBe true
    }

    test("unusable bastion corners fall back to the functional plan and a retained legacy plan never gains new bastions") {
        val blocked = surface.copy(columns = surface.columns.mapValues { (key, value) ->
            val x = (key shr 32).toInt(); val z = key.toInt()
            if (kotlin.math.abs(x) in 38..72 && kotlin.math.abs(z) in 38..72) value.copy(natural = false) else value
        })
        val plan = fortificationPlanDiagnostics { CivsSettlementPlans.prepare(base, templates, blocked, CivAutonomousAccess(), fortification = template,
            inspect = layoutFixtureDiagnostics("fortified-blocked", base, templates, blocked)) }
        plan.plots.size shouldBe 34
        plan.plots.none { it.fortification != null } shouldBe true
        val legacy = fortificationPlanDiagnostics { CivsSettlementPlans.prepare(base, templates, surface, CivAutonomousAccess()) }
        val saved = base.copy(autonomy = base.autonomy!!.copy(plan = legacy, layoutHalfWidth = legacy.halfWidth))
        val refreshed = fortificationPlanDiagnostics { CivsSettlementPlans.prepare(saved, templates, surface, CivAutonomousAccess(), fortification = template) }
        refreshed.plots shouldBe legacy.plots
        refreshed.routes shouldBe legacy.routes
        refreshed.walls shouldBe legacy.walls
        refreshed.gates shouldBe legacy.gates
        refreshed.plots.none { it.fortification != null } shouldBe true
        val oldFrame = CivsSettlementLayout.frame(saved)
        val old = CivsSettlementLayout.infrastructure(saved.copy(buildings = legacy.plots.map { it.building.copy(bounds = it.envelope) }),
            oldFrame, surface, legacy.routes.values.toList())
        old.pieces.filter { it.type == BuildingType.WALL }.flatMap { it.blocks }.all { it.position.y <= 68 } shouldBe true
    }
})

private inline fun fortificationPlanDiagnostics(action: () -> CivSettlementPlan): CivSettlementPlan = try { action() }
    catch (failure: CivsRejection) { throw AssertionError("${failure.reason}: ${failure.values}", failure) }

private fun fortificationPlot(template: CivFortificationTemplate, anchor: CivPosition, rotation: Int, id: UUID): CivSettlementPlot {
    val building = CivsSettlementPlans.at(template.building, anchor, rotation, id).copy(phase = BuildingPhase.SITE)
    return CivSettlementPlot(building, building.bounds, CivSettlementFortification(template.sourceSha256,
        template.recipeSha256, template.proofAt(anchor, rotation)))
}

private fun fortificationPlanCity(base: Settlement, forts: List<CivSettlementPlot>): Settlement {
    val frame = CivsSettlementLayout.frame(base)
    val plan = CivSettlementPlan(CivsSettlementPlans.VERSION, 0, frame.halfWidth,
        base.buildings.filterNot { it.type.isInfrastructure }.map { CivSettlementPlot(it, it.bounds) } + forts,
        emptyMap(), frame.wallObstacles, frame.gates.map { it.passage }, frame.gates.map { it.side })
    return base.copy(autonomy = base.autonomy!!.copy(plan = plan, layoutHalfWidth = plan.halfWidth))
}
