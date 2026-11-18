package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.verify
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

class CivsConstructionSupplyServiceTest : FunSpec({
    test("testing construction issuance journals the exact carried deficit once and stops on permission revocation") {
        mockkConstructor(CivsChunkPermissions::class)
        try {
            every { anyConstructed<CivsChunkPermissions>().allowed(any(), any()) } returns true
            serviceScenario { h ->
                val placement = h.incrementalPlacement()
                val order = placement.city.construction!!
                val configured = placement.city.copy(settings = CivSettlementSettings(autoConstructionMaterials = true),
                    construction = order.copy(phase = ConstructionPhase.WAITING_MATERIALS,
                        payment = CivConstructionPayment(awaiting = mapOf("STONE" to 1L))))
                every { h.store.load() } returns CompletableFuture.completedFuture(listOf(configured))
                every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
                every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
                h.start()
                every { anyConstructed<CivsChunkPermissions>().allowed(any(), any()) } returns false
                h.service.prepareTestingConstructionSupplies(configured.id).join()
                CivsEquipment.usableCount(h.service.city(h.player)!!.workers.first(), "STONE") shouldBe 0L
                verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
                every { anyConstructed<CivsChunkPermissions>().allowed(any(), any()) } returns true
                val grant = h.service.prepareTestingConstructionSupplies(configured.id)
                h.tick()
                h.service.city(h.player)!!.workers.first().items shouldBe null
                h.commit(); grant.join()
                val carried = h.service.city(h.player)!!.workers.first()
                CivsEquipment.usableCount(carried, "STONE") shouldBe 1L
                h.service.prepareTestingConstructionSupplies(configured.id).join()
                verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
            }
        } finally { unmockkConstructor(CivsChunkPermissions::class) }
    }

    test("construction supply journals both endpoints before debit and keeps their locks until receipt retirement") {
        serviceScenario { h ->
            val player = h.paper.addPlayer("SupplyOwner").also { it.setOp(true) }
            player.teleport(Location(h.world, 10.0, 65.0, 0.0))
            val site = h.supplySite(player.uniqueId, 6)
            val inactiveCity = h.store.load().join().single().copy(workers = emptyList())
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(inactiveCity))
            val barrel = (h.world.getBlockAt(10, 63, 0).state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, org.bukkit.inventory.ItemStack(Material.OAK_LOG, 2))
            player.inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.OAK_LOG, 10))
            val journal = CompletableFuture<CivInventoryTransaction>()
            val retirement = CompletableFuture<Boolean>()
            var receipt: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); journal }
            every { h.store.deleteInventoryTransaction(any()) } returns retirement
            h.start()
            val supplied = h.service.supplyConstruction(player, site.building.id); h.tick()
            player.inventory.getItem(0)!!.amount shouldBe 10
            barrel.getItem(0)!!.amount shouldBe 2
            h.saves shouldBe emptyList()
            requireNotNull(receipt).playerDebit!!.playerId shouldBe player.uniqueId
            val second = h.service.supplyConstruction(player, site.building.id)
            second.isDone shouldBe false
            journal.complete(receipt); h.tick()
            player.inventory.getItem(0)!!.amount shouldBe 6
            barrel.getItem(0)!!.amount shouldBe 6
            h.saves.size shouldBe 1
            supplied.isDone shouldBe false
            h.commit()
            supplied.isDone shouldBe false
            h.paper.callEvent(org.bukkit.event.player.PlayerCommandPreprocessEvent(player, "/sellall")).isCancelled shouldBe true
            retirement.complete(true); h.tick()
            supplied.join() shouldBe CivsSupplyResult(4, 0, false)
            second.join() shouldBe CivsSupplyResult(0, 0, false)
            h.paper.callEvent(org.bukkit.event.player.PlayerCommandPreprocessEvent(player, "/sellall")).isCancelled shouldBe false
            val repeated = h.service.supplyConstruction(player, site.building.id); h.tick()
            repeated.join() shouldBe CivsSupplyResult(0, 0, false)
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("admin construction grant checks its server permission before reading or changing materials") {
        serviceScenario { h ->
            every { h.player.hasPermission("arccivs.admin") } returns false
            h.start()
            val result = h.service.grantConstructionMaterials(h.player, h.city.buildings.first().id)
            (shouldThrow<CompletionException> { result.join() }.cause as CivsRejection).reason shouldBe "not-authorized"
            verify(exactly = 0) { h.store.loadPlan(any()); h.store.saveInventoryTransaction(any()); h.store.save(any()) }
        }
    }

    test("admin construction grant uses the same journal without debiting the administrator") {
        serviceScenario { h ->
            val player = h.paper.addPlayer("SupplyAdmin").also { it.setOp(true) }
            // Unlike Paper, a fresh MockBukkit player has a view with no top inventory.
            (player.openInventory as org.mockbukkit.mockbukkit.inventory.InventoryViewMock)
                .setTopInventory(h.paper.server.createInventory(player, 9))
            player.teleport(Location(h.world, 10.0, 65.0, 0.0))
            val site = h.supplySite(player.uniqueId, 6)
            val barrel = (h.world.getBlockAt(10, 63, 0).state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, org.bukkit.inventory.ItemStack(Material.OAK_LOG, 2))
            player.inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.OAK_LOG, 10))
            var receipt: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers {
                val accepted = firstArg<CivInventoryTransaction>()
                receipt = accepted; CompletableFuture.completedFuture(accepted)
            }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            val result = h.service.grantConstructionMaterials(player, site.building.id); h.tick()
            requireNotNull(receipt).playerDebit shouldBe null
            barrel.getItem(0)!!.amount shouldBe 6
            player.inventory.getItem(0)!!.amount shouldBe 10
            h.commit()
            result.join() shouldBe CivsSupplyResult(4, 0, false)
        }
    }

    test("explicit builder hut deposits subtract all builder stores and bags without using site or hall contents") {
        serviceScenario { h ->
            val player = h.paper.addPlayer("HutSupplier")
            player.addAttachment(h.plugin, "arccivs.use", true)
            player.teleport(Location(h.world, 20.0, 65.0, 0.0))
            val plan = h.supplySite(player.uniqueId, 6)
            val base = h.store.load().join().single()
            val first = base.buildings.last().copy(phase = BuildingPhase.ACTIVE)
            val second = first.copy(id = UUID.randomUUID(), anchor = CivPosition(20, 64, 0), entrance = CivPosition(20, 64, 3),
                bounds = CivBox(CivPosition(18, 63, -2), CivPosition(22, 68, 2)), control = CivPosition(20, 63, 0))
            val target = second.copy(id = UUID.randomUUID(), type = BuildingType.HOUSE, phase = BuildingPhase.CONSTRUCTING,
                anchor = CivPosition(30, 64, 0), entrance = CivPosition(30, 64, 3), control = CivPosition(30, 63, 0),
                bounds = CivBox(CivPosition(28, 63, -2), CivPosition(32, 68, 2)))
            val worker = base.workers.first().copy(assignment = first.id, nextDeliveryAt = 1, items = List(CivsEquipment.SLOTS) {
                if (it == 6) CivResidentItem("OAK_LOG", 2) else null
            })
            val order = CivConstruction(UUID.randomUUID(), target.id, 1, 1, 0, ConstructionPhase.PAUSED, Stock(),
                failure = "placement-cancelled", plannedBuilding = target, batches = CivConstructionBatches(1, 16),
                materials = mapOf("OAK_LOG" to 6L), payment = CivConstructionPayment())
            val hall = base.buildings.first().copy(control = CivPosition(0, 63, 0))
            val city = CivsRules.validate(base.copy(buildings = listOf(hall, first, second, target), workers = listOf(worker), construction = order))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.world.loadChunk(1, 0)
            for (x in listOf(0, 20, 30)) h.world.getBlockAt(x, 63, 0).type = Material.BARREL
            fun barrel(x: Int) = (h.world.getBlockAt(x, 63, 0).state as org.bukkit.block.Barrel).inventory
            barrel(0).setItem(0, org.bukkit.inventory.ItemStack(Material.OAK_LOG, 64))
            barrel(10).setItem(0, org.bukkit.inventory.ItemStack(Material.OAK_LOG, 1))
            barrel(30).setItem(0, org.bukkit.inventory.ItemStack(Material.OAK_LOG, 64))
            player.inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.OAK_LOG, 10))
            h.start()
            h.service.constructionAvailable(player, target.id)["OAK_LOG"] shouldBe 3L
            val supplied = h.service.supplyConstruction(player, target.id, second.id); h.tick()
            barrel(20).getItem(0)!!.amount shouldBe 3
            barrel(10).getItem(0)!!.amount shouldBe 1
            barrel(0).getItem(0)!!.amount shouldBe 64
            barrel(30).getItem(0)!!.amount shouldBe 64
            player.inventory.getItem(0)!!.amount shouldBe 7
            h.commit(); h.await(supplied) shouldBe CivsSupplyResult(3, 0, false)
            h.await(h.service.supplyConstruction(player, target.id, second.id)) shouldBe CivsSupplyResult(0, 0, false)
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
            verify(exactly = 0) { h.store.loadPlan(plan.id) }
        }
    }

    test("creative builder bag grant is durable capacity bounded repeat safe and rejects survival issuance") {
        serviceScenario { h ->
            val player = h.paper.addPlayer("CreativeBuilderSupplier")
            player.addAttachment(h.plugin, "arccivs.use", true)
            player.teleport(Location(h.world, 10.0, 65.0, 0.0))
            val placement = h.incrementalPlacement(phase = ConstructionPhase.PAUSED)
            val base = placement.city
            val worker = base.workers.first().copy(items = List(CivsEquipment.SLOTS) {
                when { it == 6 -> CivResidentItem("STONE", 63); it == 7 || it < 6 -> null; else -> CivResidentItem("DIRT", 64) }
            })
            val city = CivsRules.validate(base.copy(owner = player.uniqueId, memberNames = mapOf(player.uniqueId.toString() to player.name),
                workers = listOf(worker), construction = base.construction!!.copy(materials = mapOf("STONE" to 65L, "OAK_LOG" to 70L))))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            val journal = CompletableFuture<CivInventoryTransaction>()
            var receipt: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); journal }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            player.inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.DIAMOND, 3))
            h.start()
            player.gameMode = org.bukkit.GameMode.SURVIVAL
            val denied = h.service.grantBuilderMaterials(player, worker.id)
            (shouldThrow<CompletionException> { h.await(denied) }.cause as CivsRejection).reason shouldBe "not-authorized"
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
            player.gameMode = org.bukkit.GameMode.CREATIVE
            val granted = h.service.grantBuilderMaterials(player, worker.id); h.tick()
            requireNotNull(receipt).playerDebit shouldBe null
            requireNotNull(receipt).residentTransfer shouldBe null
            h.service.city(player)!!.workers.single() shouldBe worker
            h.saves shouldBe emptyList()
            journal.complete(receipt); h.tick()
            granted.isDone shouldBe false
            h.commit(); h.await(granted) shouldBe CivsSupplyResult(65, 7, true)
            CivsEquipment.plainCount(h.service.city(player)!!.workers.single(), "STONE") shouldBe 64
            CivsEquipment.plainCount(h.service.city(player)!!.workers.single(), "OAK_LOG") shouldBe 64
            h.await(h.service.grantBuilderMaterials(player, worker.id)) shouldBe CivsSupplyResult(0, 7, true)
            player.inventory.getItem(0) shouldBe org.bukkit.inventory.ItemStack(Material.DIAMOND, 3)
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
        }
    }

    for (prepaid in listOf(false, true)) test("construction supplies use the unpaid remainder and never refill a prepaid order prepaid=$prepaid") {
        serviceScenario { h ->
            val player = h.paper.addPlayer("RemainderOwner").also { it.setOp(true) }
            player.teleport(Location(h.world, 10.0, 65.0, 0.0))
            val plan = h.supplySite(player.uniqueId, 20)
            val original = h.store.load().join().single()
            val building = plan.building
            val order = CivConstruction(UUID.randomUUID(), building.id, 1, 2, 1, ConstructionPhase.PAUSED,
                Stock(), failure = "placement-cancelled", plannedBuilding = building,
                batches = CivConstructionBatches(2, 16), materials = mapOf("OAK_LOG" to 4L),
                payment = if (prepaid) null else CivConstructionPayment())
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(original.copy(
                buildings = original.buildings.map { if (it.id == building.id) building else it }, construction = order)))
            every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            val barrel = (h.world.getBlockAt(10, 63, 0).state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, org.bukkit.inventory.ItemStack(Material.OAK_LOG, 1))
            player.inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.OAK_LOG, 10))
            h.start()
            val result = h.service.supplyConstruction(player, building.id); h.tick()
            if (!prepaid) h.commit()
            result.join() shouldBe CivsSupplyResult(if (prepaid) 0 else 3, 0, false)
            barrel.getItem(0)!!.amount shouldBe if (prepaid) 1 else 4
            player.inventory.getItem(0)!!.amount shouldBe if (prepaid) 10 else 7
            verify(exactly = 0) { h.store.loadPlan(any()) }
            verify(exactly = if (prepaid) 0 else 1) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("a player supply receipt loaded after restart waits for login then completes its original transfer once") {
        serviceScenario { h ->
            val player = h.paper.addPlayer("SupplyReturn").also { it.setOp(true) }
            player.teleport(Location(h.world, 10.0, 65.0, 0.0))
            val plan = h.supplySite(player.uniqueId, 4)
            val city = h.store.load().join().single().copy(workers = emptyList())
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            val barrel = (h.world.getBlockAt(10, 63, 0).state as org.bukkit.block.Barrel).inventory
            player.inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.OAK_LOG, 10))
            val receipt = CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision,
                city.copy(revision = city.revision + 1), listOf(CivInventorySlotChange(requireNotNull(plan.building.control),
                    "BARREL", 0, null, CivPlainItem("OAK_LOG", 4))),
                CivPlayerDebit(player.uniqueId, listOf(CivPlayerSlotChange(0, CivPlainItem("OAK_LOG", 10), CivPlainItem("OAK_LOG", 6)))))
            every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(receipt))
            every { h.store.deleteInventoryTransaction(receipt.id) } returns CompletableFuture.completedFuture(true)
            player.disconnect()
            h.start(); h.paper.performTicks(20)
            h.saves shouldBe emptyList()
            barrel.isEmpty shouldBe true
            player.reconnect(); h.paper.performTicks(20)
            h.saves.size shouldBe 1
            player.inventory.getItem(0)!!.amount shouldBe 6
            barrel.getItem(0)!!.amount shouldBe 4
            h.commit(); h.paper.performTicks(20)
            h.service.city(player)!!.revision shouldBe receipt.after.revision
            h.saves.size shouldBe 1
            verify(exactly = 1) { h.store.deleteInventoryTransaction(receipt.id) }
        }
    }

    test("automatic builder supply diagnostics distinguish unchanged requests from committed quantities") {
        val results = mutableListOf<Map<String, Any?>>()
        mockkObject(CivsDiagnostics)
        try {
            every { CivsDiagnostics.event(any(), *anyVararg()) } answers {
                if (firstArg<String>() == "resident_supplies_result")
                    results += secondArg<Array<Pair<String, Any?>>>().toMap()
                Unit
            }
            serviceScenario { h ->
                val placement = h.incrementalPlacement()
                every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
                every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
                h.start(); h.commit()
                val worker = h.service.city(h.player)!!.workers.first()
                repeat(2) {
                    val pickup = h.service.prepareResidentSupplies(placement.city.id, worker.id, worker.assignment!!)
                    h.tick(); pickup.join()
                }
                results.size shouldBe 1 // An unchanged empty hut is one outcome, not a per-pulse message.
                results.single()["outcome"] shouldBe "no-op"
                results.single()["source"] shouldBe worker.assignment
                results.single()["order"] shouldBe placement.city.construction!!.id
                results.single()["batch"] shouldBe 0
                results.single()["required"] shouldBe mapOf("STONE" to 1L)
                results.single()["missing_after"] shouldBe mapOf("STONE" to 1L)
                results.single()["amount"] shouldBe 0L
                val barrel = (h.world.getBlockAt(20, 63, 0).state as org.bukkit.block.Barrel).inventory
                barrel.setItem(0, org.bukkit.inventory.ItemStack(Material.STONE, 1))
                val pickup = h.service.prepareResidentSupplies(placement.city.id, worker.id, worker.assignment!!)
                h.tick()
                results.size shouldBe 1 // A prepared transfer is not a committed pickup.
                h.commit(); pickup.join()
                results.size shouldBe 2
                results.last()["outcome"] shouldBe "committed"
                results.last()["picked"] shouldBe mapOf("STONE" to 1L)
                results.last()["returned"] shouldBe emptyMap<String, Long>()
                results.last()["carried_before"] shouldBe emptyMap<String, Long>()
                results.last()["carried_after"] shouldBe mapOf("STONE" to 1L)
                results.last()["missing_after"] shouldBe emptyMap<String, Long>()
                results.last()["amount"] shouldBe 1L
            }
        } finally { unmockkObject(CivsDiagnostics) }
    }
})

