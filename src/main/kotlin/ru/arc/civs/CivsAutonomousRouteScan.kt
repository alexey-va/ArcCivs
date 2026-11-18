package ru.arc.civs

import org.bukkit.Location
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import ru.arc.civs.CivsTerrainPolicy.requireTerrainRoute

/** Same detached A* and collision capture as resident movement, sharing the world scan budget. */
internal class AutonomousRoute(access: CivsWorld, private val citySnapshot: Settlement, envelope: CivBuilding,
    private val from: CivPosition, private val executor: Executor,
    private val terraform: CivTerraformPlan?, private val previous: CivBuilding?,
    private val navigationSettings: CivsNavigationSettings) :
    CivsWorldScan<Unit>(access, citySnapshot.id, citySnapshot.owner, citySnapshot.worldId, envelope) {
    private var route: CitizenPacketRoute? = null
    private var search: CitizenNavigationSearch? = null
    private var searching: CompletableFuture<CitizenSearchResult>? = null
    private var capture: CitizenNavigationCapture? = null
    private val terrain = hashMapOf<CivPosition, CitizenTerrainBlock>()
    private val obstacles = citySnapshot.buildings.filter { it.id != previous?.id }
        .flatMap { civsRouteObstacles(citySnapshot, it) } + civsRouteObstacles(citySnapshot, building, allowPlanned = true)
    private var overlayIndex = 0
    private var routeIndex = 0
    private val overlay = terraform?.blocks.orEmpty().associateBy { it.position }
    private val routeColumns = terraform?.route.orEmpty().flatMap { p ->
        (-1..1).flatMap { dx -> (-1..1).map { dz -> civsTerrainKey(p.x + dx, p.z + dz) } }
    }.toSet()

    init {
        terraform?.let {
            requireTerrainRoute(it.route, building)
            requireCivs(it.route.first() == from, "invalid-plan")
        }
        future.whenComplete { _, _ -> search?.cancel() }
    }
    override fun validateIdentity(city: Settlement?) = requireUnchangedBuilding(city, previous)
    override fun ready(): Boolean = searching?.isDone != false
    override fun step() {
        index++
        terraform?.let { plan ->
            if (overlayIndex < plan.blocks.size) {
                val cell = plan.blocks[overlayIndex]
                val tree = treeLedgerProof(plan.blocks, overlayIndex)
                if (tree && !treeSupportReady(cell.position)) return
                overlayIndex++
                checkTerraformCell(cell, plan)
                val block = read(cell.position)
                val after = parse(cell.after)
                checkTerraformBlock(block, after, tree)
                if (civsTerrainKey(cell.position.x, cell.position.z) in routeColumns) {
                    terrain[cell.position] = CitizenTerrainBlock(true, false, false, false, false,
                        citySnapshot.contains(cell.position.x, cell.position.z) && obstacles.none {
                            CivsAutonomousGeometry.horizontalContains(it, cell.position)
                        }, 1.0, if (after.material.isAir) emptyList() else listOf(CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)))
                }
                return
            }
            if (routeIndex < plan.route.size * 3) {
                checkTerrainRouteCell(plan, overlay, routeIndex++)
                return
            }
        }
        if (route == null) {
            val endpoint = building.entrance
            val corridor = terraform?.route?.mapTo(hashSetOf()) { it.copy(y = it.y - 1) }?.let {
                CitizenSearchCorridor.fromWalked(it, from.point(), endpoint.point())
            }
            val path = CitizenPacketRoute(world, Location(world, from.x + .5, from.y.toDouble(), from.z + .5),
                Location(world, endpoint.x + .5, endpoint.y.toDouble(), endpoint.z + .5),
                { p -> citySnapshot.contains(p.x, p.z) && obstacles.none { CivsAutonomousGeometry.horizontalContains(it, p) } &&
                    citySnapshot.buildings.none { CivsWorksiteLayouts.isField(it, p.x, p.z) } },
                { a, b -> citizenControlStepClear(citySnapshot, a, b) }, { 1.0 }, settings = navigationSettings,
                corridor = corridor)
            route = path
            search = path.newSearch()
            dispatch(emptyList())
            return
        }
        searching?.let { pending ->
            if (!pending.isDone) return
            searching = null
            val result = pending.join() // Observed complete; no gameplay-thread waiting.
            result.failure?.let {
                throw CivsRejection(when (it) {
                    "chunk-unloaded" -> "chunks-unloaded"
                    "search-budget" -> "autonomy-search-budget"
                    else -> "autonomy-no-path"
                }, mapOf("stage" to "native-route-search", "corridor" to requireNotNull(route).hasCourierCorridorHint.toString(),
                    "failure" to it, "nodes" to result.nodes.toString()))
            }
            // A movement segment does not prove access to a future building entrance.
            if (result.continuation) throw CivsRejection(
                if (result.continuationReason == "no-path") "autonomy-no-path" else "autonomy-search-budget",
                mapOf("stage" to "native-route-continuation", "corridor" to requireNotNull(route).hasCourierCorridorHint.toString(),
                    "continuation_reason" to result.continuationReason.orEmpty(), "nodes" to result.nodes.toString()))
            if (result.path != null) return finish(Unit)
            capture = CitizenNavigationCapture(requireNotNull(route), result.probes, terrain)
        }
        val cursor = capture ?: return
        val complete = try { cursor.step() } catch (_: CitizenNavigationCaptureLimit) {
            throw CivsRejection("autonomy-search-budget", mapOf("stage" to "native-route-capture",
                "corridor" to requireNotNull(route).hasCourierCorridorHint.toString(), "terrain_cache_entries" to terrain.size.toString()))
        }
        if (complete) {
            capture = null
            dispatch(cursor.result)
        }
    }
    private fun dispatch(captured: List<CitizenSearchCapture>) {
        val solver = requireNotNull(search)
        searching = CompletableFuture.supplyAsync({ solver.advance(captured) }, executor)
    }
}
