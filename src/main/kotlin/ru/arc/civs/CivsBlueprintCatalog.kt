package ru.arc.civs

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonElement
import java.nio.file.Files
import java.nio.file.Path

/** Small, immutable placement choices. Private source voxels never enter the public plugin. */
data class CivBlueprintChoice(val id: String, val style: String, val type: BuildingType,
    val label: String, val maxLevel: Int, val size: List<Int> = emptyList(), val category: String = "buildings")

internal data class CivBlueprintCatalogEntry(
    val id: String, val sourceId: String, val style: String, val family: String, val revision: String,
    val type: BuildingType, val level: Int, val maxLevel: Int, val hash: String,
    val size: List<Int>, val origin: List<Int>, val control: List<Int>, val worksite: JsonObject?,
    val dining: List<CivPosition>,
) {
    val familyKey get() = Triple(style, family, revision)
    fun choice() = CivBlueprintChoice(id, style, type, family.substringAfterLast('/'), maxLevel, size, decorationCategory(family))

    private fun decorationCategory(path: String): String = when {
        listOf("fountain", "well", "pond", "waterfall").any(path::contains) -> "water"
        listOf("wall", "gate", "tower", "fortification").any(path::contains) -> "walls"
        listOf("road", "path", "bridge", "rail").any(path::contains) -> "roads"
        listOf("garden", "park", "tree", "agriculture", "field").any(path::contains) -> "gardens"
        listOf("statue", "monument", "sculpture", "obelisk").any(path::contains) -> "monuments"
        listOf("supply", "camp", "ship", "boat", "cart").any(path::contains) -> "supplies"
        path.startsWith("decorations/") -> "details"
        else -> "buildings"
    }
}

