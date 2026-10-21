package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import ru.arc.paper.testing.MockBukkitTestRuntime

class CivsLogisticsTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }
    test("shipment reach uses source and destination barrels while the saved entrance remains its identity") {
        val base = logisticsCity()
        val warehouse = base.buildings[1].copy(entrance = CivPosition(-30, 64, 3))
        val smith = base.buildings[2].copy(entrance = CivPosition(60, 64, 3))
        val city = base.copy(buildings = listOf(base.buildings[0], warehouse, smith, base.buildings[3]))
        val plan = requireNotNull(CivsLogistics.plan(city, city.workers.first()) {
            if (it == warehouse.id) mapOf("IRON_INGOT" to 3L) else emptyMap()
        })
        plan.cargo.sourceBuildingId shouldBe warehouse.id
        plan.cargo.targetBuildingId shouldBe smith.id
        plan.cargo.sourceEntrance shouldBe warehouse.entrance
        // The entrances are 90 blocks apart; the actual barrel leg is only ten.
        CivsLogistics.pickup(city, city.workers.first().id, plan.cargo).workers.first().cargo shouldBe plan.cargo.copy(inventoryBacked = true)
    }

    test("accepted cargo removes duplicate demand and a second courier chooses another item") {
        val city = logisticsCity()
        val warehouse = city.buildings[1]
        val smith = city.buildings[2]
        val stock: (UUID) -> Map<String, Long> = { if (it == warehouse.id) mapOf("IRON_INGOT" to 9L, "STICK" to 9L) else emptyMap() }
        val plan = CivsLogistics.plan(city, city.workers.first(), stock)!!
        plan.cargo.material shouldBe "IRON_INGOT"
        plan.cargo.amount shouldBe 3L
        val picked = CivsLogistics.pickup(city, city.workers.first().id, plan.cargo)
        CivsLogistics.pickup(picked, picked.workers.first().id, plan.cargo) shouldBe picked
        val request = CivsLogistics.requests(picked, stock).single { it.buildingId == smith.id && it.material == "IRON_INGOT" }
        request.needed shouldBe 3L
        request.inTransit shouldBe 3L
        CivsLogistics.plan(picked, picked.workers[1], stock)!!.cargo.material shouldBe "STICK"
        CivsLogistics.plan(picked, picked.workers.first(), stock)!!.carrying shouldBe true
    }

    test("one completed shipment clears cargo and awards one warehouse point without replay") {
        val city = logisticsCity()
        val plan = CivsLogistics.plan(city, city.workers.first()) { if (it == city.buildings[1].id) mapOf("IRON_INGOT" to 3L) else emptyMap() }!!
        val picked = CivsLogistics.pickup(city, city.workers.first().id, plan.cargo)
        val restored = Gson().fromJson(Gson().toJson(picked), Settlement::class.java).let(CivsRules::validate)
        restored.workers.first().cargo shouldBe plan.cargo.copy(inventoryBacked = true)
        val delivered = CivsLogistics.unload(restored, restored.workers.first().id, plan.cargo.id, 10_000)
        delivered.workers.first().cargo shouldBe null
        delivered.workers.first().completedCycles shouldBe 1L
        delivered.workers.first().skills!!["WAREHOUSE"] shouldBe 1L
        shouldThrow<CivsRejection> { CivsLogistics.unload(delivered, delivered.workers.first().id, plan.cargo.id, 11_000) }.reason shouldBe "stale-delivery"
    }

    test("carried cargo survives reassignment and a deleted destination returns to a real depot") {
        val city = logisticsCity()
        val plan = CivsLogistics.plan(city, city.workers.first()) { if (it == city.buildings[1].id) mapOf("IRON_INGOT" to 3L) else emptyMap() }!!
        val picked = CivsLogistics.pickup(city, city.workers.first().id, plan.cargo)
        val redirected = picked.copy(buildings = picked.buildings.filter { it.id != plan.cargo.targetBuildingId },
            workers = picked.workers.map { if (it.id == picked.workers.first().id || it.assignment == plan.cargo.targetBuildingId) it.copy(assignment = null) else it })
            .let(CivsRules::validate)
        val returning = CivsLogistics.plan(redirected, redirected.workers.first()) { emptyMap() }!!
        returning.carrying shouldBe true
        returning.destinationBuildingId shouldBe city.buildings[1].id
        returning.cargo.sourceEntrance shouldBe city.buildings[1].entrance
    }

    test("deleted destinations skip unreachable warehouses and use a reachable hall or the nearest reachable warehouse") {
        val base = logisticsCity()
        val original = logisticsBuilding(80, BuildingType.WAREHOUSE, 2)
        val source = logisticsBuilding(70, BuildingType.FORESTER)
        val unreachable = logisticsBuilding(-70, BuildingType.WAREHOUSE)
        val city = base.copy(buildings = listOf(base.buildings.first(), original, base.buildings[2], source, unreachable),
            workers = base.workers.map { if (it.assignment == base.buildings[1].id) it.copy(assignment = original.id) else it })
            .let(CivsRules::validate)
        val plan = CivsLogistics.plan(city, city.workers.first()) { if (it == source.id) mapOf("OAK_LOG" to 4L) else emptyMap() }!!
        val picked = CivsLogistics.pickup(city, city.workers.first().id, plan.cargo)
        val deleted = CivsRules.finishBuildingDeletion(CivsRules.requestDeletion(picked, picked.owner, original.id), picked.owner)
        val returning = CivsLogistics.plan(deleted, deleted.workers.first()) { emptyMap() }!!
        returning.carrying shouldBe true
        returning.destinationBuildingId shouldBe city.buildings.first().id
        returning.cargo shouldBe plan.cargo.copy(inventoryBacked = true)
        deleted.workers.first().cargo shouldBe plan.cargo.copy(inventoryBacked = true)
        val farther = logisticsBuilding(40, BuildingType.WAREHOUSE)
        val nearer = logisticsBuilding(60, BuildingType.WAREHOUSE)
        val alternatives = deleted.copy(buildings = deleted.buildings + listOf(farther, nearer)).let(CivsRules::validate)
        CivsLogistics.destination(alternatives, plan.cargo)?.id shouldBe nearer.id
    }

    test("continuous input requests and output backlogs share completed courier turns") {
        var city = logisticsCity()
        val warehouse = city.buildings[1]
        val smith = city.buildings[2]
        val forester = city.buildings[3]
        // Inputs are continually consumed and finished goods replenished between completed trips.
        val stock: (UUID) -> Map<String, Long> = { id -> when (id) {
            warehouse.id -> mapOf("IRON_INGOT" to 9L, "STICK" to 9L)
            smith.id -> mapOf("IRON_PICKAXE" to 4L)
            forester.id -> mapOf("OAK_LOG" to 100L)
            else -> emptyMap()
        } }
        val observed = mutableListOf<Triple<UUID, UUID, String>>()
        val amounts = mutableListOf<Long>()
        repeat(8) { index ->
            val worker = city.workers.first()
            val plan = CivsLogistics.plan(city, worker, stock)!!
            observed += Triple(plan.cargo.sourceBuildingId, plan.cargo.targetBuildingId, plan.cargo.material)
            amounts += plan.cargo.amount
            val picked = CivsLogistics.pickup(city, worker.id, plan.cargo)
            city = CivsLogistics.unload(picked, worker.id, plan.cargo.id, (index + 1) * 10_000L)
        }
        val round = listOf(Triple(warehouse.id, smith.id, "IRON_INGOT"), Triple(warehouse.id, smith.id, "STICK"),
            Triple(smith.id, warehouse.id, "IRON_PICKAXE"), Triple(forester.id, warehouse.id, "OAK_LOG"))
        observed shouldBe round + round
        amounts shouldBe listOf(3L, 2L, 4L, 64L, 3L, 2L, 4L, 64L)
        city.workers.first().completedCycles shouldBe 8L
        city.workers.first().skills!!["WAREHOUSE"] shouldBe 8L
    }

    test("eligible construction supply keeps priority over the ordinary rotation") {
        val base = logisticsCity()
        val site = logisticsBuilding(40, BuildingType.HOUSE).copy(phase = BuildingPhase.CONSTRUCTING)
        val hut = logisticsBuilding(50, BuildingType.BUILDER_HUT)
        val required = mapOf("COBBLESTONE" to 1L)
        val order = CivConstruction(UUID.randomUUID(), site.id, 1, 1, 0, ConstructionPhase.WAITING_MATERIALS,
            Stock(), plannedBuilding = site, batches = CivConstructionBatches(1, 16), materials = required,
            payment = CivConstructionPayment(awaiting = required))
        val city = base.copy(buildings = base.buildings + site + hut, construction = order, inventory = CivInventory(CivStorageMode.PHYSICAL),
            workers = base.workers.mapIndexed { index, worker -> if (index == 0) worker.copy(completedCycles = 3) else worker })
            .let(CivsRules::validate)
        val warehouse = city.buildings[1]
        val stock: (UUID) -> Map<String, Long> = { id -> when (id) {
            warehouse.id -> mapOf("COBBLESTONE" to 1L, "IRON_INGOT" to 9L, "STICK" to 9L)
            city.buildings.last { it.type == BuildingType.FORESTER }.id -> mapOf("OAK_LOG" to 64L)
            else -> emptyMap()
        } }
        val plan = CivsLogistics.plan(city, city.workers.first(), stock)!!
        plan.cargo.targetBuildingId shouldBe hut.id
        plan.cargo.material shouldBe "COBBLESTONE"
        plan.cargo.amount shouldBe 1L
    }

    test("construction demand counts only hut stores and builder bags and suppresses duplicate shipments") {
        val base = logisticsCity()
        val site = logisticsBuilding(40, BuildingType.HOUSE).copy(phase = BuildingPhase.CONSTRUCTING)
        val hut = logisticsBuilding(50, BuildingType.BUILDER_HUT)
        val worker = CivWorker(UUID.randomUUID(), "Builder", hut.id, nextDeliveryAt = 1, items = List(CivsEquipment.SLOTS) {
            if (it == CivsEquipment.BAG) CivResidentItem("STONE", 2) else null
        })
        val required = mapOf("STONE" to 10L, "WATER_BUCKET" to 1L)
        val order = CivConstruction(UUID.randomUUID(), site.id, 1, 1, 0, ConstructionPhase.WAITING_MATERIALS,
            Stock(), plannedBuilding = site, batches = CivConstructionBatches(1, 16), materials = required,
            payment = CivConstructionPayment(awaiting = required))
        val city = base.copy(buildings = base.buildings + site + hut, workers = base.workers + worker,
            inventory = CivInventory(CivStorageMode.PHYSICAL), construction = order).let(CivsRules::validate)
        val stock: (UUID) -> Map<String, Long> = { if (it == hut.id) mapOf("STONE" to 3L) else mapOf("STONE" to 64L) }
        val request = CivsLogistics.requests(city, stock).single { it.construction }
        request shouldBe CivSupplyRequest(hut.id, "STONE", 5, 0, true)
        val plan = requireNotNull(CivsLogistics.plan(city, city.workers.first(), stock))
        plan.cargo.amount shouldBe 5L
        val picked = CivsLogistics.pickup(city, city.workers.first().id, plan.cargo)
        CivsLogistics.requests(picked, stock).single { it.construction }.inTransit shouldBe 5L
        CivsLogistics.plan(picked, picked.workers[1], stock)?.cargo?.targetBuildingId.let { it == hut.id } shouldBe false
        val courier = city.workers.first()
        val oldCargo = plan.cargo.copy(targetBuildingId = site.id, id = UUID.nameUUIDFromBytes(
            "${city.id}/${courier.id}/${courier.completedCycles}/${plan.cargo.sourceBuildingId}/${site.id}/STONE"
                .toByteArray(java.nio.charset.StandardCharsets.UTF_8)))
        CivsLogistics.destination(city, oldCargo)?.id shouldBe hut.id
        val legacyPicked = CivsLogistics.pickup(city, courier.id, oldCargo)
        CivsLogistics.pickup(legacyPicked, courier.id, oldCargo) shouldBe legacyPicked
        legacyPicked.workers.first().cargo shouldBe oldCargo.copy(inventoryBacked = true)
        CivsLogistics.requests(legacyPicked, stock).single { it.construction } shouldBe CivSupplyRequest(hut.id, "STONE", 5, 5, true)
        CivsLogistics.plan(legacyPicked, legacyPicked.workers[1], stock) shouldBe null
        val delivered = CivsLogistics.unload(legacyPicked, courier.id, oldCargo.id, 10_000)
        shouldThrow<CivsRejection> { CivsLogistics.unload(delivered, courier.id, oldCargo.id, 11_000) }.reason shouldBe "stale-delivery"
        CivsLogistics.destination(city.copy(buildings = city.buildings.filterNot { it.id == hut.id }), oldCargo)?.id shouldBe city.buildings[1].id
        val upgrading = city.copy(buildings = city.buildings.map { if (it.id == site.id) it.copy(phase = BuildingPhase.ACTIVE) else it })
        CivsLogistics.destination(upgrading, oldCargo)?.id shouldBe site.id
    }

    test("a courier chooses a reachable hut when the first staffed construction hut is outside its route") {
        val base = logisticsCity()
        val warehouse = logisticsBuilding(-30, BuildingType.WAREHOUSE, 2)
        val first = logisticsBuilding(80, BuildingType.BUILDER_HUT)
        val reachable = logisticsBuilding(20, BuildingType.BUILDER_HUT)
        val site = logisticsBuilding(40, BuildingType.HOUSE).copy(phase = BuildingPhase.CONSTRUCTING)
        val required = mapOf("STONE" to 5L)
        val order = CivConstruction(UUID.randomUUID(), site.id, 1, 1, 0, ConstructionPhase.WAITING_MATERIALS,
            Stock(), plannedBuilding = site, batches = CivConstructionBatches(1, 16), materials = required,
            payment = CivConstructionPayment(awaiting = required))
        val city = base.copy(buildings = listOf(base.buildings.first(), warehouse, first, reachable, site),
            workers = base.workers.take(2).map { it.copy(assignment = warehouse.id) } +
                CivWorker(UUID.randomUUID(), "Builder", first.id, nextDeliveryAt = 1),
            inventory = CivInventory(CivStorageMode.PHYSICAL), construction = order).let(CivsRules::validate)
        val stock: (UUID) -> Map<String, Long> = { if (it == warehouse.id) mapOf("STONE" to 10L) else emptyMap() }
        CivsLogistics.requests(city, stock).single { it.construction }.buildingId shouldBe first.id
        val courier = city.workers.first()
        val plan = requireNotNull(CivsLogistics.plan(city, courier, stock))
        plan.cargo.sourceBuildingId shouldBe warehouse.id
        plan.cargo.targetBuildingId shouldBe reachable.id
        plan.cargo.amount shouldBe 5L
        shouldThrow<CivsRejection> { CivsLogistics.pickup(city, courier.id, plan.cargo.copy(targetBuildingId = first.id)) }.reason shouldBe "stale-delivery"
        val picked = CivsLogistics.pickup(city, courier.id, plan.cargo)
        CivsLogistics.pickup(picked, courier.id, plan.cargo) shouldBe picked
        CivsLogistics.requests(picked, stock).single { it.construction }.inTransit shouldBe 5L
        CivsLogistics.plan(picked, picked.workers[1], stock) shouldBe null
    }

    test("farms request seeds tools and fertilizer without water buckets") {
        val base = logisticsProducerCity(BuildingType.FARM, "WHEAT")
        val farm = base.buildings.last().copy(schematicId = "civs/minecolonies/caledonia/agriculture/horticulture/farmer1")
        val city = base.copy(buildings = base.buildings.dropLast(1) + farm)
        CivsWorksiteLayouts.site(farm)!!.getAsJsonObject("farm").get("irrigationExisting").asBoolean shouldBe false
        CivsLogistics.requests(city) { emptyMap() }.filter { it.buildingId == farm.id }.associate { it.material to it.needed } shouldBe
            mapOf("BONE_MEAL" to 16L, "IRON_HOE" to 1L, "WHEAT_SEEDS" to 16L)
    }

    test("death destroys carried goods and frees the original request without awarding a delivery") {
        val city = logisticsCity()
        val stock: (UUID) -> Map<String, Long> = { if (it == city.buildings[1].id) mapOf("IRON_INGOT" to 3L) else emptyMap() }
        val plan = CivsLogistics.plan(city, city.workers.first(), stock)!!
        val picked = CivsLogistics.pickup(city, city.workers.first().id, plan.cargo)
        val dead = CivsRules.hurtResident(picked, picked.owner, picked.workers.first().id, 100.0)
        dead.workers.none { it.id == city.workers.first().id } shouldBe true
        CivsLogistics.requests(dead, stock).single { it.material == "IRON_INGOT" }.inTransit shouldBe 0L
        dead.workers.all { CivsResidents.skillExperience(it, "WAREHOUSE") == 0L } shouldBe true
    }

    test("available finished goods are collected when requested ingredients are absent") {
        val city = logisticsCity()
        val forester = city.buildings.last()
        val plan = CivsLogistics.plan(city, city.workers.first()) { if (it == forester.id) mapOf("OAK_LOG" to 100L) else emptyMap() }!!
        plan.cargo.sourceBuildingId shouldBe forester.id
        plan.cargo.targetBuildingId shouldBe city.buildings[1].id
        plan.cargo.amount shouldBe 64L
    }

    test("quarry collection keeps its requested cobblestone reserve and takes only the surplus") {
        val city = logisticsProducerCity(BuildingType.QUARRY, "COBBLESTONE")
        val quarry = city.buildings.last()
        var cobblestone = 32L
        val stock: (UUID) -> Map<String, Long> = { if (it == quarry.id) mapOf("COBBLESTONE" to cobblestone) else emptyMap() }
        CivsLogistics.plan(city, city.workers.first(), stock) shouldBe null
        cobblestone = 64L
        val plan = requireNotNull(CivsLogistics.plan(city, city.workers.first(), stock))
        plan.cargo.sourceBuildingId shouldBe quarry.id
        plan.cargo.targetBuildingId shouldBe city.buildings[1].id
        plan.cargo.material shouldBe "COBBLESTONE"
        plan.cargo.amount shouldBe 32L
    }

    test("the retained quarry reserve scales with its assigned miners") {
        val base = logisticsProducerCity(BuildingType.QUARRY, "COBBLESTONE")
        val quarry = base.buildings.last().copy(level = 2)
        val city = base.copy(buildings = base.buildings.dropLast(1) + quarry,
            workers = base.workers + CivWorker(UUID.randomUUID(), "Second miner", quarry.id, nextDeliveryAt = 1))
            .let(CivsRules::validate)
        var cobblestone = 64L
        val stock: (UUID) -> Map<String, Long> = { if (it == quarry.id) mapOf("COBBLESTONE" to cobblestone) else emptyMap() }
        CivsLogistics.plan(city, city.workers.first(), stock) shouldBe null
        cobblestone = 96L
        CivsLogistics.plan(city, city.workers.first(), stock)!!.cargo.amount shouldBe 32L
    }

    test("a delivered quarry supply does not become an immediate reverse collection") {
        val city = logisticsProducerCity(BuildingType.QUARRY, "COBBLESTONE")
        val warehouse = city.buildings[1]
        val quarry = city.buildings.last()
        var warehouseStock = 32L
        var quarryStock = 0L
        val stock: (UUID) -> Map<String, Long> = { id -> when (id) {
            warehouse.id -> mapOf("COBBLESTONE" to warehouseStock)
            quarry.id -> mapOf("COBBLESTONE" to quarryStock)
            else -> emptyMap()
        } }
        val plan = requireNotNull(CivsLogistics.plan(city, city.workers.first(), stock))
        plan.cargo.sourceBuildingId shouldBe warehouse.id
        plan.cargo.targetBuildingId shouldBe quarry.id
        plan.cargo.amount shouldBe 32L
        val picked = CivsLogistics.pickup(city, city.workers.first().id, plan.cargo)
        warehouseStock -= plan.cargo.amount
        quarryStock += plan.cargo.amount
        val delivered = CivsLogistics.unload(picked, picked.workers.first().id, plan.cargo.id, 10_000)
        CivsLogistics.requests(delivered, stock).none { it.buildingId == quarry.id && it.material == "COBBLESTONE" } shouldBe true
        CivsLogistics.plan(delivered, delivered.workers.first(), stock) shouldBe null
    }

    test("farm fertilizer uses the existing courier supply route in a sixteen item batch") {
        val city = logisticsProducerCity(BuildingType.FARM, "WHEAT")
        val warehouse = city.buildings[1]
        val farm = city.buildings.last()
        val plan = requireNotNull(CivsLogistics.plan(city, city.workers.first()) {
            if (it == warehouse.id) mapOf("BONE_MEAL" to 64L) else emptyMap()
        })
        plan.cargo.sourceBuildingId shouldBe warehouse.id
        plan.cargo.targetBuildingId shouldBe farm.id
        plan.cargo.material shouldBe "BONE_MEAL"
        plan.cargo.amount shouldBe 16L
    }

    for (material in listOf("CARROT", "POTATO")) test("$material collection retains the farm's requested planting stock") {
        val city = logisticsProducerCity(BuildingType.FARM, material)
        val farm = city.buildings.last()
        var crops = 16L
        val stock: (UUID) -> Map<String, Long> = { if (it == farm.id) mapOf(material to crops) else emptyMap() }
        CivsLogistics.plan(city, city.workers.first(), stock) shouldBe null
        crops = 20L
        val plan = requireNotNull(CivsLogistics.plan(city, city.workers.first(), stock))
        plan.cargo.sourceBuildingId shouldBe farm.id
        plan.cargo.targetBuildingId shouldBe city.buildings[1].id
        plan.cargo.material shouldBe material
        plan.cargo.amount shouldBe 4L
    }

    test("pickup materializes real carried cargo and unload consumes only that shipment") {
        val base = logisticsCity()
        val worker = base.workers.first().copy(items = List(CivsEquipment.SLOTS) {
            if (it == CivsEquipment.BAG) CivResidentItem("IRON_INGOT", 2) else null
        })
        val city = base.copy(workers = base.workers.map { if (it.id == worker.id) worker else it })
        val plan = requireNotNull(CivsLogistics.plan(city, worker) {
            if (it == city.buildings[1].id) mapOf("IRON_INGOT" to 3L) else emptyMap()
        })
        val picked = CivsLogistics.pickup(city, worker.id, plan.cargo)
        val carrying = picked.workers.first()
        carrying.cargo!!.inventoryBacked shouldBe true
        CivsEquipment.plainCount(carrying, "IRON_INGOT") shouldBe 5
        CivsEquipment.usableCount(carrying, "IRON_INGOT") shouldBe 2
        shouldThrow<CivsRejection> { CivsEquipment.consume(carrying, "IRON_INGOT", 3) }.reason shouldBe "resident-item-reserved"
        val unloaded = CivsLogistics.unload(picked, worker.id, plan.cargo.id, 10_000)
        CivsEquipment.plainCount(unloaded.workers.first(), "IRON_INGOT") shouldBe 2
    }

    test("old cargo JSON is materialized once without replacing an existing personal stack") {
        val base = logisticsCity()
        val worker = base.workers.first().copy(items = List(CivsEquipment.SLOTS) {
            if (it == CivsEquipment.BAG) CivResidentItem("IRON_INGOT", 2) else null
        })
        val city = base.copy(workers = base.workers.map { if (it.id == worker.id) worker else it })
        val plan = requireNotNull(CivsLogistics.plan(city, worker) {
            if (it == city.buildings[1].id) mapOf("IRON_INGOT" to 3L) else emptyMap()
        })
        val legacy = city.copy(workers = city.workers.map { if (it.id == worker.id) it.copy(cargo = plan.cargo) else it })
        val json = Gson().toJsonTree(legacy).asJsonObject.also {
            it["workers"].asJsonArray[0].asJsonObject["cargo"].asJsonObject.remove("inventoryBacked")
        }
        val decoded = Gson().fromJson(json, Settlement::class.java).let(CivsRules::validate)
        decoded.workers.first().cargo!!.inventoryBacked shouldBe false
        val migrated = CivsLogistics.materializeCargo(decoded)
        CivsEquipment.plainCount(migrated.workers.first(), "IRON_INGOT") shouldBe 5
        migrated.workers.first().cargo!!.inventoryBacked shouldBe true
        CivsLogistics.materializeCargo(migrated) shouldBe migrated
        val restored = Gson().fromJson(Gson().toJson(migrated), Settlement::class.java).let(CivsRules::validate)
        CivsLogistics.materializeCargo(restored) shouldBe restored
        val unloaded = CivsLogistics.unload(restored, worker.id, plan.cargo.id, 10_000)
        CivsEquipment.plainCount(unloaded.workers.first(), "IRON_INGOT") shouldBe 2
    }

    test("full carried inventory rejects new shipments but preserves old cargo in visible overflow slots") {
        val base = logisticsCity()
        val original = base.workers.first()
        val plan = requireNotNull(CivsLogistics.plan(base, original) {
            if (it == base.buildings[1].id) mapOf("IRON_INGOT" to 3L) else emptyMap()
        })
        val full = original.copy(items = List(CivsEquipment.SLOTS) {
            if (it >= CivsEquipment.BAG) CivResidentItem("STONE", 64) else null
        })
        val city = base.copy(workers = base.workers.map { if (it.id == full.id) full else it })
        CivsLogistics.plan(city, full) { if (it == base.buildings[1].id) mapOf("IRON_INGOT" to 3L) else emptyMap() } shouldBe null
        shouldThrow<CivsRejection> { CivsLogistics.pickup(city, full.id, plan.cargo) }.reason shouldBe "resident-inventory-full"
        val legacy = city.copy(workers = city.workers.map { if (it.id == full.id) it.copy(cargo = plan.cargo) else it })
        val migrated = CivsLogistics.materializeCargo(legacy)
        val carried = migrated.workers.first()
        carried.items!!.take(CivsEquipment.SLOTS) shouldBe full.items
        carried.items!!.drop(CivsEquipment.SLOTS) shouldBe listOf(CivResidentItem("IRON_INGOT", 3))
        carried.cargo shouldBe plan.cargo.copy(inventoryBacked = true)
        CivsLogistics.materializeCargo(migrated) shouldBe migrated
        val restored = Gson().fromJson(Gson().toJson(migrated), Settlement::class.java).let(CivsRules::validate)
        val unloaded = CivsLogistics.unload(restored, full.id, plan.cargo.id, 10_000)
        CivsEquipment.plainCount(unloaded.workers.first(), "IRON_INGOT") shouldBe 0
        CivsEquipment.plainCount(unloaded.workers.first(), "STONE") shouldBe 27 * 64
    }

    test("legacy unstackable cargo retains all sixty-four native items") {
        val base = logisticsCity()
        val plan = requireNotNull(CivsLogistics.plan(base, base.workers.first()) {
            if (it == base.buildings[1].id) mapOf("IRON_INGOT" to 3L) else emptyMap()
        })
        val worker = base.workers.first().copy(cargo = plan.cargo.copy(material = "IRON_PICKAXE", amount = 64))
        val migrated = CivsLogistics.materializeCargo(base.copy(workers = listOf(worker)))
        val carried = migrated.workers.single()
        CivsEquipment.plainCount(carried, "IRON_PICKAXE") shouldBe 64
        carried.items!!.filterNotNull().all { it.amount == 1 } shouldBe true
        carried.items!!.size shouldBe 69
        CivsEquipment.supplyRoom(carried, org.bukkit.Material.STONE) shouldBe 0
        CivsLogistics.materializeCargo(migrated) shouldBe migrated
    }

    test("malformed or unbacked legacy cargo fails snapshot validation") {
        val city = logisticsCity()
        val plan = CivsLogistics.plan(city, city.workers.first()) { if (it == city.buildings[1].id) mapOf("IRON_INGOT" to 3L) else emptyMap() }!!
        CivsLogistics.validCargo(city, plan.cargo.copy(amount = 0)) shouldBe false
        CivsLogistics.validCargo(city.copy(inventory = null), plan.cargo) shouldBe false
        val tree = Gson().toJsonTree(plan.cargo).asJsonObject.also { it.remove("material") }
        CivsLogistics.validCargo(city, Gson().fromJson(tree, CivCargo::class.java)) shouldBe false
    }
})

