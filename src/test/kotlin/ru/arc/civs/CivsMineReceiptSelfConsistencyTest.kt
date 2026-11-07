package ru.arc.civs

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class CivsMineReceiptSelfConsistencyTest : FunSpec({
    fun receiptSelfConsistency(diagnosticRefusal: Boolean = false, authoredWallDependency: Boolean = false,
        shellDrift: String? = null) {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("mine-receipt-self-consistency").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("MineReceiptSelfConsistency")
                val publicMine = spatialBuilding("nordic", "miner", 1, CivPosition(0, 75, 0), 90)
                val id = "fixture/nordic/receipt-self-consistency1"
                CivsWorksiteLayouts.installCatalog(mapOf(id to receiptNordicSite(publicMine)))
                try {
                    // Actual model from 2026-11-05, translated only in X/Z and with synthetic identity.
                    val mine = publicMine.copy(schematicId = id,
                        bounds = CivBox(CivPosition(1, 72, -6), CivPosition(23, 99, 14)),
                        entrance = CivPosition(0, 76, 0), control = CivPosition(16, 76, 7))
                    val state = CivMinePlanState(7, 4, sectionSteps = mapOf("spine" to 60L),
                        excavation = CivPosition(8, -12, -12), seals = mapOf(
                            "10,-12,-11" to "minecraft:cobblestone", "10,-12,-12" to "minecraft:cobblestone",
                            "10,-13,-11" to "minecraft:cobblestone", "10,-13,-12" to "minecraft:cobblestone"))
                    val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
                    CivsMineProgress.valid(geometry, state, mine) shouldBe true
                    val worker = spatialWorker(mine, "IRON_PICKAXE").copy(completedCycles = 73)
                    val city = spatialCity(world.uid, listOf(mine), worker).copy(
                        inventory = CivInventory(CivStorageMode.PHYSICAL), minePlans = mapOf(mine.id.toString() to state))
                    for (x in -4..4) for (z in -4..4) world.loadChunk(x, z)
                    // Constructed natural bank, not a replay of the private native capture. Its top
                    // leaves the upper landings shallow and gives floor 2 its required six-block cover.
                    seedMineNaturalCover(world, mine, top = 70)
                    fun put(local: CivPosition, data: String) {
                        val point = CivsWorksiteLayouts.point(mine, local)
                        world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(data)
                    }
                    for ((ordinal, step) in geometry.steps.withIndex()) {
                        if (geometry.completed(ordinal, state.nextStep, state.sectionSteps.orEmpty()))
                            put(step.point, geometry.expectedAfter(ordinal, mine.rotation))
                    }
                    // Authored entrance access is native ladder/backing, not additional paid progress.
                    val ladder = geometry.sections.getValue("spine").first { geometry.steps[it].after?.startsWith("minecraft:ladder") == true }
                    for (dy in 0..2) {
                        put(geometry.bottom.copy(y = geometry.bottom.y + dy), geometry.expectedAfter(ladder, mine.rotation))
                        put(geometry.backing.copy(y = geometry.backing.y + dy), "minecraft:cobblestone")
                    }
                    state.seals.orEmpty().forEach { (key, data) ->
                        val p = key.split(',').map(String::toInt)
                        put(CivPosition(p[0], p[1], p[2]), data)
                    }
                    val exposed = if (authoredWallDependency) CivPosition(9, -12, -10) else CivPosition(9, -11, -8)
                    val shell = exposed.copy(x = 10)
                    val cave = if (diagnosticRefusal || authoredWallDependency)
                        (-12..-8).map { z -> exposed.copy(z = z) } + shell else emptyList()
                    // Constructed one-high cave, not a private native replay. The head-row case
                    // remains unsafe. The body-row case pins the 2026-11-06 native rejection:
                    // its AIR shell is itself exact pending body clearance in this same landing.
                    cave.forEach { put(it, "minecraft:air") }
                    LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
                        CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                            val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, nanoTime = { 0L })
                            repeat(512) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
                            val work = withClue(runtime.debug(setOf(worker.id))) { requireNotNull(runtime.plan(city, worker)) }
                            work.status shouldBe "digging-mine"
                            work.miningStep shouldBe 4L
                            geometry.section(requireNotNull(work.miningOrdinal)) shouldBe "2/shaft/landing"
                            val cell = work.changes.single()
                            CivsMinePlan.localPoint(mine, cell.position) shouldBe CivPosition(8, -11, -11)
                            cell.before shouldBe "minecraft:stone"
                            cell.after shouldBe "minecraft:air"
                            val beforeItems = worker.items?.toList()
                            if (authoredWallDependency) {
                                work.miningOrdinal shouldBe 771
                                work.miningExcavation shouldBe state.excavation
                                geometry.cutOrdinals.getValue(shell).filter { geometry.section(it) == "2/shaft/landing" } shouldBe listOf(834)
                                geometry.cutOrdinals.getValue(shell.copy(y = shell.y - 1))
                                    .filter { geometry.section(it) == "2/shaft/landing" } shouldBe listOf(835)
                                // The selector must not send the actor to a task its own unchanged native guard refuses.
                                runtime.validate(city, work)
                                if (shellDrift != null) {
                                    // A refusal invalidates the scan. Each drift has its own fresh runtime;
                                    // never reuse that refused task or count a missing scan as guard coverage.
                                    put(shell, shellDrift)
                                    val failure = shouldThrow<CivsRejection> { runtime.validate(city, work) }
                                    failure.reason shouldBe "stale-worksite"
                                    val source = requireNotNull(CivsDiagnostics.rejectionSource(failure))
                                    source.startsWith("CivsMineWorksite.kt:") shouldBe true
                                    source.endsWith(":validateLandingClearanceFrontier") shouldBe true
                                }
                            }
                            if (diagnosticRefusal) {
                                work.miningExcavation shouldBe state.excavation
                                val failure = shouldThrow<CivsRejection> { runtime.validate(city, work) }
                                failure.reason shouldBe "stale-worksite"
                                failure.values shouldBe mapOf("frontier" to "landing",
                                    "exposed" to "9,-11,-8", "block" to "10,-11,-8", "want" to "COBBLESTONE",
                                    "dep" to "false", "air" to "true", "liq" to "false", "occ" to "false", "grav" to "false",
                                    "ord" to requireNotNull(work.miningOrdinal).toString(), "clear" to "-")
                                (failure.values.toString().length <= 240) shouldBe true
                                val source = requireNotNull(CivsDiagnostics.rejectionSource(failure))
                                source.startsWith("CivsMineWorksite.kt:") shouldBe true
                                source.endsWith(":validateLandingClearanceFrontier") shouldBe true
                            } else if (shellDrift == null) {
                                // The original sound native fixture still admits its selected receipt.
                                runtime.validate(city, work)
                            }
                            world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.before
                            cave.forEach { local ->
                                val p = CivsWorksiteLayouts.point(mine, local)
                                world.getBlockAt(p.x, p.y, p.z).blockData.asString shouldBe
                                    if (local == shell && shellDrift != null) shellDrift else "minecraft:air"
                            }
                            state.seals.orEmpty().forEach { (key, data) ->
                                val xyz = key.split(',').map(String::toInt)
                                val p = CivsWorksiteLayouts.point(mine, CivPosition(xyz[0], xyz[1], xyz[2]))
                                world.getBlockAt(p.x, p.y, p.z).blockData.asString shouldBe data
                            }
                            // Items retain their exact equipment data, including tool wear.
                            city.workers.single().items shouldBe beforeItems
                            city.workers.single().completedCycles shouldBe 73L
                            city.minePlans?.get(mine.id.toString()) shouldBe state
                            if (authoredWallDependency && shellDrift == null)
                                landingDependencyReceipts(paper, plugin, tasks, world, city, mine, geometry, shell)
                        }
                    }
                } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
            }
        }
    }

    test("a selected V7 Nordic second landing passes receipt validation on unchanged native blocks") {
        receiptSelfConsistency()
    }

    test("a refused old landing frontier reports only bounded context without receipt side effects") {
        receiptSelfConsistency(diagnosticRefusal = true)
    }

    test("a real selected landing cut validates when its old AIR wall is exact pending body clearance") {
        receiptSelfConsistency(authoredWallDependency = true)
    }

    test("liquid drift in the pending landing body wall rejects the original selected cut") {
        receiptSelfConsistency(authoredWallDependency = true, shellDrift = "minecraft:water[level=0]")
    }

    test("a foreign block in the pending landing body wall rejects the original selected cut") {
        receiptSelfConsistency(authoredWallDependency = true, shellDrift = "minecraft:stone_bricks")
    }
})

