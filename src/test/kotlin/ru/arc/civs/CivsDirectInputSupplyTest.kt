package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class CivsDirectInputSupplyTest : FunSpec({
    test("full warehouses allow only the accepted input quantity directly from unreserved native producer output") {
        val input = directInputFixture()
        val plans = allocateCivSupplies(input).plans
        plans.size shouldBe 1
        val plan = plans.values.single()
        plan.cargo.directInput shouldBe true
        plan.cargo.sourceBuildingId shouldBe UUID(0, 30)
        plan.destinationBuildingId shouldBe UUID(0, 20)
        plan.cargo.amount shouldBe 48L
        plan.cargo.inventoryBacked shouldBe false
        input.stores.getValue(UUID(0, 30)).stock shouldBe mapOf("COBBLESTONE" to 80L)
        val retained = input.copy(retained = plans)
        allocateCivSupplies(retained).plans shouldBe plans
        allocateCivSupplies(retained.copy(requests = listOf(CivSupplyRequest(UUID(0, 20), "COBBLESTONE", 48, 48))))
            .plans.values.any { it.cargo.directInput } shouldBe false
    }

    test("full warehouses deliver a current three cobblestone construction request to its roomy hut before crusher inputs") {
        val base = directInputFixture(construction = true)
        val quarry = base.stores.getValue(UUID(0, 30))
        val hut = base.stores.getValue(UUID(0, 20))
        val crusher = hut.copy(id = UUID(0, 40), type = BuildingType.CRUSHER, outputMaterial = "GRAVEL",
            desiredStock = mapOf("COBBLESTONE" to 2L))
        val input = base.copy(stores = base.stores + mapOf(quarry.id to quarry.copy(stock = mapOf("COBBLESTONE" to 11_937L)),
            hut.id to hut.copy(capacity = CivSupplyCapacity(54, emptyMap())), crusher.id to crusher),
            requests = listOf(CivSupplyRequest(hut.id, "COBBLESTONE", 3, 0, construction = true),
                CivSupplyRequest(crusher.id, "COBBLESTONE", 2, 0)), couriers = base.couriers.take(1))
        val plans = allocateCivSupplies(input).plans
        plans.values.single().let { plan ->
            plan.cargo.directInput shouldBe true
            plan.cargo.sourceBuildingId shouldBe quarry.id
            plan.destinationBuildingId shouldBe hut.id
            plan.cargo.amount shouldBe 3L
        }
        input.stores.getValue(quarry.id).stock shouldBe mapOf("COBBLESTONE" to 11_937L)
        allocateCivSupplies(input.copy(retained = plans)).plans shouldBe plans
        val supplied = input.copy(requests = input.requests.map { if (it.construction) it.copy(inTransit = 3) else it })
        allocateCivSupplies(supplied).plans.values.none { it.destinationBuildingId == hut.id } shouldBe true
    }

    test("construction direct inputs share the unpaid remainder across couriers and require a current hut destination") {
        val original = directInputFixture(construction = true)
        val source = original.stores.getValue(UUID(0, 30))
        val hut = original.stores.getValue(UUID(0, 20))
        val input = original.copy(stores = original.stores + mapOf(source.id to source.copy(stock = mapOf("COBBLESTONE" to 11_937L)),
            hut.id to hut.copy(capacity = CivSupplyCapacity(54, emptyMap()))),
            requests = listOf(CivSupplyRequest(hut.id, "COBBLESTONE", 86, 0, construction = true)))
        allocateCivSupplies(input).plans.values.map { it.cargo.amount }.sorted() shouldBe listOf(22L, 64L)
        allocateCivSupplies(input.copy(constructionId = null)).plans shouldBe emptyMap()
        allocateCivSupplies(input.copy(stores = input.stores + (hut.id to hut.copy(type = BuildingType.QUARRY))))
            .plans shouldBe emptyMap()
    }

    for ((type, material, output) in listOf(Triple(BuildingType.TOWN_HALL, "COBBLESTONE", "BOOK"),
        Triple(BuildingType.QUARRY, "DIRT", "COBBLESTONE"), Triple(BuildingType.QUARRY, "ANDESITE", "COBBLESTONE")))
    test("construction can collect native $material surplus from $type while ordinary direct recipes retain their source policy") {
        val original = directInputFixture(construction = true)
        val source = original.stores.getValue(UUID(0, 30)).copy(type = type, outputMaterial = output,
            stock = mapOf(material to 128L), desiredStock = emptyMap())
        val hut = original.stores.getValue(UUID(0, 20)).copy(capacity = CivSupplyCapacity(54, emptyMap()))
        val input = original.copy(stores = original.stores + mapOf(source.id to source, hut.id to hut),
            requests = listOf(CivSupplyRequest(hut.id, material, 3, 0, construction = true)),
            couriers = original.couriers.take(1).map { it.copy(bagRoom = mapOf(material to 64L)) },
            stackSizes = mapOf(material to 64))
        val plans = allocateCivSupplies(input).plans
        plans.values.single().let {
            it.cargo.directInput shouldBe true
            it.cargo.sourceBuildingId shouldBe source.id
            it.destinationBuildingId shouldBe hut.id
            it.cargo.material shouldBe material
            it.cargo.amount shouldBe 3L
        }
        allocateCivSupplies(input.copy(retained = plans)).plans shouldBe plans
        allocateCivSupplies(input.copy(stores = input.stores + (source.id to source.copy(desiredStock = source.stock))))
            .plans shouldBe emptyMap()
        allocateCivSupplies(input.copy(requests = emptyList(), retained = plans)).plans shouldBe emptyMap()
        val ordinary = input.copy(stores = input.stores + (hut.id to hut.copy(type = BuildingType.CRUSHER)),
            requests = input.requests.map { it.copy(construction = false) }, constructionId = null)
        allocateCivSupplies(ordinary).plans shouldBe emptyMap()
        input.stores.getValue(source.id).stock shouldBe mapOf(material to 128L)
    }

    for (condition in listOf("protected-input", "unknown-output", "destination-full", "virtual", "warehouse-room"))
    test("direct input admission preserves $condition policy") {
        val original = directInputFixture()
        val input = when (condition) {
            "protected-input" -> original.copy(stores = original.stores + (UUID(0, 30) to
                original.stores.getValue(UUID(0, 30)).copy(desiredStock = mapOf("COBBLESTONE" to 80L))))
            "unknown-output" -> original.copy(stores = original.stores + (UUID(0, 30) to
                original.stores.getValue(UUID(0, 30)).copy(outputMaterial = null)))
            "destination-full" -> original.copy(stores = original.stores + (UUID(0, 20) to
                original.stores.getValue(UUID(0, 20)).copy(capacity = CivSupplyCapacity(0, emptyMap()))))
            "virtual" -> original.copy(stores = original.stores.mapValues { (_, store) ->
                store.copy(capacity = CivSupplyCapacity(0, emptyMap(), virtualRoom = 64)) })
            else -> original.copy(stores = original.stores.mapValues { (_, store) ->
                if (store.type == BuildingType.WAREHOUSE) store.copy(capacity = CivSupplyCapacity(1, emptyMap())) else store })
        }
        allocateCivSupplies(input).plans.values.any { it.cargo.directInput } shouldBe false
    }

    test("already carried cargo remains owned when no current input request remains") {
        val original = directInputFixture()
        val accepted = allocateCivSupplies(original).plans
        val carried = accepted.mapValues { (_, plan) -> plan.copy(carrying = true,
            cargo = plan.cargo.copy(inventoryBacked = true)) }
        val input = original.copy(requests = emptyList(), stores = original.stores - UUID(0, 30), retained = carried)
        allocateCivSupplies(input).plans shouldBe carried
    }

    test("direct producer input uses the ordinary native pickup and dropoff journals exactly once") {
        directInputService { h, city, source, target, warehouse ->
            val transactions = mutableListOf<Pair<CivInventoryTransaction, CompletableFuture<CivInventoryTransaction>>>()
            every { h.store.saveInventoryTransaction(any()) } answers {
                CompletableFuture<CivInventoryTransaction>().also { transactions += firstArg<CivInventoryTransaction>() to it }
            }
            h.start()
            val courier = city.workers.first()
            val plan = h.supplyPlan(courier.id)
            plan.cargo.directInput shouldBe true
            plan.cargo.amount shouldBe 32L // This real quarry request keeps the source's own thirty-two inputs reserved.
            val pickup = h.service.pickupCargo(city.id, courier.id, plan.cargo)
            h.tickUntil("direct input pickup journal") { transactions.size == 1 }
            source.contents.filterNotNull().sumOf { it.amount } shouldBe 80
            h.service.settlements().single().workers.first().cargo shouldBe null
            transactions.single().let { it.second.complete(it.first) }
            val picked = h.await(pickup)
            source.contents.filterNotNull().sumOf { it.amount } shouldBe 48
            val carrying = picked.workers.first()
            carrying.cargo shouldBe plan.cargo.copy(inventoryBacked = true)
            CivsEquipment.plainCount(carrying, "COBBLESTONE") shouldBe 32
            target.isEmpty shouldBe true
            h.service.pickupCargo(city.id, courier.id, plan.cargo).join() shouldBe picked
            transactions.size shouldBe 1
            val legacy = Gson().toJsonTree(plan.cargo.copy(directInput = false)).asJsonObject.also { it.remove("directInput") }
            Gson().fromJson(legacy, CivCargo::class.java).directInput shouldBe false
            val retired = picked.copy(buildings = picked.buildings.filterNot { it.id == plan.cargo.sourceBuildingId },
                workers = picked.workers.map { if (it.assignment == plan.cargo.sourceBuildingId) it.copy(assignment = null) else it })
            CivsRules.validate(retired) shouldBe retired
            val restored = Gson().fromJson(Gson().toJson(picked), Settlement::class.java)
            CivsRules.validate(restored).workers.first().cargo shouldBe carrying.cargo
            CivsLogistics.validCargo(picked, requireNotNull(carrying.cargo).copy(redistribution = true)) shouldBe false
            CivsLogistics.validCargo(picked.copy(inventory = CivInventory(CivStorageMode.VIRTUAL)), carrying.cargo) shouldBe false
            val dropoff = h.service.dropoffCargo(city.id, courier.id, plan.cargo, plan.destinationBuildingId)
            h.tickUntil("direct input dropoff journal") { transactions.size == 2 }
            target.isEmpty shouldBe true
            transactions.last().let { it.second.complete(it.first) }
            val saved = h.await(dropoff)
            saved.workers.first().cargo shouldBe null
            CivsEquipment.plainCount(saved.workers.first(), "COBBLESTONE") shouldBe 0
            target.contents.filterNotNull().sumOf { it.amount } shouldBe 32
            source.contents.filterNotNull().sumOf { it.amount } + target.contents.filterNotNull().sumOf { it.amount } shouldBe 80
            warehouse.contents.filterNotNull().all { it.type == Material.STONE && it.amount == 64 } shouldBe true
            h.service.dropoffCargo(city.id, courier.id, plan.cargo, plan.destinationBuildingId).isCompletedExceptionally shouldBe true
            transactions.size shouldBe 2
        }
    }

    test("a selected road repair receives sixteen direct cobblestone through native journals without construction") {
        directInputService(upkeep = true) { h, city, source, hut, warehouse ->
            val transactions = mutableListOf<Pair<CivInventoryTransaction, CompletableFuture<CivInventoryTransaction>>>()
            every { h.store.saveInventoryTransaction(any()) } answers {
                CompletableFuture<CivInventoryTransaction>().also { transactions += firstArg<CivInventoryTransaction>() to it }
            }
            startDirectInputUpkeep(h, city)
            city.construction shouldBe null
            val courier = city.workers.first()
            val builder = city.workers[2]
            h.service.upkeepRequirements(city) shouldBe mapOf(builder.id to mapOf("COBBLESTONE" to 1L))
            val plan = upkeepDirectInputPlan(h, courier.id)
            plan.cargo.directInput shouldBe true
            plan.cargo.amount shouldBe 16L
            plan.destinationBuildingId shouldBe builder.assignment
            CivsLogistics.directInputAllowed(city, plan.cargo) shouldBe true
            val depotBefore = warehouse.contents.map { it?.clone() }
            val pickup = h.service.pickupCargo(city.id, courier.id, plan.cargo)
            h.tickUntil("upkeep cargo pickup journal") { transactions.size == 1 }
            source.storageContents.filterNotNull().sumOf { it.amount } shouldBe 80
            h.service.settlements().single().workers.first().cargo shouldBe null
            transactions.single().let { it.second.complete(it.first) }
            val picked = h.await(pickup)
            source.storageContents.filterNotNull().sumOf { it.amount } shouldBe 64
            CivsEquipment.plainCount(picked.workers.first(), "COBBLESTONE") shouldBe 16
            picked.workers.first().cargo shouldBe plan.cargo.copy(inventoryBacked = true)
            h.await(h.service.pickupCargo(city.id, courier.id, plan.cargo)) shouldBe picked
            transactions.size shouldBe 1
            val dropoff = h.service.dropoffCargo(city.id, courier.id, plan.cargo, plan.destinationBuildingId)
            h.tickUntil("upkeep cargo dropoff journal") { transactions.size == 2 }
            hut.isEmpty shouldBe true
            transactions.last().let { it.second.complete(it.first) }
            val saved = h.await(dropoff)
            saved.workers.first().cargo shouldBe null
            CivsEquipment.plainCount(saved.workers.first(), "COBBLESTONE") shouldBe 0
            hut.storageContents.filterNotNull().sumOf { it.amount } shouldBe 16
            source.storageContents.filterNotNull().sumOf { it.amount } + hut.storageContents.filterNotNull().sumOf { it.amount } shouldBe 80
            saved.stock shouldBe city.stock
            saved.construction shouldBe null
            saved.constructionQueue shouldBe city.constructionQueue
            warehouse.contents.toList() shouldBe depotBefore
            h.service.upkeepSupplyRequests(saved) { if (it == builder.assignment) mapOf("COBBLESTONE" to 16L) else emptyMap() } shouldBe emptyList()
            h.service.upkeepRequirements(saved) shouldBe mapOf(builder.id to mapOf("COBBLESTONE" to 1L))
            h.service.dropoffCargo(city.id, courier.id, plan.cargo, plan.destinationBuildingId).isCompletedExceptionally shouldBe true
            transactions.size shouldBe 2
        }
    }

    for (condition in listOf("no-current-upkeep", "fulfilled-upkeep", "inactive-producer", "foreign-producer", "stale-shipment"))
    test("upkeep direct pickup rechecks $condition without moving native items") {
        directInputService(upkeep = true) { h, city, source, hut, warehouse ->
            val maintenance = startDirectInputUpkeep(h, city)
            val courier = city.workers.first()
            val plan = upkeepDirectInputPlan(h, courier.id)
            plan.cargo.amount shouldBe 16L
            val dispatcher = CivsService::class.java.getDeclaredField("supply").also { it.isAccessible = true }
                .get(h.service) as CivsSupplyDispatcher
            mockkObject(dispatcher)
            try {
                every { dispatcher.plan(city.id, courier.id) } returns plan
                val current = h.service.settlements().single()
                when (condition) {
                    "no-current-upkeep" -> maintenance.invalidate(city.workers[2].id)
                    "fulfilled-upkeep" -> hut.setItem(0, ItemStack(Material.COBBLESTONE, 16))
                    "inactive-producer" -> replaceDirectInputCity(h, current.copy(buildings = current.buildings.map {
                        if (it.id == plan.cargo.sourceBuildingId) it.copy(phase = BuildingPhase.SITE) else it },
                        workers = current.workers.map { if (it.assignment == plan.cargo.sourceBuildingId) it.copy(assignment = null) else it }))
                    "foreign-producer" -> replaceDirectInputCity(h, current.copy(
                        buildings = current.buildings.filterNot { it.id == plan.cargo.sourceBuildingId },
                        workers = current.workers.map { if (it.assignment == plan.cargo.sourceBuildingId) it.copy(assignment = null) else it }))
                }
                val before = listOf(source, hut, warehouse).map { inventory -> inventory.contents.map { it?.clone() } }
                val requested = if (condition == "stale-shipment") plan.cargo.copy(id = UUID.randomUUID()) else plan.cargo
                val rejected = shouldThrow<CompletionException> { h.await(h.service.pickupCargo(city.id, courier.id, requested)) }
                CivsDiagnostics.rejection(rejected)?.reason shouldBe "stale-delivery"
                listOf(source, hut, warehouse).map { it.contents.toList() } shouldBe before
                h.service.settlements().single().workers.first().cargo shouldBe null
                h.service.settlements().single().stock shouldBe city.stock
            } finally { unmockkObject(dispatcher) }
        }
    }

    for (condition in listOf("protected-input", "fulfilled-request", "destination-full"))
    test("native pickup rechecks $condition after accepting a direct input plan") {
        directInputService { h, city, source, target, _ ->
            h.start()
            val courier = city.workers.first()
            val plan = h.supplyPlan(courier.id)
            val dispatcher = CivsService::class.java.getDeclaredField("supply").also { it.isAccessible = true }
                .get(h.service) as CivsSupplyDispatcher
            mockkObject(dispatcher)
            try {
                every { dispatcher.plan(city.id, courier.id) } returns plan
                when (condition) {
                    "protected-input" -> { source.clear(); source.setItem(0, ItemStack(Material.COBBLESTONE, 32)) }
                    "fulfilled-request" -> target.setItem(0, ItemStack(Material.COBBLESTONE, 32))
                    else -> repeat(target.size) { target.setItem(it, ItemStack(Material.STONE, 64)) }
                }
                val beforeSource = source.contents.map { it?.clone() }
                val beforeTarget = target.contents.map { it?.clone() }
                val rejected = shouldThrow<CompletionException> { h.await(h.service.pickupCargo(city.id, courier.id, plan.cargo)) }
                CivsDiagnostics.rejection(rejected)?.reason shouldBe "stale-delivery"
                source.contents.toList() shouldBe beforeSource
                target.contents.toList() shouldBe beforeTarget
                h.service.settlements().single().workers.first().cargo shouldBe null
            } finally { unmockkObject(dispatcher) }
        }
    }

    test("construction direct cargo uses the same durable receipts and still unloads after its order disappears") {
        directInputService(construction = true) { h, city, source, hut, warehouse ->
            val transactions = mutableListOf<Pair<CivInventoryTransaction, CompletableFuture<CivInventoryTransaction>>>()
            every { h.store.saveInventoryTransaction(any()) } answers {
                CompletableFuture<CivInventoryTransaction>().also { transactions += firstArg<CivInventoryTransaction>() to it }
            }
            h.start()
            val courier = city.workers.first()
            val plan = h.supplyPlan(courier.id)
            plan.cargo.directInput shouldBe true
            plan.cargo.amount shouldBe 3L
            val pickup = h.service.pickupCargo(city.id, courier.id, plan.cargo)
            h.tickUntil("construction cargo pickup journal") { transactions.size == 1 }
            source.contents.filterNotNull().sumOf { it.amount } shouldBe 80
            h.service.settlements().single().workers.first().cargo shouldBe null
            transactions.single().let { it.second.complete(it.first) }
            val picked = h.await(pickup)
            source.contents.filterNotNull().sumOf { it.amount } shouldBe 77
            CivsEquipment.plainCount(picked.workers.first(), "COBBLESTONE") shouldBe 3
            h.await(h.service.pickupCargo(city.id, courier.id, plan.cargo)) shouldBe picked
            transactions.size shouldBe 1

            val retired = picked.copy(construction = null,
                buildings = picked.buildings.filterNot { it.id == city.construction!!.buildingId })
            replaceDirectInputCity(h, retired)
            CivsLogistics.validCargo(retired, retired.workers.first().cargo) shouldBe true
            val dropoff = h.service.dropoffCargo(city.id, courier.id, plan.cargo, plan.destinationBuildingId)
            h.tickUntil("construction cargo dropoff journal") { transactions.size == 2 }
            hut.isEmpty shouldBe true
            transactions.last().let { it.second.complete(it.first) }
            val saved = h.await(dropoff)
            saved.workers.first().cargo shouldBe null
            CivsEquipment.plainCount(saved.workers.first(), "COBBLESTONE") shouldBe 0
            hut.contents.filterNotNull().sumOf { it.amount } shouldBe 3
            source.contents.filterNotNull().sumOf { it.amount } + hut.contents.filterNotNull().sumOf { it.amount } shouldBe 80
            warehouse.contents.filterNotNull().all { it.type == Material.STONE && it.amount == 64 } shouldBe true
            h.service.dropoffCargo(city.id, courier.id, plan.cargo, plan.destinationBuildingId).isCompletedExceptionally shouldBe true
            transactions.size shouldBe 2
        }
    }

    for ((type, material) in listOf(BuildingType.TOWN_HALL to "COBBLESTONE", BuildingType.QUARRY to "DIRT",
        BuildingType.QUARRY to "ANDESITE"))
    test("native construction transfers existing $type $material items through the ordinary pickup and unload journal") {
        directInputService(construction = true, material = material, sourceType = type) { h, city, source, hut, warehouse ->
            val transactions = mutableListOf<CivInventoryTransaction>()
            every { h.store.saveInventoryTransaction(any()) } answers {
                firstArg<CivInventoryTransaction>().let { transactions += it; CompletableFuture.completedFuture(it) }
            }
            h.start()
            val courier = city.workers.first()
            val plan = h.supplyPlan(courier.id)
            plan.cargo.material shouldBe material
            plan.cargo.amount shouldBe 3L
            val picked = h.await(h.service.pickupCargo(city.id, courier.id, plan.cargo))
            transactions.size shouldBe 1
            source.storageContents.filterNotNull().sumOf { it.amount } shouldBe 77
            CivsEquipment.plainCount(picked.workers.first(), material) shouldBe 3
            val saved = h.await(h.service.dropoffCargo(city.id, courier.id, plan.cargo, plan.destinationBuildingId))
            transactions.size shouldBe 2
            saved.workers.first().cargo shouldBe null
            hut.storageContents.filterNotNull().sumOf { it.amount } shouldBe 3
            source.storageContents.filterNotNull().sumOf { it.amount } + hut.storageContents.filterNotNull().sumOf { it.amount } shouldBe 80
            warehouse.storageContents.filterNotNull().all { it.type == Material.STONE && it.amount == 64 } shouldBe true
        }
    }

    for (condition in listOf("protected-incidental-input", "removed-incidental-order"))
    test("native construction pickup rechecks $condition without moving items") {
        directInputService(construction = true, material = "DIRT", sourceType = BuildingType.FORESTER) { h, city, source, hut, _ ->
            h.start()
            val courier = city.workers.first()
            val plan = h.supplyPlan(courier.id)
            val dispatcher = CivsService::class.java.getDeclaredField("supply").also { it.isAccessible = true }
                .get(h.service) as CivsSupplyDispatcher
            mockkObject(dispatcher)
            try {
                every { dispatcher.plan(city.id, courier.id) } returns plan
                if (condition == "protected-incidental-input") {
                    source.clear(); source.setItem(0, ItemStack(Material.DIRT, 4))
                } else {
                    val current = h.service.settlements().single()
                    replaceDirectInputCity(h, current.copy(construction = null,
                        buildings = current.buildings.filterNot { it.id == city.construction!!.buildingId }))
                }
                val beforeSource = source.contents.map { it?.clone() }
                val rejected = shouldThrow<CompletionException> { h.await(h.service.pickupCargo(city.id, courier.id, plan.cargo)) }
                CivsDiagnostics.rejection(rejected)?.reason shouldBe "stale-delivery"
                source.contents.toList() shouldBe beforeSource
                hut.isEmpty shouldBe true
                h.service.settlements().single().workers.first().cargo shouldBe null
            } finally { unmockkObject(dispatcher) }
        }
    }

    for (condition in listOf("removed-order", "missing-hut", "wrong-hut", "fulfilled-request", "arrival"))
    test("construction native pickup rejects $condition after accepting its direct plan") {
        directInputService(construction = true) { h, city, source, hut, _ ->
            h.start()
            val courier = city.workers.first()
            val plan = h.supplyPlan(courier.id)
            val dispatcher = CivsService::class.java.getDeclaredField("supply").also { it.isAccessible = true }
                .get(h.service) as CivsSupplyDispatcher
            mockkObject(dispatcher)
            try {
                every { dispatcher.plan(city.id, courier.id) } returns plan
                val current = h.service.settlements().single()
                when (condition) {
                    "removed-order" -> replaceDirectInputCity(h, current.copy(construction = null,
                        buildings = current.buildings.filterNot { it.id == city.construction!!.buildingId }))
                    "missing-hut" -> replaceDirectInputCity(h, current.copy(
                        buildings = current.buildings.filterNot { it.id == plan.destinationBuildingId },
                        workers = current.workers.map { if (it.assignment == plan.destinationBuildingId) it.copy(assignment = null) else it }))
                    "wrong-hut" -> replaceDirectInputCity(h, current.copy(
                        buildings = current.buildings.map { if (it.id == plan.destinationBuildingId) it.copy(phase = BuildingPhase.SITE) else it },
                        workers = current.workers.map { if (it.assignment == plan.destinationBuildingId) it.copy(assignment = null) else it }))
                    "fulfilled-request" -> hut.setItem(0, ItemStack(Material.COBBLESTONE, 3))
                    else -> every { h.citizens.requireStorageArrival(city.id, courier.id, plan.cargo.sourceBuildingId) } throws
                        CivsRejection("worker-too-far")
                }
                val beforeSource = source.contents.map { it?.clone() }
                val beforeHut = hut.contents.map { it?.clone() }
                val rejected = shouldThrow<CompletionException> { h.await(h.service.pickupCargo(city.id, courier.id, plan.cargo)) }
                CivsDiagnostics.rejection(rejected)?.reason shouldBe if (condition == "arrival") "worker-too-far" else "stale-delivery"
                source.contents.toList() shouldBe beforeSource
                hut.contents.toList() shouldBe beforeHut
                h.service.settlements().single().workers.first().cargo shouldBe null
            } finally { unmockkObject(dispatcher) }
        }
    }
})

