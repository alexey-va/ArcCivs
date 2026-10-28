package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.spyk
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Location
import org.bukkit.TreeType
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.world.StructureGrowEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock
import ru.arc.civs.scenarios.CapturedWorksiteWorld
import ru.arc.civs.scenarios.RecordedNativeMap
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

class CivsForestryDensityTest : FunSpec({
    for (auto in listOf(true, false)) for (seed in listOf(7_001L, 7_002L))
        test("${if (auto) "Auto" else "physical"} forester seed $seed reaches twenty real separated sites around its house") {
            densityFixture(auto, seed) { h ->
                val initialWood = h.city.stock.wood
                val originals = densityExisting.associateWith { p -> h.world.getBlockAt(p.x, p.y, p.z).blockData.asString }
                val guards = h.capture.states.filter { (p, _) -> h.mill.bounds.contains(p.x, p.y, p.z) || (p.x to p.z) in densityRoad }
                val planted = mutableListOf<CivPosition>()
                var pulses = 0
                var actions = 0
                while (planted.size + densityExisting.size < 20 && pulses < 1_000) {
                    h.pulse(); pulses++
                    val work = h.runtime.plan(h.city, h.worker) ?: continue
                    check(work.status in setOf("planting-tree", "watering"))
                    if (work.status == "planting-tree") {
                        val roots = work.changes.filter { it.after == "minecraft:oak_sapling[stage=0]" }.map { it.position }
                        roots.size shouldBe 1
                        val root = roots.single()
                        for (other in densityExisting + planted) check(densityDistance(root, other) >= 4)
                        check(densityRoad.all { (x, z) -> max(abs(root.x - x), abs(root.z - z)) >= 4 })
                        check(!h.mill.bounds.contains(root.x, root.y, root.z))
                        check(!densityClaim(root))
                        work.inputs shouldBe mapOf("OAK_SAPLING" to 1L)
                        work.outputs shouldBe emptyMap()
                        planted += root
                    }
                    h.commit(work, 1_000L + actions * 10_000L)
                    actions++
                }
                check(planted.size + densityExisting.size >= 20) {
                    "Only ${planted.size + densityExisting.size} sites after $pulses default pulses/$actions committed actions: ${h.runtime.debug(setOf(h.worker.id))}"
                }
                // A scattered seeded packing, rather than one row or a compact cluster beside the entrance.
                check(planted.map { it.x }.distinct().size >= 4 && planted.map { it.z }.distinct().size >= 4)
                check(planted.maxOf { it.x } - planted.minOf { it.x } >= 20)
                check(planted.maxOf { it.z } - planted.minOf { it.z } >= 20)
                guards.forEach { (p, state) -> h.world.getBlockAt(p.x, p.y, p.z).blockData.asString shouldBe state }
                originals.forEach { (p, state) -> h.world.getBlockAt(p.x, p.y, p.z).blockData.asString shouldBe state }
                if (auto) h.city.stock.wood shouldBe initialWood - planted.size
                else (CivsEquipment.available(h.worker, emptyMap())["OAK_SAPLING"] ?: 0L) shouldBe 32L - planted.size
                println("Forestry density: auto=$auto seed=$seed planted=${planted.size} existing=${densityExisting.size} actions=$actions pulses=$pulses")
            }
        }

    test("a fabricated large oak growth proposal may occupy air above village roofs and roads but cannot replace their solid blocks") {
        densityFixture(true, 7_101) { h ->
            val locked = hashSetOf<CivPosition>()
            val occupied = hashSetOf<CivPosition>()
            val protection = CivsProtection({ listOf(h.city) },
                worldLocked = { _, p -> p in locked }, forestryPath = { _, x, z -> (x to z) in densityRoad },
                citizenCollision = { _, p -> p in occupied })
            h.world.getBlockAt(densityOakRoot.x, densityOakRoot.y, densityOakRoot.z).blockData =
                Bukkit.createBlockData("minecraft:oak_sapling[stage=1]")
            fun proposal(extra: Map<CivPosition, String> = emptyMap()): StructureGrowEvent {
                val states = (densityLargeOak() + extra).map { (p, state) -> h.world.getBlockAt(p.x, p.y, p.z).state.apply {
                    blockData = Bukkit.createBlockData(state)
                } }.toMutableList()
                return StructureGrowEvent(Location(h.world, densityOakRoot.x + .5, densityOakRoot.y.toDouble(), densityOakRoot.z + .5),
                    TreeType.BIG_TREE, false, null, states)
            }
            val accepted = proposal()
            protection.tree(accepted)
            accepted.isCancelled shouldBe false
            val protectedBefore = h.capture.states.filter { (p, _) -> h.mill.bounds.contains(p.x, p.y, p.z) ||
                (p.x to p.z) in densityRoad && p.y <= 63 }
            accepted.blocks.forEach { it.update(true, false) shouldBe true }
            protectedBefore.forEach { (p, state) -> h.world.getBlockAt(p.x, p.y, p.z).blockData.asString shouldBe state }
            val room = CivPosition(0, 66, -7)
            val window = CivPosition(8, 66, -7)
            h.world.getBlockAt(window.x, window.y, window.z).type = Material.AIR
            for (point in listOf(room, window, CivPosition(0, 74, -12))) {
                h.world.getBlockAt(point.x, point.y, point.z).type shouldBe Material.AIR
                val foliage = proposal(mapOf(point to "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]"))
                protection.tree(foliage)
                check(!foliage.isCancelled) { "Leaves may occupy air in a room/window or above the roof at $point" }
            }
            for ((point, after) in listOf(
                CivPosition(0, 72, -7) to "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]",
                CivPosition(8, 70, -7) to "minecraft:oak_log[axis=x]",
                CivPosition(0, 63, 5) to "minecraft:oak_log[axis=y]",
                room to "minecraft:oak_log[axis=y]",
                window to "minecraft:oak_log[axis=x]",
            )) {
                val blocked = proposal(mapOf(point to after))
                val count = blocked.blocks.size
                protection.tree(blocked)
                check(blocked.isCancelled) { "Growth must not change the protected building/road cell $point" }
                blocked.blocks.size shouldBe count
            }
            locked += densityOakRoot
            proposal().also { protection.tree(it); it.isCancelled shouldBe true }
            locked.clear()
            occupied += CivPosition(0, 74, 5)
            proposal().also { protection.tree(it); it.isCancelled shouldBe true }
        }
    }

    test("a neighbouring building log column is observed without becoming a tree or bypassing denied native access") {
        densityFixture(true, 7_103) { h ->
            val root = CivPosition(10, 64, -7)
            val roof = CivPosition(8, 72, -7)
            val search = requireNotNull(CivsWorksiteLayouts.site(h.mill)).getAsJsonObject("forester").getAsJsonObject("searchBounds")
            val min = CivsWorksiteLayouts.vector(search["min"])
            val max = CivsWorksiteLayouts.vector(search["max"])
            for (x in min.x..max.x) for (z in min.z..max.z) {
                val p = CivsWorksiteLayouts.point(h.mill, CivPosition(x, 64 - h.mill.anchor.y, z))
                if (p.x in h.mill.bounds.min.x..h.mill.bounds.max.x && p.z in h.mill.bounds.min.z..h.mill.bounds.max.z ||
                    (p.x to p.z) in densityRoad || p in densityExisting) continue
                h.world.getBlockAt(p.x, 63, p.z).type = Material.OAK_PLANKS
                h.world.getBlockAt(p.x, 64, p.z).type = Material.AIR
            }
            h.world.getBlockAt(root.x, 63, root.z).type = Material.DIRT
            h.world.getBlockAt(root.x, 64, root.z).type = Material.SHORT_GRASS
            h.world.getBlockAt(roof.x, roof.y, roof.z).type = Material.OAK_LOG
            val before = h.capture.states.keys.filter { h.mill.bounds.contains(it.x, it.y, it.z) }
                .associateWith { h.world.getBlockAt(it.x, it.y, it.z).blockData.asString }
            h.denied += roof
            repeat(256) { h.pulse() }
            h.runtime.plan(h.city, h.worker) shouldBe null
            h.denied.clear()
            // Releasing a native permission has no block-change event; the retained retry must resume.
            repeat(128) { if (h.runtime.plan(h.city, h.worker) == null) h.pulse() }
            val work = requireNotNull(h.runtime.plan(h.city, h.worker)) {
                "A neighbouring house still blocks the only root outside its volume: ${h.runtime.debug(setOf(h.worker.id))}"
            }
            work.focus shouldBe root
            work.status shouldBe "planting-tree"
            check((h.deniedReads[roof] ?: 0) > 0) { "The building observation bypassed the native permission gate" }
            h.commit(work, 1_000)
            before.forEach { (p, state) -> h.world.getBlockAt(p.x, p.y, p.z).blockData.asString shouldBe state }
        }
    }

    test("physical felling captures a large oak above the roof without including original building logs or bypassing the native permission gate") {
        densityFixture(false, 7_102, tool = "DIAMOND_AXE") { h ->
            val tree = densityLargeOak()
            tree.forEach { (p, state) -> h.world.getBlockAt(p.x, p.y, p.z).blockData = Bukkit.createBlockData(state) }
            val building = h.capture.states.filter { (p, _) -> h.mill.bounds.contains(p.x, p.y, p.z) }
            var batches = 0
            var logs = 0
            while (batches == 0 || h.city.forestryJobs?.containsKey(h.mill.id.toString()) == true) {
                repeat(512) { if (h.runtime.plan(h.city, h.worker) == null) h.pulse() }
                val work = requireNotNull(h.runtime.plan(h.city, h.worker)) {
                    "Large oak stopped after $batches batches: ${h.runtime.debug(setOf(h.worker.id))} hand=${CivsEquipment.slots(h.worker)[4]}"
                }
                work.status shouldBe "felling-tree"
                work.focus shouldBe densityOakRoot
                check(work.changes.none { it.position in building })
                check(work.changes.none { (it.position.x to it.position.z) in densityRoad && it.position.y <= 63 })
                work.inputs shouldBe if (batches == 0) mapOf("OAK_SAPLING" to 1L) else emptyMap()
                val protectedCell = work.changes.first().position
                h.denied += protectedCell
                shouldThrow<CivsRejection> { h.runtime.validate(h.city, work) }
                h.denied.clear()
                logs += requireNotNull(work.forestry).harvestedLogs
                h.commit(work, 1_000L + batches * 10_000L)
                batches++
                check(batches < 8)
            }
            check(logs >= 35 && batches >= 2)
            h.world.getBlockAt(densityOakRoot.x, densityOakRoot.y, densityOakRoot.z).blockData.asString shouldBe "minecraft:oak_sapling[stage=0]"
            tree.filterKeys { it != densityOakRoot }.forEach { (p, _) -> h.world.getBlockAt(p.x, p.y, p.z).type shouldBe Material.AIR }
            building.forEach { (p, state) -> h.world.getBlockAt(p.x, p.y, p.z).blockData.asString shouldBe state }
            (CivsEquipment.available(h.worker, emptyMap())["OAK_SAPLING"] ?: 0L) shouldBe 31L
            println("Large oak physical felling: batches=$batches logs=$logs preservedBuilding=${building.size}")
        }
    }

    for (auto in listOf(true, false)) test("${if (auto) "Auto" else "physical"} birch selection still harvests and replants an existing oak before planting birch") {
        densityFixture(auto, 7_104, tool = "DIAMOND_AXE", timber = "BIRCH_LOG") { h ->
            densityLargeOak().forEach { (p, state) -> h.world.getBlockAt(p.x, p.y, p.z).blockData = Bukkit.createBlockData(state) }
            val building = h.capture.states.filter { (p, _) -> h.mill.bounds.contains(p.x, p.y, p.z) }
            var batches = 0
            var jobId: UUID? = null
            do {
                repeat(512) { if (h.runtime.plan(h.city, h.worker) == null) h.pulse() }
                val work = requireNotNull(h.runtime.plan(h.city, h.worker)) {
                    "Selected birch ignored the existing mature oak: ${h.runtime.debug(setOf(h.worker.id))}"
                }
                work.status shouldBe "felling-tree"
                val job = requireNotNull(work.forestry).job
                if (jobId == null) jobId = job.id else job.id shouldBe jobId
                job.timber shouldBe "OAK_LOG"
                job.sapling shouldBe "OAK_SAPLING"
                work.inputs shouldBe if (batches == 0) mapOf("OAK_SAPLING" to 1L) else emptyMap()
                h.commit(work, 1_000L + batches * 10_000L)
                batches++
                check(batches < 8)
            } while (h.city.forestryJobs?.containsKey(h.mill.id.toString()) == true)
            h.world.getBlockAt(densityOakRoot.x, densityOakRoot.y, densityOakRoot.z).blockData.asString shouldBe CivsForestry.saplingState("OAK_SAPLING")
            val woodBeforeBirch = h.city.stock.wood
            repeat(512) { if (h.runtime.plan(h.city, h.worker) == null) h.pulse() }
            val next = requireNotNull(h.runtime.plan(h.city, h.worker))
            next.status shouldBe "planting-tree"
            next.inputs shouldBe mapOf("BIRCH_SAPLING" to 1L)
            val birch = next.changes.single { it.after == CivsForestry.saplingState("BIRCH_SAPLING") }.position
            check(densityDistance(birch, densityOakRoot) >= 4)
            h.commit(next, 100_000)
            h.world.getBlockAt(birch.x, birch.y, birch.z).blockData.asString shouldBe CivsForestry.saplingState("BIRCH_SAPLING")
            building.forEach { (p, state) -> h.world.getBlockAt(p.x, p.y, p.z).blockData.asString shouldBe state }
            if (auto) h.city.stock.wood shouldBe woodBeforeBirch - 1
            else {
                CivsEquipment.available(h.worker, emptyMap())["OAK_SAPLING"] shouldBe 31L
                CivsEquipment.available(h.worker, emptyMap())["BIRCH_SAPLING"] shouldBe 31L
            }
        }
    }

    test("an unfunded mature oak is harvested without a replacement kit while birch supply stays unchanged") {
        densityFixture(false, 7_106, tool = "DIAMOND_AXE", timber = "BIRCH_LOG") { h ->
            h.city = h.city.copy(workers = listOf(
                CivsEquipment.consumeInputs(h.worker, mapOf("OAK_SAPLING" to 32L))))
            // No new planting is possible, but that must not hold the existing tree for a replacement kit.
            val search = requireNotNull(CivsWorksiteLayouts.site(h.mill)).getAsJsonObject("forester").getAsJsonObject("searchBounds")
            val min = CivsWorksiteLayouts.vector(search["min"])
            val max = CivsWorksiteLayouts.vector(search["max"])
            for (x in min.x..max.x) for (z in min.z..max.z) {
                val p = CivsWorksiteLayouts.point(h.mill, CivPosition(x, 64 - h.mill.anchor.y, z))
                if (p.x in h.mill.bounds.min.x..h.mill.bounds.max.x && p.z in h.mill.bounds.min.z..h.mill.bounds.max.z ||
                    (p.x to p.z) in densityRoad || p in densityExisting || p == densityOakRoot) continue
                h.world.getBlockAt(p.x, 63, p.z).type = Material.OAK_PLANKS
                h.world.getBlockAt(p.x, 64, p.z).type = Material.AIR
            }
            densityLargeOak().forEach { (p, state) -> h.world.getBlockAt(p.x, p.y, p.z).blockData = Bukkit.createBlockData(state) }
            val building = h.capture.states.filter { (p, _) -> h.mill.bounds.contains(p.x, p.y, p.z) }
            CivsEquipment.available(h.worker, emptyMap())["BIRCH_SAPLING"] shouldBe 32L
            repeat(64) { if (h.runtime.plan(h.city, h.worker) == null) h.pulse() }
            var jobId: UUID? = null
            var batches = 0
            do {
                repeat(64) { if (h.runtime.plan(h.city, h.worker) == null) h.pulse() }
                val work = requireNotNull(h.runtime.plan(h.city, h.worker))
                work.status shouldBe "felling-tree"
                work.focus shouldBe densityOakRoot
                val job = requireNotNull(work.forestry).job
                job.timber shouldBe "OAK_LOG"
                if (jobId == null) jobId = job.id else job.id shouldBe jobId
                work.inputs shouldBe emptyMap()
                h.commit(work, 1_000L + batches * 10_000L)
                batches++
                check(batches < 8)
            } while (h.city.forestryJobs?.containsKey(h.mill.id.toString()) == true)
            CivsEquipment.available(h.worker, emptyMap())["OAK_SAPLING"] shouldBe null
            CivsEquipment.available(h.worker, emptyMap())["BIRCH_SAPLING"] shouldBe 32L
            h.world.getBlockAt(densityOakRoot.x, densityOakRoot.y, densityOakRoot.z).type shouldBe Material.AIR
            building.forEach { (p, state) -> h.world.getBlockAt(p.x, p.y, p.z).blockData.asString shouldBe state }
        }
    }

    test("an iron axe breaking between paid oak batches retains its durable job through a real replacement transfer") {
        densityFixture(false, 7_105) { h ->
            val owner = h.addPlayer("ForestryToolOwner")
            h.city = h.city.copy(owner = owner.uniqueId)
            owner.openInventory(Bukkit.createInventory(owner, 9))
            densityLargeOak().forEach { (p, state) -> h.world.getBlockAt(p.x, p.y, p.z).blockData = Bukkit.createBlockData(state) }
            val building = h.capture.states.filter { (p, _) -> h.mill.bounds.contains(p.x, p.y, p.z) }
            var batches = 0
            while (CivsEquipment.slots(h.worker)[4] != null) {
                repeat(512) { if (h.runtime.plan(h.city, h.worker) == null) h.pulse() }
                val work = requireNotNull(h.runtime.plan(h.city, h.worker))
                work.status shouldBe "felling-tree"
                h.commit(work, 1_000L + batches * 10_000L)
                batches++
                check(batches < 8)
            }
            val saved = requireNotNull(h.city.forestryJobs?.get(h.mill.id.toString()))
            check(saved.next > 0 && saved.next < saved.cells.size)
            repeat(64) { h.pulse() }
            h.runtime.plan(h.city, h.worker) shouldBe null
            h.runtime.idleReason(h.city, h.worker) shouldBe "waiting-tool"
            h.city.forestryJobs?.get(h.mill.id.toString()) shouldBe saved
            owner.inventory.setItem(0, ItemStack(Material.IRON_AXE))
            h.receive(owner, requestedTool = Material.IRON_AXE)
            owner.inventory.getItem(0) shouldBe null
            CivsEquipment.slots(h.worker)[4]?.material shouldBe "IRON_AXE"
            h.city.forestryJobs?.get(h.mill.id.toString()) shouldBe saved
            do {
                repeat(512) { if (h.runtime.plan(h.city, h.worker) == null) h.pulse() }
                val work = requireNotNull(h.runtime.plan(h.city, h.worker))
                work.status shouldBe "felling-tree"
                requireNotNull(work.forestry).job.id shouldBe saved.id
                work.inputs shouldBe emptyMap()
                h.commit(work, 100_000L + batches * 10_000L)
                batches++
                check(batches < 8)
            } while (h.city.forestryJobs?.containsKey(h.mill.id.toString()) == true)
            h.world.getBlockAt(densityOakRoot.x, densityOakRoot.y, densityOakRoot.z).blockData.asString shouldBe CivsForestry.saplingState("OAK_SAPLING")
            CivsEquipment.available(h.worker, emptyMap())["OAK_SAPLING"] shouldBe 31L
            building.forEach { (p, state) -> h.world.getBlockAt(p.x, p.y, p.z).blockData.asString shouldBe state }
        }
    }

    for (grown in listOf(true, false)) test("a seeded zero-stock Auto forester after watering ${if (grown) "recovers from its known mature tree" else "honestly waits for a sapling kit in an empty forest"}") {
        densityFixture(true, if (grown) 7_107 else 7_108, clockStep = 30_000L) { h ->
            h.city = h.city.copy(stock = Stock(), forestrySeeded = setOf(h.mill.id.toString()))
            val young = densityExisting.first()
            h.world.getBlockAt(young.x, young.y, young.z).blockData = Bukkit.createBlockData(CivsForestry.saplingState("OAK_SAPLING"))
            repeat(512) { if (h.runtime.plan(h.city, h.worker) == null) h.pulse() }
            val water = requireNotNull(h.runtime.plan(h.city, h.worker))
            water.status shouldBe "watering"
            h.commit(water, 1_000)
            h.city.stock.wood shouldBe 0L
            if (grown) {
                densityLargeOak().forEach { (p, state) -> h.world.getBlockAt(p.x, p.y, p.z).blockData = Bukkit.createBlockData(state) }
                h.runtime.changed(h.world.uid, densityOakRoot)
            }
            repeat(64) { if (h.runtime.plan(h.city, h.worker) == null) h.pulse() }
            if (!grown) {
                h.runtime.plan(h.city, h.worker) shouldBe null
                h.runtime.idleReason(h.city, h.worker) shouldBe "waiting-saplings"
                h.city.stock.wood shouldBe 0L
                h.city.forestryJobs shouldBe null
            } else {
                var batches = 0
                do {
                    repeat(64) { if (h.runtime.plan(h.city, h.worker) == null) h.pulse() }
                    val work = requireNotNull(h.runtime.plan(h.city, h.worker)) {
                        "An unfunded planting turn blocked an observed harvest with deferred seed credit: ${h.runtime.debug(setOf(h.worker.id))}"
                    }
                    work.status shouldBe "felling-tree"
                    requireNotNull(work.forestry).job.seedDeferred shouldBe true
                    work.inputs shouldBe emptyMap()
                    h.commit(work, 10_000L + batches * 10_000L)
                    batches++
                    check(batches < 8)
                } while (h.city.forestryJobs?.containsKey(h.mill.id.toString()) == true)
                check(h.city.stock.wood > 0L)
                h.world.getBlockAt(densityOakRoot.x, densityOakRoot.y, densityOakRoot.z).blockData.asString shouldBe CivsForestry.saplingState("OAK_SAPLING")
            }
        }
    }
})

