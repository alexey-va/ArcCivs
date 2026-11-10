package ru.arc.civs

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class CivAutonomousDesign(val blueprint: CivBlueprint, val terrain: CivTerraformPlan? = null,
    val infrastructureSource: CivPlacementPlan? = null, val paidApproach: CivPaidApproachReuse? = null,
    val wallApproach: CivWallRefitApproach? = null)

internal data class CivWallRefitApproach(val city: Settlement, val original: List<CivPosition>, val paidFloors: Set<CivPosition>)

/** The planner and its diagnostic export capture the same water-foundation domain. */
internal fun civsAutonomousWaterColumns(frame: CivSettlementFrame, retainedGates: List<CivBox>): Set<Long> =
    (listOf(frame.core) + frame.gates.map { it.passage } + retainedGates).flatMapTo(hashSetOf()) { box ->
        (box.min.x..box.max.x).flatMap { x -> (box.min.z..box.max.z).map { z -> civsTerrainKey(x, z) } }
    }

/** Detached earthworks selection; the service owns durable SITE admission and construction. */
internal class CivsAutonomousPlacement(
    private val blueprints: CivsBlueprints,
    private val world: CivsWorld,
    private val executor: Executor,
    private val loadAccess: (Settlement) -> CompletableFuture<CivAutonomousAccess> = {
        CompletableFuture.completedFuture(CivAutonomousAccess())
    },
    private val writeCapture: (UUID, ByteArray) -> CompletableFuture<Unit> = { _, _ ->
        CompletableFuture.failedFuture(CivsRejection("storage-unavailable"))
    },
    private val deleteCapture: (UUID) -> CompletableFuture<Boolean> = { CompletableFuture.completedFuture(false) },
    private val dataVersion: Int = 0,
) : AutoCloseable {
    private val jobs = linkedMapOf<UUID, Search>()
    private val planning = linkedMapOf<UUID, CompletableFuture<CivSettlementPlan>>()
    private val terminal = linkedMapOf<UUID, Map<String, Any?>>()
    private var closed = false

    fun search(city: Settlement, type: BuildingType, level: Int, buildingId: UUID,
        captureVolume: Boolean = false, initialPlan: CivSettlementPlan? = null,
        skipPrepared: Int = 0): CompletableFuture<CivAutonomousDesign> {
        if (skipPrepared !in 0..MAXIMUM_OPTIONS || skipPrepared > 0 && initialPlan == null ||
            initialPlan != null && (!captureVolume || type !in CivsAutonomy.profile || type == BuildingType.TOWN_HALL || level != 1 ||
            CivsSettlementPlans.current(city) != initialPlan || !CivsSettlementGrowth.covers(city, initialPlan) ||
            city.building(buildingId) != null || CivsSettlementPlans.next(city, type)?.building?.id != buildingId ||
            CivsAutonomy.nextGoal(city, blueprints::maximumLevel)?.let {
                it.type == type && it.targetLevel == 1 && it.buildingId == null
            } != true || city.autonomy?.paused != false || city.pendingDeletion != null ||
            city.buildings.singleOrNull { it.type == BuildingType.TOWN_HALL } == null ||
            city.construction != null || city.constructionQueue.orEmpty().isNotEmpty() || city.pendingControls.orEmpty().isNotEmpty()))
            return CompletableFuture.failedFuture(CivsRejection("stale-development"))
        val planningCity = if (initialPlan == null) city else city.copy(autonomy = requireNotNull(city.autonomy).copy(
            plan = null, layoutHalfWidth = initialPlan.halfWidth))
        return admit(Search(planningCity, type, level, buildingId, captureVolume = captureVolume,
            nativeCity = city, initialPlan = initialPlan, skipPrepared = skipPrepared))
    }

    fun decoration(city: Settlement, entryId: String, buildingId: UUID): CompletableFuture<CivAutonomousDesign> =
        admit(Search(city, BuildingType.DECORATION, 1, buildingId, catalogEntry = entryId))

    fun infrastructure(city: Settlement, protectPaidGateFloors: Boolean =
        CivsSettlementPlans.current(city)?.perimeterEntrances != null): CompletableFuture<CivAutonomousDesign> =
        admit(Search(city, BuildingType.ROAD, 1, UUID.randomUUID(), infrastructure = true,
            protectPaidGateFloors = protectPaidGateFloors))

    /** Refit geometry is captured by the same admission and queue as a new resident-built site. */
    fun rebuildInfrastructure(city: Settlement, source: CivPlacementPlan, plans: List<CivPlacementPlan>,
        survivor: CivBuilding? = null): CompletableFuture<CivAutonomousDesign> =
        blueprints.recover(source).thenComposeAsync({
            val access = CivAutonomousAccess.from(city, plans)
            val box = source.building.bounds
            val routes = if (survivor != null) CivsInfrastructureRebuild.witnesses(city, plans)
                else (access.routes.values.flatten() + city.buildings.flatMap(CivsAutonomousGeometry::approach) +
                    city.buildings.filter { it.type == BuildingType.ROAD }.flatMap { it.transit.orEmpty() })
            val witnesses = routes.filter { it.x in box.min.x..box.max.x && it.z in box.min.z..box.max.z }.distinct()
            val proposal = CivsInfrastructureRebuild.proposal(city, source, witnesses, survivor)
            val piece = CivSettlementPiece(proposal.building.id, proposal.building.type, box, proposal.building.entrance,
                proposal.blocks.map { CivSettlementBlock(it.position, it.after) }, proposal.building.transit.orEmpty(),
                profileStage = CivsInfrastructureRebuild.stage(proposal.building))
            val approach = if (proposal.building.type == BuildingType.WALL && source.building.type == BuildingType.WALL)
                wallRefitApproach(city, source, plans) else null
            val bytes = CivsInfrastructure.schematic(piece, dataVersion)
            writeCapture(proposal.id, bytes).thenCompose {
                blueprints.prepareInfrastructure(piece, city.worldId, bytes, proposal.id)
            }.thenApply {
                CivAutonomousDesign(it.copy(building = proposal.building), proposal.terrain, source, wallApproach = approach)
            }
        }, executor)

    fun wallInfill(city: Settlement, gateId: UUID, wallId: UUID,
        acceptedWork: List<CivPlacementPlan>): CompletableFuture<CivAutonomousDesign> {
        val job = Search(city, BuildingType.WALL, 1, UUID.randomUUID(), infrastructure = true,
            wallInfill = CivsWallInfill.target(city, gateId, wallId), acceptedWork = acceptedWork.toList())
        val preceding = synchronized(jobs) {
            jobs[city.id]?.takeIf { it.unaccepted && city.building(it.buildingId) == null }
        } ?: return admit(job)
        // The service already reserves this city. Wait only for detached search cleanup;
        // waiting for its later city commit would deadlock that reservation.
        return preceding.retired.thenCompose { admit(job) }.also { waiting ->
            waiting.whenComplete { _, _ -> if (waiting.isCancelled) job.result.cancel(false) }
        }
    }

    /** One adopted plan, using the same detached height field, catalogue and finite solvers. */
    fun plan(city: Settlement, unpaidSite: CivAutonomousDesign? = null,
        recoverPerimeter: Boolean = false): CompletableFuture<CivSettlementPlan> {
        val result = CompletableFuture<CivSettlementPlan>()
        synchronized(jobs) {
            if (closed) return CompletableFuture.failedFuture(CivsRejection("world-closed"))
            if (city.id in jobs || city.id in planning || jobs.size + planning.size >= 2)
                return CompletableFuture.failedFuture(CivsRejection("world-busy"))
            planning[city.id] = result
        }
        val prepared = runCatching {
            if (recoverPerimeter) {
                if (unpaidSite != null) throw CivsRejection("stale-development")
                val hall = city.buildings.singleOrNull { it.type == BuildingType.TOWN_HALL }
                    ?: throw CivsRejection("stale-development")
                return@runCatching loadAccess(city).thenCombineAsync(world.autonomousHeightField(city, hall, hall, markWaterTop = true), { access, surface ->
                    if (result.isDone || closed) throw CivsRejection("world-closed")
                    CivsSettlementPlans.recoverDeferredPerimeter(city, surface, access, executor) { columns ->
                        if (result.isDone || closed) throw CivsRejection("world-closed")
                        world.autonomousShoreField(city, hall, columns).thenApply { survey ->
                            if (result.isDone || closed) throw CivsRejection("world-closed")
                            survey
                        }
                    }
                }, executor).thenCompose { it }
            }
            val seed = unpaidSite?.blueprint?.building?.copy(phase = BuildingPhase.SITE)
            val seedRoute = unpaidSite?.terrain?.route?.toList()
            if (seed != null && (seed.type !in CivsAutonomy.profile || seed.type == BuildingType.TOWN_HALL ||
                seed.level != 1 || city.building(seed.id) != null || CivsSettlementPlans.next(city, seed.type)?.building?.id != seed.id ||
                CivsAutonomy.nextGoal(city, blueprints::maximumLevel)?.let {
                    it.type == seed.type && it.targetLevel == 1 && it.buildingId == null
                } != true || city.autonomy?.paused != false || city.pendingDeletion != null ||
                city.buildings.singleOrNull { it.type == BuildingType.TOWN_HALL } == null ||
                city.construction != null || city.constructionQueue.orEmpty().isNotEmpty() || city.pendingControls.orEmpty().isNotEmpty() ||
                seedRoute.isNullOrEmpty() || seedRoute.last() != seed.entrance))
                throw CivsRejection("stale-development")
            val oldPlan = if (seed == null) null else requireNotNull(CivsSettlementPlans.current(city))
            val oldSlot = oldPlan?.plots?.singleOrNull { it.building.id == seed?.id }?.building
            val sameSlotPlan = oldPlan?.takeIf { seed != null && oldSlot != null && it.deferredPerimeter?.plotId != seed.id &&
                sameHorizontalSlot(seed, oldSlot) }?.let { intent ->
                val seedId = requireNotNull(seed).id
                intent.copy(plots = intent.plots.filter { it.building.id != seedId }, routes = intent.routes - seedId.toString())
            }
            val surveyCity = if (seed == null) city else city.copy(buildings = city.buildings + seed,
                autonomy = requireNotNull(city.autonomy).copy(plan = sameSlotPlan, layoutHalfWidth = requireNotNull(oldPlan).halfWidth))
            val hall = city.buildings.first { it.type == BuildingType.TOWN_HALL }
            val templates = CivsAutonomy.profile.associateWith { type ->
                val entry = seed?.takeIf { type == it.type }?.schematicId?.takeIf { it.startsWith("civs/v2/") }
                    ?: blueprints.autonomousSource(city.style, type)
                planTemplate(city, type, entry)
            }
            val frame = CivsSettlementLayout.frame(surveyCity)
            val fortification: CompletableFuture<CivFortificationTemplate?> = if (frame.halfWidth > 56 && surveyCity.autonomy?.plan == null &&
                surveyCity.buildings.none { it.type.isInfrastructure }) blueprints.fortification(city.style)
                else CompletableFuture.completedFuture(null)
            val waterColumns = civsAutonomousWaterColumns(frame, oldPlan?.gates.orEmpty())
            val inputs = loadAccess(city).thenCombine(world.autonomousHeightField(city, hall, hall, waterColumns)) { access, surface -> access to surface }
                .thenCombine(fortification) { input, template -> Triple(input.first, input.second, template) }
            CompletableFuture.allOf(*templates.values.toTypedArray()).thenCombineAsync(inputs, { _, input ->
                if (result.isDone || closed) throw CivsRejection("world-closed")
                val loaded = templates.mapValues { it.value.join() }
                val envelope = seed?.let { candidate ->
                    CivsAutonomousGeometry.union(CivsAutonomousGeometry.futureBounds(city, candidate),
                        CivsSettlementPlans.at(loaded.getValue(candidate.type).second, candidate.anchor, candidate.rotation, candidate.id).bounds)
                }
                if (seed != null && oldPlan?.deferredPerimeter?.plotId == seed.id) {
                    return@thenCombineAsync CompletableFuture.completedFuture(replaceDeferredParcel(city, oldPlan, seed,
                        requireNotNull(envelope), requireNotNull(unpaidSite.terrain), input.first))
                }
                val kept = sameSlotPlan ?: oldPlan?.takeIf { intent ->
                    seed != null && seed.type in CivsSettlementLayout.exteriorTypes &&
                        compatibleSlot(city, seed, unpaidSite.terrain, intent, requireNotNull(envelope))
                }?.let { intent -> intent.copy(plots = intent.plots.filter { it.building.id != seed?.id },
                    routes = intent.routes - requireNotNull(seed).id.toString()) }
                if (oldPlan?.perimeterEntrances != null && kept == null)
                    throw CivsRejection("autonomy-no-site", mapOf("stage" to "retained-perimeter-parcel"))
                val projected = if (seed == null) city else surveyCity.copy(autonomy = requireNotNull(surveyCity.autonomy).copy(plan = kept))
                val access = if (seed == null) input.first else input.first.copy(
                    routes = input.first.routes + (seed.id to requireNotNull(seedRoute)), reservedFortificationPorts = emptyList())
                // Replacing one declined reservation preserves its existing forecast and sibling plots.
                val profile = if (seed == null) CivsSettlementGrowth.profile(city) else requireNotNull(oldPlan).plots
                    .filter { !it.building.type.isInfrastructure && it.building.type != BuildingType.DECORATION }
                    .map { it.building.type }
                if (seed == null) {
                    return@thenCombineAsync try {
                        CompletableFuture.completedFuture(CivsSettlementPlans.prepareGrowth(projected, loaded, input.second,
                            access, profile, fortification = input.third))
                    } catch (failure: CivsRejection) {
                        if (!CivsSettlementPlans.compactCivicMayFit(projected, input.second, access, profile, failure)) throw failure
                        val type = profile.last()
                        val entry = blueprints.autonomousAlternatives(city.style, type).firstOrNull() ?: throw failure
                        planTemplate(city, type, entry).thenApplyAsync({ alternative ->
                            if (result.isDone || closed) throw CivsRejection("world-closed")
                            if (alternative.second.level != type.maxLevel) throw failure
                            CivsSettlementPlans.deferCivicParcel(projected, loaded + (type to alternative), input.second,
                                access, profile, failure) ?: throw failure
                        }, executor)
                    }
                }
                val plan = try {
                    CivsSettlementPlans.prepare(projected, loaded, input.second, access,
                        fortification = input.third, profile = profile)
                } catch (failure: CivsRejection) {
                    if (kept == null || input.second.columns.values.any { !it.loaded }) throw failure
                    val deferred = deferExteriorPerimeter(city, requireNotNull(oldPlan), seed, requireNotNull(envelope),
                        requireNotNull(unpaidSite.terrain), input.first, failure) ?: throw failure
                    return@thenCombineAsync CompletableFuture.completedFuture(deferred)
                }
                if (!CivsSettlementGrowth.covers(city, plan) ||
                    plan.plots.count { it.building.type != BuildingType.DECORATION } !=
                        requireNotNull(oldPlan).plots.count { it.building.type != BuildingType.DECORATION } ||
                    plan.plots.singleOrNull { it.building.id == seed.id }?.building != seed ||
                    plan.routes[seed.id.toString()] != seedRoute || city.buildings.filterNot { it.type.isInfrastructure }.any { actual ->
                        !civsSameBuildingGeometry(plan.plots.singleOrNull { it.building.id == actual.id }?.building,
                            actual.copy(phase = BuildingPhase.SITE))
                    }) throw CivsRejection("invalid-plan")
                val paidRoutes = input.first.routes.filterKeys { city.building(it)?.type?.isInfrastructure == false }
                    .mapKeys { it.key.toString() }
                if (kept != null && (plan.plots.filter { it.building.id != seed.id && city.building(it.building.id) == null } !=
                    kept.plots.filter { city.building(it.building.id) == null } ||
                    (kept.routes + paidRoutes).any { (id, route) -> plan.routes[id] != route } || plan.walls != kept.walls ||
                    plan.gates != kept.gates || plan.gateSides != kept.gateSides)) throw CivsRejection("invalid-plan")
                CompletableFuture.completedFuture(plan.copy(revision = Math.addExact(requireNotNull(city.autonomy?.plan).revision, 1L)))
            }, executor).thenCompose { it }
        }.getOrElse { CompletableFuture.failedFuture(it) }
        prepared.whenComplete { value, failure ->
            synchronized(jobs) { planning.remove(city.id, result) }
            if (failure == null) result.complete(value) else result.completeExceptionally(failure)
        }
        return result
    }

    private fun planTemplate(city: Settlement, type: BuildingType, entry: String?): CompletableFuture<Pair<CivBuilding, CivBuilding>> {
        val id = UUID.nameUUIDFromBytes("arccivs-plan-template/${city.id}/$type".toByteArray(Charsets.UTF_8))
        val origin = CivPosition(0, 0, 0)
        val first = if (entry == null) blueprints.prepare(city.style, type, 1, id, city.worldId, origin, 2, 0)
            else blueprints.prepareEntry(entry, id, city.worldId, origin, 2, 0)
        val last = if (entry != null) first.thenCompose { blueprint ->
            val maximum = blueprints.maximumLevel(blueprint.building)
            if (maximum == 1) CompletableFuture.completedFuture(blueprint)
            else blueprints.prepareUpgrade(blueprint.building, maximum, city.worldId)
        } else if (type.maxLevel == 1) first else blueprints.prepare(city.style, type, type.maxLevel, id, city.worldId, origin, 2, 0)
        return first.thenCombine(last) { x, y -> x.building to y.building }
    }

    private fun sameHorizontalSlot(a: CivBuilding, b: CivBuilding): Boolean = a.id == b.id && a.type == b.type &&
        a.schematicId == b.schematicId && a.anchor.x == b.anchor.x && a.anchor.z == b.anchor.z && a.rotation == b.rotation &&
        a.bounds.min.x == b.bounds.min.x && a.bounds.max.x == b.bounds.max.x &&
        a.bounds.min.z == b.bounds.min.z && a.bounds.max.z == b.bounds.max.z

    private fun deferExteriorPerimeter(city: Settlement, plan: CivSettlementPlan, seed: CivBuilding, envelope: CivBox,
        terrain: CivTerraformPlan, access: CivAutonomousAccess, failure: CivsRejection): CivSettlementPlan? {
        val deferred = CivDeferredPerimeter("infrastructure", seed.id, seed.anchor)
        val details = failure.values
        val conflict = "gate-refit-route-conflict"
        if (!deferredExteriorParcel(seed.type, deferred) || !unbuiltPerimeter(city, plan, access) ||
            failure.reason != "autonomy-layout-obstructed" ||
            details["stage"] != deferred.stage || details["closed"] != "false" ||
            (details[conflict]?.toIntOrNull() ?: 0) <= 0 ||
            details.keys.any { it !in setOf(conflict, "stage", "closed", "pieces", "wall_strips", "ROAD", "GATE", "WALL") })
            return null
        val replacement = replaceDeferredParcel(city, plan, seed, envelope, terrain, access, deferred)
        CivsDiagnostics.event("autonomous_unpaid_perimeter_candidate", "city" to city.id, "plot" to seed.id,
            "stage" to deferred.stage, "reason" to conflict, "count" to details[conflict])
        return replacement
    }

    private fun unbuiltPerimeter(city: Settlement, plan: CivSettlementPlan, access: CivAutonomousAccess): Boolean =
        plan.deferredPerimeter == null && plan.perimeterEntrances == null && plan.completedPerimeters.isNullOrEmpty() &&
            plan.plots.none { it.fortification != null } && city.buildings.none { it.type.isInfrastructure } &&
            access.infrastructure.isEmpty()

    private fun deferredExteriorParcel(type: BuildingType, deferred: CivDeferredPerimeter): Boolean =
        deferred.stage == "infrastructure" && type in CivsSettlementLayout.exteriorTypes

    /** Replace only the unpaid parcel and its approach. Recovered perimeter membership stays pinned. */
    private fun replaceDeferredParcel(city: Settlement, plan: CivSettlementPlan, seed: CivBuilding, envelope: CivBox,
        terrain: CivTerraformPlan, access: CivAutonomousAccess,
        deferred: CivDeferredPerimeter = requireNotNull(plan.deferredPerimeter)): CivSettlementPlan {
        val exterior = deferredExteriorParcel(seed.type, deferred)
        val inside = if (exterior) emptySet() else CivsSettlementGrowth.interior(CivsSettlementLayout.frame(city))
        val standing = access.standing(city, seed.id)
        val paid = access.routes.filterKeys { city.building(it)?.type?.isInfrastructure == false }
        val paidRoads = if (exterior) access.roadColumns(city) else emptySet()
        val fits = compatibleSlot(city, seed, terrain, plan, envelope) &&
            (exterior || (envelope.min.x..envelope.max.x).all { x ->
                (envelope.min.z..envelope.max.z).all { z -> civsTerrainKey(x, z) in inside }
            }) && city.buildings.any { CivsRules.isDepot(it) && it.entrance == terrain.route.firstOrNull() } &&
            paid.all { (id, route) -> plan.routes[id.toString()] == route } &&
            paidRoads.none { column -> (column shr 32).toInt() in envelope.min.x..envelope.max.x &&
                column.toInt() in envelope.min.z..envelope.max.z } &&
            terrain.blocks.none { it.before != it.after && civsTerrainKey(it.position.x, it.position.z) in paidRoads } &&
            terrain.route.all { point -> standing[civsTerrainKey(point.x, point.z)]?.y?.let { it == point.y } != false }
        if (!fits) throw CivsRejection("autonomy-no-site", mapOf("stage" to "deferred-perimeter-parcel"))
        val updated = plan.copy(revision = Math.addExact(plan.revision, 1L),
            plots = plan.plots.map { if (it.building.id == seed.id) CivSettlementPlot(seed, envelope) else it },
            routes = plan.routes + (seed.id.toString() to terrain.route.toList()),
            deferredPerimeter = deferred.copy(plotAnchor = seed.anchor))
        CivsSettlementPlans.validate(city, updated)
        return updated
    }

    /** Native proof owns actual access; a compatible parcel need not reselect its perimeter. */
    private fun compatibleSlot(city: Settlement, seed: CivBuilding, terrain: CivTerraformPlan?, plan: CivSettlementPlan,
        envelope: CivBox): Boolean {
        if (terrain == null || !civsContainsBox(envelope, seed.bounds)) return false
        val otherPlots = plan.plots.filter { it.building.id != seed.id }.map { it.envelope }
        val obstacles = otherPlots + plan.walls
        if ((obstacles + plan.gates).any { CivsAutonomousGeometry.overlaps(envelope, it) } ||
            CivsAutonomousGeometry.approach(seed).any { p -> obstacles.any { CivsAutonomousGeometry.horizontalContains(it, p) } }) return false
        val unpaidRoads = plan.routes.filterKeys { it != seed.id.toString() && city.building(UUID.fromString(it)) == null }
            .values.flatten().groupBy { civsTerrainKey(it.x, it.z) }
        if (unpaidRoads.values.flatten().any { CivsAutonomousGeometry.horizontalContains(envelope, it) } ||
            terrain.blocks.any { cell -> obstacles.any { CivsAutonomousGeometry.horizontalContains(it, cell.position) } }) return false
        return terrain.route.all { p -> obstacles.none { CivsAutonomousGeometry.horizontalContains(it, p) } &&
            unpaidRoads[civsTerrainKey(p.x, p.z)].orEmpty().all { it.y == p.y } }
    }

    fun reserved(city: Settlement, plot: CivSettlementPlot): CompletableFuture<CivAutonomousDesign> =
        admit(Search(city, plot.building.type, 1, plot.building.id, reservation = plot,
            captureVolume = CivsSettlementPlans.current(city)?.deferredPerimeter?.plotId == plot.building.id))

    /** Reuse the immutable founding capture and choose the least earthwork at this same horizontal site. */
    fun captureHallSite(city: Settlement, blueprint: CivBlueprint): CompletableFuture<CivPlacementPlan> {
        val source = city.building(blueprint.building.id)
            ?: return CompletableFuture.failedFuture(CivsRejection("stale-building"))
        if (!civsAutonomousHallSite(city, source) || city.construction != null ||
            blueprint.building != source.copy(phase = BuildingPhase.CONSTRUCTING))
            return CompletableFuture.failedFuture(CivsRejection("stale-building"))
        fun capture(selected: CivBlueprint) = world.captureManualFoundation(city.id, city.owner, city.worldId,
            selected.building, selected.cells, executor, source, autonomous = true).thenCompose { terrain ->
            if (selected.building.control != source.control && terrain?.blocks.orEmpty().any { it.position == source.control })
                throw CivsRejection("hall-source-overlap")
            world.capture(city.id, city.owner, city.worldId, selected.building, selected.cells, source, terrain)
        }
        return world.autonomousHeightField(city, source, source, localFootprint = true).thenApplyAsync({ surface ->
            val option = CivsAutonomousTerraform.options(city,
                listOf(CivsAutonomousCandidate(blueprint.building)), surface).firstOrNull()
                ?: throw CivsRejection("hall-no-local-grade")
            val target = option.building
            if (target == blueprint.building) return@thenApplyAsync blueprint
            if (!civsAutonomousHallRetarget(city, source, target)) throw CivsRejection("hall-target-control-incompatible")
            val cells = CivsBlueprints.worldCells(blueprint.snapshot, target.anchor)
            if (cells.any { !it.isBarrier && CivPosition(it.position.x, it.position.y, it.position.z) == source.control })
                throw CivsRejection("hall-source-overlap")
            blueprint.copy(building = target, cells = cells)
        }, executor).thenCompose(::capture).exceptionallyCompose { failure ->
            val reason = CivsDiagnostics.rejection(failure)?.reason
            if (reason !in setOf("hall-source-overlap", "hall-target-control-incompatible", "hall-no-local-grade"))
                CompletableFuture.failedFuture(failure)
            else {
                // Keep a previously valid site buildable without selecting an expensive grade to evade its stock.
                CivsDiagnostics.event("hall_retarget_declined", "city" to city.id, "building" to source.id,
                    "source_y" to source.anchor.y, "reason" to reason)
                capture(blueprint)
            }
        }
    }

    /** Legacy, player-pinned SITE plans retain their exact anchor and receive the same durable earthworks. */
    fun prepareExisting(city: Settlement, blueprint: CivBlueprint,
        waterFoundations: List<CivWaterFoundation> = emptyList(),
        requiredGrass: List<CivPosition> = emptyList(),
        paidGateFloors: List<CivPosition> = emptyList()): CompletableFuture<CivAutonomousDesign> {
        val previous = city.building(blueprint.building.id)
        if (previous?.phase != BuildingPhase.SITE || previous.entrance != blueprint.building.entrance ||
            previous.rotation != blueprint.building.rotation)
            return CompletableFuture.failedFuture(CivsRejection("stale-building"))
        val adopted = CivsSettlementPlans.current(city)
        val reserved = adopted?.plots?.firstOrNull { it.building.id == blueprint.building.id &&
            it.building.anchor == blueprint.building.anchor && it.building.rotation == blueprint.building.rotation &&
            it.building.entrance == blueprint.building.entrance }
        return admit(Search(city, blueprint.building.type, blueprint.building.level, blueprint.building.id, blueprint, previous,
            infrastructure = blueprint.building.type.isInfrastructure, reservation = reserved,
            pinnedRoute = adopted?.routes?.get(blueprint.building.id.toString()),
            pinnedWaterFoundations = waterFoundations.toList(), pinnedRequiredGrass = requiredGrass.toList(),
            pinnedPaidGateFloors = paidGateFloors.toList()))
    }

    private fun admit(job: Search): CompletableFuture<CivAutonomousDesign> = synchronized(jobs) {
        if (closed) return@synchronized CompletableFuture.failedFuture(CivsRejection("world-closed"))
        if (job.result.isCancelled) return@synchronized job.result
        if (job.city.id in jobs || job.city.id in planning || jobs.size + planning.size >= 2)
            return@synchronized CompletableFuture.failedFuture(CivsRejection("world-busy"))
        jobs[job.city.id] = job
        job.result.whenComplete { design, failure ->
            job.pending?.cancel(false)
            if (job.temporaryCapture) deleteCapture(job.buildingId)
            synchronized(jobs) {
                jobs.remove(job.city.id, job)
                terminal.remove(job.city.id)
                terminal[job.city.id] = job.debug() + mapOf("outcome" to when {
                    job.result.isCancelled -> "cancelled"
                    failure != null -> ((unwrap(failure) as? CivsRejection)?.reason ?: "failed")
                    else -> "prepared"
                }, "cut" to design?.terrain?.cutCount, "fill" to design?.terrain?.fillCount,
                    "route_length" to design?.terrain?.route?.size)
                while (terminal.size > 16) terminal.remove(terminal.keys.first())
            }
            // Other result callbacks may run before this one. Admission must observe the
            // removed registry entry and completed terminal bookkeeping, not just result.isDone.
            if (job.result.isCancelled) job.retired.cancel(false) else job.retired.complete(Unit)
        }
        job.start()
        job.result
    }

    /** Cached active and terminal diagnostics; no native reads or new schematic requests. */
    fun debug(cityId: UUID? = null): Map<String, Any?> = synchronized(jobs) {
        mapOf("closed" to closed, "jobs" to jobs.size + planning.size, "maximum_jobs" to 2,
            "planning" to planning.size, "planning_cities" to planning.keys.filter { cityId == null || cityId == it }.map(UUID::toString),
            "entries" to jobs.values.filter { cityId == null || it.city.id == cityId }.take(2).map { it.debug() },
            "last" to terminal.filterKeys { cityId == null || it == cityId }.values.toList().takeLast(2))
    }

    override fun close() {
        val active = synchronized(jobs) { closed = true; jobs.values.toList() }
        active.forEach { it.result.cancel(false) }
        synchronized(jobs) { planning.values.toList() }.forEach { it.cancel(false) }
    }

    private inner class Search(val city: Settlement, val type: BuildingType, val level: Int, val buildingId: UUID,
        private val pinned: CivBlueprint? = null, private val previous: CivBuilding? = null,
        private val infrastructure: Boolean = false, private val reservation: CivSettlementPlot? = null,
        private val pinnedRoute: List<CivPosition>? = null, private val catalogEntry: String? = null,
        private val pinnedWaterFoundations: List<CivWaterFoundation> = emptyList(),
        private val pinnedRequiredGrass: List<CivPosition> = emptyList(),
        private val pinnedPaidGateFloors: List<CivPosition> = emptyList(),
        private val protectPaidGateFloors: Boolean = false,
        private val captureVolume: Boolean = false, private val nativeCity: Settlement = city,
        private val initialPlan: CivSettlementPlan? = null, skipPrepared: Int = 0,
        private val wallInfill: CivWallInfillTarget? = null,
        private val acceptedWork: List<CivPlacementPlan> = emptyList()) {
        private val adopted = CivsSettlementPlans.current(city)
        @Volatile private var resolvedCatalogEntry: String? = catalogEntry
        @Volatile private var familiesTried = emptyList<String>()
        private var alternatives: Iterator<String>? = null
        @Volatile private var attempted = 0
        val result = CompletableFuture<CivAutonomousDesign>()
        val retired = CompletableFuture<Unit>()
        val unaccepted: Boolean get() = previous == null && nativeCity.building(buildingId) == null
        @Volatile var temporaryCapture = false
        @Volatile var pending: CompletableFuture<*>? = null
        @Volatile var candidates = emptyList<CivsAutonomousCandidate>()
        @Volatile var options = emptyList<CivTerraformOption>()
        @Volatile var next = 0
        @Volatile var columns = 0
        @Volatile var phase = "catalog"
        @Volatile var selected: CivBuilding? = null
        private lateinit var surface: CivTerrainSurface
        private var access = CivAutonomousAccess()
        private val protectedRoadColumns by lazy {
            val deferred = (adopted ?: initialPlan)?.deferredPerimeter
            // A replacement can borrow the paid road before its exterior perimeter is deferred.
            val recoveringExterior = initialPlan != null && type in CivsSettlementLayout.exteriorTypes &&
                unbuiltPerimeter(nativeCity, initialPlan, access)
            if (recoveringExterior || infrastructure && adopted?.perimeterEntrances != null ||
                deferred?.plotId == buildingId && (CivsSettlementPlans.protectsPaidApproach(deferred.stage) ||
                    deferredExteriorParcel(type, deferred)))
                access.roadColumns(nativeCity) else emptySet()
        }
        private var pieces = emptyMap<UUID, CivSettlementPiece>()
        private var templates: Pair<CivBuilding, CivBuilding>? = null
        private var fortificationTemplate: CivFortificationTemplate? = null
        @Volatile private var infillTried = false
        @Volatile private var frontageCandidates = 0
        @Volatile private var infillCandidates = 0
        @Volatile private var candidateSource = "frontage"
        private var distributedFamily = false
        private var familyLimit = MAXIMUM_OPTIONS
        private var infillAt = MAXIMUM_OPTIONS
        private var infillOptions = IntRange.EMPTY
        @Volatile private var gradingSamples = emptyList<Map<String, Any>>()
        @Volatile private var routeRefreshed = false
        private var keepOriginalApproach = false
        @Volatile private var nativeTerrainRejected = false
        private var preferredPending = initialPlan != null
        private var remainingPrepared = skipPrepared
        private var preferredTerrace = false
        private var preferredAttempts = 0
        val rejections = java.util.concurrent.ConcurrentHashMap<String, Int>()
        @Volatile private var blockers = emptyList<CivSettlementBlocker>()

        fun debug(): Map<String, Any?> = mapOf("city" to city.id.toString(), "building" to buildingId.toString(),
            "type" to type.name, "candidate" to next, "candidates" to candidates.size, "options" to options.size,
            "surface_columns" to columns, "phase" to phase, "rejections" to rejections.toSortedMap(),
            "selected_anchor" to selected?.anchor, "fixed" to (pinned != null), "layout_blockers" to blockers,
            "candidate_source" to candidateSource, "frontage_candidates" to frontageCandidates,
            "infill_candidates" to infillCandidates, "grading_samples" to gradingSamples,
            "catalog_source" to resolvedCatalogEntry, "families_tried" to familiesTried, "attempted" to attempted,
            "route_refreshed" to routeRefreshed, "preferred_terraces_attempted" to preferredAttempts)

        private fun frame(): CivSettlementFrame = CivsSettlementLayout.frame(city).let { frame ->
            if (adopted?.perimeterEntrances != null) frame else CivsPerimeterCrossings.adapt(
                frame, access.routes.values + adopted?.routes.orEmpty().values, access.infrastructure)
        }

        fun start() {
            phase = "access"
            await(loadAccess(city)) { snapshot -> access = snapshot; catalog() }
        }

        private fun catalog() {
            phase = "catalog"
            if (infrastructure && pinned == null) {
                val unacceptedFortifications = adopted?.plots.orEmpty().filter { it.fortification != null &&
                    (city.building(it.building.id)?.phase != BuildingPhase.ACTIVE ||
                        it.building.id !in access.acceptedFortifications) }
                if (unacceptedFortifications.isNotEmpty()) {
                    rejections["fortification-not-active"] = unacceptedFortifications.size
                    fail("autonomy-layout-obstructed")
                    return
                }
                val hall = city.buildings.first { it.type == BuildingType.TOWN_HALL }
                phase = "surface"
                val waterColumns = (frame().wallObstacles + frame().gates.map { it.passage }).flatMapTo(hashSetOf()) { box ->
                    (box.min.x..box.max.x).flatMap { x -> (box.min.z..box.max.z).map { z -> civsTerrainKey(x, z) } }
                }
                await(world.autonomousHeightField(city, hall, hall, waterColumns, markWaterTop = protectPaidGateFloors)) { field ->
                    surface = field; columns = field.columns.size
                    if (wallInfill != null) {
                        val (piece, buildable) = CivsWallInfill.prepare(city, wallInfill, field, access, acceptedWork)
                        surface = buildable
                        pieces = mapOf(piece.id to piece)
                        candidates = listOf(CivsAutonomousCandidate(CivsInfrastructure.building(piece)))
                        options = candidates.map { CivTerraformOption(it.building, 0) }
                        candidateSource = "gate-wall-infill"
                        attempt()
                        return@await
                    }
                    val pavingRoutes = (access.routes.filterKeys { id -> city.building(id)?.type?.isInfrastructure == false }.values +
                        adopted?.routes.orEmpty().values).distinct()
                    val reservedCity = city.copy(buildings = city.buildings + adopted?.plots.orEmpty()
                        .filter { city.building(it.building.id) == null }.map { it.building.copy(bounds = it.envelope) })
                    val plan = CivsSettlementLayout.infrastructure(reservedCity, frame(), field,
                        (access.routes.values + pavingRoutes).distinct(), access.infrastructure, pavingRoutes, access.routes.values,
                        acceptedFortifications = access.acceptedFortifications, retainedAccess = access,
                        protectPaidGateFloors = protectPaidGateFloors)
                    rejections.putAll(plan.rejected)
                    blockers = plan.blockers
                    val unplaced = plan.pieces.filter { city.building(it.id) == null }
                    val approvedEntrances = adopted?.perimeterEntrances
                    val completePerimeter = plan.perimeterClosed && plan.rejected.isEmpty() &&
                        (approvedEntrances == null || unplaced.all { it.type == BuildingType.ROAD || it.id.toString() in approvedEntrances })
                    if (blockers.isNotEmpty() || !completePerimeter) CivsDiagnostics.record(java.util.logging.Level.WARNING, city.id,
                        CivsDiagnostics.line("autonomous_layout_obstructed", "city" to city.id,
                            "perimeter_closed" to plan.perimeterClosed, "blockers" to blockers.take(4), "count" to blockers.size,
                            "rejected" to plan.rejected, "samples" to plan.rejectionSamples.mapValues { it.value.take(1) }))
                    // A partial outline can still contribute ordinary roads, but does not start a wall ring.
                    val missing = unplaced.filter { completePerimeter || it.type == BuildingType.ROAD }
                        .map { piece -> approvedEntrances?.get(piece.id.toString())?.let { piece.copy(entrance = it) } ?: piece }
                        .sortedWith(compareBy<CivSettlementPiece> { when (it.type) {
                            BuildingType.GATE -> 0; BuildingType.WALL -> 1; else -> 2
                        } }.thenBy { it.bounds.min.x }.thenBy { it.bounds.min.z })
                    if (missing.isEmpty()) {
                        fail(if (completePerimeter) "autonomy-layout-complete" else "autonomy-no-site")
                        return@await
                    }
                    pieces = missing.associateBy { it.id }
                    candidates = missing.map { CivsAutonomousCandidate(CivsInfrastructure.building(it)) }
                    options = candidates.map { CivTerraformOption(it.building, 0) }.take(MAXIMUM_OPTIONS)
                    attempt()
                }
                return
            }
            if (reservation != null) {
                if (reservation.fortification != null && pinned == null) {
                    phase = "fortification"
                    await(blueprints.fortification(city.style)) { template ->
                        if (template == null || !CivsSettlementPlans.matchesFortification(reservation, template)) {
                            fail("autonomy-catalog-changed")
                        } else {
                            fortificationTemplate = template
                            resolvedCatalogEntry = template.building.schematicId
                            candidates = listOf(CivsAutonomousCandidate(reservation.building.copy(bounds = reservation.envelope)))
                            captureSurface()
                        }
                    }
                    return
                }
                resolvedCatalogEntry = reservation.building.schematicId.takeIf { it.startsWith("civs/v2/") }
                candidates = listOf(CivsAutonomousCandidate(reservation.building.copy(bounds = reservation.envelope)))
                captureSurface()
                return
            }
            if (pinned != null) {
                if (infrastructure) pieces = mapOf(pinned.building.id to CivSettlementPiece(pinned.building.id,
                    pinned.building.type, pinned.building.bounds, pinned.building.entrance,
                    pinned.cells.filterNot { it.isBarrier }.map { CivSettlementBlock(
                        CivPosition(it.position.x, it.position.y, it.position.z), it.blockData) }, pinned.building.transit.orEmpty(),
                    pinnedWaterFoundations, requiredGrass = pinnedRequiredGrass, paidGateFloors = pinnedPaidGateFloors))
                candidates = listOf(CivsAutonomousCandidate(pinned.building.copy(bounds =
                    CivsAutonomousGeometry.futureBounds(city, pinned.building))))
                captureSurface()
                return
            }
            if (preferredPending) {
                preferredPending = false
                preferredTerrace = true
                val plot = initialPlan?.plots?.singleOrNull { it.building.id == buildingId && it.building.type == type }
                    ?: throw CivsRejection("invalid-plan")
                candidateSource = "reserved-terrace"
                resolvedCatalogEntry = plot.building.schematicId.takeIf { it.startsWith("civs/v2/") }
                candidates = listOf(CivsAutonomousCandidate(plot.building.copy(bounds = plot.envelope)))
                captureSurface()
                return
            }
            resolvedCatalogEntry = catalogEntry ?: reservation?.building?.schematicId?.takeIf { it.startsWith("civs/v2/") }
                ?: blueprints.autonomousSource(city.style, type)
            loadTemplates(resolvedCatalogEntry)
        }

        private fun loadTemplates(entry: String?) {
            phase = "catalog"
            resolvedCatalogEntry = entry
            val remaining = if (initialPlan == null) remainingFamilies() else null
            distributedFamily = remaining != null
            familyLimit = attempted + if (remaining?.hasNext() == true) minOf(MAXIMUM_OPTIONS / 2, MAXIMUM_OPTIONS - attempted)
                else MAXIMUM_OPTIONS - attempted
            infillAt = familyLimit
            infillOptions = IntRange.EMPTY
            if (entry != null) familiesTried = familiesTried + entry
            val current = if (entry != null) blueprints.prepareEntry(entry, buildingId, city.worldId,
                CivPosition(0, 0, 0), 2, 0) else blueprints.prepare(city.style, type, level, buildingId, city.worldId,
                CivPosition(0, 0, 0), 2, 0)
            val future = if (entry != null) current.thenCompose { blueprint ->
                val maximum = blueprints.maximumLevel(blueprint.building)
                if (maximum == level) CompletableFuture.completedFuture(blueprint)
                else blueprints.prepareUpgrade(blueprint.building, maximum, city.worldId)
            } else if (level == type.maxLevel) current else blueprints.prepare(city.style, type, type.maxLevel, buildingId,
                city.worldId, CivPosition(0, 0, 0), 2, 0)
            await(current.thenCombineAsync(future, { a, b -> a.building to b.building }, executor)) { pair ->
                templates = pair
                candidates = CivsSettlementLayout.candidates(city, pair.first, pair.second, access, frame())
                frontageCandidates = candidates.size
                if (distributedFamily && candidates.isNotEmpty() &&
                    (type !in CivsSettlementLayout.exteriorTypes || type == BuildingType.FARM && city.autonomy != null))
                    infillAt = attempted + (familyLimit - attempted) / 2
                if (candidates.isEmpty()) candidates = infill()
                captureSurface()
            }
        }

        private fun remainingFamilies(): Iterator<String>? {
            if (resolvedCatalogEntry == null || catalogEntry != null || infrastructure || pinned != null || previous != null ||
                reservation != null || adopted != null || city.autonomy?.plan != null || level != 1 ||
                city.building(buildingId) != null || attempted >= MAXIMUM_OPTIONS) return null
            return alternatives ?: blueprints.autonomousAlternatives(city.style, type)
                .filterNot { it == resolvedCatalogEntry }.distinct().take(MAXIMUM_OPTIONS).iterator().also { alternatives = it }
        }

        /** A new, unreserved site may change family; accepted geometry and all native budgets stay fixed. */
        private fun nextFamily(): Boolean {
            val remaining = remainingFamilies() ?: return false
            if (!remaining.hasNext()) return false
            candidates = emptyList()
            options = emptyList()
            next = 0
            infillTried = false
            templates = null
            selected = null
            candidateSource = "frontage"
            loadTemplates(remaining.next())
            return true
        }

        private fun infill(): List<CivsAutonomousCandidate> {
            val pair = templates ?: return emptyList()
            if (infillTried) return emptyList()
            val terrainKnown = this::surface.isInitialized
            infillTried = terrainKnown
            candidateSource = "infill"
            val seen = candidates.mapTo(hashSetOf()) { it.building.anchor to it.building.rotation }
            // Filter the detached surface before the 64-result cap. A shoreline must
            // not let the first unavailable yards hide a connected dry inner plot.
            return CivsSettlementLayout.infill(city, pair.first, pair.second, access, frame(), eligible = { building ->
                (building.anchor to building.rotation) !in seen && (!terrainKnown ||
                    CivsAutonomousTerraform.options(city, listOf(CivsAutonomousCandidate(building)), surface).isNotEmpty())
            })
                .also { infillCandidates += it.size }
        }

        private fun grade(choices: List<CivsAutonomousCandidate>): List<CivTerraformOption> =
            CivsAutonomousTerraform.options(city, choices, surface, fixed = pinned != null || reservation != null,
                tryNearbyCut = preferredTerrace) { reason, at ->
                rejections.merge(reason, 1, Int::plus)
                if (gradingSamples.size < 4) gradingSamples = gradingSamples +
                    mapOf("source" to candidateSource, "reason" to reason, "at" to at)
            }

        private fun captureSurface() {
            val first = candidates.firstOrNull() ?: run {
                if (!nextFamily()) fail("autonomy-no-site")
                return
            }
            if (this::surface.isInitialized) { gradeSurface(); return }
            phase = "surface"
            val waterColumns = pinnedWaterFoundations.mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }
            await(world.autonomousHeightField(nativeCity, first.building, previous, waterColumns)) { field ->
                surface = if (infrastructure && pinned != null) CivsWallInfill.acceptedSurface(city, pinned.building, field, access) else field
                columns = field.columns.size
                gradeSurface()
            }
        }

        private fun gradeSurface() {
            phase = "grading"
            options = if (infrastructure && pinned != null) listOf(CivTerraformOption(pinned.building, 0))
                else grade(candidates).take(familyLimit - attempted)
            infillOptions = if (candidateSource == "infill") options.indices else IntRange.EMPTY
            if (preferredTerrace && CivsAutonomousTerraform.compactTerrace(candidates.single().building)) {
                val oldY = candidates.single().building.anchor.y
                options = options.sortedWith(compareBy<CivTerraformOption> {
                    when { it.building.anchor.y > oldY -> 0; it.building.anchor.y == oldY -> 1; else -> 2 }
                }.thenBy { abs(it.building.anchor.y - oldY) })
            }
            attempt()
        }

        private fun attempt() {
            if (result.isDone) return
            if (infillAt < familyLimit && !infillTried && templates != null && attempted < familyLimit &&
                (attempted >= infillAt || next >= options.size)) {
                val extra = infill()
                candidates = candidates + extra
                val added = grade(extra).take(familyLimit - attempted)
                infillOptions = next until next + added.size
                options = (options.take(next) + added + options.drop(next)).take(next + familyLimit - attempted)
            }
            if (next >= options.size || attempted >= familyLimit || attempted >= MAXIMUM_OPTIONS) {
                if (preferredTerrace && attempted < MAXIMUM_OPTIONS) {
                    preferredTerrace = false
                    candidates = emptyList(); options = emptyList(); next = 0
                    candidateSource = "frontage"
                    catalog()
                    return
                }
                // Every tier and family shares this detached surface and the original 64-attempt budget.
                if (!infillTried && templates != null && attempted < familyLimit) {
                    val extra = infill()
                    candidates = candidates + extra
                    val added = grade(extra).take(familyLimit - attempted)
                    infillOptions = next until next + added.size
                    options = options + added
                    if (next < options.size) { attempt(); return }
                }
                if (distributedFamily && attempted < MAXIMUM_OPTIONS && nextFamily()) return
                val reason = when {
                    rejections.containsKey("chunks-unloaded") && rejections.size == 1 -> "chunks-unloaded"
                    rejections.containsKey("autonomy-search-budget") -> "autonomy-search-budget"
                    else -> "autonomy-no-site"
                }
                if (!distributedFamily && reason == "autonomy-no-site" && nextFamily()) return
                return fail(reason)
            }
            if (distributedFamily) candidateSource = if (next in infillOptions || frontageCandidates == 0) "infill" else "frontage"
            val option = options[next++]
            attempted++
            if (preferredTerrace) preferredAttempts++
            selected = option.building
            phase = "grading"
            val piece = pieces[option.building.id]
            val futureObstacles = adopted?.plots.orEmpty().filter {
                it.building.id != option.building.id && city.building(it.building.id) == null
            }.map { it.envelope }
            val infrastructureFloors = piece?.floorGrades().orEmpty()
            val reservedRoute = when {
                routeRefreshed -> null
                reservation == null -> pinnedRoute
                else -> adopted?.routes?.get(buildingId.toString()) ?: throw CivsRejection("invalid-plan")
            }
            val paidApproach = if (!keepOriginalApproach && !infrastructure && pinned != null && previous?.phase == BuildingPhase.SITE &&
                city.construction == null && city.constructionQueue.orEmpty().firstOrNull() == buildingId &&
                adopted != null && reservedRoute != null && reservedRoute == pinnedRoute &&
                civsSameBuildingGeometry(reservation?.building, previous))
                CivsAutonomousTerraform.paidApproach(city, option.building, reservedRoute, CivsSettlementLayout.frame(city),
                    surface, access, futureObstacles, adopted.routes.filterKeys { it != buildingId.toString() }.values)
            else null
            fun retryApproach(failure: Throwable) {
                val cause = unwrap(failure)
                if (paidApproach != null && cause is CivsRejection && cause.reason in setOf("autonomy-no-path", "autonomy-terrain")) {
                    // The fixed SITE already had a valid alternative. Try its original pin once under the same budget.
                    keepOriginalApproach = true
                    next--
                    attempt()
                } else retry(failure)
            }
            val draft = try { if (preferredTerrace) preferredDraft(option) else CivsAutonomousTerraform.draft(city, option, surface, access,
                (if (pinned != null && !infrastructure && pinnedRoute == null) emptyList()
                    else frame().wallObstacles) + futureObstacles,
                infrastructureFloors, waterFoundations = piece?.waterFoundations.orEmpty(),
                reservedRoute = paidApproach?.route ?: reservedRoute,
                preferredRoads = access.routes.values.flatten().mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) },
                protectedRoadColumns = protectedRoadColumns, paidGateFloors = piece?.paidGateFloors.orEmpty())
                    .copy(requiredGrass = piece?.requiredGrass.orEmpty()) }
            catch (failure: RuntimeException) { retryApproach(failure); return }
            phase = "earthworks"
            await(world.captureTerraform(nativeCity, draft, executor, previous), { failure ->
                if ((unwrap(failure) as? CivsRejection)?.reason == "autonomy-terrain") nativeTerrainRejected = true
                retryApproach(failure)
            }) { terrain ->
                phase = "route"
                await(world.autonomousRoute(nativeCity, draft.building, terrain.route.first(), executor, terrain, previous), ::retryApproach) {
                    phase = "blueprint"
                    val prepared = when {
                        pinned != null -> CompletableFuture.completedFuture(pinned)
                        piece != null -> {
                            val bytes = CivsInfrastructure.schematic(piece, dataVersion)
                            temporaryCapture = true
                            writeCapture(buildingId, bytes).thenCompose {
                                blueprints.prepareInfrastructure(piece, city.worldId, bytes, buildingId)
                            }
                        }
                        fortificationTemplate != null -> blueprints.prepareFortification(fortificationTemplate, buildingId, city.worldId,
                            draft.building.anchor, draft.building.rotation)
                        resolvedCatalogEntry != null -> blueprints.prepareEntry(requireNotNull(resolvedCatalogEntry), buildingId, city.worldId,
                            draft.building.anchor, 2, draft.building.rotation)
                        else -> blueprints.prepare(city.style, type, level, buildingId, city.worldId,
                            draft.building.anchor, 2, draft.building.rotation)
                    }
                    await(prepared) { blueprint ->
                        val oldSlot = initialPlan?.plots?.singleOrNull { it.building.id == buildingId }?.building
                        if (!civsContainsBox(draft.building.bounds, blueprint.building.bounds) ||
                            blueprint.building.entrance != draft.building.entrance || blueprint.building.control != draft.building.control) {
                            fail("autonomy-catalog-changed")
                        } else if (preferredTerrace && (oldSlot == null || !sameHorizontalSlot(blueprint.building, oldSlot))) {
                            fail("autonomy-catalog-changed")
                        } else if (captureVolume) {
                            phase = "capture"
                            await(world.captureAutonomousSite(city.id, city.owner, city.worldId, blueprint.building,
                                blueprint.cells, terrain), ::retry) {
                                if (remainingPrepared > 0) {
                                    // A prior full forecast declined this ordinal; all native proofs still ran again.
                                    remainingPrepared--
                                    attempt()
                                } else result.complete(CivAutonomousDesign(blueprint, terrain))
                            }
                        } else {
                            if (paidApproach != null && paidApproach.route != terrain.route) throw CivsRejection("invalid-plan")
                            result.complete(CivAutonomousDesign(blueprint, terrain, paidApproach = paidApproach))
                        }
                    }
                }
            }
        }

        private fun preferredDraft(option: CivTerraformOption): CivTerraformDraft {
            val intent = requireNotNull(initialPlan)
            val otherPlots = intent.plots.filter { it.building.id != buildingId && city.building(it.building.id) == null }
            val virtual = city.copy(buildings = city.buildings + otherPlots.map {
                it.building.copy(bounds = it.envelope, phase = BuildingPhase.SITE)
            })
            val hinted = access.copy(routes = (intent.routes - buildingId.toString()).mapKeys { UUID.fromString(it.key) } + access.routes,
                reservedFortificationPorts = intent.plots.flatMap { it.fortification?.let { metadata ->
                    CivsSettlementPlans.portReservations(metadata.proof) }.orEmpty() })
            val roads = hinted.routes.values.flatten().mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }
            val planned = CivsAutonomousTerraform.draft(virtual, option, surface, hinted, intent.walls, preferredRoads = roads)
            // Only this second draft reaches native capture: unpaid intent never claims an already-built road.
            val standing = hinted.standing(virtual, buildingId)
            val actual = CivsAutonomousTerraform.draft(city, option, surface, access,
                intent.walls + otherPlots.map { it.envelope }, reservedRoute = planned.route, preferredRoads = roads,
                reservedShoulders = standing.keys, protectedRoadColumns = protectedRoadColumns)
            if (actual.columns.any { column -> standing[civsTerrainKey(column.x, column.z)]?.let {
                    column.groundY != it.y - 1
                } == true }) throw CivsRejection("autonomy-no-path", mapOf("stage" to "retained-grade"))
            return actual
        }

        private fun fail(reason: String) {
            result.completeExceptionally(CivsRejection(reason, rejections.mapValues { it.value.toString() } +
                mapOf("attempted" to attempted.toString(), "options" to options.size.toString()) +
                if (nativeTerrainRejected) mapOf("native-terrain" to "true") else emptyMap()))
        }

        private fun retry(failure: Throwable) {
            val cause = unwrap(failure)
            if (cause !is CivsRejection || cause.reason !in CANDIDATE_REJECTIONS) {
                result.completeExceptionally(cause)
                return
            }
            rejections.merge(cause.reason, 1, Int::plus)
            if (!routeRefreshed && pinned != null && previous?.phase == BuildingPhase.SITE && pinnedRoute != null &&
                cause.reason == "autonomy-no-path") {
                // A SITE owns its geometry, not an obsolete approach. Retry this same fixed
                // option once through the bounded current route and native capture guards.
                routeRefreshed = true
                next--
            }
            attempt()
        }

        private fun <T> await(future: CompletableFuture<T>, failed: (Throwable) -> Unit = {
            result.completeExceptionally(unwrap(it)); Unit
        }, completed: (T) -> Unit) {
            pending = future
            if (result.isDone) { future.cancel(false); return }
            future.whenComplete { value, failure ->
                try {
                    executor.execute {
                        if (!result.isDone) try {
                            if (failure != null) failed(failure) else completed(value)
                        } catch (error: Throwable) { result.completeExceptionally(error) }
                    }
                } catch (error: RuntimeException) { result.completeExceptionally(error) }
            }
        }
    }

    companion object {
        /** A paid road can support a read-only step even where upkeep is barred from changing its grade. */
        internal fun wallRefitApproach(city: Settlement, source: CivPlacementPlan,
            plans: List<CivPlacementPlan>): CivWallRefitApproach? {
            val route = source.terrain?.route.orEmpty()
            if (route.size < 3 || source.building.id.toString() in city.roadRouteOverrides.orEmpty()) return null
            val candidates = route.subList(1, route.lastIndex).mapTo(hashSetOf()) { it.copy(y = it.y - 2) }
            val retained = plans.filter { plan ->
                val active = city.building(plan.building.id)
                active?.phase == BuildingPhase.ACTIVE && plan.id == CivsWarehouseRefit.retainedPlanId(active) &&
                    plan.capturedSha256 != null && !plan.adminPlacement
            }
            val access = CivAutonomousAccess.from(city, retained)
            val civic = access.routes.asSequence().filter { (id, _) -> city.building(id)?.type?.isInfrastructure == false }
                .flatMap { (_, paid) -> CivsAutonomousTerraform.roadProfile(paid).values.asSequence() }
            val authored = access.infrastructure.asSequence().filter { it.type == BuildingType.ROAD }.flatMap { road ->
                val surfaces = road.blocks.filter { CivsRoadMaintenance.surface(it.after) }.mapTo(hashSetOf()) { it.position }
                road.transit.asSequence().map { it.copy(y = it.y - 1) }.filter { it in surfaces }
            }
            val columns = candidates.mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }
            val floors = (civic + authored).filter { civsTerrainKey(it.x, it.z) in columns }
                .groupBy { civsTerrainKey(it.x, it.z) }.values.mapNotNullTo(hashSetOf()) { claims ->
                    claims.distinct().singleOrNull()?.takeIf { it in candidates }
                }
            return if (floors.isEmpty()) null else CivWallRefitApproach(city, route.toList(), floors)
        }

        const val MAXIMUM_SURFACE_COLUMNS = CivsAutonomousTerraform.MAXIMUM_SURFACE_COLUMNS
        const val MAXIMUM_OPTIONS = 64
        private val CANDIDATE_REJECTIONS = setOf("autonomy-terrain", "autonomy-slope", "autonomy-no-path", "autonomy-foundation",
            "autonomy-search-budget", "building-overlap", "outside-city", "outside-world", "outside-border",
            "lands-denied", "protected-terrain", "chunks-unloaded", "city-overlap", "world-changed")
        private fun unwrap(failure: Throwable): Throwable =
            if (failure is CompletionException && failure.cause != null) unwrap(failure.cause!!) else failure
    }
}

