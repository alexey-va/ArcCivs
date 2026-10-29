package ru.arc.civs

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bukkit.Bukkit
import org.bukkit.block.structure.StructureRotation
import java.nio.file.Files
import java.nio.file.Path

/** Stable source-local furniture identity; world placement follows the persisted building transform. */
internal data class CivTavernFurniture(val id: String, val kind: String, val local: CivPosition,
    val facing: String?, val tableId: String, val group: String, val state: String,
    val sourceId: String, val sourceSha256: String)

internal object CivsTavernFurniture {
    fun load(root: Path): Map<String, JsonObject> {
        val file = root.resolve("civs/furniture.json")
        if (!Files.exists(file)) return emptyMap()
        require(Files.isRegularFile(file) && Files.size(file) in 1..4_000_000L) { "Invalid tavern furniture registry size" }
        val document = Files.newBufferedReader(file).use { JsonParser.parseReader(it).asJsonObject }
        require(document["schema"].asInt == 1 && document["coordinateSpace"].asString == "building-local")
        val sites = document.getAsJsonObject("sites").entrySet().also { require(it.size <= 512) }
        return sites.associate { (id, value) ->
            require(id.matches(Regex("[a-zA-Z0-9_ /-]{1,240}")) && !id.startsWith('/') && ".." !in id.split('/'))
            val source = value.asJsonObject
            val records = source.getAsJsonArray("furniture").deepCopy()
            records.forEach { record -> record.asJsonObject.apply {
                if (!has("sourceId")) addProperty("sourceId", source["sourceId"].asString)
                if (!has("sourceSha256")) addProperty("sourceSha256", source["sourceSha256"].asString)
            } }
            validate(records)
            id to JsonObject().apply { add("furniture", records) }
        }
    }

    fun validate(records: JsonArray) {
        require(records.size() in 1..128)
        val rows = records.map { record ->
            val row = record.asJsonObject
            require(row["id"].asString.length in 1..240 && row["kind"].asString in setOf("chair", "table"))
            val point = CivsWorksiteLayouts.vector(row["point"])
            require(listOf(point.x, point.y, point.z).all { it in -512..512 })
            require(row["currentVanillaBlockState"].asString.matches(Regex("minecraft:[a-z0-9_]+(?:\\[[a-z0-9_=,]+])?")))
            require(row["sourceId"].asString.length in 1..240 && row["sourceSha256"].asString.matches(Regex("[0-9a-f]{64}")))
            require(row["tableId"].asString.length in 1..240 && row["group"].asString.length in 1..128)
            if (row["kind"].asString == "chair") require(row["facing"].asString in setOf("north", "east", "south", "west"))
            row
        }
        require(rows.map { it["id"].asString }.distinct().size == rows.size)
        val tables = rows.filter { it["kind"].asString == "table" }.associateBy { it["id"].asString }
        rows.filter { it["kind"].asString == "chair" }.forEach { chair ->
            val table = requireNotNull(tables[chair["tableId"].asString])
            require(chair["group"].asString == table["group"].asString)
        }
    }

    fun records(building: CivBuilding): List<CivTavernFurniture> = CivsWorksiteLayouts.site(building)
        ?.getAsJsonObject("tavern")?.getAsJsonArray("furniture")?.map { element ->
            val row = element.asJsonObject
            CivTavernFurniture(row["id"].asString, row["kind"].asString, CivsWorksiteLayouts.vector(row["point"]),
                row["facing"]?.takeUnless { it.isJsonNull }?.asString, row["tableId"].asString, row["group"].asString,
                row["currentVanillaBlockState"].asString, row["sourceId"].asString, row["sourceSha256"].asString)
        }.orEmpty()

    fun state(building: CivBuilding, record: CivTavernFurniture): String = Bukkit.createBlockData(record.state).apply {
        when (Math.floorMod(building.rotation / 90, 4)) {
            1 -> rotate(StructureRotation.CLOCKWISE_90)
            2 -> rotate(StructureRotation.CLOCKWISE_180)
            3 -> rotate(StructureRotation.COUNTERCLOCKWISE_90)
        }
    }.asString
}
