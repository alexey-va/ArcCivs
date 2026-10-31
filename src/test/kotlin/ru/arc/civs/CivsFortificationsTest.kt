package ru.arc.civs

import com.google.gson.GsonBuilder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.arc.paper.api.*
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class CivsFortificationsTest : FunSpec({
    test("one cancelled caller cannot cancel the shared fortification compilation") {
        MockBukkitTestRuntime.open().use {
            val root = Files.createTempDirectory("civs-fortification-cancel")
            val queue = ArrayDeque<Runnable>()
            val service = mockk<ArcSchematicService>()
            every { service.open(any(), any()) } returns mockk(relaxed = true)
            CivsBlueprints(service, root.resolve("missing-source"), root.resolve("captured"), Executor { queue.addLast(it) },
                50_000, 4444).use { owner ->
                val first = owner.fortification(CivsFortifications.STYLE)
                val second = owner.fortification(CivsFortifications.STYLE)
                first.cancel(false) shouldBe true
                while (queue.isNotEmpty()) queue.removeFirst().run()
                second.isCancelled shouldBe false
                second.join() shouldBe null
                owner.fortification(CivsFortifications.STYLE).join() shouldBe null
            }
        }
    }

    test("one cropped corner preserves supported wall-walk ports and proves only real ground barriers") {
        val template = fortificationFixture().compile()
        template.building.type shouldBe BuildingType.DECORATION
        template.building.control shouldBe null
        template.building.entrance shouldBe CivPosition(-10, 1, 0)
        template.building.bounds shouldBe CivBox(CivPosition(-21, 0, -27), CivPosition(5, 24, -1))
        template.cells.size shouldBe 18_225
        template.cells.map { it.blockData }.distinct().size.let { it <= 128 } shouldBe true
        template.cells.none { it.blockData.startsWith("minecraft:structure_void") } shouldBe true
        val cells = template.cells.associate { it.position to it.blockData }
        (template.groundBarrier.size < 27 * 27) shouldBe true
        (CivPosition(5, 0, -2) in template.groundBarrier) shouldBe false
        template.wallEndPorts.size shouldBe 2
        template.wallEndPorts.all { port -> port.floor.all { it in template.proof.wallWalkFloors } } shouldBe true
        template.wallEndPorts.forEach { port ->
            port.ground.all { it in template.groundBarrier } shouldBe true
            port.floor.all { cells[ArcSchematicPoint(it.x, it.y, it.z)] == "minecraft:stone_bricks" } shouldBe true
            (port.feet + port.head).all { cells[ArcSchematicPoint(it.x, it.y, it.z)] == "minecraft:air" } shouldBe true
        }
        template.wallEndPorts.first().ground[1].x shouldBe template.building.bounds.min.x
        template.wallEndPorts.last().ground[1].z shouldBe template.building.bounds.max.z
        // The caller supplied the Core-rotated southern stair. The merge must preserve that state.
        cells[ArcSchematicPoint(2, 10, -1)] shouldBe "minecraft:stone_brick_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"
        System.getenv("CIVS_FORTIFICATION_UNIT_PREVIEW_OUT")?.let { directory ->
            val output = Path.of(directory).also { Files.createDirectories(it) }
            Files.writeString(output.resolve("fortification-unit-geometry.json"), GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                "evidence" to "Synthetic source-shaped unit geometry; not native private-asset or world acceptance",
                "building" to template.building, "cells" to template.cells, "proof" to template.proof,
                "sources" to template.sources, "sourceSha256" to template.sourceSha256)))
        }
    }

    test("cropping rejects an above-ground authored block instead of silently cutting a wall") {
        val source = fortificationFixture()
        val changed = source.west.changed(ArcSchematicPoint(-10, 1, -6), "minecraft:stone_bricks")
        shouldThrow<CivsRejection> { source.copy(west = changed).compile() }.values["fortification"] shouldBe "crop-solid"
    }

    test("a broken elevated deck cannot reuse the connected ground barrier as a walk proof") {
        val source = fortificationFixture()
        var west = source.west
        for (z in -8..-4) west = west.changed(ArcSchematicPoint(-2, 7, z), "minecraft:air")
        shouldThrow<CivsRejection> { source.copy(west = west).compile() }.values["fortification"] shouldBe "wall-walk"
    }

    test("only reviewed ground overlaps use tower then west then south priority") {
        val source = fortificationFixture()
        val paidGround = source.west.changed(ArcSchematicPoint(7, 0, -6), "minecraft:cobblestone")
        val template = source.copy(west = paidGround).compile()
        template.cells.single { it.position == ArcSchematicPoint(-5, 0, -22) }.blockData shouldBe "minecraft:gravel"
        val collision = source.west.changed(ArcSchematicPoint(10, 2, -6), "minecraft:stone_bricks")
        shouldThrow<CivsRejection> { source.copy(west = collision).compile() }.values["fortification"] shouldBe "body-conflict"
    }

    test("changed source identity and missing endpoint support cannot produce a trusted proof") {
        val source = fortificationFixture()
        shouldThrow<CivsRejection> { source.copy(tower = source.tower.withHash("0".repeat(64))).compile() }
            .values["fortification"] shouldBe "source-hash"
        val unsupported = source.west.changed(ArcSchematicPoint(-9, 7, -6), "minecraft:air")
        shouldThrow<CivsRejection> { source.copy(west = unsupported).compile() }.values["fortification"] shouldBe "wall-port"
    }

    test("three nonair partial blocks never qualify as a full ground barrier") {
        val source = fortificationFixture()
        var partial = source.tower
        for (y in 1..3) partial = partial.changed(ArcSchematicPoint(5, y, -2),
            "minecraft:stone_brick_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")
        (CivPosition(5, 0, -17) in source.copy(tower = partial).compile().groundBarrier) shouldBe false
    }

    test("proof rotations keep canonical corner ports and bytes immutable") {
        val source = fortificationFixture()
        val template = source.compile()
        template.recipeSha256 shouldBe source.compile().recipeSha256
        val bytes = template.sourceBytes
        bytes.fill(0)
        CivsFortifications.sha256(template.sourceBytes) shouldBe template.sourceSha256
        val anchor = CivPosition(100, 63, 200)
        val ends = listOf(-21 to -22, 22 to -21, 21 to 22, -22 to 21)
        for (turn in 0..3) {
            val proof = template.proofAt(anchor, turn * 90)
            proof.wallEndPorts.first().ground[1] shouldBe CivPosition(anchor.x + ends[turn].first, 63, anchor.z + ends[turn].second)
            proof.wallEndPorts.all { p -> p.floor.all { it.y == 70 } && p.feet.all { it.y == 71 } && p.head.all { it.y == 72 } } shouldBe true
        }
        shouldThrow<UnsupportedOperationException> { (template.cells as MutableList<ArcSchematicBlock>).clear() }
        shouldThrow<UnsupportedOperationException> { (template.groundBarrier as MutableSet<CivPosition>).clear() }
    }

    test("preparation stays asynchronous and recovery replays the same canonical paid bytes") {
        MockBukkitTestRuntime.open().use {
            val template = fortificationFixture().compile()
            val root = Files.createTempDirectory("civs-fortification-test")
            val queue = ArrayDeque<Runnable>()
            val source = mockk<ArcSchematicLibrary>(relaxed = true)
            val captured = mockk<ArcSchematicLibrary>(relaxed = true)
            val service = mockk<ArcSchematicService>()
            every { service.open("arccivs-library", any()) } returns source
            every { service.open("arccivs-construction", any()) } returns captured
            val canonical = fortificationCanonicalSnapshot(template)
            val raw = FortificationSnapshot(ArcSchematicPoint(0, 0, 0), ArcSchematicPoint(26, 24, 26),
                template.cells.associate { cell -> ArcSchematicPoint(cell.position.x + 21, cell.position.y, cell.position.z + 27) to cell.blockData },
                template.sourceSha256, ArcSchematicPoint(21, 0, 27))
            every { captured.loadVerified(any(), template.sourceSha256, ArcSchematicContentPolicy.IGNORE_ENTITIES_AND_BIOMES) } returns
                CompletableFuture.completedFuture(raw)
            every { captured.transform(raw, ArcSchematicTransform(0, false)) } returns CompletableFuture.completedFuture(canonical)
            val id = UUID(149, 1); val world = UUID(149, 2); val anchor = CivPosition(40, 64, 50)
            CivsBlueprints(service, root.resolve("missing-source"), root.resolve("captured"), Executor { queue.addLast(it) }, 50_000, 4444).use { owner ->
                val prepared = owner.prepareFortification(template, id, world, anchor, 0)
                prepared.isDone shouldBe false
                verify(exactly = 0) { captured.loadVerified(any(), any(), any()) }
                var steps = 0
                while (queue.isNotEmpty()) { check(steps++ < 30); queue.removeFirst().run() }
                val blueprint = prepared.join()
                blueprint.sourceBytes.contentEquals(template.sourceBytes) shouldBe true
                blueprint.building.control shouldBe null
                blueprint.building.entrance shouldBe CivPosition(30, 65, 50)
                blueprint.building.bounds shouldBe CivBox(CivPosition(19, 64, 23), CivPosition(45, 88, 49))
                blueprint.materials["BARREL"] shouldBe null
                val plan = CivPlacementPlan(id, UUID(149, 3), world, blueprint.building,
                    blueprint.cells.filterNot { it.isBarrier }.map { cell -> CivPlanBlock(CivPosition(cell.position.x, cell.position.y, cell.position.z),
                        "minecraft:air", cell.blockData) }, capturedSha256 = template.sourceSha256)
                val recovered = owner.recover(plan)
                while (queue.isNotEmpty()) { check(steps++ < 40); queue.removeFirst().run() }
                recovered.join() shouldBe canonical
                verify(atLeast = 2) { captured.transform(raw, ArcSchematicTransform(0, false)) }
            }
        }
    }
})

