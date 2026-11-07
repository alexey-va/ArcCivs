package ru.arc.civs

import org.bukkit.ChunkSnapshot
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.entity.LivingEntity
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlin.math.abs

/** The settlement interior, including existing outlying buildings; the surrounding forestry ring stays productive. */
internal fun civsVillageVegetationArea(city: Settlement): CivBox? {
    val autonomy = city.autonomy ?: return null
    val built = civsProtectedBuildings(city).maxOfOrNull { b -> maxOf(
        abs(b.bounds.min.x.toLong() - city.center.x), abs(b.bounds.max.x.toLong() - city.center.x),
        abs(b.bounds.min.z.toLong() - city.center.z), abs(b.bounds.max.z.toLong() - city.center.z)) } ?: 0L
    val half = maxOf(autonomy.plan?.halfWidth ?: 0, autonomy.layoutHalfWidth ?: 0, (built + 2).toInt())
        .coerceAtMost(city.radius)
    return CivBox(city.center.copy(x = city.center.x - half, y = -4096, z = city.center.z - half),
        city.center.copy(x = city.center.x + half, y = 4096, z = city.center.z + half))
}

internal fun civsVillageVegetationColumn(city: Settlement, x: Int, z: Int): Boolean =
    civsVillageVegetationArea(city)?.let { x in it.min.x..it.max.x && z in it.min.z..it.max.z } == true

internal data class CivVegetationCell(val position: CivPosition, val before: String, val log: Boolean, val leaf: Boolean)

private data class CivVegetationRemnant(val logs: List<CivVegetationCell>, val air: Set<CivPosition>)

private data class CivVegetationSelection(val cells: List<CivVegetationCell>,
    val proofParents: Map<CivPosition, CivPosition>, val stumps: Set<CivPosition> = emptySet(),
    val remnants: Map<CivPosition, CivVegetationRemnant> = emptyMap())

/** A bare, upright, unowned root on natural soil; attached timber and supports remain intact. */
internal fun civsVillageVegetationStump(cell: CivVegetationCell, typeAt: (CivPosition) -> Material?): Boolean {
    val material = CivsForestry.material(cell.before)
    if (!cell.log || !material.endsWith("_LOG") || material.startsWith("STRIPPED_") || "axis=y" !in cell.before) return false
    val p = cell.position
    if (typeAt(p.copy(y = p.y - 1))?.name !in CivsForestry.soils ||
        typeAt(p.copy(y = p.y + 1))?.isAir != true || typeAt(p.copy(y = p.y + 2))?.isAir != true) return false
    return (-1..1).all { dx -> (-1..1).all { dz ->
        dx == 0 && dz == 0 || typeAt(p.copy(x = p.x + dx, z = p.z + dz))?.isSolid == false
    } }
}

private fun civsVegetationProtectionMatches(before: Settlement, after: Settlement): Boolean =
    before.worldId == after.worldId && before.center == after.center && before.radius == after.radius &&
        before.buildings == after.buildings && before.construction?.id == after.construction?.id &&
        before.construction?.plannedBuilding == after.construction?.plannedBuilding &&
        before.autonomy?.plan == after.autonomy?.plan && before.autonomy?.layoutHalfWidth == after.autonomy?.layoutHalfWidth &&
        before.roadRouteOverrides == after.roadRouteOverrides && before.pendingControls == after.pendingControls

