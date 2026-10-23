package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.block.Chest
import org.bukkit.inventory.ItemStack
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

class CivsAutonomousPlacementTest : FunSpec({
    test("candidate rings preserve full upgrade volume and each existing approach") {
        val city = spatialCity(UUID.randomUUID())
        val small = spatialBlueprint(UUID.randomUUID(), city.worldId, CivPosition(0, 0, 0), 0, 1).building
        val large = small.copy(level = 3, bounds = CivBox(CivPosition(-8, 0, -12), CivPosition(8, 10, -1)))
        val choices = CivsAutonomousGeometry.candidates(city, small, large)
        choices.isNotEmpty() shouldBe true
        (choices.size <= 64) shouldBe true
        choices shouldBe CivsAutonomousGeometry.candidates(city, small, large)
        choices.forEach { choice ->
            val b = choice.building
            civsBoxVolume(b.bounds) shouldBe civsBoxVolume(large.bounds)
            CivsAutonomousGeometry.approach(city.buildings.single()).none {
                CivsAutonomousGeometry.horizontalContains(b.bounds, it)
            } shouldBe true
            CivsAutonomousGeometry.overlaps(b.bounds, city.buildings.single().bounds, 2) shouldBe false
        }
    }

    test("search chooses and fully captures a connected native plot without changing a block") {
        spatialScenario { s ->
            val future = s.planner.search(s.city, BuildingType.HOUSE, 1, UUID.randomUUID())
            s.finish(future)
            val design = future.join()
            val selected = design.blueprint
            selected.building.anchor.y shouldBe 64
            val capture = s.worldAccess.captureAutonomousSite(s.city.id, s.city.owner, s.city.worldId,
                selected.building, selected.cells, design.terrain)
            s.finish(capture)
            capture.join().building shouldBe selected.building
            val point = selected.building.control!!
            s.world.getBlockAt(point.x, point.y, point.z).type shouldBe Material.AIR
            selected.cells.forEach { cell ->
                val p = cell.position
                s.world.getBlockAt(p.x, p.y, p.z).type shouldBe if (p.y == 64) Material.GRASS_BLOCK else Material.AIR
            }
            s.planner.debug()["jobs"] shouldBe 0
        }
    }

    test("preparing an existing site preserves its pinned geometry and refreshes changed terrain") {
        spatialScenario { s ->
            val blueprint = spatialBlueprint(UUID.randomUUID(), s.city.worldId, CivPosition(18, 64, 10), 90, 1)
            val site = blueprint.building.copy(phase = BuildingPhase.SITE)
            s.city = s.city.copy(autonomy = CivAutonomy(), buildings = s.city.buildings + site)
            val control = site.control!!
            s.world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
            val changed = site.bounds.min.copy(y = 65)
            val mound = s.world.getBlockAt(changed.x, changed.y, changed.z)
            mound.type = Material.DIRT
            s.world.getBlockAt(changed.x, 66, changed.z).type = Material.GRASS_BLOCK
            val original = mound.blockData.asString

            val first = s.planner.prepareExisting(s.city, blueprint)
            s.finish(first)
            val oldTerrain = first.join().terrain!!
            oldTerrain.blocks.single { it.position == changed }.before shouldBe original

            mound.type = Material.STONE
            val current = mound.blockData.asString
            val added = changed.copy(y = 67)
            val addedBlock = s.world.getBlockAt(added.x, added.y, added.z)
            addedBlock.type = Material.GRASS_BLOCK
            oldTerrain.blocks.any { it.position == added } shouldBe false
            val stale = s.worldAccess.capture(s.city.id, s.city.owner, s.city.worldId,
                blueprint.building, blueprint.cells, site, oldTerrain)
            s.finish(stale)
            spatialFailure(stale) shouldBe "world-changed"

            val refreshed = s.planner.prepareExisting(s.city, blueprint)
            s.finish(refreshed)
            val design = refreshed.join()
            design.blueprint.building.anchor shouldBe site.anchor
            design.blueprint.building.rotation shouldBe site.rotation
            design.blueprint.building.control shouldBe site.control
            design.blueprint.building.entrance shouldBe site.entrance
            design.blueprint.building.bounds shouldBe site.bounds
            val terrain = design.terrain!!
            terrain.groundY shouldBe site.anchor.y
            terrain.blocks.single { it.position == changed }.before shouldBe current
            terrain.blocks.single { it.position == changed }.after shouldBe "minecraft:air"
            terrain.blocks.single { it.position == added }.before shouldBe addedBlock.blockData.asString
            terrain.blocks.any { it.position == control } shouldBe false
            oldTerrain.blocks.single { it.position == changed }.before shouldBe original

            val captured = s.worldAccess.capture(s.city.id, s.city.owner, s.city.worldId,
                design.blueprint.building, design.blueprint.cells, site, terrain)
            s.finish(captured)
            captured.join().terrain shouldBe terrain
            s.city.building(site.id) shouldBe site
            s.world.getBlockAt(control.x, control.y, control.z).type shouldBe Material.BARREL
            mound.type shouldBe Material.STONE
            addedBlock.type shouldBe Material.GRASS_BLOCK
        }
    }

    test("native terrain rejects water steep ground and a player wall without clearing them") {
        spatialScenario { s ->
            val b = spatialBlueprint(UUID.randomUUID(), s.city.worldId, CivPosition(18, 64, 10), 0, 1).building
            val p = b.bounds.min
            s.world.getBlockAt(p.x, 64, p.z).type = Material.WATER
            val wet = s.worldAccess.autonomousSurface(s.city, b)
            s.finish(wet)
            spatialFailure(wet) shouldBe "autonomy-terrain"
            s.world.getBlockAt(p.x, 64, p.z).type = Material.GRASS_BLOCK
            s.world.getBlockAt(p.x, 65, p.z).type = Material.DIRT
            s.world.getBlockAt(p.x, 66, p.z).type = Material.GRASS_BLOCK
            val steep = s.worldAccess.autonomousSurface(s.city, b)
            s.finish(steep)
            spatialFailure(steep) shouldBe "autonomy-slope"
            s.world.getBlockAt(p.x, 65, p.z).type = Material.AIR
            s.world.getBlockAt(p.x, 66, p.z).type = Material.AIR
            s.world.getBlockAt(p.x, 65, p.z).type = Material.OAK_PLANKS
            val occupied = s.worldAccess.validateAutonomousSite(s.city, b)
            s.finish(occupied)
            spatialFailure(occupied) shouldBe "autonomy-terrain"
            s.world.getBlockAt(p.x, 65, p.z).type shouldBe Material.OAK_PLANKS
        }
    }

    test("a late foreign block is refused by the final automatic capture") {
        spatialScenario { s ->
            val blueprint = spatialBlueprint(UUID.randomUUID(), s.city.worldId, CivPosition(18, 64, 10), 0, 1)
            val b = blueprint.building
            val checked = s.worldAccess.validateAutonomousSite(s.city, b)
            s.finish(checked)
            checked.join() shouldBe Unit
            val p = b.bounds.min.copy(y = 65)
            s.world.getBlockAt(p.x, p.y, p.z).type = Material.BRICKS
            val captured = s.worldAccess.captureAutonomousSite(s.city.id, s.city.owner, s.city.worldId, b, blueprint.cells)
            s.finish(captured)
            spatialFailure(captured) shouldBe "autonomy-terrain"
            s.world.getBlockAt(p.x, p.y, p.z).type shouldBe Material.BRICKS
        }
    }

    test("a foreign barrel appearing at the new control marker cannot be adopted") {
        spatialScenario { s ->
            val blueprint = spatialBlueprint(UUID.randomUUID(), s.city.worldId, CivPosition(18, 64, 10), 0, 1)
            val point = blueprint.building.control!!
            val block = s.world.getBlockAt(point.x, point.y, point.z)
            block.type = Material.BARREL
            (block.state as Barrel).inventory.setItem(0, ItemStack(Material.DIAMOND, 1))
            val captured = s.worldAccess.captureAutonomousSite(s.city.id, s.city.owner, s.city.worldId,
                blueprint.building, blueprint.cells)
            s.finish(captured)
            spatialFailure(captured) shouldBe "autonomy-terrain"
            block.type shouldBe Material.BARREL
            (block.state as Barrel).inventory.getItem(0)?.type shouldBe Material.DIAMOND
        }
    }

    test("autonomous upgrade preserves its own walls but refuses foreign expansion and filled containers") {
        spatialScenario { s ->
            s.city = s.city.copy(autonomy = CivAutonomy())
            val old = s.city.buildings.single()
            for (x in old.bounds.min.x..old.bounds.max.x) for (y in old.bounds.min.y..old.bounds.max.y)
                for (z in old.bounds.min.z..old.bounds.max.z) s.world.getBlockAt(x, y, z).type = Material.OAK_PLANKS
            val marker = old.control!!
            s.world.getBlockAt(marker.x, marker.y, marker.z).type = Material.BARREL
            val upgrade = old.copy(level = 2, phase = BuildingPhase.CONSTRUCTING,
                bounds = old.bounds.copy(max = old.bounds.max.copy(x = old.bounds.max.x + 1)))
            val cells = spatialCells(upgrade)
            val accepted = s.worldAccess.captureSite(s.city.id, s.city.owner, s.city.worldId, upgrade, cells, old)
            s.finish(accepted)
            accepted.isCompletedExceptionally shouldBe false

            val outside = s.world.getBlockAt(upgrade.bounds.max.x, 65, -3)
            outside.type = Material.BRICKS
            val obstructed = s.worldAccess.captureSite(s.city.id, s.city.owner, s.city.worldId, upgrade, cells, old)
            s.finish(obstructed)
            spatialFailure(obstructed) shouldBe "autonomy-terrain"
            outside.type shouldBe Material.BRICKS
            outside.type = Material.AIR

            val inside = s.world.getBlockAt(0, 65, -4)
            inside.type = Material.CHEST
            (inside.state as Chest).inventory.setItem(0, ItemStack(Material.DIAMOND, 1))
            val occupied = s.worldAccess.captureSite(s.city.id, s.city.owner, s.city.worldId, upgrade, cells, old)
            s.finish(occupied)
            spatialFailure(occupied) shouldBe "container-not-empty"
            (inside.state as Chest).inventory.getItem(0)?.type shouldBe Material.DIAMOND

            val drifted = s.worldAccess.revalidate(accepted.join(), s.city.owner)
            s.finish(drifted)
            spatialFailure(drifted) shouldBe "autonomy-terrain"
        }
    }

    test("route proof cannot cross a wall enclosing the only entrance") {
        spatialScenario { s ->
            val b = spatialBlueprint(UUID.randomUUID(), s.city.worldId, CivPosition(0, 64, 18), 180, 1).building
            val target = b.entrance
            for (dx in -1..1) for (dz in -1..1) if (dx != 0 || dz != 0)
                for (y in 65..68) s.world.getBlockAt(target.x + dx, y, target.z + dz).type = Material.BRICKS
            val route = s.worldAccess.autonomousRoute(s.city, b, s.city.buildings.single().entrance, Executor(Runnable::run))
            s.finish(route)
            (spatialFailure(route) in setOf("autonomy-no-path", "autonomy-search-budget")) shouldBe true
        }
    }

    test("large terrain validation yields to the next tick and close cancels it") {
        spatialScenario { s ->
            val b = spatialBlueprint(UUID.randomUUID(), s.city.worldId, CivPosition(18, 64, 10), 0, 1).building.copy(
                bounds = CivBox(CivPosition(10, 64, 4), CivPosition(29, 84, 23)))
            val validation = s.worldAccess.validateAutonomousSite(s.city, b)
            s.scheduler.tick()
            validation.isDone shouldBe false
            s.worldAccess.close()
            validation.isCancelled shouldBe true
        }
    }
})

