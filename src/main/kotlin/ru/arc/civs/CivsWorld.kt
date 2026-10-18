package ru.arc.civs

import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.flags.type.Flags
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockSupport
import org.bukkit.block.TileState
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Waterlogged
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
class CivsWorld internal constructor(
    private val plugin: Plugin,
    tasks: LifecycleTaskScope,
    private val cities: () -> Collection<Settlement>,
    private val entranceSafety: (BlockData, Block, Boolean) -> Boolean,
) : AutoCloseable {
    constructor(plugin: Plugin, tasks: LifecycleTaskScope, cities: () -> Collection<Settlement>) :
        this(plugin, tasks, cities, ::civsNativeEntranceSafe)

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
        private val entranceCells = listOf(
            building.entrance.copy(y = building.entrance.y - 1),
            building.entrance,
            building.entrance.copy(y = building.entrance.y + 1),
        )
        private val predictedEntrance = HashMap<CivPosition, BlockData>()
        private var entranceIndex = 0
        protected var index = 0

        init {
            requireCivs(civsBoxVolume(building.bounds) in 1..CivsStore.MAX_PLAN_BLOCKS.toLong(), "invalid-building")
            requireCivs(building.entrance.y in -4_095..4_095, "invalid-building")
        }

        fun validateContext() {
            world = plugin.server.getWorld(worldId) ?: throw CivsRejection("world-unavailable")
            requireCivs(building.bounds.min.y >= world.minHeight && building.bounds.max.y < world.maxHeight &&
                entranceCells.all { it.y in world.minHeight until world.maxHeight }, "outside-world")
            val border = world.worldBorder
            val min = building.bounds.min
            val max = building.bounds.max
            requireCivs(border.isInside(Location(world, min.x.toDouble(), min.y.toDouble(), min.z.toDouble())) &&
                border.isInside(Location(world, max.x + 1.0 - 1e-6, max.y.toDouble(), max.z + 1.0 - 1e-6)) &&
                border.isInside(Location(world, building.entrance.x + 0.5, building.entrance.y.toDouble(), building.entrance.z + 0.5)),
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

        protected fun predict(position: CivPosition, after: BlockData) {
            if (position in entranceCells) predictedEntrance[position] = after
        }

        /** One additional world cell per step, including an entrance outside the schematic box. */
        protected fun checkEntrance(): Boolean {
            val position = entranceCells[entranceIndex]
            // Paper's predicted collision-shape query may read neighbors; never let it reach an unloaded chunk.
            val chunkX = position.x shr 4
            val chunkZ = position.z shr 4
            requireCivs((-1..1).all { dx -> (-1..1).all { dz -> world.isChunkLoaded(chunkX + dx, chunkZ + dz) } }, "chunks-unloaded")
            val block = read(position)
            val data = predictedEntrance[position] ?: block.blockData
            if (position == building.control) {
                if (block.type != Material.BARREL && (block.state is TileState || block.type in setOf(Material.BEDROCK, Material.LAVA)))
                    rejectAt("control-obstructed", block)
                if (!canModify(owner, block, Material.BARREL)) rejectAt("lands-denied", block)
            }
            if (!entranceSafety(data, block, entranceIndex == 0)) rejectAt("entrance-blocked", block)
            entranceIndex++
            return entranceIndex == entranceCells.size
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
    ) : Scan<CivPlacementPlan>(cityId, owner, worldId, building) {
        private val blocks = ArrayList<CivPlanBlock>()

        init { requireCivs(cells.size.toLong() == civsBoxVolume(building.bounds), "invalid-plan") }

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
                if (checkEntrance()) {
                    requireCivs(blocks.isNotEmpty(), "invalid-plan")
                    finish(CivPlacementPlan(UUID.randomUUID(), cityId, worldId, building, blocks.toList()))
                }
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
            if (previous?.bounds?.contains(position.x, position.y, position.z) != true &&
                (!civsNaturalTerrain(block.type) || block.state is TileState)) rejectAt("protected-terrain", block)
            // Never replace a player's inventory, including chests installed while a SITE was waiting.
            if ((block.state as? org.bukkit.block.Container)?.inventory?.isEmpty == false) rejectAt("container-not-empty", block)
            if (cities().firstOrNull { it.id == cityId }?.inventory?.containers?.contains(position) == true)
                rejectAt("registered-container", block)
            val after = parse(cell.blockData)
            requireCivs(after.material != Material.BARRIER && cell.isAir == after.material.isAir, "invalid-plan")
            if (!canModify(owner, block, after.material)) rejectAt("lands-denied", block)
            blocks += CivPlanBlock(position, block.blockData.asString, after.asString)
            predict(position, after)
        }
    }

    private inner class Revalidation(private val plan: CivPlacementPlan, owner: UUID) :
        Scan<Unit>(plan.cityId, owner, plan.worldId, plan.building) {
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
                if (checkEntrance()) finish(Unit)
                return
            }
            val cell = plan.blocks[index++]
            checkCell(cell.position)
            val block = read(cell.position)
            if ((block.state as? org.bukkit.block.Container)?.inventory?.isEmpty == false) rejectAt("container-not-empty", block)
            val current = block.blockData.asString
            if (current != cell.before && current != cell.after) rejectAt("world-changed", block)
            val after = parse(cell.after)
            requireCivs(after.material != Material.BARRIER, "invalid-plan")
            if (!canModify(owner, block, after.material)) rejectAt("lands-denied", block)
            predict(cell.position, after)
        }
    }

    companion object {
        const val CELLS_PER_TICK = 256
        const val MAX_SCANS = 4
    }
}