private val densityExisting = listOf(CivPosition(-12, 64, -21), CivPosition(14, 64, 8))
private val densityRoad = (-1..1).flatMap { x -> (0..11).map { z -> x to z } }.toSet()
private fun densityDistance(a: CivPosition, b: CivPosition): Int = max(abs(a.x - b.x), abs(a.z - b.z))
private fun densityClaim(p: CivPosition): Boolean = p.x in 16..20 && p.z in -25..-21
private val densityOakRoot = CivPosition(12, 64, -7)
private fun densityLargeOak(): Map<CivPosition, String> = linkedMapOf<CivPosition, String>().apply {
    for (y in 64..82) put(densityOakRoot.copy(y = y), "minecraft:oak_log[axis=y]")
    for (x in 2..11) put(CivPosition(x, 73, -7), "minecraft:oak_log[axis=x]")
    for (z in -6..5) put(CivPosition(2, 73, z), "minecraft:oak_log[axis=z]")
    for (x in 0..1) put(CivPosition(x, 73, 5), "minecraft:oak_log[axis=x]")
    for (center in listOf(CivPosition(2, 74, -7), CivPosition(0, 74, 5), CivPosition(12, 81, -7)))
        for (dx in -2..2) for (dy in 0..2) for (dz in -2..2) putIfAbsent(center.copy(x = center.x + dx,
            y = center.y + dy, z = center.z + dz), "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]")
}

