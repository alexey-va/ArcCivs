package ru.arc.civs

import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.util.Base64
import java.util.UUID
import kotlin.math.abs

/** One accepted tree, including its prepaid or final-output-funded replacement kit. */
data class CivForestryJob(
    val id: UUID,
    val root: CivPosition,
    val sapling: String,
    val roots: List<CivPosition>,
    val states: List<String>,
    /** State index, then relative y (6 bits), z (5 bits), x (5 bits). */
    @field:JsonAdapter(CivForestryCellsAdapter::class)
    val cells: List<Int>,
    val next: Int = 0,
    val harvestedLogs: Int = 0,
    val seedDeferred: Boolean = false,
) {
    val timber: String get() = sapling.removeSuffix("_SAPLING") + "_LOG"
    fun position(code: Int): CivPosition = CivPosition(root.x + (code and 31) - 15,
        root.y + ((code ushr 10) and 63), root.z + ((code ushr 5) and 31) - 15)
    fun state(code: Int): String = states[code ushr 16]
}

/** Three bytes per bounded cell keep fifteen simultaneous crowns within the city snapshot limit. */
internal class CivForestryCellsAdapter : TypeAdapter<List<Int>>() {
    override fun write(writer: JsonWriter, cells: List<Int>) {
        require(cells.size <= CivsForestry.MAX_CELLS && cells.all(::validCode))
        val bytes = ByteArray(cells.size * 3)
        cells.forEachIndexed { index, code ->
            bytes[index * 3] = (code ushr 16).toByte()
            bytes[index * 3 + 1] = (code ushr 8).toByte()
            bytes[index * 3 + 2] = code.toByte()
        }
        writer.value(Base64.getEncoder().encodeToString(bytes))
    }

    override fun read(reader: JsonReader): List<Int> {
        require(reader.peek() == JsonToken.STRING)
        val encoded = reader.nextString()
        require(encoded.length <= CivsForestry.MAX_CELLS * 4 && encoded.length % 4 == 0)
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size % 3 == 0)
        return List(bytes.size / 3) { index -> (((bytes[index * 3].toInt() and 255) shl 16) or
            ((bytes[index * 3 + 1].toInt() and 255) shl 8) or (bytes[index * 3 + 2].toInt() and 255)).also {
                require(validCode(it))
            } }
    }

    private fun validCode(code: Int): Boolean = code in 0..0x3FFFFF && (code and 31) <= 30 &&
        (code ushr 5 and 31) <= 30 && (code ushr 10 and 63) <= CivsForestry.MAX_HEIGHT
}

/** Native drops belong to this exact admitted batch, independently of the Auto town's final abstract credit. */
data class CivForestryProgress(val job: CivForestryJob, val next: Int,
    val drops: Map<String, Long> = emptyMap(), val harvestedLogs: Int = 0)

/** Auto towns have no transferable stock; this exact receipt authorizes only their observed forestry work. */
data class CivForestryReceipt(val workerBefore: CivWorker, val stockBefore: Stock,
    val jobBefore: CivForestryJob?, val jobsWereNull: Boolean, val work: CivWorldWork, val acceptedAt: Long,
    val seededBefore: Set<String>? = null) {
    fun before(after: Settlement): Settlement {
        val key = work.buildingId.toString()
        val jobs = after.forestryJobs.orEmpty().toMutableMap().also {
            if (jobBefore == null) it.remove(key) else it[key] = jobBefore
        }
        return after.copy(stock = stockBefore, workers = after.workers.map { if (it.id == workerBefore.id) workerBefore else it },
            forestryJobs = jobs.takeUnless { jobsWereNull }, forestrySeeded = seededBefore, revision = after.revision - 1)
    }

    fun validated(after: Settlement) {
        require(after.inventory == null && after.autonomy != null && acceptedAt >= 0 &&
            work.workerId == workerBefore.id && work.equipmentAfter == null && work.outputs.isEmpty())
        require(!jobsWereNull || jobBefore == null && before(after).forestryJobs == null)
        val before = before(after)
        require(before.building(work.buildingId)?.type == BuildingType.FORESTER)
        require(CivsRules.finishWorldWork(before, work, acceptedAt) == after)
    }
}

