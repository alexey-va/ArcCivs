package ru.arc.civs

import java.util.UUID
import kotlin.math.abs
import org.bukkit.Bukkit

/** One bounded lowering at a rising turn, retaining its exact original route as the authority witness. */
data class CivRoadRouteOverride(val originalRoute: List<CivPosition>, val route: List<CivPosition>) {
    internal fun changed(): List<Int> = originalRoute.indices.filter { originalRoute[it] != route[it] }

    internal fun validated(city: Settlement, source: CivBuilding) {
        require(!source.type.isInfrastructure)
        for (points in listOf(originalRoute, route)) {
            require(points.size in 4..CivsAutonomousTerraform.MAXIMUM_ROUTE_LENGTH && points.last() == source.entrance)
            require(points.all { city.contains(it.x, it.z) && it.y in -4_093..4_094 } &&
                points.map { civsTerrainKey(it.x, it.z) }.distinct().size == points.size)
            require(points.zipWithNext().all { (a, b) -> abs(a.x.toLong() - b.x) + abs(a.z.toLong() - b.z) == 1L &&
                abs(a.y.toLong() - b.y) <= 1L })
        }
        require(route.size == originalRoute.size && route.first() == originalRoute.first() &&
            route.zip(originalRoute).all { (a, b) -> a.x == b.x && a.z == b.z })
        val changed = changed()
        require(changed.size in 1..2 && changed.first() > 0 && changed.last() < route.lastIndex &&
            changed.zipWithNext().all { (a, b) -> b == a + 1 } &&
            changed.all { route[it].y == originalRoute[it].y - 1 })
        val at = changed.first()
        require(originalRoute[at - 1].x != originalRoute[at + 1].x && originalRoute[at - 1].z != originalRoute[at + 1].z)
        require(originalRoute[at].y == originalRoute[at - 1].y + 1 && route[at].y == route[at - 1].y &&
            route[at + 1].y in route[at].y..route[at].y + 1)
    }
}

/** A bounded witness copied from an accepted capture, never a replacement for that capture. */
data class CivRoadUpkeep(
    val cityId: UUID,
    val worldId: UUID,
    val owner: UUID,
    val planId: UUID,
    val source: CivBuilding,
    val floor: CivPosition,
    val after: String,
    /** Null denotes an authored ROAD transit cell; approaches retain the route that owns their profile. */
    val route: List<CivPosition>?,
    val neighbours: List<CivPosition>,
    val routeOverride: CivRoadRouteOverride? = null,
    /** Exact retained ROAD capture finish when its unordered transit cannot prove a turn or stair facing. */
    val authoredAfter: String? = null,
) {
    internal fun validated(city: Settlement) {
        require(city.id == cityId && city.worldId == worldId && city.owner == owner && city.pendingDeletion == null)
        require(planId == source.id && source.phase == BuildingPhase.ACTIVE && city.building(source.id) == source)
        require(city.contains(floor.x, floor.z) && floor.y in -4_094..4_093 && CivsRoadMaintenance.surface(after))
        require(neighbours.size <= 4 && neighbours.map { civsTerrainKey(it.x, it.z) }.distinct().size == neighbours.size)
        require(neighbours.all { abs(it.x.toLong() - floor.x) + abs(it.z.toLong() - floor.z) == 1L &&
            abs(it.y.toLong() - floor.y) <= 1L && city.contains(it.x, it.z) })
        val profile = (neighbours + floor).associate { civsTerrainKey(it.x, it.z) to it.y }
        if (authoredAfter != null) require(route == null && source.type == BuildingType.ROAD &&
            CivsRoadMaintenance.surface(authoredAfter))
        routeOverride?.let { repair ->
            repair.validated(city, source)
            require(city.roadRouteOverrides?.containsKey(source.id.toString()) != true && route == repair.route &&
                floor == repair.route[repair.changed().first()].let { it.copy(y = it.y - 1) } &&
                after == "minecraft:${CivsSettlementLayout.wood(city.style)}_planks")
        }
        if (routeOverride == null) city.roadRouteOverrides?.get(source.id.toString())?.let { require(route == it.route) }
        if (route == null) {
            require(source.type == BuildingType.ROAD && source.transit.orEmpty().contains(floor.copy(y = floor.y + 1)))
            require(after == "minecraft:cobblestone" || after.startsWith("minecraft:cobblestone_stairs[") ||
                after == CivsSettlementLayout.pathPavement(floor, profile, city.style, legacyStairAtTurn = true) ||
                authoredAfter?.replace("minecraft:cobblestone_stairs[", "minecraft:${CivsSettlementLayout.wood(city.style)}_stairs[") == after &&
                    (CivsRoadMaintenance.authoredStep(floor, after, profile, city.style) ||
                        CivsRoadMaintenance.landing(after) &&
                            after == CivsSettlementLayout.pathPavement(floor, profile, city.style, atTurn = true)))
        } else {
            require(!source.type.isInfrastructure && route.size in 1..CivsAutonomousTerraform.MAXIMUM_ROUTE_LENGTH &&
                route.last() == source.entrance &&
                CivsAutonomousTerraform.roadProfile(route)[civsTerrainKey(floor.x, floor.z)] == floor)
            require(route.all { city.contains(it.x, it.z) && it.y in -4_093..4_094 } &&
                route.map { civsTerrainKey(it.x, it.z) }.distinct().size == route.size)
            require(route.zipWithNext().all { (a, b) ->
                abs(a.x.toLong() - b.x) + abs(a.z.toLong() - b.z) == 1L && abs(a.y.toLong() - b.y) <= 1L
            })
            // Completed receipts from the original straight-only finish remain recoverable.
            require(after == CivsSettlementLayout.pavement(floor, profile) ||
                after == CivsSettlementLayout.pathPavement(floor, profile, city.style,
                    atTurn = floor in CivsSettlementLayout.turnFloors(route), downhill = CivsSettlementLayout.routeDownhill(floor, route)) ||
                after == CivsSettlementLayout.pathPavement(floor, profile, city.style, legacyStairAtTurn = true))
        }
    }
}