private class DensityFixture(val plugin: Plugin, val world: CapturedWorksiteWorld, val capture: RecordedNativeMap,
    val runtime: CivsWorksites, val access: CivsWorld, var city: Settlement, val denied: MutableSet<CivPosition>,
    val deniedReads: MutableMap<CivPosition, Int>, val addPlayer: (String) -> Player) {
    val worker: CivWorker get() = city.workers.single()
    val mill: CivBuilding get() = requireNotNull(city.building(requireNotNull(worker.assignment)))

    fun receive(player: Player, requestedTool: Material? = null, requestedSupplies: Map<String, Long>? = null) {
        CivsStorage(plugin) { emptyArray() }.use { storage ->
            val id = UUID.randomUUID()
            storage.replaceCities(listOf(city)); storage.reserve(city, id, player)
            val transfer = storage.prepareResidentTransfer(city, worker.id, player, null, 0, ClickType.LEFT,
                requestedTool = requestedTool, requestedSupplies = requestedSupplies)
            val after = city.copy(workers = listOf(transfer.worker), inventory = transfer.plan.inventoryAfter,
                revision = city.revision + 1)
            val receipt = CivInventoryTransaction(id, city.id, city.revision, after, transfer.plan.changes,
                residentTransfer = transfer.plan.residentTransfer).validated()
            storage.seal(receipt, keepViewersOpen = true)
            storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
            storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
            storage.unlock(id)
            city = after
        }
        runtime.invalidate(worker.id)
    }

    fun pulse() {
        (Bukkit.getScheduler() as BukkitSchedulerMock).performTicks(5)
        runtime.pulse(listOf(city))
        val budget = runtime.debug()["remainingPulseBudget"] as Map<*, *>
        check(budget["forestWorkUnits"] as Int in 0..192 && budget["columns"] as Int in 0..16 && budget["rays"] as Int in 0..8)
        clearMocks(access, answers = false, recordedCalls = true, childMocks = false, verificationMarks = true, exclusionRules = false)
    }

    fun commit(work: CivWorldWork, at: Long) {
        runtime.validate(city, work)
        val after = CivsRules.finishWorldWork(city, work, at)
        val receipt = CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision, after, emptyList(), worldChanges = work.changes,
            forestry = if (city.autonomy == null) null else CivForestryReceipt(worker, city.stock,
                city.forestryJobs?.get(work.buildingId.toString()), city.forestryJobs == null, work, at, city.forestrySeeded)).validated()
        CivsStorage(plugin) { emptyArray() }.use { storage ->
            storage.replaceCities(listOf(city)); storage.reserve(city, receipt.id); storage.seal(receipt)
            storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
            storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
            storage.unlock(receipt.id)
        }
        city = after
        runtime.invalidate(work.workerId, work)
    }
}

