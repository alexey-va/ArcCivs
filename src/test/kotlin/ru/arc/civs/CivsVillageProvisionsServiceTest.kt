package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import java.util.concurrent.CompletableFuture
import java.util.UUID
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

class CivsVillageProvisionsServiceTest : FunSpec({
    for (autonomous in listOf(false, true)) test("the off or unsupported garrison leaves physical inventory alone: Auto=$autonomous") {
        localInventoryScenario { h, disk ->
            val layout = GarrisonScenario(h, enabled = !autonomous, autonomous = autonomous)
            layout.towerStock.setItem(0, ItemStack(Material.OAK_LOG, 16))
            disk.store.save(layout.city).join()
            h.paper.addPlayer("GarrisonObserver").teleport(Location(h.world, 1.5, 64.0, .5))
            h.start()
            repeat(4) { h.time += 1_000; h.tick() }
            layout.orders().none { it.buildingId == layout.tower.id } shouldBe true
            layout.towerStock.getItem(0) shouldBe ItemStack(Material.OAK_LOG, 16)
            disk.store.flush().join()
            disk.checkpointed().settings?.lastGarrisonIssue shouldBe null
        }
    }

    test("ordinary garrison deliveries issue separate exact local lots and preserve the five-minute interval on reload") {
        localInventoryScenario { h, disk ->
            val layout = GarrisonScenario(h)
            val logs = ItemStack(Material.OAK_LOG, 4).named("Private reserve")
            val glass = ItemStack(Material.GLASS, 8).named("Do not issue")
            layout.towerStock.setItem(20, logs.clone())
            layout.towerStock.setItem(21, glass.clone())
            layout.warehouseStock.setItem(0, ItemStack(Material.OAK_LOG, 16))
            layout.warehouseStock.setItem(1, ItemStack(Material.WHEAT, 8))
            layout.hallStock.setItem(0, ItemStack(Material.OAK_LOG, 53))
            disk.store.save(layout.city).join()
            h.paper.addPlayer("GarrisonObserver").teleport(Location(h.world, 1.5, 64.0, .5))
            h.start()
            layout.orders().filter { it.buildingId == layout.tower.id }.associate { it.material to it.needed } shouldBe
                mapOf("OAK_LOG" to 16L, "WHEAT" to 8L, "GRAVEL" to 8L, "GLASS" to 8L,
                    "CHARCOAL" to 8L, "IRON_SHOVEL" to 3L, "BOOK" to 4L)
            val first = setOf(layout.deliverOne(), layout.deliverOne())
            first shouldBe setOf("OAK_LOG", "WHEAT")
            h.tickUntil("observed physical lots are consumed independently") {
                h.service.settlements().single().settings?.lastGarrisonIssue?.keys == first
            }
            layout.towerStock.plain(Material.OAK_LOG) shouldBe 0
            layout.towerStock.plain(Material.WHEAT) shouldBe 0
            layout.towerStock.getItem(20) shouldBe logs
            layout.towerStock.getItem(21) shouldBe glass
            layout.hallStock.getItem(0) shouldBe ItemStack(Material.OAK_LOG, 53)
            disk.store.flush().join()
            val issued = requireNotNull(disk.checkpointed().settings?.lastGarrisonIssue)
            issued.keys shouldBe first

            // The next order can refill one lot during the cooldown.
            layout.warehouseStock.setItem(0, ItemStack(Material.OAK_LOG, 31))
            captureServicePlanningStorage(h.service, h.service.settlements().single())
            layout.reload()
            val refilled = layout.deliverOne()
            refilled shouldBe "OAK_LOG"
            layout.towerStock.plain(Material.OAK_LOG) shouldBe 16
            h.time = issued.getValue("OAK_LOG") + 299_999
            layout.reload()
            layout.orders()
            repeat(4) { h.tick() }
            layout.towerStock.plain(Material.OAK_LOG) shouldBe 16
            h.service.settlements().single().settings?.lastGarrisonIssue shouldBe issued

            h.time += 1
            layout.reload()
            h.service.requestSupplyEvidence(layout.city.id)
            h.tickUntil("a new period spends only the refilled log lot") {
                layout.towerStock.plain(Material.OAK_LOG) == 0
            }
            disk.store.flush().join()
            disk.checkpointed().settings?.lastGarrisonIssue shouldBe issued + ("OAK_LOG" to h.time)
            layout.warehouseStock.plain(Material.OAK_LOG) shouldBe 15
            layout.towerStock.getItem(20) shouldBe logs
            layout.towerStock.getItem(21) shouldBe glass
            layout.hallStock.getItem(0) shouldBe ItemStack(Material.OAK_LOG, 53)
        }
    }

    test("the Auto planner retains lawful imports but supplies neither the tower nor locally produced logs") {
        serviceScenario { h ->
            val layout = GarrisonScenario(h)
            val forester = layout.annex(BuildingType.FORESTER, 30, "OAK_LOG")
            val smeltery = layout.annex(BuildingType.SMELTERY, 40, "CHARCOAL")
            layout.barrel(forester)
            layout.barrel(smeltery)
            val withIndustry = CivsRules.validate(layout.city.copy(buildings = layout.city.buildings + listOf(forester, smeltery),
                workers = layout.city.workers + h.city.workers[2].copy(assignment = forester.id, nextDeliveryAt = 1) +
                    h.city.workers[3].copy(assignment = smeltery.id, nextDeliveryAt = 1)))
            CivsStorage(h.plugin) { emptyArray() }.use { stock ->
                stock.foodCanModify = { _, _ -> true }
                fun observe(city: Settlement): CivSupplySnapshot {
                    stock.updateCity(city)
                    val capture = CivsSupplyPlanning(city, stock.startSupplyCapture(city, forceNative = true),
                        emptyMap(), { null }, { false })
                    return (1..20).firstNotNullOf { capture.step() }
                }
                fun grants(city: Settlement): List<CivAutoSupplyDemand> {
                    val observation = observe(city)
                    check(CivsAutoSupplies.observed(city, observation)) { observation.inputKey.toString() }
                    return CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY, observation)
                }
                val enabled = grants(withIndustry)
                enabled.none { it.buildingId == layout.tower.id || "OAK_LOG" in it.required } shouldBe true
                enabled.firstOrNull { it.buildingId == smeltery.id }?.required shouldBe mapOf("OAK_PLANKS" to 6L)
                val disabled = withIndustry.copy(settings = CivSettlementSettings())
                grants(disabled).firstOrNull { it.buildingId == smeltery.id }?.required?.get("OAK_LOG") shouldBe 8L
                layout.towerStock.isEmpty shouldBe true
            }
        }
    }
})