internal data class CivsAutonomousCandidate(val building: CivBuilding, val streetFrontage: Boolean = false,
    val spatialPriority: Int = 0)

/** Authored entrances face the centre; footprints grow outward around an open central approach. */
internal object CivsAutonomousGeometry {
    fun candidates(city: Settlement, current: CivBuilding, future: CivBuilding,
        access: CivAutonomousAccess = CivAutonomousAccess()): List<CivsAutonomousCandidate> {
        val bounds = union(withField(current.bounds, current), withField(future.bounds, future))
        val occupied = city.buildings.map { futureBounds(city, it) } + listOfNotNull(city.construction?.plannedBuilding?.bounds)
        val entries = city.buildings.flatMap(::approach)
        val fields = city.buildings.flatMap { CivsWorksiteLayouts.field(it) + CivsWorksiteLayouts.irrigation(it) }
        val roads = access.standing(city, current.id).values
        val result = mutableListOf<CivsAutonomousCandidate>()
        // ponytail: 64 compact candidates; add terrain-aware infill only if this finite profile cannot fit.
        for (radius in listOf(18, 32, 46, 60)) for (sector in 0 until 16) {
            val angle = sector * Math.PI / 8
            val dx = (sin(angle) * radius).roundToInt()
            val dz = (cos(angle) * radius).roundToInt()
            val turns = when { abs(dx) > abs(dz) -> if (dx > 0) 1 else 3; dz > 0 -> 2; else -> 0 }
            val entry = rotate(current.entrance, turns)
            val anchor = CivPosition(city.center.x + dx - entry.x, city.center.y, city.center.z + dz - entry.z)
            fun point(local: CivPosition): CivPosition = rotate(local, turns).let {
                CivPosition(Math.addExact(anchor.x, it.x), Math.addExact(anchor.y, it.y), Math.addExact(anchor.z, it.z))
            }
            val corners = listOf(point(bounds.min), point(bounds.max), point(CivPosition(bounds.min.x, bounds.min.y, bounds.max.z)),
                point(CivPosition(bounds.max.x, bounds.max.y, bounds.min.z)))
            val box = CivBox(CivPosition(corners.minOf { it.x }, corners.minOf { it.y }, corners.minOf { it.z }),
                CivPosition(corners.maxOf { it.x }, corners.maxOf { it.y }, corners.maxOf { it.z }))
            val building = current.copy(anchor = anchor, rotation = turns * 90, bounds = box,
                entrance = point(current.entrance), control = current.control?.let(::point))
            if (!city.contains(box.min.x, box.min.z) || !city.contains(box.max.x, box.max.z) ||
                approach(building).any { !city.contains(it.x, it.z) } ||
                occupied.any { overlaps(box, it, 2) } || (entries + fields + roads).any { horizontalContains(box, it) } ||
                approach(building).any { p -> occupied.any { horizontalContains(it, p) } } ||
                !CivsRules.workRouteWithinReach(city, building.entrance)) continue
            result += CivsAutonomousCandidate(building)
        }
        return result
    }

