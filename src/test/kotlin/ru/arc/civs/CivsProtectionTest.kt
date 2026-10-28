package ru.arc.civs

import com.destroystokyo.paper.event.block.BlockDestroyEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.ExplosionResult
import org.bukkit.Material
import org.bukkit.TreeType
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.Container
import org.bukkit.block.DoubleChest
import org.bukkit.block.data.Ageable
import org.bukkit.block.data.type.Farmland
import org.bukkit.entity.Player
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockGrowEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.block.BlockMultiPlaceEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.block.LeavesDecayEvent
import org.bukkit.event.block.MoistureChangeEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.world.StructureGrowEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID

class CivsProtectionTest : FunSpec({
    test("pillagers cannot spawn anywhere in ordinary or Auto territory for patrol raid or other reasons") {
        protectionScenario { paper ->
            val world = paper.addSimpleWorld("pillager-villages")
            val otherWorld = paper.addSimpleWorld("pillager-other-world")
            val ordinary = protectionCity(world.uid, UUID.randomUUID(), protectionBuilding(0, 2))
            val auto = ordinary.copy(id = UUID.randomUUID(), center = CivPosition(500, 64, 0), autonomy = CivAutonomy())
            var cities = listOf(ordinary, auto)
            val listener = CivsProtection({ cities })
            paper.server.pluginManager.registerEvents(listener, paper.createSimplePlugin("PillagerTerritoryProtection"))
            fun spawn(at: Location, reason: CreatureSpawnEvent.SpawnReason, type: EntityType = EntityType.PILLAGER,
                cancelled: Boolean = false): CreatureSpawnEvent {
                val entity = mockk<LivingEntity> { every { location } returns at; every { this@mockk.type } returns type }
                return paper.callEvent(CreatureSpawnEvent(entity, reason).also { it.isCancelled = cancelled })
            }
            for (reason in CreatureSpawnEvent.SpawnReason.entries) {
                spawn(Location(world, 75.5, 70.0, 75.5), reason).isCancelled shouldBe true
                spawn(Location(world, 600.0, 70.0, 100.0), reason).isCancelled shouldBe true
                spawn(Location(world, 601.0, 70.0, 100.0), reason).isCancelled shouldBe false
                spawn(Location(otherWorld, 75.5, 70.0, 75.5), reason).isCancelled shouldBe false
            }
            spawn(Location(world, .5, 64.0, .5), CreatureSpawnEvent.SpawnReason.NATURAL, EntityType.VILLAGER).isCancelled shouldBe false
            spawn(Location(world, 250.0, 64.0, 0.0), CreatureSpawnEvent.SpawnReason.PATROL, cancelled = true).isCancelled shouldBe true
            cities = emptyList()
            spawn(Location(world, .5, 64.0, .5), CreatureSpawnEvent.SpawnReason.PATROL).isCancelled shouldBe false
        }
    }

    test("administrator edits an unrelated Auto building and worksite while exact locks remain protected") {
        protectionScenario { paper ->
            val world = paper.addSimpleWorld("auto-admin-edit")
            val plugin = paper.createSimplePlugin("AdminEditProtection")
            val owner = paper.addPlayer("AutoOwner")
            val admin = paper.addPlayer("AutoAdmin").apply { addAttachment(plugin, "arccivs.admin", true) }
            val visitor = paper.addPlayer("AutoVisitor")
            val control = CivPosition(0, 64, 0)
            val hall = protectionBuilding(0, 2).copy(control = control)
            val farm = hall.copy(id = UUID.randomUUID(), type = BuildingType.FARM,
                schematicId = CivsBlueprints.schematicId("nordic", BuildingType.FARM, 1), control = null)
            var city = protectionCity(world.uid, owner.uniqueId, hall).copy(autonomy = CivAutonomy(), buildings = listOf(hall, farm))
            var available = true
            val locked = CivPosition(1, 64, 1)
            val listener = CivsProtection({ listOf(city) }, { available }, setOf(world.name),
                worldLocked = { worldId, at -> worldId == world.uid && at == locked })
            paper.server.pluginManager.registerEvents(listener, plugin)
            CivsAutonomousWorksites.supports(city, farm) shouldBe true
            val worksite = CivsAutonomousWorksites.protectionBounds(farm).first().min
            for (point in listOf(CivPosition(2, 65, 2), worksite)) {
                val block = world.getBlockAt(point.x, point.y, point.z).apply { type = Material.STONE }
                paper.callEvent(BlockBreakEvent(block, visitor)).isCancelled shouldBe true
                paper.callEvent(BlockBreakEvent(block, admin)).isCancelled shouldBe false
                paper.callEvent(BlockDropItemEvent(block, block.state, admin, mutableListOf())).isCancelled shouldBe false
                paper.callEvent(BlockPlaceEvent(block, block.state, block.getRelative(BlockFace.DOWN),
                    ItemStack(Material.STONE), admin, true, EquipmentSlot.HAND)).isCancelled shouldBe false
            }
            for (point in listOf(control, locked)) {
                paper.callEvent(BlockBreakEvent(world.getBlockAt(point.x, point.y, point.z), admin)).isCancelled shouldBe true
            }
            val target = world.getBlockAt(2, 65, 2)
            val cancelled = BlockBreakEvent(target, admin).also { it.isCancelled = true }
            paper.callEvent(cancelled).isCancelled shouldBe true
            available = false
            paper.callEvent(BlockBreakEvent(target, admin)).isCancelled shouldBe true
            available = true
            city = city.copy(pendingDeletion = CivDeletion(null)); listener.refresh()
            paper.callEvent(BlockBreakEvent(target, admin)).isCancelled shouldBe true
        }
    }

    test("automatic decoration keeps native blocks and container withdrawals protected from ordinary visitors") {
        protectionScenario { paper ->
            val world = paper.addSimpleWorld("auto-decoration-protection")
            val owner = paper.addPlayer("DecorationOwner")
            val visitor = paper.addPlayer("DecorationVisitor")
            val building = protectionBuilding(0, 2).copy(type = BuildingType.DECORATION, control = null)
            val city = protectionCity(world.uid, owner.uniqueId, building).copy(autonomy = CivAutonomy())
            val protection = CivsProtection({ listOf(city) })
            paper.server.pluginManager.registerEvents(protection, paper.createSimplePlugin("DecorationProtection"))
            val block = world.getBlockAt(1, 64, 1).apply { type = Material.BARREL }
            paper.callEvent(BlockBreakEvent(block, visitor)).isCancelled shouldBe true
            // Existing editor privileges are intentionally unchanged.
            paper.callEvent(BlockBreakEvent(block, owner)).isCancelled shouldBe false
            val holder = block.state as Container
            val inventory = mockk<Inventory> {
                every { getHolder(false) } returns holder
                every { location } returns block.location
            }
            val view = mockk<InventoryView>(relaxed = true) {
                every { topInventory } returns inventory
                every { player } returns visitor
            }
            val click = InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL)
            protection.click(click)
            click.isCancelled shouldBe true
        }
    }
    test("natural tree growth rejects its actual changed blocks at a current resident body") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("resident-tree-growth")
            val changed = CivPosition(2, 65, 2)
            var occupied = changed
            val protection = CivsProtection({ emptyList() }, citizenCollision = { worldId, point ->
                worldId == world.uid && point == occupied
            })
            fun growth(): StructureGrowEvent {
                val proposed = world.getBlockAt(changed.x, changed.y, changed.z).state.apply { type = Material.OAK_LOG }
                return StructureGrowEvent(Location(world, .5, 64.0, .5), TreeType.TREE, false, null, mutableListOf(proposed))
            }
            val blocked = growth()
            protection.tree(blocked)
            blocked.isCancelled shouldBe true
            blocked.blocks.size shouldBe 1
            world.getBlockAt(changed.x, changed.y, changed.z).type shouldBe Material.AIR
            // The callback observes the body now, after the earlier care action completed.
            occupied = CivPosition(0, 64, 0)
            val clear = growth()
            protection.tree(clear)
            clear.isCancelled shouldBe false
        }
    }

    test("autonomous control contents are public read-only while administrator writes retain every existing lock") {
        protectionScenario { paper ->
            val world = paper.addSimpleWorld("auto-control")
            val plugin = paper.createSimplePlugin("AutoControlProtection")
            val owner = paper.addPlayer("AutoOwner")
            val visitor = paper.addPlayer("AutoVisitor")
            val admin = paper.addPlayer("AutoAdmin").apply { addAttachment(plugin, "arccivs.admin", true) }
            for (actor in listOf(owner, visitor, admin)) {
                actor.addAttachment(plugin, "arccivs.use", true)
                actor.teleport(Location(world, .5, 64.0, 2.5))
            }
            val point = CivPosition(0, 64, 0)
            val building = protectionBuilding(0, 2).copy(control = point)
            var city = protectionCity(world.uid, owner.uniqueId, building).copy(autonomy = CivAutonomy())
            var available = true
            val protection = CivsProtection({ listOf(city) }, { available })
            val block = world.getBlockAt(0, 64, 0).apply { type = Material.BARREL }
            val holder = block.state as Container
            val inventory = mockk<Inventory> {
                every { getHolder(false) } returns holder
                every { location } returns block.location
            }
            val other = mockk<Inventory> {
                every { getHolder(false) } returns null
                every { location } returns Location(world, 20.0, 64.0, 0.0)
            }
            for (actor in listOf(owner, visitor, admin)) {
                val view = mockk<InventoryView>(relaxed = true) {
                    every { topInventory } returns inventory
                    every { player } returns actor
                }
                val opened = InventoryOpenEvent(view)
                protection.open(opened)
                opened.isCancelled shouldBe false
                for (click in listOf(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT,
                    ClickType.NUMBER_KEY, ClickType.SWAP_OFFHAND, ClickType.DOUBLE_CLICK, ClickType.DROP, ClickType.CONTROL_DROP)) {
                    val event = InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, click, InventoryAction.UNKNOWN)
                    protection.click(event)
                    event.isCancelled shouldBe (actor != admin)
                }
                val creative = InventoryCreativeEvent(view, InventoryType.SlotType.CONTAINER, 0, ItemStack(Material.DIAMOND))
                protection.click(creative)
                creative.isCancelled shouldBe (actor != admin)
                val drag = InventoryDragEvent(view, null, ItemStack(Material.STONE, 2), false, mapOf(0 to ItemStack(Material.STONE)))
                protection.drag(drag)
                drag.isCancelled shouldBe (actor != admin)
                val previouslyCancelled = InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0,
                    ClickType.LEFT, InventoryAction.PICKUP_ALL).also { it.isCancelled = true }
                protection.click(previouslyCancelled)
                previouslyCancelled.isCancelled shouldBe true
                protection.isProtected(world.uid, 0, 64, 0, actor.uniqueId) shouldBe true
            }
            for ((source, destination) in listOf(inventory to other, other to inventory)) {
                val move = InventoryMoveItemEvent(source, ItemStack(Material.STONE), destination, true)
                protection.moveItem(move)
                move.isCancelled shouldBe true
            }
            val view = mockk<InventoryView>(relaxed = true) { every { topInventory } returns inventory; every { player } returns admin }
            available = false
            val unavailable = InventoryOpenEvent(view)
            protection.open(unavailable)
            unavailable.isCancelled shouldBe true
            available = true
            city = city.copy(pendingDeletion = CivDeletion(null))
            protection.refresh() shouldBe true
            val deleting = InventoryOpenEvent(view)
            protection.open(deleting)
            deleting.isCancelled shouldBe true
            val deletingClick = InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL)
            protection.click(deletingClick)
            deletingClick.isCancelled shouldBe true
        }
    }

    test("owner attributed terrain wear may enter buildings but respects strangers and receipt locks") {
        protectionScenario { paper ->
            val world = paper.addSimpleWorld("interior-trails")
            val owner = paper.addPlayer("Owner")
            val stranger = paper.addPlayer("Stranger")
            val city = protectionCity(world.uid, owner.uniqueId, protectionBuilding(0, 2))
            val soil = world.getBlockAt(1, 64, 1).also { it.type = Material.DIRT }
            var locked = false
            val listener = CivsProtection({ listOf(city) }, worldLocked = { _, _ -> locked })
            fun wear(actor: Player): Boolean {
                val event = org.bukkit.event.entity.EntityChangeBlockEvent(actor, soil, Material.DIRT_PATH.createBlockData())
                listener.entityChange(event)
                return event.isCancelled
            }
            wear(owner) shouldBe false
            wear(stranger) shouldBe true
            locked = true
            wear(owner) shouldBe true
        }
    }

    test("hopper and member inventory checks retain a protected right half without snapshots or duplicate endpoints") {
        protectionScenario { paper ->
            val world = paper.addSimpleWorld("right-half-protection")
            val owner = paper.addPlayer("Owner")
            val stranger = paper.addPlayer("Stranger")
            val city = protectionCity(world.uid, owner.uniqueId, protectionBuilding(1, 1))
            val listener = CivsProtection({ listOf(city) })
            val left = world.getBlockAt(0, 64, 0).also { it.type = Material.CHEST }.state as Container
            val right = world.getBlockAt(1, 64, 0).also { it.type = Material.CHEST }.state as Container
            val holder = mockk<DoubleChest> {
                every { getLeftSide(false) } returns left
                every { getRightSide(false) } returns right
            }
            val doubleChest = mockk<Inventory> {
                every { getHolder(false) } returns holder
                every { location } returns Location(world, 0.5, 64.0, 0.0)
            }
            val outside = mockk<Inventory> {
                every { getHolder(false) } returns null
                every { location } returns Location(world, 10.0, 64.0, 0.0)
            }
            val otherOutside = mockk<Inventory> {
                every { getHolder(false) } returns null
                every { location } returns Location(world, 11.0, 64.0, 0.0)
            }
            val freeMove = InventoryMoveItemEvent(outside, ItemStack(Material.STONE), otherOutside, true)
            listener.moveItem(freeMove)
            freeMove.isCancelled shouldBe false
            verify(exactly = 1) { outside.getHolder(false) }
            verify(exactly = 1) { otherOutside.getHolder(false) }
            listOf(doubleChest to outside, outside to doubleChest).forEach { (source, destination) ->
                val event = InventoryMoveItemEvent(source, ItemStack(Material.STONE), destination, true)
                listener.moveItem(event)
                event.isCancelled shouldBe true
            }
            for (actor in listOf(owner, stranger)) {
                val view = mockk<InventoryView> {
                    every { topInventory } returns doubleChest
                    every { player } returns actor
                }
                val event = InventoryOpenEvent(view)
                listener.open(event)
                event.isCancelled shouldBe (actor == stranger)
            }
        }
    }

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

    test("owners and members can edit current and planned construction in every phase while strangers stay blocked") {
        protectionScenario { paper ->
            val world = paper.addSimpleWorld("settlement")
            val otherWorld = paper.addSimpleWorld("other")
            val player = paper.addPlayer("Owner")
            val member = paper.addPlayer("Member")
            val stranger = paper.addPlayer("Stranger")
            val control = CivPosition(-1, 63, 0)
            val old = protectionBuilding(0, 1).copy(phase = BuildingPhase.CONSTRUCTING, control = control)
            val planned = old.copy(level = 2, bounds = CivBox(CivPosition(0, 64, 0), CivPosition(3, 66, 3)))
            var city = protectionCity(world.uid, player.uniqueId, old).copy(members = setOf(member.uniqueId))
            val listener = CivsProtection({ listOf(city) })
            paper.server.pluginManager.registerEvents(listener, paper.createSimplePlugin("ProtectionTest"))
            val current = world.getBlockAt(0, 65, 0).also { it.type = Material.STONE }
            val future = world.getBlockAt(3, 66, 3).also { it.type = Material.STONE }
            val barrel = world.getBlockAt(control.x, control.y, control.z).also { it.type = Material.BARREL }
            for (phase in ConstructionPhase.entries) {
                city = city.copy(construction = CivConstruction(UUID.randomUUID(), old.id, 2, 1, 0,
                    phase, Stock(), plannedBuilding = planned))
                listener.refresh()
                for (actor in listOf(player, member, stranger)) {
                    val denied = actor == stranger
                    for (block in listOf(current, future)) {
                        paper.callEvent(BlockBreakEvent(block, actor)).isCancelled shouldBe denied
                        paper.callEvent(BlockPlaceEvent(block, block.state, block.getRelative(BlockFace.DOWN),
                            ItemStack(Material.STONE), actor, true, EquipmentSlot.HAND)).isCancelled shouldBe denied
                    }
                    paper.callEvent(BlockMultiPlaceEvent(listOf(current.state, future.state), world.getBlockAt(4, 63, 0),
                        ItemStack(Material.STONE), actor, true, EquipmentSlot.HAND)).isCancelled shouldBe denied
                    paper.callEvent(BlockBreakEvent(barrel, actor)).isCancelled shouldBe true
                }
            }
            paper.callEvent(BlockBreakEvent(world.getBlockAt(4, 64, 0), player)).isCancelled shouldBe false
            paper.callEvent(BlockBreakEvent(otherWorld.getBlockAt(0, 65, 0), player)).isCancelled shouldBe false
            city = city.copy(pendingDeletion = CivDeletion(null))
            listener.refresh()
            for (actor in listOf(player, member)) for (block in listOf(current, future)) {
                paper.callEvent(BlockBreakEvent(block, actor)).isCancelled shouldBe true
                paper.callEvent(BlockPlaceEvent(block, block.state, block.getRelative(BlockFace.DOWN),
                    ItemStack(Material.STONE), actor, true, EquipmentSlot.HAND)).isCancelled shouldBe true
            }
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

    test("finished doors vines and leaves receive native physics while their footprint stays protected") {
        protectionScenario { paper ->
            val world = paper.addSimpleWorld("finished-physics")
            val city = protectionCity(world.uid, UUID.randomUUID(), protectionBuilding(0, 3))
            val listener = CivsProtection({ listOf(city) })
            paper.server.pluginManager.registerEvents(listener, paper.createSimplePlugin("FinishedPhysicsTest"))
            val lower = world.getBlockAt(0, 64, 1).also {
                it.blockData = org.bukkit.Bukkit.createBlockData("minecraft:oak_door[half=lower,open=true]")
            }
            val upper = world.getBlockAt(0, 65, 1).also {
                it.blockData = org.bukkit.Bukkit.createBlockData("minecraft:oak_door[half=upper,open=false]")
            }
            val vine = world.getBlockAt(1, 65, 1).also { it.type = Material.VINE }
            val leaves = world.getBlockAt(2, 65, 1).also { it.type = Material.OAK_LEAVES }
            for (block in listOf(lower, upper, vine)) {
                listener.isProtected(world.uid, block.x, block.y, block.z) shouldBe true
                paper.callEvent(BlockPhysicsEvent(block, lower.blockData, lower)).isCancelled shouldBe false
                paper.callEvent(BlockDestroyEvent(block, Material.AIR.createBlockData(), block.blockData, 0, true))
                    .isCancelled shouldBe false
            }
            paper.callEvent(LeavesDecayEvent(leaves)).isCancelled shouldBe false
            val alreadyDenied = BlockPhysicsEvent(upper, lower.blockData, lower).also { it.isCancelled = true }
            paper.callEvent(alreadyDenied).isCancelled shouldBe true
        }
    }

    test("construction allows native door and vine physics while its control stays locked") {
        protectionScenario { paper ->
            val world = paper.addSimpleWorld("construction-physics")
            val building = protectionBuilding(0, 2).copy(control = CivPosition(1, 64, 1))
            val unrelated = protectionBuilding(10, 12).copy(type = BuildingType.HOUSE)
            val planned = building.copy(level = 2, bounds = CivBox(CivPosition(0, 64, 0), CivPosition(4, 66, 3)))
            var city = protectionCity(world.uid, UUID.randomUUID(), building).copy(buildings = listOf(building, unrelated))
            val listener = CivsProtection({ listOf(city) })
            paper.server.pluginManager.registerEvents(listener, paper.createSimplePlugin("ConstructionPhysicsTest"))
            val currentDoor = world.getBlockAt(0, 65, 1).also { it.type = Material.OAK_DOOR }
            val plannedVine = world.getBlockAt(4, 65, 1).also { it.type = Material.VINE }
            val control = world.getBlockAt(1, 64, 1).also { it.type = Material.BARREL }
            val other = world.getBlockAt(10, 65, 1).also { it.type = Material.OAK_DOOR }
            for (phase in ConstructionPhase.entries) {
                city = city.copy(construction = CivConstruction(UUID.randomUUID(), building.id, 2, 3, 0,
                    phase, Stock(), plannedBuilding = planned))
                listener.refresh()
                for (block in listOf(currentDoor, plannedVine, control)) {
                    val denied = block == control
                    paper.callEvent(BlockPhysicsEvent(block, block.blockData)).isCancelled shouldBe denied
                    paper.callEvent(BlockDestroyEvent(block, Material.AIR.createBlockData(), block.blockData, 0, true))
                        .isCancelled shouldBe denied
                }
                paper.callEvent(BlockPhysicsEvent(other, other.blockData)).isCancelled shouldBe false
            }
            city = city.copy(construction = null)
            listener.refresh()
            paper.callEvent(BlockPhysicsEvent(currentDoor, currentDoor.blockData)).isCancelled shouldBe false
            paper.callEvent(BlockPhysicsEvent(control, control.blockData)).isCancelled shouldBe true
        }
    }

    test("only authored farm crops grow and exact journal locks stop growth moisture and physics until release") {
        protectionScenario { paper ->
            val world = paper.addSimpleWorld("farm-growth")
            val farm = protectionBuilding(-3, 17).copy(type = BuildingType.FARM, anchor = CivPosition(0, 63, 0),
                schematicId = "civs/minecolonies/medievaloak/agriculture/horticulture/farmer1",
                bounds = CivBox(CivPosition(-3, 63, -23), CivPosition(17, 86, -1)), entrance = CivPosition(0, 64, 0))
            val neighbor = protectionBuilding(30, 32).copy(type = BuildingType.HOUSE)
            var city = protectionCity(world.uid, UUID.randomUUID(), farm).copy(buildings = listOf(farm, neighbor))
            val fields = CivsWorksiteLayouts.field(farm)
            val crop = fields.first().let { it.copy(y = it.y + 1) }
            val otherCrop = fields[1].let { it.copy(y = it.y + 1) }
            val soil = fields.first()
            val otherSoil = fields[1]
            listOf(soil, otherSoil).forEach { world.getBlockAt(it.x, it.y, it.z).type = Material.FARMLAND }
            val points = listOf(crop, otherCrop, CivPosition(0, 65, -2), CivPosition(30, 65, 1), CivPosition(40, 65, 1))
            points.forEach { world.getBlockAt(it.x, it.y, it.z).type = Material.WHEAT }
            var locked = emptySet<Pair<UUID, CivPosition>>()
            val listener = CivsProtection({ listOf(city) }, worldLocked = { worldId, point -> (worldId to point) in locked })
            paper.server.pluginManager.registerEvents(listener, paper.createSimplePlugin("FarmGrowthProtectionTest"))
            fun grow(point: CivPosition): BlockGrowEvent {
                val block = world.getBlockAt(point.x, point.y, point.z)
                val after = block.state
                after.blockData = (after.blockData as Ageable).also { it.age = it.maximumAge }
                return paper.callEvent(BlockGrowEvent(block, after))
            }
            fun moisture(point: CivPosition): MoistureChangeEvent {
                val block = world.getBlockAt(point.x, point.y, point.z)
                val after = block.state
                after.blockData = (after.blockData as Farmland).also { it.moisture = it.maximumMoisture }
                return paper.callEvent(MoistureChangeEvent(block, after))
            }
            grow(crop).isCancelled shouldBe false
            grow(otherCrop).isCancelled shouldBe false
            grow(CivPosition(0, 65, -2)).isCancelled shouldBe true
            grow(CivPosition(30, 65, 1)).isCancelled shouldBe true
            grow(CivPosition(40, 65, 1)).isCancelled shouldBe false

            val block = world.getBlockAt(crop.x, crop.y, crop.z)
            locked = setOf(world.uid to crop)
            grow(crop).isCancelled shouldBe true
            grow(otherCrop).isCancelled shouldBe false
            moisture(soil).isCancelled shouldBe false
            locked = setOf(world.uid to crop, world.uid to soil)
            moisture(soil).isCancelled shouldBe true
            moisture(otherSoil).isCancelled shouldBe false
            paper.callEvent(BlockPhysicsEvent(block, block.blockData)).isCancelled shouldBe true
            val adjacent = world.getBlockAt(otherCrop.x, otherCrop.y, otherCrop.z)
            paper.callEvent(BlockPhysicsEvent(adjacent, adjacent.blockData)).isCancelled shouldBe false
            locked = emptySet()
            grow(crop).isCancelled shouldBe false
            moisture(soil).isCancelled shouldBe false
            paper.callEvent(BlockPhysicsEvent(block, block.blockData)).isCancelled shouldBe false

            // An upgrading farm retains ACTIVE as its current record until the replacement is committed.
            city = city.copy(construction = CivConstruction(UUID.randomUUID(), farm.id, 2, 3, 0,
                ConstructionPhase.CONSTRUCTING, Stock(), plannedBuilding = farm.copy(level = 2, phase = BuildingPhase.CONSTRUCTING)))
            listener.refresh()
            grow(crop).isCancelled shouldBe false
        }
    }

    for (autonomous in listOf(false, true)) test("natural grass spreads over dirt in ${if (autonomous) "Auto" else "player"} buildings while exact locks stay protected") {
        protectionScenario { paper ->
            val world = paper.addSimpleWorld("grass-spread-$autonomous")
            val owner = paper.addPlayer("GrassOwner")
            val house = protectionBuilding(0, 2).copy(type = BuildingType.HOUSE)
            val city = protectionCity(world.uid, owner.uniqueId, house).let {
                if (autonomous) it.copy(inventory = null, autonomy = CivAutonomy()) else it
            }
            val target = world.getBlockAt(0, 64, 0).also { it.type = Material.DIRT }
            val source = world.getBlockAt(1, 64, 0).also { it.type = Material.GRASS_BLOCK }
            var locked: CivPosition? = null
            var available = true
            val listener = CivsProtection({ listOf(city) }, { available }, setOf(world.name),
                worldLocked = { _, point -> point == locked })
            paper.server.pluginManager.registerEvents(listener, paper.createSimplePlugin("GrassSpreadProtection"))
            fun spread(material: Material = Material.GRASS_BLOCK, cancelled: Boolean = false): BlockSpreadEvent {
                val after = target.state.also { it.type = material }
                return paper.callEvent(BlockSpreadEvent(target, source, after).also { it.isCancelled = cancelled })
            }
            spread().isCancelled shouldBe false
            paper.callEvent(BlockBreakEvent(target, paper.addPlayer("GrassStranger"))).isCancelled shouldBe true
            locked = CivPosition(target.x, target.y, target.z)
            spread().isCancelled shouldBe true
            locked = CivPosition(source.x, source.y, source.z)
            spread().isCancelled shouldBe true
            locked = null
            available = false
            spread().isCancelled shouldBe true
            available = true
            spread(cancelled = true).isCancelled shouldBe true
            source.type = Material.MYCELIUM
            spread(Material.MYCELIUM).isCancelled shouldBe true
            source.type = Material.FIRE
            spread(Material.FIRE).isCancelled shouldBe true
            source.type = Material.GRASS_BLOCK
            target.type = Material.COARSE_DIRT
            spread().isCancelled shouldBe true
            target.type = Material.DIRT
            spread().isCancelled shouldBe false
        }
    }

    test("Auto field grows naturally while player harvesting and transient receipt writes remain protected") {
        protectionScenario { paper ->
            val world = paper.addSimpleWorld("auto-growth")
            val owner = paper.addPlayer("AutoOwner")
            val farm = spatialBuilding("medievaloak", "farmer", 1, CivPosition(0, 63, 0))
            val worker = CivWorker(UUID.randomUUID(), "Farmer", farm.id)
            val city = spatialCity(world.uid, listOf(farm), worker).copy(owner = owner.uniqueId,
                inventory = null, autonomy = CivAutonomy())
            val crop = CivsWorksiteLayouts.field(farm).first().let { it.copy(y = it.y + 1) }
            val block = world.getBlockAt(crop.x, crop.y, crop.z).also { it.type = Material.WHEAT }
            var locked = false
            var available = true
            val listener = CivsProtection({ listOf(city) }, { available }, setOf(world.name),
                worldLocked = { _, point -> locked && point == crop })
            paper.server.pluginManager.registerEvents(listener, paper.createSimplePlugin("AutoFieldProtection"))
            fun grow(): BlockGrowEvent {
                val after = block.state
                after.blockData = (after.blockData as Ageable).also { it.age = 1 }
                return paper.callEvent(BlockGrowEvent(block, after))
            }
            grow().isCancelled shouldBe false
            paper.callEvent(BlockBreakEvent(block, owner)).isCancelled shouldBe true
            locked = true
            grow().isCancelled shouldBe true
            locked = false
            available = false
            grow().isCancelled shouldBe true
            available = true
            grow().isCancelled shouldBe false
        }
    }

    // MockBukkit 4.116.3 does not implement Inventory.getHolder(false) for native container events.
    test("members can remodel finished buildings with drops and containers while strangers stay blocked").config(enabled = false) {
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

    test("accepted construction locks its own controls without blocking another site control restore") {
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

            // Another owned construction footprint does not remove this unrelated SITE's restore permission.
            val accepted = protectionBuilding(-3, -1).copy(type = BuildingType.HOUSE,
                bounds = CivBox(CivPosition(-3, 62, 0), CivPosition(-1, 63, 1)))
            city = city.copy(buildings = listOf(site, accepted), construction = CivConstruction(UUID.randomUUID(), accepted.id,
                2, 3, 0, ConstructionPhase.CONSTRUCTING, Stock(), plannedBuilding = accepted.copy(level = 2, phase = BuildingPhase.CONSTRUCTING)))
            listener.refresh()
            paper.callEvent(controlPlacement(world, control, owner)).isCancelled shouldBe false
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
