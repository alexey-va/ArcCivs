package ru.arc.civs

import com.google.gson.JsonParser

/** Closed Auto construction accounting. This projection never creates extractable items. */
internal object CivsDecorationCost {
    private val timber: Set<String> by lazy {
        val input = checkNotNull(javaClass.getResourceAsStream("/decoration-wood-materials.json"))
        val root = input.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        require(root["version"].asInt == 1 && root["minecraftVersion"].asString == "1.21.11")
        root.getAsJsonArray("woodMaterials").mapTo(hashSetOf()) { it.asString }
    }

    /** The saved native material bill is authoritative on replay, not today's catalog. */
    fun cost(materials: Map<String, Long>): Stock {
        CivInventory(CivStorageMode.VIRTUAL, materials).validated()
        var wood = 0L
        var stone = 0L
        for ((material, amount) in materials) {
            if (material in timber) wood = Math.addExact(wood, amount) else stone = Math.addExact(stone, amount)
        }
        return Stock(wood, stone, 0)
    }
}
