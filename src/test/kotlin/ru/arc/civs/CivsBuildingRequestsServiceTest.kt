package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.paper.api.ArcBlockProtectionService
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicMutationResult
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot

class CivsBuildingRequestsServiceTest : FunSpec({
    test("starting an unstaffed site rejects before loading or capturing its design") {
        serviceScenario { h ->
            val hut = h.city.buildings.last().copy(type = BuildingType.BUILDER_HUT, phase = BuildingPhase.SITE,
                control = CivPosition(10, 63, 0))
            h.world.getBlockAt(10, 63, 0).type = Material.BARREL
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(h.city.copy(
                buildings = listOf(h.city.buildings.first(), hut))))
            h.start()
            val result = h.service.startBuilding(h.player, hut.id)
            result.isCompletedExceptionally shouldBe true
            (shouldThrow<CompletionException> { result.join() }.cause as CivsRejection).reason shouldBe "builder-required"
            verify(exactly = 0) {
                h.store.loadPlan(any()); h.store.loadSchematic(any()); h.blueprints.recover(any())
                h.worldAdapter.capture(any(), any(), any(), any(), any(), any(), constructionStorage = true, acceptedStorage = emptyList())
            }
        }
    }

    test("a builder may start its site with no resources and a separately journalled free approach") {
        serviceScenario { h ->
            val hut = h.city.buildings.last().copy(type = BuildingType.BUILDER_HUT, phase = BuildingPhase.SITE,
                control = CivPosition(10, 63, 0), entrance = CivPosition(10, 64, 3))
            h.world.getBlockAt(10, 63, 0).type = Material.BARREL
            val city = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                buildings = listOf(h.city.buildings.first(), hut), workers = h.city.workers.mapIndexed { index, worker ->
                    if (index == 0) worker.copy(assignment = hut.id, nextDeliveryAt = h.time + 10_000) else worker
                })
            val plan = CivPlacementPlan(hut.id, city.id, city.worldId, hut.copy(phase = BuildingPhase.CONSTRUCTING),
                listOf(CivPlanBlock(CivPosition(10, 64, 0), "minecraft:air", "minecraft:stone")), "a".repeat(64))
            val snapshot = mockk<ArcSchematicSnapshot>()
            every { snapshot.blocks() } returns listOf(ArcSchematicBlock(ArcSchematicPoint(0, 0, 0), "minecraft:stone", false, false))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.store.loadPlan(hut.id) } returns CompletableFuture.completedFuture(plan)
            every { h.store.loadSchematic(hut.id) } returns CompletableFuture.completedFuture(byteArrayOf(1))
            every { h.blueprints.recover(plan) } returns CompletableFuture.completedFuture(snapshot)
            every { h.worldAdapter.capture(city.id, city.owner, city.worldId, plan.building, any(), hut,
                constructionStorage = true, acceptedStorage = emptyList()) } returns CompletableFuture.completedFuture(plan.copy(id = UUID.randomUUID()))
            every { h.store.saveSchematic(any(), any()) } returns CompletableFuture.completedFuture(Unit)
            every { h.store.savePlan(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            h.start()
            val started = h.service.startBuilding(h.player, hut.id); h.tick()
            h.saves.single().first.construction!!.let {
                it.payment shouldBe CivConstructionPayment()
                it.materials shouldBe mapOf("STONE" to 1L)
                it.batches!!.size shouldBe 16
            }
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
            h.saves.single().first.pendingControls!!.single().approach shouldBe listOf(
                CivPlanBlock(CivPosition(10, 63, 3), "minecraft:air", "minecraft:dirt"))
            h.world.getBlockAt(10, 63, 3).type shouldBe Material.AIR
            h.commit(); started.isDone shouldBe false
            h.world.getBlockAt(10, 63, 3).type shouldBe Material.DIRT
            h.commit(); started.join() shouldBe Unit
            h.service.city(h.player)!!.inventory shouldBe city.inventory
        }
    }

    test("site approach is protected and journalled before free changes and never regenerated after retirement") {
        serviceScenario(empty = true) { h ->
            val viewer = h.paper.addPlayer("ApproachOwner")
            viewer.teleport(Location(h.world, 0.0, 65.0, 0.0))
            every { h.player.uniqueId } returns viewer.uniqueId
            every { h.player.name } returns viewer.name
            val blueprint = h.sitePreview()
            val entrance = blueprint.building.entrance
            val support = h.world.getBlockAt(entrance.x, entrance.y - 1, entrance.z)
            val feet = h.world.getBlockAt(entrance.x, entrance.y, entrance.z).also { it.type = Material.OAK_PLANKS }
            val head = h.world.getBlockAt(entrance.x, entrance.y + 1, entrance.z).also { it.type = Material.GLASS }
            h.start()
            val preview = h.prepareFounding("Approach town", "nordic"); h.tick(); preview.join()
            val confirmed = h.service.confirmPreview(h.player); h.tick()
            val receipt = h.saves.single().first.pendingControls!!.single()
            receipt.approach!!.map { it.after } shouldBe listOf("minecraft:dirt", "minecraft:air", "minecraft:air")
            CivPosition(support.x, support.y, support.z) shouldBe receipt.approach.first().position
            support.type shouldBe Material.AIR
            feet.type shouldBe Material.OAK_PLANKS
            head.type shouldBe Material.GLASS
            h.commit()
            support.type shouldBe Material.DIRT
            feet.type shouldBe Material.AIR
            head.type shouldBe Material.AIR
            val committed = h.service.city(h.player)!!
            committed.pendingControls shouldBe listOf(receipt)
            h.saves.size shouldBe 2
            h.saves.last().second.isDone shouldBe false
            CivPosition(support.x, support.y, support.z) shouldBe receipt.approach.first().position
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            protection.isProtected(h.world.uid, support.x, support.y, support.z) shouldBe true
            viewer.teleport(Location(h.world, 1_000.0, 65.0, 0.0)) // Starter-item materialization needs an actual nearby viewer.
            h.commit(); confirmed.join()
            protection.isProtected(h.world.uid, support.x, support.y, support.z) shouldBe false
            h.service.city(h.player)!!.pendingControls shouldBe emptyList()
            support.type = Material.AIR
            feet.type = Material.STONE
            h.paper.performTicks(60)
            support.type shouldBe Material.AIR
            feet.type shouldBe Material.STONE
            h.saves.size shouldBe 2
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    for (applied in listOf(0, 1, 3)) test("restart resumes $applied applied approach cells without replacing the existing barrel contents") {
        serviceScenario { h ->
            val viewer = h.paper.addPlayer("ApproachRecovery")
            viewer.teleport(Location(h.world, 0.0, 65.0, 0.0))
            val entrance = CivPosition(0, 64, 5)
            val point = CivPosition(1, 65, 1)
            val hall = h.city.buildings.first().copy(control = point, entrance = entrance)
            val cells = listOf(CivPlanBlock(entrance.copy(y = 63), "minecraft:air", "minecraft:dirt"),
                CivPlanBlock(entrance, "minecraft:oak_planks", "minecraft:air"),
                CivPlanBlock(entrance.copy(y = 65), "minecraft:glass", "minecraft:air"))
            cells.forEachIndexed { index, cell -> h.world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
                .setBlockData(org.bukkit.Bukkit.createBlockData(if (index < applied) cell.after else cell.before), false) }
            val block = h.world.getBlockAt(point.x, point.y, point.z).also { it.type = Material.BARREL }
            val barrel = (block.state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, org.bukkit.inventory.ItemStack(Material.DIAMOND, 7))
            val city = h.city.copy(stock = Stock(), workers = emptyList(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                buildings = listOf(hall) + h.city.buildings.drop(1), members = setOf(viewer.uniqueId),
                memberNames = mapOf(viewer.uniqueId.toString() to viewer.name),
                pendingControls = listOf(CivControlPlacement(hall.id, point, "minecraft:air", cells)))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            h.start(); h.paper.performTicks(20)
            cells.forEach { cell -> h.world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.after }
            barrel.getItem(0)!!.amount shouldBe 7
            h.saves.single().first.pendingControls shouldBe emptyList()
            h.commit(); h.paper.performTicks(40)
            h.saves.size shouldBe 1
        }
    }

    test("approach drift after journal commit rejects all writes including the new barrel") {
        serviceScenario(empty = true) { h ->
            val blueprint = h.sitePreview()
            val entrance = blueprint.building.entrance
            val feet = h.world.getBlockAt(entrance.x, entrance.y, entrance.z).also { it.type = Material.OAK_PLANKS }
            h.start()
            val preview = h.prepareFounding("Changed approach", "nordic"); h.tick(); preview.join()
            val confirmed = h.service.confirmPreview(h.player); h.tick()
            feet.type = Material.BEDROCK
            h.commit()
            (shouldThrow<CompletionException> { confirmed.join() }.cause as CivsRejection).reason shouldBe "world-changed"
            val control = blueprint.building.control!!
            h.world.getBlockAt(control.x, control.y, control.z).type shouldBe Material.AIR
            h.world.getBlockAt(entrance.x, entrance.y - 1, entrance.z).type shouldBe Material.AIR
            feet.type shouldBe Material.BEDROCK
            h.service.city(h.player)!!.pendingControls!!.size shouldBe 1
            h.saves.size shouldBe 1
        }
    }

    for (obstacle in listOf("bedrock", "inventory", "lands")) test("$obstacle blocks approach preparation without native changes or a city journal") {
        serviceScenario(empty = true) { h ->
            val blueprint = h.sitePreview()
            val entrance = blueprint.building.entrance
            val feet = h.world.getBlockAt(entrance.x, entrance.y, entrance.z)
            feet.type = when (obstacle) { "bedrock" -> Material.BEDROCK; "inventory" -> Material.CHEST; else -> Material.STONE }
            val chest = (feet.state as? org.bukkit.block.Chest)?.blockInventory
            chest?.setItem(0, org.bukkit.inventory.ItemStack(Material.DIAMOND, 3))
            if (obstacle == "lands") every { h.worldAdapter.canModify(any(), feet, Material.AIR) } returns false
            h.start()
            val preview = h.prepareFounding("Protected approach", "nordic"); h.tick(); preview.join()
            val confirmed = h.service.confirmPreview(h.player); h.tick()
            (shouldThrow<CompletionException> { confirmed.join() }.cause as CivsRejection).reason shouldBe
                if (obstacle == "lands") "lands-denied" else "protected-terrain"
            h.saves shouldBe emptyList()
            h.world.getBlockAt(entrance.x, entrance.y - 1, entrance.z).type shouldBe Material.AIR
            if (chest != null) chest.getItem(0)!!.amount shouldBe 3
        }
    }

    test("approach preparation keeps a solid support barrel and never clears a neighbouring building") {
        serviceScenario { h ->
            val entrance = CivPosition(0, 64, 10)
            val neighbour = h.city.buildings.last().copy(bounds = CivBox(entrance, entrance.copy(y = 65)),
                anchor = entrance, entrance = entrance.copy(z = 11))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(h.city.copy(
                buildings = listOf(h.city.buildings.first(), neighbour))))
            val base = h.previewBlueprint(CivPosition(0, 63, 10))
            h.sitePreview(base.copy(building = base.building.copy(id = UUID.randomUUID(), type = BuildingType.WAREHOUSE)))
            val support = h.world.getBlockAt(0, 63, 10).also { it.type = Material.BARREL }
            val inventory = (support.state as org.bukkit.block.Barrel).inventory
            inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.DIAMOND, 4))
            h.world.getBlockAt(0, 64, 10).type = Material.OAK_PLANKS
            h.start()
            h.service.beginBuildingSelection(h.player, BuildingType.WAREHOUSE).join()
            val selected = h.service.selectGround(h.player, h.service.selection(h.player)!!.id,
                CivsGroundTarget(h.world.uid, CivPosition(0, 63, 10))); h.tick(); selected.join()
            val confirmed = h.service.confirmPreview(h.player); h.tick()
            (shouldThrow<CompletionException> { confirmed.join() }.cause as CivsRejection).reason shouldBe "building-overlap"
            h.saves shouldBe emptyList()
            support.type shouldBe Material.BARREL
            inventory.getItem(0)!!.amount shouldBe 4
            h.world.getBlockAt(0, 64, 10).type shouldBe Material.OAK_PLANKS
        }
    }

    test("active construction accepts another site into an idempotent queue and opens new placement") {
        serviceScenario { h ->
            val active = h.incrementalPlacement(ConstructionPhase.CONSTRUCTING)
            val site = active.city.buildings[1].copy(phase = BuildingPhase.SITE)
            val city = active.city.copy(buildings = active.city.buildings.map { if (it.id == site.id) site else it })
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            h.start()
            val queued = h.service.startBuilding(h.player, site.id); h.tick()
            h.service.city(h.player)!!.constructionQueue shouldBe null
            h.saves.single().first.constructionQueue shouldBe listOf(site.id)
            h.commit(); queued.join() shouldBe Unit
            h.service.startBuilding(h.player, site.id).join() shouldBe Unit
            h.saves.size shouldBe 1
            h.service.buildingStatus(site.id) shouldBe "building-status.queued"
            h.service.beginBuildingSelection(h.player, BuildingType.WAREHOUSE).join() shouldBe Unit
            h.service.selection(h.player)!!.type shouldBe BuildingType.WAREHOUSE
            verify(exactly = 0) { h.store.loadPlan(site.id) }
        }
    }

    for (background in listOf(false, true)) test("reloaded queue starts its head from fresh terrain and removes that id only with the order commit background=$background") {
        mockkConstructor(CivsChunkLoading::class)
        try {
            var backgroundActive = false
            every { anyConstructed<CivsChunkLoading>().active(any()) } answers { backgroundActive }
            serviceScenario { h ->
                if (!background) {
                    val viewer = h.paper.addPlayer("QueueViewer")
                    viewer.teleport(Location(h.world, 0.0, 65.0, 0.0))
                }
                val site = h.city.buildings.last().copy(phase = BuildingPhase.SITE,
                    control = CivPosition(10, 63, 0), entrance = CivPosition(10, 64, 3))
                val hut = site.copy(id = UUID.randomUUID(), type = BuildingType.BUILDER_HUT, phase = BuildingPhase.ACTIVE,
                    anchor = CivPosition(20, 64, 0), bounds = CivBox(CivPosition(18, 63, -2), CivPosition(22, 68, 2)),
                    entrance = CivPosition(20, 64, 3), control = null)
                h.world.getBlockAt(10, 63, 0).type = Material.BARREL
                h.world.getBlockAt(10, 63, 3).type = Material.DIRT
                val city = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                    buildings = listOf(h.city.buildings.first(), site, hut), constructionQueue = listOf(site.id),
                    workers = listOf(h.city.workers.first().copy(assignment = hut.id, nextDeliveryAt = 1)))
                val immutable = CivPlacementPlan(site.id, city.id, city.worldId, site.copy(phase = BuildingPhase.CONSTRUCTING),
                    listOf(CivPlanBlock(CivPosition(10, 64, 0), "minecraft:dirt", "minecraft:stone")), "a".repeat(64))
                val fresh = immutable.copy(id = UUID.randomUUID(), blocks = immutable.blocks.map { it.copy(before = "minecraft:oak_planks") })
                val snapshot = mockk<ArcSchematicSnapshot>()
                every { snapshot.blocks() } returns listOf(ArcSchematicBlock(ArcSchematicPoint(0, 0, 0), "minecraft:stone", false, false))
                every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
                every { h.store.loadPlan(site.id) } returns CompletableFuture.completedFuture(immutable)
                every { h.store.loadSchematic(site.id) } returns CompletableFuture.completedFuture(byteArrayOf(1))
                every { h.blueprints.recover(immutable) } returns CompletableFuture.completedFuture(snapshot)
                every { h.worldAdapter.capture(city.id, city.owner, city.worldId, immutable.building, any(), site,
                    constructionStorage = true, acceptedStorage = emptyList()) } returns CompletableFuture.completedFuture(fresh)
                every { h.store.saveSchematic(any(), any()) } returns CompletableFuture.completedFuture(Unit)
                every { h.store.savePlan(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
                h.start(); h.paper.performTicks(20)
                if (background) {
                    h.paper.server.onlinePlayers.isEmpty() shouldBe true
                    h.saves shouldBe emptyList()
                    h.service.city(h.player)!!.construction shouldBe null
                    h.service.city(h.player)!!.constructionQueue shouldBe listOf(site.id)
                    verify(exactly = 0) { h.worldAdapter.capture(city.id, city.owner, city.worldId, immutable.building, any(), site,
                        constructionStorage = true, acceptedStorage = emptyList()) }
                    backgroundActive = true
                    h.time += 5_000L
                    h.paper.performTicks(20)
                }
                h.saves.single().first.construction!!.id shouldBe fresh.id
                h.service.city(h.player)!!.constructionQueue shouldBe listOf(site.id)
                verify(exactly = 1) { h.worldAdapter.capture(city.id, city.owner, city.worldId, immutable.building, any(), site,
                    constructionStorage = true, acceptedStorage = emptyList()) }
                verify(exactly = 1) { h.store.savePlan(fresh) }
                h.commit()
                h.service.city(h.player)!!.constructionQueue shouldBe emptyList()
                h.service.city(h.player)!!.construction!!.buildingId shouldBe site.id
            }
        } finally {
            unmockkConstructor(CivsChunkLoading::class)
        }
    }

    for ((autonomous, earthworks) in listOf(true to true, true to false, false to false))
    test("order admission refreshes only autonomous terrain captures before reserving native material payments autonomous=$autonomous terrain=$earthworks") {
        serviceScenario { h ->
            val queued = h.queuedUpgrade()
            val existing = queued.building.copy(phase = if (earthworks) BuildingPhase.SITE else BuildingPhase.ACTIVE)
            val city = queued.city.copy(autonomy = if (autonomous) CivAutonomy(paused = true) else null,
                inventory = if (autonomous) CivInventory(CivStorageMode.PHYSICAL) else queued.city.inventory,
                stock = Stock(),
                buildings = queued.city.buildings.map { if (it.id == existing.id) existing else it })
            val terrain = CivTerraformPlan(CivBox(CivPosition(4, 63, -1), CivPosition(10, 64, 3)),
                listOf(CivPlanBlock(CivPosition(5, 64, 0), "minecraft:dirt", "minecraft:air")),
                listOf(CivPosition(5, 64, 0), CivPosition(6, 64, 0)), 63, 1, 0)
            val target = if (earthworks) existing.copy(phase = BuildingPhase.CONSTRUCTING) else queued.fresh.building
            val plan = queued.fresh.copy(building = target, terrain = terrain.takeIf { earthworks })
            val previousRoute = if (earthworks) emptyList() else listOf(CivPosition(2, 64, 0))
            val old = plan.copy(id = existing.id, terrain = terrain.copy(route = previousRoute))
            val unrelated = old.copy(id = UUID.randomUUID(), cityId = UUID.randomUUID())
            val captures = mutableMapOf(old.id to old, unrelated.id to unrelated)
            val writes = mutableListOf<String>()
            val retained = CompletableFuture<CivPlacementPlan>()
            val bytes = byteArrayOf(2)
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.store.saveSchematic(any(), any()) } answers {
                secondArg<ByteArray>().toList() shouldBe bytes.toList()
                writes += "schematic:${firstArg<UUID>()}"
                CompletableFuture.completedFuture(Unit)
            }
            every { h.store.savePlan(any()) } answers {
                val capture = firstArg<CivPlacementPlan>()
                writes += "plan:${capture.id}"
                if (capture.id == existing.id) retained.thenApply { captures[it.id] = it; it }
                else CompletableFuture.completedFuture(capture).thenApply { captures[it.id] = it; it }
            }
            h.start()
            val starting = h.activateCapturedOrder(city, existing, if (earthworks) old else queued.design, plan, bytes)
            if (autonomous && earthworks) {
                h.tickUntil("retained capture submitted for persistence") { writes.lastOrNull() == "plan:${existing.id}" }
                h.saves shouldBe emptyList()
                starting.isDone shouldBe false
                captures[existing.id] shouldBe old
                retained.complete(plan.copy(id = existing.id)); h.tick()
            } else h.tick()

            val expectedWrites = listOf("schematic:${plan.id}", "plan:${plan.id}") +
                if (autonomous && earthworks) listOf("schematic:${existing.id}", "plan:${existing.id}") else emptyList()
            writes shouldBe expectedWrites
            captures[existing.id] shouldBe if (autonomous && earthworks) plan.copy(id = existing.id) else old
            captures[unrelated.id] shouldBe unrelated
            val reserved = h.saves.single().first
            reserved.construction!!.id shouldBe plan.id
            reserved.construction!!.plannedBuilding shouldBe target
            val cost = Stock()
            reserved.construction!!.payment shouldBe CivConstructionPayment()
            reserved.construction!!.cost shouldBe cost
            reserved.stock shouldBe city.stock - cost
            h.service.settlements().single().stock shouldBe city.stock
            h.commit(); starting.join()
            h.service.settlements().single().stock shouldBe city.stock - cost
            h.saves.size shouldBe 1
        }
    }

    for (failedCapture in listOf("schematic", "plan"))
    test("a retained autonomous $failedCapture failure prevents city reservation and a second charge") {
        serviceScenario { h ->
            val queued = h.queuedUpgrade()
            val existing = queued.building.copy(phase = BuildingPhase.SITE)
            val city = queued.city.copy(autonomy = CivAutonomy(paused = true), inventory = CivInventory(CivStorageMode.PHYSICAL), stock = Stock(),
                buildings = queued.city.buildings.map { if (it.id == existing.id) existing else it })
            val terrain = CivTerraformPlan(CivBox(CivPosition(4, 63, -1), CivPosition(10, 64, 3)),
                listOf(CivPlanBlock(CivPosition(5, 64, 0), "minecraft:dirt", "minecraft:air")),
                listOf(CivPosition(5, 64, 0)), 63, 1, 0)
            val plan = queued.fresh.copy(building = existing.copy(phase = BuildingPhase.CONSTRUCTING), terrain = terrain)
            val failure = IllegalStateException("retained $failedCapture failed")
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.store.saveSchematic(any(), any()) } answers {
                if (firstArg<UUID>() == existing.id && failedCapture == "schematic") CompletableFuture.failedFuture(failure)
                else CompletableFuture.completedFuture(Unit)
            }
            every { h.store.savePlan(any()) } answers {
                if (firstArg<CivPlacementPlan>().id == existing.id && failedCapture == "plan") CompletableFuture.failedFuture(failure)
                else CompletableFuture.completedFuture(firstArg())
            }
            h.start()
            val design = plan.copy(id = existing.id, terrain = terrain.copy(route = emptyList()))
            val starting = h.activateCapturedOrder(city, existing, design, plan, byteArrayOf(2))
            h.tick()

            CivsDiagnostics.cause(shouldThrow<CompletionException> { starting.join() }) shouldBe failure
            h.saves shouldBe emptyList()
            h.service.settlements().single() shouldBe city
            verify(exactly = 1) { h.store.savePlan(plan) }
            verify(exactly = if (failedCapture == "plan") 1 else 0) { h.store.savePlan(plan.copy(id = existing.id)) }
            verify(exactly = 0) { h.store.save(any()) }
        }
    }

    test("direct upgrade persists its own design behind active construction and duplicate requests keep it immutable") {
        serviceScenario { h ->
            val active = h.incrementalPlacement(ConstructionPhase.CONSTRUCTING)
            val building = active.city.buildings[1]
            val blueprint = h.sitePreview(CivBlueprint(building.copy(level = 2, phase = BuildingPhase.CONSTRUCTING,
                control = CivPosition(10, 63, 0)), h.world.uid, mockk(relaxed = true), emptyList(), emptyList(), byteArrayOf(2)))
            val captured = CivPlacementPlan(UUID.randomUUID(), active.city.id, h.world.uid, blueprint.building,
                listOf(CivPlanBlock(CivPosition(10, 64, 1), "minecraft:air", "minecraft:stone")))
            every { h.worldAdapter.captureSite(any(), any(), any(), any(), any(), any()) } returns CompletableFuture.completedFuture(captured)
            var savedPlan: CivPlacementPlan? = null
            every { h.store.savePlan(any()) } answers {
                savedPlan = firstArg(); CompletableFuture.completedFuture(firstArg())
            }
            h.start()
            val confirmed = h.service.upgradeBuilding(h.player, building.id); h.tick()
            verify(exactly = 1) { h.blueprints.prepareUpgrade(building, 2, h.world.uid) }
            h.service.preview(h.player) shouldBe null
            verify(exactly = 0) { h.renderer.show(any(), any(), any()) }
            h.service.upgradeBuilding(h.player, building.id).isDone shouldBe false
            h.service.city(h.player)!!.constructionQueue shouldBe null
            h.saves.single().first.constructionQueue shouldBe listOf(building.id)
            h.saves.single().first.construction shouldBe active.city.construction
            h.saves.single().first.building(building.id) shouldBe building
            h.world.getBlockAt(10, 63, 0).type shouldBe Material.AIR
            val design = savedPlan!!
            (design.id != building.id) shouldBe true
            design.building.level shouldBe 2
            h.commit(); confirmed.join()
            every { h.store.loadPlan(design.id) } returns CompletableFuture.completedFuture(design)
            val materials = h.service.buildingMaterials(h.player, building.id); h.tick()
            materials.join() shouldBe mapOf("STONE" to 1L)
            val repeated = h.service.upgradeBuilding(h.player, building.id); h.tick(); repeated.join() shouldBe Unit
            h.saves.size shouldBe 1
            verify(exactly = 1) { h.store.saveSchematic(design.id, any()); h.worldAdapter.captureSite(any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) {
                h.store.saveSchematic(building.id, any())
                h.worldAdapter.capture(any(), any(), any(), any(), any(), any(), constructionStorage = true)
            }
        }
    }

    test("direct upgrade preparation and capture never expose world preview geometry to boundary pulses") {
        serviceScenario { h ->
            val active = h.incrementalPlacement(ConstructionPhase.CONSTRUCTING)
            val player = h.paper.addPlayer("UpgradeMayor").apply {
                isOp = true
                teleport(Location(h.world, 0.0, 65.0, 0.0))
            }
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(active.city.copy(
                members = active.city.members + player.uniqueId)))
            val building = active.city.buildings[1]
            val blueprint = h.sitePreview(CivBlueprint(building.copy(level = 2, phase = BuildingPhase.CONSTRUCTING,
                control = CivPosition(10, 63, 0)), h.world.uid, mockk(relaxed = true), emptyList(), emptyList(), byteArrayOf(2)))
            val preparation = CompletableFuture<CivBlueprint>()
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns preparation
            every { h.worldAdapter.captureSite(any(), any(), any(), any(), any(), any()) } returns CompletableFuture<CivPlacementPlan>()
            h.start()
            val upgrade = h.service.upgradeBuilding(player, building.id)
            h.paper.performTicks(20)
            h.service.preview(player) shouldBe null
            verify(atLeast = 1) { h.boundary.pulse(player, any(), false, null, null, any(), any()) }
            preparation.complete(blueprint); h.paper.performTicks(20)
            upgrade.isDone shouldBe false
            h.service.preview(player) shouldBe null
            verify(atLeast = 2) { h.boundary.pulse(player, any(), false, null, null, any(), any()) }
            verify(exactly = 0) { h.boundary.pulse(player, any(), true, any(), any(), any(), any()); h.renderer.show(any(), any(), any()) }
        }
    }

    for (legacy in listOf(false, true)) test("a reloaded upgrade retries after a capture failure and a blocked second site legacy=$legacy") {
        serviceScenario { h ->
            val queued = h.queuedUpgrade(legacy)
            val viewer = h.paper.addPlayer("UpgradeQueueViewer")
            viewer.teleport(Location(h.world, 0.0, 65.0, 0.0))
            h.start(); h.paper.performTicks(20)
            if (legacy) {
                h.saves.single().first.pendingControls!!.single().buildingId shouldBe queued.building.id
                verify(exactly = 0) { h.worldAdapter.capture(any(), any(), any(), any(), any(), any(),
                    constructionStorage = true, acceptedStorage = emptyList()) }
                h.commit()
                h.world.getBlockAt(10, 63, 0).type shouldBe Material.BARREL
                h.saves.last().first.pendingControls shouldBe emptyList()
                h.commit()
            }
            queued.capture.completeExceptionally(CivsRejection("lands-denied")); h.tick()
            h.service.city(h.player)!!.constructionQueue shouldBe queued.city.constructionQueue
            h.service.buildingStatus(queued.building.id) shouldBe "building-status.lands-denied"
            verify(exactly = 0) { h.store.deletePlan(queued.design.id) }
            h.time += 6_000; h.paper.performTicks(20)
            val secondSite = queued.city.constructionQueue!!.last()
            h.service.buildingStatus(secondSite) shouldBe "building-status.control-missing"
            h.service.city(h.player)!!.constructionQueue shouldBe queued.city.constructionQueue
            h.service.city(h.player)!!.construction shouldBe null
            every { h.worldAdapter.capture(any(), any(), any(), any(), any(), any(),
                constructionStorage = true, acceptedStorage = emptyList()) } returns CompletableFuture.completedFuture(queued.fresh)
            h.time += 5_000; h.paper.performTicks(20)
            h.saves.last().first.construction!!.let {
                it.id shouldBe queued.fresh.id
                it.buildingId shouldBe queued.building.id
                it.targetLevel shouldBe 2
            }
            h.saves.last().first.constructionQueue shouldBe queued.city.constructionQueue!!.drop(1)
            h.service.city(h.player)!!.construction shouldBe null
            h.service.city(h.player)!!.building(queued.building.id)!!.level shouldBe 1
            verify(exactly = 0) { h.store.deletePlan(queued.design.id) }
            h.commit()
            h.service.city(h.player)!!.constructionQueue shouldBe queued.city.constructionQueue!!.drop(1)
            verify(exactly = 1) { h.store.deletePlan(queued.design.id); h.store.deleteSchematic(queued.design.id) }
            verify(exactly = 0) { h.store.deletePlan(queued.building.id); h.store.deleteSchematic(queued.building.id) }
        }
    }

    for (known in listOf(false, true)) test("upgrade admission cleans only a known rejected design and retains an unknown commit known=$known") {
        serviceScenario { h ->
            val active = h.incrementalPlacement(ConstructionPhase.CONSTRUCTING)
            val building = active.city.buildings[1]
            val blueprint = h.sitePreview(CivBlueprint(building.copy(level = 2, phase = BuildingPhase.CONSTRUCTING,
                control = CivPosition(10, 63, 0)), h.world.uid, mockk(relaxed = true), emptyList(), emptyList(), byteArrayOf(2)))
            every { h.worldAdapter.captureSite(any(), any(), any(), any(), any(), any()) } returns CompletableFuture.completedFuture(
                CivPlacementPlan(UUID.randomUUID(), active.city.id, h.world.uid, blueprint.building,
                    listOf(CivPlanBlock(CivPosition(10, 64, 1), "minecraft:air", "minecraft:stone"))))
            var savedId: UUID? = null
            every { h.store.savePlan(any()) } answers {
                savedId = firstArg<CivPlacementPlan>().id; CompletableFuture.completedFuture(firstArg())
            }
            val cleanup = CompletableFuture<Boolean>()
            every { h.store.deletePlan(any()) } returns cleanup
            every { h.store.deleteSchematic(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            val confirmed = h.service.upgradeBuilding(h.player, building.id); h.tick()
            h.service.preview(h.player) shouldBe null
            verify(exactly = 0) { h.renderer.show(any(), any(), any()) }
            h.saves.single().second.completeExceptionally(if (known) CivsRejection("stale-city") else IllegalStateException("acknowledgement lost"))
            h.tick()
            if (known) {
                confirmed.isDone shouldBe false
                val duplicate = h.service.upgradeBuilding(h.player, building.id)
                duplicate.isDone shouldBe false
                h.saves.size shouldBe 1
                cleanup.complete(true); h.tick()
                (shouldThrow<CompletionException> { duplicate.join() }.cause as CivsRejection).reason shouldBe "stale-city"
            }
            (shouldThrow<CompletionException> { confirmed.join() }.cause as CivsRejection).reason shouldBe
                if (known) "stale-city" else "storage-unavailable"
            h.service.city(h.player)!!.constructionQueue shouldBe null
            verify(exactly = if (known) 1 else 0) { h.store.deletePlan(savedId!!); h.store.deleteSchematic(savedId!!) }
            verify(exactly = 0) { h.store.deletePlan(building.id); h.store.deleteSchematic(building.id) }
        }
    }

    test("deleting a queued upgrade retires only its separate design after the building deletion commits") {
        serviceScenario { h ->
            val queued = h.queuedUpgrade()
            h.start()
            val deleted = h.service.deleteBuilding(h.player, queued.city.id, queued.building.id); h.tick()
            h.commit()
            verify(exactly = 0) { h.store.deletePlan(queued.design.id) }
            h.commit(); deleted.join()
            h.service.city(h.player)!!.constructionQueue shouldBe queued.city.constructionQueue!!.drop(1)
            verify(exactly = 1) { h.store.deletePlan(queued.design.id); h.store.deleteSchematic(queued.design.id) }
            verify(exactly = 0) { h.store.deletePlan(queued.building.id); h.store.deleteSchematic(queued.building.id) }
        }
    }

    test("a site preview and confirmation survive a delayed builder save without losing either revision") {
        serviceScenario { h ->
            val placement = h.placement()
            val source = h.previewBlueprint(CivPosition(5, 63, 5))
            val blueprint = h.sitePreview(source.copy(building = source.building.copy(
                id = UUID.randomUUID(), type = BuildingType.WAREHOUSE)))
            h.world.getBlockAt(5, 63, 5).type = Material.GRASS_BLOCK
            h.start(); placement.validation.complete(Unit); h.tick()
            h.saves.single().first.construction!!.phase shouldBe ConstructionPhase.PLACING
            h.service.beginBuildingSelection(h.player, BuildingType.WAREHOUSE).join()
            val selected = h.service.selectGround(h.player, h.service.selection(h.player)!!.id,
                CivsGroundTarget(h.world.uid, CivPosition(5, 63, 5)))
            h.tick(); selected.join()
            h.service.preview(h.player)!!.type shouldBe BuildingType.WAREHOUSE
            val confirmed = h.service.confirmPreview(h.player)
            val duplicate = h.service.confirmPreview(h.player)
            h.tick()
            confirmed.isDone shouldBe false
            duplicate.isDone shouldBe false
            h.saves.size shouldBe 1
            h.world.getBlockAt(5, 64, 0).type shouldBe Material.AIR
            verify(exactly = 0) { h.worldAdapter.captureSite(any(), any(), any(), any(), any(), any()) }
            h.commit()
            h.saves.size shouldBe 2
            h.saves.last().first.construction!!.phase shouldBe ConstructionPhase.PLACING
            h.saves.last().first.building(blueprint.building.id)!!.phase shouldBe BuildingPhase.SITE
            h.saves.last().first.constructionQueue shouldBe listOf(blueprint.building.id)
            h.service.city(h.player)!!.building(blueprint.building.id) shouldBe null
            h.world.getBlockAt(5, 64, 0).type shouldBe Material.AIR
            h.commit()
            h.world.getBlockAt(5, 64, 0).type shouldBe Material.BARREL
            h.saves.size shouldBe 3 // Retire the already-durable control receipt.
            placement.pasted.complete(ArcSchematicMutationResult(2, 0, false)); h.tick()
            h.saves.size shouldBe 3 // Builder completion waits for receipt retirement.
            h.commit(); confirmed.join(); duplicate.join()
            h.saves.size shouldBe 4
            h.saves.last().first.construction shouldBe null
            h.saves.last().first.building(blueprint.building.id)!!.phase shouldBe BuildingPhase.SITE
            h.commit()
            val saved = h.service.city(h.player)!!
            saved.building(blueprint.building.id)!!.phase shouldBe BuildingPhase.SITE
            saved.construction shouldBe null
            saved.constructionQueue shouldBe listOf(blueprint.building.id)
            saved.pendingControls shouldBe emptyList()
            h.saves.map { it.first.revision } shouldBe (1L..4L).map { placement.city.revision + it }
            verify(exactly = 1) { h.worldAdapter.captureSite(any(), any(), any(), any(), any(), any()) }
        }
    }

    test("late builder validation waits for a site reservation and keeps its newly committed geometry") {
        serviceScenario { h ->
            val placement = h.placement()
            val source = h.previewBlueprint(CivPosition(5, 63, 5))
            val blueprint = h.sitePreview(source.copy(building = source.building.copy(
                id = UUID.randomUUID(), type = BuildingType.WAREHOUSE)))
            h.world.getBlockAt(5, 63, 5).type = Material.GRASS_BLOCK
            h.start()
            h.service.beginBuildingSelection(h.player, BuildingType.WAREHOUSE).join()
            val selected = h.service.selectGround(h.player, h.service.selection(h.player)!!.id,
                CivsGroundTarget(h.world.uid, CivPosition(5, 63, 5)))
            h.tick(); selected.join()
            val confirmed = h.service.confirmPreview(h.player); h.tick()
            h.saves.size shouldBe 1
            placement.validation.complete(Unit); h.tick()
            h.saves.size shouldBe 1
            verify(exactly = 0) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            h.commit()
            h.saves.size shouldBe 2
            h.commit(); confirmed.join()
            h.saves.size shouldBe 3
            h.saves.last().first.construction!!.phase shouldBe ConstructionPhase.PLACING
            h.saves.last().first.building(blueprint.building.id)!!.phase shouldBe BuildingPhase.SITE
            h.saves.last().first.pendingControls shouldBe emptyList()
            verify(exactly = 0) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            h.commit()
            h.service.city(h.player)!!.building(blueprint.building.id)!!.phase shouldBe BuildingPhase.SITE
            verify(exactly = 1) { h.blueprints.paste(any(), any(), any(), any(), any()) }
        }
    }

    test("a requested site job waits for the builder save and queues against its newly published state") {
        serviceScenario { h ->
            val placement = h.placement()
            val site = h.city.buildings.last().copy(id = UUID.randomUUID(), type = BuildingType.WAREHOUSE,
                phase = BuildingPhase.SITE, control = CivPosition(10, 63, 0))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(
                placement.city.copy(buildings = placement.city.buildings.map {
                    if (it.id == h.city.buildings.last().id) site else it
                })))
            h.start(); placement.validation.complete(Unit); h.tick()
            h.saves.size shouldBe 1
            val requested = h.service.startBuilding(h.player, site.id)
            val duplicate = h.service.startBuilding(h.player, site.id)
            h.tick()
            requested.isDone shouldBe false
            duplicate.isDone shouldBe false
            h.saves.size shouldBe 1
            h.commit()
            h.saves.size shouldBe 2
            h.saves.last().first.construction!!.phase shouldBe ConstructionPhase.PLACING
            h.saves.last().first.constructionQueue shouldBe listOf(site.id)
            h.service.city(h.player)!!.constructionQueue.orEmpty() shouldBe emptyList()
            h.commit(); requested.join(); duplicate.join()
            h.service.city(h.player)!!.constructionQueue shouldBe listOf(site.id)
            h.saves.size shouldBe 2
            verify(exactly = 0) { h.store.loadPlan(site.id) }
        }
    }

    test("successive site requests retain arrival order across pending city writes") {
        serviceScenario { h ->
            val active = h.incrementalPlacement(ConstructionPhase.CONSTRUCTING)
            val first = active.city.buildings[1].copy(phase = BuildingPhase.SITE)
            fun site(x: Int) = first.copy(id = UUID.randomUUID(), anchor = CivPosition(x, 64, 0),
                bounds = CivBox(CivPosition(x - 2, 63, -2), CivPosition(x + 2, 68, 2)),
                entrance = CivPosition(x, 64, -2))
            val second = site(30)
            val third = site(40)
            val city = active.city.copy(buildings = active.city.buildings.map { if (it.id == first.id) first else it } + second + third)
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            h.start()
            val assignment = h.service.assign(h.player, city.workers.first().id, city.buildings.first().id)
            val requested = h.service.startBuilding(h.player, first.id)
            val next = h.service.startBuilding(h.player, second.id)
            val duplicate = h.service.startBuilding(h.player, first.id)
            h.saves.size shouldBe 1
            h.commit(); assignment.join()
            h.saves.last().first.constructionQueue shouldBe listOf(first.id)
            val last = h.service.startBuilding(h.player, third.id)
            h.commit(); requested.join(); duplicate.join()
            h.saves.last().first.constructionQueue shouldBe listOf(first.id, second.id)
            h.commit(); next.join()
            h.saves.last().first.constructionQueue shouldBe listOf(first.id, second.id, third.id)
            h.service.city(h.player)!!.constructionQueue shouldBe listOf(first.id, second.id)
            h.commit(); last.join()
            h.service.city(h.player)!!.constructionQueue shouldBe listOf(first.id, second.id, third.id)
            h.saves.size shouldBe 4
            verify(exactly = 0) { h.store.loadPlan(first.id); h.store.loadPlan(second.id); h.store.loadPlan(third.id) }
        }
    }

    test("a rejected queue write releases later requests and allows the failed request to retry") {
        serviceScenario { h ->
            val active = h.incrementalPlacement(ConstructionPhase.CONSTRUCTING)
            val first = active.city.buildings[1].copy(phase = BuildingPhase.SITE)
            val second = first.copy(id = UUID.randomUUID(), anchor = CivPosition(30, 64, 0),
                bounds = CivBox(CivPosition(28, 63, -2), CivPosition(32, 68, 2)), entrance = CivPosition(30, 64, -2))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(active.city.copy(
                buildings = active.city.buildings.map { if (it.id == first.id) first else it } + second)))
            h.start()
            val rejected = h.service.startBuilding(h.player, first.id)
            val accepted = h.service.startBuilding(h.player, second.id)
            h.saves.single().second.completeExceptionally(CivsRejection("stale-city")); h.tick()
            (shouldThrow<CompletionException> { rejected.join() }.cause as CivsRejection).reason shouldBe "stale-city"
            h.saves.last().first.constructionQueue shouldBe listOf(second.id)
            h.commit(); accepted.join()
            val retry = h.service.startBuilding(h.player, first.id)
            h.saves.last().first.constructionQueue shouldBe listOf(second.id, first.id)
            h.commit(); retry.join()
            h.service.city(h.player)!!.constructionQueue shouldBe listOf(second.id, first.id)
        }
    }

    for (outcome in listOf("cancelled", "unknown", "shutdown"))
        test("a confirmation waiting for a save fails closed after $outcome") {
            serviceScenario { h ->
                val placement = h.placement()
                val source = h.previewBlueprint(CivPosition(5, 63, 5))
                h.sitePreview(source.copy(building = source.building.copy(id = UUID.randomUUID(), type = BuildingType.WAREHOUSE)))
                h.world.getBlockAt(5, 63, 5).type = Material.GRASS_BLOCK
                h.start(); placement.validation.complete(Unit); h.tick()
                h.service.beginBuildingSelection(h.player, BuildingType.WAREHOUSE).join()
                val selected = h.service.selectGround(h.player, h.service.selection(h.player)!!.id,
                    CivsGroundTarget(h.world.uid, CivPosition(5, 63, 5)))
                h.tick(); selected.join()
                val confirmed = h.service.confirmPreview(h.player)
                confirmed.isDone shouldBe false
                when (outcome) {
                    "cancelled" -> { h.service.cancelPreview(h.player); h.commit() }
                    "unknown" -> {
                        h.saves.single().second.completeExceptionally(IllegalStateException("acknowledgement lost"))
                        h.tick()
                    }
                    "shutdown" -> { h.service.close(); h.commit() }
                }
                confirmed.isCompletedExceptionally shouldBe true
                h.saves.size shouldBe 1
                h.world.getBlockAt(5, 64, 0).type shouldBe Material.AIR
                verify(exactly = 0) { h.worldAdapter.captureSite(any(), any(), any(), any(), any(), any()) }
                if (outcome == "unknown") {
                    (shouldThrow<CompletionException> { confirmed.join() }.cause as CivsRejection).reason shouldBe "storage-unavailable"
                    h.service.confirmPreview(h.player).isCompletedExceptionally shouldBe true
                }
            }
        }
})

private fun ServiceScenario.activateCapturedOrder(city: Settlement, previous: CivBuilding, design: CivPlacementPlan,
    fresh: CivPlacementPlan, bytes: ByteArray): CompletableFuture<Unit> {
    every { store.loadPlan(design.id) } returns CompletableFuture.completedFuture(design)
    every { store.loadSchematic(design.id) } returns CompletableFuture.completedFuture(bytes)
    every { blueprints.recover(design) } returns CompletableFuture.completedFuture(mockk<ArcSchematicSnapshot> {
        every { blocks() } returns listOf(ArcSchematicBlock(ArcSchematicPoint(0, 0, 0), "minecraft:stone", false, false))
    })
    every { worldAdapter.captureManualFoundation(city.id, city.owner, city.worldId, fresh.building, any(), any(), previous) } returns
        CompletableFuture.completedFuture(fresh.terrain)
    every { worldAdapter.capture(city.id, city.owner, city.worldId, fresh.building, any(), previous, fresh.terrain,
        constructionStorage = true, acceptedStorage = emptyList()) } returns CompletableFuture.completedFuture(fresh)
    return buildingRequests.activateBuilding(city, previous)
}
