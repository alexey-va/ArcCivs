package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

class CivsWorkstationsTest : FunSpec({
    test("control relocation prefers authored barrels and otherwise reserves a spare connected interior cell") {
        val marker = ArcSchematicPoint(4, 1, 8)
        val machine = ArcSchematicPoint(2, 1, 3)
        val snapshot = WorkstationSnapshot(mapOf(marker to "minecraft:structure_void", machine to "minecraft:furnace[facing=south,lit=false]"))
        val building = workstationBuilding(snapshot)
        val spare = CivsWorkstations.authoredControls(building, snapshot)
        spare.isNotEmpty() shouldBe true
        spare.all { !it.existing && it.position != building.control && it.position != building.entrance } shouldBe true
        val graph = CivsAuthoredInterior(building, snapshot, setOf("furnace", "barrel", "crafting_table"))
        spare.forEach { candidate ->
            val point = ArcSchematicPoint(candidate.position.x - building.anchor.x + snapshot.origin.x,
                candidate.position.y - building.anchor.y + snapshot.origin.y, candidate.position.z - building.anchor.z + snapshot.origin.z)
            graph.interior(point) shouldBe true
            graph.connectedWithout(point) shouldBe true
        }
        val barrel = ArcSchematicPoint(5, 1, 5)
        val withBarrel = WorkstationSnapshot(mapOf(marker to "minecraft:structure_void", machine to "minecraft:furnace[facing=south,lit=false]",
            barrel to "minecraft:barrel[facing=up,open=false]"))
        for (rotation in listOf(0, 90, 180, 270)) {
            val rotated = workstationBuilding(withBarrel, rotation)
            val found = CivsWorkstations.authoredControls(rotated, withBarrel)
            found.any(CivControlCandidate::existing) shouldBe true
            val selected = found.first()
            CivsWorkstations.authoredControls(rotated.copy(control = selected.position), withBarrel) shouldBe emptyList()
        }
    }

    test("a new control barrel never seals the sole authored passage") {
        val snapshot = WorkstationSnapshot(buildMap {
            put(ArcSchematicPoint(4, 1, 8), "minecraft:structure_void")
            for (x in 0..6) for (y in 1..3) if (x != 3 || y == 3) put(ArcSchematicPoint(x, y, 4), "minecraft:stone")
        })
        val building = workstationBuilding(snapshot)
        val graph = CivsAuthoredInterior(building, snapshot, setOf("barrel"))
        graph.connectedWithout(ArcSchematicPoint(3, 1, 4)) shouldBe false
    }

    test("authored workstations follow the source origin and all building rotations instead of the control barrel") {
        val snapshot = WorkstationSnapshot(mapOf(
            ArcSchematicPoint(2, 1, 3) to "minecraft:furnace[facing=south,lit=false]",
            ArcSchematicPoint(4, 1, 3) to "minecraft:furnace[facing=north,lit=false]"))
        for (turns in 0..3) {
            val building = workstationBuilding(snapshot, turns * 90)
            val points = CivsWorkstations.authored(building, snapshot)
            points.isNotEmpty() shouldBe true
            points.map(CivWorkstation::focus).distinct().size shouldBe 2
            points.all { it.material == "minecraft:furnace" } shouldBe true
            points.all { it.standing != building.control && it.focus != building.control &&
                building.bounds.contains(it.standing.x, it.standing.y + 1, it.standing.z) } shouldBe true
            points.mapNotNull(CivWorkstation::focus).toSet() shouldBe setOf(
                CivsWorksiteLayouts.point(building, CivPosition(-1, 1, -6)),
                CivsWorksiteLayouts.point(building, CivPosition(1, 1, -6)))
        }
    }

    test("a crusher uses its real worktable and never treats an authored furnace as a grinder") {
        val snapshot = WorkstationSnapshot(mapOf(ArcSchematicPoint(2, 1, 3) to "minecraft:furnace[facing=south,lit=false]",
            ArcSchematicPoint(4, 1, 3) to "minecraft:crafting_table"))
        val points = CivsWorkstations.authored(workstationBuilding(snapshot).copy(type = BuildingType.CRUSHER), snapshot)
        points.isNotEmpty() shouldBe true
        points.all { it.material == "minecraft:crafting_table" } shouldBe true
    }

    test("fallbacks stay in a roofed connected room and never stand on a barrel or enter a sealed room") {
        val snapshot = WorkstationSnapshot(buildMap {
            put(ArcSchematicPoint(4, 1, 6), "minecraft:barrel[facing=up,open=false]")
            for (x in 0..6) for (y in 1..3) put(ArcSchematicPoint(x, y, 4), "minecraft:stone")
        })
        val building = workstationBuilding(snapshot)
        val points = CivsWorkstations.authored(building, snapshot)
        points.isNotEmpty() shouldBe true
        points.all { it.focus == null && it.material == null } shouldBe true
        // Local z<4 is separated from the authored entrance by a full wall.
        points.all { it.standing.z >= building.anchor.z + 5 - snapshot.origin.z } shouldBe true
        val barrel = CivsWorksiteLayouts.point(building, CivPosition(1, 1, -3))
        points.none { it.standing.x == barrel.x && it.standing.z == barrel.z } shouldBe true
        (points.size <= CivsWorkstations.MAXIMUM_POINTS) shouldBe true
    }

    test("committed cycles rotate actual machines then standing sides while one cycle stays deterministic") {
        val a = CivPosition(1, 64, 1)
        val b = CivPosition(5, 64, 1)
        val points = listOf(CivWorkstation(a.copy(z = 2), a, "minecraft:furnace"),
            CivWorkstation(a.copy(z = 0), a, "minecraft:furnace"),
            CivWorkstation(b.copy(z = 2), b, "minecraft:furnace"),
            CivWorkstation(b.copy(z = 0), b, "minecraft:furnace"))
        val worker = CivWorker(UUID(0, 0), "Resident", UUID(0, 1))
        val selected = (0L..3L).map { cycle -> CivsWorkstations.ordered(points, worker.copy(completedCycles = cycle)).first() }
        selected.map(CivWorkstation::focus) shouldBe listOf(a, b, a, b)
        selected.distinct().size shouldBe 4
        CivsWorkstations.ordered(points, worker) shouldBe CivsWorkstations.ordered(points, worker)
    }

    test("native admission checks only the bounded loaded candidates and rejects changed machines headroom and barrels") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val world = ResidentRuntimeWorld().also(paper.server::addWorld)
                world.loadChunk(0, 0)
                val building = workstationBuilding(WorkstationSnapshot()).copy(anchor = CivPosition(0, 63, 0),
                    bounds = CivBox(CivPosition(0, 63, 0), CivPosition(15, 70, 15)),
                    entrance = CivPosition(0, 64, 0), control = CivPosition(1, 64, 1))
                val worker = CivWorker(UUID(0, 0), "Resident", building.id)
                val city = Settlement(UUID(0, 9), UUID(0, 10), "Workstations", world.uid, CivPosition(0, 64, 0),
                    96, "fixture", Stock(), listOf(building), listOf(worker))
                val focus = CivPosition(5, 64, 5)
                val station = CivWorkstation(CivPosition(5, 64, 6), focus, "minecraft:furnace")
                world.getBlockAt(5, 64, 5).type = Material.FURNACE
                CivsWorkstations.valid(city, building, world, station) shouldBe true
                world.getBlockAt(5, 64, 5).type = Material.STONE
                CivsWorkstations.valid(city, building, world, station) shouldBe false
                world.getBlockAt(5, 64, 5).type = Material.FURNACE
                world.getBlockAt(5, 65, 6).type = Material.STONE
                CivsWorkstations.valid(city, building, world, station) shouldBe false
                world.getBlockAt(5, 65, 6).type = Material.AIR
                world.getBlockAt(5, 63, 6).type = Material.BARREL
                CivsWorkstations.valid(city, building, world, station) shouldBe false
                CivsWorkstations.valid(city, building, world, station.copy(standing = CivPosition(33, 64, 6))) shouldBe false
                world.isChunkLoaded(2, 0) shouldBe false
                world.getBlockAt(5, 63, 6).type = Material.STONE
                world.getBlockAt(4, 65, 6).type = Material.STONE
                val blocked = station.copy(standing = CivPosition(4, 64, 6))
                CivsWorkstations({ CompletableFuture.completedFuture(List(CivsWorkstations.MAXIMUM_PROBES) { blocked } + station) },
                    Logger.getAnonymousLogger(), world = { world }).use { owner ->
                    owner.select(city, worker) shouldBe null
                    owner.select(city, worker.copy(completedCycles = CivsWorkstations.MAXIMUM_PROBES.toLong())) shouldBe station
                }
            }
        }
    }

    test("pending extraction never probes a world and the owner cancels bounded cached requests on close") {
        MockBukkitTestRuntime.open().use {
            val snapshot = WorkstationSnapshot()
            val futures = mutableListOf<CompletableFuture<List<CivWorkstation>>>()
            var worldReads = 0
            val owner = CivsWorkstations({ CompletableFuture<List<CivWorkstation>>().also(futures::add) },
                Logger.getAnonymousLogger(), world = { worldReads++; null })
            val worker = CivWorker(UUID(0, 0), "Resident", UUID(0, 1))
            val building = workstationBuilding(snapshot).copy(id = requireNotNull(worker.assignment))
            val city = Settlement(UUID(0, 2), UUID(0, 3), "Pending", UUID(0, 4), building.anchor,
                96, "fixture", Stock(), listOf(building), listOf(worker))
            try {
                owner.select(city, worker) shouldBe null
                owner.select(city, worker) shouldBe null
                futures.size shouldBe 1
                worldReads shouldBe 0
                for (id in 2L..66L) {
                    val next = building.copy(id = UUID(0, id))
                    owner.select(city.copy(buildings = listOf(next)), worker.copy(assignment = next.id)) shouldBe null
                }
                futures.count { !it.isCancelled } shouldBe 64
            } finally { owner.close() }
            futures.all { it.isCancelled } shouldBe true
            owner.select(city, worker) shouldBe null
            futures.size shouldBe 66
        }
    }
})

