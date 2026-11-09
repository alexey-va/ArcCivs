package ru.arc.civs

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Authored, pinned work areas; live terrain is still checked before every action. */
internal object CivsWorksiteLayouts {
    private data class Field(val columns: Set<Pair<Int, Int>>, val bounds: CivBox?)
    private data class Catalog(val sites: Map<String, JsonObject>, val fields: Map<String, Field>)

    @Volatile private var catalog = Catalog(emptyMap(), emptyMap())

    /** Called once by the async catalog owner before resident/city recovery starts. */
    fun installCatalog(values: Map<String, JsonObject>, furniture: Map<String, JsonObject> = emptyMap()) {
        val detached = values.mapValues { it.value.deepCopy() }.toMutableMap()
        furniture.forEach { (id, tavern) ->
            val site = (detached[id] ?: sites[id] ?: JsonObject()).deepCopy()
            site.add("tavern", tavern.deepCopy())
            detached[id] = site
        }
        detached.values.forEach { site -> site.getAsJsonObject("tavern")?.getAsJsonArray("furniture")?.let(CivsTavernFurniture::validate) }
        catalog = Catalog(detached, detached.mapValues { (_, site) -> field(site) })
    }
    private val sites: Map<String, JsonObject> by lazy {
        val input = checkNotNull(javaClass.getResourceAsStream("/worksites.json")) { "Missing worksite layouts" }
        val root = input.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        require(root["version"].asInt == 1)
        root.getAsJsonObject("sites").entrySet().associate { it.key to it.value.asJsonObject }
    }

    private val fields by lazy { sites.mapValues { (_, site) -> field(site) } }

    private fun field(site: JsonObject): Field {
        val farm = site.getAsJsonObject("farm")
        val cells = listOf("soilCells", "irrigationCells").flatMap { farm?.getAsJsonArray(it)?.map(::vector).orEmpty() }
        if (cells.isEmpty()) return Field(emptySet(), null)
        val min = CivPosition(cells.minOf { it.x }, cells.minOf { it.y }, cells.minOf { it.z })
        val max = CivPosition(cells.maxOf { it.x }, cells.maxOf { it.y }, cells.maxOf { it.z })
        return Field(cells.mapTo(hashSetOf()) { it.x to it.z }, CivBox(min, max))
    }

    fun site(building: CivBuilding): JsonObject? = catalog.sites[building.schematicId] ?: sites[building.schematicId]

    private fun authoredField(building: CivBuilding): Field? =
        catalog.fields[building.schematicId] ?: fields[building.schematicId]

    /** Exact family links retain only higher supported cuts, not a history of the whole mine. */
    fun mineFamily(building: CivBuilding): List<Pair<String, Int>> {
        val linked = site(building)?.getAsJsonObject("upgradeSchematics")?.entrySet()?.mapNotNull { (level, id) ->
            level.toIntOrNull()?.takeIf { it in building.level..building.type.maxLevel }?.let { id.asString to it }
        }
        if (linked != null) return linked
        val prefix = building.schematicId.dropLastWhile(Char::isDigit)
        if (building.schematicId != "$prefix${building.level}") return listOf(building.schematicId to building.level)
        return (building.level..building.type.maxLevel).mapNotNull { level ->
            val id = "$prefix$level"
            (id to level).takeIf { site(building.copy(schematicId = id, level = level))?.has("mine") == true }
        }
    }

    fun point(building: CivBuilding, local: CivPosition): CivPosition {
        val (x, z) = when (Math.floorMod(building.rotation / 90, 4)) {
            0 -> local.x to local.z
            1 -> -local.z to local.x
            2 -> -local.x to -local.z
            else -> local.z to -local.x
        }
        return CivPosition(Math.addExact(building.anchor.x, x), Math.addExact(building.anchor.y, local.y), Math.addExact(building.anchor.z, z))
    }

    /** A cardinal rotation maps opposite corners to opposite corners of the exact world envelope. */
    fun worldBounds(building: CivBuilding, first: CivPosition, last: CivPosition): CivBox {
        val a = point(building, first)
        val b = point(building, last)
        return CivBox(CivPosition(minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z)),
            CivPosition(maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z)))
    }

    fun fieldBounds(building: CivBuilding): CivBox? {
        if (building.farmFieldVersion == CivsFarmFields.VERSION) CivsFarmFields.bounds(building)?.let { return it }
        return authoredField(building)?.bounds?.let { worldBounds(building, it.min, it.max) }
    }

    fun vector(value: com.google.gson.JsonElement): CivPosition = value.asJsonArray.let {
        require(it.size() == 3)
        CivPosition(it[0].asInt, it[1].asInt, it[2].asInt)
    }

    fun points(building: CivBuilding, section: String, key: String): List<CivPosition> =
        site(building)?.getAsJsonObject(section)?.getAsJsonArray(key)?.map { point(building, vector(it)) }.orEmpty()

    /** Local field query; preserves the complete authored field including its water/hay center. */
    fun isField(building: CivBuilding, x: Int, z: Int): Boolean {
        val cells = (if (building.farmFieldVersion == CivsFarmFields.VERSION && CivsFarmFields.supported(building))
            CivsFarmFields.columns(building) else authoredField(building)?.columns)
            ?.takeIf { it.isNotEmpty() } ?: return false
        val dx = x - building.anchor.x
        val dz = z - building.anchor.z
        val (localX, localZ) = when (Math.floorMod(building.rotation / 90, 4)) {
            0 -> dx to dz; 1 -> dz to -dx; 2 -> -dx to -dz; else -> -dz to dx
        }
        return (localX to localZ) in cells
    }

    fun field(building: CivBuilding): List<CivPosition> = if (building.farmFieldVersion == CivsFarmFields.VERSION &&
        CivsFarmFields.supported(building)) CivsFarmFields.soil(building) else points(building, "farm", "soilCells")
    fun irrigation(building: CivBuilding): List<CivPosition> = if (building.farmFieldVersion == CivsFarmFields.VERSION &&
        CivsFarmFields.supported(building)) CivsFarmFields.water(building) else points(building, "farm", "irrigationCells")
}