private fun densityFixture(auto: Boolean, seed: Long, tool: String = "IRON_AXE", timber: String = "OAK_LOG", clockStep: Long = 10_000L,
    action: (DensityFixture) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open().use { paper ->
        val capture = RecordedNativeMap.fromResourceRle("/scenarios/forestry-density-village.json", "9b7633b71b3dc7784eebc548a1dd7215d7ecdbeaa64707518f83df014f10e575")
        val world = CapturedWorksiteWorld("forestry-density-$auto-$seed", capture).also(paper.server::addWorld)
        val plugin = paper.createSimplePlugin("ForestryDensity")
        val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
        try {
            val mill = spatialBuilding("caledonia", "lumberjack", 1, CivPosition(0, 63, 0)).copy(id = UUID(0, seed), production = timber)
            val worker = spatialWorker(mill, tool).let {
                if (auto) it.copy(items = null, inventory = null) else CivsEquipment.collectSupplies(it,
                    mapOf("OAK_SAPLING" to 32L, timber.removeSuffix("_LOG") + "_SAPLING" to 32L))
            }
            val initial = spatialCity(world.uid, listOf(mill), worker).let {
                if (auto) it.copy(stock = Stock(wood = 64), inventory = null, autonomy = CivAutonomy()) else it
            }
            lateinit var fixture: DensityFixture
            CivsWorld(plugin, tasks) { listOf(fixture.city) }.use { nativeAccess ->
                // Exercise the existing native permission gate with a denied foreign-area decision.
                // This does not emulate or claim integration coverage of the external Lands plugin.
                val access = spyk(nativeAccess)
                val denied = hashSetOf<CivPosition>()
                val deniedReads = hashMapOf<CivPosition, Int>()
                every { access.canModify(any(), any(), any()) } answers {
                    val block = secondArg<Block>()
                    val point = CivPosition(block.x, block.y, block.z)
                    if (point in denied) deniedReads[point] = (deniedReads[point] ?: 0) + 1
                    !densityClaim(point) && point !in denied && callOriginal()
                }
                var nanos = 0L
                val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false },
                    forestryPaths = { densityRoad }, nanoTime = { nanos.also { nanos += clockStep } })
                fixture = DensityFixture(plugin, world, capture, runtime, access, initial, denied, deniedReads, paper::addPlayer)
                action(fixture)
            }
        } finally { tasks.close() }
    }
}
