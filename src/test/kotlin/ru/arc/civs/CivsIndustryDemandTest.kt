package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class CivsIndustryDemandTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("glass demand follows paid gravel and sand batches instead of the default charcoal recipe") {
        var city = industryDemandCity()
        val stock = mutableMapOf("COBBLESTONE" to 32L, "CHARCOAL" to 7_320L)
        industryJobs(industryDemand(city, stock, "GLASS", 8).copy(requests = emptyList())) shouldBe emptyMap()
        val batches = mutableListOf<String>()
        repeat(25) {
            val snapshot = industryDemand(city, stock, "GLASS", 8)
            val job = industryJobs(snapshot).values.single()
            batches += job.material
            // The allocator only chooses intent; all intermediates are supplied in later receipts.
            snapshot.stores.getValue(INDUSTRY_DEPOT).stock shouldBe stock
            job.inputs.forEach { (material, amount) ->
                ((stock[material] ?: 0) >= amount) shouldBe true
                stock[material] = stock.getValue(material) - amount
            }
            stock.merge(job.material, job.amount, Long::plus)
            city = city.copy(workers = city.workers.map { if (it.id == job.workerId) it.copy(completedCycles = it.completedCycles + 1) else it })
        }
        batches.groupingBy { it }.eachCount() shouldBe mapOf("GRAVEL" to 16, "SAND" to 8, "GLASS" to 1)
        stock.filterValues { it > 0 } shouldBe mapOf("CHARCOAL" to 7_319L, "GLASS" to 8L)
        industryJobs(industryDemand(city, stock, "GLASS", 8)) shouldBe emptyMap()
    }

    test("owner capture preserves explicit selections research and crusher level") {
        val city = industryDemandCity().copy(autonomy = null)
        val stock = mapOf("SAND" to 8L, "RAW_IRON" to 8L, "GRAVEL" to 16L, "CHARCOAL" to 7_320L)
        // Null still means the ordinary town's default CHARCOAL, not autonomous selection.
        industryJobs(industryDemand(city, stock, "GLASS", 8)) shouldBe emptyMap()
        val manual = city.copy(buildings = city.buildings.map { if (it.type == BuildingType.SMELTERY) it.copy(production = "CHARCOAL") else it })
        industryJobs(industryDemand(manual, stock, "GLASS", 8)) shouldBe emptyMap()
        industryJobs(industryDemand(manual, stock, "IRON_INGOT", 8)) shouldBe emptyMap()
        val locked = city.copy(autonomy = CivAutonomy(), research = null)
        industryJobs(industryDemand(locked, stock, "GLASS", 8)) shouldBe emptyMap()
        industryJobs(industryDemand(locked, stock, "IRON_INGOT", 8)) shouldBe emptyMap()
        industryJobs(industryDemand(locked, stock - "SAND", "SAND", 8)) shouldBe emptyMap()
        val small = city.copy(autonomy = CivAutonomy(),
            buildings = city.buildings.map { if (it.type == BuildingType.CRUSHER) it.copy(level = 1) else it })
        industryJobs(industryDemand(small, stock - "SAND", "SAND", 8)) shouldBe emptyMap()
        val selected = city.copy(buildings = city.buildings.map { if (it.type == BuildingType.SMELTERY) it.copy(production = "GLASS") else it })
        industryJobs(industryDemand(selected, stock, "GLASS", 8)).values.single().material shouldBe "GLASS"
    }

    test("an accepted batch keeps exact carried inputs across selection changes and promises only once") {
        val city = industryDemandCity()
        val stock = mapOf("SAND" to 8L, "CHARCOAL" to 1L)
        val accepted = industryJobs(industryDemand(city, stock, "GLASS", 8))
        val job = accepted.values.single()
        val changed = city.copy(buildings = city.buildings.map { if (it.id == job.buildingId) it.copy(production = "CHARCOAL") else it },
            workers = city.workers.map { worker -> if (worker.id != job.workerId) worker else worker.copy(
                items = List(CivsEquipment.SLOTS) { slot -> when (slot) {
                    CivsEquipment.BAG -> CivResidentItem("SAND", 8)
                    CivsEquipment.BAG + 1 -> CivResidentItem("CHARCOAL", 1)
                    else -> null
                } }) })
        val worker = changed.workers.single { it.id == job.workerId }
        CivsCarpentry.valid(changed, worker, job) shouldBe true
        CivsLogistics.workerWorkReserve(changed, worker, job.inputs) shouldBe job.inputs
        industryJobs(industryDemand(changed, emptyMap(), "GLASS", 8, accepted)) shouldBe accepted
        val other = industryBuilding(24, BuildingType.SMELTERY)
        val second = city.workers.first { it.assignment == job.buildingId }.copy(id = UUID(0, 14), assignment = other.id)
        industryJobs(industryDemand(city.copy(buildings = city.buildings + other, workers = city.workers + second), stock, "GLASS", 8)).size shouldBe 1
    }

    test("a charcoal request obtains wood intermediates from the existing sawmill without a virtual shortcut") {
        val sawmill = industryBuilding(32, BuildingType.SAWMILL)
        var city = industryDemandCity().let { it.copy(buildings = it.buildings + sawmill,
            workers = it.workers + CivWorker(UUID(0, 15), "Carpenter", sawmill.id, nextDeliveryAt = 1)) }
        val stock = mutableMapOf("OAK_LOG" to 10L)
        val products = mutableListOf<String>()
        repeat(3) {
            val job = industryJobs(industryDemand(city, stock, "CHARCOAL", 8)).values.single()
            products += job.material
            job.inputs.forEach { (input, amount) ->
                ((stock[input] ?: 0L) >= amount) shouldBe true
                stock[input] = stock.getValue(input) - amount
            }
            stock.merge(job.material, job.amount, Long::plus)
            city = city.copy(workers = city.workers.map { if (it.id == job.workerId) it.copy(completedCycles = it.completedCycles + 1) else it })
        }
        products shouldBe listOf("OAK_PLANKS", "OAK_PLANKS", "CHARCOAL")
        stock.filterValues { it > 0 } shouldBe mapOf("OAK_PLANKS" to 2L, "CHARCOAL" to 8L)
    }

    test("industrial batches consume carried inputs and publish output once through the normal native receipt") {
        val world = paper.addSimpleWorld("industry-demand")
        for (x in -1..3) for (z in -1..1) world.loadChunk(x, z)
        val plugin = paper.createSimplePlugin("IndustryDemand")
        CivsStorage(plugin) { emptyArray() }.use { storage ->
            for ((material, input) in listOf("GLASS" to "SAND", "IRON_INGOT" to "RAW_IRON", "STONE" to "COBBLESTONE",
                "GRAVEL" to "COBBLESTONE", "SAND" to "GRAVEL")) {
                val base = industryDemandCity().copy(worldId = world.uid, settings = CivSettlementSettings(garrisonSupplies = true))
                val job = industryJobs(industryDemand(base, mapOf(input to if (material == "SAND") 16L else 8L,
                    "CHARCOAL" to 1L), material, 8)).values.single()
                job.material shouldBe material
                job.inputs shouldBe if (material in listOf("GRAVEL", "SAND")) mapOf(input to 2L) else mapOf(input to 8L, "CHARCOAL" to 1L)
                val building = base.building(job.buildingId)!!
                val unpaid = base.workers.single { it.id == job.workerId }
                shouldThrow<CivsRejection> { CivsRules.deliver(base, base.owner, unpaid.id, building.id,
                    unpaid.completedCycles, unpaid.nextDeliveryAt, 60_000, outputHandled = true, craftJob = job) }.reason shouldBe "materials-missing"
                base.settings?.garrisonReplacement shouldBe null
                val point = requireNotNull(building.control)
                world.getBlockAt(point.x, point.y, point.z).type = Material.BARREL
                val barrel = (world.getBlockAt(point.x, point.y, point.z).state as Barrel).inventory
                barrel.clear()
                val city = base.copy(workers = base.workers.map { worker -> if (worker.id == job.workerId)
                    CivsEquipment.collectSupplies(worker, job.inputs) else worker })
                val worker = city.workers.single { it.id == job.workerId }
                val id = UUID.randomUUID()
                storage.reserve(city, id)
                val plan = storage.prepareProduction(city, building, worker, job)
                val after = CivsRules.deliver(city, city.owner, worker.id, building.id, worker.completedCycles,
                    worker.nextDeliveryAt, 60_000, outputHandled = true, craftJob = job)
                after.settings?.garrisonReplacement shouldBe if (material == "GRAVEL") mapOf("COBBLESTONE" to 2L) else null
                job.inputs.keys.forEach { CivsEquipment.plainCount(after.workers.single { it.id == worker.id }, it) shouldBe 0 }
                val receipt = CivInventoryTransaction(id, city.id, city.revision, after.copy(inventory = plan.inventoryAfter), plan.changes)
                storage.seal(receipt)
                barrel.isEmpty shouldBe true
                storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
                storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
                barrel.storageContents.filterNotNull() shouldBe listOf(ItemStack(Material.valueOf(material), job.amount.toInt()))
                storage.unlock(id)
                shouldThrow<CivsRejection> { CivsRules.deliver(after, city.owner, worker.id, building.id,
                    worker.completedCycles, worker.nextDeliveryAt, 60_001, outputHandled = true, craftJob = job) }.reason shouldBe "stale-delivery"
            }
        }
    }
})

