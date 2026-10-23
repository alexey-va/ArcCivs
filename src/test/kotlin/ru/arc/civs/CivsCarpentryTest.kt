package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class CivsCarpentryTest : FunSpec({
    test("automatic batches follow real log plank stick ladder receipts and stop at satisfied demand") {
        var input = carpenterSnapshot(stock = mapOf("OAK_LOG" to 2L))
        val products = mutableListOf<String>()
        repeat(4) {
            val job = craft(input).values.single()
            products += job.material
            val stock = input.stores.getValue(CARPENTER).stock.toMutableMap()
            job.inputs.forEach { (material, amount) ->
                ((stock[material] ?: 0L) >= amount) shouldBe true
                stock[material] = stock.getValue(material) - amount
            }
            stock.merge(job.material, job.amount, Long::plus)
            input = input.copy(stores = input.stores + (CARPENTER to input.stores.getValue(CARPENTER).copy(stock = stock)),
                craftWorkers = input.craftWorkers.map { it.copy(cycle = it.cycle + 1) }, craftEpoch = UUID.randomUUID())
        }
        products shouldBe listOf("OAK_PLANKS", "STICK", "STICK", "LADDER")
        craft(input) shouldBe emptyMap()
    }

    test("idle buffer uses paid native batches and counts warehouse and workshop stock once") {
        val initial = carpenterSnapshot("OAK_PLANKS", 4, mapOf("OAK_LOG" to 2L)).copy(requests = emptyList(),
            stockTargets = mapOf(WAREHOUSE to mapOf("OAK_PLANKS" to 8L)))
        val first = craft(initial).values.single()
        first.targetBuildingId shouldBe WAREHOUSE
        first.inputs shouldBe mapOf("OAK_LOG" to 1L)
        first.amount shouldBe 4L
        // A finite stock goal is replenished one funded batch at a time.
        craft(initial.copy(stores = initial.stores + (CARPENTER to initial.stores.getValue(CARPENTER)
            .copy(stock = mapOf("OAK_LOG" to 1L))))).values.single().inputs shouldBe mapOf("OAK_LOG" to 1L)
        val supplied = initial.copy(stores = initial.stores +
            (WAREHOUSE to initial.stores.getValue(WAREHOUSE).copy(stock = mapOf("OAK_PLANKS" to 4L))) +
            (CARPENTER to initial.stores.getValue(CARPENTER).copy(stock = mapOf("OAK_PLANKS" to 4L))))
        craft(supplied) shouldBe emptyMap()
        val short = supplied.copy(stores = supplied.stores +
            (CARPENTER to supplied.stores.getValue(CARPENTER).copy(stock = mapOf("OAK_LOG" to 1L))))
        craft(short).values.single().amount shouldBe 4L
    }

    test("stock work stops for missing inputs or full destination and respects recipe level") {
        val base = carpenterSnapshot("CHEST", 2, mapOf("OAK_PLANKS" to 16L)).copy(requests = emptyList(),
            stockTargets = mapOf(WAREHOUSE to mapOf("CHEST" to 2L)))
        craft(base) shouldBe emptyMap()
        val unlocked = base.copy(craftWorkers = base.craftWorkers.map { it.copy(level = 2) })
        craft(unlocked).values.single().material shouldBe "CHEST"
        val oneBatch = unlocked.copy(stores = unlocked.stores + (CARPENTER to unlocked.stores.getValue(CARPENTER)
            .copy(stock = mapOf("OAK_PLANKS" to 8L))))
        craft(oneBatch).values.single().let { job ->
            job.material shouldBe "CHEST"
            job.amount shouldBe 1L
            job.inputs shouldBe mapOf("OAK_PLANKS" to 8L)
        }
        val ladders = oneBatch.copy(stockTargets = mapOf(WAREHOUSE to mapOf("LADDER" to 12L)))
        craft(ladders).values.single().let { job ->
            job.material shouldBe "STICK"
            job.inputs shouldBe mapOf("OAK_PLANKS" to 2L)
        }
        craft(unlocked.copy(stores = unlocked.stores + (WAREHOUSE to unlocked.stores.getValue(WAREHOUSE)
            .copy(capacity = CivSupplyCapacity(0, emptyMap()))))) shouldBe emptyMap()
        craft(unlocked.copy(stores = unlocked.stores.mapValues { it.value.copy(stock = emptyMap()) })) shouldBe emptyMap()
    }

    test("real demand preempts a retained optional stock batch without minting its promised output") {
        val base = carpenterSnapshot("OAK_PLANKS", 4, mapOf("OAK_LOG" to 4L, "STICK" to 14L)).copy(
            requests = emptyList(), stockTargets = mapOf(WAREHOUSE to mapOf("OAK_PLANKS" to 8L)))
        val optional = craft(base)
        optional.values.single().material shouldBe "OAK_PLANKS"
        val urgent = base.copy(craftJobs = optional, requests = listOf(CivSupplyRequest(CONSUMER, "LADDER", 3, 0, true)),
            constructionId = UUID(0, 77))
        val replacement = craft(urgent).values.single()
        replacement.material shouldBe "LADDER"
        replacement.targetBuildingId shouldBe CONSUMER
        replacement.constructionId shouldBe UUID(0, 77)
        base.stores.getValue(CARPENTER).stock.getValue("OAK_LOG") shouldBe 4L
    }

    test("raw construction inputs are reserved before optional conversion to buffer products") {
        val one = carpenterSnapshot("OAK_LOG", 2, mapOf("OAK_LOG" to 2L)).copy(
            requests = listOf(CivSupplyRequest(CONSUMER, "OAK_LOG", 2, 0, true)),
            stockTargets = mapOf(WAREHOUSE to mapOf("OAK_PLANKS" to 4L)))
        craft(one) shouldBe emptyMap()
        val spare = one.copy(stores = one.stores + (CARPENTER to one.stores.getValue(CARPENTER)
            .copy(stock = mapOf("OAK_LOG" to 3L))))
        craft(spare).values.single().inputs shouldBe mapOf("OAK_LOG" to 1L)
    }

    test("buffer promises and durable warehouse cargo suppress duplicate batches across residents") {
        val one = carpenterSnapshot("OAK_PLANKS", 4, mapOf("OAK_LOG" to 3L)).copy(requests = emptyList(),
            stockTargets = mapOf(WAREHOUSE to mapOf("OAK_PLANKS" to 4L)))
        val two = one.copy(craftWorkers = one.craftWorkers + one.craftWorkers.single().copy(workerId = UUID(0, 11)))
        val accepted = craft(two)
        accepted.size shouldBe 1
        craft(two.copy(craftJobs = accepted)) shouldBe accepted
        val cargo = CivCargo(UUID(0, 21), WAREHOUSE, CARPENTER, WAREHOUSE, CivPosition(0, 64, 0), "OAK_PLANKS", 4, true)
        craft(two.copy(retained = mapOf(UUID(0, 20) to CivCourierPlan(cargo, WAREHOUSE,
            CivPosition(10, 64, 0), true)))) shouldBe emptyMap()
    }

    test("buffer output remains physical workshop excess and is carried to the warehouse") {
        val one = carpenterSnapshot("OAK_PLANKS", 4).copy(requests = emptyList(),
            stockTargets = mapOf(WAREHOUSE to mapOf("OAK_PLANKS" to 4L)),
            couriers = listOf(CivSupplyCourier(UUID(0, 20), WAREHOUSE, 0, CivPosition(10, 64, 0), mapOf("OAK_PLANKS" to 64L))))
        val produced = one.copy(stores = one.stores + (CARPENTER to one.stores.getValue(CARPENTER)
            .copy(stock = mapOf("OAK_PLANKS" to 4L))))
        val next = allocateCivSupplies(produced)
        next.crafts shouldBe emptyMap()
        next.plans.values.single().cargo.let { cargo ->
            cargo.sourceBuildingId shouldBe CARPENTER
            cargo.targetBuildingId shouldBe WAREHOUSE
            cargo.amount shouldBe 4L
        }
    }

    test("mixed native wood groups are pinned without substituting species specific recipes") {
        val chest = craft(carpenterSnapshot("CHEST", 1, mapOf("OAK_PLANKS" to 4L, "SPRUCE_PLANKS" to 4L), level = 2)).values.single()
        chest.material shouldBe "CHEST"
        chest.inputs shouldBe mapOf("OAK_PLANKS" to 4L, "SPRUCE_PLANKS" to 4L)
        CivsCarpentry.recipe(chest)?.inputs shouldBe chest.inputs
        val barrel = craft(carpenterSnapshot("BARREL", 1, mapOf("OAK_PLANKS" to 6L, "SPRUCE_SLAB" to 2L), level = 2)).values.single()
        barrel.material shouldBe "BARREL"
        barrel.inputs shouldBe mapOf("OAK_PLANKS" to 6L, "SPRUCE_SLAB" to 2L)
        CivsCarpentry.recipe(barrel)?.inputs shouldBe barrel.inputs
        val oakDoor = CivsCarpentry.recipes.first { it.material == "OAK_DOOR" }
        val forged = chest.copy(recipeId = oakDoor.id, material = oakDoor.material, amount = oakDoor.amount,
            inputs = mapOf("SPRUCE_PLANKS" to 6L))
        CivsCarpentry.recipe(forged) shouldBe null
        CivsCarpentry.recipe(chest.copy(inputs = mapOf("OAK_PLANKS" to 4L, "SPRUCE_PLANKS" to 3L))) shouldBe null
    }

    test("native batch output and ingredient promises prevent duplicate work from two residents") {
        val one = carpenterSnapshot("OAK_PLANKS", 1, mapOf("OAK_LOG" to 2L))
        val two = one.copy(craftWorkers = one.craftWorkers + one.craftWorkers.single().copy(workerId = UUID(0, 11)))
        val jobs = craft(two)
        jobs.size shouldBe 1
        jobs.values.single().amount shouldBe 4L
        craft(two.copy(craftJobs = jobs)) shouldBe jobs
        craft(two.copy(requests = emptyList(), craftJobs = jobs)) shouldBe emptyMap()
        val enough = two.copy(stores = two.stores + (WAREHOUSE to two.stores.getValue(WAREHOUSE).copy(stock = mapOf("OAK_PLANKS" to 4L))))
        craft(enough) shouldBe emptyMap()
        val partial = two.copy(requests = listOf(CivSupplyRequest(CONSUMER, "OAK_PLANKS", 8, 0)))
        craft(partial).values.sumOf { it.amount } shouldBe 8L
    }

    test("a pinned batch follows picked ingredients and a cancelled intent gets a fresh same cycle identity") {
        val initial = carpenterSnapshot("CHEST", 1, mapOf("OAK_PLANKS" to 4L, "SPRUCE_PLANKS" to 4L), level = 2)
        val accepted = craft(initial)
        val job = accepted.values.single()
        val picked = initial.copy(stores = initial.stores + (CARPENTER to initial.stores.getValue(CARPENTER).copy(stock = emptyMap())),
            craftWorkers = initial.craftWorkers.map { it.copy(carried = job.inputs) }, craftJobs = accepted)
        craft(picked) shouldBe accepted
        craft(picked.copy(requests = emptyList())) shouldBe emptyMap()
        val replacement = craft(picked.copy(craftJobs = emptyMap(), craftEpoch = UUID.randomUUID())).values.single()
        (replacement.id != job.id) shouldBe true
    }

    test("one real empty slot cannot promise two different outputs and a full store admits none") {
        val input = carpenterSnapshot("LADDER", 3).let { it.copy(
            requests = it.requests + CivSupplyRequest(CONSUMER, "OAK_PLANKS", 4, 0),
            craftWorkers = listOf(it.craftWorkers.single().copy(carried = mapOf("STICK" to 7L)),
                it.craftWorkers.single().copy(workerId = UUID(0, 11), carried = mapOf("OAK_LOG" to 1L))),
            stores = it.stores + (CARPENTER to it.stores.getValue(CARPENTER).copy(capacity = CivSupplyCapacity(1, emptyMap())))) }
        craft(input).size shouldBe 1
        craft(input.copy(stores = input.stores + (CARPENTER to input.stores.getValue(CARPENTER)
            .copy(capacity = CivSupplyCapacity(0, emptyMap()))))) shouldBe emptyMap()
    }

    test("the last native slot first receives a log then becomes the output slot after pickup") {
        var input = carpenterSnapshot("OAK_PLANKS", 4).let { it.copy(
            stores = it.stores + (CARPENTER to it.stores.getValue(CARPENTER).copy(capacity = CivSupplyCapacity(1, emptyMap()))) +
                (WAREHOUSE to it.stores.getValue(WAREHOUSE).copy(stock = mapOf("OAK_LOG" to 1L))),
            couriers = listOf(CivSupplyCourier(UUID(0, 20), WAREHOUSE, 0, CivPosition(10, 64, 0), mapOf("OAK_LOG" to 64L)))) }
        val first = allocateCivSupplies(input)
        first.crafts.size shouldBe 1
        first.plans.values.single().cargo.material shouldBe "OAK_LOG"
        first.plans.values.single().cargo.amount shouldBe 1L
        input = input.copy(craftJobs = first.crafts, couriers = emptyList(), stores = input.stores +
            (WAREHOUSE to input.stores.getValue(WAREHOUSE).copy(stock = emptyMap())) +
            (CARPENTER to input.stores.getValue(CARPENTER).copy(stock = mapOf("OAK_LOG" to 1L),
                capacity = CivSupplyCapacity(0, mapOf("OAK_LOG" to 63L)))))
        craft(input) shouldBe first.crafts
        val picked = input.copy(craftWorkers = input.craftWorkers.map { it.copy(carried = mapOf("OAK_LOG" to 1L)) },
            stores = input.stores + (CARPENTER to input.stores.getValue(CARPENTER).copy(stock = emptyMap(),
                capacity = CivSupplyCapacity(1, emptyMap()))))
        craft(picked) shouldBe first.crafts
    }

    test("a pinned chest keeps its ingredients reserved while the courier carries them to the workshop") {
        val base = carpenterSnapshot("CHEST", 1, level = 2)
        val initial = base.copy(stores = base.stores + (WAREHOUSE to base.stores.getValue(WAREHOUSE)
            .copy(stock = mapOf("OAK_PLANKS" to 8L))), couriers = listOf(CivSupplyCourier(UUID(0, 20), WAREHOUSE,
                0, CivPosition(10, 64, 0), mapOf("OAK_PLANKS" to 64L))))
        val accepted = allocateCivSupplies(initial)
        val job = accepted.crafts.values.single()
        job.material shouldBe "CHEST"
        val courier = accepted.plans.entries.single()
        val carrying = courier.value.copy(cargo = courier.value.cargo.copy(inventoryBacked = true), carrying = true)
        carrying.cargo.material shouldBe "OAK_PLANKS"
        carrying.cargo.amount shouldBe 8L
        val transit = initial.copy(craftJobs = accepted.crafts, couriers = emptyList(),
            stores = initial.stores + (WAREHOUSE to initial.stores.getValue(WAREHOUSE).copy(stock = emptyMap())),
            retained = mapOf(courier.key to carrying))
        repeat(3) { craft(transit) shouldBe accepted.crafts }
        // The promised input is not a finished chest, and cannot justify a second batch.
        craft(transit.copy(craftWorkers = transit.craftWorkers + transit.craftWorkers.single()
            .copy(workerId = UUID(0, 11)))) shouldBe accepted.crafts
    }

    test("unlock tiers preserve native quantities and unfinished external ingredients stay explicit") {
        craft(carpenterSnapshot("CHEST", 1, mapOf("OAK_PLANKS" to 8L), level = 1)) shouldBe emptyMap()
        craft(carpenterSnapshot("BOOKSHELF", 1, mapOf("OAK_PLANKS" to 6L, "BOOK" to 3L), level = 2)) shouldBe emptyMap()
        val advanced = CivsCarpentry.allocate(carpenterSnapshot("SMITHING_TABLE", 1, mapOf("OAK_PLANKS" to 4L), level = 3), AtomicBoolean())
        advanced.jobs.values.single().inputs shouldBe mapOf("OAK_PLANKS" to 4L, "IRON_INGOT" to 2L)
        advanced.requests.single().material shouldBe "IRON_INGOT"
        advanced.requests.single().needed shouldBe 2L
        CivsCarpentry.views.first { it.material == "OAK_STAIRS" }.amount shouldBe 4L
        CivsCarpentry.views.first { it.material == "OAK_SLAB" }.amount shouldBe 6L
        CivsCarpentry.views.first { it.material == "OAK_DOOR" }.amount shouldBe 3L
        CivsCarpentry.views.first { it.material == "TRIPWIRE_HOOK" }.amount shouldBe 2L
    }

    test("carried cargo satisfies demand once and cancellation never invents an output") {
        val input = carpenterSnapshot("OAK_PLANKS", 4, mapOf("OAK_LOG" to 1L))
        val cargo = CivCargo(UUID(0, 21), WAREHOUSE, WAREHOUSE, CONSUMER, CivPosition(10, 64, 0), "OAK_PLANKS", 4, true)
        val transit = input.copy(requests = listOf(CivSupplyRequest(CONSUMER, "OAK_PLANKS", 4, 4)),
            retained = mapOf(UUID(0, 20) to CivCourierPlan(cargo, CONSUMER, CivPosition(20, 64, 0), true)))
        craft(transit) shouldBe emptyMap()
        shouldThrow<java.util.concurrent.CancellationException> { CivsCarpentry.allocate(input, AtomicBoolean(true)) }
        input.stores.getValue(CARPENTER).stock shouldBe mapOf("OAK_LOG" to 1L)
    }

    test("stripping pays the carried log and real axe wear in one replay safe native receipt") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val world = paper.addSimpleWorld("carpentry-receipt")
                world.loadChunk(0, 0)
                val plugin = paper.createSimplePlugin("CarpentryReceipt")
                val point = CivPosition(4, 64, 0)
                world.getBlockAt(point.x, point.y, point.z).type = Material.BARREL
                val barrel = (world.getBlockAt(point.x, point.y, point.z).state as Barrel).inventory
                val hall = CivBuilding(UUID(0, 90), BuildingType.TOWN_HALL, 3, CivPosition(0, 63, 0), 0, "test",
                    CivBox(CivPosition(-2, 63, -2), CivPosition(2, 70, 2)), CivPosition(0, 64, -3), BuildingPhase.ACTIVE)
                val building = hall.copy(id = CARPENTER, type = BuildingType.SAWMILL, anchor = point,
                    bounds = CivBox(CivPosition(4, 63, -2), CivPosition(8, 70, 2)), entrance = CivPosition(5, 64, -3), control = point)
                val items = MutableList<CivResidentItem?>(CivsEquipment.SLOTS) { null }.also {
                    it[4] = CivResidentItem("IRON_AXE", 1); it[6] = CivResidentItem("OAK_LOG", 1)
                }
                val worker = CivWorker(CRAFT_WORKER, "Плотник", CARPENTER, nextDeliveryAt = 1, items = items)
                val city = Settlement(UUID(0, 100), UUID(0, 101), "Carpentry", world.uid, CivPosition(0, 64, 0),
                    48, "medievaloak", Stock(), listOf(hall, building), listOf(worker), inventory = CivInventory(CivStorageMode.PHYSICAL))
                val job = craft(carpenterSnapshot("STRIPPED_OAK_LOG", 1).copy(craftWorkers = listOf(
                    CivCraftWorker(worker.id, CARPENTER, 0, 3, mapOf("OAK_LOG" to 1L), true)))).values.single()
                val worn = CivsEquipment.strippedWithAxe(worker)
                (worn[4]!!.stack().itemMeta as Damageable).damage shouldBe 1
                val completed = CivsRules.deliver(city, city.owner, worker.id, CARPENTER, 0, 1, 60_000,
                    outputHandled = true, craftJob = job, equipmentAfter = worn)
                CivsEquipment.plainCount(completed.workers.single(), "OAK_LOG") shouldBe 0
                completed.workers.single().completedCycles shouldBe 1
                CivsEquipment.plainCount(worker, "OAK_LOG") shouldBe 1
                shouldThrow<CivsRejection> { CivsRules.deliver(completed, city.owner, worker.id, CARPENTER, 0, 1, 60_001,
                    outputHandled = true, craftJob = job, equipmentAfter = worn) }.reason shouldBe "stale-delivery"
                CivsStorage(plugin) { emptyArray() }.use { storage ->
                    val id = UUID.randomUUID()
                    storage.reserve(city, id)
                    val plan = storage.prepareProduction(city, building, worker, job)
                    val receipt = CivInventoryTransaction(id, city.id, city.revision, completed.copy(inventory = plan.inventoryAfter), plan.changes).validated()
                    storage.seal(receipt)
                    barrel.isEmpty shouldBe true
                    storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
                    storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
                    barrel.getItem(0) shouldBe ItemStack(Material.STRIPPED_OAK_LOG, 1)
                    storage.unlock(id)
                }
            }
        }
    }
})

