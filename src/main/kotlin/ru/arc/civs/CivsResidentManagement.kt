package ru.arc.civs

import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Player/resident item actions share the coordinator's durable write lane. */
internal class CivsResidentManagement(
    private val authority: CivsService,
    private val storage: CivsStorage,
    private val worksites: CivsWorksites,
    private val citizens: CivsCitizens,
) {
    fun corpse(player: Player, city: Settlement, workerId: UUID, diedAt: Long? = null): CivCorpse {
        val corpse = city.corpses.orEmpty().firstOrNull { it.worker.id == workerId && it.expiresAt > authority.now() }
            ?: throw CivsRejection("worker-not-found")
        rejectIf(diedAt != null && corpse.diedAt != diedAt, "worker-not-found")
        rejectIf(player.world.uid != city.worldId, "wrong-world")
        val location = authority.corpseLocation(city.id, workerId, corpse.diedAt)
            ?: throw CivsRejection("corpse-unavailable")
        rejectIf(location.world?.uid != city.worldId || !civsCorpseStandingSafe(location), "corpse-unavailable")
        rejectIf(player.location.distanceSquared(location) > 36.0, "worker-too-far")
        return corpse
    }

    fun corpseItems(player: Player, workerId: UUID): List<org.bukkit.inventory.ItemStack?> =
        corpseItems(player, authority.managed(player).id, workerId)

    fun corpseItems(player: Player, cityId: UUID, workerId: UUID): List<org.bukkit.inventory.ItemStack?> =
        CivsEquipment.slots(corpse(player, authority.corpseCity(player, cityId), workerId).worker).map { it?.stack() }

    fun transferCorpseItem(player: Player, workerId: UUID, npcSlot: Int,
        click: org.bukkit.event.inventory.ClickType): CompletableFuture<Unit> =
        authority.action("corpse_loot", player) { transferCorpseItem(player, authority.managed(player).id, workerId, npcSlot, click) }

    fun transferCorpseItem(player: Player, cityId: UUID, workerId: UUID, npcSlot: Int,
        click: org.bukkit.event.inventory.ClickType): CompletableFuture<Unit> =
        authority.action("corpse_loot", player, "city" to cityId, "worker" to workerId, "slot" to npcSlot) {
            authority.corpseCity(player, cityId)
            authority.nativePlayerMutation(cityId, player, keepViewersOpen = true) { before, id ->
                corpse(player, authority.corpseCity(player, cityId), workerId)
                val prepared = storage.prepareCorpseTransfer(before, workerId, player, npcSlot, click)
                val transfer = prepared.transfer ?: return@nativePlayerMutation null
                val after = before.copy(corpses = before.corpses.orEmpty().map {
                    if (it.worker.id == workerId) it.copy(worker = prepared.worker) else it
                }, revision = before.revision + 1)
                CivInventoryTransaction(id, before.id, before.revision, after, emptyList(), residentTransfer = transfer)
            }.thenApply { Unit }
        }

    fun resurrect(player: Player, cityId: UUID, workerId: UUID, diedAt: Long): CompletableFuture<Settlement> =
        authority.action("resident_resurrect", player, "city" to cityId, "worker" to workerId, "died_at" to diedAt) {
            authority.corpseCity(player, cityId)
            authority.nativePlayerMutation(cityId, player) { before, id ->
                val corpse = corpse(player, authority.corpseCity(player, cityId), workerId, diedAt)
                val at = authority.now()
                val after = CivsRules.resurrect(before, player.uniqueId, workerId, diedAt, at,
                    administrator = player.hasPermission("arccivs.admin"))
                val location = authority.corpseLocation(cityId, workerId, diedAt) ?: throw CivsRejection("corpse-unavailable")
                val debit = storage.prepareResurrection(before, player, corpse, at, location)
                CivInventoryTransaction(id, cityId, before.revision, after, emptyList(), resurrection = debit)
            }
        }

    fun supplyWorker(player: Player, workerId: UUID, material: String): CompletableFuture<Unit> =
        residentItem(player, workerId, material, returning = false)

    fun returnWorkerItem(player: Player, workerId: UUID, material: String): CompletableFuture<Unit> =
        residentItem(player, workerId, material, returning = true)

    private fun residentItem(player: Player, workerId: UUID, material: String, returning: Boolean): CompletableFuture<Unit> =
        authority.cityAction("resident_item", player, "worker" to workerId, "material" to material, "returning" to returning) { city ->
            rejectIf(city.inventory == null, "materials-required")
            CivsRules.supplyResident(city, player.uniqueId, workerId, material, returning)
            authority.inventoryMutation(city.id, { current ->
                if (returning) storage.prepareCredit(current, material, 1,
                    current.buildings.firstOrNull(CivsRules::isDepot)?.id ?: throw CivsRejection("storage-unavailable"))
                else storage.prepareDebit(current, mapOf(material to 1L))
            }) { before, inventory ->
                CivsRules.supplyResident(before, player.uniqueId, workerId, material, returning).copy(inventory = inventory)
            }.thenApply { Unit }
        }

    fun feedWorker(player: Player, workerId: UUID): CompletableFuture<Unit> =
        authority.cityAction("resident_feed", player, "worker" to workerId) { city ->
            authority.mutate(city.id) { CivsRules.feedResident(it, player.uniqueId, workerId) }.thenApply { Unit }
        }

    fun residentItems(player: Player, workerId: UUID): List<org.bukkit.inventory.ItemStack?> {
        val city = authority.managed(player)
        val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        return CivsEquipment.slots(worker).map { it?.stack() }
    }

    fun residentItems(player: Player, cityId: UUID, workerId: UUID): List<org.bukkit.inventory.ItemStack?> {
        val city = authority.inspectResident(player, cityId, workerId)
        return CivsEquipment.slots(city.workers.first { it.id == workerId }).map { it?.stack() }
    }

    fun toolRequest(player: Player, workerId: UUID): CivsToolRequest? {
        val city = authority.managed(player)
        val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        val tool = CivsEquipment.requestedTool(city, worker) ?: return null
        return CivsToolRequest(tool, CivsEquipment.canEquipRequestedTool(worker) &&
            (player.gameMode == org.bukkit.GameMode.CREATIVE || CivsEquipment.cheapestTool(player, tool) != null))
    }

    fun giveRequestedTool(player: Player, workerId: UUID): CompletableFuture<Unit> =
        transferResidentItems(player, workerId, null, null, org.bukkit.event.inventory.ClickType.RIGHT, true)

    fun workerRequest(player: Player, workerId: UUID): CivsBuildingRequest? {
        val city = authority.managed(player)
        val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        if (CivsResidents.isChild(worker)) return null
        toolRequest(player, workerId)?.let { return CivsBuildingRequest(it.material, 1, it.canGive) }
        val building = worker.assignment?.let(city::building) ?: return null
        return supplyRequest(player, city, building, worker)?.let { request ->
            request.copy(canGive = request.canGive && (building.type == BuildingType.UNIVERSITY || CivsEquipment.supplyRoom(worker, request.material) > 0))
        }
    }

    fun giveWorkerRequest(player: Player, workerId: UUID, material: String): CompletableFuture<Unit> =
        authority.cityAction("resident_supply", player, "worker" to workerId, "material" to material) { city ->
            val request = workerRequest(player, workerId) ?: throw CivsRejection("resident-item-missing")
            rejectIf(request.material.name != material, "resident-item-missing")
            val worker = city.workers.first { it.id == workerId }
            if (CivsEquipment.requestedTool(city, worker)?.name == material) giveRequestedTool(player, workerId)
            else giveRequestedSupply(player, city, requireNotNull(worker.assignment), material,
                workerId.takeUnless { city.building(requireNotNull(worker.assignment))?.type == BuildingType.UNIVERSITY })
        }

    fun buildingRequest(player: Player, buildingId: UUID): CivsBuildingRequest? {
        val city = authority.managed(player)
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        val workers = city.workers.filter { it.assignment == building.id && !CivsResidents.isChild(it) }
        for (worker in workers) {
            toolRequest(player, worker.id)?.let { return CivsBuildingRequest(it.material, 1, it.canGive) }
        }
        val request = if (building.type == BuildingType.SAWMILL) supplyRequest(player, city, building, null)
            else workers.firstNotNullOfOrNull { supplyRequest(player, city, building, it) }
                ?: if (workers.isEmpty()) supplyRequest(player, city, building, null) else null
        return request?.let {
                it.copy(canGive = it.canGive && city.inventory?.mode == CivStorageMode.PHYSICAL)
            }
    }

    private fun supplyRequest(player: Player, city: Settlement, building: CivBuilding, worker: CivWorker?): CivsBuildingRequest? {
        if (city.inventory == null || building.phase != BuildingPhase.ACTIVE) return null
        val available = storage.buildingAvailable(city, building.id).let { stock ->
            if (building.type == BuildingType.UNIVERSITY) stock else worker?.let { CivsEquipment.available(it, stock) } ?: stock }
        val orderedInputs = if (building.type in CivsCarpentry.workshops) {
            if (worker == null) authority.craftRequired(city, building.id).takeIf { it.isNotEmpty() }
            else authority.craftPlan(city, worker)?.inputs
        } else null
        val productionInputs = orderedInputs ?: if (building.type == BuildingType.SAWMILL) emptyMap()
            else CivsProduction.inputs(city, building)
        val requirements = worker?.let { worksites.requirement(city, it) }?.let { listOf(it) }
            ?: productionInputs.entries.sortedBy { (material, count) -> (available[material] ?: 0) >= count }
                .map { (material, count) -> material to if (orderedInputs != null || building.type == BuildingType.UNIVERSITY) count
                    else maxOf(count, Material.matchMaterial(material)?.maxStackSize?.toLong() ?: 1) }
        for ((material, need) in requirements) {
            val missing = need - (available[material] ?: 0)
            if (missing <= 0) continue
            val item = Material.matchMaterial(material) ?: continue
            val canGive = player.gameMode == org.bukkit.GameMode.CREATIVE ||
                player.inventory.storageContents.any { it?.type == item && it.amount > 0 && it.isSimilar(org.bukkit.inventory.ItemStack(item)) }
            return CivsBuildingRequest(item, missing.coerceAtMost(item.maxStackSize.toLong()), canGive)
        }
        return null
    }

    fun giveBuildingRequest(player: Player, buildingId: UUID, material: String): CompletableFuture<Unit> =
        authority.cityAction("building_supply", player, "building" to buildingId, "material" to material) { city ->
            val request = buildingRequest(player, buildingId) ?: throw CivsRejection("resident-item-missing")
            rejectIf(request.material.name != material, "resident-item-missing")
            val worker = city.workers.firstOrNull {
                it.assignment == buildingId && CivsEquipment.requestedTool(city, it)?.name == material
            }
            if (worker != null) giveRequestedTool(player, worker.id)
            else giveRequestedSupply(player, city, buildingId, material)
        }

    /** Both request screens fulfil the current deficit through the same durable supply operation. */
    private fun giveRequestedSupply(player: Player, city: Settlement, buildingId: UUID, material: String,
        workerId: UUID? = null): CompletableFuture<Unit> {
        val creative = player.gameMode == org.bukkit.GameMode.CREATIVE
        var updated: CivWorker? = null
        var moved = 0L
        return authority.inventoryMutation(city.id, { before ->
            rejectIf(!player.isOnline || creative && player.gameMode != org.bukkit.GameMode.CREATIVE, "not-authorized")
            val request = (if (workerId == null) buildingRequest(player, buildingId) else workerRequest(player, workerId))
                ?: throw CivsRejection("resident-item-missing")
            rejectIf(request.material.name != material, "resident-item-missing")
            if (workerId == null) {
                rejectIf(before.inventory?.mode != CivStorageMode.PHYSICAL, "materials-required")
                val have = storage.buildingAvailable(before, buildingId)[material] ?: 0L
                storage.prepareConstructionSupply(before, buildingId, mapOf(material to have + request.amount),
                    if (creative) null else player).also { moved = it.result.moved }.plan
            } else {
                val worker = before.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
                if (creative) {
                    val (supplied, amounts) = CivsEquipment.grantSupplies(worker, mapOf(material to request.amount))
                    moved = amounts[material] ?: 0L
                    rejectIf(moved == 0L, "resident-inventory-full")
                    updated = supplied
                    CivStoragePlan(requireNotNull(before.inventory), emptyList())
                } else storage.prepareResidentTransfer(before, workerId, player, null, null,
                    org.bukkit.event.inventory.ClickType.LEFT, requestedSupply = request.material to request.amount).also {
                    updated = it.worker
                    moved = (CivsEquipment.plainCount(it.worker, material) - CivsEquipment.plainCount(worker, material)).toLong()
                }.plan
            }
        }, sourcePlayer = if (creative) null else player, keepViewersOpen = true) { before, inventory ->
            if (moved == 0L) before else before.copy(inventory = inventory, revision = before.revision + 1,
                workers = if (workerId == null) before.workers else before.workers.map {
                    if (it.id == workerId) requireNotNull(updated) else it
                })
        }.thenApply { saved ->
            CivsDiagnostics.event(if (workerId == null) "building_supply_committed" else "resident_supply_committed",
                "city" to saved.id, "building" to buildingId, "worker" to workerId, "player" to player.uniqueId,
                "material" to material, "amount" to moved, "grant" to creative, "revision" to saved.revision)
            Unit
        }
    }

    fun summonWorker(player: Player, buildingId: UUID): CompletableFuture<Unit> =
        authority.cityAction("resident_summon", player, "building" to buildingId) { city ->
            rejectIf(player.world.uid != city.worldId, "wrong-world")
            rejectIf(city.building(buildingId) == null, "building-not-found")
            rejectIf(!citizens.summon(city, buildingId), "worker-summon-unavailable")
            CompletableFuture.completedFuture(Unit)
        }

    fun transferResidentItem(player: Player, workerId: UUID, npcSlot: Int?, playerSlot: Int?, click: org.bukkit.event.inventory.ClickType): CompletableFuture<Unit> =
        transferResidentItems(player, workerId, npcSlot, playerSlot, click, false)

    private fun transferResidentItems(player: Player, workerId: UUID, npcSlot: Int?, playerSlot: Int?, click: org.bukkit.event.inventory.ClickType, requested: Boolean): CompletableFuture<Unit> =
        authority.cityAction("resident_transfer", player, "worker" to workerId, "resident_slot" to npcSlot, "player_slot" to playerSlot) { city ->
            var updated: CivWorker? = null
            val creativeGrant = requested && player.gameMode == org.bukkit.GameMode.CREATIVE
            authority.inventoryMutation(city.id, { before ->
                val worker = before.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
                val tool = if (requested) CivsEquipment.requestedTool(before, worker)
                    ?: throw CivsRejection("resident-item-missing") else null
                if (creativeGrant) {
                    rejectIf(player.gameMode != org.bukkit.GameMode.CREATIVE || !player.isOnline, "not-authorized")
                    updated = CivsEquipment.equipRequestedTool(worker, CivResidentItem(requireNotNull(tool).name, 1))
                    CivStoragePlan(before.inventory ?: throw CivsRejection("materials-required"), emptyList())
                } else storage.prepareResidentTransfer(before, workerId, player, npcSlot, playerSlot, click, tool)
                    .also { updated = it.worker }.plan
            }, sourcePlayer = if (creativeGrant) null else player, keepViewersOpen = true) { before, inventory ->
                before.copy(workers = before.workers.map { if (it.id == workerId) requireNotNull(updated) else it },
                    inventory = inventory, revision = before.revision + 1)
            }.thenApply { Unit }
        }
    private fun rejectIf(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
}