internal object CivsForestry {
    const val MAX_CELLS = 2_048
    const val BATCH_SIZE = 128
    const val MAX_HEIGHT = 47
    const val MAX_RADIUS = 15
    private val species = setOf("OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK")
    val soils = setOf("DIRT", "GRASS_BLOCK", "COARSE_DIRT", "ROOTED_DIRT", "PODZOL", "MYCELIUM", "MOSS_BLOCK")

    fun material(state: String): String = state.substringBefore('[').removePrefix("minecraft:").uppercase()
    fun naturalLeaf(state: String, timber: String): Boolean =
        material(state) == timber.removeSuffix("_LOG") + "_LEAVES" && "persistent=false" in state && "waterlogged=true" !in state
    private val vineFaces = mapOf("north" to CivPosition(0, 0, -1), "south" to CivPosition(0, 0, 1),
        "west" to CivPosition(-1, 0, 0), "east" to CivPosition(1, 0, 0), "up" to CivPosition(0, 1, 0))
    private fun vine(state: String): Boolean = material(state) == "VINE" && vineFaces.keys.any { "$it=true" in state }
    /** A vine must actually face this tree, or inherit that attachment from the vine above it. */
    private fun attachedVines(tree: Map<CivPosition, String>, candidates: Map<CivPosition, String>): Map<CivPosition, String> {
        val attached = linkedMapOf<CivPosition, String>()
        candidates.entries.sortedByDescending { it.key.y }.forEach { (point, state) ->
            if (vineFaces.any { (face, offset) -> "$face=true" in state &&
                    (CivPosition(point.x + offset.x, point.y + offset.y, point.z + offset.z) in tree ||
                        face != "up" && attached[point.copy(y = point.y + 1)]?.contains("$face=true") == true) })
                attached[point] = state
        }
        return attached
    }
    private fun matches(expected: String, actual: String, timber: String): Boolean =
        expected == actual || naturalLeaf(expected, timber) && naturalLeaf(actual, timber)

    /** Native vines may prune faces between receipts; only this captured tree may supply a new attachment. */
    class Matcher(private val job: CivForestryJob) {
        private val remaining = job.cells.drop(job.next).associateBy(job::position)

        fun matches(code: Int, actual: String, readSupport: ((CivPosition) -> String?)? = null): Boolean {
            val point = job.position(code)
            if (remaining[point] != code) return false
            val expected = job.state(code)
            if (CivsForestry.matches(expected, actual, job.timber)) return true
            if (!vine(expected) || !vine(actual) || expected.replace("=true", "=false") != actual.replace("=true", "=false")) return false
            return vineFaces.all { (face, offset) ->
                if ("$face=true" !in actual || "$face=true" in expected) return@all true
                val support = CivPosition(point.x + offset.x, point.y + offset.y, point.z + offset.z)
                val captured = remaining[support]?.let(job::state) ?: return@all false
                if (material(captured) != job.timber && !naturalLeaf(captured, job.timber)) return@all false
                // Planning/admission supply native reads; domain replay checks the same bounded captured support.
                val current = if (readSupport == null) captured else readSupport(support) ?: return@all false
                CivsForestry.matches(captured, current, job.timber)
            }
        }
    }
    fun saplingState(sapling: String): String = "minecraft:${sapling.lowercase()}[stage=0]"
    fun large(timber: String): Boolean = timber in setOf("SPRUCE_LOG", "JUNGLE_LOG", "DARK_OAK_LOG")

    /** Conservative horizontal reserve; StructureGrowEvent also checks the actual generated crown. */
    fun crownRadius(timber: String): Int = when (timber) {
        "BIRCH_LOG" -> 3
        "SPRUCE_LOG" -> 6
        "DARK_OAK_LOG" -> 6
        else -> 7
    }

    fun crownBounds(roots: List<CivPosition>, timber: String): CivBox {
        val radius = crownRadius(timber)
        return CivBox(CivPosition(roots.minOf { it.x } - radius, roots.minOf { it.y }, roots.minOf { it.z } - radius),
            CivPosition(roots.maxOf { it.x } + radius, roots.maxOf { it.y } + MAX_HEIGHT, roots.maxOf { it.z } + radius))
    }

