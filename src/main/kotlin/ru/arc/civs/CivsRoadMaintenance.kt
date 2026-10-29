package ru.arc.civs

import java.util.UUID
import kotlin.math.abs
import org.bukkit.Bukkit

/** Bounded local cuts, each checked against the immutable accepted route. */
data class CivRoadRouteOverride(val originalRoute: List<CivPosition>, val route: List<CivPosition>) {
    internal fun changed(): List<Int> = originalRoute.indices.filter { originalRoute[it] != route[it] }

    internal fun runs(): List<IntRange> = changed().fold(mutableListOf()) { result, index ->
        if (result.lastOrNull()?.last == index - 1) result[result.lastIndex] = result.last().first..index
        else result += index..index
        result
    }

    internal fun hump(run: IntRange): Boolean {
        if (run.count() > 9 || run.first <= 0 || run.last >= route.lastIndex) return false
        val level = originalRoute[run.first - 1].y
        val section = originalRoute.subList(run.first - 1, run.last + 2)
        return originalRoute[run.last + 1].y == level && run.all {
            originalRoute[it].y - level in 1..3 && route[it].y == level
        } && (section.all { it.x == section.first().x } || section.all { it.z == section.first().z })
    }

    internal fun validated(city: Settlement, source: CivBuilding) {
        // Gates and walls can share an accepted approach; authored ROAD transit keeps its own grade.
        require(source.type != BuildingType.ROAD)
        for (points in listOf(originalRoute, route)) {
            require(points.size in 4..CivsAutonomousTerraform.MAXIMUM_ROUTE_LENGTH && points.last() == source.entrance)
            require(points.all { city.contains(it.x, it.z) && it.y in -4_093..4_094 } &&
                points.map { civsTerrainKey(it.x, it.z) }.distinct().size == points.size)
            require(points.zipWithNext().all { (a, b) -> abs(a.x.toLong() - b.x) + abs(a.z.toLong() - b.z) == 1L &&
                abs(a.y.toLong() - b.y) <= 1L })
        }
        require(route.size == originalRoute.size && route.first() == originalRoute.first() &&
            route.zip(originalRoute).all { (a, b) -> a.x == b.x && a.z == b.z })
        require(changed().isNotEmpty())
        runs().forEach { run ->
            require(run.first > 0 && run.last < route.lastIndex)
            val at = run.first
            val turn = run.count() <= 2 && run.all { route[it].y == originalRoute[it].y - 1 } &&
                originalRoute[at - 1].x != originalRoute[at + 1].x && originalRoute[at - 1].z != originalRoute[at + 1].z &&
                originalRoute[at].y == originalRoute[at - 1].y + 1 && route[at].y == route[at - 1].y &&
                route[at + 1].y in route[at].y..route[at].y + 1
            require(turn || hump(run))
        }
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
    /** Only owners sharing the complete same-grade cut; committed in the same existing receipt. */
    val linkedRouteOverrides: Map<String, CivRoadRouteOverride>? = null,
    /** Exact pre-receipt values for touched owners; absent entries previously had no override. */
    val previousRouteOverrides: Map<String, CivRoadRouteOverride>? = null,
) {
    internal fun overrides(): Map<String, CivRoadRouteOverride> = routeOverride?.let {
        linkedRouteOverrides.orEmpty() + (source.id.toString() to it)
    }.orEmpty()

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
        if (routeOverride == null) require(linkedRouteOverrides == null && previousRouteOverrides == null)
        routeOverride?.let { repair ->
            repair.validated(city, source)
            require(city.roadRouteOverrides?.get(source.id.toString())?.originalRoute?.let { it == repair.originalRoute } != false)
            val repairs = overrides()
            require(repairs.size <= CivsRules.MAX_BUILDINGS && linkedRouteOverrides?.containsKey(source.id.toString()) != true)
            require(previousRouteOverrides.orEmpty() == city.roadRouteOverrides.orEmpty().filterKeys { it in repairs })
            val previous = city.roadRouteOverrides?.get(source.id.toString())?.route ?: repair.originalRoute
            val changed = repair.route.indices.filter { repair.route[it] != previous[it] }
            require(changed.isNotEmpty() && changed.zipWithNext().all { (a, b) -> b == a + 1 } &&
                route == repair.route && floor == repair.route[changed.first()].let { it.copy(y = it.y - 1) })
            val beforeSection = previous.subList(changed.first() - 1, changed.last() + 2)
            val afterSection = repair.route.subList(changed.first() - 1, changed.last() + 2)
            repairs.forEach { (id, value) ->
                val building = requireNotNull(city.building(UUID.fromString(id)))
                require(building.phase == BuildingPhase.ACTIVE && id == building.id.toString())
                value.validated(city, building)
                val old = city.roadRouteOverrides?.get(id)
                require(old == null || old.originalRoute == value.originalRoute)
                val before = old?.route ?: value.originalRoute
                val changes = value.route.indices.filter { before[it] != value.route[it] }
                require(changes.isNotEmpty() && changes.zipWithNext().all { (a, b) -> b == a + 1 } &&
                    changes.all { value.route[it].y < before[it].y })
                val from = before.subList(changes.first() - 1, changes.last() + 2)
                val to = value.route.subList(changes.first() - 1, changes.last() + 2)
                require(from == beforeSection && to == afterSection || from == beforeSection.asReversed() && to == afterSection.asReversed())
            }
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
            (after.roadRouteOverrides.orEmpty() - work.upkeep.overrides().keys + work.upkeep.previousRouteOverrides.orEmpty())
                .takeIf { it.isNotEmpty() } else after.roadRouteOverrides)

    fun validated(after: Settlement, changes: List<CivInventorySlotChange> = emptyList()) {
        require(after.autonomy != null && work.workerId == workerBefore.id && acceptedAt >= 0)
        if (CivsAutonomy.legacyStock(after)) require(changes.isEmpty()) else {
            require(after.inventory?.mode == CivStorageMode.PHYSICAL && after.stock == stockBefore)
            val builder = requireNotNull(after.building(work.buildingId))
            val removed = linkedMapOf<String, Long>()
            changes.forEach { change ->
                require(change.container == builder.control || builder.bounds.contains(
                    change.container.x, change.container.y, change.container.z))
                require(after.buildings.none { it.id != builder.id && it.control == change.container })
                val item = requireNotNull(change.before)
                require(change.after == null || change.after.material == item.material && change.after.amount < item.amount)
                removed.merge(item.material, (item.amount - (change.after?.amount ?: 0)).toLong(), Math::addExact)
            }
            require(removed == work.storageInputs(workerBefore))
        }
        require(CivsRoadMaintenance.complete(before(after), work, acceptedAt, nativePaid = true) == after)
    }
}

/** Detached, reconstructible read model of existing retained journals. No new persistent job or registry. */
internal data class CivRoadUpkeepNetwork(val cityId: UUID, val worldId: UUID,
    val buildings: List<CivBuilding>, val targets: List<CivRoadUpkeep>,
    val routeOverrides: Map<String, CivRoadRouteOverride>? = null)

/** Main-thread native scan owner; only preparation of the detached network runs off-thread. */
internal class CivsRoadMaintenance(private val world: CivsWorld,
    private val registerSurface: ((UUID, CivPosition) -> Unit)? = null,
    private val paymentPossible: (Settlement) -> Boolean? = { true },
    private val currentTick: () -> Int = Bukkit::getCurrentTick) {
    private val networks = mutableMapOf<UUID, CivRoadUpkeepNetwork>()
    private val cursors = mutableMapOf<UUID, Int>()
    private val selected = mutableMapOf<UUID, CivWorldWork>()
    private val gradeChecks = mutableMapOf<UUID, ArrayDeque<CivRoadUpkeep>>()
    private val developmentWaiting = mutableSetOf<UUID>()
    private data class GradeNetworkWait(val token: Long, val beforeGeneration: Long?, val buildings: List<CivBuilding>,
        val overrides: Map<String, CivRoadRouteOverride>?)
    private val awaitingGradeNetwork = mutableMapOf<UUID, GradeNetworkWait>()
    private val networkGenerations = mutableMapOf<UUID, Long>()
    private var generation = 0L
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
        if (previous?.buildings != prepared.buildings || previous?.routeOverrides != prepared.routeOverrides ||
            previous?.targets != prepared.targets) {
            cursors.remove(city.id)
            city.workers.forEach { selected.remove(it.id) }
            val lowered = city.roadRouteOverrides.orEmpty().values.flatMap { repair ->
                val before = CivsAutonomousTerraform.roadProfile(repair.originalRoute)
                CivsAutonomousTerraform.roadProfile(repair.route).filter { (key, floor) ->
                    before[key]?.let { floor.y < it.y } == true
                }.values
            }.toHashSet()
            gradeChecks[city.id] = ArrayDeque(prepared.targets.filter { it.routeOverride != null || it.floor in lowered })
            networkGenerations[city.id] = ++generation
        }
        awaitingGradeNetwork[city.id]?.takeIf { it.buildings == prepared.buildings && it.overrides == prepared.routeOverrides }
            ?.let { awaitingGradeNetwork.remove(city.id) }
    }