class CivsBuilderBufferReserveServiceTest : FunSpec({
    for (condition in listOf("surplus", "reserved", "partial-reserve", "destination-room", "bag-room", "short-stack-grown"))
    test("ordinary hut recovery rechecks its whole slot proof with $condition") {
        val commitDropoff = CompletableFuture<Settlement>()
        val completedDropoffs = mutableListOf<CivInventoryTransaction>()
        serviceScenario(nativeGeometry = true, storageFactory = { plugin ->
            CivsStorage(plugin) { emptyArray() }.also { storage ->
                storage.persistCollectedNative = { _, _ -> error("Fresh courier transfer requested native persistence") }
            }
        }) { h ->
            val hut = h.city.buildings.last().copy(type = BuildingType.BUILDER_HUT, control = CivPosition(10, 64, 0),
                schematicId = CivsBlueprints.schematicId("nordic", BuildingType.BUILDER_HUT, 1))
            val warehouse = hut.copy(id = UUID.randomUUID(), type = BuildingType.WAREHOUSE,
                anchor = CivPosition(20, 64, 0), entrance = CivPosition(20, 64, -2), control = CivPosition(20, 64, 0),
                bounds = CivBox(CivPosition(18, 63, -2), CivPosition(22, 68, 2)),
                schematicId = CivsBlueprints.schematicId("nordic", BuildingType.WAREHOUSE, 1))
            val target = hut.copy(id = UUID.randomUUID(), type = BuildingType.HOUSE, phase = BuildingPhase.CONSTRUCTING,
                anchor = CivPosition(30, 64, 0), entrance = CivPosition(30, 64, -2), control = CivPosition(30, 64, 0),
                bounds = CivBox(CivPosition(28, 63, -2), CivPosition(32, 68, 2)),
                schematicId = CivsBlueprints.schematicId("nordic", BuildingType.HOUSE, 1))
            val courier = h.city.workers.first().copy(assignment = warehouse.id, nextDeliveryAt = 1,
                items = if (condition == "bag-room") List(CivsEquipment.SLOTS) {
                    when { it < CivsEquipment.BAG -> null; it == CivsEquipment.BAG -> CivResidentItem("DIRT", 1)
                        else -> CivResidentItem("STONE", 64) }
                } else null)
            val builder = h.city.workers[1].copy(assignment = hut.id, nextDeliveryAt = 1)
            val reserve = if (condition in setOf("reserved", "partial-reserve")) "DIRT" else "COBBLESTONE"
            val order = CivConstruction(UUID.randomUUID(), target.id, 1, 2, 0, ConstructionPhase.PAUSED, Stock(),
                failure = "placement-cancelled", plannedBuilding = target, batches = CivConstructionBatches(2, 16),
                materials = mapOf(reserve to if (condition == "partial-reserve") 1L else 64L, "DARK_OAK_FENCE_GATE" to 1L),
                payment = CivConstructionPayment(awaiting = mapOf("DARK_OAK_FENCE_GATE" to 1L)))
            val city = CivsRules.validate(h.city.copy(buildings = listOf(h.city.buildings.first(), hut, warehouse, target),
                workers = listOf(courier, builder), stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                construction = order))
            h.world.loadChunk(1, 0)
            for (x in listOf(10, 20)) h.world.getBlockAt(x, 64, 0).type = Material.BARREL
            val source = (h.world.getBlockAt(10, 64, 0).state as org.bukkit.block.Barrel).inventory
            val destination = (h.world.getBlockAt(20, 64, 0).state as org.bukkit.block.Barrel).inventory
            repeat(source.size) { source.setItem(it, ItemStack(Material.STONE, 64)) }
            source.setItem(0, ItemStack(Material.DIRT, if (condition == "short-stack-grown") 32 else 64))
            source.setItem(1, ItemStack(Material.COBBLESTONE, 64))
            source.setItem(2, ItemStack(Material.DIRT, 64).apply {
                editMeta { it.displayName(Component.text("Exact native builder keepsake")) }
            })
            if (condition == "destination-room") {
                repeat(destination.size) { destination.setItem(it, ItemStack(Material.STONE, 64)) }
                destination.setItem(0, ItemStack(Material.DIRT, 1))
            }
            val beforeSlots = source.contents.map { it?.clone() }
            val destinationSlots = destination.contents.map { it?.clone() }
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.store.save(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.completeInventoryTransaction(any()) } answers {
                val tx = firstArg<CivInventoryTransaction>()
                if (tx.cargoDropoff == null) CompletableFuture.completedFuture(tx.after) else {
                    completedDropoffs += tx
                    commitDropoff
                }
            }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            val dispatcher = CivsService::class.java.getDeclaredField("supply").also { it.isAccessible = true }
                .get(h.service) as CivsSupplyDispatcher
            // Accepted before the current order: a fresh native pickup must reserve its latest full bill.
            val cargo = CivCargo(CivsLogistics.shipmentId(city.id, courier.id, courier.completedCycles, hut.id, warehouse.id, "DIRT"),
                warehouse.id, hut.id, warehouse.id, hut.entrance, "DIRT", if (condition == "short-stack-grown") 16L else 64L)
            val accepted = CivCourierPlan(cargo, warehouse.id, warehouse.entrance, false)
            mockkObject(dispatcher)
            try {
                every { dispatcher.plan(city.id, courier.id) } returns accepted
                if (condition != "surplus") {
                    val rejected = shouldThrow<CompletionException> { h.await(h.service.pickupCargo(city.id, courier.id, cargo)) }
                    CivsDiagnostics.rejection(rejected)?.reason shouldBe "stale-delivery"
                    source.contents.toList() shouldBe beforeSlots
                    destination.contents.toList() shouldBe destinationSlots
                    h.service.settlements().single() shouldBe city
                    completedDropoffs shouldBe emptyList()
                    verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
                } else {
                    val picked = h.await(h.service.pickupCargo(city.id, courier.id, cargo))
                    source.getItem(0) shouldBe null
                    source.contents.drop(1) shouldBe beforeSlots.drop(1)
                    val carried = requireNotNull(picked.workers.first { it.id == courier.id }.cargo)
                    carried.amount shouldBe 64L
                    carried.inventoryBacked shouldBe true
                    completedDropoffs shouldBe emptyList()
                    val unloading = h.service.dropoffCargo(city.id, courier.id, carried, warehouse.id)
                    h.tickUntil("native cargo credit awaiting RAM commit") { completedDropoffs.isNotEmpty() }
                    val dropoff = completedDropoffs.single()
                    unloading.isDone shouldBe false
                    h.service.settlements().single() shouldBe picked
                    destination.contents.filterNotNull() shouldBe listOf(ItemStack(Material.DIRT, 64))
                    source.contents.drop(1) shouldBe beforeSlots.drop(1)
                    commitDropoff.complete(dropoff.after)
                    val saved = h.await(unloading)
                    destination.contents.filterNotNull() shouldBe listOf(ItemStack(Material.DIRT, 64))
                    saved.workers.first { it.id == courier.id }.cargo shouldBe null
                    saved.construction shouldBe order
                    source.contents.drop(1) shouldBe beforeSlots.drop(1)
                    verify(exactly = 2) { h.store.saveInventoryTransaction(any()) }
                }
            } finally { unmockkObject(dispatcher) }
        }
    }
})
