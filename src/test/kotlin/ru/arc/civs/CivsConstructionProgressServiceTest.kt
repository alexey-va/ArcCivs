package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.api.ArcBlockProtectionService
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicException
import ru.arc.paper.api.ArcSchematicFailure
import ru.arc.paper.api.ArcSchematicMutationResult
import ru.arc.paper.api.ArcSchematicOperation
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot

class CivsConstructionProgressServiceTest : FunSpec({
    test("the first ready row waits for carried materials without remote debit or partial journal or paste") {
        val storage = mockk<CivsStorage>(relaxed = true)
        every { storage.prepareConstructionDebitBackground(any(), any(), any()) } returns null
        every { storage.prepareDebit(any(), any()) } throws AssertionError("Construction must use its carried bag")
        serviceScenario(storageFactory = { storage }) { h ->
            h.incrementalPlacement()
            h.start()
            verify(exactly = 0) { storage.prepareConstructionDebitBackground(any(), any(), any()) }
            verify(exactly = 1) { storage.unlock(any()) }
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()); h.blueprints.paste(any(), any(), any(), any(), any()) }
            h.saves.single().first.construction!!.phase shouldBe ConstructionPhase.WAITING_MATERIALS
            h.commit()
            h.time += 5_000; h.paper.performTicks(20)
            h.service.city(h.player)!!.construction!!.phase shouldBe ConstructionPhase.WAITING_MATERIALS
            h.saves.size shouldBe 1
            verify(exactly = 0) { storage.prepareConstructionDebitBackground(any(), any(), any()) }
            verify(exactly = 0) { storage.prepareDebit(any(), any()); h.store.saveInventoryTransaction(any()) }
        }
    }

    test("missing row waits without debit and replenishment resumes only after the paid receipt commits") {
        serviceScenario { h ->
            val placement = h.incrementalPlacement()
            var receipt: CivInventoryTransaction? = null
            val durable = CompletableFuture<CivInventoryTransaction>()
            every { h.store.saveInventoryTransaction(any()) } answers {
                val tx = firstArg<CivInventoryTransaction>()
                if (tx.after.construction!!.phase == ConstructionPhase.PLACING) { receipt = tx; durable }
                else CompletableFuture.completedFuture(tx)
            }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start(); h.commit()
            h.service.city(h.player)!!.construction!!.phase shouldBe ConstructionPhase.WAITING_MATERIALS
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()); h.blueprints.paste(any(), any(), any(), any(), any()) }
            val barrel = (h.world.getBlockAt(20, 63, 0).state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, ItemStack(Material.STONE))
            val worker = h.service.city(h.player)!!.workers.first()
            val pickup = h.service.prepareResidentSupplies(placement.city.id, worker.id, worker.assignment!!)
            pickup.isDone shouldBe true
            val supplied = pickup.join()
            barrel.getItem(0) shouldBe null
            CivsEquipment.plainCount(supplied.workers.first(), "STONE") shouldBe 1
            h.service.city(h.player) shouldBe supplied
            verify(exactly = 1) {
                h.store.beginLocalInventory(any(), any())
                h.store.completeLocalInventory(any())
            }
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
            h.time += 5_000; h.paper.performTicks(20)
            CivsEquipment.plainCount(h.service.city(h.player)!!.workers.first(), "STONE") shouldBe 1
            val payment = requireNotNull(receipt)
            payment.beforeRevision shouldBe supplied.revision
            payment.changes shouldBe emptyList()
            payment.after.construction!!.phase shouldBe ConstructionPhase.PLACING
            payment.after.construction!!.payment!!.reservedBatch shouldBe 0
            durable.complete(payment); h.tick()
            barrel.getItem(0) shouldBe null
            h.service.city(h.player) shouldBe supplied
            verify(exactly = 0) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            h.commit(); h.paper.performTicks(20)
            h.service.city(h.player)!!.construction!!.materials shouldBe mapOf("OAK_PLANKS" to 1L)
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
            verify(exactly = 1) { h.store.completeInventoryTransaction(payment) }
            verify(exactly = 1) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            CivsEquipment.plainCount(h.service.city(h.player)!!.workers.first(), "STONE") shouldBe 0
            placement.pasted.complete(ArcSchematicMutationResult(1, 0, false)); h.tick(); h.commit()
            h.service.city(h.player)!!.construction!!.workDone shouldBe 1
            h.service.city(h.player)!!.construction!!.payment shouldBe CivConstructionPayment()
        }
    }

    for (chunkFailure in listOf(
        ArcSchematicException(ArcSchematicFailure.WORLD_UNAVAILABLE, "Schematic target chunk unloaded during mutation"),
        CivsRejection("chunks-unloaded"),
        CivsRejection("world-busy"),
    )) test("temporary construction world failures resume the paid row without another debit ${chunkFailure.javaClass.simpleName} ${CivsDiagnostics.rejection(chunkFailure)?.reason}") {
        serviceScenario { h ->
            val placement = h.incrementalPlacement(paid = true, stock = 7)
            h.start(); h.commit()
            placement.pasted.completeExceptionally(chunkFailure); h.tick()
            h.service.city(h.player)!!.construction!!.phase shouldBe ConstructionPhase.PLACING
            h.service.city(h.player)!!.construction!!.payment!!.reservedBatch shouldBe 0
            h.saves.size shouldBe 1
            val retryResult = CompletableFuture<ArcSchematicMutationResult>()
            val retry = mockk<ArcSchematicOperation>(relaxed = true)
            every { retry.completion } returns retryResult
            every { h.blueprints.paste(any(), any(), any(), any(), any()) } returns retry
            h.time += 4_999; h.paper.performTicks(20)
            verify(exactly = 1) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            h.time++; h.paper.performTicks(20)
            verify(exactly = 2) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            retryResult.complete(ArcSchematicMutationResult(1, 0, false)); h.tick(); h.commit()
            h.service.city(h.player)!!.construction!!.workDone shouldBe 1
            (h.world.getBlockAt(20, 63, 0).state as org.bukkit.block.Barrel).inventory.getItem(0)!!.amount shouldBe 7
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    for (reason in listOf("placement-failed", "world-busy"))
    test("a legacy placement failure gets one guarded retry after its chunk loads reason=$reason") {
        serviceScenario { h ->
            val placement = h.incrementalPlacement(phase = ConstructionPhase.PAUSED, paid = true, stock = 7)
            val recovering = placement.city.copy(construction = placement.city.construction!!.copy(failure = reason))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(recovering))
            h.start(); h.paper.performTicks(20)
            h.saves.single().first.construction!!.phase shouldBe ConstructionPhase.READY_TO_PLACE
            h.commit(); h.paper.performTicks(20); h.commit()
            verify(exactly = 1) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            h.service.city(h.player)!!.construction!!.payment!!.reservedBatch shouldBe 0
            (h.world.getBlockAt(20, 63, 0).state as org.bukkit.block.Barrel).inventory.getItem(0)!!.amount shouldBe 7
            placement.pasted.completeExceptionally(IllegalStateException("permanent native error")); h.tick(); h.commit()
            h.service.city(h.player)!!.construction!!.phase shouldBe ConstructionPhase.PAUSED
            h.time += 10_000; h.paper.performTicks(40)
            verify(exactly = 1) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("a paid row survives placement failure and retry without a second inventory charge") {
        serviceScenario { h ->
            h.incrementalPlacement(phase = ConstructionPhase.PAUSED, paid = true, stock = 7)
            h.start()
            val city = h.service.city(h.player)!!
            val retried = h.service.retryConstruction(h.player, city.id); h.tick(); h.commit(); retried.join()
            h.paper.performTicks(20)
            // Retry preserves the paid receipt, but placement still waits for its durable phase transition.
            verify(exactly = 0) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            h.saves.filter { !it.second.isDone }.single().first.construction!!.phase shouldBe ConstructionPhase.PLACING
            h.commit()
            h.service.city(h.player)!!.construction!!.payment!!.reservedBatch shouldBe 0
            (h.world.getBlockAt(20, 63, 0).state as org.bukkit.block.Barrel).inventory.getItem(0)!!.amount shouldBe 7
            verify(exactly = 1) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("builder site loads once off-thread and reload retires an unfinished geometry request") {
        serviceScenario { h ->
            val city = h.incrementalPlacement(phase = ConstructionPhase.CONSTRUCTING).city
            val order = requireNotNull(city.construction)
            val building = requireNotNull(order.plannedBuilding)
            val cell = CivPlanBlock(CivPosition(13, 63, 0), "minecraft:dirt", "minecraft:air")
            val plan = CivPlacementPlan(order.id, city.id, h.world.uid, building, listOf(
                CivPlanBlock(CivPosition(15, 64, 0), "minecraft:air", "minecraft:stone")),
                terrain = CivTerraformPlan(CivBox(cell.position, cell.position), listOf(cell), emptyList(), 63, 1, 0))
            val pending = CompletableFuture<CivPlacementPlan?>()
            every { h.store.loadPlan(order.id) } returns pending
            val construction = constructionRuntime(h.service)
            fun site() = construction.site(city.id, order.id)
            h.start()
            site() shouldBe null
            site() shouldBe null
            verify(exactly = 1) { h.store.loadPlan(order.id) }
            every { h.store.loadPlan(order.id) } returns CompletableFuture.completedFuture(plan)
            h.start()
            val current = requireNotNull(site())
            current.changedColumns shouldBe setOf(civsTerrainKey(cell.position.x, cell.position.z))
            pending.complete(plan.copy(terrain = null)); h.tick()
            site() shouldBe current
            verify(exactly = 2) { h.store.loadPlan(order.id) }
            h.service.close()
            site() shouldBe null
            verify(exactly = 2) { h.store.loadPlan(order.id) }
        }
    }

    test("paced paste waits for its live builder and accepted feedback is fenced and cannot fail a paid row") {
        serviceScenario { h ->
            val placement = h.incrementalPlacement(paid = true)
            val orderId = placement.city.construction!!.id
            var accepted: java.util.function.Consumer<ArcSchematicBlock>? = null
            var ready: java.util.function.BooleanSupplier? = null
            every { h.blueprints.paste(any(), any(), any(), any(), any()) } answers {
                accepted = arg(3); ready = arg(4); placement.operation
            }
            every { h.citizens.constructionReady(placement.city.id, orderId) } returns false
            h.start()
            accepted shouldBe null
            h.commit()
            requireNotNull(ready).asBoolean shouldBe false
            every { h.citizens.constructionReady(placement.city.id, orderId) } returns true
            ready!!.asBoolean shouldBe true
            val cell = ArcSchematicBlock(ArcSchematicPoint(15, 64, 0), "minecraft:stone", false, false)
            every { h.worldAdapter.constructionFeedback(h.world.uid, cell) } throws IllegalStateException("cosmetic failure")
            accepted!!.accept(cell)
            verify(exactly = 1) { h.citizens.constructionPlaced(placement.city.id, orderId, CivPosition(15, 64, 0), Material.STONE) }
            placement.pasted.isDone shouldBe false
            h.service.city(h.player)!!.construction!!.phase shouldBe ConstructionPhase.PLACING
            h.service.city(h.player)!!.construction!!.payment!!.reservedBatch shouldBe 0
            h.service.close()
            ready!!.asBoolean shouldBe false
            accepted!!.accept(cell)
            verify(exactly = 1) { h.worldAdapter.constructionFeedback(h.world.uid, cell) }
        }
    }

    test("an unfunded waiting city does not starve the next city's funded row") {
        serviceScenario { h ->
            val base = h.incrementalPlacement().city
            val order = base.construction!!
            val first = CivsRules.awaitMaterials(base, base.owner, order.id, 0, mapOf("STONE" to 1L))
            fun shiftedPoint(point: CivPosition) = point.copy(x = point.x + 256)
            fun shiftedBuilding(building: CivBuilding) = building.copy(anchor = shiftedPoint(building.anchor), entrance = shiftedPoint(building.entrance),
                control = building.control?.let(::shiftedPoint), bounds = CivBox(shiftedPoint(building.bounds.min), shiftedPoint(building.bounds.max)))
            val secondOrder = first.construction!!.copy(id = UUID.randomUUID(), plannedBuilding = shiftedBuilding(order.plannedBuilding!!))
            val second = CivsRules.validate(first.copy(id = UUID.randomUUID(), owner = UUID.randomUUID(), center = shiftedPoint(first.center),
                buildings = first.buildings.map(::shiftedBuilding), construction = secondOrder,
                workers = first.workers.mapIndexed { index, worker -> if (index == 0)
                    CivsEquipment.collectSupplies(worker, mapOf("STONE" to 1L)) else worker }))
            h.world.loadChunk(16, 0); h.world.loadChunk(17, 0)
            h.world.getBlockAt(276, 63, 0).type = Material.BARREL
            val barrel = (h.world.getBlockAt(276, 63, 0).state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, org.bukkit.inventory.ItemStack(Material.STONE, 1))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(first, second))
            every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            val secondPlan = requireNotNull(h.store.loadPlan(order.id).join()).let { plan -> plan.copy(id = secondOrder.id,
                cityId = second.id, building = requireNotNull(secondOrder.plannedBuilding),
                blocks = plan.blocks.map { it.copy(position = shiftedPoint(it.position)) }) }
            every { h.store.loadPlan(secondOrder.id) } returns CompletableFuture.completedFuture(secondPlan)
            every { h.blueprints.recover(secondPlan) } returns CompletableFuture.completedFuture(mockk(relaxed = true))
            every { h.worldAdapter.revalidate(any(), second.owner) } returns CompletableFuture.completedFuture(Unit)
            h.start()
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
            h.time += 5_000; h.paper.performTicks(20)
            h.saves.single().first.id shouldBe second.id
            h.saves.single().first.construction!!.phase shouldBe ConstructionPhase.PLACING
            barrel.getItem(0)!!.amount shouldBe 1
            h.service.city(h.player)!!.construction!!.phase shouldBe ConstructionPhase.WAITING_MATERIALS
        }
    }

    test("a late paid receipt after close leaves native inventory untouched for restart recovery") {
        serviceScenario { h ->
            val placement = h.incrementalPlacement(stock = 1)
            val supplied = placement.city.copy(workers = placement.city.workers.mapIndexed { index, worker ->
                if (index == 0) CivsEquipment.collectSupplies(worker, mapOf("STONE" to 1L)) else worker })
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(supplied))
            val durable = CompletableFuture<CivInventoryTransaction>()
            var receipt: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); durable }
            h.start()
            receipt!!.after.construction!!.payment!!.reservedBatch shouldBe 0
            h.service.close()
            durable.complete(receipt!!); h.tick()
            (h.world.getBlockAt(20, 63, 0).state as org.bukkit.block.Barrel).inventory.getItem(0)!!.amount shouldBe 1
            verify(exactly = 0) { h.store.save(any()); h.blueprints.paste(any(), any(), any(), any(), any()) }
        }
    }

    for (nativeApplied in listOf(false, true)) test("recovered batch receipt nativeApplied=$nativeApplied reconciles the debit once then resumes the same row") {
        serviceScenario { h ->
            val placement = h.incrementalPlacement(stock = if (nativeApplied) 0 else 1)
            val before = placement.city
            val order = before.construction!!
            // Exact pre-upgrade receipt: these materials were paid directly from the hut, before carried batches.
            val after = before.copy(revision = before.revision + 1, construction = order.copy(
                phase = ConstructionPhase.PLACING, materials = emptyMap(), payment = CivConstructionPayment(0)))
            val tx = CivInventoryTransaction(UUID.randomUUID(), before.id, before.revision, after,
                listOf(CivInventorySlotChange(CivPosition(20, 63, 0), "BARREL", 0, CivPlainItem("STONE", 1), null)))
            every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(tx))
            every { h.store.deleteInventoryTransaction(tx.id) } returns CompletableFuture.completedFuture(true)
            h.start(); h.paper.performTicks(20)
            (h.world.getBlockAt(20, 63, 0).state as org.bukkit.block.Barrel).inventory.getItem(0) shouldBe null
            h.commit(); h.paper.performTicks(20)
            h.service.city(h.player)!!.construction!!.payment!!.reservedBatch shouldBe 0
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
            verify(exactly = 1) { h.blueprints.paste(any(), any(), any(), any(), any()) }
        }
    }

    for (cursor in 0..1) test("recovered tile $cursor validates only its saved layer and preserves remodeled completed tiles") {
        serviceScenario { h ->
            val building = h.city.buildings.first().copy(id = UUID.randomUUID(), type = BuildingType.FORESTER, level = 1,
                anchor = CivPosition(15, 64, 0), bounds = CivBox(CivPosition(14, 63, -2), CivPosition(17, 68, 2)),
                entrance = CivPosition(15, 64, 3), phase = BuildingPhase.CONSTRUCTING)
            val order = CivConstruction(UUID.randomUUID(), building.id, 1, 2, cursor, ConstructionPhase.PLACING,
                Stock(), plannedBuilding = building, batches = CivConstructionBatches(2, 256), materials = mapOf("STONE" to 2L))
            val city = CivsRules.validate(h.city.copy(stock = Stock(), inventory = CivInventory.starting(Stock(12, 7, 3)),
                buildings = h.city.buildings + building, construction = order))
            val plan = CivPlacementPlan(order.id, city.id, h.world.uid, building, listOf(
                CivPlanBlock(CivPosition(15, 64, 0), "minecraft:air", "minecraft:stone"),
                CivPlanBlock(CivPosition(15, 65, 0), "minecraft:air", "minecraft:stone"),
                CivPlanBlock(CivPosition(15, 67, 0), "minecraft:air", "minecraft:air"),
            ), "b".repeat(64))
            val full = mockk<ArcSchematicSnapshot>(relaxed = true)
            val part = mockk<ArcSchematicSnapshot>(relaxed = true)
            val secondPart = mockk<ArcSchematicSnapshot>(relaxed = true)
            val operation = mockk<ArcSchematicOperation>(relaxed = true)
            val pasted = CompletableFuture<ArcSchematicMutationResult>()
            val secondOperation = mockk<ArcSchematicOperation>(relaxed = true)
            val secondPasted = CompletableFuture<ArcSchematicMutationResult>()
            var firstAccepted: java.util.function.Consumer<ArcSchematicBlock>? = null
            var secondReady: java.util.function.BooleanSupplier? = null
            val repair = mockk<ArcSchematicOperation>(relaxed = true)
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.store.loadPlan(order.id) } returns CompletableFuture.completedFuture(plan)
            every { h.blueprints.recover(plan) } returns CompletableFuture.completedFuture(full)
            every { h.blueprints.pasteParts(full, building, any()) } answers {
                arg<CivBuildBatch>(2).minimum.y shouldBe 64 + cursor
                CompletableFuture.completedFuture(listOf(part, secondPart))
            }
            every { operation.completion } returns pasted
            every { h.blueprints.paste(part, h.world.uid, building.anchor, any(), any()) } answers {
                firstAccepted = arg(3)
                operation
            }
            every { secondOperation.completion } returns secondPasted
            every { h.blueprints.paste(secondPart, h.world.uid, building.anchor, any(), any()) } answers {
                secondReady = arg(4)
                secondOperation
            }
            every { h.citizens.constructionReady(city.id, order.id) } returns true
            every { repair.completion } returns CompletableFuture<ArcSchematicMutationResult>()
            every { h.blueprints.repair(any(), any()) } returns repair
            // A filled chest in a completed tile would reject a whole-plan native revalidation.
            val remodeled = h.world.getBlockAt(15, 64, 0)
            if (cursor == 1) {
                remodeled.type = Material.CHEST
                (remodeled.state as org.bukkit.block.Container).inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.DIAMOND, 3))
            }
            CivsWorld(h.plugin, h.tasks, { 0L }) { listOf(city) }.use { nativeWorld ->
                every { h.worldAdapter.revalidate(any(), h.owner) } answers {
                    val batch = firstArg<CivPlacementPlan>()
                    batch.blocks.map { it.position.y } shouldBe listOf(64 + cursor)
                    nativeWorld.revalidate(batch, h.owner)
                }
                h.start()
                verify(exactly = 1) { h.blueprints.paste(part, h.world.uid, building.anchor, any(), any()) }
                verify(exactly = 0) { h.blueprints.paste(full, any(), any(), any(), any()); h.store.saveInventoryTransaction(any()) }
                firstAccepted!!.accept(ArcSchematicBlock(ArcSchematicPoint(15, 64 + cursor, 0), "minecraft:stone", false, false))
                pasted.complete(ArcSchematicMutationResult(1, 0, false)); h.paper.performTicks(1)
                h.saves.size shouldBe 0
                verify(exactly = 1) { h.blueprints.paste(secondPart, h.world.uid, building.anchor, any(), any()) }
                // A new native operation must retain the order's cadence across cropped parts.
                secondReady!!.asBoolean shouldBe false
                h.paper.performTicks(3)
                secondReady!!.asBoolean shouldBe false
                h.paper.performTicks(1)
                secondReady!!.asBoolean shouldBe true
                secondPasted.complete(ArcSchematicMutationResult(1, 0, false)); h.tick()
                h.service.city(h.player)!!.construction!!.workDone shouldBe cursor
                h.commit()
                h.service.city(h.player)!!.inventory shouldBe city.inventory
                if (cursor == 0) {
                    h.service.city(h.player)!!.construction!!.workDone shouldBe 1
                    h.service.city(h.player)!!.construction!!.phase shouldBe ConstructionPhase.CONSTRUCTING
                    h.paper.performTicks(80)
                    verify(exactly = 0) { h.blueprints.repair(any(), any()) }
                } else {
                    h.service.city(h.player)!!.construction shouldBe null
                    h.service.city(h.player)!!.building(building.id)!!.phase shouldBe BuildingPhase.ACTIVE
                    remodeled.type shouldBe Material.CHEST
                    (remodeled.state as org.bukkit.block.Container).inventory.getItem(0) shouldBe org.bukkit.inventory.ItemStack(Material.DIAMOND, 3)
                }
                verify(exactly = 2) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            }
        }
    }

    test("recovered PLACING validates before paste and commits without charging resources again") {
        serviceScenario { h ->
            val hall = h.city.buildings.first().copy(level = 1, phase = BuildingPhase.CONSTRUCTING)
            val orderId = UUID.randomUUID()
            // Exact legacy paid founding snapshot: the new founding API deliberately creates a SITE.
            val recovering = CivsRules.validate(Settlement(h.city.id, h.city.owner, "Recovered", h.world.uid,
                hall.entrance, 96, "nordic", CivsRules.STARTING_STOCK, listOf(hall),
                (1..4).map { CivWorker(UUID.randomUUID(), "Worker $it", null) }, construction =
                CivConstruction(orderId, hall.id, 1, 0, 0, ConstructionPhase.PLACING, Stock(), plannedBuilding = hall)))

            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(recovering))
            val plan = CivPlacementPlan(orderId, recovering.id, h.world.uid, hall,
                listOf(CivPlanBlock(hall.anchor, "minecraft:air", "minecraft:stone")), "b".repeat(64))
            val snapshot = mockk<ArcSchematicSnapshot>(relaxed = true)
            every { snapshot.blocks() } returns emptyList()
            every { h.store.loadPlan(orderId) } returns CompletableFuture.completedFuture(plan)
            every { h.blueprints.recover(plan) } returns CompletableFuture.completedFuture(snapshot)
            val validation = CompletableFuture<Unit>()
            every { h.worldAdapter.revalidate(plan, recovering.owner) } returns validation
            val pasted = CompletableFuture<ArcSchematicMutationResult>()
            val operation = mockk<ArcSchematicOperation>(relaxed = true)
            every { operation.completion } returns pasted
            every { h.blueprints.paste(snapshot, h.world.uid, hall.anchor, any(), any()) } returns operation
            val repaired = mockk<ArcSchematicOperation>(relaxed = true)
            every { repaired.completion } returns CompletableFuture<ArcSchematicMutationResult>()
            every { h.blueprints.repair(any(), any()) } returns repaired
            h.world.getChunkAt(hall.entrance.x shr 4, hall.entrance.z shr 4)
            h.start()
            verify(exactly = 0) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            validation.complete(Unit); h.tick()
            verify(exactly = 1) { h.blueprints.paste(snapshot, h.world.uid, hall.anchor, any(), any()) }
            h.saves.size shouldBe 0
            pasted.complete(ArcSchematicMutationResult(1, 0, false)); h.tick()
            h.service.city(h.player)!!.construction!!.phase shouldBe ConstructionPhase.PLACING
            h.saves.single().first.stock shouldBe CivsRules.STARTING_STOCK
            h.commit()
            h.service.city(h.player)!!.construction shouldBe null
            h.service.city(h.player)!!.buildings.single().phase shouldBe BuildingPhase.ACTIVE
            h.service.city(h.player)!!.stock shouldBe CivsRules.STARTING_STOCK
        }
    }

    for (writesBeforeRevocation in 0..1) test("Lands revocation after preflight stops write ${writesBeforeRevocation + 1} and pauses the same paid construction") {
        serviceScenario { h ->
            val placement = h.placement()
            val staffed = placement.city.copy(workers = placement.city.workers.mapIndexed { index, worker ->
                if (index == 0) worker.copy(assignment = placement.city.buildings.first().id, nextDeliveryAt = 1L) else worker
            })
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(staffed))
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            var allowed = true
            every { h.worldAdapter.canModify(h.owner, any(), any()) } answers { allowed }
            h.start()
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
            placement.validation.complete(Unit); h.tick()
            h.saves.single().first.construction!!.phase shouldBe ConstructionPhase.PLACING
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
            if (writesBeforeRevocation == 0) allowed = false
            h.commit()
            if (writesBeforeRevocation == 1) {
                protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe true
                verify(exactly = 1) { h.worldAdapter.canModify(h.owner, h.world.getBlockAt(15, 64, 0), Material.OAK_PLANKS) }
                h.world.getBlockAt(15, 64, 0).type = Material.OAK_PLANKS
                allowed = false
            }
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15 + writesBeforeRevocation, 64, 0) shouldBe false
            placement.pasted.completeExceptionally(ArcSchematicException(ArcSchematicFailure.PROTECTED_AREA, "Lands denied the next write"))
            h.tick()
            h.saves.last().first.construction!!.phase shouldBe ConstructionPhase.PAUSED
            h.commit()
            val paused = h.service.city(h.player)!!
            paused.construction!!.id shouldBe placement.city.construction!!.id
            paused.construction.failure shouldBe "protected-area"
            h.service.buildingStatus(paused.construction.buildingId) shouldBe "building-status.protected-area"
            h.service.workerStatus(staffed.workers.first().id) shouldBe "workers.status.protected-area"
            paused.stock shouldBe placement.city.stock
            h.world.getBlockAt(15, 64, 0).type shouldBe if (writesBeforeRevocation == 1) Material.OAK_PLANKS else Material.AIR
            h.world.getBlockAt(16, 64, 0).type shouldBe Material.AIR
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
            h.service.health().activeLeases shouldBe 0
        }
    }

    for (stairState in listOf("before", "after"))
    test("autonomous final write gate allows changed terrain for a $stairState stair plan") {
        serviceScenario { h ->
            val placement = h.incrementalPlacement(paid = true)
            val order = requireNotNull(placement.city.construction)
            val original = requireNotNull(h.store.loadPlan(order.id).join())
            val snapshot = h.blueprints.recover(original).join()
            val stair = "minecraft:oak_stairs[facing=north,half=top,shape=straight,waterlogged=false]"
            val before = if (stairState == "before") stair else "minecraft:stone"
            val after = if (stairState == "after") stair else "minecraft:stone"
            val plan = original.copy(blocks = original.blocks.mapIndexed { index, cell ->
                if (index == 0) cell.copy(before = before, after = after) else cell
            })
            val village = CivsRules.validate(placement.city.copy(autonomy = CivAutonomy(), inventory = null,
                stock = Stock(40, 30, 20), construction = order.copy(payment = null,
                    batches = order.batches!!.copy(atomicPairs = true),
                    cost = CivsAutonomy.cost(requireNotNull(order.plannedBuilding).type, order.targetLevel))))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(village))
            every { h.store.loadPlan(order.id) } returns CompletableFuture.completedFuture(plan)
            every { h.blueprints.recover(plan) } returns CompletableFuture.completedFuture(snapshot)
            var landsAllowed = true
            every { h.worldAdapter.canModify(h.owner, any(), any()) } answers { landsAllowed }
            val block = h.world.getBlockAt(15, 64, 0)
            block.blockData = Bukkit.createBlockData(before)
            h.start(); h.commit()
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            for (state in listOf(stair, stair.replace("straight", "outer_left"), stair.replace("straight", "inner_right"), "minecraft:stone")) {
                block.blockData = Bukkit.createBlockData(state)
                protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe true
            }
            for (state in listOf(stair.replace("north", "south"), stair.replace("top", "bottom"),
                stair.replace("false", "true"), stair.replace("oak_stairs", "stone_brick_stairs"), "minecraft:air")) {
                block.blockData = Bukkit.createBlockData(state)
                protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe true
                block.blockData.asString shouldBe state
            }
            block.blockData = Bukkit.createBlockData(stair.replace("straight", "outer_right"))
            landsAllowed = false
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
            h.service.settlements().single().stock shouldBe village.stock
        }
    }

    for (fenceState in listOf("before", "after"))
    test("autonomous final write gate allows changed terrain for a $fenceState fence plan") {
        serviceScenario { h ->
            val placement = h.incrementalPlacement(paid = true)
            val order = requireNotNull(placement.city.construction)
            val original = requireNotNull(h.store.loadPlan(order.id).join())
            val snapshot = h.blueprints.recover(original).join()
            val fence = "minecraft:spruce_fence[east=true,north=false,south=false,waterlogged=false,west=true]"
            val observed = "minecraft:spruce_fence[east=true,north=true,south=false,waterlogged=false,west=false]"
            val trapdoor = "minecraft:spruce_trapdoor[facing=east,half=bottom,open=true,powered=false,waterlogged=false]"
            val before = if (fenceState == "before") fence else trapdoor
            val after = if (fenceState == "after") fence else trapdoor
            val plan = original.copy(blocks = original.blocks.mapIndexed { index, cell ->
                if (index == 0) cell.copy(before = before, after = after) else cell
            })
            val village = CivsRules.validate(placement.city.copy(autonomy = CivAutonomy(), inventory = null,
                stock = Stock(40, 30, 20), construction = order.copy(payment = null,
                    batches = order.batches!!.copy(atomicPairs = true),
                    cost = CivsAutonomy.cost(requireNotNull(order.plannedBuilding).type, order.targetLevel))))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(village))
            every { h.store.loadPlan(order.id) } returns CompletableFuture.completedFuture(plan)
            every { h.blueprints.recover(plan) } returns CompletableFuture.completedFuture(snapshot)
            var landsAllowed = true
            every { h.worldAdapter.canModify(h.owner, any(), any()) } answers { landsAllowed }
            val block = h.world.getBlockAt(15, 64, 0)
            block.blockData = Bukkit.createBlockData(before)
            h.start(); h.commit()
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            for (state in listOf(fence, observed,
                "minecraft:spruce_fence[east=false,north=true,south=true,waterlogged=false,west=false]", trapdoor)) {
                block.blockData = Bukkit.createBlockData(state)
                protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe true
                block.blockData.asString shouldBe state
            }
            for (state in listOf(observed.replace("spruce_fence", "oak_fence"), observed.replace("waterlogged=false", "waterlogged=true"),
                "minecraft:air", trapdoor.replace("facing=east", "facing=west"), trapdoor.replace("open=true", "open=false"),
                trapdoor.replace("powered=false", "powered=true"), trapdoor.replace("half=bottom", "half=top"),
                trapdoor.replace("waterlogged=false", "waterlogged=true"))) {
                block.blockData = Bukkit.createBlockData(state)
                protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe true
                block.blockData.asString shouldBe state
            }
            block.blockData = Bukkit.createBlockData(observed)
            landsAllowed = false
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
            h.service.settlements().single().stock shouldBe village.stock
        }
    }

    for (passage in listOf("door", "fence_gate"))
    test("autonomous final write gate allows changed $passage terrain during an oak-to-spruce upgrade") {
        serviceScenario { h ->
            val placement = h.incrementalPlacement(paid = true)
            val order = requireNotNull(placement.city.construction)
            val original = requireNotNull(h.store.loadPlan(order.id).join())
            val snapshot = h.blueprints.recover(original).join()
            val before = if (passage == "door") "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]"
                else "minecraft:oak_fence_gate[facing=north,in_wall=false,open=false,powered=false]"
            val after = if (passage == "door") "minecraft:spruce_door[facing=north,half=lower,hinge=right,open=false,powered=false]"
                else "minecraft:spruce_fence_gate[facing=north,in_wall=false,open=false,powered=false]"
            val plan = original.copy(blocks = original.blocks.mapIndexed { index, cell ->
                if (index == 0) cell.copy(before = before, after = after) else cell
            })
            val village = CivsRules.validate(placement.city.copy(autonomy = CivAutonomy(), inventory = null,
                stock = Stock(40, 30, 20), construction = order.copy(payment = null,
                    batches = order.batches!!.copy(atomicPairs = true),
                    cost = CivsAutonomy.cost(requireNotNull(order.plannedBuilding).type, order.targetLevel))))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(village))
            every { h.store.loadPlan(order.id) } returns CompletableFuture.completedFuture(plan)
            every { h.blueprints.recover(plan) } returns CompletableFuture.completedFuture(snapshot)
            var landsAllowed = true
            every { h.worldAdapter.canModify(h.owner, any(), any()) } answers { landsAllowed }
            val block = h.world.getBlockAt(15, 64, 0)
            block.blockData = Bukkit.createBlockData(before)
            h.start(); h.commit()
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            for (state in listOf(before, before.replace("open=false", "open=true"),
                after, after.replace("open=false", "open=true"))) {
                block.blockData = Bukkit.createBlockData(state)
                protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe true
                block.blockData.asString shouldBe state
            }
            val drifted = listOf(before, after).flatMap { state -> listOf(
                state.replace("facing=north", "facing=south"),
                if (passage == "door") state.replace("half=lower", "half=upper") else state.replace("in_wall=false", "in_wall=true"),
                state.replace("powered=false", "powered=true"),
            ) } + (if (passage == "door") listOf(before.replace("hinge=left", "hinge=right"), after.replace("hinge=right", "hinge=left"))
                else emptyList()) + listOf(before.replace("oak_", "birch_"), "minecraft:air")
            for (state in drifted) {
                block.blockData = Bukkit.createBlockData(state)
                protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe true
                block.blockData.asString shouldBe state
            }
            block.blockData = Bukkit.createBlockData(before.replace("open=false", "open=true"))
            landsAllowed = false
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
            h.service.settlements().single().stock shouldBe village.stock
        }
    }

    test("accepted autonomous construction overwrites late terrain edits but preserves bedrock and stored items") {
        serviceScenario { h ->
            val placement = h.incrementalPlacement(paid = true)
            val order = requireNotNull(placement.city.construction)
            val village = CivsRules.validate(placement.city.copy(autonomy = CivAutonomy(), inventory = null,
                stock = Stock(40, 30, 20), construction = order.copy(payment = null,
                    batches = order.batches!!.copy(atomicPairs = true),
                    cost = CivsAutonomy.cost(requireNotNull(order.plannedBuilding).type, order.targetLevel))))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(village))
            h.start()
            h.saves.single().first.construction!!.phase shouldBe ConstructionPhase.PLACING
            val block = h.world.getBlockAt(15, 64, 0)
            block.type = Material.BRICKS
            h.commit()
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe true
            block.type shouldBe Material.BRICKS
            block.type = Material.BEDROCK
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
            block.type = Material.BARREL
            (block.state as org.bukkit.block.Barrel).inventory.setItem(0, ItemStack(Material.BREAD))
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
            block.type = Material.AIR
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe true
            block.type = Material.STONE
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe true
            h.service.settlements().single().stock shouldBe village.stock
        }
    }

    test("manual foundations reserve an empty payment and run before an unreachable builder entrance") {
        serviceScenario { h ->
            val placement = h.incrementalPlacement(paid = false)
            val old = requireNotNull(placement.city.construction)
            val building = requireNotNull(old.plannedBuilding)
            val cell = CivPlanBlock(CivPosition(15, 63, 0), "minecraft:air", "minecraft:dirt")
            val plan = CivPlacementPlan(old.id, h.city.id, h.world.uid, building, listOf(
                CivPlanBlock(CivPosition(15, 64, 0), "minecraft:air", "minecraft:stone"),
                CivPlanBlock(CivPosition(16, 64, 0), "minecraft:air", "minecraft:oak_planks")), "b".repeat(64),
                CivTerraformPlan(CivBox(cell.position, cell.position), listOf(cell), emptyList(), 63, 0, 1))
            val order = old.copy(phase = ConstructionPhase.READY_TO_PLACE, payment = CivConstructionPayment(),
                batches = CivConstructionBatches(3, 16, true), workRequired = 3, workDone = 0, workCredit = null,
                materials = mapOf("STONE" to 1L, "OAK_PLANKS" to 1L),
                automaticPreparationBatches = 1, preparationWorkplace = null, blocksRequired = 3, blocksPlaced = 0)
            val city = CivsRules.validate(placement.city.copy(construction = order))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.store.loadPlan(order.id) } returns CompletableFuture.completedFuture(plan)
            every { h.blueprints.recover(plan) } returns CompletableFuture.completedFuture(mockk(relaxed = true))
            every { h.citizens.constructionReady(city.id, order.id) } returns false
            every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            val applied = CompletableFuture<Unit>()
            var gate: (() -> Boolean)? = null
            every { h.worldAdapter.applyTerraform(plan, h.owner, listOf(cell), 0, any(), any()) } answers {
                gate = arg(4)
                applied
            }
            h.start()
            val reserved = h.saves.single().first
            reserved.construction!!.phase shouldBe ConstructionPhase.PLACING
            reserved.construction!!.payment!!.reservedBatch shouldBe 0
            reserved.construction!!.materials shouldBe order.materials
            reserved.inventory shouldBe city.inventory
            gate shouldBe null
            h.commit()
            requireNotNull(gate).invoke() shouldBe true
            applied.complete(Unit); h.tick()
            val finished = h.saves.last().first
            finished.construction!!.workDone shouldBe 1
            finished.construction!!.phase shouldBe ConstructionPhase.CONSTRUCTING
            finished.construction!!.materials shouldBe order.materials
            finished.construction!!.preparationWorkplace shouldBe building.entrance
            verify(exactly = 0) { h.citizens.constructionReady(city.id, order.id); h.blueprints.paste(any(), any(), any(), any(), any()) }
        }
    }

    for (resumedBatch in 0..1) test("autonomous earthworks resume batch $resumedBatch with actual worker admission and durable next front") {
        serviceScenario { h ->
            val placement = h.incrementalPlacement(paid = true)
            val old = requireNotNull(placement.city.construction)
            val building = requireNotNull(old.plannedBuilding)
            val cells = listOf(CivPlanBlock(CivPosition(5, 64, 0), "minecraft:dirt", "minecraft:air"),
                CivPlanBlock(CivPosition(7, 63, 0), "minecraft:air", "minecraft:cobblestone"))
            val route = (0..12).map { CivPosition(it, 64, 0) }
            val plan = CivPlacementPlan(old.id, h.city.id, h.world.uid, building, listOf(
                CivPlanBlock(CivPosition(15, 64, 0), "minecraft:air", "minecraft:stone"),
                CivPlanBlock(CivPosition(16, 64, 0), "minecraft:air", "minecraft:oak_planks")), "b".repeat(64),
                CivTerraformPlan(CivBox(CivPosition(5, 63, 0), CivPosition(7, 64, 0)), cells, route, 63, 1, 1))
            val prefix = civsTerraformBatches(plan)
            val order = old.copy(phase = ConstructionPhase.PLACING, payment = null,
                cost = CivsAutonomy.cost(building.type, old.targetLevel),
                batches = CivConstructionBatches(4, 16, true), workRequired = 4, workDone = resumedBatch, workCredit = 2,
                blocksRequired = 4, blocksPlaced = resumedBatch, preparationWorkplace = prefix[resumedBatch].workplace)
            val village = CivsRules.validate(placement.city.copy(autonomy = CivAutonomy(), inventory = null,
                stock = Stock(40, 30, 20), construction = order))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(village))
            every { h.store.loadPlan(order.id) } returns CompletableFuture.completedFuture(plan)
            val snapshot = mockk<ArcSchematicSnapshot>(relaxed = true)
            every { h.blueprints.recover(plan) } returns CompletableFuture.completedFuture(snapshot)
            var gate: (() -> Boolean)? = null
            val applied = CompletableFuture<Unit>()
            var placed: ((CivPlanBlock) -> Unit)? = null
            every { h.citizens.constructionPlaced(village.id, order.id, any(), any()) } throws IllegalStateException("cosmetic packet failure")
            every { h.worldAdapter.applyTerraform(plan, h.owner, any(), resumedBatch, any(), any()) } answers {
                arg<List<CivPlanBlock>>(2) shouldBe listOf(cells[resumedBatch])
                gate = arg(4)
                placed = arg(5)
                applied
            }
            every { h.citizens.constructionReady(village.id, order.id) } returns false
            h.start()
            verify(exactly = 1) { h.worldAdapter.applyTerraform(plan, h.owner, any(), resumedBatch, any(), any()) }
            verify(exactly = 0) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            requireNotNull(gate).invoke() shouldBe false
            every { h.citizens.constructionReady(village.id, order.id) } returns true
            requireNotNull(gate).invoke() shouldBe true
            h.saves.size shouldBe 0
            // A gesture/packet failure after a native write must not pause the paid earthwork order.
            requireNotNull(placed).invoke(cells[resumedBatch])
            applied.complete(Unit); h.tick()
            val saved = h.saves.single().first
            saved.construction!!.workDone shouldBe resumedBatch + 1
            saved.construction!!.preparationWorkplace shouldBe (prefix.getOrNull(resumedBatch + 1)?.workplace ?: route.last())
            saved.construction!!.blocksPlaced shouldBe resumedBatch + 1
            saved.stock shouldBe village.stock
            h.service.settlements().single().construction!!.workDone shouldBe resumedBatch
            // Persist before publishing; closing here simulates a restart at this durable boundary.
            h.saves.single().second.complete(saved)
            h.service.close()
        }
    }

    test("a player container filled after preflight keeps its items at the exact native write boundary") {
        // WorldEdit clears container contents before setBlock, even for the same target material.
        listOf(Material.OAK_PLANKS, Material.CHEST).forEach { target ->
            serviceScenario { h ->
                val placement = h.placement(target)
                h.start(); placement.validation.complete(Unit); h.tick(); h.commit()
                val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
                val block = h.world.getBlockAt(15, 64, 0)
                block.type = Material.CHEST
                val contents = (block.state as org.bukkit.block.Container).inventory
                contents.setItem(0, org.bukkit.inventory.ItemStack(Material.DIAMOND, 3))
                protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
                contents.getItem(0)!!.amount shouldBe 3
                contents.clear()
                protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe true
            }
        }
    }

    test("placement guards reject unloaded chunks and asynchronous calls without touching Lands or loading chunks") {
        serviceScenario { h ->
            val placement = h.placement()
            every { h.worldAdapter.canModify(h.owner, any(), any()) } returns true
            h.start(); placement.validation.complete(Unit); h.tick(); h.commit()
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            h.world.unloadChunk(1, 0)
            h.world.isChunkLoaded(1, 0) shouldBe false
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 16, 64, 0) shouldBe false
            h.world.isChunkLoaded(1, 0) shouldBe false
            val worldId = h.world.uid
            CompletableFuture.supplyAsync {
                protection.permitsSchematicMutation("arccivs-construction", worldId, 15, 64, 0)
            }.join() shouldBe false
            verify(exactly = 0) { h.worldAdapter.canModify(any(), any(), any()) }
            h.world.loadChunk(1, 0)
            protection.permitsSchematicMutation("arccivs-construction", worldId, 16, 64, 0) shouldBe true
            verify(exactly = 1) { h.worldAdapter.canModify(h.owner, h.world.getBlockAt(16, 64, 0), Material.STONE) }
        }
    }

    test("repair retains only its planned footprint and checks live neighbor materials without an unprotected fallback") {
        serviceScenario { h ->
            val placement = h.placement()
            var allowed = true
            every { h.worldAdapter.canModify(h.owner, any(), any()) } answers { allowed }
            h.start(); placement.validation.complete(Unit); h.tick(); h.commit()
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 0, 64, 0) shouldBe false
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe true
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 18, 64, 0) shouldBe false
            placement.pasted.complete(ArcSchematicMutationResult(2, 0, false)); h.tick(); h.commit()
            h.service.city(h.player)!!.construction shouldBe null
            val neighbor = h.world.getBlockAt(18, 64, 0).also { it.type = Material.OAK_FENCE }
            protection.isProtected(h.world.uid, 18, 64, 0) shouldBe false
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 18, 64, 0) shouldBe true
            verify(exactly = 1) { h.worldAdapter.canModify(h.owner, neighbor, Material.OAK_FENCE) }
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 19, 64, 0) shouldBe false
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 0, 64, 0) shouldBe false
            allowed = false
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 18, 64, 0) shouldBe false
            allowed = true
            placement.repaired.complete(ArcSchematicMutationResult(1, 0, false)); h.tick()
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 18, 64, 0) shouldBe false
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
            h.service.health().activeLeases shouldBe 0
        }
    }

    test("cancelled placement releases its guard and retains the paid paused order") {
        serviceScenario { h ->
            val placement = h.placement()
            every { h.worldAdapter.canModify(h.owner, any(), any()) } returns true
            h.start(); placement.validation.complete(Unit); h.tick(); h.commit()
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe true
            placement.pasted.complete(ArcSchematicMutationResult(1, 0, true)); h.tick(); h.commit()
            val city = h.service.city(h.player)!!
            city.construction!!.phase shouldBe ConstructionPhase.PAUSED
            city.construction.failure shouldBe "placement-cancelled"
            city.stock shouldBe placement.city.stock
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
            h.service.health().activeLeases shouldBe 0
        }
    }

    test("closing an active placement releases its guard and a late completion cannot start repair") {
        serviceScenario { h ->
            val placement = h.placement()
            every { h.worldAdapter.canModify(h.owner, any(), any()) } returns true
            h.start(); placement.validation.complete(Unit); h.tick(); h.commit()
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe true
            h.service.close()
            verify(exactly = 1) { placement.operation.cancel() }
            placement.pasted.complete(ArcSchematicMutationResult(2, 0, false)); h.tick()
            h.saves.size shouldBe 1
            verify(exactly = 0) { h.blueprints.repair(any(), any()) }
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
            h.service.health().activeLeases shouldBe 0
        }
    }
})