    fun paths(city: Settlement, retained: Set<Pair<Int, Int>> = emptySet()): Set<Pair<Int, Int>> = buildSet {
        addAll(retained)
        civsProtectedBuildings(city).forEach { building ->
            (CivsAutonomousGeometry.approach(building) + building.transit.orEmpty()).forEach { add(it.x to it.z) }
        }
    }

    fun blockedColumn(city: Settlement, x: Int, z: Int, paths: Set<Pair<Int, Int>>): Boolean =
        !city.contains(x, z) || (x to z) in paths || civsProtectedBuildings(city).any {
            x in it.bounds.min.x..it.bounds.max.x && z in it.bounds.min.z..it.bounds.max.z || CivsWorksiteLayouts.isField(it, x, z)
        }

    fun crownClear(city: Settlement, roots: List<CivPosition>, timber: String, paths: Set<Pair<Int, Int>>): Boolean {
        val bounds = crownBounds(roots, timber)
        fun contains(x: Int, z: Int) = x in bounds.min.x..bounds.max.x && z in bounds.min.z..bounds.max.z
        return city.contains(bounds.min.x, bounds.min.z) && city.contains(bounds.max.x, bounds.max.z) &&
            paths.none { contains(it.first, it.second) } && civsProtectedBuildings(city).none { building ->
                bounds.min.x <= building.bounds.max.x && bounds.max.x >= building.bounds.min.x &&
                    bounds.min.z <= building.bounds.max.z && bounds.max.z >= building.bounds.min.z ||
                    CivsWorksiteLayouts.field(building).any { contains(it.x, it.z) }
            }
    }

    fun valid(city: Settlement, buildingId: String, job: CivForestryJob): Boolean = runCatching {
        require(city.buildings.any { it.id.toString() == buildingId && it.type == BuildingType.FORESTER })
        require(job.id != null && job.sapling.removeSuffix("_SAPLING") in species && job.sapling.endsWith("_SAPLING"))
        require(job.root.y in -4_096..4_096 && job.roots.size in setOf(1, 4) && job.root in job.roots)
        require(job.roots.distinct().size == job.roots.size && job.roots.all {
            it.y == job.root.y && abs(it.x.toLong() - job.root.x) <= 1 && abs(it.z.toLong() - job.root.z) <= 1
        })
        require(job.roots.size == 1 || large(job.timber) && job.roots.map { it.x }.distinct().size == 2 &&
            job.roots.map { it.z }.distinct().size == 2)
        require(job.sapling != "DARK_OAK_SAPLING" || job.roots.size == 4)
        require(job.states.size in 1..64 && job.states.distinct().size == job.states.size && job.states.all {
            it.length in 1..256 && (material(it) == job.timber || naturalLeaf(it, job.timber) || vine(it))
        })
        require(job.cells.size in 1..MAX_CELLS && job.next in 0..job.cells.size && job.cells.all {
            it >= 0 && it ushr 16 in job.states.indices && (it and 31) <= 30 && (it ushr 5 and 31) <= 30 &&
                (it ushr 10 and 63) <= MAX_HEIGHT && city.contains(job.position(it).x, job.position(it).z)
        })
        require(job.cells.map(job::position).distinct().size == job.cells.size)
        require(job.cells.takeLast(job.roots.size).map(job::position).toSet() == job.roots.toSet())
        require(job.cells.takeLast(job.roots.size).all { material(job.state(it)) == job.timber })
        require(job.harvestedLogs in 0..job.next)
        require(!job.seedDeferred || city.autonomy != null)
        val captured = job.cells.associate { job.position(it) to job.state(it) }
        val vines = captured.filterValues(::vine)
        require(attachedVines(captured - vines.keys, vines).size == vines.size)
    }.isSuccess

    private fun validDrops(job: CivForestryJob, drops: Map<String, Long>): Boolean = drops.size <= 6 && drops.all { (item, count) ->
        item in setOf(job.timber, job.sapling, job.timber.removeSuffix("_LOG") + "_LEAVES", "STICK", "APPLE", "VINE") &&
            count in 1..MAX_CELLS * 64L
    }

