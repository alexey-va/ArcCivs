package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.TileState
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.Waterlogged
import org.bukkit.block.data.type.Stairs
import org.bukkit.plugin.Plugin
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor

import ru.arc.civs.CivsTerrainPolicy.fullTerraformSupport
import ru.arc.civs.CivsTerrainPolicy.TERRAFORM_GROUND
import ru.arc.civs.CivsTerrainPolicy.sameConstructionState

/** Current native road-maintenance geometry and authority, without inventory or journal ownership. */
internal class CivsRoadWorld(
    private val plugin: Plugin,
    private val cities: () -> Collection<Settlement>,
    private val canModify: (UUID, Block, Material?) -> Boolean,
    private val isClosed: () -> Boolean,
) {
    /** Exact pre-receipt probe. A healthy column never consumes another stone. */
    internal fun roadUpkeepPending(city: Settlement, work: CivWorldWork): Boolean {
        check(Bukkit.isPrimaryThread())
        val upkeep = work.upkeep ?: return false
        if (city.id != upkeep.cityId || city.worldId != upkeep.worldId || city.owner != upkeep.owner ||
            city.building(upkeep.source.id) != upkeep.source) return false
        val cell = work.changes.firstOrNull() ?: return false
        val world = plugin.server.getWorld(city.worldId) ?: return false
        if (!world.isChunkLoaded(cell.position.x shr 4, cell.position.z shr 4) ||
            cell.position.y !in world.minHeight until world.maxHeight) return false
        if (work.changes.none { it.position == upkeep.floor } &&
            !sameConstructionState(world.getBlockAt(upkeep.floor.x, upkeep.floor.y, upkeep.floor.z).blockData,
                Bukkit.createBlockData(upkeep.after))) return false
        return work.changes.all { change -> change.position.y in world.minHeight until world.maxHeight &&
            world.getBlockAt(change.position.x, change.position.y, change.position.z).blockData.asString == change.before }
    }

    /** Discovery is externally budgeted. This method never loads chunks or writes a block. */
    internal fun captureRoadUpkeep(city: Settlement, worker: CivWorker, upkeep: CivRoadUpkeep,
        registerSurface: ((UUID, CivPosition) -> Unit)? = null): CivWorldWork? {
        check(Bukkit.isPrimaryThread())
        val world = plugin.server.getWorld(city.worldId) ?: return null
        val p = upkeep.floor
        if (!world.isChunkLoaded(p.x shr 4, p.z shr 4) ||
            p.y !in world.minHeight + 1 until world.maxHeight - CivsRoadMaintenance.MAX_CLEARANCE) return null
        val block = world.getBlockAt(p.x, p.y, p.z)
        val after = Bukkit.createBlockData(upkeep.after)
        val pavement = CivsRoadMaintenance.ownedPavement(city, upkeep)
        val change = if (sameConstructionState(block.blockData, after)) null else {
            if (!roadUpkeepReplaceable(block) && block.blockData.asString !in pavement[p].orEmpty()) return null
            CivPlanBlock(p, block.blockData.asString, after.asString)
        }
        val cover = CivsRoadMaintenance.covers(p, (1..CivsRoadMaintenance.MAX_CLEARANCE).map { dy ->
            world.getBlockAt(p.x, p.y + dy, p.z).blockData.asString
        }, pavement) ?: return null
        val changes = listOfNotNull(change) + cover
        if (changes.isEmpty()) {
            val stairs = block.blockData as? Stairs
            if (registerSurface != null && stairs != null && stairs.material != Material.COBBLESTONE_STAIRS &&
                stairs.half == Bisected.Half.BOTTOM && !stairs.isWaterlogged && !isClosed() && city.autonomy != null &&
                world.worldBorder.isInside(block.location)) {
                try { upkeep.validated(city) } catch (_: IllegalArgumentException) { return null }
                if (canModify(city.owner, block, after.material)) registerSurface(city.id, p)
            }
            return null
        }
        val assignment = worker.assignment ?: return null
        for (dy in listOf(1, 0, 2, 3)) for ((dx, dz) in listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)) {
            val standing = CivPosition(p.x + dx, p.y + dy, p.z + dz)
            if (!city.contains(standing.x, standing.z) || !citizenControlClear(city, standing) ||
                !citizenStandable(world, standing) || !citizenPacketBodyClear(world,
                    CitizenPoint(standing.x + .5, standing.y.toDouble(), standing.z + .5), false)) continue
            if (civsProtectedBuildings(city).any { it.type != BuildingType.ROAD &&
                    (CivsAutonomousGeometry.horizontalContains(CivsAutonomousGeometry.futureBounds(city, it), standing) ||
                        CivsWorksiteLayouts.isField(it, standing.x, standing.z)) }) continue
            val eye = Location(world, standing.x + .5, standing.y + 1.62, standing.z + .5)
            val focus = changes.asReversed().firstOrNull { citizenCanReachBlock(world, eye, it.position) }?.position ?: continue
            val work = CivWorldWork(worker.id, worker.completedCycles, assignment, standing, changes,
                status = "repairing-road", focus = focus, upkeep = upkeep.detached())
            try { validateRoadUpkeep(city, work); return work }
            catch (_: CivsRejection) { return null }
        }
        return null
    }

    /** Sealed receipts recheck native authority on both first application and recovery. No arrival fiction. */
    internal fun validateRoadUpkeep(city: Settlement, work: CivWorldWork) {
        check(Bukkit.isPrimaryThread())
        requireCivs(!isClosed(), "not-ready")
        val upkeep = work.upkeep ?: throw CivsRejection("stale-worksite")
        try { upkeep.validated(city) } catch (_: IllegalArgumentException) { throw CivsRejection("stale-worksite") }
        requireCivs(city.autonomy != null && city.pendingDeletion == null, "not-authorized")
        try { CivsRoadMaintenance.validateChanges(city, upkeep, work.changes) }
        catch (_: IllegalArgumentException) { throw CivsRejection("stale-worksite") }
        val cell = work.changes.firstOrNull { it.position == upkeep.floor }
        val world = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("world-unloaded")
        fun read(p: CivPosition): Block {
            requireCivs(p.y in world.minHeight until world.maxHeight, "outside-world")
            requireCivs(world.isChunkLoaded(p.x shr 4, p.z shr 4), "chunks-unloaded")
            return world.getBlockAt(p.x, p.y, p.z)
        }
        val p = upkeep.floor
        requireCivs(world.worldBorder.isInside(Location(world, p.x + .5, p.y.toDouble(), p.z + .5)), "outside-border")
        val all = cities().filter { it.id != city.id } + city
        for (other in all) {
            if (other.worldId != city.worldId) continue
            requireCivs(other.id == city.id || !other.contains(p.x, p.z), "city-overlap")
            val positions = work.changes.map { it.position } + p
            requireCivs(positions.none { other.inventory?.containers?.contains(it) == true }, "registered-container")
            requireCivs(other.pendingControls.orEmpty().none { it.position in positions || it.approach.orEmpty().any { c -> c.position in positions } },
                "building-overlap")
            for (building in civsProtectedBuildings(other)) {
                requireCivs(building.control !in positions, "building-overlap")
                val ownRoad = other.id == city.id && building == upkeep.source && building.type == BuildingType.ROAD &&
                    building.transit.orEmpty().contains(p.copy(y = p.y + 1))
                requireCivs(ownRoad || !CivsAutonomousGeometry.horizontalContains(CivsAutonomousGeometry.futureBounds(other, building), p) &&
                    !CivsWorksiteLayouts.isField(building, p.x, p.z), "building-overlap")
            }
        }
        val block = read(p)
        val after = Bukkit.createBlockData(upkeep.after)
        val already = sameConstructionState(block.blockData, after)
        requireCivs(cell?.let { block.blockData.asString == it.before } == true || already, "world-changed")
        requireCivs(block.state !is TileState && !block.isLiquid &&
            (block.blockData as? Waterlogged)?.isWaterlogged != true, "protected-terrain")
        requireCivs(already || roadUpkeepReplaceable(block) ||
            block.blockData.asString in CivsRoadMaintenance.ownedPavement(city, upkeep)[p].orEmpty(), "protected-terrain")
        // A new half step removes side support. Do not make adjacent torches, ladders or decor pop as loot.
        if (!already && (after is Stairs || after.material == Material.DIRT_PATH)) for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            val beside = read(p.copy(x = p.x + dx, z = p.z + dz))
            requireCivs(roadUpkeepSideSafe(beside), "protected-terrain")
        }
        val support = read(p.copy(y = p.y - 1))
        val retainedStepSupport = CivsRoadMaintenance.surface(support.blockData.asString) &&
            civsRetainedRouteStep(support.blockData) && support.collisionShape.boundingBoxes.any {
                it.maxY == 1.0 && it.maxX > it.minX && it.maxZ > it.minZ
            }
        requireCivs(roadUpkeepGround(support) && fullTerraformSupport(support) || retainedStepSupport, "autonomy-no-path")
        requireCivs(canModify(city.owner, block, after.material), "lands-denied")
        val cover = work.changes.filter { it.position != p }.associateBy { it.position }
        for (dy in 1..maxOf(2, cover.keys.maxOfOrNull { it.y - p.y } ?: 0)) {
            val head = read(p.copy(y = p.y + dy))
            val clear = cover[CivPosition(head.x, head.y, head.z)]
            if (clear == null) requireCivs(head.type.isAir, "world-changed")
            else {
                requireCivs(head.blockData.asString == clear.before || head.blockData.asString == clear.after, "world-changed")
                requireCivs(head.state !is TileState && !head.isLiquid &&
                    (head.blockData as? Waterlogged)?.isWaterlogged != true, "protected-terrain")
                requireCivs(canModify(city.owner, head, Material.AIR), "lands-denied")
                // Removing a bank must not spill fluid or later detach an unjournalled supported block.
                if (CivsRoadMaintenance.clearanceGround(clear.before) || CivsRoadMaintenance.surface(clear.before)) {
                    val above = head.y + 1
                    if (p.copy(y = above) !in cover) {
                        val top = read(p.copy(y = above))
                        requireCivs(top.type.isAir, "protected-terrain")
                    }
                    for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                        val beside = read(p.copy(x = p.x + dx, y = head.y, z = p.z + dz))
                        requireCivs(roadUpkeepSideSafe(beside), "protected-terrain")
                    }
                }
            }
        }

        // Stairs have two levels, not a fictional full cube. Actual neighbours retain their native shapes.
        val localAfter = roadUpkeepBoxes(if (already) block.blockData else after).map { it.at(CivPosition(0, 0, 0)) }
        val collisions = hashMapOf<CivPosition, CitizenCollisionSnapshot>()
        fun collision(x: Int, y: Int, z: Int): CitizenCollisionSnapshot = collisions.getOrPut(CivPosition(x, y, z)) {
            val at = CivPosition(x, y, z)
            val native = read(at)
            val local = if (at == p) localAfter else if (at in cover) emptyList()
                else native.collisionShape.boundingBoxes.map { it.clone() }
            CitizenCollisionSnapshot(if (at == p || at in cover) false else native.isLiquid,
                at != p && citizenRouteOpenable(native.blockData) != null, local,
                local.map { it.clone().shift(x.toDouble(), y.toDouble(), z.toDouble()) })
        }
        val top = localAfter.filter { .5 in it.minX..it.maxX && .5 in it.minZ..it.maxZ }.maxOf { it.maxY }
        val future = CitizenPoint(p.x + .5, p.y + top, p.z + .5)
        requireCivs(citizenPacketBodyClear(world, future, false, collision = ::collision), "autonomy-no-path")
        requireCivs(citizenPacketCampfireClear(world, future), "autonomy-no-path")
        upkeep.junction?.let { validateRoadJunction(city, upkeep, it, world, all) }
        val repair = upkeep.routeOverride ?: city.roadRouteOverrides?.get(upkeep.source.id.toString())
        repair?.runs()?.forEach { run ->
            val section = repair.route.subList(run.first - 1, minOf(repair.route.size, run.last + 2))
            if (section.none { abs(it.x.toLong() - p.x) <= 1L && abs(it.z.toLong() - p.z) <= 1L }) return@forEach
            if (repair.descendingTurn(run) && upkeep.junction?.let { it.repair == repair && it.run == run.first } == true) {
                // Its complete shared profile was checked above; the old paid stair is journalled cover.
            } else if (repair.hump(run)) {
                validateRoadCut(city, upkeep, repair, run, world, all)
            } else {
                if (upkeep.routeOverride != null) {
                    for (index in run) requireCivs(read(repair.originalRoute[index].let { it.copy(y = it.y - 1) }).type.isAir, "world-changed")
                    for (feet in section) {
                        val surface = read(feet.copy(y = feet.y - 1))
                        val support = read(feet.copy(y = feet.y - 2))
                        requireCivs(surface.state !is TileState && !surface.isLiquid &&
                            (surface.blockData as? Waterlogged)?.isWaterlogged != true &&
                            (roadUpkeepGround(surface) || CivsRoadMaintenance.surface(surface.blockData.asString)), "protected-terrain")
                        requireCivs(roadUpkeepGround(support) && fullTerraformSupport(support) ||
                            CivsRoadMaintenance.surface(support.blockData.asString) && civsRetainedRouteStep(support.blockData), "autonomy-no-path")
                    }
                }
                requireCivs(roadHalfStepWalk(world, section, ::collision) != null, "autonomy-no-path")
            }
        }

        for (neighbour in upkeep.neighbours) {
            val floor = read(neighbour)
            if (!roadUpkeepGround(floor) && !civsRetainedRouteStep(floor.blockData) &&
                !CivsRoadMaintenance.landing(floor.blockData.asString)) continue
            val top = floor.collisionShape.boundingBoxes.filter { .5 in it.minX..it.maxX && .5 in it.minZ..it.maxZ }
                .maxOfOrNull { it.maxY } ?: continue
            val beside = CitizenPoint(neighbour.x + .5, neighbour.y + top, neighbour.z + .5)
            // Adjacent unfinished banks are future jobs, not mandatory traversal edges of this job.
            if (!citizenPacketBodyClear(world, beside, false)) continue
            requireCivs(citizenPacketBodyClear(world, beside, false, collision = ::collision) &&
                citizenPacketStepClear(world, beside, future, false, ::collision) &&
                citizenPacketStepClear(world, future, beside, false, ::collision), "autonomy-no-path")
        }
    }

    /** A shared cut projects the same merged network that subsequent paid column receipts will use. */
    private fun validateRoadJunction(city: Settlement, upkeep: CivRoadUpkeep, junction: CivRoadJunction,
        world: World, settlements: List<Settlement>) {
        val targets = junction.targets(city, after = true)
        val prior = junction.targets(city, after = false).associateBy { civsTerrainKey(it.floor.x, it.floor.z) }
        val pavement = CivsRoadMaintenance.ownedPavement(city, upkeep)
        val projected = hashMapOf<CivPosition, BlockData>()
        fun read(p: CivPosition): Block {
            requireCivs(p.y in world.minHeight until world.maxHeight, "outside-world")
            requireCivs(world.isChunkLoaded(p.x shr 4, p.z shr 4), "chunks-unloaded")
            return world.getBlockAt(p.x, p.y, p.z)
        }
        for (target in targets) {
            val floor = target.floor
            requireCivs(city.contains(floor.x, floor.z) && world.worldBorder.isInside(
                Location(world, floor.x + .5, floor.y.toDouble(), floor.z + .5)), "outside-border")
            for (other in settlements.filter { it.worldId == city.worldId }) {
                requireCivs(other.id == city.id || !other.contains(floor.x, floor.z), "city-overlap")
                fun column(p: CivPosition) = p.x == floor.x && p.z == floor.z
                requireCivs(other.inventory?.containers.orEmpty().none(::column) &&
                    other.pendingControls.orEmpty().none { column(it.position) || it.approach.orEmpty().any { c -> column(c.position) } },
                    "building-overlap")
                requireCivs(civsProtectedBuildings(other).none {
                    CivsAutonomousGeometry.horizontalContains(CivsAutonomousGeometry.futureBounds(other, it), floor) ||
                        CivsWorksiteLayouts.isField(it, floor.x, floor.z) || it.control?.let(::column) == true
                }, "building-overlap")
            }
            val block = read(floor)
            val finish = Bukkit.createBlockData(target.after)
            val before = prior[civsTerrainKey(floor.x, floor.z)]?.takeIf { it.floor == floor }?.after
            requireCivs(roadUpkeepReplaceable(block) || sameConstructionState(block.blockData, finish) ||
                block.blockData.asString == before, "protected-terrain")
            requireCivs(block.state !is TileState && !block.isLiquid && (block.blockData as? Waterlogged)?.isWaterlogged != true,
                "protected-terrain")
            val support = read(floor.copy(y = floor.y - 1))
            requireCivs(roadUpkeepGround(support) && fullTerraformSupport(support) ||
                CivsRoadMaintenance.surface(support.blockData.asString) && civsRetainedRouteStep(support.blockData), "autonomy-no-path")
            val cover = CivsRoadMaintenance.covers(floor, (1..CivsRoadMaintenance.MAX_CLEARANCE).map {
                read(floor.copy(y = floor.y + it)).blockData.asString
            }, pavement) ?: throw CivsRejection("protected-terrain")
            requireCivs(canModify(city.owner, block, finish.material), "lands-denied")
            if (finish is Stairs || finish.material == Material.DIRT_PATH) for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1))
                requireCivs(roadUpkeepSideSafe(read(floor.copy(x = floor.x + dx, z = floor.z + dz))), "protected-terrain")
            for (change in cover) {
                val head = read(change.position)
                requireCivs(head.state !is TileState && !head.isLiquid && (head.blockData as? Waterlogged)?.isWaterlogged != true,
                    "protected-terrain")
                requireCivs(canModify(city.owner, head, Material.AIR), "lands-denied")
                val above = change.position.copy(y = change.position.y + 1)
                requireCivs(cover.any { it.position == above } || read(above).type.isAir, "protected-terrain")
                for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1))
                    requireCivs(roadUpkeepSideSafe(read(change.position.copy(x = floor.x + dx, z = floor.z + dz))), "protected-terrain")
                projected[change.position] = Bukkit.createBlockData(Material.AIR)
            }
            projected[floor] = finish
        }
        fun collision(x: Int, y: Int, z: Int): CitizenCollisionSnapshot {
            val p = CivPosition(x, y, z)
            val data = projected[p]
            val block = if (data == null) read(p) else null
            val boxes = if (data?.material?.isAir == true) emptyList()
                else if (data != null) roadUpkeepBoxes(data).map { it.at(CivPosition(0, 0, 0)) }
                else requireNotNull(block).collisionShape.boundingBoxes.map { it.clone() }
            return CitizenCollisionSnapshot(block?.isLiquid == true, block?.let { citizenRouteOpenable(it.blockData) != null } == true,
                boxes, boxes.map { it.clone().shift(x.toDouble(), y.toDouble(), z.toDouble()) })
        }
        for (owner in junction.owners) {
            val route = if (owner.source.id == junction.repairOwner) junction.repair.route else owner.route
            val keys = junction.columns()
            val indices = route.indices.filter { civsTerrainKey(route[it].x, route[it].z) in keys }
            if (indices.isEmpty()) continue // A shoulder can overlap without the centreline entering this cut.
            val section = route.subList(maxOf(0, indices.first() - 1), minOf(route.size, indices.last() + 2))
            requireCivs(section.size >= 2 && roadHalfStepWalk(world, section, ::collision) != null &&
                roadHalfStepWalk(world, section.asReversed(), ::collision) != null, "autonomy-no-path")
        }
    }

    /** Preflight only: bounded final cut geometry never supplies navigation arrival or work credit. */
    private fun validateRoadCut(city: Settlement, upkeep: CivRoadUpkeep, repair: CivRoadRouteOverride,
        run: IntRange, world: World, settlements: List<Settlement>) {
        val section = repair.route.subList(run.first - 1, run.last + 2)
        val profile = CivsAutonomousTerraform.roadProfile(section)
        val levels = profile.mapValues { it.value.y }
        val pavement = CivsRoadMaintenance.ownedPavement(city, upkeep)
        val projected = hashMapOf<CivPosition, BlockData>()
        fun read(p: CivPosition): Block {
            requireCivs(p.y in world.minHeight until world.maxHeight, "outside-world")
            requireCivs(world.isChunkLoaded(p.x shr 4, p.z shr 4), "chunks-unloaded")
            return world.getBlockAt(p.x, p.y, p.z)
        }
        for (floor in profile.values) {
            requireCivs(city.contains(floor.x, floor.z) && world.worldBorder.isInside(
                Location(world, floor.x + .5, floor.y.toDouble(), floor.z + .5)), "outside-border")
            for (other in settlements.filter { it.worldId == city.worldId }) {
                requireCivs(other.id == city.id || !other.contains(floor.x, floor.z), "city-overlap")
                fun column(p: CivPosition) = p.x == floor.x && p.z == floor.z
                requireCivs(other.inventory?.containers.orEmpty().none(::column) &&
                    other.pendingControls.orEmpty().none { column(it.position) || it.approach.orEmpty().any { c -> column(c.position) } },
                    "building-overlap")
                requireCivs(civsProtectedBuildings(other).none {
                    CivsAutonomousGeometry.horizontalContains(CivsAutonomousGeometry.futureBounds(other, it), floor) ||
                        CivsWorksiteLayouts.isField(it, floor.x, floor.z) || it.control?.let(::column) == true
                }, "building-overlap")
            }
            val block = read(floor)
            val finish = Bukkit.createBlockData(CivsSettlementLayout.pathPavement(floor, levels, city.style))
            requireCivs(roadUpkeepReplaceable(block) || sameConstructionState(block.blockData, finish), "protected-terrain")
            requireCivs(block.state !is TileState && !block.isLiquid && (block.blockData as? Waterlogged)?.isWaterlogged != true,
                "protected-terrain")
            val support = read(floor.copy(y = floor.y - 1))
            requireCivs(roadUpkeepGround(support) && fullTerraformSupport(support), "autonomy-no-path")
            val cover = CivsRoadMaintenance.covers(floor, (1..CivsRoadMaintenance.MAX_CLEARANCE).map {
                read(floor.copy(y = floor.y + it)).blockData.asString
            }, pavement) ?: throw CivsRejection("protected-terrain")
            requireCivs(canModify(city.owner, block, finish.material), "lands-denied")
            for (change in cover) {
                val head = read(change.position)
                requireCivs(head.state !is TileState && !head.isLiquid && (head.blockData as? Waterlogged)?.isWaterlogged != true,
                    "protected-terrain")
                requireCivs(canModify(city.owner, head, Material.AIR), "lands-denied")
                val above = change.position.copy(y = change.position.y + 1)
                requireCivs(cover.any { it.position == above } || read(above).type.isAir, "protected-terrain")
                for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1))
                    requireCivs(roadUpkeepSideSafe(read(change.position.copy(x = floor.x + dx, z = floor.z + dz))), "protected-terrain")
                projected[change.position] = Bukkit.createBlockData(Material.AIR)
            }
            projected[floor] = finish
        }
        fun collision(x: Int, y: Int, z: Int): CitizenCollisionSnapshot {
            val p = CivPosition(x, y, z)
            val data = projected[p]
            val block = if (data == null) read(p) else null
            val boxes = if (data?.material?.isAir == true) emptyList()
                else if (data != null) roadUpkeepBoxes(data).map { it.at(CivPosition(0, 0, 0)) }
                else requireNotNull(block).collisionShape.boundingBoxes.map { it.clone() }
            return CitizenCollisionSnapshot(block?.isLiquid == true, block?.let { citizenRouteOpenable(it.blockData) != null } == true,
                boxes, boxes.map { it.clone().shift(x.toDouble(), y.toDouble(), z.toDouble()) })
        }
        requireCivs(roadHalfStepWalk(world, section, ::collision) != null, "autonomy-no-path")
    }

    private fun roadUpkeepGround(block: Block): Boolean =
        (block.type in TERRAFORM_GROUND || block.type == Material.DIRT_PATH || block.type == Material.COBBLESTONE) &&
            block.state !is TileState && !block.isLiquid && (block.blockData as? Waterlogged)?.isWaterlogged != true

    /** These ordinary plants attach below, never to the side of the changed column. */
    private fun roadUpkeepSideSafe(block: Block): Boolean = block.type.isAir || roadUpkeepGround(block) ||
        civsRetainedRouteStep(block.blockData) || CivsRoadMaintenance.landing(block.blockData.asString) ||
        block.type == Material.SHORT_GRASS || block.type == Material.FERN

    private fun roadUpkeepReplaceable(block: Block): Boolean = block.type.isAir || roadUpkeepGround(block) ||
        CivsRoadMaintenance.surface(block.blockData.asString) && civsRetainedRouteStep(block.blockData)

}

private fun requireCivs(condition: Boolean, reason: String) { if (!condition) throw CivsRejection(reason) }
