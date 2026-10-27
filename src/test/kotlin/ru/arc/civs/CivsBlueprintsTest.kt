package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.arc.paper.api.*
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class CivsBlueprintsTest : FunSpec({
    test("infrastructure preparation keeps paid spine barriers skipped and verifies every new shoulder cell") {
        MockBukkitTestRuntime.open().use {
            val id = UUID.randomUUID()
            val cells = buildList {
                for (x in 10..12) for (y in 64..66) add(CivSettlementBlock(CivPosition(x, y, 20),
                    if (x == 11) "minecraft:barrier" else if (y == 64) "minecraft:cobblestone" else "minecraft:air"))
            }
            val piece = CivSettlementPiece(id, BuildingType.ROAD,
                CivBox(CivPosition(10, 64, 20), CivPosition(12, 66, 20)), CivPosition(10, 65, 19), cells,
                (10..12).map { x -> CivPosition(x, 65, 20) })
            val bytes = CivsInfrastructure.schematic(piece, 4444)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val snapshot = BlueprintCellsSnapshot(ArcSchematicPoint(0, 0, 0), ArcSchematicPoint(2, 2, 0),
                ArcSchematicPoint(0, 0, 0), cells.associate { block ->
                    ArcSchematicPoint(block.position.x - 10, block.position.y - 64, block.position.z - 20) to block.after
                }, hash, "minecraft:barrier")
            val libraries = BlueprintLibraries()
            every { libraries.captured.loadVerified(id.toString(), hash,
                ArcSchematicContentPolicy.IGNORE_ENTITIES_AND_BIOMES) } returns CompletableFuture.completedFuture(snapshot)
            libraries.subject.use { blueprints ->
                val prepared = blueprints.prepareInfrastructure(piece, UUID.randomUUID(), bytes).join()
                prepared.building.transit shouldBe piece.transit
                prepared.building.bounds shouldBe piece.bounds
                prepared.cells.filter { it.position.x == 11 }.all { it.isBarrier } shouldBe true
                prepared.materials shouldBe mapOf("COBBLESTONE" to 2L)
                val changed = piece.copy(blocks = cells.map { cell -> if (cell.position == CivPosition(10, 64, 20))
                    cell.copy(after = "minecraft:dirt") else cell })
                val failure = shouldThrow<java.util.concurrent.CompletionException> {
                    blueprints.prepareInfrastructure(changed, UUID.randomUUID(), bytes).join()
                }
                (failure.cause as CivsRejection).reason shouldBe "invalid-plan"
            }
        }
    }

    test("builtin board uses its owned verified capture off thread and restores the ordinary control in all rotations") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use {
                val root = Files.createTempDirectory("civs-board-blueprint")
                val work = ArrayDeque<Runnable>()
                fun finish() {
                    var count = 0
                    while (work.isNotEmpty()) {
                        check(count++ < 100) { "Builtin blueprint fixture did not drain" }
                        work.removeFirst().run()
                    }
                }
                val bytes = CivsPlanBoardBlueprint.schematic(4444)
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                val origin = ArcSchematicPoint(3, 0, 3)
                val cells = CivsPlanBoardBlueprint.blocks().mapKeys { (p, _) -> ArcSchematicPoint(p.x, p.y, p.z) }
                val original = BlueprintCellsSnapshot(ArcSchematicPoint(-2, 0, 0), ArcSchematicPoint(8, 7, 2), origin, cells, hash, "minecraft:barrier")
                val libraries = BlueprintLibraries(root, Executor { work.addLast(it) }, 4444)
                every { libraries.captured.loadVerified("builtin/plan_board/v3", hash,
                    ArcSchematicContentPolicy.IGNORE_ENTITIES_AND_BIOMES) } returns CompletableFuture.completedFuture(original)
                every { libraries.captured.transform(original, any()) } answers {
                    val turns = secondArg<ArcSchematicTransform>().quarterTurns
                    fun rotated(p: ArcSchematicPoint) = CivsBlueprintGeometry.rotate(
                        ArcSchematicPoint(p.x - origin.x, p.y - origin.y, p.z - origin.z), turns)
                    val corners = listOf(-2, 8).flatMap { x -> listOf(0, 2).map { z -> rotated(ArcSchematicPoint(x, 0, z)) } }
                    CompletableFuture.completedFuture(BlueprintCellsSnapshot(
                        ArcSchematicPoint(corners.minOf { it.x }, 0, corners.minOf { it.z }),
                        ArcSchematicPoint(corners.maxOf { it.x }, 7, corners.maxOf { it.z }), ArcSchematicPoint(0, 0, 0),
                        cells.mapKeys { (p, _) -> rotated(p) }, hash, "minecraft:barrier"))
                }
                try {
                    libraries.subject.use { subject ->
                        val placement = CivPosition(40, 64, 20)
                        val controls = listOf(CivPosition(40, 65, 19), CivPosition(41, 65, 20),
                            CivPosition(40, 65, 21), CivPosition(39, 65, 20))
                        for (turns in 0..3) {
                            val pending = subject.prepare("nordic", BuildingType.PLAN_BOARD, 1, UUID.randomUUID(), UUID.randomUUID(),
                                placement, 2, exactRotation = turns * 90)
                            if (turns == 0) Files.exists(root.resolve("builtin")) shouldBe false
                            pending.isDone shouldBe false
                            finish()
                            val blueprint = pending.join()
                            blueprint.building.schematicId shouldBe CivsPlanBoardBlueprint.SCHEMATIC_ID
                            blueprint.building.anchor shouldBe placement
                            blueprint.building.entrance shouldBe CivPosition(40, 65, 20)
                            blueprint.building.control shouldBe controls[turns]
                            blueprint.sourceBytes.toList() shouldBe bytes.toList()
                            blueprint.materials shouldBe mapOf("STRIPPED_SPRUCE_LOG" to 30L, "STONE_BRICKS" to 6L,
                                "SMOOTH_SANDSTONE" to 36L, "DARK_OAK_STAIRS" to 22L, "DARK_OAK_SLAB" to 11L)
                            val control = subject.controlPosition(blueprint.building.copy(phase = BuildingPhase.ACTIVE))
                            finish()
                            control.join() shouldBe controls[turns]
                        }
                        Files.readAllBytes(root.resolve("builtin/plan_board/v3.schem")).toList() shouldBe bytes.toList()
                        verify(exactly = 8) { libraries.captured.loadVerified("builtin/plan_board/v3", hash,
                            ArcSchematicContentPolicy.IGNORE_ENTITIES_AND_BIOMES) }
                        val legacyBytes = CivsPlanBoardBlueprint.schematic(4444, legacy = true)
                        val legacyHash = MessageDigest.getInstance("SHA-256").digest(legacyBytes).joinToString("") { "%02x".format(it) }
                        val legacySnapshot = BlueprintCellsSnapshot(ArcSchematicPoint(0, 0, 0), ArcSchematicPoint(6, 5, 2), origin,
                            CivsPlanBoardBlueprint.blocks(legacy = true).mapKeys { (p, _) -> ArcSchematicPoint(p.x, p.y, p.z) },
                            legacyHash, "minecraft:barrier")
                        every { libraries.captured.loadVerified("builtin/plan_board/v1", legacyHash,
                            ArcSchematicContentPolicy.IGNORE_ENTITIES_AND_BIOMES) } returns CompletableFuture.completedFuture(legacySnapshot)
                        val old = CivBuilding(UUID.randomUUID(), BuildingType.PLAN_BOARD, 1, placement, 270,
                            CivsPlanBoardBlueprint.LEGACY_SCHEMATIC_ID,
                            CivBox(CivPosition(37, 64, 17), CivPosition(39, 69, 23)), CivPosition(40, 65, 20), BuildingPhase.ACTIVE,
                            control = CivPosition(39, 65, 17))
                        val retainedControl = subject.controlPosition(old)
                        finish()
                        retainedControl.join() shouldBe old.control
                        Files.readAllBytes(root.resolve("builtin/plan_board/v1.schem")).toList() shouldBe legacyBytes.toList()
                        verify(exactly = 0) { libraries.source.load(any(), any()) }
                        verify(exactly = 0) { libraries.source.captureSource(any(), any()) }
                    }
                } finally { root.toFile().deleteRecursively() }
            }
        }
    }

    test("catalog selects all 153 authored levels only from the private upgrade-aligned namespace") {
        val ids = CivsBlueprints.STYLES.flatMap { style ->
            BuildingType.entries.filterNot { it.isInfrastructure || it in setOf(BuildingType.PLAN_BOARD, BuildingType.DECORATION) }
                .flatMap { type -> (1..3).map { CivsBlueprints.schematicId(style, type, it) } }
        }
        ids.size shouldBe 153
        ids.toSet().size shouldBe 153
        ids.all { it.startsWith("civs/minecolonies/") } shouldBe true
        shouldThrow<CivsRejection> { CivsBlueprints.schematicId("nordic", BuildingType.DECORATION, 1) }
        CivsBlueprints.schematicId("nordic", BuildingType.TOWN_HALL, 1) shouldBe
            "civs/minecolonies/nordic/fundamentals/townhall1"
        CivsBlueprints.schematicId("caledonia", BuildingType.FARM, 3) shouldBe
            "civs/minecolonies/caledonia/agriculture/horticulture/farmer3"
        for ((type, family) in mapOf(BuildingType.SAWMILL to "craftsmanship/carpentry/sawmill",
            BuildingType.BLACKSMITH to "craftsmanship/metallurgy/blacksmith",
            BuildingType.STONEMASON to "craftsmanship/masonry/stonemason", BuildingType.LIBRARY to "education/library",
            BuildingType.TAVERN to "fundamentals/tavern", BuildingType.BAKERY to "craftsmanship/luxury/baker",
            BuildingType.GUARD_TOWER to "military/guardtower", BuildingType.UNIVERSITY to "education/university",
            BuildingType.SMELTERY to "craftsmanship/metallurgy/smeltery", BuildingType.CRUSHER to "craftsmanship/masonry/crusher")) {
            for (style in CivsBlueprints.STYLES) for (level in 1..3)
                CivsBlueprints.schematicId(style, type, level) shouldBe "civs/minecolonies/$style/$family$level"
        }
        shouldThrow<IllegalArgumentException> { CivsBlueprints.schematicId("../nordic", BuildingType.HOUSE, 1) }
        shouldThrow<IllegalArgumentException> { CivsBlueprints.schematicId("nordic", BuildingType.HOUSE, 4) }
        CivsBlueprints.STYLES.map { CivsBlueprints.schematicId(it, BuildingType.PLAN_BOARD, 1) }.toSet() shouldBe
            setOf(CivsPlanBoardBlueprint.SCHEMATIC_ID)
        shouldThrow<IllegalArgumentException> { CivsBlueprints.schematicId("nordic", BuildingType.PLAN_BOARD, 2) }
    }

    test("warehouse maintenance derives private rotated rack differences and rejects unrelated changes") {
        val at = ArcSchematicPoint(0, 0, 0)
        val before = BlueprintCellsSnapshot(at, at, at, mapOf(at to "minecraft:bookshelf"))
        val after = BlueprintCellsSnapshot(at, at, at, mapOf(at to "minecraft:chest[facing=south,type=single,waterlogged=false]"))
        val libraries = BlueprintLibraries()
        val id = CivsBlueprints.schematicId("medievaloak", BuildingType.WAREHOUSE, 1)
        every { libraries.source.load("civs/legacy-warehouse-racks/medievaloak/warehouse1", any()) } returns CompletableFuture.completedFuture(before)
        every { libraries.source.load("civs/legacy-warehouse-chests/medievaloak/warehouse1", any()) } returns CompletableFuture.completedFuture(after)
        every { libraries.source.load(id, any()) } returns CompletableFuture.completedFuture(after)
        every { libraries.source.transform(any(), any()) } answers { CompletableFuture.completedFuture(firstArg()) }
        val building = blueprintBuilding(before, CivPosition(12, 64, -10), at).copy(type = BuildingType.WAREHOUSE,
            phase = BuildingPhase.ACTIVE, schematicId = id, rotation = 180)
        libraries.subject.use { subject ->
            subject.buildingStorageRepair(building).join() shouldBe listOf(CivPlanBlock(CivPosition(12, 64, -10),
                "minecraft:bookshelf", "minecraft:chest[facing=south,type=single,waterlogged=false]"))
            verify(exactly = 3) { libraries.source.transform(any(), ArcSchematicTransform(2, false)) }
            every { libraries.source.load(id, any()) } returns CompletableFuture.completedFuture(
                BlueprintCellsSnapshot(at, at, at, mapOf(at to "minecraft:diamond_block")))
            shouldThrow<java.util.concurrent.CompletionException> { subject.buildingStorageRepair(building).join() }
        }
    }

    test("warehouse alignment retains exact shelf and previous single chest alternatives only at authored racks") {
        val first = ArcSchematicPoint(0, 0, 0)
        val second = ArcSchematicPoint(1, 0, 0)
        fun snapshot(states: List<String>) = BlueprintCellsSnapshot(first, second, first,
            listOf(first, second).zip(states).toMap())
        val racks = snapshot(listOf("minecraft:bookshelf", "minecraft:bookshelf"))
        val old = snapshot(listOf("minecraft:chest[facing=west,type=single,waterlogged=false]",
            "minecraft:chest[facing=east,type=single,waterlogged=false]"))
        val aligned = snapshot(listOf("minecraft:chest[facing=south,type=right,waterlogged=false]",
            "minecraft:chest[facing=south,type=left,waterlogged=false]"))
        val libraries = BlueprintLibraries()
        val id = CivsBlueprints.schematicId("medievaloak", BuildingType.WAREHOUSE, 1)
        every { libraries.source.load("civs/legacy-warehouse-racks/medievaloak/warehouse1", any()) } returns CompletableFuture.completedFuture(racks)
        every { libraries.source.load("civs/legacy-warehouse-chests/medievaloak/warehouse1", any()) } returns CompletableFuture.completedFuture(old)
        every { libraries.source.load(id, any()) } returns CompletableFuture.completedFuture(aligned)
        every { libraries.source.transform(any(), any()) } answers { CompletableFuture.completedFuture(firstArg()) }
        val building = blueprintBuilding(racks, CivPosition(12, 64, -10), first).copy(type = BuildingType.WAREHOUSE,
            phase = BuildingPhase.ACTIVE, schematicId = id)
        libraries.subject.use { subject ->
            val cells = subject.buildingStorageRepair(building).join()
            cells.groupBy { it.position }.mapValues { (_, alternatives) -> alternatives.map { it.before } } shouldBe mapOf(
                CivPosition(12, 64, -10) to listOf("minecraft:bookshelf", "minecraft:chest[facing=west,type=single,waterlogged=false]"),
                CivPosition(13, 64, -10) to listOf("minecraft:bookshelf", "minecraft:chest[facing=east,type=single,waterlogged=false]"))
            cells.groupBy { it.position }.values.all { it.map(CivPlanBlock::after).distinct().size == 1 } shouldBe true
            every { libraries.source.load("civs/legacy-warehouse-chests/medievaloak/warehouse1", any()) } returns
                CompletableFuture.completedFuture(snapshot(listOf("minecraft:diamond_block", old.blockAt(1, 0, 0).blockData)))
            shouldThrow<java.util.concurrent.CompletionException> { subject.buildingStorageRepair(building).join() }
        }
    }

    test("ordinary building rack repair uses its exact family baseline and preserves decorative bookshelves") {
        val rack = ArcSchematicPoint(0, 0, 0)
        val decor = ArcSchematicPoint(1, 0, 0)
        val before = BlueprintCellsSnapshot(rack, decor, rack, mapOf(rack to "minecraft:chiseled_bookshelf", decor to "minecraft:bookshelf"))
        val after = BlueprintCellsSnapshot(rack, decor, rack, mapOf(rack to "minecraft:barrel[facing=north,open=false]", decor to "minecraft:bookshelf"))
        val libraries = BlueprintLibraries()
        val id = CivsBlueprints.schematicId("nordic", BuildingType.FORESTER, 3)
        every { libraries.source.load("civs/legacy-building-racks/nordic/fundamentals/lumberjack3", any()) } returns CompletableFuture.completedFuture(before)
        every { libraries.source.load(id, any()) } returns CompletableFuture.completedFuture(after)
        every { libraries.source.transform(any(), any()) } answers { CompletableFuture.completedFuture(firstArg()) }
        val building = blueprintBuilding(before, CivPosition(12, 64, -10), rack).copy(type = BuildingType.FORESTER,
            level = 3, phase = BuildingPhase.ACTIVE, schematicId = id)
        libraries.subject.use { subject ->
            subject.buildingStorageRepair(building).join() shouldBe listOf(CivPlanBlock(CivPosition(12, 64, -10),
                "minecraft:chiseled_bookshelf", "minecraft:barrel[facing=north,open=false]"))
            val shifted = BlueprintCellsSnapshot(rack, decor, decor, mapOf(rack to "minecraft:barrel[facing=north,open=false]", decor to "minecraft:bookshelf"))
            every { libraries.source.load(id, any()) } returns CompletableFuture.completedFuture(shifted)
            CivsDiagnostics.rejection(shouldThrow<java.util.concurrent.CompletionException> {
                subject.buildingStorageRepair(building).join()
            })?.reason shouldBe "control-location-changed"
        }
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

    test("all four player headings keep the selected ground pivot and rotate the offset control around it") {
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
            alignment.anchor shouldBe CivPosition(100, 64, -300)
            alignment.control shouldBe CivPosition(100 + offset.x, 65, -300 + offset.z)
            alignment.entrance shouldBe CivPosition(100, 65, -300)
            CivsBlueprintGeometry.rotate(ArcSchematicPoint(0, 0, 1), alignment.quarterTurns) shouldBe wantedFronts[facing]
        }
    }

    test("ground selection preserves ground height for every authored marker Y offset and rotation") {
        for (height in listOf(1, 2, 4)) for (rotation in listOf(0, 90, 180, 270)) {
            val snapshot = BlueprintGeometrySnapshot(ArcSchematicPoint(16, 1, 26),
                listOf(blueprintMarker(ArcSchematicPoint(8, 1 + height, 12))))
            val aligned = CivsBlueprintGeometry.alignment(snapshot, CivPosition(10, 64, 20), 0, rotation)
            val offset = listOf(CivPosition(-8, height, -14), CivPosition(14, height, -8),
                CivPosition(8, height, 14), CivPosition(-14, height, 8))[rotation / 90]
            aligned.control shouldBe CivPosition(10 + offset.x, 64 + height, 20 + offset.z)
            aligned.anchor shouldBe CivPosition(10, 64, 20)
            aligned.entrance shouldBe CivPosition(10, 65, 20)
        }
    }

    test("fixed-entrance upgrades preserve the footprint origin and rotation across aligned levels") {
        val offsets = listOf(ArcSchematicPoint(-8, 2, -14), ArcSchematicPoint(14, 2, -8),
            ArcSchematicPoint(8, 2, 14), ArcSchematicPoint(-14, 2, 8))
        for (groundY in listOf(1, 3, 5)) for (rotation in listOf(0, 90, 180, 270)) for (facing in 0..3) {
            val snapshot = BlueprintGeometrySnapshot(ArcSchematicPoint(16, groundY, 26),
                listOf(blueprintMarker(ArcSchematicPoint(8, groundY + 2, 12))))
            val aligned = CivsBlueprintGeometry.alignment(snapshot, CivPosition(-999, 12, -999), facing, rotation,
                fixedEntrance = CivPosition(10, 70, 20))
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

    test("lightweight control lookup subtracts the authored origin at every rotation without preparing a preview") {
        val original = BlueprintCellsSnapshot(ArcSchematicPoint(0, 0, 0), ArcSchematicPoint(4, 4, 2), ArcSchematicPoint(2, 1, 3),
            mapOf(ArcSchematicPoint(1, 3, 1) to "minecraft:structure_void"))
        val minima = listOf(ArcSchematicPoint(-2, -1, -3), ArcSchematicPoint(1, -1, -2),
            ArcSchematicPoint(-2, -1, 1), ArcSchematicPoint(-3, -1, -2))
        val maxima = listOf(ArcSchematicPoint(2, 3, -1), ArcSchematicPoint(3, 3, 2),
            ArcSchematicPoint(2, 3, 3), ArcSchematicPoint(-1, 3, 2))
        val controls = listOf(ArcSchematicPoint(-1, 2, -2), ArcSchematicPoint(2, 2, -1),
            ArcSchematicPoint(1, 2, 2), ArcSchematicPoint(-2, 2, 1))
        val libraries = BlueprintLibraries()
        every { libraries.source.load("test", ArcSchematicContentPolicy.IGNORE_ENTITIES_AND_BIOMES) } returns CompletableFuture.completedFuture(original)
        libraries.subject.use { subject ->
            for (turns in 0..3) {
                val transformed = BlueprintCellsSnapshot(minima[turns], maxima[turns], ArcSchematicPoint(0, 0, 0))
                val anchor = CivPosition(100, 64, 200)
                val building = blueprintBuilding(transformed, anchor, controls[turns]).copy(rotation = turns * 90,
                    entrance = anchor.copy(y = anchor.y + 1))
                subject.controlPosition(building).join() shouldBe building.control
            }
            verify(exactly = 0) { libraries.source.captureSource(any(), any()); libraries.source.transform(any(), any()) }
        }
    }

    test("prepare places the complete captured schematic at the selected ground pivot and derives its internal control") {
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
                CivPosition(100, 64, 200), 2, 0).join()
            prepared.building.control shouldBe CivPosition(100, 65, 198)
            prepared.building.anchor shouldBe CivPosition(100, 64, 200)
            prepared.building.entrance shouldBe CivPosition(100, 65, 200)
            prepared.cells.size.toLong() shouldBe transformed.volume
            prepared.cells.single { it.blockData == "minecraft:structure_void" }.position shouldBe ArcSchematicPoint(100, 65, 198)
            prepared.sourceBytes.toList() shouldBe bytes.toList()
            prepared.snapshot.fileSha256 shouldBe original.fileSha256
        }
    }

    test("recovery preserves existing marker-aligned placements at all four rotations without realigning them") {
        val original = BlueprintCellsSnapshot(ArcSchematicPoint(0, 0, 0), ArcSchematicPoint(4, 4, 2), ArcSchematicPoint(2, 1, 3),
            mapOf(ArcSchematicPoint(1, 3, 1) to "minecraft:structure_void"))
        val offsets = listOf(ArcSchematicPoint(-1, 2, -2), ArcSchematicPoint(2, 2, -1),
            ArcSchematicPoint(1, 2, 2), ArcSchematicPoint(-2, 2, 1))
        for (turns in 0..3) {
            val corners = listOf(ArcSchematicPoint(-2, -1, -3), ArcSchematicPoint(2, 3, -1)).map {
                CivsBlueprintGeometry.rotate(it, turns)
            }
            val marker = offsets[turns]
            val transformed = BlueprintCellsSnapshot(
                ArcSchematicPoint(corners.minOf { it.x }, -1, corners.minOf { it.z }),
                ArcSchematicPoint(corners.maxOf { it.x }, 3, corners.maxOf { it.z }), ArcSchematicPoint(0, 0, 0),
                mapOf(marker to "minecraft:structure_void"))
            val existingControl = CivPosition(120, 68, 210)
            val existingAnchor = CivPosition(existingControl.x - marker.x, existingControl.y - marker.y, existingControl.z - marker.z)
            val building = blueprintBuilding(transformed, existingAnchor, marker).copy(rotation = turns * 90,
                entrance = existingAnchor.copy(y = existingAnchor.y + 1))
            val cells = CivsBlueprints.worldCells(transformed, existingAnchor)
            val plan = CivPlacementPlan(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), building,
                cells.filterNot { it.blockData == "minecraft:structure_void" }.map {
                    CivPlanBlock(CivPosition(it.position.x, it.position.y, it.position.z), "minecraft:air", it.blockData)
                }, original.fileSha256)
            val libraries = BlueprintLibraries()
            every { libraries.captured.loadVerified(plan.id.toString(), original.fileSha256,
                ArcSchematicContentPolicy.IGNORE_ENTITIES_AND_BIOMES) } returns CompletableFuture.completedFuture(original)
            every { libraries.captured.transform(original, ArcSchematicTransform(turns, false)) } returns CompletableFuture.completedFuture(transformed)
            libraries.subject.use { subject ->
                val recovered = subject.recover(plan).join()
                recovered shouldBe transformed
                CivsBlueprints.worldCells(recovered, plan.building.anchor) shouldBe cells
                plan.building.anchor shouldBe existingAnchor
                plan.building.control shouldBe existingControl
                plan.building.bounds.min.y shouldBe existingAnchor.y - 1
            }
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
    override val fileSha256: String = "a".repeat(64),
    private val defaultData: String = "minecraft:air",
) : ArcSchematicSnapshot {
    override val sizeX = maximum.x - minimum.x + 1
    override val sizeY = maximum.y - minimum.y + 1
    override val sizeZ = maximum.z - minimum.z + 1
    override val volume = sizeX.toLong() * sizeY * sizeZ
    override val nonBarrierCount get() = blocks().count { !it.isBarrier }
    override val hasBiomes = false
    override val entityCount = 0
    override val contentSha256 = "b".repeat(64)
    override fun blockAt(x: Int, y: Int, z: Int): ArcSchematicBlock {
        require(x in minimum.x..maximum.x && y in minimum.y..maximum.y && z in minimum.z..maximum.z)
        val point = ArcSchematicPoint(x, y, z)
        val state = data[point] ?: defaultData
        return ArcSchematicBlock(point, state, state == "minecraft:air", state == "minecraft:barrier")
    }
    override fun blocks(): List<ArcSchematicBlock> = buildList {
        for (y in minimum.y..maximum.y) for (x in minimum.x..maximum.x) for (z in minimum.z..maximum.z) add(blockAt(x, y, z))
    }
    fun cropped(minimum: ArcSchematicPoint, maximum: ArcSchematicPoint): ArcSchematicSnapshot =
        BlueprintCellsSnapshot(minimum, maximum, origin, data)
}

