package ru.arc.civs

import java.util.UUID
import kotlin.math.abs

/** A short detour belongs to one immutable refitted gate, never to arbitrary surrounding terrain. */
data class CivGateRouteDetour(val gateId: UUID, val captureId: UUID,
    val before: List<CivPosition>, val after: List<CivPosition>) {
    internal fun detached() = copy(before = before.toList(), after = after.toList())

    internal fun validated(city: Settlement, owner: CivBuilding) {
        val gate = requireNotNull(city.building(gateId))
        require(owner.id == gateId && gate.phase == BuildingPhase.ACTIVE && CivsGateRefit.validTransit(gate) &&
            CivsGateRefit.retainedPlanId(gate) == captureId)
        require(before.size in 3..30 && after.size in 3..30 && before != after &&
            before.first() == after.first() && before.last() == after.last())
        for (route in listOf(before, after)) {
            require(route.map { it.x to it.z }.distinct().size == route.size)
            require(route.all { gate.bounds.contains(it.x, it.y, it.z) && it.y in gate.bounds.min.y + 1..gate.bounds.max.y - 4 })
            require(route.zipWithNext().all { (a, b) -> step(a, b) })
        }
        require(after.all { it in gate.transit.orEmpty() })
        require(before.any { it !in gate.transit.orEmpty() })
    }
}

/** Reuses the existing route-override owner; only a gate's own approach may detour inside its paid floor. */
internal object CivsGateRoutes {
    fun refit(city: Settlement, old: CivBuilding, target: CivBuilding, original: List<CivPosition>): CivRoadRouteOverride? {
        val prior = city.roadRouteOverrides?.get(old.id.toString())
        require(prior == null || prior.originalRoute == original)
        val route = prior?.route ?: original
        if (route.isEmpty()) return null
        require(route.last() == old.entrance && route.size <= CivsAutonomousTerraform.MAXIMUM_ROUTE_LENGTH)
        val allowed = target.transit.orEmpty().toSet()
        val detours = mutableListOf<CivGateRouteDetour>()
        var result = route.toList()
        var at = 0
        while (at < route.size) {
            if (!inside(target.bounds, route[at])) { at++; continue }
            val start = at
            while (at < route.size && inside(target.bounds, route[at])) at++
            val section = route.subList(start, at)
            if (section.all { it in allowed }) continue
            rejectGateRoute(section.first() !in allowed || section.last() !in allowed, "gate-refit-route-conflict")
            val occupied = result.filter { it !in section }.mapTo(hashSetOf()) { it.x to it.z }
            val next = path(section.first(), section.last(), allowed.filter { (it.x to it.z) !in occupied }.toSet())
                ?: throw CivsRejection("gate-refit-route-conflict")
            val detour = CivGateRouteDetour(old.id, CivsGateRefit.retainedPlanId(target), section.toList(), next)
            result = replace(result, detour.before, detour.after)
            detours += detour
        }
        if (detours.isEmpty()) return null
        require(detours.size <= 4 && prior?.gateDetours.isNullOrEmpty())
        return CivRoadRouteOverride(original.toList(), result, detours.toList())
    }

    /** Reversing exact segments recovers the unchanged accepted route and any earlier vertical-only repair. */
    fun base(city: Settlement, source: CivBuilding, override: CivRoadRouteOverride): List<CivPosition> {
        val detours = requireNotNull(override.gateDetours)
        require(detours.size in 1..4)
        var route = override.route
        detours.asReversed().forEach { detour ->
            detour.validated(city, source)
            route = replace(route, detour.after, detour.before)
        }
        val first = detours.minOf { route.indexOf(it.before.first()) }
        require(first >= 0 && route.drop(maxOf(0, first - 2)) == override.originalRoute.drop(maxOf(0, first - 2))) {
            "A gate detour cannot move or reinterpret an accepted external grade repair"
        }
        return route
    }

    private fun path(from: CivPosition, to: CivPosition, allowed: Set<CivPosition>): List<CivPosition>? {
        require(allowed.size <= 30)
        val parents = linkedMapOf<CivPosition, CivPosition?>(from to null)
        val queue = ArrayDeque<CivPosition>().also { it.add(from) }
        val ordered = allowed.sortedWith(compareBy<CivPosition> { abs(it.x - to.x) + abs(it.z - to.z) }
            .thenBy { it.x }.thenBy { it.z }.thenBy { it.y })
        while (queue.isNotEmpty()) {
            val point = queue.removeFirst()
            if (point == to) return generateSequence(to) { parents[it] }.toList().asReversed()
            ordered.filter { it !in parents && step(point, it) }.forEach { parents[it] = point; queue.add(it) }
        }
        return null
    }

    private fun replace(route: List<CivPosition>, before: List<CivPosition>, after: List<CivPosition>): List<CivPosition> {
        require(before.isNotEmpty())
        val start = route.indexOf(before.first())
        require(start >= 0 && start + before.size <= route.size && route.subList(start, start + before.size) == before)
        return route.take(start) + after + route.drop(start + before.size)
    }

    private fun inside(box: CivBox, point: CivPosition) = point.x in box.min.x..box.max.x && point.z in box.min.z..box.max.z
}

private fun step(a: CivPosition, b: CivPosition): Boolean =
    abs(a.x.toLong() - b.x) + abs(a.z.toLong() - b.z) == 1L && abs(a.y.toLong() - b.y) <= 1L

private fun rejectGateRoute(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
