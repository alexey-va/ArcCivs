package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.UUID

class CivsPreparedRoadFinishTest : FunSpec({
    test("one observed paid approach center uses the existing builder and charge before development") {
        val h = PreparedRoadFinishFixture()
        h.observe()
        h.maintenance.requirements(h.city) shouldBe emptyMap()
        h.maintenance.admitDevelopment(h.city) shouldBe false

        h.tick++
        val work = requireNotNull(h.maintenance.plan(h.city, h.worker))
        work.upkeep shouldBe h.target
        work.inputs shouldBe mapOf("COBBLESTONE" to 1L)
        work.changes.single().before shouldBe "minecraft:coarse_dirt"
        work.changes.single().after shouldBe "minecraft:dirt_path"
        val after = CivsRoadMaintenance.complete(h.city, work, 3_000)
        CivRoadUpkeepReceipt(h.worker, h.city.stock, work, 3_000).validated(after)
        CivsEquipment.usableCount(after.workers.single(), "COBBLESTONE") shouldBe 63
        h.maintenance.upkeepRetired(after, h.target)

        // A second prepared center remains available, but this order gets its turn.
        h.tick++
        h.maintenance.backfillSurfaces(after)
        h.maintenance.plan(after, after.workers.single()) shouldBe null
        h.maintenance.admitDevelopment(after) shouldBe true
    }

    test("the prepared-center priority expires or releases when its exact native before-image changes") {
        val stale = PreparedRoadFinishFixture()
        stale.observe()
        stale.maintenance.admitDevelopment(stale.city) shouldBe false
        stale.tick++
        stale.before = "minecraft:dirt"
        stale.maintenance.plan(stale.city, stale.worker) shouldBe null
        stale.maintenance.admitDevelopment(stale.city) shouldBe true

        val waiting = PreparedRoadFinishFixture()
        waiting.observe()
        waiting.maintenance.admitDevelopment(waiting.city) shouldBe false
        waiting.tick++
        requireNotNull(waiting.maintenance.plan(waiting.city, waiting.worker))
        waiting.tick = 600
        waiting.maintenance.admitDevelopment(waiting.city) shouldBe true
        waiting.maintenance.requirements(waiting.city) shouldBe emptyMap()
        waiting.maintenance.plan(waiting.city, waiting.worker) shouldBe null
    }

    test("a shoulder, ordinary dirt, missing builder funds or active placement creates no priority") {
        val shoulder = PreparedRoadFinishFixture { it.x == 1 }
        shoulder.observe()
        shoulder.maintenance.admitDevelopment(shoulder.city) shouldBe true

        val wear = PreparedRoadFinishFixture()
        wear.before = "minecraft:dirt"
        wear.observe()
        wear.maintenance.admitDevelopment(wear.city) shouldBe true

        val unfunded = PreparedRoadFinishFixture()
        val empty = unfunded.city.copy(workers = listOf(unfunded.worker.copy(items = emptyList())))
        unfunded.maintenance.backfillSurfaces(empty)
        unfunded.maintenance.admitDevelopment(empty) shouldBe true

        val working = PreparedRoadFinishFixture()
        working.observe()
        val placing = working.city.copy(construction = CivConstruction(UUID(0, 8),
            working.target.source.id, 2, 1, 1, ConstructionPhase.PLACING, Stock()))
        working.maintenance.admitDevelopment(placing) shouldBe true
        working.maintenance.plan(placing, working.worker) shouldBe null
    }
})

private class PreparedRoadFinishFixture(include: (CivPosition) -> Boolean = { it.x == 0 }) {
    private val base = roadAdmissionCity()
    private val route = (0..4).map { z -> CivPosition(0, 64, z) }
    private val site = base.buildings.last().copy(type = BuildingType.HOUSE, transit = null, entrance = route.last(),
        anchor = CivPosition(0, 63, 5), bounds = CivBox(CivPosition(0, 63, 5), CivPosition(3, 69, 8)))
    val city = base.copy(buildings = base.buildings.dropLast(1) + site)
    val worker = city.workers.single()
    private val placement = CivPlacementPlan(site.id, city.id, city.worldId, site, emptyList(), terrain = CivTerraformPlan(
        CivBox(CivPosition(-1, 63, -1), CivPosition(3, 69, 8)), emptyList(), route, 63, 0, 0))
    private val network = CivsRoadMaintenance.prepare(city, listOf(placement),
        restrictTo = setOf(civsTerrainKey(0, 1), civsTerrainKey(0, 2), civsTerrainKey(1, 1)), proposeCuts = false)
        .let { network -> network.copy(targets = network.targets.filter { include(it.floor) }) }
    val target = network.targets.first()
    var tick = 0
    var before = "minecraft:coarse_dirt"
    private val native = mockk<CivsWorld>()
    val maintenance = CivsRoadMaintenance(native, registerSurface = { _, _ -> }) { tick }

    init {
        every { native.captureRoadUpkeep(any(), any(), any(), any()) } answers {
            val target = thirdArg<CivRoadUpkeep>()
            roadAdmissionWork(firstArg(), target).copy(changes = listOf(CivPlanBlock(target.floor, before, target.after)))
        }
        maintenance.publish(city, network)
    }

    fun observe() = maintenance.backfillSurfaces(city)
}