/** No drops, resource credits, player data or city revisions. Repeating an interrupted sweep is idempotent. */
internal class CivsVillageVegetation(
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    private val executor: Executor,
    private val loadPlans: (Settlement) -> CompletableFuture<List<CivPlacementPlan>>,
    private val city: (UUID) -> Settlement?,
    private val canModify: (Settlement, org.bukkit.block.Block) -> Boolean,
    private val locked: (UUID, CivPosition) -> Boolean,
    private val occupied: (UUID, CivPosition) -> Boolean,
    private val changed: (UUID, Collection<CivPosition>) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    private data class Frame(val city: Settlement, val area: CivBox, val minimumY: Int, val maximumY: Int,
        val chunks: Map<Pair<Int, Int>, ChunkSnapshot>, val plans: List<CivPlacementPlan>,
        val logs: Set<Material>, val leaves: Set<Material>, val productiveGroves: Set<Pair<Int, Int>>,
        val reserved: Set<CivPosition>)
    private data class Sweep(val city: Settlement, val selection: CivVegetationSelection, val reserved: Set<CivPosition>,
        val deferred: MutableSet<CivPosition> = hashSetOf(),
        var next: Int = 0, var removed: Int = 0, var skipped: Int = 0)
    private val epoch = tasks.token()
    private val nextSweep = mutableMapOf<UUID, Long>()
    private var preparing = false
    private var sweep: Sweep? = null
    private var closed = false

    fun pulse(cities: Collection<Settlement>) {
        if (closed) return
        sweep?.let { apply(it); return }
        if (preparing) return
        val now = clock()
        val selected = cities.filter { it.autonomy != null && it.pendingDeletion == null &&
            now >= (nextSweep[it.id] ?: 0) }.minByOrNull { nextSweep[it.id] ?: 0 } ?: return
        nextSweep[selected.id] = now + 120_000
        preparing = true
        loadPlans(selected).whenCompleteSync(tasks) loaded@ { plans, failure ->
            if (closed) return@loaded
            if (failure != null) { preparing = false; return@loaded }
            val current = city(selected.id)
            if (current == null || current.pendingDeletion != null || !civsVegetationProtectionMatches(selected, current)) {
                preparing = false; return@loaded
            }
            val world = plugin.server.getWorld(current.worldId)
            val area = civsVillageVegetationArea(current)
            if (world == null || area == null) { preparing = false; return@loaded }
            // Copy existing chunks, never generate or synchronously load a surrounding forest.
            val chunks = runCatching { buildMap {
                for (cx in (area.min.x shr 4)..(area.max.x shr 4)) for (cz in (area.min.z shr 4)..(area.max.z shr 4))
                    if (world.isChunkLoaded(cx, cz)) put(cx to cz,
                        world.getChunkAt(cx, cz).getChunkSnapshot(true, false, false, false))
            } }.getOrElse { failure ->
                preparing = false
                CivsDiagnostics.event("vegetation_sweep_failed", "city" to current.id,
                    "failure" to failure.javaClass.simpleName)
                return@loaded
            }
            val retained = requireNotNull(plans)
            val reserved = retained.filter { it.id == current.construction?.id }.flatMap {
                (it.blocks + it.terrain?.blocks.orEmpty()).map { block -> block.position }
            }.toHashSet()
            // The initial approach owner must publish its exact preimages before changing a SITE.
            // Its entrance can lie outside the building box, including a temporary leaf support.
            civsProtectedBuildings(current).filter { it.phase != BuildingPhase.ACTIVE }.forEach { building ->
                CivsAutonomousGeometry.approach(building).forEach { feet ->
                    for (dy in -1..2) reserved += feet.copy(y = feet.y + dy)
                }
            }
            current.pendingControls.orEmpty().forEach { pending ->
                reserved += pending.position
                pending.retire?.let { reserved += it.position }
                reserved += pending.approach.orEmpty().map { it.position }
            }
            val streets = (retained.flatMap { it.terrain?.route.orEmpty() } + current.buildings.flatMap {
                CivsAutonomousGeometry.approach(it) + it.transit.orEmpty()
            }).flatMap { p -> (-2..2).flatMap { dx -> (-2..2).map { dz -> p.x + dx to p.z + dz } } }.toHashSet()
            val groves = buildSet {
                for (building in current.buildings.filter { it.type == BuildingType.FORESTER }) {
                    val bounds = CivsWorksiteLayouts.site(building)?.getAsJsonObject("forester")
                        ?.getAsJsonObject("searchBounds") ?: continue
                    val min = CivsWorksiteLayouts.vector(bounds["min"])
                    val max = CivsWorksiteLayouts.vector(bounds["max"])
                    for (x in min.x..max.x) for (z in min.z..max.z) {
                        val p = CivsWorksiteLayouts.point(building, CivPosition(x, 0, z))
                        if ((p.x to p.z) !in streets && current.buildings.none { b ->
                            p.x.toLong() in (b.bounds.min.x.toLong() - 2)..(b.bounds.max.x.toLong() + 2) &&
                                p.z.toLong() in (b.bounds.min.z.toLong() - 2)..(b.bounds.max.z.toLong() + 2)
                        }) add(p.x to p.z)
                    }
                }
            }
            val frame = Frame(current, area, world.minHeight, world.maxHeight, chunks, retained,
                Tag.LOGS.values.toSet(), Tag.LEAVES.values.toSet(), groves, reserved)
            val pending = try { CompletableFuture.supplyAsync({ capture(frame) }, executor) }
            catch (failure: RejectedExecutionException) {
                preparing = false
                nextSweep[current.id] = clock() + 1_000
                CivsDiagnostics.event("vegetation_sweep_failed", "city" to current.id,
                    "failure" to failure.javaClass.simpleName)
                return@loaded
            }
            pending.whenCompleteSync(tasks) captured@ { selection, error ->
                preparing = false
                if (closed || !tasks.isCurrent(epoch)) return@captured
                if (error != null) {
                    CivsDiagnostics.event("vegetation_sweep_failed", "city" to selected.id,
                        "failure" to error.javaClass.simpleName)
                } else sweep = Sweep(current, requireNotNull(selection), reserved)
            }
        }
    }

    private fun capture(frame: Frame): CivVegetationSelection {
        val logs = frame.logs.mapTo(hashSetOf()) { "minecraft:${it.name.lowercase()}" }
        val leaves = frame.leaves.mapTo(hashSetOf()) { "minecraft:${it.name.lowercase()}" }
        val expected = frame.plans.associateBy { it.building.id }
        val authored = frame.plans.flatMap { p -> p.blocks.filter { cell ->
            cell.after.substringBefore('[') in logs || cell.after.substringBefore('[') in leaves
        }.map { it.position } }.toHashSet()
        val previousNatural = frame.plans.flatMap { p -> (p.blocks + p.terrain?.blocks.orEmpty()).filter { cell ->
            cell.after == "minecraft:air" && cell.before.substringBefore('[') in logs
        }.map { it.position to it.before } }.toMap()
        val pending = frame.city.forestryJobs.orEmpty().values.flatMap { job -> job.cells.map(job::position) }.toHashSet()
        val protectedBounds = civsProtectedBuildings(frame.city)
            .filter { it.phase != BuildingPhase.ACTIVE || it.id !in expected }.map { it.bounds }.toList()
        val found = linkedMapOf<CivPosition, CivVegetationCell>()
        for (x in frame.area.min.x..frame.area.max.x) for (z in frame.area.min.z..frame.area.max.z) {
            if ((x to z) in frame.productiveGroves) continue
            val chunk = frame.chunks[(x shr 4) to (z shr 4)] ?: continue
            val unknown = protectedBounds.filter { x in it.min.x..it.max.x && z in it.min.z..it.max.z }
            val top = chunk.getHighestBlockYAt(x and 15, z and 15).coerceAtMost(frame.maximumY - 1)
            for (y in top downTo maxOf(frame.minimumY, top - CivsForestry.MAX_HEIGHT)) {
                val p = CivPosition(x, y, z)
                if (p in authored || p in pending || p in frame.reserved) continue
                var covered = false
                for (i in unknown.indices) if (y in unknown[i].min.y..unknown[i].max.y) {
                    covered = true
                    break
                }
                if (covered) continue
                val type = chunk.getBlockType(x and 15, y, z and 15)
                if (type !in frame.logs && type !in frame.leaves) continue
                val data = chunk.getBlockData(x and 15, y, z and 15)
                val state = data.asString
                val material = state.substringBefore('[')
                val leaf = material in leaves && "persistent=false" in state && "waterlogged=true" !in state
                if (leaf || material in logs) found[p] = CivVegetationCell(p, state, material in logs, leaf)
            }
        }
        fun typeAt(p: CivPosition): Material? =
            if (p.y !in frame.minimumY until frame.maximumY) null
            else frame.chunks[(p.x shr 4) to (p.z shr 4)]?.getBlockType(p.x and 15, p.y, p.z and 15)
        val stumps = found.values.filter { civsVillageVegetationStump(it, ::typeAt) }.mapTo(hashSetOf()) { it.position }
        val selection = civsVillageVegetationSelection(found, previousNatural, stumps)
        val extended = withConstructionRemnants(frame, found, selection, ::typeAt)
        return extended.copy(cells = extended.cells.take(MAXIMUM_CELLS))
    }

    /** A completed, still-clear timber cut and its own original crown can witness a severed branch. */
    private fun retainedTimberCuts(frame: Frame, typeAt: (CivPosition) -> Material?): Map<CivPosition, String> = buildMap {
        val completed = frame.city.buildings.filter { it.phase == BuildingPhase.ACTIVE }
            .associate { CivsWarehouseRefit.retainedPlanId(it) to it.id }
        for (plan in frame.plans) {
            if (completed[plan.id] != plan.building.id || plan.id == frame.city.construction?.id ||
                plan.cityId != frame.city.id || plan.worldId != frame.city.worldId) continue
            val cleared = hashMapOf<CivPosition, String>()
            for (cell in plan.terrain?.blocks.orEmpty().asSequence() + plan.blocks.asSequence()) {
                val material = cell.before.substringBefore('[')
                if (cell.after != "minecraft:air") cleared.remove(cell.position)
                else if (material.endsWith("_log") || material.endsWith("_leaves")) cleared.putIfAbsent(cell.position, cell.before)
            }
            for ((p, state) in cleared) {
                val timber = CivsForestry.material(state)
                if (!timber.endsWith("_LOG") || timber.startsWith("STRIPPED_") || typeAt(p) != Material.AIR) continue
                if (vegetationNeighbors(p).any { near ->
                    cleared[near]?.let { CivsForestry.naturalLeaf(it, timber) } == true && typeAt(near) == Material.AIR
                }) put(p, state)
            }
        }
    }

    private fun withConstructionRemnants(frame: Frame, found: Map<CivPosition, CivVegetationCell>,
        selection: CivVegetationSelection, typeAt: (CivPosition) -> Material?): CivVegetationSelection {
        val selected = selection.cells.mapTo(hashSetOf()) { it.position }
        val unproved = found.filter { (p, cell) -> cell.log && p !in selected }
        if (unproved.isEmpty()) return selection
        val additions = mutableListOf<CivVegetationCell>()
        val parents = selection.proofParents.toMutableMap()
        val remnants = hashMapOf<CivPosition, CivVegetationRemnant>()
        val checked = hashSetOf<CivPosition>()
        for ((cut, before) in retainedTimberCuts(frame, typeAt)) {
            val timber = CivsForestry.material(before)
            val axis = when (before.substringAfter("[axis=", "").substringBefore(']')) {
                "x" -> CivPosition(1, 0, 0)
                "y" -> CivPosition(0, 1, 0)
                "z" -> CivPosition(0, 0, 1)
                else -> continue
            }
            for (direction in listOf(-1, 1)) {
                val seed = CivPosition(cut.x + direction * axis.x, cut.y + direction * axis.y, cut.z + direction * axis.z)
                if (unproved[seed]?.before != before || !checked.add(seed)) continue
                val connected = linkedMapOf(seed to cut)
                val queue = ArrayDeque(listOf(seed))
                val air = hashSetOf<CivPosition>()
                while (queue.isNotEmpty() && connected.size <= MAXIMUM_REMNANT_LOGS) {
                    val parent = queue.removeFirst()
                    for (near in vegetationNeighbors(parent)) {
                        val candidate = unproved[near]
                        if (candidate == null || CivsForestry.material(candidate.before) != timber) air += near
                        else if (connected.putIfAbsent(near, parent) == null) queue += near
                    }
                }
                checked += connected.keys
                // A support, live crown, paid log or unknown chunk anywhere on the edge vetoes the branch.
                if (connected.size > MAXIMUM_REMNANT_LOGS || air.any { typeAt(it)?.isAir != true }) continue
                val logs = connected.keys.toList().asReversed().map(unproved::getValue)
                val remnant = CivVegetationRemnant(logs, air)
                additions += logs
                parents.putAll(connected)
                logs.forEach { remnants[it.position] = remnant }
            }
        }
        return if (additions.isEmpty()) selection else selection.copy(cells = selection.cells + additions,
            proofParents = parents, remnants = remnants)
    }

    private fun apply(current: Sweep) {
        val settlement = city(current.city.id)
        val world = plugin.server.getWorld(current.city.worldId)
        if (settlement == null || world == null || settlement.pendingDeletion != null || settlement.worldId != current.city.worldId) {
            sweep = null; return
        }
        if (!civsVegetationProtectionMatches(current.city, settlement)) {
            nextSweep[settlement.id] = clock()
            sweep = null; return
        }
        val deadline = nanoTime() + 1_000_000L
        val area = civsVillageVegetationArea(settlement) ?: run { sweep = null; return }
        val touched = mutableListOf<CivPosition>()
        val pending = settlement.forestryJobs.orEmpty().values.flatMap { job -> job.cells.map(job::position) }.toHashSet()
        val cells = current.selection.cells
        fun loaded(p: CivPosition) = if (p.y in world.minHeight until world.maxHeight && world.isChunkLoaded(p.x shr 4, p.z shr 4))
            world.getBlockAt(p.x, p.y, p.z) else null
        fun skip(cell: CivVegetationCell) {
            current.skipped++
            // Children precede their proof parent. Keep that native chain through the crown
            // so a later sweep (including after restart) can still identify a blocked trunk.
            current.selection.proofParents[cell.position]?.let { current.deferred += it }
        }
        var count = 0
        while (current.next < cells.size && count++ < 64 && nanoTime() < deadline) {
            val cell = cells[current.next++]
            val p = cell.position
            if (p in current.deferred || !settlement.contains(p.x, p.z) || p.x !in area.min.x..area.max.x || p.z !in area.min.z..area.max.z ||
                !world.isChunkLoaded(p.x shr 4, p.z shr 4) || p in pending || locked(world.uid, p) ||
                occupied(world.uid, p) || occupied(world.uid, p.copy(y = p.y + 1)) ||
                p in current.reserved || civsProtectedBuildings(settlement).any {
                    (it.phase != BuildingPhase.ACTIVE || it !in current.city.buildings) && it.bounds.contains(p.x, p.y, p.z) } ||
                world.getNearbyEntities(BoundingBox(p.x.toDouble(), p.y + 1.0, p.z.toDouble(),
                    p.x + 1.0, p.y + 2.8, p.z + 1.0)).any { it is LivingEntity }) { skip(cell); continue }
            val block = world.getBlockAt(p.x, p.y, p.z)
            if (block.blockData.asString != cell.before || !canModify(settlement, block)) { skip(cell); continue }
            if (p in current.selection.stumps && !civsVillageVegetationStump(cell) { loaded(it)?.type }) { skip(cell); continue }
            val remnant = current.selection.remnants[p]
            if (remnant != null && (remnant.air.any { loaded(it)?.type?.isAir != true } || remnant.logs.any {
                val live = loaded(it.position)
                live == null || !live.type.isAir && live.blockData.asString != it.before
            })) { skip(cell); continue }
            block.setType(Material.AIR, false)
            touched += p
            current.removed++
        }
        if (touched.isNotEmpty()) changed(world.uid, touched)
        if (current.next == cells.size) {
            CivsDiagnostics.event("vegetation_sweep", "city" to settlement.id, "candidates" to cells.size,
                "removed" to current.removed, "skipped" to current.skipped)
            if (current.removed > 0) nextSweep[settlement.id] = clock() + 1_000
            sweep = null
        }
    }

    override fun close() { closed = true; sweep = null; nextSweep.clear() }

    companion object {
        const val MAXIMUM_CELLS = 32_768
        private const val MAXIMUM_REMNANT_LOGS = 8
    }
}