/** Native shape queries are intentionally kept at a narrow seam: MockBukkit does not implement them. */
private fun civsNativeEntranceSafe(data: BlockData, block: Block, support: Boolean): Boolean =
    if (support) data.isFaceSturdy(BlockFace.UP, BlockSupport.CENTER)
    else data.material != Material.WATER && data.material != Material.LAVA &&
        (data !is Waterlogged || !data.isWaterlogged) && data.getCollisionShape(block.location).boundingBoxes.isEmpty()

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

/** Material policy only: placed terrain and unstripped logs have no reliable natural-provenance marker. */
internal fun civsNaturalTerrain(material: Material): Boolean = material.isAir || material.name in NATURAL_TERRAIN ||
    material.name.endsWith("_LEAVES") || material.name.endsWith("_SAPLING") || material.name.endsWith("_ORE") ||
    material.name.endsWith("_LOG") && !material.name.startsWith("STRIPPED_")

private val NATURAL_TERRAIN = setOf(
    "STONE", "GRANITE", "DIORITE", "ANDESITE", "DEEPSLATE", "TUFF", "CALCITE", "DIRT", "GRASS_BLOCK",
    "COARSE_DIRT", "ROOTED_DIRT", "PODZOL", "MYCELIUM", "MUD", "CLAY", "SAND", "RED_SAND", "GRAVEL",
    "MOSS_BLOCK", "PALE_MOSS_BLOCK", "MOSS_CARPET", "PALE_MOSS_CARPET", "SNOW", "SNOW_BLOCK",
    "WATER", "ICE", "PACKED_ICE", "BLUE_ICE", "POWDER_SNOW", "SEAGRASS", "TALL_SEAGRASS", "KELP", "KELP_PLANT", "LILY_PAD",
    "CRIMSON_STEM", "WARPED_STEM", "MANGROVE_ROOTS", "MUDDY_MANGROVE_ROOTS", "COCOA", "MELON", "PUMPKIN", "SWEET_BERRY_BUSH",
    "NETHERRACK", "SOUL_SAND", "SOUL_SOIL", "BASALT", "BLACKSTONE", "END_STONE",
    "SHORT_GRASS", "TALL_GRASS", "FERN", "LARGE_FERN", "DEAD_BUSH", "BUSH", "FIREFLY_BUSH",
    "SHORT_DRY_GRASS", "TALL_DRY_GRASS", "VINE", "CAVE_VINES", "CAVE_VINES_PLANT", "GLOW_LICHEN",
    "HANGING_ROOTS", "PALE_HANGING_MOSS", "SPORE_BLOSSOM", "BAMBOO", "BAMBOO_SAPLING", "SUGAR_CANE",
    "CACTUS", "CACTUS_FLOWER", "DANDELION", "POPPY", "BLUE_ORCHID", "ALLIUM", "AZURE_BLUET",
    "RED_TULIP", "ORANGE_TULIP", "WHITE_TULIP", "PINK_TULIP", "OXEYE_DAISY", "CORNFLOWER",
    "LILY_OF_THE_VALLEY", "SUNFLOWER", "LILAC", "ROSE_BUSH", "PEONY", "PINK_PETALS", "WILDFLOWERS",
    "LEAF_LITTER", "BROWN_MUSHROOM", "RED_MUSHROOM", "BROWN_MUSHROOM_BLOCK", "RED_MUSHROOM_BLOCK",
    "MUSHROOM_STEM", "AZALEA", "FLOWERING_AZALEA", "SMALL_DRIPLEAF", "BIG_DRIPLEAF", "BIG_DRIPLEAF_STEM",
    "CRIMSON_FUNGUS", "WARPED_FUNGUS", "CRIMSON_ROOTS", "WARPED_ROOTS", "NETHER_SPROUTS",
    "WEEPING_VINES", "WEEPING_VINES_PLANT", "TWISTING_VINES", "TWISTING_VINES_PLANT",
)

private fun requireCivs(condition: Boolean, reason: String) { if (!condition) throw CivsRejection(reason) }
