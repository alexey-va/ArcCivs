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

    test("food discovery uses the detached holder and a stale observation cannot debit an empty barrel") {
        h.foodPhase(BuildingPhase.ACTIVE)
        h.barrel.setItem(0, ItemStack(Material.BREAD, 4))
        h.storage.residentFoodSource(h.city, h.worker, h.feet) shouldBe null
        val source = requireNotNull(h.observedFoodSource(h.worker, h.feet))
        h.barrel.setItem(0, null)
        h.storage.residentFoodSource(h.city, h.worker, h.feet) shouldBe source
        shouldThrow<CivsRejection> { h.pickup(source) }.reason shouldBe "resident-food-missing"
        h.barrel.getItem(0) shouldBe null
    }

    test("a dirty holder leaves fresh food visible in another holder of the same building") {
        h.foodPhase(BuildingPhase.ACTIVE)
        val hall = h.city.buildings.single()
        val other = CivPosition(0, 64, 1)
        val block = h.world.getBlockAt(other.x, other.y, other.z).also { it.type = Material.BARREL }
        (block.state as Barrel).inventory.setItem(0, ItemStack(Material.BREAD, 4))
        h.barrel.setItem(0, ItemStack(Material.BREAD, 4))
        h.indexed += other
        h.storage.refreshBuilding(h.city, hall)
        requireNotNull(h.observedFoodSource(h.worker, h.feet)).container shouldBe h.point

        h.storage.invalidateSupplyPositions(h.city.worldId, listOf(h.point))
        h.storage.planningContainer(h.city, hall.id, h.point) shouldBe null
        requireNotNull(h.storage.residentFoodSource(h.city, h.worker, h.feet)).container shouldBe other
    }

    test("food discovery uses frozen membership until a city claim is released") {
        h.foodPhase(BuildingPhase.ACTIVE)
        val hall = h.city.buildings.single()
        h.observedFoodSource(h.worker, h.feet) shouldBe null
        val other = CivPosition(0, 64, 1)
        val claim = UUID.randomUUID()
        h.storage.reserve(h.city, claim)
        try {
            val block = h.world.getBlockAt(other.x, other.y, other.z).also { it.type = Material.BARREL }
            (block.state as Barrel).inventory.setItem(0, ItemStack(Material.BREAD, 4))
            h.indexed += other
            h.storage.refreshBuilding(h.city, hall)
            h.storage.capturePlanningStorage(h.city)
            h.storage.planningContainer(h.city, hall.id, other)?.stock shouldBe mapOf("BREAD" to 4L)
            h.storage.residentFoodSource(h.city, h.worker, h.feet) shouldBe null
        } finally {
            h.storage.unlock(claim)
        }
        requireNotNull(h.storage.residentFoodSource(h.city, h.worker, h.feet)).container shouldBe other
    }

    for (phase in BuildingPhase.entries) test("existing hall control bread remains usable through $phase without creating food") {
        h.foodPhase(phase)
        h.barrel.setItem(0, ItemStack(Material.BREAD, 24))
        val source = requireNotNull(h.observedFoodSource(h.worker, h.feet))
        source.container shouldBe h.point
        val tx = h.pickup(source)
        tx.collected!!.food!!.materialized shouldBe 0
        tx.after.stock shouldBe Stock()
        h.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        h.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        h.barrel.getItem(0)!!.amount shouldBe 20
        val carried = tx.after.workers.single()
        CivsEquipment.usableCount(carried, "BREAD") shouldBe 4
        carried.hunger shouldBe 0
        val meal = requireNotNull(CivsResidentLife.mealPlan(tx.after, carried))
        val fed = CivsResidentLife.finishMeal(tx.after, meal)
        fed.workers.single().hunger shouldBe 18
        fed.workers.single().life!!.meals shouldBe 3
        CivsEquipment.usableCount(fed.workers.single(), "BREAD") shouldBe 1
        fed.stock shouldBe Stock()
        h.barrel.getItem(0)!!.amount + CivsEquipment.usableCount(fed.workers.single(), "BREAD") +
            fed.workers.single().life!!.meals shouldBe 24L
        shouldThrow<CivsRejection> { CivsResidentLife.finishMeal(fed, meal) }.reason shouldBe "stale-meal"
    }

    for (phase in listOf(BuildingPhase.SITE, BuildingPhase.CONSTRUCTING)) {
        test("unfinished $phase food access excludes interior holders and forged interior receipts") {
            h.foodPhase(phase)
            val interior = CivPosition(0, 64, 1)
            h.world.getBlockAt(interior.x, interior.y, interior.z).type = Material.BARREL
            val other = (h.world.getBlockAt(interior.x, interior.y, interior.z).state as Barrel).inventory
            other.setItem(0, ItemStack(Material.BREAD, 24))
            h.city = h.city.copy(inventory = h.city.inventory!!.copy(containers = listOf(interior)))
            h.storage.replaceCities(listOf(h.city))
            h.observedFoodSource(h.worker, h.feet) shouldBe null
            val id = UUID.randomUUID()
            h.storage.reserve(h.city, id)
            shouldThrow<CivsRejection> { h.storage.prepareFoodPickup(h.city, h.worker.id,
                h.source.copy(container = interior), h.feet) }.reason shouldBe "worker-too-far"
            h.storage.unlock(id)
            h.barrel.setItem(0, ItemStack(Material.BREAD, 24))
            val tx = h.pickup()
            val transfer = requireNotNull(tx.collected)
            shouldThrow<IllegalArgumentException> {
                transfer.copy(containers = transfer.containers.map { it.copy(container = interior) }).validated(tx.after)
            }
            other.getItem(0)!!.amount shouldBe 24
            h.barrel.getItem(0)!!.amount shouldBe 24
        }

        test("unfinished $phase control food retains claim and current control fences") {
            h.foodPhase(phase)
            h.barrel.setItem(0, ItemStack(Material.BREAD, 24))
            h.foodAllowed = false
            h.observedFoodSource(h.worker, h.feet) shouldBe null
            val id = UUID.randomUUID()
            h.storage.reserve(h.city, id)
            shouldThrow<CivsRejection> { h.storage.prepareFoodPickup(h.city, h.worker.id, h.source, h.feet) }
                .reason shouldBe "lands-denied"
            h.storage.unlock(id)
            h.foodAllowed = true
            val tx = h.pickup()
            val movedControl = tx.after.copy(buildings = tx.after.buildings.map { it.copy(control = CivPosition(1, 64, 0)) })
            shouldThrow<IllegalArgumentException> { tx.collected!!.validated(movedControl) }
            h.foodAllowed = false
            shouldThrow<CivsRejection> { h.storage.seal(tx) }.reason shouldBe "lands-denied"
            h.barrel.getItem(0)!!.amount shouldBe 24
            h.city.stock shouldBe Stock()
        }

        test("unfinished $phase control must still be a native barrel") {
            h.foodPhase(phase)
            h.world.getBlockAt(0, 64, 0).type = Material.CHEST
            h.observedFoodSource(h.worker, h.feet) shouldBe null
            val id = UUID.randomUUID()
            h.storage.reserve(h.city, id)
            shouldThrow<CivsRejection> { h.storage.prepareFoodPickup(h.city, h.worker.id, h.source, h.feet) }
                .reason shouldBe "storage-container-invalid"
        }
    }

    test("unfinished foreign home control does not become a public food source") {
        h.foodPhase(BuildingPhase.SITE)
        val home = h.city.buildings.single().copy(id = UUID.randomUUID(), type = BuildingType.HOUSE,
            anchor = CivPosition(8, 64, 0), control = CivPosition(8, 64, 0), entrance = CivPosition(8, 64, -2),
            bounds = CivBox(CivPosition(6, 64, -2), CivPosition(10, 68, 2)))
        h.city = h.city.copy(buildings = h.city.buildings + home)
        h.world.getBlockAt(8, 64, 0).type = Material.BARREL
        (h.world.getBlockAt(8, 64, 0).state as Barrel).inventory.setItem(0, ItemStack(Material.BREAD, 24))
        h.observedFoodSource(h.worker, h.feet) shouldBe null
        val id = UUID.randomUUID()
        h.storage.reserve(h.city, id)
        shouldThrow<CivsRejection> { h.storage.prepareFoodPickup(h.city, h.worker.id,
            CivResidentFoodSource(home.id, requireNotNull(home.control), home.entrance), h.feet) }
            .reason shouldBe "worker-too-far"
        h.storage.unlock(id)
        h.barrel.setItem(0, ItemStack(Material.BREAD, 24))
        val tx = h.pickup()
        val transfer = requireNotNull(tx.collected)
        shouldThrow<IllegalArgumentException> {
            transfer.copy(food = transfer.food!!.copy(buildingId = home.id),
                containers = transfer.containers.map { it.copy(container = requireNotNull(home.control)) }).validated(tx.after)
        }
    }

    test("unfinished tavern control food does not enable innkeeper service") {
        h.foodPhase(BuildingPhase.SITE)
        val tavern = h.city.buildings.single().copy(id = UUID.randomUUID(), type = BuildingType.TAVERN,
            anchor = CivPosition(8, 64, 0), control = CivPosition(8, 64, 0), entrance = CivPosition(8, 64, -2),
            bounds = CivBox(CivPosition(6, 64, -2), CivPosition(10, 68, 2)))
        h.city = h.city.copy(buildings = h.city.buildings + tavern)
        h.world.getBlockAt(8, 64, 0).type = Material.BARREL
        (h.world.getBlockAt(8, 64, 0).state as Barrel).inventory.setItem(0, ItemStack(Material.BREAD, 24))
        h.observedFoodSource(h.worker, h.feet, tavernService = true) shouldBe null
        requireNotNull(h.observedFoodSource(h.worker, h.feet)).buildingId shouldBe tavern.id
    }

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
        val source = requireNotNull(h.observedFoodSource(h.worker, h.feet))
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
        val saved = h.journal.submitNative(before, tx)
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
        h.indexed += extra
        h.city = h.city.copy(buildings = listOf(hall, tavern), stock = Stock(),
            inventory = CivInventory(CivStorageMode.PHYSICAL))
        h.barrel.setItem(0, ItemStack(Material.BREAD, 4))
        h.observedFoodSource(h.worker, h.feet) shouldBe null
        requireNotNull(h.observedFoodSource(h.worker, h.feet)).buildingId shouldBe hall.id
        h.barrel.getItem(0)!!.amount shouldBe 4
        h.worker.hunger shouldBe 14
    }

    test("an ordinary native apple moves from its real chest to the bag and restores four hunger without FOOD debit") {
        val foods = CivsResidentFoods(mapOf("BREAD" to CivResidentFoodDefinition(6), "APPLE" to CivResidentFoodDefinition(4)))
        h.storage.residentFoods = foods
        h.barrel.setItem(0, ItemStack(Material.APPLE, 4))
        val selected = requireNotNull(h.observedFoodSource(h.worker, h.feet))
        selected.material shouldBe "APPLE"
        val before = h.city
        val tx = h.pickup(selected)
        val picking = h.journal.submitNative(before, tx)
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
                writeLegacyInventoryReceipt(root, tx)
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
        h.observedFoodSource(h.worker, h.feet) shouldBe null
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

    test("an empty barrel cannot turn legacy stock into a personal food pickup") {
        val before = h.city
        shouldThrow<CivsRejection> { h.pickup() }.reason shouldBe "resident-food-missing"
        h.barrel.getItem(0) shouldBe null
        CivsEquipment.usableCount(h.worker, "BREAD") shouldBe 0
        h.city shouldBe before
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
        h.observedFoodSource(resident, Location(h.world, 7.5, 64.0, .5))!!.buildingId shouldBe house.id
        home.setItem(0, null)
        h.observedFoodSource(resident, Location(h.world, 7.5, 64.0, .5))!!.buildingId shouldBe hall.id
        h.barrel.setItem(0, null)
        h.observedFoodSource(resident, h.feet) shouldBe null
        home.getItem(0) shouldBe null
        h.barrel.getItem(0) shouldBe null
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
        h.observedFoodSource(h.worker, h.feet) shouldBe null
        val id = UUID.randomUUID()
        h.storage.reserve(h.city, id)
        shouldThrow<CivsRejection> { h.storage.prepareFoodPickup(h.city, h.worker.id, h.source, h.feet) }.reason shouldBe "lands-denied"
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
        val result = h.journal.submitNative(before, tx)
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
        val result = h.journal.submitNative(before, tx)
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
        h.storage.persistCollectedNative = { _, _ -> error("No forced native persistence") }
        val tx = h.pickup()
        val completed = CompletableFuture<Settlement>()
        every { h.store.completeInventoryTransaction(tx) } returns completed
        val result = h.journal.submitNative(h.city, tx)
        h.ticks()
        h.barrel.getItem(0) shouldBe null
        h.foodAllowed = false
        completed.complete(tx.after); h.ticks()
        result.join() shouldBe tx.after
        h.foodAllowed shouldBe false
        h.barrel.getItem(0) shouldBe null
        CivsEquipment.usableCount(h.worker, "BREAD") shouldBe 4
        verify(exactly = 1) { h.store.deleteInventoryTransaction(tx.id) }
    }

    test("a saved legacy stock-to-bread receipt recovers once before the bread is picked up") {
        val before = h.city
        val after = before.copy(stock = before.stock.copy(food = before.stock.food - 4), revision = before.revision + 1)
        val receipt = CivInventoryTransaction(UUID.randomUUID(), before.id, before.revision, after, emptyList(),
            collected = CivCollectedTransfer(h.worker.id, CivsEquipment.slots(h.worker),
                containers = listOf(CivNativeContainerChange(h.point, "BARREL", 0, null, CivResidentItem("BREAD", 4))),
                food = CivResidentFoodReceipt(h.source.buildingId, before.stock, materialized = 4)))
        val root = Files.createTempDirectory("civs-food-store-")
        val loaded = try {
            CivsStore(root, Executor(Runnable::run)).use { store ->
                writeLegacyInventoryReceipt(root, receipt)
                store.loadInventoryTransactions().join()
            }
        } finally { root.toFile().deleteRecursively() }
        loaded shouldBe listOf(receipt)
        shouldThrow<IllegalArgumentException> { receipt.copy(after = after.copy(stock = before.stock)).validated() }
        every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(loaded)
        every { h.store.loadConsumedItems() } returns CompletableFuture.completedFuture(CivConsumedItemIndex.EMPTY)
        val boot = h.journal.bootstrap(); h.ticks(); boot.join()
        h.barrel.getItem(0) shouldBe null
        repeat(2) { h.journal.recover(); h.ticks() }
        h.city shouldBe after
        h.barrel.getItem(0) shouldBe ItemStack(Material.BREAD, 4)
        h.journal.pendingCount shouldBe 0
        verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        verify(exactly = 1) { h.store.deleteInventoryTransaction(receipt.id) }

        val pickup = h.journal.submitNative(h.city, h.pickup())
        h.ticks()
        pickup.join() shouldBe h.city
        h.city.stock shouldBe after.stock
        CivsEquipment.usableCount(h.worker, "BREAD") shouldBe 4
        h.barrel.getItem(0) shouldBe null
    }

    for (material in listOf("BREAD", "APPLE")) {
        test("new food pickup does not invoke forced native persistence material=$material") {
            h.storage.residentFoods = CivsResidentFoods(mapOf("BREAD" to CivResidentFoodDefinition(6), "APPLE" to CivResidentFoodDefinition(4)))
            h.barrel.setItem(0, ItemStack(Material.valueOf(material), 4))
            h.storage.persistCollectedNative = { _, _ -> error("No per-operation persistence") }
            val tx = h.pickup(h.source.copy(material = material))
            val future = h.journal.submitNative(h.city, tx)
            h.ticks()
            future.join() shouldBe tx.after
            h.city shouldBe tx.after
            h.barrel.getItem(0) shouldBe null
            verify(exactly = 1) { h.store.saveInventoryTransaction(tx) }
            h.busy.isEmpty() shouldBe true
        }

        test("RAM completion failure recovers the same food pickup once material=$material") {
            h.storage.residentFoods = CivsResidentFoods(mapOf("BREAD" to CivResidentFoodDefinition(6), "APPLE" to CivResidentFoodDefinition(4)))
            h.barrel.setItem(0, ItemStack(Material.valueOf(material), 4))
            h.storage.persistCollectedNative = { _, _ -> error("No forced persistence on RAM retry") }
            var attempts = 0
            val tx = h.pickup(h.source.copy(material = material))
            every { h.store.completeInventoryTransaction(tx) } answers {
                if (++attempts == 1) CompletableFuture.failedFuture(IllegalStateException("RAM completion unavailable"))
                else CompletableFuture.completedFuture(tx.after)
            }
            val before = h.city
            val future = h.journal.submitNative(before, tx)
            h.ticks()
            shouldThrow<CompletionException> { future.join() }
            h.city shouldBe before
            h.barrel.getItem(0) shouldBe null
            every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(tx))
            every { h.store.loadConsumedItems() } returns CompletableFuture.completedFuture(CivConsumedItemIndex.EMPTY)
            val boot = h.journal.bootstrap(); h.ticks(); boot.join()
            h.journal.recover(); h.ticks()
            h.city shouldBe tx.after
            h.journal.recover(); h.ticks()
            h.city shouldBe tx.after
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
            verify(exactly = 1) { h.store.deleteInventoryTransaction(tx.id) }
            attempts shouldBe 2
        }
    }

})

