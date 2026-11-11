package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.util.UUID

class CivsDeferredExteriorGrowthTest : FunSpec({
    test("a paid quarry crossing can keep the deferred outline while admitting the next farm") {
        val legacy = legacyGrowthCity()
        val complete = requireNotNull(CivsSettlementPlans.current(legacy))
        val types = listOf(BuildingType.TOWN_HALL, BuildingType.BUILDER_HUT, BuildingType.FORESTER, BuildingType.QUARRY)
        val plots = types.map { type -> complete.plots.first { it.building.type == type } }
        val quarry = plots.last().building
        val routes = complete.routes.filterKeys { id -> plots.any { it.building.id.toString() == id } }
        val prior = complete.copy(revision = 3, plots = plots, routes = routes,
            deferredPerimeter = CivDeferredPerimeter("infrastructure", quarry.id, quarry.anchor))
        val city = legacy.copy(buildings = plots.map { it.building.copy(phase = BuildingPhase.ACTIVE) },
            autonomy = requireNotNull(legacy.autonomy).copy(plan = prior))
        val access = CivAutonomousAccess(routes.mapKeys { UUID.fromString(it.key) })
        val surface = settlementPlanSurface(city)
        val profile = CivsSettlementGrowth.profile(city)
        profile shouldBe types + BuildingType.FARM
        val crossing = access.routes.getValue(quarry.id)[10]
        val rejection = CivsRejection("autonomy-layout-obstructed", mapOf("stage" to "gate-junction",
            "first" to "${crossing.x},${crossing.z}", "retained_crossing" to "true", "fully_loaded" to "true"))
        mockkObject(CivsSettlementPlans)
        try {
            every { CivsSettlementPlans.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws rejection
            val pending = CivsSettlementPlans.prepareGrowth(city, settlementPlanTemplates(), surface, access, profile)
            val farm = pending.plots.single { it.building.type == BuildingType.FARM }
            val approach = pending.routes.getValue(farm.building.id.toString())
            pending shouldBe prior.copy(revision = 4, plots = plots + farm,
                routes = routes + (farm.building.id.toString() to approach),
                deferredPerimeter = CivDeferredPerimeter("infrastructure", farm.building.id, farm.building.anchor))
            approach.first() shouldBe city.buildings.first().entrance
            approach.last() shouldBe farm.building.entrance
            val adopted = city.copy(autonomy = requireNotNull(city.autonomy).copy(plan = pending))
            CivsSettlementPlans.next(adopted, BuildingType.FARM) shouldBe farm
        } finally {
            unmockkObject(CivsSettlementPlans)
        }
    }
})