/** The curation manifest accounts for rejected inputs too; only READY entries become choices. */
internal class CivBlueprintCatalog private constructor(
    val present: Boolean,
    val sourceCount: Int,
    val rejectedCount: Int,
    private val current: List<CivBlueprintCatalogEntry>,
    private val retained: List<CivBlueprintCatalogEntry>,
) {
    private val byId = (retained + current).associateBy { it.id }
    private val levels = byId.values.groupBy { it.familyKey }
    val worksiteLayouts = byId.values.mapNotNull { entry -> entry.worksite?.let { entry.id to it.deepCopy() } }.toMap()

    fun entry(id: String): CivBlueprintCatalogEntry? = byId[id]
    fun choices(type: BuildingType, style: String? = null): List<CivBlueprintChoice> = current.asSequence()
        .filter { it.type == type && it.level == 1 && (style == null || it.style == style) }
        .sortedWith(compareBy<CivBlueprintCatalogEntry> { it.family }.thenBy { it.id }).map { it.choice() }.toList()

    fun styles(type: BuildingType? = null): List<String> = current.asSequence()
        .filter { it.level == 1 && (type == null || it.type == type) }.map { it.style }.distinct().sorted().toList()

    fun smallDecorations(style: String): List<CivBlueprintChoice> = current.asSequence()
        .filter { it.type == BuildingType.DECORATION && it.style == style && it.family.startsWith("decorations/") &&
            it.size[0] <= 15 && it.size[2] <= 15 && it.size[1] <= 12 }
        .sortedBy { it.id }.take(32).map { it.choice() }.toList()

    fun default(style: String, type: BuildingType, level: Int): CivBlueprintCatalogEntry? {
        val first = choices(type, style).firstOrNull { it.maxLevel >= level } ?: return null
        return upgrade(first.id, level)
    }

    /** New automatic buildings may mix styles; accepted revisions never use this selection again. */
    fun autonomous(style: String, type: BuildingType): CivBlueprintCatalogEntry? {
        val required = when (type) {
            BuildingType.TOWN_HALL, BuildingType.UNIVERSITY, BuildingType.SMELTERY, BuildingType.CRUSHER -> 3
            else -> minOf(2, type.maxLevel)
        }
        val available = current.filter { it.type == type && it.level == 1 }
        val capable = available.filter { it.maxLevel >= required }.ifEmpty { available }
        return capable.sortedWith(compareBy<CivBlueprintCatalogEntry> { if (it.style == style) 0 else 1 }
            .thenByDescending { it.maxLevel }.thenBy { it.style }.thenBy { it.family }.thenBy { it.id }).firstOrNull()
    }

    fun upgrade(id: String, targetLevel: Int): CivBlueprintCatalogEntry? = byId[id]?.let { source ->
        levels[source.familyKey]?.singleOrNull { it.level == targetLevel && it.type == source.type }
    }

    companion object {
        val EMPTY = CivBlueprintCatalog(false, 0, 0, emptyList(), emptyList())
        private val hash = Regex("[a-f0-9]{64}")
        private val revision = Regex("[a-f0-9]{20}")
        private val style = Regex("[a-z0-9][a-z0-9_-]{0,79}")
        private val id = Regex("civs/v2/[a-z0-9_-]+/[a-z0-9_-]+/[a-f0-9]{20}/level[1-3]")
        private val checks = setOf("source-hashes", "shared-max-level-frame", "ground-origin", "front-door-route",
            "one-control", "storage-provenance", "fluid-boundary", "full-cell-readback")

        fun load(root: Path): CivBlueprintCatalog {
            val file = root.resolve("civs/v2/catalog.json")
            if (!Files.exists(file)) return EMPTY
            require(Files.isRegularFile(file) && Files.size(file) in 1..64_000_000L) { "Invalid blueprint catalog size" }
            val document = Files.newBufferedReader(file).use { JsonParser.parseReader(it).asJsonObject }
            return parse(document).also { catalog ->
                catalog.byId.values.forEach { entry ->
                    val path = root.resolve(entry.id + ".schem").normalize()
                    require(path.startsWith(root.normalize()) && Files.isRegularFile(path)) { "Missing curated blueprint: ${entry.id}" }
                }
            }
        }

        /** Production load requires the complete pinned source inventory; small synthetic fixtures use an explicit count. */
        internal fun parse(document: JsonObject, expectedSources: Int = 14_830): CivBlueprintCatalog {
            require(document["schemaVersion"].asInt == 2 && document["completeInventory"].asBoolean)
            require(document["privateAssetsOnly"].asBoolean && hash.matches(document["recipeSha256"].asString))
            require(hash.matches(document["sourceInventorySha256"].asString))
            val rows = document.getAsJsonArray("entries")
            require(rows.size() == expectedSources && document["totalSources"].asInt == expectedSources)
            require(rows.map { it.asJsonObject["sourceId"].asString }.toSet().size == expectedSources)
            rows.forEach { value ->
                val row = value.asJsonObject
                require(row["sourceId"].asString.length in 1..512 && hash.matches(row["sourceSha256"].asString) &&
                    hash.matches(row["convertedSha256"].asString))
                require(row["verdict"].asString in setOf("READY", "REJECTED", "METADATA_ONLY"))
                require(row["verdict"].asString == "READY" || row.getAsJsonArray("reasons").size() > 0)
            }
            val current = rows.filter { it.asJsonObject["verdict"].asString == "READY" }.map { entry(it.asJsonObject) }
            val retainedRows = document.getAsJsonArray("retainedEntries")
            require((retainedRows?.size() ?: 0) <= 45_000)
            val retained = retainedRows?.map { entry(it.asJsonObject) }.orEmpty()
            require(current.map { it.id }.distinct().size == current.size && retained.map { it.id }.distinct().size == retained.size)
            val all = retained + current
            all.groupBy { it.id }.values.forEach { duplicates -> require(duplicates.all { it == duplicates.first() }) }
            all.distinctBy { it.id }.groupBy { it.familyKey }.values.forEach { family ->
                val first = family.first()
                require(family.map { it.level }.sorted() == (1..first.maxLevel).toList())
                require(family.all { it.type == first.type && it.size == first.size && it.origin == first.origin &&
                    it.control == first.control && it.maxLevel == first.maxLevel })
                val links = family.associate { it.level.toString() to it.id }
                family.forEach { entry ->
                    val declared = entry.worksite?.getAsJsonObject("upgradeSchematics")?.entrySet()?.associate { it.key to it.value.asString }
                    require((declared == null && entry.type != BuildingType.QUARRY) || declared == links)
                }
            }
            return CivBlueprintCatalog(true, rows.size(), rows.size() - current.size, current.toList(), retained.toList())
        }

        private fun entry(row: JsonObject): CivBlueprintCatalogEntry {
            val identifier = row["schematicId"].asString
            val styleId = row["style"].asString
            val family = row["family"].asString
            val rev = row["revision"].asString
            val type = BuildingType.valueOf(row["type"].asString)
            val level = row["level"].asInt
            val maxLevel = row["maxSupportedLevel"].asInt
            fun vector(key: String) = row.getAsJsonArray(key).also { require(it.size() == 3) }.map { it.asInt }
            val size = vector("size"); val origin = vector("origin"); val control = vector("control")
            require(id.matches(identifier) && style.matches(styleId) && revision.matches(rev) &&
                identifier.startsWith("civs/v2/$styleId/") && identifier.endsWith("/$rev/level$level"))
            require(family.length in 1..256 && !family.contains("..") && type != BuildingType.PLAN_BOARD && !type.isInfrastructure)
            require(level in 1..maxLevel && maxLevel in 1..type.maxLevel && row["referenceLevel"].asInt == maxLevel)
            require(size.all { it in 1..256 } && size.fold(1L) { a, b -> a * b } in 1..50_000L)
            require(origin[0] in 0 until size[0] && origin[1] in 0 until size[1] - 2 && origin[2] == size[2])
            require(control.indices.all { control[it] in 0 until size[it] })
            require(row["surface"].asInt in 1..6_000 && hash.matches(row["sha256"].asString))
            require(row.getAsJsonArray("checks").map { it.asString }.containsAll(checks))
            val worksite = row["worksite"]?.takeUnless { it.isJsonNull }?.asJsonObject?.deepCopy()
            worksite?.let { site ->
                boundedMetadata(site)
                require(site["schematicId"].asString == identifier && site["schematicSha256"].asString == row["sha256"].asString &&
                    site["type"].asString == type.name && site["level"].asInt == level)
                val bounds = site.getAsJsonObject("schematicBounds")
                require(bounds.getAsJsonArray("min").map { it.asInt } == origin.map { -it } &&
                    bounds.getAsJsonArray("max").map { it.asInt } == size.indices.map { size[it] - origin[it] - 1 })
                require(site.getAsJsonArray("entrance").map { it.asInt } == listOf(0, 1, 0))
            }
            if (type in setOf(BuildingType.FORESTER, BuildingType.FARM, BuildingType.QUARRY)) {
                val role = when (type) { BuildingType.FORESTER -> "forester"; BuildingType.FARM -> "farm"; else -> "mine" }
                require(worksite?.has(role) == true && worksite["schematicId"].asString == identifier &&
                    worksite["schematicSha256"].asString == row["sha256"].asString)
                val geometry = worksite.getAsJsonObject(role)
                when (type) {
                    BuildingType.FARM -> require(geometry.getAsJsonArray("soilCells").size() in 1..128 &&
                        geometry.getAsJsonArray("irrigationCells").size() in 1..16)
                    BuildingType.QUARRY -> require(geometry["ladderFacing"].asString in setOf("north", "south", "east", "west") &&
                        geometry.getAsJsonArray("ladderBottom").size() == 3 && geometry.getAsJsonArray("shaftCenter").size() == 3 &&
                        geometry.getAsJsonArray("floors").size() in 1..3)
                    else -> require(geometry.getAsJsonArray("workEntry").size() == 3 && geometry.has("searchBounds"))
                }
            }
            val dining = row.getAsJsonArray("dining")?.map { point ->
                val values = point.asJsonArray.also { require(it.size() == 3) }.map { it.asInt }
                require(values.indices.all { values[it] in 0 until size[it] })
                CivPosition(values[0] - origin[0], values[1] - origin[1], values[2] - origin[2])
            }.orEmpty()
            require(dining.size <= 6 && dining.distinct().size == dining.size)
            return CivBlueprintCatalogEntry(identifier, row["sourceId"].asString, styleId, family, rev, type,
                level, maxLevel, row["sha256"].asString, size, origin, control, worksite, dining)
        }

        private fun boundedMetadata(root: JsonElement) {
            var remaining = 8_000
            fun visit(value: JsonElement, depth: Int) {
                require(--remaining >= 0 && depth <= 10)
                when {
                    value.isJsonObject -> value.asJsonObject.entrySet().forEach { (key, child) ->
                        require(key.length <= 64); visit(child, depth + 1)
                    }
                    value.isJsonArray -> { require(value.asJsonArray.size() <= 1_024); value.asJsonArray.forEach { visit(it, depth + 1) } }
                    value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> require(value.asString.matches(Regex("-?[0-9]{1,4}")) && value.asInt in -512..512)
                    value.isJsonPrimitive && value.asJsonPrimitive.isString -> require(value.asString.length <= 512)
                    else -> require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean || value.isJsonNull)
                }
            }
            visit(root, 0)
        }
    }
}
