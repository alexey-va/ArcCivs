package ru.arc.civs

/** Exact native slots only; relocation never changes a building, worker, quota or transferable stock. */
data class CivRelocatedContainer(val position: CivPosition, val type: String, val blockData: String,
    val before: List<CivResidentItem?>, val after: List<CivResidentItem?>)

data class CivContainerRelocation(val building: CivBuilding, val containers: List<CivRelocatedContainer>) {
    fun validated(city: Settlement) {
        require(city.building(building.id) == building && building.phase == BuildingPhase.ACTIVE &&
            !building.type.isInfrastructure && city.pendingDeletion == null)
        require(containers.size in 2..MAX_CONTAINERS && containers.map { it.position }.distinct().size == containers.size)
        require(containers.map { (it.position.x shr 4) to (it.position.z shr 4) }.distinct().size <= 8)
        val delta = mutableMapOf<Pair<String, String?>, Long>()
        fun count(item: CivResidentItem?, sign: Long) {
            item?.validated()?.let { delta.merge(it.material to it.data, sign * it.amount, Math::addExact) }
        }
        containers.forEach { holder ->
            require(holder.type in CIVS_STORAGE_TYPES && storagePositionAllowed(city, holder.position, holder.type) &&
                (holder.position == building.control || building.bounds.contains(holder.position.x, holder.position.y, holder.position.z)) &&
                city.buildings.none { it.id != building.id && it.control == holder.position })
            require(holder.blockData.substringBefore('[') == "minecraft:${holder.type.lowercase()}" &&
                holder.blockData.length in 1..CivsStore.MAX_BLOCK_STATE_CHARS)
            require(holder.before.size == STORAGE_SLOTS && holder.after.size == STORAGE_SLOTS)
            if (holder.after.any { it != null }) {
                require(civsRetainContainerState(holder.blockData))
                holder.before.zip(holder.after).forEach { (before, after) ->
                    require(before == null || after != null && before.material == after.material && before.data == after.data &&
                        before.amount <= after.amount)
                }
            }
            holder.before.forEach { count(it, -1) }; holder.after.forEach { count(it, 1) }
        }
        require(containers.any { it.before.any { item -> item != null } && it.after.all { item -> item == null } })
        require(changes().isNotEmpty() && delta.values.all { it == 0L }) { "Container relocation must conserve exact native items" }
    }

    fun changes(): List<CivNativeContainerChange> = containers.flatMap { holder -> holder.before.indices.mapNotNull { slot ->
        if (holder.before[slot] == holder.after[slot]) null else
            CivNativeContainerChange(holder.position, holder.type, slot, holder.before[slot], holder.after[slot])
    } }

    companion object { const val MAX_CONTAINERS = 32 }
}

private val retainedChest = Regex("minecraft:(?:trapped_)?chest\\[facing=(north|south|east|west),type=(single|left|right),waterlogged=false]")
private val retainedBarrel = Regex("minecraft:barrel\\[facing=(north|south|east|west|up|down),open=(true|false)]")
internal fun civsRetainContainerState(state: String): Boolean = retainedChest.matches(state) || retainedBarrel.matches(state)
internal fun civsRetainedContainer(cell: CivPlanBlock): Boolean = cell.before == cell.after && civsRetainContainerState(cell.before)

/** A barrel viewer may close after journal admission; only that canonical presentation bit may drift. */
internal fun civsSameRelocationContainerState(actual: String, expected: String): Boolean {
    if (actual == expected) return true
    val before = retainedBarrel.matchEntire(expected) ?: return false
    val after = retainedBarrel.matchEntire(actual) ?: return false
    return before.groupValues[1] == after.groupValues[1]
}

/** Upgrade orders retain the old ACTIVE building until completion; its old holders remain debit sources only. */
internal fun civsStorageUpgrade(city: Settlement): CivConstruction? = city.construction?.takeIf { order ->
    city.building(order.buildingId)?.let { old -> old.phase == BuildingPhase.ACTIVE && !old.type.isInfrastructure &&
        order.targetLevel == old.level + 1 } == true
}

internal fun civsNativeContainerAcceptsInsert(city: Settlement, position: CivPosition): Boolean {
    if (city.buildings.any { it.control == position }) return true
    val old = civsStorageUpgrade(city)?.let { city.building(it.buildingId) } ?: return true
    return !old.bounds.contains(position.x, position.y, position.z)
}

private fun relocationChestPartner(position: CivPosition, state: String): CivPosition? {
    val chest = retainedChest.matchEntire(state) ?: return null
    if (chest.groupValues[2] == "single") return null
    val sign = if (chest.groupValues[2] == "left") 1 else -1
    val (x, z) = when (chest.groupValues[1]) {
        "north" -> 0 to -1; "south" -> 0 to 1; "east" -> 1 to 0; else -> -1 to 0
    }
    return position.copy(x = position.x - z * sign, z = position.z + x * sign)
}

/** Detached all-or-nothing packing; native holders stay untouched until the existing receipt is durable. */
internal fun civsPlanContainerRelocation(building: CivBuilding, target: CivBuilding,
    future: Map<CivPosition, String>, holders: List<CivRelocatedContainer>): CivContainerRelocation? {
    require(holders.size <= CivContainerRelocation.MAX_CONTAINERS)
    val byPosition = holders.associateBy { it.position }
    val retained = holders.filter { holder ->
        if (holder.position == building.control) return@filter holder.position == target.control && holder.type == "BARREL"
        if (!civsRetainContainerState(holder.blockData) || future[holder.position] != holder.blockData) return@filter false
        val chest = retainedChest.matchEntire(holder.blockData) ?: return@filter true
        val part = chest.groupValues[2]
        if (part == "single") return@filter true
        val partner = relocationChestPartner(holder.position, holder.blockData)?.let(byPosition::get)
        partner != null && partner.type == holder.type && relocationChestPartner(partner.position, partner.blockData) == holder.position &&
            retainedChest.matchEntire(partner.blockData)?.groupValues?.get(1) == chest.groupValues[1] &&
            future[partner.position] == partner.blockData
    }.mapTo(hashSetOf(), CivRelocatedContainer::position)
    val sources = holders.filter { it.position !in retained && it.before.any { item -> item != null } }
    if (sources.isEmpty()) return null
    val destinations = holders.filter { it.position in retained }
    val slots = destinations.associate { it.position to it.before.toMutableList() }
    for (source in sources) for (item in source.before.filterNotNull()) {
        var remaining = item.amount
        val maximum = item.stack().maxStackSize
        for (empty in listOf(false, true)) for (destination in destinations) for (slot in 0 until STORAGE_SLOTS) {
            val values = slots.getValue(destination.position)
            val current = values[slot]
            if (remaining == 0 || (current == null) != empty || current != null &&
                (current.material != item.material || current.data != item.data)) continue
            val moved = minOf(remaining, maximum - (current?.amount ?: 0))
            if (moved <= 0) continue
            values[slot] = item.copy(amount = (current?.amount ?: 0) + moved)
            remaining -= moved
        }
        if (remaining != 0) throw CivsRejection("storage-full")
    }
    val sourcePositions = sources.mapTo(hashSetOf(), CivRelocatedContainer::position)
    return CivContainerRelocation(building, holders.map { holder -> holder.copy(after = when (holder.position) {
        in sourcePositions -> List(STORAGE_SLOTS) { null }
        in retained -> slots.getValue(holder.position).toList()
        else -> holder.before
    }) })
}