private val CARPENTER = UUID(0, 1)
private val WAREHOUSE = UUID(0, 2)
private val CONSUMER = UUID(0, 3)
private val CRAFT_WORKER = UUID(0, 10)
private fun craft(snapshot: CivSupplySnapshot) = CivsCarpentry.allocate(snapshot, AtomicBoolean()).jobs
private fun carpenterSnapshot(material: String = "LADDER", amount: Long = 3,
    stock: Map<String, Long> = emptyMap(), level: Int = 1): CivSupplySnapshot {
    fun store(id: UUID, type: BuildingType, x: Int, materials: Map<String, Long>) = CivSupplyStore(id,
        CivPosition(x, 64, 0), CivPosition(x, 64, 1), materials, emptyMap(),
        "OAK_PLANKS".takeIf { type == BuildingType.SAWMILL }, CivSupplyCapacity(27, emptyMap()), type)
    return CivSupplySnapshot(UUID(0, 100), Unit, mapOf(CARPENTER to store(CARPENTER, BuildingType.SAWMILL, 0, stock),
        WAREHOUSE to store(WAREHOUSE, BuildingType.WAREHOUSE, 10, emptyMap()), CONSUMER to store(CONSUMER, BuildingType.BLACKSMITH, 20, emptyMap())),
        listOf(CivSupplyRequest(CONSUMER, material, amount, 0)), emptyList(), emptyList(), emptyMap(),
        CivsCarpentry.materials.associateWith { 64 }, craftWorkers = listOf(CivCraftWorker(CRAFT_WORKER, CARPENTER, 0, level, emptyMap(), false)))
}