    fun atHeight(building: CivBuilding, ground: Int): CivBuilding {
        val delta = Math.subtractExact(ground, building.anchor.y)
        fun point(p: CivPosition) = p.copy(y = Math.addExact(p.y, delta))
        return building.copy(anchor = point(building.anchor), entrance = point(building.entrance),
            control = building.control?.let(::point), bounds = CivBox(point(building.bounds.min), point(building.bounds.max)))
    }

    fun approach(building: CivBuilding): List<CivPosition> {
        if (building.type.isInfrastructure) return listOf(building.entrance)
        val front = rotate(CivPosition(0, 0, 1), building.rotation / 90)
        return (0..3).flatMap { distance -> (-1..1).map { side ->
            CivPosition(building.entrance.x + front.x * distance + front.z * side, building.entrance.y,
                building.entrance.z + front.z * distance - front.x * side)
        } }
    }

    fun futureBounds(city: Settlement, building: CivBuilding): CivBox {
        if (building.type == BuildingType.PLAN_BOARD) return CivsPlanBoardBlueprint.futureBounds(building)
        if (building.type.isInfrastructure) return building.bounds
        if (building.schematicId.startsWith("civs/v2/") || building.type.maxLevel == 1) return withField(building.bounds, building)
        val style = CivsBlueprints.styleOf(building) ?: city.style
        if (style !in CivsBlueprints.STYLES) return withField(building.bounds, building)
        val target = building.copy(schematicId = CivsBlueprints.schematicId(style, building.type, building.type.maxLevel))
        val bounds = CivsWorksiteLayouts.site(target)?.getAsJsonObject("schematicBounds") ?: return withField(building.bounds, building)
        val projected = CivsWorksiteLayouts.worldBounds(target, CivsWorksiteLayouts.vector(bounds["min"]),
            CivsWorksiteLayouts.vector(bounds["max"]))
        return withField(union(building.bounds, projected), target)
    }

