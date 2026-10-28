package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock
import ru.arc.civs.scenarios.CapturedWorksiteWorld
import ru.arc.civs.scenarios.RecordedNativeMap
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID

class CivsForestrySearchLatencyTest : FunSpec({
    test("many crowded planting candidates yield to a proven free plot without minutes of repeated native scans") {
        var nanos = 0L
        val capture = RecordedNativeMap.fromResourceRle("/scenarios/forestry-planting-pressure.json",
            "7438534d0d8fb1ba2e2b9fa8d5ab6be811174a069c21f09a5548d3e0a978279c")
        plantingDelayFixture("forestry-crowded-planting", capture = capture,
            nanoTime = { nanos.also { nanos += 10_000L } }) { world, runtime, city ->
            val worker = city.workers.single()
            val mill = requireNotNull(city.building(requireNotNull(worker.assignment)))
            val columns = plantingDelayColumns(mill)
            val neighbours = linkedMapOf(
                CivPosition(-8, 64, -16) to "OAK_SAPLING",
                CivPosition(8, 64, -16) to "SPRUCE_SAPLING",
                CivPosition(-8, 64, 0) to "OAK_SAPLING",
                CivPosition(8, 64, 0) to "BIRCH_SAPLING",
            )
            neighbours.forEach { (p, sapling) ->
                world.getBlockAt(p.x, p.y, p.z).blockData.asString shouldBe CivsForestry.saplingState(sapling, 1)
            }
            val available = columns.map { it.copy(y = 64) }.filter { p ->
                CivsForestry.crownClear(city, listOf(p), "OAK_LOG", CivsForestry.paths(city)) && neighbours.none { (tree, sapling) ->
                    val radius = 7 + CivsForestry.crownRadius(sapling.removeSuffix("_SAPLING") + "_LOG")
                    kotlin.math.abs(p.x - tree.x) <= radius && kotlin.math.abs(p.z - tree.z) <= radius
                }
            }
            check(available.size > 100) { "The fixture must contain many genuinely available plots: ${available.size}" }
            val original = neighbours.mapValues { (p, _) -> world.getBlockAt(p.x, p.y, p.z).blockData.asString }
            var coldPulses = 0
            while (coldPulses < 256 && plantingDelayEntry(runtime, worker)["forestSearch"].let { it !is Map<*, *> || it["column"] != it["columns"] }) {
                plantingDelayPulse(runtime, city)
                coldPulses++
            }
            val covered = plantingDelayEntry(runtime, worker)["forestSearch"] as Map<*, *>
            covered["column"] shouldBe columns.size
            var warmPulses = 0
            while (warmPulses < 24 && runtime.plan(city, worker) == null) {
                plantingDelayPulse(runtime, city)
                warmPulses++
            }
            val observed = plantingDelayEntry(runtime, worker)
            val search = observed["forestSearch"] as Map<*, *>
            val work = requireNotNull(runtime.plan(city, worker)) {
                "No planting with ${available.size} available plots after $coldPulses discovery + $warmPulses warm default pulses: $observed"
            }
            work.status shouldBe "planting-tree"
            val rejections = observed["plantingRejections"] as Map<*, *>
            check(work.focus in available)
            check((search["unplantable"] as Int) >= 3) { "The fixture must exercise repeated spacing refusals: $search" }
            check(((rejections["reasons"] as Map<*, *>)["native-neighbour"] as Long) >= 3)
            runtime.validate(city, work)
            val after = plantingDelayCommit(city, runtime, work)
            after.stock.wood shouldBe city.stock.wood - 1
            original.forEach { (p, state) -> world.getBlockAt(p.x, p.y, p.z).blockData.asString shouldBe state }
            println("Planting latency: available=${available.size} cold=$coldPulses warm=$warmPulses refused=${search["unplantable"]}")
        }
    }

    test("bounded owner diagnostics count overlapping crown road and building refusals independently") {
        var nanos = 0L
        val paths = hashSetOf<Pair<Int, Int>>()
        plantingDelayFixture("forestry-refusal-reasons", retainedPaths = paths,
            nanoTime = { nanos.also { nanos += 10_000L } }) { world, runtime, city ->
            val worker = city.workers.single()
            val mill = requireNotNull(city.building(requireNotNull(worker.assignment)))
            val protected = civsProtectedBuildings(city)
            val root = plantingDelayColumns(mill).map { it.copy(y = 64) }.first { p ->
                !CivsForestry.blockedColumn(city, p.x, p.z, CivsForestry.paths(city)) &&
                    CivsForestry.crownBounds(listOf(p), "OAK_LOG").let { crown -> protected.any {
                        crown.min.x <= it.bounds.max.x && crown.max.x >= it.bounds.min.x &&
                            crown.min.z <= it.bounds.max.z && crown.max.z >= it.bounds.min.z
                    } }
            }
            plantingDelaySparseField(world, mill, root)
            paths += root.x + 1 to root.z
            repeat(256) { plantingDelayPulse(runtime, city) }
            runtime.plan(city, worker) shouldBe null
            val report = plantingDelayEntry(runtime, worker)["plantingRejections"] as Map<*, *>
            report["scope"] shouldBe "retained-zone-search"
            report["total"] shouldBe 1L
            val reasons = report["reasons"] as Map<*, *>
            reasons["crown-road"] shouldBe 1L
            reasons["crown-building"] shouldBe 1L
            check(reasons.size <= 4)
            val last = report["last"] as Map<*, *>
            last["candidate"] shouldBe "${root.x},${root.y},${root.z}"
            (last["reasons"] as List<*>).toSet() shouldBe setOf("crown-road", "crown-building")
            world.getBlockAt(root.x, root.y, root.z).type shouldBe Material.SHORT_GRASS
            city.stock.wood shouldBe 64L
        }
    }

    test("a removed stage one hint is revalidated live before it can refuse the only free planting plot") {
        var nanos = 0L
        var suspended = false
        var hintBoundary: () -> Boolean = { false }
        plantingDelayFixture("forestry-stale-hint", nanoTime = {
            // Expire one ordinary 2ms slice at the hint boundary; resume its real cursor next pulse.
            if (!suspended && hintBoundary()) { nanos += 2_000_000L; suspended = true }
            nanos.also { nanos += 10_000L }
        }) { world, runtime, city ->
            val worker = city.workers.single()
            val mill = requireNotNull(city.building(requireNotNull(worker.assignment)))
            val root = CivPosition(-15, 64, -26)
            val oldTree = root.copy(x = -8)
            plantingDelaySparseField(world, mill, root)
            world.getBlockAt(oldTree.x, 63, oldTree.z).type = Material.DIRT
            world.getBlockAt(oldTree.x, 64, oldTree.z).blockData = Bukkit.createBlockData("minecraft:oak_sapling[stage=1]")
            hintBoundary = {
                val pending = plantingDelayEntry(runtime, worker)["forestPlant"] as? Map<*, *>
                pending != null && (pending["hintColumns"] as Int) > 0 && pending["checkedHints"] == 0
            }
            repeat(256) { if (!suspended) plantingDelayPulse(runtime, city) }
            check(suspended) { "The discovery hint was not captured before its native revalidation" }
            val before = plantingDelayEntry(runtime, worker)
            ((before["forestSearch"] as Map<*, *>)["observedTreeColumns"] as Int) shouldBe 1
            // Simulate a missed native change event: the stored hint itself must never be rejection authority.
            world.getBlockAt(oldTree.x, 64, oldTree.z).type = Material.AIR
            repeat(24) { if (runtime.plan(city, worker) == null) plantingDelayPulse(runtime, city) }
            val work = requireNotNull(runtime.plan(city, worker)) { "A stale hint pinned an available plot: ${plantingDelayEntry(runtime, worker)}" }
            work.focus shouldBe root
            work.status shouldBe "planting-tree"
            ((plantingDelayEntry(runtime, worker)["forestSearch"] as Map<*, *>)["unplantable"] as Int) shouldBe 0
            plantingDelayCommit(city, runtime, work).stock.wood shouldBe city.stock.wood - 1
        }
    }

    test("a native tree change resets partial spacing progress and still denies its newly crowded plot") {
        var nanos = 0L
        plantingDelayFixture("forestry-changed-spacing", nanoTime = { nanos.also { nanos += 10_000L } }) { world, runtime, city ->
            val worker = city.workers.single()
            val mill = requireNotNull(city.building(requireNotNull(worker.assignment)))
            val root = CivPosition(-15, 64, -26)
            val neighbour = root.copy(x = -14)
            plantingDelaySparseField(world, mill, root)
            var partial = false
            repeat(256) {
                if (!partial) {
                    plantingDelayPulse(runtime, city)
                    val pending = plantingDelayEntry(runtime, worker)["forestPlant"] as? Map<*, *>
                    partial = pending?.get("checkedColumns")?.let { it as Int > 400 } == true
                }
            }
            check(partial)
            world.getBlockAt(neighbour.x, 63, neighbour.z).type = Material.DIRT
            world.getBlockAt(neighbour.x, 64, neighbour.z).blockData = Bukkit.createBlockData("minecraft:spruce_sapling[stage=1]")
            runtime.changed(world.uid, neighbour)
            val reset = plantingDelayEntry(runtime, worker)["forestPlant"] as Map<*, *>
            reset["checkedColumns"] shouldBe 0
            reset["checkedHints"] shouldBe 0
            repeat(64) { plantingDelayPulse(runtime, city) }
            runtime.plan(city, worker) shouldBe null
            world.getBlockAt(root.x, 64, root.z).type shouldBe Material.SHORT_GRASS
            city.stock.wood shouldBe 64L
        }
    }

    for (refusal in listOf("claim", "unloaded-chunk")) test("$refusal remains authoritative and local release restores the real planting") {
        var nanos = 0L
        val locked = hashSetOf<CivPosition>()
        plantingDelayFixture("forestry-delay-$refusal", locked = locked, nanoTime = { nanos.also { nanos += 10_000L } }) { world, runtime, city ->
            val worker = city.workers.single()
            val mill = requireNotNull(city.building(requireNotNull(worker.assignment)))
            val root = CivPosition(-15, 64, -26)
            plantingDelaySparseField(world, mill, root)
            if (refusal == "claim") locked += root
            else world.unloadChunk(root.x shr 4, root.z shr 4)
            repeat(256) { plantingDelayPulse(runtime, city) }
            runtime.plan(city, worker) shouldBe null
            if (refusal == "claim") {
                locked.clear()
                runtime.changed(world.uid, root)
            } else {
                world.isChunkLoaded(root.x shr 4, root.z shr 4) shouldBe false
                runtime.forgetChunk(world.uid, root.x shr 4, root.z shr 4)
                world.loadChunk(root.x shr 4, root.z shr 4)
            }
            repeat(256) { if (runtime.plan(city, worker) == null) plantingDelayPulse(runtime, city) }
            val work = requireNotNull(runtime.plan(city, worker)) { "Local release lost the plot: ${plantingDelayEntry(runtime, worker)}" }
            work.focus shouldBe root
            plantingDelayCommit(city, runtime, work).stock.wood shouldBe city.stock.wood - 1
        }
    }
})

