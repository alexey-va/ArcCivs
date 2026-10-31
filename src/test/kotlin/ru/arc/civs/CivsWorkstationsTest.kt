package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Barrel
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

class CivsWorkstationsTest : FunSpec({
    for (machine in listOf("furnace", "blast_furnace", "smithing_table"))
        test("a three-block-high open forge selects its real $machine for the captured worker cycle") {
            failOnUnsupportedMockBukkitOperation {
                MockBukkitTestRuntime.open().use { paper ->
                    val snapshot = OpenForgeSnapshot(machine)
                    val building = openForgeBuilding()
                    val world = ResidentRuntimeWorld().also(paper.server::addWorld)
                    installOpenForge(world, building, snapshot)
                    val worker = CivWorker(UUID.fromString("76f2af5b-5b5f-4a21-8779-d8df9ec3ea28"), "Asya", building.id,
                        completedCycles = 55, nextDeliveryAt = 1)
                    val city = openForgeCity(world.uid, building, worker)
                    val before = city
                    val points = CivsWorkstations.authored(building, snapshot)
                    points.isNotEmpty() shouldBe true
                    val focus = CivPosition(-1838, 65, -2766)
                    val barrel = CivsWorksiteLayouts.point(building, CivPosition(-2, 1, -5))
                    points.all { it.focus == focus && it.material == "minecraft:$machine" } shouldBe true
                    points.all { building.bounds.contains(it.standing.x, it.standing.y + 1, it.standing.z) } shouldBe true
                    points.none { it.focus == building.control || it.standing == building.control ||
                        it.standing.x == barrel.x && it.standing.z == barrel.z } shouldBe true
                    CivsWorkstations({ CompletableFuture.completedFuture(points) }, Logger.getAnonymousLogger(), world = { world }).use { owner ->
                        val selected = requireNotNull(owner.select(city, worker))
                        selected.focus shouldBe focus
                        CivsWorkstations.valid(city, building, world, selected) shouldBe true
                        citizenCanReachBlock(world, selected.standing.location(world).add(0.0, 1.5, 0.0), focus) shouldBe true
                        world.getBlockAt(selected.standing.x, selected.standing.y - 1, selected.standing.z).type shouldBe Material.STONE
                    }
                    city shouldBe before
                    worker.completedCycles shouldBe 55L
                }
            }
        }

    for (machine in listOf("smithing_table", "crafting_table"))
        test("a blacksmith retains its existing $machine preference ahead of a furnace") {
            val snapshot = WorkstationSnapshot(mapOf(ArcSchematicPoint(2, 1, 3) to "minecraft:$machine",
                ArcSchematicPoint(4, 1, 3) to "minecraft:furnace[facing=east,lit=false]"))
            val points = CivsWorkstations.authored(workstationBuilding(snapshot).copy(type = BuildingType.BLACKSMITH), snapshot)
            points.isNotEmpty() shouldBe true
            points.first().material shouldBe "minecraft:$machine"
        }

    for (machine in listOf("smithing_table", "crafting_table"))
        test("blacksmith selection keeps $machine ahead of furnace across committed cycles") {
            failOnUnsupportedMockBukkitOperation {
                MockBukkitTestRuntime.open().use { paper ->
                    val snapshot = WorkstationSnapshot(mapOf(ArcSchematicPoint(2, 1, 3) to "minecraft:$machine",
                        ArcSchematicPoint(4, 1, 3) to "minecraft:furnace[facing=east,lit=false]"))
                    val building = workstationBuilding(snapshot).copy(type = BuildingType.BLACKSMITH)
                    val world = ResidentRuntimeWorld().also(paper.server::addWorld)
                    installOpenForge(world, building, snapshot)
                    val worker = CivWorker(UUID.fromString("76f2af5b-5b5f-4a21-8779-d8df9ec3ea28"), "Asya", building.id)
                    val points = CivsWorkstations.authored(building, snapshot)
                    points.any { it.material == "minecraft:furnace" } shouldBe true
                    CivsWorkstations({ CompletableFuture.completedFuture(points) }, Logger.getAnonymousLogger(), world = { world }).use { owner ->
                        for (cycle in 55L..62L) {
                            val current = worker.copy(completedCycles = cycle)
                            val city = openForgeCity(world.uid, building, current)
                            requireNotNull(owner.select(city, current)).material shouldBe "minecraft:$machine"
                        }
                    }
                }
            }
        }

    test("an open forge does not admit unfocused fallback positions or relocate a control into its unroofed floor") {
        val snapshot = OpenForgeSnapshot(null)
        val building = openForgeBuilding()
        CivsWorkstations.authored(building, snapshot) shouldBe emptyList()
        CivsWorkstations.authoredControls(building, snapshot) shouldBe emptyList()
        CivsWorkstations.authoredControls(building, OpenForgeSnapshot("furnace")) shouldBe emptyList()
    }

    test("a valid forge never grants a pickaxe while its accepted batch is missing two iron") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val building = openForgeBuilding()
                val world = paper.addSimpleWorld("open-forge-inputs")
                installOpenForge(world, building, OpenForgeSnapshot("furnace"))
                val control = requireNotNull(building.control)
                val controlBlock = world.getBlockAt(control.x, control.y, control.z)
                controlBlock.type = Material.AIR
                controlBlock.type = Material.BARREL
                val worker = CivsEquipment.collectSupplies(CivWorker(UUID(148, 55), "Smith", building.id,
                    completedCycles = 55, nextDeliveryAt = 1), mapOf("IRON_INGOT" to 1L, "STICK" to 2L))
                val city = openForgeCity(world.uid, building, worker)
                val recipe = CivsCarpentry.recipes.single { it.id == "blacksmith/iron_pickaxe" }
                val job = CivCraftJob(UUID.randomUUID(), worker.id, building.id, worker.completedCycles, recipe.id,
                    CivsCarpentry.VERSION, recipe.material, recipe.amount, recipe.inputs, recipe.material, building.id)
                val before = city
                CivsCarpentry.valid(city, worker, job) shouldBe true
                CivsEquipment.storageInputs(worker, job.inputs) shouldBe mapOf("IRON_INGOT" to 2L)
                val barrel = controlBlock.state as Barrel
                CivsStorage(paper.createSimplePlugin("OpenForgeInputs")) { emptyArray() }.use { storage ->
                    val receipt = UUID.randomUUID()
                    storage.reserve(city, receipt)
                    shouldThrow<CivsRejection> { storage.prepareProduction(city, building, worker, job) }.reason shouldBe "materials-missing"
                    storage.unlock(receipt)
                }
                barrel.inventory.isEmpty shouldBe true
                CivsEquipment.plainCount(worker, "IRON_PICKAXE") shouldBe 0
                CivsEquipment.plainCount(worker, "IRON_INGOT") shouldBe 1
                worker.completedCycles shouldBe 55L
                city shouldBe before
            }
        }
    }

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