private fun workstationBuilding(snapshot: ArcSchematicSnapshot, rotation: Int = 0): CivBuilding {
    val anchor = CivPosition(20, 63, 20)
    fun point(p: ArcSchematicPoint): CivPosition = CivsBlueprintGeometry.rotate(ArcSchematicPoint(
        p.x - snapshot.origin.x, p.y - snapshot.origin.y, p.z - snapshot.origin.z), rotation / 90).let {
        CivPosition(anchor.x + it.x, anchor.y + it.y, anchor.z + it.z)
    }
    val corners = listOf(snapshot.minimum.x, snapshot.maximum.x).flatMap { x ->
        listOf(snapshot.minimum.z, snapshot.maximum.z).flatMap { z ->
            listOf(snapshot.minimum.y, snapshot.maximum.y).map { y -> point(ArcSchematicPoint(x, y, z)) }
        }
    }
    return CivBuilding(UUID(0, 1), BuildingType.SMELTERY, 1, anchor, rotation, "fixture/workstations",
        CivBox(CivPosition(corners.minOf { it.x }, corners.minOf { it.y }, corners.minOf { it.z }),
            CivPosition(corners.maxOf { it.x }, corners.maxOf { it.y }, corners.maxOf { it.z })),
        point(CivsBlueprintGeometry.entrance(snapshot).position), BuildingPhase.ACTIVE,
        control = point(ArcSchematicPoint(4, 1, 8)))
}

