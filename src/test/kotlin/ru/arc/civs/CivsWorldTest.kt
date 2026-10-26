package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
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
    test("the retained route step exception accepts only dry bottom stair block states") {
        worldScenario { _ ->
            for (material in listOf("cobblestone", "oak")) for (facing in listOf("north", "east", "south", "west"))
                for (half in listOf("bottom", "top")) for (wet in listOf(false, true)) {
                    val data = Bukkit.createBlockData("minecraft:${material}_stairs[facing=$facing,half=$half,shape=straight,waterlogged=$wet]")
                    civsRetainedRouteStep(data) shouldBe (half == "bottom" && !wet)
                }
            civsRetainedRouteStep(Bukkit.createBlockData(Material.COBBLESTONE)) shouldBe false
            civsRetainedRouteStep(Bukkit.createBlockData(Material.WATER)) shouldBe false
        }
    }

    for (wet in listOf(false, true))
    test("preserved route capture ${if (wet) "rejects waterlogged" else "retains dry"} paved stairs without rewriting them") {
        worldScenario(collisionWorld = true) { s ->
            val building = worldBuilding(worldBox(0, 0))
            val city = worldCity(s.world.uid, building).copy(buildings = emptyList(), autonomy = CivAutonomy())
            s.cities += city
            val data = "minecraft:cobblestone_stairs[facing=east,half=bottom,shape=straight,waterlogged=$wet]"
            s.world.getBlockAt(-1, 63, 0).blockData = Bukkit.createBlockData(data)
            val draft = CivTerraformDraft(building, listOf(CivTerraformColumn(-1, 0, 63, 63, 63, preserve = true)),
                listOf(building.entrance), 64)
            val captured = s.subject.captureTerraform(city, draft, java.util.concurrent.Executor(Runnable::run))
            repeat(5) { if (!captured.isDone) s.scheduler.tick() }
            if (wet) failureReason(captured) shouldBe "autonomy-no-path" else completed(captured).blocks shouldBe emptyList()
            s.world.getBlockAt(-1, 63, 0).blockData.asString shouldBe data
        }
    }

    for (type in listOf(BuildingType.ROAD, BuildingType.GATE))
    test("a planned $type containing its depot source permits a validated route through its own footprint") {
        worldScenario(collisionWorld = true) { s ->
            val source = CivPosition(0, 64, 0)
            val depot = worldBuilding(worldBox(-5, -3)).copy(type = BuildingType.WAREHOUSE,
                phase = BuildingPhase.ACTIVE, entrance = source)
            val columns = (-1..3).flatMap { x -> (-1..1).map { z -> CivPosition(x, 64, z) } }
            val road = worldBuilding(CivBox(CivPosition(-1, 63, -1), CivPosition(3, 66, 1))).copy(type = type,
                anchor = CivPosition(-1, 63, -1), entrance = CivPosition(4, 64, 0), transit = columns)
            val city = worldCity(s.world.uid, depot).copy(autonomy = CivAutonomy())
            s.cities += city
            val surface = CivTerrainSurface(-64, 320, (-10..10).flatMap { x -> (-5..5).map { z ->
                civsTerrainKey(x, z) to CivTerrainSurfaceColumn(63, true)
            } }.toMap())
            val draft = CivsAutonomousTerraform.draft(city, CivTerraformOption(road, 0), surface,
                infrastructureFloors = columns.associate { civsTerrainKey(it.x, it.z) to 63 })
            draft.route.first() shouldBe source
            draft.route.last() shouldBe road.entrance
            val path = s.subject.autonomousRoute(city, road, source, java.util.concurrent.Executor { it.run() })
            repeat(100) { if (!path.isDone) s.scheduler.tick() }
            completed(path) shouldBe Unit
        }
    }

    test("autonomous construction tolerates only neighbour-derived stair shape and reports real drift") {
        worldScenario { s ->
            val position = CivPosition(0, 64, 0)
            val before = "minecraft:oak_stairs[facing=north,half=top,shape=straight,waterlogged=false]"
            val after = "minecraft:stone_bricks"
            s.world.getBlockAt(0, 64, 0).blockData = Bukkit.createBlockData(before)
            val building = worldBuilding(worldBox(0, 0))
            val city = worldCity(s.world.uid, building).copy(autonomy = CivAutonomy())
            s.cities += city
            val plan = CivPlacementPlan(UUID.randomUUID(), city.id, s.world.uid, building,
                listOf(CivPlanBlock(position, before, after)))
            for (state in listOf(before.replace("straight", "outer_left"), before.replace("straight", "inner_right"), after)) {
                s.world.getBlockAt(0, 64, 0).blockData = Bukkit.createBlockData(state)
                val result = s.subject.revalidate(plan, city.owner)
                s.scheduler.tick()
                completed(result) shouldBe Unit
            }
            for (state in listOf(before.replace("north", "south"), before.replace("top", "bottom"),
                before.replace("false", "true"), before.replace("oak_stairs", "stone_brick_stairs"), "minecraft:air")) {
                s.world.getBlockAt(0, 64, 0).blockData = Bukkit.createBlockData(state)
                val result = s.subject.revalidate(plan, city.owner)
                s.scheduler.tick()
                val failure = rejection(result)
                failure.reason shouldBe "autonomy-terrain"
                failure.values["actual"] shouldBe state
                failure.values["before"] shouldBe before
            }
        }
    }

    test("autonomous revalidation accepts the observed fence connections before a trapdoor upgrade without accepting real drift") {
        worldScenario { s ->
            val position = CivPosition(0, 64, 0)
            val before = "minecraft:spruce_fence[east=true,north=false,south=false,waterlogged=false,west=true]"
            val observed = "minecraft:spruce_fence[east=true,north=true,south=false,waterlogged=false,west=false]"
            val after = "minecraft:spruce_trapdoor[facing=east,half=bottom,open=true,powered=false,waterlogged=false]"
            val building = worldBuilding(worldBox(0, 0))
            val city = worldCity(s.world.uid, building).copy(autonomy = CivAutonomy())
            s.cities += city
            val plan = CivPlacementPlan(UUID.randomUUID(), city.id, s.world.uid, building,
                listOf(CivPlanBlock(position, before, after)))
            val block = s.world.getBlockAt(0, 64, 0)
            for (state in listOf(before, observed,
                "minecraft:spruce_fence[east=false,north=true,south=true,waterlogged=false,west=false]", after)) {
                block.blockData = Bukkit.createBlockData(state)
                val result = s.subject.revalidate(plan, city.owner)
                s.scheduler.tick()
                completed(result) shouldBe Unit
                block.blockData.asString shouldBe state
            }
            for (state in listOf(observed.replace("spruce_fence", "oak_fence"), observed.replace("waterlogged=false", "waterlogged=true"),
                "minecraft:air", after.replace("facing=east", "facing=west"), after.replace("open=true", "open=false"),
                after.replace("powered=false", "powered=true"), after.replace("half=bottom", "half=top"),
                after.replace("waterlogged=false", "waterlogged=true"))) {
                block.blockData = Bukkit.createBlockData(state)
                val result = s.subject.revalidate(plan, city.owner)
                s.scheduler.tick()
                val failure = rejection(result)
                failure.reason shouldBe "autonomy-terrain"
                failure.values["actual"] shouldBe state
                failure.values["before"] shouldBe before
                failure.values["after"] shouldBe after
                block.blockData.asString shouldBe state
            }
        }
    }

    for (passage in listOf("door", "fence_gate"))
    test("autonomous revalidation tolerates only $passage opening during an oak-to-spruce upgrade") {
        worldScenario { s ->
            val position = CivPosition(0, 64, 0)
            val before = if (passage == "door") "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]"
                else "minecraft:oak_fence_gate[facing=north,in_wall=false,open=false,powered=false]"
            val after = if (passage == "door") "minecraft:spruce_door[facing=north,half=lower,hinge=right,open=false,powered=false]"
                else "minecraft:spruce_fence_gate[facing=north,in_wall=false,open=false,powered=false]"
            val building = worldBuilding(worldBox(0, 0))
            val city = worldCity(s.world.uid, building).copy(autonomy = CivAutonomy())
            s.cities += city
            val plan = CivPlacementPlan(UUID.randomUUID(), city.id, s.world.uid, building,
                listOf(CivPlanBlock(position, before, after)))
            val block = s.world.getBlockAt(0, 64, 0)
            for (state in listOf(before, before.replace("open=false", "open=true"),
                after, after.replace("open=false", "open=true"))) {
                block.blockData = Bukkit.createBlockData(state)
                val result = s.subject.revalidate(plan, city.owner)
                s.scheduler.tick()
                completed(result) shouldBe Unit
                block.blockData.asString shouldBe state
            }
            val drifted = listOf(before, after).flatMap { state -> listOf(
                state.replace("facing=north", "facing=south"),
                if (passage == "door") state.replace("half=lower", "half=upper") else state.replace("in_wall=false", "in_wall=true"),
                state.replace("powered=false", "powered=true"),
            ) } + (if (passage == "door") listOf(before.replace("hinge=left", "hinge=right"), after.replace("hinge=right", "hinge=left"))
                else emptyList()) + listOf(before.replace("oak_", "birch_"), "minecraft:air")
            for (state in drifted) {
                block.blockData = Bukkit.createBlockData(state)
                val result = s.subject.revalidate(plan, city.owner)
                s.scheduler.tick()
                val failure = rejection(result)
                failure.reason shouldBe "autonomy-terrain"
                failure.values["actual"] shouldBe state
                failure.values["before"] shouldBe before
                failure.values["after"] shouldBe after
                block.blockData.asString shouldBe state
            }
        }
    }

    test("natural grass over captured dirt does not invalidate an autonomous upgrade") {
        worldScenario { s ->
            val position = CivPosition(0, 64, 0)
            val building = worldBuilding(worldBox(0, 0))
            val city = worldCity(s.world.uid, building).copy(autonomy = CivAutonomy())
            s.cities += city
            val plan = CivPlacementPlan(UUID.randomUUID(), city.id, s.world.uid, building,
                listOf(CivPlanBlock(position, "minecraft:dirt", "minecraft:stone")))
            val block = s.world.getBlockAt(0, 64, 0)
            block.blockData = Bukkit.createBlockData("minecraft:grass_block[snowy=false]")
            val result = s.subject.revalidate(plan, city.owner)
            s.scheduler.tick()
            completed(result) shouldBe Unit
            block.type shouldBe Material.GRASS_BLOCK
            for (material in listOf(Material.COARSE_DIRT, Material.MYCELIUM, Material.PODZOL)) {
                CivsWorld.sameConstructionState(material.createBlockData(), Material.DIRT.createBlockData()) shouldBe false
            }
            CivsWorld.sameConstructionState(Material.DIRT.createBlockData(), Material.GRASS_BLOCK.createBlockData()) shouldBe false
        }
    }

    test("other multiple-facing blocks retain exact construction state") {
        worldScenario {
            val expected = Bukkit.createBlockData("minecraft:vine[east=false,north=true,south=false,up=false,west=false]")
            val actual = Bukkit.createBlockData("minecraft:vine[east=true,north=true,south=false,up=false,west=false]")
            CivsWorld.sameConstructionState(actual, expected) shouldBe false
        }
    }

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

    test("warehouse rack maintenance changes only exact authored empty shelves and is idempotent") {
        worldScenario { s ->
            val building = worldBuilding(worldBox(0, 3)).copy(type = BuildingType.WAREHOUSE, phase = BuildingPhase.ACTIVE)
            val city = worldCity(s.world.uid, building); s.cities += city
            s.world.getBlockAt(0, 64, 0).type = Material.BOOKSHELF
            s.world.getBlockAt(1, 64, 0).type = Material.GOLD_BLOCK
            s.world.getBlockAt(2, 64, 0).type = Material.CHEST
            val chest = s.world.getBlockAt(2, 64, 0).state as org.bukkit.block.Chest
            chest.blockInventory.setItem(0, ItemStack(Material.DIAMOND, 3))
            val cells = (0..2).map { x -> CivPlanBlock(CivPosition(x, 64, 0), "minecraft:bookshelf",
                "minecraft:chest[facing=north,type=single,waterlogged=false]") }
            val repaired = s.subject.repairStorageRacks(city, building, cells)
            s.scheduler.tick()
            completed(repaired) shouldBe mapOf("changed" to 1, "unchanged" to 1, "conflicts" to 1, "occupied" to 0, "denied" to 0)
            s.world.getBlockAt(0, 64, 0).type shouldBe Material.CHEST
            s.world.getBlockAt(1, 64, 0).type shouldBe Material.GOLD_BLOCK
            chest.blockInventory.getItem(0) shouldBe ItemStack(Material.DIAMOND, 3)
            val again = s.subject.repairStorageRacks(city, building, cells)
            s.scheduler.tick()
            completed(again).getValue("changed") shouldBe 0
        }
    }

    test("ordinary workshop rack repair admits barrels and retains an occupied resulting container") {
        worldScenario { s ->
            val building = worldBuilding(worldBox(0, 1)).copy(type = BuildingType.SAWMILL, phase = BuildingPhase.ACTIVE)
            val city = worldCity(s.world.uid, building); s.cities += city
            s.world.getBlockAt(0, 64, 0).type = Material.BOOKSHELF
            val occupied = s.world.getBlockAt(1, 64, 0).also { it.type = Material.BARREL }
            val barrel = occupied.state as org.bukkit.block.Barrel
            barrel.inventory.setItem(0, ItemStack(Material.OAK_LOG, 17))
            val cells = listOf(
                CivPlanBlock(CivPosition(0, 64, 0), "minecraft:bookshelf", "minecraft:barrel[facing=north,open=false]"),
                CivPlanBlock(CivPosition(1, 64, 0), "minecraft:bookshelf", occupied.blockData.asString))
            val repaired = s.subject.repairStorageRacks(city, building, cells)
            s.scheduler.tick()
            completed(repaired).getValue("changed") shouldBe 1
            completed(repaired).getValue("unchanged") shouldBe 1
            s.world.getBlockAt(0, 64, 0).blockData.asString shouldBe cells.first().after
            val repeated = s.subject.repairStorageRacks(city, building, cells)
            s.scheduler.tick()
            completed(repeated).getValue("unchanged") shouldBe 2
            barrel.inventory.getItem(0) shouldBe ItemStack(Material.OAK_LOG, 17)
        }
    }

    test("access repair restores only exact inverse cells and all repair scans share one changed block per tick") {
        worldScenario { s ->
            val city = worldAccessCity(s)
            val feet = CivPosition(0, 64, 0)
            val support = CivPosition(1, 63, 0)
            s.world.getBlockAt(feet.x, feet.y, feet.z).type = Material.DIRT
            val repairs = listOf(
                worldAccessRepair(city, feet, CivPlanBlock(feet, "minecraft:dirt", "minecraft:air")),
                worldAccessRepair(city, support.copy(y = 64), CivPlanBlock(support, "minecraft:air", "minecraft:stone")))
            val repaired = s.subject.repairAutonomousAccess(city, repairs)
            s.scheduler.tick()
            repaired.isDone shouldBe false
            s.world.getBlockAt(0, 64, 0).type shouldBe Material.AIR
            s.world.getBlockAt(1, 63, 0).type shouldBe Material.AIR
            s.scheduler.tick()
            completed(repaired) shouldBe mapOf("changed" to 2, "unchanged" to 0, "conflicts" to 0, "denied" to 0)
            s.world.getBlockAt(1, 63, 0).type shouldBe Material.STONE
            val replay = s.subject.repairAutonomousAccess(city, repairs)
            s.scheduler.tick()
            completed(replay) shouldBe mapOf("changed" to 0, "unchanged" to 2, "conflicts" to 0, "denied" to 0)
            s.cities.single() shouldBe city

            val concurrent = (0..1).map { x ->
                val point = CivPosition(x, 64, 0)
                s.world.getBlockAt(x, 64, 0).type = Material.DIRT
                s.subject.repairAutonomousAccess(city, listOf(worldAccessRepair(city, point,
                    CivPlanBlock(point, "minecraft:dirt", "minecraft:air"))))
            }
            s.scheduler.tick()
            (0..1).count { s.world.getBlockAt(it, 64, 0).type == Material.AIR } shouldBe 1
            s.scheduler.tick()
            concurrent.forEach { completed(it).getValue("changed") shouldBe 1 }
        }
    }

    for (obstacle in listOf("player-edit", "liquid", "container", "lands", "registered", "falling-support", "solid-head"))
    test("access repair preserves $obstacle and never turns it into a journal reward") {
        worldScenario { s ->
            var city = worldAccessCity(s)
            val standing = CivPosition(0, 64, 0)
            val point = standing.copy(y = when (obstacle) { "falling-support" -> 63; "solid-head" -> 65; else -> 64 })
            val block = s.world.getBlockAt(point.x, point.y, point.z)
            block.type = when (obstacle) {
                "player-edit" -> Material.COARSE_DIRT
                "liquid" -> Material.WATER
                "container" -> Material.CHEST
                "falling-support" -> Material.AIR
                else -> Material.DIRT
            }
            if (obstacle == "container") (block.state as Chest).blockInventory.setItem(0, ItemStack(Material.DIAMOND, 2))
            if (obstacle == "lands") {
                val lands = s.paper.createSimplePlugin("Lands")
                s.paper.server.pluginManager.disablePlugin(lands)
            }
            if (obstacle == "registered") {
                city = city.copy(inventory = CivInventory(CivStorageMode.VIRTUAL, containers = listOf(point)))
                s.cities.clear(); s.cities += city
            }
            val before = if (obstacle == "player-edit") "minecraft:dirt" else block.blockData.asString
            val after = when (obstacle) { "falling-support" -> "minecraft:sand"; "solid-head" -> "minecraft:stone"; else -> "minecraft:air" }
            val original = block.blockData.asString
            val repaired = s.subject.repairAutonomousAccess(city, listOf(worldAccessRepair(city, standing,
                CivPlanBlock(point, before, after))))
            s.scheduler.tick()
            completed(repaired) shouldBe mapOf("changed" to 0, "unchanged" to 0,
                "conflicts" to if (obstacle == "player-edit") 1 else 0, "denied" to if (obstacle == "player-edit") 0 else 1)
            block.blockData.asString shouldBe original
            s.cities.single() shouldBe city
            if (obstacle == "container") (block.state as Chest).blockInventory.getItem(0) shouldBe ItemStack(Material.DIAMOND, 2)
        }
    }

    for (drift in listOf("mode", "deletion", "protected-building", "offending-building", "construction"))
    test("access repair stops its unfinished suffix after $drift changes") {
        worldScenario { s ->
            val city = worldAccessCity(s)
            val repairs = (0..1).map { x ->
                val point = CivPosition(x, 64, 0)
                s.world.getBlockAt(x, 64, 0).type = Material.DIRT
                worldAccessRepair(city, point, CivPlanBlock(point, "minecraft:dirt", "minecraft:air"))
            }
            val repaired = s.subject.repairAutonomousAccess(city, repairs)
            s.scheduler.tick()
            repaired.isDone shouldBe false
            val changed = when (drift) {
                "mode" -> city.copy(autonomy = null)
                "deletion" -> city.copy(pendingDeletion = CivDeletion(null))
                "construction" -> city.copy(construction = CivConstruction(UUID.randomUUID(), city.buildings[1].id,
                    2, 1, 0, ConstructionPhase.CONSTRUCTING, Stock()))
                else -> city.copy(buildings = city.buildings.mapIndexed { index, building ->
                    if (index == if (drift == "protected-building") 0 else 1) building.copy(level = 2) else building
                })
            }
            s.cities.clear(); s.cities += changed
            s.scheduler.tick()
            failureReason(repaired) shouldBe "stale-building"
            s.world.getBlockAt(1, 64, 0).type shouldBe Material.DIRT
        }
    }

    test("access repair rejects duplicate or oversized native requests before mutating terrain") {
        worldScenario { s ->
            val city = worldAccessCity(s)
            val point = CivPosition(0, 64, 0)
            val repair = worldAccessRepair(city, point, CivPlanBlock(point, "minecraft:dirt", "minecraft:air"))
            failureReason(s.subject.repairAutonomousAccess(city, listOf(repair, repair))) shouldBe "invalid-plan"
            val oversized = List(513) { x -> repair.copy(standing = point.copy(x = x),
                cell = repair.cell.copy(position = point.copy(x = x))) }
            failureReason(s.subject.repairAutonomousAccess(city, oversized)) shouldBe "invalid-plan"
            s.world.getBlockAt(0, 64, 0).type shouldBe Material.AIR
        }
    }

    test("warehouse repair preserves books inside an authored chiseled bookshelf") {
        worldScenario { s ->
            val building = worldBuilding(worldBox(0, 0)).copy(type = BuildingType.WAREHOUSE, phase = BuildingPhase.ACTIVE)
            val city = worldCity(s.world.uid, building); s.cities += city
            val block = s.world.getBlockAt(0, 64, 0); block.type = Material.CHISELED_BOOKSHELF
            val shelf = block.state as org.bukkit.block.ChiseledBookshelf
            shelf.inventory.setItem(0, ItemStack(Material.BOOK))
            shelf.update(true, false)
            val repair = s.subject.repairStorageRacks(city, building, listOf(CivPlanBlock(CivPosition(0, 64, 0),
                block.blockData.asString, "minecraft:chest[facing=north,type=single,waterlogged=false]")))
            s.scheduler.tick()
            completed(repair).getValue("occupied") shouldBe 1
            block.type shouldBe Material.CHISELED_BOOKSHELF
            (block.state as org.bukkit.block.ChiseledBookshelf).inventory.getItem(0) shouldBe ItemStack(Material.BOOK)
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

    test("terrain capture keeps exact originals and schematic before states use the terrain overlay") {
        worldScenario(collisionWorld = true) { s ->
            val building = worldBuilding(worldBox(0, 0))
            val cell = CivPlanBlock(CivPosition(0, 64, 0), "minecraft:dirt", "minecraft:air")
            val proposed = worldTerraformPlan(s, listOf(cell), building)
            val city = worldTerraformCity(s, proposed).copy(buildings = emptyList(), construction = null)
            s.cities.clear(); s.cities += city
            s.world.getBlockAt(0, 64, 0).type = Material.DIRT
            val capture = s.subject.captureAutonomousSite(city.id, city.owner, city.worldId, building,
                listOf(worldCell(0, 64, "minecraft:stone")), proposed.terrain)
            s.scheduler.tick()
            val plan = completed(capture)
            plan.terrain!!.blocks shouldBe listOf(cell)
            plan.blocks.single().before shouldBe "minecraft:air"
            plan.blocks.single().after shouldBe "minecraft:stone"
            s.world.getBlockAt(0, 64, 0).type shouldBe Material.DIRT

            s.world.getBlockAt(0, 64, 0).type = Material.COARSE_DIRT
            val stale = s.subject.captureAutonomousSite(city.id, city.owner, city.worldId, building,
                listOf(worldCell(0, 64, "minecraft:stone")), proposed.terrain)
            s.scheduler.tick()
            failureReason(stale) shouldBe "world-changed"
            s.world.getBlockAt(0, 64, 0).type shouldBe Material.COARSE_DIRT
        }
    }

    for ((data, y, allowed) in listOf(
        Triple("minecraft:gravel", 63, true),
        Triple("minecraft:sand", 63, true),
        Triple("minecraft:red_sand", 63, true),
        Triple("minecraft:gravel", 65, false),
        Triple("minecraft:sand", 65, false),
        Triple("minecraft:red_sand", 65, false),
        Triple("minecraft:bedrock", 63, false),
        Triple("minecraft:chest", 63, false),
        Triple("minecraft:oak_planks", 63, false),
        Triple("minecraft:cobblestone", 63, false),
        Triple("minecraft:water", 63, false),
        Triple("minecraft:lava", 63, false),
        Triple("minecraft:mangrove_roots[waterlogged=true]", 63, false),
    )) test("prepared autonomous capture ${if (allowed) "accepts" else "rejects"} $data at y=$y outside its terrain overlay") {
        worldScenario(collisionWorld = true) { s ->
            val building = worldBuilding(CivBox(CivPosition(0, 63, 0), CivPosition(0, 65, 0)))
            val prepared = CivPlanBlock(CivPosition(0, 64, 0), "minecraft:dirt", "minecraft:air")
            val proposed = worldTerraformPlan(s, listOf(prepared), building)
            val city = worldTerraformCity(s, proposed).copy(buildings = emptyList(), construction = null)
            s.cities.clear(); s.cities += city
            s.world.getBlockAt(0, 64, 0).type = Material.DIRT
            val target = s.world.getBlockAt(0, y, 0).also { it.blockData = Bukkit.createBlockData(data) }
            val original = target.blockData.asString
            val capture = s.subject.captureAutonomousSite(city.id, city.owner, city.worldId, building,
                listOf(worldCell(0, 63, "minecraft:stone"), worldCell(0, 64, "minecraft:air"),
                    worldCell(0, 65, "minecraft:air")), proposed.terrain)
            s.scheduler.tick()
            if (allowed) {
                val plan = completed(capture)
                plan.terrain!!.blocks shouldBe listOf(prepared)
                plan.blocks.single { it.position == CivPosition(0, y, 0) }.before shouldBe original
                plan.blocks.single { it.position == prepared.position }.before shouldBe "minecraft:air"
            } else {
                val failure = rejection(capture)
                failure.reason shouldBe "autonomy-terrain"
                failure.values shouldBe mapOf("block" to target.type.name, "x" to "0", "y" to y.toString(), "z" to "0")
            }
            target.blockData.asString shouldBe original
            s.world.getBlockAt(0, 64, 0).type shouldBe Material.DIRT
        }
    }

    test("a terrain batch changes one block per tick and replay counts already applied cells without repeating them") {
        worldScenario { s ->
            val cells = listOf(
                CivPlanBlock(CivPosition(0, 64, 0), "minecraft:air", "minecraft:dirt"),
                CivPlanBlock(CivPosition(1, 64, 0), "minecraft:air", "minecraft:stone"),
            )
            val plan = worldTerraformPlan(s, cells, worldBuilding(worldBox(0, 1)))
            val city = worldTerraformCity(s, plan)
            val placed = mutableListOf<CivPlanBlock>()
            val apply = s.subject.applyTerraform(plan, city.owner, cells, 0, { true }, placed::add)
            s.scheduler.tick()
            placed shouldBe cells.take(1)
            s.world.getBlockAt(0, 64, 0).type shouldBe Material.DIRT
            s.world.getBlockAt(1, 64, 0).type shouldBe Material.AIR
            apply.isDone shouldBe false
            s.scheduler.tick()
            completed(apply) shouldBe Unit
            placed shouldBe cells

            val replayed = mutableListOf<CivPlanBlock>()
            val replay = s.subject.applyTerraform(plan, city.owner, cells, 0, { true }, replayed::add)
            s.scheduler.tick()
            completed(replay) shouldBe Unit
            replayed shouldBe cells
            s.world.getBlockAt(0, 64, 0).type shouldBe Material.DIRT
            s.world.getBlockAt(1, 64, 0).type shouldBe Material.STONE
        }
    }

    test("terrain restart accepts a completed prefix but preserves a later manual natural edit") {
        worldScenario { s ->
            val cells = listOf(
                CivPlanBlock(CivPosition(0, 64, 0), "minecraft:air", "minecraft:dirt"),
                CivPlanBlock(CivPosition(1, 64, 0), "minecraft:air", "minecraft:stone"),
            )
            val plan = worldTerraformPlan(s, cells, worldBuilding(worldBox(0, 1)))
            val city = worldTerraformCity(s, plan)
            s.world.getBlockAt(0, 64, 0).type = Material.DIRT
            s.world.getBlockAt(1, 64, 0).type = Material.COARSE_DIRT
            val placed = mutableListOf<CivPlanBlock>()
            val apply = s.subject.applyTerraform(plan, city.owner, cells, 0, { true }, placed::add)
            s.scheduler.tick()
            failureReason(apply) shouldBe "world-changed"
            placed shouldBe cells.take(1)
            s.world.getBlockAt(0, 64, 0).type shouldBe Material.DIRT
            s.world.getBlockAt(1, 64, 0).type shouldBe Material.COARSE_DIRT
        }
    }

    test("terrain work waits for worker authorization and cancellation fences the unfinished suffix") {
        worldScenario { s ->
            val cells = (0..1).map { CivPlanBlock(CivPosition(it, 64, 0), "minecraft:air", "minecraft:dirt") }
            val plan = worldTerraformPlan(s, cells, worldBuilding(worldBox(0, 1)))
            val city = worldTerraformCity(s, plan)
            var ready = false
            val placed = mutableListOf<CivPlanBlock>()
            val apply = s.subject.applyTerraform(plan, city.owner, cells, 0, { ready }, placed::add)
            s.scheduler.tick(2)
            placed shouldBe emptyList()
            apply.isDone shouldBe false
            ready = true
            s.scheduler.tick()
            placed shouldBe cells.take(1)
            apply.cancel(false) shouldBe true
            s.scheduler.tick(2)
            placed shouldBe cells.take(1)
            s.world.getBlockAt(1, 64, 0).type shouldBe Material.AIR
        }
    }

    for ((material, reason) in listOf(Material.CHEST to "protected-terrain", Material.WATER to "autonomy-terrain",
        Material.BEDROCK to "protected-terrain", Material.OAK_PLANKS to "autonomy-terrain")) {
        test("terrain writes preserve protected $material even when its before image matches") {
            worldScenario { s ->
                val block = s.world.getBlockAt(0, 64, 0).also { it.type = material }
                val cell = CivPlanBlock(CivPosition(0, 64, 0), block.blockData.asString, "minecraft:air")
                val plan = worldTerraformPlan(s, listOf(cell))
                val city = worldTerraformCity(s, plan)
                val apply = s.subject.applyTerraform(plan, city.owner, listOf(cell), 0, { true }, {})
                s.scheduler.tick()
                failureReason(apply) shouldBe reason
                block.type shouldBe material
            }
        }
    }

    test("terrain writes preserve a filled container and recheck Lands immediately before changing a block") {
        worldScenario { s ->
            val block = s.world.getBlockAt(0, 64, 0).also { it.type = Material.CHEST }
            (block.state as Chest).blockInventory.setItem(0, ItemStack(Material.DIAMOND, 2))
            val cell = CivPlanBlock(CivPosition(0, 64, 0), block.blockData.asString, "minecraft:air")
            val plan = worldTerraformPlan(s, listOf(cell))
            val city = worldTerraformCity(s, plan)
            val filled = s.subject.applyTerraform(plan, city.owner, listOf(cell), 0, { true }, {})
            s.scheduler.tick()
            failureReason(filled) shouldBe "container-not-empty"
            (block.state as Chest).blockInventory.getItem(0) shouldBe ItemStack(Material.DIAMOND, 2)

            block.type = Material.AIR
            val dry = cell.copy(before = "minecraft:air", after = "minecraft:dirt")
            val protectedPlan = plan.copy(terrain = plan.terrain!!.copy(blocks = listOf(dry), cutCount = 0, fillCount = 1))
            val lands = s.paper.createSimplePlugin("Lands")
            val denied = s.subject.applyTerraform(protectedPlan, city.owner, listOf(dry), 0, {
                s.paper.server.pluginManager.disablePlugin(lands); true
            }, {})
            s.scheduler.tick()
            failureReason(denied) shouldBe "lands-denied"
            block.type shouldBe Material.AIR
        }
    }

    test("a terrain batch cannot escape the city or cover a foreign control outside its footprint") {
        worldScenario { s ->
            val outside = CivPlanBlock(CivPosition(3, 64, 0), "minecraft:air", "minecraft:dirt")
            val plan = worldTerraformPlan(s, listOf(outside))
            val city = worldTerraformCity(s, plan).copy(radius = 1)
            s.cities.clear(); s.cities += city
            val escaped = s.subject.applyTerraform(plan, city.owner, listOf(outside), 0, { true }, {})
            s.scheduler.tick()
            failureReason(escaped) shouldBe "outside-city"
            s.world.getBlockAt(3, 64, 0).type shouldBe Material.AIR

            val control = CivPosition(2, 64, 0)
            val neighbour = worldBuilding(worldBox(10, 10)).copy(type = BuildingType.HOUSE,
                phase = BuildingPhase.ACTIVE, control = control)
            s.cities.clear(); s.cities += city.copy(radius = 100, buildings = city.buildings + neighbour)
            val guarded = outside.copy(position = control)
            val controlPlan = plan.copy(terrain = plan.terrain!!.copy(blocks = listOf(guarded)))
            val covered = s.subject.applyTerraform(controlPlan, city.owner, listOf(guarded), 0, { true }, {})
            s.scheduler.tick()
            failureReason(covered) shouldBe "building-overlap"
            s.world.getBlockAt(control.x, control.y, control.z).type shouldBe Material.AIR
        }
    }

    test("native autonomous capture and apply cannot rewrite another existing entrance column") {
        worldScenario { s ->
            val point = CivPosition(2, 64, 0)
            val cell = CivPlanBlock(point, "minecraft:air", "minecraft:dirt")
            val plan = worldTerraformPlan(s, listOf(cell))
            val city = worldTerraformCity(s, plan)
            val older = worldBuilding(worldBox(10, 10)).copy(type = BuildingType.HOUSE,
                phase = BuildingPhase.ACTIVE, entrance = point)
            s.cities.clear(); s.cities += city.copy(buildings = city.buildings + older)
            val apply = s.subject.applyTerraform(plan, city.owner, listOf(cell), 0, { true }, {})
            s.scheduler.tick()
            failureReason(apply) shouldBe "building-overlap"
            val capture = s.subject.captureAutonomousSite(city.id, city.owner, city.worldId, plan.building,
                listOf(worldCell(0, 64, "minecraft:stone")), plan.terrain, city.buildings.single())
            s.scheduler.tick()
            failureReason(capture) shouldBe "building-overlap"
            s.world.getBlockAt(point.x, point.y, point.z).type shouldBe Material.AIR
        }
    }

    test("native terrain capture marks a nonpreserved retained road column unsafe") {
        worldScenario(collisionWorld = true) { s ->
            val building = worldBuilding(worldBox(0, 0))
            val older = worldBuilding(worldBox(10, 10)).copy(type = BuildingType.HOUSE,
                phase = BuildingPhase.ACTIVE, entrance = CivPosition(5, 64, 0))
            val city = worldCity(s.world.uid, older).copy(autonomy = CivAutonomy())
            s.cities += city
            s.world.getBlockAt(0, 65, 0).type = Material.DIRT
            val access = CivAutonomousAccess(mapOf(older.id to (0..5).map { CivPosition(it, 64, 0) }))
            val draft = CivTerraformDraft(building, listOf(
                CivTerraformColumn(-1, 0, 63, 63, 63, preserve = true),
                CivTerraformColumn(0, 0, 64, 63, 65)), listOf(building.entrance), 64, access)
            val captured = s.subject.captureTerraform(city, draft, java.util.concurrent.Executor(Runnable::run))
            s.scheduler.tick()
            failureReason(captured) shouldBe "autonomy-terrain"
            s.world.getBlockAt(0, 65, 0).type shouldBe Material.DIRT
        }
    }

    test("route standing cells use future fill and the source support remains unchanged") {
        worldScenario(collisionWorld = true) { s ->
            val building = worldBuilding(worldBox(2, 2)).copy(entrance = CivPosition(1, 65, 0))
            s.world.getBlockAt(0, 63, 0).type = Material.STONE
            val cell = CivPlanBlock(CivPosition(1, 64, 0), "minecraft:air", "minecraft:dirt")
            val route = listOf(CivPosition(0, 64, 0), building.entrance)
            val proposed = worldTerraformPlan(s, listOf(cell), building).let {
                it.copy(terrain = it.terrain!!.copy(route = route))
            }
            val city = worldTerraformCity(s, proposed).copy(buildings = emptyList(), construction = null)
            s.cities.clear(); s.cities += city
            val capture = s.subject.captureAutonomousSite(city.id, city.owner, city.worldId, building,
                listOf(worldCell(2, 64, "minecraft:stone")), proposed.terrain)
            s.scheduler.tick()
            completed(capture).terrain!!.route shouldBe route
            s.world.getBlockAt(1, 64, 0).type shouldBe Material.AIR

            val changedSource = CivPlanBlock(CivPosition(0, 63, 0), "minecraft:stone", "minecraft:dirt")
            val unsafe = proposed.terrain!!.copy(bounds = CivBox(CivPosition(0, 63, 0), CivPosition(1, 65, 0)),
                blocks = listOf(changedSource, cell), fillCount = 2)
            val rejected = s.subject.captureAutonomousSite(city.id, city.owner, city.worldId, building,
                listOf(worldCell(2, 64, "minecraft:stone")), unsafe)
            s.scheduler.tick()
            failureReason(rejected) shouldBe "autonomy-no-path"
            s.world.getBlockAt(0, 63, 0).type shouldBe Material.STONE
        }
    }

    test("terrain capture refuses a route jump and a mismatched ledger batch before any mutation") {
        worldScenario { s ->
            val cell = CivPlanBlock(CivPosition(0, 64, 0), "minecraft:air", "minecraft:dirt")
            val plan = worldTerraformPlan(s, listOf(cell))
            val city = worldTerraformCity(s, plan)
            val jumped = plan.terrain!!.copy(route = listOf(CivPosition(-3, 64, 0), plan.building.entrance))
            val capture = s.subject.captureAutonomousSite(city.id, city.owner, city.worldId, plan.building,
                worldCells(plan.building), jumped, city.buildings.single())
            failureReason(capture) shouldBe "autonomy-no-path"
            val foreign = cell.copy(position = CivPosition(1, 64, 0))
            val applied = s.subject.applyTerraform(plan, city.owner, listOf(foreign), 0, { true }, {})
            s.scheduler.tick()
            failureReason(applied) shouldBe "invalid-plan"
            s.world.getBlockAt(0, 64, 0).type shouldBe Material.AIR
            s.world.getBlockAt(1, 64, 0).type shouldBe Material.AIR
        }
    }

    test("height field spans the shared tick budget and leaves unavailable chunks unloaded") {
        worldScenario { s ->
            val building = worldBuilding(worldBox(0, 0))
            val city = worldCity(s.world.uid, building).copy(buildings = emptyList(), radius = 32, autonomy = CivAutonomy())
            s.cities += city
            s.world.isChunkLoaded(2, 0) shouldBe false
            val field = s.subject.autonomousHeightField(city, building)
            s.scheduler.tick()
            field.isDone shouldBe false
            s.scheduler.tick(3)
            completed(field).columns.size shouldBe 65 * 65
            completed(field).at(32, 0)!!.loaded shouldBe false
            s.world.isChunkLoaded(2, 0) shouldBe false
        }
    }

    test("height field resolves soil below a bounded natural canopy but refuses bare logs") {
        worldScenario(collisionWorld = true) { s ->
            val building = worldBuilding(worldBox(0, 0))
            val city = worldCity(s.world.uid, building).copy(buildings = emptyList(), radius = 1, autonomy = CivAutonomy())
            s.cities += city
            s.world.getBlockAt(0, 64, 0).type = Material.OAK_LOG
            s.world.getBlockAt(0, 65, 0).type = Material.OAK_LOG
            s.world.getBlockAt(0, 66, 0).blockData = Bukkit.createBlockData("minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]")
            s.world.getBlockAt(1, 64, 0).type = Material.OAK_LOG
            val field = s.subject.autonomousHeightField(city, building)
            s.scheduler.tick()
            val surface = completed(field)
            surface.at(0, 0)!!.groundY shouldBe 63
            surface.at(0, 0)!!.topY shouldBe 66
            surface.at(0, 0)!!.natural shouldBe true
            surface.at(1, 0)!!.natural shouldBe false
            s.world.getBlockAt(0, 66, 0).type shouldBe Material.OAK_LEAVES
        }
    }

    test("manual foundations fill every footprint and entrance column beyond twenty four blocks with dirt") {
        worldScenario(collisionWorld = true) { s ->
            val building = worldBuilding(CivBox(CivPosition(0, 100, 0), CivPosition(1, 105, 0)))
                .copy(anchor = CivPosition(0, 104, 0), entrance = CivPosition(-1, 105, 0))
            val work = mutableListOf<Runnable>()
            val captured = s.subject.captureManualFoundation(UUID.randomUUID(), UUID.randomUUID(), s.world.uid,
                building, worldSchematicCells(building), java.util.concurrent.Executor(work::add))
            s.scheduler.tick()
            captured.isDone shouldBe false
            work.size shouldBe 1
            CompletableFuture.runAsync { work.single().run() }.join()
            captured.isDone shouldBe false
            s.scheduler.tick()
            val terrain = requireNotNull(completed(captured))
            terrain.route shouldBe emptyList()
            terrain.cutCount shouldBe 0
            terrain.fillCount shouldBe 3 * (104 - 63)
            terrain.blocks.all { it.after == "minecraft:dirt" } shouldBe true
            for (x in -1..1) terrain.blocks.filter { it.position.x == x }.map { it.position.y } shouldBe (64..104).toList()
            s.world.getBlockAt(0, 100, 0).type shouldBe Material.AIR
        }
    }

    test("manual foundation cuts uneven natural terrain before the founding site is published") {
        worldScenario(collisionWorld = true) { s ->
            val marker = CivPosition(0, 69, 0)
            val building = worldBuilding(CivBox(CivPosition(0, 64, 0), CivPosition(1, 72, 0)))
                .copy(anchor = CivPosition(0, 68, 0), entrance = CivPosition(-1, 69, 0), control = marker)
            for (y in 64..73) s.world.getBlockAt(1, y, 0).type = Material.STONE
            s.world.getBlockAt(marker.x, marker.y, marker.z).type = Material.STONE
            val cells = worldSchematicCells(building).map { if (it.position.x == marker.x && it.position.y == marker.y)
                worldCell(marker.x, marker.y, "minecraft:structure_void") else it }
            val cityId = UUID.randomUUID()
            val owner = UUID.randomUUID()
            val preparation = s.subject.captureManualFoundation(cityId, owner, s.world.uid, building, cells,
                java.util.concurrent.Executor(Runnable::run))
            s.scheduler.tick()
            val terrain = requireNotNull(completed(preparation))
            terrain.blocks.none { it.position == marker } shouldBe true
            terrain.blocks.filter { it.position.x == 1 && it.after == "minecraft:air" }.map { it.position.y } shouldBe (73 downTo 69).toList()
            val capture = s.subject.captureSite(cityId, owner, s.world.uid, building, cells, null, terrain)
            s.scheduler.tick()
            val plan = completed(capture)
            plan.terrain shouldBe terrain
            plan.blocks.first { it.position == CivPosition(1, 70, 0) }.before shouldBe "minecraft:air"
            s.world.getBlockAt(1, 73, 0).type shouldBe Material.STONE
            s.world.getBlockAt(marker.x, marker.y, marker.z).type shouldBe Material.STONE
        }
    }

    test("manual foundation returns no terrain when the footprint and entrance already have safe support") {
        worldScenario(collisionWorld = true) { s ->
            val building = worldBuilding(worldBox(0, 0))
            s.world.getBlockAt(0, 64, 0).type = Material.STONE
            val preparation = s.subject.captureManualFoundation(UUID.randomUUID(), UUID.randomUUID(), s.world.uid,
                building, worldSchematicCells(building), java.util.concurrent.Executor(Runnable::run))
            s.scheduler.tick()
            completed(preparation) shouldBe null
            s.world.getBlockAt(0, 64, 0).type shouldBe Material.STONE
        }
    }

    for (plant in listOf(Material.LEAF_LITTER, Material.WILDFLOWERS, Material.PINK_PETALS, Material.BUSH,
        Material.FIREFLY_BUSH, Material.SHORT_DRY_GRASS, Material.TALL_DRY_GRASS, Material.CLOSED_EYEBLOSSOM,
        Material.OPEN_EYEBLOSSOM, Material.CACTUS_FLOWER, Material.PALE_MOSS_CARPET, Material.PALE_HANGING_MOSS))
    test("strict autonomous admission treats native $plant ground cover as clearable vegetation") {
        worldScenario(collisionWorld = true) { s ->
            val building = worldBuilding(worldBox(0, 0)).copy(anchor = CivPosition(0, 63, 0))
            s.world.getBlockAt(0, 63, 0).type = Material.DIRT
            val block = s.world.getBlockAt(0, 64, 0).also { it.type = plant }
            val before = block.blockData.asString
            val capture = s.subject.captureAutonomousSite(UUID.randomUUID(), UUID.randomUUID(), s.world.uid,
                building, worldSchematicCells(building))
            s.scheduler.tick()
            completed(capture).blocks.single().before shouldBe before
            block.type shouldBe plant
        }
    }

    for (ground in listOf(63, 64))
    test("native leaf litter ${if (ground == 63) "clears" else "fills"} through terrain overlay - MockBukkit lacks LeafLitter properties")
        .config(enabled = false) {
        worldScenario(collisionWorld = true) { s ->
            val building = worldBuilding(CivBox(CivPosition(0, 64, 0), CivPosition(0, 66, 0)))
                .copy(anchor = CivPosition(0, ground, 0), entrance = CivPosition(-1, ground + 1, 0))
            val position = CivPosition(0, 64, 0)
            // MockBukkit 4.116.3 cannot parse LeafLitter's facing/segment_amount, including terrain-ledger replay.
            // Keep this native scenario explicit; enabled material-admission and exact detached-planner cases cover their layers.
            val litterData = Bukkit.createBlockData("minecraft:leaf_litter[facing=south,segment_amount=3]")
            val litter = litterData.asString
            s.world.getBlockAt(0, 63, 0).type = Material.DIRT
            s.world.getBlockAt(0, 64, 0).blockData = litterData
            val cityId = UUID.randomUUID()
            val owner = UUID.randomUUID()
            val cells = worldSchematicCells(building)
            // Without a terrain overlay, strict schematic admission must recognise the same plant.
            val direct = s.subject.captureAutonomousSite(cityId, owner, s.world.uid, building, cells)
            s.scheduler.tick()
            completed(direct).blocks.first { it.position == position }.before shouldBe litter
            val preparation = s.subject.captureManualFoundation(cityId, owner, s.world.uid,
                building, cells, java.util.concurrent.Executor(Runnable::run), autonomous = true)
            s.scheduler.tick()
            val terrain = requireNotNull(completed(preparation))
            val after = if (ground == 63) "minecraft:air" else "minecraft:dirt"
            terrain.blocks.filter { it.position.x == 0 } shouldBe listOf(CivPlanBlock(position, litter, after))
            // Capturing the final plan also revalidates the exact original earthwork cell.
            val capture = s.subject.captureAutonomousSite(cityId, owner, s.world.uid, building, cells, terrain)
            s.scheduler.tick()
            completed(capture).blocks.first { it.position == position }.before shouldBe after
            s.world.getBlockAt(0, 63, 0).type shouldBe Material.DIRT
            s.world.getBlockAt(0, 64, 0).blockData.asString shouldBe litter
        }
    }

    for (platform in listOf(Material.OAK_PLANKS, Material.STONE_BRICKS))
    test("ordinary manual foundation reuses an unchanged $platform platform without creating terrain") {
        worldScenario(collisionWorld = true) { s ->
            val building = worldBuilding(worldBox(0, 0)).copy(anchor = CivPosition(0, 63, 0))
            s.world.getBlockAt(0, 63, 0).type = platform
            val cityId = UUID.randomUUID()
            val owner = UUID.randomUUID()
            val preparation = s.subject.captureManualFoundation(cityId, owner, s.world.uid,
                building, worldSchematicCells(building), java.util.concurrent.Executor(Runnable::run))
            s.scheduler.tick()
            completed(preparation) shouldBe null
            val capture = s.subject.captureSite(cityId, owner, s.world.uid, building, worldSchematicCells(building), null)
            s.scheduler.tick()
            completed(capture).terrain shouldBe null
            s.world.getBlockAt(0, 63, 0).type shouldBe platform
        }
    }

    test("ordinary mixed platform preserves crafted supports while only natural gaps receive dirt") {
        worldScenario(collisionWorld = true) { s ->
            val building = worldBuilding(worldBox(0, 1))
            s.world.getBlockAt(0, 63, 0).type = Material.OAK_PLANKS
            s.world.getBlockAt(0, 64, 0).type = Material.STONE_BRICKS
            s.world.getBlockAt(1, 63, 0).type = Material.AIR
            val cityId = UUID.randomUUID()
            val owner = UUID.randomUUID()
            val cells = (0..1).map { worldCell(it, 64, "minecraft:oak_planks") }
            val preparation = s.subject.captureManualFoundation(cityId, owner, s.world.uid, building, cells,
                java.util.concurrent.Executor(Runnable::run))
            s.scheduler.tick()
            val terrain = requireNotNull(completed(preparation))
            terrain.blocks shouldBe listOf(
                CivPlanBlock(CivPosition(1, 63, 0), "minecraft:air", "minecraft:dirt"),
                CivPlanBlock(CivPosition(1, 64, 0), "minecraft:air", "minecraft:dirt"))
            val capture = s.subject.captureSite(cityId, owner, s.world.uid, building, cells, null, terrain)
            s.scheduler.tick()
            val plan = completed(capture)
            plan.blocks.first().before shouldBe "minecraft:stone_bricks"
            plan.blocks.last().before shouldBe "minecraft:dirt"
            s.world.getBlockAt(0, 63, 0).type shouldBe Material.OAK_PLANKS
            s.world.getBlockAt(0, 64, 0).type shouldBe Material.STONE_BRICKS
            s.world.getBlockAt(1, 63, 0).type shouldBe Material.AIR
        }
    }

    for (published in listOf(false, true)) for (platform in listOf(Material.OAK_PLANKS, Material.STONE_BRICKS, Material.COAL_ORE))
    test("${if (published) "published" else "unpublished"} autonomous foundation retains an unchanged $platform base") {
        worldScenario(collisionWorld = true) { s ->
            val building = worldBuilding(worldBox(0, 0)).copy(anchor = CivPosition(0, 63, 0))
            val city = worldCity(s.world.uid, building).copy(buildings = emptyList(), autonomy = CivAutonomy())
            if (published) s.cities += city
            s.world.getBlockAt(0, 63, 0).type = platform
            val cells = worldSchematicCells(building)
            val preparation = s.subject.captureManualFoundation(city.id, city.owner, s.world.uid,
                building, cells, java.util.concurrent.Executor(Runnable::run), autonomous = !published)
            s.scheduler.tick()
            completed(preparation) shouldBe null
            val capture = s.subject.captureAutonomousSite(city.id, city.owner, s.world.uid, building, cells)
            s.scheduler.tick()
            completed(capture).terrain shouldBe null
            s.world.getBlockAt(0, 63, 0).type shouldBe platform
        }
    }

    test("autonomous foundation retains ore and crafted fill support without authorizing a crafted schematic replacement") {
        worldScenario(collisionWorld = true) { s ->
            val building = worldBuilding(CivBox(CivPosition(0, 66, 0), CivPosition(0, 70, 0)))
                .copy(anchor = CivPosition(0, 68, 0), entrance = CivPosition(-1, 69, 0))
            s.world.getBlockAt(0, 65, 0).type = Material.COAL_ORE
            s.world.getBlockAt(0, 67, 0).type = Material.STONE_BRICKS
            val cityId = UUID.randomUUID()
            val owner = UUID.randomUUID()
            val cells = worldSchematicCells(building)
            val preparation = s.subject.captureManualFoundation(cityId, owner, s.world.uid,
                building, cells, java.util.concurrent.Executor(Runnable::run), autonomous = true)
            s.scheduler.tick()
            val terrain = requireNotNull(completed(preparation))
            terrain.blocks.filter { it.position.x == 0 } shouldBe listOf(
                CivPlanBlock(CivPosition(0, 66, 0), "minecraft:air", "minecraft:dirt"),
                CivPlanBlock(CivPosition(0, 68, 0), "minecraft:air", "minecraft:dirt"))
            val capture = s.subject.captureAutonomousSite(cityId, owner, s.world.uid, building, cells, terrain)
            s.scheduler.tick()
            val failure = rejection(capture)
            failure.reason shouldBe "autonomy-terrain"
            failure.values["block"] shouldBe "STONE_BRICKS"
            failure.values["y"] shouldBe "67"
            s.world.getBlockAt(0, 65, 0).type shouldBe Material.COAL_ORE
            s.world.getBlockAt(0, 67, 0).type shouldBe Material.STONE_BRICKS
            s.world.getBlockAt(0, 66, 0).type shouldBe Material.AIR
        }
    }

    test("ordinary manual foundation retains its existing crafted marker placement permission") {
        worldScenario(collisionWorld = true) { s ->
            val marker = CivPosition(0, 65, 0)
            val building = worldBuilding(CivBox(CivPosition(0, 64, 0), marker)).copy(control = marker)
            s.world.getBlockAt(0, 64, 0).type = Material.STONE
            s.world.getBlockAt(0, 65, 0).type = Material.OAK_PLANKS
            val cells = listOf(worldCell(0, 64, "minecraft:air"), worldCell(0, 65, "minecraft:structure_void"))
            val cityId = UUID.randomUUID()
            val owner = UUID.randomUUID()
            val preparation = s.subject.captureManualFoundation(cityId, owner, s.world.uid, building, cells,
                java.util.concurrent.Executor(Runnable::run))
            s.scheduler.tick()
            completed(preparation) shouldBe null
            val capture = s.subject.captureSite(cityId, owner, s.world.uid, building, cells, null)
            s.scheduler.tick()
            completed(capture).blocks.single().before shouldBe "minecraft:stone"
            s.world.getBlockAt(0, 65, 0).type shouldBe Material.OAK_PLANKS
        }
    }

    test("ordinary foundation still refuses to cut a crafted block above its intended ground") {
        worldScenario(collisionWorld = true) { s ->
            val building = worldBuilding(CivBox(CivPosition(0, 64, 0), CivPosition(0, 70, 0)))
            s.world.getBlockAt(0, 67, 0).type = Material.OAK_PLANKS
            val preparation = s.subject.captureManualFoundation(UUID.randomUUID(), UUID.randomUUID(), s.world.uid,
                building, worldSchematicCells(building), java.util.concurrent.Executor(Runnable::run))
            s.scheduler.tick()
            failureReason(preparation) shouldBe "autonomy-terrain"
            s.world.getBlockAt(0, 67, 0).type shouldBe Material.OAK_PLANKS
        }
    }

    test("an autonomous first marker may occupy natural rock when its manual foundation needs no changes") {
        worldScenario(collisionWorld = true) { s ->
            val marker = CivPosition(0, 65, 0)
            val building = worldBuilding(CivBox(CivPosition(0, 64, 0), marker)).copy(control = marker)
            s.world.getBlockAt(0, 64, 0).type = Material.STONE
            s.world.getBlockAt(0, 65, 0).type = Material.STONE
            val cells = listOf(worldCell(0, 64, "minecraft:air"), worldCell(0, 65, "minecraft:structure_void"))
            val cityId = UUID.randomUUID()
            val owner = UUID.randomUUID()
            val preparation = s.subject.captureManualFoundation(cityId, owner, s.world.uid,
                building, cells, java.util.concurrent.Executor(Runnable::run))
            s.scheduler.tick()
            completed(preparation) shouldBe null
            val capture = s.subject.captureAutonomousSite(cityId, owner, s.world.uid, building, cells)
            s.scheduler.tick()
            completed(capture).blocks.single().before shouldBe "minecraft:stone"
            s.world.getBlockAt(0, 65, 0).type shouldBe Material.STONE
        }
    }

    for (obstacle in listOf(Material.WATER, Material.CHEST))
    test("manual foundation protects its reserved marker from $obstacle even when no other terrain changes") {
        worldScenario(collisionWorld = true) { s ->
            val marker = CivPosition(0, 65, 0)
            val building = worldBuilding(CivBox(CivPosition(0, 64, 0), marker)).copy(control = marker)
            s.world.getBlockAt(0, 64, 0).type = Material.STONE
            s.world.getBlockAt(0, 65, 0).type = obstacle
            val preparation = s.subject.captureManualFoundation(UUID.randomUUID(), UUID.randomUUID(), s.world.uid,
                building, worldSchematicCells(building), java.util.concurrent.Executor(Runnable::run))
            s.scheduler.tick()
            failureReason(preparation) shouldBe "autonomy-terrain"
            s.world.getBlockAt(0, 65, 0).type shouldBe obstacle
        }
    }

    for (obstacle in listOf(Material.WATER, Material.CHEST))
    test("manual foundation refuses $obstacle in its first deep column before scanning the rest or dispatching planning") {
        worldScenario(collisionWorld = true) { s ->
            val building = worldBuilding(CivBox(CivPosition(0, 100, 0), CivPosition(12, 105, 12)))
                .copy(anchor = CivPosition(0, 104, 0), entrance = CivPosition(-1, 105, 0))
            s.world.getBlockAt(0, 70, 0).type = obstacle
            val work = mutableListOf<Runnable>()
            val preparation = s.subject.captureManualFoundation(UUID.randomUUID(), UUID.randomUUID(), s.world.uid,
                building, worldSchematicCells(building), java.util.concurrent.Executor(work::add))
            s.scheduler.tick()
            val failure = rejection(preparation)
            failure.reason shouldBe "autonomy-terrain"
            failure.values shouldBe mapOf("block" to obstacle.name, "x" to "0", "y" to "70", "z" to "0",
                "world" to s.world.uid.toString(), "world_name" to s.world.name)
            work shouldBe emptyList()
            s.world.getBlockAt(0, 70, 0).type shouldBe obstacle
            s.world.getBlockAt(0, 100, 0).type shouldBe Material.AIR
        }
    }

    test("a denied manual base keeps its Lands reason and emits one failed scan event") {
        worldScenario(collisionWorld = true) { s ->
            val building = worldBuilding(worldBox(0, 0)).copy(anchor = CivPosition(0, 63, 0))
            s.world.getBlockAt(0, 63, 0).type = Material.COAL_ORE
            val lands = s.paper.createSimplePlugin("Lands")
            lands.server.pluginManager.disablePlugin(lands)
            val events = mutableListOf<Pair<String, Map<String, Any?>>>()
            mockkObject(CivsDiagnostics)
            every { CivsDiagnostics.event(any(), *anyVararg()) } answers {
                events += firstArg<String>() to secondArg<Array<Pair<String, Any?>>>().toMap()
                Unit
            }
            try {
                val work = mutableListOf<Runnable>()
                val preparation = s.subject.captureManualFoundation(UUID.randomUUID(), UUID.randomUUID(), s.world.uid,
                    building, worldSchematicCells(building), java.util.concurrent.Executor(work::add), autonomous = true)
                s.scheduler.tick(3)
                val failure = rejection(preparation)
                failure.reason shouldBe "lands-denied"
                failure.values["block"] shouldBe "COAL_ORE"
                failure.values["y"] shouldBe "63"
                work shouldBe emptyList()
                val event = events.filter { it.first == "world_scan" }.single().second
                event["phase"] shouldBe "manualfoundationcapture"
                event["cells"] shouldBe 3
                event["outcome"] shouldBe "rejected"
                event["reason"] shouldBe "lands-denied"
                event["detail"] shouldBe failure.values
                ((event["elapsed_ms"] as Long) >= 0) shouldBe true
                s.world.getBlockAt(0, 63, 0).type shouldBe Material.COAL_ORE
            } finally { unmockkObject(CivsDiagnostics) }
        }
    }

    test("manual foundation refreshes retained entrance protection after a building list changes between slices") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                var nanos = 0L
                WorldScenario(paper, collisionWorld = true) { nanos.also { nanos += CivsWorld.NANOS_PER_TICK } }.use { s ->
                    val building = worldBuilding(worldBox(0, 1))
                    val existing = worldBuilding(worldBox(5, 5)).copy(phase = BuildingPhase.ACTIVE,
                        entrance = CivPosition(8, 65, 0))
                    val city = worldCity(s.world.uid, existing)
                    s.cities += city
                    val preparation = s.subject.captureManualFoundation(city.id, city.owner, s.world.uid,
                        building, worldSchematicCells(building), java.util.concurrent.Executor(Runnable::run))
                    s.scheduler.tick(4)
                    preparation.isDone shouldBe false
                    s.cities[0] = city.copy(buildings = listOf(existing.copy(entrance = CivPosition(1, 65, 0))))
                    s.scheduler.tick(3)
                    val failure = rejection(preparation)
                    failure.reason shouldBe "building-overlap"
                    failure.values["x"] shouldBe "1"
                    failure.values["y"] shouldBe "64"
                    s.world.getBlockAt(1, 64, 0).type shouldBe Material.AIR
                }
            }
        }
    }

    test("a large manual foundation yields to the shared native scan budget") {
        worldScenario(collisionWorld = true) { s ->
            val building = worldBuilding(CivBox(CivPosition(0, 100, 0), CivPosition(12, 105, 12)))
                .copy(anchor = CivPosition(0, 104, 0), entrance = CivPosition(-1, 105, 0))
            val preparation = s.subject.captureManualFoundation(UUID.randomUUID(), UUID.randomUUID(), s.world.uid,
                building, worldSchematicCells(building), java.util.concurrent.Executor(Runnable::run))
            s.scheduler.tick()
            preparation.isDone shouldBe false
            repeat(10) { if (!preparation.isDone) s.scheduler.tick() }
            requireNotNull(completed(preparation)).fillCount shouldBe 170 * (104 - 63)
            s.world.getBlockAt(0, 100, 0).type shouldBe Material.AIR
        }
    }

    test("manual terrain apply requires the exact persisted automatic preparation prefix") {
        worldScenario(collisionWorld = true) { s ->
            val cell = CivPlanBlock(CivPosition(0, 64, 0), "minecraft:air", "minecraft:dirt")
            val plan = worldTerraformPlan(s, listOf(cell)).let { it.copy(terrain = it.terrain!!.copy(route = emptyList())) }
            val city = worldTerraformCity(s, plan).let { it.copy(autonomy = null,
                construction = it.construction!!.copy(automaticPreparationBatches = 1)) }
            s.cities.clear(); s.cities += city
            val unreserved = city.copy(construction = city.construction!!.copy(automaticPreparationBatches = null))
            s.cities.clear(); s.cities += unreserved
            val rejected = s.subject.applyTerraform(plan, city.owner, listOf(cell), 0, { true }, {})
            s.scheduler.tick()
            failureReason(rejected) shouldBe "stale-construction"
            s.world.getBlockAt(0, 64, 0).type shouldBe Material.AIR
            s.cities.clear(); s.cities += city
            val placed = mutableListOf<CivPlanBlock>()
            val accepted = s.subject.applyTerraform(plan, city.owner, listOf(cell), 0, { true }, placed::add)
            s.scheduler.tick()
            completed(accepted) shouldBe Unit
            placed shouldBe listOf(cell)
            s.world.getBlockAt(0, 64, 0).type shouldBe Material.DIRT
        }
    }

    test("terrain materialization preserves a dirt path and native completion waits for the owning tick") {
        worldScenario(collisionWorld = true) { s ->
            val building = worldBuilding(worldBox(0, 0))
            val city = worldCity(s.world.uid, building).copy(buildings = emptyList(), autonomy = CivAutonomy())
            s.cities += city
            s.world.getBlockAt(-1, 63, 0).type = Material.DIRT_PATH
            s.world.getBlockAt(0, 65, 0).type = Material.DIRT
            val draft = CivTerraformDraft(building, listOf(
                CivTerraformColumn(-1, 0, 63, 63, 63, preserve = true),
                CivTerraformColumn(0, 0, 64, 63, 65),
            ), listOf(building.entrance), 64)
            val work = mutableListOf<Runnable>()
            val captured = s.subject.captureTerraform(city, draft, java.util.concurrent.Executor(work::add))
            s.scheduler.tick()
            captured.isDone shouldBe false
            work.size shouldBe 1
            CompletableFuture.runAsync { work.single().run() }.join()
            captured.isDone shouldBe false
            s.scheduler.tick()
            completed(captured).blocks shouldBe listOf(
                CivPlanBlock(CivPosition(0, 65, 0), "minecraft:dirt", "minecraft:air"),
                CivPlanBlock(CivPosition(0, 64, 0), "minecraft:air", "minecraft:coarse_dirt"),
            )
            s.world.getBlockAt(0, 65, 0).type shouldBe Material.DIRT
            s.world.getBlockAt(0, 64, 0).type shouldBe Material.AIR
            s.world.getBlockAt(-1, 63, 0).type shouldBe Material.DIRT_PATH
        }
    }

    test("tree restart uses the original removed canopy proof and preserves a changed pending log") {
        worldScenario(collisionWorld = true) { s ->
            val leaves = Bukkit.createBlockData("minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]").asString
            val log = Bukkit.createBlockData("minecraft:oak_log[axis=y]").asString
            val cells = listOf(
                CivPlanBlock(CivPosition(0, 66, 0), leaves, "minecraft:air"),
                CivPlanBlock(CivPosition(0, 65, 0), log, "minecraft:air"),
                CivPlanBlock(CivPosition(0, 64, 0), log, "minecraft:air"),
            )
            val plan = worldTerraformPlan(s, cells)
            val city = worldTerraformCity(s, plan)
            s.world.getBlockAt(0, 65, 0).type = Material.OAK_LOG
            s.world.getBlockAt(0, 64, 0).type = Material.OAK_LOG
            val placed = mutableListOf<CivPlanBlock>()
            // The completed leaf prefix is absent from both the native world and the resumed batch.
            val resumed = s.subject.applyTerraform(plan, city.owner, cells.subList(1, 3), 1, { true }, placed::add)
            s.scheduler.tick()
            placed shouldBe cells.subList(1, 2)
            s.world.getBlockAt(0, 65, 0).type shouldBe Material.AIR
            s.world.getBlockAt(0, 64, 0).type = Material.BIRCH_LOG
            s.scheduler.tick()
            failureReason(resumed) shouldBe "world-changed"
            s.world.getBlockAt(0, 64, 0).type shouldBe Material.BIRCH_LOG

            s.world.getBlockAt(0, 64, 0).type = Material.OAK_LOG
            val remaining = s.subject.applyTerraform(plan, city.owner, cells.takeLast(1), 2, { true }, {})
            s.scheduler.tick()
            completed(remaining) shouldBe Unit
            s.world.getBlockAt(0, 64, 0).type shouldBe Material.AIR
        }
    }

    test("a raised terrace replaces a short tree bottom up using its later original canopy proof") {
        worldScenario(collisionWorld = true) { s ->
            val log = Bukkit.createBlockData("minecraft:oak_log[axis=y]").asString
            val leaves = Bukkit.createBlockData("minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]").asString
            val cells = listOf(
                CivPlanBlock(CivPosition(0, 64, 0), log, "minecraft:dirt"),
                CivPlanBlock(CivPosition(0, 65, 0), log, "minecraft:dirt"),
                CivPlanBlock(CivPosition(0, 66, 0), leaves, "minecraft:coarse_dirt"),
            )
            val plan = worldTerraformPlan(s, cells)
            val city = worldTerraformCity(s, plan)
            s.world.getBlockAt(0, 64, 0).type = Material.OAK_LOG
            s.world.getBlockAt(0, 65, 0).type = Material.OAK_LOG
            s.world.getBlockAt(0, 66, 0).blockData = Bukkit.createBlockData(leaves)
            val placed = mutableListOf<CivPlanBlock>()
            val apply = s.subject.applyTerraform(plan, city.owner, cells, 0, { true }, placed::add)
            s.scheduler.tick(3)
            completed(apply) shouldBe Unit
            placed shouldBe cells
            (64..66).map { s.world.getBlockAt(0, it, 0).type } shouldBe listOf(Material.DIRT, Material.DIRT, Material.COARSE_DIRT)
        }
    }

    test("an uncommitted dirt fill replays after grass growth without rewriting the grown ground") {
        worldScenario(collisionWorld = true) { s ->
            val point = CivPosition(0, 64, 0)
            val cell = CivPlanBlock(point, "minecraft:air", "minecraft:dirt")
            val plan = worldTerraformPlan(s, listOf(cell))
            val city = worldTerraformCity(s, plan)
            val block = s.world.getBlockAt(point.x, point.y, point.z)
            // The first attempt placed dirt but did not commit its durable batch cursor.
            block.blockData = Bukkit.createBlockData("minecraft:grass_block[snowy=true]")
            repeat(2) {
                val placed = mutableListOf<CivPlanBlock>()
                val replay = s.subject.applyTerraform(plan, city.owner, listOf(cell), 0, { true }, placed::add)
                s.scheduler.tick()
                completed(replay) shouldBe Unit
                placed shouldBe listOf(cell)
                block.blockData.asString shouldBe "minecraft:grass_block[snowy=true]"
            }
            for (material in listOf(Material.MYCELIUM, Material.PODZOL, Material.COARSE_DIRT)) {
                block.type = material
                val rejected = s.subject.applyTerraform(plan, city.owner, listOf(cell), 0, { true }, {})
                s.scheduler.tick()
                failureReason(rejected) shouldBe "world-changed"
                block.type shouldBe material
            }
        }
    }

    test("retained dirt earthwork accepts natural grass without accepting other ground substitutions") {
        worldScenario(collisionWorld = true) { s ->
            val point = CivPosition(0, 64, 0)
            val cell = CivPlanBlock(point, "minecraft:dirt", "minecraft:coarse_dirt")
            val plan = worldTerraformPlan(s, listOf(cell))
            val city = worldTerraformCity(s, plan)
            val block = s.world.getBlockAt(point.x, point.y, point.z)
            for (state in listOf("minecraft:grass_block[snowy=false]", "minecraft:grass_block[snowy=true]")) {
                block.blockData = Bukkit.createBlockData(state)
                val apply = s.subject.applyTerraform(plan, city.owner, listOf(cell), 0, { true }, {})
                s.scheduler.tick()
                completed(apply) shouldBe Unit
                block.type shouldBe Material.COARSE_DIRT
            }
            for (material in listOf(Material.MYCELIUM, Material.PODZOL, Material.ROOTED_DIRT)) {
                block.type = material
                val guarded = s.subject.applyTerraform(plan, city.owner, listOf(cell), 0, { true }, {})
                s.scheduler.tick()
                failureReason(guarded) shouldBe "world-changed"
                block.type shouldBe material
            }
        }
    }

    test("natural leaf distance drift is recaptured and applied but material persistent and wet edits stay protected") {
        worldScenario(collisionWorld = true) { s ->
            val point = CivPosition(0, 65, 0)
            val original = Bukkit.createBlockData("minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]").asString
            val changed = Bukkit.createBlockData("minecraft:oak_leaves[distance=7,persistent=false,waterlogged=false]")
            val cell = CivPlanBlock(point, original, "minecraft:air")
            val plan = worldTerraformPlan(s, listOf(cell))
            val city = worldTerraformCity(s, plan)
            val block = s.world.getBlockAt(point.x, point.y, point.z)
            block.blockData = changed
            val apply = s.subject.applyTerraform(plan, city.owner, listOf(cell), 0, { true }, {})
            s.scheduler.tick()
            completed(apply) shouldBe Unit
            block.type shouldBe Material.AIR

            for ((data, reason) in listOf(
                "minecraft:birch_leaves[distance=7,persistent=false,waterlogged=false]" to "world-changed",
                "minecraft:oak_leaves[distance=7,persistent=true,waterlogged=false]" to "autonomy-terrain",
                "minecraft:oak_leaves[distance=7,persistent=false,waterlogged=true]" to "autonomy-terrain",
            )) {
                block.blockData = Bukkit.createBlockData(data)
                val guarded = s.subject.applyTerraform(plan, city.owner, listOf(cell), 0, { true }, {})
                s.scheduler.tick()
                failureReason(guarded) shouldBe reason
                block.blockData.asString shouldBe Bukkit.createBlockData(data).asString
            }

            s.cities.clear(); s.cities += city.copy(buildings = emptyList(), construction = null)
            block.blockData = changed
            val capture = s.subject.captureAutonomousSite(city.id, city.owner, city.worldId, plan.building,
                listOf(worldCell(0, 64, "minecraft:stone")), plan.terrain)
            s.scheduler.tick()
            completed(capture).terrain!!.blocks.single().before shouldBe changed.asString
        }
    }

    test("bare logs and persistent leaves cannot be cleared as natural trees") {
        for (data in listOf("minecraft:oak_log[axis=y]",
            "minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]")) worldScenario { s ->
            val block = s.world.getBlockAt(0, 64, 0)
            block.blockData = Bukkit.createBlockData(data)
            val cell = CivPlanBlock(CivPosition(0, 64, 0), block.blockData.asString, "minecraft:air")
            val plan = worldTerraformPlan(s, listOf(cell))
            val city = worldTerraformCity(s, plan)
            val apply = s.subject.applyTerraform(plan, city.owner, listOf(cell), 0, { true }, {})
            s.scheduler.tick()
            failureReason(apply) shouldBe "autonomy-terrain"
            block.blockData.asString shouldBe Bukkit.createBlockData(data).asString
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

private class WorldScenario(val paper: MockBukkitTestRuntime, collisionWorld: Boolean = false,
    nanoTime: () -> Long = { 0L }) : AutoCloseable {
    val world = if (collisionWorld) CivsWorksiteWorldFixture("civs-world").also(paper.server::addWorld)
        else paper.addSimpleWorld("civs-world")
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

private fun worldScenario(collisionWorld: Boolean = false, action: (WorldScenario) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open().use { paper -> WorldScenario(paper, collisionWorld).use(action) }
}

private fun worldBox(minX: Int, maxX: Int) = CivBox(CivPosition(minX, 64, 0), CivPosition(maxX, 64, 0))
private fun worldBuilding(box: CivBox) = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1,
    CivPosition(box.min.x, 64, 0), 0, "test", box, CivPosition(-1, 64, 0), BuildingPhase.CONSTRUCTING)
private fun worldCity(worldId: UUID, building: CivBuilding) = Settlement(UUID.randomUUID(), UUID.randomUUID(), "Test",
    worldId, CivPosition(0, 64, 0), 100, "test", Stock(), listOf(building), emptyList())
private fun worldCell(x: Int, y: Int, data: String) = ArcSchematicBlock(ArcSchematicPoint(x, y, 0), data,
    data == "minecraft:air", data == "minecraft:barrier")
private fun worldCells(building: CivBuilding) = (building.bounds.min.x..building.bounds.max.x).map { worldCell(it, 64, "minecraft:air") }
private fun worldSchematicCells(building: CivBuilding) = buildList {
    for (y in building.bounds.min.y..building.bounds.max.y) for (z in building.bounds.min.z..building.bounds.max.z)
        for (x in building.bounds.min.x..building.bounds.max.x) add(ArcSchematicBlock(ArcSchematicPoint(x, y, z), "minecraft:air", true, false))
}
private fun worldTerraformPlan(s: WorldScenario, cells: List<CivPlanBlock>,
    building: CivBuilding = worldBuilding(worldBox(0, 0))): CivPlacementPlan {
    val positions = cells.map { it.position } + building.entrance
    val bounds = CivBox(CivPosition(positions.minOf { it.x }, positions.minOf { it.y }, positions.minOf { it.z }),
        CivPosition(positions.maxOf { it.x }, positions.maxOf { it.y }, positions.maxOf { it.z }))
    val terrain = CivTerraformPlan(bounds, cells, listOf(building.entrance), building.anchor.y,
        cells.count { it.after == "minecraft:air" }, cells.count { it.after != "minecraft:air" })
    return CivPlacementPlan(UUID.randomUUID(), UUID.randomUUID(), s.world.uid, building,
        listOf(CivPlanBlock(building.bounds.min, "minecraft:air", "minecraft:stone")), terrain = terrain)
}
private fun worldTerraformCity(s: WorldScenario, plan: CivPlacementPlan): Settlement {
    val city = worldCity(s.world.uid, plan.building.copy(phase = BuildingPhase.SITE)).copy(id = plan.cityId,
        autonomy = CivAutonomy(), construction = CivConstruction(plan.id, plan.building.id, 1, 1, 0,
            ConstructionPhase.PLACING, Stock(), plannedBuilding = plan.building))
    s.cities += city
    return city
}
private fun worldAccessCity(s: WorldScenario): Settlement {
    val older = worldBuilding(worldBox(-10, -8)).copy(type = BuildingType.HOUSE, phase = BuildingPhase.ACTIVE,
        entrance = CivPosition(0, 64, 0))
    val newer = worldBuilding(worldBox(8, 10)).copy(type = BuildingType.FORESTER, phase = BuildingPhase.ACTIVE,
        entrance = CivPosition(0, 66, 0))
    return worldCity(s.world.uid, older).copy(autonomy = CivAutonomy(), buildings = listOf(older, newer),
        stock = Stock(9, 7, 5)).also(s.cities::add)
}
private fun worldAccessRepair(city: Settlement, standing: CivPosition, cell: CivPlanBlock) =
    CivAutonomousAccessRepair(city.buildings[0].id, city.buildings[1].id, standing, cell)
private fun <T> completed(future: CompletableFuture<T>): T { future.isDone shouldBe true; return future.join() }
private fun rejection(future: CompletableFuture<*>): CivsRejection {
    future.isDone shouldBe true
    return try { future.join(); error("Expected a CivsRejection") } catch (failure: CompletionException) { failure.cause as CivsRejection }
}
private fun failureReason(future: CompletableFuture<*>): String = rejection(future).reason
