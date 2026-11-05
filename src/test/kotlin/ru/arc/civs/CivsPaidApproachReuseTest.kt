package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** The ordinary queue loads a fixed SITE, rechecks terrain, writes captures and publishes one city revision. */
class CivsPaidApproachReuseTest : FunSpec({
    test("an unfinished owner, a reserved wall port or another plot cannot supply the shortcut") {
        serviceScenario { h ->
            val s = paidApproachExample(h.city)
            val frame = CivsSettlementLayout.frame(s.city)
            val old = s.plan.routes.getValue(s.site.id.toString())
            val retained = CivAutonomousAccess.from(s.city, listOf(s.hutJournal))
            val lot = CivBox(CivPosition(0,61,2),CivPosition(0,72,2))
            CivsAutonomousTerraform.paidApproach(s.city,s.site,old,frame,s.surface,retained,listOf(lot),emptyList()) shouldBe null
            CivsAutonomousTerraform.paidApproach(s.city,s.site,old,frame,s.surface,
                retained.copy(reservedFortificationPorts = listOf(lot)),emptyList(),emptyList()) shouldBe null
            for (phase in listOf(BuildingPhase.SITE,BuildingPhase.CONSTRUCTING)) {
                val unfinished = s.city.copy(buildings = s.city.buildings.map {
                    if (it.id == s.hutJournal.building.id) it.copy(phase = phase) else it
                })
                CivsAutonomousTerraform.paidApproach(unfinished,s.site,old,frame,s.surface,retained,emptyList(),emptyList()) shouldBe null
            }
        }
    }

    for (state in listOf("old", "prepared-journal", "changed-grade", "fresh-capture-denied", "forecast-revised"))
    test("the unpaid SITE keeps its exact paid feeder and order publication boundary for $state") {
        serviceScenario { h ->
            val s = paidApproachExample(h.city)
            val grade = if (state == "changed-grade") {
                val key = civsTerrainKey(0, 2)
                s.surface.copy(columns = s.surface.columns + (key to CivTerrainSurfaceColumn(63, true)))
            } else s.surface
            val old = s.plan.routes.getValue(s.site.id.toString())
            val queued = if (state == "prepared-journal") s.short else old
            val journal = s.siteJournal.copy(terrain = s.siteJournal.terrain!!.copy(route = queued))
            val result = paidApproachQueue(h, s.city, journal, s.hutJournal, grade,
                denyCapture = state == "fresh-capture-denied", reviseBeforeSave = state == "forecast-revised")
            if (state == "fresh-capture-denied") {
                result.drafts.isNotEmpty() shouldBe true
                result.orders shouldBe emptyList()
                h.service.settlements().single().autonomy!!.plan shouldBe s.plan
                h.service.settlements().single().construction shouldBe null
                h.service.settlements().single().constructionQueue shouldBe listOf(s.site.id)
            } else if (state == "forecast-revised") {
                result.drafts.last().route shouldBe s.short
                result.orders.map { it.id }.toSet() shouldBe setOf(result.orderId, s.site.id)
                h.saves.none { it.first.construction?.id == result.orderId } shouldBe true
                val current = h.service.settlements().single()
                current.autonomy!!.plan shouldBe s.plan.copy(revision = s.plan.revision + 1)
                current.construction shouldBe null
                current.constructionQueue shouldBe listOf(s.site.id)
            } else {
                val expected = if (state == "changed-grade") old else s.short
                result.drafts.last().route shouldBe expected
                val save = h.saves.single { it.first.construction?.id == result.orderId }
                val after = save.first
                // The published city still owns the original queue/forecast until its single save completes.
                h.service.settlements().single().autonomy!!.plan shouldBe s.plan
                h.service.settlements().single().construction shouldBe null
                val desired = if (expected == old) s.plan else s.plan.copy(revision = s.plan.revision + 1,
                    routes = s.plan.routes + (s.site.id.toString() to expected))
                after.autonomy!!.plan shouldBe desired
                after.constructionQueue.orEmpty() shouldBe emptyList()
                after.construction!!.payment shouldBe CivConstructionPayment()
                result.orders.map { it.id }.toSet() shouldBe setOf(result.orderId, s.site.id)
                result.orders.all { it.terrain!!.route == expected } shouldBe true
                h.commit()
                h.service.settlements().single().autonomy!!.plan shouldBe desired
                h.service.settlements().single().construction!!.id shouldBe result.orderId
                result.orders.none { it.id == s.hutJournal.id } shouldBe true
            }
        }
    }
})