    /** The same calculation drives admission and the durable receipt; deferred kits use only observed harvest credit. */
    fun autoStock(city: Settlement, work: CivWorldWork): Stock {
        val building = city.building(work.buildingId) ?: throw CivsRejection("building-not-found")
        val progress = work.forestry
        val finished = progress != null && progress.next == progress.job.cells.size
        val amount = if (finished && requireNotNull(progress).let { it.job.harvestedLogs + it.harvestedLogs > 0 })
            CivsRules.deliveryAmount(building) else 0L
        val cost = when {
            progress?.job?.seedDeferred == true -> if (finished) 1L else 0L
            work.inputs.isEmpty() -> 0L
            progress == null && city.stock.wood == 0L && building.id.toString() !in city.forestrySeeded.orEmpty() -> 0L
            else -> 1L
        }
        if (finished && progress?.job?.seedDeferred == true && amount == 0L || city.stock.wood + amount < cost)
            throw CivsRejection("materials-missing")
        val wood = city.stock.wood + amount - cost
        if (wood > CivsRules.capacity(city)) throw CivsRejection("storage-full")
        return city.stock.copy(wood = wood)
    }

    fun after(progress: CivForestryProgress): CivForestryJob? = progress.job.takeIf { progress.next < it.cells.size }?.copy(
        next = progress.next, harvestedLogs = progress.job.harvestedLogs + progress.harvestedLogs)

    fun batchEnd(job: CivForestryJob): Int {
        val end = minOf(job.cells.size, job.next + BATCH_SIZE)
        return if (end in job.cells.size - job.roots.size + 1 until job.cells.size) job.cells.size - job.roots.size else end
    }

    fun rootClaimed(city: Settlement, buildingId: UUID, root: CivPosition): Boolean = city.forestryJobs.orEmpty().any { (id, job) ->
        id != buildingId.toString() && root in job.roots
    }

    fun claimClear(city: Settlement, buildingId: UUID, job: CivForestryJob): Boolean {
        val others = city.forestryJobs.orEmpty().filterKeys { it != buildingId.toString() }.values
        if (others.isEmpty()) return true
        val positions = job.cells.mapTo(hashSetOf(), job::position)
        return others.none { other -> other.cells.any { other.position(it) in positions } }
    }

    /** Validates the persisted batch boundary and the exact prepaid or deferred replant reservation. */
    fun validateWork(city: Settlement, work: CivWorldWork, readSupport: ((CivPosition) -> String?)? = null) {
        val building = city.building(work.buildingId) ?: throw CivsRejection("building-not-found")
        require(building.type == BuildingType.FORESTER && work.changes.size <= BATCH_SIZE)
        val progress = work.forestry
        if (progress == null) {
            require(city.forestryJobs?.get(building.id.toString()) == null)
            require(work.changes.size in setOf(1, 4) && work.outputs.isEmpty())
            val sapling = requireNotNull(CivsProduction.material(building)).removeSuffix("_LOG") + "_SAPLING"
            require(work.inputs == mapOf(sapling to work.changes.size.toLong()))
            require(work.changes.all { material(it.before) in setOf("AIR", "CAVE_AIR", "VOID_AIR") && it.after == saplingState(sapling) })
            val roots = work.changes.map { it.position }
            require(roots.distinct().size == roots.size && roots.all { it.y == roots.first().y })
            require(if (large(sapling.removeSuffix("_SAPLING") + "_LOG")) roots.size == 4 &&
                roots.maxOf { it.x } - roots.minOf { it.x } == 1 && roots.maxOf { it.z } - roots.minOf { it.z } == 1 else roots.size == 1)
            return
        }
        val job = progress.job
        require(valid(city, building.id.toString(), job))
        val existing = city.forestryJobs?.get(building.id.toString())
        require(if (existing == null) job.next == 0 && job.harvestedLogs == 0 else existing == job)
        if (existing == null) {
            require(claimClear(city, building.id, job))
            require(job.seedDeferred == (city.autonomy != null && city.stock.wood == 0L))
        }
        require(progress.next in job.next + 1..minOf(job.cells.size, job.next + BATCH_SIZE))
        require(progress.next <= job.cells.size - job.roots.size || progress.next == job.cells.size)
        require(progress.harvestedLogs in 0..progress.next - job.next && validDrops(job, progress.drops))
        val cells = job.cells.subList(job.next, progress.next).associateBy(job::position)
        val matcher = Matcher(job)
        require(work.changes.map { it.position }.distinct().size == work.changes.size && work.changes.all { cell ->
            val code = cells[cell.position] ?: return@all false
            val after = if (cell.position in job.roots) saplingState(job.sapling) else "minecraft:air"
            cell.after == after && (matcher.matches(code, cell.before, readSupport) ||
                cell.position in job.roots && material(cell.before) in setOf("AIR", "CAVE_AIR", "VOID_AIR"))
        })
        require(progress.harvestedLogs == work.changes.count { material(it.before) == job.timber })
        require(work.inputs == if (existing == null && !job.seedDeferred) mapOf(job.sapling to job.roots.size.toLong()) else emptyMap<String, Long>())
        require(work.outputs == if (city.autonomy == null) progress.drops else emptyMap<String, Long>())
        if (city.autonomy != null) require(progress.drops.isEmpty())
        // The last receipt must plant every reserved root; no progress-only final grant.
        if (progress.next == job.cells.size) require(work.changes.filter { it.position in job.roots }.size == job.roots.size)
    }

