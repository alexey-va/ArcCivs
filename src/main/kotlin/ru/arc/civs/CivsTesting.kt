package ru.arc.civs

import java.util.UUID

/** Explicit testing issuance uses real bag capacity and the current captured construction row. */
internal data class CivTestingConstructionGrant(val workers: List<CivWorker>, val issued: Map<UUID, Map<String, Long>>)

internal fun civsTestingConstructionGrant(city: Settlement, required: Map<String, Long>,
    available: Map<String, Long>): CivTestingConstructionGrant? {
    val order = city.construction ?: return null
    if (order.payment == null || order.payment.reservedBatch != null) return null
    var missing = required.mapValues { (material, amount) ->
        (amount - available.getOrDefault(material, 0)).coerceAtLeast(0)
    }.filterValues { it > 0 }
    if (missing.isEmpty()) return null
    val issued = linkedMapOf<UUID, Map<String, Long>>()
    val builders = CivsRules.constructionWorkers(city, order.buildingId).mapTo(hashSetOf(), CivWorker::id)
    val workers = city.workers.map { worker ->
        if (worker.id !in builders || missing.isEmpty()) worker else {
            val (updated, moved) = CivsEquipment.grantSupplies(worker, missing)
            if (moved.isNotEmpty()) {
                issued[worker.id] = moved
                missing = missing.mapValues { (material, amount) -> amount - moved.getOrDefault(material, 0) }.filterValues { it > 0 }
            }
            updated
        }
    }
    return if (issued.isEmpty()) null else CivTestingConstructionGrant(workers, issued)
}
