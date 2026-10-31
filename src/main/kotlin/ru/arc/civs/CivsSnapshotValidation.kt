package ru.arc.civs

import java.util.UUID

/** Canonical immutable snapshot integrity, independent of runtime world observations. */
internal object CivsSnapshotValidation {
    fun validate(city: Settlement): Settlement {
        try {
            snapshot(CivsRules.present(city.id, city.owner, city.worldId, city.center, city.stock, city.buildings, city.workers, city.members))
            snapshot(CivsRules.validText(city.name, 48) && CivsRules.validText(city.style, 128) && city.radius > 0 && city.revision >= 0 && city.createdAt >= 0)
            snapshot(city.owner !in city.members && city.members.all { CivsRules.present(it) })
            city.autonomy?.let { autonomy ->
                snapshot((city.inventory == null || city.inventory.mode == CivStorageMode.PHYSICAL) && city.members.isEmpty() &&
                    autonomy.recruitmentMinutes in 0..CivsAutonomy.RECRUITMENT_MINUTES)
                snapshot(autonomy.layoutHalfWidth?.let { it in 24..CivsSettlementLayout.MAXIMUM_HALF_WIDTH } != false)
                autonomy.storage?.let { stores -> snapshot(stores.size <= CivsRules.MAX_SITES && stores.all { (id, store) ->
                    UUID.fromString(id).toString() == id && store.valid()
                }) }
                snapshot(!CivsAutonomy.legacyStock(city) || city.buildings.all { it.type in CivsAutonomy.allowedTypes })
            }
            city.memberNames?.let { names ->
                val participants = (city.members + city.owner).mapTo(hashSetOf(), UUID::toString)
                snapshot(names.keys.all { it in participants } && names.values.all {
                    it.isNotBlank() && it.length <= 32 && it.none(Char::isISOControl)
                })
            }
            snapshot(city.buildings.size in 1..CivsRules.MAX_SITES && CivsRules.buildingCount(city) <= CivsRules.MAX_BUILDINGS &&
                city.buildings.count { it.type.isInfrastructure } <= CivsRules.MAX_INFRASTRUCTURE &&
                city.buildings.count { it.type == BuildingType.DECORATION } <= CivsRules.decorationLimit(city) &&
                city.buildings.map { it.id }.distinct().size == city.buildings.size)
            snapshot(city.buildings.count { it.type == BuildingType.TOWN_HALL } == 1)
            snapshot(city.buildings.all { validBuilding(city, it) })
            snapshot(city.buildings.all { it.type == BuildingType.TOWN_HALL || it.level <= city.level() })
            snapshot(city.buildings.withIndex().all { (index, building) -> city.buildings.drop(index + 1).none { it.bounds.overlaps(building.bounds) } })
            val order = city.construction
            city.constructionQueue?.let { queue ->
                snapshot(queue.size <= CivsRules.MAX_SITES && queue.distinct().size == queue.size &&
                    queue.all { id -> CivsRules.present(id) && city.building(id)?.let {
                        it.phase == BuildingPhase.SITE || it.phase == BuildingPhase.ACTIVE &&
                            (it.level < it.type.maxLevel || CivsPlanBoardBlueprint.needsRefit(it))
                    } == true && id != order?.buildingId })
            }
            city.pendingDeletion?.buildingId?.let { id -> snapshot(city.building(id)?.type?.let { it != BuildingType.TOWN_HALL } == true) }
            if (order != null) {
                val plan = order.plannedBuilding
                snapshot(plan != null && validBuilding(city, plan))
                val target = plan!!
                val current = city.building(order.buildingId)
                snapshot(CivsRules.present(order.id) && current != null && order.buildingId == target.id && order.targetLevel == target.level)
                snapshot(target.phase == BuildingPhase.CONSTRUCTING && city.buildings.none { it.id != target.id && it.bounds.overlaps(target.bounds) })
                val founding = CivsRules.isFounding(city)
                snapshot(if (current!!.phase == BuildingPhase.CONSTRUCTING) current == target && target.level == 1 else
                    current.phase == BuildingPhase.ACTIVE && current.type == target.type &&
                        (current.level + 1 == target.level || CivsPlanBoardBlueprint.refit(current, target)) &&
                        current.entrance == target.entrance && current.rotation == target.rotation)
                snapshot(target.type == BuildingType.TOWN_HALL || target.level <= city.level())
                snapshot(founding || target.type != BuildingType.TOWN_HALL || target.level > 1 || order.batches != null)
                snapshot(validConstructionPrice(city, order, target))
                order.preparationWorkplace?.let { point ->
                    snapshot(city.contains(point.x, point.z) && point.y in -4095..4095)
                }
                order.automaticPreparationBatches?.let { count ->
                    snapshot(order.batches != null && count in 1 until order.workRequired)
                }
                snapshot((order.blocksRequired == null) == (order.blocksPlaced == null))
                order.blocksRequired?.let { total -> snapshot(total in 0..CivsStore.MAX_PLAN_BLOCKS && order.blocksPlaced!! in 0..total) }
                order.workCredit?.let { credit -> snapshot((order.payment != null || city.autonomy != null && order.batches != null) &&
                    credit in 0..CivsRules.MAX_BUILDER_WORK_CREDIT &&
                    credit <= order.workRequired - order.workDone && (order.phase != ConstructionPhase.CONSTRUCTING || credit == 0)) }
                snapshot(if (order.batches == null) order.workDone in 0..order.workRequired else order.workDone in 0 until order.workRequired)
                snapshot(when (order.phase) {
                    ConstructionPhase.CONSTRUCTING -> order.workDone < order.workRequired && order.failure == null
                    ConstructionPhase.READY_TO_PLACE, ConstructionPhase.PLACING ->
                        (order.batches != null || order.workDone == order.workRequired) && order.failure == null
                    ConstructionPhase.WAITING_MATERIALS -> order.payment?.awaiting?.isNotEmpty() == true && order.failure == null
                    ConstructionPhase.PAUSED -> order.failure != null && CivsRules.validText(order.failure, 512)
                })
                order.payment?.let { payment ->
                    snapshot(order.batches != null && order.batches.size <= 16)
                    snapshot(payment.reservedBatch == null || payment.reservedBatch == order.workDone &&
                        order.phase in setOf(ConstructionPhase.READY_TO_PLACE, ConstructionPhase.PLACING, ConstructionPhase.PAUSED))
                    snapshot(order.phase != ConstructionPhase.PLACING || payment.reservedBatch == order.workDone)
                    snapshot(payment.awaiting == null || order.phase in setOf(ConstructionPhase.WAITING_MATERIALS, ConstructionPhase.PAUSED) &&
                        payment.reservedBatch == null && payment.awaiting.isNotEmpty() && validMaterials(payment.awaiting) &&
                        payment.awaiting.all { (key, value) -> order.materials.orEmpty().getOrDefault(key, 0) >= value })
                }
            }
            snapshot(city.buildings.none { it.phase == BuildingPhase.CONSTRUCTING && it.id != order?.buildingId })
            snapshot(city.workers.size in 0..CivsRules.MAX_RESIDENTS &&
                city.workers.map { it.id }.distinct().size == city.workers.size)
            CivsCorpses.validate(city)
            CivsResearch.validate(city)
            city.roadRouteOverrides?.let { overrides ->
                snapshot(city.autonomy != null && overrides.isNotEmpty() && overrides.size <= CivsRules.MAX_BUILDINGS)
                overrides.forEach { (id, override) ->
                    val source = requireNotNull(city.building(UUID.fromString(id)))
                    snapshot(source.id.toString() == id)
                    override.validated(city, source)
                }
            }
            city.mineProgress?.let { progress -> snapshot(progress.size <= CivsRules.MAX_BUILDINGS && progress.all { (id, step) ->
                java.util.UUID.fromString(id).toString() == id && step in 0..100_000 }) }
            city.minePlans?.let { plans -> snapshot(plans.size <= CivsRules.MAX_BUILDINGS && plans.all { (id, plan) ->
                val buildingId = java.util.UUID.fromString(id)
                val building = city.building(buildingId)
                val geometry = building?.takeIf { it.type == BuildingType.QUARRY }?.let { CivsMinePlan.forBuilding(it, plan.version) }
                val size = geometry?.steps?.size
                val v2Size = building?.takeIf { it.type == BuildingType.QUARRY }?.let { CivsMinePlan.forBuilding(it, 2) }?.steps?.size
                val v3Size = building?.takeIf { it.type == BuildingType.QUARRY }?.let { CivsMinePlan.forBuilding(it, 3) }?.steps?.size
                val v4Size = building?.takeIf { it.type == BuildingType.QUARRY }?.let { CivsMinePlan.forBuilding(it, 4) }?.steps?.size
                buildingId.toString() == id && size != null && CivsRules.present(plan) &&
                    plan.version in 2..CivsMinePlan.VERSION && plan.nextStep in 0..size.toLong() &&
                    (plan.v2Step == null || plan.version >= 3 && v2Size != null && plan.v2Step in 0..v2Size.toLong()) &&
                    (plan.v3Step == null || plan.version >= 4 && v3Size != null && plan.v3Step in 0..v3Size.toLong()) &&
                    (plan.v4Step == null || plan.version >= 5 && v4Size != null && plan.v4Step in 0..v4Size.toLong()) &&
                    plan.legacyStep == city.mineProgress?.get(id) && CivsMineProgress.valid(requireNotNull(geometry), plan, requireNotNull(building)) &&
                    CivsMineProgress.validLevels(requireNotNull(building), plan) &&
                    plan.levels.orEmpty().all { validBuilding(city, it.building) }
            }) }
            city.forestryJobs?.let { jobs -> snapshot(jobs.size <= CivsRules.MAX_BUILDINGS && jobs.all { (id, job) ->
                CivsForestry.valid(city, id, job) && job.next in 1 until job.cells.size
            }) }
            city.forestrySeeded?.let { ids -> snapshot(city.autonomy != null && ids.size <= CivsRules.MAX_BUILDINGS && ids.all { id ->
                city.buildings.any { it.id.toString() == id && it.type == BuildingType.FORESTER }
            }) }
            val shipments = city.workers.mapNotNull { it.cargo?.id }
            snapshot(shipments.distinct().size == shipments.size)
            city.immigration?.let { snapshot(it.remaining in 0..CivsPopulation.IMMIGRANT_POPULATION && it.nextArrivalAt >= city.createdAt) }
            snapshot(city.workers.all { worker ->
                CivsResidents.validated(worker)
                CivsRules.present(worker.id) && CivsRules.validText(worker.name, 32) && worker.completedCycles >= 0 && worker.nextDeliveryAt >= 0 &&
                    (worker.pendingStock?.let { pending -> city.autonomy != null &&
                        Resource.entries.all { pending[it] in 0..CivInventory.MAX_AMOUNT } } != false) &&
                    CivsLogistics.validCargo(city, worker.cargo) &&
                    (worker.cargo?.takeIf { it.inventoryBacked }?.let {
                        CivsEquipment.plainCount(worker, it.material) >= it.amount
                    } != false) &&
                    (worker.assignment == null || worker.nextDeliveryAt > 0 && city.building(worker.assignment)?.let {
                        CivsRules.slots(city, it) > 0 || (city.autonomy == null && it.type == BuildingType.TOWN_HALL && CivsRules.isFounding(city))
                    } == true)
            })
            CivsPopulation.validated(city)
            snapshot(city.buildings.all { building ->
                city.workers.count { it.assignment == building.id } <=
                    if (city.autonomy == null && building.type == BuildingType.TOWN_HALL && CivsRules.isFounding(city)) 1 else CivsRules.slots(city, building)
            })
            // Retiring housing/storage does not evict residents or erase stock. New intake still checks current capacity.
            snapshot(city.workers.size <= CivsRules.residentLimit(city) &&
                city.workers.count { !CivsResidents.isChild(it) } <= CivsRules.populationLimit(city))
            snapshot(Resource.entries.all { city.stock[it] in 0..(if (city.autonomy != null) CivInventory.MAX_AMOUNT
                else (128L + (CivsRules.MAX_BUILDINGS - 1) * 1024L)) })
            if (city.autonomy != null) snapshot(Resource.entries.all {
                CivsAutonomousLogistics.held(city, it) in 0..CivInventory.MAX_AMOUNT
            })
            city.inventory?.let { inventory ->
                inventory.validated()
                snapshot(city.stock == Stock())
                snapshot(inventory.containers.all { city.contains(it.x, it.z) && it.y in -4_096..4_096 })
                inventory.buildingMaterials?.forEach { (id, items) ->
                    val building = city.building(UUID.fromString(id))
                    snapshot(building != null && (city.autonomy != null && inventory.mode == CivStorageMode.PHYSICAL ||
                        items.values.sum() <= CivInventory.buildingCapacity(building)))
                }
            }
            city.pendingControls?.let { pending ->
                snapshot(pending.size <= CivsRules.MAX_BUILDINGS && pending.map { it.buildingId }.distinct().size == pending.size)
                snapshot((city.buildings + listOfNotNull(city.construction?.plannedBuilding)).none { intersectsApproach(city, it) })
                val approachPoints = pending.flatMap { it.approach.orEmpty() }.map { it.position }
                snapshot(approachPoints.distinct().size == approachPoints.size)
                val retired = pending.mapNotNull { it.retire?.position }
                snapshot(retired.distinct().size == retired.size && retired.none { it in approachPoints })
                snapshot(pending.all { CivsRules.present(it.buildingId, it.position) && city.building(it.buildingId)?.control == it.position &&
                    it.before.isNotEmpty() && it.before.length <= CivsStore.MAX_BLOCK_STATE_CHARS &&
                    it.before.startsWith("minecraft:") && it.before.none(Char::isISOControl) })
                pending.forEach { receipt ->
                    val building = requireNotNull(city.building(receipt.buildingId))
                    receipt.retire?.let { cell ->
                        snapshot(city.autonomy != null && city.inventory?.mode == CivStorageMode.PHYSICAL &&
                            building.phase == BuildingPhase.ACTIVE && building.type in CivsWorkstations.TYPES &&
                            city.construction?.buildingId != building.id &&
                            cell.position != receipt.position && cell.position.y in -4_096..4_096 &&
                            city.contains(cell.position.x, cell.position.z) &&
                            building.bounds.contains(cell.position.x, cell.position.y, cell.position.z) &&
                            city.buildings.none { it.id != building.id && (it.control == cell.position ||
                                it.bounds.contains(cell.position.x, cell.position.y, cell.position.z)) } &&
                            civsRetainContainerState(cell.before) && cell.before.startsWith("minecraft:barrel[") && cell.after == "minecraft:air")
                    }
                    val approach = receipt.approach.orEmpty()
                    snapshot(approach.size <= 3 && approach.map { it.position }.distinct().size == approach.size)
                    snapshot(approach.all { cell ->
                        val point = cell.position
                        CivsRules.present(point, cell.before, cell.after) && point.x == building.entrance.x && point.z == building.entrance.z &&
                            point.y.toLong() in (building.entrance.y.toLong() - 1)..(building.entrance.y.toLong() + 1) &&
                            point.y in -4_096..4_096 && point != receipt.position && !building.bounds.contains(point.x, point.y, point.z) &&
                            CivsRules.validText(cell.before, CivsStore.MAX_BLOCK_STATE_CHARS) && cell.before.startsWith("minecraft:") &&
                            cell.before != cell.after && cell.after == if (point.y < building.entrance.y) "minecraft:dirt" else "minecraft:air"
                    })
                }
            }
            return city
        } catch (error: CivsRejection) {
            if (error.reason == "invalid-state") throw error
            CivsRules.reject("invalid-state")
        } catch (_: RuntimeException) {
            CivsRules.reject("invalid-state")
        }
    }