private class AutonomousSpatialScenario(paper: MockBukkitTestRuntime) : AutoCloseable {
    val world = CivsWorksiteWorldFixture("autonomous-placement").also(paper.server::addWorld)
    var city = spatialCity(world.uid)
    val scheduler = TestTaskScheduler()
    private val tasks = LifecycleTaskScope(scheduler, true)
    val worldAccess = CivsWorld(paper.createSimplePlugin("CivsAutonomousPlacementTest"), tasks, { 0L }) { listOf(city) }
    private val blueprints = mockk<CivsBlueprints>()
    val planner = CivsAutonomousPlacement(blueprints, worldAccess, Executor(Runnable::run))
    init {
        for (x in -3..3) for (z in -3..3) world.loadChunk(x, z)
        for (x in -40..40) for (z in -40..40) {
            world.getBlockAt(x, 63, z).type = Material.DIRT
            world.getBlockAt(x, 64, z).type = Material.GRASS_BLOCK
        }
        every { blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            CompletableFuture.completedFuture(spatialBlueprint(arg(3), arg(4), arg(5), arg<Int?>(7) ?: 0, arg(2)))
        }
    }
    fun finish(future: CompletableFuture<*>) {
        repeat(300) { if (!future.isDone) scheduler.tick() }
        future.isDone shouldBe true
    }
    override fun close() { planner.close(); worldAccess.close(); tasks.close() }
}

