package ru.arc.civs

import com.destroystokyo.paper.event.block.BlockDestroyEvent
import io.papermc.paper.event.block.PlayerShearBlockEvent
import org.bukkit.Bukkit
import org.bukkit.Location
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
 * Generated structures are permanent city assets, never withdrawable world loot.
 * The full current and planned boxes are locked for everyone, including the owner;
 * the settlement radius itself grants no event protection. Core pastes do not emit these events.
 */
class CivsProtection(
    private val cities: () -> Collection<Settlement>,
    private val available: () -> Boolean = { true },
    private val protectedWorlds: Set<String> = emptySet(),
) : Listener {
    internal data class Footprint(val worldId: UUID, val bounds: CivBox)
    internal data class Snapshot(val geometry: List<Footprint>, val worlds: Map<UUID, IndexNode>)
    internal class IndexNode(val bounds: CivBox, val boxes: List<CivBox>, val left: IndexNode? = null, val right: IndexNode? = null) {
        fun contains(x: Int, y: Int, z: Int): Boolean = bounds.contains(x, y, z) &&
            (boxes.any { it.contains(x, y, z) } || left?.contains(x, y, z) == true || right?.contains(x, y, z) == true)
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
            val current = cities.flatMap { city -> civsProtectedBuildings(city).map { Footprint(city.worldId, it.bounds) }.toList() }.distinct()
            return Snapshot(current, current.groupBy { it.worldId }.mapValues { (_, footprints) ->
                index(footprints.map { it.bounds })
            })
        }

        /** Area-independent memory preserves old large footprints without enumerating their chunks. */
        private fun index(boxes: List<CivBox>): IndexNode {
            val bounds = CivBox(CivPosition(boxes.minOf { it.min.x }, boxes.minOf { it.min.y }, boxes.minOf { it.min.z }),
                CivPosition(boxes.maxOf { it.max.x }, boxes.maxOf { it.max.y }, boxes.maxOf { it.max.z }))
            if (boxes.size <= 8) return IndexNode(bounds, boxes)
            val xWidth = bounds.max.x.toLong() - bounds.min.x
            val zWidth = bounds.max.z.toLong() - bounds.min.z
            val sorted = boxes.sortedBy { if (xWidth >= zWidth) it.min.x.toLong() + it.max.x else it.min.z.toLong() + it.max.z }
            val middle = sorted.size / 2
            return IndexNode(bounds, emptyList(), index(sorted.subList(0, middle)), index(sorted.subList(middle, sorted.size)))
        }
    }

    fun isProtected(worldId: UUID, x: Int, y: Int, z: Int): Boolean =
        (!available() && Bukkit.getWorld(worldId)?.name in protectedWorlds) ||
            snapshot.worlds[worldId]?.contains(x, y, z) == true

    private fun protected(block: Block): Boolean = isProtected(block.world.uid, block.x, block.y, block.z)
    private fun protected(location: Location?): Boolean = location?.world?.let {
        isProtected(it.uid, location.blockX, location.blockY, location.blockZ)
    } ?: false
    private fun protected(block: Block, direction: BlockFace): Boolean =
        isProtected(block.world.uid, block.x + direction.modX, block.y + direction.modY, block.z + direction.modZ)

    private fun protected(holder: InventoryHolder?): Boolean = when (holder) {
        is BlockState -> protected(holder.location)
        is DoubleChest -> protected(holder.leftSide) || protected(holder.rightSide)
        else -> false
    }
    private fun protected(inventory: Inventory): Boolean = protected(inventory.holder) || protected(inventory.location)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun breakBlock(event: BlockBreakEvent) { if (protected(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun placeBlock(event: BlockPlaceEvent) { if (protected(event.blockPlaced)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun placeMultiple(event: BlockMultiPlaceEvent) {
        if (event.replacedBlockStates.any { protected(it.location) }) event.isCancelled = true
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
    fun grow(event: BlockGrowEvent) { if (protected(event.block)) event.isCancelled = true }

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
    fun leaves(event: LeavesDecayEvent) { if (protected(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun physics(event: BlockPhysicsEvent) { if (protected(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun destroy(event: BlockDestroyEvent) { if (protected(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun drops(event: BlockDropItemEvent) { if (protected(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun cook(event: BlockCookEvent) { if (protected(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun sign(event: SignChangeEvent) { if (protected(event.block)) event.isCancelled = true }

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
        if (protected(event.block) || protected(event.blockClicked) || protected(event.blockClicked, event.blockFace)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun fillBucket(event: PlayerBucketFillEvent) {
        if (protected(event.block) || protected(event.blockClicked)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun harvest(event: PlayerHarvestBlockEvent) { if (protected(event.harvestedBlock)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun shear(event: PlayerShearBlockEvent) { if (protected(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun interact(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        if (!protected(block)) return
        event.setUseItemInHand(Event.Result.DENY)
        if (event.action != Action.RIGHT_CLICK_BLOCK || block.blockData !is Openable) {
            event.setUseInteractedBlock(Event.Result.DENY)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun open(event: InventoryOpenEvent) { if (protected(event.inventory)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun click(event: InventoryClickEvent) {
        if (protected(event.view.topInventory)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun drag(event: InventoryDragEvent) {
        if (protected(event.view.topInventory)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun moveItem(event: InventoryMoveItemEvent) {
        if (protected(event.source) || protected(event.destination) || protected(event.initiator)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun pickup(event: InventoryPickupItemEvent) { if (protected(event.inventory)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun cauldron(event: CauldronLevelChangeEvent) { if (protected(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun prime(event: TNTPrimeEvent) { if (protected(event.block)) event.isCancelled = true }
}
