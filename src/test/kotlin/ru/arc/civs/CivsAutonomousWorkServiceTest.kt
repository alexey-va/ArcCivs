package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.verify
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.logging.Handler
import java.util.logging.LogRecord
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack

class CivsAutonomousWorkServiceTest : FunSpec({
    test("a farmer discovers work during a held player-to-barrel receipt but execution keeps the write fence") {
        serviceScenario(nativeGeometry = true, serverFixture = CivsWorksiteServerFixture()) { h ->
            val hall = h.city.buildings.first().copy(control = CivPosition(0, 63, 0))
            val farm = spatialBuilding("medievaloak", "farmer", 1, CivPosition(30, 63, 0)).copy(production = "WHEAT")
            val farmer = spatialWorker(farm, "IRON_HOE")
            val player = h.paper.addPlayer("SupplyOwner")
            player.inventory.setItem(0, ItemStack(Material.COBBLESTONE, 4))
            val town = CivsRules.validate(h.city.copy(owner = player.uniqueId, buildings = listOf(hall, farm),
                workers = listOf(farmer), stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL)))
            for (x in -1..4) for (z in -3..1) h.world.loadChunk(x, z)
            val plots = CivsWorksiteLayouts.field(farm)
            plots.forEach { soil ->
                h.world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=7]")
                h.world.getBlockAt(soil.x, soil.y + 1, soil.z).blockData = Bukkit.createBlockData("minecraft:wheat[age=2]")
            }
            val water = CivsWorksiteLayouts.irrigation(farm)
            water.forEach { h.world.getBlockAt(it.x, it.y, it.z).type = Material.WATER }
            val ripe = plots[20].copy(y = plots[20].y + 1)
            h.world.getBlockAt(ripe.x, ripe.y, ripe.z).blockData = Bukkit.createBlockData("minecraft:wheat[age=7]")
            val fieldBefore = (plots.flatMap { listOf(it, it.copy(y = it.y + 1)) } + water).associateWith {
                h.world.getBlockAt(it.x, it.y, it.z).blockData.asString
            }
            val source = requireNotNull(hall.control)
            val barrel = h.world.getBlockAt(source.x, source.y, source.z)
                .also { it.type = Material.BARREL }.let { (it.state as Barrel).inventory }
            barrel.setItem(1, ItemStack(Material.WHEAT, 64))
            val farmControl = requireNotNull(farm.control)
            h.world.getBlockAt(farmControl.x, farmControl.y, farmControl.z).type = Material.BARREL
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(town))
            val admission = CompletableFuture<CivInventoryTransaction>()
            var held: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { held = firstArg(); admission }
            every { h.store.completeInventoryTransaction(any()) } answers {
                CompletableFuture.completedFuture(firstArg<CivInventoryTransaction>().after)
            }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.world.time = 18_000
            val storage = CivsService::class.java.getDeclaredField("storage").also { it.isAccessible = true }
                .get(h.service) as CivsStorage
            val worksites = CivsService::class.java.getDeclaredField("worksites").also { it.isAccessible = true }
                .get(h.service) as CivsWorksites
            CivsWorksites::class.java.getDeclaredField("nanoTime").also { it.isAccessible = true }.set(worksites, { 0L })
            val deposit = h.service.start().thenCompose {
                // Warm only the native holder cache. A retained order would mask this regression.
                val capture = storage.startSupplyCapture(town)
                while (!capture.step()) Unit
                requireNotNull(capture.result).plainStock?.get("WHEAT") shouldBe 64L
                h.service.productionOrder(town, farmer) shouldBe null
                h.service.inventoryMutation(town.id, { city ->
                    storage.prepareConstructionSupply(city, hall.id, mapOf("COBBLESTONE" to 4L), player).plan
                }, sourcePlayer = player) { city, inventory -> city.copy(inventory = inventory, revision = city.revision + 1) }
            }
            h.tickUntil("player-to-barrel receipt waits for storage acknowledgement") { held != null }
            h.service.collectionAvailable(town.id) shouldBe false
            h.service.productionOrder(town, farmer) shouldBe null
            storage.planningBuildingStock(town, hall.id) shouldBe null
            storage.planningContainer(town, hall.id, source) shouldBe null
            h.tickUntil("dispatcher publishes the first farm order during the unrelated receipt") {
                h.service.productionOrder(town, farmer) != null
            }
            val order = requireNotNull(h.service.productionOrder(town, farmer))
            order.matches(farm) shouldBe true
            order.allows("WHEAT") shouldBe true
            repeat(2) { worksites.pulse(listOf(town)) }
            worksites.plan(town, farmer) shouldBe null

            h.world.time = 6_000
            repeat(16) { worksites.pulse(listOf(town)) }
            val work = requireNotNull(worksites.plan(town, farmer))
            work.focus shouldBe ripe
            shouldThrow<CivsRejection> { worksites.validate(town, work) }.reason shouldBe "city-busy"
            shouldThrow<CivsRejection> {
                h.service.inventoryMutation(town.id, { error("A second receipt reached native preparation") }) { city, inventory ->
                    city.copy(inventory = inventory)
                }
            }.reason shouldBe "city-busy"
            deposit.isDone shouldBe false
            barrel.getItem(0) shouldBe null
            player.inventory.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 4)
            h.service.settlements().single() shouldBe town
            fieldBefore.forEach { (point, before) -> h.world.getBlockAt(point.x, point.y, point.z).blockData.asString shouldBe before }
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
            verify(exactly = 0) { h.store.completeInventoryTransaction(any()) }

            admission.complete(requireNotNull(held))
            val after = h.await(deposit)
            after shouldBe town.copy(revision = town.revision + 1)
            barrel.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 4)
            player.inventory.getItem(0) shouldBe null
            barrel.getItem(1) shouldBe ItemStack(Material.WHEAT, 64)
            worksites.plan(after, farmer) shouldBe work
            worksites.validate(after, work)
            h.world.getBlockAt(ripe.x, ripe.y, ripe.z).type = Material.STONE
            shouldThrow<CivsRejection> { worksites.validate(after, work) }.reason shouldBe "stale-worksite"
        }
    }

    test("autonomous production requires entrance arrival and a durable unique trip receipt without physical inventory") {
        serviceScenario { h ->
            val hall = h.city.buildings.first().copy(control = CivPosition(0, 64, 0))
            val quarry = h.city.buildings.last().copy(type = BuildingType.QUARRY,
                control = CivPosition(10, 64, 0))
            val worker = h.city.workers.first().copy(assignment = quarry.id, nextDeliveryAt = 1)
            val village = h.city.copy(autonomy = CivAutonomy(), buildings = listOf(hall, quarry),
                workers = listOf(worker))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(village))
            h.start()
            val deliver = CivsService::class.java.getDeclaredMethod("deliver", UUID::class.java, UUID::class.java,
                UUID::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE, UUID::class.java, java.lang.Long.TYPE)
                .also { it.isAccessible = true }
            fun complete() = deliver.invokeService(h.service, village.id, worker.id, quarry.id, worker.completedCycles,
                worker.nextDeliveryAt, null, h.time) as CompletableFuture<*>
            val token = CitizenToken(quarry.id, worker.completedCycles, worker.nextDeliveryAt, null)
            every { h.citizens.requireAutonomousDelivery(village.id, worker.id, token) } throws CivsRejection("route-unreachable")
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { complete().join() })?.reason shouldBe "route-unreachable"
            h.saves shouldBe emptyList()
            every { h.citizens.requireAutonomousDelivery(village.id, worker.id, token) } returns requireNotNull(citizenPlan(village, worker))
            val delivered = complete()
            delivered.isDone shouldBe false
            h.service.settlements().single().stock shouldBe village.stock
            h.commit(); delivered.join()
            val saved = h.service.settlements().single()
            saved.stock shouldBe village.stock.copy(stone = village.stock.stone + CivsRules.deliveryAmount(quarry))
            saved.workers.single().completedCycles shouldBe 1L
            saved.inventory shouldBe null
            saved.workers.single().items shouldBe null
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { complete().join() })?.reason shouldBe "stale-delivery"
            h.saves.size shouldBe 1
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
            verify(exactly = 0) { h.citizens.requireStorageArrival(any(), any(), any()) }
        }
    }

    test("pending autonomous goods require arrival and unload once without another production reward") {
        serviceScenario { h ->
            val quarry = h.city.buildings.last().copy(type = BuildingType.QUARRY, control = CivPosition(10, 64, 0))
            val worker = h.city.workers.first().copy(assignment = quarry.id, nextDeliveryAt = 1,
                pendingStock = Stock(stone = 32))
            val village = h.city.copy(autonomy = CivAutonomy(), stock = Stock(),
                buildings = listOf(h.city.buildings.first(), quarry), workers = listOf(worker))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(village))
            h.start()
            val deliver = CivsService::class.java.getDeclaredMethod("deliver", UUID::class.java, UUID::class.java,
                UUID::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE, UUID::class.java, java.lang.Long.TYPE)
                .also { it.isAccessible = true }
            val token = CitizenToken(quarry.id, worker.completedCycles, worker.nextDeliveryAt, null)
            fun complete() = deliver.invokeService(h.service, village.id, worker.id, quarry.id, worker.completedCycles,
                worker.nextDeliveryAt, null, h.time) as CompletableFuture<*>
            every { h.citizens.pendingStockDelivery(village.id, worker.id) } returns worker.pendingStock
            every { h.citizens.requirePendingStockDelivery(village.id, worker.id, token) } throws CivsRejection("route-unreachable")
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { complete().join() })?.reason shouldBe "route-unreachable"
            h.saves shouldBe emptyList()
            every { h.citizens.requirePendingStockDelivery(village.id, worker.id, token) } returns
                CivPendingStockDelivery(requireNotNull(worker.pendingStock), village.buildings.first().id)
            val delivery = complete()
            delivery.isDone shouldBe false
            h.service.settlements().single() shouldBe village
            h.commit(); delivery.join()
            val saved = h.service.settlements().single()
            saved.stock shouldBe Stock(stone = 32)
            saved.workers.single().let {
                it.pendingStock shouldBe null
                it.completedCycles shouldBe worker.completedCycles
                it.skills shouldBe worker.skills
            }
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { complete().join() })?.reason shouldBe "stale-delivery"
            h.saves.size shouldBe 1
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()); h.citizens.requireAutonomousDelivery(any(), any(), any()) }
        }
    }

    test("autonomous delivery rechecks observed arrival after waiting for another city revision") {
        serviceScenario { h ->
            val quarry = h.city.buildings.last().copy(type = BuildingType.QUARRY,
                control = CivPosition(10, 67, 0))
            val worker = h.city.workers.first().copy(assignment = quarry.id, nextDeliveryAt = 1)
            val village = h.city.copy(autonomy = CivAutonomy(), buildings = listOf(h.city.buildings.first(), quarry),
                workers = listOf(worker))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(village))
            h.start()
            val unrelated: (Settlement) -> Settlement = { it.copy(stock = it.stock.copy(stone = it.stock.stone + 1),
                revision = it.revision + 1) }
            val pending = h.service.mutate(village.id, transform = unrelated)
            val deliver = CivsService::class.java.getDeclaredMethod("deliver", UUID::class.java, UUID::class.java,
                UUID::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE, UUID::class.java, java.lang.Long.TYPE)
                .also { it.isAccessible = true }
            val token = CitizenToken(quarry.id, worker.completedCycles, worker.nextDeliveryAt, null)
            every { h.citizens.requireAutonomousDelivery(village.id, worker.id, token) } returns requireNotNull(citizenPlan(village, worker))
            val queued = deliver.invokeService(h.service, village.id, worker.id, quarry.id, worker.completedCycles,
                worker.nextDeliveryAt, null, h.time) as CompletableFuture<*>
            queued.isDone shouldBe false
            verify(exactly = 0) { h.citizens.requireAutonomousDelivery(any(), any(), any()) }
            // The resident leaves the endpoint while the unrelated revision is still being persisted.
            every { h.citizens.requireAutonomousDelivery(village.id, worker.id, token) } throws CivsRejection("route-unreachable")
            h.commit(); pending.join()
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { queued.join() })?.reason shouldBe "route-unreachable"
            verify(exactly = 1) { h.citizens.requireAutonomousDelivery(village.id, worker.id, token) }
            h.saves.size shouldBe 1
            h.service.settlements().single().stock shouldBe village.stock.copy(stone = village.stock.stone + 1)
            h.service.settlements().single().workers.single().completedCycles shouldBe 0L
        }
    }

    test("Auto field scenery applies successive native actions without saving or replaying the old work") {
        serviceScenario(nativeGeometry = true) { h ->
            val farm = spatialBuilding("medievaloak", "farmer", 1, CivPosition(30, 63, 0))
            val worker = h.city.workers.first().copy(assignment = farm.id, nextDeliveryAt = 1)
            val village = h.city.copy(autonomy = CivAutonomy(), inventory = null,
                buildings = listOf(h.city.buildings.first(), farm), workers = listOf(worker))
            for (x in -1..4) for (z in -3..1) h.world.loadChunk(x, z)
            CivsWorksiteLayouts.field(farm).forEach { soil ->
                h.world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=7]")
                h.world.getBlockAt(soil.x, soil.y + 1, soil.z).blockData = Bukkit.createBlockData("minecraft:wheat[age=1]")
            }
            CivsWorksiteLayouts.irrigation(farm).forEach { h.world.getBlockAt(it.x, it.y, it.z).type = Material.WATER }
            val crop = CivsWorksiteLayouts.field(farm).first().let { it.copy(y = it.y + 1) }
            h.world.getBlockAt(crop.x, crop.y, crop.z).type = Material.AIR
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(village))
            h.start()
            val worksites = CivsService::class.java.getDeclaredField("worksites").also { it.isAccessible = true }
                .get(h.service) as CivsWorksites
            // Keep the native planning budget independent of host and MockK speed.
            CivsWorksites::class.java.getDeclaredField("nanoTime").also { it.isAccessible = true }
                .set(worksites, { 0L })
            repeat(16) { worksites.pulse(listOf(village)) }
            val work = requireNotNull(worksites.plan(village, worker))
            every { h.citizens.autonomousVisualWork(village.id, worker.id) } returns work
            val deliver = CivsService::class.java.getDeclaredMethod("deliver", UUID::class.java, UUID::class.java,
                UUID::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE, UUID::class.java, java.lang.Long.TYPE)
                .also { it.isAccessible = true }
            fun complete(cycle: Long = worker.completedCycles) = deliver.invokeService(h.service, village.id, worker.id,
                farm.id, cycle, worker.nextDeliveryAt, null, h.time) as CompletableFuture<*>
            val pending = complete()
            h.saves shouldBe emptyList()
            h.await(pending) shouldBe village
            work.changes.forEach { cell ->
                h.world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.after
            }
            verify(exactly = 1) { h.citizens.requireWorldWorkArrival(village.id, worker.id, work) }
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { complete(worker.completedCycles + 1).join() })
                ?.reason shouldBe "stale-delivery"
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { complete().join() })?.reason shouldBe "stale-worksite"
            h.world.getBlockAt(crop.x, crop.y, crop.z).blockData.asString shouldBe "minecraft:wheat[age=0]"

            val planted = h.world.getBlockAt(crop.x, crop.y, crop.z)
            planted.blockData = Bukkit.createBlockData("minecraft:wheat[age=7]")
            worksites.changed(village.worldId, crop)
            repeat(16) { worksites.pulse(listOf(village)) }
            val nextWork = requireNotNull(worksites.plan(village, worker))
            nextWork.changes.single() shouldBe CivPlanBlock(crop, "minecraft:wheat[age=7]", "minecraft:wheat[age=0]")
            every { h.citizens.autonomousVisualWork(village.id, worker.id) } returns nextWork
            h.await(complete()) shouldBe village
            planted.blockData.asString shouldBe "minecraft:wheat[age=0]"
            h.service.settlements().single() shouldBe village
            verify(exactly = 1) { h.citizens.requireWorldWorkArrival(village.id, worker.id, nextWork) }
            verify(exactly = 0) { h.store.save(any()); h.store.saveInventoryTransaction(any()); h.store.deleteInventoryTransaction(any()) }
        }
    }

    test("Auto quarry visual work still saves its new cursor before acknowledgement") {
        serviceScenario(nativeGeometry = true, serverFixture = CivsWorksiteServerFixture()) { h ->
            val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
            val worker = spatialWorker(mine, "IRON_PICKAXE", supplies = false)
            val before = spatialCity(h.world.uid, listOf(mine), worker).copy(owner = h.owner,
                autonomy = CivAutonomy(), inventory = null)
            val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
            val ladder = CivsWorksiteLayouts.point(mine, geometry.bottom)
            for (x in -3..3) for (z in -3..2) h.world.loadChunk(x, z)
            for (y in ladder.y..65) {
                h.world.getBlockAt(ladder.x, y, ladder.z).blockData =
                    Bukkit.createBlockData("minecraft:ladder[facing=${geometry.facing},waterlogged=false]")
            }
            every { h.worldAdapter.canMine(any()) } returns true
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(before))
            h.start()
            val worksites = CivsService::class.java.getDeclaredField("worksites").also { it.isAccessible = true }
                .get(h.service) as CivsWorksites
            CivsWorksites::class.java.getDeclaredField("nanoTime").also { it.isAccessible = true }
                .set(worksites, { 0L })
            repeat(64) { if (worksites.plan(before, worker) == null) worksites.pulse(listOf(before)) }
            val work = withClue(worksites.debug()) { requireNotNull(worksites.plan(before, worker)) }
            val nextStep = requireNotNull(work.miningStep)
            every { h.citizens.autonomousVisualWork(before.id, worker.id) } returns work
            val deliver = CivsService::class.java.getDeclaredMethod("deliver", UUID::class.java, UUID::class.java,
                UUID::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE, UUID::class.java, java.lang.Long.TYPE)
                .also { it.isAccessible = true }
            val pending = deliver.invokeService(h.service, before.id, worker.id, mine.id, worker.completedCycles,
                worker.nextDeliveryAt, null, h.time) as CompletableFuture<*>
            if (pending.isCompletedExceptionally) pending.join()
            val saved = h.saves.single().first
            pending.isDone shouldBe false
            h.service.settlements().single() shouldBe before
            work.changes.forEach { cell ->
                h.world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.after
            }
            requireNotNull(saved.minePlans?.get(mine.id.toString())).nextStep shouldBe nextStep
            saved.copy(minePlans = before.minePlans, revision = before.revision) shouldBe before
            saved.revision shouldBe before.revision + 1
            h.commit()
            h.await(pending) shouldBe saved
            verify(exactly = 1) { h.citizens.requireWorldWorkArrival(before.id, worker.id, work) }
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    for (alreadySaved in listOf(false, true))
    test("legacy Auto scenery retirement waits for disk without replaying native or city state saved=$alreadySaved") {
        serviceScenario { h ->
            val (fixture, work) = visualReceiptFixture("miner")
            val before = fixture.copy(worldId = h.world.uid, owner = h.owner)
            val receipt = autonomousVisualTransaction(before, work).validated()
            val current = (if (alreadySaved) receipt.after else before).let {
                it.copy(autonomy = requireNotNull(it.autonomy).copy(paused = true))
            }
            val cell = work.changes.single()
            h.world.loadChunk(cell.position.x shr 4, cell.position.z shr 4)
            val block = h.world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
            block.type = Material.DIAMOND_BLOCK // An obsolete scenery receipt must not undo later native edits.
            val retirement = CompletableFuture<Boolean>()
            every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(receipt))
            every { h.store.deleteInventoryTransaction(receipt.id) } returns retirement
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(current))
            val started = h.service.start()
            h.tick()
            started.isDone shouldBe false
            verify(exactly = 0) { h.store.load(); h.store.save(any()) }
            block.type shouldBe Material.DIAMOND_BLOCK
            retirement.complete(true)
            h.await(started) shouldBe Unit
            h.service.ready() shouldBe true
            h.service.settlements().single() shouldBe current
            val overview = h.service.debugState("overview", current.id, null)
            overview["inventory_recovery_total"] shouldBe 0
            overview["blocked_recovery_total"] shouldBe 0
            ((overview["cities"] as List<*>).single() as Map<*, *>)["unhealthy"] shouldBe false
            block.type shouldBe Material.DIAMOND_BLOCK
            h.saves shouldBe emptyList()
            verify(exactly = 1) { h.store.deleteInventoryTransaction(receipt.id) }
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()); h.citizens.requireWorldWorkArrival(any(), any(), any()) }
        }
    }

    test("retiring legacy Auto scenery never discards an ordinary empty inventory receipt") {
        serviceScenario { h ->
            val (fixture, work) = visualReceiptFixture("miner")
            val before = fixture.copy(worldId = h.world.uid)
            val cosmetic = autonomousVisualTransaction(before, work).validated()
            val personal = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL), workers = emptyList())
            val ordinary = CivInventoryTransaction(UUID.randomUUID(), personal.id, personal.revision,
                personal.copy(revision = personal.revision + 1), emptyList()).validated()
            every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(cosmetic, ordinary))
            every { h.store.deleteInventoryTransaction(cosmetic.id) } returns CompletableFuture.completedFuture(true)
            every { h.store.deleteInventoryTransaction(ordinary.id) } returns CompletableFuture.completedFuture(true)
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(personal,
                before.copy(autonomy = requireNotNull(before.autonomy).copy(paused = true))))
            h.start()
            h.tickUntil("ordinary receipt still follows inventory recovery") { h.saves.any { it.first.id == personal.id } }
            h.saves.single { it.first.id == personal.id }.first shouldBe ordinary.after
            verify(exactly = 1) { h.store.deleteInventoryTransaction(cosmetic.id) }
            verify(exactly = 0) { h.store.deleteInventoryTransaction(ordinary.id) }
            h.saves.single { it.first.id == personal.id }.let { it.second.complete(it.first) }; h.tick()
            verify(exactly = 1) { h.store.deleteInventoryTransaction(ordinary.id) }
        }
    }

    test("legacy Auto scenery retirement reports real file failures before loading cities") {
        serviceScenario { h ->
            val (city, work) = visualReceiptFixture("miner")
            val receipt = autonomousVisualTransaction(city, work).validated()
            every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(receipt))
            every { h.store.deleteInventoryTransaction(receipt.id) } returns
                CompletableFuture.failedFuture(java.io.IOException("Cannot retire scenery journal"))
            val started = h.service.start()
            h.tick()
            started.isCompletedExceptionally shouldBe true
            h.service.ready() shouldBe false
            h.service.unavailableReason() shouldBe "storage-unavailable"
            verify(exactly = 0) { h.store.load(); h.store.save(any()) }
        }
    }

    test("a forged value-bearing visual receipt is never retired as scenery") {
        serviceScenario { h ->
            val (city, work) = visualReceiptFixture("farmer")
            val original = autonomousVisualTransaction(city, work)
            val forged = original.copy(autonomousVisual = requireNotNull(original.autonomousVisual).copy(
                work = work.copy(inputs = mapOf("WHEAT_SEEDS" to 1L))))
            every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(forged))
            val started = h.service.start()
            h.tick()
            started.isCompletedExceptionally shouldBe true
            h.service.ready() shouldBe false
            verify(exactly = 0) { h.store.deleteInventoryTransaction(any()); h.store.load() }
        }
    }

    test("stale Auto native preflight clears its admitted work so another farmer can replan without a payout") {
        serviceScenario(nativeGeometry = true) { h ->
            val farm = spatialBuilding("medievaloak", "farmer", 1, CivPosition(30, 63, 0))
            val worker = h.city.workers.first().copy(assignment = farm.id, nextDeliveryAt = 1)
            val village = h.city.copy(autonomy = CivAutonomy(), inventory = null,
                buildings = listOf(h.city.buildings.first(), farm), workers = listOf(worker))
            for (x in -1..4) for (z in -3..1) h.world.loadChunk(x, z)
            CivsWorksiteLayouts.field(farm).forEach { soil ->
                h.world.getBlockAt(soil.x, soil.y, soil.z).blockData = org.bukkit.Bukkit.createBlockData("minecraft:farmland[moisture=7]")
                h.world.getBlockAt(soil.x, soil.y + 1, soil.z).blockData = org.bukkit.Bukkit.createBlockData("minecraft:wheat[age=1]")
            }
            CivsWorksiteLayouts.irrigation(farm).forEach { h.world.getBlockAt(it.x, it.y, it.z).type = Material.WATER }
            val crop = CivsWorksiteLayouts.field(farm).first().let { it.copy(y = it.y + 1) }
            h.world.getBlockAt(crop.x, crop.y, crop.z).type = Material.AIR
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(village))
            h.start()
            val worksites = CivsService::class.java.getDeclaredField("worksites").also { it.isAccessible = true }
                .get(h.service) as CivsWorksites
            // This receipt scenario is independent of host/MockK speed, like the native-application fixture above.
            CivsWorksites::class.java.getDeclaredField("nanoTime").also { it.isAccessible = true }
                .set(worksites, { 0L })
            repeat(16) { worksites.pulse(listOf(village)) }
            val work = requireNotNull(worksites.plan(village, worker))
            every { h.citizens.autonomousVisualWork(village.id, worker.id) } returns work
            h.world.getBlockAt(crop.x, crop.y, crop.z).blockData = org.bukkit.Bukkit.createBlockData("minecraft:wheat[age=0]")
            val deliver = CivsService::class.java.getDeclaredMethod("deliver", UUID::class.java, UUID::class.java,
                UUID::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE, UUID::class.java, java.lang.Long.TYPE)
                .also { it.isAccessible = true }
            val rejected = deliver.invokeService(h.service, village.id, worker.id, farm.id, worker.completedCycles,
                worker.nextDeliveryAt, null, h.time) as CompletableFuture<*>
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { rejected.join() })?.reason shouldBe "stale-worksite"
            worksites.plan(village, worker) shouldBe null
            h.saves shouldBe emptyList()
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
            val overview = h.service.debugState("overview", village.id, null)
            overview["inventory_recovery_total"] shouldBe 0
            ((overview["cities"] as List<*>).single() as Map<*, *>)["unhealthy"] shouldBe false
            h.service.settlements().single().stock shouldBe village.stock
            h.world.getBlockAt(crop.x, crop.y, crop.z).type = Material.AIR
            repeat(16) { worksites.pulse(listOf(village)) }
            requireNotNull(worksites.plan(village, worker)).changes.single().position shouldBe crop
        }
    }
})

