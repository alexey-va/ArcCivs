package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executor

class CivsMineV6MigrationTest : FunSpec({
    test("canonical progress and an ordinary journal reject a stale V5 receipt after a fresh V6 plan") {
        val (city, work) = frozenV5Receipt(UUID.randomUUID())
        val mine = requireNotNull(city.building(work.buildingId))
        val old = city.minePlans!!.getValue(mine.id.toString())
        val historical = CivsMineProgress.after(mine, old, null, work)
        val after = city.copy(minePlans = mapOf(mine.id.toString() to historical))
        CivMineReceipt(mine, old, null, work).validated(after)
        val current = CivMinePlanState(6, 0, sectionSteps = emptyMap())
        CivsMineProgress.valid(requireNotNull(CivsMinePlan.forBuilding(mine, 6)), current, mine) shouldBe true
        shouldThrow<IllegalArgumentException> { CivsMineProgress.after(mine, current, null, work) }
        shouldThrow<IllegalArgumentException> { CivMineReceipt(mine, current, null, work).validated(after) }
    }

    test("initial native spine proof is bounded by at most sixty-eight cells for every supported mine") {
        for (style in listOf("medievaloak", "nordic", "caledonia")) for (level in 1..3) {
            val mine = spatialBuilding(style, "miner", level, CivPosition(0, 70, 0))
            val plan = requireNotNull(CivsMinePlan.forBuilding(mine, 6))
            val spine = plan.sections.getValue("spine")
            spine.size shouldBe listOf(52, 60, 68)[level - 1]
            spine.forEach { CivsMineSafety.requiredCover(plan, it, plan.steps[it].point) shouldBe 0 }
            val old = CivMinePlanState(5, 0, sectionSteps = emptyMap())
            val work = v6SpineReceipt(mine, spine.last())
            CivsMineProgress.initialSpineAccess(mine, old, work).size shouldBe spine.size - 1
        }
    }

    test("same-level import contains only contiguous fully matching paid V5 spine descriptors") {
        val mine = spatialBuilding("caledonia", "miner", 1, CivPosition(-1844, 70, -2696), 180)
        val old = requireNotNull(CivsMinePlan.forBuilding(mine, 5))
        val plan = requireNotNull(CivsMinePlan.forBuilding(mine, 6))
        val before = sparseV5Progress()
        val retained = CivsMineProgress.retainedSpine(mine, before)
        val count = retained.getValue("spine").toInt()
        count shouldBe old.sections.getValue("spine").size
        val paid = old.sections.getValue("spine").filter { old.completed(it, before.nextStep, before.sectionSteps.orEmpty()) }
        for ((current, predecessor) in plan.sections.getValue("spine").take(count).zip(paid)) {
            plan.steps[current] shouldBe old.steps[predecessor]
            plan.section(current) shouldBe old.section(predecessor)
            plan.expectedAfter(current, mine.rotation) shouldBe old.expectedAfter(predecessor, mine.rotation)
        }
        (plan.steps[plan.sections.getValue("spine")[count]] in paid.map(old.steps::get)) shouldBe false
        CivsMineProgress.retainedSpine(mine, CivMinePlanState(5, 1, sectionSteps = emptyMap())) shouldBe mapOf("spine" to 1L)
        val interrupted = old.normalizedProgress(0, mapOf("spine" to (count - 2).toLong()))
        CivsMineProgress.retainedSpine(mine, CivMinePlanState(5, interrupted.first,
            sectionSteps = interrupted.second)) shouldBe mapOf("spine" to (count - 2).toLong())
        shouldThrow<IllegalArgumentException> { CivsMineProgress.retainedSpine(mine,
            before.copy(nextStep = old.steps.size.toLong() + 1)) }
        // Import is a transient calculation, without a receipt or any city mutation.
        before shouldBe sparseV5Progress()
        before.v5Proof shouldBe null
        retained.keys shouldBe setOf("spine")
    }

    test("the first V6 receipt cannot claim a future spine action or an unrelated gallery") {
        val mine = spatialBuilding("caledonia", "miner", 1, CivPosition(0, 70, 0))
        val plan = requireNotNull(CivsMinePlan.forBuilding(mine, 6))
        val before = sparseV5Progress()
        val spine = plan.sections.getValue("spine")
        val count = CivsMineProgress.retainedSpine(mine, before).getValue("spine").toInt()
        val work = v6SpineReceipt(mine, spine[count + 1])
        CivsMineProgress.initialSpineAccess(mine, before, work) shouldBe listOf(spine[count])
        shouldThrow<IllegalArgumentException> { CivsMineProgress.after(mine, before, null, work.copy(
            miningAccessSections = work.miningAccessSections!! + ("spine" to (count + 2).toLong()))) }
        val gallery = plan.steps.indices.first { plan.section(it) != "spine" }
        shouldThrow<IllegalArgumentException> { CivsMineProgress.after(mine, before, null,
            work.copy(miningOrdinal = gallery)) }
        shouldThrow<IllegalArgumentException> { CivsMineProgress.after(mine, before, null,
            work.copy(miningAccessSections = work.miningAccessSections!! + (plan.section(gallery) to 1L))) }
    }

    test("additional initial V6 access rechecks each native rung and sound backing before a durable write") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("initial-v6-native-access").also(paper.server::addWorld)
            val mine = spatialBuilding("caledonia", "miner", 1, CivPosition(0, 45, 0))
            val worker = spatialWorker(mine, "IRON_PICKAXE")
            val before = sparseV5Progress()
            val city = spatialCity(world.uid, listOf(mine), worker).copy(minePlans = mapOf(mine.id.toString() to before))
            val plan = requireNotNull(CivsMinePlan.forBuilding(mine, 6))
            val spine = plan.sections.getValue("spine")
            val count = CivsMineProgress.retainedSpine(mine, before).getValue("spine").toInt()
            val ordinal = spine[count + 1]
            val draft = v6SpineReceipt(mine, ordinal)
            val work = draft.copy(workerId = worker.id,
                changes = listOf(draft.changes.single().copy(before = "minecraft:stone")))
            val access = CivsMineProgress.initialSpineAccess(mine, before, work)
            access.size shouldBe 1
            for (index in access + ordinal) {
                val point = CivsWorksiteLayouts.point(mine, plan.steps[index].point)
                world.loadChunk(point.x shr 4, point.z shr 4)
                if (plan.steps[index].after?.startsWith("minecraft:ladder[") == true && index != ordinal)
                    world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(plan.expectedAfter(index, mine.rotation))
            }
            val plugin = paper.createSimplePlugin("InitialV6NativeAccess")
            LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
                CivsWorld(plugin, tasks) { listOf(city) }.use { accessWorld ->
                    val runtime = CivsWorksites(plugin, accessWorld, { _, _ -> emptyMap() }, { _, _ -> false })
                    runtime.validateMineReceiptNative(city, work)
                    // A later cursor also claims the preceding unpaid rung. Neither
                    // a missing rung nor a matching player-placed ladder is authority.
                    val later = v6SpineReceipt(mine, spine[count + 3]).let { it.copy(workerId = worker.id,
                        changes = listOf(it.changes.single().copy(before = "minecraft:stone"))) }
                    val rung = spine[count + 1]
                    val point = CivsWorksiteLayouts.point(mine, plan.steps[rung].point)
                    world.getBlockAt(point.x, point.y, point.z).type = Material.STONE
                    shouldThrow<CivsRejection> { runtime.validateMineReceiptNative(city, later) }.reason shouldBe "stale-worksite"
                    world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(plan.expectedAfter(rung, mine.rotation))
                    shouldThrow<CivsRejection> { runtime.validateMineReceiptNative(city, later) }.reason shouldBe "mine-foreign-block"
                    // Keep this target pending: an already applied receipt may retire
                    // independently of a later missing support or protection change.
                    world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(work.changes.single().before)
                    val backing = access.first { plan.steps[it].shaftSupport }
                    val support = CivsWorksiteLayouts.point(mine, plan.steps[backing].point)
                    world.getBlockAt(support.x, support.y, support.z).type = Material.AIR
                    shouldThrow<CivsRejection> { runtime.validateMineReceiptNative(city, work) }.reason shouldBe "stale-worksite"
                    city.minePlans!!.getValue(mine.id.toString()) shouldBe before
                    city.workers.single().completedCycles shouldBe worker.completedCycles
                    city.stock shouldBe spatialCity(world.uid, listOf(mine), worker).stock
                }
            }
        }
    }

    for (alreadyApplied in listOf(false, true))
    test("first V6 journal retires only an already applied target after Lands and native access change $alreadyApplied") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("first-v6-recovery-$alreadyApplied").also(paper.server::addWorld)
            val plugin = paper.createSimplePlugin("FirstV6Recovery")
            val mine = spatialBuilding("caledonia", "miner", 1, CivPosition(0, 45, 0))
            val worker = spatialWorker(mine, "IRON_PICKAXE")
            val previous = sparseV5Progress()
            val city = spatialCity(world.uid, listOf(mine), worker).copy(minePlans = mapOf(mine.id.toString() to previous))
            val plan = requireNotNull(CivsMinePlan.forBuilding(mine, 6))
            val count = CivsMineProgress.retainedSpine(mine, previous).getValue("spine").toInt()
            val draft = v6SpineReceipt(mine, plan.sections.getValue("spine")[count + 1])
            val work = draft.copy(workerId = worker.id, inputs = mapOf("LADDER" to 1L))
            val cell = work.changes.single()
            world.loadChunk(cell.position.x shr 4, cell.position.z shr 4)
            val target = world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
            target.blockData = Bukkit.createBlockData(cell.before)
            val old = requireNotNull(CivsMinePlan.forBuilding(mine, 5))
            val rung = CivsWorksiteLayouts.point(mine, old.bottom.copy(y = old.lowest))
            world.getBlockAt(rung.x, rung.y, rung.z).blockData = Bukkit.createBlockData(old.expectedAfter(
                old.sections.getValue("spine").last(), mine.rotation))
            val root = Files.createTempDirectory("arc-civs-first-v6-recovery-")
            try {
                LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                        runtime.validateMineReceiptNative(city, work)
                        val after = CivsRules.finishWorldWork(city, work, 1_000)
                        val transaction = CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision, after,
                            emptyList(), worldChanges = work.changes,
                            mine = CivMineReceipt(mine, previous, null, work)).validated()
                        CivsStore(root, Executor(Runnable::run)).use { store ->
                            store.save(city).join()
                            store.saveInventoryTransaction(transaction).join()
                            val restored = store.loadInventoryTransactions().join().single().validated()
                            if (alreadyApplied) target.blockData = Bukkit.createBlockData(cell.after)
                            world.getBlockAt(rung.x, rung.y, rung.z).type = Material.AIR
                            val backing = CivsMineProgress.initialSpineAccess(mine, previous, work).single()
                            val point = CivsWorksiteLayouts.point(mine, plan.steps[backing].point)
                            world.getBlockAt(point.x, point.y, point.z).type = Material.AIR
                            val lands = paper.createSimplePlugin("Lands")
                            paper.server.pluginManager.disablePlugin(lands)
                            CivsStorage(plugin) { emptyArray() }.use { storage ->
                                storage.lock(restored)
                                storage.validateMineNative = { tx ->
                                    val receipt = requireNotNull(tx.mine)
                                    try { runtime.validateMineReceiptNative(receipt.before(tx.after), receipt.work); null }
                                    catch (failure: CivsRejection) { CivInventoryApplyResult.Conflict(mapOf("reason" to failure.reason)) }
                                }
                                repeat(2) {
                                    val result = storage.reconcile(restored)
                                    if (alreadyApplied) result shouldBe CivInventoryApplyResult.Applied
                                    else (result as CivInventoryApplyResult.Conflict).values["reason"] shouldBe "protected-area"
                                }
                                if (alreadyApplied) {
                                    store.save(restored.after).join()
                                    store.deleteInventoryTransaction(restored.id).join() shouldBe true
                                    val saved = store.load().join().single()
                                    saved.minePlans!!.getValue(mine.id.toString()).v5Proof shouldBe
                                        CivMineV5Proof(previous.nextStep, previous.sectionSteps, previous.excavation, previous.seals)
                                    saved.workers.single().completedCycles shouldBe worker.completedCycles + 1
                                    CivsEquipment.plainCount(saved.workers.single(), "LADDER") shouldBe 15
                                    saved.stock shouldBe city.stock
                                } else store.load().join().single() shouldBe city
                                target.blockData.asString shouldBe if (alreadyApplied) cell.after else cell.before
                            }
                        }
                    }
                }
            } finally { root.toFile().deleteRecursively() }
        }
    }

    test("the first real V6 receipt freezes exact sparse V5 authority once without reusing its ordinal") {
        val mine = spatialBuilding("caledonia", "miner", 1, CivPosition(-1844, 70, -2696), 180)
        val oldPlan = requireNotNull(CivsMinePlan.forBuilding(mine, 5))
        val plan = requireNotNull(CivsMinePlan.forBuilding(mine, 6))
        plan.version shouldBe 6
        plan.floors.map { mine.anchor.y + it.feetY } shouldBe listOf(59, 55, 51, 47, 43, 39)
        CivsMineSafety.FULL_COVER shouldBe 16
        val before = sparseV5Progress()
        CivsMineProgress.valid(oldPlan, before, mine) shouldBe true
        val ordinal = plan.sections.getValue("spine").first { plan.steps[it].point.y < oldPlan.lowest &&
            plan.steps[it].after?.startsWith("minecraft:ladder[") == true }
        val work = v6SpineReceipt(mine, ordinal)
        val migrated = CivsMineProgress.after(mine, before, null, work)
        migrated.version shouldBe 6
        (migrated.nextStep < before.nextStep) shouldBe true
        migrated.v5Step shouldBe 1818L
        migrated.v5Proof shouldBe CivMineV5Proof(before.nextStep, before.sectionSteps, before.excavation, before.seals)
        CivsMineProgress.v5State(migrated) shouldBe before
        CivsMineProgress.valid(plan, migrated, mine) shouldBe true
        val nextOrdinal = plan.sections.getValue("spine").first { it > ordinal }
        val next = v6SpineReceipt(mine, nextOrdinal, migrated.sectionSteps.orEmpty(), migrated.nextStep)
        CivsMineProgress.after(mine, migrated, null, next).v5Proof shouldBe migrated.v5Proof
        CivsMineProgress.valid(plan, migrated.copy(v5Step = before.nextStep + 1), mine) shouldBe false
        CivsMineProgress.valid(plan, migrated.copy(v5Proof = migrated.v5Proof!!.copy(
            sectionSteps = mapOf("foreign" to 1))), mine) shouldBe false
        val foreignCut = plan.plannedCuts.minBy { it.y }.let { it.copy(y = it.y - 32) }
        CivsMineProgress.valid(plan, migrated.copy(v5Proof = migrated.v5Proof!!.copy(
            seals = mapOf(CivsMineProgress.key(foreignCut) to "minecraft:cobblestone"))), mine) shouldBe false
        val oldStanding = oldPlan.floors.last().let { floor -> floor.cells.map { it.copy(y = floor.feetY) }.first {
            oldPlan.builtStanding(it, before.nextStep, before.sectionSteps.orEmpty()) &&
                !plan.builtStanding(it, migrated.nextStep, migrated.sectionSteps.orEmpty())
        } }
        val worldStanding = CivsWorksiteLayouts.point(mine, oldStanding)
        CivsMineProgress.priorStanding(mine, migrated, worldStanding) shouldBe true
        citizenMineWorkRouteAllowed(mine, CitizenPoint(worldStanding.x + .5, worldStanding.y.toDouble(), worldStanding.z + .5),
            migrated.nextStep, migrated) shouldBe true
    }

    test("V5 predecessor maps detach through the real store codec and survive a later building-level upgrade") {
        val mine = spatialBuilding("caledonia", "miner", 1, CivPosition(0, 70, 0))
        val plan = requireNotNull(CivsMinePlan.forBuilding(mine, 6))
        val ordinal = plan.sections.getValue("spine")[CivsMineProgress.retainedSpine(mine, sparseV5Progress()).getValue("spine").toInt() + 1]
        val migrated = CivsMineProgress.after(mine, sparseV5Progress(), null, v6SpineReceipt(mine, ordinal))
        val sections = migrated.v5Proof!!.sectionSteps!!.toMutableMap()
        val seals = mutableMapOf<String, String>()
        val mutable = migrated.copy(v5Proof = migrated.v5Proof!!.copy(sectionSteps = sections, seals = seals))
        val city = spatialCity(UUID.randomUUID(), listOf(mine), spatialWorker(mine, "IRON_PICKAXE")).copy(
            minePlans = mapOf(mine.id.toString() to mutable))
        val expected = city.copy(minePlans = mapOf(mine.id.toString() to CivsMineProgress.detached(mutable)))
        val pending = ArrayDeque<Runnable>()
        val root = Files.createTempDirectory("arc-civs-v5-proof-")
        try {
            CivsStore(root, Executor { pending.addLast(it) }).use { store ->
                val saving = store.save(city)
                sections["foreign"] = 99
                seals["foreign"] = "minecraft:diamond_block"
                pending.removeFirst().run()
                saving.join() shouldBe expected
            }
            CivsStore(root, Executor(Runnable::run)).use { it.load().join() shouldBe listOf(expected) }
            val nextBuilding = mine.copy(level = 2, schematicId = mine.schematicId.dropLast(1) + "2")
            val upgraded = requireNotNull(CivsMineProgress.upgraded(mine, nextBuilding,
                expected.minePlans!!.getValue(mine.id.toString()), null))
            upgraded.levels!!.single().state() shouldBe expected.minePlans!!.getValue(mine.id.toString())
            CivsMineProgress.validLevels(nextBuilding, upgraded) shouldBe true
        } finally { root.toFile().deleteRecursively() }
    }

    for (alreadyApplied in listOf(false, true))
    test("a pending V5 ordinary mine journal recovers its frozen native authority once with applied cell $alreadyApplied") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("frozen-v5-receipt-$alreadyApplied").also(paper.server::addWorld)
            val plugin = paper.createSimplePlugin("FrozenV5MineReceipt")
            val (city, work) = frozenV5Receipt(world.uid)
            val mine = requireNotNull(city.building(work.buildingId))
            val cell = work.changes.single()
            world.loadChunk(cell.position.x shr 4, cell.position.z shr 4)
            val block = world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
            block.blockData = Bukkit.createBlockData(if (alreadyApplied) cell.after else cell.before)
            val after = CivsRules.finishWorldWork(city, work, 1_000)
            val transaction = CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision, after, emptyList(),
                worldChanges = work.changes, mine = CivMineReceipt(mine, city.minePlans!!.getValue(mine.id.toString()), null, work)).validated()
            val root = Files.createTempDirectory("arc-civs-v5-journal-")
            try {
                CivsStore(root, Executor(Runnable::run)).use { store ->
                    store.saveInventoryTransaction(transaction).join() shouldBe transaction
                    val restored = store.loadInventoryTransactions().join().single()
                    restored.mine!!.work.miningPlanVersion shouldBe 5
                    LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
                        CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                            val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                            CivsStorage(plugin) { emptyArray() }.use { storage ->
                                storage.lock(restored)
                                storage.validateMineNative = { tx ->
                                    val receipt = requireNotNull(tx.mine)
                                    runtime.validateMineReceiptNative(receipt.before(tx.after), receipt.work)
                                    null
                                }
                                repeat(2) { storage.reconcile(restored) shouldBe CivInventoryApplyResult.Applied }
                                block.blockData.asString shouldBe cell.after
                                restored.after.minePlans!!.getValue(mine.id.toString()).version shouldBe 5
                                restored.after.workers.single().completedCycles shouldBe 1
                                CivsEquipment.plainCount(restored.after.workers.single(), "COBBLESTONE") shouldBe 31
                                restored.after.stock shouldBe city.stock
                            }
                        }
                    }
                }
            } finally { root.toFile().deleteRecursively() }
        }
    }

    test("a V5 Auto journal remains valid but cannot be proposed as new live work under V6") {
        val (base, ordinary) = frozenV5Receipt(UUID.randomUUID())
        val city = base.copy(inventory = null, autonomy = CivAutonomy(), stock = Stock(7, 8, 9))
        val mine = requireNotNull(city.building(ordinary.buildingId))
        val work = ordinary.copy(inputs = emptyMap(), autonomousVisual = true)
        shouldThrow<IllegalArgumentException> { CivsAutonomousWorksites.visual(city, mine, ordinary) }
        val after = CivsRules.finishAutonomousVisual(city, work)
        val receipt = CivAutonomousVisualReceipt(city.workers.single(), city.stock, city.autonomy!!,
            city.mineProgress, city.minePlans, work, 1_000, city.id, city.worldId, city.owner, mine)
        val transaction = CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision, after, emptyList(),
            worldChanges = work.changes, autonomousVisual = receipt).validated()
        Gson().fromJson(Gson().toJson(transaction), CivInventoryTransaction::class.java).validated() shouldBe transaction
        after.minePlans!!.getValue(mine.id.toString()).version shouldBe 5
        after.workers shouldBe city.workers
        after.stock shouldBe city.stock
        val current = requireNotNull(CivsMinePlan.forBuilding(mine, 6))
        val deep = current.steps.first { it.point.y < requireNotNull(CivsMinePlan.forBuilding(mine, 5)).lowest - 1 }
        shouldThrow<IllegalArgumentException> {
            CivsAutonomousWorksites.validate(city, work.copy(changes = listOf(CivPlanBlock(
                CivsWorksiteLayouts.point(mine, deep.point), "minecraft:stone", "minecraft:air"))))
        }
    }
})