/** Internal service receipt boundary, not an NPC-arrival test. No future work or mining receipt is fabricated. */
private fun landingDependencyReceipts(paper: MockBukkitTestRuntime, plugin: Plugin, tasks: LifecycleTaskScope,
    world: CivsWorksiteWorldFixture, initial: Settlement, mine: CivBuilding, plan: CivsMinePlan, wall: CivPosition) {
    val directory = Files.createTempDirectory("mine-landing-dependency-")
    val direct = Executor(Runnable::run)
    var service: CivsService? = null
    try {
        CivsStore(directory, direct).use { store ->
            // Control membership comes from the saved building record. Setting type also
            // installs MockBukkit's real Barrel state; BlockData alone leaves its old state.
            val controls = initial.buildings.mapNotNull(CivBuilding::control).associateWith { point ->
                world.getBlockAt(point.x, point.y, point.z).also { it.type = Material.BARREL }
            }
            val control = requireNotNull(mine.control)
            val source = (controls.getValue(control).state as Barrel).inventory
            // A supported ordinary collection stance, separate from the retained underground bank.
            world.getBlockAt(control.x + 1, control.y - 1, control.z).type = Material.COBBLESTONE
            world.getBlockAt(control.x + 1, control.y, control.z).type = Material.AIR
            world.getBlockAt(control.x + 1, control.y + 1, control.z).type = Material.AIR
            val initialSupply = mapOf("COBBLESTONE" to 512L, "LADDER" to 128L, "TORCH" to 128L)
            var slot = 0
            initialSupply.forEach { (material, count) ->
                var remaining = count
                while (remaining > 0) {
                    val amount = minOf(64L, remaining).toInt()
                    source.setItem(slot++, ItemStack(Material.valueOf(material), amount))
                    remaining -= amount
                }
            }
            store.save(initial).join() shouldBe initial
            val storage = CivsStorage(plugin) { chunk ->
                controls.values.filter { it.x shr 4 == chunk.x && it.z shr 4 == chunk.z }
                    .map { it.state }.toTypedArray()
            }
            val access = CivsWorld(plugin, tasks) { service?.settlements() ?: listOf(initial) }
            val citizens = mockk<CivsCitizens>(relaxed = true)
            every { citizens.closeAsync() } returns CompletableFuture.completedFuture(null)
            val renderer = mockk<CivsPreviewRenderer>(relaxed = true)
            every { renderer.drained() } returns CompletableFuture.completedFuture(null)
            val boundary = mockk<CivsBoundaryRenderer>(relaxed = true)
            every { boundary.drained() } returns CompletableFuture.completedFuture(null)
            var at = 1_000_000L
            val authority = CivsService(plugin, tasks, CivsSettings(directory, setOf(world.name), 96,
                500, 50_000, 4, 120, 48, 96), store, mockk(relaxed = true), direct,
                worldAdapter = access, previewRenderer = renderer, citizenRuntime = citizens,
                boundaryRenderer = boundary, storageAdapter = storage, supplyExecutor = direct, clock = { at })
            service = authority
            fun <T> await(future: CompletableFuture<T>): T {
                repeat(160) { if (future.isDone) return future.join(); paper.performTicks(1) }
                error("landing dependency service future did not finish")
            }
            await(authority.start())
            val runtime = CivsService::class.java.getDeclaredField("worksites").also { it.isAccessible = true }
                .get(authority) as CivsWorksites
            fun current() = authority.settlements().single()
            fun proof() = current().minePlans!!.getValue(mine.id.toString())
            fun completed(ordinal: Int) = proof().let { plan.completed(ordinal, it.nextStep, it.sectionSteps.orEmpty()) }
            fun progressClue(): String {
                val worker = current().workers.single()
                val state = proof()
                val carried = CivsLogistics.QUARRY_SUPPLIES.keys.associateWith { CivsEquipment.plainCount(worker, it) }
                return "cycle=${worker.completedCycles} carried=$carried step=${state.nextStep} " +
                    "sections=${state.sectionSteps.orEmpty().entries.take(8)} seals=${state.seals.orEmpty().size}"
            }
            fun nextWork(): CivWorldWork {
                try {
                    repeat(512) {
                        val city = current()
                        runtime.pulse(listOf(city))
                        runtime.plan(city, city.workers.single())?.let { return it }
                    }
                } catch (failure: CivsRejection) {
                    throw IllegalStateException("landing dependency selection rejected: ${failure.reason} " +
                        "values=${failure.values.entries.take(8)} ${progressClue()}", failure)
                }
                error("no valid landing dependency work: ${progressClue()} ${runtime.debug(setOf(initial.workers.single().id))}")
            }
            fun native(local: CivPosition): String = CivsWorksiteLayouts.point(mine, local).let { point ->
                world.getBlockAt(point.x, point.y, point.z).blockData.asString
            }
            fun pendingLandingClue(): String {
                val state = proof()
                val count = plan.sectionCount("2/shaft/landing", state.nextStep, state.sectionSteps.orEmpty())
                val ordinal = plan.sections.getValue("2/shaft/landing").getOrNull(count.toInt())
                    ?: return "landing2=$count complete"
                val point = plan.steps[ordinal].point
                val neighbours = listOf(point, point.copy(x = point.x - 1), point.copy(x = point.x + 1),
                    point.copy(y = point.y - 1), point.copy(y = point.y + 1),
                    point.copy(z = point.z - 1), point.copy(z = point.z + 1))
                return "landing2=$count ordinal=$ordinal point=$point expected=${plan.expectedAfter(ordinal, mine.rotation)} " +
                    "native=${neighbours.associateWith(::native)}"
            }
            storage.buildingAvailable(current(), mine.id) shouldBe initialSupply
            val originalCycles = current().workers.single().completedCycles
            val supplyDebits = mutableMapOf<String, Long>()
            var supplyReceipts = 0
            var receipts = 0
            var actualLowerBodyCut = false
            var continuedAfterClearance = false
            val shellKey = CivsMineProgress.key(wall)
            var temporaryShellSeals = 0
            var temporaryShellCuts = 0
            var pendingTemporaryShell = false
            var lastLandingCount = 0L
            var landingChangedAt = 0
            var landingPlateau: String? = null
            var lastWorkerDebug = emptyMap<String, Any?>()
            while (!continuedAfterClearance && receipts < 128) {
                val clearedBeforeReceipt = completed(834) && completed(835)
                val work = nextWork()
                // Capture before invalidation: the explicit worker has a real cached task here.
                lastWorkerDebug = runtime.debug(setOf(initial.workers.single().id), setOf(mine.id))
                val landingCount = proof().let { plan.sectionCount("2/shaft/landing", it.nextStep, it.sectionSteps.orEmpty()) }
                if (landingCount != lastLandingCount) {
                    lastLandingCount = landingCount
                    landingChangedAt = receipts
                    landingPlateau = null
                } else if (landingPlateau == null && receipts - landingChangedAt >= 16) {
                    landingPlateau = "receipts=$receipts ${pendingLandingClue()} worker=$lastWorkerDebug"
                }
                if (receipts == 0) work.miningOrdinal shouldBe 771
                runtime.validate(current(), work)
                val before = current()
                val shellChange = work.changes.singleOrNull { CivsMinePlan.localPoint(mine, it.position) == wall }
                val sealingShell = shellChange?.let { it.before == "minecraft:air" && it.after == "minecraft:cobblestone" } == true
                val cuttingShell = shellChange?.let { it.before == "minecraft:cobblestone" && it.after == "minecraft:air" } == true
                withClue("receipts=$receipts ordinal=${work.miningOrdinal} section=${work.miningOrdinal?.let(plan::section)} proof=${proof()} changes=${work.changes}") {
                    if (clearedBeforeReceipt) {
                        // A completed body passage must stay open through every later receipt.
                        native(wall) shouldBe "minecraft:air"
                        native(wall.copy(y = wall.y - 1)) shouldBe "minecraft:air"
                        work.changes.none { CivsMinePlan.localPoint(mine, it.position) == wall.copy(y = wall.y - 1) &&
                            it.after == "minecraft:cobblestone" } shouldBe true
                        sealingShell shouldBe false
                    } else if (native(wall) == "minecraft:cobblestone") {
                        pendingTemporaryShell shouldBe true
                        proof().seals?.get(shellKey) shouldBe "minecraft:cobblestone"
                    } else native(wall) shouldBe "minecraft:air"
                    if (sealingShell) {
                        work.status shouldBe "securing-mine"
                        work.inputs["COBBLESTONE"] shouldBe 1L
                        pendingTemporaryShell shouldBe false
                    }
                    if (cuttingShell) {
                        work.status shouldBe "digging-mine"
                        work.miningOrdinal shouldBe 834
                        pendingTemporaryShell shouldBe true
                        proof().seals?.get(shellKey) shouldBe "minecraft:cobblestone"
                    }
                }
                at += 5_000
                var finished: Settlement? = null
                val saved = await(authority.inventoryMutation(initial.id, { fresh ->
                    runtime.validate(fresh, work)
                    finished = CivsRules.finishWorldWork(fresh, work, at)
                    storage.prepareWork(fresh, mine.id, work.storageInputs(fresh.workers.single()), emptyMap(), work.changes, work)
                        .also { if (work.miningStep != null) requireNotNull(it.mine) else it.mine shouldBe null }
                }) { _, inventory -> requireNotNull(finished).copy(inventory = inventory) })
                saved.workers.single().completedCycles shouldBe before.workers.single().completedCycles + 1
                saved.workers.single().items shouldBe requireNotNull(finished).workers.single().items
                if (sealingShell) {
                    // A temporary safety wall is paid and owned by this actual durable receipt.
                    CivsEquipment.plainCount(saved.workers.single(), "COBBLESTONE") shouldBe
                        CivsEquipment.plainCount(before.workers.single(), "COBBLESTONE") - 1
                    proof().seals?.get(shellKey) shouldBe "minecraft:cobblestone"
                    temporaryShellSeals++
                    pendingTemporaryShell = true
                }
                if (cuttingShell) {
                    proof().seals?.get(shellKey) shouldBe null
                    temporaryShellCuts++
                    pendingTemporaryShell = false
                }
                if (work.status == "collecting-supplies") {
                    supplyReceipts++
                    work.storageInputs(before.workers.single()).forEach { (material, amount) ->
                        supplyDebits[material] = supplyDebits.getOrDefault(material, 0L) + amount
                    }
                }
                work.changes.forEach { cell ->
                    world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.after
                }
                if (work.miningOrdinal == 835 && work.changes.any {
                        CivsMinePlan.localPoint(mine, it.position) == wall.copy(y = wall.y - 1) &&
                            it.before == "minecraft:stone" && it.after == "minecraft:air" }) actualLowerBodyCut = true
                store.loadInventoryTransactions().join() shouldBe emptyList()
                native(wall) shouldBe if (pendingTemporaryShell) "minecraft:cobblestone" else "minecraft:air"
                if (completed(834) && completed(835)) {
                    pendingTemporaryShell shouldBe false
                    native(wall) shouldBe "minecraft:air"
                    native(wall.copy(y = wall.y - 1)) shouldBe "minecraft:air"
                }
                runtime.invalidate(initial.workers.single().id, completedWork = work)
                receipts++
                if (clearedBeforeReceipt) continuedAfterClearance = true
            }
            withClue("receipts=$receipts shellSeals=$temporaryShellSeals shellCuts=$temporaryShellCuts " +
                "proof=${proof()} ${pendingLandingClue()} plateau=$landingPlateau lastWorker=$lastWorkerDebug") {
                completed(834) shouldBe true
                completed(835) shouldBe true
                actualLowerBodyCut shouldBe true
                continuedAfterClearance shouldBe true
                temporaryShellCuts shouldBe temporaryShellSeals
                pendingTemporaryShell shouldBe false
                storage.buildingAvailable(current(), mine.id) shouldBe initialSupply.mapValues { (material, count) ->
                    count - supplyDebits.getOrDefault(material, 0L)
                }.filterValues { it > 0 }
                plan.builtStanding(wall.copy(y = wall.y - 1), proof().nextStep, proof().sectionSteps.orEmpty()) shouldBe true
                native(wall) shouldBe "minecraft:air"
                native(wall.copy(y = wall.y - 1)) shouldBe "minecraft:air"
                current().workers.single().completedCycles shouldBe originalCycles + receipts
                plan.sectionCount("0/shaft/entrance", proof().nextStep, proof().sectionSteps.orEmpty()) shouldBe 0L
                plan.sectionCount("0/shaft/opening", proof().nextStep, proof().sectionSteps.orEmpty()) shouldBe 0L
                // Both pending body ordinals are now complete: another valid selection proves the wall no longer needs deferral.
                runtime.validate(current(), nextWork())
            }
            println("mine_landing_dependency_receipts receipts=$receipts temporary_shell_seals=$temporaryShellSeals " +
                "owned_shell_cuts=$temporaryShellCuts supply_receipts=$supplyReceipts supply_debits=$supplyDebits")
        }
    } finally {
        service?.close()
        directory.toFile().deleteRecursively()
    }
}

