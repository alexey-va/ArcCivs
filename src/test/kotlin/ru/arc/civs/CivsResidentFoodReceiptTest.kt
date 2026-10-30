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

class CivsUpgradeStorageReceiptTest : FunSpec({
    lateinit var f: UpgradeStorageFixture
    beforeEach { f = UpgradeStorageFixture() }
    afterEach { f.close() }

    for (type in listOf(BuildingType.WAREHOUSE, BuildingType.FARM, BuildingType.FORESTER))
    test("a full changing $type chest moves every exact item to existing unchanged native slots once") {
        f.configure(type)
        f.fillSource()
        val before = f.h.city
        val source = f.items(f.source)
        val target = f.items(f.destination)
        val tx = f.prepare()
        tx.containerRelocation!!.containers.single { it.position == f.source }.after.all { it == null } shouldBe true
        tx.collected shouldBe null
        tx.after shouldBe before.copy(revision = before.revision + 1)
        f.items(f.source) shouldBe source
        f.items(f.destination) shouldBe target // Preparation and journal serialization are read-only.
        f.h.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        f.h.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        f.items(f.source).all { it == null } shouldBe true
        f.items(f.destination) shouldBe source
        f.inventory(f.destination).getItem(0)!!.itemMeta.persistentDataContainer.get(
            org.bukkit.NamespacedKey(f.h.plugin, "keepsake"), org.bukkit.persistence.PersistentDataType.STRING) shouldBe "native-metadata"
        tx.after.stock shouldBe before.stock
        tx.after.workers shouldBe before.workers
        tx.after.inventory shouldBe before.inventory
    }

    test("insufficient exact native room rejects the whole evacuation before any write") {
        f.configure()
        f.fillSource()
        val receiver = f.addReceiver()
        repeat(STORAGE_SLOTS) { f.inventory(receiver.anchor).setItem(it, ItemStack(Material.DIRT, 64)) }
        repeat(STORAGE_SLOTS) { f.inventory(f.destination).setItem(it, ItemStack(Material.DIRT, 64)) }
        val before = f.allItems()
        val external = f.items(receiver.anchor)
        val city = f.h.city
        val id = UUID.randomUUID()
        f.h.storage.reserve(city, id)
        shouldThrow<CivsRejection> { f.h.storage.prepareUpgradeRelocation(city, f.target, f.future) }.reason shouldBe "storage-full"
        f.allItems() shouldBe before
        f.items(receiver.anchor) shouldBe external
        f.h.city shouldBe city
        verify(exactly = 0) { f.h.store.saveInventoryTransaction(any()) }
        f.h.storage.unlock(id)
    }

    for (receiverType in listOf("chest", "control", "double"))
    test("a full upgrade evacuates exact custom items into another ACTIVE building's $receiverType once") {
        f.configure()
        f.fillSource()
        repeat(STORAGE_SLOTS) { f.inventory(f.destination).setItem(it, ItemStack(Material.DIRT, 64)) }
        val receiver = f.addReceiver()
        val point = if (receiverType == "control") requireNotNull(receiver.control) else receiver.anchor
        if (receiverType == "control") {
            f.inventory(point).clear()
            repeat(STORAGE_SLOTS) { f.inventory(receiver.anchor).setItem(it, ItemStack(Material.DIRT, 64)) }
        }
        if (receiverType == "double") {
            val partner = point.copy(x = point.x - 1)
            f.h.world.loadChunk(partner.x shr 4, partner.z shr 4)
            f.chest(point, "south", "left"); f.chest(partner, "south", "right")
            f.h.city = f.h.city.copy(inventory = f.h.city.inventory!!.copy(
                containers = f.h.city.inventory!!.containers + partner))
            f.h.storage.replaceCities(listOf(f.h.city))
        }
        f.inventory(point).setItem(0, f.inventory(f.source).getItem(0)!!.clone().also { it.amount = 2 })
        val before = f.h.city
        val source = f.items(f.source)
        val oldDestination = f.items(f.destination)
        val existing = f.items(point)
        val tx = f.prepare()
        tx.containerRelocation!!.containers.any { it.position == point } shouldBe true
        f.items(f.source) shouldBe source
        f.items(point) shouldBe existing
        val moved = f.h.journal.submitRelocation(before, tx)
        f.h.ticks()
        moved.join() shouldBe tx.after
        f.h.storage.lock(tx)
        f.h.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        f.h.storage.unlock(tx.id)
        f.items(f.source).all { it == null } shouldBe true
        f.items(f.destination) shouldBe oldDestination
        f.items(point)[0] shouldBe requireNotNull(source[0]).copy(amount = 9)
        f.inventory(point).getItem(0)!!.itemMeta.persistentDataContainer.get(
            org.bukkit.NamespacedKey(f.h.plugin, "keepsake"), org.bukkit.persistence.PersistentDataType.STRING) shouldBe "native-metadata"
        tx.after.stock shouldBe before.stock
        tx.after.workers shouldBe before.workers
        tx.after.inventory shouldBe before.inventory
        val external = tx.containerRelocation!!.containers.filter { !civsRelocationLocal(f.building, it.position) }
        fun totals(values: Collection<List<CivResidentItem?>>) = values.flatten().filterNotNull()
            .groupingBy { it.material to it.data }.fold(0L) { sum, item -> sum + item.amount }
        totals(listOf(source) + external.map(CivRelocatedContainer::before)) shouldBe totals(external.map { f.items(it.position) })
        verify(exactly = 1) { f.h.store.saveInventoryTransaction(tx) }
        verify(exactly = 1) { f.h.store.deleteInventoryTransaction(tx.id) }
    }

    test("an external native holder cannot be completely debited even by an otherwise conserving receipt") {
        f.configure()
        f.fillSource()
        val receiver = f.addReceiver()
        val tx = f.prepare()
        val proof = tx.containerRelocation!!
        val destination = proof.containers.single { it.position == f.destination }
        val item = requireNotNull(destination.after[0])
        val credited = destination.copy(after = destination.after.mapIndexed { slot, value ->
            if (slot == 0) item.copy(amount = item.amount + 1) else value
        })
        val foreign = CivRelocatedContainer(receiver.anchor, "CHEST", f.state("east"),
            List(STORAGE_SLOTS) { if (it == 0) item.copy(amount = 1) else null }, List(STORAGE_SLOTS) { null })
        shouldThrow<IllegalArgumentException> { tx.copy(containerRelocation = proof.copy(containers =
            proof.containers.map { if (it.position == credited.position) credited else it } + foreign)).validated() }
    }

    for (excluded in listOf("retiring", "replaced", "construction", "inactive", "denied", "unloaded"))
    test("an external $excluded holder never receives an upgrade evacuation") {
        f.configure()
        f.fillSource()
        repeat(STORAGE_SLOTS) { f.inventory(f.destination).setItem(it, ItemStack(Material.DIRT, 64)) }
        val receiver = f.addReceiver(if (excluded == "construction") BuildingType.WAREHOUSE else BuildingType.SMELTERY,
            if (excluded == "unloaded") CivPosition(-8, 64, 8) else CivPosition(0, 64, 8))
        var future = f.future
        when (excluded) {
            "retiring" -> {
                f.h.world.getBlockAt(receiver.anchor.x, receiver.anchor.y, receiver.anchor.z).type = Material.BARREL
                f.h.city = CivsRules.validate(f.h.city.copy(pendingControls = listOf(CivControlPlacement(receiver.id,
                    requireNotNull(receiver.control), "minecraft:air", retire = CivPlanBlock(receiver.anchor,
                        f.h.world.getBlockAt(receiver.anchor.x, receiver.anchor.y, receiver.anchor.z).blockData.asString, "minecraft:air")))))
            }
            "replaced" -> future = future + (receiver.anchor to "minecraft:air")
            "construction" -> {
                f.beginUpgrade(receiver)
                f.h.city = CivsRules.validate(f.h.city)
            }
            "inactive" -> f.h.city = CivsRules.validate(f.h.city.copy(buildings = f.h.city.buildings.map {
                if (it.id == receiver.id) it.copy(phase = BuildingPhase.SITE) else it
            }))
            "denied" -> f.h.storage.foodCanModify = { _, point -> point != receiver.anchor }
            else -> f.h.world.unloadChunk(receiver.anchor.x shr 4, receiver.anchor.z shr 4)
        }
        f.h.storage.replaceCities(listOf(f.h.city))
        val city = f.h.city
        val original = if (excluded == "unloaded") null else f.items(receiver.anchor)
        val source = f.items(f.source)
        val id = UUID.randomUUID()
        f.h.storage.reserve(city, id)
        shouldThrow<CivsRejection> { f.h.storage.prepareUpgradeRelocation(city, f.target, future) }.reason shouldBe "storage-full"
        if (excluded != "unloaded") {
            f.items(receiver.anchor) shouldBe original
        } else f.h.world.isChunkLoaded(receiver.anchor.x shr 4, receiver.anchor.z shr 4) shouldBe false
        f.items(f.source) shouldBe source
        f.h.city shouldBe city
        verify(exactly = 0) { f.h.store.saveInventoryTransaction(any()) }
        f.h.storage.unlock(id)
    }

    test("a cross-building relocation replays a mixed exact native image without changing stock or workers") {
        f.configure()
        f.fillSource()
        repeat(STORAGE_SLOTS) { f.inventory(f.destination).setItem(it, ItemStack(Material.DIRT, 64)) }
        val receiver = f.addReceiver()
        val original = f.items(f.source)
        val before = f.h.city
        val tx = f.prepare()
        val root = Files.createTempDirectory("civs-cross-building-relocation-")
        val accepted = try {
            CivsStore(root, Executor(Runnable::run)).use { store ->
                store.saveInventoryTransaction(tx).join()
                store.loadInventoryTransactions().join().single().also { it shouldBe tx }
            }
        } finally { root.toFile().deleteRecursively() }
        accepted.containerRelocation!!.changes().filter {
            it.container == f.source && it.slot == 0 || it.container == receiver.anchor && it.slot == 1
        }.forEach { f.inventory(it.container).setItem(it.slot, it.after?.stack()) }
        f.h.storage.unlock(tx.id)
        every { f.h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(accepted))
        every { f.h.store.loadConsumedItems() } returns CompletableFuture.completedFuture(CivConsumedItemIndex.EMPTY)
        val boot = f.h.journal.bootstrap()
        f.h.ticks(); boot.join()
        repeat(2) { f.h.journal.recover(); f.h.ticks() }
        f.items(f.source).all { it == null } shouldBe true
        f.items(receiver.anchor) shouldBe original
        f.h.city shouldBe accepted.after
        f.h.city.stock shouldBe before.stock
        f.h.city.workers shouldBe before.workers
        f.h.journal.pendingCount shouldBe 0
        verify(exactly = 0) { f.h.store.saveInventoryTransaction(any()) }
        verify(exactly = 1) { f.h.store.deleteInventoryTransaction(tx.id) }
    }

    for (drift in listOf("slot", "facing", "building", "authority"))
    test("$drift drift is classified across all endpoints before clearing a source slot") {
        f.configure()
        f.inventory(f.source).setItem(0, ItemStack(Material.DIAMOND, 7))
        val tx = f.prepare()
        val original = f.items(f.source)
        when (drift) {
            "slot" -> f.inventory(f.destination).setItem(26, ItemStack(Material.APPLE))
            "facing" -> f.chest(f.destination, "north")
            "building" -> f.h.city = f.h.city.copy(buildings = f.h.city.buildings.map {
                if (it.id == f.building.id) it.copy(production = "WHEAT") else it
            })
            else -> f.h.foodAllowed = false
        }
        val result = f.h.storage.reconcile(tx)
        (result is CivInventoryApplyResult.Conflict || result is CivInventoryApplyResult.Retry) shouldBe true
        f.items(f.source) shouldBe original
    }

    test("a saved relocation restarts from a mixed before and after image without duplicating custom items") {
        f.configure()
        f.fillSource()
        val original = f.items(f.source)
        val tx = f.prepare()
        val root = Files.createTempDirectory("civs-upgrade-relocation-")
        val accepted = try {
            CivsStore(root, Executor(Runnable::run)).use { store ->
                store.saveInventoryTransaction(tx).join() shouldBe tx
                store.loadInventoryTransactions().join().single().also { it shouldBe tx }
            }
        } finally { root.toFile().deleteRecursively() }
        // An interrupted native write has cleared one source and credited one destination slot.
        accepted.containerRelocation!!.changes().filter {
            it.container == f.source && it.slot == 0 || it.container == f.destination && it.slot == 1
        }.forEach { f.inventory(it.container).setItem(it.slot, it.after?.stack()) }
        f.h.storage.unlock(tx.id)
        every { f.h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(accepted))
        every { f.h.store.loadConsumedItems() } returns CompletableFuture.completedFuture(CivConsumedItemIndex.EMPTY)
        val boot = f.h.journal.bootstrap()
        f.h.ticks(); boot.join()
        repeat(2) { f.h.journal.recover(); f.h.ticks() }
        f.items(f.source).all { it == null } shouldBe true
        f.items(f.destination) shouldBe original
        f.h.city shouldBe accepted.after
        f.h.journal.pendingCount shouldBe 0
        verify(exactly = 0) { f.h.store.saveInventoryTransaction(any()) }
        verify(exactly = 1) { f.h.store.deleteInventoryTransaction(tx.id) }
    }

    for (facing in listOf("north", "south"))
    test("an accepted relocation ${if (facing == "north") "recovers after closing the same barrel" else "rejects a barrel facing change after closing"}") {
        f.configure()
        f.fillSource()
        val block = f.h.world.getBlockAt(f.control.x, f.control.y, f.control.z)
        fun state(value: String) {
            val contents = f.inventory(f.control).storageContents.map { it?.clone() }.toTypedArray()
            (block.state as Barrel).also {
                // The pinned mock's update replaces live contents from its separate snapshot inventory.
                it.snapshotInventory.storageContents = contents
                it.blockData = org.bukkit.Bukkit.createBlockData(value)
                it.update(true, false) shouldBe true
            }
            f.items(f.control) shouldBe contents.map(CivResidentItem::of)
        }
        val original = f.allItems()
        fun totals(values: Collection<List<CivResidentItem?>>) = values.flatten().filterNotNull()
            .groupingBy { it.material to it.data }.fold(0L) { count, item -> count + item.amount }
        state("minecraft:barrel[facing=north,open=true]")
        f.allItems() shouldBe original
        val tx = f.prepare()
        val gson = com.google.gson.Gson()
        val accepted = gson.fromJson(gson.toJson(tx), CivInventoryTransaction::class.java).validated()
        val holders = accepted.containerRelocation!!.containers
        val before = holders.associate { it.position to it.before }
        val after = holders.associate { it.position to it.after }
        before shouldBe original
        holders.single { it.position == f.control }.blockData shouldBe
            "minecraft:barrel[facing=north,open=true]"
        f.h.storage.unlock(tx.id)
        // Closing the already journalled holder changes a native presentation bit, without changing a slot.
        state("minecraft:barrel[facing=$facing,open=false]")
        f.allItems() shouldBe original
        every { f.h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(accepted))
        every { f.h.store.loadConsumedItems() } returns CompletableFuture.completedFuture(CivConsumedItemIndex.EMPTY)
        val boot = f.h.journal.bootstrap()
        f.h.ticks(); boot.join()
        repeat(2) { f.h.journal.recover(); f.h.ticks() }
        if (facing == "north") {
            f.h.city shouldBe accepted.after
            f.items(f.source).all { it == null } shouldBe true
            holders.associate { it.position to f.items(it.position) } shouldBe after
            f.h.journal.pendingCount shouldBe 0
            verify(exactly = 1) { f.h.store.deleteInventoryTransaction(tx.id) }
        } else {
            f.h.city shouldBe tx.after.copy(revision = tx.beforeRevision)
            holders.associate { it.position to f.items(it.position) } shouldBe before
            f.h.journal.pendingCount shouldBe 1
            verify(exactly = 0) { f.h.store.deleteInventoryTransaction(any()) }
        }
        totals(holders.map { f.items(it.position) }) shouldBe totals(original.values)
        verify(exactly = 0) { f.h.store.saveInventoryTransaction(any()) }
    }

    test("relocation waits for native source and native after disk fences before publishing the city") {
        f.configure()
        f.fillSource()
        val original = f.items(f.source)
        val before = f.h.city
        val tx = f.prepare()
        val source = CompletableFuture<Unit>()
        val after = CompletableFuture<Unit>()
        f.h.storage.persistCollectedNative = { _, first -> if (first) source else after }
        val result = f.h.journal.submitRelocation(before, tx)
        f.h.ticks()
        result.isDone shouldBe false
        f.items(f.source) shouldBe original
        verify(exactly = 0) { f.h.store.saveInventoryTransaction(any()) }
        source.complete(Unit); f.h.ticks()
        f.items(f.source).all { it == null } shouldBe true
        f.items(f.destination) shouldBe original
        f.h.city shouldBe before
        result.isDone shouldBe false
        verify(exactly = 1) { f.h.store.saveInventoryTransaction(tx) }
        after.complete(Unit); f.h.ticks()
        result.join() shouldBe tx.after
        f.h.city shouldBe tx.after
        verify(exactly = 1) { f.h.store.deleteInventoryTransaction(tx.id) }
    }

    test("an after disk timeout replays the same exact relocation receipt once") {
        f.configure()
        f.fillSource()
        val original = f.items(f.source)
        val before = f.h.city
        val tx = f.prepare()
        var attempts = 0
        var intent = true
        var admissions = 0
        f.h.storage.persistCollectedNative = { _, first ->
            if (!first && ++attempts == 1) CompletableFuture.failedFuture(CivsNativePersistenceRetry("after fence"))
            else CompletableFuture.completedFuture(Unit)
        }
        val result = f.h.journal.submitRelocation(before, tx) { admissions++; check(intent) }
        f.h.ticks()
        shouldThrow<CompletionException> { result.join() }
        f.h.city shouldBe before
        f.items(f.destination) shouldBe original
        intent = false // An accepted durable transfer recovers independently of the retired player request.
        f.h.time += 5_000
        repeat(2) { f.h.journal.recover(); f.h.ticks() }
        f.h.city shouldBe tx.after
        f.items(f.source).all { it == null } shouldBe true
        f.items(f.destination) shouldBe original
        attempts shouldBe 2
        admissions shouldBe 1
        verify(exactly = 1) { f.h.store.saveInventoryTransaction(tx) }
        verify(exactly = 1) { f.h.store.deleteInventoryTransaction(tx.id) }
    }

    test("native source disk failure leaves all items and accounting untouched without a journal") {
        f.configure()
        f.fillSource()
        val original = f.allItems()
        val before = f.h.city
        val tx = f.prepare()
        f.h.storage.persistCollectedNative = { _, _ -> CompletableFuture.failedFuture(java.io.IOException("source fence")) }
        val result = f.h.journal.submitRelocation(before, tx)
        f.h.ticks()
        shouldThrow<CompletionException> { result.join() }
        f.allItems() shouldBe original
        f.h.city shouldBe before
        f.h.busy.isEmpty() shouldBe true
        verify(exactly = 0) { f.h.store.saveInventoryTransaction(any()) }
    }

    test("revoked ephemeral player intent after the source disk fence prevents journal admission and all native moves") {
        f.configure()
        f.fillSource()
        val original = f.allItems()
        val before = f.h.city
        val tx = f.prepare()
        val source = CompletableFuture<Unit>()
        f.h.storage.persistCollectedNative = { _, first -> if (first) source else CompletableFuture.completedFuture(Unit) }
        var permitted = true
        var admissions = 0
        val result = f.h.journal.submitRelocation(before, tx) {
            admissions++
            if (!permitted) throw CivsRejection("not-authorized")
        }
        f.h.ticks()
        admissions shouldBe 0
        permitted = false
        source.complete(Unit); f.h.ticks()
        CivsDiagnostics.rejection(shouldThrow<CompletionException> { result.join() })?.reason shouldBe "not-authorized"
        admissions shouldBe 1
        f.allItems() shouldBe original
        f.h.city shouldBe before
        f.h.journal.pendingCount shouldBe 0
        f.h.busy.isEmpty() shouldBe true
        verify(exactly = 0) { f.h.store.saveInventoryTransaction(any()) }
        // A rejected ephemeral request owns no lingering native or city reservation.
        val next = UUID.randomUUID()
        f.h.storage.reserve(before, next)
        f.h.storage.unlock(next)
    }

    test("only exact reciprocal unchanged double halves may accept a changing chest's contents") {
        f.configure()
        val item = CivResidentItem("DIAMOND", 1)
        val source = CivRelocatedContainer(f.source, "CHEST", f.state("south"),
            List(STORAGE_SLOTS) { if (it == 0) item else null }, List(STORAGE_SLOTS) { null })
        val left = f.destination
        val correct = left.copy(x = left.x - 1) // South/left's exact partner is west.
        val wrong = left.copy(z = left.z + 1)
        fun half(point: CivPosition, part: String) = CivRelocatedContainer(point, "CHEST", f.state("south", part),
            List(STORAGE_SLOTS) { null }, List(STORAGE_SLOTS) { null })
        val pair = listOf(source, half(left, "left"), half(correct, "right"))
        val future = pair.associate { it.position to it.blockData } + (f.source to f.state("east"))
        requireNotNull(civsPlanContainerRelocation(f.building, f.target, future, pair)).changes()
            .any { it.container == left && it.after == item } shouldBe true
        val misplaced = listOf(source, half(left, "left"), half(wrong, "right"))
        shouldThrow<CivsRejection> { civsPlanContainerRelocation(f.building, f.target,
            misplaced.associate { it.position to it.blockData } + (f.source to f.state("east")), misplaced) }.reason shouldBe "storage-full"
        shouldThrow<CivsRejection> { civsPlanContainerRelocation(f.building, f.target,
            future + (correct to "minecraft:air"), pair) }.reason shouldBe "storage-full"
    }

    test("receipt validation conserves metadata as well as amount and never grants a worker credit") {
        f.configure()
        f.fillSource()
        val tx = f.prepare()
        val proof = tx.containerRelocation!!
        val destination = proof.containers.single { it.position == f.destination }
        val changed = destination.copy(after = destination.after.mapIndexed { index, item -> if (index == 0) item!!.copy(data = null) else item })
        shouldThrow<IllegalArgumentException> { tx.copy(containerRelocation = proof.copy(containers = proof.containers.map {
            if (it.position == changed.position) changed else it
        })).validated() }
        val workerCredit = tx.copy(after = tx.after.copy(workers = tx.after.workers.map {
            CivsEquipment.collectSupplies(it, mapOf("DIAMOND" to 1L))
        })).validated()
        shouldThrow<IllegalArgumentException> { f.h.storage.seal(workerCredit) }
    }
})