internal data class PaidQueueObservation(val orderId: UUID, val drafts: List<CivTerraformDraft>, val orders: List<CivPlacementPlan>)

internal fun paidApproachQueue(h: ServiceScenario, city: Settlement, sitePlan: CivPlacementPlan, hutPlan: CivPlacementPlan,
    surface: CivTerrainSurface, denyCapture: Boolean = false, reviseBeforeSave: Boolean = false): PaidQueueObservation {
    val site = requireNotNull(city.building(sitePlan.building.id))
    val orderId = UUID(214, 700)
    city.buildings.mapNotNull { it.control }.forEach { p ->
        h.world.loadChunk(p.x shr 4, p.z shr 4)
        h.world.getBlockAt(p.x, p.y, p.z).type = Material.BARREL
    }
    h.paper.addPlayer("GateRoadViewer").teleport(Location(h.world, city.center.x + .5, city.center.y.toDouble(), city.center.z + .5))
    val snapshot = mockk<ArcSchematicSnapshot>()
    val cell = sitePlan.blocks.first()
    every { snapshot.blocks() } returns listOf(ArcSchematicBlock(ArcSchematicPoint(
        cell.position.x - site.anchor.x, cell.position.y - site.anchor.y, cell.position.z - site.anchor.z), cell.after, false, false))
    every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
    every { h.store.loadPlan(hutPlan.id) } returns CompletableFuture.completedFuture(hutPlan)
    every { h.store.loadPlan(sitePlan.id) } returns CompletableFuture.completedFuture(sitePlan)
    every { h.store.loadSchematic(sitePlan.id) } returns CompletableFuture.completedFuture(byteArrayOf(1))
    every { h.blueprints.recover(sitePlan) } returns CompletableFuture.completedFuture(snapshot)
    every { h.worldAdapter.autonomousHeightField(any(), any(), any(), any()) } returns CompletableFuture.completedFuture(surface)
    val drafts = mutableListOf<CivTerraformDraft>()
    every { h.worldAdapter.captureTerraform(any(), any(), any(), any()) } answers {
        val draft = arg<CivTerraformDraft>(1)
        drafts += draft
        if (denyCapture) CompletableFuture.failedFuture(CivsRejection("protected-terrain"))
        else CompletableFuture.completedFuture(requireNotNull(sitePlan.terrain).copy(route = draft.route))
    }
    every { h.worldAdapter.autonomousRoute(any(), any(), any(), any(), any(), any()) } returns CompletableFuture.completedFuture(Unit)
    every { h.worldAdapter.capture(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
        CompletableFuture.completedFuture(sitePlan.copy(id = orderId, building = arg(3), terrain = arg(6)))
    }
    val saved = mutableListOf<CivPlacementPlan>()
    every { h.store.saveSchematic(any(), any()) } returns CompletableFuture.completedFuture(Unit)
    every { h.store.savePlan(any()) } answers {
        val value = firstArg<CivPlacementPlan>().also(saved::add)
        if (reviseBeforeSave && value.id == site.id) {
            // Simulate an independently published new forecast between prepared journal writes and city CAS.
            val state = requireNotNull(city.autonomy); val forecast = requireNotNull(state.plan)
            @Suppress("UNCHECKED_CAST")
            val records = CivsService::class.java.getDeclaredField("cities").also { it.isAccessible = true }
                .get(h.service) as MutableMap<UUID,Settlement>
            records[city.id] = city.copy(revision = city.revision + 1, autonomy = state.copy(plan = forecast.copy(revision = forecast.revision + 1)))
        }
        CompletableFuture.completedFuture(value)
    }
    h.start()
    h.tickUntil("an ordinary queued SITE reaches fresh native capture") { drafts.isNotEmpty() }
    if (!denyCapture && !reviseBeforeSave)
        h.tickUntil("the recaptured order reaches the durable city save") { h.saves.any { it.first.construction?.id == orderId } }
    else h.tick()
    return PaidQueueObservation(orderId, drafts, saved)
}

private data class PaidApproachExample(val city: Settlement, val plan: CivSettlementPlan, val site: CivBuilding,
    val siteJournal: CivPlacementPlan, val hutJournal: CivPlacementPlan, val short: List<CivPosition>, val surface: CivTerrainSurface)

private fun paidApproachExample(base: Settlement): PaidApproachExample {
    fun p(x: Int, z: Int, y: Int = 63) = CivPosition(x, y, z)
    fun box(x0: Int, z0: Int, x1: Int, z1: Int) = CivBox(p(x0, z0, 65), p(x1, z1, 65))
    fun building(type: BuildingType, id: Long, anchor: CivPosition, bounds: CivBox, rotation: Int, control: CivPosition) =
        CivBuilding(UUID(214, id), type, 1, anchor, rotation, CivsBlueprints.schematicId(base.style, type, 1),
            bounds, anchor.copy(y = anchor.y + 1), if (type == BuildingType.BUILDER_HUT) BuildingPhase.ACTIVE else BuildingPhase.SITE, control)
    val hall = building(BuildingType.TOWN_HALL, 1, p(0, 0, 64), CivBox(p(-8,-8,63),p(8,-1,72)), 0, p(1,-1,65))
    val hut = building(BuildingType.BUILDER_HUT, 2, p(-10,14,62), CivBox(p(-14,15,61),p(-7,23,70)), 180, p(-11,15))
    val forester = building(BuildingType.FORESTER, 3, p(25,8,62), CivBox(p(26,4,61),p(33,12,72)), 90, p(26,8))
    val prefix = listOf(hall.entrance, p(0,1,64), p(0,2), p(0,3), p(0,4))
    val paid = prefix + (5..14).map { p(0,it) } + (-1 downTo -10).map { p(it,14) }
    val oldPrefix = listOf(hall.entrance, p(-1,0,65), p(-2,0,64), p(-3,0), p(-4,0)) +
        (1..4).map { p(-4,it) } + (-3..0).map { p(it,4) }
    val suffix = (1..25).map { p(it,4) } + (5..8).map { p(25,it) }
    val old = oldPrefix + suffix
    val walls = listOf(box(-16,-11,10,-11), box(-16,-10,-16,25), box(10,-10,10,0), box(3,1,10,1),
        box(3,7,3,25), box(-15,25,2,25))
    val bs = listOf(hall,hut,forester)
    val plan = CivSettlementPlan(CivsSettlementPlans.VERSION, 7, 48,
        bs.map { CivSettlementPlot(it.copy(phase = BuildingPhase.SITE), it.bounds) },
        mapOf(hut.id.toString() to paid, forester.id.toString() to old), walls, listOf(box(1,2,5,6)), listOf(1))
    val city = CivsRules.validate(base.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
        buildings = bs, workers = listOf(base.workers.first().copy(assignment = hut.id, nextDeliveryAt = 1)),
        autonomy = CivAutonomy(layoutHalfWidth = 48, plan = plan), construction = null,
        constructionQueue = listOf(forester.id), pendingControls = emptyList()))
    fun journal(b: CivBuilding, route: List<CivPosition>): CivPlacementPlan {
        val bounds = CivBox(p(-16,-11,60),p(34,26,76))
        return CivPlacementPlan(b.id, city.id, city.worldId, b.copy(phase = BuildingPhase.CONSTRUCTING),
            listOf(CivPlanBlock(b.bounds.min, "minecraft:air", "minecraft:oak_planks")), "a".repeat(64),
            CivTerraformPlan(bounds, emptyList(), route, b.anchor.y, 0, 0))
    }
    val native = (paid + old).associate { civsTerrainKey(it.x,it.z) to it.y - 1 }
    val surface = CivTerrainSurface(-64,320,buildMap {
        for (x in -50..50) for (z in -50..50) {
            val key = civsTerrainKey(x,z)
            put(key,CivTerrainSurfaceColumn(native[key] ?: 62,true))
        }
    })
    return PaidApproachExample(city,plan,forester,journal(forester,old),journal(hut,paid),prefix+suffix,surface)
}
