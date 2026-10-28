package ru.arc.civs

import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.entity.Item
import java.util.UUID

/** Native endpoints for a single resident's existing inventory receipt. */
data class CivGroundItemChange(val entityId: UUID, val position: CivPosition,
    val before: CivResidentItem, val after: CivResidentItem?)
data class CivNativeContainerChange(val container: CivPosition, val containerType: String, val slot: Int,
    val before: CivResidentItem?, val after: CivResidentItem?)
data class CivCollectedTransfer(val workerId: UUID, val before: List<CivResidentItem?>,
    val ground: List<CivGroundItemChange> = emptyList(), val homeId: UUID? = null,
    val containers: List<CivNativeContainerChange> = emptyList(), val food: CivResidentFoodReceipt? = null) {
    fun validated(city: Settlement) {
        require(ground.size <= 8 && containers.size <= 108)
        food?.let {
            CivsEquipment.validate(before)
            require(containers.map { change -> change.container to change.slot }.distinct().size == containers.size)
            it.validated(city, this)
            return
        }
        require(ground.isNotEmpty() && homeId == null && containers.isEmpty() ||
            ground.isEmpty() && homeId != null && containers.isNotEmpty())
        CivsEquipment.validate(before)
        val worker = city.workers.single { it.id == workerId }
        val after = CivsEquipment.slots(worker)
        CivsEquipment.validate(after)
        require(before.take(CivsEquipment.BAG) == after.take(CivsEquipment.BAG))
        require(ground.map { it.entityId }.distinct().size == ground.size)
        require(containers.map { it.container to it.slot }.distinct().size == containers.size)
        val delta = mutableMapOf<Pair<String, String?>, Long>()
        fun count(item: CivResidentItem?, sign: Long) {
            item?.validated()?.let { delta.merge(it.material to it.data, sign * it.amount, Long::plus) }
        }
        before.forEach { count(it, -1) }; after.forEach { count(it, 1) }
        ground.forEach { change ->
            require(change.position.y in -4096..4096 && city.contains(change.position.x, change.position.z))
            require(change.after == null || change.after.material == change.before.material &&
                change.after.data == change.before.data && change.after.amount < change.before.amount)
            count(change.before, -1); count(change.after, 1)
        }
        containers.forEach { change ->
            val home = city.building(requireNotNull(homeId)) ?: error("Missing home")
            require(home.type.isResidence || home.type == BuildingType.TOWN_HALL)
            require(home.id == civsCollectionHome(city, worker)?.id)
            require(change.containerType in CIVS_STORAGE_TYPES && change.slot in 0..26 && change.before != change.after)
            require(change.container == home.control || home.phase == BuildingPhase.ACTIVE &&
                home.bounds.contains(change.container.x, change.container.y, change.container.z))
            require(city.buildings.none { it.id != home.id && it.control == change.container })
            require(change.container != home.control || change.containerType == "BARREL")
            val target = requireNotNull(change.after)
            require(change.before == null || change.before.material == target.material && change.before.data == target.data &&
                change.before.amount < target.amount)
            count(change.before, -1); count(change.after, 1)
        }
        require(delta.values.all { it == 0L }) { "Collected transfer must conserve exact native items" }
        if (ground.isNotEmpty()) require(worker.hasCollectedItems == true)
    }
}

internal data class CivCollectedPlan(val worker: CivWorker, val transfer: CivCollectedTransfer?, val stock: Stock? = null)
data class CivCollectedHome(val homeId: UUID, val container: CivPosition?, val surplus: Boolean)

internal fun civsCollectionHome(city: Settlement, worker: CivWorker): CivBuilding? =
    worker.homeId?.let(city::building)?.takeIf { it.type.isResidence || it.type == BuildingType.TOWN_HALL }
        ?: city.buildings.firstOrNull { it.type == BuildingType.TOWN_HALL }

internal fun civsCanReachContainer(feet: Location, point: CivPosition): Boolean {
    val world = feet.world ?: return false
    return civsCorpseStandingSafe(feet) && citizenChunkReady(world, point.x shr 4, point.z shr 4) &&
        citizenCanReachBlock(world, feet.clone().add(0.0, 1.5, 0.0), point, 3.0)
}

internal fun civsGroundItemAvailable(item: Item, workerId: UUID): Boolean = item.isValid && !item.isDead &&
    item.isOnGround && item.pickupDelay <= 0 && item.canMobPickup() && item.canPlayerPickup() &&
    (item.owner == null || item.owner == workerId) && item.itemStack.amount > 0 &&
    !item.itemStack.type.isAir && citizenChunkReady(item.world, item.location.blockX shr 4, item.location.blockZ shr 4)

/** A short real reach and clear eye ray; no ground query ever loads a chunk or collects an airborne item. */
internal fun civsCanCollect(feet: Location, item: Item, workerId: UUID, reach: Double = 2.0): Boolean {
    val world = feet.world ?: return false
    if (item.world.uid != world.uid || !civsGroundItemAvailable(item, workerId) ||
        feet.distanceSquared(item.location) > reach * reach || !civsCorpseStandingSafe(feet)) return false
    val eye = feet.clone().add(0.0, 1.5, 0.0)
    val target = item.location.clone().add(0.0, .1, 0.0)
    val direction = target.toVector().subtract(eye.toVector())
    val distance = direction.length()
    return distance < .01 || world.rayTraceBlocks(eye, direction.normalize(), distance,
        FluidCollisionMode.ALWAYS, true) == null
}
