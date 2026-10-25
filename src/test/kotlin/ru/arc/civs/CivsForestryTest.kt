package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.TreeType
import org.bukkit.event.world.StructureGrowEvent
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executor

class CivsForestryTest : FunSpec({
    test("detached capture crosses the old height and 128-log ceilings follows downward branches and yields by its read allowance") {
        val (scan, cells, roots) = forestryCapture()
        var pulses = 0
        while (!scan.complete && !scan.failed && pulses++ < 10_000) {
            var reads = 0
            val used = scan.advance(7) { point -> reads++; cells[point] ?: "minecraft:air" }
            (used <= 7) shouldBe true
            reads shouldBe used
        }
        scan.failed shouldBe false
        (pulses > 1) shouldBe true
        val job = requireNotNull(scan.job(roots))
        (job.cells.count { CivsForestry.material(job.state(it)) == job.timber } > 128) shouldBe true
        (CivPosition(5, 94, 0) in job.cells.map(job::position)) shouldBe true
        job.cells.takeLast(4).map(job::position).toSet() shouldBe roots.toSet()
        CivsForestry.valid(forestryCity(), forestryCity().buildings.last().id.toString(), job) shouldBe true
    }

    test("incomplete unloaded or independently rooted touching crowns never become a partial harvest") {
        val (_, captured, roots) = forestryCapture()
        val missing = CivsForestry.Scan(roots.first(), "SPRUCE_LOG")
        while (!missing.complete && !missing.failed) missing.advance(32) { point ->
            if (point.y == 90) null else captured[point] ?: "minecraft:air"
        }
        missing.job(roots) shouldBe null
        val joined = captured.toMutableMap()
        for (y in 64..98) joined[CivPosition(5, y, 5)] = "minecraft:spruce_log[axis=y]"
        joined[CivPosition(5, 63, 5)] = "minecraft:dirt"
        val connected = CivsForestry.Scan(roots.first(), "SPRUCE_LOG")
        while (!connected.complete && !connected.failed) connected.advance(64) { joined[it] ?: "minecraft:air" }
        connected.job(roots) shouldBe null
        val sapling = CivsForestry.Scan(roots.first(), "SPRUCE_LOG")
        sapling.advance(8) { "minecraft:spruce_sapling[stage=1]" }
        sapling.job(roots) shouldBe null
    }

    test("only vines facing this tree and their hanging chain join the bounded captured crown") {
        val (_, source, roots) = forestryCapture()
        val cells = source.toMutableMap()
        val attached = listOf(CivPosition(-5, 98, 0), CivPosition(-5, 97, 0), CivPosition(-5, 96, 0))
        val vine = "minecraft:vine[east=true,north=false,south=false,up=false,west=false]"
        attached.forEach { cells[it] = vine }
        val unrelated = CivPosition(-5, 98, 2)
        cells[unrelated] = "minecraft:vine[east=false,north=false,south=false,up=false,west=true]"
        val scan = CivsForestry.Scan(roots.first(), "SPRUCE_LOG")
        while (!scan.complete && !scan.failed) scan.advance(11) { cells[it] ?: "minecraft:air" }
        val job = requireNotNull(scan.job(roots))
        job.cells.take(attached.size).map(job::position) shouldBe attached
        (unrelated in job.cells.map(job::position)) shouldBe false
        val city = forestryCity(autonomous = false).let { base -> base.copy(workers = listOf(base.workers.single()
            .copy(items = MutableList<CivResidentItem?>(CivsEquipment.SLOTS) { null }.also {
                it[CivsEquipment.BAG] = CivResidentItem("SPRUCE_SAPLING", 4)
            }))) }
        CivsForestry.valid(city, city.buildings.last().id.toString(), job) shouldBe true
        val before = forestryTestWork(city, job)
        val observedDrops = mapOf("VINE" to 3L)
        val work = before.copy(outputs = observedDrops, forestry = before.forestry!!.copy(drops = observedDrops))
        CivsForestry.validateWork(city, work)
        // A receipt can carry the observed native drop, but cannot introduce another material or an unattached vine.
        shouldThrow<IllegalArgumentException> { CivsForestry.validateWork(city,
            work.copy(outputs = mapOf("DIAMOND" to 1L), forestry = work.forestry!!.copy(drops = mapOf("DIAMOND" to 1L)))) }
        val altered = job.copy(states = job.states.map { if (it == vine) cells.getValue(unrelated) else it })
        CivsForestry.valid(city, city.buildings.last().id.toString(), altered) shouldBe false
    }

    test("a saved multi-batch vine capture accepts native face pruning and new attachments only to its revalidated remaining tree") {
        val directory = Files.createTempDirectory("civs-forestry-vine-growth-")
        try {
            val (_, source, roots) = forestryCapture()
            val blocks = source.toMutableMap()
            fun vine(face: String) = "minecraft:vine[" + listOf("east", "north", "south", "up", "west")
                .joinToString(",") { "$it=${it == face}" } + "]"
            for (y in 65..96) for (side in 0..1) {
                blocks.putIfAbsent(CivPosition(-1, y, side), vine("east"))
                blocks.putIfAbsent(CivPosition(2, y, side), vine("west"))
                blocks.putIfAbsent(CivPosition(side, y, -1), vine("south"))
                blocks.putIfAbsent(CivPosition(side, y, 2), vine("north"))
            }
            val target = CivPosition(-1, 79, 0)
            val leaf = "minecraft:spruce_leaves[distance=1,persistent=false,waterlogged=false]"
            blocks[target.copy(y = 80)] = leaf
            blocks[target.copy(z = -1)] = leaf
            val scan = CivsForestry.Scan(roots.first(), "SPRUCE_LOG")
            while (!scan.complete && !scan.failed) scan.advance(19) { blocks[it] ?: "minecraft:air" }
            val job = requireNotNull(scan.job(roots))
            (job.cells.count { CivsForestry.material(job.state(it)) == "VINE" } > 128) shouldBe true
            val before = forestryCity()
            val first = forestryTestWork(before, job)
            first.changes.none { it.position == target } shouldBe true
            val partial = CivsRules.finishWorldWork(before, first, 1_000)
            first.changes.forEach { blocks[it.position] = it.after }
            CivsStore(directory, Executor(Runnable::run)).use {
                it.save(before).join()
                it.save(partial).join()
            }
            val reopened = CivsStore(directory, Executor(Runnable::run)).use { it.load().join().single() }
            val saved = reopened.forestryJobs!!.values.single()
            val code = saved.cells.single { saved.position(it) == target }
            val actual = blocks.getValue(target).replace("north=false", "north=true").replace("up=false", "up=true")
            blocks[target] = actual
            val matcher = CivsForestry.Matcher(saved)
            var supportReads = 0
            matcher.matches(code, actual) { point -> supportReads++; blocks[point] ?: "minecraft:air" } shouldBe true
            supportReads shouldBe 2
            matcher.matches(code, actual) { "minecraft:stone" } shouldBe false
            matcher.matches(code, actual) { null } shouldBe false
            matcher.matches(code, "minecraft:stone") { blocks[it] } shouldBe false
            val pruned = actual.replace("east=true", "east=false")
            matcher.matches(code, pruned) { blocks[it] } shouldBe true
            matcher.matches(code, actual.replace("west=false", "west=true")) { "minecraft:spruce_log[axis=y]" } shouldBe false
            val unrelated = (code and 31.inv()) or 0 // A wall vine outside this accepted cell set cannot join a later batch.
            matcher.matches(unrelated, actual) { blocks[it] } shouldBe false

            blocks[target] = pruned
            val next = forestryTestWork(reopened, saved).let { work -> work.copy(changes = work.changes.map {
                if (it.position == target) it.copy(before = pruned) else it
            }) }
            CivsForestry.validateWork(reopened, next) { blocks[it] ?: "minecraft:air" }
            shouldThrow<IllegalArgumentException> {
                CivsForestry.validateWork(reopened, next) { "minecraft:stone" }
            }
            val receipt = forestryReceipt(reopened, next, 2_000)
            receipt.worldChanges!!.single { it.position == target }.before shouldBe pruned
            receipt.validated() shouldBe receipt
            receipt.after.forestryJobs!!.values.single().next shouldBe 256
            receipt.after.stock shouldBe reopened.stock
            shouldThrow<CivsRejection> { CivsRules.finishWorldWork(receipt.after, next, 3_000) }.reason shouldBe "stale-delivery"
        } finally { directory.toFile().deleteRecursively() }
    }

    test("a crown batch retains its real root as the reachable work focus even when the slice already decayed") {
        val city = forestryCity()
        val first = forestryTestWork(city, forestryTestJob())
        first.changes.none { it.position == first.forestry!!.job.root } shouldBe true
        citizenWorkFocus(first) shouldBe first.forestry!!.job.root
        citizenWorkFocus(first.copy(changes = emptyList())) shouldBe first.forestry.job.root
        citizenWorkFocus(first.copy(forestry = null)) shouldBe null
        citizenWorkFocus(first.copy(focus = CivPosition(90, 90, 90))) shouldBe null
        val job = first.forestry.job.copy(cells = first.forestry.job.cells.take(253) + first.forestry.job.cells.takeLast(4), next = 128)
        CivsForestry.batchEnd(job) shouldBe 253
        CivsForestry.batchEnd(job.copy(next = 253)) shouldBe 257
    }

    test("two mills cannot acquire the same partially harvested native tree or commit two stale initial claims") {
        val base = forestryCity()
        val first = forestryTestWork(base, forestryTestJob())
        val secondMill = base.buildings.last().copy(id = UUID(0, 4), anchor = CivPosition(-20, 64, 20),
            bounds = CivBox(CivPosition(-20, 63, 20), CivPosition(-18, 68, 22)), entrance = CivPosition(-19, 64, 23))
        val secondWorker = base.workers.single().copy(id = UUID(0, 5), assignment = secondMill.id)
        val before = CivsRules.validate(base.copy(buildings = base.buildings + secondMill, workers = base.workers + secondWorker))
        val stale = first.copy(workerId = secondWorker.id, buildingId = secondMill.id,
            forestry = first.forestry!!.copy(job = first.forestry.job.copy(id = UUID.randomUUID())))
        CivsForestry.validateWork(before, stale)
        val after = CivsRules.finishWorldWork(before, first, 1_000)
        shouldThrow<CivsRejection> { CivsRules.finishWorldWork(after, stale, 2_000) }.reason shouldBe "stale-worksite"
        CivsForestry.rootClaimed(after, secondMill.id, first.forestry.job.root) shouldBe true
        CivsForestry.rootClaimed(after, first.buildingId, first.forestry.job.root) shouldBe false

        // Capture the real remaining block-state sample, proving that the trunk still looks independently harvestable.
        val (_, cells, roots) = forestryCapture()
        val partial = cells.toMutableMap().also { map -> first.changes.forEach { map[it.position] = it.after } }
        val scan = CivsForestry.Scan(roots.first(), "SPRUCE_LOG")
        while (!scan.complete && !scan.failed) scan.advance(64) { partial[it] ?: "minecraft:air" }
        val recaptured = requireNotNull(scan.job(roots))
        CivsForestry.claimClear(after, secondMill.id, recaptured) shouldBe false
        val duplicate = forestryTestWork(after, recaptured)
        shouldThrow<CivsRejection> { CivsRules.finishWorldWork(after, duplicate, 3_000) }.reason shouldBe "stale-worksite"
        val otherTree = recaptured.copy(root = recaptured.root.copy(x = 40), roots = recaptured.roots.map { it.copy(x = it.x + 40) })
        CivsForestry.claimClear(after, secondMill.id, otherTree) shouldBe true
    }

    test("Auto prepays one complete giant planting kit persists its cursor and credits only the completed tree once") {
        val root = Files.createTempDirectory("civs-forestry-restart-")
        try {
            val before = forestryCity()
            val job = forestryTestJob()
            val first = forestryTestWork(before, job)
            first.inputs shouldBe mapOf("SPRUCE_SAPLING" to 4L)
            first.outputs shouldBe emptyMap()
            val partial = CivsRules.finishWorldWork(before, first, 1_000)
            partial.stock.wood shouldBe 19L
            partial.workers.single().items shouldBe null
            partial.forestryJobs!!.getValue(first.buildingId.toString()).next shouldBe 128
            val receipt = forestryReceipt(before, first, 1_000)
            receipt.validated() shouldBe receipt
            CivsStore(root, Executor(Runnable::run)).use { store ->
                store.save(before).join()
                store.saveInventoryTransaction(receipt).join() shouldBe receipt
            }
            CivsStore(root, Executor(Runnable::run)).use { reopened ->
                reopened.loadInventoryTransactions().join().single() shouldBe receipt
                reopened.save(partial).join()
                reopened.deleteInventoryTransaction(receipt.id).join()
                var city = reopened.load().join().single()
                var final: CivWorldWork? = null
                while (city.forestryJobs != null) {
                    val work = forestryTestWork(city, city.forestryJobs!!.values.single())
                    work.inputs shouldBe emptyMap()
                    work.outputs shouldBe emptyMap()
                    val oldStock = city.stock
                    val complete = work.forestry!!.next == work.forestry.job.cells.size
                    city = CivsRules.finishWorldWork(city, work, 2_000 + city.revision)
                    city.stock shouldBe if (complete) oldStock.copy(wood = oldStock.wood + 8) else oldStock
                    if (complete) final = work
                    city = CivsRules.validate(Gson().fromJson(Gson().toJson(city), Settlement::class.java))
                }
                city.stock.wood shouldBe 27L
                city.workers.single().items shouldBe null
                city.forestryJobs shouldBe null
                shouldThrow<CivsRejection> { CivsRules.finishWorldWork(city, requireNotNull(final), 9_000) }.reason shouldBe "stale-delivery"
            }
            shouldThrow<CivsRejection> { CivsRules.finishWorldWork(before.copy(stock = Stock()), first, 1_000) }.reason shouldBe "stale-worksite"
            shouldThrow<CivsRejection> { CivsRules.deliver(before, before.owner, before.workers.single().id,
                before.buildings.last().id, 0, 1, 60_000) }.reason shouldBe "stale-worksite"
            citizenPlan(before, before.workers.single())!!.autonomousWork shouldBe null
        } finally { root.toFile().deleteRecursively() }
    }

    test("an Auto mill uses its paid construction seed kit once at zero wood and persists the marker before planting") {
        val directory = Files.createTempDirectory("civs-forestry-kit-")
        try {
            val before = forestryCity().copy(stock = Stock())
            val oak = before.copy(buildings = before.buildings.map {
                if (it.type == BuildingType.FORESTER) it.copy(production = "OAK_LOG") else it
            })
            val ordinary = forestryPlantWork(oak)
            ordinary.inputs shouldBe mapOf("OAK_SAPLING" to 1L)
            CivsRules.finishWorldWork(oak, ordinary, 1_000).stock shouldBe Stock()
            val work = forestryPlantWork(before)
            val receipt = forestryReceipt(before, work, 1_000)
            receipt.after.stock shouldBe Stock()
            receipt.after.forestrySeeded shouldBe setOf(work.buildingId.toString())
            receipt.after.forestryJobs shouldBe null
            CivsStore(directory, Executor(Runnable::run)).use { store ->
                store.save(before).join()
                store.saveInventoryTransaction(receipt).join() shouldBe receipt
            }
            CivsStore(directory, Executor(Runnable::run)).use { store ->
                val replay = store.loadInventoryTransactions().join().single()
                replay.validated() shouldBe receipt
                store.save(replay.after).join()
                val after = store.load().join().single()
                after.forestrySeeded shouldBe setOf(work.buildingId.toString())
                shouldThrow<CivsRejection> { CivsRules.finishWorldWork(after, forestryPlantWork(after), 2_000) }
                    .reason shouldBe "materials-missing"
                shouldThrow<CivsRejection> { CivsRules.finishWorldWork(after, work, 2_000) }.reason shouldBe "stale-delivery"
                shouldThrow<IllegalArgumentException> { receipt.copy(after = after.copy(forestrySeeded = null)).validated() }
                shouldThrow<IllegalArgumentException> { receipt.copy(forestry = receipt.forestry!!.copy(
                    seededBefore = setOf(work.buildingId.toString()))).validated() }
                val deleting = CivsRules.requestDeletion(after, after.owner, work.buildingId)
                deleting.forestrySeeded shouldBe after.forestrySeeded
                CivsRules.finishBuildingDeletion(deleting, after.owner).forestrySeeded shouldBe null
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    test("last wood planting recovers through one deferred tree kit and never pays before observed final logs") {
        val one = forestryCity().copy(stock = Stock(1))
        val planted = CivsRules.finishWorldWork(one, forestryPlantWork(one), 1_000)
        planted.stock.wood shouldBe 0L
        planted.forestrySeeded shouldBe setOf(one.buildings.last().id.toString())
        val job = forestryTestJob().copy(seedDeferred = true)
        val first = forestryTestWork(planted, job)
        first.inputs shouldBe emptyMap()
        val receipt = forestryReceipt(planted, first, 2_000)
        receipt.validated() shouldBe receipt
        var city = CivsRules.validate(Gson().fromJson(Gson().toJson(receipt.after), Settlement::class.java))
        city.stock.wood shouldBe 0L
        city.forestryJobs!!.values.single().seedDeferred shouldBe true
        val wrongIdentity = forestryTestWork(city, city.forestryJobs!!.values.single().copy(seedDeferred = false))
        shouldThrow<CivsRejection> { CivsRules.finishWorldWork(city, wrongIdentity, 3_000) }.reason shouldBe "stale-worksite"
        var final: CivWorldWork? = null
        while (city.forestryJobs != null) {
            val next = forestryTestWork(city, city.forestryJobs!!.values.single())
            next.inputs shouldBe emptyMap()
            val complete = next.forestry!!.next == next.forestry.job.cells.size
            city = CivsRules.finishWorldWork(city, next, 3_000 + city.revision)
            city.stock.wood shouldBe if (complete) 7L else 0L
            if (complete) final = next
        }
        city.workers.single().items shouldBe null
        shouldThrow<CivsRejection> { CivsRules.finishWorldWork(city, requireNotNull(final), 9_000) }.reason shouldBe "stale-delivery"
        val forged = forestryTestWork(one, job)
        shouldThrow<CivsRejection> { CivsRules.finishWorldWork(one, forged, 9_000) }.reason shouldBe "stale-worksite"

        // Missing captured logs grant neither funded replanting nor abstract stock.
        var vanished = planted
        var pending: CivForestryJob? = job
        while (pending != null) {
            val expected = forestryTestWork(vanished, pending)
            val withoutLogs = expected.copy(changes = expected.changes.filter {
                CivsForestry.material(it.before) != job.timber || it.position in job.roots
            }.map { if (it.position in job.roots) it.copy(before = "minecraft:air") else it },
                forestry = expected.forestry!!.copy(harvestedLogs = 0))
            if (withoutLogs.forestry!!.next == job.cells.size) {
                shouldThrow<CivsRejection> { CivsRules.finishWorldWork(vanished, withoutLogs, 10_000) }
                    .reason shouldBe "materials-missing"
                break
            }
            vanished = CivsRules.finishWorldWork(vanished, withoutLogs, 4_000 + vanished.revision)
            pending = vanished.forestryJobs!!.values.single()
        }
        vanished.stock.wood shouldBe 0L
    }

    test("a paid native tree job survives construction spending the exact remaining wood without charging its kit again") {
        val before = forestryCity().copy(stock = Stock(17, 8))
        val first = forestryTestWork(before, forestryTestJob())
        var city = CivsRules.finishWorldWork(before, first, 1_000)
        city.stock shouldBe Stock(16, 8)
        val savedJob = city.forestryJobs!!.values.single()
        val hut = before.buildings.first().copy(id = UUID(0, 8), type = BuildingType.BUILDER_HUT, level = 1,
            anchor = CivPosition(-15, 64, -15), bounds = CivBox(CivPosition(-15, 63, -15), CivPosition(-13, 68, -13)),
            entrance = CivPosition(-14, 64, -12), phase = BuildingPhase.CONSTRUCTING, schematicId = "fixture/builder")
        val order = CivConstruction(UUID.randomUUID(), hut.id, 1, 1, 0, ConstructionPhase.CONSTRUCTING,
            CivsAutonomy.cost(hut.type, 1), plannedBuilding = hut, batches = CivConstructionBatches(1, 16, true),
            materials = mapOf("STONE_BRICKS" to 1L), blocksRequired = 1, blocksPlaced = 0)
        city = CivsRules.reserveBuild(city, city.owner, hut, order)
        city.stock shouldBe Stock()
        city.forestryJobs!!.values.single() shouldBe savedJob
        city = CivsRules.validate(Gson().fromJson(Gson().toJson(city), Settlement::class.java))
        while (city.forestryJobs != null) {
            val next = forestryTestWork(city, city.forestryJobs!!.values.single())
            next.inputs shouldBe emptyMap()
            next.forestry!!.job.seedDeferred shouldBe false
            city = CivsRules.finishWorldWork(city, next, 2_000 + city.revision)
        }
        city.stock.wood shouldBe 8L
    }

    test("a Silk Touch harvest larger than the entire bag releases only each exact native batch and resumes after unloading") {
        MockBukkitTestRuntime.open().use {
            var city = forestryCity(autonomous = false)
            val initialWorker = city.workers.single().copy(items = MutableList<CivResidentItem?>(CivsEquipment.SLOTS) { null }.also {
                it[CivsEquipment.BAG] = CivResidentItem("SPRUCE_SAPLING", 4)
            })
            city = CivsRules.validate(city.copy(workers = listOf(initialWorker)))
            val roots = listOf(CivPosition(0, 64, 0), CivPosition(0, 64, 1), CivPosition(1, 64, 0), CivPosition(1, 64, 1))
            // Receipt-capacity fixture, not a native tree generator: one item per captured Silk Touch block.
            val leaves = (0 until CivsForestry.MAX_CELLS - 4).map { index ->
                (1 shl 16) or ((2 + index / (31 * 31)) shl 10) or ((index / 31 % 31) shl 5) or (index % 31)
            }
            val firstJob = CivForestryJob(UUID.randomUUID(), roots.first(), "SPRUCE_SAPLING", roots,
                listOf("minecraft:spruce_log[axis=y]", "minecraft:spruce_leaves[distance=1,persistent=false,waterlogged=false]"),
                leaves + roots.map { ((it.z + 15) shl 5) or (it.x + 15) })
            var job: CivForestryJob? = firstJob
            var delivered = 0L
            var final: CivWorldWork? = null
            while (job != null) {
                val work = forestryTestWork(city, job, sampleDrops = true, silkTouch = true)
                (work.outputs.values.sum() <= 128) shouldBe true
                work.outputs shouldBe work.forestry!!.drops
                work.inputs shouldBe if (job.next == 0) mapOf("SPRUCE_SAPLING" to 4L) else emptyMap()
                city = CivsRules.finishWorldWork(city, work, 1_000 + city.revision)
                work.outputs.forEach { (material, count) -> CivsEquipment.plainCount(city.workers.single(), material).toLong() shouldBe count }
                delivered += work.outputs.values.sum()
                // Existing courier accounting owns this unload; the forestry cursor must survive an empty bag.
                city = city.copy(workers = listOf(CivsEquipment.consumeInputs(city.workers.single(), work.outputs)))
                job = city.forestryJobs?.values?.single()
                if (job == null) final = work
            }
            delivered shouldBe CivsForestry.MAX_CELLS.toLong()
            (delivered > 27 * 64) shouldBe true
            CivsEquipment.plainCount(city.workers.single(), "SPRUCE_SAPLING") shouldBe 0
            shouldThrow<CivsRejection> { CivsRules.finishWorldWork(city, requireNotNull(final), 9_000) }.reason shouldBe "stale-delivery"
            city.inventory shouldBe CivInventory(CivStorageMode.VIRTUAL)
            city.stock shouldBe Stock()
        }
    }

    test("an Auto construction kit native receipt replays once and cannot carry unrelated world changes") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("forestry-plant-replay")
            val plugin = paper.createSimplePlugin("ForestryPlantReplay")
            val city = forestryCity().copy(worldId = world.uid, stock = Stock())
            val roots = listOf(CivPosition(0, 65, 0), CivPosition(0, 65, 1), CivPosition(1, 65, 0), CivPosition(1, 65, 1))
            roots.forEach { world.getBlockAt(it.x, it.y, it.z).type = Material.AIR }
            world.loadChunk(0, 0)
            val work = CivWorldWork(city.workers.single().id, 0, city.buildings.last().id, roots.first().copy(x = -1),
                roots.map { CivPlanBlock(it, "minecraft:air", "minecraft:spruce_sapling[stage=0]") },
                inputs = mapOf("SPRUCE_SAPLING" to 4L), status = "planting-tree", focus = roots.first())
            val receipt = forestryReceipt(city, work, 1_000)
            receipt.after.stock.wood shouldBe 0L
            receipt.after.forestrySeeded shouldBe setOf(work.buildingId.toString())
            receipt.after.forestryJobs shouldBe null
            CivsStorage(plugin).use { storage ->
                storage.replaceCities(listOf(city))
                storage.reserve(city, receipt.id)
                storage.seal(receipt)
                storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
                storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
                roots.forEach { world.getBlockAt(it.x, it.y, it.z).type shouldBe Material.SPRUCE_SAPLING }
                storage.unlock(receipt.id)
            }
            shouldThrow<IllegalArgumentException> { receipt.copy(worldChanges = receipt.worldChanges!! +
                CivPlanBlock(CivPosition(2, 65, 0), "minecraft:stone", "minecraft:air")).validated() }
            shouldThrow<IllegalArgumentException> { receipt.copy(after = receipt.after.copy(stock = Stock(21))).validated() }
        }
    }

    test("fifteen maximal compact crowns with every native vine face state fit and roundtrip through the production city store") {
        val root = Files.createTempDirectory("civs-forestry-size-")
        try {
            val base = forestryCity().copy(workers = emptyList(), radius = 256)
            val vineStates = (1..31).map { mask -> "minecraft:vine[" + listOf("east", "north", "south", "up", "west")
                .mapIndexed { index, face -> "$face=${mask and (1 shl index) != 0}" }.joinToString(",") + "]" }
            val state = listOf("minecraft:spruce_log[axis=y]", "minecraft:spruce_log[axis=x]", "minecraft:spruce_log[axis=z]") +
                (1..7).map { "minecraft:spruce_leaves[distance=$it,persistent=false,waterlogged=false]" } + vineStates
            val vines = (0 until 31).associate { index -> CivPosition(-14 + index % 10 * 3, 66, -14 + index / 10 * 3) to index + 10 }
            val roots = listOf(CivPosition(0, 64, 0), CivPosition(0, 64, 1), CivPosition(1, 64, 0), CivPosition(1, 64, 1))
            val codes = (0 until CivsForestry.MAX_CELLS - 4).map { index ->
                val x = index % 31; val z = index / 31 % 31; val y = 2 + index / (31 * 31)
                ((vines[CivPosition(x - 15, y + 64, z - 15)] ?: (index % 10)) shl 16) or (y shl 10) or (z shl 5) or x
            } + roots.map { ((it.z + 15) shl 5) or (it.x + 15) }
            val mills = (0 until 15).map { index -> base.buildings.last().copy(id = UUID(9, index.toLong()),
                anchor = CivPosition(40 + index * 6, 64, 40), bounds = CivBox(CivPosition(40 + index * 6, 64, 40),
                    CivPosition(42 + index * 6, 68, 42)), entrance = CivPosition(41 + index * 6, 64, 43)) }
            val jobs = mills.associate { mill -> mill.id.toString() to CivForestryJob(UUID.randomUUID(), roots.first(),
                "SPRUCE_SAPLING", roots, state, codes, 128) }
            val city = CivsRules.validate(base.copy(buildings = listOf(base.buildings.first()) + mills, forestryJobs = jobs))
            (Gson().toJson(city).toByteArray().size < CivsStore.MAX_CITY_BYTES) shouldBe true
            (Gson().toJson(city).contains("\"cells\":\"")) shouldBe true
            CivsStore(root, Executor(Runnable::run)).use { store -> store.save(city).join() shouldBe city }
            CivsStore(root, Executor(Runnable::run)).use { store -> store.load().join().single() shouldBe city }
        } finally { root.toFile().deleteRecursively() }
    }

    test("compact cell decoding rejects oversized invalid coordinate and state codes before domain admission") {
        val adapter = CivForestryCellsAdapter()
        shouldThrow<IllegalArgumentException> { adapter.fromJson("\"${"AAAA".repeat(CivsForestry.MAX_CELLS + 1)}\"") }
        for (code in listOf(64 shl 16, 31, 31 shl 5, 48 shl 10)) {
            val encoded = Base64.getEncoder().encodeToString(byteArrayOf((code ushr 16).toByte(), (code ushr 8).toByte(), code.toByte()))
            shouldThrow<IllegalArgumentException> { adapter.fromJson("\"$encoded\"") }
        }
        val job = forestryTestJob()
        CivsForestry.valid(forestryCity(), forestryCity().buildings.last().id.toString(), job.copy(states = job.states.take(1))) shouldBe false
    }

    test("future crown columns and the actual whole growth event keep roofs approaches and retained roads clear") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("forestry-growth-guard")
            val city = forestryCity().copy(worldId = world.uid)
            val roots = listOf(CivPosition(0, 64, 0), CivPosition(0, 64, 1), CivPosition(1, 64, 0), CivPosition(1, 64, 1))
            CivsForestry.crownClear(city, roots, "SPRUCE_LOG", CivsForestry.paths(city)) shouldBe true
            CivsForestry.crownClear(city, roots, "SPRUCE_LOG", CivsForestry.paths(city, setOf(7 to 0))) shouldBe false
            val house = city.buildings.last().copy(id = UUID(0, 5), type = BuildingType.HOUSE, production = null,
                anchor = CivPosition(6, 64, 0), bounds = CivBox(CivPosition(6, 63, 0), CivPosition(8, 70, 2)),
                entrance = CivPosition(7, 64, 3))
            val housed = city.copy(buildings = city.buildings + house)
            CivsForestry.crownClear(housed, roots, "SPRUCE_LOG", CivsForestry.paths(housed)) shouldBe false
            val locked = hashSetOf<CivPosition>()
            val protection = CivsProtection({ listOf(housed) }, worldLocked = { worldId, point -> worldId == world.uid && point in locked },
                forestryPath = { worldId, x, z ->
                worldId == world.uid && x == -8 && z == 0
            })
            fun growth(point: CivPosition): StructureGrowEvent {
                val proposed = world.getBlockAt(point.x, point.y, point.z).state.apply { type = Material.SPRUCE_LEAVES }
                return StructureGrowEvent(Location(world, .5, 64.0, .5), TreeType.MEGA_REDWOOD, false, null, mutableListOf(proposed))
            }
            for (point in listOf(CivPosition(6, 100, 0), CivPosition(7, 100, 4), CivPosition(-8, 100, 0))) {
                protection.isProtected(world.uid, point.x, point.y, point.z) shouldBe false
                val event = growth(point)
                protection.tree(event)
                event.isCancelled shouldBe true
                event.blocks.size shouldBe 1 // Cancel the native tree; never prune it into a partial crown.
            }
            val open = growth(CivPosition(-12, 100, 0))
            protection.tree(open)
            open.isCancelled shouldBe false
            for (point in listOf(CivPosition(0, 64, 0), CivPosition(-12, 100, 0))) {
                locked += point
                val duringReceipt = growth(CivPosition(-12, 100, 0))
                protection.tree(duringReceipt)
                duringReceipt.isCancelled shouldBe true
                duringReceipt.blocks.size shouldBe 1
                locked.clear()
            }
        }
    }

    for (timber in listOf("SPRUCE", "JUNGLE", "DARK_OAK")) test("$timber planting plans four paid saplings with no mature-tree substitute") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = CivsWorksiteWorldFixture("forestry-$timber", Material.DIRT).also(paper.server::addWorld)
            val plugin = paper.createSimplePlugin("Forestry$timber")
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            try {
                val mill = spatialBuilding("nordic", "lumberjack", 1, CivPosition(0, 63, 0)).copy(production = "${timber}_LOG")
                val worker = spatialWorker(mill, "IRON_AXE")
                val city = spatialCity(world.uid, listOf(mill), worker)
                for (x in -4..4) for (z in -4..4) world.loadChunk(x, z)
                CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                    val runtime = CivsWorksites(plugin, access, { _, _ -> mapOf("${timber}_SAPLING" to 4L) }, { _, _ -> false })
                    repeat(100) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
                    val plan = requireNotNull(runtime.plan(city, worker))
                    plan.changes.size shouldBe 4
                    plan.inputs shouldBe mapOf("${timber}_SAPLING" to 4L)
                    plan.outputs shouldBe emptyMap()
                    plan.forestry shouldBe null
                    plan.status shouldBe "planting-tree"
                    plan.changes.all { it.before == "minecraft:air" && it.after == "minecraft:${timber.lowercase()}_sapling[stage=0]" } shouldBe true
                    runtime.validate(city, plan)
                    plan.changes.forEach { world.getBlockAt(it.position.x, it.position.y, it.position.z).type shouldBe Material.AIR }
                }
            } finally { tasks.close() }
        }
    }

    test("Auto worksite admits the initial construction kit without physical supplies and invalidates an unfunded cached second plot") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = CivsWorksiteWorldFixture("forestry-auto-empty", Material.DIRT).also(paper.server::addWorld)
            val plugin = paper.createSimplePlugin("ForestryAutoEmpty")
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            try {
                val mill = spatialBuilding("nordic", "lumberjack", 1, CivPosition(0, 63, 0)).copy(production = "SPRUCE_LOG")
                val worker = spatialWorker(mill, "IRON_AXE").copy(items = null, inventory = null)
                val city = spatialCity(world.uid, listOf(mill), worker).copy(inventory = null, autonomy = CivAutonomy(), stock = Stock())
                for (x in -4..4) for (z in -4..4) world.loadChunk(x, z)
                CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                    val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                    repeat(100) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
                    val plan = requireNotNull(runtime.plan(city, worker))
                    plan.status shouldBe "planting-tree"
                    plan.equipmentAfter shouldBe null
                    runtime.validate(city, plan)
                    val depleted = city.copy(forestrySeeded = setOf(mill.id.toString()))
                    runtime.plan(depleted, worker) shouldBe null
                }
            } finally { tasks.close() }
        }
    }
})

