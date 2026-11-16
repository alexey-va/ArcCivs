package ru.arc.civs

import java.util.UUID
import kotlin.math.abs

/** The two immutable paid corridors and, when needed, their already accepted Hall projection. */
data class CivGateSharedApproach(
    val source: CivBuilding, val sourceCapture: CivPerimeterCapture,
    val donor: CivBuilding, val donorCapture: CivPerimeterCapture,
    val donorRoute: List<CivPosition>, val donorTravel: List<CivPosition>,
    val hall: CivHallApproach? = null, val hallFloors: List<CivPosition> = emptyList(),
) {
    internal fun detached() = copy(source = source.copy(transit = source.transit?.toList()),
        donor = donor.copy(transit = donor.transit?.toList()), donorRoute = donorRoute.toList(),
        donorTravel = donorTravel.toList(), hall = hall?.detached(), hallFloors = hallFloors.toList())

    /** Accepted geometry belongs to the gate; later removal of the donor cannot invalidate a saved city. */
    internal fun validated(city: Settlement, owner: CivBuilding, before: List<CivPosition>, after: List<CivPosition>) {
        require(source.id == owner.id && source.type == BuildingType.GATE && source.phase == BuildingPhase.ACTIVE &&
            source.anchor == owner.anchor && source.rotation == owner.rotation && source.bounds == owner.bounds &&
            source.entrance == owner.entrance && source.transit == owner.transit)
        require(donor.id != source.id && donor.phase == BuildingPhase.ACTIVE && !donor.type.isInfrastructure &&
            donor.type != BuildingType.TOWN_HALL && sourceCapture.valid() && donorCapture.valid() &&
            sourceCapture.id == CivsWarehouseRefit.retainedPlanId(source) &&
            donorCapture.id == CivsWarehouseRefit.retainedPlanId(donor))
        for (route in listOf(before, after, donorRoute, donorTravel)) {
            require(route.size in 4..CivsAutonomousTerraform.MAXIMUM_ROUTE_LENGTH &&
                route.all { city.contains(it.x, it.z) && it.y in -4093..4094 } &&
                route.distinctBy { it.x to it.z }.size == route.size && route.zipWithNext().all { (a, b) ->
                    abs(a.x.toLong() - b.x) + abs(a.z.toLong() - b.z) == 1L && abs(a.y.toLong() - b.y) <= 1L })
        }
        require(before.last() == source.entrance && donorRoute.last() == donor.entrance &&
            donorTravel.first() == donorRoute.first() && donorTravel.last() == donorRoute.last())
        require(hallFloors.size <= 512 && hallFloors.distinctBy { it.x to it.z }.size == hallFloors.size &&
            hallFloors.all { city.contains(it.x, it.z) && it.y in -4094..4093 })
        require(if (hall == null) hallFloors.isEmpty() && donorTravel == donorRoute else hall.founding.valid() && hallFloors.isNotEmpty())
        hall?.let { witness ->
            require(witness.branches.isNotEmpty())
            for (branches in listOf(witness.branches, witness.crossings.orEmpty())) {
                require(branches.size <= CivsRules.MAX_SITES && branches.distinctBy { it.owner }.size == branches.size)
                for (branch in branches) {
                    require(CivPerimeterCapture(branch.plan, branch.routeSha256).valid() &&
                        branch.prefix.size in 2..CivsAutonomousTerraform.MAXIMUM_ROUTE_LENGTH &&
                        branch.prefix.all { city.contains(it.x, it.z) && it.y in -4093..4094 } &&
                        branch.prefix.distinctBy { it.x to it.z }.size == branch.prefix.size &&
                        branch.prefix.zipWithNext().all { (a, b) -> abs(a.x.toLong() - b.x) + abs(a.z.toLong() - b.z) == 1L &&
                            abs(a.y.toLong() - b.y) <= 1L })
                }
            }
        }
        require(CivsGateSharedApproaches.replacement(before, donorRoute, donorTravel) == after)
        val allowed = CivsAutonomousTerraform.roadProfile(before).values.toSet() +
            CivsAutonomousTerraform.roadProfile(donorRoute).values + hallFloors
        require(CivsAutonomousTerraform.roadProfile(after).values.all { it in allowed })
    }
}

/** Detached, bounded projection of an existing plan; no schematic blocks or second registry. */
internal data class CivSharedRoadCapture(val source: CivBuilding, val capture: CivPerimeterCapture,
    val route: List<CivPosition>, val pavement: List<CivPlanBlock>)