private val INDUSTRY_DEPOT = UUID(0, 40)
private fun industryJobs(snapshot: CivSupplySnapshot) = CivsCarpentry.allocate(snapshot, AtomicBoolean()).jobs
private fun industryBuilding(x: Int, type: BuildingType) = CivBuilding(UUID(0, x.toLong()), type, 3,
    CivPosition(x, 64, 0), 0, "test/$type", CivBox(CivPosition(x - 2, 63, -2), CivPosition(x + 2, 70, 2)),
    CivPosition(x, 64, -3), BuildingPhase.ACTIVE, CivPosition(x, 64, 0))
private fun industryDemandCity(): Settlement {
    val buildings = listOf(industryBuilding(0, BuildingType.TOWN_HALL), industryBuilding(8, BuildingType.SMELTERY),
        industryBuilding(16, BuildingType.CRUSHER), industryBuilding(40, BuildingType.WAREHOUSE))
    return Settlement(UUID(0, 100), UUID(0, 101), "Industry", UUID(0, 102), CivPosition(0, 64, 0), 80,
        "nordic", Stock(), buildings, listOf(CivWorker(UUID(0, 11), "Smelter", UUID(0, 8), nextDeliveryAt = 1),
            CivWorker(UUID(0, 12), "Crusher", UUID(0, 16), nextDeliveryAt = 1)), inventory = CivInventory(CivStorageMode.PHYSICAL),
        autonomy = CivAutonomy(), research = CivResearchState(setOf(CivResearchId.MATERIALS, CivResearchId.METALLURGY, CivResearchId.GLASSWORK)))
}

