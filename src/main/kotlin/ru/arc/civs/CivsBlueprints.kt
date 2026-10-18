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
    data class Alignment(val anchor: CivPosition, val quarterTurns: Int)

    fun entrance(snapshot: ArcSchematicSnapshot): Entry {
        val origin = snapshot.origin
        // The support, feet and head column stays outside every level's paste.
        // CivsWorld validates the unchanged terrain there before committing.
        if (origin.x !in snapshot.minimum.x..snapshot.maximum.x ||
            origin.y.toLong() < snapshot.minimum.y || origin.y.toLong() + 2 > snapshot.maximum.y ||
            origin.z.toLong() != snapshot.maximum.z.toLong() + 1) throw CivsRejection("entrance-missing")
        return Entry(ArcSchematicPoint(origin.x, origin.y + 1, origin.z), 2)
    }

    fun alignment(snapshot: ArcSchematicSnapshot, worldEntrance: CivPosition, facing: Int, exactRotation: Int?): Alignment {
        require(facing in 0..3)
        require(exactRotation == null || exactRotation in listOf(0, 90, 180, 270))
        val entry = entrance(snapshot)
        val turns = exactRotation?.div(90) ?: Math.floorMod(facing - entry.facing, 4)
        val relative = ArcSchematicPoint(entry.position.x - snapshot.origin.x,
            entry.position.y - snapshot.origin.y, entry.position.z - snapshot.origin.z)
        val transformed = rotate(relative, turns)
        return Alignment(CivPosition(worldEntrance.x - transformed.x, worldEntrance.y - transformed.y, worldEntrance.z - transformed.z), turns)
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
        entrance: CivPosition, facing: Int, exactRotation: Int? = null,
    ): CompletableFuture<CivBlueprint> {
        val id = schematicId(style, type, level)
        return source.load(id, ArcSchematicContentPolicy.IGNORE_ENTITIES_AND_BIOMES).thenComposeAsync({ original ->
            requireVolume(original)
            val (anchor, turns) = CivsBlueprintGeometry.alignment(original, entrance, facing, exactRotation)
            source.captureSource(id, original.fileSha256).thenComposeAsync({ bytes ->
                source.transform(original, ArcSchematicTransform(turns, false)).thenApplyAsync({ transformed ->
                    val bounds = CivBox(
                        CivPosition(anchor.x + transformed.minimum.x, anchor.y + transformed.minimum.y, anchor.z + transformed.minimum.z),
                        CivPosition(anchor.x + transformed.maximum.x, anchor.y + transformed.maximum.y, anchor.z + transformed.maximum.z),
                    )
                    val building = CivBuilding(buildingId, type, level, anchor, turns * 90, id, bounds, entrance, BuildingPhase.CONSTRUCTING, control = anchor)
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

        internal fun verifyCapturedPlan(plan: CivPlacementPlan, snapshot: ArcSchematicSnapshot) {
            val cells = worldCells(snapshot, plan.building.anchor).filterNot { it.isBarrier }
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
