package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Chest
import org.bukkit.inventory.ItemStack
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class CivsWorldTest : FunSpec({
    test("geometry uses inclusive boxes and long arithmetic while city separation ignores height") {
        civsBoxVolume(worldBox(0, 0)) shouldBe 1
        civsBoxVolume(CivBox(CivPosition(-2, 3, -4), CivPosition(2, 4, 0))) shouldBe 50
        civsBoxVolume(CivBox(CivPosition(Int.MIN_VALUE, 0, 0), CivPosition(Int.MAX_VALUE, 0, 0))) shouldBe 0
        civsBoxVolume(CivBox(CivPosition(1, 0, 0), CivPosition(0, 0, 0))) shouldBe 0
        val city = worldCity(UUID.randomUUID(), worldBuilding(worldBox(0, 0)))
        civsIntersectsCity(worldBox(100, 100), city) shouldBe true
        civsIntersectsCity(worldBox(101, 101), city) shouldBe false
        civsIntersectsCity(CivBox(CivPosition(0, 1_000, 0), CivPosition(0, 1_001, 0)), city) shouldBe true
        civsContainsBox(worldBox(0, 2), worldBox(0, 1)) shouldBe true
        civsContainsBox(worldBox(1, 2), worldBox(0, 1)) shouldBe false
    }

    test("terrain material policy admits ordinary vegetation rivers and snow without claiming provenance") {
        listOf(Material.AIR, Material.DIRT, Material.STONE, Material.OAK_LEAVES, Material.SHORT_GRASS,
            Material.IRON_ORE, Material.OAK_LOG, Material.JUNGLE_LOG, Material.CRIMSON_STEM, Material.WARPED_STEM,
            Material.MANGROVE_ROOTS, Material.MUDDY_MANGROVE_ROOTS, Material.COCOA, Material.MELON,
            Material.PUMPKIN, Material.SWEET_BERRY_BUSH, Material.WATER, Material.ICE, Material.PACKED_ICE,
            Material.BLUE_ICE, Material.POWDER_SNOW, Material.SEAGRASS, Material.TALL_SEAGRASS,
            Material.KELP, Material.KELP_PLANT, Material.LILY_PAD).all(::civsNaturalTerrain) shouldBe true
        listOf(Material.LAVA, Material.CHEST, Material.BARREL, Material.STRIPPED_OAK_LOG, Material.OAK_WOOD,
            Material.OAK_PLANKS, Material.STONE_BRICKS, Material.GLASS, Material.BEDROCK,
            Material.WHITE_CONCRETE, Material.CARVED_PUMPKIN, Material.JACK_O_LANTERN).none(::civsNaturalTerrain) shouldBe true
    }

    test("jungle logs cocoa and ground are captured for clearing or building with exact before states") {
        worldScenario { s ->
            val before = listOf("minecraft:jungle_log[axis=y]", "minecraft:cocoa[age=2,facing=west]",
                "minecraft:grass_block[snowy=false]", "minecraft:dirt", "minecraft:short_grass")
            before.forEachIndexed { x, data -> s.world.getBlockAt(x, 64, 0).blockData = Bukkit.createBlockData(data) }
            val originals = before.indices.map { s.world.getBlockAt(it, 64, 0).blockData.asString }
            val after = listOf("minecraft:air", "minecraft:air", "minecraft:stone", "minecraft:oak_planks", "minecraft:air")
            val building = worldBuilding(worldBox(0, 4))
            val owner = UUID.randomUUID()
            val captured = s.subject.capture(UUID.randomUUID(), owner, s.world.uid, building,
                after.mapIndexed { x, data -> worldCell(x, 64, data) }, null)
            s.scheduler.tick()
            val plan = completed(captured)
            plan.blocks.map { it.before } shouldBe originals
            plan.blocks.map { it.after } shouldBe after
            before.indices.map { s.world.getBlockAt(it, 64, 0).blockData.asString } shouldBe originals

            val unchanged = s.subject.revalidate(plan, owner)
            s.scheduler.tick()
            completed(unchanged) shouldBe Unit
            s.world.getBlockAt(0, 64, 0).type = Material.AIR
            val partiallyPlaced = s.subject.revalidate(plan, owner)
            s.scheduler.tick()
            completed(partiallyPlaced) shouldBe Unit
            s.world.getBlockAt(1, 64, 0).blockData = Bukkit.createBlockData("minecraft:cocoa[age=1,facing=west]")
            val drifted = s.subject.revalidate(plan, owner)
            s.scheduler.tick()
            val failure = rejection(drifted)
            failure.reason shouldBe "world-changed"
            failure.values shouldBe mapOf("block" to "COCOA", "x" to "1", "y" to "64", "z" to "0",
                "world" to s.world.uid.toString(), "world_name" to s.world.name)
            s.world.getBlockAt(1, 64, 0).blockData.asString shouldBe "minecraft:cocoa[age=1,facing=west]"
        }
    }

    test("water and waterlogged natural mangrove roots can be replaced by a foundation or air") {
        worldScenario { s ->
            s.world.getBlockAt(0, 64, 0).blockData = Bukkit.createBlockData("minecraft:water[level=3]")
            s.world.getBlockAt(1, 64, 0).blockData = Bukkit.createBlockData("minecraft:mangrove_roots[waterlogged=true]")
            val originals = (0..1).map { s.world.getBlockAt(it, 64, 0).blockData.asString }
            val building = worldBuilding(worldBox(0, 1))
            val captured = s.subject.capture(UUID.randomUUID(), UUID.randomUUID(), s.world.uid, building,
                listOf(worldCell(0, 64, "minecraft:stone"), worldCell(1, 64, "minecraft:air")), null)
            s.scheduler.tick()
            val plan = completed(captured)
            plan.blocks.map { it.before } shouldBe originals
            plan.blocks.map { it.after } shouldBe listOf("minecraft:stone", "minecraft:air")
            (0..1).map { s.world.getBlockAt(it, 64, 0).blockData.asString } shouldBe originals
        }
    }

    test("four scans share one 256-cell budget and a fifth request fails without reading the world") {
        worldScenario { s ->
            for (x in 0..3) s.world.loadChunk(x, 0)
            val building = worldBuilding(worldBox(0, 63))
            val requests = List(4) {
                s.subject.capture(UUID.randomUUID(), UUID.randomUUID(), s.world.uid, building, worldCells(building), null)
            }
            failureReason(s.subject.capture(UUID.randomUUID(), UUID.randomUUID(), s.world.uid, building, worldCells(building), null)) shouldBe "world-busy"
            s.scheduler.tick()
            requests.all { !it.isDone } shouldBe true
            s.scheduler.tick()
            requests.forEach { completed(it).blocks.size shouldBe 64 }
            s.world.getBlockAt(0, 64, 0).type shouldBe Material.AIR
        }
    }

    test("capture accepts asynchronous submission but scans only when its main-thread scheduler advances") {
        worldScenario { s ->
            val id = s.world.uid
            val building = worldBuilding(worldBox(0, 0))
            val request = CompletableFuture.supplyAsync {
                s.subject.capture(UUID.randomUUID(), UUID.randomUUID(), id, building, worldCells(building), null)
            }.join()
            request.isDone shouldBe false
            s.scheduler.tick()
            completed(request).blocks.single().before shouldBe "minecraft:air"
        }
    }

    test("barriers remain absent from the before-image and entrance checks see predicted source blocks") {
        worldScenario { s ->
            val building = worldBuilding(CivBox(CivPosition(0, 63, 0), CivPosition(0, 66, 0)))
                .copy(entrance = CivPosition(0, 64, 0))
            val cells = listOf(
                worldCell(0, 63, "minecraft:stone"), worldCell(0, 64, "minecraft:air"),
                worldCell(0, 65, "minecraft:air"), worldCell(0, 66, "minecraft:barrier"),
            )
            val future = s.subject.capture(UUID.randomUUID(), UUID.randomUUID(), s.world.uid, building, cells, null)
            s.scheduler.tick()
            val plan = completed(future)
            plan.blocks.size shouldBe 3
            plan.blocks.all { it.before == "minecraft:air" } shouldBe true
            plan.blocks.first().after shouldBe "minecraft:stone"
            s.entranceObservations shouldBe listOf(Material.STONE, Material.AIR, Material.AIR)
            s.world.getBlockAt(0, 63, 0).type shouldBe Material.AIR
        }
    }

    test("a skipped barrier leaves a chest and its inventory outside the replacement plan") {
        worldScenario { s ->
            val block = s.world.getBlockAt(0, 64, 0)
            block.type = Material.CHEST
            val chest = block.state as Chest
            chest.blockInventory.setItem(0, ItemStack(Material.DIAMOND, 3))
            (block.state as Chest).blockInventory.getItem(0) shouldBe ItemStack(Material.DIAMOND, 3)
            val original = block.blockData.asString
            val building = worldBuilding(worldBox(0, 1))
            val future = s.subject.capture(UUID.randomUUID(), UUID.randomUUID(), s.world.uid, building,
                listOf(worldCell(0, 64, "minecraft:barrier"), worldCell(1, 64, "minecraft:air")), null)
            s.scheduler.tick()
            completed(future).blocks.map { it.position } shouldBe listOf(CivPosition(1, 64, 0))
            block.blockData.asString shouldBe original
            (block.state as Chest).blockInventory.getItem(0) shouldBe ItemStack(Material.DIAMOND, 3)
        }
    }

    for (material in listOf(Material.CHEST, Material.LAVA, Material.BEDROCK, Material.OAK_PLANKS,
        Material.STRIPPED_JUNGLE_LOG)) test("actual replacement still rejects ${material.name} with exact coordinates") {
        worldScenario { s ->
            val block = s.world.getBlockAt(0, 64, 0)
            block.type = material
            val original = block.blockData.asString
            val building = worldBuilding(worldBox(0, 0))
            val future = s.subject.capture(UUID.randomUUID(), UUID.randomUUID(), s.world.uid, building, worldCells(building), null)
            s.scheduler.tick()
            val failure = rejection(future)
            failure.reason shouldBe "protected-terrain"
            failure.values shouldBe mapOf("block" to material.name, "x" to "0", "y" to "64", "z" to "0",
                "world" to s.world.uid.toString(), "world_name" to s.world.name)
            block.blockData.asString shouldBe original
        }
    }

    test("skipped barriers and replaceable river terrain retain fail-closed Lands checks") {
        worldScenario { s ->
            val lands = s.paper.createSimplePlugin("Lands")
            lands.server.pluginManager.disablePlugin(lands)
            val building = worldBuilding(worldBox(0, 1))
            s.world.getBlockAt(0, 64, 0).type = Material.CHEST
            val barrier = s.subject.capture(UUID.randomUUID(), UUID.randomUUID(), s.world.uid, building,
                listOf(worldCell(0, 64, "minecraft:barrier"), worldCell(1, 64, "minecraft:air")), null)
            s.scheduler.tick()
            val deniedBarrier = rejection(barrier)
            deniedBarrier.reason shouldBe "lands-denied"
            deniedBarrier.values["block"] shouldBe "CHEST"
            s.world.getBlockAt(0, 64, 0).type shouldBe Material.CHEST

            s.world.getBlockAt(0, 64, 0).type = Material.WATER
            val terrain = s.subject.capture(UUID.randomUUID(), UUID.randomUUID(), s.world.uid, building,
                listOf(worldCell(0, 64, "minecraft:stone"), worldCell(1, 64, "minecraft:air")), null)
            s.scheduler.tick()
            failureReason(terrain) shouldBe "lands-denied"
            s.world.getBlockAt(0, 64, 0).type shouldBe Material.WATER
        }
    }

    test("unloaded chunks are rejected and remain unloaded") {
        worldScenario { s ->
            val building = worldBuilding(worldBox(32, 32))
            s.world.isChunkLoaded(2, 0) shouldBe false
            val future = s.subject.capture(UUID.randomUUID(), UUID.randomUUID(), s.world.uid, building, worldCells(building), null)
            s.scheduler.tick()
            failureReason(future) shouldBe "chunks-unloaded"
            s.world.isChunkLoaded(2, 0) shouldBe false
        }
    }

    test("world height and border checks reject before sampling an invalid footprint") {
        worldScenario { s ->
            val high = worldBuilding(CivBox(CivPosition(0, s.world.maxHeight, 0), CivPosition(0, s.world.maxHeight, 0)))
            val height = s.subject.capture(UUID.randomUUID(), UUID.randomUUID(), s.world.uid, high,
                listOf(worldCell(0, s.world.maxHeight, "minecraft:air")), null)
            s.scheduler.tick()
            failureReason(height) shouldBe "outside-world"
            s.world.worldBorder.size = 1.0
            s.world.worldBorder.setCenter(20.0, 20.0)
            val building = worldBuilding(worldBox(0, 0))
            val border = s.subject.capture(UUID.randomUUID(), UUID.randomUUID(), s.world.uid, building, worldCells(building), null)
            s.scheduler.tick()
            failureReason(border) shouldBe "outside-border"
        }
    }

    test("an entrance outside the schematic still requires its real support and headroom") {
        worldScenario { s ->
            val building = worldBuilding(worldBox(0, 0))
            s.world.getBlockAt(-1, 65, 0).type = Material.STONE
            val blocked = s.subject.capture(UUID.randomUUID(), UUID.randomUUID(), s.world.uid, building, worldCells(building), null)
            s.scheduler.tick()
            failureReason(blocked) shouldBe "entrance-blocked"
            s.world.getBlockAt(-1, 65, 0).type = Material.AIR
            s.world.getBlockAt(-1, 63, 0).type = Material.AIR
            val unsupported = s.subject.capture(UUID.randomUUID(), UUID.randomUUID(), s.world.uid, building, worldCells(building), null)
            s.scheduler.tick()
            failureReason(unsupported) shouldBe "entrance-blocked"
        }
    }

    test("upgrade exempts only the exact previous footprint and permits a shifted source anchor") {
        worldScenario { s ->
            val old = worldBuilding(worldBox(0, 0)).copy(phase = BuildingPhase.ACTIVE)
            val city = worldCity(s.world.uid, old)
            s.cities += city
            s.world.getBlockAt(0, 64, 0).type = Material.CHEST
            val upgrade = old.copy(level = 2, anchor = CivPosition(1, 64, 0), bounds = worldBox(0, 1), phase = BuildingPhase.CONSTRUCTING)
            val accepted = s.subject.capture(city.id, city.owner, city.worldId, upgrade, worldCells(upgrade), old)
            s.scheduler.tick()
            completed(accepted).blocks.first().before.startsWith("minecraft:chest") shouldBe true
            s.world.getBlockAt(1, 64, 0).type = Material.CHEST
            val rejected = s.subject.capture(city.id, city.owner, city.worldId, upgrade, worldCells(upgrade), old)
            s.scheduler.tick()
            failureReason(rejected) shouldBe "protected-terrain"
        }
    }

    test("revalidation accepts before and after but rejects a third state without overwriting it") {
        worldScenario { s ->
            val building = worldBuilding(worldBox(0, 1))
            val id = UUID.randomUUID()
            val plan = CivPlacementPlan(id, UUID.randomUUID(), s.world.uid, building, listOf(
                CivPlanBlock(CivPosition(0, 64, 0), "minecraft:air", "minecraft:oak_planks"),
                CivPlanBlock(CivPosition(1, 64, 0), "minecraft:air", "minecraft:stone"),
            ))
            val city = worldCity(s.world.uid, building).copy(id = plan.cityId,
                construction = CivConstruction(id, building.id, 1, 0, 0, ConstructionPhase.PLACING, Stock(), plannedBuilding = building))
            s.cities += city
            s.world.getBlockAt(0, 64, 0).type = Material.OAK_PLANKS
            val accepted = s.subject.revalidate(plan, city.owner)
            s.scheduler.tick()
            completed(accepted) shouldBe Unit
            s.world.getBlockAt(1, 64, 0).type = Material.GOLD_BLOCK
            val rejected = s.subject.revalidate(plan, city.owner)
            s.scheduler.tick()
            failureReason(rejected) shouldBe "world-changed"
            s.world.getBlockAt(1, 64, 0).type shouldBe Material.GOLD_BLOCK
        }
    }

    test("an upgrade cannot abandon generated blocks outside its replacement footprint") {
        worldScenario { s ->
            val old = worldBuilding(worldBox(0, 2)).copy(phase = BuildingPhase.ACTIVE)
            val city = worldCity(s.world.uid, old)
            s.cities += city
            val smaller = old.copy(level = 2, bounds = worldBox(0, 1), phase = BuildingPhase.CONSTRUCTING)
            val capture = s.subject.capture(city.id, city.owner, city.worldId, smaller, worldCells(smaller), old)
            s.scheduler.tick()
            failureReason(capture) shouldBe "upgrade-footprint-changed"
        }
    }

    test("planned upgrade overlap and owner mismatch are rejected before block capture") {
        worldScenario { s ->
            val hall = worldBuilding(worldBox(10, 10)).copy(phase = BuildingPhase.ACTIVE)
            val planned = hall.copy(level = 2, bounds = worldBox(0, 10), phase = BuildingPhase.CONSTRUCTING)
            val city = worldCity(s.world.uid, hall).copy(construction = CivConstruction(UUID.randomUUID(), hall.id,
                2, 1, 0, ConstructionPhase.CONSTRUCTING, Stock(), plannedBuilding = planned))
            s.cities += city
            val candidate = worldBuilding(worldBox(0, 0)).copy(type = BuildingType.HOUSE)
            val denied = s.subject.capture(city.id, UUID.randomUUID(), city.worldId, candidate, worldCells(candidate), null)
            val overlap = s.subject.capture(city.id, city.owner, city.worldId, candidate, worldCells(candidate), null)
            s.scheduler.tick()
            failureReason(denied) shouldBe "not-authorized"
            failureReason(overlap) shouldBe "building-overlap"
        }
    }

    test("close cancels pending work and no future request can restart it") {
        worldScenario { s ->
            val building = worldBuilding(worldBox(0, 0))
            val future = s.subject.capture(UUID.randomUUID(), UUID.randomUUID(), s.world.uid, building, worldCells(building), null)
            s.subject.close()
            future.isCancelled shouldBe true
            s.scheduler.tick(2)
            s.entranceObservations.isEmpty() shouldBe true
            failureReason(s.subject.capture(UUID.randomUUID(), UUID.randomUUID(), s.world.uid, building, worldCells(building), null)) shouldBe "world-closed"
        }
    }
})