class CivsQuarryStaffingRecoveryTest : FunSpec({
    for (persisted in listOf(false, true)) for (nativeAfter in listOf(false, true))
    test("legacy quarry staff wait for exact receipt retirement before one staffing revision persisted=$persisted nativeAfter=$nativeAfter") {
        serviceScenario(nativeGeometry = true, serverFixture = CivsWorksiteServerFixture()) { h ->
            val original = quarryStaffingCity(h.world.uid).copy(owner = h.owner)
            val extra = CivsEquipment.collectSupplies(original.workers[1], mapOf("COAL" to 4L))
            val before = original.copy(workers = original.workers.map { if (it.id == extra.id) extra else it })
            val returned = CivsEquipment.consume(extra, "COAL", 4)
            val after = before.copy(workers = before.workers.map { if (it.id == extra.id) returned else it },
                revision = before.revision + 1)
            val control = requireNotNull(before.buildings.first { it.type == BuildingType.QUARRY }.control)
            h.world.loadChunk(control.x shr 4, control.z shr 4)
            h.world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
            val barrel = (h.world.getBlockAt(control.x, control.y, control.z).state as org.bukkit.block.Barrel).inventory
            if (nativeAfter) barrel.setItem(0, ItemStack(Material.COAL, 4))
            val receipt = CivInventoryTransaction(UUID.randomUUID(), before.id, before.revision, after,
                listOf(CivInventorySlotChange(control, "BARREL", 0, null, CivPlainItem("COAL", 4)))).validated()
            val retirement = CompletableFuture<Boolean>()
            var retiring = false
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(if (persisted) after else before))
            every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(receipt))
            every { h.store.deleteInventoryTransaction(receipt.id) } answers { retiring = true; retirement }
            every { h.citizens.canReassign(any(), any()) } returns true
            h.start()
            h.service.residentSettlements() shouldBe emptyList()
            if (!persisted) {
                h.tickUntil("exact old receipt city write") { h.saves.isNotEmpty() }
                h.saves.single().first shouldBe receipt.after
                h.commit()
            }
            h.tickUntil("exact old receipt deletion") { retiring }
            val receiptWrites = if (persisted) 0 else 1
            h.paper.performTicks(40)
            h.saves.size shouldBe receiptWrites
            h.service.settlements().single() shouldBe receipt.after
            h.service.residentSettlements() shouldBe emptyList()
            barrel.getItem(0) shouldBe ItemStack(Material.COAL, 4)
            retirement.complete(true)
            h.tickUntil("one staffing write after old receipt retirement") { h.saves.size == receiptWrites + 1 }
            val normalized = h.saves.last().first
            normalized shouldBe CivsRules.reconcileQuarryStaffing(receipt.after, h.time)
            normalized.revision shouldBe receipt.after.revision + 1
            normalized.workers.first() shouldBe receipt.after.workers.first()
            normalized.workers.drop(1).zip(receipt.after.workers.drop(1)).forEach { (worker, originalWorker) ->
                worker shouldBe originalWorker.copy(assignment = null, nextDeliveryAt = maxOf(h.time, originalWorker.nextDeliveryAt + 1))
            }
            h.service.settlements().single() shouldBe receipt.after
            h.commit()
            h.service.settlements().single() shouldBe normalized
            h.service.residentSettlements() shouldBe listOf(normalized)
            h.paper.performTicks(40)
            h.saves.size shouldBe receiptWrites + 1
            barrel.getItem(0) shouldBe ItemStack(Material.COAL, 4)
            verify(exactly = 1) { h.store.deleteInventoryTransaction(receipt.id) }
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("existing output and an accepted resident transfer postpone staffing reconciliation") {
        serviceScenario { h ->
            val original = quarryStaffingCity(h.world.uid).copy(owner = h.owner)
            val extra = CivsEquipment.collectSupplies(original.workers[1], mapOf("COAL" to 4L))
            val loaded = original.copy(workers = original.workers.map { if (it.id == extra.id) extra else it })
            val quarry = loaded.buildings.single { it.type == BuildingType.QUARRY }
            every { h.worldAdapter.captureMineOpening(loaded, quarry, any()) } returns CompletableFuture.completedFuture(emptyList())
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(loaded))
            every { h.citizens.canReassign(any(), any()) } returns true
            h.start()
            h.paper.performTicks(40)
            verify(exactly = 1) { h.worldAdapter.captureMineOpening(loaded, quarry, any()) }
            h.saves shouldBe emptyList()
            h.service.settlements().single() shouldBe loaded
            h.service.residentSettlements() shouldBe listOf(loaded)
            val drained = original.copy(revision = loaded.revision + 1)
            val saved = h.service.mutate(loaded.id) { drained }
            every { h.citizens.canReassign(loaded.id, extra.id) } returns false
            h.commit(); saved.join() shouldBe drained
            h.paper.performTicks(40)
            h.saves.size shouldBe 1
            every { h.citizens.canReassign(loaded.id, extra.id) } returns true
            h.tickUntil("drained available residents reconcile") { h.saves.size == 2 }
            h.saves.last().first shouldBe CivsRules.reconcileQuarryStaffing(drained, h.time)
            h.commit()
        }
    }

    test("unknown physical pending stock remains assigned intact instead of becoming orphaned") {
        serviceScenario(nativeGeometry = true, serverFixture = CivsWorksiteServerFixture()) { h ->
            val base = quarryStaffingCity(h.world.uid, autonomous = true).copy(owner = h.owner,
                autonomy = CivAutonomy(paused = true))
            val loaded = base.copy(workers = base.workers.mapIndexed { index, worker ->
                if (index == 1) worker.copy(pendingStock = Stock(stone = 3)) else worker
            })
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(loaded))
            every { h.citizens.canReassign(any(), any()) } returns true
            loaded.buildings.mapNotNull { it.control }.forEach { control ->
                h.world.loadChunk(control.x shr 4, control.z shr 4)
                h.world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
            }
            val messages = mutableListOf<String>()
            val handler = object : Handler() {
                override fun publish(record: LogRecord) { messages += record.message }
                override fun flush() = Unit
                override fun close() = Unit
            }
            h.plugin.logger.addHandler(handler)
            try {
                h.start(); h.paper.performTicks(60)
                h.saves shouldBe emptyList()
                h.service.settlements().single() shouldBe loaded
                messages.count { "quarry_staffing_waiting" in it && "physical-pending-stock" in it } shouldBe 1
            } finally { h.plugin.logger.removeHandler(handler) }
        }
    }
})

