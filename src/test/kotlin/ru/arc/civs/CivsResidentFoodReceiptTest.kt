package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

class CivsResidentFoodReceiptTest : FunSpec({
    lateinit var h: ResidentFoodFixture
    beforeEach { h = ResidentFoodFixture() }
    afterEach { h.close() }

    for (automatic in listOf(false, true)) test("an unstaffed native tavern is preferred over a nearer hall and its pickup replays once Auto=$automatic") {
        val hall = h.city.buildings.single()
        val point = CivPosition(8, 64, 0)
        val tavern = hall.copy(id = UUID.randomUUID(), type = BuildingType.TAVERN, anchor = point, control = point,
            bounds = CivBox(CivPosition(7, 64, -1), CivPosition(10, 68, 2)), entrance = CivPosition(8, 64, -2))
        h.city = h.city.copy(buildings = listOf(hall, tavern), stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
            autonomy = CivAutonomy().takeIf { automatic })
        h.world.getBlockAt(8, 64, 0).type = Material.BARREL
        val venue = (h.world.getBlockAt(8, 64, 0).state as Barrel).inventory
        h.barrel.setItem(0, ItemStack(Material.BREAD, 20))
        venue.setItem(0, ItemStack(Material.BREAD, 20))
        val source = requireNotNull(h.storage.residentFoodSource(h.city, h.worker, h.feet))
        source.buildingId shouldBe tavern.id
        h.feet.x = source.standing.x + .5; h.feet.y = source.standing.y.toDouble(); h.feet.z = source.standing.z + .5
        val tx = h.pickup(source)
        h.barrel.getItem(0)!!.amount shouldBe 20
        venue.getItem(0)!!.amount shouldBe 20
        h.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        h.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        h.barrel.getItem(0)!!.amount shouldBe 20
        venue.getItem(0)!!.amount shouldBe 16
        CivsEquipment.usableCount(tx.after.workers.single(), "BREAD") shouldBe 4
        tx.after.workers.single().hunger shouldBe 14 // A real pickup does not silently feed the resident.
        h.storage.unlock(tx.id)
    }

    test("a full innkeeper picks up a seated guest order into its own durable bag exactly once") {
        val originalHall = h.city.buildings.single()
        val hall = originalHall.copy(anchor = CivPosition(8, 64, 0), control = CivPosition(8, 64, 0),
            bounds = CivBox(CivPosition(6, 64, -2), CivPosition(10, 68, 2)), entrance = CivPosition(8, 64, -2))
        val tavern = originalHall.copy(id = UUID.randomUUID(), type = BuildingType.TAVERN)
        val guest = h.worker
        val keeper = CivWorker(UUID.randomUUID(), "Innkeeper", tavern.id, nextDeliveryAt = 1, hunger = 20)
        h.city = CivsRules.validate(h.city.copy(buildings = listOf(hall, tavern), workers = listOf(guest, keeper)))
        h.barrel.setItem(0, ItemStack(Material.BREAD, 4))
        val source = CivResidentFoodSource(tavern.id, h.point, CivPosition(1, 64, 0), servingFor = guest.id,
            servingTicket = UUID.randomUUID())
        val before = h.city
        val id = UUID.randomUUID()
        h.storage.reserve(before, id)
        shouldThrow<CivsRejection> { h.storage.prepareFoodPickup(before, keeper.id,
            source.copy(servingFor = null, servingTicket = null), h.feet) }
            .reason shouldBe "resident-food-missing"
        shouldThrow<CivsRejection> { h.storage.prepareFoodPickup(before, keeper.id, source.copy(servingTicket = null), h.feet) }
            .reason shouldBe "stale-meal"
        val prepared = h.storage.prepareFoodPickup(before, keeper.id, source, h.feet)
        val tx = CivInventoryTransaction(id, before.id, before.revision, before.copy(workers = before.workers.map {
            if (it.id == keeper.id) prepared.worker else it
        }, revision = before.revision + 1), emptyList(), collected = requireNotNull(prepared.transfer))
        h.storage.seal(tx)
        val saved = h.journal.submitCollected(before, tx)
        h.ticks()
        saved.join() shouldBe tx.after
        h.barrel.getItem(0) shouldBe null
        h.journal.recover(); h.ticks()
        h.city shouldBe tx.after
        CivsEquipment.plainCount(h.city.workers.first { it.id == keeper.id }, "BREAD") shouldBe 4
        CivsEquipment.plainCount(h.city.workers.first { it.id == guest.id }, "BREAD") shouldBe 0
        h.city.workers.first { it.id == guest.id }.hunger shouldBe 14
        tx.collected!!.food!!.servingFor shouldBe guest.id
        tx.collected!!.food!!.servingTicket shouldBe source.servingTicket
        shouldThrow<IllegalArgumentException> { tx.copy(after = tx.after.copy(workers = tx.after.workers.map {
            if (it.id == keeper.id) it.copy(assignment = hall.id) else it
        })).validated() }
    }

    for (legacy in listOf(false, true))
    test("an accepted serving receipt recovers without the retired actor session legacy=$legacy") {
        val originalHall = h.city.buildings.single()
        val hall = originalHall.copy(anchor = CivPosition(8, 64, 0), control = CivPosition(8, 64, 0),
            bounds = CivBox(CivPosition(6, 64, -2), CivPosition(10, 68, 2)), entrance = CivPosition(8, 64, -2))
        val tavern = originalHall.copy(id = UUID.randomUUID(), type = BuildingType.TAVERN)
        val guest = h.worker
        val keeper = CivWorker(UUID.randomUUID(), "Innkeeper", tavern.id, nextDeliveryAt = 1, hunger = 20)
        h.city = CivsRules.validate(h.city.copy(buildings = listOf(hall, tavern), workers = listOf(guest, keeper)))
        h.barrel.setItem(0, ItemStack(Material.BREAD, 8))
        val ticket = UUID.randomUUID()
        val source = CivResidentFoodSource(tavern.id, h.point, CivPosition(1, 64, 0), servingFor = guest.id, servingTicket = ticket)
        val before = h.city
        val id = UUID.randomUUID()
        h.storage.reserve(before, id)
        val prepared = h.storage.prepareFoodPickup(before, keeper.id, source, h.feet)
        val tx = CivInventoryTransaction(id, before.id, before.revision, before.copy(workers = before.workers.map {
            if (it.id == keeper.id) prepared.worker else it
        }, revision = before.revision + 1), emptyList(), collected = requireNotNull(prepared.transfer))
        val gson = com.google.gson.Gson()
        val document = gson.toJsonTree(tx).asJsonObject
        if (legacy) document.getAsJsonObject("collected").getAsJsonObject("food").remove("servingTicket")
        val accepted = gson.fromJson(document, CivInventoryTransaction::class.java).validated()
        accepted.collected!!.food!!.servingTicket shouldBe if (legacy) null else ticket
        h.storage.unlock(id)
        every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(accepted))
        every { h.store.loadConsumedItems() } returns CompletableFuture.completedFuture(CivConsumedItemIndex.EMPTY)
        h.freshAllowed = false
        val boot = h.journal.bootstrap()
        h.ticks()
        boot.join()
        repeat(2) { h.journal.recover(); h.ticks() }
        h.city shouldBe accepted.after
        h.barrel.getItem(0)!!.amount shouldBe 4
        CivsEquipment.plainCount(h.city.workers.first { it.id == keeper.id }, "BREAD") shouldBe 4L
        CivsEquipment.plainCount(h.city.workers.first { it.id == guest.id }, "BREAD") shouldBe 0L
        h.journal.pendingCount shouldBe 0
        verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        verify(exactly = 1) { h.store.deleteInventoryTransaction(id) }
    }

    test("a large empty tavern does not hide real emergency hall food behind the native read window") {
        val hall = h.city.buildings.single()
        val point = CivPosition(8, 64, 0)
        val extra = (4..15).flatMap { x -> (2..4).map { z -> CivPosition(x, 64, z) } }.take(20)
        val tavern = hall.copy(id = UUID.randomUUID(), type = BuildingType.TAVERN, anchor = point, control = point,
            bounds = CivBox(CivPosition(4, 64, -1), CivPosition(15, 68, 5)), entrance = CivPosition(8, 64, -2))
        (extra + point).forEach { h.world.getBlockAt(it.x, it.y, it.z).type = Material.BARREL }
        h.city = h.city.copy(buildings = listOf(hall, tavern), stock = Stock(),
            inventory = CivInventory(CivStorageMode.PHYSICAL, containers = extra))
        h.barrel.setItem(0, ItemStack(Material.BREAD, 4))
        h.storage.residentFoodSource(h.city, h.worker, h.feet) shouldBe null
        requireNotNull(h.storage.residentFoodSource(h.city, h.worker, h.feet)).buildingId shouldBe hall.id
        h.barrel.getItem(0)!!.amount shouldBe 4
        h.worker.hunger shouldBe 14
    }

    test("Auto materialization debits one existing FOOD per real bread and pickup only transfers that bread") {
        val initial = h.city
        val tx = h.materialize()
        h.barrel.getItem(0) shouldBe null
        tx.after.stock.food shouldBe initial.stock.food - 4
        tx.collected!!.food!!.materialized shouldBe 4
        h.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        h.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        h.barrel.getItem(0)!!.amount shouldBe 4
        h.storage.unlock(tx.id)
        h.city = tx.after
        val pickup = h.pickup()
        pickup.after.stock shouldBe tx.after.stock
        CivsEquipment.usableCount(pickup.after.workers.single(), "BREAD") shouldBe 4
        h.storage.reconcile(pickup) shouldBe CivInventoryApplyResult.Applied
        h.storage.reconcile(pickup) shouldBe CivInventoryApplyResult.Applied
        h.barrel.getItem(0) shouldBe null
        shouldThrow<IllegalArgumentException> { tx.copy(after = tx.after.copy(stock = initial.stock)).validated() }
    }

    test("an ordinary native apple moves from its real chest to the bag and restores four hunger without FOOD debit") {
        val foods = CivsResidentFoods(mapOf("BREAD" to CivResidentFoodDefinition(6), "APPLE" to CivResidentFoodDefinition(4)))
        h.storage.residentFoods = foods
        h.barrel.setItem(0, ItemStack(Material.APPLE, 4))
        val selected = requireNotNull(h.storage.residentFoodSource(h.city, h.worker, h.feet))
        selected.material shouldBe "APPLE"
        val before = h.city
        val tx = h.pickup(selected)
        val picking = h.journal.submitCollected(before, tx)
        h.ticks()
        picking.join() shouldBe tx.after
        h.barrel.getItem(0) shouldBe null
        CivsEquipment.usableCount(h.worker, "APPLE") shouldBe 4
        val plan = requireNotNull(CivsResidentLife.mealPlan(h.city, h.worker, foods = foods))
        plan.material shouldBe "APPLE"
        plan.nutrition shouldBe 4
        plan.amount shouldBe 1
        h.city = CivsResidentLife.finishMeal(h.city, plan, foods)
        h.worker.hunger shouldBe 18
        h.city.stock shouldBe before.stock
        CivsEquipment.usableCount(h.worker, "APPLE") shouldBe 3
        shouldThrow<CivsRejection> { CivsResidentLife.finishMeal(h.city, plan, foods) }.reason shouldBe "stale-meal"
        val root = Files.createTempDirectory("civs-apple-receipt-")
        try {
            CivsStore(root, Executor(Runnable::run)).use { store ->
                store.saveInventoryTransaction(tx).join() shouldBe tx
                store.loadInventoryTransactions().join().single().collected!!.food!!.material shouldBe "APPLE"
            }
        } finally { root.toFile().deleteRecursively() }
    }

    test("named food and cargo never become a meal and native work ingredients remain reserved") {
        val foods = CivsResidentFoods(mapOf("BREAD" to CivResidentFoodDefinition(6), "APPLE" to CivResidentFoodDefinition(4),
            "CARROT" to CivResidentFoodDefinition(3)))
        h.storage.residentFoods = foods
        val named = ItemStack(Material.APPLE, 4).apply { editMeta { it.displayName(net.kyori.adventure.text.Component.text("Keepsake")) } }
        h.barrel.setItem(0, named)
        h.storage.residentFoodSource(h.city, h.worker, h.feet) shouldBe null
        val namedWorker = h.worker.copy(items = List(CivsEquipment.SLOTS) { if (it == CivsEquipment.BAG) CivResidentItem.of(named) else null })
        CivsResidentLife.mealPlan(h.city.copy(workers = listOf(namedWorker)), namedWorker, foods = foods) shouldBe null
        val farm = h.city.buildings.single().copy(id = UUID.randomUUID(), type = BuildingType.FARM, production = "CARROT")
        val worker = CivsEquipment.collectSupplies(h.worker.copy(assignment = farm.id, nextDeliveryAt = 1), mapOf("CARROT" to 4L))
        val city = h.city.copy(buildings = h.city.buildings + farm, workers = listOf(worker))
        foods.available(city, worker, "CARROT") shouldBe 0
        foods.mayPickUp(city, worker, "CARROT") shouldBe false
        CivsResidentLife.mealPlan(city, worker, foods = foods) shouldBe null
        val cargo = CivCargo(UUID.randomUUID(), farm.id, farm.id, farm.id, farm.entrance, "APPLE", 4, inventoryBacked = true)
        val courier = CivsEquipment.collectSupplies(h.worker, mapOf("APPLE" to 4L)).copy(cargo = cargo)
        foods.available(h.city.copy(workers = listOf(courier)), courier, "APPLE") shouldBe 0
    }

    test("empty stock never materializes bread and an empty chest never grants a personal item") {
        h.city = h.city.copy(stock = Stock())
        val id = UUID.randomUUID()
        h.storage.reserve(h.city, id)
        shouldThrow<CivsRejection> { h.storage.prepareFoodMaterialization(h.city, h.worker.id, h.feet) }.reason shouldBe "resident-food-missing"
        shouldThrow<CivsRejection> { h.storage.prepareFoodPickup(h.city, h.worker.id, h.source, h.feet) }.reason shouldBe "resident-food-missing"
        h.barrel.getItem(0) shouldBe null
        CivsEquipment.usableCount(h.worker, "BREAD") shouldBe 0
    }

    test("materialization is bounded by the remaining stock and never refills an existing four-bread buffer") {
        h.city = h.city.copy(stock = Stock(food = 2))
        val tx = h.materialize()
        tx.after.stock.food shouldBe 0L
        tx.collected!!.food!!.materialized shouldBe 2
        h.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        h.storage.unlock(tx.id)
        h.city = tx.after.copy(stock = Stock(food = 20))
        h.barrel.setItem(0, ItemStack(Material.BREAD, 4))
        val id = UUID.randomUUID()
        h.storage.reserve(h.city, id)
        h.storage.prepareFoodMaterialization(h.city, h.worker.id, h.feet).transfer shouldBe null
        h.city.stock.food shouldBe 20L
    }

    test("a full resident still has a physical four-bread public reserve between distinct transfers") {
        h.city = h.city.copy(workers = h.city.workers.map { it.copy(hunger = 20) })
        val initial = h.city
        val tx = h.materialize()
        h.journal.reserveCollection(initial.worldId, tx.id)
        val saving = h.journal.submitCollected(initial, tx)
        h.ticks()
        saving.join() shouldBe tx.after
        repeat(4) { h.ticks(); h.barrel.getItem(0)!!.amount shouldBe 4 }
        h.journal.collectionAvailable(h.city.worldId) shouldBe false
        CivsEquipment.usableCount(h.worker, "BREAD") shouldBe 0
        val id = UUID.randomUUID()
        h.storage.reserve(h.city, id)
        h.storage.prepareFoodMaterialization(h.city, h.worker.id, h.feet).transfer shouldBe null
        h.barrel.getItem(0)!!.amount shouldBe 4
        h.city.stock.food shouldBe initial.stock.food - 4
    }

    test("source search chooses the closest stocked actual permitted container and empty reserves fall back to the hall") {
        val hall = h.city.buildings.single()
        val house = hall.copy(id = UUID.randomUUID(), type = BuildingType.HOUSE, anchor = CivPosition(8, 64, 0),
            control = CivPosition(8, 64, 0), entrance = CivPosition(7, 64, 0),
            bounds = CivBox(CivPosition(6, 64, -2), CivPosition(10, 68, 2)))
        val resident = h.worker.copy(homeId = house.id)
        h.city = h.city.copy(buildings = listOf(hall, house), workers = listOf(resident))
        h.world.getBlockAt(8, 64, 0).type = Material.BARREL
        val home = (h.world.getBlockAt(8, 64, 0).state as Barrel).inventory
        home.setItem(0, ItemStack(Material.BREAD, 3))
        h.barrel.setItem(0, ItemStack(Material.BREAD, 4))
        h.storage.residentFoodSource(h.city, resident, Location(h.world, 7.5, 64.0, .5))!!.buildingId shouldBe house.id
        home.setItem(0, null)
        h.storage.residentFoodSource(h.city, resident, Location(h.world, 7.5, 64.0, .5))!!.buildingId shouldBe hall.id
        h.barrel.setItem(0, null)
        h.storage.residentFoodSource(h.city, resident, h.feet) shouldBe null
        val tx = h.materialize()
        tx.collected!!.food!!.buildingId shouldBe hall.id
        h.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        home.getItem(0) shouldBe null
        h.barrel.getItem(0)!!.amount shouldBe 4
    }

    test("food access includes public stores own home and assigned work but rejects another home and city") {
        val hall = h.city.buildings.single()
        for (type in listOf(BuildingType.TOWN_HALL, BuildingType.TAVERN, BuildingType.WAREHOUSE, BuildingType.HOUSE, BuildingType.FARM)) {
            val source = hall.copy(type = type)
            val resident = h.worker.copy(homeId = source.id.takeIf { type == BuildingType.HOUSE },
                assignment = source.id.takeIf { type == BuildingType.FARM }, nextDeliveryAt = if (type == BuildingType.FARM) 1 else 0)
            CivsResidentFood.eligible(h.city.copy(buildings = listOf(source), workers = listOf(resident)), resident, source) shouldBe true
        }
        CivsResidentFood.eligible(h.city, h.worker, hall.copy(id = UUID.randomUUID(), type = BuildingType.HOUSE)) shouldBe false
        val id = UUID.randomUUID()
        h.storage.reserve(h.city, id)
        shouldThrow<CivsRejection> { h.storage.prepareFoodPickup(h.city, h.worker.id,
            h.source.copy(buildingId = UUID.randomUUID()), h.feet) }.reason shouldBe "building-not-found"
        shouldThrow<CivsRejection> { h.storage.prepareFoodPickup(h.city, h.worker.id,
            h.source.copy(container = CivPosition(90, 64, 0)), h.feet) }.reason shouldBe "worker-too-far"
    }

    test("ordinary city couriers and home deposits retain supported personal food at the final transfer boundary") {
        val foods = CivsResidentFoods(mapOf("BREAD" to CivResidentFoodDefinition(6), "APPLE" to CivResidentFoodDefinition(4)))
        val hall = h.city.buildings.single()
        val farm = hall.copy(id = UUID.randomUUID(), type = BuildingType.FARM, control = CivPosition(8, 64, 0),
            anchor = CivPosition(8, 64, 0), entrance = CivPosition(8, 64, -2),
            bounds = CivBox(CivPosition(6, 64, -2), CivPosition(10, 68, 2)))
        val warehouse = hall.copy(id = UUID.randomUUID(), type = BuildingType.WAREHOUSE, control = CivPosition(12, 64, 0),
            anchor = CivPosition(12, 64, 0), entrance = CivPosition(12, 64, -2),
            bounds = CivBox(CivPosition(11, 64, -2), CivPosition(14, 68, 2)))
        val producer = CivsEquipment.collectSupplies(h.worker.copy(assignment = farm.id, nextDeliveryAt = 1), mapOf("APPLE" to 4L))
        val courier = CivWorker(UUID.randomUUID(), "Courier", warehouse.id, nextDeliveryAt = 1)
        val city = CivsRules.validate(h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL), autonomy = null,
            buildings = listOf(hall, farm, warehouse), workers = listOf(producer, courier)))
        CivsLogistics.workerExcess(city, producer)["APPLE"] shouldBe 4L // The exact previous bread-only policy.
        CivsLogistics.workerExcess(city, producer, foods = foods)["APPLE"] shouldBe null
        CivsEquipment.homeSurplus(producer, CivsLogistics.workerReserve(city, producer, foods = foods)) shouldBe emptyMap()
        val id = CivsLogistics.shipmentId(city.id, courier.id, courier.completedCycles, farm.id, warehouse.id, "APPLE", producer.id)
        val cargo = CivCargo(id, warehouse.id, farm.id, warehouse.id, CivPosition(9, 64, 0), "APPLE", 4,
            sourceWorkerId = producer.id)
        shouldThrow<CivsRejection> { CivsLogistics.pickup(city, courier.id, cargo, foods = foods) }.reason shouldBe "stale-delivery"
        CivsEquipment.usableCount(producer, "APPLE") shouldBe 4
        CivsEquipment.usableCount(courier, "APPLE") shouldBe 0
    }

    test("personal crop food is retained in addition to its actual profession ingredients") {
        val foods = CivsResidentFoods(mapOf("BREAD" to CivResidentFoodDefinition(6), "CARROT" to CivResidentFoodDefinition(3)))
        val hall = h.city.buildings.single()
        val farm = hall.copy(id = UUID.randomUUID(), type = BuildingType.FARM, production = "CARROT", control = CivPosition(8, 64, 0),
            anchor = CivPosition(8, 64, 0), entrance = CivPosition(8, 64, -2),
            bounds = CivBox(CivPosition(6, 64, -2), CivPosition(10, 68, 2)))
        val producer = h.worker.copy(assignment = farm.id, nextDeliveryAt = 1)
        var city = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL), autonomy = null,
            buildings = listOf(hall, farm), workers = listOf(producer))
        val ingredients = requireNotNull(CivsLogistics.workerWorkReserve(city, producer)["CARROT"])
        val supplied = CivsEquipment.collectSupplies(producer, mapOf("CARROT" to ingredients + 4))
        city = city.copy(workers = listOf(supplied))
        CivsLogistics.workerReserve(city, supplied, foods = foods)["CARROT"] shouldBe ingredients + 16
        CivsLogistics.workerExcess(city, supplied, foods = foods)["CARROT"] shouldBe null
        foods.available(city, supplied, "CARROT") shouldBe 4
        val plan = requireNotNull(CivsResidentLife.mealPlan(city, supplied, foods = foods))
        val after = CivsResidentLife.finishMeal(city, plan, foods)
        CivsEquipment.usableCount(after.workers.single(), "CARROT").toLong() shouldBe ingredients + 3
        after.workers.single().hunger shouldBe 17
    }

    test("partial native pickup and replay preserve every exact slot without a second credit") {
        h.barrel.setItem(0, ItemStack(Material.BREAD, 2))
        h.barrel.setItem(1, ItemStack(Material.BREAD, 3))
        val tx = h.pickup()
        tx.collected!!.containers.size shouldBe 2
        h.barrel.setItem(0, null) // First slot reached its after-state before an interrupted apply.
        h.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        h.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        h.barrel.getItem(1)!!.amount shouldBe 1
        CivsEquipment.usableCount(tx.after.workers.single(), "BREAD") shouldBe 4
        shouldThrow<CivsRejection> { h.storage.reserve(h.city, UUID.randomUUID()) }.reason shouldBe "storage-busy"
    }

    test("a blocked native food source cannot be discovered prepared or sealed") {
        h.barrel.setItem(0, ItemStack(Material.BREAD, 4))
        h.foodAllowed = false
        h.storage.residentFoodSource(h.city, h.worker, h.feet) shouldBe null
        val id = UUID.randomUUID()
        h.storage.reserve(h.city, id)
        shouldThrow<CivsRejection> { h.storage.prepareFoodPickup(h.city, h.worker.id, h.source, h.feet) }.reason shouldBe "lands-denied"
        h.storage.prepareFoodMaterialization(h.city, h.worker.id, h.feet).transfer shouldBe null
        h.storage.unlock(id)
        h.foodAllowed = true
        val tx = h.pickup()
        h.foodAllowed = false
        shouldThrow<CivsRejection> { h.storage.seal(tx) }.reason shouldBe "lands-denied"
        h.city.stock.food shouldBe 20L
        h.barrel.getItem(0)!!.amount shouldBe 4
    }

    test("claim loss during the before fence cannot create an accepted food intent or debit") {
        h.barrel.setItem(0, ItemStack(Material.BREAD, 4))
        val source = CompletableFuture<Unit>()
        h.storage.persistCollectedNative = { _, before -> if (before) source else CompletableFuture.completedFuture(Unit) }
        val tx = h.pickup()
        val before = h.city
        val result = h.journal.submitCollected(before, tx)
        h.foodAllowed = false
        source.complete(Unit)
        h.ticks()
        shouldThrow<CompletionException> { result.join() }
        h.city shouldBe before
        h.barrel.getItem(0)!!.amount shouldBe 4
        verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        h.journal.pendingCount shouldBe 0
    }

    test("claim loss after accepting a food intent retains the same journal until authority returns") {
        h.barrel.setItem(0, ItemStack(Material.BREAD, 4))
        val accepted = CompletableFuture<CivInventoryTransaction>()
        every { h.store.saveInventoryTransaction(any()) } returns accepted
        val tx = h.pickup()
        val before = h.city
        val result = h.journal.submitCollected(before, tx)
        h.ticks() // The before fence must finish and the immutable intent must reach the store first.
        verify(exactly = 1) { h.store.saveInventoryTransaction(tx) }
        h.foodAllowed = false
        accepted.complete(tx)
        h.ticks()
        shouldThrow<CompletionException> { result.join() }
        h.city shouldBe before
        h.barrel.getItem(0)!!.amount shouldBe 4
        h.journal.pendingCount shouldBe 1
        verify(exactly = 0) { h.store.deleteInventoryTransaction(any()) }
        h.foodAllowed = true
        h.time += 5_000
        h.journal.recover(); h.ticks()
        h.city shouldBe tx.after
        h.barrel.getItem(0) shouldBe null
        verify(exactly = 1) { h.store.saveInventoryTransaction(tx) }
        verify(exactly = 1) { h.store.deleteInventoryTransaction(tx.id) }
    }

    test("partly applied recovered food remains locked on claim loss and never replays the applied slot") {
        h.barrel.setItem(0, ItemStack(Material.BREAD, 2))
        h.barrel.setItem(1, ItemStack(Material.BREAD, 3))
        val tx = h.pickup()
        val before = h.city
        h.barrel.setItem(0, null)
        h.storage.unlock(tx.id)
        every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(tx))
        every { h.store.loadConsumedItems() } returns CompletableFuture.completedFuture(CivConsumedItemIndex.EMPTY)
        h.foodAllowed = false
        val boot = h.journal.bootstrap(); h.ticks(); boot.join()
        h.journal.recover(); h.ticks()
        h.city shouldBe before
        h.barrel.getItem(0) shouldBe null
        h.barrel.getItem(1)!!.amount shouldBe 3
        h.journal.pendingCount shouldBe 1
        verify(exactly = 0) { h.store.deleteInventoryTransaction(any()) }
        h.foodAllowed = true
        h.time += 5_000
        h.journal.recover(); h.ticks()
        h.city shouldBe tx.after
        h.barrel.getItem(0) shouldBe null
        h.barrel.getItem(1)!!.amount shouldBe 1
        CivsEquipment.usableCount(h.worker, "BREAD") shouldBe 4
        h.journal.pendingCount shouldBe 0
    }

    test("claim loss after every exact native after-state still completes the one accepted accounting") {
        h.barrel.setItem(0, ItemStack(Material.BREAD, 4))
        h.storage.persistCollectedNative = { _, before ->
            if (!before) h.foodAllowed = false
            CompletableFuture.completedFuture(Unit)
        }
        val tx = h.pickup()
        val result = h.journal.submitCollected(h.city, tx)
        h.ticks()
        result.join() shouldBe tx.after
        h.foodAllowed shouldBe false
        h.barrel.getItem(0) shouldBe null
        CivsEquipment.usableCount(h.worker, "BREAD") shouldBe 4
        verify(exactly = 1) { h.store.deleteInventoryTransaction(tx.id) }
    }

    test("food intent and existing legacy home receipts round trip through the production store") {
        val tx = h.materialize()
        val root = Files.createTempDirectory("civs-food-store-")
        try {
            CivsStore(root, Executor(Runnable::run)).use { store ->
                store.saveInventoryTransaction(tx).join() shouldBe tx
                store.loadInventoryTransactions().join() shouldBe listOf(tx)
                store.saveInventoryTransaction(tx).join() shouldBe tx
                shouldThrow<CompletionException> { store.saveInventoryTransaction(tx.copy(after = tx.after.copy(stock = Stock()))).join() }
            }
        } finally { root.toFile().deleteRecursively() }
    }

    for ((material, materializing) in listOf("BREAD" to false, "BREAD" to true, "APPLE" to false)) {
        test("before-journal disk failure leaves native food stock and bag unchanged material=$material materializing=$materializing") {
            h.storage.residentFoods = CivsResidentFoods(mapOf("BREAD" to CivResidentFoodDefinition(6), "APPLE" to CivResidentFoodDefinition(4)))
            if (!materializing) h.barrel.setItem(0, ItemStack(Material.valueOf(material), 4))
            h.storage.persistCollectedNative = { _, _ -> CompletableFuture.failedFuture(java.io.IOException("before fence")) }
            val tx = if (materializing) h.materialize() else h.pickup(h.source.copy(material = material))
            val before = h.city
            val future = h.journal.submitCollected(before, tx)
            h.ticks()
            shouldThrow<CompletionException> { future.join() }
            h.city shouldBe before
            h.barrel.getItem(0)?.amount shouldBe if (materializing) null else 4
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
            h.busy.isEmpty() shouldBe true
        }

        test("after-native timeout recovers the same food receipt once material=$material materializing=$materializing") {
            h.storage.residentFoods = CivsResidentFoods(mapOf("BREAD" to CivResidentFoodDefinition(6), "APPLE" to CivResidentFoodDefinition(4)))
            if (!materializing) h.barrel.setItem(0, ItemStack(Material.valueOf(material), 4))
            var afterAttempts = 0
            h.storage.persistCollectedNative = { _, before ->
                if (!before && ++afterAttempts == 1) CompletableFuture.failedFuture(CivsNativePersistenceRetry("after fence"))
                else CompletableFuture.completedFuture(Unit)
            }
            val tx = if (materializing) h.materialize() else h.pickup(h.source.copy(material = material))
            val before = h.city
            h.journal.reserveCollection(before.worldId, tx.id)
            val future = h.journal.submitCollected(before, tx)
            h.ticks()
            shouldThrow<CompletionException> { future.join() }
            h.city shouldBe before
            h.barrel.getItem(0)?.amount shouldBe if (materializing) 4 else null
            h.time += 5_000
            h.journal.recover()
            h.ticks()
            h.city shouldBe tx.after
            h.journal.recover()
            h.ticks()
            h.city shouldBe tx.after
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
            verify(exactly = 1) { h.store.deleteInventoryTransaction(tx.id) }
            afterAttempts shouldBe 2
        }
    }
})