    fun plan(city: Settlement, worker: CivWorker): CivWorldWork? {
        if (!eligible(city) || worker.assignment?.let(city::building)?.let {
                it.type == BuildingType.BUILDER_HUT && it.phase == BuildingPhase.ACTIVE
            } != true || CivsResidents.isChild(worker) || worker.cargo != null ||
            CivsAutonomy.legacyStock(city) && !funded(city)) {
            selected.remove(worker.id)
            waiting.remove(worker.id)
            return null
        }
        val network = networks[city.id]?.takeIf { it.worldId == city.worldId && it.buildings == city.buildings &&
            it.routeOverrides == city.roadRouteOverrides } ?: return null
        selected[worker.id]?.let { work ->
            if (work.cycle == worker.completedCycles && work.buildingId == worker.assignment && work.inputs == inputs(city) &&
                !budget(worker.id)) return work
            if (work.cycle == worker.completedCycles && work.buildingId == worker.assignment && work.inputs == inputs(city)) {
                val refreshed = world.captureRoadUpkeep(city, worker, requireNotNull(work.upkeep), registerSurface)
                    ?.copy(inputs = inputs(city))
                if (refreshed != null) {
                    val retained = if (refreshed.changes == work.changes) work else refreshed
                    selected[worker.id] = retained
                    return retained
                }
                gradeChecks[city.id]?.removeAll { it.floor == work.upkeep?.floor }
            }
            selected.remove(worker.id)
        }
        if (network.targets.isEmpty()) return null
        repeat(minOf(SCAN_COLUMNS, network.targets.size)) {
            if (!budget(worker.id)) return null
            val grade = gradeChecks[city.id]?.removeFirstOrNull()
            if (grade == null && (city.id in developmentWaiting || city.constructionQueue.orEmpty().isNotEmpty())) return null
            val target = grade ?: nextTarget(city.id, network)
            val work = world.captureRoadUpkeep(city, worker, target, registerSurface)?.copy(inputs = inputs(city))
            if (grade != null && work != null) gradeChecks.getValue(city.id).addLast(grade)
            if (work == null) return@repeat
            selected[worker.id] = work
            return work
        }
        return null
    }

