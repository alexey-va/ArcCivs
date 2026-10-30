package ru.arc.civs

import java.util.UUID

/** New physical tree admission only. Inventory and accepted tree/receipt ownership stay with their existing owners. */
internal class CivsForestrySurplus {
    private val paused = hashSetOf<UUID>()
    private data class Observation(val decision: Decision, val evaluatedAt: Long, val captureStartedAt: Long?)
    private val observations = linkedMapOf<UUID, Observation>()
    fun retain(ids: Set<UUID>) { paused.retainAll(ids); observations.keys.retainAll(ids) }

    /** Last admission decisions only: no inventory, chunk or current-proof callbacks run during debug. */
    fun debug(cityId: UUID?, now: Long): Map<String, Any?> {
        val selected = if (cityId == null) observations.entries.toList() else observations.entries.filter { it.key == cityId }
        return mapOf("source" to "cached-forestry-admission", "freshNativeRead" to false,
            "maximumCities" to MAXIMUM_DIAGNOSTIC_CITIES, "total" to selected.size, "truncated" to (selected.size > 8),
            "entries" to selected.take(8).map { (id, observed) -> mapOf("city" to id,
                "evaluatedAtMillis" to observed.evaluatedAt, "decisionAgeMillis" to (now - observed.evaluatedAt).coerceAtLeast(0),
                "snapshotAgeMillis" to observed.captureStartedAt?.let { (now - it).coerceAtLeast(0) },
                "reason" to observed.decision.reason, "paused" to observed.decision.paused,
                "familyStock" to observed.decision.familyStock, "neededSpecies" to observed.decision.needed.sorted(),
                "uncertainSpecies" to observed.decision.uncertainSpecies.sorted()) })
    }

    internal data class Decision(val familyStock: Long?, val paused: Boolean, val needed: Set<String>, val reason: String,
        val uncertainSpecies: Set<String> = emptySet()) {
        fun blocks(timber: String): Boolean = paused && timber !in needed &&
            CivsCarpentry.timberSpecies(timber) !in uncertainSpecies
    }
    private data class Demand(val needed: Set<String>, val uncertainSpecies: Set<String>)

    fun observe(city: Settlement, snapshot: CivSupplySnapshot?, jobs: Map<UUID, CivCraftJob>, now: Long,
        current: (CivSupplyCaptureResult) -> Boolean): Decision {
        fun remember(decision: Decision): Decision {
            observations.remove(city.id)
            observations[city.id] = Observation(decision, now,
                (snapshot?.inputKey as? CivSupplyObservation)?.capture?.startedAtMillis)
            if (observations.size > MAXIMUM_DIAGNOSTIC_CITIES) observations.remove(observations.keys.first())
            return decision
        }
        fun unknown(reason: String): Decision {
            // Unknown stock cannot start or release a latch. Current authored demand may only loosen its exact species.
            val requested = demand(city, emptyMap(), jobs)
            return remember(Decision(null, city.id in paused, requested.needed, reason, requested.uncertainSpecies))
        }
        val mode = city.inventory?.mode ?: if (city.autonomy != null) CivStorageMode.PHYSICAL else null
        if (mode != CivStorageMode.PHYSICAL || city.pendingDeletion != null) {
            paused -= city.id
            return remember(Decision(null, false, emptySet(), "not-physical"))
        }
        val evidence = snapshot?.inputKey as? CivSupplyObservation ?: return unknown("observation-missing")
        val started = evidence.capture.startedAtMillis ?: return unknown("capture-age-missing")
        val native = evidence.capture.plainStock ?: return unknown("unique-stock-missing")
        if (evidence.capture.cityId != city.id || snapshot.cityId != city.id) return unknown("observation-city-mismatch")
        if (!evidence.capture.complete) return unknown("capture-incomplete")
        if (evidence.inputs != civSupplyInputs(city)) return unknown("supply-inputs-changed")
        if (evidence.capture.stores.values.any { it.unavailable != 0 }) return unknown("holder-unavailable")
        if (!CivsAutoSupplies.observed(city, snapshot)) return unknown("capture-stores-incomplete")
        if (now - started !in 0..MAXIMUM_AGE_MILLIS) return unknown("capture-age-invalid")
        if (!current(evidence.capture)) return unknown("capture-not-current")
        if (city.workers.any { it.cargo?.let { cargo -> !cargo.inventoryBacked || cargo.stockResource != null } == true })
            return unknown("cargo-migration-pending")
        val holdings = native.toMutableMap()
        // Durable carried cargo is already present in these exact plain bag slots. Unpicked cargo is still native stock.
        city.workers.forEach { worker -> CivsEquipment.slots(worker).filterNotNull().filter { it.data == null }.forEach {
            holdings.merge(it.material, it.amount.toLong(), Math::addExact)
        } }
        val family = holdings.filterKeys(CivsCarpentry::timberFamily).values.fold(0L, Math::addExact)
        val requested = demand(city, holdings, jobs)
        val stop = if (city.id in paused) family > RESUME_AT else family >= STOP_AT
        if (stop) paused += city.id else paused -= city.id
        return remember(Decision(family, stop, requested.needed, if (stop) "timber-family-surplus" else "below-surplus",
            requested.uncertainSpecies))
    }

    /** Expand only species-specific requirements through the pinned recipe catalog. Generic wood inputs
     * can use the existing family buffer; an unsupported recipe exempts only its exact species. */
    private fun demand(city: Settlement, holdings: Map<String, Long>, jobs: Map<UUID, CivCraftJob>): Demand {
        val required = linkedMapOf<String, Long>()
        fun add(material: String, count: Long) { if (count > 0) required.merge(material, count, Math::addExact) }
        city.construction?.materials?.let(CivsMaterials::unpaid)?.forEach(::add)
        city.buildings.filter { it.phase == BuildingPhase.ACTIVE && it.type != BuildingType.FORESTER &&
            it.type !in CivsCarpentry.workshops && CivsRules.productionAvailable(city, it) }.forEach { building ->
            val workers = city.workers.count { it.assignment == building.id && it.cargo == null && !CivsResidents.isChild(it) }
            CivsProduction.inputs(city, building).forEach { (material, count) -> add(material, Math.multiplyExact(count, workers.toLong())) }
        }
        jobs.values.forEach { job ->
            val worker = city.workers.firstOrNull { it.id == job.workerId } ?: return@forEach
            if (CivsCarpentry.valid(city, worker, job)) job.inputs.forEach(::add)
        }
        val available = holdings.toMutableMap()
        val needed = linkedSetOf<String>()
        val uncertain = linkedSetOf<String>()
        fun requireMaterial(material: String, count: Long, depth: Int) {
            if (count <= 0) return
            val used = minOf(count, available[material] ?: 0L)
            available[material] = (available[material] ?: 0L) - used
            val missing = count - used
            if (missing <= 0) return
            if (CivsCarpentry.timberFamily(material) && !material.startsWith("STRIPPED_") &&
                (material.endsWith("_LOG") || material.endsWith("_STEM"))) { needed += material; return }
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
        return Demand(needed, uncertain)
    }

    companion object {
        const val STOP_AT = 512L
        const val RESUME_AT = 256L
        const val MAXIMUM_AGE_MILLIS = 15_000L
        private const val MAXIMUM_DIAGNOSTIC_CITIES = 128
    }
}