internal object CivsGateSharedApproaches {
    /** A relocated control does not rewrite the immutable approach. Receipts still pin the full current owner. */
    private fun sameRoadSource(saved: CivBuilding, current: CivBuilding): Boolean = saved.id == current.id &&
        saved.type == current.type && saved.level == current.level && saved.schematicId == current.schematicId &&
        saved.anchor == current.anchor && saved.rotation == current.rotation && saved.bounds == current.bounds &&
        saved.entrance == current.entrance && saved.transit == current.transit && saved.warehouseCaptureId == current.warehouseCaptureId &&
        saved.phase in setOf(BuildingPhase.CONSTRUCTING, BuildingPhase.ACTIVE)
    fun proof(repair: CivRoadRouteOverride?): CivGateSharedApproach? =
        repair?.gateDetours?.singleOrNull()?.sharedApproach

    fun repair(city: Settlement, upkeep: CivRoadUpkeep): CivRoadRouteOverride? =
        (upkeep.routeOverride ?: upkeep.wallCleanup?.sharedRoute ?: city.roadRouteOverrides?.get(upkeep.source.id.toString()))
            ?.takeIf { proof(it) != null }

    fun connector(repair: CivRoadRouteOverride): CivPosition = repair.route[repair.route.lastIndex - 1].let { it.copy(y = it.y - 1) }

    fun currentOwners(city: Settlement, repair: CivRoadRouteOverride): Boolean {
        val proof = proof(repair) ?: return false
        return city.construction == null && city.constructionQueue.isNullOrEmpty() &&
            city.building(proof.source.id) == proof.source && city.building(proof.donor.id) == proof.donor &&
            city.roadRouteOverrides?.containsKey(proof.donor.id.toString()) != true &&
            city.roadRouteOverrides?.get(proof.source.id.toString())?.let { it == repair } != false
    }

    fun validateWork(city: Settlement, upkeep: CivRoadUpkeep, repair: CivRoadRouteOverride) {
        val proof = requireNotNull(proof(repair))
        repair.validated(city, upkeep.source)
        require(currentOwners(city, repair) && upkeep.source == proof.source && upkeep.route == repair.route &&
            upkeep.floor == connector(repair) && upkeep.after == "minecraft:dirt_path" && upkeep.neighbours.isEmpty() &&
            upkeep.linkedRouteOverrides == null && upkeep.previousRouteOverrides == null && upkeep.junction == null &&
            upkeep.hall == null && upkeep.authoredAfter == null && upkeep.wallCleanup == null)
    }

    /** One straight parallel tail, two cells apart. No search or new corridor is introduced. */
    fun replacement(before: List<CivPosition>, donor: List<CivPosition>, travel: List<CivPosition>): List<CivPosition>? {
        if (before.size !in 4..30 || donor.size !in 4..256 || travel.size !in 4..256 ||
            before.first() != donor.first() || travel.first() != before.first()) return null
        val common = before.zip(donor).takeWhile { (a, b) -> a == b }.size
        if (common < 2 || common >= before.size || common >= donor.size) return null
        val fork = before[common - 1]
        val entry = before.last()
        val dx = (entry.x - fork.x).compareTo(0)
        val dz = (entry.z - fork.z).compareTo(0)
        if (abs(dx) + abs(dz) != 1 || entry.y != fork.y || before.drop(common - 1).zipWithNext().any { (a, b) ->
                b.x - a.x != dx || b.z - a.z != dz || a.y != fork.y || b.y != fork.y }) return null
        val at = donor.indices.firstOrNull { i -> i >= common && donor[i].y == entry.y &&
            (donor[i].x - entry.x) * dx + (donor[i].z - entry.z) * dz == 0 &&
            abs(donor[i].x - entry.x) + abs(donor[i].z - entry.z) == 2 } ?: return null
        val join = donor[at]
        val sx = (join.x - entry.x).compareTo(0)
        val sz = (join.z - entry.z).compareTo(0)
        val expected = buildList {
            add(fork)
            for (n in 1..2) add(fork.copy(x = fork.x + sx * n, z = fork.z + sz * n))
            val length = abs(entry.x - fork.x) + abs(entry.z - fork.z)
            if (length !in 3..24) return null
            for (n in 1..length) add(fork.copy(x = fork.x + sx * 2 + dx * n, z = fork.z + sz * 2 + dz * n))
        }
        if (donor.subList(common - 1, at + 1) != expected) return null
        val projectedJoin = travel.indexOf(join)
        if (projectedJoin < 0) return null
        val result = travel.take(projectedJoin + 1) + entry.copy(x = entry.x + sx, z = entry.z + sz) + entry
        if (result.size !in 4..30 || result.distinctBy { it.x to it.z }.size != result.size ||
            (travel.toSet() + result).size >= (travel.toSet() + before).size) return null
        return result
    }