private fun forestryCity(autonomous: Boolean = true): Settlement {
    val hall = CivBuilding(UUID(0, 1), BuildingType.TOWN_HALL, 3, CivPosition(-30, 64, -30), 0, "fixture/hall",
        CivBox(CivPosition(-30, 63, -30), CivPosition(-28, 68, -28)), CivPosition(-29, 64, -27), BuildingPhase.ACTIVE)
    val mill = hall.copy(id = UUID(0, 2), type = BuildingType.FORESTER, level = 2, anchor = CivPosition(20, 64, 20),
        bounds = CivBox(CivPosition(20, 63, 20), CivPosition(22, 68, 22)), entrance = CivPosition(21, 64, 23),
        schematicId = "fixture/forester", production = "SPRUCE_LOG")
    return CivsRules.validate(Settlement(UUID(0, 10), UUID(0, 11), "Forestry", UUID(0, 12), CivPosition(0, 64, 0), 96,
        "nordic", if (autonomous) Stock(20) else Stock(), listOf(hall, mill), listOf(CivWorker(UUID(0, 3), "Forester", mill.id,
            nextDeliveryAt = 1)), inventory = if (autonomous) null else CivInventory(CivStorageMode.VIRTUAL),
        autonomy = if (autonomous) CivAutonomy() else null))
}

