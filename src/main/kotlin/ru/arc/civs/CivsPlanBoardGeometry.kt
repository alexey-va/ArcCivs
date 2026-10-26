package ru.arc.civs

import java.util.UUID
import kotlin.math.min

internal data class CivPlanBoardCell(val x: Long, val z: Long)
internal data class CivPlanBoardRect(val x0: Long, val z0: Long, val x1: Long, val z1: Long)
internal data class CivPlanBoardPatch(val key: String, val x: Double, val y: Double, val z: Double,
    val width: Double, val height: Double, val material: String)
internal data class CivPlanBoardRaster(val key: String, val x: Double, val y: Double, val z: Double,
    val width: Double, val height: Double, val columns: Int, val rows: List<String>, val color: Int = 0x684525)
internal data class CivPlanBoardEntry(val id: UUID, val number: Int, val type: BuildingType,
    val x: Double, val y: Double, val width: Double, val height: Double, val forecast: Boolean = false)
internal data class CivPlanBoardLayout(val patches: List<CivPlanBoardPatch>, val rasters: List<CivPlanBoardRaster>,
    val entries: List<CivPlanBoardEntry>, val northLeft: Boolean, val roadCells: Set<CivPlanBoardCell>,
    val roadRectangles: List<CivPlanBoardRect>, val showNumbers: Boolean = true) {
    val displayParts: Int get() = patches.size + rasters.size + (if (showNumbers) entries.size else 0) + FIXED_PARTS
    companion object { const val FIXED_PARTS = 11 }
}

/** Detached geometry only. Candidate sketches remain separate from accepted buildings and routes. */
internal object CivsPlanBoardGeometry {
    private const val LEFT = 1.14
    private const val RIGHT = 4.20
    private const val BOTTOM = 1.22
    private const val TOP = 3.86
    const val MAX_PARTS = 256
    // Our allocation/packet guard, not a Minecraft protocol maximum or a gameplay route limit.
    const val MAX_RASTER_TEXT_CHARS = 262_144
    private const val MAX_FORECAST_RASTER_TEXT_CHARS = 131_072

    /** Exact row-run union: every output cell existed in the input, including gaps between parallel paths. */
    fun rectangles(cells: Set<CivPlanBoardCell>): List<CivPlanBoardRect> {
        val finished = mutableListOf<CivPlanBoardRect>()
        var active = mutableMapOf<Pair<Long, Long>, CivPlanBoardRect>()
        for ((z, points) in cells.groupBy { it.z }.toSortedMap()) {
            val row = points.map { it.x }.sorted()
            val runs = mutableListOf<Pair<Long, Long>>()
            var start = row.first(); var end = start + 1
            for (x in row.drop(1)) {
                if (x == end) end++ else { runs += start to end; start = x; end = x + 1 }
            }
            runs += start to end
            val next = mutableMapOf<Pair<Long, Long>, CivPlanBoardRect>()
            for (run in runs) {
                val previous = active.remove(run)
                if (previous != null && previous.z1 == z) next[run] = previous.copy(z1 = z + 1)
                else {
                    if (previous != null) finished += previous
                    next[run] = CivPlanBoardRect(run.first, z, run.second, z + 1)
                }
            }
            finished += active.values
            active = next
        }
        return (finished + active.values).sortedWith(compareBy({ it.z0 }, { it.x0 }, { it.z1 }, { it.x1 }))
    }

