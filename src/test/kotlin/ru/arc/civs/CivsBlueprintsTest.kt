package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import ru.arc.paper.api.*
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class CivsBlueprintsTest : FunSpec({
    test("catalog selects all 63 levels only from the private upgrade-aligned namespace") {
        val ids = CivsBlueprints.STYLES.flatMap { style ->
            BuildingType.entries.flatMap { type -> (1..3).map { CivsBlueprints.schematicId(style, type, it) } }
        }
        ids.size shouldBe 63
        ids.toSet().size shouldBe 63
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

    test("the primary hut marker is required exactly once rather than guessed from the origin") {
        val origin = ArcSchematicPoint(16, 1, 26)
        shouldThrow<CivsRejection> { CivsBlueprintGeometry.controlMarker(BlueprintGeometrySnapshot(origin, emptyList())) }
            .reason shouldBe "control-marker-missing"
        val first = blueprintMarker(ArcSchematicPoint(8, 2, 12))
        val second = blueprintMarker(ArcSchematicPoint(9, 2, 12))
        shouldThrow<CivsRejection> { CivsBlueprintGeometry.controlMarker(BlueprintGeometrySnapshot(origin, listOf(first, second))) }
            .reason shouldBe "control-marker-ambiguous"
        CivsBlueprintGeometry.controlMarker(BlueprintGeometrySnapshot(origin, listOf(first))) shouldBe first.position
    }

    test("all four player headings align the chosen barrel to the marker and derive a separate front entrance") {
        val wantedFronts = listOf(ArcSchematicPoint(0, 0, -1), ArcSchematicPoint(1, 0, 0),
            ArcSchematicPoint(0, 0, 1), ArcSchematicPoint(-1, 0, 0))
        val offsets = listOf(ArcSchematicPoint(-8, 1, -14), ArcSchematicPoint(14, 1, -8),
            ArcSchematicPoint(8, 1, 14), ArcSchematicPoint(-14, 1, 8))
        for (groundY in listOf(0, 1, 3, 5)) for (facing in 0..3) {
            val snapshot = BlueprintGeometrySnapshot(ArcSchematicPoint(16, groundY, 26),
                listOf(blueprintMarker(ArcSchematicPoint(8, groundY + 1, 12))))
            val alignment = CivsBlueprintGeometry.alignment(snapshot, CivPosition(100, 64, -300), facing, null)
            alignment.quarterTurns shouldBe listOf(2, 3, 0, 1)[facing]
            val offset = offsets[alignment.quarterTurns]
            alignment.anchor shouldBe CivPosition(100 - offset.x, 63, -300 - offset.z)
            alignment.control shouldBe CivPosition(100, 64, -300)
            alignment.entrance shouldBe CivPosition(100 - offset.x, 64, -300 - offset.z)
            CivsBlueprintGeometry.rotate(ArcSchematicPoint(0, 0, 1), alignment.quarterTurns) shouldBe wantedFronts[facing]
        }
    }

    test("ground selection preserves ground height for every authored marker Y offset and rotation") {
        for (height in listOf(1, 2, 4)) for (rotation in listOf(0, 90, 180, 270)) {
            val snapshot = BlueprintGeometrySnapshot(ArcSchematicPoint(16, 1, 26),
                listOf(blueprintMarker(ArcSchematicPoint(8, 1 + height, 12))))
            val aligned = CivsBlueprintGeometry.alignment(snapshot, CivPosition(10, 64, 20), 0, rotation, groundLevel = true)
            aligned.control shouldBe CivPosition(10, 64 + height, 20)
            aligned.anchor.y shouldBe 64
            aligned.entrance.y shouldBe 65
        }
    }

    test("fixed-entrance upgrades preserve the footprint origin and rotation across aligned levels") {
        val offsets = listOf(ArcSchematicPoint(-8, 2, -14), ArcSchematicPoint(14, 2, -8),
            ArcSchematicPoint(8, 2, 14), ArcSchematicPoint(-14, 2, 8))
        for (groundY in listOf(1, 3, 5)) for (rotation in listOf(0, 90, 180, 270)) for (facing in 0..3) {
            val snapshot = BlueprintGeometrySnapshot(ArcSchematicPoint(16, groundY, 26),
                listOf(blueprintMarker(ArcSchematicPoint(8, groundY + 2, 12))))
            val aligned = CivsBlueprintGeometry.alignment(snapshot, CivPosition(-999, 12, -999), facing, rotation,
                fixedEntrance = CivPosition(10, 70, 20), groundLevel = true)
            aligned.anchor shouldBe CivPosition(10, 69, 20)
            aligned.quarterTurns shouldBe rotation / 90
            aligned.entrance shouldBe CivPosition(10, 70, 20)
            val offset = offsets[rotation / 90]
            aligned.control shouldBe CivPosition(10 + offset.x, 71, 20 + offset.z)
        }
        shouldThrow<IllegalArgumentException> {
            CivsBlueprintGeometry.alignment(BlueprintGeometrySnapshot(ArcSchematicPoint(16, 1, 26)), CivPosition(10, 70, 20), 0, 45)
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

    test("prepare keeps complete cells and exact captured bytes while moving the marker to the chosen ground center") {
        val original = BlueprintCellsSnapshot(ArcSchematicPoint(0, 0, 0), ArcSchematicPoint(2, 2, 2), ArcSchematicPoint(1, 0, 3),
            mapOf(ArcSchematicPoint(1, 1, 1) to "minecraft:structure_void"))
        val transformed = BlueprintCellsSnapshot(ArcSchematicPoint(-1, 0, -3), ArcSchematicPoint(1, 2, -1), ArcSchematicPoint(0, 0, 0),
            mapOf(ArcSchematicPoint(0, 1, -2) to "minecraft:structure_void"))
        val libraries = BlueprintLibraries()
        val bytes = byteArrayOf(4, 3, 2, 1)
        every { libraries.source.load(any(), ArcSchematicContentPolicy.IGNORE_ENTITIES_AND_BIOMES) } returns CompletableFuture.completedFuture(original)
        every { libraries.source.captureSource(any(), original.fileSha256) } returns CompletableFuture.completedFuture(bytes)
        every { libraries.source.transform(original, ArcSchematicTransform(0, false)) } returns CompletableFuture.completedFuture(transformed)
        libraries.subject.use { subject ->
            val prepared = subject.prepare("nordic", BuildingType.TOWN_HALL, 1, UUID.randomUUID(), UUID.randomUUID(),
                CivPosition(100, 64, 200), 2, 0, groundLevel = true).join()
            prepared.building.control shouldBe CivPosition(100, 65, 200)
            prepared.building.anchor shouldBe CivPosition(100, 64, 202)
            prepared.building.entrance shouldBe CivPosition(100, 65, 202)
            prepared.cells.size.toLong() shouldBe transformed.volume
            prepared.cells.single { it.blockData == "minecraft:structure_void" }.position shouldBe ArcSchematicPoint(100, 65, 200)
            prepared.sourceBytes.toList() shouldBe bytes.toList()
            prepared.snapshot.fileSha256 shouldBe original.fileSha256
        }
    }

    test("paste parts cover every full-volume cell once except the exact marker including edge and singleton cases") {
        for (size in listOf(1, 3)) for (marker in listOf(ArcSchematicPoint(0, 0, 0), ArcSchematicPoint(size / 2, size / 2, size / 2))) {
            val snapshot = BlueprintCellsSnapshot(ArcSchematicPoint(0, 0, 0), ArcSchematicPoint(size - 1, size - 1, size - 1),
                ArcSchematicPoint(0, 0, 0), mapOf(marker to "minecraft:structure_void"))
            val building = blueprintBuilding(snapshot, CivPosition(100, 64, 200), marker)
            BlueprintLibraries().subject.use { subject ->
                val parts = subject.pasteParts(snapshot, building).join()
                (parts.size <= 6) shouldBe true
                val actual = parts.flatMap { it.blocks() }.map { it.position }
                actual.toSet() shouldBe snapshot.blocks().map { it.position }.toSet() - marker
                actual.distinct().size shouldBe actual.size
                parts.all { it.fileSha256 == snapshot.fileSha256 } shouldBe true
                snapshot.blockAt(marker.x, marker.y, marker.z).blockData shouldBe "minecraft:structure_void"
            }
        }
    }

    test("single-layer batch parts stay within the selected batch and preserve air on both sides of the barrel") {
        val marker = ArcSchematicPoint(1, 1, 1)
        val snapshot = BlueprintCellsSnapshot(ArcSchematicPoint(0, 0, 0), ArcSchematicPoint(2, 2, 2), ArcSchematicPoint(0, 0, 0),
            mapOf(marker to "minecraft:structure_void"))
        val building = blueprintBuilding(snapshot, CivPosition(100, 64, 200), marker)
        BlueprintLibraries().subject.use { subject ->
            for (y in 0..2) {
                val batch = CivBuildBatch(CivPosition(100, 64 + y, 200), CivPosition(102, 64 + y, 202))
                val parts = subject.pasteParts(snapshot, building, batch).join()
                (parts.size <= if (y == 1) 4 else 1) shouldBe true
                val actual = parts.flatMap { it.blocks() }
                actual.map { it.position }.toSet() shouldBe snapshot.blocks().filter { it.position.y == y && it.position != marker }.map { it.position }.toSet()
                actual.map { it.position }.distinct().size shouldBe actual.size
                actual.all { it.isAir } shouldBe true
            }
        }
    }

    test("legacy captures keep their original structure void while authored plans require its unique exact control") {
        val marker = ArcSchematicPoint(1, 1, 1)
        val snapshot = BlueprintCellsSnapshot(ArcSchematicPoint(0, 0, 0), ArcSchematicPoint(2, 2, 2), ArcSchematicPoint(0, 0, 0),
            mapOf(marker to "minecraft:structure_void", ArcSchematicPoint(2, 2, 2) to "minecraft:barrier"))
        val building = blueprintBuilding(snapshot, CivPosition(100, 64, 200), marker)
        val cells = CivsBlueprints.worldCells(snapshot, building.anchor)
        val full = cells.filterNot { it.isBarrier }.map {
            CivPlanBlock(CivPosition(it.position.x, it.position.y, it.position.z), "minecraft:air", it.blockData)
        }
        val plan = CivPlacementPlan(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), building,
            full.filterNot { it.position == building.control })
        CivsBlueprints.verifyCapturedPlan(plan, snapshot)
        shouldThrow<CivsRejection> { CivsBlueprints.verifyCapturedPlan(plan.copy(blocks = full), snapshot) }.reason shouldBe "invalid-plan"
        shouldThrow<CivsRejection> { CivsBlueprints.verifyCapturedPlan(plan.copy(building = building.copy(control = CivPosition(102, 65, 201))), snapshot) }
            .reason shouldBe "invalid-plan"
        val missing = BlueprintCellsSnapshot(snapshot.minimum, snapshot.maximum, snapshot.origin)
        shouldThrow<CivsRejection> { CivsBlueprints.verifyCapturedPlan(plan, missing) }.reason shouldBe "control-marker-missing"
        val ambiguous = BlueprintCellsSnapshot(snapshot.minimum, snapshot.maximum, snapshot.origin,
            mapOf(marker to "minecraft:structure_void", ArcSchematicPoint(0, 0, 0) to "minecraft:structure_void"))
        shouldThrow<CivsRejection> { CivsBlueprints.verifyCapturedPlan(plan, ambiguous) }.reason shouldBe "control-marker-ambiguous"
        val legacy = plan.copy(building = building.copy(control = building.anchor), blocks = full)
        CivsBlueprints.verifyCapturedPlan(legacy, snapshot)
        BlueprintLibraries().subject.use { subject ->
            subject.pasteParts(snapshot, legacy.building).join().single() shouldBe snapshot
        }
    }
})

private class BlueprintGeometrySnapshot(
    override val origin: ArcSchematicPoint,
    private val cells: List<ArcSchematicBlock>? = null,
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
    override fun blocks(): List<ArcSchematicBlock> = cells ?: error("Entrance must not infer a door from source blocks")
}

private fun blueprintMarker(point: ArcSchematicPoint) = ArcSchematicBlock(point, "minecraft:structure_void", false, false)

private class BlueprintCellsSnapshot(
    override val minimum: ArcSchematicPoint,
    override val maximum: ArcSchematicPoint,
    override val origin: ArcSchematicPoint,
    private val data: Map<ArcSchematicPoint, String> = emptyMap(),
) : ArcSchematicSnapshot {
    override val sizeX = maximum.x - minimum.x + 1
    override val sizeY = maximum.y - minimum.y + 1
    override val sizeZ = maximum.z - minimum.z + 1
    override val volume = sizeX.toLong() * sizeY * sizeZ
    override val nonBarrierCount get() = blocks().count { !it.isBarrier }
    override val hasBiomes = false
    override val entityCount = 0
    override val fileSha256 = "a".repeat(64)
    override val contentSha256 = "b".repeat(64)
    override fun blockAt(x: Int, y: Int, z: Int): ArcSchematicBlock {
        require(x in minimum.x..maximum.x && y in minimum.y..maximum.y && z in minimum.z..maximum.z)
        val point = ArcSchematicPoint(x, y, z)
        val state = data[point] ?: "minecraft:air"
        return ArcSchematicBlock(point, state, state == "minecraft:air", state == "minecraft:barrier")
    }
    override fun blocks(): List<ArcSchematicBlock> = buildList {
        for (y in minimum.y..maximum.y) for (x in minimum.x..maximum.x) for (z in minimum.z..maximum.z) add(blockAt(x, y, z))
    }
    fun cropped(minimum: ArcSchematicPoint, maximum: ArcSchematicPoint): ArcSchematicSnapshot =
        BlueprintCellsSnapshot(minimum, maximum, origin, data)
}

private class BlueprintLibraries {
    val source = mockk<ArcSchematicLibrary>(relaxed = true)
    private val captured = mockk<ArcSchematicLibrary>(relaxed = true)
    private val service = mockk<ArcSchematicService>()
    val subject: CivsBlueprints
    init {
        every { service.open("arccivs-library", any()) } returns source
        every { service.open("arccivs-construction", any()) } returns captured
        every { captured.crop(any(), any(), any()) } answers {
            CompletableFuture.completedFuture(firstArg<BlueprintCellsSnapshot>().cropped(secondArg(), thirdArg()))
        }
        subject = CivsBlueprints(service, Path.of("source"), Path.of("captured"), Executor { it.run() }, 150_000)
    }
}

private fun blueprintBuilding(snapshot: ArcSchematicSnapshot, anchor: CivPosition, marker: ArcSchematicPoint): CivBuilding =
    CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, anchor, 0, "test",
        CivBox(CivPosition(anchor.x + snapshot.minimum.x, anchor.y + snapshot.minimum.y, anchor.z + snapshot.minimum.z),
            CivPosition(anchor.x + snapshot.maximum.x, anchor.y + snapshot.maximum.y, anchor.z + snapshot.maximum.z)),
        CivPosition(anchor.x, anchor.y + 1, anchor.z + snapshot.maximum.z + 1), BuildingPhase.CONSTRUCTING,
        control = CivPosition(anchor.x + marker.x, anchor.y + marker.y, anchor.z + marker.z))