/** The shipped public layout matches the curated mine after this verified half-turn and translation. */
private fun receiptNordicSite(publicMine: CivBuilding) = requireNotNull(CivsWorksiteLayouts.site(publicMine)).deepCopy().apply {
    fun point(value: JsonElement) = CivsWorksiteLayouts.vector(value).let { p ->
        JsonArray().apply { add(4 - p.x); add(p.y - 1); add(-23 - p.z) }
    }
    val mine = getAsJsonObject("mine")
    for (key in listOf("ladderBottom", "shaftCenter", "firstSubgroundPoint")) mine.add(key, point(mine[key]))
    mine.addProperty("ladderFacing", "south")
    mine.add("shaftFootprint", JsonArray().apply {
        mine.getAsJsonArray("shaftFootprint").forEach { entry ->
            add(JsonArray().apply { add(4 - entry.asJsonArray[0].asInt); add(-23 - entry.asJsonArray[1].asInt) })
        }
    })
    mine.getAsJsonArray("floors").forEach { entry ->
        val floor = entry.asJsonObject
        floor.addProperty("feetY", floor["feetY"].asInt - 1)
        floor.add("landing", point(floor["landing"]))
        for (key in listOf("floorCells", "torchCells"))
            floor.add(key, JsonArray().apply { floor.getAsJsonArray(key).forEach { add(point(it)) } })
    }
    add("schematicBounds", JsonParser.parseString("""{"min":[-6,-3,-23],"max":[14,24,-1]}"""))
}
