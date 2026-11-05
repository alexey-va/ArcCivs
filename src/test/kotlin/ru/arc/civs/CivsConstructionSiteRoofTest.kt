package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.HeightMap
import org.bukkit.Material
import java.util.UUID

class CivsConstructionSiteRoofTest : FunSpec({
    test("an ordinary exterior column retains its supported entrance level") {
        residentRuntime { h ->
            val site = h.prepareRoofSite(roof = false)
            val floor = CivPosition(32, 69, 0)
            site.exteriorColumns.first() shouldBe floor
            h.world.getHighestBlockYAt(floor.x, floor.z, HeightMap.MOTION_BLOCKING_NO_LEAVES) shouldBe 68
            citizenConstructionStanding(h.city, h.world, site, floor) shouldBe true
            citizenConstructionTarget(h.city, h.world, site) shouldBe floor
        }
    }

    test("a roofed public column selects the usable entrance floor instead of its standable roof") {
        residentRuntime { h ->
            val site = h.prepareRoofSite()
            val floor = CivPosition(32, 69, 0)
            val roof = floor.copy(y = 74)
            h.world.getHighestBlockYAt(floor.x, floor.z, HeightMap.MOTION_BLOCKING_NO_LEAVES) shouldBe 73
            for (point in listOf(floor, roof)) {
                citizenConstructionStanding(h.city, h.world, site, point) shouldBe true
                citizenPacketSupported(h.world, point.point()) shouldBe true
                citizenPacketBodyClear(h.world, point.point(), false) shouldBe true
                citizenRoutePointAllowed(h.city, emptySet(), point) shouldBe true
            }
            citizenConstructionTarget(h.city, h.world, site) shouldBe floor
        }
    }

    test("a blocked entrance floor retains the existing highest supported fallback") {
        residentRuntime { h ->
            val site = h.prepareRoofSite()
            val floor = CivPosition(32, 69, 0)
            h.world.getBlockAt(floor.x, floor.y + 1, floor.z).type = Material.STONE
            citizenConstructionStanding(h.city, h.world, site, floor) shouldBe false
            citizenConstructionTarget(h.city, h.world, site) shouldBe floor.copy(y = 74)
        }
    }

    test("an existing builder replaces its cached roof when the entrance floor becomes usable without spending its order") {
        residentRuntime { h ->
            val site = h.prepareRoofSite()
            val floor = CivPosition(32, 69, 0)
            val roof = floor.copy(y = 74)
            val obstruction = h.world.getBlockAt(floor.x, floor.y + 1, floor.z)
            obstruction.type = Material.STONE
            h.holdNavigation = true
            h.navigationNow = 2_000
            val before = h.city
            val actor = h.owner.collisionActors().single()
            h.owner.pulse(2_000)
            h.builderWorkplace() shouldBe roof

            obstruction.type = Material.AIR
            citizenConstructionStanding(h.city, h.world, site, floor) shouldBe true
            citizenConstructionStanding(h.city, h.world, site, roof) shouldBe true
            h.navigationNow = 3_000
            h.owner.pulse(3_000)

            h.builderWorkplace() shouldBe floor
            (h.owner.collisionActors().single() === actor) shouldBe true
            h.city shouldBe before // Includes the same order, reserved batch, work credit and stock.
            h.deliveries shouldBe 0
            h.owner.constructionReady(h.city.id, site.orderId) shouldBe false
        }
    }
})

/** Public authored blocks on the existing test world; no private terrain or mocked standing decisions. */
private fun ResidentRuntime.prepareRoofSite(roof: Boolean = true): CivConstructionSite {
    val hut = assignFarm().copy(type = BuildingType.BUILDER_HUT, schematicId = "builder1", production = null)
    for (z in -1..1) world.loadChunk(3, z)
    val wall = hut.copy(id = UUID.randomUUID(), type = BuildingType.WALL, schematicId = "roof-site-wall",
        anchor = CivPosition(30, 68, 0), entrance = CivPosition(31, 69, 0), control = null,
        bounds = CivBox(CivPosition(30, 68, 0), CivPosition(30, 72, 2)), phase = BuildingPhase.CONSTRUCTING)
    val gate = wall.copy(id = UUID.randomUUID(), type = BuildingType.GATE, schematicId = "roof-site-gate",
        anchor = CivPosition(31, 68, -2), bounds = CivBox(CivPosition(31, 68, -2), CivPosition(35, 73, 2)),
        phase = BuildingPhase.ACTIVE, transit = (31..35).map { CivPosition(it, 69, 0) })
    constructionColumns = setOf(civsTerrainKey(31, 0))
    city = city.copy(buildings = listOf(city.buildings.first(), hut, gate, wall),
        construction = CivConstruction(UUID.randomUUID(), wall.id, 1, 14, 7, ConstructionPhase.PLACING,
            Stock(), plannedBuilding = wall, payment = CivConstructionPayment(reservedBatch = 7), workCredit = 7,
            blocksRequired = 21, blocksPlaced = 14, preparationWorkplace = CivPosition(30, 69, -1)))
    for (x in 29..35) for (z in -2..3) world.getBlockAt(x, 68, z).type = Material.STONE
    if (roof) for (x in 31..35) for (z in -2..2) world.getBlockAt(x, 73, z).type = Material.OAK_PLANKS
    return requireNotNull(constructionSite())
}

private fun ResidentRuntime.builderWorkplace(): CivPosition? {
    val entry = (owner.debug(city.id, workerId)["entries"] as List<*>).single() as Map<*, *>
    return (entry["plan"] as Map<*, *>)["workplace"] as CivPosition?
}