private fun directInputFixture(construction: Boolean = false): CivSupplySnapshot {
    fun store(id: Long, type: BuildingType, stock: Map<String, Long> = emptyMap(), desired: Map<String, Long> = emptyMap(),
        output: String? = null, empty: Int = 0) = CivSupplyStore(UUID(0, id), CivPosition(id.toInt(), 64, -2),
        CivPosition(id.toInt(), 64, 0), stock, desired, output, CivSupplyCapacity(empty, emptyMap()), type)
    val stores = listOf(store(10, BuildingType.WAREHOUSE), store(11, BuildingType.WAREHOUSE),
        store(20, if (construction) BuildingType.BUILDER_HUT else BuildingType.QUARRY, empty = 1),
        store(30, BuildingType.QUARRY, mapOf("COBBLESTONE" to 80L), mapOf("COBBLESTONE" to 32L), "COBBLESTONE"))
    return CivSupplySnapshot(UUID(0, 1), Unit, stores.associateBy { it.id },
        listOf(CivSupplyRequest(UUID(0, 20), "COBBLESTONE", 48, 0, construction)),
        listOf(10L, 11L).map { CivSupplyCourier(UUID(0, 100 + it), UUID(0, it), 0, CivPosition(10, 64, 0),
            mapOf("COBBLESTONE" to 64L)) }, emptyList(), emptyMap(), mapOf("COBBLESTONE" to 64),
        constructionId = UUID(0, 50).takeIf { construction })
}