    /** Metadata and grade observations after resident work; a captured paid candidate is never selected here. */
    fun backfillSurfaces(city: Settlement) {
        if (registerSurface == null && gradeChecks[city.id].isNullOrEmpty() || city.autonomy == null || city.pendingDeletion != null) return
        val network = networks[city.id]?.takeIf { it.worldId == city.worldId && it.buildings == city.buildings &&
            it.routeOverrides == city.roadRouteOverrides && it.targets.isNotEmpty() } ?: return
        val worker = city.workers.firstOrNull { worker ->
            !CivsResidents.isChild(worker) && worker.cargo == null && worker.assignment?.let(city::building)?.let {
                it.type == BuildingType.BUILDER_HUT && it.phase == BuildingPhase.ACTIVE
            } == true
        } ?: return
        repeat(minOf(SCAN_COLUMNS, network.targets.size)) {
            if (!budget(worker.id)) return
            val grade = gradeChecks[city.id]?.removeFirstOrNull()
            val target = grade ?: nextTarget(city.id, network)
            // A damaged candidate stays unselected while its grade remains in the finite observation pass.
            val captured = world.captureRoadUpkeep(city, worker, target, registerSurface)
            if (grade != null && captured != null) gradeChecks.getValue(city.id).addLast(grade)
        }
    }