    fun validBuilding(city: Settlement, building: CivBuilding): Boolean =
        CivsRules.present(building.id, building.type, building.phase, building.anchor, building.bounds, building.entrance) && building.level in 1..building.type.maxLevel &&
            building.rotation in listOf(0, 90, 180, 270) && CivsRules.validText(building.schematicId, 512) &&
            building.bounds.min.x <= building.bounds.max.x && building.bounds.min.y <= building.bounds.max.y && building.bounds.min.z <= building.bounds.max.z &&
            city.contains(building.bounds.min.x, building.bounds.min.z) && city.contains(building.bounds.max.x, building.bounds.max.z) &&
            city.contains(building.anchor.x, building.anchor.z) && city.contains(building.entrance.x, building.entrance.z) &&
            (building.phase != BuildingPhase.SITE || building.level == 1) &&
            (building.control?.let { city.contains(it.x, it.z) } != false) &&
            (!(building.type.isInfrastructure || building.type == BuildingType.DECORATION) || building.control == null) &&
            (building.transit?.let { cells ->
                building.type in setOf(BuildingType.ROAD, BuildingType.GATE) && cells.size in 1..256 &&
                    cells.map { civsTerrainKey(it.x, it.z) }.distinct().size == cells.size &&
                    cells.size.toLong() == (building.bounds.max.x.toLong() - building.bounds.min.x + 1) *
                        (building.bounds.max.z.toLong() - building.bounds.min.z + 1) && cells.all { point ->
                        building.bounds.contains(point.x, point.y, point.z)
                    }
            } != false) &&
            (building.type !in setOf(BuildingType.ROAD, BuildingType.GATE) || building.transit != null) &&
            // Old farms may still store BREAD; their effective selection is now wheat.
            // Preserve the stored value and previously accepted receipts on reload.
            (building.production?.let { it in CivsProduction.options(building.type) ||
                building.type == BuildingType.FARM && it == "BREAD" } != false)

