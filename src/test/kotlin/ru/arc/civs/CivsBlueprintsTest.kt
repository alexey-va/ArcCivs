package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot

class CivsBlueprintsTest : FunSpec({
    test("catalog selects all 54 levels only from the private upgrade-aligned namespace") {
        val ids = CivsBlueprints.STYLES.flatMap { style ->
            BuildingType.entries.flatMap { type -> (1..3).map { CivsBlueprints.schematicId(style, type, it) } }
        }
        ids.size shouldBe 54
        ids.toSet().size shouldBe 54
        ids.all { it.startsWith("civs/minecolonies/") } shouldBe true
        CivsBlueprints.schematicId("nordic", BuildingType.TOWN_HALL, 1) shouldBe
            "civs/minecolonies/nordic/fundamentals/townhall1"
        CivsBlueprints.schematicId("caledonia", BuildingType.FARM, 3) shouldBe
            "civs/minecolonies/caledonia/agriculture/horticulture/farmer3"
        shouldThrow<IllegalArgumentException> { CivsBlueprints.schematicId("../nordic", BuildingType.HOUSE, 1) }
        shouldThrow<IllegalArgumentException> { CivsBlueprints.schematicId("nordic", BuildingType.HOUSE, 4) }
    }

    test("arrival uses the curated ground pivot without scanning doors or selecting a balcony") {
        // This fixture refuses all block reads: a doorless workshop and a hall
        // with an elevated door must both use the documented ground approach.
        val snapshot = BlueprintGeometrySnapshot(ArcSchematicPoint(16, 1, 26))
        CivsBlueprintGeometry.entrance(snapshot) shouldBe
            CivsBlueprintGeometry.Entry(ArcSchematicPoint(16, 2, 26), 2)
    }

    test("legacy technical origins and source-edge origins cannot pass as curated approaches") {
        for (origin in listOf(ArcSchematicPoint(18, 2, 7), ArcSchematicPoint(16, 1, 25),
            ArcSchematicPoint(16, 1, 27), ArcSchematicPoint(-1, 1, 26),
            ArcSchematicPoint(28, 1, 26), ArcSchematicPoint(16, -1, 26), ArcSchematicPoint(16, 26, 26))) {
            shouldThrow<CivsRejection> { CivsBlueprintGeometry.entrance(BlueprintGeometrySnapshot(origin)) }
        }
    }

    test("all four player headings preserve the chosen world ground and face the front correctly") {
        val wantedFronts = listOf(ArcSchematicPoint(0, 0, -1), ArcSchematicPoint(1, 0, 0),
            ArcSchematicPoint(0, 0, 1), ArcSchematicPoint(-1, 0, 0))
        for (groundY in listOf(0, 1, 3, 5)) for (facing in 0..3) {
            val snapshot = BlueprintGeometrySnapshot(ArcSchematicPoint(16, groundY, 26))
            val alignment = CivsBlueprintGeometry.alignment(snapshot, CivPosition(100, 64, -300), facing, null)
            alignment.anchor shouldBe CivPosition(100, 63, -300)
            alignment.quarterTurns shouldBe listOf(2, 3, 0, 1)[facing]
            CivsBlueprintGeometry.rotate(ArcSchematicPoint(0, 0, 1), alignment.quarterTurns) shouldBe wantedFronts[facing]
        }
    }

    test("upgrades retain persisted actual rotation regardless of the player's current heading") {
        val snapshot = BlueprintGeometrySnapshot(ArcSchematicPoint(16, 1, 26))
        for (rotation in listOf(0, 90, 180, 270)) for (facing in 0..3) {
            CivsBlueprintGeometry.alignment(snapshot, CivPosition(10, 70, 20), facing, rotation) shouldBe
                CivsBlueprintGeometry.Alignment(CivPosition(10, 69, 20), rotation / 90)
        }
        shouldThrow<IllegalArgumentException> {
            CivsBlueprintGeometry.alignment(snapshot, CivPosition(10, 70, 20), 0, 45)
        }
    }

    test("quarter turns match Core's origin-relative transform and preserve basement depth") {
        val point = ArcSchematicPoint(-16, -1, -26)
        val expected = listOf(point, ArcSchematicPoint(26, -1, -16),
            ArcSchematicPoint(16, -1, 26), ArcSchematicPoint(-26, -1, 16))
        for (turns in 0..3) {
            CivsBlueprintGeometry.rotate(point, turns) shouldBe expected[turns]
            CivsBlueprintGeometry.rotate(CivsBlueprintGeometry.rotate(point, turns), -turns) shouldBe point
        }
        CivsBlueprintGeometry.rotate(point, 4) shouldBe point
    }
})

private class BlueprintGeometrySnapshot(
    override val origin: ArcSchematicPoint,
) : ArcSchematicSnapshot {
    override val minimum = ArcSchematicPoint(0, 0, 0)
    override val maximum = ArcSchematicPoint(27, 27, 25)
    override val sizeX = 28
    override val sizeY = 28
    override val sizeZ = 26
    override val volume = 20_384L
    override val nonBarrierCount = 0
    override val hasBiomes = false
    override val entityCount = 0
    override val fileSha256 = "0".repeat(64)
    override val contentSha256 = "0".repeat(64)
    override fun blockAt(x: Int, y: Int, z: Int): ArcSchematicBlock = error("Entrance must use the curated pivot")
    override fun blocks(): List<ArcSchematicBlock> = error("Entrance must not infer a door from source blocks")
}
