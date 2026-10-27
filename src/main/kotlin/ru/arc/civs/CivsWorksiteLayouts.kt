package ru.arc.civs

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Authored, pinned work areas; live terrain is still checked before every action. */
internal object CivsWorksiteLayouts {
    @Volatile private var catalogSites: Map<String, JsonObject> = emptyMap()
    @Volatile private var catalogFields: Map<String, Set<Pair<Int, Int>>> = emptyMap()

    /** Called once by the async catalog owner before resident/city recovery starts. */
    fun installCatalog(values: Map<String, JsonObject>) {
        val detached = values.mapValues { it.value.deepCopy() }
        catalogFields = detached.mapValues { (_, site) ->
            val farm = site.getAsJsonObject("farm")
            listOf("soilCells", "irrigationCells").flatMap { key -> farm?.getAsJsonArray(key)?.map {
                vector(it).let { p -> p.x to p.z }
            }.orEmpty() }.toSet()
        }
        catalogSites = detached
    }
    private val sites: Map<String, JsonObject> by lazy {
        val input = checkNotNull(javaClass.getResourceAsStream("/worksites.json")) { "Missing worksite layouts" }
        val root = input.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        require(root["version"].asInt == 1)
        root.getAsJsonObject("sites").entrySet().associate { it.key to it.value.asJsonObject }
    }

    private val fieldColumns by lazy { sites.mapValues { (_, site) ->
        val farm = site.getAsJsonObject("farm")
        listOf("soilCells", "irrigationCells").flatMap { key -> farm?.getAsJsonArray(key)?.map {
            vector(it).let { p -> p.x to p.z }
        }.orEmpty() }.toHashSet()
    } }

    fun site(building: CivBuilding): JsonObject? = catalogSites[building.schematicId] ?: sites[building.schematicId]

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

    fun vector(value: com.google.gson.JsonElement): CivPosition = value.asJsonArray.let {
        require(it.size() == 3)
        CivPosition(it[0].asInt, it[1].asInt, it[2].asInt)
    }

    fun points(building: CivBuilding, section: String, key: String): List<CivPosition> =
        site(building)?.getAsJsonObject(section)?.getAsJsonArray(key)?.map { point(building, vector(it)) }.orEmpty()

    /** Local field query; preserves the complete authored field including its water/hay center. */
    fun isField(building: CivBuilding, x: Int, z: Int): Boolean {
        val cells = (catalogFields[building.schematicId] ?: fieldColumns[building.schematicId])?.takeIf { it.isNotEmpty() } ?: return false
        val dx = x - building.anchor.x
        val dz = z - building.anchor.z
        val (localX, localZ) = when (Math.floorMod(building.rotation / 90, 4)) {
            0 -> dx to dz; 1 -> dz to -dx; 2 -> -dx to -dz; else -> -dz to dx
        }
        return (localX to localZ) in cells
    }

    fun field(building: CivBuilding): List<CivPosition> = points(building, "farm", "soilCells")
    fun irrigation(building: CivBuilding): List<CivPosition> = points(building, "farm", "irrigationCells")
}
