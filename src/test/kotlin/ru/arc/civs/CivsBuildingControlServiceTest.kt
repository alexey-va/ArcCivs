package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.Item
import org.bukkit.inventory.ItemStack
import ru.arc.paper.api.ArcBlockProtectionService
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicPoint

class CivsBuildingControlServiceTest : FunSpec({
    test("public building inspection is nearby and autonomous without widening foreign personal access") {
        serviceScenario { h ->
            val hall = h.city.buildings.first().copy(control = CivPosition(0, 63, 0))
            val personal = h.city.copy(owner = UUID.randomUUID(), stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL),
                buildings = listOf(hall), workers = emptyList())
            val auto = personal.copy(id = UUID.randomUUID(), owner = UUID.randomUUID(), inventory = null,
                stock = CivsRules.STARTING_STOCK, autonomy = CivAutonomy())
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(personal, auto))
            val visitor = h.paper.addPlayer("Inspector").apply {
                addAttachment(h.plugin, "arccivs.use", true)
                teleport(Location(h.world, .5, 64.0, 2.5))
            }
            h.start()
            h.service.inspectBuilding(visitor, auto.id, hall.id) shouldBe auto
            shouldThrow<CivsRejection> { h.service.inspectBuilding(visitor, personal.id, hall.id) }.reason shouldBe "not-authorized"
            visitor.addAttachment(h.plugin, "arccivs.admin", true)
            shouldThrow<CivsRejection> { h.service.inspectBuilding(visitor, personal.id, hall.id) }.reason shouldBe "not-authorized"
            visitor.teleport(Location(h.world, 30.0, 64.0, 0.0))
            shouldThrow<CivsRejection> { h.service.inspectBuilding(visitor, auto.id, hall.id) }.reason shouldBe "too-far"
        }
    }

    test("warehouse maintenance completes exceptionally when a delayed schematic outlives its building") {
        serviceScenario { h ->
            val warehouse = h.city.buildings.last().copy(type = BuildingType.WAREHOUSE,
                schematicId = CivsBlueprints.schematicId("nordic", BuildingType.WAREHOUSE, 1), control = CivPosition(10, 63, 0))
            val city = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL),
                buildings = listOf(h.city.buildings.first(), warehouse))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            val schematic = CompletableFuture<List<CivPlanBlock>>()
            every { h.blueprints.buildingStorageRepair(warehouse) } returns schematic
            h.start()
            val repair = h.service.repairBuildingStorage(city.id, warehouse.id)
            repair.isDone shouldBe false
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city.copy(
                buildings = listOf(city.buildings.first(), warehouse.copy(phase = BuildingPhase.SITE)))))
            val reload = h.service.reload(h.player)
            h.tick(); reload.join()
            schematic.complete(listOf(CivPlanBlock(CivPosition(10, 64, 0), "minecraft:bookshelf", "minecraft:air")))
            h.tick()
            repair.isCompletedExceptionally shouldBe true
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { repair.join() })?.reason shouldBe "stale-building"
            verify(exactly = 0) { h.worldAdapter.repairStorageRacks(any(), any(), any()) }
        }
    }

    test("warehouse maintenance releases its city reservation when world admission throws") {
        serviceScenario { h ->
            val warehouse = h.city.buildings.last().copy(type = BuildingType.WAREHOUSE,
                schematicId = CivsBlueprints.schematicId("nordic", BuildingType.WAREHOUSE, 1), control = CivPosition(10, 63, 0))
            val city = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL),
                buildings = listOf(h.city.buildings.first(), warehouse))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.blueprints.buildingStorageRepair(warehouse) } returns CompletableFuture.completedFuture(
                listOf(CivPlanBlock(CivPosition(10, 64, 0), "minecraft:bookshelf", "minecraft:air")))
            every { h.worldAdapter.repairStorageRacks(any(), any(), any()) } throws CivsRejection("world-closed")
            h.start()
            val repair = h.service.repairBuildingStorage(city.id, warehouse.id)
            h.tick()
            repair.isCompletedExceptionally shouldBe true
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { repair.join() })?.reason shouldBe "world-closed"
            h.service.setSettlementSetting(h.player, CivSettlementSetting.IMMORTAL_RESIDENTS, true).let { changed ->
                h.saves.size shouldBe 1
                h.commit(); changed.join()
            }
            h.service.city(h.player)!!.settings?.immortalResidents shouldBe true
        }
    }

    test("autonomous pending control installs for an ordinary visitor while its creator is offline") {
        serviceScenario { h ->
            val viewer = h.paper.addPlayer("VillageVisitor")
            viewer.teleport(Location(h.world, 0.0, 65.0, 0.0))
            val point = CivPosition(1, 65, 1)
            val hall = h.city.buildings.first().copy(control = point)
            val receipt = CivControlPlacement(hall.id, point, "minecraft:air")
            val village = h.city.copy(autonomy = CivAutonomy(), workers = emptyList(),
                buildings = listOf(hall) + h.city.buildings.drop(1), pendingControls = listOf(receipt))
            village.canManage(viewer.uniqueId) shouldBe false
            h.paper.server.getPlayer(village.owner) shouldBe null
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(village))
            h.start(); h.paper.performTicks(20)
            h.world.getBlockAt(point.x, point.y, point.z).type shouldBe Material.BARREL
            h.saves.single().first.pendingControls shouldBe emptyList()
            h.commit()
            h.service.settlements().single().pendingControls shouldBe emptyList()
        }
    }

    for (alreadyPlaced in listOf(false, true)) test("pending control restart reconciles alreadyPlaced=$alreadyPlaced without clearing inventory or repeating a completed grant") {
        serviceScenario { h ->
            val viewer = h.paper.addPlayer("ControlOwner")
            viewer.teleport(Location(h.world, 0.0, 65.0, 0.0))
            val point = CivPosition(1, 65, 1)
            val hall = h.city.buildings.first().copy(control = point)
            val receipt = CivControlPlacement(hall.id, point, "minecraft:air")
            val city = h.city.copy(stock = Stock(), workers = emptyList(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                members = setOf(viewer.uniqueId), memberNames = mapOf(viewer.uniqueId.toString() to viewer.name), buildings = listOf(hall) + h.city.buildings.drop(1), pendingControls = listOf(receipt))
            val block = h.world.getBlockAt(point.x, point.y, point.z)
            if (alreadyPlaced) {
                block.type = Material.BARREL
                (block.state as org.bukkit.block.Barrel).inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.DIAMOND, 7))
            }
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            h.start(); h.paper.performTicks(20)
            block.type shouldBe Material.BARREL
            if (alreadyPlaced) (block.state as org.bukkit.block.Barrel).inventory.getItem(0)!!.amount shouldBe 7
            h.saves.single().first.pendingControls shouldBe emptyList()
            h.commit()
            block.type = Material.AIR // Simulate an external world edit, outside the normal protected break flow.
            h.paper.performTicks(60)
            block.type shouldBe Material.AIR
            h.saves.size shouldBe 1
        }
    }

    test("direct legacy upgrade reserves the authored control before capture without requiring a player barrel") {
        serviceScenario { h ->
            val hall = h.city.buildings.first().let { it.copy(control = it.anchor) }
            val target = CivPosition(1, 65, 1)
            val city = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                buildings = listOf(hall) + h.city.buildings.drop(1))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            val cells = buildList {
                for (y in hall.bounds.min.y..hall.bounds.max.y) for (x in hall.bounds.min.x..hall.bounds.max.x)
                    for (z in hall.bounds.min.z..hall.bounds.max.z) {
                        val marker = CivPosition(x, y, z) == target
                        add(ArcSchematicBlock(ArcSchematicPoint(x, y, z),
                            if (marker) "minecraft:structure_void" else "minecraft:air", !marker, false))
                    }
            }
            val upgraded = CivBlueprint(hall.copy(level = 3, control = target, phase = BuildingPhase.CONSTRUCTING), h.world.uid,
                mockk(relaxed = true), cells, emptyList(), byteArrayOf(1))
            every { h.blueprints.controlPosition(hall) } returns CompletableFuture.completedFuture(target)
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                arg<CivPosition>(5) shouldBe hall.anchor
                arg<CivPosition>(8) shouldBe hall.entrance
                CompletableFuture.completedFuture(if (arg<Int>(2) == 3) upgraded else upgraded.copy(building = hall.copy(control = target)))
            }
            h.start()
            val confirmation = h.service.upgradeBuilding(h.player, hall.id); h.tick()
            verify(exactly = 1) { h.blueprints.prepareUpgrade(hall, 3, h.world.uid) }
            h.service.preview(h.player) shouldBe null
            verify(exactly = 0) { h.renderer.show(any(), any(), any()) }
            confirmation.isDone shouldBe false
            h.saves.single().first.pendingControls!!.single().position shouldBe target
            h.world.getBlockAt(target.x, target.y, target.z).type shouldBe Material.AIR
            verify(exactly = 0) {
                h.worldAdapter.capture(any(), any(), any(), any(), any(), any(), constructionStorage = true)
                h.store.saveInventoryTransaction(any())
            }
            h.commit()
            h.world.getBlockAt(target.x, target.y, target.z).type shouldBe Material.BARREL
            every { h.worldAdapter.capture(any(), any(), any(), any(), any(), any(), constructionStorage = true) } returns
                CompletableFuture.failedFuture(CivsRejection("outside-city"))
            h.commit()
            (shouldThrow<CompletionException> { h.await(confirmation) }.cause as CivsRejection).reason shouldBe "outside-city"
            h.service.city(h.player)!!.building(hall.id)!!.control shouldBe target
        }
    }

    test("legacy control rebinding commits first and preserves both barrel inventories and shared supplies") {
        serviceScenario { h ->
            val hall = h.city.buildings.first().let { it.copy(control = it.anchor) }
            val target = CivPosition(1, 65, 1)
            val oldBlock = h.world.getBlockAt(hall.anchor.x, hall.anchor.y, hall.anchor.z).also { it.type = Material.BARREL }
            val oldInventory = (oldBlock.state as org.bukkit.block.Barrel).inventory
            oldInventory.setItem(0, org.bukkit.inventory.ItemStack(Material.DIAMOND, 7))
            val newBlock = h.world.getBlockAt(target.x, target.y, target.z).also { it.type = Material.BARREL }
            val newInventory = (newBlock.state as org.bukkit.block.Barrel).inventory
            newInventory.setItem(0, org.bukkit.inventory.ItemStack(Material.EMERALD, 5))
            val city = h.city.copy(stock = Stock(), inventory = CivInventory.starting(Stock(12, 7, 3)),
                buildings = listOf(hall) + h.city.buildings.drop(1))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.worldAdapter.canModify(any(), any(), any()) } returns true
            every { h.blueprints.controlPosition(hall) } returns CompletableFuture.completedFuture(target)
            h.start()
            val stale = h.service.relocateControl(h.player, hall.id, target.copy(x = target.x + 1)); h.tick()
            (shouldThrow<CompletionException> { stale.join() }.cause as CivsRejection).reason shouldBe "control-location-changed"
            h.saves.size shouldBe 0
            val relocating = h.service.relocateControl(h.player, hall.id, target); h.tick()
            relocating.isDone shouldBe false
            h.service.city(h.player)!!.building(hall.id)!!.control shouldBe hall.anchor
            h.saves.single().first.inventory!!.containers shouldBe listOf(hall.anchor)
            h.commit(); relocating.isDone shouldBe false
            h.commit(); relocating.join() shouldBe Unit
            h.service.city(h.player)!!.building(hall.id)!!.control shouldBe target
            h.service.city(h.player)!!.inventory!!.materials shouldBe city.inventory!!.materials
            oldBlock.type shouldBe Material.BARREL
            oldInventory.getItem(0)!!.amount shouldBe 7
            newInventory.getItem(0)!!.amount shouldBe 5
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()); h.store.savePlan(any()) }
        }
    }

    test("legacy control relocation creates its free marker barrel only after the durable reservation") {
        serviceScenario { h ->
            val hall = h.city.buildings.first().let { it.copy(control = it.anchor) }
            val target = CivPosition(1, 65, 1)
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(h.city.copy(
                stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL), buildings = listOf(hall) + h.city.buildings.drop(1))))
            every { h.blueprints.controlPosition(hall) } returns CompletableFuture.completedFuture(target)
            h.start()
            val relocating = h.service.relocateControl(h.player, hall.id, target); h.tick()
            h.world.getBlockAt(1, 65, 1).type shouldBe Material.AIR
            h.saves.single().first.pendingControls!!.single() shouldBe CivControlPlacement(hall.id, target, "minecraft:air")
            h.commit()
            h.world.getBlockAt(1, 65, 1).type shouldBe Material.BARREL
            relocating.isDone shouldBe false
            h.commit(); relocating.join() shouldBe Unit
            h.service.city(h.player)!!.pendingControls shouldBe emptyList()
        }
    }

    test("control relocation cannot rewrite a saved legacy site plan") {
        serviceScenario { h ->
            val hall = h.city.buildings.first().copy(level = 1, phase = BuildingPhase.SITE)
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(h.city.copy(buildings = listOf(hall))))
            h.start()
            val rejection = shouldThrow<CompletionException> { h.service.relocateControl(h.player, hall.id, hall.anchor).join() }.cause as CivsRejection
            rejection.reason shouldBe "control-relocation-site"
            verify(exactly = 0) { h.store.save(any()); h.store.savePlan(any()) }
        }
    }

    test("new town sites create a free authored barrel after commit and retain it as a protected control") {
        serviceScenario(empty = true) { h ->
            h.start()
            val blueprint = h.previewBlueprint()
            every { blueprint.snapshot.fileSha256 } returns "a".repeat(64)
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns CompletableFuture.completedFuture(blueprint)
            val prepared = h.prepareFounding("Barrel town", "nordic"); h.tick(); prepared.join()
            val point = blueprint.building.control!!
            val block = h.world.getBlockAt(point.x, point.y, point.z)
            val capture = CompletableFuture<CivPlacementPlan>()
            var cityId: UUID? = null
            every { h.worldAdapter.capture(any(), any(), any(), any(), any(), any(), any(), true) } answers { cityId = firstArg(); capture }
            every { h.store.saveSchematic(any(), any()) } returns CompletableFuture.completedFuture(Unit)
            every { h.store.savePlan(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            val confirming = h.service.confirmPreview(h.player)
            capture.complete(CivPlacementPlan(UUID.randomUUID(), cityId!!, h.world.uid, blueprint.building, emptyList()))
            h.tick()
            block.type shouldBe Material.AIR
            h.service.city(h.player) shouldBe null
            h.saves.single().first.pendingControls!!.single().position shouldBe point
            h.commit()
            block.type shouldBe Material.BARREL
            h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!.isProtected(h.world.uid, point.x, point.y, point.z) shouldBe true
            h.commit(); confirming.join() shouldBe Unit
            h.service.city(h.player)!!.pendingControls shouldBe emptyList()
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("an obstructed control after site commit completes with an error and keeps its recoverable receipt") {
        serviceScenario(empty = true) { h ->
            h.start()
            val blueprint = h.previewBlueprint()
            every { blueprint.snapshot.fileSha256 } returns "a".repeat(64)
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns CompletableFuture.completedFuture(blueprint)
            every { h.worldAdapter.capture(any(), any(), any(), any(), any(), any(), any(), true) } answers {
                CompletableFuture.completedFuture(CivPlacementPlan(UUID.randomUUID(), firstArg(), h.world.uid, blueprint.building, emptyList()))
            }
            every { h.store.saveSchematic(any(), any()) } returns CompletableFuture.completedFuture(Unit)
            every { h.store.savePlan(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            val prepared = h.prepareFounding("Barrel town", "nordic"); h.tick(); prepared.join()
            val point = blueprint.building.control!!
            val block = h.world.getBlockAt(point.x, point.y, point.z)
            val confirming = h.service.confirmPreview(h.player); h.tick()
            h.saves.size shouldBe 1
            block.type = Material.DIAMOND_BLOCK
            h.commit()
            (shouldThrow<CompletionException> { confirming.join() }.cause as CivsRejection).reason shouldBe "control-obstructed"
            block.type shouldBe Material.DIAMOND_BLOCK
            val city = h.service.city(h.player)!!
            city.buildings.single().phase shouldBe BuildingPhase.SITE
            city.pendingControls!!.size shouldBe 1
            h.service.cityBusy(h.player) shouldBe false
            val start = h.service.startBuilding(h.player, blueprint.building.id)
            (shouldThrow<CompletionException> { start.join() }.cause as CivsRejection).reason shouldBe "control-position-missing"
            verify(exactly = 0) { h.store.loadPlan(any()) }
            block.type shouldBe Material.DIAMOND_BLOCK
        }
    }

    for (alreadyRemoved in listOf(false, true)) test("restart finishes pending building deletion once with control alreadyRemoved=$alreadyRemoved") {
        serviceScenario { h ->
            val control = CivPosition(10, 63, 0)
            val building = h.city.buildings.last().copy(control = control)
            val block = h.world.getBlockAt(control.x, control.y, control.z)
            if (!alreadyRemoved) {
                block.type = Material.BARREL
                (block.state as org.bukkit.block.Barrel).inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.DIAMOND, 3))
            }
            val city = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                buildings = listOf(h.city.buildings.first(), building), pendingDeletion = CivDeletion(building.id))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            h.start(); h.paper.performTicks(20)
            block.type shouldBe Material.AIR
            h.service.city(h.player)!!.pendingDeletion shouldBe CivDeletion(building.id)
            h.service.buildingStatus(building.id) shouldBe "building-status.deleting"
            h.saves.single().first.building(building.id) shouldBe null
            h.commit(); h.time += 6_000; h.paper.performTicks(40)
            h.service.city(h.player)!!.pendingDeletion shouldBe null
            h.service.city(h.player)!!.building(building.id) shouldBe null
            h.world.entities.filterIsInstance<org.bukkit.entity.Item>().sumOf { it.itemStack.amount } shouldBe if (alreadyRemoved) 0 else 3
            h.saves.size shouldBe 1
        }
    }

    test("deleting an active construction fences its late validation and cancels only the selected building") {
        serviceScenario { h ->
            val placement = h.placement()
            h.start(); h.paper.performTicks(20)
            val buildingId = placement.city.construction!!.buildingId
            val deletion = h.service.deleteBuilding(h.player, h.city.id, buildingId)
            h.tick()
            h.saves.single().first.pendingDeletion shouldBe CivDeletion(buildingId)
            h.commit()
            placement.validation.complete(Unit); h.tick()
            verify(exactly = 0) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            h.saves.size shouldBe 2
            h.commit(); deletion.join() shouldBe Unit
            h.service.city(h.player)!!.construction shouldBe null
            h.service.city(h.player)!!.building(buildingId) shouldBe null
            h.service.city(h.player)!!.buildings.size shouldBe h.city.buildings.size
        }
    }

    test("town hall controls open the settlement overview and other controls open their building") {
        serviceScenario { h ->
            val hall = h.city.buildings.first().copy(control = CivPosition(0, 63, 0))
            val home = h.city.buildings.last().copy(control = CivPosition(10, 63, 0))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(h.city.copy(buildings = listOf(hall, home))))
            val ui = mockk<CivsUi>(relaxed = true)
            h.service.ui = ui
            h.start()
            for (building in listOf(hall, home)) {
                val point = building.control!!
                val block = h.world.getBlockAt(point.x, point.y, point.z).also { it.type = Material.BARREL }
                val event = org.bukkit.event.player.PlayerInteractEvent(h.player, org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,
                    null, block, BlockFace.UP, org.bukkit.inventory.EquipmentSlot.HAND)
                h.service.controlClicked(event); h.tick()
                event.useInteractedBlock() shouldBe org.bukkit.event.Event.Result.DENY
            }
            verify(exactly = 1) { ui.open(h.player); ui.openBuilding(h.player, h.city.id, home.id) }
            verify(exactly = 0) { ui.openBuilding(h.player, h.city.id, hall.id) }
        }
    }
})