private fun plantingDelaySparseField(world: CivsWorksiteWorldFixture, mill: CivBuilding, root: CivPosition) {
    plantingDelayColumns(mill).forEach { p -> world.getBlockAt(p.x, 63, p.z).type = Material.STONE }
    world.getBlockAt(root.x, 63, root.z).type = Material.DIRT
    world.getBlockAt(root.x, 64, root.z).type = Material.SHORT_GRASS
}

private fun plantingDelayEntry(runtime: CivsWorksites, worker: CivWorker): Map<*, *> =
    (runtime.debug(setOf(worker.id))["entries"] as List<*>).firstOrNull() as? Map<*, *> ?: emptyMap<Any, Any>()

private fun plantingDelayPulse(runtime: CivsWorksites, city: Settlement) {
    (Bukkit.getScheduler() as BukkitSchedulerMock).performTicks(5)
    runtime.pulse(listOf(city))
    val budget = runtime.debug()["remainingPulseBudget"] as Map<*, *>
    check(budget["forestWorkUnits"] as Int in 0..192 && budget["columns"] as Int in 0..16 && budget["rays"] as Int in 0..8)
}

private fun plantingDelayColumns(mill: CivBuilding): List<CivPosition> {
    val bounds = requireNotNull(CivsWorksiteLayouts.site(mill)).getAsJsonObject("forester").getAsJsonObject("searchBounds")
    val min = CivsWorksiteLayouts.vector(bounds["min"])
    val max = CivsWorksiteLayouts.vector(bounds["max"])
    return (min.z..max.z).flatMap { z -> (min.x..max.x).map { x -> CivsWorksiteLayouts.point(mill, CivPosition(x, 0, z)) } }
}