class CivsUpgradeStorageNoRefillTest : FunSpec({
    for (type in listOf(BuildingType.WAREHOUSE, BuildingType.FARM))
    test("an admitted $type upgrade cannot refill evacuated old holders and still permits withdrawal and its control") {
        UpgradeStorageFixture().use { f ->
            f.configure(type)
            f.fillSource()
            val relocation = f.prepare()
            f.h.storage.reconcile(relocation) shouldBe CivInventoryApplyResult.Applied
            f.h.storage.unlock(relocation.id)
            f.h.city = relocation.after
            f.beginUpgrade()
            repeat(STORAGE_SLOTS) { f.h.barrel.setItem(it, ItemStack(Material.STONE, 64)) }
            val original = f.allItems()
            val id = UUID.randomUUID()
            f.h.storage.reserve(f.h.city, id)
            val cargo = CivCargo(UUID.randomUUID(), f.building.id, f.building.id, f.building.id,
                f.building.entrance, "DIRT", 4, inventoryBacked = true)
            f.h.storage.cargoRoom(f.h.city, f.building.id, "DIRT") shouldBe 0L
            shouldThrow<CivsRejection> { f.h.storage.prepareCargoDropoff(f.h.city, cargo, f.building.id) }.reason shouldBe "storage-full"
            shouldThrow<CivsRejection> { f.h.storage.prepareCredit(f.h.city, "DIRT", 4) }.reason shouldBe "storage-full"
            shouldThrow<CivsRejection> { f.h.storage.prepareWork(f.h.city, f.building.id,
                emptyMap(), mapOf("DIRT" to 4L), emptyList()) }.reason shouldBe "storage-full"
            val withdrawn = f.h.storage.prepareCargoPickup(f.h.city, cargo.copy(material = "COBBLESTONE", amount = 64))
            withdrawn.changes.all { it.container == f.destination && it.after == null } shouldBe true
            withdrawn.changes.sumOf { (it.before?.amount ?: 0) - (it.after?.amount ?: 0) } shouldBe 64
            f.allItems() shouldBe original
            // Free three real slots: a producer still keeps its existing two-slot output headroom.
            repeat(3) { f.inventory(f.control).setItem(it, null) }
            val admitted = f.h.storage.prepareWork(f.h.city, f.building.id, emptyMap(), mapOf("DIRT" to 4L), emptyList())
            admitted.changes.map { it.container }.toSet() shouldBe setOf(f.control)
            val global = f.h.storage.prepareCredit(f.h.city, "DIRT", 4)
            global.changes.map { it.container }.toSet() shouldBe setOf(f.control)
            f.items(f.source).all { it == null } shouldBe true
            f.h.storage.unlock(id)
        }
    }

    test("a resident retains collected home surplus when only its changing old chest has room") {
        UpgradeStorageFixture().use { f ->
            f.configure(BuildingType.HOUSE)
            f.fillSource()
            val relocation = f.prepare()
            f.h.storage.reconcile(relocation) shouldBe CivInventoryApplyResult.Applied
            f.h.storage.unlock(relocation.id)
            f.h.city = relocation.after.copy(workers = relocation.after.workers.map { worker ->
                CivsEquipment.collectSupplies(worker, mapOf("DIRT" to 4L)).copy(homeId = f.building.id, hasCollectedItems = true)
            })
            f.beginUpgrade()
            for (x in 7..13) for (z in -1..2) f.h.world.getBlockAt(x, 63, z).type = Material.STONE
            val before = f.h.worker
            val id = UUID.randomUUID()
            f.h.storage.reserve(f.h.city, id)
            val prepared = f.h.storage.prepareHomeDeposit(f.h.city, before.id, f.source,
                Location(f.h.world, 8.5, 64.0, 1.5), emptyMap())
            prepared.transfer shouldBe null
            prepared.worker shouldBe before
            f.items(f.source).all { it == null } shouldBe true
            f.h.storage.unlock(id)
        }
    }
})