    fun captures(city: Settlement, plans: Map<UUID, CivPlacementPlan>): Map<UUID, CivSharedRoadCapture> = buildMap {
        for (source in city.buildings) {
            if (source.phase != BuildingPhase.ACTIVE || source.type == BuildingType.TOWN_HALL ||
                source.type.isInfrastructure && source.type != BuildingType.GATE) continue
            val plan = plans[source.id] ?: continue
            val capture = CivPerimeterCapture(plan.id, plan.capturedSha256 ?: continue)
            val route = plan.terrain?.route ?: continue
            if (plan.adminPlacement || plan.id != CivsWarehouseRefit.retainedPlanId(source) || !capture.valid() ||
                plan.cityId != city.id || plan.worldId != city.worldId || !sameRoadSource(plan.building, source) ||
                route.size !in 4..256 || route.last() != source.entrance) continue
            val pavement = plan.terrain.blocks.filter { it.before != it.after && it.after in CivsWallApproachCleanup.FINISHES &&
                CivsAutonomousTerraform.roadProfileContains(route, it.position) }
            put(source.id, CivSharedRoadCapture(source, capture, route.toList(), pavement.toList()))
        }
    }

    fun current(city: Settlement, repair: CivRoadRouteOverride, access: CivAutonomousAccess): Boolean {
        val proof = proof(repair) ?: return false
        if (!currentOwners(city, repair)) return false
        val source = access.sharedRoadCaptures[proof.source.id] ?: return false
        val donor = access.sharedRoadCaptures[proof.donor.id] ?: return false
        if (source.capture != proof.sourceCapture || source.route != repair.originalRoute ||
            donor.capture != proof.donorCapture || donor.route != proof.donorRoute) return false
        val hall = access.hallApproach(city)
        return hall?.witness == proof.hall && (hall?.travel(donor.route) ?: donor.route) == proof.donorTravel &&
            hall?.surfaces.orEmpty().keys.toSet() == proof.hallFloors.toSet()
    }

    fun candidates(city: Settlement, access: CivAutonomousAccess): List<CivRoadRouteOverride> {
        val capacity = CivsRules.MAX_BUILDINGS - city.roadRouteOverrides.orEmpty().size
        if (city.construction != null || !city.constructionQueue.isNullOrEmpty() ||
            capacity <= 0) return emptyList()
        val hall = access.hallApproach(city)
        val result = mutableListOf<CivRoadRouteOverride>()
        for (source in access.sharedRoadCaptures.values) {
            if (source.source.type != BuildingType.GATE || CivsInfrastructureRebuild.version(source.source) != 5 ||
                source.capture.id != source.source.id || !CivsGateRefit.validTransit(source.source) ||
                city.roadRouteOverrides?.containsKey(source.source.id.toString()) == true) continue
            for (donor in access.sharedRoadCaptures.values) {
                if (donor.source.type.isInfrastructure || city.roadRouteOverrides?.containsKey(donor.source.id.toString()) == true) continue
                val travel = hall?.travel(donor.route) ?: donor.route
                val route = replacement(source.route, donor.route, travel) ?: continue
                val proof = CivGateSharedApproach(source.source, source.capture, donor.source, donor.capture, donor.route,
                    travel, access.hallWitness.takeIf { hall != null }, hall?.surfaces.orEmpty().keys.toList())
                val detour = CivGateRouteDetour(source.source.id, source.capture.id, source.route, route, proof)
                val repair = CivRoadRouteOverride(source.route, route, listOf(detour))
                if (runCatching { repair.validated(city, source.source) }.isFailure) continue
                result += repair
                if (result.size == capacity) return result
                break // Other gates remain selectable when this one's native work is blocked.
            }
        }
        return result
    }

    fun targets(city: Settlement, access: CivAutonomousAccess): List<CivRoadUpkeep> = buildList {
        val repairs = candidates(city, access) + city.roadRouteOverrides.orEmpty().values.filter { proof(it) != null }
        for (repair in repairs) {
            if (!current(city, repair, access)) continue
            val proof = requireNotNull(proof(repair))
            val cleanup = CivsWallApproachCleanup.sharedTargets(city, access, repair)
            if (cleanup.isEmpty()) continue
            val install = repair.takeIf { city.roadRouteOverrides?.containsKey(proof.source.id.toString()) != true }
            add(CivRoadUpkeep(city.id, city.worldId, city.owner, proof.source.id, proof.source, connector(repair),
                "minecraft:dirt_path", repair.route, emptyList(), routeOverride = install))
            addAll(cleanup)
        }
    }
}