private fun plantingDelayCommit(city: Settlement, runtime: CivsWorksites, work: CivWorldWork): Settlement {
    runtime.validate(city, work)
    val after = CivsRules.finishWorldWork(city, work, 1_000)
    val receipt = CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision, after, emptyList(), worldChanges = work.changes,
        forestry = CivForestryReceipt(city.workers.single(), city.stock, city.forestryJobs?.get(work.buildingId.toString()),
            city.forestryJobs == null, work, 1_000, city.forestrySeeded)).validated()
    CivsStorage(requireNotNull(Bukkit.getPluginManager().getPlugin("PlantingDelay"))) { emptyArray() }.use { storage ->
        storage.replaceCities(listOf(city))
        storage.reserve(city, receipt.id)
        storage.seal(receipt)
        storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.unlock(receipt.id)
    }
    runtime.invalidate(work.workerId, work)
    return after
}

private fun plantingDelayFixture(name: String, nanoTime: () -> Long, locked: Set<CivPosition> = emptySet(),
    retainedPaths: Set<Pair<Int, Int>> = emptySet(),
    capture: RecordedNativeMap? = null,
    scenario: (CivsWorksiteWorldFixture, CivsWorksites, Settlement) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open().use { paper ->
        val world = (capture?.let { CapturedWorksiteWorld(name, it) } ?: CivsWorksiteWorldFixture(name, Material.DIRT))
            .also(paper.server::addWorld)
        val plugin = paper.createSimplePlugin("PlantingDelay")
        val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
        try {
            val mill = spatialBuilding("nordic", "lumberjack", 1, CivPosition(0, 63, 0))
                .copy(id = UUID(0, 9_101), production = "OAK_LOG")
            val worker = spatialWorker(mill, "IRON_AXE").copy(items = null, inventory = null)
            val city = spatialCity(world.uid, listOf(mill), worker).copy(inventory = null, autonomy = CivAutonomy(), stock = Stock(wood = 64))
            for (x in -4..4) for (z in -4..4) world.loadChunk(x, z)
            CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, p -> p in locked },
                    forestryPaths = { retainedPaths }, nanoTime = nanoTime)
                scenario(world, runtime, city)
            }
        } finally { tasks.close() }
    }
}
