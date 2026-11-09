package ru.arc.civs

import com.google.gson.JsonParser
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsWorksiteEnvelopeTest : FunSpec({
    test("retained farm envelopes match the exact soil and water across styles, levels, and rotations") {
        val families = listOf(
            "civs/v2/nordic/agriculture-horticulture-farmer/c0ad265fc22f522f22f9",
            "civs/v2/nordic/agriculture-horticulture-farmer/660de08515ed8f6e5b27",
            "civs/v2/medievaloak/agriculture-horticulture-altfarmer/b2847c45d9139927e715",
        )
        for (family in families) for (level in 1..3) for (rotation in listOf(0, 90, 180, 270)) {
            val farm = envelopeFarm().copy(level = level, schematicId = "$family/level$level",
                anchor = CivPosition(-43, 83, 29), rotation = rotation)
            CivsWorksiteLayouts.field(farm).size shouldBe 120
            CivsWorksiteLayouts.irrigation(farm).size shouldBe 15
            CivsAutonomousGeometry.futureBounds(envelopeCity(farm), farm) shouldBe pointwiseEnvelope(farm.bounds, farm)
        }
    }

    test("authored sparse fields and legacy maximum level footprints retain their exact envelope") {
        val authoredId = "civs/v2/test/farm/level1"
        val legacyId = CivsBlueprints.schematicId("nordic", BuildingType.FARM, 1)
        val legacyTargetId = CivsBlueprints.schematicId("nordic", BuildingType.FARM, 3)
        val authoredSite = JsonParser.parseString("""{"farm":{"soilCells":[[2,1,8],[4,-2,3]],
            "irrigationCells":[[13,4,-8]]}}""").asJsonObject
        val plannedSite = JsonParser.parseString("""{"schematicBounds":{"min":[-6,-1,-6],"max":[5,12,5]},
            "farm":{"soilCells":[[6,0,5]],"irrigationCells":[[-9,2,-7]]}}""").asJsonObject
        CivsWorksiteLayouts.installCatalog(mapOf(authoredId to authoredSite, legacyTargetId to plannedSite))
        try {
            for (rotation in listOf(0, 90, 180, 270)) {
                val farm = envelopeFarm().copy(rotation = rotation, schematicId = authoredId, farmFieldVersion = null)
                val city = envelopeCity(farm)
                CivsAutonomousGeometry.futureBounds(city, farm) shouldBe pointwiseEnvelope(farm.bounds, farm)
                val water = CivsWorksiteLayouts.irrigation(farm).single()
                CivsWorksiteLayouts.isField(farm, water.x, water.z) shouldBe true

                val legacy = farm.copy(schematicId = legacyId)
                val target = legacy.copy(schematicId = legacyTargetId)
                val projectedBuilding = listOf(CivPosition(-6, -1, -6), CivPosition(5, 12, 5))
                    .map { CivsWorksiteLayouts.point(target, it) }
                    .fold(legacy.bounds) { box, p -> CivsAutonomousGeometry.union(box, CivBox(p, p)) }
                CivsAutonomousGeometry.futureBounds(city, legacy) shouldBe pointwiseEnvelope(projectedBuilding, target)
            }
        } finally {
            CivsWorksiteLayouts.installCatalog(emptyMap())
        }
    }

    test("road discovery computes a protected farm envelope once for all four viable stances") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("farm-envelope-road").also(paper.server::addWorld)
            for (x in 0..1) for (z in -1..0) world.loadChunk(x, z)
            val farm = envelopeFarm()
            val center = CivPosition(15, 63, -14)
            val worker = CivWorker(UUID(0, 9), "Builder", farm.id)
            val city = envelopeCity(farm, world.uid).copy(workers = listOf(worker))
            val road = farm.copy(id = UUID(0, 10), type = BuildingType.ROAD, farmFieldVersion = null)
            val upkeep = CivRoadUpkeep(city.id, world.uid, city.owner, road.id, road, center,
                "minecraft:cobblestone", null, emptyList())
            val native = CivsRoadWorld(paper.createSimplePlugin("FarmEnvelope"), { listOf(city) },
                { _, _, _ -> true }, { false })
            world.getBlockAt(center.x, center.y, center.z).type = Material.GRASS_BLOCK
            val stances = listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1).map { (dx, dz) ->
                CivPosition(center.x + dx, center.y + 1, center.z + dz)
            }
            stances.all { citizenStandable(world, it) && citizenPacketBodyClear(world, it.point(), false) } shouldBe true

            var envelopes = 0
            var transforms = 0
            mockkObject(CivsAutonomousGeometry, CivsWorksiteLayouts)
            try {
                every { CivsAutonomousGeometry.futureBounds(city, farm) } answers { envelopes++; callOriginal() }
                every { CivsWorksiteLayouts.point(farm, any()) } answers { transforms++; callOriginal() }
                native.captureRoadUpkeep(city, worker, upkeep) shouldBe null
                withClue("road stance discovery used $envelopes farm envelopes and $transforms transforms") {
                    envelopes shouldBe 1
                    transforms shouldBe 2
                }
            } finally {
                unmockkObject(CivsAutonomousGeometry, CivsWorksiteLayouts)
            }
        }
    }
})

private fun envelopeFarm(): CivBuilding {
    val anchor = CivPosition(0, 63, 0)
    return CivBuilding(UUID(0, 3), BuildingType.FARM, 1, anchor, 0,
        "civs/v2/nordic/agriculture-horticulture-farmer/c0ad265fc22f522f22f9/level1",
        CivBox(anchor, anchor.copy(y = 71)), anchor.copy(y = 64), BuildingPhase.ACTIVE,
        farmFieldVersion = CivsFarmFields.VERSION)
}

private fun envelopeCity(farm: CivBuilding, worldId: UUID = UUID(0, 5)) =
    Settlement(UUID(0, 1), UUID(0, 2), "Farm Envelope", worldId, farm.anchor, 48, "nordic", Stock(),
        listOf(farm), emptyList())

private fun pointwiseEnvelope(bounds: CivBox, farm: CivBuilding) =
    (CivsWorksiteLayouts.field(farm) + CivsWorksiteLayouts.irrigation(farm)).fold(bounds) { box, point ->
        CivsAutonomousGeometry.union(box, CivBox(point, point))
    }
