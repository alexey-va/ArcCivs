package ru.arc.civs

import org.bukkit.FluidCollisionMode
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import java.util.UUID
import java.util.concurrent.CompletableFuture

internal class CivsPlacementSession(
    private val authority: CivsService,
    private val records: CivsCityRecords,
    private val buildingRequestsOwner: () -> CivsBuildingRequests,
    private val diagnosticsOwner: () -> CivsCityDiagnostics,
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    private val settings: CivsSettings,
    private val blueprints: CivsBlueprints,
    private val renderer: CivsPreviewRenderer,
    private val boundary: CivsBoundaryRenderer,
) {
    private val buildingRequests get() = buildingRequestsOwner()
    private val diagnostics get() = diagnosticsOwner()
    private val previews = mutableMapOf<UUID, CivsPlacementPreview>()
    private val selections = mutableMapOf<UUID, Selection>()
    private val requests = mutableMapOf<UUID, UUID>()
    private val previewLoads = mutableMapOf<UUID, Boolean>() // Whether the preparation has a visible placement preview.

    fun preparing(playerId: UUID): Boolean = playerId in previewLoads
    fun draft(playerId: UUID): CivsPlacementPreview? = previews[playerId]
    val previewCount get() = previews.size
    val selectionCount get() = selections.size
    val loadingCount get() = previewLoads.size
    fun cancelAll(reason: String) = (previews.keys + selections.keys + requests.keys).toList().forEach { cancelPreview(it, reason) }
    fun expire() {
        selections.filterValues { it.expires <= authority.now() }.keys.toList().forEach { cancelSelection(it, "expired") }
        previews.filterValues { it.expires <= authority.now() }.keys.toList().forEach { cancelPreview(it, "expired") }
    }
    fun clearPreviewsAndSelections() { previews.clear(); selections.keys.toList().forEach { cancelSelection(it, "shutdown") } }
    fun clearLoads() { requests.clear(); previewLoads.clear() }

    fun preview(player: Player): CivsPreviewView? = previews[player.uniqueId]?.takeIf { !it.upgrading && it.expires > authority.now() }?.let {
        val b = it.blueprint.building
        CivsPreviewView(b.type, b.level, it.style, it.cityName, b.anchor, b.rotation,
            b.bounds.max.x - b.bounds.min.x + 1, b.bounds.max.y - b.bounds.min.y + 1,
            b.bounds.max.z - b.bounds.min.z + 1, CivsRules.cost(b.type, b.level), it.blueprint.snapshot.nonBarrierCount,
            it.id, it.blueprint.worldId, b.entrance, b.bounds, !it.upgrading, player.uniqueId in previewLoads, it.blueprint.materials,
            b.control ?: b.anchor, it.autonomous)
    }

    fun selection(player: Player): CivsSelectionView? {
        val selection = selections[player.uniqueId] ?: return null
        if (selection.expires <= authority.now()) {
            cancelSelection(player.uniqueId, "expired")
            return null
        }
        return CivsSelectionView(selection.id, selection.worldId, selection.type, selection.expires,
            player.uniqueId in previewLoads)
    }

    fun beginFoundingSelection(player: Player, name: String, style: String): CompletableFuture<Unit> = authority.action("begin_founding_selection", player, "city_name" to name, "style" to style) {
        authority.requireReady(player)
        buildingRequests.requireFounder(player)
        rejectIf(name.trim().isBlank() || name.trim().length > 48 || name.any { it.isISOControl() }, "invalid-name")
        beginSelection(player, null, name.trim(), style, BuildingType.TOWN_HALL)
    }

    fun beginAutonomousSelection(player: Player, name: String, style: String): CompletableFuture<Unit> =
        authority.action("begin_autonomous_selection", player, "city_name" to name, "style" to style) {
            authority.requireReady(player)
            buildingRequests.requireFounder(player, autonomous = true)
            rejectIf(name.trim().isBlank() || name.trim().length > 48 || name.any { it.isISOControl() }, "invalid-name")
            beginSelection(player, null, name.trim(), style, BuildingType.TOWN_HALL, autonomous = true)
        }

    fun beginBuildingSelection(player: Player, type: BuildingType): CompletableFuture<Unit> =
        beginBuildingSelectionWithStyle(player, type, null)

    fun beginBuildingSelection(player: Player, type: BuildingType, style: String): CompletableFuture<Unit> =
        beginBuildingSelectionWithStyle(player, type, style)

    fun beginBlueprintSelection(player: Player, type: BuildingType, style: String, entryId: String): CompletableFuture<Unit> =
        authority.action("begin_blueprint_selection", player, "type" to type, "style" to style, "entry" to entryId) {
            val city = authority.managed(player)
            rejectIf(type == BuildingType.TOWN_HALL || type.isInfrastructure, "invalid-building")
            rejectIf(authority.blueprintChoices(type, style).none { it.id == entryId }, "blueprint-unavailable")
            CivsResearch.required(type, 1)?.let { rejectIf(!CivsResearch.has(city, it), "research-required") }
            rejectIf(player.world.uid != city.worldId, "outside-city")
            beginSelection(player, city.id, city.name, style, type, entryId = entryId)
        }

    private fun beginBuildingSelectionWithStyle(player: Player, type: BuildingType, style: String?): CompletableFuture<Unit> = authority.action("begin_building_selection", player, "type" to type, "style" to style) {
        val city = authority.managed(player)
        rejectIf(type == BuildingType.TOWN_HALL || type.isInfrastructure, "invalid-building")
        CivsResearch.required(type, 1)?.let { rejectIf(!CivsResearch.has(city, it), "research-required") }
        rejectIf(player.world.uid != city.worldId, "outside-city")
        beginSelection(player, city.id, city.name, style ?: city.style, type)
    }

    fun beginPlanBoardSelection(player: Player, cityId: UUID): CompletableFuture<Unit> =
        authority.action("begin_plan_board_selection", player, "city" to cityId, "type" to BuildingType.PLAN_BOARD) {
            val city = managedPlacement(player, cityId, BuildingType.PLAN_BOARD)
            beginSelection(player, city.id, city.name, city.style, BuildingType.PLAN_BOARD, autonomous = city.autonomy != null)
        }

    private fun beginSelection(player: Player, cityId: UUID?, name: String, style: String, type: BuildingType, autonomous: Boolean = false,
        entryId: String? = null): CompletableFuture<Unit> {
        requirePlacementWorld(player)
        rejectIf(type != BuildingType.PLAN_BOARD && style !in authority.styles(type), "invalid-style")
        requirePreviewSlot(player)
        if (player.uniqueId in previews || player.uniqueId in selections || player.uniqueId in requests)
            cancelPreview(player.uniqueId, "replaced")
        val selection = Selection(UUID.randomUUID(), player.world.uid, cityId, name, style, type,
            authority.now() + settings.previewSeconds * 1_000L, autonomous, entryId)
        selections[player.uniqueId] = selection
        refreshBoundary(player)
        CivsDiagnostics.event("selection_begin", *CivsDiagnostics.actor(player), "selection" to selection.id,
            "city" to cityId, "city_name" to name, "style" to style, "type" to type, "expires_at" to selection.expires)
        return CompletableFuture.completedFuture(Unit)
    }

    fun selectGround(player: Player, selectionId: UUID, target: CivsGroundTarget): CompletableFuture<Unit> =
        authority.action("select_ground", player, "selection" to selectionId, "target_world" to target.worldId, "block" to target.block) {
            authority.requireReady(player)
            val selection = selections[player.uniqueId]?.takeIf { it.id == selectionId && it.expires > authority.now() }
                ?: throw CivsRejection("selection-expired")
            rejectIf(selection.worldId != player.world.uid, "selection-expired")
            rejectIf(player.uniqueId in previewLoads, "city-busy")
            val point = clickedPlacement(player, target)
            if (selection.cityId == null) {
                buildingRequests.requireFounder(player, selection.autonomous)
                buildingRequests.checkSettlementSpace(player.world.uid, point)
            } else {
                val city = managedPlacement(player, selection.cityId, selection.type)
                rejectIf(!city.contains(point.x, point.z), "outside-city")
            }
            CivsDiagnostics.event("selection_selected", *CivsDiagnostics.actor(player), "selection" to selection.id,
                "block" to target.block, "placement" to point, "type" to selection.type)
            prepare(player, selection.cityId, selection.name, selection.style, selection.type, 1, UUID.randomUUID(),
                point, false, selectionId = selection.id, autonomous = selection.autonomous, entryId = selection.entryId)
        }.whenComplete { _, failure ->
            if (failure != null) CivsDiagnostics.event("selection_rejected", "player" to player.uniqueId,
                "selection" to selectionId, "block" to target.block, "reason" to CivsDiagnostics.rejection(failure)?.reason,
                "cause" to CivsDiagnostics.cause(failure)?.javaClass?.simpleName)
        }

    fun cancelSelection(player: Player, reason: String) = cancelSelection(player.uniqueId, reason)

    fun cancelSelection(playerId: UUID, reason: String) {
        val selection = selections.remove(playerId) ?: return
        requests.remove(playerId)
        CivsDiagnostics.event(if (reason == "expired") "selection_expired" else "selection_cancel",
            "player" to playerId, "selection" to selection.id, "reason" to reason,
            "world" to selection.worldId, "type" to selection.type)
    }

    fun rotatePreview(player: Player, degrees: Int): CompletableFuture<Unit> = authority.action("rotate_preview", player, "degrees" to degrees) {
        val old = currentPreview(player)
        rejectIf(old.upgrading, "upgrade-position-fixed")
        require(degrees == -90 || degrees == 90)
        val b = old.blueprint.building
        prepare(player, old.cityId, old.cityName, old.style, b.type, b.level, b.id, b.anchor, false,
            Math.floorMod(b.rotation + degrees, 360), old.movementOrigin, autonomous = old.autonomous,
            entryId = b.schematicId.takeIf { it.startsWith("civs/v2/") })
    }

    fun movePreview(player: Player): CompletableFuture<Unit> = authority.action("move_preview", player) {
        val old = currentPreview(player)
        rejectIf(old.upgrading, "upgrade-position-fixed")
        movePreviewTo(player, old, target(player))
    }

    fun movePreview(player: Player, target: CivsGroundTarget): CompletableFuture<Unit> =
        authority.action("move_preview", player, "target_world" to target.worldId, "block" to target.block) {
            val old = currentPreview(player)
            rejectIf(old.upgrading, "upgrade-position-fixed")
            movePreviewTo(player, old, clickedPlacement(player, target))
        }

    private fun movePreviewTo(player: Player, old: CivsPlacementPreview, placement: CivPosition): CompletableFuture<Unit> {
        val b = old.blueprint.building
        if (old.cityId == null) buildingRequests.checkSettlementSpace(player.world.uid, placement)
        return prepare(player, old.cityId, old.cityName, old.style, b.type, b.level, b.id, placement, false, b.rotation, autonomous = old.autonomous,
            entryId = b.schematicId.takeIf { it.startsWith("civs/v2/") })
    }

    fun nudgePreview(player: Player, direction: CivsPreviewMove): CompletableFuture<Unit> = authority.action("nudge_preview", player, "direction" to direction) {
        val old = currentPreview(player)
        rejectIf(old.upgrading, "upgrade-position-fixed")
        val b = old.blueprint.building
        val anchor = civsMovedEntrance(b.anchor, direction, facing(player))
        rejectIf(listOf(anchor.x.toLong() - old.movementOrigin.x, anchor.y.toLong() - old.movementOrigin.y,
            anchor.z.toLong() - old.movementOrigin.z).any { kotlin.math.abs(it) > 64 }, "placement-offset-limit")
        authority.near(player, old.blueprint.worldId, anchor)
        rejectIf(anchor.y <= player.world.minHeight || anchor.y + 1 >= player.world.maxHeight, "outside-world")
        if (old.cityId == null) buildingRequests.checkSettlementSpace(player.world.uid, anchor)
        prepare(player, old.cityId, old.cityName, old.style, b.type, b.level, b.id, anchor, false, b.rotation, old.movementOrigin, autonomous = old.autonomous,
            entryId = b.schematicId.takeIf { it.startsWith("civs/v2/") })
    }

    fun prepare(
        player: Player, cityId: UUID?, name: String, style: String, type: BuildingType, level: Int,
        buildingId: UUID, placement: CivPosition, upgrading: Boolean, rotation: Int? = null, movementOrigin: CivPosition? = null,
        selectionId: UUID? = null, fixedEntrance: CivPosition? = null, autonomous: Boolean = false,
        entryId: String? = null,
    ): CompletableFuture<Unit> {
        authority.requireReady(player)
        rejectIf(!upgrading && type != BuildingType.PLAN_BOARD && style !in authority.styles(type), "invalid-style")
        requirePreviewSlot(player)
        val worldId = player.world.uid
        if (cityId != null) managedPlacement(player, cityId, type).also {
            rejectIf((it.autonomy != null) != autonomous, "stale-city")
        }
        val request = UUID.randomUUID()
        requests[player.uniqueId] = request
        CivsDiagnostics.event("preview_load", *CivsDiagnostics.actor(player), "request" to request,
            "city" to cityId, "city_name" to name, "style" to style, "type" to type,
            "placement" to placement, "fixed_entrance" to fixedEntrance, "rotation" to rotation)
        previewLoads[player.uniqueId] = !upgrading
        if (!upgrading) refreshBoundary(player)
        // Keep the last usable position visible until the replacement is ready.
        val result = authority.tracked<Unit>()
        val preparedBlueprint = if (upgrading) blueprints.prepareUpgrade(
            requireNotNull(cityId?.let { records.cities[it]?.building(buildingId) }), level, worldId)
        else if (entryId != null) blueprints.prepareEntry(entryId, buildingId, worldId, placement, facing(player), rotation)
        else blueprints.prepare(style, type, level, buildingId, worldId, placement, facing(player), rotation, fixedEntrance)
        preparedBlueprint
            .whenCompleteSync(tasks) { blueprint, failure ->
                previewLoads.remove(player.uniqueId)
                if (requests[player.uniqueId] != request || !player.isOnline || player.world.uid != worldId ||
                    selectionId != null && selections[player.uniqueId]?.let { it.id == selectionId && it.expires > authority.now() } != true) {
                    result.completeExceptionally(CivsRejection("preview-expired"))
                } else if (failure != null) {
                    authority.log("prepare preview", failure)
                    result.completeExceptionally(failure)
                } else runCatching {
                    val source = requireNotNull(blueprint)
                    val city = cityId?.let { managedPlacement(player, it, source.building.type) }
                    if (city != null) rejectIf((city.autonomy != null) != autonomous, "stale-city")
                    val prepared = source.copy(building = source.building.copy(production = city?.building(buildingId)?.production))
                    if (city != null) rejectIf(!city.contains(prepared.building.bounds.min.x, prepared.building.bounds.min.z) ||
                        !city.contains(prepared.building.bounds.max.x, prepared.building.bounds.max.z), "outside-city")
                    if (!upgrading) renderer.show(player, prepared.building, prepared.surface)
                    previews[player.uniqueId] = CivsPlacementPreview(request, cityId, name, style, prepared, upgrading,
                        authority.now() + settings.previewSeconds * 1_000L, movementOrigin ?: prepared.building.anchor, autonomous)
                    if (selectionId != null) selections.remove(player.uniqueId)
                    if (!upgrading) refreshBoundary(player)
                    CivsDiagnostics.event("preview_ready", "player" to player.uniqueId, "request" to request,
                        "selection" to selectionId, "world" to worldId, "bounds" to prepared.building.bounds,
                        "control" to prepared.building.control, "entrance" to prepared.building.entrance,
                        "anchor" to prepared.building.anchor, "blocks" to prepared.snapshot.nonBarrierCount)
                    result.complete(Unit)
                }.onFailure { result.completeExceptionally(it) }
            }
        return result
    }

    fun cancelPreview(player: Player) { cancelPreview(player.uniqueId) }
    fun cancelPreview(playerId: UUID, reason: String = "cancelled") {
        cancelSelection(playerId, reason)
        val request = requests.remove(playerId)
        if (request != null) CivsDiagnostics.event("preview_cancel", "player" to playerId, "request" to request)
        previews.remove(playerId)
        renderer.clear(playerId)
    }

    fun refreshBoundary(player: Player) {
        val preview = previews[player.uniqueId]?.takeUnless { it.upgrading }
        val selection = selections[player.uniqueId]
        val selectedCity = selection?.cityId ?: preview?.cityId
        val type = if (selection?.cityId != null) selection.type else preview?.blueprint?.building?.type
        val location = player.location
        val constructionCity = if (authority.ready() && selectedCity == null)
            civsNearbyConstructionCity(authority.settlements(), player.world.uid, location.x, location.y, location.z) else null
        val city = if (!authority.ready()) null else if (selectedCity != null && type != null)
            runCatching { managedPlacement(player, selectedCity, type) }.getOrNull() else {
            authority.city(player)?.takeIf { it.worldId == player.world.uid &&
                kotlin.math.abs(it.center.x.toDouble() - location.x) <= it.radius + 24.0 &&
                kotlin.math.abs(it.center.z.toDouble() - location.z) <= it.radius + 24.0 }
                ?: constructionCity
        }
        val authorized = selectedCity == null || city != null
        val visiblePreview = preview?.takeIf { authorized }
        boundary.pulse(player, city,
            authorized && (player.uniqueId in selections || preview != null || previewLoads[player.uniqueId] == true),
            visiblePreview?.blueprint?.building?.bounds, visiblePreview?.blueprint?.building?.id,
            city?.buildings?.associate { it.id to diagnostics.buildingStatus(it.id) }.orEmpty(), constructionCity ?: city)
    }

    fun currentPreview(player: Player): CivsPlacementPreview {
        authority.requireReady(player)
        rejectIf(player.uniqueId in previewLoads, "city-busy")
        return (previews[player.uniqueId]?.takeIf { it.expires > authority.now() && it.blueprint.worldId == player.world.uid }
            ?: throw CivsRejection("preview-expired")).also { preview ->
            preview.cityId?.let { managedPlacement(player, it, preview.blueprint.building.type) }
        }
    }
    /** Normal personal authority, with one explicit administrative placement for an existing Auto city. */
    fun managedPlacement(player: Player, cityId: UUID, type: BuildingType): Settlement {
        authority.requireReady(player)
        val selected = records.cities[cityId] ?: throw CivsRejection("no-city")
        val city = if (selected.autonomy != null) {
            rejectIf(type != BuildingType.PLAN_BOARD || !player.hasPermission("arccivs.admin"), "not-authorized")
            selected
        } else authority.managed(player).also { rejectIf(it.id != cityId, "not-authorized") }
        rejectIf(city.worldId != player.world.uid, "outside-city")
        rejectIf(city.id in records.unhealthy, "storage-unavailable")
        rejectIf(city.pendingDeletion != null, "deletion-pending")
        return city
    }
    private fun requirePreviewSlot(player: Player) {
        rejectIf(player.uniqueId in previewLoads, "city-busy")
        val occupied = previews.keys + selections.keys + previewLoads.keys
        rejectIf(occupied.size >= settings.maximumPreviews && player.uniqueId !in occupied, "too-many-previews")
    }
    private fun requirePlacementWorld(player: Player) {
        rejectIf(settings.worlds.isEmpty(), "founding-disabled")
        if (player.world.name !in settings.worlds) throw CivsRejection("wrong-world", mapOf(
            "world" to player.world.name, "worlds" to settings.worlds.sorted().joinToString(),
        ))
    }
    /** Only the legacy command uses the current aim; world interactions pass their actual clicked block. */
    private fun target(player: Player): CivPosition {
        requirePlacementWorld(player)
        val hit = player.rayTraceBlocks(12.0, FluidCollisionMode.NEVER)?.hitBlock ?: throw CivsRejection("look-at-ground")
        return clickedPlacement(player, CivsGroundTarget(hit.world.uid, CivPosition(hit.x, hit.y, hit.z)))
    }
    private fun clickedPlacement(player: Player, target: CivsGroundTarget): CivPosition {
        requirePlacementWorld(player)
        rejectIf(target.worldId != player.world.uid, "selection-expired")
        val point = target.block
        val world = player.world
        rejectIf(point.y < world.minHeight || point.y.toLong() + 2 >= world.maxHeight, "outside-world")
        rejectIf(!world.isChunkLoaded(point.x shr 4, point.z shr 4), "chunk-unloaded")
        val eye = player.eyeLocation
        rejectIf(eye.toVector().distanceSquared(org.bukkit.util.Vector(point.x + 0.5, point.y + 0.5, point.z + 0.5)) > 12.0 * 12.0, "too-far")
        val block = world.getBlockAt(point.x, point.y, point.z)
        return civsGroundSupport(block) ?: throw CivsRejection("look-at-ground")
    }
    private fun facing(player: Player): Int = Math.floorMod(kotlin.math.floor((player.location.yaw + 45.0) / 90.0).toInt(), 4)

    private fun rejectIf(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
}
private data class Selection(
    val id: UUID, val worldId: UUID, val cityId: UUID?, val name: String, val style: String,
    val type: BuildingType, val expires: Long, val autonomous: Boolean = false, val entryId: String? = null,
)

internal data class CivsPlacementPreview(
    val id: UUID, val cityId: UUID?, val cityName: String, val style: String, val blueprint: CivBlueprint,
    val upgrading: Boolean, val expires: Long, val movementOrigin: CivPosition, val autonomous: Boolean = false,
)
