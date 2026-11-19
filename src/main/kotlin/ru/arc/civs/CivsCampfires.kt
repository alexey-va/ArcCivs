package ru.arc.civs

import org.bukkit.Material
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.Lightable
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random

internal const val CITIZEN_CAMP_RANGE = 24
internal const val CITIZEN_CONVERSATION_RANGE = 24
internal const val CITIZEN_BUBBLE_TICKS = 55
private const val CAMP_CACHE_TICKS = 2_400
private const val MAX_CAMP_CHUNKS = 256
private const val MAX_FIRES_PER_CHUNK = 16

internal data class CitizenCampfire(val worldId: UUID, val position: CivPosition)
internal data class CitizenCampVisit(val fire: CitizenCampfire?, val seat: CivPosition, val startedAt: Int,
                                    var arrivedAt: Int? = null, var greeted: Boolean = false,
                                    val partnerId: UUID? = null, var conversation: CitizenConversation? = null) {
    val conversationAt get() = conversation?.startedAt
}

/** One shuffled catalog for the active runtime, advanced only when a pair actually starts talking. */
internal class CitizenConversationDeck(keys: Collection<String>, private val random: Random = Random.Default) {
    private val topics = keys.filter { it.startsWith("workers.conversation.") }.groupBy { it.substringBeforeLast('.') }
        .map { (topic, keys) ->
            val lines = keys.sorted()
            require(lines.size in 2..4 && lines.withIndex().all { (n, key) -> key == "$topic.line-$n" }) {
                "ArcCivs conversation $topic requires two to four consecutive lines starting at line-0"
            }
            lines
        }.also { require(it.isNotEmpty()) { "ArcCivs requires at least one resident conversation" } }
    private var round = emptyList<List<String>>()
    private var next = 0

    fun draw(): List<String> {
        if (next == round.size) {
            val previous = round.lastOrNull()
            round = topics.toMutableList().apply {
                shuffle(random)
                if (size > 1 && first() == previous) java.util.Collections.swap(this, 0, lastIndex)
            }
            next = 0
        }
        return round[next++]
    }
}

/** Both stationary residents share this turn cursor. A late simulation pulse still delivers the next reply. */
internal class CitizenConversation(private val lines: List<String>, private val first: UUID, val startedAt: Int) {
    private var line = -1
    private var spokenAt = startedAt

    fun speak(worker: UUID, tick: Int): Boolean {
        val next = line + 1
        if (next >= lines.size || (next % 2 == 0) != (worker == first) || line >= 0 && tick - spokenAt < TURN_TICKS)
            return false
        line = next
        spokenAt = tick
        return true
    }

    fun bubble(worker: UUID, tick: Int): String? =
        if (line >= 0 && (line % 2 == 0) == (worker == first) && tick - spokenAt in 0 until CITIZEN_BUBBLE_TICKS)
            lines[line] else null

    fun finished(tick: Int): Boolean = line == lines.lastIndex && tick - spokenAt >= TURN_TICKS

    private companion object { const val TURN_TICKS = 60 }
}

/** Transient social time never grants work, advances a delivery, or survives an actor's retirement. */
internal class CitizenCampRoutine(private val workerId: UUID) {
    var visit: CitizenCampVisit? = null
        private set
    private var lastAttempt: Int? = null
    private var delay = 0
    private var eveningDay: Long? = null
    private var firstMeetingAt: Int? = null
    val restTicks: Int = 500 + Math.floorMod(workerId.hashCode(), 401)

    fun available(tick: Int, day: Long, evening: Boolean): Boolean =
        visit == null && (!evening || eveningDay != day) && lastAttempt?.let { tick - it >= delay } != false

    fun start(fire: CitizenCampfire, seat: CivPosition, tick: Int, day: Long, evening: Boolean) {
        visit = CitizenCampVisit(fire, seat, tick)
        if (evening) eveningDay = day
    }

    fun meetingAvailable(tick: Int): Boolean {
        val first = firstMeetingAt ?: (tick + 200 + Math.floorMod(workerId.hashCode(), 301)).also { firstMeetingAt = it }
        return tick - first >= 0 && available(tick, 0, evening = false)
    }

    fun startMeeting(partnerId: UUID, seat: CivPosition, tick: Int) {
        visit = CitizenCampVisit(null, seat, tick, partnerId = partnerId)
    }

    fun discoveryMiss(tick: Int) { lastAttempt = tick; delay = 100 }

    fun finish(tick: Int) {
        visit = null
        lastAttempt = tick
        delay = 1_800 + Math.floorMod(workerId.hashCode(), 1_201)
    }

    fun expired(tick: Int): Boolean = visit?.let {
        if (it.partnerId != null) it.conversation?.let { talk -> talk.finished(tick) || tick - talk.startedAt >= 400 }
            ?: (tick - it.startedAt >= 400)
        else tick - (it.arrivedAt ?: it.startedAt) >= if (it.arrivedAt == null) 900 else restTicks
    } ?: false
}

internal fun citizenCampEligible(plan: CitizenPlan, phase: CitizenPhase, time: Long): Boolean {
    if (plan.courier != null || time in 12_000L..23_000L) return false
    if (plan.idleReason in setOf("unassigned", "no-construction", "waiting-orders", "waiting-growth", "waiting-tool",
            "waiting-materials", "construction-paused")) return true
    return plan.idleReason == null && phase == CitizenPhase.IDLE && time in 10_500L until 12_000L
}

internal fun citizenCampfireLit(block: Block): Boolean {
    val type = block.type
    return (type == Material.CAMPFIRE || type == Material.SOUL_CAMPFIRE) && (block.blockData as? Lightable)?.isLit == true
}

internal fun citizenCampfireLit(world: World, point: CivPosition): Boolean =
    point.y in world.minHeight until world.maxHeight && world.isChunkLoaded(point.x shr 4, point.z shr 4) &&
        citizenCampfireLit(world.getBlockAt(point.x, point.y, point.z))

/** A fireplace is a social destination only on its open side, never through a wall or floor. */
internal fun citizenCampfireVisible(world: World, eye: Location, fire: CivPosition): Boolean =
    citizenCanReachBlockPoint(world, eye, fire, Location(world, fire.x + .5, fire.y + .4, fire.z + .5), reach = 4.0)

internal fun citizenCampfireVisible(world: World, seat: CivPosition, fire: CivPosition): Boolean =
    citizenCampfireVisible(world, Location(world, seat.x + .5, seat.y + 1.62, seat.z + .5), fire)

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