class CivsMineFallbackAuthorityTest : FunSpec({
    test("fallback Lands revoked while its journal ACK is pending prevents the item and tool transition") {
        serviceScenario(nativeGeometry = true) { h ->
            val fixture = ServiceMineFallback(h)
            val ack = CompletableFuture<CivInventoryTransaction>()
            var tx: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { tx = firstArg(); ack }
            h.start()
            val result = fixture.submit()
            h.saves shouldBe emptyList()
            every { h.worldAdapter.canModify(any(), any(), any()) } returns false
            ack.complete(requireNotNull(tx))
            h.tickUntil("fallback authority after ACK") { result.isDone || h.saves.isNotEmpty() }
            h.saves shouldBe emptyList()
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { result.join() })?.reason shouldBe "inventory-conflict"
            h.service.settlements().single() shouldBe fixture.city
            verify(exactly = 0) { h.store.deleteInventoryTransaction(any()) }
        }
    }

    test("allowed fallback ACK publishes its frozen output and one tool use exactly once") {
        serviceScenario(nativeGeometry = true) { h ->
            val fixture = ServiceMineFallback(h)
            var tx: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { tx = firstArg(); CompletableFuture.completedFuture(firstArg()) }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            val result = fixture.submit()
            h.tickUntil("allowed fallback credit") { h.saves.isNotEmpty() || result.isDone }
            h.saves.size shouldBe 1
            val accepted = requireNotNull(tx)
            accepted.mine!!.workerBefore shouldBe fixture.worker
            accepted.mine.before(accepted.after) shouldBe fixture.city
            accepted.changes shouldBe emptyList()
            accepted.worldChanges.orEmpty() shouldBe emptyList()
            val directory = java.nio.file.Files.createTempDirectory("fallback-proof-codec-")
            try {
                writeLegacyInventoryReceipt(directory, accepted)
                CivsStore(directory, Executor(Runnable::run)).use { it.loadInventoryTransactions().join().single() shouldBe accepted }
            } finally { directory.toFile().deleteRecursively() }
            h.commit()
            val after = result.join()
            after.minePlans shouldBe fixture.city.minePlans
            after.mineProgress shouldBe fixture.city.mineProgress
            after.stock shouldBe Stock()
            after.workers.single().completedCycles shouldBe 1L
            CivsEquipment.plainCount(after.workers.single(), "COBBLESTONE") shouldBe 1
            (CivsEquipment.slots(after.workers.single())[4]!!.stack().itemMeta as org.bukkit.inventory.meta.Damageable).damage shouldBe 1
            verify(exactly = 1) { h.store.deleteInventoryTransaction(accepted.id) }
        }
    }

    for (persisted in listOf(false, true)) test("fallback recovery after city persisted=$persisted needs no actor cache and never credits twice") {
        serviceScenario(nativeGeometry = true) { h ->
            val fixture = ServiceMineFallback(h)
            val receipt = fixture.receipt()
            every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(receipt))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(if (persisted) receipt.after else fixture.city))
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            if (persisted) every { h.worldAdapter.canModify(any(), any(), any()) } returns false
            h.start()
            if (!persisted) {
                h.tickUntil("fallback recovery save") { h.saves.isNotEmpty() }
                h.commit()
            }
            h.tickUntil("fallback proof retirement") { h.service.debugState("overview", fixture.city.id, null)["inventory_recovery_total"] == 0 }
            val after = h.service.settlements().single()
            after shouldBe receipt.after
            CivsEquipment.plainCount(after.workers.single(), "COBBLESTONE") shouldBe 1
            (CivsEquipment.slots(after.workers.single())[4]!!.stack().itemMeta as org.bukkit.inventory.meta.Damageable).damage shouldBe 1
            h.saves.size shouldBe if (persisted) 0 else 1
            verify(exactly = 1) { h.store.deleteInventoryTransaction(receipt.id) }
            verify(exactly = 0) { h.citizens.requireStorageArrival(any(), any(), any()) }
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("unpublished fallback recovery with a missing native barrel remains pending without an output") {
        serviceScenario(nativeGeometry = true) { h ->
            val fixture = ServiceMineFallback(h)
            val receipt = fixture.receipt()
            every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(receipt))
            h.world.getBlockAt(10, 64, 0).type = Material.AIR
            h.start()
            h.tickUntil("fallback blocked recovery") {
                h.service.debugState("overview", fixture.city.id, null)["blocked_recovery_total"] == 1
            }
            h.saves shouldBe emptyList()
            h.service.settlements().single() shouldBe fixture.city
            verify(exactly = 0) { h.store.deleteInventoryTransaction(any()) }
        }
    }

    test("a fallback ACK in an unloaded chunk retries after load without issuing a second job") {
        serviceScenario(nativeGeometry = true) { h ->
            val fixture = ServiceMineFallback(h)
            val ack = CompletableFuture<CivInventoryTransaction>()
            var tx: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { tx = firstArg(); ack }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            val result = fixture.submit()
            h.world.unloadChunk(0, 0)
            ack.complete(requireNotNull(tx))
            h.tickUntil("fallback unload retry") { result.isDone }
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { result.join() })?.reason shouldBe "inventory-recovery"
            h.saves shouldBe emptyList()
            h.service.debugState("overview", fixture.city.id, null)["blocked_recovery_total"] shouldBe 0
            h.world.loadChunk(0, 0)
            h.tickUntil("loaded fallback recovery") { h.saves.isNotEmpty() }
            h.commit()
            h.service.settlements().single() shouldBe tx!!.after
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
            verify(exactly = 1) { h.store.deleteInventoryTransaction(tx!!.id) }
        }
    }

    test("fallback proof cannot mix a grant or change the accepted owner city and output") {
        serviceScenario(nativeGeometry = true) { h ->
            val fixture = ServiceMineFallback(h)
            val receipt = fixture.receipt()
            val proof = requireNotNull(receipt.mine)
            shouldThrow<IllegalArgumentException> { receipt.copy(autoSupplyBuildingId = fixture.mine.id).validated() }
            shouldThrow<IllegalArgumentException> { receipt.copy(after = receipt.after.copy(autonomy = null)).validated() }
            shouldThrow<IllegalArgumentException> { receipt.copy(mine = proof.copy(work = proof.work.copy(
                outputs = mapOf("COBBLESTONE" to 2L)))).validated() }
            shouldThrow<IllegalArgumentException> { receipt.copy(changes = listOf(CivInventorySlotChange(
                fixture.mine.control!!, "BARREL", 0, null, CivPlainItem("COBBLESTONE", 1)))).validated() }
            val storage = CivsService::class.java.getDeclaredField("storage").also { it.isAccessible = true }.get(h.service) as CivsStorage
            storage.reserve(fixture.city, receipt.id)
            shouldThrow<IllegalArgumentException> { storage.seal(receipt.copy(after = receipt.after.copy(name = "Changed"))) }
            storage.unlock(receipt.id)
        }
    }
})