private class BlueprintLibraries(
    capturedRoot: Path = Path.of("captured"),
    executor: Executor = Executor { it.run() },
    dataVersion: Int = 0,
) {
    val source = mockk<ArcSchematicLibrary>(relaxed = true)
    val captured = mockk<ArcSchematicLibrary>(relaxed = true)
    private val service = mockk<ArcSchematicService>()
    val subject: CivsBlueprints
    init {
        every { service.open("arccivs-library", any()) } returns source
        every { service.open("arccivs-construction", any()) } returns captured
        every { captured.crop(any(), any(), any()) } answers {
            CompletableFuture.completedFuture(firstArg<BlueprintCellsSnapshot>().cropped(secondArg(), thirdArg()))
        }
        subject = CivsBlueprints(service, Path.of("source"), capturedRoot, executor, 150_000, dataVersion)
    }
}

private fun blueprintBuilding(snapshot: ArcSchematicSnapshot, anchor: CivPosition, marker: ArcSchematicPoint): CivBuilding =
    CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, anchor, 0, "test",
        CivBox(CivPosition(anchor.x + snapshot.minimum.x, anchor.y + snapshot.minimum.y, anchor.z + snapshot.minimum.z),
            CivPosition(anchor.x + snapshot.maximum.x, anchor.y + snapshot.maximum.y, anchor.z + snapshot.maximum.z)),
        CivPosition(anchor.x, anchor.y + 1, anchor.z + snapshot.maximum.z + 1), BuildingPhase.CONSTRUCTING,
        control = CivPosition(anchor.x + marker.x, anchor.y + marker.y, anchor.z + marker.z))