private class WorldScenario(val paper: MockBukkitTestRuntime) : AutoCloseable {
    val world = paper.addSimpleWorld("civs-world")
    val cities = mutableListOf<Settlement>()
    val scheduler = TestTaskScheduler()
    private val tasks = LifecycleTaskScope(scheduler, true)
    val entranceObservations = mutableListOf<Material>()
    val subject = CivsWorld(paper.createSimplePlugin("CivsWorldTest"), tasks, { cities }) { data, _, support ->
        // Native BlockData shape queries are unsupported by this pinned MockBukkit version.
        entranceObservations += data.material
        if (support) data.material.isSolid else data.material.isAir
    }
    init {
        for (x in -2..1) for (z in -1..1) world.loadChunk(x, z)
        world.getBlockAt(-1, 63, 0).type = Material.STONE
    }
    override fun close() { subject.close(); tasks.close() }
}

private fun worldScenario(action: (WorldScenario) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open().use { paper -> WorldScenario(paper).use(action) }
}

private fun worldBox(minX: Int, maxX: Int) = CivBox(CivPosition(minX, 64, 0), CivPosition(maxX, 64, 0))
private fun worldBuilding(box: CivBox) = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1,
    CivPosition(box.min.x, 64, 0), 0, "test", box, CivPosition(-1, 64, 0), BuildingPhase.CONSTRUCTING)
private fun worldCity(worldId: UUID, building: CivBuilding) = Settlement(UUID.randomUUID(), UUID.randomUUID(), "Test",
    worldId, CivPosition(0, 64, 0), 100, "test", Stock(), listOf(building), emptyList())
private fun worldCell(x: Int, y: Int, data: String) = ArcSchematicBlock(ArcSchematicPoint(x, y, 0), data,
    data == "minecraft:air", data == "minecraft:barrier")
private fun worldCells(building: CivBuilding) = (building.bounds.min.x..building.bounds.max.x).map { worldCell(it, 64, "minecraft:air") }
private fun <T> completed(future: CompletableFuture<T>): T { future.isDone shouldBe true; return future.join() }
private fun rejection(future: CompletableFuture<*>): CivsRejection {
    future.isDone shouldBe true
    return try { future.join(); error("Expected a CivsRejection") } catch (failure: CompletionException) { failure.cause as CivsRejection }
}
private fun failureReason(future: CompletableFuture<*>): String = rejection(future).reason
