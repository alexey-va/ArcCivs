package ru.arc.civs

data class CivCargoContainerState(val position: CivPosition, val blockData: String)

/** Arrival is checked before acceptance. Recovery owns this exact native credit even if the actor later disappears. */
data class CivCargoDropoffReceipt(
    val workerBefore: CivWorker,
    val destination: CivBuilding,
    val acceptedAt: Long,
    val containers: List<CivCargoContainerState>,
) {
    fun validated(after: Settlement, changes: List<CivInventorySlotChange>) {
        require(after.inventory?.mode == CivStorageMode.PHYSICAL && acceptedAt >= 0)
        val cargo = requireNotNull(workerBefore.cargo)
        require(cargo.inventoryBacked && cargo.stockResource == null && CivsLogistics.validCargo(after, cargo))
        require(after.building(destination.id) == destination && destination.control != null &&
            CivsLogistics.destination(after, cargo)?.id == destination.id)
        val positions = containers.map(CivCargoContainerState::position)
        require(changes.size in 1..64 && positions.size == positions.distinct().size &&
            positions.toSet() == changes.mapTo(hashSetOf()) { it.container })
        require(containers.all { it.blockData.length in 1..2_048 })
        require(positions.map { (it.x shr 4) to (it.z shr 4) }.distinct().size <= 8)
        var added = 0L
        changes.forEach { change ->
            require(change.container == destination.control || destination.phase == BuildingPhase.ACTIVE &&
                destination.bounds.contains(change.container.x, change.container.y, change.container.z))
            require(after.buildings.none { it.id != destination.id && it.control == change.container })
            val item = requireNotNull(change.after)
            require(item.material == cargo.material && (change.before == null ||
                change.before.material == cargo.material && change.before.amount < item.amount))
            added = Math.addExact(added, (item.amount - (change.before?.amount ?: 0)).toLong())
        }
        require(added == cargo.amount) { "Cargo delivery must conserve its exact accepted batch" }
        val before = after.copy(workers = after.workers.map { if (it.id == workerBefore.id) workerBefore else it },
            revision = after.revision - 1)
        require(CivsLogistics.unload(before, workerBefore.id, cargo.id, acceptedAt) == after)
    }

    internal fun detached(): CivCargoDropoffReceipt = copy(
        workerBefore = workerBefore.copy(items = workerBefore.items?.toList(), inventory = workerBefore.inventory?.toMap()),
        destination = destination.copy(transit = destination.transit?.toList()), containers = containers.toList())
}
