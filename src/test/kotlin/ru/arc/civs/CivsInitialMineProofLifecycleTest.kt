package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.Material
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class CivsInitialMineProofLifecycleTest : FunSpec({
    test("natural initial opening completes through the service's real mine receipts") {
        initialMineLifecycle(Material.STONE, Material.STONE, openingExpected = true)
    }

    test("an exact retained initial cobblestone cap is available to the service-owned miner") {
        initialMineLifecycle(Material.COBBLESTONE, Material.COBBLESTONE, openingExpected = true)
    }

    test("a foreign cobblestone cap is not authorized by a retained natural-stone plan") {
        initialMineLifecycle(Material.COBBLESTONE, Material.STONE, openingExpected = false)
    }

    test("the shallow surface entrance and complete retained mouth wait for real initial-plan IO") {
        initialMineLifecycle(Material.COBBLESTONE, Material.COBBLESTONE, openingExpected = true,
            shallow = true, delayInitialPlan = true, restartFirstCap = true)
    }

    test("the paid upper shaft opens its three rows before any deeper spine work") {
        initialMineLifecycle(Material.COBBLESTONE, Material.COBBLESTONE, openingExpected = true,
            shallow = true, restartFirstCap = true, upperOnly = true)
    }
})

/** Constructed public bank, not a replay of native Auto1 terrain or an NPC-arrival test. */
private fun initialMineLifecycle(nativeCap: Material, recordedCap: Material, openingExpected: Boolean,
    shallow: Boolean = false, delayInitialPlan: Boolean = false, restartFirstCap: Boolean = false,
    upperOnly: Boolean = false) =
    failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("initial-mine-proof").also(paper.server::addWorld)
            val plugin = paper.createSimplePlugin("InitialMineProofLifecycle")
            var directory = Files.createTempDirectory("initial-mine-proof-")
            val directories = mutableListOf(directory)
            val direct = Executor(Runnable::run)
            var tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val io = InitialMineIo()
            var store = CivsStore(directory, io)
            var service: CivsService? = null
            try {
                val mine = spatialBuilding("nordic", "miner", 1, CivPosition(0, 75, 0), 90)
                val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
                val opening = "0/shaft/opening"
                val entrance = "0/shaft/entrance"
                plan.sections.getValue(opening).size shouldBe 27
                val openingCells = plan.sections.getValue(opening).map { CivsWorksiteLayouts.point(mine, plan.steps[it].point) }
                val cap = openingCells.filter { it.y == CivsWorksiteLayouts.point(mine, plan.bottom).y }
                cap.size shouldBe 9
                for (x in -4..4) for (z in -4..4) world.loadChunk(x, z)
                // The original controls isolate ownership. The shallow case has no six-block overburden.
                val top = if (shallow) mine.anchor.y + 1 else mine.anchor.y + 16
                seedMineNaturalCover(world, mine, top)
                if (shallow) {
                    val ordinal = plan.sections.getValue(entrance).first()
                    val local = plan.steps[ordinal].point
                    (top - CivsWorksiteLayouts.point(mine, local.copy(y = CivsMineSafety.ceiling(plan, ordinal, local))).y < 6) shouldBe true
                }
                // Explicit supported historical starting contract: paid spine and its native rungs.
                // No entrance/opening progress, seals or fabricated mining receipt are supplied.
                val spine = plan.sections.getValue("spine")
                val upperSpine = spine.takeWhile { plan.steps[it].point.y >= plan.transferFeetY }
                val initial = CivMinePlanState(CivsMinePlan.VERSION, 0, sectionSteps = emptyMap())
                fun native(local: CivPosition) = CivsWorksiteLayouts.point(mine, local).let { world.getBlockAt(it.x, it.y, it.z) }
                val lowerSpine = if (upperOnly) spine.drop(upperSpine.size).mapTo(hashSetOf()) {
                    CivsWorksiteLayouts.point(mine, plan.steps[it].point)
                } else emptySet()
                val state = if (!upperOnly) seedMinePaidSpine(world, mine, initial) else {
                    upperSpine.size shouldBe 4
                    upperSpine.forEach { ordinal ->
                        native(plan.steps[ordinal].point).blockData = Bukkit.createBlockData(plan.expectedAfter(ordinal, mine.rotation))
                    }
                    val (prefix, sections) = plan.normalizedProgress(0, mapOf("spine" to upperSpine.size.toLong()))
                    initial.copy(nextStep = prefix, sectionSteps = sections)
                }
                CivsMineProgress.valid(plan, state, mine) shouldBe true
                plan.sectionCount(entrance, state.nextStep, state.sectionSteps.orEmpty()) shouldBe 0L
                plan.sectionCount(opening, state.nextStep, state.sectionSteps.orEmpty()) shouldBe 0L
                val ladder = plan.sections.getValue("spine").first { plan.steps[it].after?.startsWith("minecraft:ladder") == true }
                for (dy in 0..2) {
                    val rung = CivsWorksiteLayouts.point(mine, plan.bottom.copy(y = plan.bottom.y + dy))
                    world.getBlockAt(rung.x, rung.y, rung.z).blockData = Bukkit.createBlockData(plan.expectedAfter(ladder, mine.rotation))
                    val backing = CivsWorksiteLayouts.point(mine, plan.backing.copy(y = plan.backing.y + dy))
                    world.getBlockAt(backing.x, backing.y, backing.z).type = Material.COBBLESTONE
                }
                cap.forEach { world.getBlockAt(it.x, it.y, it.z).type = nativeCap }
                // The large entry has more lights than one ordinary eight-torch supply trip.
                val worker = CivsEquipment.collectSupplies(spatialWorker(mine, "IRON_PICKAXE"), mapOf("TORCH" to 24L))
                val city = CivsRules.validate(spatialCity(world.uid, listOf(mine), worker).copy(
                    inventory = CivInventory(CivStorageMode.PHYSICAL),
                    settings = CivSettlementSettings(chunkLoading = false),
                    minePlans = mapOf(mine.id.toString() to state)))
                city.buildings.mapNotNull(CivBuilding::control).forEach {
                    world.getBlockAt(it.x, it.y, it.z).type = Material.BARREL
                }
                // Retained completed-building input, not payment proof created by this test.
                // The original CONSTRUCTING snapshot and current ACTIVE record share geometry.
                val retained = CivPlacementPlan(mine.id, city.id, world.uid,
                    mine.copy(phase = BuildingPhase.CONSTRUCTING), cap.map {
                        CivPlanBlock(it, "minecraft:air", recordedCap.createBlockData().asString)
                    }, "a".repeat(64))
                store.savePlan(retained).join() shouldBe retained
                store.save(city).join() shouldBe city
                store.loadPlan(mine.id).join() shouldBe retained
                var at = 1_000_000L
                lateinit var storage: CivsStorage
                // Only unused rendering/actor lifecycle is substituted. The store, world,
                // worksite, storage, permission checks and inventory journal are real.
                val citizens = mockk<CivsCitizens>(relaxed = true)
                every { citizens.closeAsync() } returns CompletableFuture.completedFuture(null)
                val renderer = mockk<CivsPreviewRenderer>(relaxed = true)
                every { renderer.drained() } returns CompletableFuture.completedFuture(null)
                val boundary = mockk<CivsBoundaryRenderer>(relaxed = true)
                every { boundary.drained() } returns CompletableFuture.completedFuture(null)
                fun createService(): CivsService {
                    storage = CivsStorage(plugin) { emptyArray() }
                    val access = CivsWorld(plugin, tasks) { service?.settlements() ?: listOf(city) }
                    return CivsService(plugin, tasks, CivsSettings(directory, setOf(world.name), 96,
                        500, 50_000, 4, 120, 48, 96), store, mockk(relaxed = true), direct,
                        worldAdapter = access, previewRenderer = renderer, citizenRuntime = citizens,
                        boundaryRenderer = boundary, storageAdapter = storage, supplyExecutor = direct, clock = { at })
                }
                var authority = createService()
                service = authority
                fun <T> await(future: CompletableFuture<T>): T {
                    repeat(80) { if (future.isDone) return future.join(); paper.performTicks(1) }
                    error("initial mine lifecycle future did not finish")
                }
                fun startService(delayPlan: Boolean) {
                    io.hold = delayPlan
                    val firstPlanRead = io.completed + 3
                    val starting = authority.start()
                    if (delayPlan) {
                        // Real bootstrap reads: journal, consumed items, then city. Leave the retained plan queued.
                        val nativeBefore = cap.associateWith { world.getBlockAt(it.x, it.y, it.z).blockData.asString }
                        repeat(80) {
                            if (io.completed < firstPlanRead) io.runNext()
                            paper.performTicks(1)
                        }
                        io.completed shouldBe firstPlanRead
                        (io.pending > 0) shouldBe true
                        starting.isDone shouldBe false
                        authority.ready() shouldBe false
                        nativeBefore.forEach { (point, data) ->
                            world.getBlockAt(point.x, point.y, point.z).blockData.asString shouldBe data
                        }
                        io.hold = false
                        while (io.runNext()) Unit
                    }
                    await(starting)
                    authority.ready() shouldBe true
                }
                startService(delayInitialPlan)
                fun serviceWorksites() = CivsService::class.java.getDeclaredField("worksites").also { it.isAccessible = true }
                    .get(authority) as CivsWorksites
                var runtime = serviceWorksites()
                fun restartService() {
                    // Copy actual durable before-city + pending-receipt bytes, never manufacture a mining receipt.
                    val next = Files.createTempDirectory("initial-mine-restart-")
                    directories.add(next)
                    Files.walk(directory).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach { source ->
                        val target = next.resolve(directory.relativize(source))
                        Files.createDirectories(target.parent)
                        Files.copy(source, target)
                    } }
                    authority.close()
                    tasks.close()
                    store.close()
                    io.discardPending()
                    directory = next
                    tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                    store = CivsStore(directory, io)
                    authority = createService()
                    service = authority
                    startService(delayPlan = true)
                    runtime = serviceWorksites()
                }
                // Existing service-test observation seam; never replace the service's owner or its cache.
                val trace = mutableListOf<String>()
                var refusal: Map<*, *>? = null
                fun blockedOpening(): Map<*, *>? {
                    val entry = (runtime.debug(setOf(worker.id))["entries"] as List<*>).singleOrNull() as? Map<*, *> ?: return null
                    val sections = entry["mineSections"] as? Map<*, *> ?: return null
                    val blocked = sections["blocked"] as? Map<*, *> ?: return null
                    return (blocked["entries"] as List<*>).filterIsInstance<Map<*, *>>()
                        .firstOrNull { it["section"] == opening }
                }
                fun current() = authority.settlements().single()
                fun openingCount(): Long = current().minePlans!!.getValue(mine.id.toString()).let {
                    plan.sectionCount(opening, it.nextStep, it.sectionSteps.orEmpty())
                }
                val maximumReceipts = plan.sections.getValue(entrance).size + plan.sections.getValue(opening).size +
                    requireNotNull(plan.entranceLanding).torches.size + 16
                var restarted = false
                var earlyWitnessChecked = false
                for (receipt in 0 until maximumReceipts) {
                    if (openingCount() == 27L) break
                    var work: CivWorldWork? = null
                    for (pulse in 0 until 512) {
                        val live = current()
                        runtime.pulse(listOf(live))
                        refusal = blockedOpening()
                        if (refusal != null) break
                        work = runtime.plan(live, live.workers.single())
                        if (work != null) break
                    }
                    if (refusal != null) break
                    val selected = withClue("before cap: ${runtime.debug()}") { requireNotNull(work) }
                    val live = current()
                    if (upperOnly) {
                        if (receipt == 0) selected.miningOrdinal shouldBe plan.sections.getValue(entrance).first()
                        selected.changes.none { it.position in lowerSpine } shouldBe true
                        if (!earlyWitnessChecked && selected.miningOrdinal == plan.sections.getValue(opening).first()) {
                            val active = selected.changes.single().position
                            val floor = cap.first { it.x != active.x || it.z != active.z }
                                .copy(y = CivsWorksiteLayouts.point(mine, plan.bottom).y - 3)
                                .let { world.getBlockAt(it.x, it.y, it.z) }
                            val priorFloor = floor.blockData
                            for (unsafe in listOf(Material.AIR, Material.WATER)) {
                                floor.type = unsafe
                                shouldThrow<CivsRejection> { runtime.validateMineReceiptNative(live, selected) }.reason shouldBe "mine-early-floor"
                            }
                            floor.blockData = priorFloor
                            val rung = native(plan.steps[upperSpine.last()].point)
                            val priorRung = rung.blockData
                            rung.type = Material.AIR
                            shouldThrow<CivsRejection> { runtime.validateMineReceiptNative(live, selected) }.reason shouldBe "mine-upper-access"
                            rung.blockData = priorRung
                            runtime.invalidate(worker.id)
                            earlyWitnessChecked = true
                            continue
                        }
                    }
                    runtime.validate(live, selected)
                    citizenStandable(world, selected.standing) shouldBe true
                    citizenPacketBodyClear(world, selected.standing.point(), false) shouldBe true
                    at += 5_000
                    var completed: Settlement? = null
                    val recoverCap = restartFirstCap && !restarted && selected.changes.any {
                        it.position in cap && it.before == "minecraft:cobblestone" && it.after == "minecraft:air"
                    }
                    if (recoverCap) { io.pending shouldBe 0; io.hold = true }
                    val mutation = authority.inventoryMutation(city.id, { fresh ->
                        runtime.validate(fresh, selected)
                        completed = CivsRules.finishWorldWork(fresh, selected, at)
                        storage.prepareWork(fresh, mine.id, selected.storageInputs(fresh.workers.single()),
                            emptyMap(), selected.changes, selected).also { requireNotNull(it.mine) }
                    }) { _, inventory -> requireNotNull(completed).copy(inventory = inventory) }
                    val after = if (!recoverCap) await(mutation) else {
                        io.runNext() shouldBe true // The real journal file is now durable; its native callback has not run.
                        mutation.isDone shouldBe false
                        val durable = CivsStore(directory, direct).use { it.loadInventoryTransactions().join().single() }
                        requireNotNull(durable.mine).work shouldBe selected
                        durable.beforeRevision shouldBe live.revision
                        selected.changes.forEach { cell ->
                            world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.before
                        }
                        restartService()
                        repeat(80) {
                            if (store.loadInventoryTransactions().join().isNotEmpty()) paper.performTicks(1)
                        }
                        store.loadInventoryTransactions().join() shouldBe emptyList()
                        current() shouldBe durable.after
                        val recovered = current()
                        restartService()
                        paper.performTicks(40)
                        current() shouldBe recovered
                        store.loadInventoryTransactions().join() shouldBe emptyList()
                        restarted = true
                        current()
                    }
                    after.workers.single().completedCycles shouldBe live.workers.single().completedCycles + 1
                    selected.changes.forEach { cell ->
                        world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.after
                    }
                    store.loadInventoryTransactions().join() shouldBe emptyList()
                    trace += "${selected.status}:${selected.miningOrdinal}:${selected.changes.map { it.before + "->" + it.after }}"
                    runtime.invalidate(worker.id, selected)
                }
                restarted shouldBe restartFirstCap
                withClue("cap=$nativeCap retained=$recordedCap refusal=$refusal receipts=${trace.size} last=${trace.takeLast(12)}") {
                    val finalState = current().minePlans!!.getValue(mine.id.toString())
                    plan.sectionCount(entrance, finalState.nextStep, finalState.sectionSteps.orEmpty()) shouldBe
                        plan.sections.getValue(entrance).size.toLong()
                    if (openingExpected) {
                        openingCount() shouldBe 27L
                        cap.forEach { world.getBlockAt(it.x, it.y, it.z).type shouldBe Material.AIR }
                        if (upperOnly) {
                            earlyWitnessChecked shouldBe true
                            plan.sectionCount("spine", finalState.nextStep, finalState.sectionSteps.orEmpty()) shouldBe upperSpine.size.toLong()
                            openingCells.forEach { world.getBlockAt(it.x, it.y, it.z).type shouldBe Material.AIR }
                            lowerSpine.all { world.getBlockAt(it.x, it.y, it.z).type == Material.STONE } shouldBe true
                        }
                    } else {
                        refusal?.get("reason") shouldBe "mine-foreign-block"
                        openingCount() shouldBe 18L
                        cap.forEach { world.getBlockAt(it.x, it.y, it.z).type shouldBe Material.COBBLESTONE }
                    }
                }
            } finally {
                service?.close()
                tasks.close()
                store.close()
                directories.forEach { it.toFile().deleteRecursively() }
            }
        }
    }

/** Controllable real-store IO only; service publication and native callbacks remain production code. */
private class InitialMineIo : Executor {
    var hold = false
    var completed = 0
        private set
    private val queued = ArrayDeque<Runnable>()
    val pending get() = queued.size
    override fun execute(command: Runnable) {
        if (hold) queued.addLast(command) else { command.run(); completed++ }
    }
    fun runNext(): Boolean = queued.removeFirstOrNull()?.let { it.run(); completed++; true } ?: false
    fun discardPending() { queued.clear() }
}
