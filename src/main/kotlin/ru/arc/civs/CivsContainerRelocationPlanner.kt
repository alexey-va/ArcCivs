package ru.arc.civs

import org.bukkit.Material
import org.bukkit.World
import java.util.UUID

private const val MAX_UPGRADE_HOLDER_HEADERS = 128

/** Captures native slots and plans their relocation for construction, upgrades and control moves. */
internal class CivsContainerRelocationPlanner(
    private val containers: CivsStorageContainers,
    private val findWorld: (UUID) -> World?,
    private val checkClaim: (Settlement) -> Unit,
    private val canModify: (Settlement, CivPosition) -> Boolean,
) {
    /** Inputs are the approved plan's changed holder cells. Read exactly one native half per durable batch. */
    internal fun prepareConstructionStorage(city: Settlement, target: CivBuilding,
        cells: List<CivPlanBlock>): CivConstructionStorageBatch? {
        checkClaim(city)
        val building = requireNotNull(city.building(target.id))
        val registered = requireNotNull(city.inventory).containers
        val world = findWorld(city.worldId) ?: throw CivsRejection("storage-unavailable")
        for (cell in cells) {
            val point = cell.position
            require(civsStorageChangingCell(cell))
            if (!civsConstructionStorageOwns(city, building, target, point)) continue
            reject(!world.isChunkLoaded(point.x shr 4, point.z shr 4), "storage-unavailable")
            val block = world.getBlockAt(point.x, point.y, point.z)
            reject(!civsSameRelocationContainerState(block.blockData.asString, cell.before), "world-changed")
            reject(!canModify(city, point), "lands-denied")
            val source = containers.resolve(city, point, block.type.name)
            val retirement = if (point in registered)
                CivUpgradeContainerRetirement(target, registered.toList(), listOf(cell)) else null
            if (source.inventory.isEmpty && retirement == null) continue
            val before = source.inventory.storageContents.map(CivResidentItem::ofConstruction)
            return CivConstructionStorageBatch(CivRelocatedContainer(point, source.type, cell.before, before,
                List(STORAGE_SLOTS) { null }), cell, retirement)
        }
        return null
    }

    internal fun constructionReturnCandidates(city: Settlement, snapshot: CivConstructionStorageSnapshot): List<CivPosition> {
        val building = requireNotNull(city.building(snapshot.owner.original.id))
        val former = snapshot.owner.cancelledRegisteredSource(city, snapshot.cell)
        return (containers.positions(city).keys + listOfNotNull(former)).filter {
            it == former || civsConstructionReturnAllowed(city, building, it)
        }
            .sortedWith(compareBy<CivPosition>({ it != snapshot.cell.position }, { !civsRelocationLocal(building, it) },
                { it.point().distanceSquared(building.entrance.point()) }, { it.x }, { it.y }, { it.z }))
    }

    internal fun prepareConstructionReturn(city: Settlement, snapshot: CivConstructionStorageSnapshot,
        part: CivConstructionStoragePart, candidates: List<CivPosition>): CivContainerRelocation? {
        checkClaim(city)
        val owner = snapshot.owner
        require(owner.matches(city) && !owner.held(city) &&
            city.inventory?.constructionBuffers?.get(owner.original.id.toString())?.parts?.contains(part) == true)
        require(candidates.size <= CivContainerRelocation.MAX_CONTAINERS)
        val building = requireNotNull(city.building(owner.original.id))
        val world = findWorld(city.worldId) ?: throw CivsRejection("storage-unavailable")
        val remaining = snapshot.contents(part).toMutableList()
        val original = snapshot.cell.position
        val former = owner.cancelledRegisteredSource(city, snapshot.cell)
        val inventory = requireNotNull(city.inventory)
        val registered = if (former != null && former !in inventory.containers)
            city.copy(inventory = inventory.copy(containers = inventory.containers + former)) else city
        val changed = mutableListOf<CivRelocatedContainer>()
        val chunks = mutableSetOf<Pair<Int, Int>>()
        for (p in candidates) {
            if (remaining.all { it == null }) break
            if (p != former && !civsConstructionReturnAllowed(city, building, p)) continue
            if (!world.isChunkLoaded(p.x shr 4, p.z shr 4) || !canModify(city, p)) continue
            val chunk = (p.x shr 4) to (p.z shr 4)
            if (chunks.size == 8 && chunk !in chunks) continue
            val block = world.getBlockAt(p.x, p.y, p.z)
            val state = block.blockData.asString
            if (!civsRetainContainerState(state) || p == former && !civsSameRelocationContainerState(state, snapshot.cell.before)) continue
            val holder = containers.resolve(if (p == former) registered else city, p, block.type.name)
            if (p == former && registered !== city && !holder.inventory.isEmpty) continue
            val before = holder.inventory.storageContents.map(CivResidentItem::ofConstruction)
            val after = before.toMutableList()
            for (row in remaining.indices) {
                val item = remaining[row] ?: continue
                var rest = item.amount
                val max = item.constructionStack().maxStackSize
                val slots = if (p == original) listOf(row) + after.indices.filter { it != row } else after.indices.toList()
                for (empty in listOf(false, true)) for (slot in slots) {
                    val old = after[slot]
                    if (rest == 0 || (old == null) != empty || old != null &&
                        (old.material != item.material || old.data != item.data)) continue
                    val moved = minOf(rest, max - (old?.amount ?: 0))
                    if (moved <= 0) continue
                    after[slot] = item.copy(amount = (old?.amount ?: 0) + moved)
                    rest -= moved
                }
                remaining[row] = item.copy(amount = rest).takeIf { rest != 0 }
            }
            if (after != before) {
                changed += CivRelocatedContainer(p, holder.type, state, before, after)
                chunks += chunk
            }
        }
        if (changed.isEmpty()) return null
        val next = snapshot.balance(part.sha256, remaining).takeUnless { it.empty() }
        return CivContainerRelocation(building, changed, buffer = CivConstructionStorageMove(owner, part, next, snapshot.cell),
            restoreSourceRegistration = registered !== city && changed.any { it.position == former })
    }

    internal fun prepareUpgradeRelocation(city: Settlement, target: CivBuilding,
        future: Map<CivPosition, String>): CivContainerRelocation? {
        checkClaim(city)
        val building = city.building(target.id) ?: throw CivsRejection("building-not-found")
        require(building.phase == BuildingPhase.ACTIVE && !building.type.isInfrastructure &&
            target.type == building.type && target.level == building.level + 1 &&
            target.rotation == building.rotation && target.entrance == building.entrance)
        val positions = containers.buildingPositions(city, building.id)
        val world = findWorld(city.worldId) ?: throw CivsRejection("storage-unavailable")
        fun fitsReceipt(points: Collection<CivPosition>) = points.size <= CivContainerRelocation.MAX_CONTAINERS &&
            points.map { (it.x shr 4) to (it.z shr 4) }.distinct().size <= 8

        val retainedHeaders = if (fitsReceipt(positions.keys)) emptySet() else {
            // Large warehouses can retain a whole shelf without reading any of its inventory slots.
            reject(positions.size > MAX_UPGRADE_HOLDER_HEADERS, "storage-transaction-too-large")
            val states = positions.mapValues { (point, type) ->
                reject(!world.isChunkLoaded(point.x shr 4, point.z shr 4), "storage-unavailable")
                reject(point.y !in world.minHeight until world.maxHeight, "storage-container-invalid")
                val block = world.getBlockAt(point.x, point.y, point.z)
                reject(block.type.name !in CIVS_STORAGE_TYPES || type != null && block.type.name != type, "storage-container-invalid")
                block.blockData.asString
            }
            civsRelocationRetained(building, target, future, states).also {
                if (it.size == positions.size) return null
            }
        }
        // The already installed, unchanged control is the first receiver. The normal world
        // capture still validates every affected cell before it can accept construction.
        val participating = if (retainedHeaders.isEmpty()) positions
            else positions.filterKeys { it == building.control || it !in retainedHeaders }
        reject(!fitsReceipt(participating.keys), "storage-transaction-too-large")
        fun capture(point: CivPosition, type: String?): CivRelocatedContainer {
            reject(!canModify(city, point), "lands-denied")
            val holder = containers.resolve(city, point, type)
            val contents = holder.inventory.storageContents.map(CivResidentItem::of)
            require(contents.size == STORAGE_SLOTS)
            return CivRelocatedContainer(point, holder.type, world.getBlockAt(point.x, point.y, point.z).blockData.asString,
                contents, contents)
        }
        val holders = participating.map { (point, type) -> capture(point, type) }.toMutableList()
        fun plan() = civsPlanContainerRelocation(building, target, future, holders, city.inventory?.containers.orEmpty())
            ?.also { it.validated(it.after(city)) }
        val full = try { return plan() } catch (failure: CivsRejection) {
            if (failure.reason != "storage-full") throw failure
            failure
        }
        val retained = civsRelocationRetained(building, target, future, holders)
        val outputMaximums = holders.filter { it.position !in retained }.flatMap { it.before.filterNotNull() }
            .distinctBy { it.material to it.data }.associate { (it.material to it.data) to it.stack().maxStackSize }
        val candidates = containers.positions(city).filter { (point, _) ->
            point in retainedHeaders && point !in participating ||
                point !in positions && point !in future && !civsRelocationLocal(target, point) &&
                    civsRelocationExternalReceiver(city, building, point)
        }
        // The oversized path budgets all slot captures together; the existing small-holder
        // path retains its separate bound for the search outside the building.
        val maximumReceiverReads = CivContainerRelocation.MAX_CONTAINERS - if (retainedHeaders.isEmpty()) 0 else holders.size
        val captured = mutableMapOf<CivPosition, CivRelocatedContainer?>()
        fun receiver(point: CivPosition): CivRelocatedContainer? {
            if (point in captured) return captured[point]
            if (point !in candidates || captured.size >= maximumReceiverReads) return null
            val value = if (!world.isChunkLoaded(point.x shr 4, point.z shr 4) || !canModify(city, point)) null
            else try { capture(point, candidates[point]) } catch (failure: CivsRejection) {
                if (failure.reason !in setOf("storage-unavailable", "storage-container-invalid", "not-authorized")) throw failure
                null
            }
            captured[point] = value
            return value
        }
        // ponytail: at most 32 indexed receiver probes; larger searches need sliced discovery, not chunk loads.
        for (point in candidates.keys.sortedWith(compareBy<CivPosition>(
                { it !in positions }, { it.point().distanceSquared(building.entrance.point()) }, { it.x }, { it.y }, { it.z }))) {
            if (holders.size == CivContainerRelocation.MAX_CONTAINERS) break
            if (holders.any { it.position == point }) continue
            val receiptPoints = holders.map { it.position } + point
            if (!fitsReceipt(receiptPoints)) continue
            val holder = receiver(point) ?: continue
            val partner = relocationChestPartner(point, holder.blockData)
            val group = if (partner == null) listOf(holder) else {
                if (partner in receiptPoints || !fitsReceipt(receiptPoints + partner)) continue
                listOf(holder, receiver(partner) ?: continue)
            }
            val unchanged = civsRelocationRetained(building, target, future, holders + group)
            if (group.any { it.position !in unchanged } || group.none { destination -> destination.before.any { slot ->
                    slot == null || slot.amount < (outputMaximums[slot.material to slot.data] ?: 0)
                } }) continue
            holders += group
            try { return plan() } catch (failure: CivsRejection) {
                if (failure.reason != "storage-full") throw failure
            }
        }
        throw full
    }

    /** The pending-control owner installs and persists its receiver before this exact-slot evacuation. */
    internal fun prepareControlRelocation(city: Settlement, building: CivBuilding, from: CivPosition, to: CivPosition): CivContainerRelocation? {
        checkClaim(city)
        val hallHandoff = building.phase == BuildingPhase.SITE && civsAutonomousHallHandoff(city, building, from, to) &&
            city.pendingControls.orEmpty().any { it.buildingId == building.id && it.retire?.position == from && it.position == to }
        require(city.building(building.id) == building && city.autonomy != null && city.inventory?.mode == CivStorageMode.PHYSICAL &&
            (building.phase == BuildingPhase.ACTIVE || hallHandoff) && CivsBuildingControls.supported(building.type) && from != to &&
            building.bounds.contains(from.x, from.y, from.z) && building.bounds.contains(to.x, to.y, to.z) &&
            (from == building.control || city.pendingControls.orEmpty().any { it.buildingId == building.id && it.retire?.position == from && it.position == to }))
        val target = findWorld(city.worldId) ?: throw CivsRejection("world-unavailable")
        reject(!target.isChunkLoaded(to.x shr 4, to.z shr 4), "chunk-unloaded")
        val replacement = target.getBlockAt(to.x, to.y, to.z)
        reject(replacement.type != Material.BARREL && !replacement.type.isAir, "control-obstructed")
        fun capture(point: CivPosition): CivRelocatedContainer {
            reject(!target.isChunkLoaded(point.x shr 4, point.z shr 4), "chunk-unloaded")
            reject(!canModify(city, point), "lands-denied")
            val native = target.getBlockAt(point.x, point.y, point.z)
            reject(native.type.name !in CIVS_STORAGE_TYPES || point == from && native.type != Material.BARREL, "storage-container-invalid")
            val source = containers.resolve(city, point, native.type.name)
            val items = source.inventory.storageContents.map(CivResidentItem::of)
            require(items.size == STORAGE_SLOTS)
            return CivRelocatedContainer(point, source.type, native.blockData.asString, items, items)
        }
        val previous = capture(from)
        if (hallHandoff) {
            // A staged marker has exactly one receiver. Preserve every slot, including holes and item data.
            reject(replacement.type != Material.BARREL, "control-obstructed")
            if (previous.before.all { it == null }) return null
            val receiver = capture(to)
            reject(receiver.before.any { it != null }, "storage-busy")
            return CivContainerRelocation(building, listOf(previous.copy(after = List(STORAGE_SLOTS) { null }),
                receiver.copy(after = previous.before))).also { it.validated(city) }
        }
        if (previous.before.all { it == null }) return null
        fun plan(holders: List<CivRelocatedContainer>) = civsPlanContainerRelocation(building, building.copy(control = to),
            holders.associate { it.position to if (it.position == from) "minecraft:air" else it.blockData }, holders)?.also { it.validated(city) }
        // A fresh 27-slot barrel always holds the old barrel; unrelated storage is not a prerequisite.
        if (replacement.type == Material.BARREL) try {
            return plan(listOf(previous, capture(to)))
        } catch (failure: CivsRejection) {
            if (failure.reason != "storage-full") throw failure
        }
        val receivers = (if (replacement.type == Material.BARREL) listOf(to) else emptyList()) +
            containers.buildingPositions(city, building.id).keys.filter { it != from && it != to && building.bounds.contains(it.x, it.y, it.z) }
        reject(receivers.size + 1 > CivContainerRelocation.MAX_CONTAINERS, "storage-transaction-too-large")
        reject(receivers.isEmpty(), "storage-full")
        val holders = listOf(previous) + receivers.map(::capture)
        return plan(holders)
    }

    private fun reject(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
}
