package ru.arc.civs

import ru.arc.paper.api.*
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

data class CivBlueprint(
    val building: CivBuilding,
    val worldId: UUID,
    val snapshot: ArcSchematicSnapshot,
    val cells: List<ArcSchematicBlock>,
    val surface: List<ArcSchematicBlock>,
    val sourceBytes: ByteArray,
    val materials: Map<String, Long> = emptyMap(),
)

/** Curated families share one ground pivot, one south-facing front and identical level bounds. */
internal object CivsBlueprintGeometry {
    data class Entry(val position: ArcSchematicPoint, val facing: Int)
    data class Alignment(val anchor: CivPosition, val quarterTurns: Int, val entrance: CivPosition, val control: CivPosition)

    fun controlMarker(snapshot: ArcSchematicSnapshot): ArcSchematicPoint {
        var marker: ArcSchematicPoint? = null
        snapshot.blocks().forEach { cell ->
            if (cell.blockData.substringBefore('[') == "minecraft:structure_void") {
                if (marker != null) throw CivsRejection("control-marker-ambiguous")
                marker = cell.position
            }
        }
        return marker ?: throw CivsRejection("control-marker-missing")
    }

    fun entrance(snapshot: ArcSchematicSnapshot): Entry {
        val origin = snapshot.origin
        // The approach stays outside every level's paste and supplies a stable ground pivot.
        // Existing terrain there does not determine the schematic's placement.
        if (origin.x !in snapshot.minimum.x..snapshot.maximum.x ||
            origin.y.toLong() < snapshot.minimum.y || origin.y.toLong() + 2 > snapshot.maximum.y ||
            origin.z.toLong() != snapshot.maximum.z.toLong() + 1) throw CivsRejection("entrance-missing")
        return Entry(ArcSchematicPoint(origin.x, origin.y + 1, origin.z), 2)
    }

    fun alignment(snapshot: ArcSchematicSnapshot, placement: CivPosition, facing: Int, exactRotation: Int?,
        fixedEntrance: CivPosition? = null): Alignment {
        require(facing in 0..3)
        require(exactRotation == null || exactRotation in listOf(0, 90, 180, 270))
        val entry = entrance(snapshot)
        val marker = controlMarker(snapshot)
        val turns = exactRotation?.div(90) ?: Math.floorMod(facing - entry.facing, 4)
        val entryOffset = rotate(ArcSchematicPoint(entry.position.x - snapshot.origin.x,
            entry.position.y - snapshot.origin.y, entry.position.z - snapshot.origin.z), turns)
        val markerOffset = rotate(ArcSchematicPoint(marker.x - snapshot.origin.x,
            marker.y - snapshot.origin.y, marker.z - snapshot.origin.z), turns)
        // The selected support block is the ground pivot; marker height must not lift the whole building.
        val anchor = fixedEntrance?.let { CivPosition(it.x - entryOffset.x, it.y - entryOffset.y, it.z - entryOffset.z) }
            ?: placement
        return Alignment(anchor, turns,
            CivPosition(anchor.x + entryOffset.x, anchor.y + entryOffset.y, anchor.z + entryOffset.z),
            CivPosition(anchor.x + markerOffset.x, anchor.y + markerOffset.y, anchor.z + markerOffset.z))
    }

    fun rotate(point: ArcSchematicPoint, turns: Int): ArcSchematicPoint = when (Math.floorMod(turns, 4)) {
        0 -> point
        1 -> ArcSchematicPoint(-point.z, point.y, point.x)
        2 -> ArcSchematicPoint(-point.x, point.y, -point.z)
        else -> ArcSchematicPoint(point.z, point.y, -point.x)
    }
}

