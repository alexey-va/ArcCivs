package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.verify
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.Ageable
import org.bukkit.inventory.ItemStack

class CivsResidentProductionServiceTest : FunSpec({
    test("unfunded research journals the exact university barrel bill once before any researcher is assigned") {
        serviceScenario { h ->
            val university = h.city.buildings.last().copy(type = BuildingType.UNIVERSITY,
                control = CivPosition(10, 63, 0))
            val prerequisites = listOf(BuildingType.FORESTER, BuildingType.QUARRY).mapIndexed { index, type ->
                university.copy(id = UUID.randomUUID(), type = type, anchor = CivPosition(20 + index * 10, 64, 0),
                    bounds = CivBox(CivPosition(18 + index * 10, 63, -2), CivPosition(22 + index * 10, 68, 2)),
                    entrance = CivPosition(20 + index * 10, 64, -2), control = CivPosition(20 + index * 10, 63, 0))
            }
            val base = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL), workers = emptyList(),
                buildings = listOf(h.city.buildings.first(), university) + prerequisites)
            val before = CivsResearch.begin(base, CivResearchId.MATERIALS, UUID.randomUUID(), h.time)
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(before))
            h.world.getBlockAt(10, 63, 0).type = Material.BARREL
            val barrel = (h.world.getBlockAt(10, 63, 0).state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, ItemStack(Material.BOOK, 1))
            barrel.setItem(1, ItemStack(Material.PAPER, 8))
            barrel.setItem(2, ItemStack(Material.COBBLESTONE, 8))
            barrel.setItem(3, ItemStack(Material.DIAMOND, 2))
            val journal = CompletableFuture<CivInventoryTransaction>()
            var receipt: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); journal }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            h.paper.addPlayer("ResearchFundingWitness").teleport(Location(h.world, .5, 65.0, .5))
            val pulse = CivsCityResearch::class.java.getDeclaredMethod("pulseResearch").also { it.isAccessible = true }
            pulse.invokeService(h.service)
            val tx = requireNotNull(receipt)
            tx.changes.map { it.container }.distinct() shouldBe listOf(university.control)
            tx.changes.map { it.before!!.material to it.before!!.amount }.toMap() shouldBe
                mapOf("BOOK" to 1, "PAPER" to 8, "COBBLESTONE" to 8)
            tx.changes.all { it.after == null } shouldBe true
            barrel.getItem(0)!!.amount shouldBe 1
            h.service.city(h.player)!!.research!!.project!!.funded shouldBe false
            h.saves shouldBe emptyList()
            pulse.invokeService(h.service)
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
            journal.complete(tx)
            h.tickUntil("exact research debit and pending city credit") { h.saves.isNotEmpty() }
            barrel.getItem(0) shouldBe null
            barrel.getItem(1) shouldBe null
            barrel.getItem(2) shouldBe null
            barrel.getItem(3) shouldBe ItemStack(Material.DIAMOND, 2)
            h.service.city(h.player)!!.research!!.project!!.funded shouldBe false
            tx.after.research!!.project!!.funded shouldBe true
            tx.after.workers shouldBe emptyList()
            tx.after.stock shouldBe Stock()
            h.commit()
            pulse.invokeService(h.service); h.tick()
            val funded = h.service.city(h.player)!!
            funded.research!!.project!!.funded shouldBe true
            funded.research!!.project!!.workDone shouldBe 0
            h.saves.size shouldBe 1
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()); h.store.deleteInventoryTransaction(tx.id) }
        }
    }

    test("research session requires the selected observed trip and commits progress cycle and training together once") {
        serviceScenario { h ->
            val university = h.city.buildings.last().copy(type = BuildingType.UNIVERSITY, control = CivPosition(10, 63, 0))
            val prerequisites = listOf(BuildingType.FORESTER, BuildingType.QUARRY).mapIndexed { index, type ->
                university.copy(id = UUID.randomUUID(), type = type, anchor = CivPosition(20 + index * 10, 64, 0),
                    bounds = CivBox(CivPosition(18 + index * 10, 63, -2), CivPosition(22 + index * 10, 68, 2)),
                    entrance = CivPosition(20 + index * 10, 64, -2), control = CivPosition(20 + index * 10, 63, 0))
            }
            val base = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL),
                buildings = listOf(h.city.buildings.first(), university) + prerequisites,
                workers = listOf(h.city.workers.first().copy(assignment = university.id, nextDeliveryAt = 1)))
            val begun = CivsResearch.begin(base, CivResearchId.MATERIALS, UUID.randomUUID(), 1_000)
            val ready = CivsResearch.fund(begun, begun.research!!.project!!.id, 2_000, nativePaid = true)
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(ready))
            h.start()
            val worker = ready.workers.single()
            val plan = requireNotNull(citizenPlan(ready, worker))
            val deliver = CivsService::class.java.getDeclaredMethod("deliver", UUID::class.java, UUID::class.java,
                UUID::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE, UUID::class.java, java.lang.Long.TYPE)
                .also { it.isAccessible = true }
            fun complete() = deliver.invokeService(h.service, ready.id, worker.id, university.id, worker.completedCycles,
                worker.nextDeliveryAt, null, h.time) as CompletableFuture<*>
            every { h.citizens.requireSelectedWorkDelivery(ready.id, worker.id, plan.token) } throws CivsRejection("route-unreachable")
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { complete().join() })?.reason shouldBe "route-unreachable"
            h.saves shouldBe emptyList()
            every { h.citizens.requireSelectedWorkDelivery(ready.id, worker.id, plan.token) } returns Unit
            val completed = complete()
            completed.isDone shouldBe false
            h.service.city(h.player) shouldBe ready
            h.saves.single().first.let { saved ->
                saved.research!!.project!!.workDone shouldBe 1
                saved.workers.single().completedCycles shouldBe 1L
                saved.workers.single().skills shouldBe mapOf("UNIVERSITY" to 1L)
                saved.stock shouldBe Stock()
                saved.inventory shouldBe ready.inventory
                saved.revision shouldBe ready.revision + 1
            }
            h.commit(); completed.join()
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { complete().join() })?.reason shouldBe "stale-delivery"
            h.saves.size shouldBe 1
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("research keeps the explicit Auto city public for reads and rechecks admin permission after a pending save") {
        serviceScenario { h ->
            val university = h.city.buildings.last().copy(type = BuildingType.UNIVERSITY, control = CivPosition(10, 63, 0))
            val prerequisites = listOf(BuildingType.FORESTER, BuildingType.QUARRY).mapIndexed { index, type ->
                university.copy(id = UUID.randomUUID(), type = type, anchor = CivPosition(20 + index * 10, 64, 0),
                    bounds = CivBox(CivPosition(18 + index * 10, 63, -2), CivPosition(22 + index * 10, 68, 2)),
                    entrance = CivPosition(20 + index * 10, 64, -2), control = CivPosition(20 + index * 10, 63, 0))
            }
            val personal = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL), workers = emptyList(),
                buildings = listOf(h.city.buildings.first(), university) + prerequisites)
            val auto = personal.copy(id = UUID.randomUUID(), owner = UUID.randomUUID(), name = "Independent research",
                stock = Stock(96, 96, 24), inventory = null, autonomy = CivAutonomy(paused = true))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(personal, auto))
            h.start()
            every { h.player.hasPermission("arccivs.admin") } returns false
            h.service.research(h.player, auto.id).cityId shouldBe auto.id
            h.service.industry(h.player, auto.id).autonomous shouldBe true
            CivsDiagnostics.rejection(shouldThrow<CompletionException> {
                h.service.beginResearch(h.player, auto.id, CivResearchId.MATERIALS).join()
            })?.reason shouldBe "not-authorized"
            val otherWorld = h.paper.addSimpleWorld("research_other")
            every { h.player.world } returns otherWorld
            shouldThrow<CivsRejection> { h.service.research(h.player, auto.id) }.reason shouldBe "not-authorized"
            every { h.player.world } returns h.world
            every { h.player.hasPermission("arccivs.admin") } returns true
            val unrelated: (Settlement) -> Settlement = { it.copy(revision = it.revision + 1) }
            val pending = h.service.mutate(auto.id, transform = unrelated)
            val queued = h.service.beginResearch(h.player, auto.id, CivResearchId.MATERIALS)
            queued.isDone shouldBe false
            every { h.player.hasPermission("arccivs.admin") } returns false
            h.commit(); pending.join()
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { h.await(queued) })?.reason shouldBe "not-authorized"
            h.saves.size shouldBe 1
            h.service.settlements().single { it.id == auto.id }.research shouldBe null
            h.service.city(h.player) shouldBe personal
            val acceptedPersonal = h.service.beginResearch(h.player, personal.id, CivResearchId.MATERIALS)
            h.saves.last().first.id shouldBe personal.id
            h.saves.last().first.research!!.project!!.cost.materials shouldBe mapOf("BOOK" to 1L, "PAPER" to 8L, "COBBLESTONE" to 8L)
            h.commit(); acceptedPersonal.join()
            h.service.settlements().single { it.id == auto.id }.research shouldBe null
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("useful crop care requires arrival at the plot and commits its cycle only through the journal") {
        serviceScenario { h ->
            val world = CivsWorksiteWorldFixture("service-crop-care").also(h.paper.server::addWorld)
            val anchor = CivPosition(30, 63, 0)
            val farm = h.city.buildings.last().copy(type = BuildingType.FARM, anchor = anchor, level = 1,
                schematicId = "civs/minecolonies/medievaloak/agriculture/horticulture/farmer1",
                bounds = CivBox(CivPosition(26, 63, -22), CivPosition(48, 80, -1)),
                entrance = CivPosition(30, 64, 0), control = CivPosition(30, 64, -3), production = "WHEAT")
            val worker = h.city.workers.first().copy(assignment = farm.id, nextDeliveryAt = 1,
                inventory = null, items = List(CivsEquipment.SLOTS) { if (it == 4) CivResidentItem("IRON_HOE", 1) else null })
            val ready = h.city.copy(worldId = world.uid, buildings = listOf(h.city.buildings.first(), farm),
                workers = listOf(worker), stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL))
            for (x in 0..4) for (z in -3..1) world.loadChunk(x, z)
            CivsWorksiteLayouts.field(farm).forEach { soil ->
                world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=3]")
                world.getBlockAt(soil.x, soil.y + 1, soil.z).blockData =
                    (Material.WHEAT.createBlockData() as Ageable).also { it.age = 2 }
            }
            CivsWorksiteLayouts.irrigation(farm).forEach { world.getBlockAt(it.x, it.y, it.z).type = Material.WATER }
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(ready))
            val journal = CompletableFuture<CivInventoryTransaction>()
            var receipt: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); journal }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            every { h.citizens.requireStorageArrival(any(), any(), any()) } throws CivsRejection("stale-delivery")
            h.start()
            val runtime = CivsService::class.java.getDeclaredField("worksites").also { it.isAccessible = true }.get(h.service) as CivsWorksites
            // Arrival and journal ordering use deterministic planning; pulse budgets have separate coverage.
            CivsWorksites::class.java.getDeclaredField("nanoTime").also { it.isAccessible = true }
                .set(runtime, { 0L })
            repeat(32) { if (runtime.plan(h.service.city(h.player)!!, worker) == null) h.paper.performTicks(5) }
            val work = requireNotNull(runtime.plan(h.service.city(h.player)!!, worker))
            work.status shouldBe "watering"
            work.changes.size shouldBe 2
            work.changes.map { it.before to it.after }.toSet() shouldBe setOf(
                "minecraft:farmland[moisture=3]" to "minecraft:farmland[moisture=7]",
                "minecraft:wheat[age=2]" to "minecraft:wheat[age=3]",
            )
            val deliver = CivsService::class.java.getDeclaredMethod("deliver", UUID::class.java, UUID::class.java,
                UUID::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE, UUID::class.java, java.lang.Long.TYPE)
                .also { it.isAccessible = true }
            fun complete() = deliver.invokeService(h.service, ready.id, worker.id, farm.id, worker.completedCycles,
                worker.nextDeliveryAt, null, h.time) as CompletableFuture<*>
            val completion = complete()
            val accepted = requireNotNull(receipt)
            accepted.changes shouldBe emptyList()
            accepted.worldChanges shouldBe work.changes
            accepted.after.workers.single().completedCycles shouldBe 1L
            accepted.after.workers.single().skills shouldBe worker.skills
            accepted.after.workers.single().items shouldBe worker.items
            h.service.city(h.player)!!.workers.single().completedCycles shouldBe 0L
            h.saves shouldBe emptyList()
            completion.isDone shouldBe false
            verify(exactly = 2) { h.citizens.requireWorldWorkArrival(ready.id, worker.id, work) }
            verify(exactly = 0) { h.citizens.requireStorageArrival(any(), any(), any()) }
            journal.complete(accepted); h.tick()
            h.service.city(h.player)!!.workers.single().completedCycles shouldBe 0L
            h.commit(); completion.join()
            h.service.city(h.player)!!.workers.single().completedCycles shouldBe 1L
            complete().isCompletedExceptionally shouldBe true
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
        }
    }

    for (fullBag in listOf(false, true)) test("harvest enters only the real producer bag after its journal and rejects fullBag=$fullBag before native effects") {
        serviceScenario { h ->
            val world = CivsWorksiteWorldFixture("service-harvest-bag").also(h.paper.server::addWorld)
            val farm = h.city.buildings.last().copy(type = BuildingType.FARM, anchor = CivPosition(30, 63, 0), level = 1,
                schematicId = "civs/minecolonies/medievaloak/agriculture/horticulture/farmer1",
                bounds = CivBox(CivPosition(26, 63, -22), CivPosition(48, 80, -1)),
                entrance = CivPosition(30, 64, 0), control = CivPosition(30, 64, -3), production = "WHEAT")
            val worker = h.city.workers.first().copy(assignment = farm.id, nextDeliveryAt = 1,
                inventory = null, items = List(CivsEquipment.SLOTS) { slot -> when {
                    slot == 4 -> CivResidentItem("IRON_HOE", 1)
                    fullBag && slot >= CivsEquipment.BAG -> CivResidentItem("STONE", 64)
                    else -> null
                } })
            val ready = h.city.copy(worldId = world.uid, buildings = listOf(h.city.buildings.first(), farm),
                workers = listOf(worker), stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL))
            for (x in 0..4) for (z in -3..1) world.loadChunk(x, z)
            CivsWorksiteLayouts.field(farm).forEach { soil ->
                world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=7]")
                world.getBlockAt(soil.x, soil.y + 1, soil.z).blockData =
                    (Material.WHEAT.createBlockData() as Ageable).also { it.age = it.maximumAge }
            }
            CivsWorksiteLayouts.irrigation(farm).forEach { world.getBlockAt(it.x, it.y, it.z).type = Material.WATER }
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(ready))
            val journal = CompletableFuture<CivInventoryTransaction>()
            var receipt: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); journal }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            val runtime = CivsService::class.java.getDeclaredField("worksites").also { it.isAccessible = true }.get(h.service) as CivsWorksites
            repeat(64) { if (runtime.plan(h.service.city(h.player)!!, worker) == null) h.paper.performTicks(5) }
            val work = requireNotNull(runtime.plan(h.service.city(h.player)!!, worker))
            ((work.outputs["WHEAT"] ?: 0L) > 0L) shouldBe true
            val focus = requireNotNull(work.focus)
            val crop = world.getBlockAt(focus.x, focus.y, focus.z)
            val nativeBefore = crop.blockData.asString
            val deliver = CivsService::class.java.getDeclaredMethod("deliver", UUID::class.java, UUID::class.java,
                UUID::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE, UUID::class.java, java.lang.Long.TYPE).also { it.isAccessible = true }
            fun complete() = deliver.invokeService(h.service, ready.id, worker.id, farm.id, worker.completedCycles,
                worker.nextDeliveryAt, null, h.time) as CompletableFuture<*>
            val result = complete()
            crop.blockData.asString shouldBe nativeBefore
            h.service.city(h.player)!!.workers.single().completedCycles shouldBe 0L
            if (fullBag) {
                val failure = shouldThrow<CompletionException> { result.join() }.cause as CivsRejection
                failure.reason shouldBe "storage-full"
                receipt shouldBe null
                h.saves shouldBe emptyList()
                h.service.city(h.player)!!.workers.single().items shouldBe worker.items
            } else {
                val accepted = requireNotNull(receipt)
                accepted.changes shouldBe emptyList()
                accepted.after.inventory shouldBe ready.inventory
                work.outputs.forEach { (material, amount) ->
                    CivsEquipment.plainCount(accepted.after.workers.single(), material) shouldBe amount.toInt()
                    CivsEquipment.plainCount(h.service.city(h.player)!!.workers.single(), material) shouldBe 0
                }
                journal.complete(accepted); h.tick()
                (crop.blockData.asString != nativeBefore) shouldBe true
                h.service.city(h.player)!!.workers.single().completedCycles shouldBe 0L
                h.commit(); result.join()
                work.outputs.forEach { (material, amount) -> CivsEquipment.plainCount(h.service.city(h.player)!!.workers.single(), material) shouldBe amount.toInt() }
                complete().isCompletedExceptionally shouldBe true
                verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
            }
        }
    }

    test("a replaced same cycle craft rejects both old service callbacks without consuming stock or advancing work") {
        serviceScenario { h ->
            val (workshop, carpenter) = h.prepareCarpentry(mapOf("OAK_LOG" to 1L))
            h.start()
            val old = h.craftJob(carpenter.id)
            val dispatcher = CivsService::class.java.getDeclaredField("supply").let {
                it.isAccessible = true; it.get(h.service) as CivsSupplyDispatcher
            }
            dispatcher.releaseCraft(h.city.id, carpenter.id, old.id)
            val replacement = h.craftJob(carpenter.id)
            (replacement.id != old.id) shouldBe true
            replacement.cycle shouldBe old.cycle
            val before = requireNotNull(h.service.city(h.player))
            val supplies = h.service.prepareResidentSupplies(before.id, carpenter.id, workshop.id, old.id)
            (shouldThrow<CompletionException> { h.await(supplies) }.cause as CivsRejection).reason shouldBe "stale-delivery"
            // Invoke the exact private callback wired into CivsCitizens, without adding a production test seam.
            val delivery = CivsResidentSupplies::class.java.getDeclaredMethod("deliverCraft", UUID::class.java, UUID::class.java,
                UUID::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE, UUID::class.java, java.lang.Long.TYPE).let {
                it.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                (it.invokeService(h.service, before.id, carpenter.id, workshop.id, carpenter.completedCycles,
                    carpenter.nextDeliveryAt, old.id, h.time) as CompletableFuture<Settlement>)
            }
            (shouldThrow<CompletionException> { h.await(delivery) }.cause as CivsRejection).reason shouldBe "stale-delivery"
            h.service.city(h.player) shouldBe before
            h.service.localMaterials(h.player, workshop.id) shouldBe mapOf("OAK_LOG" to 1L)
            h.saves shouldBe emptyList()
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }
})

