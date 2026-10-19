package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.ExplosionResult
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.Container
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockMultiPlaceEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID

class CivsProtectionTest : FunSpec({
    test("pending deletion protects an absent control from replacement until the record is retired") {
        protectionScenario { paper ->
            val world = paper.addSimpleWorld("deletion-protection")
            val owner = UUID.randomUUID()
            val building = protectionBuilding(0, 1).copy(control = CivPosition(0, 64, 0))
            val city = protectionCity(world.uid, owner, building).copy(pendingDeletion = CivDeletion(null))
            val index = CivsProtection.prepare(listOf(city)).worlds.getValue(world.uid)
            index.contains(0, 64, 0, owner, restoringBarrel = true) shouldBe true
            val retired = CivsProtection.prepare(emptyList())
            retired.worlds[world.uid] shouldBe null
        }
    }

    test("large persisted footprints use area independent indexes and keep every boundary protected") {
        protectionScenario { paper ->
            val world = paper.addSimpleWorld("large-protection")
            val huge = protectionBuilding(0, 1).copy(bounds = CivBox(CivPosition(-1_000_000, 64, -1_000_000),
                CivPosition(1_000_000, 66, 1_000_000)))
            val city = protectionCity(world.uid, UUID.randomUUID(), huge).copy(radius = 1_000_000,
                workers = List(4) { CivWorker(UUID.randomUUID(), "Worker$it", null) })
            CivsRules.validate(city).radius shouldBe 1_000_000
            val prepared = java.util.concurrent.CompletableFuture.supplyAsync { CivsProtection.prepare(listOf(city)) }.join()
            prepared.worlds.getValue(world.uid).boxes.size shouldBe 1
            val listener = CivsProtection({ emptyList() })
            listener.publish(prepared)
            listener.isProtected(world.uid, -1_000_000, 64, 1_000_000) shouldBe true
            listener.isProtected(world.uid, 1_000_000, 66, -1_000_000) shouldBe true
            listener.isProtected(world.uid, 1_000_001, 64, 0) shouldBe false
        }
    }

    test("worker prepared footprint stays unpublished until the main thread swaps its complete index") {
        protectionScenario { paper ->
            val world = paper.addSimpleWorld("prepared-protection")
            val old = protectionBuilding(0, 1)
            val initial = protectionCity(world.uid, UUID.randomUUID(), old).copy(radius = 512,
                workers = List(4) { CivWorker(UUID.randomUUID(), "Worker$it", null) })
            CivsRules.validate(initial).radius shouldBe 512
            val listener = CivsProtection({ listOf(initial) })
            val changed = initial.copy(buildings = listOf(old.copy(bounds = CivBox(CivPosition(32, 64, 0), CivPosition(34, 66, 2)))))
            val prepared = java.util.concurrent.CompletableFuture.supplyAsync { CivsProtection.prepare(listOf(changed)) }.join()
            listener.isProtected(world.uid, 0, 64, 0) shouldBe true
            listener.isProtected(world.uid, 32, 64, 0) shouldBe false
            listener.publish(prepared) shouldBe true
            listener.isProtected(world.uid, 0, 64, 0) shouldBe false
            listener.isProtected(world.uid, 32, 64, 0) shouldBe true
        }
    }

    test("full current and planned boxes protect owners while the rest of their city stays editable") {
        protectionScenario { paper ->
            val world = paper.addSimpleWorld("settlement")
            val otherWorld = paper.addSimpleWorld("other")
            val player = paper.addPlayer("Owner")
            val old = protectionBuilding(0, 1)
            val planned = old.copy(level = 2, bounds = CivBox(CivPosition(0, 64, 0), CivPosition(3, 66, 3)))
            val city = protectionCity(world.uid, player.uniqueId, old).copy(construction = CivConstruction(UUID.randomUUID(),
                old.id, 2, 1, 0, ConstructionPhase.CONSTRUCTING, Stock(), plannedBuilding = planned))
            val listener = CivsProtection({ listOf(city) })
            paper.server.pluginManager.registerEvents(listener, paper.createSimplePlugin("ProtectionTest"))
            paper.callEvent(BlockBreakEvent(world.getBlockAt(0, 65, 0), player)).isCancelled shouldBe true
            paper.callEvent(BlockBreakEvent(world.getBlockAt(3, 66, 3), player)).isCancelled shouldBe true
            paper.callEvent(BlockBreakEvent(world.getBlockAt(4, 64, 0), player)).isCancelled shouldBe false
            paper.callEvent(BlockBreakEvent(otherWorld.getBlockAt(0, 65, 0), player)).isCancelled shouldBe false
            val states = listOf(world.getBlockAt(4, 64, 0).state, world.getBlockAt(3, 65, 0).state)
            paper.callEvent(BlockMultiPlaceEvent(states, world.getBlockAt(4, 63, 0), ItemStack(Material.STONE), player,
                true, EquipmentSlot.HAND)).isCancelled shouldBe true
        }
    }

    test("pistons fluids and explosions cannot move destroy or replace a protected boundary") {
        protectionScenario { paper ->
            val world = paper.addSimpleWorld("settlement")
            val listener = CivsProtection({ listOf(protectionCity(world.uid, UUID.randomUUID(), protectionBuilding(0, 1))) })
            paper.server.pluginManager.registerEvents(listener, paper.createSimplePlugin("ProtectionTest"))
            val outside = world.getBlockAt(-1, 64, 0)
            val inside = world.getBlockAt(0, 64, 0)
            paper.callEvent(BlockPistonExtendEvent(world.getBlockAt(-2, 64, 0), listOf(outside), BlockFace.EAST)).isCancelled shouldBe true
            paper.callEvent(BlockPistonRetractEvent(world.getBlockAt(-2, 64, 0), listOf(inside), BlockFace.WEST)).isCancelled shouldBe true
            paper.callEvent(BlockFromToEvent(outside, inside)).isCancelled shouldBe true
            paper.callEvent(BlockFromToEvent(inside, outside)).isCancelled shouldBe true
            val explosion = BlockExplodeEvent(outside, outside.state, mutableListOf<org.bukkit.block.Block>(outside, inside), 1f, ExplosionResult.DESTROY)
            paper.callEvent(explosion).blockList() shouldBe listOf(outside)
        }
    }

    test("members can remodel finished buildings with drops and containers while strangers stay blocked") {
        protectionScenario { paper ->
            val world = paper.addSimpleWorld("settlement")
            val player = paper.addPlayer("Owner")
            val member = paper.addPlayer("Member")
            val stranger = paper.addPlayer("Stranger")
            val city = protectionCity(world.uid, player.uniqueId, protectionBuilding(0, 2)).copy(members = setOf(member.uniqueId))
            val listener = CivsProtection({ listOf(city) })
            paper.server.pluginManager.registerEvents(listener, paper.createSimplePlugin("ProtectionTest"))
            val door = world.getBlockAt(0, 64, 0).apply { type = Material.OAK_DOOR }
            val doorClick = PlayerInteractEvent(stranger, Action.RIGHT_CLICK_BLOCK, ItemStack(Material.STICK),
                door, BlockFace.NORTH, EquipmentSlot.HAND)
            val priorUse = doorClick.useInteractedBlock()
            paper.callEvent(doorClick).useInteractedBlock() shouldBe priorUse
            doorClick.useItemInHand() shouldBe Event.Result.DENY
            val deniedDoorClick = PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null, door, BlockFace.NORTH, EquipmentSlot.HAND)
            deniedDoorClick.setUseInteractedBlock(Event.Result.DENY)
            paper.callEvent(deniedDoorClick).useInteractedBlock() shouldBe Event.Result.DENY
            val chest = world.getBlockAt(1, 64, 0).apply { type = Material.CHEST }
            val chestClick = paper.callEvent(PlayerInteractEvent(stranger, Action.RIGHT_CLICK_BLOCK, null, chest, BlockFace.NORTH, EquipmentSlot.HAND))
            chestClick.useInteractedBlock() shouldBe Event.Result.DENY
            val inventory = (chest.state as Container).inventory
            for (actor in listOf(player, member, stranger)) {
                val denied = actor == stranger
                paper.callEvent(BlockBreakEvent(chest, actor)).isCancelled shouldBe denied
                paper.callEvent(BlockPlaceEvent(chest, chest.state, door, ItemStack(Material.CHEST), actor, true, EquipmentSlot.HAND)).isCancelled shouldBe denied
                paper.callEvent(BlockMultiPlaceEvent(listOf(chest.state, door.state), door, ItemStack(Material.CHEST), actor,
                    true, EquipmentSlot.HAND)).isCancelled shouldBe denied
                paper.callEvent(BlockDropItemEvent(chest, chest.state, actor, mutableListOf())).isCancelled shouldBe denied
                val view = mockk<InventoryView>()
                every { view.topInventory } returns inventory
                every { view.player } returns actor
                paper.callEvent(InventoryOpenEvent(view)).isCancelled shouldBe denied
                val click = PlayerInteractEvent(actor, Action.RIGHT_CLICK_BLOCK, ItemStack(Material.STICK), chest, BlockFace.NORTH, EquipmentSlot.HAND)
                val before = click.useItemInHand()
                paper.callEvent(click).useItemInHand() shouldBe if (denied) Event.Result.DENY else before
            }
            val outside = world.getBlockAt(10, 64, 0).apply { type = Material.CHEST }
            val outsideInventory = (outside.state as Container).inventory
            paper.callEvent(InventoryMoveItemEvent(inventory, ItemStack(Material.STONE), outsideInventory, true)).isCancelled shouldBe true
            paper.callEvent(InventoryMoveItemEvent(outsideInventory, ItemStack(Material.STONE), inventory, true)).isCancelled shouldBe true
            paper.callEvent(InventoryPickupItemEvent(inventory, mockk())).isCancelled shouldBe true
        }
    }

    test("sites allow member edits but control blocks and every accepted construction phase stay locked") {
        protectionScenario { paper ->
            val world = paper.addSimpleWorld("site-protection")
            val owner = paper.addPlayer("Owner")
            val member = paper.addPlayer("Member")
            val stranger = paper.addPlayer("Stranger")
            val control = CivPosition(-1, 63, 0)
            val building = protectionBuilding(0, 2).copy(phase = BuildingPhase.SITE, control = control)
            var city = protectionCity(world.uid, owner.uniqueId, building).copy(members = setOf(member.uniqueId))
            val listener = CivsProtection({ listOf(city) })
            paper.server.pluginManager.registerEvents(listener, paper.createSimplePlugin("SiteProtectionTest"))
            val barrel = world.getBlockAt(control.x, control.y, control.z).apply { type = Material.BARREL }
            for (actor in listOf(owner, member, stranger)) {
                paper.callEvent(BlockBreakEvent(world.getBlockAt(1, 64, 1), actor)).isCancelled shouldBe (actor == stranger)
                paper.callEvent(BlockBreakEvent(world.getBlockAt(control.x, control.y, control.z), actor)).isCancelled shouldBe true
                val view = mockk<InventoryView>()
                every { view.topInventory } returns (barrel.state as Container).inventory
                every { view.player } returns actor
                paper.callEvent(InventoryOpenEvent(view)).isCancelled shouldBe (actor == stranger)
            }
            for (phase in ConstructionPhase.entries) {
                val planned = building.copy(phase = BuildingPhase.CONSTRUCTING,
                    bounds = CivBox(CivPosition(0, 64, 0), CivPosition(4, 66, 4)))
                city = city.copy(buildings = listOf(building.copy(phase = BuildingPhase.ACTIVE)),
                    construction = CivConstruction(UUID.randomUUID(), building.id, 2, 3, 0, phase, Stock(), plannedBuilding = planned))
                listener.refresh()
                for (actor in listOf(owner, member, stranger)) {
                    listener.isProtected(world.uid, 0, 64, 0, actor.uniqueId) shouldBe true
                    listener.isProtected(world.uid, 4, 64, 4, actor.uniqueId) shouldBe true
                    listener.isProtected(world.uid, control.x, control.y, control.z, actor.uniqueId) shouldBe true
                }
            }
        }
    }

    for (phase in listOf(BuildingPhase.SITE, BuildingPhase.ACTIVE)) test("owners and members may restore only a missing $phase control barrel while its breaking stays denied") {
        protectionScenario { paper ->
            val world = paper.addSimpleWorld("restore-site-control")
            val owner = paper.addPlayer("Owner")
            val member = paper.addPlayer("Member")
            val stranger = paper.addPlayer("Stranger")
            val control = CivPosition(-1, 63, 0)
            val building = protectionBuilding(0, 2).copy(phase = phase, control = control)
            var city = protectionCity(world.uid, owner.uniqueId, building).copy(members = setOf(member.uniqueId))
            val listener = CivsProtection({ listOf(city) })
            paper.server.pluginManager.registerEvents(listener, paper.createSimplePlugin("RestoreSiteControlTest"))

            for (actor in listOf(owner, member, stranger)) {
                paper.callEvent(controlPlacement(world, control, actor)).isCancelled shouldBe (actor == stranger)
                paper.callEvent(controlPlacement(world, control, actor, Material.STONE)).isCancelled shouldBe true
                paper.callEvent(controlPlacement(world, control, actor, replaced = Material.BARREL)).isCancelled shouldBe true
                val barrel = world.getBlockAt(control.x, control.y, control.z).apply { type = Material.BARREL }
                paper.callEvent(BlockBreakEvent(barrel, actor)).isCancelled shouldBe true
                listener.isProtected(world.uid, control.x, control.y, control.z, actor.uniqueId) shouldBe true
            }
            city = city.copy(members = emptySet())
            listener.refresh() shouldBe true
            paper.callEvent(controlPlacement(world, control, member)).isCancelled shouldBe true
            paper.callEvent(controlPlacement(world, control, owner)).isCancelled shouldBe false
            city = city.copy(buildings = listOf(building.copy(phase = BuildingPhase.CONSTRUCTING)))
            listener.refresh() shouldBe true
            paper.callEvent(controlPlacement(world, control, owner)).isCancelled shouldBe true
        }
    }

    test("a control barrel restore never bypasses accepted current or planned construction footprints") {
        protectionScenario { paper ->
            val world = paper.addSimpleWorld("accepted-control-protection")
            val owner = paper.addPlayer("Owner")
            val member = paper.addPlayer("Member")
            val control = CivPosition(-1, 63, 0)
            val plannedControl = CivPosition(-2, 63, 0)
            val site = protectionBuilding(0, 2).copy(phase = BuildingPhase.SITE, control = control)
            var city = protectionCity(world.uid, owner.uniqueId, site).copy(members = setOf(member.uniqueId))
            val listener = CivsProtection({ listOf(city) })
            paper.server.pluginManager.registerEvents(listener, paper.createSimplePlugin("AcceptedControlProtectionTest"))

            for (phase in ConstructionPhase.entries) {
                val planned = site.copy(phase = BuildingPhase.CONSTRUCTING, control = plannedControl)
                city = city.copy(buildings = listOf(site), construction = CivConstruction(UUID.randomUUID(), site.id,
                    1, 3, 0, phase, Stock(), plannedBuilding = planned))
                listener.refresh()
                for (actor in listOf(owner, member)) {
                    paper.callEvent(controlPlacement(world, control, actor)).isCancelled shouldBe true
                    paper.callEvent(controlPlacement(world, plannedControl, actor)).isCancelled shouldBe true
                }
            }
            city = city.copy(buildings = listOf(site.copy(phase = BuildingPhase.CONSTRUCTING)), construction = null)
            listener.refresh()
            paper.callEvent(controlPlacement(world, control, owner)).isCancelled shouldBe true

            // A different accepted building may cover this control without covering the SITE's building box.
            val accepted = protectionBuilding(-3, -1).copy(type = BuildingType.HOUSE,
                bounds = CivBox(CivPosition(-3, 62, 0), CivPosition(-1, 63, 1)))
            city = city.copy(buildings = listOf(site, accepted), construction = CivConstruction(UUID.randomUUID(), accepted.id,
                2, 3, 0, ConstructionPhase.CONSTRUCTING, Stock(), plannedBuilding = accepted.copy(level = 2, phase = BuildingPhase.CONSTRUCTING)))
            listener.refresh()
            paper.callEvent(controlPlacement(world, control, owner)).isCancelled shouldBe true
        }
    }

    test("SITE control restoration preserves prior cancellation readiness and multi-place protection") {
        protectionScenario { paper ->
            val world = paper.addSimpleWorld("guarded-control-restore")
            val owner = paper.addPlayer("Owner")
            val control = CivPosition(-1, 63, 0)
            val site = protectionBuilding(0, 2).copy(phase = BuildingPhase.SITE, control = control)
            val city = protectionCity(world.uid, owner.uniqueId, site)
            var ready = true
            val listener = CivsProtection({ listOf(city) }, { ready }, setOf(world.name))
            paper.server.pluginManager.registerEvents(listener, paper.createSimplePlugin("GuardedControlRestoreTest"))

            val cancelled = controlPlacement(world, control, owner).also { it.isCancelled = true }
            paper.callEvent(cancelled).isCancelled shouldBe true
            val placement = controlPlacement(world, control, owner)
            paper.callEvent(BlockMultiPlaceEvent(listOf(placement.blockReplacedState), placement.blockAgainst,
                ItemStack(Material.BARREL), owner, true, EquipmentSlot.HAND)).isCancelled shouldBe true
            ready = false
            paper.callEvent(controlPlacement(world, control, owner)).isCancelled shouldBe true
            ready = true
            paper.callEvent(controlPlacement(world, control, owner)).isCancelled shouldBe false
        }
    }

    test("membership-only publication immediately revokes edits without changing geometry") {
        protectionScenario { paper ->
            val world = paper.addSimpleWorld("membership-protection")
            val owner = UUID.randomUUID()
            val member = UUID.randomUUID()
            var city = protectionCity(world.uid, owner, protectionBuilding(0, 2)).copy(members = setOf(member))
            val listener = CivsProtection({ listOf(city) })
            listener.isProtected(world.uid, 1, 64, 1, owner) shouldBe false
            listener.isProtected(world.uid, 1, 64, 1, member) shouldBe false
            listener.isProtected(world.uid, 1, 64, 1) shouldBe true
            city = city.copy(members = emptySet())
            listener.refresh() shouldBe true
            listener.isProtected(world.uid, 1, 64, 1, member) shouldBe true
            listener.isProtected(world.uid, 1, 64, 1, owner) shouldBe false
        }
    }

    test("startup failure closes only configured worlds until the stored footprint is available") {
        protectionScenario { paper ->
            val protectedWorld = paper.addSimpleWorld("settlement")
            val other = paper.addSimpleWorld("resource")
            var ready = false
            val listener = CivsProtection({ emptyList() }, { ready }, setOf("settlement"))
            listener.isProtected(protectedWorld.uid, 200, 64, 200) shouldBe true
            listener.isProtected(other.uid, 200, 64, 200) shouldBe false
            ready = true
            listener.isProtected(protectedWorld.uid, 200, 64, 200) shouldBe false
        }
    }

    test("chunk index refreshes only geometry and includes negative and cross-chunk planned bounds") {
        protectionScenario { paper ->
            val world = paper.addSimpleWorld("settlement")
            val building = protectionBuilding(-17, -15)
            var city = protectionCity(world.uid, UUID.randomUUID(), building)
            var queries = 0
            val listener = CivsProtection({ queries++; listOf(city) })
            val initialQueries = queries
            repeat(100) {
                listener.isProtected(world.uid, -17, 64, 0) shouldBe true
                listener.isProtected(world.uid, -15, 64, 0) shouldBe true
                listener.isProtected(world.uid, -14, 64, 0) shouldBe false
            }
            queries shouldBe initialQueries
            city = city.copy(stock = Stock(1), revision = 1)
            listener.refresh() shouldBe false
            val planned = building.copy(level = 2, bounds = CivBox(CivPosition(-17, 64, -17), CivPosition(16, 66, 3)))
            city = city.copy(construction = CivConstruction(UUID.randomUUID(), building.id, 2, 1, 0,
                ConstructionPhase.CONSTRUCTING, Stock(), plannedBuilding = planned))
            listener.refresh() shouldBe true
            listener.isProtected(world.uid, 16, 64, -17) shouldBe true
            listener.isProtected(world.uid, 16, 67, -17) shouldBe false
            listener.isProtected(UUID.randomUUID(), 16, 64, -17) shouldBe false
        }
    }
})

private fun protectionScenario(action: (MockBukkitTestRuntime) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open().use(action)
}
private fun protectionBuilding(minX: Int, maxX: Int) = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1,
    CivPosition(0, 64, 0), 0, "test", CivBox(CivPosition(minX, 64, 0), CivPosition(maxX, 66, 3)),
    CivPosition(-1, 64, 0), BuildingPhase.ACTIVE)
private fun protectionCity(worldId: UUID, owner: UUID, building: CivBuilding) = Settlement(UUID.randomUUID(), owner,
    "Test", worldId, CivPosition(0, 64, 0), 100, "test", Stock(), listOf(building), emptyList())
private fun controlPlacement(world: World, point: CivPosition, actor: Player, material: Material = Material.BARREL,
    replaced: Material = Material.AIR): BlockPlaceEvent {
    val block = world.getBlockAt(point.x, point.y, point.z).apply { type = replaced }
    val before = block.state
    block.type = material
    return BlockPlaceEvent(block, before, block.getRelative(BlockFace.DOWN), ItemStack(material), actor, true, EquipmentSlot.HAND)
}
