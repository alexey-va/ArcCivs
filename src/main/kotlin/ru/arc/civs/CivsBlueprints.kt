package ru.arc.civs

import ru.arc.paper.api.*
import ru.arc.persistence.AtomicFileStore
import java.nio.file.Path
import java.security.MessageDigest
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
    private val capturedRoot: Path,
    private val executor: Executor,
    private val maximumVolume: Int,
    private val dataVersion: Int = 0,
) : AutoCloseable {
    private val source = service.open("arccivs-library", sourceRoot)
    private val captured = service.open("arccivs-construction", capturedRoot)
    private data class Builtin(val bytes: ByteArray, val sha256: String)
    private val builtins = mutableMapOf<String, CompletableFuture<Builtin>>()
    private var closed = false
    @Volatile private var catalog = CivBlueprintCatalog.EMPTY
    private val catalogLoad = CompletableFuture.supplyAsync({
        CivBlueprintCatalog.load(sourceRoot) to CivsTavernFurniture.load(sourceRoot)
    }, executor).thenApply { (loaded, furniture) ->
        synchronized(this) {
            if (closed) throw CivsRejection("not-ready")
            catalog = loaded
            CivsWorksiteLayouts.installCatalog(loaded.worksiteLayouts, furniture)
        }
        Unit
    }

    /** Boot waits for metadata only; decoded voxel snapshots still belong to Core's bounded cache. */
    fun catalogReady(): CompletableFuture<Unit> = catalogLoad
    fun availableStyles(type: BuildingType? = null): List<String> =
        if (type == BuildingType.PLAN_BOARD) listOf("plan-board")
        else if (catalog.present) catalog.styles(type) else if (type == BuildingType.DECORATION) emptyList() else STYLES

    fun choices(type: BuildingType, style: String): List<CivBlueprintChoice> =
        if (catalog.present) catalog.choices(type, style) else emptyList()
    fun smallDecorations(style: String): List<CivBlueprintChoice> = catalog.smallDecorations(style)
    fun autonomousSource(style: String, type: BuildingType): String? =
        if (type == BuildingType.PLAN_BOARD || !catalog.present) null
        else catalog.autonomous(style, type)?.id ?: throw CivsRejection("blueprint-unavailable")

    fun maximumLevel(building: CivBuilding): Int = catalog.entry(building.schematicId)?.maxLevel ?: building.type.maxLevel

    fun prepareEntry(entryId: String, buildingId: UUID, worldId: UUID, placement: CivPosition, facing: Int,
        exactRotation: Int? = null): CompletableFuture<CivBlueprint> = catalogLoad.thenCompose {
        val entry = catalog.entry(entryId) ?: throw CivsRejection("blueprint-unavailable")
        prepareSource(entry.id, entry.type, entry.level, buildingId, worldId, placement, facing, exactRotation, null)
    }

    /** A later catalog must never choose another family's shape for an accepted building. */
    fun prepareUpgrade(building: CivBuilding, targetLevel: Int, worldId: UUID): CompletableFuture<CivBlueprint> = catalogLoad.thenCompose {
        val sourceId = if (building.schematicId.startsWith("civs/v2/")) {
            catalog.upgrade(building.schematicId, targetLevel)?.id ?: throw CivsRejection("blueprint-unavailable")
        } else schematicId(styleOf(building) ?: throw CivsRejection("invalid-style"), building.type, targetLevel)
        prepareSource(sourceId, building.type, targetLevel, building.id, worldId, building.anchor,
            building.rotation / 90, building.rotation, building.entrance)
    }

    /** Encoded generated source only; decoded snapshots stay with the expiring caller and Core cache. */
    private fun isPlanBoard(id: String) = id in CivsPlanBoardBlueprint.versions

    @Synchronized private fun planBoard(id: String): CompletableFuture<Builtin> {
        if (closed) return CompletableFuture.failedFuture(CivsRejection("not-ready"))
        require(isPlanBoard(id))
        builtins[id]?.let { return it }
        val prepared = CompletableFuture.supplyAsync({
            val bytes = CivsPlanBoardBlueprint.schematic(dataVersion, id)
            val file = AtomicFileStore(capturedRoot, Path.of("builtin", "plan_board", "${id.substringAfterLast('/')}.schem"),
                CivsStore.MAX_SCHEMATIC_BYTES.toLong(), { value: ByteArray -> value }, { it })
            file.write(bytes)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            Builtin(bytes, hash)
        }, executor)
        builtins[id] = prepared
        prepared.whenComplete { _, failure -> if (failure != null) synchronized(this) { builtins.remove(id, prepared) } }
        return prepared
    }

    private fun sourceSnapshot(id: String): CompletableFuture<ArcSchematicSnapshot> =
        if (isPlanBoard(id)) planBoard(id).thenCompose {
            captured.loadVerified("builtin/plan_board/${id.substringAfterLast('/')}", it.sha256, ArcSchematicContentPolicy.IGNORE_ENTITIES_AND_BIOMES)
        }
        else catalog.entry(id)?.let { source.loadVerified(id, it.hash, ArcSchematicContentPolicy.IGNORE_ENTITIES_AND_BIOMES) }
            ?: source.load(id, ArcSchematicContentPolicy.IGNORE_ENTITIES_AND_BIOMES)

    private fun sourceBytes(id: String, snapshot: ArcSchematicSnapshot): CompletableFuture<ByteArray> =
        if (isPlanBoard(id)) planBoard(id).thenApply { it.bytes.copyOf() }
        else source.captureSource(id, snapshot.fileSha256)

    /** A marker lookup needs no material bill, transformed block palette or preview surface. */
    fun controlPosition(building: CivBuilding): CompletableFuture<CivPosition> =
        sourceSnapshot(building.schematicId).thenApplyAsync({ original ->
            requireVolume(original)
            val aligned = CivsBlueprintGeometry.alignment(original, building.anchor, building.rotation / 90,
                building.rotation, building.entrance)
            val corners = listOf(original.minimum.x, original.maximum.x).flatMap { x ->
                listOf(original.minimum.z, original.maximum.z).map { z ->
                    CivsBlueprintGeometry.rotate(ArcSchematicPoint(x - original.origin.x, 0, z - original.origin.z), aligned.quarterTurns)
                }
            }
            val bounds = CivBox(CivPosition(aligned.anchor.x + corners.minOf { it.x }, aligned.anchor.y + original.minimum.y - original.origin.y,
                aligned.anchor.z + corners.minOf { it.z }),
                CivPosition(aligned.anchor.x + corners.maxOf { it.x }, aligned.anchor.y + original.maximum.y - original.origin.y,
                    aligned.anchor.z + corners.maxOf { it.z }))
            if (aligned.anchor != building.anchor || aligned.entrance != building.entrance || bounds != building.bounds)
                throw CivsRejection("control-location-changed")
            aligned.control
        }, executor)

    fun prepare(
        style: String, type: BuildingType, level: Int, buildingId: UUID, worldId: UUID,
        placement: CivPosition, facing: Int, exactRotation: Int? = null,
        fixedEntrance: CivPosition? = null,
    ): CompletableFuture<CivBlueprint> = catalogLoad.thenCompose {
        val identifier = if (type == BuildingType.PLAN_BOARD || type.isInfrastructure || !catalog.present)
            schematicId(style, type, level)
        else catalog.default(style, type, level)?.id ?: throw CivsRejection("blueprint-unavailable")
        prepareSource(identifier, type, level, buildingId, worldId, placement, facing, exactRotation, fixedEntrance)
    }

    internal fun preparePlanBoardRefit(board: CivBuilding, worldId: UUID): CompletableFuture<CivBlueprint> =
        prepareSource(CivsPlanBoardBlueprint.refitId(board), board.type, board.level, board.id, worldId,
            board.anchor, board.rotation / 90, board.rotation, board.entrance).thenApply {
            if (!CivsPlanBoardBlueprint.refit(board, it.building)) throw CivsRejection("invalid-building")
            it
        }

    private fun prepareSource(id: String, type: BuildingType, level: Int, buildingId: UUID, worldId: UUID,
        placement: CivPosition, facing: Int, exactRotation: Int?, fixedEntrance: CivPosition?): CompletableFuture<CivBlueprint> {
        return sourceSnapshot(id).thenComposeAsync({ original ->
            requireVolume(original)
            val (anchor, turns, entrance, worldControl) = CivsBlueprintGeometry.alignment(original, placement, facing, exactRotation, fixedEntrance)
            sourceBytes(id, original).thenComposeAsync({ bytes ->
                val library = if (isPlanBoard(id)) captured else source
                library.transform(original, ArcSchematicTransform(turns, false)).thenApplyAsync({ transformed ->
                    val bounds = CivBox(
                        CivPosition(anchor.x + transformed.minimum.x, anchor.y + transformed.minimum.y, anchor.z + transformed.minimum.z),
                        CivPosition(anchor.x + transformed.maximum.x, anchor.y + transformed.maximum.y, anchor.z + transformed.maximum.z),
                    )
                    val building = CivBuilding(buildingId, type, level, anchor, turns * 90, id, bounds, entrance,
                        BuildingPhase.CONSTRUCTING, control = worldControl.takeUnless { type == BuildingType.DECORATION })
                    val cells = worldCells(transformed, anchor).map { cell ->
                        if (type == BuildingType.DECORATION && cell.blockData.substringBefore('[') == "minecraft:structure_void")
                            cell.copy(blockData = "minecraft:air") else cell
                    }
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

    /** Generated infrastructure uses the same verified, owned capture as authored buildings. */
    internal fun prepareInfrastructure(piece: CivSettlementPiece, worldId: UUID, bytes: ByteArray, captureId: UUID = piece.id): CompletableFuture<CivBlueprint> {
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return captured.loadVerified(captureId.toString(), hash, ArcSchematicContentPolicy.IGNORE_ENTITIES_AND_BIOMES)
            .thenApplyAsync({ snapshot ->
                requireVolume(snapshot)
                val building = CivsInfrastructure.building(piece)
                val cells = worldCells(snapshot, building.anchor)
                val exact = cells.filterNot { it.isBarrier }.associate {
                    CivPosition(it.position.x, it.position.y, it.position.z) to it.blockData
                }
                if (exact != piece.blocks.filterNot { it.after == "minecraft:barrier" }
                        .associate { it.position to it.after }) throw CivsRejection("invalid-plan")
                rejectHazards(cells)
                val estimate = CivsMaterials.required(CivPlacementPlan(piece.id, piece.id, worldId, building,
                    exact.map { (position, after) -> CivPlanBlock(position, "minecraft:air", after) }))
                CivBlueprint(building, worldId, snapshot, cells, exposedCells(cells), bytes.copyOf(), estimate)
            }, executor)
    }

    /** Explicit maintenance accepts only exact legacy racks or the reviewed warehouse chest baseline.
     * Geometry remains private; neither arbitrary blocks nor library bookshelves are repair targets. */
    internal fun buildingStorageRepair(building: CivBuilding): CompletableFuture<List<CivPlanBlock>> {
        require(building.phase == BuildingPhase.ACTIVE)
        val style = STYLES.singleOrNull { building.schematicId == schematicId(it, building.type, building.level) }
            ?: return CompletableFuture.failedFuture(CivsRejection("invalid-building"))
        val legacyId = if (building.type == BuildingType.WAREHOUSE) "civs/legacy-warehouse-racks/$style/warehouse${building.level}"
            else building.schematicId.replace("civs/minecolonies/", "civs/legacy-building-racks/")
        val legacy = source.load(legacyId, ArcSchematicContentPolicy.IGNORE_ENTITIES_AND_BIOMES)
        val current = source.load(building.schematicId, ArcSchematicContentPolicy.IGNORE_ENTITIES_AND_BIOMES)
        fun transformed(snapshot: ArcSchematicSnapshot) = source.transform(snapshot, ArcSchematicTransform(building.rotation / 90, false))
        val previousChests = if (building.type == BuildingType.WAREHOUSE)
            source.load("civs/legacy-warehouse-chests/$style/warehouse${building.level}", ArcSchematicContentPolicy.IGNORE_ENTITIES_AND_BIOMES)
                .thenCompose(::transformed)
        else CompletableFuture.completedFuture(null)
        return legacy.thenCompose(::transformed).thenCombine(current.thenCompose(::transformed), ::Pair)
            .thenCombineAsync(previousChests, { (before, after), previous ->
            requireVolume(before); requireVolume(after)
            if (before.minimum != after.minimum || before.maximum != after.maximum || before.origin != after.origin)
                throw CivsRejection("control-location-changed")
            val replacements = worldCells(after, building.anchor).associateBy { it.position }
            val racks = worldCells(before, building.anchor).mapNotNull { cell ->
                val target = replacements.getValue(cell.position)
                if (cell.blockData == target.blockData) return@mapNotNull null
                require(cell.blockData.substringBefore('[') in setOf("minecraft:bookshelf", "minecraft:chiseled_bookshelf") &&
                    target.blockData.substringBefore('[') in setOf("minecraft:chest", "minecraft:barrel", "minecraft:air")) { "Storage repair contains non-rack changes" }
                CivPlanBlock(CivPosition(cell.position.x, cell.position.y, cell.position.z), cell.blockData, target.blockData)
            }
            val rackPositions = racks.mapTo(hashSetOf()) { it.position }
            val chests = previous?.let { snapshot ->
                requireVolume(snapshot)
                if (snapshot.minimum != after.minimum || snapshot.maximum != after.maximum || snapshot.origin != after.origin)
                    throw CivsRejection("control-location-changed")
                worldCells(snapshot, building.anchor).mapNotNull { cell ->
                    val target = replacements.getValue(cell.position)
                    if (cell.blockData == target.blockData) return@mapNotNull null
                    val position = CivPosition(cell.position.x, cell.position.y, cell.position.z)
                    require(position in rackPositions && cell.blockData.substringBefore('[') == "minecraft:chest" &&
                        target.blockData.substringBefore('[') == "minecraft:chest") { "Chest alignment contains non-chest changes" }
                    CivPlanBlock(position, cell.blockData, target.blockData)
                }
            }.orEmpty()
            (racks + chests).also { require(it.size in 1..512) { "Storage repair must contain at most512 exact legacy alternatives" } }
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

    fun paste(snapshot: ArcSchematicSnapshot, worldId: UUID, anchor: CivPosition,
        onPlaced: java.util.function.Consumer<ArcSchematicBlock>, ready: java.util.function.BooleanSupplier): ArcSchematicOperation =
        captured.paste(snapshot, ArcSchematicPasteTarget(worldId, ArcSchematicPoint(anchor.x, anchor.y, anchor.z)),
            ArcSchematicPasteOptions(false, true, false), onPlaced, ready, 5)

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

    override fun close() {
        synchronized(this) { closed = true; builtins.clear() }
        source.close(); captured.close()
    }

    private fun requireVolume(snapshot: ArcSchematicSnapshot) {
        if (snapshot.volume !in 1..maximumVolume.toLong()) throw CivsRejection("building-too-large")
    }

    companion object {
        val STYLES = listOf("nordic", "medievaloak", "caledonia")
        fun styleOf(building: CivBuilding): String? = if (building.schematicId.startsWith("civs/v2/"))
            building.schematicId.removePrefix("civs/v2/").substringBefore('/')
        else STYLES.firstOrNull { building.type != BuildingType.DECORATION && building.schematicId == schematicId(it, building.type, building.level) }
        fun schematicId(style: String, type: BuildingType, level: Int): String {
            if (type == BuildingType.PLAN_BOARD) {
                require(level == 1)
                return CivsPlanBoardBlueprint.SCHEMATIC_ID
            }
            require(style in STYLES && level in 1..type.maxLevel)
            val name = when (type) {
                BuildingType.TOWN_HALL -> "fundamentals/townhall"
                BuildingType.BUILDER_HUT -> "fundamentals/builder"
                BuildingType.HOUSE -> "fundamentals/residence"
                BuildingType.FORESTER -> "fundamentals/lumberjack"
                BuildingType.QUARRY -> "fundamentals/miner"
                BuildingType.FARM -> "agriculture/horticulture/farmer"
                BuildingType.WAREHOUSE -> "craftsmanship/storage/warehouse"
                BuildingType.SAWMILL -> "craftsmanship/carpentry/sawmill"
                BuildingType.BLACKSMITH -> "craftsmanship/metallurgy/blacksmith"
                BuildingType.STONEMASON -> "craftsmanship/masonry/stonemason"
                BuildingType.LIBRARY -> "education/library"
                BuildingType.TAVERN -> "fundamentals/tavern"
                BuildingType.BAKERY -> "craftsmanship/luxury/baker"
                BuildingType.GUARD_TOWER -> "military/guardtower"
                BuildingType.PLAN_BOARD -> return CivsPlanBoardBlueprint.SCHEMATIC_ID
                BuildingType.UNIVERSITY -> "education/university"
                BuildingType.SMELTERY -> "craftsmanship/metallurgy/smeltery"
                BuildingType.CRUSHER -> "craftsmanship/masonry/crusher"
                BuildingType.DECORATION -> throw CivsRejection("blueprint-unavailable")
                BuildingType.WALL, BuildingType.GATE, BuildingType.ROAD ->
                    return "civs/infrastructure/$style/${type.name.lowercase()}$level"
            }
            return "civs/minecolonies/$style/$name$level"
        }

        fun worldCells(snapshot: ArcSchematicSnapshot, anchor: CivPosition, decoration: Boolean = false): List<ArcSchematicBlock> = snapshot.blocks().map { cell ->
            cell.copy(position = ArcSchematicPoint(cell.position.x + anchor.x, cell.position.y + anchor.y, cell.position.z + anchor.z),
                blockData = if (decoration && cell.blockData.substringBefore('[') == "minecraft:structure_void") "minecraft:air" else cell.blockData)
        }

        /** Curated origins are outside the box; an authored control is inside, distinct from the legacy origin control. */
        internal fun hasAuthoredControl(building: CivBuilding): Boolean = building.control != null && building.control != building.anchor

        internal fun verifyCapturedPlan(plan: CivPlacementPlan, snapshot: ArcSchematicSnapshot) {
            if (hasAuthoredControl(plan.building)) {
                val marker = CivsBlueprintGeometry.controlMarker(snapshot)
                if (CivPosition(marker.x + plan.building.anchor.x, marker.y + plan.building.anchor.y,
                        marker.z + plan.building.anchor.z) != plan.building.control) throw CivsRejection("invalid-plan")
            }
            val cells = worldCells(snapshot, plan.building.anchor).map { cell ->
                if (plan.building.type == BuildingType.DECORATION && cell.blockData.substringBefore('[') == "minecraft:structure_void")
                    cell.copy(blockData = "minecraft:air") else cell
            }.filterNot { it.isBarrier ||
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