private fun openForgeBuilding() = CivBuilding(UUID.fromString("054f70ce-b78b-492d-a62a-852fc102067f"),
    BuildingType.BLACKSMITH, 1, CivPosition(-1833, 64, -2763), 0, "fixture/open-forge",
    CivBox(CivPosition(-1839, 64, -2775), CivPosition(-1831, 66, -2764)), CivPosition(-1833, 65, -2763),
    BuildingPhase.ACTIVE, CivPosition(-1832, 65, -2764), production = "IRON_PICKAXE")

private fun openForgeCity(world: UUID, building: CivBuilding, worker: CivWorker) = Settlement(UUID(148, 1), UUID(148, 2),
    "Open forge", world, building.entrance, 48, "fixture", Stock(), listOf(building), listOf(worker),
    inventory = CivInventory(CivStorageMode.PHYSICAL), autonomy = CivAutonomy())

private fun installOpenForge(world: World, building: CivBuilding, snapshot: ArcSchematicSnapshot) {
    for (x in (building.bounds.min.x shr 4) - 1..(building.bounds.max.x shr 4) + 1)
        for (z in (building.bounds.min.z shr 4) - 1..(building.bounds.max.z shr 4) + 1) world.loadChunk(x, z)
    for (cell in snapshot.blocks()) {
        val p = CivsWorksiteLayouts.point(building, CivPosition(cell.position.x - snapshot.origin.x,
            cell.position.y - snapshot.origin.y, cell.position.z - snapshot.origin.z))
        world.getBlockAt(p.x, p.y, p.z).blockData = Bukkit.createBlockData(
            if (cell.blockData == "minecraft:structure_void") "minecraft:barrel[facing=north,open=false]" else cell.blockData)
    }
}

/** Same low bounds, entry, marker and furnace coordinates as the captured starter forge; no roof. */
private class OpenForgeSnapshot(private val machine: String?) : ArcSchematicSnapshot {
    override val minimum = ArcSchematicPoint(0, 0, 0)
    override val maximum = ArcSchematicPoint(8, 2, 11)
    override val origin = ArcSchematicPoint(6, 0, 12)
    override val sizeX = 9
    override val sizeY = 3
    override val sizeZ = 12
    override val volume = sizeX.toLong() * sizeY * sizeZ
    override val nonBarrierCount = volume.toInt()
    override val hasBiomes = false
    override val entityCount = 0
    override val fileSha256 = "c".repeat(64)
    override val contentSha256 = "d".repeat(64)
    override fun blockAt(x: Int, y: Int, z: Int): ArcSchematicBlock {
        require(x in 0..8 && y in 0..2 && z in 0..11)
        val p = ArcSchematicPoint(x, y, z)
        val data = when {
            y == 0 -> "minecraft:stone"
            p == ArcSchematicPoint(7, 1, 11) -> "minecraft:structure_void"
            p == ArcSchematicPoint(4, 1, 7) -> "minecraft:barrel[facing=north,open=false]"
            p == ArcSchematicPoint(1, 1, 9) && machine != null -> "minecraft:$machine" +
                if (machine.endsWith("furnace")) "[facing=east,lit=false]" else ""
            else -> "minecraft:air"
        }
        return ArcSchematicBlock(p, data, data == "minecraft:air", false)
    }
    override fun blocks(): List<ArcSchematicBlock> = buildList {
        for (y in 0..2) for (x in 0..8) for (z in 0..11) add(blockAt(x, y, z))
    }
}

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
