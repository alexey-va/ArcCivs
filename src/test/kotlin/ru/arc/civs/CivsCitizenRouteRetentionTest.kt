package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsCitizenRouteRetentionTest : FunSpec({
    test("a courier leaves its observed farm field after the interception plan ends") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("courier-field-departure").also(paper.server::addWorld)
            // Test-city farm and courier pose from the 2026-10-28 13:27:49Z no-path event.
            // The flat dirt path models the clear ground; it is not a native world export.
            val farm = spatialBuilding("caledonia", "farmer", 1, CivPosition(-1813, 63, -2735), 90).copy(
                bounds = CivBox(CivPosition(-1812, 63, -2741), CivPosition(-1800, 75, -2729)))
            val warehouse = CivBuilding(UUID.randomUUID(), BuildingType.WAREHOUSE, 1,
                CivPosition(-1896, 63, -2774), 270, "fixture/warehouse",
                CivBox(CivPosition(-1915, 63, -2784), CivPosition(-1897, 80, -2766)),
                CivPosition(-1896, 64, -2774), BuildingPhase.ACTIVE)
            val unrelated = farm.copy(id = UUID.randomUUID(), anchor = farm.anchor.copy(z = -2710),
                bounds = CivBox(CivPosition(-1812, 63, -2716), CivPosition(-1800, 75, -2704)))
            val worker = CivWorker(UUID.randomUUID(), "Courier", warehouse.id)
            val city = Settlement(UUID.randomUUID(), UUID.randomUUID(), "Test", world.uid,
                CivPosition(-1868, 64, -2759), 96, "caledonia", Stock(), listOf(farm, warehouse, unrelated), listOf(worker),
                autonomy = CivAutonomy())
            val plan = requireNotNull(citizenPlan(city, worker))
            val origin = Location(world, -1805.2177848797216, 63.9375, -2723.2177848797214)
            val from = CivPosition(origin.blockX, origin.blockY, origin.blockZ)
            farm.bounds.contains(from.x, from.y, from.z) shouldBe false
            CivsWorksiteLayouts.isField(farm, from.x, from.z) shouldBe true
            val allowed = citizenRouteBuildings(city, plan, warehouse.entrance, from)
            (farm.id in allowed) shouldBe true
            (unrelated.id in allowed) shouldBe false
            val otherField = CivsWorksiteLayouts.field(unrelated).first()
            citizenRoutePointAllowed(city, allowed, otherField.copy(y = otherField.y + 1)) shouldBe false
            citizenRoutePointAllowed(city.copy(radius = 1), allowed, from) shouldBe false

            for (x in -114..-112) for (z in -171..-170) world.loadChunk(x, z)
            for (x in -1815..-1800) for (z in -2729..-2720) world.getBlockAt(x, 63, z).type = Material.DIRT_PATH
            val target = Location(world, -1813.5, 63.9375, -2723.5)
            val route = CitizenPacketRoute(world, origin, target,
                { citizenRoutePointAllowed(city, allowed, it) && citizenControlClear(city, it) },
                { first, second -> citizenControlStepClear(city, first, second) }, { 1.0 })
            var operations = 0
            while (route.searching && operations++ < 20_000) route.expand()
            route.searching shouldBe false
            route.failure shouldBe null
            var at = origin
            repeat(120) { route.move(at, .8) { true }?.let { at = it } }
            route.failure shouldBe null
            route.finished shouldBe true
            at.distanceSquared(target) shouldBe 0.0
        }
    }

    for (invalidation in listOf("source-building-moved", "source-removed", "cargo-missing"))
    test("a distant courier keeps its building route while its producer moves until $invalidation") {
        residentRuntime { h ->
            val (sourceBuilding, courierId) = h.courierSource("WHEAT")
            h.idleReason = "searching-work"
            val source = h.owner.collisionActors().single()
            source.teleport(Location(h.world, 22.5, 64.0, .5))
            val warehouse = h.city.buildings.last()
            h.city = h.city.copy(buildings = h.city.buildings.map { if (it.id == warehouse.id) it.copy(
                anchor = CivPosition(-40, 64, 0), entrance = CivPosition(-40, 64, 0), control = CivPosition(-40, 64, 0),
                bounds = CivBox(CivPosition(-42, 64, -2), CivPosition(-38, 68, 2))) else it })
            for (x in -4..4) for (z in -1..1) h.world.loadChunk(x, z)
            h.world.getBlockAt(-40, 64, 0).type = Material.BARREL
            h.owner.pulse(2_000)
            var stored = 64L
            h.courierProvider = { city, worker -> if (worker.id != courierId) null else
                CivsLogistics.plan(city, worker) { id -> if (id == sourceBuilding.id) mapOf("WHEAT" to stored) else emptyMap() } }
            val accepted = requireNotNull(h.courierProvider?.invoke(h.city, h.city.workers.last()))
            accepted.cargo.sourceWorkerId shouldBe null
            h.holdNavigation = true
            h.navigationNow = 3_000
            h.owner.pulse(3_000)
            val first = requireNotNull(h.routeIdentity(courierId)) { h.owner.debug(h.city.id, courierId).toString() }
            val motion = requireNotNull(h.routeMotion(courierId))
            val target = h.routeTarget(courierId)
            for (second in 4L..8L) {
                source.teleport(Location(h.world, if (second % 2 == 0L) 26.5 else 22.5, 64.0, .5))
                h.owner.pulse(second * 1_000)
                h.routeIdentity(courierId) shouldBe first
                motion.searching shouldBe true
                h.routeTarget(courierId) shouldBe target
            }
            h.navigationNow = 8_000
            h.releaseNavigation()
            for (tick in 1..400) {
                h.navigate()
                if (tick % 20 == 0) {
                    source.teleport(Location(h.world, if (tick % 40 == 0) 26.5 else 22.5, 64.0, .5))
                    h.owner.pulse(requireNotNull(h.navigationNow))
                }
                withClue(h.navigationState(courierId)) { h.routeIdentity(courierId) shouldBe first }
                if (motion.forwardProgress >= 3.0) break
            }
            withClue(h.navigationState(courierId)) { (motion.forwardProgress >= 3.0) shouldBe true }
            h.pickups shouldBe 0
            h.city.workers.last().cargo shouldBe null
            CivsEquipment.plainCount(h.city.workers.first(), "WHEAT") shouldBe 64
            // Retaining an approach never extends its destination area or source/cargo authority.
            when (invalidation) {
                "source-building-moved" -> h.city = h.city.copy(buildings = h.city.buildings.map {
                    if (it.id == sourceBuilding.id) it.copy(control = CivPosition(45, 64, 0)) else it
                })
                "source-removed" -> h.city = h.city.copy(buildings = h.city.buildings.filterNot { it.id == sourceBuilding.id },
                    workers = h.city.workers.map { if (it.assignment == sourceBuilding.id) it.copy(assignment = null) else it })
                else -> stored = 0L
            }
            h.owner.pulse(requireNotNull(h.navigationNow) + 1_000)
            (h.routeIdentity(courierId) === first) shouldBe false
            motion.preparationPhase shouldBe "cancelled"
            h.pickups shouldBe 0
            h.city.workers.last().cargo shouldBe null
        }
    }
})