    private fun nextTarget(cityId: UUID, network: CivRoadUpkeepNetwork): CivRoadUpkeep {
        val cursor = Math.floorMod(cursors[cityId] ?: 0, network.targets.size)
        cursors[cityId] = (cursor + 1) % network.targets.size
        return network.targets[cursor]
    }

    /** Growth yields to an admitted column and one finite native pass of the lowered grade, not ordinary wear. */
    fun admitDevelopment(city: Settlement): Boolean {
        developmentWaiting += city.id
        if (!eligible(city)) return true
        if (city.id in awaitingGradeNetwork) return false
        val builders = city.workers.filter { worker -> !CivsResidents.isChild(worker) && worker.cargo == null &&
            worker.assignment?.let(city::building)?.let { it.type == BuildingType.BUILDER_HUT && it.phase == BuildingPhase.ACTIVE } == true }
        if (builders.isEmpty() || CivsAutonomy.legacyStock(city) && !funded(city)) return true
        if (paymentPossible(city) == false) {
            gradeChecks.remove(city.id)
            selected.entries.removeIf { it.value.upkeep?.cityId == city.id }
            return true
        }
        val admitted = builders.any { worker -> selected[worker.id]?.let { work ->
            worker.assignment == work.buildingId && worker.completedCycles == work.cycle &&
                worker.cargo == null && !CivsResidents.isChild(worker)
        } == true }
        return !admitted && gradeChecks[city.id].isNullOrEmpty()
    }

    fun developmentFinished(cityId: UUID) { developmentWaiting.remove(cityId) }
    fun awaitGradeNetwork(city: Settlement): Long {
        val token = ++generation
        awaitingGradeNetwork[city.id] = GradeNetworkWait(token, networkGenerations[city.id], city.buildings.toList(),
            city.roadRouteOverrides?.toMap())
        return token
    }
    /** A stale load may finish without publishing; it cannot clear a newer wait or freshly observed work. */
    fun gradeNetworkCompleted(cityId: UUID, token: Long) {
        val wait = awaitingGradeNetwork[cityId]?.takeIf { it.token == token } ?: return
        awaitingGradeNetwork.remove(cityId)
        if (networkGenerations[cityId] == wait.beforeGeneration) {
            gradeChecks.remove(cityId)
            selected.entries.removeIf { it.value.upkeep?.cityId == cityId }
        }
    }

    fun invalidate(workerId: UUID) {
        selected.remove(workerId)?.upkeep?.let { failed -> gradeChecks[failed.cityId]?.removeAll { it.floor == failed.floor } }
        waiting.remove(workerId)
    }
    fun clear() {
        networks.clear(); cursors.clear(); selected.clear(); waiting.clear()
        gradeChecks.clear(); developmentWaiting.clear(); awaitingGradeNetwork.clear(); networkGenerations.clear()
    }