private fun replaceDirectInputCity(h: ServiceScenario, city: Settlement) {
    @Suppress("UNCHECKED_CAST")
    val cities = CivsService::class.java.getDeclaredField("cities").also { it.isAccessible = true }
        .get(h.service) as MutableMap<UUID, Settlement>
    cities[city.id] = CivsRules.validate(city)
}

private fun directInputService(construction: Boolean = false, material: String = "COBBLESTONE", sourceType: BuildingType = BuildingType.QUARRY,
    upkeep: Boolean = false,
    action: (ServiceScenario, Settlement, org.bukkit.inventory.Inventory,
    org.bukkit.inventory.Inventory, org.bukkit.inventory.Inventory) -> Unit) =
    serviceScenario(storageFactory = { plugin -> CivsStorage(plugin) { emptyArray() }.also {
        it.persistCollectedNative = { _, _ -> CompletableFuture.completedFuture(Unit) }
    } }) { h ->
        fun building(x: Int, type: BuildingType) = h.city.buildings.first().copy(id = UUID.randomUUID(), type = type, level = 1,
            anchor = CivPosition(x, 64, 0), entrance = CivPosition(x, 64, -2), control = CivPosition(x, 63, 0),
            bounds = CivBox(CivPosition(x - 2, 63, -2), CivPosition(x + 2, 68, 2)))
        val warehouse = building(10, BuildingType.WAREHOUSE)
        val secondWarehouse = building(50, BuildingType.WAREHOUSE)
        val source = building(20, sourceType)
        val target = building(30, if (construction || upkeep) BuildingType.BUILDER_HUT else BuildingType.QUARRY)
        val site = building(70, BuildingType.QUARRY).copy(phase = BuildingPhase.CONSTRUCTING)
        val floor = CivPosition(60, 63, 0)
        val road = CivBuilding(UUID.randomUUID(), BuildingType.ROAD, 1, floor, 0, "civs/infrastructure/test-road",
            CivBox(floor, floor.copy(y = 65)), floor.copy(y = 64), BuildingPhase.ACTIVE,
            transit = listOf(floor.copy(y = 64)))
        val order = if (construction) CivConstruction(UUID.randomUUID(), site.id, 1, 2, 0, ConstructionPhase.PAUSED, Stock(),
            failure = "fixture-paused", plannedBuilding = site, batches = CivConstructionBatches(2, 16),
            materials = mapOf(material to 3L), payment = CivConstructionPayment(awaiting = mapOf(material to 3L))) else null
        val city = CivsRules.validate(h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
            buildings = listOfNotNull(h.city.buildings.first().takeUnless { sourceType == BuildingType.TOWN_HALL },
                warehouse, source, target, secondWarehouse) + listOfNotNull(site.takeIf { construction }, road.takeIf { upkeep }),
            construction = order, autonomy = CivAutonomy().takeIf { upkeep },
            workers = h.city.workers.mapIndexed { index, worker -> worker.copy(assignment =
                listOf(warehouse.id, source.id, target.id, null)[index], nextDeliveryAt = 1) }))
        every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
        every { h.store.save(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
        every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
        every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
        fun inventory(building: CivBuilding): org.bukkit.inventory.Inventory {
            val p = requireNotNull(building.control)
            h.world.loadChunk(p.x shr 4, p.z shr 4)
            h.world.getBlockAt(p.x, p.y, p.z).type = Material.BARREL
            return (h.world.getBlockAt(p.x, p.y, p.z).state as Barrel).inventory
        }
        val depot = inventory(warehouse)
        val second = inventory(secondWarehouse)
        for (inv in listOf(depot, second)) repeat(inv.size) { inv.setItem(it, ItemStack(Material.STONE, 64)) }
        val output = inventory(source)
        output.setItem(0, ItemStack(Material.valueOf(material), 64)); output.setItem(1, ItemStack(Material.valueOf(material), 16))
        action(h, city, output, inventory(target), depot)
    }

private fun startDirectInputUpkeep(h: ServiceScenario, city: Settlement): CivsRoadMaintenance {
    val builder = city.workers[2]
    val road = city.buildings.single { it.type == BuildingType.ROAD }
    val floor = road.anchor
    val changes = listOf(CivPlanBlock(floor, "minecraft:dirt", "minecraft:cobblestone"))
    val work = CivWorldWork(builder.id, builder.completedCycles, requireNotNull(builder.assignment), floor.copy(x = floor.x - 1, y = floor.y + 1),
        changes, inputs = mapOf("COBBLESTONE" to 1L), status = "repairing-road", focus = floor,
        upkeep = CivRoadUpkeep(city.id, city.worldId, city.owner, road.id, road, floor, "minecraft:cobblestone", null, emptyList()))
    every { h.worldAdapter.captureRoadUpkeep(any(), builder, any(), any()) } returns work
    every { h.worldAdapter.roadUpkeepPending(any(), work) } returns true
    h.paper.addPlayer("UpkeepSupplyWitness").teleport(Location(h.world, 1.5, 65.0, .5))
    h.start()
    val maintenance = CivsService::class.java.getDeclaredField("roadMaintenance").also { it.isAccessible = true }
        .get(h.service) as CivsRoadMaintenance
    maintenance.publish(city, CivsRoadMaintenance.prepare(city, listOf(CivPlacementPlan(road.id, city.id, city.worldId, road, changes))))
    maintenance.plan(city, builder) shouldBe work
    h.time += CivsSupplyDispatcher.REFRESH_MILLIS
    (CivsService::class.java.getDeclaredField("supply").also { it.isAccessible = true }.get(h.service) as CivsSupplyDispatcher).request(city.id)
    return maintenance
}

private fun upkeepDirectInputPlan(h: ServiceScenario, workerId: UUID): CivCourierPlan {
    var plan: CivCourierPlan? = null
    h.tickUntil("accepted upkeep courier plan") {
        val current = h.service.settlements().single()
        plan = h.service.courierPlan(current, current.workers.first { it.id == workerId })
        plan != null
    }
    return requireNotNull(plan)
}