internal fun civsSyntheticFortification(): CivFortificationTemplate = fortificationFixture().compile()

private data class FortificationSources(val tower: FortificationSnapshot, val west: FortificationSnapshot, val south: FortificationSnapshot) {
    fun compile() = CivsFortifications.compile(tower, west, south, 4444)
}

/** These are geometry/guard fixtures, not copies of the private authored schematics. */
private fun fortificationFixture(): FortificationSources {
    val tower = buildMap {
        for (x in -5..5) for (z in -12..-1) put(ArcSchematicPoint(x, 0, z), "minecraft:gravel")
        for (x in -2..2) for (z in -9..-5) for (y in 1..7) put(ArcSchematicPoint(x, y, z), "minecraft:stone_bricks")
        put(ArcSchematicPoint(1, 1, -1), "minecraft:structure_void")
        put(ArcSchematicPoint(-2, 8, -7), "minecraft:spruce_door[facing=west,half=lower,hinge=left,open=false,powered=false]")
        put(ArcSchematicPoint(-2, 9, -7), "minecraft:spruce_door[facing=west,half=upper,hinge=left,open=false,powered=false]")
        put(ArcSchematicPoint(0, 8, -5), "minecraft:spruce_door[facing=south,half=lower,hinge=right,open=false,powered=false]")
        put(ArcSchematicPoint(0, 9, -5), "minecraft:spruce_door[facing=south,half=upper,hinge=right,open=false,powered=false]")
    }
    val wall = buildMap {
        for (x in -10..10) for (z in -10..-1) put(ArcSchematicPoint(x, 0, z), "minecraft:gravel")
        for (x in -9..9) for (z in -8..-4) for (y in 1..7) put(ArcSchematicPoint(x, y, z), "minecraft:stone_bricks")
        put(ArcSchematicPoint(1, 1, -1), "minecraft:structure_void")
    }
    val south = wall.mapKeys { (p, _) -> ArcSchematicPoint(-p.z, p.y, p.x) }.toMutableMap().apply {
        put(ArcSchematicPoint(8, 10, 9), "minecraft:stone_brick_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]")
    }
    return FortificationSources(FortificationSnapshot(ArcSchematicPoint(-5, 0, -12), ArcSchematicPoint(5, 24, -1), tower, CivsFortifications.TOWER_SHA256),
        FortificationSnapshot(ArcSchematicPoint(-10, 0, -10), ArcSchematicPoint(10, 11, -1), wall, CivsFortifications.WALL_SHA256),
        FortificationSnapshot(ArcSchematicPoint(1, 0, -10), ArcSchematicPoint(10, 11, 10), south, CivsFortifications.WALL_SHA256))
}