    fun intersectsApproach(city: Settlement, building: CivBuilding): Boolean = city.pendingControls.orEmpty().any {
        it.buildingId != building.id && (it.approach.orEmpty() + listOfNotNull(it.retire)).any { cell ->
            building.bounds.contains(cell.position.x, cell.position.y, cell.position.z) || building.control == cell.position
        }
    }

    fun validConstructionPrice(city: Settlement, order: CivConstruction, building: CivBuilding): Boolean {
        if (city.autonomy != null && order.payment == null) {
            val batches = order.batches ?: return false
            return building.type in CivsAutonomy.allowedTypes && order.payment == null &&
                batches.atomicPairs == true &&
                batches.total in 1..75_000 && batches.size in 1..256 && order.workRequired == batches.total &&
                order.cost == (if (CivsPlanBoardBlueprint.refit(city.building(building.id), building)) Stock()
                    else CivsAutonomy.cost(building.type, building.level, order.materials)) &&
                order.materials?.let(::validMaterials) == true
        }
        if (building.type in setOf(BuildingType.SAWMILL, BuildingType.BLACKSMITH,
                BuildingType.STONEMASON, BuildingType.LIBRARY, BuildingType.TAVERN, BuildingType.BAKERY,
                BuildingType.GUARD_TOWER, BuildingType.WALL, BuildingType.GATE, BuildingType.ROAD,
                BuildingType.PLAN_BOARD, BuildingType.UNIVERSITY, BuildingType.SMELTERY, BuildingType.CRUSHER,
                BuildingType.DECORATION) && order.payment == null) return false
        val batches = order.batches
        if (batches == null) return order.payment == null && building.type in setOf(BuildingType.TOWN_HALL,
            BuildingType.WAREHOUSE, BuildingType.HOUSE, BuildingType.FORESTER, BuildingType.QUARRY, BuildingType.FARM) &&
            order.materials == null && order.cost == CivsRules.cost(building.type, building.level) &&
            order.workRequired == CivsRules.workRequired(building.type, building.level)
        val materials = order.materials ?: return false
        return batches.total in 1..75_000 && batches.size in 1..256 && order.workRequired == batches.total &&
            order.cost == Stock() && validMaterials(materials)
    }

    fun validMaterials(materials: Map<String, Long>): Boolean =
        runCatching { CivInventory(CivStorageMode.VIRTUAL, materials).validated() }.isSuccess

    private fun snapshot(condition: Boolean) = CivsRules.check(condition, "invalid-state")
}