private class ServiceMineFallback(private val h: ServiceScenario) {
    val mine = h.city.buildings.last().copy(type = BuildingType.QUARRY, control = CivPosition(10, 64, 0))
    val worker = h.city.workers.first().copy(assignment = mine.id, nextDeliveryAt = 1,
        items = List(CivsEquipment.SLOTS) { if (it == 4) CivResidentItem("IRON_PICKAXE", 1) else null })
    val city = CivsRules.validate(h.city.copy(autonomy = CivAutonomy(), inventory = CivInventory(CivStorageMode.PHYSICAL),
        stock = Stock(), buildings = listOf(h.city.buildings.first(), mine), workers = listOf(worker)))
    val work = CivWorldWork(worker.id, worker.completedCycles, mine.id, CivPosition(9, 64, 0), emptyList(),
        outputs = mapOf("COBBLESTONE" to 1L), durationMillis = CivsMineFallback.DURATION_MILLIS, status = "digging-mine",
        equipmentAfter = worker.items!!.toMutableList().also { slots ->
            val pickaxe = ItemStack(Material.IRON_PICKAXE)
            val damage = pickaxe.itemMeta as org.bukkit.inventory.meta.Damageable
            damage.damage = 1
            pickaxe.itemMeta = damage
            slots[4] = CivResidentItem.of(pickaxe)
        })
    private val storage = CivsService::class.java.getDeclaredField("storage").also { it.isAccessible = true }
        .get(h.service) as CivsStorage