/** Detached geometry unit fixture; it does not generate a tree or establish native growth. */
private fun forestryCapture(): Triple<CivsForestry.Scan, Map<CivPosition, String>, List<CivPosition>> {
    val roots = listOf(CivPosition(0, 64, 0), CivPosition(0, 64, 1), CivPosition(1, 64, 0), CivPosition(1, 64, 1))
    val cells = linkedMapOf<CivPosition, String>()
    roots.forEach { root ->
        cells[root.copy(y = 63)] = "minecraft:dirt"
        for (y in 64..97) cells[root.copy(y = y)] = "minecraft:spruce_log[axis=y]"
    }
    for (point in listOf(CivPosition(2, 95, 0), CivPosition(3, 96, 0), CivPosition(4, 95, 0), CivPosition(5, 94, 0)))
        cells[point] = "minecraft:spruce_log[axis=x]"
    for (y in 97..98) for (x in -4..5) for (z in -4..5) cells.putIfAbsent(CivPosition(x, y, z),
        "minecraft:spruce_leaves[distance=1,persistent=false,waterlogged=false]")
    return Triple(CivsForestry.Scan(roots.first(), "SPRUCE_LOG"), cells, roots)
}

private fun forestryTestJob(): CivForestryJob {
    val (scan, cells, roots) = forestryCapture()
    while (!scan.complete && !scan.failed) scan.advance(128) { cells[it] ?: "minecraft:air" }
    return requireNotNull(scan.job(roots))
}