/** One paid native column. The existing inventory journal owns replay, locks and retirement. */
data class CivRoadUpkeepReceipt(val workerBefore: CivWorker, val stockBefore: Stock,
    val work: CivWorldWork, val acceptedAt: Long) {
    fun before(after: Settlement): Settlement = after.copy(stock = stockBefore,
        workers = after.workers.map { if (it.id == workerBefore.id) workerBefore else it }, revision = after.revision - 1,
        roadRouteOverrides = if (work.upkeep?.routeOverride != null)
            after.roadRouteOverrides?.minus(work.upkeep.source.id.toString())?.takeIf { it.isNotEmpty() } else after.roadRouteOverrides)

    fun validated(after: Settlement) {
        require(after.inventory == null && after.autonomy != null && work.workerId == workerBefore.id && acceptedAt >= 0)
        require(CivsRoadMaintenance.complete(before(after), work, acceptedAt) == after)
    }
}

/** Detached, reconstructible read model of existing retained journals. No new persistent job or registry. */
internal data class CivRoadUpkeepNetwork(val cityId: UUID, val worldId: UUID,
    val buildings: List<CivBuilding>, val targets: List<CivRoadUpkeep>,
    val routeOverrides: Map<String, CivRoadRouteOverride>? = null)

/** Main-thread native scan owner; only preparation of the detached network runs off-thread. */
internal class CivsRoadMaintenance(private val world: CivsWorld, private val currentTick: () -> Int = Bukkit::getCurrentTick) {
    private val networks = mutableMapOf<UUID, CivRoadUpkeepNetwork>()
    private val cursors = mutableMapOf<UUID, Int>()
    private val selected = mutableMapOf<UUID, CivWorldWork>()
    private var budgetTick = Int.MIN_VALUE
    private var remaining = 0
    private val waiting = linkedMapOf<UUID, Int>()

    /** Fixed citizen iteration cannot let one healthy town consume every native probe forever. */
    private fun budget(workerId: UUID): Boolean {
        val tick = currentTick()
        if (budgetTick != tick) { budgetTick = tick; remaining = SCAN_COLUMNS }
        waiting.entries.removeIf { (_, seen) -> tick < seen || tick.toLong() - seen > 40L }
        waiting[workerId] = tick
        if (remaining == 0 || waiting.keys.first() != workerId) return false
        remaining--
        waiting.remove(workerId)
        waiting[workerId] = tick
        return true
    }