private fun spatialScenario(block: (AutonomousSpatialScenario) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open().use { paper -> AutonomousSpatialScenario(paper).use(block) }
}

private fun spatialCity(world: UUID): Settlement {
    val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(0, 64, 0), 0, "fixture",
        CivBox(CivPosition(-3, 64, -7), CivPosition(3, 68, -1)), CivPosition(0, 65, 0), BuildingPhase.ACTIVE,
        CivPosition(0, 65, -2))
    return Settlement(UUID.randomUUID(), UUID.randomUUID(), "Spatial", world, CivPosition(0, 64, 0), 96,
        "fixture", Stock(), listOf(hall), emptyList())
}

private fun spatialBlueprint(id: UUID, world: UUID, anchor: CivPosition, rotation: Int, level: Int): CivBlueprint {
    fun point(x: Int, y: Int, z: Int): CivPosition = CivsBlueprintGeometry.rotate(ArcSchematicPoint(x, y, z), rotation / 90)
        .let { CivPosition(anchor.x + it.x, anchor.y + it.y, anchor.z + it.z) }
    val corners = listOf(point(-2, 0, -4), point(2, 4 + level, -1))
    val bounds = CivBox(CivPosition(corners.minOf { it.x }, corners.minOf { it.y }, corners.minOf { it.z }),
        CivPosition(corners.maxOf { it.x }, corners.maxOf { it.y }, corners.maxOf { it.z }))
    val control = point(0, 1, -2)
    val building = CivBuilding(id, BuildingType.HOUSE, level, anchor, rotation, "fixture", bounds, point(0, 1, 0),
        BuildingPhase.CONSTRUCTING, control)
    val cells = spatialCells(building)
    return CivBlueprint(building, world, mockk<ArcSchematicSnapshot>(), cells, cells, byteArrayOf(1))
}

private fun spatialCells(building: CivBuilding): List<ArcSchematicBlock> {
    val bounds = building.bounds
    return buildList {
        for (x in bounds.min.x..bounds.max.x) for (y in bounds.min.y..bounds.max.y) for (z in bounds.min.z..bounds.max.z) {
            val at = CivPosition(x, y, z)
            val data = if (at == building.control) "minecraft:structure_void" else if (y == bounds.min.y) "minecraft:oak_planks" else "minecraft:air"
            add(ArcSchematicBlock(ArcSchematicPoint(x, y, z), data, data == "minecraft:air", false))
        }
    }
}

private fun spatialFailure(future: CompletableFuture<*>): String {
    future.isDone shouldBe true
    return try { future.join(); error("Expected failed placement") } catch (failure: CompletionException) {
        val rejection = generateSequence(failure as Throwable) { it.cause }
            .filterIsInstance<CivsRejection>().firstOrNull()
        rejection?.reason ?: throw AssertionError("Expected CivsRejection, got ${failure.cause}", failure)
    }
}