    fun horizontalContains(box: CivBox, point: CivPosition): Boolean = point.x in box.min.x..box.max.x && point.z in box.min.z..box.max.z
    fun overlaps(a: CivBox, b: CivBox, margin: Int = 0): Boolean =
        a.min.x.toLong() - margin <= b.max.x && a.max.x.toLong() + margin >= b.min.x &&
            a.min.z.toLong() - margin <= b.max.z && a.max.z.toLong() + margin >= b.min.z

    private fun rotate(p: CivPosition, turns: Int): CivPosition = when (Math.floorMod(turns, 4)) {
        0 -> p; 1 -> CivPosition(-p.z, p.y, p.x); 2 -> CivPosition(-p.x, p.y, -p.z); else -> CivPosition(p.z, p.y, -p.x)
    }
    internal fun union(a: CivBox, b: CivBox) = CivBox(
        CivPosition(minOf(a.min.x, b.min.x), minOf(a.min.y, b.min.y), minOf(a.min.z, b.min.z)),
        CivPosition(maxOf(a.max.x, b.max.x), maxOf(a.max.y, b.max.y), maxOf(a.max.z, b.max.z)))
    private fun withField(bounds: CivBox, building: CivBuilding): CivBox =
        CivsWorksiteLayouts.fieldBounds(building)?.let { union(bounds, it) } ?: bounds
}
