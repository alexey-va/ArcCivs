package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

class CivsAutonomousHallRetargetTest : FunSpec({
    test("an unbuilt autonomous hall reserves its translated order while retaining the original site and full control") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("auto3-hall-retarget")
            // Observed Auto3 source geometry; this is admission only, not a native terrain replay.
            val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1,
                CivPosition(10674, 71, 11982), 0, "fixture/birch/town_hall_1",
                CivBox(CivPosition(10670, 70, 11947), CivPosition(10700, 95, 11981)),
                CivPosition(10674, 72, 11982), BuildingPhase.SITE,
                control = CivPosition(10675, 72, 11981))
            val hut = CivBuilding(UUID.randomUUID(), BuildingType.BUILDER_HUT, 1,
                CivPosition(10675, 76, 12005), 0, "fixture/birch/builder_hut_1",
                CivBox(CivPosition(10672, 75, 11999), CivPosition(10678, 82, 12004)),
                CivPosition(10675, 77, 12005), BuildingPhase.ACTIVE,
                control = CivPosition(10674, 77, 12004))
            val city = CivsRules.validate(Settlement(UUID.randomUUID(), UUID.randomUUID(), "Auto3 fixture", world.uid,
                requireNotNull(hall.control), 96, "birch", Stock(), listOf(hall, hut),
                listOf(CivWorker(UUID.randomUUID(), "Builder", hut.id, nextDeliveryAt = 1)),
                inventory = CivInventory(CivStorageMode.PHYSICAL), autonomy = CivAutonomy(),
                constructionQueue = listOf(hall.id)))
            val target = CivsAutonomousGeometry.atHeight(hall, 76).copy(phase = BuildingPhase.CONSTRUCTING)
            val sourcePoint = requireNotNull(hall.control)
            val targetPoint = requireNotNull(target.control)
            listOf(sourcePoint, targetPoint).forEach { world.loadChunk(it.x shr 4, it.z shr 4) }
            val source = world.getBlockAt(sourcePoint.x, sourcePoint.y, sourcePoint.z)
            source.type = Material.BARREL
            val inventory = (source.state as Barrel).inventory
            repeat(STORAGE_SLOTS) { slot ->
                inventory.setItem(slot, ItemStack(Material.DIAMOND, slot + 1).apply {
                    itemMeta = itemMeta.apply { displayName(Component.text("Original slot $slot")) }
                })
            }
            val originalSlots = inventory.storageContents.map(CivResidentItem::of)
            originalSlots.size shouldBe STORAGE_SLOTS
            originalSlots.all { it != null } shouldBe true
            val futureControl = world.getBlockAt(targetPoint.x, targetPoint.y, targetPoint.z)
            futureControl.type = Material.DIRT
            val order = CivConstruction(UUID.randomUUID(), hall.id, 1, 3, 0,
                ConstructionPhase.READY_TO_PLACE, Stock(), plannedBuilding = target,
                batches = CivConstructionBatches(3, 16, atomicPairs = true),
                materials = mapOf("BIRCH_PLANKS" to 16L), payment = CivConstructionPayment(),
                automaticPreparationBatches = 1)
            (order.id == hall.id) shouldBe false
            CivsSnapshotValidation.validConstructionPrice(city, order, target) shouldBe true

            val reserved = CivsRules.reserveBuild(city, city.owner, target, order)

            reserved.building(hall.id) shouldBe hall
            reserved.construction shouldBe order
            reserved.constructionQueue shouldBe emptyList()
            reserved.copy(construction = null, constructionQueue = city.constructionQueue,
                revision = city.revision) shouldBe city
            reserved.construction!!.workDone shouldBe 0
            reserved.construction!!.payment shouldBe CivConstructionPayment()
            reserved.pendingControls shouldBe city.pendingControls
            source.type shouldBe Material.BARREL
            inventory.storageContents.map(CivResidentItem::of) shouldBe originalSlots
            futureControl.type shouldBe Material.DIRT
            val reloaded = CivsRules.validate(Gson().fromJson(Gson().toJson(reserved), Settlement::class.java))
            reloaded shouldBe reserved
            CivsRules.reserveBuild(reloaded, reloaded.owner, target, order) shouldBe reloaded

            val manual = CivsRules.validate(city.copy(autonomy = null))
            shouldThrow<CivsRejection> { CivsRules.reserveBuild(manual, manual.owner, target, order) }.reason shouldBe "invalid-building"
            val active = city.copy(buildings = listOf(hall.copy(phase = BuildingPhase.ACTIVE), hut), constructionQueue = null)
            shouldThrow<CivsRejection> { CivsRules.reserveBuild(active, active.owner, target, order) }.reason shouldBe "invalid-building"
            fun east(p: CivPosition) = p.copy(x = p.x + 1)
            val shifted = target.copy(anchor = east(target.anchor), entrance = east(target.entrance), control = east(targetPoint),
                bounds = CivBox(east(target.bounds.min), east(target.bounds.max)))
            shouldThrow<CivsRejection> { CivsRules.reserveBuild(city, city.owner, shifted, order.copy(plannedBuilding = shifted)) }
                .reason shouldBe "invalid-building"
            shouldThrow<CivsRejection> { CivsRules.reserveBuild(city, city.owner, target, order.copy(id = hall.id)) }
                .reason shouldBe "invalid-construction"
            val unchanged = hall.copy(phase = BuildingPhase.CONSTRUCTING)
            val entered = CivsRules.reserveBuild(city, city.owner, unchanged,
                order.copy(id = UUID.randomUUID(), plannedBuilding = unchanged))
            shouldThrow<CivsRejection> { CivsRules.reserveBuild(entered, entered.owner, target, order) }.reason shouldBe "construction-busy"
        }
    }

    test("ordinary saved preparation clears the exact future marker and refuses either write path over the stock source") {
        MockBukkitTestRuntime.open().use { paper ->
            val native = CivsWorksiteWorldFixture("hall-marker-terraform").also(paper.server::addWorld)
            val plugin = paper.createSimplePlugin("HallMarkerTerraform")
            val scheduler = TestTaskScheduler()
            val executor = Executor(Runnable::run)
            val sourcePoint = CivPosition(1, 72, -1)
            val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(0, 71, 0), 0,
                "fixture/birch/town_hall_1", CivBox(CivPosition(-2, 70, -5), CivPosition(2, 82, -1)),
                CivPosition(0, 72, 0), BuildingPhase.SITE, sourcePoint)
            var city = CivsRules.validate(Settlement(UUID.randomUUID(), UUID.randomUUID(), "Hall fixture", native.uid,
                sourcePoint, 48, "birch", Stock(), listOf(hall), emptyList(),
                inventory = CivInventory(CivStorageMode.PHYSICAL), autonomy = CivAutonomy()))
            for (x in -2..2) for (z in -5..0) {
                native.loadChunk(x shr 4, z shr 4)
                for (y in 73..76) native.getBlockAt(x, y, z).type = Material.DIRT
            }
            fun block(p: CivPosition) = native.getBlockAt(p.x, p.y, p.z)
            val source = block(sourcePoint)
            source.type = Material.BARREL
            val stock = (source.state as Barrel).inventory
            stock.setItem(26, ItemStack(Material.DIAMOND, 13).apply {
                itemMeta = itemMeta.apply { displayName(Component.text("Original control item")) }
            })
            val originalSlots = stock.storageContents.map(CivResidentItem::of)
            fun target(y: Int) = CivsAutonomousGeometry.atHeight(hall, y).copy(phase = BuildingPhase.CONSTRUCTING)
            fun cells(building: CivBuilding) = buildList {
                for (y in building.bounds.min.y..building.bounds.max.y)
                    for (z in building.bounds.min.z..building.bounds.max.z)
                        for (x in building.bounds.min.x..building.bounds.max.x) {
                            val p = CivPosition(x, y, z)
                            val state = when {
                                p == building.control -> "minecraft:structure_void"
                                y == building.anchor.y -> "minecraft:birch_planks"
                                else -> "minecraft:air"
                            }
                            add(ArcSchematicBlock(ArcSchematicPoint(x, y, z), state, state == "minecraft:air", false))
                        }
            }
            fun <T> await(future: CompletableFuture<T>): T {
                repeat(120) { if (!future.isDone) scheduler.tick() }
                future.isDone shouldBe true
                return future.join()
            }
            LifecycleTaskScope(scheduler, true).use { tasks ->
                CivsWorld(plugin, tasks, { 0L }) { listOf(city) }.use { world ->
                    val authoredOverlap = target(73)
                    val overlapCells = cells(authoredOverlap).sortedBy { if (CivPosition(it.position.x, it.position.y, it.position.z) == sourcePoint) 0 else 1 }
                    CivsDiagnostics.rejection(shouldThrow<CompletionException> {
                        await(world.capture(city.id, city.owner, city.worldId, authoredOverlap, overlapCells, hall))
                    })?.reason shouldBe "hall-source-overlap"
                    val terrainOverlap = target(74)
                    terrainOverlap.bounds.contains(sourcePoint.x, sourcePoint.y, sourcePoint.z) shouldBe false
                    CivsDiagnostics.rejection(shouldThrow<CompletionException> {
                        await(world.captureManualFoundation(city.id, city.owner, city.worldId, terrainOverlap,
                            cells(terrainOverlap), executor, hall, autonomous = true))
                    })?.reason shouldBe "hall-source-overlap"
                    // With a +3 local median, the preferred grade would probe the old stock barrel. The
                    // normal original-height capture remains valid and must still be allowed to start.
                    for (x in -2..2) for (z in -5..0) for (y in 75..76) native.getBlockAt(x, y, z).type = Material.AIR
                    val lowerSurface = await(world.autonomousHeightField(city, hall, hall, localFootprint = true))
                    CivsAutonomousTerraform.options(city, listOf(CivsAutonomousCandidate(hall)), lowerSurface)
                        .first().building.anchor.y shouldBe 74
                    val originalDesign = target(71)
                    val originalCells = cells(originalDesign)
                    val snapshot = mockk<ArcSchematicSnapshot>()
                    every { snapshot.blocks() } returns originalCells.map { cell ->
                        cell.copy(position = ArcSchematicPoint(cell.position.x - originalDesign.anchor.x,
                            cell.position.y - originalDesign.anchor.y, cell.position.z - originalDesign.anchor.z))
                    }
                    val blueprint = CivBlueprint(originalDesign, city.worldId, snapshot, originalCells, emptyList(), byteArrayOf(1))
                    CivsAutonomousPlacement(mockk(), world, executor).use { placement ->
                        val fallback = await(placement.captureHallSite(city, blueprint))
                        fallback.building shouldBe originalDesign
                        fallback.blocks.none { it.position == sourcePoint } shouldBe true
                        fallback.terrain?.blocks.orEmpty().none { it.position == sourcePoint } shouldBe true
                        val prep = civsTerraformBatches(fallback).size
                        val rows = CivConstructionBatches(prep + civsMaterialBatches(fallback, atomicPairs = true).size, 16, atomicPairs = true)
                        val sameSiteOrder = CivConstruction(fallback.id, hall.id, 1, rows.total, 0, ConstructionPhase.READY_TO_PLACE,
                            Stock(), plannedBuilding = originalDesign, batches = rows, materials = CivsMaterials.required(fallback),
                            payment = CivConstructionPayment(), automaticPreparationBatches = prep)
                        CivsRules.reserveBuild(city, city.owner, originalDesign, sameSiteOrder).construction shouldBe sameSiteOrder
                    }
                    source.type shouldBe Material.BARREL
                    stock.storageContents.map(CivResidentItem::of) shouldBe originalSlots
                    for (x in -2..2) for (z in -5..0) for (y in 75..76) native.getBlockAt(x, y, z).type = Material.DIRT
                    val surface = await(world.autonomousHeightField(city, hall, hall, localFootprint = true))
                    surface.columns.size shouldBe 25
                    CivsAutonomousTerraform.options(city, listOf(CivsAutonomousCandidate(hall)), surface)
                        .first().building.anchor.y shouldBe 76
                    val raised = target(76)
                    val marker = requireNotNull(raised.control)
                    block(marker).type = Material.DIRT
                    val schematic = cells(raised)
                    val terrain = requireNotNull(await(world.captureManualFoundation(city.id, city.owner, city.worldId,
                        raised, schematic, executor, hall, autonomous = true)))
                    val markerCut = CivPlanBlock(marker, "minecraft:dirt", "minecraft:air")
                    terrain.blocks shouldBe listOf(markerCut)
                    val plan = await(world.capture(city.id, city.owner, city.worldId, raised, schematic, hall, terrain))
                    plan.blocks.none { it.position == sourcePoint || it.position == marker } shouldBe true
                    block(marker).type shouldBe Material.DIRT
                    val root = Files.createTempDirectory("hall-marker-terraform")
                    try {
                        CivsStore(root, executor).use { store ->
                            val bytes = byteArrayOf(1, 2, 3) // Capture identity fixture; decoding is outside this native scan test.
                            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                            store.saveSchematic(plan.id, bytes).join()
                            store.savePlan(plan.copy(capturedSha256 = sha)).join()
                            val saved = requireNotNull(store.loadPlan(plan.id).join())
                            saved.terrain?.blocks shouldBe listOf(markerCut)
                            val preparation = civsTerraformBatches(saved)
                            preparation.size shouldBe 1
                            val batches = CivConstructionBatches(preparation.size + civsMaterialBatches(saved, atomicPairs = true).size,
                                16, atomicPairs = true)
                            val order = CivConstruction(saved.id, hall.id, 1, batches.total, 0, ConstructionPhase.READY_TO_PLACE,
                                Stock(), plannedBuilding = raised, batches = batches, materials = CivsMaterials.required(saved),
                                payment = CivConstructionPayment(), automaticPreparationBatches = preparation.size)
                            city = CivsRules.reserveBuild(city, city.owner, raised, order)
                            city = CivsRules.payBatch(city, city.owner, order.id, 0, emptyMap())
                            val applied = mutableListOf<CivPlanBlock>()
                            block(marker).type = Material.STONE_BRICKS
                            CivsDiagnostics.rejection(shouldThrow<CompletionException> {
                                await(world.applyTerraform(saved, city.owner, listOf(markerCut), 0, { true }, applied::add))
                            })?.reason shouldBe "world-changed"
                            applied shouldBe emptyList()
                            block(marker).type shouldBe Material.STONE_BRICKS
                            block(marker).type = Material.DIRT
                            await(world.applyTerraform(saved, city.owner, listOf(markerCut), 0, { true }, applied::add))
                            city = CivsRules.completeBatch(city, city.owner, order.id)
                            applied shouldBe listOf(markerCut)
                            city.construction?.workDone shouldBe 1
                            city.construction?.materials shouldBe order.materials
                            city.building(hall.id) shouldBe hall
                            block(marker).type shouldBe Material.AIR
                            source.type shouldBe Material.BARREL
                            stock.storageContents.map(CivResidentItem::of) shouldBe originalSlots
                        }
                    } finally { root.toFile().deleteRecursively() }
                }
            }
        }
    }

    test("the existing control and inventory journals move all hall slots once and recover retirement before final debit") {
        MockBukkitTestRuntime.open().use { paper ->
            val native = CivsWorksiteWorldFixture("hall-control-handoff").also(paper.server::addWorld)
            val plugin = paper.createSimplePlugin("HallControlHandoff")
            val scheduler = TestTaskScheduler()
            val root = Files.createTempDirectory("hall-control-handoff")
            val old = CivPosition(1, 64, 1)
            val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(0, 63, 2), 0,
                "fixture/birch/town_hall_1", CivBox(CivPosition(0, 62, 0), CivPosition(2, 73, 1)),
                CivPosition(0, 64, 2), BuildingPhase.SITE, old)
            val hut = CivBuilding(UUID.randomUUID(), BuildingType.BUILDER_HUT, 1, CivPosition(9, 63, 8), 0,
                "fixture/birch/builder_hut_1", CivBox(CivPosition(8, 62, 7), CivPosition(10, 69, 8)),
                CivPosition(9, 64, 9), BuildingPhase.ACTIVE, CivPosition(10, 64, 8))
            val worker = CivWorker(UUID.randomUUID(), "Builder", hut.id, nextDeliveryAt = 1,
                items = List(CivsEquipment.SLOTS) { if (it == CivsEquipment.BAG) CivResidentItem("STONE", 1) else null })
            val sourceCity = CivsRules.validate(Settlement(UUID.randomUUID(), UUID.randomUUID(), "Hall handoff", native.uid,
                old, 48, "birch", Stock(), listOf(hall, hut), listOf(worker),
                inventory = CivInventory(CivStorageMode.PHYSICAL), autonomy = CivAutonomy()))
            val target = CivsAutonomousGeometry.atHeight(hall, 66).copy(phase = BuildingPhase.CONSTRUCTING)
            val receiver = requireNotNull(target.control)
            val order = CivConstruction(UUID.randomUUID(), hall.id, 1, 1, 0, ConstructionPhase.CONSTRUCTING,
                Stock(), plannedBuilding = target, batches = CivConstructionBatches(1, 16, atomicPairs = true),
                materials = mapOf("STONE" to 1L), payment = CivConstructionPayment())
            val reserved = CivsRules.reserveBuild(sourceCity, sourceCity.owner, target, order)
            // Domain-only earned builder row; this test exercises the native transfer, not Citizens arrival.
            var city = CivsRules.deliver(reserved, reserved.owner, worker.id, hut.id, 0, worker.nextDeliveryAt, worker.nextDeliveryAt)
            val last = requireNotNull(city.construction)
            last.phase shouldBe ConstructionPhase.READY_TO_PLACE
            shouldThrow<CivsRejection> { CivsRules.payBatch(city, city.owner, last.id, 0, last.materials.orEmpty()) }
                .reason shouldBe "control-location-changed"
            native.loadChunk(0, 0)
            fun block(p: CivPosition) = native.getBlockAt(p.x, p.y, p.z)
            fun items(p: CivPosition) = (block(p).state as Barrel).inventory
            block(old).type = Material.BARREL
            block(receiver).type shouldBe Material.AIR
            repeat(STORAGE_SLOTS) { slot ->
                items(old).setItem(slot, ItemStack(Material.DIAMOND, slot + 1).apply {
                    itemMeta = itemMeta.apply { displayName(Component.text("Exact hall slot $slot")) }
                })
            }
            val original = items(old).storageContents.map(CivResidentItem::of)
            val busy = mutableSetOf<UUID>()
            val unhealthy = mutableSetOf<UUID>()
            val staging = CompletableFuture<Unit>()
            var retirement = CompletableFuture<Unit>()
            var transfers = 0
            fun tick() { scheduler.tick(); paper.performTicks(1) }
            try {
                LifecycleTaskScope(scheduler, true).use { tasks ->
                    CivsStore(root, Executor(Runnable::run)).use { store ->
                        CivsStorage(plugin, tileEntities = { chunk -> listOf(old, receiver, requireNotNull(hut.control))
                            .filter { it.x shr 4 == chunk.x && it.z shr 4 == chunk.z && block(it).type == Material.BARREL }
                            .map { block(it).state }.toTypedArray() }).use { storage ->
                            CivsWorld(plugin, tasks, { 0L }) { listOf(city) }.use { world ->
                                fun publish(saved: Settlement): CompletableFuture<Unit> {
                                    city = saved
                                    storage.updateCity(saved)
                                    return CompletableFuture.completedFuture(Unit)
                                }
                                fun mutate(id: UUID, transform: (Settlement) -> Settlement): CompletableFuture<Settlement> {
                                    id shouldBe city.id
                                    return store.save(transform(city)).thenApply { saved -> publish(saved); saved }
                                }
                                store.save(sourceCity).join()
                                store.save(reserved).join()
                                store.save(city).join()
                                storage.replaceCities(listOf(city))
                                storage.foodCanModify = { _, _ -> true }
                                storage.relocationCurrentCity = { id -> city.takeIf { it.id == id } }
                                storage.persistControlStagingNative = { _, _ -> staging }
                                storage.persistControlRetirementNative = { _, _ -> retirement }
                                storage.persistCollectedNative = { _, _ -> CompletableFuture.completedFuture(Unit) }
                                val journal = CivsInventoryJournal(plugin, tasks, store, storage, { mapOf(city.id to city) }, busy,
                                    unhealthy, { false }, ::publish, ::mutate, {}, {}, { _, _, _ -> null }, {}, { _, _ -> city }, {}, { 0L })
                                val installations = CivsControlInstallations(plugin, world, storage, mockk(relaxed = true),
                                    { mapOf(city.id to city) }, busy, unhealthy, { true }, ::mutate)
                                fun relocations() = CivsControlRelocations(plugin, tasks, world, storage, installations,
                                    { CompletableFuture.completedFuture(emptyList()) }, { listOf(city) },
                                    { it.id !in busy && it.id !in unhealthy && !journal.pending(it.id) },
                                    { saved, building, from, to ->
                                        transfers++
                                        val id = UUID.randomUUID()
                                        storage.reserve(saved, id)
                                        val move = requireNotNull(storage.prepareControlRelocation(saved, building, from, to))
                                        val tx = CivInventoryTransaction(id, saved.id, saved.revision, saved.copy(revision = saved.revision + 1),
                                            emptyList(), containerRelocation = move).also(storage::seal)
                                        busy += saved.id
                                        journal.submitRelocation(saved, tx)
                                    }, ::mutate, { 10_000L })
                                relocations().use { owner ->
                                    val pending = owner.handoffHall(city, last)
                                    repeat(5) { tick() }
                                    city.building(hall.id) shouldBe hall
                                    items(old).storageContents.map(CivResidentItem::of) shouldBe original
                                    items(receiver).storageContents.all { it == null } shouldBe true
                                    store.load().join().single().pendingControls!!.single().position shouldBe receiver
                                    transfers shouldBe 0
                                    staging.complete(Unit)
                                    repeat(20) { tick() }
                                    transfers shouldBe 1
                                    block(old).type shouldBe Material.AIR
                                    items(receiver).storageContents.map(CivResidentItem::of) shouldBe original
                                    city.construction shouldBe last
                                    retirement.completeExceptionally(IOException("unknown retirement acknowledgement"))
                                    repeat(5) { tick() }
                                    pending.isCompletedExceptionally shouldBe true
                                }
                                city = store.load().join().single()
                                storage.updateCity(city)
                                city.pendingControls!!.size shouldBe 1
                                retirement = CompletableFuture.completedFuture(Unit)
                                relocations().use { owner -> owner.pulse(); repeat(20) { tick() }; owner.drained().join() }
                                city.pendingControls shouldBe emptyList()
                                city.building(hall.id) shouldBe hall.copy(control = receiver)
                                city.center shouldBe sourceCity.center
                                city.construction shouldBe last
                                transfers shouldBe 1
                                items(receiver).storageContents.map(CivResidentItem::of) shouldBe original
                                store.loadInventoryTransactions().join() shouldBe emptyList()
                                val paid = CivsRules.payBatch(city, city.owner, last.id, 0, last.materials.orEmpty())
                                paid.construction?.payment?.reservedBatch shouldBe 0
                            }
                        }
                    }
                }
            } finally { root.toFile().deleteRecursively() }
        }
    }
})