private fun vegetationNeighbors(p: CivPosition): Sequence<CivPosition> = sequence {
    for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
        if (dx != 0 || dy != 0 || dz != 0) yield(CivPosition(p.x + dx, p.y + dy, p.z + dz))
    }
}

/** Connected natural crowns prove their trunks; retained natural before-images also identify construction stumps. */
internal fun civsVillageVegetationCandidates(found: Map<CivPosition, CivVegetationCell>,
    previousNatural: Map<CivPosition, String> = emptyMap()): List<CivVegetationCell> =
    civsVillageVegetationSelection(found, previousNatural).cells

private fun civsVillageVegetationSelection(found: Map<CivPosition, CivVegetationCell>,
    previousNatural: Map<CivPosition, String>, stumps: Set<CivPosition> = emptySet()): CivVegetationSelection {
    val logs = found.values.filter { it.log }.associateBy { it.position }
    val proofs = mutableMapOf<CivPosition, CivPosition>()
    val reached = hashSetOf<CivPosition>()
    val todo = ArrayDeque<CivPosition>()
    val trunks = mutableListOf<CivVegetationCell>()
    for ((p, log) in logs) {
        val leaf = vegetationNeighbors(p).firstOrNull { found[it]?.leaf == true }
        if (previousNatural[p] == log.before || p in stumps || leaf != null) {
            if (previousNatural[p] != log.before && p !in stumps) proofs[p] = requireNotNull(leaf)
            reached += p
            todo += p
        }
    }
    while (todo.isNotEmpty()) {
        val p = todo.removeFirst()
        trunks += logs.getValue(p)
        for (near in vegetationNeighbors(p)) {
            if (near in logs && reached.add(near)) {
                proofs[near] = p
                todo += near
            }
        }
    }
    // Remove away from the proof first: every remaining log keeps a path to its crown
    // even when a per-tick limit, a blocker or a restart interrupts the sweep.
    return CivVegetationSelection(trunks.asReversed() + found.values.filter { it.leaf }, proofs, stumps)
}
