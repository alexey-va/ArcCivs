package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.block.data.type.Leaves
import org.bukkit.inventory.ItemStack
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class CivsVillageVegetationTest : FunSpec({
    fun log(x: Int, y: Int, z: Int) = CivVegetationCell(CivPosition(x, y, z), "minecraft:oak_log[axis=y]", true, false)
    fun leaf(x: Int, y: Int, z: Int) = CivVegetationCell(CivPosition(x, y, z),
        "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]", false, true)

    test("natural crown identifies its connected trunk and unproven timber stays intact") {
        val cells = listOf(log(0, 64, 0), log(0, 65, 0), leaf(1, 65, 0), leaf(2, 65, 0), log(9, 64, 9))
        val selected = civsVillageVegetationCandidates(cells.associateBy { it.position })
        selected.toSet() shouldBe cells.take(4).toSet()
        selected.take(2).all { it.log } shouldBe true
    }

    test("a retained natural before image identifies a stump without making arbitrary logs removable") {
        val stump = log(0, 64, 0)
        val other = log(3, 64, 0)
        val found = listOf(stump, other).associateBy { it.position }
        civsVillageVegetationCandidates(found) shouldBe emptyList()
        civsVillageVegetationCandidates(found, mapOf(stump.position to stump.before)) shouldBe listOf(stump)
        civsVillageVegetationCandidates(found, mapOf(stump.position to "minecraft:birch_log[axis=y]")) shouldBe emptyList()
    }

    test("a detached stump capture rechecks newly attached support geometry before removal") {
        VillageVegetationFixture().use { h ->
            val stump = CivPosition(-5, 64, -5)
            h.put(stump.copy(y = 63), Material.DIRT)
            h.put(stump, Material.OAK_LOG)
            h.startCapture()
            h.finishCapture()
            h.put(stump.copy(y = 65), Material.BARREL)
            h.drain()
            h.type(stump) shouldBe Material.OAK_LOG
            h.type(stump.copy(y = 65)) shouldBe Material.BARREL
            h.notifications shouldBe emptySet()
        }
    }

    test("village scope covers spatial plan and outlying buildings while respecting its territory") {
        val building = CivBuilding(UUID(0, 1), BuildingType.TOWN_HALL, 1, CivPosition(0, 64, 0), 0, "test",
            CivBox(CivPosition(-3, 63, -3), CivPosition(3, 68, 3)), CivPosition(0, 64, 4), BuildingPhase.ACTIVE)
        val city = Settlement(UUID(0, 2), UUID(0, 3), "Village", UUID(0, 4), CivPosition(0, 64, 0), 80,
            "nordic", Stock(), listOf(building), emptyList(), autonomy = CivAutonomy(layoutHalfWidth = 24))
        civsVillageVegetationColumn(city, 24, -24) shouldBe true
        civsVillageVegetationColumn(city, 25, 0) shouldBe false
        val edge = building.copy(id = UUID(0, 5), bounds = CivBox(CivPosition(30, 63, 0), CivPosition(35, 69, 5)))
        civsVillageVegetationColumn(city.copy(buildings = city.buildings + edge), 37, 0) shouldBe true
        civsVillageVegetationColumn(city.copy(radius = 12), 13, 0) shouldBe false
        civsVillageVegetationArea(city.copy(autonomy = null)) shouldBe null
        city.autonomy?.paused shouldBe false
        civsVillageVegetationColumn(city.copy(autonomy = city.autonomy?.copy(paused = true)), 24, 0) shouldBe true
    }

    test("a rejected capture releases preparation and the next sweep can clear the tree") {
        VillageVegetationFixture().use { h ->
            val tree = h.tree(CivPosition(-5, 64, -5))
            h.rejectCaptures = 1
            h.startCapture()
            h.pending.size shouldBe 0
            h.now = 1_000
            h.startCapture()
            h.finishCapture()
            h.drain()
            h.captureAttempts shouldBe 2
            tree.forEach { h.type(it) shouldBe Material.AIR }
            h.notifications shouldBe tree.toSet()
        }
    }

    test("a temporarily blocked trunk retains its proof through the crown across a fresh owner") {
        VillageVegetationFixture().use { h ->
            val root = CivPosition(-5, 64, -5)
            val tree = h.tree(root)
            val independent = h.tree(CivPosition(5, 64, -5))
            h.blocked += root
            h.startCapture()
            h.finishCapture()
            h.drain()
            tree.forEachIndexed { i, p -> h.type(p) shouldBe if (i == tree.lastIndex) Material.OAK_LEAVES else Material.OAK_LOG }
            independent.forEach { h.type(it) shouldBe Material.AIR }
            h.notifications shouldBe independent.toSet()
            h.blocked.clear()
            h.restartOwner()
            h.startCapture()
            h.finishCapture()
            h.drain()
            tree.forEach { h.type(it) shouldBe Material.AIR }
            h.notifications shouldBe (tree + independent).toSet()
        }
    }

    test("a delayed old capture cannot clear the productive grove of a newly added forester") {
        VillageVegetationFixture().use { h ->
            val tree = h.tree(CivPosition(-5, 64, -5))
            h.startCapture()
            val mill = h.city.buildings.single().copy(id = UUID(0, 9), type = BuildingType.FORESTER,
                anchor = CivPosition(4, 64, 4), bounds = CivBox(CivPosition(4, 63, 4), CivPosition(5, 69, 5)),
                entrance = CivPosition(4, 64, 6), control = null,
                schematicId = "civs/minecolonies/nordic/fundamentals/lumberjack1")
            requireNotNull(CivsWorksiteLayouts.site(mill)).has("forester") shouldBe true
            h.city = h.city.copy(buildings = h.city.buildings + mill)
            h.finishCapture()
            h.owner.pulse(listOf(h.city)) // Discard the old protection frame before any native writes.
            h.notifications shouldBe emptySet()
            h.startCapture()
            h.finishCapture()
            h.drain()
            tree.forEachIndexed { i, p -> h.type(p) shouldBe if (i == tree.lastIndex) Material.OAK_LEAVES else Material.OAK_LOG }
            h.notifications shouldBe emptySet()
            h.captureAttempts shouldBe 2
        }
    }

    test("one column protects inclusive construction and planned bounds across multiple heights") {
        VillageVegetationFixture().use { h ->
            val hall = h.city.buildings.single()
            val constructing = hall.copy(id = UUID(0, 10), phase = BuildingPhase.CONSTRUCTING,
                bounds = CivBox(CivPosition(-5, 64, -5), CivPosition(-5, 65, -5)))
            val planned = hall.copy(id = UUID(0, 11),
                bounds = CivBox(CivPosition(-5, 67, -5), CivPosition(-5, 68, -5)))
            h.city = h.city.copy(buildings = h.city.buildings + constructing,
                construction = CivConstruction(UUID(0, 12), planned.id, 1, 1, 0,
                    ConstructionPhase.CONSTRUCTING, Stock(), plannedBuilding = planned))
            val leaves = (-5..-4).flatMap { x -> (63..70).map { y -> CivPosition(x, y, -5) } }
            leaves.forEach { h.put(it, Material.OAK_LEAVES) }
            h.startCapture()
            h.finishCapture()
            h.drain()
            val protectedCells = leaves.filter { it.x == -5 && it.y in setOf(64, 65, 67, 68) }.toSet()
            leaves.forEach { h.type(it) shouldBe if (it in protectedCells) Material.OAK_LEAVES else Material.AIR }
            h.notifications shouldBe leaves.toSet() - protectedCells
        }
    }

    test("a detached sweep clears natural trees and preserves authored timber leaves inventories and denied cells") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val plugin = paper.createSimplePlugin("VillageVegetationTest")
            val world = CivsWorksiteWorldFixture("village-vegetation")
            paper.server.addWorld(world)
            for (x in -1..0) for (z in -1..0) world.loadChunk(x, z)
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val hall = CivBuilding(UUID(0, 1), BuildingType.TOWN_HALL, 1, CivPosition(0, 64, 0), 0, "test",
                CivBox(CivPosition(-2, 63, -2), CivPosition(2, 69, 2)), CivPosition(0, 64, 3),
                BuildingPhase.ACTIVE, CivPosition(1, 64, 0))
            val city = Settlement(UUID(0, 2), UUID(0, 3), "Village", world.uid, CivPosition(0, 64, 0), 8,
                "nordic", Stock(), listOf(hall), emptyList(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                autonomy = CivAutonomy(layoutHalfWidth = 6))
            fun put(p: CivPosition, material: Material) { world.getBlockAt(p.x, p.y, p.z).type = material }
            val trunk = listOf(CivPosition(-3, 64, 0), CivPosition(-3, 65, 0))
            val crown = CivPosition(-2, 66, 0)
            val authored = CivPosition(1, 64, 1)
            val placedLeaf = CivPosition(3, 66, 0)
            val denied = listOf(CivPosition(4, 64, 4), CivPosition(4, 65, 4), CivPosition(4, 66, 4))
            val stump = CivPosition(-5, 64, 3)
            val cherryStump = CivPosition(-5, 64, 5)
            val strippedTimber = CivPosition(-3, 64, 5)
            val support = CivPosition(-5, 64, 0)
            val timberOnStone = CivPosition(-5, 64, -3)
            listOf(stump, support, timberOnStone).forEach { put(it, Material.OAK_LOG) }
            put(cherryStump, Material.CHERRY_LOG)
            put(strippedTimber, Material.STRIPPED_OAK_LOG)
            listOf(stump, cherryStump, strippedTimber, support, authored).forEach { put(it.copy(y = it.y - 1), Material.DIRT) }
            put(support.copy(y = support.y + 1), Material.BARREL)
            put(timberOnStone.copy(y = timberOnStone.y - 1), Material.STONE)
            (trunk + authored + denied.take(2)).forEach { put(it, Material.OAK_LOG) }
            listOf(crown, placedLeaf, denied.last()).forEach { put(it, Material.OAK_LEAVES) }
            world.getBlockAt(placedLeaf.x, placedLeaf.y, placedLeaf.z).let { block ->
                block.blockData = (block.blockData as Leaves).also { it.isPersistent = true }
            }
            val control = requireNotNull(hall.control)
            put(control, Material.BARREL)
            val inventory = (world.getBlockAt(control.x, control.y, control.z).state as Barrel).inventory
            inventory.setItem(26, ItemStack(Material.BREAD, 37))
            val original = inventory.storageContents.map { it?.clone() }
            val authoredData = world.getBlockAt(authored.x, authored.y, authored.z).blockData.asString
            val retained = CivPlacementPlan(hall.id, city.id, world.uid, hall,
                listOf(CivPlanBlock(authored, "minecraft:air", authoredData)))
            val pending = ArrayDeque<Runnable>()
            val notifications = mutableSetOf<CivPosition>()
            CivsVillageVegetation(plugin, tasks, Executor { pending += it },
                { CompletableFuture.completedFuture(listOf(retained)) }, { city },
                { _, block -> block.x != 4 }, { _, _ -> false }, { _, _ -> false },
                { _, positions -> notifications += positions }, clock = { 0L }).use { owner ->
                owner.pulse(listOf(city))
                paper.server.scheduler.performOneTick()
                pending.size shouldBe 1
                val background = Thread(pending.removeFirst(), "vegetation-detached-test")
                background.start()
                background.join(10_000)
                background.isAlive shouldBe false
                paper.server.scheduler.performOneTick()
                repeat(30) { owner.pulse(listOf(city)); paper.server.scheduler.performOneTick() }
                (trunk + crown + stump + cherryStump).forEach { world.getBlockAt(it.x, it.y, it.z).type shouldBe Material.AIR }
                world.getBlockAt(strippedTimber.x, strippedTimber.y, strippedTimber.z).type shouldBe Material.STRIPPED_OAK_LOG
                world.getBlockAt(support.x, support.y, support.z).type shouldBe Material.OAK_LOG
                world.getBlockAt(timberOnStone.x, timberOnStone.y, timberOnStone.z).type shouldBe Material.OAK_LOG
                world.getBlockAt(authored.x, authored.y, authored.z).blockData.asString shouldBe authoredData
                world.getBlockAt(placedLeaf.x, placedLeaf.y, placedLeaf.z).type shouldBe Material.OAK_LEAVES
                denied.forEachIndexed { index, p -> world.getBlockAt(p.x, p.y, p.z).type shouldBe
                    if (index == 2) Material.OAK_LEAVES else Material.OAK_LOG }
                inventory.storageContents.toList() shouldBe original
                notifications shouldBe (trunk + crown + stump + cherryStump).toSet()
                city.revision shouldBe 0
                city.stock shouldBe Stock()
                pending.size shouldBe 0
            }
            tasks.close()
        }
    }
})

