package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Barrel
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
    test("site capture reserves an empty authored marker without placing a barrel and construction still requires it") {
        worldScenario { s ->
            val marker = CivPosition(1, 64, 0)
            val building = worldBuilding(worldBox(0, 1)).copy(control = marker)
            val cells = listOf(worldCell(0, 64, "minecraft:stone"), worldCell(1, 64, "minecraft:structure_void"))
            val cityId = UUID.randomUUID()
            val owner = UUID.randomUUID()
            val capture = s.subject.captureSite(cityId, owner, s.world.uid, building, cells, null)
            s.scheduler.tick()
            val plan = completed(capture)
            plan.blocks.map { it.position } shouldBe listOf(CivPosition(0, 64, 0))
            CivsMaterials.required(plan) shouldBe mapOf("STONE" to 1L)
            s.world.getBlockAt(1, 64, 0).type shouldBe Material.AIR
            val missing = s.subject.revalidate(plan, owner)
            s.scheduler.tick()
            failureReason(missing) shouldBe "control-position-missing"
            s.world.getBlockAt(1, 64, 0).type = Material.BARREL
            val accepted = s.subject.revalidate(plan, owner)
            s.scheduler.tick()
            completed(accepted) shouldBe Unit
        }
    }

    for (material in listOf(Material.CHEST, Material.BEDROCK)) test("automatic site control cannot replace $material") {
        worldScenario { s ->
            val marker = CivPosition(1, 64, 0)
            s.world.getBlockAt(1, 64, 0).type = material
            val building = worldBuilding(worldBox(0, 1)).copy(control = marker)
            val capture = s.subject.captureSite(UUID.randomUUID(), UUID.randomUUID(), s.world.uid, building,
                listOf(worldCell(0, 64, "minecraft:stone"), worldCell(1, 64, "minecraft:structure_void")), null)
            s.scheduler.tick()
            failureReason(capture) shouldBe "control-obstructed"
            s.world.getBlockAt(1, 64, 0).type shouldBe material
        }
    }

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
            completed(drifted) shouldBe Unit
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

    test("four scans share one 2048-cell budget and a fifth request fails without reading the world") {
        worldScenario { s ->
            val size = CivsWorld.CELLS_PER_TICK / 4
            for (x in 0..(size shr 4)) s.world.loadChunk(x, 0)
            val building = worldBuilding(worldBox(0, size - 1))
            val requests = List(4) {
                s.subject.capture(UUID.randomUUID(), UUID.randomUUID(), s.world.uid, building, worldCells(building), null)
            }
            failureReason(s.subject.capture(UUID.randomUUID(), UUID.randomUUID(), s.world.uid, building, worldCells(building), null)) shouldBe "world-busy"
            s.scheduler.tick()
            requests.all { !it.isDone } shouldBe true
            s.scheduler.tick()
            requests.forEach { completed(it).blocks.size shouldBe size }
            s.world.getBlockAt(0, 64, 0).type shouldBe Material.AIR
        }
    }

    test("the time budget yields after an expensive cell without starving other scans") {
        MockBukkitTestRuntime.open().use { paper ->
            var nanos = 0L
            WorldScenario(paper) { nanos.also { nanos += CivsWorld.NANOS_PER_TICK } }.use { s ->
                val building = worldBuilding(worldBox(0, 0))
                val requests = List(4) {
                    s.subject.capture(UUID.randomUUID(), UUID.randomUUID(), s.world.uid, building, worldCells(building), null)
                }
                repeat(4) { s.scheduler.tick() }
                requests.all { !it.isDone } shouldBe true
                repeat(4) { s.scheduler.tick() }
                requests.forEach { completed(it).blocks.size shouldBe 1 }
            }
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

    test("barriers remain absent from the before-image while the foundation is still empty") {
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

    test("the exact authored marker preserves a filled barrel during capture and revalidation") {
        worldScenario { s ->
            val control = CivPosition(0, 64, 0)
            val building = worldBuilding(worldBox(0, 1)).copy(anchor = CivPosition(-1, 63, 0), control = control)
            val block = s.world.getBlockAt(control.x, control.y, control.z).also { it.type = Material.BARREL }
            (block.state as Barrel).inventory.setItem(0, ItemStack(Material.DIAMOND, 3))
            val original = block.blockData.asString
            val owner = UUID.randomUUID()
            val captured = s.subject.capture(UUID.randomUUID(), owner, s.world.uid, building,
                listOf(worldCell(0, 64, "minecraft:structure_void"), worldCell(1, 64, "minecraft:air")), null)
            s.scheduler.tick()
            val plan = completed(captured)
            plan.blocks.map { it.position } shouldBe listOf(CivPosition(1, 64, 0))
            block.blockData.asString shouldBe original
            (block.state as Barrel).inventory.getItem(0) shouldBe ItemStack(Material.DIAMOND, 3)

            // Deposits after capture are permitted: the control's native inventory is never part of the paste.
            (block.state as Barrel).inventory.setItem(1, ItemStack(Material.OAK_LOG, 8))
            val revalidated = s.subject.revalidate(plan, owner)
            s.scheduler.tick()
            completed(revalidated) shouldBe Unit
            block.blockData.asString shouldBe original
            (block.state as Barrel).inventory.getItem(0) shouldBe ItemStack(Material.DIAMOND, 3)
            (block.state as Barrel).inventory.getItem(1) shouldBe ItemStack(Material.OAK_LOG, 8)
        }
    }

    test("a missing authored barrel or any attempted plan write at its control is rejected with no mutation") {
        worldScenario { s ->
            val control = CivPosition(0, 64, 0)
            val building = worldBuilding(worldBox(0, 1)).copy(anchor = CivPosition(-1, 63, 0), control = control)
            val cells = listOf(worldCell(0, 64, "minecraft:structure_void"), worldCell(1, 64, "minecraft:air"))
            val owner = UUID.randomUUID()
            val missing = s.subject.capture(UUID.randomUUID(), owner, s.world.uid, building, cells, null)
            s.scheduler.tick()
            val rejected = rejection(missing)
            rejected.reason shouldBe "control-position-missing"
            rejected.values shouldBe mapOf("block" to "AIR", "x" to "0", "y" to "64", "z" to "0",
                "world" to s.world.uid.toString(), "world_name" to s.world.name)
            val block = s.world.getBlockAt(0, 64, 0).also { it.type = Material.BARREL }
            val capture = s.subject.capture(UUID.randomUUID(), owner, s.world.uid, building, cells, null)
            s.scheduler.tick()
            val plan = completed(capture)
            val malformed = plan.copy(blocks = plan.blocks + CivPlanBlock(control, block.blockData.asString, "minecraft:structure_void"))
            failureReason(s.subject.revalidate(malformed, owner)) shouldBe "invalid-plan"
            block.type = Material.STONE
            val changed = s.subject.revalidate(plan, owner)
            s.scheduler.tick()
            failureReason(changed) shouldBe "control-position-missing"
            block.type shouldBe Material.STONE
        }
    }

    test("preserved marker cells still require Lands permission and never empty their barrel") {
        worldScenario { s ->
            val control = CivPosition(0, 64, 0)
            val block = s.world.getBlockAt(0, 64, 0).also { it.type = Material.BARREL }
            (block.state as Barrel).inventory.setItem(0, ItemStack(Material.DIAMOND, 3))
            val lands = s.paper.createSimplePlugin("Lands")
            lands.server.pluginManager.disablePlugin(lands)
            val building = worldBuilding(worldBox(0, 1)).copy(anchor = CivPosition(-1, 63, 0), control = control)
            val captured = s.subject.capture(UUID.randomUUID(), UUID.randomUUID(), s.world.uid, building,
                listOf(worldCell(0, 64, "minecraft:structure_void"), worldCell(1, 64, "minecraft:air")), null)
            s.scheduler.tick()
            val failure = rejection(captured)
            failure.reason shouldBe "lands-denied"
            failure.values["x"] shouldBe "0"
            failure.values["block"] shouldBe "BARREL"
            (block.state as Barrel).inventory.getItem(0) shouldBe ItemStack(Material.DIAMOND, 3)
        }
    }

    test("a batch away from the control still checks its chunk without loading it") {
        worldScenario { s ->
            val control = CivPosition(32, 64, 0)
            val building = worldBuilding(worldBox(0, 32)).copy(anchor = CivPosition(-1, 63, 0), control = control)
            val plan = CivPlacementPlan(UUID.randomUUID(), UUID.randomUUID(), s.world.uid, building,
                listOf(CivPlanBlock(CivPosition(0, 64, 0), "minecraft:air", "minecraft:air")))
            s.world.isChunkLoaded(2, 0) shouldBe false
            val revalidated = s.subject.revalidate(plan, UUID.randomUUID())
            s.scheduler.tick()
            failureReason(revalidated) shouldBe "chunks-unloaded"
            s.world.isChunkLoaded(2, 0) shouldBe false
        }
    }

    test("the separate control permission check shares the scan budget after a full cell slice") {
        worldScenario { s ->
            for (x in 0..(CivsWorld.CELLS_PER_TICK shr 4)) s.world.loadChunk(x, 0)
            val control = CivPosition(0, 64, 0)
            s.world.getBlockAt(0, 64, 0).type = Material.BARREL
            val building = worldBuilding(worldBox(0, CivsWorld.CELLS_PER_TICK)).copy(anchor = CivPosition(-1, 63, 0), control = control)
            val plan = CivPlacementPlan(UUID.randomUUID(), UUID.randomUUID(), s.world.uid, building,
                (1..CivsWorld.CELLS_PER_TICK).map { CivPlanBlock(CivPosition(it, 64, 0), "minecraft:air", "minecraft:air") })
            val revalidated = s.subject.revalidate(plan, UUID.randomUUID())
            s.scheduler.tick()
            revalidated.isDone shouldBe false
            val lands = s.paper.createSimplePlugin("Lands")
            lands.server.pluginManager.disablePlugin(lands)
            s.scheduler.tick()
            val failure = rejection(revalidated)
            failure.reason shouldBe "lands-denied"
            failure.values["block"] shouldBe "BARREL"
            failure.values["x"] shouldBe "0"
        }
    }

    test("legacy origin controls retain their original structure-void cells and revalidation semantics") {
        worldScenario { s ->
            val origin = CivPosition(-1, 63, 0)
            val building = worldBuilding(worldBox(0, 1)).copy(anchor = origin, control = origin)
            val owner = UUID.randomUUID()
            val captured = s.subject.capture(UUID.randomUUID(), owner, s.world.uid, building,
                listOf(worldCell(0, 64, "minecraft:structure_void"), worldCell(1, 64, "minecraft:air")), null)
            s.scheduler.tick()
            val plan = completed(captured)
            plan.blocks.size shouldBe 2
            plan.blocks.first().after shouldBe "minecraft:structure_void"
            val revalidated = s.subject.revalidate(plan, owner)
            s.scheduler.tick()
            completed(revalidated) shouldBe Unit
            s.world.getBlockAt(0, 64, 0).type shouldBe Material.AIR
            s.world.getBlockAt(origin.x, origin.y, origin.z).type shouldBe Material.STONE
        }
    }

    for (material in listOf(Material.CHEST, Material.BEDROCK)) test("actual replacement still rejects ${material.name} with exact coordinates") {
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

    for (material in listOf(Material.OAK_PLANKS, Material.STONE_BRICKS, Material.GLASS, Material.STRIPPED_JUNGLE_LOG,
        Material.LAVA, Material.WHITE_CONCRETE)) test("a new site can clear $material and use it for the automatic control") {
        worldScenario { s ->
            val marker = CivPosition(1, 64, 0)
            val building = worldBuilding(worldBox(0, 1)).copy(control = marker)
            (0..1).forEach { s.world.getBlockAt(it, 64, 0).type = material }
            val before = s.world.getBlockAt(0, 64, 0).blockData.asString
            val site = s.subject.captureSite(UUID.randomUUID(), UUID.randomUUID(), s.world.uid, building,
                listOf(worldCell(0, 64, "minecraft:air"), worldCell(1, 64, "minecraft:structure_void")), null)
            s.scheduler.tick()
            completed(site).blocks.single().before shouldBe before
            (0..1).map { s.world.getBlockAt(it, 64, 0).type } shouldBe listOf(material, material)
        }
    }

    test("bedrock stays protected inside an existing footprint and during revalidation") {
        worldScenario { s ->
            val site = worldBuilding(worldBox(0, 0)).copy(phase = BuildingPhase.SITE)
            val city = worldCity(s.world.uid, site)
            s.cities += city
            s.world.getBlockAt(0, 64, 0).type = Material.BEDROCK
            val building = site.copy(phase = BuildingPhase.CONSTRUCTING)
            val capture = s.subject.capture(city.id, city.owner, city.worldId, building, worldCells(building), site)
            s.scheduler.tick()
            failureReason(capture) shouldBe "protected-terrain"
            val plan = CivPlacementPlan(UUID.randomUUID(), city.id, city.worldId, building,
                listOf(CivPlanBlock(CivPosition(0, 64, 0), "minecraft:bedrock", "minecraft:air")))
            s.cities.clear()
            val validation = s.subject.revalidate(plan, city.owner)
            s.scheduler.tick()
            failureReason(validation) shouldBe "protected-terrain"
            s.world.getBlockAt(0, 64, 0).type shouldBe Material.BEDROCK
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

    test("site capture and construction allow blocked unsupported and wet approaches without changing them") {
        worldScenario { s ->
            val building = worldBuilding(worldBox(0, 0))
            val owner = UUID.randomUUID()
            for ((support, feet, head) in listOf(
                Triple(Material.STONE, Material.STONE, Material.OAK_FENCE),
                Triple(Material.SHORT_GRASS, Material.AIR, Material.AIR),
                Triple(Material.AIR, Material.AIR, Material.AIR),
                Triple(Material.STONE, Material.WATER, Material.WATER),
            )) {
                val before = listOf(support, feet, head)
                before.forEachIndexed { i, material -> s.world.getBlockAt(-1, 63 + i, 0).type = material }
                val site = s.subject.captureSite(UUID.randomUUID(), owner, s.world.uid, building, worldCells(building), null)
                s.scheduler.tick()
                val plan = completed(site)
                val construction = s.subject.capture(plan.cityId, owner, s.world.uid, building, worldCells(building), null)
                s.scheduler.tick()
                completed(construction).blocks shouldBe plan.blocks
                val revalidation = s.subject.revalidate(plan, owner)
                s.scheduler.tick()
                completed(revalidation) shouldBe Unit
                (63..65).map { s.world.getBlockAt(-1, it, 0).type } shouldBe before
            }
        }
    }

    test("an unloaded approach outside the footprint is neither scanned nor loaded") {
        worldScenario { s ->
            val building = worldBuilding(worldBox(0, 0)).copy(entrance = CivPosition(32, 64, 0))
            s.world.isChunkLoaded(2, 0) shouldBe false
            val captured = s.subject.captureSite(UUID.randomUUID(), UUID.randomUUID(), s.world.uid, building,
                worldCells(building), null)
            s.scheduler.tick()
            completed(captured).blocks.size shouldBe 1
            s.world.isChunkLoaded(2, 0) shouldBe false
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

    test("future cells accept manual clearing and rebuilding while bedrock and filled containers stay safe") {
        worldScenario { s ->
            val building = worldBuilding(worldBox(0, 1))
            val id = UUID.randomUUID()
            val plan = CivPlacementPlan(id, UUID.randomUUID(), s.world.uid, building, listOf(
                CivPlanBlock(CivPosition(0, 64, 0), "minecraft:oak_log[axis=y]", "minecraft:oak_planks"),
                CivPlanBlock(CivPosition(1, 64, 0), "minecraft:air", "minecraft:stone"),
            ))
            val city = worldCity(s.world.uid, building).copy(id = plan.cityId,
                construction = CivConstruction(id, building.id, 1, 0, 0, ConstructionPhase.PLACING, Stock(), plannedBuilding = building))
            s.cities += city
            // The player cleared the log and rebuilt another still-pending cell after capture.
            s.world.getBlockAt(0, 64, 0).type = Material.AIR
            s.world.getBlockAt(1, 64, 0).type = Material.GOLD_BLOCK
            val accepted = s.subject.revalidate(plan, city.owner)
            s.scheduler.tick()
            completed(accepted) shouldBe Unit
            s.world.getBlockAt(0, 64, 0).type shouldBe Material.AIR
            s.world.getBlockAt(1, 64, 0).type shouldBe Material.GOLD_BLOCK

            s.world.getBlockAt(0, 64, 0).type = Material.BEDROCK
            val bedrock = s.subject.revalidate(plan, city.owner)
            s.scheduler.tick()
            failureReason(bedrock) shouldBe "protected-terrain"
            s.world.getBlockAt(0, 64, 0).type shouldBe Material.BEDROCK
            val chest = s.world.getBlockAt(0, 64, 0).also { it.type = Material.CHEST }.state as org.bukkit.block.Container
            chest.inventory.setItem(0, ItemStack(Material.DIAMOND, 3))
            val filledContainer = s.subject.revalidate(plan, city.owner)
            s.scheduler.tick()
            failureReason(filledContainer) shouldBe "container-not-empty"
            chest.inventory.getItem(0) shouldBe ItemStack(Material.DIAMOND, 3)
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

    for (material in listOf(Material.CHEST, Material.BEDROCK)) test("a $material support cannot become an unusable locked control") {
        worldScenario { s ->
            val control = CivPosition(-1, 63, 0)
            s.world.getBlockAt(control.x, control.y, control.z).type = material
            val building = worldBuilding(worldBox(0, 0)).copy(anchor = control, control = control)
            val capture = s.subject.capture(UUID.randomUUID(), UUID.randomUUID(), s.world.uid, building, worldCells(building), null)
            s.scheduler.tick()
            failureReason(capture) shouldBe "control-obstructed"
            s.world.getBlockAt(control.x, control.y, control.z).type shouldBe material
        }
    }

    for (registered in listOf(false, true)) test("starting a site preserves a registered=$registered player container") {
        worldScenario { s ->
            val site = worldBuilding(worldBox(0, 0)).copy(phase = BuildingPhase.SITE)
            val point = CivPosition(0, 64, 0)
            val city = worldCity(s.world.uid, site).copy(inventory = CivInventory(CivStorageMode.PHYSICAL,
                containers = if (registered) listOf(point) else emptyList()))
            s.cities += city
            val block = s.world.getBlockAt(0, 64, 0).also { it.type = Material.CHEST }
            if (!registered) (block.state as Chest).blockInventory.setItem(0, ItemStack(Material.DIAMOND, 3))
            val target = site.copy(phase = BuildingPhase.CONSTRUCTING)
            val capture = s.subject.capture(city.id, city.owner, city.worldId, target, worldCells(target), site)
            s.scheduler.tick()
            failureReason(capture) shouldBe if (registered) "registered-container" else "container-not-empty"
            block.type shouldBe Material.CHEST
            if (!registered) (block.state as Chest).blockInventory.getItem(0) shouldBe ItemStack(Material.DIAMOND, 3)
        }
    }

    test("another building cannot cover a control located outside the original footprint") {
        worldScenario { s ->
            val hall = worldBuilding(worldBox(10, 10)).copy(phase = BuildingPhase.ACTIVE, control = CivPosition(0, 64, 0))
            val city = worldCity(s.world.uid, hall)
            s.cities += city
            val candidate = worldBuilding(worldBox(0, 0)).copy(type = BuildingType.HOUSE)
            val capture = s.subject.capture(city.id, city.owner, city.worldId, candidate, worldCells(candidate), null)
            s.scheduler.tick()
            failureReason(capture) shouldBe "building-overlap"
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

    test("a new footprint cannot cover another building's pending entrance preparation") {
        worldScenario { s ->
            val hall = worldBuilding(worldBox(10, 10)).copy(phase = BuildingPhase.SITE,
                entrance = CivPosition(0, 65, 0), control = CivPosition(10, 64, 0))
            val point = CivPosition(0, 64, 0)
            val city = worldCity(s.world.uid, hall).copy(pendingControls = listOf(CivControlPlacement(
                hall.id, requireNotNull(hall.control), "minecraft:air", listOf(CivPlanBlock(point, "minecraft:air", "minecraft:dirt")))))
            s.cities += city
            val building = worldBuilding(worldBox(0, 0)).copy(type = BuildingType.HOUSE)
            val capture = s.subject.captureSite(city.id, city.owner, city.worldId, building, worldCells(building), null)
            s.scheduler.tick()
            failureReason(capture) shouldBe "building-overlap"
            s.world.getBlockAt(point.x, point.y, point.z).type shouldBe Material.AIR
        }
    }

    test("close cancels pending work and no future request can restart it") {
        worldScenario { s ->
            val building = worldBuilding(worldBox(0, 0))
            val future = s.subject.capture(UUID.randomUUID(), UUID.randomUUID(), s.world.uid, building, worldCells(building), null)
            s.subject.close()
            future.isCancelled shouldBe true
            s.scheduler.tick(2)
            s.world.getBlockAt(0, 64, 0).type shouldBe Material.AIR
            failureReason(s.subject.capture(UUID.randomUUID(), UUID.randomUUID(), s.world.uid, building, worldCells(building), null)) shouldBe "world-closed"
        }
    }
})

private class WorldScenario(val paper: MockBukkitTestRuntime, nanoTime: () -> Long = { 0L }) : AutoCloseable {
    val world = paper.addSimpleWorld("civs-world")
    val cities = mutableListOf<Settlement>()
    val scheduler = TestTaskScheduler()
    private val tasks = LifecycleTaskScope(scheduler, true)
    val subject = CivsWorld(paper.createSimplePlugin("CivsWorldTest"), tasks, nanoTime) { cities }
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