    fun publish(city: Settlement, prepared: CivRoadUpkeepNetwork) {
        require(prepared.cityId == city.id && prepared.worldId == city.worldId && prepared.buildings == city.buildings &&
            prepared.routeOverrides == city.roadRouteOverrides)
        val previous = networks.put(city.id, prepared)
        if (previous?.buildings != prepared.buildings || previous?.routeOverrides != prepared.routeOverrides) {
            cursors.remove(city.id)
            city.workers.forEach { selected.remove(it.id) }
        }
    }

    fun plan(city: Settlement, worker: CivWorker): CivWorldWork? {
        if (!eligible(city) || worker.assignment?.let(city::building)?.let {
                it.type == BuildingType.BUILDER_HUT && it.phase == BuildingPhase.ACTIVE
            } != true || CivsResidents.isChild(worker) || worker.cargo != null || !funded(city)) {
            selected.remove(worker.id)
            waiting.remove(worker.id)
            return null
        }
        val network = networks[city.id]?.takeIf { it.worldId == city.worldId && it.buildings == city.buildings &&
            it.routeOverrides == city.roadRouteOverrides } ?: return null
        selected[worker.id]?.let { work ->
            if (work.cycle == worker.completedCycles && work.buildingId == worker.assignment &&
                (!budget(worker.id) || world.roadUpkeepPending(city, work))) return work
            selected.remove(worker.id)
        }
        if (network.targets.isEmpty()) return null
        repeat(minOf(SCAN_COLUMNS, network.targets.size)) {
            if (!budget(worker.id)) return null
            val cursor = Math.floorMod(cursors[city.id] ?: 0, network.targets.size)
            cursors[city.id] = (cursor + 1) % network.targets.size
            val work = world.captureRoadUpkeep(city, worker, network.targets[cursor]) ?: return@repeat
            selected[worker.id] = work
            return work
        }
        return null
    }

    fun invalidate(workerId: UUID) { selected.remove(workerId); waiting.remove(workerId) }
    fun clear() { networks.clear(); cursors.clear(); selected.clear(); waiting.clear() }

