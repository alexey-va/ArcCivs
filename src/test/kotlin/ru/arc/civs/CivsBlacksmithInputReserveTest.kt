package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsBlacksmithInputReserveTest : FunSpec({
    lateinit var h: BlacksmithInputReserveFixture
    beforeEach { h = BlacksmithInputReserveFixture() }
    afterEach { h.close() }

    test("partial accepted pickaxe inputs stay carried until native supplies complete one paid craft") {
        for (iron in 1L..2L) {
            h.carry(mapOf("IRON_INGOT" to iron, "STICK" to 2L, "BREAD" to 1L))
            CivsCarpentry.valid(h.city, h.worker, h.job) shouldBe true
            CivsLogistics.workerExcess(h.city, h.worker, h.job.inputs) shouldBe emptyMap()
            h.offersOutputs() shouldBe false
            val id = UUID.randomUUID()
            h.storage.reserve(h.city, id)
            val returned = h.storage.prepareResidentReturn(h.city, h.worker.id, h.smith.id, h.job.inputs)
            returned.worker shouldBe h.worker
            returned.plan.changes shouldBe emptyList()
            h.storage.unlock(id)
            h.barrel.isEmpty shouldBe true
        }

        h.carry(mapOf("IRON_INGOT" to 1L, "STICK" to 2L, "BREAD" to 1L))
        h.barrel.setItem(0, ItemStack(Material.IRON_INGOT, 2))
        val pickupId = UUID.randomUUID()
        h.storage.reserve(h.city, pickupId)
        val missing = CivsEquipment.storageInputs(h.worker, h.job.inputs)
        missing shouldBe mapOf("IRON_INGOT" to 2L)
        val pickup = h.storage.prepareResidentPickup(h.city, h.worker.id, h.smith.id, missing)
        h.commit(pickupId, pickup.plan, h.city.copy(workers = listOf(pickup.worker), revision = h.city.revision + 1))
        h.barrel.isEmpty shouldBe true
        CivsEquipment.inputsReady(h.worker, h.job.inputs, emptyMap()) shouldBe true
        h.offersOutputs() shouldBe false
        CivsLogistics.workerExcess(h.city, h.worker, h.job.inputs) shouldBe emptyMap()

        val craftId = UUID.randomUUID()
        h.storage.reserve(h.city, craftId)
        val output = h.storage.prepareProduction(h.city, h.smith, h.worker, h.job)
        val completed = CivsRules.deliver(h.city, h.city.owner, h.worker.id, h.smith.id,
            h.worker.completedCycles, h.worker.nextDeliveryAt, 10_000, outputHandled = true, craftJob = h.job)
        h.commit(craftId, output, completed)
        h.worker.completedCycles shouldBe 1L
        CivsEquipment.plainCount(h.worker, "IRON_INGOT") shouldBe 0
        CivsEquipment.plainCount(h.worker, "STICK") shouldBe 0
        CivsEquipment.plainCount(h.worker, "BREAD") shouldBe 1
        h.count(Material.IRON_PICKAXE) shouldBe 1
        h.count(Material.IRON_INGOT) shouldBe 0
        h.count(Material.STICK) shouldBe 0
        CivsCarpentry.valid(h.city, h.worker, h.job) shouldBe false
    }

    test("native surplus return retains the accepted bill and returns only the real excess") {
        h.carry(mapOf("IRON_INGOT" to 5L, "STICK" to 3L, "BREAD" to 1L))
        h.offersOutputs() shouldBe true
        CivsLogistics.workerExcess(h.city, h.worker, h.job.inputs) shouldBe mapOf("IRON_INGOT" to 2L, "STICK" to 1L)
        val id = UUID.randomUUID()
        h.storage.reserve(h.city, id)
        val returned = h.storage.prepareResidentReturn(h.city, h.worker.id, h.smith.id, h.job.inputs)
        h.barrel.isEmpty shouldBe true
        CivsEquipment.plainCount(returned.worker, "IRON_INGOT") shouldBe 3
        CivsEquipment.plainCount(returned.worker, "STICK") shouldBe 2
        h.commit(id, returned.plan, h.city.copy(workers = listOf(returned.worker), revision = h.city.revision + 1))
        h.count(Material.IRON_INGOT) shouldBe 2
        h.count(Material.STICK) shouldBe 1
        CivsEquipment.plainCount(h.worker, "BREAD") shouldBe 1
        h.offersOutputs() shouldBe false
        CivsEquipment.inputsReady(h.worker, h.job.inputs, emptyMap()) shouldBe true
        val nextId = UUID.randomUUID()
        h.storage.reserve(h.city, nextId)
        val next = h.storage.prepareResidentReturn(h.city, h.worker.id, h.smith.id, h.job.inputs)
        next.worker shouldBe h.worker
        next.plan.changes shouldBe emptyList()
        h.storage.unlock(nextId)
    }

    test("a blacksmith without an accepted batch and other professions keep their existing reserves") {
        CivsLogistics.workerWorkReserve(h.city, h.worker) shouldBe mapOf("IRON_INGOT" to 1L, "STICK" to 2L)
        val sawmill = h.smith.copy(type = BuildingType.SAWMILL, production = "OAK_PLANKS")
        val carpenterCity = h.city.copy(buildings = h.city.buildings.map { if (it.id == sawmill.id) sawmill else it })
        CivsLogistics.workerWorkReserve(carpenterCity, h.worker) shouldBe emptyMap()
        val batch = mapOf("SPRUCE_PLANKS" to 6L)
        CivsLogistics.workerWorkReserve(carpenterCity, h.worker, batch) shouldBe batch
        val smeltery = h.smith.copy(type = BuildingType.SMELTERY, production = "CHARCOAL")
        val smelterCity = h.city.copy(buildings = h.city.buildings.map { if (it.id == smeltery.id) smeltery else it })
        CivsLogistics.workerWorkReserve(smelterCity, h.worker, h.job.inputs) shouldBe
            mapOf("OAK_LOG" to 8L, "OAK_PLANKS" to 6L)
    }
})