class CivsRoutedTerraformPaymentServiceTest : FunSpec({
    test("routed terrain reserves no items and the next authored row still consumes one carried stone") {
        serviceScenario { h ->
            val f = ServiceRoutedTerraform(h, stone = 1)
            h.start()
            f.receipts.size shouldBe 1
            f.receipts.single().after.construction!!.payment!!.reservedBatch shouldBe 0
            f.receipts.single().changes shouldBe emptyList()
            h.saves.single().first.construction!!.materials shouldBe f.order.materials
            h.saves.single().first.workers shouldBe f.city.workers
            f.gate shouldBe null
            h.commit()
            requireNotNull(f.gate).invoke() shouldBe false
            f.workerReady = true
            requireNotNull(f.gate).invoke() shouldBe true
            f.completeTerrain()
            val completedPrefix = h.saves.last().first
            completedPrefix.construction!!.workDone shouldBe 1
            completedPrefix.construction!!.workCredit shouldBe 2
            completedPrefix.construction!!.materials shouldBe f.order.materials
            completedPrefix.construction!!.blocksPlaced shouldBe 1
            completedPrefix.workers shouldBe f.city.workers
            h.commit()
            h.tickUntil("next authored row payment") { f.receipts.size == 2 }
            val stonePayment = f.receipts.last()
            stonePayment.after.construction!!.payment!!.reservedBatch shouldBe 1
            stonePayment.after.construction!!.materials shouldBe mapOf("OAK_PLANKS" to 1L)
            stonePayment.after.workers.first().let { CivsEquipment.plainCount(it, "STONE") } shouldBe 0
            h.service.city(h.player)!!.workers.first().let { CivsEquipment.plainCount(it, "STONE") } shouldBe 1
            verify(exactly = 0) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            h.commit(); h.paper.performTicks(20)
            h.service.city(h.player)!!.workers.first().let { CivsEquipment.plainCount(it, "STONE") } shouldBe 0
            h.service.city(h.player)!!.construction!!.materials shouldBe mapOf("OAK_PLANKS" to 1L)
            f.receipts.size shouldBe 2
            verify(exactly = 1) { h.blueprints.paste(any(), any(), any(), any(), any()) }
        }
    }

    test("reloaded unstarted invalid-construction order retries its routed prefix without repricing the accepted bill") {
        serviceScenario { h ->
            val f = ServiceRoutedTerraform(h, phase = ConstructionPhase.PAUSED)
            h.start()
            h.saves shouldBe emptyList()
            h.service.city(h.player)!!.construction shouldBe f.order
            val retry = h.service.retryConstruction(h.player, f.city.id)
            h.tick(); h.commit(); retry.join()
            h.paper.performTicks(20)
            f.receipts.size shouldBe 1
            val receipt = f.receipts.single()
            receipt.after.construction!!.id shouldBe f.order.id
            receipt.after.construction!!.workDone shouldBe 0
            receipt.after.construction!!.blocksPlaced shouldBe 0
            receipt.after.construction!!.materials shouldBe f.order.materials
            receipt.after.construction!!.payment!!.reservedBatch shouldBe 0
            receipt.after.workers shouldBe f.city.workers
            receipt.changes shouldBe emptyList()
            h.commit()
            f.workerReady = true
            requireNotNull(f.gate).invoke() shouldBe true
            f.completeTerrain()
            val completed = h.saves.last().first
            completed.construction!!.id shouldBe f.order.id
            completed.construction!!.workDone shouldBe 1
            completed.construction!!.materials shouldBe f.order.materials
            completed.construction!!.failure shouldBe null
            h.commit()
            h.service.city(h.player)!!.construction!!.workDone shouldBe 1
        }
    }

    test("reloaded completed terrain still waits for genuinely missing authored materials without another prefix write") {
        serviceScenario { h ->
            val f = ServiceRoutedTerraform(h, workDone = 1)
            h.start()
            val waiting = h.saves.single().first.construction!!
            waiting.id shouldBe f.order.id
            waiting.phase shouldBe ConstructionPhase.WAITING_MATERIALS
            waiting.workDone shouldBe 1
            waiting.blocksPlaced shouldBe 1
            waiting.payment!!.awaiting shouldBe mapOf("STONE" to 1L)
            waiting.materials shouldBe f.order.materials
            f.receipts shouldBe emptyList()
            h.commit()
            h.time += 5_000; h.paper.performTicks(20)
            h.service.city(h.player)!!.construction!!.payment!!.awaiting shouldBe mapOf("STONE" to 1L)
            h.world.getBlockAt(f.terrain.position.x, f.terrain.position.y, f.terrain.position.z).type shouldBe Material.COARSE_DIRT
            verify(exactly = 0) {
                h.worldAdapter.applyTerraform(any(), any(), any(), any(), any(), any())
                h.blueprints.paste(any(), any(), any(), any(), any())
                h.store.saveInventoryTransaction(any())
            }
        }
    }

    test("a saved terrain material wait clears only its obsolete marker before reserving an empty debit") {
        serviceScenario { h ->
            val f = ServiceRoutedTerraform(h, phase = ConstructionPhase.WAITING_MATERIALS, legacyTerrainAwaiting = true)
            f.order.payment!!.awaiting shouldBe mapOf("DIRT" to 1L)
            f.order.materials shouldBe mapOf("STONE" to 1L, "DIRT" to 1L)
            f.city.workers.all { CivsEquipment.plainCount(it, "DIRT") == 0 } shouldBe true
            for (position in listOf(CivPosition(0, 63, 0), CivPosition(15, 63, 1), CivPosition(20, 63, 0))) {
                (h.world.getBlockAt(position.x, position.y, position.z).state as org.bukkit.block.Barrel).inventory.isEmpty shouldBe true
            }
            h.start()
            val cleared = h.saves.single().first
            cleared.construction!!.phase shouldBe ConstructionPhase.READY_TO_PLACE
            cleared.construction!!.id shouldBe f.order.id
            cleared.construction!!.workDone shouldBe 0
            cleared.construction!!.blocksPlaced shouldBe 0
            cleared.construction!!.workCredit shouldBe f.order.workCredit
            cleared.construction!!.materials shouldBe f.order.materials
            cleared.construction!!.payment shouldBe CivConstructionPayment()
            cleared.workers shouldBe f.city.workers
            h.service.city(h.player)!!.construction shouldBe f.order
            f.receipts shouldBe emptyList()
            f.gate shouldBe null
            verify(exactly = 0) { h.worldAdapter.applyTerraform(any(), any(), any(), any(), any(), any()) }
            // Lose the prepare owner immediately after this metadata save, before its next scheduled pulse.
            h.saves.single().second.complete(cleared)
            h.service.close()
            f.receipts shouldBe emptyList()
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(cleared))
            val reopenedTasks = LifecycleTaskScope(BukkitTaskScheduler(h.plugin))
            val reopened = CivsService(h.plugin, reopenedTasks,
                CivsSettings(Path.of("/tmp/civs-unused"), setOf("vanilla"), 96, 500, 50_000, 4, 120, 48, 96),
                h.store, h.blueprints, Executor(Runnable::run), h.worldAdapter, h.renderer, h.citizens,
                backendTransfer = h.transfer, clock = { h.time }, boundaryRenderer = h.boundary,
                storageAdapter = CivsStorage(h.plugin) { emptyArray() }, supplyExecutor = Executor(Runnable::run))
            try {
                h.await(reopened.start()) shouldBe Unit
                h.tickUntil("terrain payment after durable legacy wait reload") { f.receipts.isNotEmpty() }
                val receipt = f.receipts.single()
                receipt.after.construction!!.id shouldBe f.order.id
                receipt.after.construction!!.payment!!.reservedBatch shouldBe 0
                receipt.after.construction!!.materials shouldBe f.order.materials
                receipt.after.workers shouldBe f.city.workers
                receipt.changes shouldBe emptyList()
                h.tickUntil("reloaded terrain payment model save") {
                    h.saves.any { it.first == receipt.after && !it.second.isDone }
                }
                reopened.city(h.player)!!.construction!!.payment!!.reservedBatch shouldBe null
                f.gate shouldBe null
                h.saves.single { it.first == receipt.after }.second.complete(receipt.after)
                h.tickUntil("reloaded paid terrain native gate") { f.gate != null }
                requireNotNull(f.gate).invoke() shouldBe false
                f.workerReady = true
                f.completeTerrain()
                h.saves.last().first.construction!!.workDone shouldBe 1
                h.saves.last().first.construction!!.materials shouldBe f.order.materials
            } finally {
                reopened.close()
                reopenedTasks.close()
            }
        }
    }
})