class CivsCraftCompletionFreshnessTest : FunSpec({
    for (change in listOf("cycle", "items")) test("a funded accepted craft completes after only another worker changes $change") {
        serviceScenario { h ->
            val (workshop, carpenter) = prepareFundedCraft(h)
            val receipts = mutableListOf<CivInventoryTransaction>()
            val journal = CompletableFuture<CivInventoryTransaction>()
            every { h.store.saveInventoryTransaction(any()) } answers { receipts += firstArg<CivInventoryTransaction>(); journal }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            val job = h.craftJob(carpenter.id)
            job.inputs shouldBe mapOf("OAK_LOG" to 1L)
            job.material shouldBe "OAK_PLANKS"
            job.amount shouldBe 4L
            val captured = requireNotNull(h.service.city(h.player))
            val supplies = CivsService::class.java.getDeclaredField("residentSupplies").also { it.isAccessible = true }
                .get(h.service) as CivsResidentSupplies
            supplies.craftObservationCurrent(captured) shouldBe true
            val other = captured.workers.first { it.id != carpenter.id }
            val changedOther = if (change == "cycle") other.copy(completedCycles = other.completedCycles + 1)
                else CivsEquipment.collectSupplies(other, mapOf("BREAD" to 1L))
            val current = captured.copy(workers = captured.workers.map { if (it.id == other.id) changedOther else it })
            h.replaceCityInMemory(current)
            supplies.craftObservationCurrent(current) shouldBe false
            h.service.craftPlan(current, carpenter) shouldBe job
            CivsCarpentry.valid(current, carpenter, job) shouldBe true
            CivsEquipment.inputsReady(carpenter, job.inputs, emptyMap()) shouldBe true
            val barrel = craftTestBarrel(h, workshop)
            barrel.isEmpty shouldBe true
            val delivery = deliverCraftTest(h, current, carpenter, job.id)
            h.tickUntil("accepted craft reaches its journal") { receipts.isNotEmpty() || delivery.isDone }
            if (delivery.isDone) h.await(delivery)
            receipts.size shouldBe 1
            barrel.isEmpty shouldBe true
            h.service.city(h.player) shouldBe current
            journal.complete(receipts.single())
            h.tickUntil("paid craft awaits city publication") { h.saves.size == 1 }
            delivery.isDone shouldBe false
            barrel.contents.filterNotNull() shouldBe listOf(ItemStack(Material.OAK_PLANKS, 4))
            h.commit()
            val completed = h.await(delivery)
            val paid = completed.workers.first { it.id == carpenter.id }
            paid.completedCycles shouldBe carpenter.completedCycles + 1
            CivsEquipment.plainCount(paid, "OAK_LOG") shouldBe 0
            CivsEquipment.plainCount(paid, "OAK_PLANKS") shouldBe 0
            completed.workers.first { it.id == other.id } shouldBe changedOther
            completed.stock shouldBe current.stock
            verify(exactly = 1) {
                h.citizens.requireSelectedWorkDelivery(current.id, carpenter.id,
                    citizenPlan(current, carpenter)!!.token.copy(craftJobId = job.id), job)
                h.citizens.requireStorageArrival(current.id, carpenter.id, workshop.id)
                h.store.saveInventoryTransaction(any())
            }
            (shouldThrow<CompletionException> { h.await(deliverCraftTest(h, completed, carpenter, job.id)) }
                .cause as CivsRejection).reason shouldBe "stale-delivery"
            h.saves.size shouldBe 1
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
        }
    }

    for (guard in listOf("job", "cycle", "assignment", "missing-inputs", "output-full"))
        test("craft completion preserves its $guard rejection without a receipt or free output") {
            serviceScenario { h ->
                val (workshop, carpenter) = prepareFundedCraft(h)
                h.start()
                val job = h.craftJob(carpenter.id)
                var current = requireNotNull(h.service.city(h.player))
                if (guard in setOf("assignment", "missing-inputs")) {
                    current = current.copy(workers = current.workers.map { worker ->
                        if (worker.id != carpenter.id) worker
                        else if (guard == "assignment") worker.copy(assignment = current.buildings.first().id)
                        else CivsEquipment.consumeInputs(worker, job.inputs)
                    })
                    h.replaceCityInMemory(current)
                }
                val barrel = craftTestBarrel(h, workshop)
                if (guard == "output-full") repeat(barrel.size) { barrel.setItem(it, ItemStack(Material.STONE, 64)) }
                val slots = barrel.contents.map { it?.clone() }
                val rejected = deliverCraftTest(h, current, carpenter, if (guard == "job") UUID.randomUUID() else job.id,
                    cycle = carpenter.completedCycles + if (guard == "cycle") 1 else 0)
                val reason = (shouldThrow<CompletionException> { h.await(rejected) }.cause as CivsRejection).reason
                reason shouldBe when (guard) {
                    "missing-inputs" -> "materials-missing"
                    "output-full" -> "storage-full"
                    else -> "stale-delivery"
                }
                h.service.city(h.player) shouldBe current
                barrel.contents.toList() shouldBe slots
                current.workers.first { it.id == carpenter.id }.completedCycles shouldBe carpenter.completedCycles
                CivsEquipment.plainCount(current.workers.first { it.id == carpenter.id }, "OAK_PLANKS") shouldBe 0
                h.saves shouldBe emptyList()
                verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
            }
        }
})