private class BlacksmithInputReserveFixture : AutoCloseable {
    private val paper = MockBukkitTestRuntime.open()
    private val world = paper.addSimpleWorld("blacksmith-reserve")
    private val plugin = paper.createSimplePlugin("BlacksmithInputReserve")
    val storage = CivsStorage(plugin) { emptyArray() }
    private val hall = building(0, BuildingType.TOWN_HALL)
    val smith = building(8, BuildingType.BLACKSMITH).copy(production = "IRON_SHOVEL")
    var city: Settlement
    val worker get() = city.workers.single()
    val job: CivCraftJob
    val barrel: Inventory

    init {
        for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
        listOf(hall, smith).forEach { building ->
            val point = requireNotNull(building.control)
            world.getBlockAt(point.x, point.y, point.z).type = Material.BARREL
        }
        barrel = (world.getBlockAt(8, 64, 0).state as Barrel).inventory
        city = CivsRules.validate(Settlement(UUID.randomUUID(), UUID.randomUUID(), "Smith", world.uid,
            CivPosition(0, 64, 0), 32, "nordic", Stock(), listOf(hall, smith),
            listOf(CivWorker(UUID.randomUUID(), "Smith", smith.id, nextDeliveryAt = 1)),
            inventory = CivInventory(CivStorageMode.PHYSICAL), autonomy = CivAutonomy()))
        val recipe = CivsCarpentry.recipes.single { it.id == "blacksmith/iron_pickaxe" }
        job = CivCraftJob(UUID.randomUUID(), worker.id, smith.id, worker.completedCycles, recipe.id,
            CivsCarpentry.VERSION, recipe.material, recipe.amount, recipe.inputs, recipe.material, hall.id)
    }

    private fun building(x: Int, type: BuildingType): CivBuilding {
        val point = CivPosition(x, 64, 0)
        return CivBuilding(UUID.randomUUID(), type, 1, point, 0, "test/$type",
            CivBox(point.copy(x = x - 2, z = -2), point.copy(x = x + 2, y = 68, z = 2)),
            point.copy(z = -2), BuildingPhase.ACTIVE, point)
    }

    fun carry(items: Map<String, Long>) {
        val empty = worker.copy(items = List(CivsEquipment.SLOTS) { null })
        city = city.copy(workers = listOf(CivsEquipment.collectSupplies(empty, items)))
    }

    fun offersOutputs(): Boolean {
        val runtime = CitizenRuntime(plugin) { listOf(city) }
        val couriers = CivsCitizenCouriers(runtime, { error("unused routes") }, { error("unused leisure") },
            { error("unused work") }, { error("unused logistics") }, CivsPaths(), CivsResidentFoods.BREAD_ONLY,
            { current, resident -> job.takeIf { CivsCarpentry.valid(current, resident, it) } }, CivsNavigationSettings())
        val point = requireNotNull(smith.control)
        val plan = CitizenPlan(CitizenToken(smith.id, worker.completedCycles, worker.nextDeliveryAt, null,
            craftJobId = job.id), point, point, smith.type, 0, null)
        return couriers.sourceOffersCargo(CitizenActor(city.id, worker.id, city.worldId, mockk(relaxed = true), plan), city, worker)
    }

    fun count(material: Material): Int = barrel.storageContents.filterNotNull().filter { it.type == material }.sumOf(ItemStack::getAmount)

    fun commit(id: UUID, plan: CivStoragePlan, after: Settlement) {
        val tx = CivInventoryTransaction(id, city.id, city.revision, after.copy(inventory = plan.inventoryAfter),
            plan.changes, residentTransfer = plan.residentTransfer)
        storage.seal(tx)
        storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        city = tx.after
        storage.unlock(id)
    }

    override fun close() { storage.close(); paper.close() }
}
