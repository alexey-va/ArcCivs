package ru.arc.civs

/** Source slots and the recipient bag are one immutable debit/credit receipt, independently of actor lifetime. */
data class CivCargoPickupReceipt(
    val workerBefore: CivWorker,
    val cargo: CivCargo,
    val source: CivBuilding,
    val target: CivBuilding,
    val containers: List<CivCargoContainerState>,
) {
    fun before(after: Settlement): Settlement = after.copy(
        workers = after.workers.map { if (it.id == workerBefore.id) workerBefore else it }, revision = after.revision - 1)

    fun validated(after: Settlement, changes: List<CivInventorySlotChange>) {
        val before = before(after)
        require(workerBefore.cargo == null && CivsLogistics.redistributionAllowed(before, cargo))
        require(source == before.building(cargo.sourceBuildingId) && target == before.building(cargo.targetBuildingId))
        val positions = containers.map(CivCargoContainerState::position)
        require(changes.size in 1..64 && positions.size == positions.distinct().size &&
            positions.toSet() == changes.mapTo(hashSetOf()) { it.container })
        require(containers.all { it.blockData.length in 1..2_048 } &&
            positions.map { (it.x shr 4) to (it.z shr 4) }.distinct().size <= 8)
        var removed = 0L
        changes.forEach { change ->
            require(change.container == source.control || source.bounds.contains(change.container.x, change.container.y, change.container.z))
            require(before.buildings.none { it.id != source.id && it.control == change.container })
            val item = requireNotNull(change.before)
            require(item.material == cargo.material && (change.after == null ||
                change.after.material == item.material && change.after.amount < item.amount))
            removed = Math.addExact(removed, (item.amount - (change.after?.amount ?: 0)).toLong())
        }
        require(removed == cargo.amount && changes.any { it.after == null }) {
            "Depot evacuation must conserve its exact batch and release a source slot"
        }
        require(CivsLogistics.pickup(before, workerBefore.id, cargo) == after)
    }

    internal fun detached(): CivCargoPickupReceipt = copy(
        workerBefore = workerBefore.copy(items = workerBefore.items?.toList(), inventory = workerBefore.inventory?.toMap()),
        source = source.copy(transit = source.transit?.toList()),
        target = target.copy(transit = target.transit?.toList()), containers = containers.toList())
}