/** Owns library leases only. Decoded snapshots belong to bounded, expiring previews/builds. */
class CivsBlueprints(
    service: ArcSchematicService,
    sourceRoot: Path,
    capturedRoot: Path,
    private val executor: Executor,
    private val maximumVolume: Int,
) : AutoCloseable {
    private val source = service.open("arccivs-library", sourceRoot)
    private val captured = service.open("arccivs-construction", capturedRoot)

    fun prepare(
        style: String, type: BuildingType, level: Int, buildingId: UUID, worldId: UUID,
        placement: CivPosition, facing: Int, exactRotation: Int? = null,
        fixedEntrance: CivPosition? = null,
    ): CompletableFuture<CivBlueprint> {
        val id = schematicId(style, type, level)
        return source.load(id, ArcSchematicContentPolicy.IGNORE_ENTITIES_AND_BIOMES).thenComposeAsync({ original ->
            requireVolume(original)
            val (anchor, turns, entrance, worldControl) = CivsBlueprintGeometry.alignment(original, placement, facing, exactRotation, fixedEntrance)
            source.captureSource(id, original.fileSha256).thenComposeAsync({ bytes ->
                source.transform(original, ArcSchematicTransform(turns, false)).thenApplyAsync({ transformed ->
                    val bounds = CivBox(
                        CivPosition(anchor.x + transformed.minimum.x, anchor.y + transformed.minimum.y, anchor.z + transformed.minimum.z),
                        CivPosition(anchor.x + transformed.maximum.x, anchor.y + transformed.maximum.y, anchor.z + transformed.maximum.z),
                    )
                    val building = CivBuilding(buildingId, type, level, anchor, turns * 90, id, bounds, entrance, BuildingPhase.CONSTRUCTING, control = worldControl)
                    val cells = worldCells(transformed, anchor)
                    rejectHazards(cells)
                    val surface = exposedCells(cells)
                    if (surface.size > 6_000) throw CivsRejection("preview-too-large")
                    val estimate = CivsMaterials.required(CivPlacementPlan(buildingId, buildingId, worldId, building,
                        cells.map { CivPlanBlock(CivPosition(it.position.x, it.position.y, it.position.z), "minecraft:air", it.blockData) }))
                    CivBlueprint(building, worldId, transformed, cells, surface, bytes, estimate)
                }, executor)
            }, executor)
        }, executor)
    }

    /** A committed construction replays captured bytes, never a newer mutable library file. */
    fun recover(plan: CivPlacementPlan): CompletableFuture<ArcSchematicSnapshot> =
        captured.loadVerified(plan.id.toString(), requireNotNull(plan.capturedSha256) { "Construction capture digest is missing" }, ArcSchematicContentPolicy.IGNORE_ENTITIES_AND_BIOMES)
            .thenCompose { captured.transform(it, ArcSchematicTransform(plan.building.rotation / 90, false)) }
            .thenApplyAsync({ snapshot ->
                verifyCapturedPlan(plan, snapshot)
                snapshot
            }, executor)

    fun paste(snapshot: ArcSchematicSnapshot, worldId: UUID, anchor: CivPosition): ArcSchematicOperation =
        captured.paste(snapshot, ArcSchematicPasteTarget(worldId, ArcSchematicPoint(anchor.x, anchor.y, anchor.z)), ArcSchematicPasteOptions(false, true, false))

    internal fun crop(snapshot: ArcSchematicSnapshot, building: CivBuilding, batch: CivBuildBatch): CompletableFuture<ArcSchematicSnapshot> =
        captured.crop(snapshot,
            ArcSchematicPoint(batch.minimum.x - building.anchor.x, batch.minimum.y - building.anchor.y, batch.minimum.z - building.anchor.z),
            ArcSchematicPoint(batch.maximum.x - building.anchor.x, batch.maximum.y - building.anchor.y, batch.maximum.z - building.anchor.z))

    /** Preserve the authored barrel cell without modifying captured bytes or discarding air around it. */
    internal fun pasteParts(snapshot: ArcSchematicSnapshot, building: CivBuilding, batch: CivBuildBatch? = null): CompletableFuture<List<ArcSchematicSnapshot>> = try {
        val minimum = batch?.minimum?.let { CivPosition(it.x - building.anchor.x, it.y - building.anchor.y, it.z - building.anchor.z) }
            ?: CivPosition(snapshot.minimum.x, snapshot.minimum.y, snapshot.minimum.z)
        val maximum = batch?.maximum?.let { CivPosition(it.x - building.anchor.x, it.y - building.anchor.y, it.z - building.anchor.z) }
            ?: CivPosition(snapshot.maximum.x, snapshot.maximum.y, snapshot.maximum.z)
        val bounds = CivBox(minimum, maximum)
        val sourceBounds = CivBox(CivPosition(snapshot.minimum.x, snapshot.minimum.y, snapshot.minimum.z),
            CivPosition(snapshot.maximum.x, snapshot.maximum.y, snapshot.maximum.z))
        if (civsBoxVolume(bounds) == 0L || !civsContainsBox(sourceBounds, bounds)) throw CivsRejection("invalid-plan")
        val marker = building.control?.let { CivPosition(it.x - building.anchor.x, it.y - building.anchor.y, it.z - building.anchor.z) }
        val preserved = marker?.takeIf { sourceBounds.contains(it.x, it.y, it.z) &&
            snapshot.blockAt(it.x, it.y, it.z).blockData.substringBefore('[') == "minecraft:structure_void" }
        if (hasAuthoredControl(building) && preserved == null) throw CivsRejection("invalid-plan")
        val boxes = if (preserved != null && bounds.contains(preserved.x, preserved.y, preserved.z)) withoutPoint(bounds, preserved) else listOf(bounds)
        if (boxes == listOf(sourceBounds)) CompletableFuture.completedFuture(listOf(snapshot))
        else boxes.fold(CompletableFuture.completedFuture(emptyList<ArcSchematicSnapshot>())) { prepared, box ->
            val part = captured.crop(snapshot, ArcSchematicPoint(box.min.x, box.min.y, box.min.z), ArcSchematicPoint(box.max.x, box.max.y, box.max.z))
            prepared.thenCombine(part) { parts, next -> parts + next }
        }
    } catch (failure: RuntimeException) { CompletableFuture.failedFuture(failure) }

    fun repair(worldId: UUID, blocks: List<ArcSchematicBlock>): ArcSchematicOperation = captured.repairConnections(worldId, blocks)

    override fun close() { source.close(); captured.close() }

    private fun requireVolume(snapshot: ArcSchematicSnapshot) {
        if (snapshot.volume !in 1..maximumVolume.toLong()) throw CivsRejection("building-too-large")
    }

    companion object {
        val STYLES = listOf("nordic", "medievaloak", "caledonia")
        fun schematicId(style: String, type: BuildingType, level: Int): String {
            require(style in STYLES && level in 1..3)
            val name = when (type) {
                BuildingType.TOWN_HALL -> "fundamentals/townhall"
                BuildingType.BUILDER_HUT -> "fundamentals/builder"
                BuildingType.HOUSE -> "fundamentals/residence"
                BuildingType.FORESTER -> "fundamentals/lumberjack"
                BuildingType.QUARRY -> "fundamentals/miner"
                BuildingType.FARM -> "agriculture/horticulture/farmer"
                BuildingType.WAREHOUSE -> "craftsmanship/storage/warehouse"
            }
            return "civs/minecolonies/$style/$name$level"
        }

        fun worldCells(snapshot: ArcSchematicSnapshot, anchor: CivPosition): List<ArcSchematicBlock> = snapshot.blocks().map { cell ->
            cell.copy(position = ArcSchematicPoint(cell.position.x + anchor.x, cell.position.y + anchor.y, cell.position.z + anchor.z))
        }

        /** Curated origins are outside the box; an authored control is inside, distinct from the legacy origin control. */
        internal fun hasAuthoredControl(building: CivBuilding): Boolean = building.control != null && building.control != building.anchor

        internal fun verifyCapturedPlan(plan: CivPlacementPlan, snapshot: ArcSchematicSnapshot) {
            if (hasAuthoredControl(plan.building)) {
                val marker = CivsBlueprintGeometry.controlMarker(snapshot)
                if (CivPosition(marker.x + plan.building.anchor.x, marker.y + plan.building.anchor.y,
                        marker.z + plan.building.anchor.z) != plan.building.control) throw CivsRejection("invalid-plan")
            }
            val cells = worldCells(snapshot, plan.building.anchor).filterNot { it.isBarrier ||
                CivPosition(it.position.x, it.position.y, it.position.z) == plan.building.control &&
                it.blockData.substringBefore('[') == "minecraft:structure_void" }
            val expected = plan.blocks.associateBy { it.position }
            if (cells.size != plan.blocks.size || expected.size != cells.size || cells.any {
                expected[CivPosition(it.position.x, it.position.y, it.position.z)]?.after != it.blockData
            }) throw CivsRejection("invalid-plan")
            val anchor = plan.building.anchor
            val actualBounds = CivBox(
                CivPosition(anchor.x + snapshot.minimum.x, anchor.y + snapshot.minimum.y, anchor.z + snapshot.minimum.z),
                CivPosition(anchor.x + snapshot.maximum.x, anchor.y + snapshot.maximum.y, anchor.z + snapshot.maximum.z),
            )
            if (actualBounds != plan.building.bounds) throw CivsRejection("invalid-plan")
            if (hasAuthoredControl(plan.building) && plan.building.control?.let { actualBounds.contains(it.x, it.y, it.z) } != true)
                throw CivsRejection("invalid-plan")
        }

        /** Six disjoint slabs cover the entire inclusive box except one cell; a single layer needs at most four. */
        private fun withoutPoint(box: CivBox, point: CivPosition): List<CivBox> = buildList {
            if (box.min.x < point.x) add(CivBox(box.min, box.max.copy(x = point.x - 1)))
            if (point.x < box.max.x) add(CivBox(box.min.copy(x = point.x + 1), box.max))
            if (box.min.y < point.y) add(CivBox(box.min.copy(x = point.x), box.max.copy(x = point.x, y = point.y - 1)))
            if (point.y < box.max.y) add(CivBox(box.min.copy(x = point.x, y = point.y + 1), box.max.copy(x = point.x)))
            if (box.min.z < point.z) add(CivBox(CivPosition(point.x, point.y, box.min.z), CivPosition(point.x, point.y, point.z - 1)))
            if (point.z < box.max.z) add(CivBox(CivPosition(point.x, point.y, point.z + 1), CivPosition(point.x, point.y, box.max.z)))
        }

        private fun exposedCells(cells: List<ArcSchematicBlock>): List<ArcSchematicBlock> {
            val solid = cells.filter { !it.isAir && !it.isBarrier && !it.blockData.startsWith("minecraft:structure_void") }
            val coordinates = solid.mapTo(HashSet()) { it.position }
            val offsets = listOf(ArcSchematicPoint(1, 0, 0), ArcSchematicPoint(-1, 0, 0), ArcSchematicPoint(0, 1, 0), ArcSchematicPoint(0, -1, 0), ArcSchematicPoint(0, 0, 1), ArcSchematicPoint(0, 0, -1))
            return solid.filter { cell -> offsets.any { delta ->
                ArcSchematicPoint(cell.position.x + delta.x, cell.position.y + delta.y, cell.position.z + delta.z) !in coordinates
            } }
        }

        private fun rejectHazards(cells: List<ArcSchematicBlock>) {
            val denied = setOf("minecraft:tnt", "minecraft:command_block", "minecraft:repeating_command_block", "minecraft:chain_command_block", "minecraft:end_portal", "minecraft:end_gateway", "minecraft:nether_portal", "minecraft:bedrock", "minecraft:spawner", "minecraft:trial_spawner", "minecraft:vault")
            if (cells.any { it.blockData.substringBefore('[') in denied }) throw CivsRejection("unsafe-schematic")
        }
    }
}