private fun prepareFundedCraft(h: ServiceScenario): Pair<CivBuilding, CivWorker> {
    val (workshop, carpenter) = h.prepareCarpentry()
    val base = h.store.load().join().single()
    val funded = CivsEquipment.collectSupplies(carpenter, mapOf("OAK_LOG" to 1L))
    val ready = CivsRules.validate(base.copy(inventory = CivInventory(CivStorageMode.PHYSICAL),
        buildings = base.buildings.map { if (it.type == BuildingType.TOWN_HALL) it.copy(control = CivPosition(0, 63, 0)) else it },
        workers = base.workers.map { if (it.id == carpenter.id) funded else it }))
    h.world.loadChunk(1, 0)
    ready.buildings.forEach { building -> requireNotNull(building.control).let { h.world.getBlockAt(it.x, it.y, it.z).type = Material.BARREL } }
    every { h.store.load() } returns CompletableFuture.completedFuture(listOf(ready))
    return workshop to funded
}

private fun craftTestBarrel(h: ServiceScenario, building: CivBuilding): org.bukkit.inventory.Inventory =
    requireNotNull(building.control).let { (h.world.getBlockAt(it.x, it.y, it.z).state as org.bukkit.block.Barrel).inventory }

private fun deliverCraftTest(h: ServiceScenario, city: Settlement, worker: CivWorker, jobId: UUID,
    cycle: Long = worker.completedCycles): CompletableFuture<Settlement> =
    CivsResidentSupplies::class.java.getDeclaredMethod("deliverCraft", UUID::class.java, UUID::class.java,
        UUID::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE, UUID::class.java, java.lang.Long.TYPE).let {
        it.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        it.invokeService(h.service, city.id, worker.id, requireNotNull(worker.assignment), cycle,
            worker.nextDeliveryAt, jobId, h.time) as CompletableFuture<Settlement>
    }