private fun forestryTestWork(city: Settlement, job: CivForestryJob, sampleDrops: Boolean = false, silkTouch: Boolean = false): CivWorldWork {
    val building = city.buildings.last { it.type == BuildingType.FORESTER }
    val worker = city.workers.single { it.assignment == building.id }
    val next = CivsForestry.batchEnd(job)
    val cells = job.cells.subList(job.next, next)
    val logs = cells.count { CivsForestry.material(job.state(it)) == job.timber }
    val drops = if (sampleDrops) buildMap {
        if (logs > 0) put(job.timber, logs.toLong())
        if (logs < cells.size) put(if (silkTouch) job.timber.removeSuffix("_LOG") + "_LEAVES" else job.sapling,
            if (silkTouch) (cells.size - logs).toLong() else 1L)
    } else emptyMap()
    val progress = CivForestryProgress(job, next, drops, logs)
    return CivWorldWork(worker.id, worker.completedCycles, building.id, job.root.copy(x = job.root.x - 1), cells.map { code ->
        val point = job.position(code)
        CivPlanBlock(point, job.state(code), if (point in job.roots) CivsForestry.saplingState(job.sapling) else "minecraft:air")
    }, inputs = if (job.next == 0 && !job.seedDeferred) mapOf(job.sapling to job.roots.size.toLong()) else emptyMap(),
        outputs = if (city.autonomy == null) drops else emptyMap(),
        status = "felling-tree", focus = job.root, forestry = progress)
}

