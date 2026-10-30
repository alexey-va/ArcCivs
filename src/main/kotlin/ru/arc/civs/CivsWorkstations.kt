package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.logging.Level
import java.util.logging.Logger

/** Detached authored work position; a fallback has no machine to face or revalidate. */
data class CivWorkstation(val standing: CivPosition, val focus: CivPosition? = null, val material: String? = null)

/** At most 64 small source results; no decoded schematic or native block survives a lookup. */
internal class CivsWorkstations(
    private val load: (CivBuilding) -> CompletableFuture<List<CivWorkstation>>,
    private val logger: Logger,
    private val world: (UUID) -> World? = Bukkit::getWorld,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private class Entry(val building: CivBuilding, val result: CompletableFuture<List<CivWorkstation>>) {
        var retryAt = 0L
    }
    private val entries = linkedMapOf<Pair<UUID, UUID>, Entry>()
    private var closed = false

    /** Called for a new admission only; the resident retains its accepted station until its cycle ends. */
    fun select(city: Settlement, worker: CivWorker): CivWorkstation? {
        checkCitizenMainThread()
        val building = worker.assignment?.let(city::building)?.takeIf {
            !closed && it.phase == BuildingPhase.ACTIVE && it.type in TYPES
        } ?: return null
        val key = city.worldId to building.id
        val now = clock()
        var entry = entries[key]
        if (entry == null || entry.building != building || entry.retryAt != 0L && now >= entry.retryAt) {
            entries.remove(key)?.result?.cancel(false)
            if (entries.size >= MAXIMUM_ENTRIES) entries.entries.first().let { oldest ->
                oldest.value.result.cancel(false)
                entries.remove(oldest.key)
            }
            val future = try { load(building) } catch (failure: Exception) { CompletableFuture.failedFuture(failure) }
            entry = Entry(building, future)
            entries[key] = entry
        }
        if (!entry.result.isDone || entry.retryAt != 0L) return null
        val candidates = try { entry.result.getNow(emptyList()).take(MAXIMUM_POINTS) } catch (failure: Exception) {
            val cause = (failure as? CompletionException)?.cause ?: failure
            entry.retryAt = now + 30_000L
            logger.log(Level.WARNING, "ArcCivs workstation source failed city=${city.id} building=${building.id} " +
                "schematic=${building.schematicId} retry_ms=30000", cause)
            return null
        }
        val nativeWorld = world(city.worldId) ?: return null
        return ordered(candidates, worker).take(MAXIMUM_PROBES).firstOrNull { valid(city, building, nativeWorld, it) }
    }

    override fun close() {
        checkCitizenMainThread()
        closed = true
        entries.values.forEach { it.result.cancel(false) }
        entries.clear()
    }

    companion object {
        internal const val MAXIMUM_POINTS = 32
        internal const val MAXIMUM_PROBES = 8
        private const val MAXIMUM_ENTRIES = 64
        val TYPES = setOf(BuildingType.SMELTERY, BuildingType.CRUSHER, BuildingType.BLACKSMITH,
            BuildingType.SAWMILL, BuildingType.STONEMASON, BuildingType.BAKERY, BuildingType.LIBRARY)

        private val stations = mapOf(
            BuildingType.SMELTERY to listOf("furnace", "blast_furnace", "crafting_table"),
            BuildingType.CRUSHER to listOf("grindstone", "stonecutter", "crafting_table"),
            BuildingType.BLACKSMITH to listOf("smithing_table", "anvil", "chipped_anvil", "damaged_anvil", "crafting_table"),
            BuildingType.SAWMILL to listOf("stonecutter", "crafting_table", "fletching_table"),
            BuildingType.STONEMASON to listOf("stonecutter", "crafting_table"),
            BuildingType.BAKERY to listOf("smoker", "furnace", "crafting_table"),
            BuildingType.LIBRARY to listOf("lectern", "crafting_table", "bookshelf"),
        )
        private val furniture = stations.values.flatten().toSet() + setOf("barrel", "chest", "trapped_chest", "hopper")
        private val directions = listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)
        private val operatingOffsets = directions + listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1) + directions.map { (x, z) -> x * 2 to z * 2 }

        /** Pure immutable-snapshot work, always invoked on the blueprint executor. */
        internal fun authored(building: CivBuilding, snapshot: ArcSchematicSnapshot): List<CivWorkstation> =
            authored(CivsAuthoredInterior(building, snapshot, furniture), false)

        internal fun authoredControls(building: CivBuilding, snapshot: ArcSchematicSnapshot): List<CivControlCandidate> {
            val marker = CivsBlueprintGeometry.controlMarker(snapshot)
            val original = CivsWorksiteLayouts.point(building, CivPosition(marker.x - snapshot.origin.x,
                marker.y - snapshot.origin.y, marker.z - snapshot.origin.z))
            // A saved override is stable; never move an interior control again or alter the authored pivot.
            if (building.control != original) return emptyList()
            val interior = CivsAuthoredInterior(building, snapshot, furniture)
            val existing = authored(interior, true).map { CivControlCandidate(requireNotNull(it.focus), it.standing, true) }
            val avoid = authored(interior, false).flatMap { listOfNotNull(it.standing, it.focus) }
            val possible = interior.reachable.asSequence().filter { p -> interior.interior(p) && interior.name(p) == "air" &&
                kotlin.math.abs(p.x - interior.entrance.x) + kotlin.math.abs(p.z - interior.entrance.z) >= 4 &&
                interior.wallScore(p) > 0 && avoid.none { work -> interior.world(p).let {
                    kotlin.math.abs(it.y.toLong() - work.y) <= 1 && kotlin.math.abs(it.x.toLong() - work.x) + kotlin.math.abs(it.z.toLong() - work.z) < 2
                } } }.sortedWith(compareBy({ kotlin.math.abs(it.y - interior.entrance.y) },
                    { -interior.wallScore(it) }, { kotlin.math.abs(it.x - snapshot.origin.x) + kotlin.math.abs(it.z - (snapshot.minimum.z + snapshot.maximum.z) / 2) },
                    { it.x }, { it.z })).take(64).toList()
            val spare = possible.mapNotNull { point ->
                if (!interior.connectedWithout(point)) return@mapNotNull null
                val standing = interior.neighbors(point).firstOrNull(interior::interior)
                    ?: return@mapNotNull null
                CivControlCandidate(interior.world(point), interior.world(standing), false)
            }.take(MAXIMUM_POINTS)
            return if (spare.isEmpty()) existing else (existing.take(MAXIMUM_POINTS / 2) + spare).take(MAXIMUM_POINTS)
        }

        private fun authored(interior: CivsAuthoredInterior, controlBarrels: Boolean): List<CivWorkstation> {
            val building = interior.building
            val snapshot = interior.snapshot
            val materials = if (controlBarrels) listOf("barrel") else stations[building.type] ?: return emptyList()
            val cells = interior.cells
            val entrance = interior.entrance
            val machines = cells.filter { cell -> cell.blockData.substringBefore('[').removePrefix("minecraft:") in materials &&
                (!controlBarrels || cell.position.let { p ->
                    p.x > snapshot.minimum.x && p.x < snapshot.maximum.x && p.z > snapshot.minimum.z && p.z < snapshot.maximum.z &&
                        kotlin.math.abs(p.x - entrance.x) + kotlin.math.abs(p.z - entrance.z) >= 3
                }) }
                .sortedWith(compareBy({ materials.indexOf(it.blockData.substringBefore('[').removePrefix("minecraft:")) },
                    { it.position.y }, { it.position.z }, { it.position.x }))
            val result = linkedSetOf<CivWorkstation>()
            // Keep several actual machines before filling the bounded list with alternate standing sides.
            val sides = machines.map { cell -> operatingOffsets.flatMap { (dx, dz) -> listOf(0, -1, 1).map { dy ->
                cell.position.let { p -> p.copy(x = p.x + dx, y = p.y + dy, z = p.z + dz) }
            } }.filter(interior::interior).map { CivWorkstation(interior.world(it), interior.world(cell.position), cell.blockData.substringBefore('[')) } }
            val preferred = sides.filter { side -> side.firstOrNull()?.material?.substringAfter(':') !in setOf(null, "crafting_table", "fletching_table") }
            for (side in 0 until operatingOffsets.size * 3) for (machine in preferred.ifEmpty { sides }) machine.getOrNull(side)?.let { result.add(it) }
            if (result.isEmpty() && !controlBarrels) interior.reachable.asSequence().filter(interior::interior)
                .sortedWith(compareBy({ kotlin.math.abs(it.y - entrance.y) },
                    { (it.x - entrance.x).toLong() * (it.x - entrance.x) + (it.z - entrance.z).toLong() * (it.z - entrance.z) },
                    { it.x }, { it.z }))
                .take(MAXIMUM_POINTS).mapTo(result) { CivWorkstation(interior.world(it)) }
            return result.take(MAXIMUM_POINTS)
        }

        /** Rotate machines first, then their standing sides, once per committed production cycle. */
        internal fun ordered(candidates: List<CivWorkstation>, worker: CivWorker): List<CivWorkstation> {
            val groups = candidates.groupBy(CivWorkstation::focus).values.toList()
            if (groups.isEmpty()) return emptyList()
            val first = (Math.floorMod(worker.id.hashCode(), groups.size) + Math.floorMod(worker.completedCycles, groups.size.toLong()).toInt()) % groups.size
            return (groups.indices).flatMap { offset ->
                val group = groups[(first + offset) % groups.size]
                val side = (Math.floorMod(worker.id.hashCode(), group.size) +
                    Math.floorMod(worker.completedCycles / groups.size, group.size.toLong()).toInt()) % group.size
                group.indices.map { group[(side + it) % group.size] }
            }
        }

        internal fun valid(city: Settlement, building: CivBuilding, world: World, station: CivWorkstation): Boolean {
            val p = station.standing
            if (world.uid != city.worldId || !city.contains(p.x, p.z) ||
                !building.bounds.contains(p.x, p.y, p.z) || !building.bounds.contains(p.x, p.y + 1, p.z) ||
                city.buildings.any { it.id != building.id && it.bounds.contains(p.x, p.y, p.z) } ||
                !citizenControlClear(city, p) || !citizenChunkReady(world, p.x shr 4, p.z shr 4)) return false
            val support = world.getBlockAt(p.x, p.y - 1, p.z).type
            if (support == Material.BARREL || support == Material.CHEST || support == Material.TRAPPED_CHEST ||
                support in CITIZEN_LEISURE_HAZARDS || !citizenStandable(world, p) || !citizenPacketBodyClear(world, p.point(), false)) return false
            val focus = station.focus ?: return station.material == null
            return station.material != null && building.bounds.contains(focus.x, focus.y, focus.z) &&
                citizenChunkReady(world, focus.x shr 4, focus.z shr 4) &&
                world.getBlockAt(focus.x, focus.y, focus.z).type.key.toString() == station.material &&
                citizenCanReachBlock(world, p.location(world).add(0.0, 1.5, 0.0), focus)
        }
    }
}