private class WorkstationSnapshot(private val extra: Map<ArcSchematicPoint, String> = emptyMap()) : ArcSchematicSnapshot {
    override val minimum = ArcSchematicPoint(0, 0, 0)
    override val maximum = ArcSchematicPoint(6, 4, 8)
    override val origin = ArcSchematicPoint(3, 0, 9)
    override val sizeX = 7
    override val sizeY = 5
    override val sizeZ = 9
    override val volume = sizeX.toLong() * sizeY * sizeZ
    override val nonBarrierCount = volume.toInt()
    override val hasBiomes = false
    override val entityCount = 0
    override val fileSha256 = "a".repeat(64)
    override val contentSha256 = "b".repeat(64)
    override fun blockAt(x: Int, y: Int, z: Int): ArcSchematicBlock {
        require(x in 0..6 && y in 0..4 && z in 0..8)
        val p = ArcSchematicPoint(x, y, z)
        val data = extra[p] ?: when {
            y == 0 || y == 4 -> "minecraft:stone"
            x == 0 || x == 6 || z == 0 || z == 8 && (x != 3 || y == 3) -> "minecraft:stone"
            p == ArcSchematicPoint(4, 1, 8) -> "minecraft:structure_void"
            else -> "minecraft:air"
        }
        return ArcSchematicBlock(p, data, data == "minecraft:air", false)
    }
    override fun blocks(): List<ArcSchematicBlock> = buildList {
        for (y in 0..4) for (x in 0..6) for (z in 0..8) add(blockAt(x, y, z))
    }
}