class CivsConstructionTransientFenceTest : FunSpec({
    test("a pending native receipt suspends a schematic batch and exact cell writes without losing the paid order") {
        lateinit var storage: CivsStorage
        serviceScenario(storageFactory = { plugin ->
            io.mockk.spyk(CivsStorage(plugin) { emptyArray() }).also { storage = it }
        }) { h ->
            val placement = h.placement()
            every { h.citizens.constructionReady(any(), any()) } returns true
            var gate: java.util.function.BooleanSupplier? = null
            every { h.blueprints.paste(any(), any(), any(), any(), any()) } answers {
                gate = arg(4)
                placement.operation
            }
            h.start(); placement.validation.complete(Unit); h.tick(); h.commit()
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            val cell = CivPosition(15, 64, 0)
            val before = h.service.settlements().single()
            val worldBefore = h.world.getBlockAt(cell.x, cell.y, cell.z).blockData.asString
            requireNotNull(gate).asBoolean shouldBe true
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, cell.x, cell.y, cell.z) shouldBe true
            every { storage.isWorldLocked(h.world.uid, cell) } returns true
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, cell.x, cell.y, cell.z) shouldBe false
            @Suppress("UNCHECKED_CAST")
            val busy = CivsService::class.java.getDeclaredField("busy").also { it.isAccessible = true }.get(h.service) as MutableSet<UUID>
            busy.add(before.id)
            repeat(4) {
                requireNotNull(gate).asBoolean shouldBe false
                h.paper.performTicks(1)
                h.world.getBlockAt(cell.x, cell.y, cell.z).blockData.asString shouldBe worldBefore
                h.service.settlements().single().construction shouldBe before.construction
            }
            busy.remove(before.id)
            every { storage.isWorldLocked(h.world.uid, cell) } returns false
            requireNotNull(gate).asBoolean shouldBe true
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, cell.x, cell.y, cell.z) shouldBe true
            h.service.settlements().single().construction shouldBe before.construction
        }
    }
})