    /** Detached captured strings only. Native reads are supplied one at a time under the shared pulse budget. */
    class Scan(val root: CivPosition, val timber: String) {
        private val queue = ArrayDeque<CivPosition>().apply { add(root) }
        private val seen = hashSetOf<CivPosition>()
        private val found = linkedMapOf<CivPosition, String>()
        private val vines = linkedMapOf<CivPosition, String>()
        private val ground = hashSetOf<CivPosition>()
        var failed: Boolean = false
            private set
        val complete: Boolean get() = !failed && queue.isEmpty()
        val size: Int get() = found.size

        fun advance(budget: Int, read: (CivPosition) -> String?): Int {
            var used = 0
            while (queue.isNotEmpty() && used < budget && !failed) {
                val point = queue.removeFirst()
                if (!seen.add(point)) continue
                used++
                val state = read(point)
                if (state == null) { failed = true; break }
                val log = material(state) == timber
                if (material(state) in soils) ground += point
                val vine = vine(state)
                if (!log && !naturalLeaf(state, timber) && !vine) continue
                if (abs(point.x.toLong() - root.x) > MAX_RADIUS || abs(point.z.toLong() - root.z) > MAX_RADIUS ||
                    point.y !in root.y..root.y + MAX_HEIGHT || found.size + vines.size == MAX_CELLS) { failed = true; break }
                if (vine) {
                    vines[point] = state
                    // A hanging chain cannot connect this capture to another tree or an unrelated vine wall.
                    point.copy(y = point.y - 1).takeIf { it !in seen }?.let(queue::add)
                    continue
                }
                found[point] = state
                // Logs may branch diagonally or downward. Leaves use native face connectivity.
                for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                    if (dx == 0 && dy == 0 && dz == 0 || !log && abs(dx) + abs(dy) + abs(dz) != 1) continue
                    val next = CivPosition(point.x + dx, point.y + dy, point.z + dz)
                    if (next !in seen) queue.add(next)
                }
            }
            return used
        }

        fun job(roots: List<CivPosition>): CivForestryJob? {
            if (!complete || found.values.none { naturalLeaf(it, timber) } || roots.any { material(found[it] ?: "") != timber }) return null
            // Touching crowns must not turn an adjacent, independently rooted tree into this mill's harvest.
            if (found.filter { (point, state) -> material(state) == timber && point.copy(y = point.y - 1) in ground }
                    .keys != roots.toSet()) return null
            val captured = found + attachedVines(found, vines)
            val order = captured.entries.sortedWith(compareBy<Map.Entry<CivPosition, String>> {
                when { vine(it.value) -> 0; it.key in roots -> 3; material(it.value) == timber -> 2; else -> 1 }
            }.thenByDescending { it.key.y }.thenBy { it.key.x }.thenBy { it.key.z })
            val states = order.map { it.value }.distinct()
            if (states.size > 64) return null
            return CivForestryJob(UUID.randomUUID(), root, timber.removeSuffix("_LOG") + "_SAPLING", roots, states,
                order.map { (point, state) -> (states.indexOf(state) shl 16) or ((point.y - root.y) shl 10) or
                    ((point.z - root.z + 15) shl 5) or (point.x - root.x + 15) })
        }
    }
}
