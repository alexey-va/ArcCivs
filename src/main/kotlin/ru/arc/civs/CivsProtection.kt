package ru.arc.civs

import com.destroystokyo.paper.event.block.BlockDestroyEvent
import io.papermc.paper.event.block.PlayerShearBlockEvent
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.block.DoubleChest
import org.bukkit.block.data.Directional
import org.bukkit.block.data.Openable
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.*
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityInteractEvent
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
) : Listener {
    internal data class Footprint(val worldId: UUID, val bounds: CivBox, val editors: Set<UUID>,
        val barrelRestorers: Set<UUID> = emptySet())
    internal data class Snapshot(val geometry: List<Footprint>, val worlds: Map<UUID, IndexNode>,
        val controlEditors: Map<Pair<UUID, CivPosition>, Set<UUID>>,
        val cropCells: Set<Pair<UUID, CivPosition>>)
    internal class IndexNode(val bounds: CivBox, val boxes: List<Footprint>, val left: IndexNode? = null, val right: IndexNode? = null) {
        fun contains(x: Int, y: Int, z: Int, actor: UUID?, restoringBarrel: Boolean = false): Boolean = bounds.contains(x, y, z) &&
            (boxes.any { it.bounds.contains(x, y, z) &&
                (actor == null || actor !in it.editors && (!restoringBarrel || actor !in it.barrelRestorers)) } ||
                left?.contains(x, y, z, actor, restoringBarrel) == true || right?.contains(x, y, z, actor, restoringBarrel) == true)

        fun lockedPhysics(x: Int, y: Int, z: Int): Boolean = bounds.contains(x, y, z) &&
            (boxes.any { it.editors.isEmpty() && it.bounds.contains(x, y, z) } ||
                left?.lockedPhysics(x, y, z) == true || right?.lockedPhysics(x, y, z) == true)
    }
    private var snapshot = prepare(emptyList())

    init { refresh() }

    /** Compatibility for small fixture/offline callers; runtime preparation uses [prepare] on its worker. */
    fun refresh(): Boolean {
        val prepared = prepare(cities())
        if (prepared.geometry == snapshot.geometry) return false
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
                    city.inventory?.containers?.forEach { point -> add(Footprint(city.worldId, CivBox(point, point), editors)) }
                    city.buildings.forEach { building ->
                        val locked = city.pendingDeletion != null
                        add(Footprint(city.worldId, building.bounds, if (locked) emptySet() else editors))
                        if (building.type == BuildingType.FARM && building.phase == BuildingPhase.ACTIVE && city.pendingDeletion == null) {
                            CivsWorksiteLayouts.field(building).forEach { soil ->
                                val crop = soil.copy(y = soil.y + 1)
                                crops += city.worldId to crop
                                add(Footprint(city.worldId, CivBox(soil, crop), editors))
                            }
                        }
                    }
                    city.construction?.plannedBuilding?.let {
                        add(Footprint(city.worldId, it.bounds, if (city.pendingDeletion != null) emptySet() else editors))
                    }
                    civsProtectedBuildings(city).forEach { building ->
                        val point = building.control ?: return@forEach
                        val restorers = if (city.pendingDeletion == null && building.phase != BuildingPhase.CONSTRUCTING && city.construction?.buildingId != building.id)
                            editors else emptySet()
                        add(Footprint(city.worldId, CivBox(point, point), emptySet(), restorers))
                    }
                }
            }.distinct()
            return Snapshot(current, current.groupBy { it.worldId }.mapValues { (_, footprints) ->
                index(footprints)
            }, cities.flatMap { city -> city.buildings.mapNotNull { it.control }.map { (city.worldId to it) to (city.members + city.owner) } }.toMap(), crops)
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

    private fun isProtected(worldId: UUID, x: Int, y: Int, z: Int, actor: UUID?, restoringBarrel: Boolean): Boolean =
        (!available() && Bukkit.getWorld(worldId)?.name in protectedWorlds) ||
            snapshot.worlds[worldId]?.contains(x, y, z, actor, restoringBarrel) == true

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
        if (available() && location?.world != null && actor in snapshot.controlEditors[location.world!!.uid to
                CivPosition(location.blockX, location.blockY, location.blockZ)].orEmpty()) return false
        return protected(location, actor)
    }
    private fun protected(inventory: Inventory, actor: UUID): Boolean =
        protected(inventory.getHolder(false), actor) || protectedInventory(inventory.location, actor)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun breakBlock(event: BlockBreakEvent) { if (protected(event.block, event.player.uniqueId)) event.isCancelled = true }

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
    fun entityChange(event: EntityChangeBlockEvent) { if (protected(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun entityInteract(event: EntityInteractEvent) { if (protected(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun grow(event: BlockGrowEvent) {
        val block = event.block
        if (physicsProtected(block)) { event.isCancelled = true; return }
        if ((block.world.uid to CivPosition(block.x, block.y, block.z)) in snapshot.cropCells &&
            block.blockData is org.bukkit.block.data.Ageable) return
        if (protected(block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun spread(event: BlockSpreadEvent) {
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

    private fun physicsProtected(block: Block): Boolean =
        (!available() && block.world.name in protectedWorlds) || worldLocked(block.world.uid, CivPosition(block.x, block.y, block.z)) ||
            snapshot.worlds[block.world.uid]?.lockedPhysics(block.x, block.y, block.z) == true

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun destroy(event: BlockDestroyEvent) { if (physicsProtected(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun drops(event: BlockDropItemEvent) { if (protected(event.block, event.player.uniqueId)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun cook(event: BlockCookEvent) { if (protected(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun sign(event: SignChangeEvent) { if (protected(event.block, event.player.uniqueId)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun tree(event: StructureGrowEvent) {
        if (protected(event.location) || event.blocks.any { protected(it.location) }) event.isCancelled = true
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
        if (!protected(block, event.player.uniqueId)) return
        event.setUseItemInHand(Event.Result.DENY)
        if (event.action != Action.RIGHT_CLICK_BLOCK || block.blockData !is Openable) {
            event.setUseInteractedBlock(Event.Result.DENY)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun open(event: InventoryOpenEvent) { if (protected(event.inventory, event.player.uniqueId)) event.isCancelled = true }

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
