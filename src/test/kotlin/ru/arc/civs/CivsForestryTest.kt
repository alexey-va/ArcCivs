package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.HeightMap
import org.bukkit.Material
import org.bukkit.TreeType
import org.bukkit.Bukkit
import org.bukkit.event.world.StructureGrowEvent
import org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executor

class CivsForestryTest : FunSpec({
    test("free sapling care preserves Auto stock its unused construction kit and worker state across receipt replay") {
        val directory = Files.createTempDirectory("civs-forestry-care-")
        try {
            val before = forestryCity().copy(stock = Stock())
            val work = forestryCareWork(before)
            CivsForestry.validateWork(before, work)
            CivsForestry.autoStock(before, work) shouldBe before.stock
            val receipt = forestryReceipt(before, work, 1_000)
            receipt.validated() shouldBe receipt
            receipt.worldChanges shouldBe work.changes
            receipt.after.stock shouldBe before.stock
            receipt.after.forestrySeeded shouldBe before.forestrySeeded
            receipt.after.forestryJobs shouldBe before.forestryJobs
            receipt.after.workers.single().let { after ->
                after.copy(completedCycles = 0, nextDeliveryAt = before.workers.single().nextDeliveryAt)
            } shouldBe before.workers.single()
            CivsStore(directory, Executor(Runnable::run)).use { store ->
                store.save(before).join()
                store.saveInventoryTransaction(receipt).join() shouldBe receipt
            }
            CivsStore(directory, Executor(Runnable::run)).use { store ->
                store.loadInventoryTransactions().join().single().validated() shouldBe receipt
            }
            shouldThrow<CivsRejection> { CivsRules.finishWorldWork(receipt.after, work, 2_000) }.reason shouldBe "stale-delivery"
            val seeded = before.copy(forestrySeeded = setOf(work.buildingId.toString()))
            CivsRules.finishWorldWork(seeded, work, 1_000).forestrySeeded shouldBe seeded.forestrySeeded
            // Watering cannot consume or unlock the one paid planting kit.
            CivsRules.finishWorldWork(receipt.after, forestryPlantWork(receipt.after), 2_000).stock shouldBe Stock()
            val physical = forestryCity(autonomous = false)
            val physicalWork = forestryCareWork(physical)
            val physicalAfter = CivsRules.finishWorldWork(physical, physicalWork, 1_000)
            physicalAfter.workers.single().skills shouldBe physical.workers.single().skills
            physicalAfter.workers.single().items shouldBe physical.workers.single().items
            CivInventoryTransaction(UUID.randomUUID(), physical.id, physical.revision, physicalAfter, emptyList(),
                worldChanges = physicalWork.changes).validated().after shouldBe physicalAfter
        } finally { directory.toFile().deleteRecursively() }
    }

    test("care accepts only one exact configured stage zero to one transition without goods equipment or reward authority") {
        val city = forestryCity().copy(stock = Stock())
        val work = forestryCareWork(city)
        val cell = work.changes.single()
        for (invalid in listOf(
            work.copy(inputs = mapOf("SPRUCE_SAPLING" to 1L)),
            work.copy(inputs = mapOf("BONE_MEAL" to 1L)),
            work.copy(outputs = mapOf("SPRUCE_LOG" to 1L)),
            work.copy(equipmentAfter = emptyList()),
            work.copy(durationMillis = 3_000),
            work.copy(focus = cell.position.copy(x = 2)),
            work.copy(changes = emptyList()),
            work.copy(changes = listOf(cell, cell.copy(position = cell.position.copy(x = 1)))),
            work.copy(changes = listOf(cell.copy(before = cell.after))),
            work.copy(changes = listOf(cell.copy(after = "minecraft:spruce_log[axis=y]"))),
            work.copy(changes = listOf(cell.copy(before = "minecraft:oak_sapling[stage=0]", after = "minecraft:oak_sapling[stage=1]"))),
            work.copy(miningStep = 1),
        )) shouldThrow<IllegalArgumentException> { CivsForestry.validateWork(city, invalid) }
        val paid = city.copy(forestryJobs = mapOf(work.buildingId.toString() to forestryTestJob()))
        shouldThrow<IllegalArgumentException> { CivsForestry.validateWork(paid, work) }
    }

    test("Auto and physical care replay their existing native journal once without creating a tree or any reward") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("forestry-care-replay")
            val plugin = paper.createSimplePlugin("ForestryCareReplay")
            world.loadChunk(0, 0)
            for (autonomous in listOf(true, false)) {
                val city = forestryCity(autonomous).copy(worldId = world.uid, stock = Stock())
                val work = forestryCareWork(city)
                val cell = work.changes.single()
                world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData = paper.server.createBlockData(cell.before)
                val receipt = if (autonomous) forestryReceipt(city, work, 1_000) else CivInventoryTransaction(UUID.randomUUID(),
                    city.id, city.revision, CivsRules.finishWorldWork(city, work, 1_000), emptyList(), worldChanges = work.changes)
                CivsStorage(plugin).use { storage ->
                    storage.replaceCities(listOf(city))
                    storage.reserve(city, receipt.id)
                    storage.seal(receipt)
                    storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
                    storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
                    world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.after
                    receipt.after.stock shouldBe city.stock
                    receipt.after.forestrySeeded shouldBe city.forestrySeeded
                    receipt.after.forestryJobs shouldBe city.forestryJobs
                    storage.unlock(receipt.id)
                }
            }
        }
    }

    test("a mature tree beyond the first sixteen columns outranks earlier care and empty planting ground") {
        forestryWorksite("forestry-care-priority") { world, runtime, city ->
            val worker = city.workers.single()
            val young = CivPosition(-15, 64, -26)
            val mature = CivPosition(10, 64, -26)
            world.getBlockAt(young.x, young.y, young.z).blockData = Material.OAK_SAPLING.createBlockData()
            forestrySmallOak(world, mature)
            runtime.pulse(listOf(city))
            runtime.plan(city, worker) shouldBe null
            repeat(2_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val work = requireNotNull(runtime.plan(city, worker))
            work.status shouldBe "felling-tree"
            work.focus shouldBe mature
            runtime.validate(city, work)
            world.getBlockAt(young.x, young.y, young.z).blockData.asString shouldBe "minecraft:oak_sapling[stage=0]"
        }
    }

    test("a nearby tree and the forester's existing two-block stance precede a distant scan-order tree") {
        forestryWorksite("forestry-nearby-tree", nanoTime = { 0L }) { world, runtime, city ->
            val worker = city.workers.single()
            val nearby = CivPosition(10, 64, 10)
            val standing = nearby.copy(z = nearby.z - 2)
            forestrySmallOak(world, CivPosition(-15, 64, -26))
            forestrySmallOak(world, nearby)
            runtime.residentFeet = { Location(world, standing.x + .5, standing.y.toDouble(), standing.z + .5) }
            repeat(12) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val work = requireNotNull(runtime.plan(city, worker))
            work.status shouldBe "felling-tree"
            work.focus shouldBe nearby
            work.standing shouldBe standing
            runtime.validate(city, work)
            world.getBlockAt(nearby.x, nearby.y, nearby.z).type shouldBe Material.OAK_LOG
        }
    }

    test("finishing a tree retains the unvisited forest instead of rescanning its empty prefix") {
        forestryWorksite("forestry-resume-after-felling", nanoTime = { 0L }) { world, runtime, initial ->
            var city = initial
            var worker = city.workers.single()
            val firstRoot = CivPosition(10, 64, 10)
            val nextRoot = firstRoot.copy(x = 12)
            forestrySmallOak(world, firstRoot)
            forestrySmallOak(world, nextRoot)
            repeat(2_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val first = requireNotNull(runtime.plan(city, worker))
            first.focus shouldBe firstRoot
            runtime.validate(city, first)
            city = CivsRules.finishWorldWork(city, first, 1_000)
            first.changes.forEach { cell -> world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
                .blockData = Bukkit.createBlockData(cell.after) }
            worker = city.workers.single()
            runtime.invalidate(worker.id)
            repeat(8) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val next = requireNotNull(runtime.plan(city, worker))
            next.status shouldBe "felling-tree"
            next.focus shouldBe nextRoot
            runtime.validate(city, next)
        }
    }

    for (arrival in listOf("carried", "building-stock")) test("$arrival saplings arriving mid-scan restore priority to an earlier mature tree") {
        var supplies = emptyMap<String, Long>()
        forestryWorksite("forestry-care-refill-$arrival", autonomous = false, supplies = { supplies }) { world, runtime, before ->
            var city = before
            var worker = city.workers.single()
            val mature = CivPosition(-15, 64, -26)
            val young = CivPosition(10, 64, -26)
            forestrySmallOak(world, mature)
            world.getBlockAt(young.x, young.y, young.z).blockData = Material.OAK_SAPLING.createBlockData()
            // At most 1,024 of 1,596 columns: the first unfunded root has passed, but care is not admitted yet.
            repeat(64) { runtime.pulse(listOf(city)) }
            runtime.plan(city, worker) shouldBe null
            if (arrival == "carried") {
                worker = CivsEquipment.collectSupplies(worker, mapOf("OAK_SAPLING" to 1L))
                city = city.copy(workers = listOf(worker))
            } else supplies = mapOf("OAK_SAPLING" to 1L)
            repeat(2_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val work = requireNotNull(runtime.plan(city, worker))
            work.status shouldBe "felling-tree"
            work.focus shouldBe mature
            work.inputs shouldBe mapOf("OAK_SAPLING" to 1L)
            runtime.validate(city, work)
            world.getBlockAt(young.x, young.y, young.z).blockData.asString shouldBe "minecraft:oak_sapling[stage=0]"
        }
    }

    for (paid in listOf(false, true)) test("a grown obstruction replaces the cached forestry stance without losing the ${if (paid) "paid" else "unpaid"} tree") {
        var paused = false
        forestryWorksite("forestry-grown-stance-$paid", paused = { paused }) { world, runtime, initial ->
            val worker = initial.workers.single()
            val root = CivPosition(-15, 64, -26)
            forestrySmallOak(world, root)
            var city = initial
            fun select(): CivWorldWork {
                repeat(3_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
                return requireNotNull(runtime.plan(city, worker))
            }
            var first = select()
            if (paid) {
                val original = requireNotNull(first.forestry).job
                val cut = original.position(original.cells.first())
                world.getBlockAt(cut.x, cut.y, cut.z).type = Material.AIR
                val job = original.copy(next = 1)
                city = city.copy(forestryJobs = mapOf(first.buildingId.toString() to job))
                runtime.invalidate(worker.id)
                first = select()
                first.forestry!!.job shouldBe job
            }
            val before = city
            val oldStanding = first.standing
            world.getBlockAt(oldStanding.x, oldStanding.y, oldStanding.z).type = Material.OAK_LOG
            world.getBlockAt(oldStanding.x, oldStanding.y + 1, oldStanding.z).type = Material.OAK_LOG
            paused = true
            repeat(20) { runtime.pulse(listOf(city)) }
            runtime.plan(city, worker) shouldBe first
            paused = false
            repeat(3_000) {
                if (runtime.plan(city, worker)?.standing == oldStanding || runtime.plan(city, worker) == null)
                    runtime.pulse(listOf(city))
            }
            val second = requireNotNull(runtime.plan(city, worker))
            (second.standing != oldStanding) shouldBe true
            second.focus shouldBe first.focus
            second.inputs shouldBe first.inputs
            if (paid) second.forestry!!.job shouldBe first.forestry!!.job
            city shouldBe before
            world.getBlockAt(oldStanding.x, oldStanding.y, oldStanding.z).type shouldBe Material.OAK_LOG
            runtime.validate(city, second)
        }
    }

    test("temporary chunk unload preserves the cached forestry stance without rejecting it") {
        forestryWorksite("forestry-stance-chunk-unload") { world, runtime, city ->
            val worker = city.workers.single()
            forestrySmallOak(world, CivPosition(-15, 64, -26))
            repeat(3_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val first = requireNotNull(runtime.plan(city, worker))
            val chunkX = first.standing.x shr 4
            val chunkZ = first.standing.z shr 4
            world.unloadChunk(chunkX, chunkZ)
            repeat(20) { runtime.pulse(listOf(city)) }
            runtime.plan(city, worker) shouldBe first
            world.isChunkLoaded(chunkX, chunkZ) shouldBe false
            world.loadChunk(chunkX, chunkZ)
            runtime.pulse(listOf(city))
            runtime.plan(city, worker) shouldBe first
            runtime.validate(city, first)
            // An explicit rejection is still the first failure of this physical point.
            runtime.rejectStanding(city, worker, first) shouldBe true
            repeat(3_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val next = requireNotNull(runtime.plan(city, worker))
            (next.standing != first.standing) shouldBe true
            next.focus shouldBe first.focus
            city.forestryJobs shouldBe null
            runtime.validate(city, next)
        }
    }

    test("a rejected reachable forestry stance is not selected again while another approach is available") {
        forestryWorksite("forestry-rejected-stance") { world, runtime, city ->
            val worker = city.workers.single()
            val root = CivPosition(-15, 64, -26)
            forestrySmallOak(world, root)
            repeat(3_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val first = requireNotNull(runtime.plan(city, worker))
            runtime.rejectStanding(city, worker, first) shouldBe true
            runtime.rejectStanding(city, worker, first) shouldBe false
            repeat(3_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val second = requireNotNull(runtime.plan(city, worker))
            (second.standing != first.standing) shouldBe true
            second.focus shouldBe root
            second.inputs shouldBe first.inputs
            city.forestryJobs shouldBe null
            runtime.validate(city, second)
            runtime.rejectStanding(city, worker, second) shouldBe true
            runtime.invalidate(worker.id)
            repeat(3_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val third = requireNotNull(runtime.plan(city, worker))
            (third.standing !in setOf(first.standing, second.standing)) shouldBe true
            third.focus shouldBe root
            runtime.validate(city, third)
        }
    }

    for (paid in listOf(false, true)) test("a ${if (paid) "paid" else "unpaid"} tree retries its exact batch after a ninety six second pause without repeating field discovery") {
        failOnUnsupportedMockBukkitOperation {
            var paused = false
            forestryWorksite("forestry-slow-retry-$paid", paused = { paused }) { world, runtime, initial ->
                val clock = Bukkit.getScheduler() as BukkitSchedulerMock
                val worker = initial.workers.single()
                val mill = initial.building(requireNotNull(worker.assignment))!!
                val bounds = CivsWorksiteLayouts.site(mill)!!.getAsJsonObject("forester").getAsJsonObject("searchBounds")
                val last = CivsWorksiteLayouts.vector(bounds["max"])
                val root = CivsWorksiteLayouts.point(mill, CivPosition(last.x - 1, 1, last.z - 1))
                forestrySmallOak(world, root)
                var city = initial
                fun select(limit: Int): CivWorldWork {
                    repeat(limit) {
                        // One admission every nine five-tick service slots matches the reported city load.
                        clock.performTicks(45)
                        runtime.pulse(listOf(city))
                        runtime.plan(city, worker)?.let { return it }
                    }
                    error("No replacement approach within $limit admissions: ${runtime.debug(setOf(worker.id))}")
                }
                var first = select(3_000)
                (Bukkit.getCurrentTick() >= 1_920) shouldBe true
                if (paid) {
                    val job = requireNotNull(first.forestry).job
                    val cut = job.position(job.cells.first())
                    world.getBlockAt(cut.x, cut.y, cut.z).type = Material.AIR
                    city = city.copy(forestryJobs = mapOf(mill.id.toString() to job.copy(next = 1)))
                    runtime.invalidate(worker.id)
                    first = select(64)
                }
                val original = city
                runtime.rejectStanding(city, worker, first) shouldBe true
                runtime.plan(city, worker) shouldBe null
                paused = true
                clock.performTicks(1_921)
                repeat(4) { runtime.pulse(listOf(city)) }
                runtime.plan(city, worker) shouldBe null
                paused = false
                val replacement = select(4)
                (replacement.standing != first.standing) shouldBe true
                replacement.copy(standing = first.standing) shouldBe first
                runtime.validate(city, replacement)
                city shouldBe original
                val receipt = forestryReceipt(city, replacement, 200_000).validated()
                val plugin = Bukkit.getPluginManager().getPlugin("ForestryCare")!!
                CivsStorage(plugin) { emptyArray() }.use { storage ->
                    storage.replaceCities(listOf(city))
                    storage.reserve(city, receipt.id)
                    storage.seal(receipt)
                    storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
                    storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
                    storage.unlock(receipt.id)
                }
                shouldThrow<CivsRejection> { CivsRules.finishWorldWork(receipt.after, replacement, 200_001) }.reason shouldBe "stale-delivery"
            }
        }
    }

    for (forestBudget in listOf(128, 192)) test("an accepted tree recovers exhausted approaches with forest budget $forestBudget without changing its batch") {
        var yieldRetry = false
        var nanos = 0L
        forestryWorksite("forestry-exhausted-recovery", nanoTime = {
            if (yieldRetry) nanos.also { nanos += 500_000L } else 0L
        }, settings = CivsWorksiteSettings(forestWorkUnitsPerPulse = forestBudget)) { world, runtime, initial ->
            val clock = Bukkit.getScheduler() as BukkitSchedulerMock
            val worker = initial.workers.single()
            forestrySmallOak(world, CivPosition(-15, 64, -26))
            repeat(50_000) { if (runtime.plan(initial, worker) == null) runtime.pulse(listOf(initial)) }
            val admitted = requireNotNull(runtime.plan(initial, worker)).forestry!!.job
            val cut = admitted.position(admitted.cells.first())
            world.getBlockAt(cut.x, cut.y, cut.z).type = Material.AIR
            val accepted = admitted.copy(next = 1)
            val city = initial.copy(forestryJobs = mapOf(requireNotNull(worker.assignment).toString() to accepted))
            runtime.invalidate(worker.id)
            repeat(64) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val first = requireNotNull(runtime.plan(city, worker))
            first.forestry!!.job shouldBe accepted
            var current = first
            val tried = linkedSetOf<CivPosition>()
            var exhausted = false
            repeat(80) {
                if (exhausted) return@repeat
                tried.add(current.standing) shouldBe true
                runtime.rejectStanding(city, worker, current) shouldBe true
                world.getBlockAt(current.standing.x, current.standing.y, current.standing.z).type = Material.STONE
                var next: CivWorldWork? = null
                for (pulse in 1..64) {
                    clock.performTicks(5)
                    val rays = world.rayTraces
                    runtime.pulse(listOf(city))
                    (world.rayTraces - rays <= 8) shouldBe true
                    next = runtime.plan(city, worker)
                    if (next != null) break
                    val entry = (runtime.debug(setOf(worker.id))["entries"] as List<*>).single() as Map<*, *>
                    if ((entry["failedStances"] as? Map<*, *>)?.get("exhausted") == true) { exhausted = true; break }
                }
                if (!exhausted) {
                    current = requireNotNull(next)
                    current.copy(standing = first.standing) shouldBe first
                }
            }
            exhausted shouldBe true
            runtime.plan(city, worker) shouldBe null
            repeat(4) { clock.performTicks(5); runtime.pulse(listOf(city)) }
            runtime.plan(city, worker) shouldBe null
            for (dy in 0..2) world.getBlockAt(first.standing.x, first.standing.y + dy, first.standing.z).type = Material.AIR
            runtime.changed(world.uid, first.standing)
            yieldRetry = true
            clock.performTicks(100)
            runtime.pulse(listOf(city))
            runtime.plan(city, worker) shouldBe null
            val entry = (runtime.debug(setOf(worker.id))["entries"] as List<*>).single() as Map<*, *>
            val pending = entry["pendingApproach"] as Map<*, *>
            ((pending["checkedChanges"] as Number).toInt() in 1 until first.changes.size) shouldBe true
            (entry["failedStances"] as Map<*, *>)["exhausted"] shouldBe false
            repeat(64) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val recovered = requireNotNull(runtime.plan(city, worker))
            recovered shouldBe first
            runtime.validate(city, recovered)
            city.forestryJobs?.values?.single() shouldBe accepted
            city.stock shouldBe Stock()
        }
    }

    for (paid in listOf(false, true)) test("a ${if (paid) "paid" else "unpaid"} tree can work remotely through its own captured crown after one rejected approach") {
        forestryWorksite("forestry-remote-$paid", nanoTime = { 0L }) { world, runtime, initial ->
            val worker = initial.workers.single()
            val root = CivPosition(-15, 64, -26)
            forestrySmallOak(world, root)
            val leaf = "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]"
            for (y in 64..66) for (z in root.z - 1..root.z + 1)
                world.getBlockAt(root.x - 2, y, z).blockData = Bukkit.createBlockData(leaf)
            world.getBlockAt(root.x - 1, 65, root.z).blockData = Bukkit.createBlockData(leaf)
            var city = initial
            fun select(): CivWorldWork {
                repeat(3_000) {
                    runtime.pulse(listOf(city))
                    runtime.plan(city, worker)?.let { return it }
                }
                error("No forestry candidate: ${runtime.debug(setOf(worker.id))}")
            }
            var first = select()
            if (paid) {
                val job = requireNotNull(first.forestry).job
                val cut = job.position(job.cells.first())
                world.getBlockAt(cut.x, cut.y, cut.z).type = Material.AIR
                city = city.copy(forestryJobs = mapOf(first.buildingId.toString() to job.copy(next = 1)))
                runtime.invalidate(worker.id)
                first = select()
            }
            val feet = Location(world, root.x - 5.5, 64.0, root.z + .5)
            runtime.residentFeet = { feet }
            val eye = feet.clone().add(0.0, 1.62, 0.0)
            citizenReachableBlockPoint(world, eye, root, CITIZEN_REMOTE_WORK_REACH) shouldBe null
            runtime.rejectStanding(city, worker, first) shouldBe true
            val remote = select()
            remote.remoteWork shouldBe true
            remote.standing shouldBe CivPosition(feet.blockX, 64, feet.blockZ)
            remote.copy(standing = first.standing, remoteWork = false) shouldBe first
            runtime.validate(city, remote)
            (citizenReachableBlockPoint(world, eye, root, citizenWorkReach(BuildingType.FORESTER, remote), remote) != null) shouldBe true
            val receipt = forestryReceipt(city, remote, 200_000).validated()
            receipt.worldChanges shouldBe first.changes
            shouldThrow<CivsRejection> { CivsRules.finishWorldWork(receipt.after, remote, 200_001) }.reason shouldBe "stale-delivery"
            // Another natural crown may hide the animation, without becoming part of this receipt.
            for (y in 64..66) for (z in root.z - 1..root.z + 1)
                world.getBlockAt(root.x - 3, y, z).blockData = Bukkit.createBlockData(leaf)
            (citizenReachableBlockPoint(world, eye, root, CITIZEN_REMOTE_WORK_REACH, remote) != null) shouldBe true
            runtime.validate(city, remote)
            remote.changes shouldBe first.changes
            // Decorative foliage and an actual wall remain obstacles.
            for (state in listOf("minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]", "minecraft:stone")) {
                for (y in 64..66) for (z in root.z - 1..root.z + 1)
                    world.getBlockAt(root.x - 3, y, z).blockData = Bukkit.createBlockData(state)
                citizenReachableBlockPoint(world, eye, root, CITIZEN_REMOTE_WORK_REACH, remote) shouldBe null
                shouldThrow<CivsRejection> { runtime.validate(city, remote) }.reason shouldBe "stale-worksite"
            }
            city.stock shouldBe Stock()
        }
    }

    for (paid in listOf(false, true)) test("initial ${if (paid) "paid" else "unpaid"} tree admission works from safe distant feet when nearby stances are unsupported") {
        forestryWorksite("forestry-initial-remote-$paid", autonomous = false, nanoTime = { 0L },
            settings = CivsWorksiteSettings(standingChecksPerPulse = 1, rayChecksPerPulse = 1)) { world, runtime, initial ->
            val worker = initial.workers.single().let { it.copy(items = it.items!!.toMutableList().also { slots ->
                slots[CivsEquipment.BAG] = CivResidentItem("OAK_SAPLING", 4)
            }) }
            val root = CivPosition(-15, 64, -26)
            forestrySmallOak(world, root)
            for (x in root.x - 2..root.x + 2) for (z in root.z - 2..root.z + 2)
                if (x != root.x || z != root.z) for (y in 61..63) world.getBlockAt(x, y, z).type = Material.AIR
            val scan = CivsForestry.Scan(root, "OAK_LOG")
            while (!scan.complete && !scan.failed) scan.advance(128) { p -> world.getBlockAt(p.x, p.y, p.z).blockData.asString }
            val captured = requireNotNull(scan.job(listOf(root)))
            val job = if (paid) captured.copy(next = 1) else null
            job?.let {
                val previous = it.position(it.cells.first())
                world.getBlockAt(previous.x, previous.y, previous.z).type = Material.AIR
            }
            val city = initial.copy(workers = listOf(worker),
                forestryJobs = job?.let { mapOf(requireNotNull(worker.assignment).toString() to it) })
            // This neighbouring natural crown is not part of either immutable tree batch.
            for (y in 64..66) for (z in root.z - 1..root.z + 1)
                world.getBlockAt(root.x - 3, y, z).blockData = Bukkit.createBlockData(
                    "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]")
            val feet = Location(world, root.x - 5.5, 64.0, root.z + .5)
            runtime.residentFeet = { feet }
            citizenCanReachBlock(world, feet.clone().add(0.0, 1.62, 0.0), root, CITIZEN_BLOCK_REACH) shouldBe false
            runtime.plan(city, worker) shouldBe null
            repeat(3_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val work = requireNotNull(runtime.plan(city, worker)) { "No initial remote work: ${runtime.debug(setOf(worker.id))}" }
            work.remoteWork shouldBe true
            work.standing shouldBe CivPosition(feet.blockX, 64, feet.blockZ)
            work.focus shouldBe root
            work.inputs shouldBe if (paid) emptyMap() else mapOf("OAK_SAPLING" to 1L)
            if (paid) requireNotNull(work.forestry).job shouldBe job
            runtime.validate(city, work)
            val after = CivsRules.finishWorldWork(city, work, 200_000)
            after.workers.single().completedCycles shouldBe worker.completedCycles + 1
            CivsEquipment.plainCount(after.workers.single(), "OAK_SAPLING") shouldBe if (paid) 4L else 3L
            CivsEquipment.plainCount(after.workers.single(), "OAK_LOG") shouldBe work.outputs.getValue("OAK_LOG")
            after.stock shouldBe city.stock
            shouldThrow<CivsRejection> { CivsRules.finishWorldWork(after, work, 200_001) }.reason shouldBe "stale-delivery"
            work.changes.forEach { cell ->
                world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.before
            }
            city.workers.single() shouldBe worker
            CivsEquipment.plainCount(worker, "OAK_SAPLING") shouldBe 4L
            city.forestryJobs?.values?.single() shouldBe job
        }
    }

    test("one ordinary ray slice yields to a safe remote tree approach on the very next pulse") {
        forestryWorksite("forestry-bounded-initial-remote", autonomous = false, nanoTime = { 0L },
            settings = CivsWorksiteSettings(rayChecksPerPulse = 1)) { world, runtime, initial ->
            val worker = initial.workers.single()
            val root = CivPosition(-15, 64, -26)
            forestrySmallOak(world, root)
            val scan = CivsForestry.Scan(root, "OAK_LOG")
            while (!scan.complete && !scan.failed) scan.advance(128) { p -> world.getBlockAt(p.x, p.y, p.z).blockData.asString }
            val job = requireNotNull(scan.job(listOf(root))).copy(next = 1)
            val cut = job.position(job.cells.first())
            world.getBlockAt(cut.x, cut.y, cut.z).type = Material.AIR
            val city = initial.copy(forestryJobs = mapOf(requireNotNull(worker.assignment).toString() to job))
            for (y in 64..66) for (z in root.z - 3..root.z + 3)
                world.getBlockAt(root.x - 1, y, z).blockData = Bukkit.createBlockData(
                    "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]")
            val ordinary = root.copy(x = root.x - 2)
            citizenStandable(world, ordinary) shouldBe true
            citizenCanReachBlock(world, Location(world, ordinary.x + .5, ordinary.y + 1.62, ordinary.z + .5), root) shouldBe false
            val feet = Location(world, root.x - 5.5, 64.0, root.z + .5)
            runtime.residentFeet = { feet }
            runtime.pulse(listOf(city))
            runtime.plan(city, worker) shouldBe null
            (runtime.debug()["remainingPulseBudget"] as Map<*, *>)["rays"] shouldBe 0
            // One service pulse is 250ms; the other ordinary aim points must not consume seconds first.
            runtime.pulse(listOf(city))
            val work = requireNotNull(runtime.plan(city, worker)) { "Remote probe did not follow its ordinary slice: ${runtime.debug()}" }
            work.remoteWork shouldBe true
            work.standing shouldBe CivPosition(feet.blockX, feet.blockY, feet.blockZ)
            requireNotNull(work.forestry).job shouldBe job
            runtime.validate(city, work)
            city.forestryJobs?.values?.single() shouldBe job
            work.changes.forEach { cell ->
                world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.before
            }
        }
    }

    test("ordinary slices before actor spawn preserve the early remote attempt for its first real feet") {
        forestryWorksite("forestry-cold-start-remote", autonomous = false, nanoTime = { 0L },
            settings = CivsWorksiteSettings(rayChecksPerPulse = 1)) { world, runtime, initial ->
            val worker = initial.workers.single()
            val root = CivPosition(-15, 64, -26)
            forestrySmallOak(world, root)
            val scan = CivsForestry.Scan(root, "OAK_LOG")
            while (!scan.complete && !scan.failed) scan.advance(128) { p -> world.getBlockAt(p.x, p.y, p.z).blockData.asString }
            val job = requireNotNull(scan.job(listOf(root))).copy(next = 1)
            val cut = job.position(job.cells.first())
            world.getBlockAt(cut.x, cut.y, cut.z).type = Material.AIR
            val city = initial.copy(forestryJobs = mapOf(requireNotNull(worker.assignment).toString() to job))
            for ((dx, dz) in listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1))
                for (y in 61..63) world.getBlockAt(root.x + dx, y, root.z + dz).type = Material.AIR
            for (y in 64..66) for (z in root.z - 3..root.z + 3)
                world.getBlockAt(root.x + 1, y, z).blockData = Bukkit.createBlockData(
                    "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]")
            val ordinary = root.copy(x = root.x + 2)
            citizenStandable(world, ordinary) shouldBe true
            citizenCanReachBlock(world, Location(world, ordinary.x + .5, ordinary.y + 1.62, ordinary.z + .5), root) shouldBe false
            runtime.residentFeet = { null }
            repeat(2) {
                val raysBefore = world.rayTraces
                runtime.pulse(listOf(city))
                runtime.plan(city, worker) shouldBe null
                // Missing actor must neither consume the remote attempt nor stall the ordinary aim cursor.
                world.rayTraces - raysBefore shouldBe 1
                (runtime.debug()["remainingPulseBudget"] as Map<*, *>)["rays"] shouldBe 0
            }
            val feet = Location(world, root.x + 6.5, 64.0, root.z + .5)
            citizenPacketSupported(world, CitizenPoint(feet.x, feet.y, feet.z)) shouldBe true
            citizenPacketBodyClear(world, CitizenPoint(feet.x, feet.y, feet.z), false) shouldBe true
            runtime.residentFeet = { feet }
            runtime.pulse(listOf(city))
            val work = requireNotNull(runtime.plan(city, worker)) { "Spawn did not unlock the reserved remote probe: ${runtime.debug()}" }
            work.remoteWork shouldBe true
            work.standing shouldBe CivPosition(feet.blockX, feet.blockY, feet.blockZ)
            requireNotNull(work.forestry).job shouldBe job
            runtime.validate(city, work)
            city.forestryJobs?.values?.single() shouldBe job
            work.changes.forEach { cell ->
                world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.before
            }
        }
    }

    test("an optional unloaded stance cannot pin a paid tree ahead of its loaded remote worker") {
        forestryWorksite("forestry-optional-unloaded-stance", autonomous = false, nanoTime = { 0L },
            // A complete 224-candidate ordinary sweep must fit before its same-pulse remote probe.
            settings = CivsWorksiteSettings(standingChecksPerPulse = 256, rayChecksPerPulse = 1)) { world, runtime, initial ->
            val worker = initial.workers.single()
            val root = CivPosition(15, 64, -26)
            forestrySmallOak(world, root)
            for (x in root.x - 3..root.x + 3) for (z in root.z - 3..root.z + 3)
                if (x != root.x || z != root.z) for (y in 59..63) world.getBlockAt(x, y, z).type = Material.AIR
            val scan = CivsForestry.Scan(root, "OAK_LOG")
            while (!scan.complete && !scan.failed) scan.advance(128) { p -> world.getBlockAt(p.x, p.y, p.z).blockData.asString }
            val job = requireNotNull(scan.job(listOf(root))).copy(next = 1)
            val cut = job.position(job.cells.first())
            world.getBlockAt(cut.x, cut.y, cut.z).type = Material.AIR
            val city = initial.copy(forestryJobs = mapOf(requireNotNull(worker.assignment).toString() to job))
            val feet = Location(world, root.x - 5.5, 64.0, root.z + .5)
            runtime.residentFeet = { feet }
            val missingX = (root.x + 1) shr 4
            val missingZ = root.z shr 4
            world.unloadChunk(missingX, missingZ)
            world.isChunkLoaded(root.x shr 4, root.z shr 4) shouldBe true
            world.isChunkLoaded(feet.blockX shr 4, feet.blockZ shr 4) shouldBe true
            runtime.pulse(listOf(city))
            val work = requireNotNull(runtime.plan(city, worker)) { "Unloaded optional stance pinned ${runtime.debug(setOf(worker.id))}" }
            work.remoteWork shouldBe true
            work.standing shouldBe CivPosition(feet.blockX, feet.blockY, feet.blockZ)
            work.focus shouldBe root
            requireNotNull(work.forestry).job shouldBe job
            work.inputs shouldBe emptyMap()
            runtime.validate(city, work)
            world.isChunkLoaded(missingX, missingZ) shouldBe false
            city.forestryJobs?.values?.single() shouldBe job
            work.changes.forEach { cell ->
                world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.before
            }
        }
    }

    for (stepped in listOf(false, true)) test("remote forestry uses actual fractional feet on ${if (stepped) "an off-centre lower stair" else "a lower slab"}") {
        forestryWorksite("forestry-fractional-$stepped", nanoTime = { 0L }, collisionForData = { data ->
            when (data.material) {
                Material.OAK_SLAB -> listOf(org.bukkit.util.BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0))
                Material.OAK_STAIRS -> listOf(org.bukkit.util.BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0),
                    org.bukkit.util.BoundingBox(.5, .5, 0.0, 1.0, 1.0, 1.0))
                else -> null
            }
        }) { world, runtime, city ->
            val worker = city.workers.single()
            val root = CivPosition(-15, 64, -26)
            forestrySmallOak(world, root)
            fun select(): CivWorldWork {
                repeat(3_000) { runtime.pulse(listOf(city)); runtime.plan(city, worker)?.let { return it } }
                error("No fractional forestry fallback: ${runtime.debug(setOf(worker.id))}")
            }
            val first = select()
            val feet = Location(world, root.x - 6.0 + if (stepped) .15 else .5, 64.5, root.z + .5)
            world.getBlockAt(feet.blockX, 64, feet.blockZ).type = if (stepped) Material.OAK_STAIRS else Material.OAK_SLAB
            runtime.residentFeet = { feet }
            citizenPacketSupported(world, CitizenPoint(feet.x, feet.y, feet.z)) shouldBe true
            citizenPacketBodyClear(world, CitizenPoint(feet.x, feet.y, feet.z), false) shouldBe true
            runtime.rejectStanding(city, worker, first) shouldBe true
            val remote = select()
            remote.remoteWork shouldBe true
            remote.copy(standing = first.standing, remoteWork = false) shouldBe first
            runtime.validate(city, remote)
            forestryReceipt(city, remote, 200_000).validated().worldChanges shouldBe first.changes
        }
    }

    test("a known distant forester keeps trying distinct stances at its unpaid tree before deferring it") {
        forestryWorksite("forestry-remote-too-far", nanoTime = { 0L }) { world, runtime, city ->
            val worker = city.workers.single()
            val firstRoot = CivPosition(-15, 64, -26)
            val laterRoot = CivPosition(10, 64, -26)
            forestrySmallOak(world, firstRoot)
            forestrySmallOak(world, laterRoot)
            repeat(3_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val first = requireNotNull(runtime.plan(city, worker))
            runtime.residentFeet = { Location(world, 8.5, 64.0, -25.5) }
            runtime.rejectStanding(city, worker, first) shouldBe true
            repeat(3_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val next = requireNotNull(runtime.plan(city, worker))
            next.focus shouldBe firstRoot
            (next.standing != first.standing) shouldBe true
            next.copy(standing = first.standing) shouldBe first
            next.remoteWork shouldBe false
            runtime.rejectStanding(city, worker, next) shouldBe true
            repeat(3_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val third = requireNotNull(runtime.plan(city, worker))
            third.focus shouldBe firstRoot
            (third.standing !in setOf(first.standing, next.standing)) shouldBe true
            third.copy(standing = first.standing) shouldBe first
            val entry = (runtime.debug(setOf(worker.id))["entries"] as List<*>).single() as Map<*, *>
            (entry["deferredForestApproaches"] as Map<*, *>)["total"] shouldBe 0
            runtime.validate(city, third)
            city.forestryJobs shouldBe null
        }
    }

    test("a route rejected unpaid tree yields to a later tree and stays deferred when discovery restarts") {
        forestryWorksite("forestry-route-rejected-field", nanoTime = { 0L }) { world, runtime, city ->
            val clock = Bukkit.getScheduler() as BukkitSchedulerMock
            val worker = city.workers.single()
            val firstRoot = CivPosition(-15, 64, -26)
            val laterRoot = CivPosition(10, 64, -26)
            val onlyStanding = firstRoot.copy(x = firstRoot.x + 1)
            forestrySmallOak(world, firstRoot)
            forestrySmallOak(world, laterRoot)
            // The isolated footing passes support/body/ray checks, but has no supported incoming step.
            for (x in firstRoot.x - 3..firstRoot.x + 3) for (z in firstRoot.z - 3..firstRoot.z + 3)
                if ((x != firstRoot.x || z != firstRoot.z) && (x != onlyStanding.x || z != onlyStanding.z))
                    for (y in 59..63) world.getBlockAt(x, y, z).type = Material.AIR
            fun select(): CivWorldWork {
                repeat(3_000) {
                    clock.performTicks(5)
                    runtime.pulse(listOf(city))
                    runtime.plan(city, worker)?.let { return it }
                }
                error("No forestry candidate: ${runtime.debug(setOf(worker.id))}")
            }
            val first = select()
            first.focus shouldBe firstRoot
            first.standing shouldBe onlyStanding
            runtime.validate(city, first)
            runtime.rejectStanding(city, worker, first) shouldBe true
            val later = select()
            later.focus shouldBe laterRoot
            runtime.validate(city, later)
            val entry = (runtime.debug(setOf(worker.id))["entries"] as List<*>).single() as Map<*, *>
            (entry["deferredForestApproaches"] as Map<*, *>)["total"] shouldBe 1
            val column = (entry["forestSearch"] as Map<*, *>)["column"]
            val distant = laterRoot.copy(x = laterRoot.x + 2, y = 63)
            world.getBlockAt(distant.x, distant.y, distant.z).type = Material.STONE
            runtime.changed(world.uid, distant)
            val unchanged = (runtime.debug(setOf(worker.id))["entries"] as List<*>).single() as Map<*, *>
            (unchanged["deferredForestApproaches"] as Map<*, *>)["total"] shouldBe 1
            (unchanged["forestSearch"] as Map<*, *>)["column"] shouldBe column
            runtime.plan(city, worker) shouldBe later
            // Restart discovery without a receipt; elapsed time must not put the bad root first again.
            runtime.invalidate(worker.id)
            runtime.invalidate(worker.id)
            clock.performTicks(1_921)
            select().focus shouldBe laterRoot
            val reopened = onlyStanding.copy(x = onlyStanding.x + 1, y = 63)
            world.getBlockAt(reopened.x, reopened.y, reopened.z).type = Material.DIRT
            world.getBlockAt(reopened.x + 1, reopened.y, reopened.z).type = Material.DIRT
            runtime.changed(world.uid, reopened)
            val refreshed = (runtime.debug(setOf(worker.id))["entries"] as List<*>).single() as Map<*, *>
            (refreshed["deferredForestApproaches"] as Map<*, *>)["total"] shouldBe 0
            runtime.invalidate(worker.id)
            select().focus shouldBe firstRoot
            city.forestryJobs shouldBe null
            city.stock shouldBe Stock()
            for (cell in first.changes)
                world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.before
        }
    }

    test("an unpaid failed tree retains exhausted approaches until relevant geometry changes") {
        forestryWorksite("forestry-deferred-full-sweep", nanoTime = { 0L }) { world, runtime, initial ->
            val clock = Bukkit.getScheduler() as BukkitSchedulerMock
            val worker = initial.workers.single()
            val city = initial.copy(forestrySeeded = setOf(requireNotNull(worker.assignment).toString()))
            val root = CivPosition(-15, 64, -26)
            val onlyStanding = root.copy(x = root.x + 1)
            forestrySmallOak(world, root)
            for (x in root.x - 3..root.x + 3) for (z in root.z - 3..root.z + 3)
                if ((x != root.x || z != root.z) && (x != onlyStanding.x || z != onlyStanding.z))
                    for (y in 59..63) world.getBlockAt(x, y, z).type = Material.AIR
            repeat(3_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val first = requireNotNull(runtime.plan(city, worker))
            first.standing shouldBe onlyStanding
            runtime.rejectStanding(city, worker, first) shouldBe true
            var retryAt: Long? = null
            repeat(3_000) {
                if (retryAt == null) {
                    runtime.pulse(listOf(city))
                    runtime.plan(city, worker) shouldBe null
                    val entry = (runtime.debug(setOf(worker.id))["entries"] as List<*>).single() as Map<*, *>
                    retryAt = ((entry["forestSearch"] as? Map<*, *>)?.get("retryAtTick") as? Number)?.toLong()
                }
            }
            requireNotNull(retryAt) shouldBe Bukkit.getCurrentTick().toLong() + 100
            runtime.idleReason(city, worker) shouldBe "worksite-no-access"
            clock.performTicks(99)
            repeat(20) { runtime.pulse(listOf(city)); runtime.plan(city, worker) shouldBe null }
            clock.performTicks(1)
            repeat(20) { runtime.pulse(listOf(city)); runtime.plan(city, worker) shouldBe null }
            val reopened = onlyStanding.copy(x = onlyStanding.x + 1, y = 63)
            world.getBlockAt(reopened.x, reopened.y, reopened.z).type = Material.DIRT
            world.getBlockAt(reopened.x + 1, reopened.y, reopened.z).type = Material.DIRT
            runtime.changed(world.uid, reopened)
            repeat(3_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val retried = requireNotNull(runtime.plan(city, worker))
            retried.focus shouldBe root
            retried.standing shouldBe onlyStanding
            runtime.validate(city, retried)
            city.forestryJobs shouldBe null
            city.stock shouldBe Stock()
        }
    }

    test("an unaccepted cached care is replaced when a native mature tree appears in a later scan") {
        forestryWorksite("forestry-care-preempt") { world, runtime, city ->
            val worker = city.workers.single()
            val young = CivPosition(-15, 64, -26)
            val mature = CivPosition(10, 64, -26)
            world.getBlockAt(young.x, young.y, young.z).blockData = Material.OAK_SAPLING.createBlockData()
            repeat(2_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val care = requireNotNull(runtime.plan(city, worker))
            care.status shouldBe "watering"
            care.focus shouldBe young
            forestrySmallOak(world, mature)
            repeat(2_000) { if (runtime.plan(city, worker) == care) runtime.pulse(listOf(city)) }
            val harvest = requireNotNull(runtime.plan(city, worker))
            harvest.status shouldBe "felling-tree"
            harvest.focus shouldBe mature
            runtime.validate(city, harvest)
        }
    }

    test("supported mixed-stage giant plots receive only one free real stage and stale native growth invalidates care") {
        forestryWorksite("forestry-care-giant", "SPRUCE") { world, runtime, initial ->
            var city = initial.copy(forestrySeeded = setOf(initial.buildings.first().id.toString()))
            var worker = city.workers.single()
            val roots = listOf(CivPosition(-15, 64, -26), CivPosition(-15, 64, -25),
                CivPosition(-14, 64, -26), CivPosition(-14, 64, -25))
            roots.forEachIndexed { index, root -> world.getBlockAt(root.x, root.y, root.z).blockData =
                org.bukkit.Bukkit.createBlockData(CivsForestry.saplingState("SPRUCE_SAPLING", if (index == 0) 0 else 1)) }
            runtime.pulse(listOf(city))
            runtime.plan(city, worker) shouldBe null
            repeat(2_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val care = requireNotNull(runtime.plan(city, worker))
            care.status shouldBe "watering"
            care.durationMillis shouldBe 4_000L
            care.changes.single().position shouldBe roots.first()
            care.inputs shouldBe emptyMap()
            care.outputs shouldBe emptyMap()
            care.equipmentAfter shouldBe null
            runtime.plan(city.copy(stock = Stock()), worker) shouldBe care
            runtime.validate(city, care)
            for (changed in listOf(roots.last(), roots.last().copy(y = 63))) {
                val block = world.getBlockAt(changed.x, changed.y, changed.z)
                val previous = block.blockData
                block.type = Material.AIR
                shouldThrow<CivsRejection> { runtime.validate(city, care) }.reason shouldBe "stale-worksite"
                block.blockData = previous
            }
            val neighbour = world.getBlockAt(roots.last().x, roots.last().y, roots.last().z)
            neighbour.type = Material.OAK_SAPLING
            shouldThrow<CivsRejection> { runtime.validate(city, care) }.reason shouldBe "stale-worksite"
            neighbour.blockData = org.bukkit.Bukkit.createBlockData("minecraft:spruce_sapling[stage=1]")
            val target = world.getBlockAt(roots.first().x, roots.first().y, roots.first().z)
            target.blockData = org.bukkit.Bukkit.createBlockData("minecraft:spruce_sapling[stage=1]")
            shouldThrow<CivsRejection> { runtime.validate(city, care) }.reason shouldBe "stale-worksite"
            target.blockData = org.bukkit.Bukkit.createBlockData("minecraft:spruce_sapling[stage=0]")
            runtime.validate(city, care)
            city = CivsRules.finishWorldWork(city, care, 1_000)
            target.blockData = org.bukkit.Bukkit.createBlockData(care.changes.single().after)
            worker = city.workers.single()
            runtime.invalidate(worker.id)
            repeat(2_000) { runtime.pulse(listOf(city)) }
            runtime.plan(city, worker) shouldBe null
            city.stock shouldBe Stock()
            city.forestryJobs shouldBe null
            city.forestrySeeded shouldBe initial.forestrySeeded.orEmpty() + initial.buildings.first().id.toString()
        }
    }

    test("physical care needs no spare supplies and rejects unloaded locked or newly obstructed native work") {
        val locked = hashSetOf<CivPosition>()
        forestryWorksite("forestry-care-guards", autonomous = false, locked = locked) { world, runtime, city ->
            val worker = city.workers.single()
            val point = CivPosition(-15, 64, -26)
            world.getBlockAt(point.x, point.y, point.z).blockData = Material.OAK_SAPLING.createBlockData()
            repeat(2_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val care = requireNotNull(runtime.plan(city, worker))
            care.status shouldBe "watering"
            care.inputs shouldBe emptyMap()
            care.equipmentAfter shouldBe null
            runtime.validate(city, care)
            locked += point
            shouldThrow<CivsRejection> { runtime.validate(city, care) }.reason shouldBe "stale-worksite"
            locked.clear()
            world.unloadChunk(point.x shr 4, point.z shr 4)
            shouldThrow<CivsRejection> { runtime.validate(city, care) }.reason shouldBe "stale-worksite"
            world.isChunkLoaded(point.x shr 4, point.z shr 4) shouldBe false
            world.loadChunk(point.x shr 4, point.z shr 4)
            val stance = world.getBlockAt(care.standing.x, care.standing.y, care.standing.z)
            stance.type = Material.STONE
            shouldThrow<CivsRejection> { runtime.validate(city, care) }.reason shouldBe "stale-worksite"
            stance.type = Material.AIR
            runtime.validate(city, care)
        }
    }

    test("Auto2 shaped adjacent oaks thin crowded roots and replant the isolated survivor without touching foreign trunks") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val world = CivsWorksiteWorldFixture("forestry-auto2-joined", Material.DIRT).also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("ForestryAuto2Joined")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                try {
                    val mill = spatialBuilding("caledonia", "lumberjack", 1, CivPosition(0, 63, 0), 180)
                    var worker = spatialWorker(mill, "IRON_AXE").copy(items = null, inventory = null)
                    var city = spatialCity(world.uid, listOf(mill), worker)
                        .copy(style = "caledonia", inventory = null, autonomy = CivAutonomy(), stock = Stock(),
                            forestrySeeded = setOf(mill.id.toString()))
                    val roots = (-20..-18).map { CivPosition(it, 64, -2) }
                    val remainingTrunks = linkedMapOf<CivPosition, String>()
                    for (root in roots) for (y in 64..67) {
                        val point = root.copy(y = y)
                        remainingTrunks[point] = "minecraft:oak_log[axis=y]"
                        world.getBlockAt(point.x, point.y, point.z).blockData = paper.server.createBlockData(remainingTrunks.getValue(point))
                    }
                    val leaves = (-21..-17).map { CivPosition(it, 68, -2) }
                    leaves.forEach { world.getBlockAt(it.x, it.y, it.z).blockData = paper.server.createBlockData(
                        "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]") }
                    for (x in -4..4) for (z in -4..4) world.loadChunk(x, z)
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        CivsStorage(plugin) { emptyArray() }.use { storage ->
                            val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, nanoTime = { 0L })
                            runtime.pulse(listOf(city))
                            val before = runtime.debug(setOf(worker.id))
                            val entry = (before["entries"] as List<*>).single() as Map<*, *>
                            (entry["forestSearch"] != null) shouldBe true
                            runtime.debug(setOf(worker.id)) shouldBe before
                            repeat(3) { cycle ->
                                repeat(2_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
                                val work = requireNotNull(runtime.plan(city, worker))
                                work.status shouldBe "felling-tree"
                                work.outputs shouldBe emptyMap()
                                work.inputs shouldBe if (cycle < 2) emptyMap() else mapOf("OAK_SAPLING" to 1L)
                                work.forestry!!.job.thinning shouldBe (cycle < 2)
                                val root = requireNotNull(work.forestry).job.root
                                work.changes.filter { CivsForestry.material(it.before) == "OAK_LOG" }.map { it.position }.toSet() shouldBe
                                    (64..67).mapTo(hashSetOf()) { root.copy(y = it) }
                                runtime.validate(city, work)
                                val receipt = forestryReceipt(city, work, 1_000L + cycle * 2_000L)
                                receipt.validated()
                                storage.replaceCities(listOf(city))
                                storage.reserve(city, receipt.id)
                                storage.seal(receipt)
                                storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
                                storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
                                storage.unlock(receipt.id)
                                city = receipt.after
                                city.stock.wood shouldBe (cycle + 1) * 4L - if (cycle == 2) 1L else 0L
                                city.forestryJobs shouldBe null
                                world.getBlockAt(root.x, root.y, root.z).blockData.asString shouldBe
                                    if (cycle < 2) "minecraft:air" else "minecraft:oak_sapling[stage=0]"
                                remainingTrunks.keys.removeAll(work.changes.map { it.position }.toSet())
                                remainingTrunks.forEach { (point, state) -> world.getBlockAt(point.x, point.y, point.z).blockData.asString shouldBe state }
                                // Shared foliage remains native until the last neighbouring trunk has also been harvested.
                                if (cycle < 2) leaves.forEach { world.getBlockAt(it.x, it.y, it.z).type shouldBe Material.OAK_LEAVES }
                                worker = city.workers.single()
                                runtime.invalidate(worker.id)
                            }
                            repeat(2_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
                            val planting = requireNotNull(runtime.plan(city, worker))
                            planting.status shouldBe "planting-tree"
                            planting.inputs shouldBe mapOf("OAK_SAPLING" to 1L)
                            (planting.focus !in roots) shouldBe true
                            runtime.validate(city, planting)
                            CivsRules.finishWorldWork(city, planting, 8_000).stock shouldBe city.stock.copy(wood = city.stock.wood - 1)
                        }
                    }
                } finally { tasks.close() }
            }
        }
    }

    for (planting in listOf(false, true)) test("an exhausted unaccepted ${if (planting) "planting plot" else "tree stance"} yields to later work") {
        forestryWorksite("forestry-inaccessible-$planting") { world, runtime, city ->
            val worker = city.workers.single()
            val blockedRoot = CivPosition(-15, 64, -26)
            for (x in blockedRoot.x - 3..blockedRoot.x + 3) for (z in blockedRoot.z - 3..blockedRoot.z + 3)
                if (x != blockedRoot.x || z != blockedRoot.z) for (y in 59..63) world.getBlockAt(x, y, z).type = Material.AIR
            val laterRoot = CivPosition(10, 64, -26)
            if (!planting) {
                forestrySmallOak(world, blockedRoot)
                forestrySmallOak(world, laterRoot)
            }
            repeat(3_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val work = requireNotNull(runtime.plan(city, worker))
            work.status shouldBe if (planting) "planting-tree" else "felling-tree"
            (work.focus != blockedRoot) shouldBe true
            if (!planting) work.focus shouldBe laterRoot
            runtime.validate(city, work)
            if (!planting) {
                val blockedCells = (64..66).associate { blockedRoot.copy(y = it) to "minecraft:oak_log[axis=y]" } + mapOf(
                    blockedRoot.copy(y = 63) to "minecraft:dirt", blockedRoot.copy(y = 67) to
                        "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]")
                val scan = CivsForestry.Scan(blockedRoot, "OAK_LOG")
                while (!scan.complete && !scan.failed) scan.advance(13) { blockedCells[it] ?: "minecraft:air" }
                val paid = requireNotNull(scan.job(listOf(blockedRoot))).copy(next = 1)
                val committed = city.copy(forestryJobs = mapOf(work.buildingId.toString() to paid))
                runtime.invalidate(worker.id)
                var blocked = false
                repeat(100) {
                    runtime.pulse(listOf(committed))
                    if (runtime.idleReason(committed, worker) == "worksite-no-access") blocked = true
                }
                runtime.plan(committed, worker) shouldBe null
                committed.forestryJobs?.get(work.buildingId.toString()) shouldBe paid
                blocked shouldBe true
            }
        }
    }

    test("a physical forester without replacement saplings skips an unaccepted tree and reaches free care") {
        forestryWorksite("forestry-no-seed-care", autonomous = false, nanoTime = { 0L }) { world, runtime, city ->
            val root = CivPosition(-15, 64, -26)
            val care = CivPosition(10, 64, -26)
            forestrySmallOak(world, root)
            world.getBlockAt(care.x, care.y, care.z).type = Material.OAK_SAPLING
            val worker = city.workers.single()
            runtime.residentFeet = { Location(world, root.x - 1.5, 64.0, root.z + .5) }
            repeat(3_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val work = requireNotNull(runtime.plan(city, worker))
            work.status shouldBe "watering"
            work.focus shouldBe care
            work.inputs shouldBe emptyMap()
            world.getBlockAt(root.x, root.y, root.z).type shouldBe Material.OAK_LOG
            runtime.validate(city, work)
        }
    }

    test("lost unreserved saplings do not pin an exhausted unpaid tree before free care") {
        val supplies = mutableMapOf("OAK_SAPLING" to 1L)
        forestryWorksite("forestry-exhausted-supply-loss", autonomous = false, supplies = { supplies },
            nanoTime = { 0L }) { world, runtime, city ->
            val worker = city.workers.single()
            val root = CivPosition(-15, 64, -26)
            val care = CivPosition(10, 64, -26)
            val onlyStanding = root.copy(x = root.x + 1)
            forestrySmallOak(world, root)
            world.getBlockAt(care.x, care.y, care.z).type = Material.OAK_SAPLING
            // Preserve one locally valid stance whose incoming route was rejected.
            for (x in root.x - 3..root.x + 3) for (z in root.z - 3..root.z + 3)
                if ((x != root.x || z != root.z) && (x != onlyStanding.x || z != onlyStanding.z))
                    for (y in 59..63) world.getBlockAt(x, y, z).type = Material.AIR
            fun entry(): Map<*, *> =
                (runtime.debug(setOf(worker.id))["entries"] as List<*>).single() as Map<*, *>
            fun select(): CivWorldWork {
                repeat(3_000) {
                    runtime.pulse(listOf(city))
                    runtime.plan(city, worker)?.let { return it }
                }
                error("No forestry candidate: ${runtime.debug(setOf(worker.id))}")
            }
            val first = select()
            first.focus shouldBe root
            first.standing shouldBe onlyStanding
            first.inputs shouldBe mapOf("OAK_SAPLING" to 1L)
            runtime.validate(city, first)
            runtime.rejectStanding(city, worker, first) shouldBe true
            var retryAt: Long? = null
            repeat(3_000) {
                if (retryAt == null) {
                    runtime.pulse(listOf(city))
                    runtime.plan(city, worker) shouldBe null
                    retryAt = ((entry()["failedStances"] as? Map<*, *>)?.get("retryAtTick") as? Number)?.toLong()
                }
            }
            requireNotNull(retryAt) shouldBe Bukkit.getCurrentTick().toLong() + 100
            supplies.clear()
            runtime.pulse(listOf(city))
            (entry()["deferredForestApproaches"] as Map<*, *>)["total"] shouldBe 1
            entry()["pendingApproach"] shouldBe null
            (runtime.idleReason(city, worker) != "waiting-saplings") shouldBe true
            // Do not reopen the route or wait out the failed root's retry interval.
            (Bukkit.getCurrentTick().toLong() < requireNotNull(retryAt)) shouldBe true
            val work = select()
            work.status shouldBe "watering"
            work.focus shouldBe care
            work.inputs shouldBe emptyMap()
            runtime.validate(city, work)
            supplies shouldBe emptyMap()
            city.forestryJobs shouldBe null
            city.stock shouldBe Stock()
            worker.completedCycles shouldBe 0L
            for (cell in first.changes)
                world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.before
        }
    }

    for (protectionDenied in listOf(false, true)) test("a complete unpaid tree capture yields after ${if (protectionDenied) "protection denial" else "a changed block"} during stance search") {
        val locked = hashSetOf<CivPosition>()
        forestryWorksite("forestry-stale-capture-$protectionDenied", locked = locked) { world, runtime, city ->
            val worker = city.workers.single()
            val firstRoot = CivPosition(-15, 64, -26)
            val laterRoot = CivPosition(10, 64, -26)
            forestrySmallOak(world, firstRoot)
            forestrySmallOak(world, laterRoot)
            // Leave no supported stance initially so capture completes before its bounded stance search.
            for (x in firstRoot.x - 2..firstRoot.x + 2) for (z in firstRoot.z - 2..firstRoot.z + 2)
                if (x != firstRoot.x || z != firstRoot.z) for (y in 61..63) world.getBlockAt(x, y, z).type = Material.AIR
            var delayed = false
            repeat(2_000) {
                if (!delayed) {
                    runtime.pulse(listOf(city))
                    val entry = (runtime.debug(setOf(worker.id))["entries"] as List<*>).single() as Map<*, *>
                    val scan = entry["forestScan"] as? Map<*, *>
                    delayed = scan?.get("phase") == "complete" && entry["candidateSearch"] != null
                }
            }
            delayed shouldBe true
            runtime.plan(city, worker) shouldBe null
            val changed = firstRoot.copy(y = 67)
            if (protectionDenied) locked += changed else world.getBlockAt(changed.x, changed.y, changed.z).type = Material.STONE
            // A lower stance is still ahead of the search cursor; the actual batch must now observe the stale capture.
            for (x in firstRoot.x - 2..firstRoot.x + 2) for (z in firstRoot.z - 2..firstRoot.z + 2)
                if (x != firstRoot.x || z != firstRoot.z) world.getBlockAt(x, 61, z).type = Material.STONE
            val expectedStatus = if (protectionDenied) "worksite-protected" else "worksite-obstacle"
            var rejected = false
            repeat(3_000) {
                if (runtime.plan(city, worker) == null) {
                    runtime.pulse(listOf(city))
                    if (runtime.idleReason(city, worker) == expectedStatus) rejected = true
                }
            }
            rejected shouldBe true
            val work = requireNotNull(runtime.plan(city, worker))
            work.status shouldBe "felling-tree"
            work.focus shouldBe laterRoot
            work.changes.none { it.position.x == firstRoot.x && it.position.z == firstRoot.z } shouldBe true
            runtime.validate(city, work)
            city.stock.wood shouldBe 0L
            worker.completedCycles shouldBe 0L
            city.forestryJobs shouldBe null
            val after = CivsRules.finishWorldWork(city, work, 1_000)
            after.stock.wood shouldBe 3L
            after.workers.single().completedCycles shouldBe 1L
            for (y in 64..66) world.getBlockAt(firstRoot.x, y, firstRoot.z).type shouldBe Material.OAK_LOG
            world.getBlockAt(changed.x, changed.y, changed.z).type shouldBe if (protectionDenied) Material.OAK_LEAVES else Material.STONE
        }
    }

    test("detached capture crosses the old height and 128-log ceilings follows downward branches and yields by its read allowance") {
        val (scan, cells, roots) = forestryCapture()
        var pulses = 0
        while (!scan.complete && !scan.failed && pulses++ < 10_000) {
            var reads = 0
            val used = scan.advance(7) { point -> reads++; cells[point] ?: "minecraft:air" }
            (used <= 7) shouldBe true
            (reads <= used) shouldBe true
        }
        scan.failed shouldBe false
        (pulses > 1) shouldBe true
        val job = requireNotNull(scan.job(roots))
        (job.cells.count { CivsForestry.material(job.state(it)) == job.timber } > 128) shouldBe true
        (CivPosition(5, 94, 0) in job.cells.map(job::position)) shouldBe true
        job.cells.takeLast(4).map(job::position).toSet() shouldBe roots.toSet()
        CivsForestry.valid(forestryCity(), forestryCity().buildings.last().id.toString(), job) shouldBe true
    }

    test("unavailable timber stays rejected while a joined rooted trunk keeps its independent neighbour") {
        val (_, captured, roots) = forestryCapture()
        val missing = CivsForestry.Scan(roots.first(), "SPRUCE_LOG", roots)
        while (!missing.complete && !missing.failed) missing.advance(32) { point ->
            if (point.y == 90) null else captured[point] ?: "minecraft:air"
        }
        missing.job(roots) shouldBe null
        missing.failureReason shouldBe "tree-trunk-unavailable"
        val joined = captured.toMutableMap()
        for (y in 64..98) joined[CivPosition(5, y, 5)] = "minecraft:spruce_log[axis=y]"
        joined[CivPosition(5, 63, 5)] = "minecraft:dirt"
        val connected = CivsForestry.Scan(roots.first(), "SPRUCE_LOG", roots)
        while (!connected.complete && !connected.failed) connected.advance(64) { joined[it] ?: "minecraft:air" }
        val separated = requireNotNull(connected.job(roots))
        separated.roots shouldBe roots
        separated.cells.all { CivsForestry.material(separated.state(it)) == "SPRUCE_LOG" } shouldBe true
        separated.cells.none { separated.position(it).let { p -> p.x == 5 && p.z == 5 } } shouldBe true
        connected.retainedCrownReason shouldBe "shared-crown"
        val sapling = CivsForestry.Scan(roots.first(), "SPRUCE_LOG", roots)
        repeat(8) {
            if (!sapling.complete && !sapling.failed) sapling.advance(8) { "minecraft:spruce_sapling[stage=1]" }
        }
        sapling.failed shouldBe true
        sapling.job(roots) shouldBe null
        sapling.failureReason shouldBe "tree-root-changed"
    }

    test("adjacent grounded oaks keep whole vertical trunks and leave shared connecting wood and foliage native") {
        val root = CivPosition(0, 64, 0)
        val cells = linkedMapOf<CivPosition, String>()
        for (x in listOf(0, 2)) {
            cells[CivPosition(x, 63, 0)] = "minecraft:dirt"
            for (y in 64..68) cells[CivPosition(x, y, 0)] = "minecraft:oak_log[axis=y]"
        }
        val bridge = CivPosition(1, 65, 0)
        cells[bridge] = "minecraft:oak_log[axis=x]"
        for (x in -1..3) cells[CivPosition(x, 69, 0)] = "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]"
        val scan = CivsForestry.Scan(root, "OAK_LOG")
        var ownershipPulses = 0
        while (!scan.complete && !scan.failed) {
            if (scan.phase == "ownership") ownershipPulses++
            var reads = 0
            val used = scan.advance(7) { point -> reads++; cells[point] ?: "minecraft:air" }
            (reads <= used) shouldBe true
            (reads <= 7) shouldBe true
        }
        val job = requireNotNull(scan.job(listOf(root)))
        job.cells.map(job::position).toSet() shouldBe (64..68).mapTo(hashSetOf()) { root.copy(y = it) }
        (bridge in job.cells.map(job::position)) shouldBe false
        scan.groundedRoots shouldBe 2
        (ownershipPulses > 1) shouldBe true
        scan.retainedCrownReason shouldBe "shared-trunks"
        scan.failureReason shouldBe null
    }

    for (otherRootX in listOf(6, 24)) test("a leaf-connected oak at distance $otherRootX cannot expand or invalidate the selected trunk") {
        val root = CivPosition(0, 64, 0)
        val cells = linkedMapOf<CivPosition, String>()
        for (x in listOf(0, otherRootX)) {
            cells[CivPosition(x, 63, 0)] = "minecraft:dirt"
            for (y in 64..66) cells[CivPosition(x, y, 0)] = "minecraft:oak_log[axis=y]"
        }
        for (x in 0..otherRootX) cells[CivPosition(x, 67, 0)] =
            "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]"
        val scan = CivsForestry.Scan(root, "OAK_LOG")
        while (!scan.complete && !scan.failed) scan.advance(5) { cells[it] ?: "minecraft:air" }
        val job = requireNotNull(scan.job(listOf(root)))
        job.cells.map(job::position).toSet() shouldBe (64..66).mapTo(hashSetOf()) { root.copy(y = it) }
        scan.retainedCrownReason shouldBe if (otherRootX == 6) "shared-crown" else "crown-bounds"
        scan.failureReason shouldBe null
    }

    test("a large connected crown stays native while excessive or unavailable timber never yields a partial job") {
        val root = CivPosition(0, 64, 0)
        val trunk = (64..66).associate { root.copy(y = it) to "minecraft:oak_log[axis=y]" }
        val crown = linkedMapOf<CivPosition, String>().apply {
            put(root.copy(y = 63), "minecraft:dirt")
            putAll(trunk)
            for (x in -15..15) for (y in 67..90) for (z in -15..15)
                put(CivPosition(x, y, z), "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]")
        }
        val foliage = CivsForestry.Scan(root, "OAK_LOG")
        while (!foliage.complete && !foliage.failed) foliage.advance(31) { crown[it] ?: "minecraft:air" }
        requireNotNull(foliage.job(listOf(root))).cells.size shouldBe trunk.size
        foliage.retainedCrownReason shouldBe "crown-too-large"
        for (reason in listOf("tree-trunk-bounds", "tree-trunk-too-large", "tree-trunk-unavailable")) {
            val scan = CivsForestry.Scan(root, "OAK_LOG")
            while (!scan.complete && !scan.failed) scan.advance(17) { p -> when {
                reason == "tree-trunk-unavailable" && p == root.copy(y = 65) -> null
                reason == "tree-trunk-bounds" && p.y == 64 && p.z == 0 && p.x in 0..16 -> "minecraft:oak_log[axis=x]"
                reason == "tree-trunk-too-large" && p.x in 0..14 && p.y in 64..75 && p.z in 0..14 -> "minecraft:oak_log[axis=y]"
                p.y == 63 -> "minecraft:dirt"
                else -> trunk[p] ?: "minecraft:air"
            } }
            scan.job(listOf(root)) shouldBe null
            scan.failureReason shouldBe reason
            (scan.size <= CivsForestry.MAX_CELLS) shouldBe true
        }
    }

    test("only vines facing this tree and their hanging chain join the bounded captured crown") {
        val (_, source, roots) = forestryCapture()
        val cells = source.toMutableMap()
        val attached = listOf(CivPosition(-5, 98, 0), CivPosition(-5, 97, 0), CivPosition(-5, 96, 0))
        val vine = "minecraft:vine[east=true,north=false,south=false,up=false,west=false]"
        attached.forEach { cells[it] = vine }
        val unrelated = CivPosition(-5, 98, 2)
        cells[unrelated] = "minecraft:vine[east=false,north=false,south=false,up=false,west=true]"
        val scan = CivsForestry.Scan(roots.first(), "SPRUCE_LOG", roots)
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
            val scan = CivsForestry.Scan(roots.first(), "SPRUCE_LOG", roots)
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
        val scan = CivsForestry.Scan(roots.first(), "SPRUCE_LOG", roots)
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

    test("thinning persists across batches without seed debit and old accepted jobs still replant") {
        val before = forestryCity().copy(stock = Stock(4))
        val original = forestryTestJob()
        val paid = forestryReceipt(before, forestryTestWork(before, original), 1_000)
        val legacyJson = Gson().toJson(paid).replace(",\"thinning\":false", "")
        val restored = Gson().fromJson(legacyJson, CivInventoryTransaction::class.java).validated()
        restored shouldBe paid
        restored.after.forestryJobs!!.values.single().thinning shouldBe false
        val changed = forestryTestWork(restored.after, restored.after.forestryJobs!!.values.single().copy(thinning = true))
        shouldThrow<CivsRejection> { CivsRules.finishWorldWork(restored.after, changed, 2_000) }.reason shouldBe "stale-worksite"

        var city = before.copy(stock = Stock())
        var job: CivForestryJob? = original.copy(thinning = true)
        var final: CivWorldWork? = null
        while (job != null) {
            val work = forestryTestWork(city, job)
            work.inputs shouldBe emptyMap()
            work.changes.filter { it.position in job!!.roots }.forEach { it.after shouldBe "minecraft:air" }
            val receipt = forestryReceipt(city, work, 3_000 + city.revision)
            receipt.validated()
            city = CivsRules.validate(Gson().fromJson(Gson().toJson(receipt.after), Settlement::class.java))
            job = city.forestryJobs?.values?.single()
            city.stock.wood shouldBe if (job == null) 8L else 0L
            if (job == null) final = work else job.thinning shouldBe true
        }
        shouldThrow<CivsRejection> { CivsRules.finishWorldWork(city, requireNotNull(final), 9_000) }.reason shouldBe "stale-delivery"
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

    test("planting keeps road clearance while leaves may grow above roofs and roads") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("forestry-growth-guard")
            val city = forestryCity().copy(worldId = world.uid)
            val roots = listOf(CivPosition(0, 64, 0), CivPosition(0, 64, 1), CivPosition(1, 64, 0), CivPosition(1, 64, 1))
            CivsForestry.plantingClear(city, roots, CivsForestry.paths(city)) shouldBe true
            CivsForestry.plantingClear(city, roots, CivsForestry.paths(city, setOf(7 to 0))) shouldBe true
            CivsForestry.plantingClear(city, roots, CivsForestry.paths(city, setOf(4 to 0))) shouldBe false
            val house = city.buildings.last().copy(id = UUID(0, 5), type = BuildingType.HOUSE, production = null,
                anchor = CivPosition(6, 64, 0), bounds = CivBox(CivPosition(6, 63, 0), CivPosition(8, 70, 2)),
                entrance = CivPosition(7, 64, 3))
            val housed = city.copy(buildings = city.buildings + house)
            CivsForestry.plantingClear(housed, roots, CivsForestry.paths(housed)) shouldBe true
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
                event.isCancelled shouldBe false
                event.blocks.size shouldBe 1 // Preserve the complete proposed growth event.
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
                    val runtime = CivsWorksites(plugin, access, { _, _ -> mapOf("${timber}_SAPLING" to 4L) }, { _, _ -> false }, nanoTime = { 0L })
                    repeat(2_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
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
                    val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, nanoTime = { 0L })
                    repeat(2_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
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

    for (mature in listOf(false, true)) test("a selected birch forester handles an oak ${if (mature) "tree" else "sapling"} below leaves") {
        forestryWorksite("forestry-species-spacing-$mature", timber = "BIRCH", nanoTime = { 0L }) { world, runtime, city ->
            val oak = CivPosition(-5, 64, -26)
            if (mature) forestrySmallOak(world, oak)
            else {
                world.getBlockAt(oak.x, oak.y, oak.z).type = Material.OAK_SAPLING
                world.getBlockAt(oak.x, oak.y + 3, oak.z).blockData = Bukkit.createBlockData(
                    "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]")
            }
            val worker = city.workers.single()
            repeat(2_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val work = requireNotNull(runtime.plan(city, worker))
            if (mature) {
                work.status shouldBe "felling-tree"
                work.focus shouldBe oak
            } else {
                work.status shouldBe "planting-tree"
                val planted = work.changes.single().position
                (kotlin.math.abs(planted.x - oak.x) >= 4 || kotlin.math.abs(planted.z - oak.z) >= 4) shouldBe true
            }
            runtime.validate(city, work)
            world.getBlockAt(oak.x, oak.y, oak.z).type shouldBe if (mature) Material.OAK_LOG else Material.OAK_SAPLING
        }
    }

    test("an isolated overhanging leaf does not require the whole future planting crown to be empty") {
        forestryWorksite("forestry-overhanging-leaf", timber = "BIRCH", nanoTime = { 0L }) { world, runtime, city ->
            val root = CivPosition(-15, 64, -26)
            val mill = requireNotNull(city.building(city.workers.single().assignment!!))
            val bounds = CivsWorksiteLayouts.site(mill)!!.getAsJsonObject("forester").getAsJsonObject("searchBounds")
            val min = CivsWorksiteLayouts.vector(bounds["min"])
            val max = CivsWorksiteLayouts.vector(bounds["max"])
            for (x in min.x..max.x) for (z in min.z..max.z) {
                val point = CivsWorksiteLayouts.point(mill, CivPosition(x, 1, z))
                if (point != root) world.getBlockAt(point.x, 63, point.z).type = Material.STONE
            }
            world.getBlockAt(root.x + 2, root.y + 2, root.z).blockData = Bukkit.createBlockData(
                "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]")
            val worker = city.workers.single()
            repeat(2_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val work = requireNotNull(runtime.plan(city, worker))
            work.status shouldBe "planting-tree"
            work.focus shouldBe root
            runtime.validate(city, work)
            world.getBlockAt(root.x + 2, root.y + 2, root.z).type shouldBe Material.OAK_LEAVES
        }
    }

    test("consecutive oak planting sees non-motion-blocking saplings and keeps the next young crown separate") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = CivsWorksiteWorldFixture("forestry-young-crowns", Material.DIRT).also(paper.server::addWorld)
            val plugin = paper.createSimplePlugin("ForestryYoungCrowns")
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            try {
                val mill = spatialBuilding("nordic", "lumberjack", 1, CivPosition(0, 63, 0)).copy(production = "OAK_LOG")
                var worker = spatialWorker(mill, "IRON_AXE").copy(items = null, inventory = null)
                var city = spatialCity(world.uid, listOf(mill), worker)
                    .copy(inventory = null, autonomy = CivAutonomy(), stock = Stock(20))
                for (x in -4..4) for (z in -4..4) world.loadChunk(x, z)
                CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                    val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, nanoTime = { 0L })
                    repeat(2_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
                    val first = requireNotNull(runtime.plan(city, worker))
                    first.status shouldBe "planting-tree"
                    runtime.validate(city, first)
                    city = CivsRules.finishWorldWork(city, first, 1_000)
                    first.changes.forEach { cell -> world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
                        .blockData = paper.server.createBlockData(cell.after) }
                    val planted = first.changes.single().position
                    world.getHighestBlockYAt(planted.x, planted.z) shouldBe planted.y - 1
                    world.getHighestBlockYAt(planted.x, planted.z, HeightMap.WORLD_SURFACE) shouldBe planted.y
                    // Native care has already advanced this sapling; the spacing check concerns the next planting.
                    world.getBlockAt(planted.x, planted.y, planted.z).blockData =
                        paper.server.createBlockData("minecraft:oak_sapling[stage=1]")
                    worker = city.workers.single()
                    runtime.invalidate(worker.id)
                    repeat(1_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
                    val second = requireNotNull(runtime.plan(city, worker))
                    second.status shouldBe "planting-tree"
                    val reserved = CivsForestry.plantingBounds(second.changes.map { it.position })
                    (planted.x in reserved.min.x..reserved.max.x && planted.z in reserved.min.z..reserved.max.z) shouldBe false
                    runtime.validate(city, second)
                    world.getBlockAt(planted.x, planted.y, planted.z).type shouldBe Material.OAK_SAPLING
                }
            } finally { tasks.close() }
        }
    }
})

internal fun forestryCity(autonomous: Boolean = true): Settlement {
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
    return Triple(CivsForestry.Scan(roots.first(), "SPRUCE_LOG", roots), cells, roots)
}

/** Declared geometry isolates resident-position eligibility; it is not the live Auto2 tree. */
private enum class ForestryOriginMove { NEAR, FARTHER, AROUND }

private data class ForestryOriginRecipe(val name: String, val initiallyNear: Boolean = false, val blocked: Boolean = false,
    val ordinaryLater: Boolean = false, val deferredLater: Boolean = false, val move: ForestryOriginMove? = null,
    val expectWork: Boolean = true)

class CivsForestryRemoteRevalidationTest : FunSpec({
    for (recipe in listOf(
        ForestryOriginRecipe("a safe near resident can use its own crown without ordinary footings", initiallyNear = true),
        ForestryOriginRecipe("moving a far resident into remote reach reconsiders its deferred tree", move = ForestryOriginMove.NEAR),
        ForestryOriginRecipe("a real wall still denies a near resident the remote tree", initiallyNear = true, blocked = true, expectWork = false),
        ForestryOriginRecipe("a far deferred tree yields to another ordinarily reachable tree", ordinaryLater = true),
        ForestryOriginRecipe("a different near angle reconsiders a tree without changing the wall", initiallyNear = true, blocked = true, move = ForestryOriginMove.AROUND),
        ForestryOriginRecipe("no movement or same-cell movement keeps a far tree deferred without new rays", expectWork = false),
        ForestryOriginRecipe("moving from far to farther does not reopen the deferred tree", move = ForestryOriginMove.FARTHER, expectWork = false),
        ForestryOriginRecipe("moving near one deferred tree retains the other deferred root", deferredLater = true, move = ForestryOriginMove.NEAR),
    )) test(recipe.name) {
        val initiallyNear = recipe.initiallyNear
        val blocked = recipe.blocked
        val laterTree = recipe.ordinaryLater || recipe.deferredLater
        forestryWorksite("remote-revalidation-${recipe.hashCode()}", nanoTime = { 0L }) { world, runtime, initial ->
            val worker = initial.workers.single()
            val city = initial.copy(forestrySeeded = setOf(requireNotNull(worker.assignment).toString()))
            val root = CivPosition(-15, 64, -26)
            val later = CivPosition(10, 64, -26)
            forestrySmallOak(world, root)
            fun closeOrdinaryFootings(at: CivPosition) {
                for (x in at.x - 3..at.x + 3) for (z in at.z - 3..at.z + 3)
                    if (x != at.x || z != at.z) for (y in 59..63) world.getBlockAt(x, y, z).type = Material.AIR
            }
            closeOrdinaryFootings(root)
            val leaves = Bukkit.createBlockData("minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]")
            for (y in 64..66) for (z in root.z - 1..root.z + 1)
                world.getBlockAt(root.x - 2, y, z).blockData = leaves
            world.getBlockAt(root.x - 1, 65, root.z).blockData = leaves
            if (blocked) for (y in 64..66) for (z in root.z - 2..root.z + 2)
                world.getBlockAt(root.x - 4, y, z).type = Material.STONE
            if (laterTree) forestrySmallOak(world, later)
            if (recipe.deferredLater) closeOrdinaryFootings(later)
            val near = Location(world, root.x - 5.5, 64.0, root.z + .5)
            val far = near.clone().subtract(12.0, 0.0, 0.0)
            val farther = far.clone().subtract(3.0, 0.0, 0.0)
            val around = Location(world, root.x + .5, 64.0, root.z - 5.5)
            var feet = if (initiallyNear) near else far
            var feetReads = 0
            runtime.residentFeet = { feetReads++; feet }
            val nearPoint = CitizenPoint(near.x, near.y, near.z)
            citizenPacketSupported(world, nearPoint) shouldBe true
            citizenPacketBodyClear(world, nearPoint, false) shouldBe true
            val ordinary = buildList {
                for (dy in -4..2) for (dx in -3..3) for (dz in -3..3) {
                    if (dx == 0 && dz == 0) continue
                    val p = root.copy(x = root.x + dx, y = root.y + dy, z = root.z + dz)
                    if (citizenStandable(world, p) && citizenPacketBodyClear(world, CitizenPoint(p.x + .5, p.y.toDouble(), p.z + .5), false) &&
                        citizenCanReachBlock(world, Location(world, p.x + .5, p.y + 1.62, p.z + .5), root)) add(p)
                }
            }
            ordinary shouldBe emptyList()
            val scan = CivsForestry.Scan(root, "OAK_LOG")
            repeat(100) { if (!scan.complete && !scan.failed) scan.advance(192) { world.getBlockAt(it.x, it.y, it.z).blockData.asString } }
            val intent = forestryTestWork(city, requireNotNull(scan.job(listOf(root))).copy(seedDeferred = true)).copy(remoteWork = true)
            val nearEye = near.clone().add(0.0, 1.62, 0.0)
            citizenReachableBlockPoint(world, nearEye, root, CITIZEN_REMOTE_WORK_REACH) shouldBe null
            (citizenReachableBlockPoint(world, nearEye, root, CITIZEN_REMOTE_WORK_REACH, intent) != null) shouldBe !blocked
            citizenReachableBlockPoint(world, far.clone().add(0.0, 1.62, 0.0), root, CITIZEN_REMOTE_WORK_REACH, intent) shouldBe null
            if (recipe.move == ForestryOriginMove.AROUND) {
                citizenPacketSupported(world, CitizenPoint(around.x, around.y, around.z)) shouldBe true
                citizenPacketBodyClear(world, CitizenPoint(around.x, around.y, around.z), false) shouldBe true
                (citizenReachableBlockPoint(world, around.clone().add(0.0, 1.62, 0.0), root, CITIZEN_REMOTE_WORK_REACH, intent) != null) shouldBe true
            }
            val known = buildList {
                for (x in root.x - 6..root.x + 3) for (z in root.z - 3..root.z + 3) for (y in 59..68) add(CivPosition(x, y, z))
                for (at in listOf(near, far, farther, around)) for (dy in -1..2) add(CivPosition(at.blockX, at.blockY + dy, at.blockZ))
                if (laterTree) for (x in later.x - 3..later.x + 3) for (z in later.z - 3..later.z + 3) for (y in 59..68)
                    add(CivPosition(x, y, z))
            }.associateWith { world.getBlockAt(it.x, it.y, it.z).blockData.asString }
            fun entry() = (runtime.debug(setOf(worker.id))["entries"] as List<*>).single() as Map<*, *>
            fun deferred() = (entry()["deferredForestApproaches"] as Map<*, *>)["total"] as Int
            fun deferredObject(point: CivPosition): Any? = ((CivsWorksites::class.java.getDeclaredField("deferredForestApproaches")
                .also { it.isAccessible = true }.get(runtime) as Map<*, *>)[worker.id] as? Map<*, *>)?.get(point)
            fun pulse() {
                (Bukkit.getScheduler() as BukkitSchedulerMock).performTicks(5)
                runtime.pulse(listOf(city))
            }
            fun pumpUntil(done: () -> Boolean) {
                repeat(2_000) {
                    if (done()) return
                    pulse()
                }
            }
            runtime.pulse(listOf(city))
            pumpUntil { runtime.plan(city, worker) != null || deferred() >= if (recipe.deferredLater) 2 else 1 }
            val before = entry()["forestSearch"] as Map<*, *>
            val search = (CivsWorksites::class.java.getDeclaredField("forestSearches").also { it.isAccessible = true }
                .get(runtime) as Map<*, *>)[worker.id]
            val keptRoot = if (recipe.deferredLater) checkNotNull(deferredObject(later)) else null
            val originalRoot = deferredObject(root)
            if (recipe.move != null) {
                deferred() shouldBe if (recipe.deferredLater) 2 else 1
                runtime.plan(city, worker) shouldBe null
                feet = when (recipe.move) {
                    ForestryOriginMove.NEAR -> near
                    ForestryOriginMove.FARTHER -> farther
                    ForestryOriginMove.AROUND -> around
                } // The sole changed input; geometry, claims and city remain unchanged.
            }
            val raysBefore = world.rayTraces
            val readsBefore = feetReads
            pumpUntil { runtime.plan(city, worker) != null }
            if (!recipe.expectWork) {
                world.rayTraces shouldBe raysBefore
                (feetReads - readsBefore <= 2_000) shouldBe true
                (deferredObject(root) === originalRoot) shouldBe true
                if (!initiallyNear && recipe.move == null) {
                    // Fractional movement within the same standing cell is deliberately not a new witness.
                    feet = feet.clone().add(.1, 0.0, .1)
                    val sameCellReads = feetReads
                    repeat(20) { pulse() }
                    (feetReads - sameCellReads <= 20) shouldBe true
                    world.rayTraces shouldBe raysBefore
                    (deferredObject(root) === originalRoot) shouldBe true
                }
            }
            val after = entry()["forestSearch"] as Map<*, *>
            ((CivsWorksites::class.java.getDeclaredField("forestSearches").also { it.isAccessible = true }
                .get(runtime) as Map<*, *>)[worker.id] === search) shouldBe true
            after["columns"] shouldBe before["columns"]
            ((after["column"] as Int) >= (before["column"] as Int)) shouldBe true
            known.forEach { (point, state) -> world.getBlockAt(point.x, point.y, point.z).blockData.asString shouldBe state }
            city.stock shouldBe initial.stock
            city.workers shouldBe initial.workers
            city.forestryJobs shouldBe null
            if (!recipe.expectWork) {
                runtime.plan(city, worker) shouldBe null
                deferred() shouldBe 1
            } else {
                val work = checkNotNull(runtime.plan(city, worker)) {
                    "No work after resident input change; initiallyNear=$initiallyNear laterTree=$laterTree ${runtime.debug(setOf(worker.id))}"
                }
                work.focus shouldBe if (recipe.ordinaryLater) later else root
                work.remoteWork shouldBe !recipe.ordinaryLater
                runtime.validate(city, work)
            }
            if (recipe.deferredLater) {
                (deferredObject(later) === keptRoot) shouldBe true
                deferred() shouldBe 1
            }
            println("Remote revalidation case=${recipe.name} deferred=${deferred()} field=${after["column"]}/${after["columns"]}" +
                " work=${runtime.plan(city, worker)?.focus}")
        }
    }
})

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
        CivPlanBlock(point, job.state(code), if (!job.thinning && point in job.roots) CivsForestry.saplingState(job.sapling) else "minecraft:air")
    }, inputs = if (job.next == 0 && !job.seedDeferred && !job.thinning) mapOf(job.sapling to job.roots.size.toLong()) else emptyMap(),
        outputs = if (city.autonomy == null) drops else emptyMap(),
        status = "felling-tree", focus = job.root, forestry = progress)
}

internal fun forestryReceipt(city: Settlement, work: CivWorldWork, at: Long): CivInventoryTransaction {
    val after = CivsRules.finishWorldWork(city, work, at)
    return CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision, after, emptyList(), worldChanges = work.changes,
        forestry = CivForestryReceipt(city.workers.single(), city.stock, city.forestryJobs?.get(work.buildingId.toString()),
            city.forestryJobs == null, work, at, city.forestrySeeded))
}

internal fun forestryPlantWork(city: Settlement): CivWorldWork {
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

private fun forestryCareWork(city: Settlement): CivWorldWork {
    val building = city.buildings.last { it.type == BuildingType.FORESTER }
    val worker = city.workers.single { it.assignment == building.id }
    val sapling = requireNotNull(CivsProduction.material(building)).removeSuffix("_LOG") + "_SAPLING"
    val point = CivPosition(0, 64, 0)
    return CivWorldWork(worker.id, worker.completedCycles, building.id, point.copy(x = -1),
        listOf(CivPlanBlock(point, CivsForestry.saplingState(sapling), CivsForestry.saplingState(sapling, 1))),
        durationMillis = 4_000, status = "watering", focus = point)
}

internal fun forestryWorksite(name: String, timber: String = "OAK", autonomous: Boolean = true,
    locked: Set<CivPosition> = emptySet(), supplies: () -> Map<String, Long> = { emptyMap() },
    paused: () -> Boolean = { false },
    nanoTime: () -> Long = { 0L },
    settings: CivsWorksiteSettings = CivsWorksiteSettings(),
    retainedPaths: Set<Pair<Int, Int>> = emptySet(),
    strict: Boolean = false,
    collisionForData: ((org.bukkit.block.data.BlockData) -> List<org.bukkit.util.BoundingBox>?)? = null,
    scenario: (CivsWorksiteWorldFixture, CivsWorksites, Settlement) -> Unit) {
    MockBukkitTestRuntime.open().use { paper ->
        val world = CivsWorksiteWorldFixture(name, Material.DIRT, collisionForData).also(paper.server::addWorld)
        val plugin = paper.createSimplePlugin("ForestryCare")
        val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
        try {
            val mill = spatialBuilding("nordic", "lumberjack", 1, CivPosition(0, 63, 0)).copy(production = "${timber}_LOG")
            val worker = spatialWorker(mill, "IRON_AXE").let { if (autonomous) it.copy(items = null, inventory = null) else it }
            val city = spatialCity(world.uid, listOf(mill), worker).let {
                if (autonomous) it.copy(inventory = null, autonomy = CivAutonomy(), stock = Stock()) else it
            }
            for (x in -4..4) for (z in -4..4) world.loadChunk(x, z)
            CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                val runtime = CivsWorksites(plugin, access, { _, _ -> supplies() }, { _, point -> point in locked }, { paused() },
                    forestryPaths = { retainedPaths }, nanoTime = nanoTime, settings = settings)
                if (strict) failOnUnsupportedMockBukkitOperation { scenario(world, runtime, city) }
                else scenario(world, runtime, city)
            }
        } finally { tasks.close() }
    }
}

/** Detached native-block fixture only; it does not run Minecraft's tree generator. */
internal fun forestrySmallOak(world: CivsWorksiteWorldFixture, root: CivPosition) {
    for (y in root.y..root.y + 2) world.getBlockAt(root.x, y, root.z).type = Material.OAK_LOG
    world.getBlockAt(root.x, root.y + 3, root.z).blockData = org.bukkit.Bukkit.createBlockData(
        "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]")
}
