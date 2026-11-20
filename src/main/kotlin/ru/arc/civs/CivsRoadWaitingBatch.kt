package ru.arc.civs

import java.util.UUID

/** A spare builder's carried debit is bound to the exact unpaid construction row. */
data class CivRoadWaitingBatch(val orderId: UUID, val batch: Int) {
    internal fun admits(crew: CivRoadWaitingCrew?, worker: CivWorker, work: CivWorldWork): Boolean {
        val target = work.upkeep ?: return false
        val amount = work.inputs["COBBLESTONE"] ?: return false
        return crew?.batch == this && target.waitingBatch == this && target.waitingRepair &&
            (!target.preparedCentre || work.changes.any { it.position == target.floor && it.before == "minecraft:coarse_dirt" }) &&
            worker.id == work.workerId && worker.completedCycles == work.cycle && worker.assignment == work.buildingId &&
            work.inputs.size == 1 && amount in 1L..(crew.carried[worker.id]?.toLong() ?: 0L)
    }
}

/** One projection per immutable waiting city. Construction pays from these same cargo-free builders' plain bag slots. */
internal class CivRoadWaitingCrew(val batch: CivRoadWaitingBatch, val carried: Map<UUID, Int>) {
    fun available(accepts: (UUID) -> Boolean): UUID? {
        var chosen: UUID? = null
        var best = 0
        for ((id, amount) in carried) if (amount > best && accepts(id)) {
            chosen = id
            best = amount
        }
        return chosen
    }

    companion object {
        fun from(city: Settlement): CivRoadWaitingCrew? {
            val order = city.construction ?: return null
            val payment = order.payment ?: return null
            val bill = payment.awaiting ?: return null
            if (city.autonomy?.paused != false || city.inventory?.mode != CivStorageMode.PHYSICAL ||
                city.pendingDeletion != null || !city.constructionQueue.isNullOrEmpty() || !city.deferredConstruction.isNullOrEmpty() ||
                order.phase != ConstructionPhase.WAITING_MATERIALS || order.plannedBuilding == null ||
                payment.reservedBatch != null || "COBBLESTONE" in bill) return null
            val missing = CivsMaterials.unpaid(bill).toMutableMap()
            if (missing.isEmpty()) return null
            val carried = mutableMapOf<UUID, Int>()
            var builders = 0
            for (worker in CivsRules.constructionWorkers(city, order.buildingId)) {
                var stone = 0
                var supplying = false
                for (item in CivsEquipment.slots(worker)) {
                    if (item == null || item.data != null) continue
                    if (item.material == "COBBLESTONE") stone += item.amount
                    else if (item.material != "WATER_BUCKET" && item.material in bill) {
                        supplying = true
                        val needed = missing[item.material] ?: continue
                        if (needed > item.amount) missing[item.material] = needed - item.amount
                        else {
                            missing.remove(item.material)
                            if (missing.isEmpty()) return null
                        }
                    }
                }
                if (worker.pendingStock != null || worker.hasCollectedItems == true ||
                    worker.assignment?.let(city::building)?.phase != BuildingPhase.ACTIVE) continue
                builders++
                if (!supplying && stone > 0) carried[worker.id] = stone
            }
            return if (builders >= 2 && carried.isNotEmpty()) CivRoadWaitingCrew(CivRoadWaitingBatch(order.id, order.workDone), carried) else null
        }
    }
}

/** Native and worker observations belong to this network and exact accepted site. Main thread only. */
internal class CivRoadWaitingPass(val site: CivConstructionSite, targets: List<CivRoadUpkeep>) {
    val remaining = ArrayDeque(targets)
    var workerId: UUID? = null
    private var snapshot: Settlement? = null
    private var crew: CivRoadWaitingCrew? = null

    fun crew(city: Settlement): CivRoadWaitingCrew? {
        if (snapshot !== city) {
            snapshot = city
            crew = CivRoadWaitingCrew.from(city)
        }
        return crew
    }
}