    init {
        h.world.getBlockAt(9, 63, 0).type = Material.STONE
        h.world.getBlockAt(9, 64, 0).type = Material.AIR
        h.world.getBlockAt(9, 65, 0).type = Material.AIR
        h.world.getBlockAt(10, 64, 0).type = Material.BARREL
        every { h.worldAdapter.canMine(any()) } returns true
        every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
    }

    @Suppress("UNCHECKED_CAST")
    fun submit(): CompletableFuture<Settlement> {
        val prepare: (Settlement) -> CivStoragePlan? = { before -> storage.prepareWork(before, mine.id,
            work.storageInputs(before.workers.single()), emptyMap(), work.changes, work) }
        val transition: (Settlement, CivInventory) -> Settlement = { before, inventory ->
            CivsRules.finishWorldWork(before, work, h.time).copy(inventory = inventory)
        }
        return h.service.inventoryMutation(city.id, prepare, null, true, transition = transition)
    }

    fun receipt(): CivInventoryTransaction {
        val after = CivsRules.finishWorldWork(city, work, h.time)
        val tx = CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision, after, emptyList(),
            mine = CivMineReceipt(mine, null, null, work, workerBefore = worker))
        return com.google.gson.Gson().fromJson(com.google.gson.Gson().toJson(tx), CivInventoryTransaction::class.java).validated()
    }
}
