package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsAutoStorageMigrationTest : FunSpec({
    test("remaining closed reserves migrate once without copying native bags") {
        val base = physicalAutoFixture()
        val forest = base.buildings.first { it.type == BuildingType.FORESTER }
        val warehouse = base.buildings.first { it.type == BuildingType.WAREHOUSE }
        val producer = CivWorker(UUID.randomUUID(), "Forester", forest.id, nextDeliveryAt = 1,
            pendingStock = Stock(5, 0, 2), items = List(CivsEquipment.SLOTS) { if (it == CivsEquipment.BAG) CivResidentItem("BREAD", 12) else null })
        val cargo = CivCargo(UUID.randomUUID(), warehouse.id, forest.id, warehouse.id, forest.entrance,
            "STONE", 3, sourceWorkerId = producer.id, stockResource = Resource.STONE)
        val carrier = CivWorker(UUID.randomUUID(), "Courier", warehouse.id, nextDeliveryAt = 1, cargo = cargo)
        val legacy = CivsRules.validate(base.copy(stock = Stock(10, 20, 7), inventory = null,
            workers = listOf(producer, carrier), autonomy = CivAutonomy(products = mapOf(
                CivIndustryProduct.RAW_IRON to 2L, CivIndustryProduct.IRON_TOOL_KIT to 1L))))
        val migrated = CivsAutoStorageMigration.migrate(legacy)
        migrated.stock shouldBe Stock()
        migrated.inventory!!.materials shouldBe mapOf("OAK_LOG" to 10L, "COBBLESTONE" to 20L, "BREAD" to 7L,
            "RAW_IRON" to 2L, "IRON_PICKAXE" to 1L)
        migrated.inventory!!.buildingMaterials shouldBe mapOf(forest.id.toString() to mapOf("OAK_LOG" to 5L, "BREAD" to 2L),
            warehouse.id.toString() to mapOf("COBBLESTONE" to 3L))
        migrated.workers.first().items shouldBe producer.items
        migrated.workers.first().pendingStock shouldBe null
        migrated.workers.last().cargo shouldBe null
        migrated.autonomy!!.products shouldBe null
        migrated.revision shouldBe legacy.revision + 1
        CivsAutoStorageMigration.migrate(migrated) shouldBe migrated
        CivsAutoStorageMigration.ready(migrated) shouldBe false
        CivsAutoStorageMigration.ready(migrated.copy(inventory = migrated.inventory!!.copy(materials = emptyMap(), buildingMaterials = null))) shouldBe true
    }

    test("ordinary cities and native auto inventories are never issued another reserve") {
        val city = physicalAutoFixture()
        CivsAutoStorageMigration.migrate(city) shouldBe city
        CivsAutoStorageMigration.migrate(city.copy(autonomy = null)) shouldBe city.copy(autonomy = null)
    }

    test("new auto towns use ordinary physical starting reserves and native research terms") {
        val city = physicalAutoFixture()
        city.inventory!!.mode shouldBe CivStorageMode.PHYSICAL
        city.stock shouldBe Stock()
        for (research in CivResearchId.entries)
            CivsResearch.cost(city, research) shouldBe CivsResearch.cost(city.copy(autonomy = null), research)
        CivsAutonomy.nextGoal(city)!!.ready shouldBe true
    }

    test("new physical auto construction accepts the same native material payment as ordinary towns") {
        val city = physicalAutoFixture()
        val hut = city.buildings.first { it.type == BuildingType.BUILDER_HUT }
        val target = hut.copy(level = 2)
        val order = CivConstruction(UUID.randomUUID(), hut.id, 2, 1, 0, ConstructionPhase.CONSTRUCTING,
            Stock(), batches = CivConstructionBatches(1, 16, true), materials = mapOf("OAK_PLANKS" to 16L),
            payment = CivConstructionPayment(), blocksRequired = 16, blocksPlaced = 0)
        val ready = city.copy(buildings = city.buildings.map { if (it.type == BuildingType.TOWN_HALL) it.copy(level = 2) else it })
        val automatic = CivsRules.reserveBuild(ready, ready.owner, target, order)
        val ordinary = CivsRules.reserveBuild(ready.copy(autonomy = null), ready.owner, target, order)
        automatic.construction shouldBe ordinary.construction
        automatic.stock shouldBe Stock()
    }

    test("migration preserves an unfinished paid job even with full closed pending goods") {
        val base = physicalAutoFixture()
        val forest = base.buildings.first { it.type == BuildingType.FORESTER }
        val root = forest.anchor
        val job = CivForestryJob(UUID.randomUUID(), root, "OAK_SAPLING", listOf(root),
            listOf("minecraft:oak_log[axis=y]"), listOf((1 shl 10) or (15 shl 5) or 15, (15 shl 5) or 15),
            next = 1, harvestedLogs = 1, seedDeferred = true)
        val worker = CivWorker(UUID.randomUUID(), "Forester", forest.id, nextDeliveryAt = 1, pendingStock = Stock(999_999))
        val legacy = base.copy(stock = Stock(1), inventory = null, workers = listOf(worker),
            forestryJobs = mapOf(forest.id.toString() to job))
        val migrated = CivsAutoStorageMigration.migrate(legacy)
        migrated.forestryJobs shouldBe legacy.forestryJobs
        migrated.inventory!!.materials shouldBe mapOf("OAK_LOG" to 1L)
        migrated.inventory!!.buildingStock(forest.id) shouldBe mapOf("OAK_LOG" to 999_999L)
        migrated.workers.single().pendingStock shouldBe null
        CivsAutoStorageMigration.migrate(migrated) shouldBe migrated
        CivsAutoStorageMigration.waiting(legacy) shouldBe true
        CivsAutoStorageMigration.canMigrate(legacy) shouldBe true
    }

    test("accepted unfunded and funded research bills migrate without charging again") {
        val base = physicalAutoFixture()
        val university = autoFixtureBuilding(BuildingType.UNIVERSITY, 6)
        val legacy = base.copy(stock = Stock(30, 30, 10), inventory = null, buildings = base.buildings + university)
        val started = CivsResearch.begin(legacy, CivResearchId.MATERIALS, UUID.randomUUID(), 2_000)
        val accepted = started.research!!.project!!
        for (before in listOf(started, CivsResearch.fund(started, accepted.id, 3_000))) {
            val migrated = CivsAutoStorageMigration.migrate(before)
            val project = migrated.research!!.project!!
            project.funded shouldBe before.research!!.project!!.funded
            project.cost shouldBe CivResearchCost(materials = mapOf("OAK_LOG" to 24L, "COBBLESTONE" to 16L, "BREAD" to 4L))
            if (project.funded) CivsResearch.fund(migrated, project.id, 4_000, nativePaid = true) shouldBe migrated
        }
    }

    test("corpse closed town goods migrate once while personal items and death ownership remain exact") {
        val base = physicalAutoFixture()
        val forest = base.buildings.first { it.type == BuildingType.FORESTER }
        val depot = base.buildings.first { it.type == BuildingType.WAREHOUSE }
        val fallen = CivWorker(UUID.randomUUID(), "Fallen", depot.id, nextDeliveryAt = 1,
            pendingStock = Stock(2, 3, 4), items = List(CivsEquipment.SLOTS) {
                if (it == CivsEquipment.BAG) CivResidentItem("BREAD", 6) else null
            }, cargo = CivCargo(UUID.randomUUID(), depot.id, forest.id, depot.id, forest.entrance,
                "WOOD", 1, sourceWorkerId = UUID.randomUUID(), stockResource = Resource.WOOD))
        val corpse = CivsCorpses.create(fallen, CivDeathContext(forest.anchor, 2_000))
        val legacy = CivsRules.validate(base.copy(stock = Stock(), inventory = null, corpses = listOf(corpse)))
        val migrated = CivsAutoStorageMigration.migrate(legacy)
        migrated.inventory!!.materials shouldBe emptyMap()
        migrated.inventory!!.buildingStock(depot.id) shouldBe mapOf("OAK_LOG" to 3L, "COBBLESTONE" to 3L, "BREAD" to 4L)
        migrated.corpses!!.single() shouldBe corpse.copy(worker = corpse.worker.copy(pendingStock = null, cargo = null))
        CivsAutoStorageMigration.migrate(migrated) shouldBe migrated
    }

    test("auto supplies are bounded and rotate requests while ordinary towns receive none") {
        val base = physicalAutoFixture()
        val workplaces = base.buildings.filter { it.type in setOf(BuildingType.FORESTER, BuildingType.FARM, BuildingType.QUARRY) }
        val city = base.copy(workers = workplaces.map { building ->
            CivWorker(UUID.randomUUID(), building.type.name, building.id, nextDeliveryAt = 1)
        })
        val supplies = CivsAutoSupplies()
        val observed = supplyObservation(city)
        val demands = CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY, observed)
        demands.isNotEmpty() shouldBe true
        demands.all { it.required.size <= 8 && it.required.values.all { count -> count in 1..64 } } shouldBe true
        List(demands.size) { supplies.next(city, null, CivsResidentFoods.BREAD_ONLY, observed) }.toSet() shouldBe demands.toSet()
        CivsAutoSupplies.demands(city.copy(autonomy = null), null, CivsResidentFoods.BREAD_ONLY) shouldBe emptyList()
        CivsAutoSupplies.demands(city.copy(autonomy = CivAutonomy(paused = true)), null, CivsResidentFoods.BREAD_ONLY) shouldBe emptyList()
        CivsAutoSupplies.demands(city.copy(inventory = city.inventory!!.copy(materials = mapOf("OAK_LOG" to 1L))),
            null, CivsResidentFoods.BREAD_ONLY) shouldBe emptyList()
    }
})