private class ResidentFoodFixture : AutoCloseable {
    val paper = MockBukkitTestRuntime.open()
    val world = CivsWorksiteWorldFixture("food-receipt").also(paper.server::addWorld)
    val plugin = paper.createSimplePlugin("ResidentFoodTest")
    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    val storage = CivsStorage(plugin) { emptyArray() }
    val point = CivPosition(0, 64, 0)
    private val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, point, 0, "test",
        CivBox(CivPosition(-2, 64, -2), CivPosition(2, 68, 2)), CivPosition(0, 64, -2), BuildingPhase.ACTIVE, control = point)
    private val cities = mutableMapOf<UUID, Settlement>()
    var city: Settlement
        get() = cities.values.single()
        set(value) { cities[value.id] = value }
    val worker get() = city.workers.single()
    val feet = Location(world, 1.5, 64.0, .5)
    val source get() = CivResidentFoodSource(hall.id, point, CivPosition(1, 64, 0))
    val barrel get() = (world.getBlockAt(0, 64, 0).state as Barrel).inventory
    val store = mockk<CivsStore>()
    val busy = mutableSetOf<UUID>()
    val unhealthy = mutableSetOf<UUID>()
    var time = 10_000L
    var foodAllowed = true
    var freshAllowed = true
    val journal: CivsInventoryJournal
    init {
        world.loadChunk(0, 0)
        world.getBlockAt(0, 64, 0).type = Material.BARREL
        city = CivsRules.validate(Settlement(UUID.randomUUID(), UUID.randomUUID(), "Food", world.uid, point, 16,
            "nordic", Stock(food = 20), listOf(hall), listOf(CivWorker(UUID.randomUUID(), "Hungry", null, hunger = 14)), autonomy = CivAutonomy()))
        storage.persistCollectedNative = { _, _ -> CompletableFuture.completedFuture(Unit) }
        storage.foodCanModify = { _, _ -> foodAllowed }
        every { store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
        every { store.save(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
        every { store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
        journal = CivsInventoryJournal(plugin, tasks, store, storage, { cities }, busy, unhealthy, { false },
            { saved -> city = saved; CompletableFuture.completedFuture(Unit) },
            { _, _ -> error("unexpected ordinary mutation") }, {}, { tx ->
                check(freshAllowed) { "retired actor cannot validate a new pickup" }
                check(storage.collectedBeforeState(tx) == CivInventoryApplyResult.Applied)
                if (tx.collected!!.food!!.materialized == 0) check(civsCanReachContainer(feet, point))
            }, { _, _, _ -> null }, {}, { tx, _ -> cities.getValue(tx.cityId) }, {}, { time })
    }
    fun materialize(): CivInventoryTransaction = prepare { storage.prepareFoodMaterialization(city, worker.id, feet) }
    fun pickup(target: CivResidentFoodSource = source): CivInventoryTransaction = prepare { storage.prepareFoodPickup(city, worker.id, target, feet) }
    private fun prepare(plan: () -> CivCollectedPlan): CivInventoryTransaction {
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val prepared = plan()
        val tx = CivInventoryTransaction(id, city.id, city.revision,
            city.copy(stock = prepared.stock ?: city.stock, workers = listOf(prepared.worker), revision = city.revision + 1),
            emptyList(), collected = requireNotNull(prepared.transfer))
        storage.seal(tx)
        return tx
    }
    fun ticks() { paper.performTicks(8) }
    override fun close() { tasks.close(); storage.close(); paper.close() }
}
