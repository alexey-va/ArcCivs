package ru.arc.civs

import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.Lightable
import java.util.UUID
import kotlin.math.abs

internal const val CITIZEN_CAMP_RANGE = 24
private const val CAMP_CACHE_TICKS = 2_400
private const val MAX_CAMP_CHUNKS = 256
private const val MAX_FIRES_PER_CHUNK = 16

internal data class CitizenCampfire(val worldId: UUID, val position: CivPosition)
internal data class CitizenCampVisit(val fire: CitizenCampfire, val seat: CivPosition, val startedAt: Int,
                                    var arrivedAt: Int? = null, var greeted: Boolean = false)

/** Transient social time never grants work, advances a delivery, or survives an actor's retirement. */
internal class CitizenCampRoutine(private val workerId: UUID) {
    var visit: CitizenCampVisit? = null
        private set
    private var lastAttempt: Int? = null
    private var delay = 0
    private var eveningDay: Long? = null
    val restTicks: Int = 500 + Math.floorMod(workerId.hashCode(), 401)

    fun available(tick: Int, day: Long, evening: Boolean): Boolean =
        visit == null && (!evening || eveningDay != day) && lastAttempt?.let { tick - it >= delay } != false

    fun start(fire: CitizenCampfire, seat: CivPosition, tick: Int, day: Long, evening: Boolean) {
        visit = CitizenCampVisit(fire, seat, tick)
        if (evening) eveningDay = day
    }

    fun discoveryMiss(tick: Int) { lastAttempt = tick; delay = 100 }

    fun finish(tick: Int) {
        visit = null
        lastAttempt = tick
        delay = 1_800 + Math.floorMod(workerId.hashCode(), 1_201)
    }

    fun expired(tick: Int): Boolean = visit?.let {
        tick - (it.arrivedAt ?: it.startedAt) >= if (it.arrivedAt == null) 900 else restTicks
    } ?: false
}

internal fun citizenCampEligible(plan: CitizenPlan, phase: CitizenPhase, time: Long): Boolean {
    if (plan.courier != null || time in 12_000L..23_000L) return false
    if (plan.idleReason in setOf("unassigned", "no-construction", "waiting-orders", "waiting-growth", "waiting-tool",
            "waiting-materials", "construction-paused")) return true
    return plan.idleReason == null && phase == CitizenPhase.IDLE && time in 10_500L until 12_000L
}

internal fun citizenCampfireLit(block: Block): Boolean =
    block.type in setOf(Material.CAMPFIRE, Material.SOUL_CAMPFIRE) && (block.blockData as? Lightable)?.isLit == true

internal fun citizenCampfireLit(world: World, point: CivPosition): Boolean =
    point.y in world.minHeight until world.maxHeight && world.isChunkLoaded(point.x shr 4, point.z shr 4) &&
        citizenCampfireLit(world.getBlockAt(point.x, point.y, point.z))

/** Six distinct standing places leave the fire and the route between neighbours clear. */
internal fun citizenCampSeats(fire: CivPosition, workerId: UUID): Sequence<CivPosition> = sequence {
    val offsets = listOf(2 to 0, 1 to 2, -1 to 2, -2 to 0, -1 to -2, 1 to -2)
    val start = Math.floorMod(workerId.hashCode(), offsets.size)
    for (i in offsets.indices) {
        val (dx, dz) = offsets[(i + start) % offsets.size]
        for (dy in listOf(0, 1, -1)) yield(CivPosition(fire.x + dx, fire.y + dy, fire.z + dz))
    }
}

/**
 * One loaded chunk's filtered tile-entity index per pulse for the whole owner.
 * No block-volume scan, chunk loads, per-NPC timer, or persistent campfire registry.
 */
internal class CivsCampfires {
    internal data class ChunkKey(val worldId: UUID, val x: Int, val z: Int)
    private data class Cached(val tick: Int, val fires: List<CitizenCampfire>)
    private val cached = linkedMapOf<ChunkKey, Cached>()
    private val pending = linkedSetOf<ChunkKey>()

    fun nearby(world: World, city: Settlement, anchor: CivPosition, tick: Int): List<CitizenCampfire> {
        val keys = buildList {
            for (x in ((anchor.x - CITIZEN_CAMP_RANGE) shr 4)..((anchor.x + CITIZEN_CAMP_RANGE) shr 4))
                for (z in ((anchor.z - CITIZEN_CAMP_RANGE) shr 4)..((anchor.z + CITIZEN_CAMP_RANGE) shr 4))
                    if (world.isChunkLoaded(x, z)) add(ChunkKey(world.uid, x, z))
        }.sortedBy { key ->
            val dx = key.x * 16 + 8.0 - anchor.x
            val dz = key.z * 16 + 8.0 - anchor.z
            dx * dx + dz * dz
        }
        for (key in keys) {
            if (cached[key]?.let { tick - it.tick < CAMP_CACHE_TICKS } != true && pending.size < MAX_CAMP_CHUNKS)
                pending.add(key)
        }
        return keys.asSequence().flatMap { cached[it]?.fires.orEmpty() }.filter { fire ->
            val p = fire.position
            val dx = p.x.toLong() - anchor.x
            val dz = p.z.toLong() - anchor.z
            city.contains(p.x, p.z) && abs(p.y.toLong() - anchor.y) <= 3 &&
                dx * dx + dz * dz <= CITIZEN_CAMP_RANGE.toLong() * CITIZEN_CAMP_RANGE
        }.sortedBy {
            val p = it.position
            val dx = p.x.toDouble() - anchor.x
            val dy = p.y.toDouble() - anchor.y
            val dz = p.z.toDouble() - anchor.z
            dx * dx + dy * dy + dz * dz
        }.toList()
    }

    fun scanOne(tick: Int, world: (UUID) -> World?) {
        val key = pending.firstOrNull() ?: return
        pending.remove(key)
        val target = world(key.worldId) ?: return
        if (!target.isChunkLoaded(key.x, key.z)) return
        val chunk = target.getChunkAt(key.x, key.z, false)
        var accepted = 0
        val fires = chunk.getTileEntities({ block ->
            if (accepted < MAX_FIRES_PER_CHUNK && citizenCampfireLit(block)) { accepted++; true } else false
        }, false).map { CitizenCampfire(key.worldId, CivPosition(it.x, it.y, it.z)) }
        cached.remove(key)
        cached[key] = Cached(tick, fires)
        while (cached.size > MAX_CAMP_CHUNKS) cached.remove(cached.keys.first())
    }

    fun invalidate(worldId: UUID, x: Int, z: Int) {
        val key = ChunkKey(worldId, x, z)
        cached.remove(key)
        pending.remove(key)
    }

    fun removeWorld(worldId: UUID) {
        cached.keys.removeIf { it.worldId == worldId }
        pending.removeIf { it.worldId == worldId }
    }

    fun clear() { cached.clear(); pending.clear() }
}