private fun forestryReceipt(city: Settlement, work: CivWorldWork, at: Long): CivInventoryTransaction {
    val after = CivsRules.finishWorldWork(city, work, at)
    return CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision, after, emptyList(), worldChanges = work.changes,
        forestry = CivForestryReceipt(city.workers.single(), city.stock, city.forestryJobs?.get(work.buildingId.toString()),
            city.forestryJobs == null, work, at, city.forestrySeeded))
}

private fun forestryPlantWork(city: Settlement): CivWorldWork {
    val building = city.buildings.last { it.type == BuildingType.FORESTER }
    val worker = city.workers.single { it.assignment == building.id }
    val timber = requireNotNull(CivsProduction.material(building))
    val sapling = timber.removeSuffix("_LOG") + "_SAPLING"
    val roots = listOf(CivPosition(0, 64, 0), CivPosition(0, 64, 1), CivPosition(1, 64, 0), CivPosition(1, 64, 1))
        .take(if (CivsForestry.large(timber)) 4 else 1)
    return CivWorldWork(worker.id, worker.completedCycles, building.id, roots.first().copy(x = -1),
        roots.map { CivPlanBlock(it, "minecraft:air", CivsForestry.saplingState(sapling)) },
        inputs = mapOf(sapling to roots.size.toLong()), status = "planting-tree", focus = roots.first())
}