private fun sparseV5Progress() = CivMinePlanState(5, 1818, sectionSteps = mapOf(
    "1/gallery/left" to 50, "1/gallery/right" to 50,
    "2/gallery/left" to 50, "2/gallery/main" to 50, "2/gallery/right" to 50,
    "3/gallery/left" to 62, "3/gallery/main" to 62, "3/gallery/right" to 66),
    excavation = CivPosition(0, -19, 0), v5Step = 1818)

private fun v6SpineReceipt(mine: CivBuilding, ordinal: Int, before: Map<String, Long> = emptyMap(),
    prefix: Long = 0): CivWorldWork {
    val plan = requireNotNull(CivsMinePlan.forBuilding(mine, 6))
    val spine = plan.sections.getValue("spine")
    val offset = spine.indexOf(ordinal)
    val access = before + ("spine" to maxOf(before["spine"] ?: 0, offset.toLong()))
    val completed = plan.advanceSection(ordinal, access)
    val point = CivsWorksiteLayouts.point(mine, plan.steps[ordinal].point)
    return CivWorldWork(UUID.randomUUID(), 0, mine.id, mine.entrance,
        listOf(CivPlanBlock(point, "minecraft:air", plan.expectedAfter(ordinal, mine.rotation))), status = "digging-mine",
        miningPlanVersion = 6, miningStep = plan.normalizedProgress(prefix, completed).first,
        miningAccessStep = plan.normalizedProgress(prefix, access).first, miningSections = completed,
        miningAccessSections = access, miningOrdinal = ordinal)
}

private fun frozenV5Receipt(world: UUID): Pair<Settlement, CivWorldWork> {
    val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
    val worker = spatialWorker(mine, "IRON_PICKAXE")
    val plan = requireNotNull(CivsMinePlan.forBuilding(mine, 5))
    val ordinal = plan.sections.getValue("spine").first()
    val sections = plan.advanceSection(ordinal, emptyMap())
    val point = CivsWorksiteLayouts.point(mine, plan.steps[ordinal].point)
    val city = CivsRules.validate(spatialCity(world, listOf(mine), worker).copy(
        minePlans = mapOf(mine.id.toString() to CivMinePlanState(5, 0, sectionSteps = emptyMap()))))
    return city to CivWorldWork(worker.id, 0, mine.id, mine.entrance,
        listOf(CivPlanBlock(point, "minecraft:air", plan.expectedAfter(ordinal, mine.rotation))),
        inputs = mapOf("COBBLESTONE" to 1L), status = "digging-mine", focus = point,
        miningPlanVersion = 5, miningStep = plan.normalizedProgress(0, sections).first, miningAccessStep = 0,
        miningSections = sections, miningAccessSections = emptyMap(), miningOrdinal = ordinal)
}
