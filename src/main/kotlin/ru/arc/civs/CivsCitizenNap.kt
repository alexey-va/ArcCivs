package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.util.BoundingBox
import ru.arc.paper.npc.PacketNpcPose
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal const val CIVS_IDLE_NAP_SEAT_TOP = .48

internal class CivsIdleNapVisual(val pulse: (Int) -> Unit, val close: () -> Unit)

internal data class CitizenIdleNap(val ground: Location, val anchor: CitizenPoint, val bounds: BoundingBox,
    val startedAt: Int, val visual: CivsIdleNapVisual) {
    var lastPulse = 0
    var lastClearanceCheck = 0
}

/** Optional visual rest only. The resident keeps its real ground position for gameplay. */
internal class CivsCitizenNap(
    private val runtime: CitizenRuntime,
    private val collisions: () -> CivsCitizenCollisions,
) {
    var show: ((Location, Float, Double) -> CivsIdleNapVisual)? = null

    fun start(actor: CitizenActor, city: Settlement, worker: CivWorker, world: World, now: Long): Boolean {
        val render = show ?: return false
        val nextAt = actor.nextIdleNapAt
        if (nextAt != null && now < nextAt) return false
        if (!idle(actor, worker, world) || actor.npc.isSeated || actor.npc.pose != PacketNpcPose.STANDING) return false
        val offset = Math.floorMod(actor.workerId.hashCode(), 45_000).toLong()
        if (nextAt == null) {
            actor.nextIdleNapAt = now + 45_000L + offset
            return false
        }
        // A missed audience or unsafe seat is a short staggered recheck; actual rest uses the long cooldown.
        actor.nextIdleNapAt = now + 5_000L + offset % 5_000L
        val npc = actor.npc
        val ground = npc.location
        if (ground.world != world || !hasViewer(world, ground)) return false
        if (runtime.actors.values.count { it.idleNap != null } >= 2 || !safeGround(city, world, ground)) return false
        val yaw = npc.bodyYaw
        val bounds = seatedBounds(ground, yaw, npc.scale)
        if (!clear(actor, world, ground, bounds)) return false

        val seat = ground.clone().also {
            it.y += (CIVS_IDLE_NAP_SEAT_TOP - .7040625) * npc.scale
            it.yaw = yaw
        }
        try {
            val visual = render(ground.clone(), yaw, npc.scale)
            actor.idleNap = CitizenIdleNap(ground, seat.point(), bounds, Bukkit.getCurrentTick(), visual)
            npc.sit(seat)
            visual.pulse(0)
        } catch (error: Exception) {
            stop(actor)
            runtime.logFailure(actor, "idle-nap", error)
            return false
        }
        actor.nextIdleNapAt = now + 120_000L + offset
        runtime.statuses[actor.workerId] = "resting"
        CivsDiagnostics.event("citizen_idle_nap", "city" to actor.cityId, "worker" to actor.workerId,
            "action" to "start", "ground" to ground.point().coordinates(), "idle_reason" to actor.plan.idleReason)
        return true
    }

    fun continueRest(actor: CitizenActor, city: Settlement, worker: CivWorker, world: World): Boolean {
        val nap = actor.idleNap ?: return false
        val age = Bukkit.getCurrentTick() - nap.startedAt
        val npc = actor.npc
        if (age >= 160 || !idle(actor, worker, world) || !npc.isSeated || npc.world != world ||
            npc.location.point() != nap.anchor) {
            stop(actor)
            return false
        }
        if (age - nap.lastClearanceCheck >= 20) {
            if (!hasViewer(world, nap.ground) || !safeGround(city, world, nap.ground) ||
                !clear(actor, world, nap.ground, nap.bounds)) {
                stop(actor)
                return false
            }
            nap.lastClearanceCheck = age
        }
        if (age != nap.lastPulse) {
            try {
                nap.visual.pulse(age)
                nap.lastPulse = age
            } catch (error: Exception) {
                stop(actor)
                runtime.logFailure(actor, "idle-nap", error)
                return false
            }
        }
        runtime.statuses[actor.workerId] = "resting"
        return true
    }

    fun stop(actor: CitizenActor, restoreStanding: Boolean = true): Boolean {
        val nap = actor.idleNap ?: return actor.npc.isValid
        actor.idleNap = null
        runCatching { nap.visual.close() }.onFailure { runtime.logFailure(actor, "idle-nap-close", it) }
        CivsDiagnostics.event("citizen_idle_nap", "city" to actor.cityId, "worker" to actor.workerId,
            "action" to "end")
        val npc = actor.npc
        if (!npc.isValid || !npc.isSeated) return npc.isValid
        npc.stand()
        if (!restoreStanding) return true
        val ground = nap.ground
        if (npc.world == ground.world && npc.location.point() == nap.anchor &&
            citizenPacketSupported(npc.world, ground.point(), allowClimbable = false) &&
            citizenDiningVolumeClear(npc.world, diningStandingBounds(ground, npc.scale)) &&
            collisions().residentMoveAllowed(actor, ground, ground)) {
            npc.teleport(ground)
            return true
        }
        // Match the ordinary meal-seat policy when terrain or a body takes the exit.
        npc.remove()
        return false
    }

    fun releaseUnseen(excluding: UUID? = null) {
        for (actor in runtime.actors.values) {
            val nap = actor.idleNap ?: continue
            if (!hasViewer(actor.npc.world, nap.ground, excluding)) stop(actor)
        }
    }

    private fun idle(actor: CitizenActor, worker: CivWorker, world: World): Boolean {
        val plan = actor.plan
        return plan.idleReason in IDLE_REASONS && actor.cycle.phase == CitizenPhase.IDLE && actor.route == null &&
            actor.npc.isValid && world.time !in 12_000L..23_000L && !CivsResidents.isChild(worker) &&
            CivsResidents.hunger(worker) > 14 && worker.cargo == null && worker.pendingStock == null &&
            plan.worldWork == null && plan.autonomousWork == null && plan.industryWork == null &&
            plan.courier == null && plan.token.constructionId == null && plan.token.craftJobId == null &&
            plan.pendingStockDelivery == null && !plan.suppliesNeeded &&
            actor.pendingChunkTarget == null && actor.residentYield == null && actor.terrainObstructedAt == null &&
            actor.social.visit == null && !actor.sleeping && !actor.sheltering && actor.attacker == null &&
            actor.workerId !in runtime.pendingHarm && actor.mealVisit == null && actor.foodSource == null &&
            actor.tavernService == null && actor.supplyPickup == null && actor.outputReturn == null &&
            !actor.returningToWarehouse && actor.collectionSeek == null && actor.collectionHome == null
    }

    private fun safeGround(city: Settlement, world: World, ground: Location): Boolean {
        val point = CivPosition(ground.blockX, ground.blockY, ground.blockZ)
        return city.contains(point.x, point.z) && world.worldBorder.isInside(ground) &&
            citizenControlClear(city, point) && citizenIdleTerrain(world, point, city.buildings) &&
            citizenPacketSupported(world, ground.point(), allowClimbable = false)
    }

    private fun clear(actor: CitizenActor, world: World, ground: Location, bounds: BoundingBox): Boolean =
        citizenDiningVolumeClear(world, bounds) && collisions().shelterOccupied(actor).none(bounds::overlaps) &&
            world.players.none { it.isOnline && it.isCollidable && it.location.distanceSquared(ground) < 9.0 &&
                bounds.overlaps(it.boundingBox) }

    private fun hasViewer(world: World, ground: Location, excluding: UUID? = null): Boolean =
        world.players.any { it.uniqueId != excluding && it.isOnline && !it.hasMetadata("NPC") &&
            it.location.distanceSquared(ground) <= 24.0 * 24.0 }

    private fun seatedBounds(ground: Location, yaw: Float, scale: Double): BoundingBox {
        // Cover the seated body and the chair back, with standing clearance.
        val sin = sin(Math.toRadians(yaw.toDouble()))
        val cos = cos(Math.toRadians(yaw.toDouble()))
        val centerX = ground.x - .16 * sin * scale
        val centerZ = ground.z + .16 * cos * scale
        val halfX = (.49 * abs(cos) + .60 * abs(sin)) * scale
        val halfZ = (.49 * abs(sin) + .60 * abs(cos)) * scale
        return BoundingBox(centerX - halfX, ground.y + .001, centerZ - halfZ,
            centerX + halfX, ground.y + 1.799 * scale, centerZ + halfZ).union(diningStandingBounds(ground, scale))
    }

    private companion object {
        val IDLE_REASONS = setOf("unassigned", "no-construction", "waiting-growth", "waiting-orders", "storage-surplus")
    }
}