    /** Only an observed damaged column creates a request; healthy roads never reserve or receive supplies. */
    fun requirements(city: Settlement): Map<UUID, Map<String, Long>> = if (!eligible(city) ||
        city.inventory?.mode != CivStorageMode.PHYSICAL) emptyMap() else city.workers.mapNotNull { worker ->
        selected[worker.id]?.takeIf { work -> work.cycle == worker.completedCycles && work.buildingId == worker.assignment &&
            work.inputs == NATIVE_INPUTS && worker.cargo == null && !CivsResidents.isChild(worker) &&
            city.building(work.buildingId)?.let { it.type == BuildingType.BUILDER_HUT && it.phase == BuildingPhase.ACTIVE } == true
        }?.let { worker.id to it.inputs }
    }.toMap()

    companion object {
        private const val SCAN_COLUMNS = 8
        private val NATIVE_INPUTS = mapOf("COBBLESTONE" to 1L)
        private fun inputs(city: Settlement): Map<String, Long> = if (CivsAutonomy.legacyStock(city)) emptyMap() else NATIVE_INPUTS
        /** A complete zero observation lets development create its missing production source. */
        internal fun paymentPossible(city: Settlement, snapshot: CivSupplySnapshot?): Boolean? {
            if (CivsAutonomy.legacyStock(city) || city.workers.any {
                    CivsEquipment.usableCount(it, "COBBLESTONE") > 0 || it.cargo?.let { cargo ->
                        cargo.inventoryBacked && cargo.stockResource == null && cargo.material == "COBBLESTONE" && cargo.amount > 0
                    } == true
                } || snapshot?.stores?.values?.any { (it.stock["COBBLESTONE"] ?: 0L) > 0 } == true) return true
            return if (CivsAutoSupplies.observed(city, snapshot)) false else null
        }
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
                val repair = CivRoadRouteOverride(city.roadRouteOverrides?.get(source.id.toString())?.originalRoute ?: route.toList(), lowered.toList())
                if (runCatching { repair.validated(city, source) }.isSuccess) return repair
            }
            return null
        }

        internal fun humpOverride(city: Settlement, source: CivBuilding, route: List<CivPosition>): CivRoadRouteOverride? {
            val original = city.roadRouteOverrides?.get(source.id.toString())?.originalRoute ?: route
            for (at in 1 until route.lastIndex) {
                val level = route[at - 1].y
                if (route[at].y != level + 1) continue
                val end = (at..minOf(route.lastIndex, at + 9)).firstOrNull { route[it].y <= level } ?: continue
                if (end == at || route[end].y != level) continue
                val lowered = route.mapIndexed { index, point -> if (index in at until end) point.copy(y = level) else point }
                val repair = CivRoadRouteOverride(original, lowered)
                if (repair.hump(at until end) && runCatching { repair.validated(city, source) }.isSuccess) return repair
            }
            return null
        }

        /** At most the retained three-wide old road finish, at its exact old height. */
        internal fun ownedPavement(city: Settlement, upkeep: CivRoadUpkeep): Map<CivPosition, Set<String>> {
            val repair = upkeep.routeOverride ?: city.roadRouteOverrides?.get(upkeep.source.id.toString()) ?: return emptyMap()
            val old = CivsAutonomousTerraform.roadProfile(repair.originalRoute)
            val final = CivsAutonomousTerraform.roadProfile(repair.route)
            val profile = old.mapValues { it.value.y }
            return old.filter { (key, floor) -> final[key]?.y?.let { it < floor.y } == true }.values.associateWith { floor ->
                setOf(CivsSettlementLayout.pavement(floor, profile),
                    CivsSettlementLayout.pathPavement(floor, profile, city.style, legacyStairAtTurn = true),
                    CivsSettlementLayout.pathPavement(floor, profile, city.style,
                        atTurn = floor in CivsSettlementLayout.turnFloors(repair.originalRoute),
                        downhill = CivsSettlementLayout.routeDownhill(floor, repair.originalRoute)))
            }
        }

        /** Natural banks; paid pavement is admitted separately by exact original profile and finish. */
        internal fun clearanceGround(data: String): Boolean = data.substringBefore('[') in setOf(
            "minecraft:grass_block", "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:rooted_dirt",
            "minecraft:podzol", "minecraft:mycelium", "minecraft:moss_block", "minecraft:clay", "minecraft:stone",
            "minecraft:deepslate", "minecraft:granite", "minecraft:diorite", "minecraft:andesite", "minecraft:tuff")

        internal fun covers(floor: CivPosition, lower: String, upper: String): List<CivPlanBlock>? =
            covers(floor, listOf(lower, upper))

        /** A bounded open cut; paired plants require both exact halves in this same receipt. */
        internal fun covers(floor: CivPosition, cells: List<String>, pavement: Map<CivPosition, Set<String>> = emptyMap()): List<CivPlanBlock>? {
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
                    if (!clearanceGround(data) && data !in setOf("minecraft:short_grass", "minecraft:fern") &&
                        data !in pavement[floor.copy(y = floor.y + index + 1)].orEmpty()) return null
                    changes += clear(index + 1, data)
                    index++
                }
            }
            return changes
        }

        internal fun validateChanges(city: Settlement, upkeep: CivRoadUpkeep, changes: List<CivPlanBlock>) {
            require(changes.size in 1..MAX_CLEARANCE + 1 && changes.map { it.position }.distinct().size == changes.size)
            require(changes.all { it.before != it.after })
            val floor = changes.firstOrNull()?.takeIf { it.position == upkeep.floor }
            require(floor == null || floor.after == upkeep.after)
            val cover = changes.filter { it.position != upkeep.floor }
            val expected = covers(upkeep.floor, (1..MAX_CLEARANCE).map { dy ->
                cover.firstOrNull { it.position == upkeep.floor.copy(y = upkeep.floor.y + dy) }?.before ?: "minecraft:air"
            }, ownedPavement(city, upkeep))
            require(expected != null && changes == listOfNotNull(floor) + expected)
        }

        fun eligible(city: Settlement): Boolean = city.autonomy?.paused == false &&
            (city.inventory == null || city.inventory.mode == CivStorageMode.PHYSICAL) &&
            city.pendingDeletion == null && city.construction == null

        private fun funded(city: Settlement): Boolean =
            city.stock.stone > (CivsAutonomy.nextGoal(city)?.cost?.stone ?: 0L)

        /** One native cobblestone per worked column; accepted legacy receipts retain their closed debit. */
        fun complete(city: Settlement, work: CivWorldWork, at: Long, nativePaid: Boolean = false): Settlement {
            val legacy = CivsAutonomy.legacyStock(city)
            require(eligible(city) && (!legacy || funded(city)) && at in 0..Long.MAX_VALUE - 1_000)
            val upkeep = requireNotNull(work.upkeep)
            upkeep.validated(city)
            val worker = requireNotNull(city.workers.firstOrNull { it.id == work.workerId })
            val builder = requireNotNull(worker.assignment?.let(city::building))
            require(builder.id == work.buildingId && builder.type == BuildingType.BUILDER_HUT && builder.phase == BuildingPhase.ACTIVE &&
                worker.completedCycles == work.cycle && worker.completedCycles < Long.MAX_VALUE &&
                worker.cargo == null && !CivsResidents.isChild(worker))
            validateChanges(city, upkeep, work.changes)
            require((work.focus == upkeep.floor || work.changes.any { it.position == work.focus }) &&
                work.status == "repairing-road" && work.durationMillis == 3_000L)
            require(work.inputs == inputs(city) && work.outputs.isEmpty() && work.equipmentAfter == null && work.forestry == null &&
                work.miningStep == null && work.miningPlanVersion == null && !work.miningGenerator && work.miningAccessStep == null)
            require(city.contains(work.standing.x, work.standing.z) && work.standing.y in -4_094..4_094 &&
                (work.standing.x != upkeep.floor.x || work.standing.z != upkeep.floor.z))
            require(city.revision < Long.MAX_VALUE)
            require(legacy || nativePaid || work.storageInputs(worker).isEmpty())
            val paid = if (legacy) worker else CivsEquipment.consumeInputs(worker, work.inputs)
            val updated = paid.copy(completedCycles = worker.completedCycles + 1, nextDeliveryAt = at + 1_000)
            return CivsRules.validate(city.copy(stock = if (legacy) city.stock.copy(stone = city.stock.stone - 1) else city.stock,
                workers = city.workers.map { if (it.id == worker.id) updated else it }, revision = city.revision + 1,
                roadRouteOverrides = upkeep.routeOverride?.let { city.roadRouteOverrides.orEmpty() + upkeep.overrides() }
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
                    val current = access.routes[source.id].orEmpty()
                    val repair = humpOverride(city, source, current) ?: turnOverride(city, source, current) ?: return@mapNotNull null
                    val changes = current.indices.filter { current[it] != repair.route[it] }
                    val from = changes.first() - 1
                    val until = changes.last() + 2
                    val beforeSection = current.subList(from, until)
                    val afterSection = repair.route.subList(from, until)
                    val oldProfile = CivsAutonomousTerraform.roadProfile(current)
                    val corrected = CivsAutonomousTerraform.roadProfile(repair.route)
                    val altered = corrected.filter { (key, point) -> oldProfile[key] != point && !excluded(point) }
                    // Hold the unfinished cut until its first native receipt can safely admit the full section.
                    altered.keys.forEach(held::add)
                    if (changes.any { excluded(repair.route[it]) } ||
                        altered.any { (key, _) -> key in ambiguous || key in roadColumns })
                        return@mapNotNull null
                    val linked = linkedMapOf<String, CivRoadRouteOverride>()
                    for ((id, otherRoute) in access.routes) {
                        if (id == source.id) continue
                        val otherProfile = CivsAutonomousTerraform.roadProfile(otherRoute)
                        if (altered.none { (key, point) -> otherProfile[key]?.let { it.y != point.y } == true }) continue
                        val index = otherRoute.windowed(beforeSection.size).indexOfFirst {
                            it == beforeSection || it == beforeSection.asReversed()
                        }
                        if (index < 0) return@mapNotNull null // Crossing or a different paid grade.
                        val replacement = if (otherRoute[index] == beforeSection.first()) afterSection else afterSection.asReversed()
                        val next = otherRoute.toMutableList().also { replacement.forEachIndexed { i, point -> it[index + i] = point } }
                        val other = requireNotNull(city.building(id))
                        val value = CivRoadRouteOverride(city.roadRouteOverrides?.get(id.toString())?.originalRoute ?: otherRoute, next)
                        if (runCatching { value.validated(city, other) }.isFailure) return@mapNotNull null
                        linked[id.toString()] = value
                    }
                    val finalAccess = access.copy(routes = access.routes + linked.mapKeys { UUID.fromString(it.key) }.mapValues { it.value.route })
                    val retained = finalAccess.standing(city, source.id)
                    if (altered.any { (key, point) -> retained[key]?.let { it.y - 1 != point.y } == true }) return@mapNotNull null
                    val floor = repair.route[changes.first()].let { it.copy(y = it.y - 1) }
                    val neighbours = directions.mapNotNull { (dx, dz) -> corrected[civsTerrainKey(floor.x + dx, floor.z + dz)] }
                        .filter { abs(it.y.toLong() - floor.y) <= 1L && !excluded(it) }
                    val profile = (neighbours + floor).associate { civsTerrainKey(it.x, it.z) to it.y }
                    val finish = CivsSettlementLayout.pathPavement(floor, profile, city.style,
                        atTurn = floor in CivsSettlementLayout.turnFloors(repair.route),
                        downhill = CivsSettlementLayout.routeDownhill(floor, repair.route))
                    val touched = linked.keys + source.id.toString()
                    CivRoadUpkeep(city.id, city.worldId, city.owner, source.id, source, floor,
                        finish, repair.route, neighbours, repair, linkedRouteOverrides = linked.takeIf { it.isNotEmpty() },
                        previousRouteOverrides = city.roadRouteOverrides?.filterKeys { it in touched }?.takeIf { it.isNotEmpty() })
                        .also { it.validated(city) }
                }.distinctBy { it.floor }

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
