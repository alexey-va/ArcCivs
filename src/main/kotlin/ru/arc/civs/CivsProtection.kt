package ru.arc.civs

import com.destroystokyo.paper.event.block.BlockDestroyEvent
import io.papermc.paper.event.block.PlayerShearBlockEvent
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.block.Container
import org.bukkit.block.DoubleChest
import org.bukkit.block.data.Directional
import org.bukkit.block.data.Openable
import org.bukkit.entity.EntityType
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.*
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityInteractEvent
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerHarvestBlockEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.world.StructureGrowEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import java.util.UUID

/**
 * Members may remodel their buildings during construction and restore an eligible missing control barrel.
 * Existing control blocks, pending deletion and exact inventory transaction cells stay locked.
 * Environmental changes and third-party pastes retain the complete footprint guard;
 * the settlement radius itself grants no event protection. Core pastes do not emit these events.
 */
class CivsProtection(
    private val cities: () -> Collection<Settlement>,
    private val available: () -> Boolean = { true },
    private val protectedWorlds: Set<String> = emptySet(),
    private val worldLocked: (UUID, CivPosition) -> Boolean = { _, _ -> false },
    private val forestryPath: (UUID, Int, Int) -> Boolean = { _, _, _ -> false },
    private val citizenCollision: (UUID, CivPosition) -> Boolean = { _, _ -> false },
) : Listener {
    internal data class Footprint(val worldId: UUID, val bounds: CivBox, val editors: Set<UUID>,
        val barrelRestorers: Set<UUID> = emptySet(), val adminEditable: Boolean = false)
    internal data class Snapshot(val geometry: List<Footprint>, val worlds: Map<UUID, IndexNode>,
        val controlEditors: Map<Pair<UUID, CivPosition>, Set<UUID>>,
        val cropCells: Set<Pair<UUID, CivPosition>>,
        val autonomousControls: Map<Pair<UUID, CivPosition>, Boolean> = emptyMap(),
        val growthWorlds: Map<UUID, IndexNode> = emptyMap(), val growthGeometry: List<Footprint> = emptyList(),
        val autonomousInventoryWorlds: Map<UUID, IndexNode> = emptyMap(),
        val autonomousInventoryGeometry: List<Footprint> = emptyList())
    internal class IndexNode(val bounds: CivBox, val boxes: List<Footprint>, val left: IndexNode? = null, val right: IndexNode? = null) {
        fun contains(x: Int, y: Int, z: Int, actor: UUID?, restoringBarrel: Boolean = false, admin: Boolean = false): Boolean = bounds.contains(x, y, z) &&
            (boxes.any { it.bounds.contains(x, y, z) &&
                !(admin && it.adminEditable) &&
                (actor == null || actor !in it.editors && (!restoringBarrel || actor !in it.barrelRestorers)) } ||
                left?.contains(x, y, z, actor, restoringBarrel, admin) == true || right?.contains(x, y, z, actor, restoringBarrel, admin) == true)

        fun lockedPhysics(x: Int, y: Int, z: Int): Boolean = bounds.contains(x, y, z) &&
            (boxes.any { it.editors.isEmpty() && it.bounds.contains(x, y, z) } ||
                left?.lockedPhysics(x, y, z) == true || right?.lockedPhysics(x, y, z) == true)
    }
    private var snapshot = prepare(emptyList())

    init { refresh() }

    /** Compatibility for small fixture/offline callers; runtime preparation uses [prepare] on its worker. */
    fun refresh(): Boolean {
        val prepared = prepare(cities())
        if (prepared.geometry == snapshot.geometry && prepared.controlEditors == snapshot.controlEditors &&
            prepared.cropCells == snapshot.cropCells && prepared.autonomousControls == snapshot.autonomousControls &&
            prepared.growthGeometry == snapshot.growthGeometry &&
            prepared.autonomousInventoryGeometry == snapshot.autonomousInventoryGeometry) return false
        return publish(prepared)
    }

    internal fun publish(prepared: Snapshot): Boolean {
        check(Bukkit.isPrimaryThread()) { "Civs protection publication requires the server thread" }
        snapshot = prepared
        return true
    }

    companion object {
        /** Pure index construction over detached immutable city records. */
        internal fun prepare(cities: Collection<Settlement>): Snapshot {
            val crops = mutableSetOf<Pair<UUID, CivPosition>>()
            val current = cities.flatMap { city ->
                val editors = (city.members + city.owner).toSet()
                buildList {
                    city.pendingControls.orEmpty().flatMap { it.approach.orEmpty() }.forEach { cell ->
                        add(Footprint(city.worldId, CivBox(cell.position, cell.position), emptySet()))
                    }
                    city.inventory?.containers?.forEach { point -> add(Footprint(city.worldId, CivBox(point, point), editors,
                        adminEditable = city.pendingDeletion == null)) }
                    city.buildings.forEach { building ->
                        val locked = city.pendingDeletion != null
                        add(Footprint(city.worldId, building.bounds, if (locked) emptySet() else editors, adminEditable = !locked))
                        if (CivsAutonomousWorksites.supports(city, building) && building.phase == BuildingPhase.ACTIVE)
                            CivsAutonomousWorksites.protectionBounds(building).forEach {
                                add(Footprint(city.worldId, it, emptySet(), adminEditable = !locked))
                            }
                        if (building.type == BuildingType.FARM && building.phase == BuildingPhase.ACTIVE && city.pendingDeletion == null) {
                            CivsWorksiteLayouts.field(building).forEach { soil ->
                                val crop = soil.copy(y = soil.y + 1)
                                crops += city.worldId to crop
                                add(Footprint(city.worldId, CivBox(soil, crop), editors, adminEditable = true))
                            }
                        }
                    }
                    city.construction?.plannedBuilding?.let {
                        add(Footprint(city.worldId, it.bounds, if (city.pendingDeletion != null) emptySet() else editors,
                            adminEditable = city.pendingDeletion == null))
                    }
                    civsProtectedBuildings(city).forEach { building ->
                        val point = building.control ?: return@forEach
                        val restorers = if (city.pendingDeletion == null && building.phase != BuildingPhase.CONSTRUCTING && city.construction?.buildingId != building.id)
                            editors else emptySet()
                        add(Footprint(city.worldId, CivBox(point, point), emptySet(), restorers))
                    }
                }
            }.distinct()
            val growth = current.map { it.copy(bounds = CivBox(it.bounds.min.copy(y = -4_096), it.bounds.max.copy(y = 4_096))) } +
                cities.flatMap { city -> CivsForestry.paths(city).map { (x, z) ->
                    Footprint(city.worldId, CivBox(CivPosition(x, -4_096, z), CivPosition(x, 4_096, z)), emptySet())
                } }
            // Inventory authority is stricter than owner-attributed native terrain and path edits.
            val inventories = cities.filter { it.autonomy != null }.flatMap { city ->
                val boxes = city.buildings.map(CivBuilding::bounds) + listOfNotNull(city.construction?.plannedBuilding?.bounds) +
                    (city.inventory?.containers.orEmpty() + city.buildings.mapNotNull(CivBuilding::control)).map { CivBox(it, it) }
                boxes.map { Footprint(city.worldId, it, emptySet(), adminEditable = city.pendingDeletion == null) }
            }.distinct()
            return Snapshot(current, current.groupBy { it.worldId }.mapValues { (_, footprints) ->
                index(footprints)
            }, cities.flatMap { city -> city.buildings.mapNotNull { it.control }.map { (city.worldId to it) to (city.members + city.owner) } }.toMap(), crops,
                cities.filter { it.autonomy != null }.flatMap { city ->
                    city.buildings.mapNotNull { it.control }.map { (city.worldId to it) to (city.pendingDeletion == null) }
                }.toMap(), growth.groupBy { it.worldId }.mapValues { (_, footprints) -> index(footprints) }, growth,
                inventories.groupBy { it.worldId }.mapValues { (_, footprints) -> index(footprints) }, inventories)
        }

        /** Area-independent memory preserves old large footprints without enumerating their chunks. */
        private fun index(boxes: List<Footprint>): IndexNode {
            val bounds = CivBox(CivPosition(boxes.minOf { it.bounds.min.x }, boxes.minOf { it.bounds.min.y }, boxes.minOf { it.bounds.min.z }),
                CivPosition(boxes.maxOf { it.bounds.max.x }, boxes.maxOf { it.bounds.max.y }, boxes.maxOf { it.bounds.max.z }))
            if (boxes.size <= 8) return IndexNode(bounds, boxes)
            val xWidth = bounds.max.x.toLong() - bounds.min.x
            val zWidth = bounds.max.z.toLong() - bounds.min.z
            val sorted = boxes.sortedBy { if (xWidth >= zWidth) it.bounds.min.x.toLong() + it.bounds.max.x else it.bounds.min.z.toLong() + it.bounds.max.z }
            val middle = sorted.size / 2
            return IndexNode(bounds, emptyList(), index(sorted.subList(0, middle)), index(sorted.subList(middle, sorted.size)))
        }
    }

    fun isProtected(worldId: UUID, x: Int, y: Int, z: Int, actor: UUID? = null): Boolean =
        isProtected(worldId, x, y, z, actor, false)

    private fun isProtected(worldId: UUID, x: Int, y: Int, z: Int, actor: UUID?, restoringBarrel: Boolean): Boolean {
        // Permission lookup stays on the owning thread; actorless/asynchronous protection remains immutable geometry.
        val admin = actor != null && Bukkit.isPrimaryThread() && Bukkit.getPlayer(actor)?.hasPermission("arccivs.admin") == true
        return (!available() && Bukkit.getWorld(worldId)?.name in protectedWorlds) ||
            admin && worldLocked(worldId, CivPosition(x, y, z)) ||
            snapshot.worlds[worldId]?.contains(x, y, z, actor, restoringBarrel, admin) == true
    }

    private fun protected(block: Block): Boolean = isProtected(block.world.uid, block.x, block.y, block.z)
    private fun protected(location: Location?): Boolean = location?.world?.let {
        isProtected(it.uid, location.blockX, location.blockY, location.blockZ)
    } ?: false
    private fun protected(block: Block, direction: BlockFace): Boolean =
        isProtected(block.world.uid, block.x + direction.modX, block.y + direction.modY, block.z + direction.modZ)
    private fun protected(block: Block, actor: UUID): Boolean = isProtected(block.world.uid, block.x, block.y, block.z, actor)
    private fun protected(location: Location?, actor: UUID): Boolean = location?.world?.let {
        isProtected(it.uid, location.blockX, location.blockY, location.blockZ, actor)
    } ?: false
    private fun protected(block: Block, direction: BlockFace, actor: UUID): Boolean =
        isProtected(block.world.uid, block.x + direction.modX, block.y + direction.modY, block.z + direction.modZ, actor)

    private fun protected(holder: InventoryHolder?): Boolean = when (holder) {
        is BlockState -> protected(holder.location)
        is DoubleChest -> protected(holder.getLeftSide(false)) || protected(holder.getRightSide(false))
        else -> false
    }
    private fun protected(inventory: Inventory): Boolean = protected(inventory.getHolder(false)) || protected(inventory.location)
    private fun protected(holder: InventoryHolder?, actor: UUID): Boolean = when (holder) {
        is BlockState -> protectedInventory(holder.location, actor)
        is DoubleChest -> protected(holder.getLeftSide(false), actor) || protected(holder.getRightSide(false), actor)
        else -> false
    }
    private fun protectedInventory(location: Location?, actor: UUID): Boolean {
        if (location?.world != null) {
            val worldId = location.world!!.uid
            val inventories = snapshot.autonomousInventoryWorlds[worldId]
            if (inventories?.contains(location.blockX, location.blockY, location.blockZ, null) == true) {
                val admin = Bukkit.isPrimaryThread() && Bukkit.getPlayer(actor)?.hasPermission("arccivs.admin") == true
                return !available() || worldLocked(worldId, CivPosition(location.blockX, location.blockY, location.blockZ)) ||
                    inventories.contains(location.blockX, location.blockY, location.blockZ, actor, admin = admin)
            }
        }
        if (available() && location?.world != null) {
            val key = location.world!!.uid to CivPosition(location.blockX, location.blockY, location.blockZ)
            if (key in snapshot.autonomousControls) return snapshot.autonomousControls[key] != true ||
                Bukkit.getPlayer(actor)?.hasPermission("arccivs.admin") != true
            if (actor in snapshot.controlEditors[key].orEmpty()) return false
        }
        return protected(location, actor)
    }
    private fun protected(inventory: Inventory, actor: UUID): Boolean =
        protected(inventory.getHolder(false), actor) || protectedInventory(inventory.location, actor)

    private fun autonomousInventory(location: Location?): Boolean = location?.world?.let { world ->
        snapshot.autonomousInventoryWorlds[world.uid]?.contains(location.blockX, location.blockY, location.blockZ, null)
    } == true

    private fun protectedContainer(state: BlockState, actor: UUID): Boolean =
        state is Container && autonomousInventory(state.location) && protectedInventory(state.location, actor)

    private fun inspectableAutonomous(inventory: Inventory, actor: UUID): Boolean {
        val locations = inventoryLocations(inventory.getHolder(false)).ifEmpty { listOfNotNull(inventory.location) }
        if (locations.none(::autonomousInventory)) return false
        val player = Bukkit.getPlayer(actor) ?: return false
        return available() && player.hasPermission("arccivs.use") && locations.all { location ->
            if (!autonomousInventory(location)) !protectedInventory(location, actor) else {
                val world = requireNotNull(location.world)
                snapshot.autonomousInventoryWorlds[world.uid]?.contains(location.blockX, location.blockY, location.blockZ,
                    null, admin = true) == false &&
                    !worldLocked(world.uid, CivPosition(location.blockX, location.blockY, location.blockZ)) &&
                    player.world.uid == world.uid && player.location.distanceSquared(location.clone().add(.5, .5, .5)) <= 64.0
            }
        }
    }

    private fun inventoryLocations(holder: InventoryHolder?): List<Location> = when (holder) {
        is BlockState -> listOf(holder.location)
        is DoubleChest -> inventoryLocations(holder.getLeftSide(false)) + inventoryLocations(holder.getRightSide(false))
        else -> emptyList()
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun breakBlock(event: BlockBreakEvent) {
        if (protected(event.block, event.player.uniqueId) ||
            autonomousInventory(event.block.location) && protectedContainer(event.block.getState(false), event.player.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun pillagerSpawn(event: CreatureSpawnEvent) {
        if (event.entityType != EntityType.PILLAGER) return
        val at = event.location
        val worldId = at.world?.uid ?: return
        if (cities().any { it.worldId == worldId && it.contains(at.blockX, at.blockZ) }) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun placeBlock(event: BlockPlaceEvent) {
        val block = event.blockPlaced
        val restoringBarrel = event !is BlockMultiPlaceEvent && block.type == Material.BARREL &&
            event.blockReplacedState.type != Material.BARREL
        if (isProtected(block.world.uid, block.x, block.y, block.z, event.player.uniqueId, restoringBarrel)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun placeMultiple(event: BlockMultiPlaceEvent) {
        if (event.replacedBlockStates.any { protected(it.location, event.player.uniqueId) }) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun explode(event: EntityExplodeEvent) { event.blockList().removeIf(::protected) }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun explodeBlock(event: BlockExplodeEvent) { event.blockList().removeIf(::protected) }

    private fun piston(block: Block, moved: List<Block>, direction: BlockFace): Boolean =
        protected(block) || protected(block, direction) || moved.any { protected(it) || protected(it, direction) }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun extend(event: BlockPistonExtendEvent) {
        if (piston(event.block, event.blocks, event.direction)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun retract(event: BlockPistonRetractEvent) {
        // Paper reports the movement direction for retraction; check both sides to include the piston head.
        if (piston(event.block, event.blocks, event.direction) || protected(event.block, event.direction.oppositeFace)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun fluid(event: BlockFromToEvent) {
        if (protected(event.block) || protected(event.toBlock)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun ignite(event: BlockIgniteEvent) {
        if (protected(event.block) || event.ignitingBlock?.let(::protected) == true) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun burn(event: BlockBurnEvent) {
        if (protected(event.block) || event.ignitingBlock?.let(::protected) == true) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun entityChange(event: EntityChangeBlockEvent) {
        val block = event.block
        val player = event.entity as? org.bukkit.entity.Player
        // Trails attributes its transition to the real online owner/member, as a manual edit would.
        val denied = if (player != null) protected(block, player.uniqueId) else protected(block)
        if (denied || worldLocked(block.world.uid, CivPosition(block.x, block.y, block.z))) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun entityInteract(event: EntityInteractEvent) { if (protected(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun grow(event: BlockGrowEvent) {
        val block = event.block
        if (transientPhysicsLock(block)) { event.isCancelled = true; return }
        if ((block.world.uid to CivPosition(block.x, block.y, block.z)) in snapshot.cropCells &&
            block.blockData is org.bukkit.block.data.Ageable) return
        if (protected(block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun moisture(event: MoistureChangeEvent) { if (physicsProtected(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun spread(event: BlockSpreadEvent) {
        if (event.block.type == Material.DIRT && event.source.type == Material.GRASS_BLOCK &&
            event.newState.type == Material.GRASS_BLOCK) {
            if (transientPhysicsLock(event.block) || transientPhysicsLock(event.source)) event.isCancelled = true
            return
        }
        if (protected(event.block) || protected(event.source)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun form(event: BlockFormEvent) { if (protected(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun entityForm(event: EntityBlockFormEvent) { if (protected(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun fade(event: BlockFadeEvent) { if (protected(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun leaves(event: LeavesDecayEvent) { if (physicsProtected(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun physics(event: BlockPhysicsEvent) { if (physicsProtected(event.block)) event.isCancelled = true }

    private fun transientPhysicsLock(block: Block): Boolean =
        (!available() && block.world.name in protectedWorlds) || worldLocked(block.world.uid, CivPosition(block.x, block.y, block.z))

    private fun physicsProtected(block: Block): Boolean =
        transientPhysicsLock(block) ||
            snapshot.worlds[block.world.uid]?.lockedPhysics(block.x, block.y, block.z) == true

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun destroy(event: BlockDestroyEvent) { if (physicsProtected(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun drops(event: BlockDropItemEvent) {
        if (protected(event.block, event.player.uniqueId) || protectedContainer(event.blockState, event.player.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun cook(event: BlockCookEvent) { if (protected(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun sign(event: SignChangeEvent) { if (protected(event.block, event.player.uniqueId)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun tree(event: StructureGrowEvent) {
        fun blockedRoot(location: Location): Boolean = location.world?.let { world ->
            worldLocked(world.uid, CivPosition(location.blockX, location.blockY, location.blockZ)) || protected(location) ||
                snapshot.growthWorlds[world.uid]?.contains(location.blockX, location.blockY, location.blockZ, null) == true ||
                forestryPath(world.uid, location.blockX, location.blockZ)
        } ?: false
        if (blockedRoot(event.location) || event.blocks.any { proposed ->
                val world = proposed.world
                val point = CivPosition(proposed.x, proposed.y, proposed.z)
                val before = proposed.block
                val leaves = before.blockData as? org.bukkit.block.data.type.Leaves
                val proposedWood = proposed.type.name.let { it.endsWith("_LOG") || it.endsWith("_WOOD") }
                worldLocked(world.uid, point) || citizenCollision(world.uid, point) ||
                    before.blockData.asString != proposed.blockData.asString &&
                    (protected(proposed.location) && (!before.type.isAir || proposedWood) || !before.type.isAir &&
                        forestryPath(world.uid, proposed.x, proposed.z) && (leaves == null || leaves.isPersistent))
            }) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun fertilize(event: BlockFertilizeEvent) {
        if (protected(event.block) || event.blocks.any { protected(it.location) }) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun absorb(event: SpongeAbsorbEvent) {
        if (protected(event.block) || event.blocks.any { protected(it.location) }) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun dispense(event: BlockDispenseEvent) {
        val facing = (event.block.blockData as? Directional)?.facing
        if (protected(event.block) || facing != null && protected(event.block, facing)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun dispenseLoot(event: BlockDispenseLootEvent) { if (protected(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun emptyBucket(event: PlayerBucketEmptyEvent) {
        val actor = event.player.uniqueId
        if (protected(event.block, actor) || protected(event.blockClicked, actor) || protected(event.blockClicked, event.blockFace, actor)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun fillBucket(event: PlayerBucketFillEvent) {
        if (protected(event.block, event.player.uniqueId) || protected(event.blockClicked, event.player.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun harvest(event: PlayerHarvestBlockEvent) { if (protected(event.harvestedBlock, event.player.uniqueId)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun shear(event: PlayerShearBlockEvent) { if (protected(event.block, event.player.uniqueId)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun interact(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        if (event.action == Action.RIGHT_CLICK_BLOCK && autonomousInventory(block.location)) {
            val container = block.getState(false) as? Container
            if (container != null && inspectableAutonomous(container.inventory, event.player.uniqueId)) {
                event.setUseItemInHand(Event.Result.DENY)
                return
            }
        }
        if (!protected(block, event.player.uniqueId)) return
        event.setUseItemInHand(Event.Result.DENY)
        if (event.action != Action.RIGHT_CLICK_BLOCK || block.blockData !is Openable) {
            event.setUseInteractedBlock(Event.Result.DENY)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun open(event: InventoryOpenEvent) {
        if (!inspectableAutonomous(event.inventory, event.player.uniqueId) && protected(event.inventory, event.player.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun click(event: InventoryClickEvent) {
        if (protected(event.view.topInventory, event.whoClicked.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun drag(event: InventoryDragEvent) {
        if (protected(event.view.topInventory, event.whoClicked.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun moveItem(event: InventoryMoveItemEvent) {
        if (protected(event.source) || protected(event.destination)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun pickup(event: InventoryPickupItemEvent) { if (protected(event.inventory)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun cauldron(event: CauldronLevelChangeEvent) { if (protected(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun prime(event: TNTPrimeEvent) { if (protected(event.block)) event.isCancelled = true }
}