    companion object {
        private const val SCAN_COLUMNS = 8
        internal const val MAX_CLEARANCE = 4
        private val directions = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        private val stair = Regex("minecraft:(cobblestone|oak|spruce|dark_oak)_stairs\\[facing=(north|south|east|west),half=bottom," +
            "shape=(straight|inner_left|inner_right|outer_left|outer_right),waterlogged=false]")

        internal fun landing(data: String): Boolean = data in setOf("minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:dark_oak_planks")

        internal fun surface(data: String): Boolean = data == "minecraft:cobblestone" || data == "minecraft:dirt_path" ||
            landing(data) || stair.matches(data)

        internal fun authoredStep(floor: CivPosition, data: String, profile: Map<Long, Int>, style: String): Boolean {
            if (!stair.matches(data) || !data.startsWith("minecraft:${CivsSettlementLayout.wood(style)}_stairs[")) return false
            val (dx, dz) = when (data.substringAfter("facing=").substringBefore(',')) {
                "east" -> -1 to 0; "west" -> 1 to 0; "south" -> 0 to -1; else -> 0 to 1
            }
            return profile[civsTerrainKey(floor.x + dx, floor.z + dz)] == floor.y - 1 &&
                directions.any { (x, z) -> (x to z) != (dx to dz) &&
                    profile[civsTerrainKey(floor.x + x, floor.z + z)]?.let { it in floor.y..floor.y + 1 } == true }
        }

        internal fun turnOverride(city: Settlement, source: CivBuilding, route: List<CivPosition>): CivRoadRouteOverride? {
            for (at in 1 until route.lastIndex) {
                if (route[at - 1].x == route[at + 1].x || route[at - 1].z == route[at + 1].z ||
                    route[at].y != route[at - 1].y + 1) continue
                val lowered = route.toMutableList()
                lowered[at] = lowered[at].copy(y = lowered[at].y - 1)
                if (lowered[at + 1].y > lowered[at].y + 1)
                    lowered[at + 1] = lowered[at + 1].copy(y = lowered[at + 1].y - 1)
                val repair = CivRoadRouteOverride(route.toList(), lowered.toList())
                if (runCatching { repair.validated(city, source) }.isSuccess) return repair
            }
            return null
        }

        /** Natural cut banks only; road surfaces, falling terrain and crafted blocks are never excavated. */
        internal fun clearanceGround(data: String): Boolean = data.substringBefore('[') in setOf(
            "minecraft:grass_block", "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:rooted_dirt",
            "minecraft:podzol", "minecraft:mycelium", "minecraft:moss_block", "minecraft:clay", "minecraft:stone",
            "minecraft:deepslate", "minecraft:granite", "minecraft:diorite", "minecraft:andesite", "minecraft:tuff")

        internal fun covers(floor: CivPosition, lower: String, upper: String): List<CivPlanBlock>? =
            covers(floor, listOf(lower, upper))

        /** A bounded open cut; paired plants require both exact halves in this same receipt. */
        internal fun covers(floor: CivPosition, cells: List<String>): List<CivPlanBlock>? {
            require(cells.size in 2..MAX_CLEARANCE)
            fun air(data: String) = data in setOf("minecraft:air", "minecraft:cave_air", "minecraft:void_air")
            fun clear(y: Int, before: String) = CivPlanBlock(floor.copy(y = floor.y + y), before, "minecraft:air")
            val changes = mutableListOf<CivPlanBlock>()
            var index = 0
            while (index < cells.size) {
                val data = cells[index]
                if (air(data)) { index++; continue }
                val plant = listOf("tall_grass", "large_fern").firstOrNull { data == "minecraft:$it[half=lower]" }
                if (plant != null) {
                    if (cells.getOrNull(index + 1) != "minecraft:$plant[half=upper]") return null
                    changes += clear(index + 1, data)
                    changes += clear(index + 2, cells[index + 1])
                    index += 2
                } else {
                    if (!clearanceGround(data) && data !in setOf("minecraft:short_grass", "minecraft:fern")) return null
                    changes += clear(index + 1, data)
                    index++
                }
            }
            return changes
        }

        internal fun validateChanges(upkeep: CivRoadUpkeep, changes: List<CivPlanBlock>) {
            require(changes.size in 1..MAX_CLEARANCE + 1 && changes.map { it.position }.distinct().size == changes.size)
            require(changes.all { it.before != it.after })
            val floor = changes.firstOrNull()?.takeIf { it.position == upkeep.floor }
            require(floor == null || floor.after == upkeep.after)
            val cover = changes.filter { it.position != upkeep.floor }
            val expected = covers(upkeep.floor, (1..MAX_CLEARANCE).map { dy ->
                cover.firstOrNull { it.position == upkeep.floor.copy(y = upkeep.floor.y + dy) }?.before ?: "minecraft:air"
            })
            require(expected != null && changes == listOfNotNull(floor) + expected)
        }

        fun eligible(city: Settlement): Boolean = city.autonomy?.paused == false && city.inventory == null &&
            city.pendingDeletion == null && city.construction == null && city.constructionQueue.orEmpty().isEmpty()

        private fun funded(city: Settlement): Boolean =
            city.stock.stone > (CivsAutonomy.nextGoal(city)?.cost?.stone ?: 0L)

        /** One abstract construction-supply STONE per worked column; never a vanilla item recipe. */
        fun complete(city: Settlement, work: CivWorldWork, at: Long): Settlement {
            require(eligible(city) && funded(city) && at in 0..Long.MAX_VALUE - 1_000)
            val upkeep = requireNotNull(work.upkeep)
            upkeep.validated(city)
            val worker = requireNotNull(city.workers.firstOrNull { it.id == work.workerId })
            val builder = requireNotNull(worker.assignment?.let(city::building))
            require(builder.id == work.buildingId && builder.type == BuildingType.BUILDER_HUT && builder.phase == BuildingPhase.ACTIVE &&
                worker.completedCycles == work.cycle && worker.completedCycles < Long.MAX_VALUE &&
                worker.cargo == null && !CivsResidents.isChild(worker))
            validateChanges(upkeep, work.changes)
            require((work.focus == upkeep.floor || work.changes.any { it.position == work.focus }) &&
                work.status == "repairing-road" && work.durationMillis == 3_000L)
            require(work.inputs.isEmpty() && work.outputs.isEmpty() && work.equipmentAfter == null && work.forestry == null &&
                work.miningStep == null && work.miningPlanVersion == null && !work.miningGenerator && work.miningAccessStep == null)
            require(city.contains(work.standing.x, work.standing.z) && work.standing.y in -4_094..4_094 &&
                (work.standing.x != upkeep.floor.x || work.standing.z != upkeep.floor.z))
            require(city.revision < Long.MAX_VALUE)
            val updated = worker.copy(completedCycles = worker.completedCycles + 1, nextDeliveryAt = at + 1_000)
            return CivsRules.validate(city.copy(stock = city.stock.copy(stone = city.stock.stone - 1),
                workers = city.workers.map { if (it.id == worker.id) updated else it }, revision = city.revision + 1,
                roadRouteOverrides = upkeep.routeOverride?.let { city.roadRouteOverrides.orEmpty() + (upkeep.source.id.toString() to it) }
                    ?: city.roadRouteOverrides))
        }

        /** Capture order owns crossings; incompatible grades and authored entrances remain untouched. */
        fun prepare(city: Settlement, plans: List<CivPlacementPlan>): CivRoadUpkeepNetwork {
            val access = CivAutonomousAccess.from(city, plans)
            val floors = linkedMapOf<Long, CivPosition>()
            val ambiguous = hashSetOf<Long>()
            fun remember(p: CivPosition) {
                val key = civsTerrainKey(p.x, p.z)
                val old = floors.putIfAbsent(key, p)
                if (old != null && old.y != p.y) ambiguous += key
            }
            val roadColumns = hashSetOf<Long>()
            val authoredFinishes = hashMapOf<Long, String>()
            val candidates = linkedMapOf<Long, Pair<CivBuilding, CivPosition>>()
            val accepted = access.infrastructure.associateBy { it.id }
            city.buildings.filter { it.phase == BuildingPhase.ACTIVE && it.type == BuildingType.ROAD }.forEach { source ->
                val road = accepted[source.id] ?: return@forEach
                val after = road.blocks.associate { it.position to it.after }
                source.transit.orEmpty().forEach { feet ->
                    val floor = feet.copy(y = feet.y - 1)
                    val key = civsTerrainKey(floor.x, floor.z)
                    roadColumns += key
                    remember(floor)
                    after[floor]?.takeIf(::surface)?.let {
                        if (candidates.putIfAbsent(key, source to floor) == null && (landing(it) || stair.matches(it)))
                            authoredFinishes[key] = it
                    }
                }
            }
            city.buildings.filter { it.phase == BuildingPhase.ACTIVE && !it.type.isInfrastructure }.forEach { source ->
                access.routes[source.id].orEmpty().forEach { feet ->
                    val floor = feet.copy(y = feet.y - 1)
                    val key = civsTerrainKey(floor.x, floor.z)
                    remember(floor)
                    if (key !in roadColumns) candidates.putIfAbsent(key, source to floor)
                }
            }
            val protected = civsProtectedBuildings(city).filter { it.type != BuildingType.ROAD }
            val reserved = protected.associate { it.id to CivsAutonomousGeometry.futureBounds(city, it) }
            fun excluded(floor: CivPosition) = !city.contains(floor.x, floor.z) || protected.any {
                CivsAutonomousGeometry.horizontalContains(reserved.getValue(it.id), floor) ||
                    CivsWorksiteLayouts.isField(it, floor.x, floor.z)
            }
            // Every accepted centreline wins before any shoulder. Never regrade another paid route.
            city.buildings.filter { it.phase == BuildingPhase.ACTIVE && !it.type.isInfrastructure }.forEach { source ->
                CivsAutonomousTerraform.roadProfile(access.routes[source.id].orEmpty()).forEach { (key, floor) ->
                    if (key !in floors && !excluded(floor)) {
                        floors[key] = floor
                        candidates[key] = source to floor
                    }
                }
            }
            val held = hashSetOf<Long>()
            val gradeTargets = city.buildings.filter { it.phase == BuildingPhase.ACTIVE && !it.type.isInfrastructure }
                .mapNotNull { source ->
                    if (city.roadRouteOverrides?.containsKey(source.id.toString()) == true) return@mapNotNull null
                    val repair = turnOverride(city, source, access.routes[source.id].orEmpty()) ?: return@mapNotNull null
                    val at = repair.changed().first()
                    // Do not raise the old corner or flatten its outgoing stair before the new grade is committed.
                    repair.route.subList(at - 1, minOf(repair.route.size, at + 3)).forEach { p ->
                        for (dx in -1..1) for (dz in -1..1) held += civsTerrainKey(p.x + dx, p.z + dz)
                    }
                    val oldProfile = CivsAutonomousTerraform.roadProfile(repair.originalRoute)
                    val corrected = CivsAutonomousTerraform.roadProfile(repair.route)
                    val retained = access.standing(city, source.id)
                    if (repair.changed().any { index -> repair.route[index].let { feet ->
                            excluded(feet.copy(y = feet.y - 1)) || civsTerrainKey(feet.x, feet.z) in ambiguous
                        } } || corrected.any { (key, point) -> oldProfile[key] != point && !excluded(point) &&
                            retained[key]?.let { it.y - 1 != point.y } == true })
                        return@mapNotNull null
                    val floor = repair.route[at].let { it.copy(y = it.y - 1) }
                    val neighbours = directions.mapNotNull { (dx, dz) -> corrected[civsTerrainKey(floor.x + dx, floor.z + dz)] }
                        .filter { abs(it.y.toLong() - floor.y) <= 1L && !excluded(it) }
                    CivRoadUpkeep(city.id, city.worldId, city.owner, source.id, source, floor,
                        "minecraft:${CivsSettlementLayout.wood(city.style)}_planks", repair.route, neighbours, repair)
                        .also { it.validated(city) }
                }
            val targets = candidates.mapNotNull { (key, candidate) ->
                val (source, floor) = candidate
                if (key in ambiguous || key in held || excluded(floor)) return@mapNotNull null
                val neighbours = directions.mapNotNull { (dx, dz) -> floors[civsTerrainKey(floor.x + dx, floor.z + dz)] }
                    .filter { abs(it.y.toLong() - floor.y) <= 1L && civsTerrainKey(it.x, it.z) !in ambiguous && !excluded(it) }
                val profile = (neighbours + floor).associate { civsTerrainKey(it.x, it.z) to it.y }
                val route = access.routes[source.id]?.toList().takeIf { source.type != BuildingType.ROAD }
                // ROAD transit is a profile, not an ordered route. Preserve its captured facing or landing.
                val saved = authoredFinishes[key].takeIf { source.type == BuildingType.ROAD }
                val authored = saved?.replace("minecraft:cobblestone_stairs[", "minecraft:${CivsSettlementLayout.wood(city.style)}_stairs[")
                if (authored != null && !authoredStep(floor, authored, profile, city.style) &&
                    !(landing(authored) && authored == CivsSettlementLayout.pathPavement(floor, profile, city.style, atTurn = true)))
                    return@mapNotNull null
                val finish = authored ?: CivsSettlementLayout.pathPavement(floor, profile, city.style,
                    atTurn = route != null && floor in CivsSettlementLayout.turnFloors(route),
                    downhill = route?.let { CivsSettlementLayout.routeDownhill(floor, it) },
                    legacyStairAtTurn = source.type == BuildingType.ROAD)
                CivRoadUpkeep(city.id, city.worldId, city.owner, source.id, source, floor, finish,
                    route, neighbours.toList(), authoredAfter = saved)
                    .also { it.validated(city) }
            }
            return CivRoadUpkeepNetwork(city.id, city.worldId, city.buildings.toList(), gradeTargets + targets, city.roadRouteOverrides)
        }
    }
}
