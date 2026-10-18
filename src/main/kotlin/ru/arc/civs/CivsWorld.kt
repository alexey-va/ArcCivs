package ru.arc.civs

import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.flags.type.Flags
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.TileState
import org.bukkit.block.data.BlockData
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.api.ArcSchematicBlock
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Settlement-specific placement policy; Core remains the sole schematic mutation owner.
 * Requests may arrive on any thread. All Bukkit reads run in one lifecycle-owned timer,
 * sharing 256 cells/tick across at most four scans. No scan loads a chunk or changes a block.
 * Final revalidation must follow publication of the durable, protected construction footprint.
 */
class CivsWorld(
    private val plugin: Plugin,
    tasks: LifecycleTaskScope,
    private val cities: () -> Collection<Settlement>,
) : AutoCloseable {
    private val monitor = Any()
    private val pending = LinkedHashSet<Scan<*>>()
    private var closed = false
    private var lands: CivsLandsAccess? = null
    private val timer = tasks.runTimer(1, 1, ::tick)

    fun capture(
        cityId: UUID, owner: UUID, worldId: UUID, building: CivBuilding,
        cells: List<ArcSchematicBlock>, previous: CivBuilding?,
    ): CompletableFuture<CivPlacementPlan> = admit {
        requireCivs(cells.size in 1..CivsStore.MAX_PLAN_BLOCKS, "invalid-plan")
        Capture(cityId, owner, worldId, building, cells.toList(), previous)
    }

    /** A new site reserves its marker before the one-time control barrel is installed. */
    fun captureSite(cityId: UUID, owner: UUID, worldId: UUID, building: CivBuilding,
        cells: List<ArcSchematicBlock>, previous: CivBuilding?): CompletableFuture<CivPlacementPlan> = admit {
        requireCivs(cells.size in 1..CivsStore.MAX_PLAN_BLOCKS, "invalid-plan")
        Capture(cityId, owner, worldId, building, cells.toList(), previous, allowControlPlacement = true)
    }

    fun revalidate(plan: CivPlacementPlan, owner: UUID): CompletableFuture<Unit> = admit {
        requireCivs(plan.blocks.size in 1..CivsStore.MAX_PLAN_BLOCKS, "invalid-plan")
        Revalidation(plan.copy(blocks = plan.blocks.toList()), owner)
    }

    /** Exact owner identity, including material-specific Lands role flags. Server thread only. */
    fun canModify(owner: UUID, block: Block, placing: Material? = null): Boolean {
        check(Bukkit.isPrimaryThread()) { "Civs world access requires the server thread" }
        val provider = plugin.server.pluginManager.getPlugin("Lands") ?: return true
        if (!provider.isEnabled) return false
        return try {
            (lands ?: CivsLandsAccess(plugin).also { lands = it }).canModify(owner, block, placing)
        } catch (_: RuntimeException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    override fun close() {
        val cancelled = synchronized(monitor) {
            if (closed) return
            closed = true
            pending.toList().also { pending.clear() }
        }
        timer?.cancel()
        cancelled.forEach { it.future.cancel(false) }
    }

    private fun <T> admit(create: () -> Scan<T>): CompletableFuture<T> = synchronized(monitor) {
        if (closed || timer == null) return@synchronized CompletableFuture.failedFuture(CivsRejection("world-closed"))
        pending.removeIf { it.future.isDone }
        if (pending.size >= MAX_SCANS) return@synchronized CompletableFuture.failedFuture(CivsRejection("world-busy"))
        try {
            create().also { pending += it }.future
        } catch (failure: RuntimeException) {
            CompletableFuture.failedFuture(failure)
        }
    }

    private fun tick() {
        check(Bukkit.isPrimaryThread()) { "Civs world scans require the server thread" }
        val scans = synchronized(monitor) {
            if (closed) return
            pending.removeIf { it.future.isDone }
            pending.toList()
        }
        scans.forEach { scan -> scan.guarded { scan.validateContext() } }
        var remaining = CELLS_PER_TICK
        while (remaining > 0 && scans.any { !it.future.isDone }) {
            for (scan in scans) {
                if (remaining == 0) break
                if (scan.future.isDone) continue
                scan.guarded { scan.step() }
                remaining--
            }
        }
    }

    private abstract inner class Scan<T>(
        val cityId: UUID, val owner: UUID, val worldId: UUID, val building: CivBuilding,
    ) {
        val future = CompletableFuture<T>()
        protected lateinit var world: World
        protected val seen = HashSet<CivPosition>()
        private val palette = HashMap<String, BlockData>()
        private val authoredControl = building.control.takeIf { CivsBlueprints.hasAuthoredControl(building) }
        protected var index = 0

        init {
            requireCivs(civsBoxVolume(building.bounds) in 1..CivsStore.MAX_PLAN_BLOCKS.toLong(), "invalid-building")
            requireCivs(building.entrance.y in -4_095..4_095, "invalid-building")
            authoredControl?.let { requireCivs(building.bounds.contains(it.x, it.y, it.z), "invalid-building") }
        }

        fun validateContext() {
            world = plugin.server.getWorld(worldId) ?: throw CivsRejection("world-unavailable")
            requireCivs(building.bounds.min.y >= world.minHeight && building.bounds.max.y < world.maxHeight &&
                building.entrance.y in world.minHeight until world.maxHeight &&
                (authoredControl?.y?.let { it in world.minHeight until world.maxHeight } != false), "outside-world")
            val border = world.worldBorder
            val min = building.bounds.min
            val max = building.bounds.max
            requireCivs(border.isInside(Location(world, min.x.toDouble(), min.y.toDouble(), min.z.toDouble())) &&
                border.isInside(Location(world, max.x + 1.0 - 1e-6, max.y.toDouble(), max.z + 1.0 - 1e-6)) &&
                border.isInside(Location(world, building.entrance.x + 0.5, building.entrance.y.toDouble(), building.entrance.z + 0.5)) &&
                (authoredControl?.let { border.isInside(Location(world, it.x + 0.5, it.y.toDouble(), it.z + 0.5)) } != false),
                "outside-border")
            val all = cities()
            val city = all.firstOrNull { it.id == cityId }
            requireCivs(city == null || city.owner == owner && city.worldId == worldId, "not-authorized")
            requireCivs(city != null || building.type == BuildingType.TOWN_HALL && building.level == 1, "not-authorized")
            if (city != null) {
                requireCivs(city.contains(min.x, min.z) && city.contains(max.x, max.z) &&
                    city.contains(building.entrance.x, building.entrance.z), "outside-city")
            }
            requireCivs(all.none { it.id != cityId && it.worldId == worldId && civsIntersectsCity(building.bounds, it) }, "city-overlap")
            requireCivs(all.filter { it.worldId == worldId }.none { other ->
                civsProtectedBuildings(other).any {
                    (other.id != cityId || it.id != building.id) && (it.bounds.overlaps(building.bounds) ||
                        it.control?.let { p -> building.bounds.contains(p.x, p.y, p.z) || p == building.control } == true ||
                        building.control?.let { p -> it.bounds.contains(p.x, p.y, p.z) } == true)
                } || other.pendingControls.orEmpty().any { receipt ->
                    (other.id != cityId || receipt.buildingId != building.id) && receipt.approach.orEmpty().any { cell ->
                        building.bounds.contains(cell.position.x, cell.position.y, cell.position.z) || cell.position == building.control
                    }
                }
            }, "building-overlap")
            validateIdentity(city)
        }

        abstract fun validateIdentity(city: Settlement?)
        abstract fun step()

        protected fun read(position: CivPosition): Block {
            requireCivs(position.y in world.minHeight until world.maxHeight, "outside-world")
            requireCivs(world.isChunkLoaded(position.x shr 4, position.z shr 4), "chunks-unloaded")
            return world.getBlockAt(position.x, position.y, position.z)
        }

        protected fun rejectAt(reason: String, block: Block): Nothing = throw CivsRejection(reason, mapOf(
            "block" to block.type.name,
            "x" to block.x.toString(), "y" to block.y.toString(), "z" to block.z.toString(),
            "world" to block.world.uid.toString(), "world_name" to block.world.name,
        ))

        protected fun checkCell(position: CivPosition) {
            requireCivs(building.bounds.contains(position.x, position.y, position.z) && seen.add(position), "invalid-plan")
        }

        protected fun parse(text: String): BlockData = palette.getOrPut(text) {
            requireCivs(text.length in 1..CivsStore.MAX_BLOCK_STATE_CHARS, "invalid-plan")
            try { Bukkit.createBlockData(text) } catch (_: IllegalArgumentException) { throw CivsRejection("invalid-plan") }
        }

        protected open fun checkControl(block: Block) {
            if (block.type != Material.BARREL) rejectAt("control-position-missing", block)
            if (!canModify(owner, block, Material.BARREL)) rejectAt("lands-denied", block)
        }

        /** Only the preserved control needs a separate read; approach terrain is not part of construction. */
        protected fun checkPreservedControl() {
            val position = building.control ?: return
            val block = read(position)
            if (authoredControl != null) return checkControl(block)
            // Legacy controls can lie outside the old footprint and still must not replace player data.
            if (block.type != Material.BARREL && (block.state is TileState || block.type == Material.BEDROCK))
                rejectAt("control-obstructed", block)
            if (!canModify(owner, block, Material.BARREL)) rejectAt("lands-denied", block)
        }

        protected fun finish(value: T) {
            validateContext()
            synchronized(monitor) { pending.remove(this) }
            future.complete(value)
        }

        fun guarded(action: () -> Unit) {
            if (future.isDone) return
            try { action() } catch (failure: Exception) {
                synchronized(monitor) { pending.remove(this) }
                future.completeExceptionally(failure)
            } catch (failure: LinkageError) {
                synchronized(monitor) { pending.remove(this) }
                future.completeExceptionally(failure)
            }
        }
    }

    private inner class Capture(
        cityId: UUID, owner: UUID, worldId: UUID, building: CivBuilding,
        private val cells: List<ArcSchematicBlock>, private val previous: CivBuilding?,
        private val allowControlPlacement: Boolean = false,
    ) : Scan<CivPlacementPlan>(cityId, owner, worldId, building) {
        private val blocks = ArrayList<CivPlanBlock>()

        init { requireCivs(cells.size.toLong() == civsBoxVolume(building.bounds), "invalid-plan") }

        override fun checkControl(block: Block) {
            if (!allowControlPlacement || block.type == Material.BARREL) return super.checkControl(block)
            if (block.type == Material.BEDROCK || block.state is TileState) rejectAt("control-obstructed", block)
            if (!canModify(owner, block, Material.BARREL)) rejectAt("lands-denied", block)
        }

        override fun validateIdentity(city: Settlement?) {
            requireCivs(city?.building(building.id) == previous, "invalid-building")
            if (previous != null) requireCivs(previous.id == building.id && previous.type == building.type &&
                previous.entrance == building.entrance && previous.rotation == building.rotation &&
                (previous.level + 1 == building.level && previous.phase == BuildingPhase.ACTIVE ||
                    previous.level == building.level && previous.phase == BuildingPhase.SITE), "invalid-building")
            if (previous != null) requireCivs(civsContainsBox(building.bounds, previous.bounds), "upgrade-footprint-changed")
        }

        override fun step() {
            if (index == cells.size) {
                checkPreservedControl()
                requireCivs(blocks.isNotEmpty(), "invalid-plan")
                finish(CivPlacementPlan(UUID.randomUUID(), cityId, worldId, building, blocks.toList()))
                return
            }
            val cell = cells[index++]
            val position = CivPosition(cell.position.x, cell.position.y, cell.position.z)
            checkCell(position)
            // Even skipped barrier cells must belong to loaded chunks; Core skips these exact cells.
            val block = read(position)
            if (cell.isBarrier) {
                requireCivs(parse(cell.blockData).material == Material.BARRIER, "invalid-plan")
                if (!canModify(owner, block)) rejectAt("lands-denied", block)
                return
            }
            if (position == building.control && parse(cell.blockData).material == Material.STRUCTURE_VOID) {
                requireCivs(!cell.isAir, "invalid-plan")
                checkControl(block)
                return
            }
            if (block.type == Material.BEDROCK || previous?.bounds?.contains(position.x, position.y, position.z) != true &&
                block.state is TileState) rejectAt("protected-terrain", block)
            // Never replace a player's inventory, including chests installed while a SITE was waiting.
            if ((block.state as? org.bukkit.block.Container)?.inventory?.isEmpty == false) rejectAt("container-not-empty", block)
            if (cities().firstOrNull { it.id == cityId }?.inventory?.containers?.contains(position) == true)
                rejectAt("registered-container", block)
            val after = parse(cell.blockData)
            requireCivs(after.material != Material.BARRIER && cell.isAir == after.material.isAir, "invalid-plan")
            if (!canModify(owner, block, after.material)) rejectAt("lands-denied", block)
            blocks += CivPlanBlock(position, block.blockData.asString, after.asString)
        }
    }

    private inner class Revalidation(private val plan: CivPlacementPlan, owner: UUID) :
        Scan<Unit>(plan.cityId, owner, plan.worldId, plan.building) {
        init {
            requireCivs(!CivsBlueprints.hasAuthoredControl(building) || plan.blocks.none { it.position == building.control }, "invalid-plan")
        }
        override fun validateIdentity(city: Settlement?) {
            val current = city?.building(building.id)
            requireCivs(current == null || current == building || current.type == building.type &&
                current.entrance == building.entrance && current.rotation == building.rotation &&
                current.level + 1 == building.level && current.phase == BuildingPhase.ACTIVE, "invalid-building")
            if (current != null) requireCivs(civsContainsBox(building.bounds, current.bounds), "upgrade-footprint-changed")
            city?.construction?.let {
                requireCivs(it.id == plan.id && it.buildingId == building.id && it.plannedBuilding == building, "invalid-plan")
            }
        }

        override fun step() {
            if (index == plan.blocks.size) {
                checkPreservedControl()
                finish(Unit)
                return
            }
            val cell = plan.blocks[index++]
            checkCell(cell.position)
            val block = read(cell.position)
            if (block.type == Material.BEDROCK) rejectAt("protected-terrain", block)
            if ((block.state as? org.bukkit.block.Container)?.inventory?.isEmpty == false) rejectAt("container-not-empty", block)
            val current = block.blockData.asString
            if (current != cell.before && current != cell.after) rejectAt("world-changed", block)
            val after = parse(cell.after)
            requireCivs(after.material != Material.BARRIER, "invalid-plan")
            if (!canModify(owner, block, after.material)) rejectAt("lands-denied", block)
        }
    }

    companion object {
        const val CELLS_PER_TICK = 256
        const val MAX_SCANS = 4
    }
}

/** Loaded only when the optional Lands provider is present; no reflective integration. */
private class CivsLandsAccess(plugin: Plugin) {
    private val integration = LandsIntegration.of(plugin)
    fun canModify(owner: UUID, block: Block, placing: Material?): Boolean {
        val world = integration.getWorld(block.world) ?: return true
        val location = block.location
        if (world.getArea(location) == null) return true
        val player = integration.getLandPlayer(owner) ?: return false
        return (block.type.isAir || world.hasRoleFlag(player, location, Flags.BLOCK_BREAK, block.type, false)) &&
            (placing == null || world.hasRoleFlag(player, location, Flags.BLOCK_PLACE, placing, false))
    }
}

internal fun civsBoxVolume(box: CivBox): Long {
    val lengths = listOf(box.max.x.toLong() - box.min.x + 1, box.max.y.toLong() - box.min.y + 1, box.max.z.toLong() - box.min.z + 1)
    if (lengths.any { it !in 1..CivsStore.MAX_PLAN_BLOCKS.toLong() }) return 0
    return lengths.fold(1L) { volume, length -> (volume * length).coerceAtMost(CivsStore.MAX_PLAN_BLOCKS.toLong() + 1) }
}

internal fun civsContainsBox(outer: CivBox, inner: CivBox): Boolean =
    outer.contains(inner.min.x, inner.min.y, inner.min.z) && outer.contains(inner.max.x, inner.max.y, inner.max.z)

/** Radius separates city plans; event protection below uses building boxes only. */
internal fun civsIntersectsCity(box: CivBox, city: Settlement): Boolean =
    box.min.x.toLong() <= city.center.x.toLong() + city.radius && box.max.x.toLong() >= city.center.x.toLong() - city.radius &&
        box.min.z.toLong() <= city.center.z.toLong() + city.radius && box.max.z.toLong() >= city.center.z.toLong() - city.radius

internal fun civsProtectedBuildings(city: Settlement): Sequence<CivBuilding> = sequence {
    yieldAll(city.buildings)
    city.construction?.plannedBuilding?.let { yield(it) }
}

private fun requireCivs(condition: Boolean, reason: String) { if (!condition) throw CivsRejection(reason) }