private class VillageVegetationFixture : AutoCloseable {
    private val paper = MockBukkitTestRuntime.open(CivsWorksiteServerFixture())
    private val plugin = paper.createSimplePlugin("VillageVegetationRegression")
    private val world = CivsWorksiteWorldFixture("village-vegetation-regression").also(paper.server::addWorld)
    private val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    var city = Settlement(UUID(0, 2), UUID(0, 3), "Village", world.uid, CivPosition(0, 64, 0), 8,
        "nordic", Stock(), listOf(CivBuilding(UUID(0, 1), BuildingType.TOWN_HALL, 1, CivPosition(0, 64, 0), 0, "test",
            CivBox(CivPosition(-2, 63, -2), CivPosition(2, 69, 2)), CivPosition(0, 64, 3), BuildingPhase.ACTIVE)),
        emptyList(), autonomy = CivAutonomy(layoutHalfWidth = 6))
    var now = 0L
    var rejectCaptures = 0
    var captureAttempts = 0
    val pending = ArrayDeque<Runnable>()
    val blocked = hashSetOf<CivPosition>()
    val notifications = hashSetOf<CivPosition>()
    var owner = newOwner()
        private set

    init { for (x in -1..0) for (z in -1..0) world.loadChunk(x, z) }

    private fun newOwner() = CivsVillageVegetation(plugin, tasks, Executor {
        captureAttempts++
        if (rejectCaptures > 0) { rejectCaptures--; throw RejectedExecutionException("full fixture queue") }
        pending += it
    }, { current -> CompletableFuture.completedFuture(current.buildings.map {
        CivPlacementPlan(it.id, current.id, current.worldId, it, emptyList())
    }) }, { city }, { _, _ -> true }, { _, p -> p in blocked }, { _, _ -> false },
        { _, positions -> notifications += positions }, clock = { now }, nanoTime = { 0L })

    fun tree(root: CivPosition): List<CivPosition> = ((0..3).map { root.copy(y = root.y + it) } +
        root.copy(y = root.y + 4)).also { cells -> cells.forEachIndexed { i, p ->
        world.getBlockAt(p.x, p.y, p.z).type = if (i == cells.lastIndex) Material.OAK_LEAVES else Material.OAK_LOG
    } }

    fun type(p: CivPosition): Material = world.getBlockAt(p.x, p.y, p.z).type

    fun put(p: CivPosition, material: Material) { world.getBlockAt(p.x, p.y, p.z).type = material }

    fun startCapture() { owner.pulse(listOf(city)); paper.server.scheduler.performOneTick() }

    fun finishCapture() {
        pending.size shouldBe 1
        val worker = Thread(pending.removeFirst(), "vegetation-regression-worker")
        worker.start()
        worker.join(10_000)
        worker.isAlive shouldBe false
        paper.server.scheduler.performOneTick()
    }

    fun drain() { repeat(4) { owner.pulse(listOf(city)); paper.server.scheduler.performOneTick() } }

    fun restartOwner() { owner.close(); owner = newOwner() }

    override fun close() { owner.close(); tasks.close(); paper.close() }
}