private fun logisticsBuilding(x: Int, type: BuildingType, level: Int = 1) = CivBuilding(UUID.randomUUID(), type, level,
    CivPosition(x, 64, 0), 0, "test", CivBox(CivPosition(x - 2, 64, -2), CivPosition(x + 2, 68, 2)),
    CivPosition(x, 64, 3), BuildingPhase.ACTIVE, control = CivPosition(x, 63, 0),
    production = if (type == BuildingType.BLACKSMITH) "IRON_PICKAXE" else null)

private fun logisticsCity(): Settlement {
    val buildings = listOf(logisticsBuilding(0, BuildingType.TOWN_HALL, 2), logisticsBuilding(10, BuildingType.WAREHOUSE, 2),
        logisticsBuilding(20, BuildingType.BLACKSMITH), logisticsBuilding(30, BuildingType.FORESTER))
    return CivsRules.validate(Settlement(UUID.randomUUID(), UUID.randomUUID(), "Cargo", UUID.randomUUID(),
        CivPosition(0, 64, 0), 96, "nordic", Stock(), buildings,
        listOf(CivWorker(UUID.randomUUID(), "Courier 1", buildings[1].id, nextDeliveryAt = 1),
            CivWorker(UUID.randomUUID(), "Courier 2", buildings[1].id, nextDeliveryAt = 1),
            CivWorker(UUID.randomUUID(), "Smith", buildings[2].id, nextDeliveryAt = 1)), inventory = CivInventory(CivStorageMode.VIRTUAL)))
}

private fun logisticsProducerCity(type: BuildingType, production: String): Settlement {
    val base = logisticsCity()
    val producer = base.buildings.last().copy(type = type, production = production)
    return base.copy(buildings = base.buildings.dropLast(1) + producer,
        workers = base.workers + CivWorker(UUID.randomUUID(), "Producer", producer.id, nextDeliveryAt = 1)).let(CivsRules::validate)
}
