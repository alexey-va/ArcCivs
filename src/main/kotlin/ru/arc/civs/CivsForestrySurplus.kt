package ru.arc.civs

/** The shared order review expands species-specific bills through the pinned carpentry catalogue. */
internal object CivsForestrySurplus {
    const val STOP_AT = 512L
    const val RESUME_AT = 256L
    const val MAXIMUM_AGE_MILLIS = 15_000L

    data class Decision(val familyStock: Long, val paused: Boolean, val neededUnits: Map<String, Long>,
        val uncertainSpecies: Set<String>)

    fun decide(holdings: Map<String, Long>, required: Map<String, Long>, wasPaused: Boolean, complete: Boolean,
        craftOutputs: Map<String, Long> = emptyMap()): Decision {
        val available = holdings.toMutableMap()
        craftOutputs.forEach { (material, count) -> available.merge(material, count, Math::addExact) }
        val needed = linkedMapOf<String, Long>()
        val uncertain = linkedSetOf<String>()
        fun requireMaterial(material: String, count: Long, depth: Int) {
            if (count <= 0) return
            val used = minOf(count, available[material] ?: 0L)
            available[material] = (available[material] ?: 0L) - used
            val missing = count - used
            if (missing <= 0) return
            if (CivsCarpentry.timberFamily(material) && !material.startsWith("STRIPPED_") &&
                (material.endsWith("_LOG") || material.endsWith("_STEM"))) { needed.merge(material, missing, Math::addExact); return }
            val species = CivsCarpentry.timberSpecies(material) ?: return
            if (depth >= 8) { uncertain += species; return }
            val recipes = CivsCarpentry.recipes.filter { it.material == material }
            val recipe = recipes.minWithOrNull(compareBy<CivCraftRecipe> { recipe ->
                val batches = (missing - 1) / recipe.amount + 1
                recipe.inputs.entries.sumOf { (input, amount) ->
                    (Math.multiplyExact(amount, batches) - (available[input] ?: 0L)).coerceAtLeast(0)
                }
            }.thenBy { it.id })
            if (recipe == null || recipe.inputs.keys.none { CivsCarpentry.timberSpecies(it) == species }) {
                uncertain += species; return
            }
            val batches = (missing - 1) / recipe.amount + 1
            recipe.inputs.forEach { (input, amount) -> requireMaterial(input, Math.multiplyExact(amount, batches), depth + 1) }
        }
        required.toSortedMap().forEach { (material, count) -> requireMaterial(material, count, 0) }
        val family = holdings.entries.filter { CivsCarpentry.timberFamily(it.key) }.sumOf { it.value }
        val paused = if (!complete) wasPaused else if (wasPaused) family > RESUME_AT else family >= STOP_AT
        return Decision(family, paused, needed, uncertain)
    }
}