    fun layout(snapshot: CivPlanBoardSnapshot): CivPlanBoardLayout {
        require(snapshot.buildings.size <= CivsRules.MAX_SITES && snapshot.plannedBuildings.size <= CivsRules.MAX_SITES + 1)
        val groups = linkedMapOf<String, Set<CivPlanBoardCell>>()
        snapshot.routes.toSortedMap(compareBy(UUID::toString)).forEach { (id, route) ->
            val cells = route.mapTo(linkedSetOf()) { CivPlanBoardCell(it.x.toLong(), it.z.toLong()) }
            if (cells.isNotEmpty()) groups["route-$id"] = cells
        }
        snapshot.buildings.filter { it.type == BuildingType.ROAD || it.type == BuildingType.GATE }.forEach { building ->
            val cells = building.transit.orEmpty().mapTo(linkedSetOf()) { CivPlanBoardCell(it.x.toLong(), it.z.toLong()) }
            if (cells.isNotEmpty()) groups["transit-${building.id}"] = cells
        }
        val roadCells = groups.values.flatten().toSet()
        val merged = rectangles(roadCells)
        fun bounds(building: CivBuilding) = CivPlanBoardRect(building.bounds.min.x.toLong(), building.bounds.min.z.toLong(),
            building.bounds.max.x.toLong() + 1, building.bounds.max.z.toLong() + 1)
        // A legacy piece without recorded transit remains a built footprint, never an invented road connection.
        val built = snapshot.buildings.filter {
            it.type != BuildingType.ROAD && it.type != BuildingType.GATE || it.transit.isNullOrEmpty()
        }
        val planned = snapshot.plannedBuildings
        val forecast = snapshot.forecast?.buildings.orEmpty()
        require(forecast.size <= 8)
        val occupied = (snapshot.buildings + planned + forecast).map { it.bounds }
        fun sketch(boxes: List<CivBox>): Set<CivPlanBoardCell> = buildSet {
            for (box in boxes) for (x in box.min.x..box.max.x) for (z in box.min.z..box.max.z) {
                val along = if (box.max.x - box.min.x >= box.max.z - box.min.z) x else z
                val cell = CivPlanBoardCell(x.toLong(), z.toLong())
                if (Math.floorMod(along, 6) < 3 && cell !in roadCells && occupied.none {
                    x in it.min.x..it.max.x && z in it.min.z..it.max.z
                }) add(cell)
            }
        }
        val sketches = mapOf("forecast-layout" to sketch(snapshot.forecast?.streets.orEmpty() +
            snapshot.forecast?.perimeter.orEmpty())).filterValues { it.isNotEmpty() }
        val sketchRects = sketches.mapValues { rectangles(it.value) }
        val extents = (snapshot.buildings + planned + forecast).map(::bounds) + merged + sketchRects.values.flatten()
        val all = extents.ifEmpty { listOf(CivPlanBoardRect(snapshot.center.x.toLong(), snapshot.center.z.toLong(),
            snapshot.center.x.toLong() + 1, snapshot.center.z.toLong() + 1)) }
        val minX = all.minOf { it.x0 } - 3; val maxX = all.maxOf { it.x1 } + 3
        val minZ = all.minOf { it.z0 } - 3; val maxZ = all.maxOf { it.z1 } + 3
        val northLeft = maxZ - minZ > maxX - minX
        val minU = if (northLeft) minZ else minX; val maxU = if (northLeft) maxZ else maxX
        val minV = if (northLeft) minX else minZ; val maxV = if (northLeft) maxX else maxZ
        val scale = min((RIGHT - LEFT) / (maxU - minU), (TOP - BOTTOM) / (maxV - minV))
        val left = (LEFT + RIGHT - (maxU - minU) * scale) / 2
        val bottom = (BOTTOM + TOP - (maxV - minV) * scale) / 2
        fun uv(cell: CivPlanBoardCell) = if (northLeft) CivPlanBoardCell(cell.z, cell.x) else cell
        fun project(rect: CivPlanBoardRect, key: String, material: String, depth: Double): CivPlanBoardPatch {
            val lo = uv(CivPlanBoardCell(rect.x0, rect.z0)); val hi = uv(CivPlanBoardCell(rect.x1, rect.z1))
            val y = if (northLeft) lo.z - minV else maxV - hi.z
            return CivPlanBoardPatch(key, left + (lo.x - minU) * scale, bottom + y * scale, depth,
                (hi.x - lo.x) * scale, (hi.z - lo.z) * scale, material)
        }
        val ordinary = (snapshot.buildings + planned + forecast).filterNot { it.type.isInfrastructure }
            .distinctBy { it.id }.sortedWith(compareBy({ it.type.ordinal }, { it.id.toString() }))
        val entries = ordinary.mapIndexed { index, building ->
            val patch = project(bounds(building), "", "", 0.0)
            CivPlanBoardEntry(building.id, index + 1, building.type, patch.x + patch.width / 2,
                patch.y + patch.height / 2, patch.width, patch.height, forecast.any { it.id == building.id })
        }
        val patches = mutableListOf<CivPlanBoardPatch>()
        sketchRects.forEach { (key, rectangles) -> rectangles.forEachIndexed { index, rect ->
            patches += project(rect, "$key-$index", "PURPLE_CONCRETE", 2.008)
        } }
        merged.forEachIndexed { index, rect -> patches += project(rect, "road-$index", "BROWN_CONCRETE", 2.012) }
        built.forEach { building -> patches += project(bounds(building), "built-${building.id}",
            if (building.type.isInfrastructure) "GRAY_CONCRETE" else "CYAN_CONCRETE", 2.026) }
        planned.forEach { building ->
            val p = project(bounds(building), "planned-${building.id}", "YELLOW_CONCRETE", 2.040)
            val t = min(.032, min(p.width, p.height) / 3)
            patches += p.copy(key = "${p.key}-bottom", height = t)
            patches += p.copy(key = "${p.key}-top", y = p.y + p.height - t, height = t)
            patches += p.copy(key = "${p.key}-left", y = p.y + t, width = t, height = p.height - 2 * t)
            patches += p.copy(key = "${p.key}-right", x = p.x + p.width - t, y = p.y + t, width = t, height = p.height - 2 * t)
        }
        forecast.forEach { building ->
            val p = project(bounds(building), "forecast-${building.id}", "PURPLE_CONCRETE", 2.040)
            val t = min(.022, min(p.width, p.height) / 5)
            val arm = min(p.width, p.height) * .32
            for (right in listOf(false, true)) for (top in listOf(false, true)) {
                val x = if (right) p.x + p.width - arm else p.x
                val y = if (top) p.y + p.height - t else p.y
                val key = "${p.key}-$right-$top"
                patches += p.copy(key = "$key-h", x = x, y = y, width = arm, height = t)
                patches += p.copy(key = "$key-v", x = if (right) p.x + p.width - t else p.x,
                    y = if (top) p.y + p.height - arm else p.y, width = t, height = arm)
            }
        }
        if (patches.size + entries.size + CivPlanBoardLayout.FIXED_PARTS <= MAX_PARTS)
            return CivPlanBoardLayout(patches, emptyList(), entries, northLeft, roadCells, merged)

        // Pathological exact geometry uses one transparent bitmap per bounded source route/piece.
        // Four vanilla full blocks and nine spaces both advance 36 pixels. The native one-pixel
        // shadow bridges the glyph's 8-pixel ink to its 9-pixel advance/line pitch. It stays inside
        // the cell, preserving contacts without spilling into an adjacent empty coordinate.
        var rasterCharacters = 0L
        val raster = (groups + sketches).map { (key, points) ->
            val mapped = points.mapTo(hashSetOf(), ::uv)
            val x0 = mapped.minOf { it.x }; val x1 = mapped.maxOf { it.x } + 1
            val z0 = mapped.minOf { it.z }; val z1 = mapped.maxOf { it.z } + 1
            val width = x1 - x0; val height = z1 - z0
            require(width <= 512 && height <= 512 && width * height <= 65_536) {
                "Settlement plan raster $key exceeds display bounds: ${width}x$height"
            }
            // An empty cell uses nine UTF-16 characters, an occupied one four; include line breaks.
            // At 512 columns, even an all-ink row is only 6,144 UTF-8 bytes in its own NBT string.
            val characters = width * height * 9 - mapped.size * 5L + height - 1
            if (key.startsWith("forecast-")) require(characters <= MAX_FORECAST_RASTER_TEXT_CHARS) {
                "Settlement forecast raster exceeds $MAX_FORECAST_RASTER_TEXT_CHARS characters"
            } else rasterCharacters += characters
            require(rasterCharacters <= MAX_RASTER_TEXT_CHARS) {
                "Settlement plan raster text exceeds $MAX_RASTER_TEXT_CHARS characters at $key: $rasterCharacters"
            }
            // Text rows run top to bottom. Turning north left must turn east up, never mirror it down.
            val rowOrder = if (northLeft) z1 - 1 downTo z0 else z0 until z1
            val rows = rowOrder.map { z -> buildString {
                for (x in x0 until x1) append(if (CivPlanBoardCell(x, z) in mapped) "████" else "         ")
            } }
            val y = if (northLeft) z0 - minV else maxV - z1
            CivPlanBoardRaster(key, left + (x0 - minU) * scale, bottom + y * scale, 2.012,
                (x1 - x0) * scale, (z1 - z0) * scale, (x1 - x0).toInt(), rows,
                if (key.startsWith("forecast-")) 0x7944A0 else 0x684525)
        }
        val compact = built.map { building -> project(bounds(building), "built-${building.id}",
            if (building.type.isInfrastructure) "GRAY_CONCRETE" else "CYAN_CONCRETE", 2.026) } +
            planned.map { project(bounds(it), "planned-${it.id}", "ORANGE_STAINED_GLASS", 2.040) } +
            forecast.map { project(bounds(it), "forecast-${it.id}", "PURPLE_STAINED_GLASS", 2.040) }
        val numbered = CivPlanBoardLayout(compact, raster, entries, northLeft, roadCells, merged)
        // All map geometry wins over optional number labels at the full-city/queued-upgrade boundary.
        // The renderer explicitly directs readers to the building menu instead of showing an unlinked key.
        return (if (numbered.displayParts <= MAX_PARTS) numbered else numbered.copy(showNumbers = false)).also {
            require(it.displayParts <= MAX_PARTS) { "Settlement plan exceeds its bounded display contract" }
        }
    }
}