private fun fortificationCanonicalSnapshot(template: CivFortificationTemplate) = FortificationSnapshot(
    ArcSchematicPoint(-21, 0, -27), ArcSchematicPoint(5, 24, -1), template.cells.associate { it.position to it.blockData }, template.sourceSha256)

private class FortificationSnapshot(override val minimum: ArcSchematicPoint, override val maximum: ArcSchematicPoint,
    private val data: Map<ArcSchematicPoint, String>, override val fileSha256: String,
    override val origin: ArcSchematicPoint = ArcSchematicPoint(0, 0, 0)) : ArcSchematicSnapshot {
    override val sizeX = maximum.x - minimum.x + 1
    override val sizeY = maximum.y - minimum.y + 1
    override val sizeZ = maximum.z - minimum.z + 1
    override val volume = sizeX.toLong() * sizeY * sizeZ
    override val nonBarrierCount get() = blocks().count { !it.isBarrier }
    override val hasBiomes = false
    override val entityCount = 0
    override val contentSha256 = "0".repeat(64)
    override fun blockAt(x: Int, y: Int, z: Int): ArcSchematicBlock {
        val p = ArcSchematicPoint(x, y, z); val state = data[p] ?: "minecraft:air"
        return ArcSchematicBlock(p, state, state == "minecraft:air", state.substringBefore('[') == "minecraft:barrier")
    }
    override fun blocks() = buildList {
        for (y in minimum.y..maximum.y) for (x in minimum.x..maximum.x) for (z in minimum.z..maximum.z) add(blockAt(x, y, z))
    }
    fun changed(p: ArcSchematicPoint, state: String) = FortificationSnapshot(minimum, maximum, data + (p to state), fileSha256, origin)
    fun withHash(hash: String) = FortificationSnapshot(minimum, maximum, data, hash, origin)
}