private class GarrisonScenario(val h: ServiceScenario, enabled: Boolean = true, autonomous: Boolean = true) {
    val hall = h.city.buildings.first().copy(control = CivPosition(0, 63, 0))
    val warehouse = annex(BuildingType.WAREHOUSE, 10)
    val tower = annex(BuildingType.GUARD_TOWER, 20)
    private val courier = h.city.workers[0].copy(assignment = warehouse.id, nextDeliveryAt = 1)
    private val guard = h.city.workers[1].copy(assignment = tower.id, nextDeliveryAt = 1)
    val city = CivsRules.validate(h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
        autonomy = CivAutonomy().takeIf { autonomous }, settings = CivSettlementSettings(garrisonSupplies = enabled),
        buildings = listOf(hall, warehouse, tower), workers = listOf(courier, guard)))
    val hallStock = barrel(hall)
    val warehouseStock = barrel(warehouse)
    val towerStock = barrel(tower)

    init {
        every { h.blueprints.interiorControls(any()) } returns CompletableFuture.completedFuture(emptyList())
    }

    fun reload() {
        h.tickUntil("the physical inventory receipt releases the village before reload") {
            val debug = h.service.debugState("overview", city.id, null)
            val row = (debug.getValue("cities") as List<*>).single() as Map<*, *>
            row["busy"] == false
        }
        h.await(h.service.reload(h.player))
    }

    fun annex(type: BuildingType, x: Int, product: String? = null) = h.city.buildings.last().copy(
        id = UUID.randomUUID(), type = type, anchor = CivPosition(x, 64, 0), entrance = CivPosition(x, 64, -2),
        bounds = CivBox(CivPosition(x - 2, 63, -2), CivPosition(x + 2, 68, 2)), control = CivPosition(x, 63, 0),
        production = product)

    fun barrel(building: CivBuilding): Inventory {
        val point = requireNotNull(building.control)
        for (x in (building.bounds.min.x shr 4)..(building.bounds.max.x shr 4))
            for (z in (building.bounds.min.z shr 4)..(building.bounds.max.z shr 4)) h.world.loadChunk(x, z)
        val block = h.world.getBlockAt(point.x, point.y, point.z)
        block.type = Material.BARREL
        return (block.state as Barrel).inventory
    }

    fun orders(): List<CivSupplyRequest> {
        val supply = CivsService::class.java.getDeclaredField("supply").also { it.isAccessible = true }
            .get(h.service) as CivsSupplyDispatcher
        h.service.requestSupplyEvidence(city.id)
        h.tickUntil("the supply dispatcher captures the village") { supply.observation(city.id) != null }
        return requireNotNull(supply.observation(city.id)).requests
    }

    fun deliverOne(): String {
        h.time += 1_000L
        h.service.requestSupplyEvidence(city.id)
        fun plan(): CivCourierPlan? {
            val current = h.service.settlements().single()
            return h.service.courierPlan(current, current.workers.single { it.id == courier.id })
        }
        h.tickUntil("the warehouse courier receives a garrison shipment") { plan() != null }
        val plan = requireNotNull(plan())
        plan.destinationBuildingId shouldBe tower.id
        plan.cargo.sourceBuildingId shouldBe warehouse.id
        h.await(h.service.pickupCargo(city.id, courier.id, plan.cargo))
        h.await(h.service.dropoffCargo(city.id, courier.id, plan.cargo, tower.id))
        return plan.cargo.material
    }
}

private fun ItemStack.named(name: String) = apply {
    itemMeta = itemMeta!!.also { it.displayName(Component.text(name)) }
}

private fun Inventory.plain(type: Material) = storageContents
    .mapNotNull(CivsStorageItems::plain).filter { it.material == type.name }.sumOf(CivPlainItem::amount)