class CivsUpgradeStorageReceiptTest : FunSpec({
    lateinit var f: UpgradeStorageFixture
    beforeEach { f = UpgradeStorageFixture() }
    afterEach { f.close() }

    test("a full old warehouse control evacuates into its new control beside 58 unchanged chests") {
        val future = f.largeWarehouse()
        f.h.world.getBlockAt(f.source.x, f.source.y, f.source.z).type = Material.BARREL
        f.fillSource()
        f.inventory(f.control).clear()
        val keepsake = ItemStack(Material.GOLD_INGOT, 3)
        f.inventory(f.destination).setItem(3, keepsake)
        val before = f.h.city
        val source = f.items(f.source)
        val checked = mutableSetOf<CivPosition>()
        f.h.storage.foodCanModify = { _, point -> checked.add(point); true }
        val tx = f.prepare(future + (f.source to "minecraft:air"))

        tx.containerRelocation!!.containers.map { it.position }.toSet() shouldBe setOf(f.source, f.control)
        tx.playerId() shouldBe null
        checked shouldBe setOf(f.source, f.control)
        f.items(f.source) shouldBe source
        f.items(f.control).all { it == null } shouldBe true
        verify(exactly = 0) { f.h.store.saveInventoryTransaction(any()) }

        val result = f.h.journal.submitRelocation(before, tx)
        f.h.ticks()
        result.join() shouldBe tx.after
        f.items(f.source).all { it == null } shouldBe true
        f.items(f.control) shouldBe source
        f.inventory(f.destination).getItem(3) shouldBe keepsake
        f.h.city.inventory!!.containers shouldBe listOf(f.destination)
        verify(exactly = 1) { f.h.store.saveInventoryTransaction(tx) }
    }

    test("one changed chest half evacuates both native inventories beside an oversized stable shelf set") {
        val future = f.largeWarehouse()
        val partner = f.source.copy(x = f.source.x - 1)
        f.chest(f.source, "south", "left")
        f.chest(partner, "south", "right")
        f.inventory(f.source).setItem(0, ItemStack(Material.DIAMOND, 3))
        f.inventory(partner).setItem(5, ItemStack(Material.GOLD_INGOT, 7))
        f.inventory(f.control).clear()
        val source = f.items(f.source).filterNotNull() + f.items(partner).filterNotNull()
        val tx = f.prepare(future + mapOf(f.source to "minecraft:air", partner to f.state("south", "right")))

        tx.containerRelocation!!.containers.map { it.position }.toSet() shouldBe setOf(f.source, partner, f.control)
        f.h.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        f.h.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        f.items(f.source).all { it == null } shouldBe true
        f.items(partner).all { it == null } shouldBe true
        f.items(f.control).filterNotNull().toSet() shouldBe source.toSet()
    }

    test("a full new warehouse control falls back to both safe halves of a retained local shelf before another building") {
        val future = f.largeWarehouse()
        f.h.world.getBlockAt(f.source.x, f.source.y, f.source.z).type = Material.BARREL
        f.fillSource()
        val left = f.source.copy(z = f.source.z - 1)
        val right = left.copy(x = left.x - 1)
        f.chest(left, "south", "left")
        f.chest(right, "south", "right")
        f.fillShelves(future, setOf(left, right))
        val unused = ItemStack(Material.DIRT, 64)
        for ((point, occupied) in listOf(left to 14, right to 13)) {
            val shelf = f.inventory(point)
            repeat(occupied) { shelf.setItem(it, unused.clone()) }
        }
        val elsewhere = f.addReceiver()
        val external = f.items(elsewhere.anchor)
        val source = f.items(f.source).filterNotNull()
        val marker = f.items(f.control)
        val tx = f.prepare(future + mapOf(f.source to "minecraft:air",
            left to f.state("south", "left"), right to f.state("south", "right")))

        tx.containerRelocation!!.containers.map { it.position }.toSet() shouldBe setOf(f.source, f.control, left, right)
        f.items(f.source).filterNotNull() shouldBe source
        f.h.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        f.items(f.source).all { it == null } shouldBe true
        f.items(f.control) shouldBe marker
        f.items(elsewhere.anchor) shouldBe external
        val moved = (f.items(left) + f.items(right)).filterNotNull()
        moved.filter { it.material == "DIRT" }.size shouldBe 27
        moved.filterNot { it.material == "DIRT" } shouldBe source
    }

    test("large warehouse evacuation leaves all inventories intact when every receiver is full") {
        val future = f.largeWarehouse()
        f.h.world.getBlockAt(f.source.x, f.source.y, f.source.z).type = Material.BARREL
        f.fillSource()
        f.fillShelves(future)
        val city = f.h.city
        val slots = f.allItems()
        val checked = mutableSetOf<CivPosition>()
        f.h.storage.foodCanModify = { _, point -> checked.add(point); true }
        val id = UUID.randomUUID()
        f.h.storage.reserve(city, id)

        shouldThrow<CivsRejection> {
            f.h.storage.prepareUpgradeRelocation(city, f.target, future + (f.source to "minecraft:air"))
        }.reason shouldBe "storage-full"
        f.h.city shouldBe city
        f.allItems() shouldBe slots
        (checked.size <= CivContainerRelocation.MAX_CONTAINERS) shouldBe true
        verify(exactly = 0) { f.h.store.saveInventoryTransaction(any()) }
        f.h.storage.unlock(id)
    }

    test("one empty changing barrel retires without a receiver when every other native holder is full") {
        f.configure()
        f.h.world.getBlockAt(f.source.x, f.source.y, f.source.z).type = Material.BARREL
        repeat(STORAGE_SLOTS) { f.inventory(f.destination).setItem(it, ItemStack(Material.DIRT, 64)) }
        val before = f.h.city
        val original = f.allItems()
        val fullHall = f.h.barrel.storageContents.map(CivResidentItem::of)
        val tx = f.prepare(f.future + (f.source to "minecraft:air"))
        val proof = requireNotNull(tx.containerRelocation)
        proof.containers.map { it.position } shouldBe listOf(f.source)
        proof.changes() shouldBe emptyList()
        proof.unregister!!.cells.map { it.position } shouldBe listOf(f.source)
        val result = f.h.journal.submitRelocation(before, tx); f.h.ticks()
        result.join() shouldBe tx.after
        f.h.city.inventory!!.containers shouldBe listOf(f.destination)
        f.allItems() shouldBe original
        f.h.barrel.storageContents.map(CivResidentItem::of) shouldBe fullHall
        f.h.world.getBlockAt(f.source.x, f.source.y, f.source.z).type shouldBe Material.BARREL
        shouldThrow<IllegalArgumentException> { proof.copy(unregister = null).validated(before) }
    }

    test("empty registration retirement waits for RAM admission and completion without native disk fences") {
        f.configure()
        val before = f.h.city
        val original = f.allItems()
        val tx = f.prepare()
        requireNotNull(tx.containerRelocation).changes() shouldBe emptyList()
        val admitted = CompletableFuture<CivInventoryTransaction>()
        val completed = CompletableFuture<Settlement>()
        every { f.h.store.saveInventoryTransaction(tx) } returns admitted
        every { f.h.store.completeInventoryTransaction(tx) } returns completed
        f.h.storage.persistCollectedNative = { _, _ -> error("No forced native persistence") }
        val result = f.h.journal.submitRelocation(before, tx)
        f.h.ticks()
        f.h.city shouldBe before
        verify(exactly = 1) { f.h.store.saveInventoryTransaction(tx) }
        admitted.complete(tx); f.h.ticks()
        f.h.city shouldBe before
        result.isDone shouldBe false
        completed.complete(tx.after); f.h.ticks()
        result.join() shouldBe tx.after
        f.h.city.inventory!!.containers shouldBe listOf(f.destination)
        f.allItems() shouldBe original
        f.h.world.getBlockAt(f.source.x, f.source.y, f.source.z).type shouldBe Material.CHEST
    }

    for (published in listOf(false, true))
    test("empty registration retirement survives durable receipt reload without dropping any native holder published=$published") {
        f.configure()
        val before = f.h.city
        val original = f.allItems()
        val tx = f.prepare()
        val root = Files.createTempDirectory("civs-empty-registration-retirement-")
        val accepted = try {
            CivsStore(root, Executor(Runnable::run)).use { store ->
                store.save(before).join()
                if (published) store.save(tx.after).join()
                writeLegacyInventoryReceipt(root, tx)
                f.h.city = store.load().join().single()
                store.loadInventoryTransactions().join().single().also { it shouldBe tx }
            }
        } finally { root.toFile().deleteRecursively() }
        f.h.storage.unlock(tx.id)
        f.h.storage.updateCity(f.h.city)
        every { f.h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(accepted))
        every { f.h.store.loadConsumedItems() } returns CompletableFuture.completedFuture(CivConsumedItemIndex.EMPTY)
        val boot = f.h.journal.bootstrap(); f.h.ticks(); boot.join()
        repeat(2) { f.h.journal.recover(); f.h.ticks() }
        f.h.city shouldBe accepted.after
        requireNotNull(accepted.containerRelocation).before(f.h.city) shouldBe before
        f.allItems() shouldBe original
        f.h.journal.pendingCount shouldBe 0
        verify(exactly = 1) { f.h.store.deleteInventoryTransaction(tx.id) }
    }

    test("a legacy relocation JSON without registration proof replays exact items and keeps its registry") {
        f.configure()
        f.fillSource()
        val before = f.h.city
        val items = f.items(f.source)
        val tx = f.prepare()
        f.h.storage.unlock(tx.id)
        val legacy = tx.copy(after = before.copy(revision = before.revision + 1),
            containerRelocation = tx.containerRelocation!!.copy(unregister = null)).validated()
        val gson = com.google.gson.Gson()
        val json = gson.toJsonTree(legacy).asJsonObject
        json.getAsJsonObject("containerRelocation").remove("unregister")
        val accepted = gson.fromJson(json, CivInventoryTransaction::class.java).validated()
        accepted.containerRelocation!!.unregister shouldBe null
        every { f.h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(accepted))
        every { f.h.store.loadConsumedItems() } returns CompletableFuture.completedFuture(CivConsumedItemIndex.EMPTY)
        val boot = f.h.journal.bootstrap(); f.h.ticks(); boot.join()
        repeat(2) { f.h.journal.recover(); f.h.ticks() }
        f.h.city shouldBe accepted.after
        f.h.city.inventory shouldBe before.inventory
        f.items(f.source).all { it == null } shouldBe true
        f.items(f.destination) shouldBe items
        f.h.journal.pendingCount shouldBe 0
        verify(exactly = 1) { f.h.store.deleteInventoryTransaction(tx.id) }
    }

    test("the durable retirement snapshot detaches proof collections before asynchronous storage") {
        f.configure()
        val tx = f.prepare()
        val proof = requireNotNull(tx.containerRelocation)
        val retirement = requireNotNull(proof.unregister)
        val registered = retirement.registeredBefore.toMutableList()
        val cells = retirement.cells.toMutableList()
        val mutable = tx.copy(containerRelocation = proof.copy(unregister = retirement.copy(
            registeredBefore = registered, cells = cells)))
        val pending = ArrayDeque<Runnable>()
        val root = Files.createTempDirectory("civs-retirement-snapshot-")
        try {
            CivsStore(root, Executor(Runnable::run)).save(f.h.city).join()
            CivsStore(root, Executor { pending.addLast(it) }).use { store ->
                val write = store.saveInventoryTransaction(mutable)
                registered.clear(); cells.clear()
                pending.removeFirst().run()
                write.join() shouldBe tx
                val load = store.loadInventoryTransactions()
                pending.removeFirst().run()
                load.join() shouldBe listOf(tx)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    test("refilling an empty source before receipt admission rejects registration retirement") {
        f.configure()
        val before = f.h.city
        val tx = f.prepare()
        val fence = CompletableFuture<Unit>()
        f.h.storage.persistCollectedNative = { _, _ -> fence }
        val result = f.h.journal.submitRelocation(before, tx)
        f.inventory(f.source).setItem(0, ItemStack(Material.DIAMOND, 7))
        fence.complete(Unit); f.h.ticks()
        shouldThrow<CompletionException> { result.join() }
        f.h.city shouldBe before
        f.inventory(f.source).getItem(0) shouldBe ItemStack(Material.DIAMOND, 7)
        verify(exactly = 0) { f.h.store.saveInventoryTransaction(any()) }
    }

    test("a source refill after accepted native retirement does not roll back the committed city") {
        f.configure()
        val before = f.h.city
        val tx = f.prepare()
        val completed = CompletableFuture<Settlement>()
        every { f.h.store.completeInventoryTransaction(tx) } returns completed
        f.h.storage.persistCollectedNative = { _, _ -> error("No forced native persistence") }
        val result = f.h.journal.submitRelocation(before, tx); f.h.ticks()
        result.isDone shouldBe false
        f.inventory(f.source).setItem(0, ItemStack(Material.DIAMOND, 7))
        completed.complete(tx.after); f.h.ticks()
        result.join() shouldBe tx.after
        f.h.city shouldBe tx.after
        f.inventory(f.source).getItem(0) shouldBe ItemStack(Material.DIAMOND, 7)
        verify(exactly = 0) { f.h.store.save(any()) }
        verify(exactly = 1) { f.h.store.deleteInventoryTransaction(tx.id) }
    }

    test("permission loss after accepted native retirement does not revoke its committed result") {
        f.configure()
        val before = f.h.city
        val original = f.allItems()
        val tx = f.prepare()
        val completed = CompletableFuture<Settlement>()
        every { f.h.store.completeInventoryTransaction(tx) } returns completed
        val result = f.h.journal.submitRelocation(before, tx); f.h.ticks()
        f.h.foodAllowed = false
        completed.complete(tx.after); f.h.ticks()
        result.join() shouldBe tx.after
        f.h.city shouldBe tx.after
        f.h.journal.pendingCount shouldBe 0
        f.allItems() shouldBe original
        verify(exactly = 0) { f.h.store.save(any()) }
        verify(exactly = 1) { f.h.store.saveInventoryTransaction(tx) }
        verify(exactly = 1) { f.h.store.deleteInventoryTransaction(tx.id) }
    }

    test("fifteen retained control barrels stop blocking upgrade capture only after exact empty retirement") {
        f.configure()
        f.h.world.loadChunk(0, -1)
        f.building = f.building.copy(bounds = f.building.bounds.copy(max = f.building.bounds.max.copy(z = 3)))
        val barrels = (7..11).flatMap { x -> (1..3).map { z -> CivPosition(x, 64, z) } }
        barrels.forEach { p -> f.h.world.getBlockAt(p.x, p.y, p.z).type = Material.BARREL }
        f.h.city = CivsRules.validate(f.h.city.copy(buildings = f.h.city.buildings.map {
            if (it.id == f.building.id) f.building else it
        }, inventory = f.h.city.inventory!!.copy(containers = barrels + f.destination)))
        f.h.storage.replaceCities(listOf(f.h.city))
        val before = f.h.city
        val future = f.future + (f.source to f.state("south")) + barrels.associateWith { "minecraft:air" }
        val cells = (f.target.bounds.min.x..f.target.bounds.max.x).flatMap { x ->
            (f.target.bounds.min.y..f.target.bounds.max.y).flatMap { y ->
                (f.target.bounds.min.z..f.target.bounds.max.z).map { z ->
                    val p = CivPosition(x, y, z)
                    val state = future[p] ?: "minecraft:barrier"
                    ru.arc.paper.api.ArcSchematicBlock(ru.arc.paper.api.ArcSchematicPoint(x, y, z), state,
                        state == "minecraft:air", state == "minecraft:barrier")
                }
            }
        }
        CivsWorld(f.h.plugin, f.h.tasks, { 0L }) { listOf(f.h.city) }.use { world ->
            fun capture() = world.capture(before.id, before.owner, before.worldId, f.target, cells, f.building)
                .also { f.h.ticks() }
            requireNotNull(CivsDiagnostics.rejection(shouldThrow<CompletionException> { capture().join() })).reason shouldBe
                "registered-container"
            val tx = f.prepare(future)
            tx.containerRelocation!!.unregister!!.cells.size shouldBe 15
            tx.containerRelocation!!.changes() shouldBe emptyList()
            val retired = f.h.journal.submitRelocation(before, tx); f.h.ticks(); retired.join() shouldBe tx.after
            f.h.city.inventory!!.containers shouldBe listOf(f.destination)
            val accepted = capture().join()
            val cleared = accepted.blocks.filter { it.position in barrels }
            cleared.size shouldBe 15
            cleared.all { it.before.startsWith("minecraft:barrel[") && it.after == "minecraft:air" } shouldBe true
            accepted.blocks.filter { it.position !in barrels }.all { it.before == it.after } shouldBe true
            barrels.forEach { p -> f.h.world.getBlockAt(p.x, p.y, p.z).type shouldBe Material.BARREL }
            f.inventory(barrels.first()).setItem(0, ItemStack(Material.DIAMOND))
            requireNotNull(CivsDiagnostics.rejection(shouldThrow<CompletionException> { capture().join() })).reason shouldBe
                "container-not-empty"
            f.inventory(barrels.first()).getItem(0) shouldBe ItemStack(Material.DIAMOND)
        }
    }

    test("retirement proof cannot unregister a current control foreign holder or matching authored holder") {
        f.configure()
        val foreign = f.addReceiver()
        val tx = f.prepare()
        val proof = requireNotNull(tx.containerRelocation)
        val retirement = requireNotNull(proof.unregister)
        for (point in listOf(f.control, foreign.anchor, f.destination)) {
            val block = f.h.world.getBlockAt(point.x, point.y, point.z)
            val state = block.blockData.asString
            val holder = CivRelocatedContainer(point, block.type.name, state,
                List(STORAGE_SLOTS) { null }, List(STORAGE_SLOTS) { null })
            val registered = (retirement.registeredBefore + point).distinct()
            val changed = retirement.copy(registeredBefore = registered, cells = listOf(CivPlanBlock(point, state,
                if (point == f.destination) state else "minecraft:air")))
            val forged = proof.copy(containers = proof.containers.filterNot { it.position == point } + holder, unregister = changed)
            val after = tx.after.copy(inventory = tx.after.inventory!!.copy(containers = registered - point))
            shouldThrow<IllegalArgumentException> { tx.copy(after = after, containerRelocation = forged).validated() }
        }
    }

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
        tx.after shouldBe before.copy(revision = before.revision + 1,
            inventory = before.inventory!!.copy(containers = before.inventory!!.containers - f.source))
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
        tx.after.inventory shouldBe before.inventory!!.copy(containers = before.inventory!!.containers - f.source)
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
        tx.after.inventory shouldBe before.inventory!!.copy(containers = before.inventory!!.containers - f.source)
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
                writeLegacyInventoryReceipt(root, tx)
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
                writeLegacyInventoryReceipt(root, tx)
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
            f.h.city shouldBe requireNotNull(tx.containerRelocation).before(tx.after)
            holders.associate { it.position to f.items(it.position) } shouldBe before
            f.h.journal.pendingCount shouldBe 1
            verify(exactly = 0) { f.h.store.deleteInventoryTransaction(any()) }
        }
        totals(holders.map { f.items(it.position) }) shouldBe totals(original.values)
        verify(exactly = 0) { f.h.store.saveInventoryTransaction(any()) }
    }

    test("relocation preserves admission and publication locks without forcing native saves") {
        f.configure()
        f.fillSource()
        val original = f.items(f.source)
        val before = f.h.city
        val tx = f.prepare()
        val admitted = CompletableFuture<CivInventoryTransaction>()
        val completed = CompletableFuture<Settlement>()
        every { f.h.store.saveInventoryTransaction(tx) } returns admitted
        every { f.h.store.completeInventoryTransaction(tx) } returns completed
        f.h.storage.persistCollectedNative = { _, _ -> error("No forced native persistence") }
        val result = f.h.journal.submitRelocation(before, tx)
        f.h.ticks()
        result.isDone shouldBe false
        f.items(f.source) shouldBe original
        verify(exactly = 1) { f.h.store.saveInventoryTransaction(tx) }
        admitted.complete(tx); f.h.ticks()
        f.items(f.source).all { it == null } shouldBe true
        f.items(f.destination) shouldBe original
        f.h.city shouldBe before
        result.isDone shouldBe false
        completed.complete(tx.after); f.h.ticks()
        result.join() shouldBe tx.after
        f.h.city shouldBe tx.after
        verify(exactly = 1) { f.h.store.deleteInventoryTransaction(tx.id) }
    }

    test("RAM completion failure replays the same exact accepted relocation once") {
        f.configure()
        f.fillSource()
        val original = f.items(f.source)
        val before = f.h.city
        val tx = f.prepare()
        var attempts = 0
        var intent = true
        var admissions = 0
        f.h.storage.persistCollectedNative = { _, _ -> error("No forced native persistence") }
        every { f.h.store.completeInventoryTransaction(tx) } answers {
            if (++attempts == 1) CompletableFuture.failedFuture(IllegalStateException("RAM completion unavailable"))
            else CompletableFuture.completedFuture(tx.after)
        }
        val result = f.h.journal.submitRelocation(before, tx) { admissions++; check(intent) }
        f.h.ticks()
        shouldThrow<CompletionException> { result.join() }
        f.h.city shouldBe before
        f.items(f.destination) shouldBe original
        intent = false // An accepted durable transfer recovers independently of the retired player request.
        every { f.h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(tx))
        every { f.h.store.loadConsumedItems() } returns CompletableFuture.completedFuture(CivConsumedItemIndex.EMPTY)
        val boot = f.h.journal.bootstrap(); f.h.ticks(); boot.join()
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

    test("ordinary relocation has no dependency on the native persistence writer") {
        f.configure()
        f.fillSource()
        val original = f.allItems()
        val before = f.h.city
        val tx = f.prepare()
        f.h.storage.persistCollectedNative = { _, _ -> error("No forced native persistence") }
        val result = f.h.journal.submitRelocation(before, tx)
        f.h.ticks()
        result.join() shouldBe tx.after
        f.h.city shouldBe tx.after
        fun totals(values: Collection<List<CivResidentItem?>>) = values.flatten().filterNotNull()
            .groupingBy { it.material to it.data }.fold(0L) { sum, item -> sum + item.amount }
        totals(f.allItems().values) shouldBe totals(original.values)
        f.h.busy.isEmpty() shouldBe true
        verify(exactly = 1) { f.h.store.saveInventoryTransaction(tx) }
    }

    test("revoked ephemeral player intent before RAM admission prevents journal admission and all native moves") {
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
            // The upgraded receipt has removed this old registration; it cannot receive a fresh deposit.
            shouldThrow<CivsRejection> { f.h.storage.prepareHomeDeposit(f.h.city, before.id, f.source,
                Location(f.h.world, 8.5, 64.0, 1.5), emptyMap()) }.reason shouldBe "worker-too-far"
            val prepared = f.h.storage.prepareHomeDeposit(f.h.city, before.id, f.destination,
                Location(f.h.world, 10.5, 64.0, 1.5), emptyMap())
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
    fun largeWarehouse(): Map<CivPosition, String> {
        configure()
        val others = buildList {
            for (x in 7..12) for (y in 64..68) for (z in -1..1) {
                val point = CivPosition(x, y, z)
                if (point != source && point != destination && point != control) add(point)
            }
        }.take(57)
        for (point in others) {
            h.world.loadChunk(point.x shr 4, point.z shr 4)
            chest(point, "east")
        }
        h.indexed += others
        h.storage.replaceCities(listOf(h.city))
        return future + others.associateWith { state("east") }
    }
    fun fillShelves(future: Map<CivPosition, String>, except: Set<CivPosition> = emptySet()) {
        for (point in future.keys - except - source - control) {
            val shelf = inventory(point)
            repeat(STORAGE_SLOTS) { shelf.setItem(it, ItemStack(Material.DIRT, 64)) }
        }
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
    fun prepare(futureCells: Map<CivPosition, String> = future): CivInventoryTransaction {
        val before = h.city
        val id = UUID.randomUUID()
        h.storage.reserve(before, id)
        val proof = requireNotNull(h.storage.prepareUpgradeRelocation(before, target, futureCells))
        return CivInventoryTransaction(id, before.id, before.revision, proof.after(before),
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
    val indexed = mutableListOf<CivPosition>()
    val storage = CivsStorage(plugin) { chunk ->
        indexed.filter { (it.x shr 4) == chunk.x && (it.z shr 4) == chunk.z }
            .map { world.getBlockAt(it.x, it.y, it.z).state }.toTypedArray()
    }
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
    val store = mockk<CivsStore>().also { it.stubCheckpointAcknowledgements() }
    val busy = mutableSetOf<UUID>()
    val unhealthy = mutableSetOf<UUID>()
    var time = 10_000L
    var foodAllowed = true
    var freshAllowed = true
    var arrivedForBufferedPickup: (UUID, UUID, UUID) -> Boolean = { _, _, _ -> true }
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
            }, { _, _, _ -> null }, {}, { tx, _ -> cities.getValue(tx.cityId) }, {}, { time },
            requireBufferedPickupArrival = { city, worker, building ->
                if (!arrivedForBufferedPickup(city, worker, building)) throw CivsRejection("route-unreachable")
            })
    }
    fun foodPhase(phase: BuildingPhase) {
        val hall = city.buildings.single().copy(phase = phase)
        city = CivsRules.validate(city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
            buildings = listOf(hall), workers = city.workers.map { it.copy(hunger = 0, homeId = hall.id) },
            construction = if (phase == BuildingPhase.CONSTRUCTING) CivConstruction(UUID.randomUUID(), hall.id,
                1, 1, 0, ConstructionPhase.CONSTRUCTING, Stock(), plannedBuilding = hall,
                batches = CivConstructionBatches(1, 16), materials = emptyMap(), payment = CivConstructionPayment()) else null))
        storage.replaceCities(listOf(city))
    }
    fun observedFoodSource(worker: CivWorker, feet: Location, tavernService: Boolean = false): CivResidentFoodSource? {
        storage.capturePlanningStorage(city)
        return storage.residentFoodSource(city, worker, feet, tavernService)
    }
    fun pickup(target: CivResidentFoodSource = source): CivInventoryTransaction {
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val prepared = storage.prepareFoodPickup(city, worker.id, target, feet)
        val tx = CivInventoryTransaction(id, city.id, city.revision,
            city.copy(workers = listOf(prepared.worker), revision = city.revision + 1),
            emptyList(), collected = requireNotNull(prepared.transfer))
        storage.seal(tx)
        return tx
    }
    fun ticks() { paper.performTicks(8) }
    override fun close() { tasks.close(); storage.close(); paper.close() }
}


internal fun CivsStorage.capturePlanningStorage(city: Settlement) {
    updateCity(city)
    val capture = startSupplyCapture(city, forceNative = true)
    while (!capture.step()) Unit
}

class CivsConstructionStorageTest : FunSpec({
    test("full native construction stores evacuate one exact house chest and return its partial balance after cancellation") {
        UpgradeStorageFixture().use { f ->
            f.configure(BuildingType.HOUSE)
            f.fillSource()
            repeat(STORAGE_SLOTS) { f.inventory(f.destination).setItem(it, ItemStack(Material.DIRT, 64)) }
            val before = f.h.city
            val origin = f.items(f.source)
            val oldControl = f.items(f.control)
            val oldShelf = f.items(f.destination)
            val owner = CivConstructionStorageOwner(before.id, before.worldId, f.building, f.target, "a".repeat(64))
            val cell = CivPlanBlock(f.source, f.state("south"), "minecraft:air")
            val id = UUID.randomUUID()
            f.h.storage.reserve(before, id)
            val capture = requireNotNull(f.h.storage.prepareConstructionStorage(before, f.target, listOf(cell)))
            val root = Files.createTempDirectory("civs-construction-buffer")
            val direct = Executor { it.run() }
            try {
                val snapshot = CivConstructionStorageSnapshot(id, owner, cell, origin, UUID.randomUUID())
                val part = CivsStore(root, direct).use { store ->
                    store.save(before).join()
                    store.savePlan(CivPlacementPlan(snapshot.planId, before.id, before.worldId, f.target, listOf(cell), owner.schematicSha256)).join()
                    store.saveConstructionStorage(snapshot).join()
                }
                val move = CivContainerRelocation(f.building, listOf(capture.holder), capture.retirement,
                    CivConstructionStorageMove(owner, null, part, cell))
                val tx = CivInventoryTransaction(id, before.id, before.revision, move.after(before), emptyList(),
                    containerRelocation = move).validated()
                f.h.storage.seal(tx)
                f.items(f.source) shouldBe origin
                CivsStore(root, direct).use { store ->
                    store.saveInventoryTransaction(tx).join() shouldBe tx
                    store.deletePlan(snapshot.planId).join() shouldBe true
                    store.saveInventoryTransaction(tx).join() shouldBe tx
                    store.loadInventoryTransactions().join() shouldBe listOf(tx)
                    f.h.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
                    store.save(tx.after).join() shouldBe tx.after
                    store.deleteInventoryTransaction(tx.id).join()
                }
                f.h.city = tx.after
                f.h.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
                f.h.storage.unlock(id)
                f.h.storage.updateCity(tx.after)
                f.items(f.source).filterNotNull() shouldBe emptyList()
                f.items(f.control) shouldBe oldControl
                f.items(f.destination) shouldBe oldShelf
                CivsStore(root, direct).use { store ->
                    store.load().join().single() shouldBe tx.after
                    store.verifyConstructionReceipt(tx).join()
                    store.deleteInventoryTransaction(id).join()
                    shouldThrow<CompletionException> { store.saveInventoryTransaction(tx).join() }
                    val loaded = store.loadConstructionStorage(owner, part).join()
                    loaded.contents(part) shouldBe origin
                    val occupied = f.h.city.copy(constructionQueue = listOf(f.building.id))
                    owner.held(occupied) shouldBe true
                    shouldThrow<IllegalArgumentException> { store.delete(f.h.city) }
                    val inventory = requireNotNull(f.h.city.inventory)
                    shouldThrow<CivsRejection> { inventory.requireStorageMode(CivStorageMode.VIRTUAL) }.reason shouldBe "storage-busy"
                    shouldThrow<CompletionException> { store.save(f.h.city.copy(inventory = inventory.copy(mode = CivStorageMode.VIRTUAL),
                        revision = f.h.city.revision + 1)).join() }
                    val cancelled = f.h.city
                    repeat(STORAGE_SLOTS) { f.inventory(f.source).setItem(it, ItemStack(Material.DIRT, 64)) }
                    for (targetId in listOf(f.building.id, null)) {
                        val deleting = CivsRules.requestDeletion(cancelled, cancelled.owner, targetId)
                        owner.returningForDeletion(deleting) shouldBe true
                        if (targetId != null) shouldThrow<CivsRejection> { CivsRules.finishBuildingDeletion(deleting, deleting.owner) }
                            .reason shouldBe "storage-busy"
                        f.h.city = deleting
                        f.h.storage.updateCity(deleting)
                        val blockedId = UUID.randomUUID()
                        f.h.storage.reserve(deleting, blockedId)
                        f.h.storage.prepareConstructionReturn(deleting, loaded, part) shouldBe null
                        f.h.storage.unlock(blockedId)
                        loaded.contents(part) shouldBe origin
                        deleting.building(f.building.id) shouldBe f.building
                    }
                    f.h.city = cancelled
                    f.h.storage.updateCity(cancelled)
                    f.inventory(f.destination).setItem(17, null)
                    val returnId = UUID.randomUUID()
                    f.h.storage.reserve(f.h.city, returnId)
                    val restore = requireNotNull(f.h.storage.prepareConstructionReturn(f.h.city, loaded, part))
                    val returned = CivInventoryTransaction(returnId, before.id, f.h.city.revision, restore.after(f.h.city),
                        emptyList(), containerRelocation = restore).validated()
                    f.h.storage.seal(returned)
                    store.saveInventoryTransaction(returned).join()
                    f.h.storage.reconcile(returned) shouldBe CivInventoryApplyResult.Applied
                    store.save(returned.after).join()
                    f.h.city = returned.after
                    f.h.storage.reconcile(returned) shouldBe CivInventoryApplyResult.Applied
                    f.items(f.destination)[17] shouldBe origin[0]
                    val remainder = returned.after.inventory!!.constructionBuffers!!.getValue(f.building.id.toString()).parts.single()
                    loaded.contents(remainder).filterNotNull() shouldBe origin.drop(1).filterNotNull()
                    f.h.storage.unlock(returnId)
                    store.deleteInventoryTransaction(returnId).join()
                    CivsStore(root, direct).use { reopened ->
                        reopened.load().join().single() shouldBe returned.after
                        reopened.loadConstructionStorage(owner, remainder).join().contents(remainder) shouldBe loaded.contents(remainder)
                    }
                }
            } finally { root.toFile().deleteRecursively() }
        }
    }
    test("large native components stay outside the city and a failed durable snapshot never clears a slot") {
        UpgradeStorageFixture().use { f ->
            f.configure(BuildingType.FARM)
            val before = f.h.city
            val random = java.util.Random(333)
            for (slot in 0..19) {
                val bytes = ByteArray(if (slot == 0) 40_000 else 18_000).also(random::nextBytes)
                f.inventory(f.source).setItem(slot, ItemStack(Material.PAPER, 1).apply { editMeta {
                    it.displayName(net.kyori.adventure.text.Component.text("Original $slot"))
                    it.persistentDataContainer.set(org.bukkit.NamespacedKey(f.h.plugin, "payload"),
                        org.bukkit.persistence.PersistentDataType.BYTE_ARRAY, bytes)
                } })
            }
            val id = UUID.randomUUID()
            val owner = CivConstructionStorageOwner(before.id, before.worldId, f.building, f.target, "b".repeat(64))
            val cell = CivPlanBlock(f.source, f.state("south"), "minecraft:oak_planks")
            f.h.storage.reserve(before, id)
            val capture = requireNotNull(f.h.storage.prepareConstructionStorage(before, f.target, listOf(cell)))
            val snapshot = CivConstructionStorageSnapshot(id, owner, cell, capture.holder.before, UUID.randomUUID())
            val root = Files.createTempDirectory("civs-large-construction-storage")
            val direct = Executor { it.run() }
            try {
                CivsStore(root, direct).use { store ->
                    store.savePlan(CivPlacementPlan(snapshot.planId, before.id, before.worldId, f.target, listOf(cell), owner.schematicSha256)).join()
                    val blocked = root.resolve("construction-buffers")
                    Files.writeString(blocked, "file instead of directory")
                    shouldThrow<CompletionException> { store.saveConstructionStorage(snapshot).join() }
                    capture.holder.before shouldBe f.inventory(f.source).storageContents.map(CivResidentItem::ofConstruction)
                    Files.delete(blocked)
                    val part = store.saveConstructionStorage(snapshot).join()
                    (Files.size(blocked.resolve("$id.json")) > CivsStore.MAX_CITY_BYTES) shouldBe true
                    (capture.holder.before.first()!!.data!!.length > 32768) shouldBe true
                    val move = CivContainerRelocation(f.building, listOf(capture.holder), capture.retirement,
                        CivConstructionStorageMove(owner, null, part, cell))
                    val after = move.after(before)
                    (com.google.gson.Gson().toJson(after).toByteArray().size < CivsStore.MAX_CITY_BYTES) shouldBe true
                    val restored = store.loadConstructionStorage(owner, part).join().contents(part)
                    restored.forEachIndexed { slot, item ->
                        val actual = item?.constructionStack()
                        val original = f.inventory(f.source).getItem(slot)
                        actual?.type shouldBe original?.type
                        actual?.amount shouldBe original?.amount
                        actual?.itemMeta?.displayName() shouldBe original?.itemMeta?.displayName()
                        val key = org.bukkit.NamespacedKey(f.h.plugin, "payload")
                        val type = org.bukkit.persistence.PersistentDataType.BYTE_ARRAY
                        actual?.itemMeta?.persistentDataContainer?.get(key, type)?.toList() shouldBe
                            original?.itemMeta?.persistentDataContainer?.get(key, type)?.toList()
                    }
                    val altered = snapshot.copy(items = snapshot.items.toMutableList().also { it[0] = null })
                    shouldThrow<CompletionException> { store.saveConstructionStorage(altered).join() }
                    shouldThrow<CompletionException> { store.loadConstructionStorage(owner, part.copy(sha256 = "0".repeat(64))).join() }
                    shouldThrow<CompletionException> { store.loadConstructionStorage(owner.copy(cityId = UUID.randomUUID()), part).join() }
                    val file = blocked.resolve("$id.json")
                    val exactBytes = Files.readAllBytes(file)
                    Files.write(file, exactBytes + byteArrayOf(32))
                    shouldThrow<CompletionException> { store.loadConstructionStorage(owner, part).join() }
                    shouldThrow<CompletionException> { store.saveConstructionStorage(snapshot).join() }
                    Files.write(file, exactBytes)
                    store.saveConstructionStorage(snapshot).join() shouldBe part
                }
            } finally {
                f.h.storage.unlock(id)
                root.toFile().deleteRecursively()
            }
        }
    }

    test("thirty five changing holders empty in individually bounded once-only batches") {
        UpgradeStorageFixture().use { f ->
            val future = f.largeWarehouse()
            val before = f.h.city
            val points = future.keys.filter { it != f.control && it != f.destination }.take(35)
            val cells = points.map { point ->
                f.inventory(point).setItem(13, ItemStack(Material.COBBLESTONE, 2))
                CivPlanBlock(point, f.h.world.getBlockAt(point.x, point.y, point.z).blockData.asString, "minecraft:air")
            }
            val owner = CivConstructionStorageOwner(before.id, before.worldId, f.building, f.target, "c".repeat(64))
            val originals = mutableSetOf<CivPosition>()
            repeat(35) {
                val city = f.h.city
                val id = UUID.randomUUID()
                f.h.storage.reserve(city, id)
                val batch = requireNotNull(f.h.storage.prepareConstructionStorage(city, f.target, cells))
                originals.add(batch.cell.position) shouldBe true
                val snapshot = CivConstructionStorageSnapshot(id, owner, batch.cell, batch.holder.before, UUID.randomUUID())
                val part = snapshot.initial()
                val move = CivContainerRelocation(f.building, listOf(batch.holder), batch.retirement,
                    CivConstructionStorageMove(owner, null, part, batch.cell))
                val tx = CivInventoryTransaction(id, before.id, city.revision, move.after(city), emptyList(),
                    containerRelocation = move).validated()
                f.h.storage.seal(tx)
                f.h.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
                f.h.city = tx.after
                f.h.storage.unlock(id)
                f.h.storage.updateCity(tx.after)
            }
            val last = UUID.randomUUID()
            f.h.storage.reserve(f.h.city, last)
            f.h.storage.prepareConstructionStorage(f.h.city, f.target, cells) shouldBe null
            f.h.storage.unlock(last)
            originals shouldBe points.toSet()
            f.h.city.inventory!!.bufferStock(f.building.id) shouldBe mapOf("COBBLESTONE" to 70L)
        }
    }
    test("mixed native and buffered cargo waits for RAM completion before publishing the same courier bag") {
        UpgradeStorageFixture().use { f ->
            f.configure()
            val hut = f.addReceiver(BuildingType.BUILDER_HUT)
            val city = f.h.city.copy(workers = listOf(f.h.worker.copy(assignment = f.building.id, nextDeliveryAt = 1)))
            val owner = CivConstructionStorageOwner(city.id, city.worldId, f.building, f.target, "d".repeat(64))
            val cell = CivPlanBlock(f.source, f.state("south"), "minecraft:air")
            val rows = MutableList<CivResidentItem?>(STORAGE_SLOTS) { null }.also {
                it[0] = CivResidentItem("COAL", 1)
                it[1] = CivResidentItem.of(ItemStack(Material.COAL, 5).apply { editMeta { m ->
                    m.displayName(net.kyori.adventure.text.Component.text("Keepsake"))
                } })
            }
            val snapshot = CivConstructionStorageSnapshot(UUID.randomUUID(), owner, cell, rows, UUID.randomUUID())
            val part = snapshot.initial()
            f.h.city = city.copy(inventory = city.inventory!!.updatePart(owner, null, part))
            f.h.storage.updateCity(f.h.city)
            f.inventory(f.destination).setItem(5, ItemStack(Material.COAL, 1))
            val worker = f.h.worker
            val shipment = CivsLogistics.shipmentId(city.id, worker.id, worker.completedCycles, f.building.id, hut.id, "COAL")
            val cargo = CivCargo(shipment, f.building.id, f.building.id, hut.id, f.building.entrance, "COAL", 2)
            f.h.storage.buildingAvailable(f.h.city, f.building.id)["COAL"] shouldBe 2
            val id = UUID.randomUUID()
            val before = f.h.city
            f.h.storage.reserve(before, id)
            val prepared = f.h.storage.prepareCargoPickup(before, cargo, worker.id)
            val proof = f.h.storage.captureBufferedPickup(before, requireNotNull(prepared.bufferPickup), prepared.changes)
            proof.withdrawn shouldBe mapOf("COAL" to 1L)
            val after = CivsLogistics.pickup(before, worker.id, cargo).copy(inventory = prepared.inventoryAfter)
            val tx = CivInventoryTransaction(id, city.id, before.revision, after, prepared.changes, bufferPickup = proof).validated()
            f.h.storage.seal(tx)
            val beforeFence = CompletableFuture<Unit>()
            var arrived = true
            f.h.arrivedForBufferedPickup = { city, actor, building ->
                city shouldBe before.id; actor shouldBe worker.id; building shouldBe cargo.sourceBuildingId
                arrived
            }
            f.h.storage.persistCollectedNative = { _, source -> check(source); beforeFence }
            val rejected = f.h.journal.submitCargoPickup(before, tx)
            arrived = false
            beforeFence.complete(Unit)
            f.h.ticks()
            requireNotNull(CivsDiagnostics.rejection(shouldThrow<CompletionException> { rejected.join() })).reason shouldBe "route-unreachable"
            verify(exactly = 0) { f.h.store.saveInventoryTransaction(any()) }
            f.items(f.destination)[5] shouldBe CivResidentItem("COAL", 1)
            f.h.city shouldBe before
            arrived = true
            f.h.storage.reserve(before, id)
            f.h.storage.seal(tx)
            val persisted = CompletableFuture<Settlement>()
            every { f.h.store.verifyConstructionReceipt(tx) } returns CompletableFuture.completedFuture(Unit)
            every { f.h.store.completeInventoryTransaction(tx) } returns persisted
            f.h.storage.persistCollectedNative = { _, _ -> error("No forced native persistence") }
            val result = f.h.journal.submitCargoPickup(before, tx)
            f.h.ticks()
            result.isDone shouldBe false
            f.items(f.destination)[5] shouldBe null
            f.h.city shouldBe before
            verify(exactly = 0) { f.h.store.save(after) }
            persisted.complete(after)
            f.h.ticks()
            result.join() shouldBe after
            CivsEquipment.plainCount(f.h.worker, "COAL") shouldBe 2
            snapshot.contents(after.inventory!!.constructionBuffers!!.getValue(f.building.id.toString()).parts.single())
                .filterNotNull() shouldBe listOf(rows[1])
            CivsLogistics.pickup(after, worker.id, cargo) shouldBe after
        }
    }

    test("unregistered and foreign site chests stay independent and only an active depot can receive overflow") {
        UpgradeStorageFixture().use { f ->
            f.configure(BuildingType.FARM)
            val house = f.addReceiver(BuildingType.HOUSE, CivPosition(3, 64, 5))
            val depot = f.addReceiver(BuildingType.WAREHOUSE, CivPosition(-5, 64, 8))
            val city = f.h.city
            val cell = CivPlanBlock(f.source, f.state("south"), "minecraft:air")
            val site = f.building.copy(phase = BuildingPhase.SITE)
            val siteTarget = site.copy(phase = BuildingPhase.CONSTRUCTING)
            civsConstructionStorageOwns(city, site, siteTarget, f.source) shouldBe true
            civsConstructionStorageOwns(city.copy(inventory = city.inventory!!.copy(containers = emptyList())), site, siteTarget, f.source) shouldBe false
            civsConstructionStorageOwns(city, site, siteTarget, f.control) shouldBe false
            civsConstructionReturnAllowed(city, f.building, house.anchor) shouldBe false
            civsConstructionReturnAllowed(city, f.building, depot.anchor) shouldBe true
            civsConstructionReturnAllowed(city.copy(constructionQueue = listOf(depot.id)), f.building, depot.anchor) shouldBe false
        }
    }
    test("an expanded registered chest keeps its exact debit authority in cold after-revision recovery") {
        UpgradeStorageFixture().use { f ->
            f.configure()
            val point = CivPosition(13, 64, 0)
            f.chest(point, "east")
            f.inventory(point).setItem(4, ItemStack(Material.GOLD_INGOT, 3))
            f.h.city = f.h.city.copy(inventory = f.h.city.inventory!!.copy(containers = f.h.city.inventory!!.containers + point))
            f.h.storage.replaceCities(listOf(f.h.city))
            val before = f.h.city
            val target = f.target.copy(bounds = CivBox(f.target.bounds.min, f.target.bounds.max.copy(x = 13)))
            val owner = CivConstructionStorageOwner(before.id, before.worldId, f.building, target, "f".repeat(64))
            val id = UUID.randomUUID()
            val cell = CivPlanBlock(point, f.state("east"), "minecraft:air")
            f.h.storage.reserve(before, id)
            val batch = requireNotNull(f.h.storage.prepareConstructionStorage(before, target, listOf(cell)))
            val snapshot = CivConstructionStorageSnapshot(id, owner, cell, batch.holder.before, UUID.randomUUID())
            val part = snapshot.initial()
            val move = CivContainerRelocation(f.building, listOf(batch.holder), batch.retirement,
                CivConstructionStorageMove(owner, null, part, cell))
            val tx = CivInventoryTransaction(id, before.id, before.revision, move.after(before), emptyList(),
                containerRelocation = move).validated()
            f.inventory(point).setItem(4, ItemStack(Material.GOLD_INGOT, 2))
            shouldThrow<CivsRejection> { f.h.storage.seal(tx) }.reason shouldBe "inventory-conflict"
            f.items(point)[4] shouldBe CivResidentItem("GOLD_INGOT", 2)
            f.inventory(point).setItem(4, ItemStack(Material.GOLD_INGOT, 3))
            f.h.storage.unlock(id)
            f.h.city = tx.after
            f.h.storage.updateCity(tx.after)
            f.h.storage.lock(tx)
            f.h.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
            f.h.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
            f.items(point).filterNotNull() shouldBe emptyList()
            (point in tx.after.inventory!!.containers) shouldBe false
            f.h.storage.unlock(id)
            // Reopening the approved order after unregister can still consume its own exact empty source.
            // A newly filled, unregistered chest at the former position never receives this permission.
            f.h.world.loadChunk(0, -1)
            val blocks = (target.bounds.min.x..target.bounds.max.x).flatMap { x ->
                (target.bounds.min.y..target.bounds.max.y).flatMap { y ->
                    (target.bounds.min.z..target.bounds.max.z).map { z ->
                        val p = CivPosition(x, y, z)
                        val state = when (p) { point -> "minecraft:air"; target.control -> "minecraft:structure_void"; else -> "minecraft:barrier" }
                        ru.arc.paper.api.ArcSchematicBlock(ru.arc.paper.api.ArcSchematicPoint(x, y, z), state,
                            state == "minecraft:air", state == "minecraft:barrier")
                    }
                }
            }
            CivsWorld(f.h.plugin, f.h.tasks, { 0L }) { listOf(f.h.city) }.use { world ->
                fun retry() = world.capture(before.id, before.owner, before.worldId, target, blocks, f.building,
                    adminPlacement = true, constructionStorage = true, acceptedStorage = listOf(cell)).also { f.h.ticks() }
                retry().join().blocks.single { it.position == point } shouldBe cell
                f.inventory(point).setItem(4, ItemStack(Material.DIAMOND))
                requireNotNull(CivsDiagnostics.rejection(shouldThrow<CompletionException> { retry().join() })).reason shouldBe "protected-terrain"
                f.items(point)[4] shouldBe CivResidentItem("DIAMOND", 1)
            }
            for (p in listOf(f.source, f.destination)) repeat(STORAGE_SLOTS) { f.inventory(p).setItem(it, ItemStack(Material.DIRT, 64)) }
            val group = tx.after.inventory!!.constructionBuffers!!.getValue(f.building.id.toString())
            val returnId = UUID.randomUUID()
            f.h.storage.reserveConstructionReturn(tx.after, returnId, group, part)
            f.h.storage.prepareConstructionReturn(tx.after, snapshot, part) shouldBe null
            f.inventory(point).clear()
            f.h.foodAllowed = false
            f.h.storage.prepareConstructionReturn(tx.after, snapshot, part) shouldBe null
            f.h.foodAllowed = true
            val restored = requireNotNull(f.h.storage.prepareConstructionReturn(tx.after, snapshot, part))
            restored.restoreSourceRegistration shouldBe true
            restored.containers.map { it.position } shouldBe listOf(point)
            val returned = CivInventoryTransaction(returnId, before.id, tx.after.revision, restored.after(tx.after),
                emptyList(), containerRelocation = restored).validated()
            f.h.storage.seal(returned)
            f.h.storage.reconcile(returned) shouldBe CivInventoryApplyResult.Applied
            f.h.city = returned.after
            f.h.storage.updateCity(returned.after)
            f.h.storage.reconcile(returned) shouldBe CivInventoryApplyResult.Applied
            f.h.storage.unlock(returnId)
            f.items(point)[4] shouldBe CivResidentItem("GOLD_INGOT", 3)
            returned.after.inventory!!.constructionBuffers shouldBe null
            (point in returned.after.inventory!!.containers) shouldBe true
        }
    }

    test("a full registration list cannot credit a refilled unregistered SITE chest by its old position") {
        UpgradeStorageFixture().use { f ->
            f.configure(BuildingType.HOUSE)
            val site = f.building.copy(phase = BuildingPhase.SITE)
            f.h.city = f.h.city.copy(buildings = f.h.city.buildings.map { if (it.id == site.id) site else it })
            val target = site.copy(phase = BuildingPhase.CONSTRUCTING)
            val before = f.h.city
            val owner = CivConstructionStorageOwner(before.id, before.worldId, site, target, "d".repeat(64))
            val cell = CivPlanBlock(f.source, f.state("south"), "minecraft:stone")
            f.inventory(f.source).setItem(3, ItemStack(Material.GOLD_INGOT, 2))
            val id = UUID.randomUUID()
            f.h.storage.replaceCities(listOf(before))
            f.h.storage.reserve(before, id)
            val batch = requireNotNull(f.h.storage.prepareConstructionStorage(before, target, listOf(cell)))
            val blob = CivConstructionStorageSnapshot(id, owner, cell, batch.holder.before, UUID.randomUUID())
            val part = blob.initial()
            val move = CivContainerRelocation(site, listOf(batch.holder), batch.retirement, CivConstructionStorageMove(owner, null, part, cell))
            val tx = CivInventoryTransaction(id, before.id, before.revision, move.after(before), emptyList(), containerRelocation = move).validated()
            f.h.storage.seal(tx)
            f.h.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
            f.h.storage.unlock(id)
            val registered = (0 until CivInventory.MAX_CONTAINERS).map { CivPosition(it - 8, 64, 6) }
            val full = CivsRules.validate(tx.after.copy(revision = tx.after.revision + 1,
                inventory = tx.after.inventory!!.copy(containers = registered)))
            f.h.city = full
            f.h.indexed += f.source
            f.h.storage.replaceCities(listOf(full))
            f.inventory(f.source).setItem(7, ItemStack(Material.DIAMOND, 1))
            val group = full.inventory!!.constructionBuffers!!.getValue(site.id.toString())
            val returning = UUID.randomUUID()
            f.h.storage.reserveConstructionReturn(full, returning, group, part)
            owner.cancelledRegisteredSource(full, cell) shouldBe null
            (f.source in f.h.storage.constructionReturnCandidates(full, blob)) shouldBe false
            f.h.storage.prepareConstructionReturn(full, blob, part, listOf(f.source)) shouldBe null
            val original = f.items(f.source)
            val forged = CivContainerRelocation(site, listOf(CivRelocatedContainer(f.source, "CHEST", cell.before,
                original, original.toMutableList().also { it[3] = CivResidentItem("GOLD_INGOT", 2) })),
                buffer = CivConstructionStorageMove(owner, part, null, cell))
            shouldThrow<IllegalArgumentException> { forged.validated(forged.after(full)) }
            f.h.storage.unlock(returning)
            f.items(f.source)[7] shouldBe CivResidentItem("DIAMOND", 1)
            full.inventory!!.bufferStock(site.id) shouldBe mapOf("GOLD_INGOT" to 2L)
        }
    }

    test("a paused same-level plan board holds its stores through the exact refit and releases them on completion or cancellation") {
        UpgradeStorageFixture().use { f ->
            f.configure(BuildingType.HOUSE)
            val original = f.building.copy(type = BuildingType.PLAN_BOARD, schematicId = CivsPlanBoardBlueprint.LEGACY_SCHEMATIC_ID)
            val target = original.copy(schematicId = CivsPlanBoardBlueprint.refitId(original), phase = BuildingPhase.CONSTRUCTING)
            val city = f.h.city.copy(buildings = f.h.city.buildings.map { if (it.id == original.id) original else it })
            val pin = CivConstructionStorageOwner(city.id, city.worldId, original, target, "a".repeat(64))
            pin.validate()
            val paused = CivConstruction(UUID.randomUUID(), target.id, original.level, 4, 1,
                ConstructionPhase.PAUSED, Stock(), failure = "world-changed", plannedBuilding = target)
            pin.held(city.copy(construction = paused)) shouldBe true
            val deferred = city.copy(construction = null, constructionQueue = listOf(original.id), deferredConstruction = listOf(paused))
            pin.held(deferred) shouldBe true
            pin.held(deferred.copy(pendingDeletion = CivDeletion(original.id))) shouldBe false
            pin.held(deferred.copy(deferredConstruction = listOf(paused.copy(plannedBuilding = target.copy(rotation = (target.rotation + 90) % 360))))) shouldBe false
            pin.held(city.copy(construction = paused.copy(plannedBuilding = target.copy(rotation = (target.rotation + 90) % 360)))) shouldBe false
            pin.held(city.copy(constructionQueue = listOf(original.id))) shouldBe true
            pin.held(city) shouldBe false
            val completed = city.copy(buildings = city.buildings.map { if (it.id == target.id) target.copy(phase = BuildingPhase.ACTIVE) else it })
            pin.matches(completed) shouldBe true
            pin.held(completed.copy(constructionQueue = listOf(target.id))) shouldBe false
        }
    }
    test("a queued producer restart reuses its exact held owner after the ordinary production selection changes") {
        UpgradeStorageFixture().use { f ->
            f.configure(BuildingType.FARM)
            val city = f.h.city.copy(constructionQueue = listOf(f.building.id))
            val first = CivPlanBlock(f.source, f.state("south"), "minecraft:stone")
            val second = CivPlanBlock(f.destination, f.state("east"), "minecraft:air")
            val plan = CivPlacementPlan(UUID.randomUUID(), city.id, city.worldId, f.target, listOf(first, second), "b".repeat(64))
            val owner = CivConstructionStorageOwner.forPlan(city, f.building, plan)
            val rows = MutableList<CivResidentItem?>(STORAGE_SLOTS) { null }.also { it[1] = CivResidentItem("COAL", 2) }
            val held = CivConstructionStorageSnapshot(UUID.randomUUID(), owner, first, rows, plan.id).initial()
            val partial = city.copy(inventory = city.inventory!!.updatePart(owner, null, held))
            val restart = CivsRules.setProduction(partial, city.owner, f.building.id, "CARROT", 10_000)
            val current = requireNotNull(restart.building(f.building.id))
            val next = plan.copy(id = UUID.randomUUID(), building = plan.building.copy(production = current.production))
            CivConstructionStorageOwner.forPlan(restart, current, next) shouldBe owner
            shouldThrow<IllegalArgumentException> { CivConstructionStorageOwner.forPlan(restart, current, next.copy(capturedSha256 = "c".repeat(64))) }
            shouldThrow<IllegalArgumentException> { CivConstructionStorageOwner.forPlan(restart, current, next.copy(building = next.building.copy(control = f.source))) }
            f.h.city = restart
            f.h.storage.updateCity(restart)
            f.inventory(f.destination).setItem(4, ItemStack(Material.WHEAT, 3))
            val id = UUID.randomUUID()
            f.h.storage.reserve(restart, id)
            val captured = requireNotNull(f.h.storage.prepareConstructionStorage(restart, next.building, listOf(second)))
            val root = Files.createTempDirectory("construction-producer-retry")
            try {
                Files.createDirectories(root.resolve("cities"))
                Files.writeString(root.resolve("cities/${restart.id}.json"), com.google.gson.Gson().toJson(
                    mapOf("schemaVersion" to CivsStore.SCHEMA_VERSION, "city" to restart)))
                CivsStore(root, Executor { it.run() }).use { store ->
                    store.savePlan(next).join()
                    val saved = store.saveConstructionStorage(CivConstructionStorageSnapshot(id, owner, second, captured.holder.before, next.id)).join()
                    val move = CivContainerRelocation(current, listOf(captured.holder), captured.retirement,
                        CivConstructionStorageMove(owner, null, saved, second))
                    val tx = CivInventoryTransaction(id, city.id, restart.revision, move.after(restart), emptyList(), containerRelocation = move).validated()
                    store.saveInventoryTransaction(tx).join()
                    f.h.storage.seal(tx)
                    f.h.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
                    tx.after.inventory!!.bufferStock(f.building.id) shouldBe mapOf("COAL" to 2L, "WHEAT" to 3L)
                }
            } finally { f.h.storage.unlock(id); root.toFile().deleteRecursively() }
        }
    }

    test("an autonomous site proposal reads only a registered chest and its newly placed wall retires an empty registration under the accepted plan") {
        UpgradeStorageFixture().use { f ->
            f.configure(BuildingType.HOUSE)
            val p = f.source
            val before = f.h.city.copy(buildings = f.h.city.buildings.filterNot { it.id == f.building.id },
                inventory = f.h.city.inventory!!.copy(containers = listOf(p)))
            val target = f.building.copy(type = BuildingType.WALL, anchor = p, bounds = CivBox(p, p),
                entrance = p, control = null, phase = BuildingPhase.CONSTRUCTING)
            val cell = CivPlanBlock(p, f.state("south"), "minecraft:stone_bricks")
            val blocks = listOf(ru.arc.paper.api.ArcSchematicBlock(ru.arc.paper.api.ArcSchematicPoint(p.x, p.y, p.z), cell.after, false, false))
            f.inventory(p).setItem(0, ItemStack(Material.GOLD_INGOT, 3))
            CivsWorld(f.h.plugin, f.h.tasks, { 0L }) { listOf(f.h.city) }.use { world ->
                fun proposal() = world.captureAutonomousSite(before.id, before.owner, before.worldId, target, blocks).also { f.h.ticks() }
                f.h.city = before.copy(inventory = before.inventory!!.copy(containers = emptyList()))
                shouldThrow<CompletionException> { proposal().join() }
                f.h.city = before
                val plan = proposal().join().copy(capturedSha256 = "b".repeat(64))
                plan.blocks shouldBe listOf(cell)
                f.items(p)[0] shouldBe CivResidentItem("GOLD_INGOT", 3)
                val site = target.copy(phase = BuildingPhase.SITE)
                f.h.city = CivsRules.validate(before.copy(buildings = before.buildings + site))
                f.h.storage.replaceCities(listOf(f.h.city))
                f.inventory(p).clear()
                val id = UUID.randomUUID()
                f.h.storage.reserve(f.h.city, id)
                val prep = requireNotNull(f.h.storage.prepareConstructionStorage(f.h.city, target, plan.blocks))
                val retiring = requireNotNull(prep.retirement).copy(planId = plan.id)
                val move = CivContainerRelocation(site, listOf(prep.holder), retiring)
                val tx = CivInventoryTransaction(id, before.id, f.h.city.revision, move.after(f.h.city),
                    emptyList(), containerRelocation = move).validated()
                val root = Files.createTempDirectory("construction-registered-site")
                try {
                    CivsStore(root, Executor { it.run() }).use { store ->
                        store.save(f.h.city).join()
                        shouldThrow<CompletionException> { store.saveInventoryTransaction(tx).join() }
                        store.savePlan(plan).join()
                        store.saveInventoryTransaction(tx).join()
                        f.h.storage.seal(tx)
                        f.h.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
                        tx.after.inventory!!.containers shouldBe emptyList()
                        tx.after.inventory!!.constructionBuffers shouldBe null
                    }
                } finally { f.h.storage.unlock(id); root.toFile().deleteRecursively() }
            }
        }
    }

    test("only the assigned builder can pick up buffered native materials and its real bag pays once") {
        UpgradeStorageFixture().use { f ->
            f.configure(BuildingType.BUILDER_HUT)
            val targetSite = f.addReceiver(BuildingType.HOUSE).copy(phase = BuildingPhase.CONSTRUCTING)
            val needed = mapOf("BRICKS" to 4L)
            val builder = f.h.worker.copy(assignment = f.building.id, nextDeliveryAt = 1)
            val order = CivConstruction(UUID.randomUUID(), targetSite.id, 1, 1, 0, ConstructionPhase.READY_TO_PLACE,
                Stock(), plannedBuilding = targetSite, batches = CivConstructionBatches(1, 16), materials = needed,
                payment = CivConstructionPayment())
            val base = f.h.city.copy(autonomy = null, workers = listOf(builder), construction = order,
                buildings = f.h.city.buildings.map { if (it.id == targetSite.id) targetSite else it })
            val owner = CivConstructionStorageOwner(base.id, base.worldId, f.building, f.target, "7".repeat(64))
            val rows = MutableList<CivResidentItem?>(STORAGE_SLOTS) { null }.also { it[2] = CivResidentItem("BRICKS", 4) }
            val cell = CivPlanBlock(f.source, f.state("south"), "minecraft:stone")
            val blob = CivConstructionStorageSnapshot(UUID.randomUUID(), owner, cell, rows, UUID.randomUUID())
            val part = blob.initial()
            val before = CivsRules.validate(base.copy(inventory = base.inventory!!.updatePart(owner, null, part)))
            f.h.city = before
            f.h.storage.updateCity(before)
            shouldThrow<CivsRejection> { CivsRules.payBatch(before, before.owner, order.id, 0, needed) }.reason shouldBe "materials-missing"
            val id = UUID.randomUUID()
            f.h.storage.reserve(before, id)
            val pickup = f.h.storage.prepareBuilderPickup(before, builder.id, needed)
            val after = before.copy(workers = listOf(pickup.worker), inventory = pickup.plan.inventoryAfter, revision = before.revision + 1)
            val tx = CivInventoryTransaction(id, before.id, before.revision, after, pickup.plan.changes,
                bufferPickup = requireNotNull(pickup.plan.bufferPickup)).validated()
            tx.mixedBufferPickup() shouldBe false
            f.h.storage.seal(tx)
            f.h.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
            f.h.storage.unlock(id)
            CivsEquipment.plainCount(after.workers.single(), "BRICKS") shouldBe 4
            val paid = CivsRules.payBatch(after, after.owner, order.id, 0, needed)
            CivsEquipment.plainCount(paid.workers.single(), "BRICKS") shouldBe 0
            shouldThrow<CivsRejection> { CivsRules.payBatch(paid, paid.owner, order.id, 0, needed) }.reason shouldBe "stale-construction"
            paid.inventory!!.constructionBuffers shouldBe null
        }
    }
})
