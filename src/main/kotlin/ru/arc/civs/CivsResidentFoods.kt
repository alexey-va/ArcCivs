package ru.arc.civs

import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

data class CivResidentFoodDefinition(val nutrition: Int, val consumeMillis: Long = 1_600) {
    init { require(nutrition in 1..20 && consumeMillis in 1..10_000) }
}

/** Immutable native defaults captured once on Main. Domain transitions never read Bukkit registries. */
class CivsResidentFoods(definitions: Map<String, CivResidentFoodDefinition>) {
    private val definitions = definitions.toMap()
    fun definition(material: String): CivResidentFoodDefinition? = definitions[material]

    fun available(city: Settlement, worker: CivWorker, material: String): Int {
        if (material !in definitions) return 0
        val reserve = if (material == "BREAD") 0 else CivsLogistics.workerWorkReserve(city, worker)[material] ?: 0
        val bag = CivsEquipment.slots(worker).drop(CivsEquipment.BAG).filterNotNull()
            .filter { it.material == material && it.data == null }.sumOf { it.amount }
        return minOf(bag, (CivsEquipment.usableCount(worker, material).toLong() - reserve).coerceAtLeast(0).toInt())
    }

    fun carried(city: Settlement, worker: CivWorker): String? = CivsEquipment.slots(worker).drop(CivsEquipment.BAG)
        .firstOrNull { it != null && it.data == null && available(city, worker, it.material) > 0 }?.material

    fun mayPickUp(city: Settlement, worker: CivWorker, material: String): Boolean = material in definitions &&
        (material == "BREAD" || (CivsLogistics.workerWorkReserve(city, worker)[material] ?: 0) == 0L)

    /** The existing sixteen-food personal reserve also covers supported ordinary food in the bag. */
    internal fun personalReserve(worker: CivWorker): Map<String, Long> = buildMap {
        put("BREAD", 16L)
        CivsEquipment.slots(worker).drop(CivsEquipment.BAG).filterNotNull().forEach {
            if (it.data == null && it.material in definitions) put(it.material, 16L)
        }
    }

    companion object {
        val BREAD_ONLY = CivsResidentFoods(mapOf("BREAD" to CivResidentFoodDefinition(6)))

        /** No custom components, status/teleport effects or unhandled bowl/bottle remainder. */
        fun native(): CivsResidentFoods {
            check(Bukkit.isPrimaryThread())
            val definitions = linkedMapOf("BREAD" to CivResidentFoodDefinition(6))
            for (material in Material.entries) {
                if (material.isLegacy || !material.isItem) continue
                fromNative(ItemStack(material))?.let { definitions[material.name] = it }
            }
            return CivsResidentFoods(definitions)
        }

        internal fun fromNative(stack: ItemStack): CivResidentFoodDefinition? {
            val food = stack.getData(DataComponentTypes.FOOD) ?: return null
            val consume = stack.getData(DataComponentTypes.CONSUMABLE) ?: return null
            if (food.nutrition() !in 1..20 || consume.animation() != ItemUseAnimation.EAT ||
                consume.consumeEffects().isNotEmpty() || stack.hasData(DataComponentTypes.USE_REMAINDER)) return null
            return CivResidentFoodDefinition(if (stack.type == Material.BREAD) 6 else food.nutrition(),
                (consume.consumeSeconds() * 1_000).toLong().coerceIn(1, 10_000))
        }
    }
}