private fun constructionRuntime(service: CivsService): CivsConstructionRuntime =
    CivsService::class.java.getDeclaredField("construction").also { it.isAccessible = true }
        .get(service) as CivsConstructionRuntime

/** Same saved per-batch item contract as routed new construction, including an old paused work-zero order. */
private class ServiceRoutedTerraform(private val h: ServiceScenario,
    phase: ConstructionPhase = ConstructionPhase.READY_TO_PLACE, workDone: Int = 0, stone: Int = 0,
    legacyTerrainAwaiting: Boolean = false) {
    val terrain = CivPlanBlock(CivPosition(7, 63, 0), "minecraft:air",
        if (legacyTerrainAwaiting) "minecraft:dirt" else "minecraft:coarse_dirt")
    val order: CivConstruction
    val city: Settlement
    val receipts = mutableListOf<CivInventoryTransaction>()
    var workerReady = false
    var gate: (() -> Boolean)? = null
    private var placed: ((CivPlanBlock) -> Unit)? = null
    private val applied = CompletableFuture<Unit>()

    init {
        val placement = h.incrementalPlacement()
        val original = requireNotNull(placement.city.construction)
        val building = requireNotNull(original.plannedBuilding)
        val route = (0..12).map { CivPosition(it, 64, 0) }
        val captured = requireNotNull(h.store.loadPlan(original.id).join())
        val plan = captured.copy(blocks = if (legacyTerrainAwaiting) captured.blocks.mapIndexed { index, cell ->
            if (index == 1) cell.copy(after = "minecraft:dirt") else cell
        } else captured.blocks, terrain = CivTerraformPlan(
            CivBox(terrain.position, terrain.position), listOf(terrain), route, 63, 0, 1))
        order = original.copy(phase = phase, failure = "invalid-construction".takeIf { phase == ConstructionPhase.PAUSED },
            batches = CivConstructionBatches(3, 16, true), workRequired = 3, workDone = workDone,
            workCredit = 3 - workDone, blocksRequired = 3, blocksPlaced = workDone,
            materials = if (legacyTerrainAwaiting) mapOf("STONE" to 1L, "DIRT" to 1L) else original.materials,
            payment = CivConstructionPayment(awaiting = mapOf("DIRT" to 1L).takeIf { legacyTerrainAwaiting }),
            preparationWorkplace = if (workDone == 0) civsTerraformBatches(plan).single().workplace else route.last())
        city = CivsRules.validate(placement.city.copy(construction = order,
            workers = placement.city.workers.mapIndexed { index, worker ->
                if (index == 0 && stone > 0) CivsEquipment.collectSupplies(worker, mapOf("STONE" to stone.toLong())) else worker
            }))
        CivsMaterials.required(plan) shouldBe order.materials
        order.automaticPreparationBatches shouldBe null
        civsConstructionBatches(plan, order).size shouldBe order.workRequired
        val snapshot = mockk<ArcSchematicSnapshot>(relaxed = true)
        every { snapshot.blocks() } returns listOf(
            ArcSchematicBlock(ArcSchematicPoint(0, 0, 0), "minecraft:stone", false, false),
            ArcSchematicBlock(ArcSchematicPoint(1, 0, 0), plan.blocks[1].after, false, false))
        every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
        every { h.store.loadPlan(order.id) } returns CompletableFuture.completedFuture(plan)
        every { h.blueprints.recover(plan) } returns CompletableFuture.completedFuture(snapshot)
        every { h.blueprints.pasteParts(snapshot, building, any()) } answers { CompletableFuture.completedFuture(listOf(snapshot)) }
        every { h.blueprints.paste(snapshot, h.world.uid, building.anchor, any(), any()) } returns placement.operation
        every { h.citizens.constructionReady(city.id, order.id) } answers { workerReady }
        every { h.store.saveInventoryTransaction(any()) } answers {
            val receipt = firstArg<CivInventoryTransaction>()
            receipts += receipt
            CompletableFuture.completedFuture(receipt)
        }
        every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
        every { h.worldAdapter.applyTerraform(plan, h.owner, listOf(terrain), 0, any(), any()) } answers {
            gate = arg(4)
            placed = arg(5)
            applied
        }
        h.world.getBlockAt(terrain.position.x, terrain.position.y, terrain.position.z).type =
            if (workDone > 0) Material.COARSE_DIRT else Material.AIR
    }

    fun completeTerrain() {
        requireNotNull(gate).invoke() shouldBe true
        h.world.getBlockAt(terrain.position.x, terrain.position.y, terrain.position.z).blockData = Bukkit.createBlockData(terrain.after)
        requireNotNull(placed).invoke(terrain)
        applied.complete(Unit)
        h.tick()
    }
}