/** Captured native contents feed the actual owner planner, including research and manual recipe selection. */
private fun industryDemand(city: Settlement, stock: Map<String, Long>, material: String, amount: Long,
    jobs: Map<UUID, CivCraftJob> = emptyMap()): CivSupplySnapshot {
    val capture = mockk<CivsSupplyCapture>()
    every { capture.step() } returns true
    every { capture.result } returns CivSupplyCaptureResult(city.id, city.buildings.associate { building ->
        building.id to CivSupplyCapturedStore(if (building.id == INDUSTRY_DEPOT) stock.toMap() else emptyMap(),
            CivSupplyCapacity(27, emptyMap()), CivSupplyStorageKey(city.id, city.worldId, city.owner, CivStorageMode.PHYSICAL,
                building.id, building.type, building.phase, building.level, building.bounds, building.control,
                mapOf(requireNotNull(building.control) to "BARREL"), emptyMap(), 0))
    })
    val planning = CivsSupplyPlanning(city, capture, emptyMap(), { null }, { false }, jobs)
    var snapshot: CivSupplySnapshot? = null
    repeat(city.workers.size + 2) { if (snapshot == null) snapshot = planning.step() }
    return requireNotNull(snapshot).copy(requests = listOf(CivSupplyRequest(UUID(0, 0), material, amount, 0)),
        stockTargets = emptyMap()).detached()
}