internal fun physicalAutoFixture(): Settlement {
    val id = UUID.randomUUID()
    val owner = UUID.randomUUID()
    val hall = autoFixtureBuilding(BuildingType.TOWN_HALL, 0)
    val founded = CivsAutonomy.found(id, owner, "Physical", UUID.randomUUID(), CivPosition(0, 64, 0), 96,
        "nordic", hall, 1_000)
    return CivsRules.validate(founded.copy(buildings = listOf(hall) + listOf(BuildingType.BUILDER_HUT,
        BuildingType.FORESTER, BuildingType.QUARRY, BuildingType.FARM, BuildingType.WAREHOUSE).mapIndexed { index, type ->
        autoFixtureBuilding(type, index + 1)
    }, inventory = CivInventory(CivStorageMode.PHYSICAL)))
}

private fun autoFixtureBuilding(type: BuildingType, index: Int): CivBuilding {
    val point = CivPosition(index * 12, 64, 0)
    return CivBuilding(UUID.randomUUID(), type, 1, point, 0, "test/${type.name.lowercase()}",
        CivBox(point.copy(x = point.x - 2, y = 62, z = -2), point.copy(x = point.x + 2, y = 68, z = 2)),
        point.copy(z = -3), BuildingPhase.ACTIVE, point)
}