private class UpgradeStorageFixture : AutoCloseable {
    val h = ResidentFoodFixture()
    val source = CivPosition(8, 64, 0)
    val destination = CivPosition(10, 64, 0)
    val control = CivPosition(12, 64, 0)
    lateinit var building: CivBuilding
    val target get() = building.copy(level = 2, phase = BuildingPhase.CONSTRUCTING)
    val future get() = mapOf(source to state("east"), destination to state("east"), control to "minecraft:structure_void")
    fun state(facing: String, part: String = "single") = "minecraft:chest[facing=$facing,type=$part,waterlogged=false]"
    fun configure(type: BuildingType = BuildingType.WAREHOUSE) {
        building = h.city.buildings.single().copy(id = UUID.randomUUID(), type = type, anchor = source, control = control,
            bounds = CivBox(CivPosition(7, 64, -1), CivPosition(12, 68, 1)), entrance = CivPosition(8, 64, -2))
        chest(source, "south"); chest(destination, "east")
        h.world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
        repeat(STORAGE_SLOTS) { inventory(control).setItem(it, ItemStack(Material.STONE, 64)) }
        repeat(STORAGE_SLOTS) { h.barrel.setItem(it, ItemStack(Material.STONE, 64)) }
        h.city = CivsRules.validate(h.city.copy(stock = Stock(), buildings = h.city.buildings + building,
            inventory = CivInventory(CivStorageMode.PHYSICAL, containers = listOf(source, destination))))
        h.storage.replaceCities(listOf(h.city))
        h.storage.relocationCurrentCity = { h.city.takeIf { city -> city.id == it } }
    }
    fun addReceiver(type: BuildingType = BuildingType.WAREHOUSE, point: CivPosition = CivPosition(0, 64, 8)): CivBuilding {
        h.world.loadChunk(point.x shr 4, point.z shr 4)
        val receiver = building.copy(id = UUID.randomUUID(), type = type, level = 1, anchor = point,
            bounds = CivBox(point.copy(x = point.x - 1, z = point.z - 1), point.copy(x = point.x + 2, y = 68, z = point.z + 1)),
            entrance = point.copy(z = point.z + 2), control = point.copy(x = point.x + 2))
        chest(point, "east")
        val marker = requireNotNull(receiver.control)
        h.world.getBlockAt(marker.x, marker.y, marker.z).type = Material.BARREL
        repeat(STORAGE_SLOTS) { inventory(marker).setItem(it, ItemStack(Material.STONE, 64)) }
        h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings + receiver,
            inventory = h.city.inventory!!.copy(containers = h.city.inventory!!.containers + point)))
        h.storage.replaceCities(listOf(h.city))
        return receiver
    }
    fun chest(point: CivPosition, facing: String, part: String = "single") {
        val block = h.world.getBlockAt(point.x, point.y, point.z)
        block.type = Material.CHEST
        (block.state as org.bukkit.block.Chest).also {
            it.blockData = org.bukkit.Bukkit.createBlockData(state(facing, part))
            it.update(true, false)
        }
    }
    fun inventory(point: CivPosition): org.bukkit.inventory.Inventory = when (val state = h.world.getBlockAt(point.x, point.y, point.z).state) {
        is org.bukkit.block.Chest -> state.blockInventory
        is Barrel -> state.inventory
        else -> error("Expected a real native container at $point")
    }
    fun items(point: CivPosition) = inventory(point).storageContents.map(CivResidentItem::of)
    fun allItems() = listOf(source, destination, control).associateWith(::items)
    fun fillSource() {
        repeat(STORAGE_SLOTS) { inventory(source).setItem(it, ItemStack(Material.COBBLESTONE, 64)) }
        inventory(source).setItem(0, ItemStack(Material.DIAMOND, 7).apply { editMeta {
            it.displayName(net.kyori.adventure.text.Component.text("Warehouse keepsake"))
            it.persistentDataContainer.set(org.bukkit.NamespacedKey(h.plugin, "keepsake"),
                org.bukkit.persistence.PersistentDataType.STRING, "native-metadata")
        } })
    }
    fun prepare(): CivInventoryTransaction {
        val before = h.city
        val id = UUID.randomUUID()
        h.storage.reserve(before, id)
        val proof = requireNotNull(h.storage.prepareUpgradeRelocation(before, target, future))
        return CivInventoryTransaction(id, before.id, before.revision, before.copy(revision = before.revision + 1),
            emptyList(), containerRelocation = proof).validated().also { h.storage.seal(it) }
    }
    fun beginUpgrade(upgrading: CivBuilding = building) {
        val city = h.city.copy(autonomy = null, buildings = h.city.buildings.map {
            if (it.type == BuildingType.TOWN_HALL) it.copy(level = 2) else it
        })
        val upgrade = upgrading.copy(level = upgrading.level + 1, phase = BuildingPhase.CONSTRUCTING)
        val order = CivConstruction(UUID.randomUUID(), upgrading.id, upgrade.level, 1, 0, ConstructionPhase.CONSTRUCTING,
            Stock(), plannedBuilding = upgrade, batches = CivConstructionBatches(1, 16), materials = mapOf("STONE" to 1L),
            payment = CivConstructionPayment())
        h.city = CivsRules.reserveBuild(city, city.owner, upgrade, order)
        h.storage.updateCity(h.city)
    }
    override fun close() = h.close()
}

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
