package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class CivsMineV3Test : FunSpec({
    test("the historical V3 central opening stays clear through every aligned three-wide mining floor") {
        for (style in listOf("nordic", "medievaloak", "caledonia")) for (level in 1..3) {
            val mine = spatialBuilding(style, "miner", level, CivPosition(0, 80, 0))
            val plan = requireNotNull(CivsMinePlan.forBuilding(mine, 3))
            val center = CivsWorksiteLayouts.vector(CivsWorksiteLayouts.site(mine)!!.getAsJsonObject("mine")["shaftCenter"])
            val hole = (-1..1).flatMap { x -> (-1..1).map { z -> center.x + x to center.z + z } }.toSet()
            plan.floors.size shouldBe level + 2
            plan.floors.zipWithNext().all { (a, b) -> a.feetY - b.feetY == 4 } shouldBe true
            plan.floors.map { floor -> floor.cells.map { it.x to it.z }.toSet() }.distinct().size shouldBe 1
            plan.floors.all { floor -> floor.cells.none { (it.x to it.z) in hole } } shouldBe true
            for (y in plan.lowest..plan.bottom.y) for ((x, z) in hole) {
                val actions = plan.steps.filter { it.point == CivPosition(x, y, z) }
                actions.any { it.after == null } shouldBe true
                actions.none { it.after != null } shouldBe true
            }
            val first = plan.floors.first()
            val supports = first.cells.map { it.x to it.z }.toSet()
            // Every axial gateway is genuinely three wide. Ladder/backing live
            // in a corner, and the transfer floor occupies only the tier ceiling.
            for (distance in 2..4) for (side in -1..1) {
                (center.x + distance to center.z + side in supports) shouldBe true
                (center.x - distance to center.z + side in supports) shouldBe true
                (center.x + side to center.z + distance in supports) shouldBe true
                (center.x + side to center.z - distance in supports) shouldBe true
            }
            plan.transferFloor.all { it.y == first.feetY + 3 && (it.x to it.z) !in hole } shouldBe true
            // Four equal 16-block arms start outside the nine-by-nine ring.
            for (distance in 5..20) for (side in -1..1) {
                (center.x + distance to center.z + side in supports) shouldBe true
                (center.x - distance to center.z + side in supports) shouldBe true
                (center.x + side to center.z + distance in supports) shouldBe true
                (center.x + side to center.z - distance in supports) shouldBe true
            }
            supports.all { (x, z) -> kotlin.math.abs(x - center.x) <= 20 && kotlin.math.abs(z - center.z) <= 20 } shouldBe true
            for (rotation in listOf(0, 90, 180, 270)) {
                val rotated = mine.copy(rotation = rotation)
                first.cells.forEach { local ->
                    CivsMinePlan.localPoint(rotated, CivsWorksiteLayouts.point(rotated, local)) shouldBe local
                }
            }
        }
    }

    test("inexhaustible extraction has one weighted item and no high-tier yield from an unsuitable pickaxe") {
        for (level in 1..3) {
            (0..9_999).map { CivsWorksites.generatorOutput(it, level) { true } }.groupingBy { it }.eachCount() shouldBe
                mapOf("COBBLESTONE" to 10_000 - 516 * level, "COAL" to 400 * level, "RAW_IRON" to 100 * level,
                    "RAW_GOLD" to 5 * level, "REDSTONE" to 10 * level, "DIAMOND" to level)
            (0..9_999).map { CivsWorksites.generatorOutput(it, level) { material -> material == "RAW_IRON" } }
                .groupingBy { it }.eachCount() shouldBe
                mapOf("COBBLESTONE" to 10_000 - 500 * level, "COAL" to 400 * level, "RAW_IRON" to 100 * level)
            (0..9_999).map { CivsWorksites.generatorOutput(it, level) { false } }.groupingBy { it }.eachCount() shouldBe
                mapOf("COBBLESTONE" to 10_000 - 400 * level, "COAL" to 400 * level)
        }
    }

    test("a historical V3 deferred access wall expires before transfer work and cannot borrow a later central opening row") {
        for (style in listOf("nordic", "medievaloak", "caledonia")) {
            val mine = spatialBuilding(style, "miner", 1, CivPosition(0, 80, 0))
            val plan = requireNotNull(CivsMinePlan.forBuilding(mine, 3))
            val exposed = plan.steps.first { it.after == null && it.point.y == plan.bottom.y - 1 &&
                (it.point.x to it.point.z) !in plan.openingColumns }.point
            val deadline = plan.lowerClearanceEnd(exposed)
            (deadline > 0) shouldBe true
            (deadline <= plan.steps.indexOfFirst { it.branch == "transfer" }) shouldBe true
            plan.lowerClearanceEnd(plan.bottom.copy(y = plan.transferFeetY)) shouldBe 0
        }
    }

    test("a completed V2 snapshot and paid receipt remain readable while the first V3 action stores an independent cursor") {
        failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use {
        val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 80, 0))
        val oldPlan = requireNotNull(CivsMinePlan.forBuilding(mine, 2))
        oldPlan.steps.size shouldBe 281
        val worker = spatialWorker(mine, "IRON_PICKAXE").copy(completedCycles = 49)
        val key = mine.id.toString()
        val old = spatialCity(UUID.randomUUID(), listOf(mine), worker).copy(mineProgress = mapOf(key to 214L),
            minePlans = mapOf(key to CivMinePlanState(2, 281, 214)))
        CivsRules.validate(Gson().fromJson(Gson().toJson(old), Settlement::class.java)) shouldBe old
        val target = requireNotNull(CivsMinePlan.forBuilding(mine, 3)).center
        val cell = CivPlanBlock(CivsWorksiteLayouts.point(mine, target), "minecraft:stone", "minecraft:air")
        val oldReceipt = CivInventoryTransaction(UUID.randomUUID(), old.id, old.revision,
            old.copy(revision = old.revision + 1), emptyList(), worldChanges = listOf(cell)).validated()
        Gson().fromJson(Gson().toJson(oldReceipt), CivInventoryTransaction::class.java).validated().after.minePlans shouldBe old.minePlans
        val work = CivWorldWork(worker.id, 49, mine.id, mine.entrance, listOf(cell), status = "digging-mine",
            miningStep = 3, miningPlanVersion = 3)
        val after = CivsRules.finishWorldWork(old, work, 100_000)
        after.minePlans shouldBe mapOf(key to CivMinePlanState(3, 3, 214, 281))
        after.mineProgress shouldBe old.mineProgress
        after.workers.single().completedCycles shouldBe 50L
        CivsRules.validate(Gson().fromJson(Gson().toJson(after), Settlement::class.java)) shouldBe after
        shouldThrow<CivsRejection> { CivsRules.finishWorldWork(after, work, 100_001) }.reason shouldBe "stale-delivery"
        shouldThrow<CivsRejection> { CivsRules.validate(after.copy(minePlans = mapOf(key to
            CivMinePlanState(3, 3, 214, 282)))) }.reason shouldBe "invalid-state"
        }
        }
    }

    test("underground access retains three foundation layers and never enters another quarry footprint") {
        val building = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 80, 0)).copy(
            type = BuildingType.TOWN_HALL, bounds = CivBox(CivPosition(-10, 66, -10), CivPosition(10, 90, 10)))
        CivsMinePlan.belowFoundation(building, 62) shouldBe true
        CivsMinePlan.belowFoundation(building, 63) shouldBe false
        CivsMinePlan.belowFoundation(building, 61, 1) shouldBe true
        CivsMinePlan.belowFoundation(building, 62, 1) shouldBe false
        CivsMinePlan.belowFoundation(building.copy(type = BuildingType.QUARRY), -60, 1) shouldBe false
    }

    test("V4 retains the original V2 natural backing column instead of cutting it for an offset ladder") {
        val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
        val old = requireNotNull(CivsMinePlan.forBuilding(mine, 2))
        val previous = requireNotNull(CivsMinePlan.forBuilding(mine, 3))
        val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
        old.steps.size shouldBe 281
        val local = CivPosition(8, -9, -8)
        old.steps.last { it.point == local }.shaftSupport shouldBe true
        previous.steps.any { it.point == local && it.after == null } shouldBe true
        plan.steps.any { it.point == local && it.after == null } shouldBe false
        plan.steps.last { it.point == local }.shaftSupport shouldBe true
        plan.bottom shouldBe old.bottom
        plan.backing shouldBe old.backing
    }

    test("V2 compatibility still rejects a paid cobblestone floor replaced with stone") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("mine-v2-paid-floor-drift").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("MineV2PaidFloorDrift")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                try {
                    val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
                    val old = requireNotNull(CivsMinePlan.forBuilding(mine, 2))
                    val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
                    val oldFinal = old.steps.associateBy { it.point }
                    val index = plan.steps.indexOfFirst { it.after == "minecraft:cobblestone" &&
                        oldFinal[it.point]?.let { oldStep -> !oldStep.shaftSupport && oldStep.after == "minecraft:cobblestone" } == true }
                    (index >= 0) shouldBe true
                    for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                    seedMineNaturalCover(world, mine)
                    for (step in old.steps + plan.steps.take(index)) {
                        val p = CivsWorksiteLayouts.point(mine, step.point)
                        world.getBlockAt(p.x, p.y, p.z).blockData = Bukkit.createBlockData(step.after ?: "minecraft:air")
                    }
                    val target = CivsWorksiteLayouts.point(mine, plan.steps[index].point)
                    world.getBlockAt(target.x, target.y, target.z).type = Material.STONE
                    val city = spatialCity(world.uid, listOf(mine), spatialWorker(mine, "IRON_PICKAXE"))
                        .copy(minePlans = mapOf(mine.id.toString() to seedMinePaidSpine(world, mine,
                            CivMinePlanState(CivsMinePlan.VERSION, index.toLong(), v2Step = 281))))
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        // Legacy-cell validation must not depend on the test host's native-slice timing.
                        val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false },
                            nanoTime = { 0L })
                        var rejectedPaidFloor = false
                        repeat(128) {
                            runtime.pulse(listOf(city))
                            val entries = runtime.debug(setOf(city.workers.single().id))["entries"] as List<*>
                            val need = (entries.single() as Map<*, *>)["need"] as? Map<*, *>
                            if (need?.get("reason") == "legacy-cell-changed" &&
                                need["point"] == "${target.x},${target.y},${target.z}") rejectedPaidFloor = true
                            // Deferring this section may expose a safe lower action, never a repair of the replaced paid floor.
                            runtime.plan(city, city.workers.single())?.changes.orEmpty().none { it.position == target } shouldBe true
                        }
                        rejectedPaidFloor shouldBe true
                        world.getBlockAt(target.x, target.y, target.z).type shouldBe Material.STONE
                        city.workers.single().completedCycles shouldBe 0L
                    }
                } finally { tasks.close() }
            }
        }
    }

    test("a prepared stone-face receipt recovers exactly one bag item and its equipment snapshot without replay") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("mine-v3-prepared-receipt").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("MineV3PreparedReceipt")
                val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
                val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
                val face = CivsWorksiteLayouts.point(mine, requireNotNull(geometry.generator))
                val standing = CivsWorksiteLayouts.point(mine, requireNotNull(geometry.generatorStanding))
                world.loadChunk(face.x shr 4, face.z shr 4)
                world.getBlockAt(face.x, face.y, face.z).type = Material.COBBLESTONE
                var city = spatialCity(world.uid, listOf(mine), spatialWorker(mine, "IRON_PICKAXE"))
                    .copy(minePlans = mapOf(mine.id.toString() to CivMinePlanState(CivsMinePlan.VERSION, geometry.steps.size.toLong())))
                CivsStorage(plugin) { emptyArray() }.use { storage ->
                    repeat(2) { cycle ->
                        val worker = city.workers.single()
                        val beforeCount = worker.items!!.drop(CivsEquipment.BAG).sumOf { it?.amount ?: 0 }
                        // Controlled planner output: this test verifies journal/bag/equipment
                        // bookkeeping, not Paper's preferred-tool API or damage calculation.
                        val slots = CivsEquipment.slots(worker).toMutableList()
                        val tool = requireNotNull(slots[4]).stack()
                        tool.itemMeta = (tool.itemMeta as Damageable).also { it.damage = cycle + 1 }
                        slots[4] = CivResidentItem.of(tool)
                        val before = if (cycle == 0) "minecraft:cobblestone" else "minecraft:stone"
                        val afterFace = if (cycle == 0) "minecraft:stone" else "minecraft:cobblestone"
                        val work = CivWorldWork(worker.id, worker.completedCycles, mine.id, standing,
                            listOf(CivPlanBlock(face, before, afterFace)), outputs = mapOf("COBBLESTONE" to 1L),
                            durationMillis = 6_000, status = "digging-mine", miningStep = geometry.steps.size.toLong(),
                            equipmentAfter = slots, focus = face, miningPlanVersion = CivsMinePlan.VERSION, miningGenerator = true)
                        val id = UUID.randomUUID()
                        storage.reserve(city, id)
                        val native = storage.prepareWork(city, mine.id, emptyMap(), emptyMap(), work.changes)
                        val after = CivsRules.finishWorldWork(city, work, 100_000 + cycle * 7_000L).copy(inventory = native.inventoryAfter)
                        val receipt = CivInventoryTransaction(id, city.id, city.revision, after, native.changes,
                            worldChanges = native.worldChanges).validated()
                        val recovered = Gson().fromJson(Gson().toJson(receipt), CivInventoryTransaction::class.java).validated()
                        storage.seal(recovered, keepViewersOpen = true)
                        storage.reconcile(recovered) shouldBe CivInventoryApplyResult.Applied
                        storage.reconcile(recovered) shouldBe CivInventoryApplyResult.Applied
                        city = recovered.after
                        city.workers.single().items!!.drop(CivsEquipment.BAG).sumOf { it?.amount ?: 0 } shouldBe beforeCount + 1
                        (city.workers.single().items!![4]!!.stack().itemMeta as Damageable).damage shouldBe cycle + 1
                        city.inventory!!.buildingStock(mine.id) shouldBe emptyMap()
                        city.workers.single().completedCycles shouldBe cycle + 1L
                        world.getBlockAt(face.x, face.y, face.z).blockData.asString shouldBe afterFace
                        storage.unlock(id)
                    }
                }
            }
        }
    }

    // Pinned MockBukkit omits BlockData.isPreferredTool. Keep this actual planner
    // scenario explicit and unverified until supported Paper/native acceptance.
    test("native stone-face planning and tool tiers - skipped because MockBukkit lacks BlockData.isPreferredTool")
        .config(enabled = false) {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val ore = Bukkit.createBlockData("minecraft:iron_ore")
                ore.isPreferredTool(ItemStack(Material.WOODEN_PICKAXE)) shouldBe false
                ore.isPreferredTool(ItemStack(Material.STONE_PICKAXE)) shouldBe true
                val world = CivsWorksiteWorldFixture("mine-v3-generator").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("MineV3Generator")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                try {
                    val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
                    val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
                    for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                    for (step in geometry.steps) {
                        val p = CivsWorksiteLayouts.point(mine, step.point)
                        world.getBlockAt(p.x, p.y, p.z).blockData = Bukkit.createBlockData(step.after ?: "minecraft:air")
                    }
                    var city = spatialCity(world.uid, listOf(mine), spatialWorker(mine, "IRON_PICKAXE"))
                        .copy(minePlans = mapOf(mine.id.toString() to CivMinePlanState(CivsMinePlan.VERSION, geometry.steps.size.toLong())))
                    val face = CivsWorksiteLayouts.point(mine, requireNotNull(geometry.generator))
                    CivsStorage(plugin) { emptyArray() }.use { storage ->
                        CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                            val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                            fun next(): CivWorldWork {
                                repeat(64) {
                                    runtime.pulse(listOf(city))
                                    runtime.plan(city, city.workers.single())?.let { return it }
                                }
                                error("Generator blocked: ${runtime.idleReason(city, city.workers.single())}")
                            }
                            repeat(2) { cycle ->
                                val worker = city.workers.single()
                                val beforeCount = worker.items!!.drop(CivsEquipment.BAG).sumOf { it?.amount ?: 0 }
                                val work = next()
                                work.miningGenerator shouldBe true
                                work.durationMillis shouldBe 6_000L
                                work.inputs shouldBe emptyMap()
                                work.outputs.values.sum() shouldBe 1L
                                work.changes.single().before shouldBe if (cycle == 0) "minecraft:cobblestone" else "minecraft:stone"
                                work.changes.single().after shouldBe if (cycle == 0) "minecraft:stone" else "minecraft:cobblestone"
                                val tool = work.equipmentAfter!![4]!!.stack()
                                (tool.itemMeta as Damageable).damage shouldBe cycle + 1
                                runtime.validate(city, work)
                                val id = UUID.randomUUID()
                                storage.reserve(city, id)
                                val native = storage.prepareWork(city, mine.id, work.storageInputs(worker), emptyMap(), work.changes)
                                val after = CivsRules.finishWorldWork(city, work, 100_000 + cycle * 7_000L).copy(inventory = native.inventoryAfter)
                                val receipt = CivInventoryTransaction(id, city.id, city.revision, after, native.changes,
                                    worldChanges = native.worldChanges).validated()
                                val recovered = Gson().fromJson(Gson().toJson(receipt), CivInventoryTransaction::class.java).validated()
                                storage.seal(recovered, keepViewersOpen = true)
                                storage.reconcile(recovered) shouldBe CivInventoryApplyResult.Applied
                                storage.reconcile(recovered) shouldBe CivInventoryApplyResult.Applied
                                city = recovered.after
                                city.workers.single().items!!.drop(CivsEquipment.BAG).sumOf { it?.amount ?: 0 } shouldBe beforeCount + 1
                                city.inventory!!.buildingStock(mine.id) shouldBe emptyMap()
                                city.workers.single().completedCycles shouldBe cycle + 1L
                                storage.unlock(id)
                                runtime.invalidate(worker.id)
                            }
                            val admitted = next()
                            world.getBlockAt(face.x, face.y, face.z).type = Material.DIRT
                            shouldThrow<CivsRejection> { runtime.validate(city, admitted) }.reason shouldBe "stale-worksite"
                            runtime.invalidate(city.workers.single().id)
                            repeat(64) { runtime.pulse(listOf(city)) }
                            runtime.plan(city, city.workers.single()) shouldBe null
                            runtime.idleReason(city, city.workers.single()) shouldBe "worksite-obstacle"
                            world.getBlockAt(face.x, face.y, face.z).type shouldBe Material.DIRT
                            city.workers.single().completedCycles shouldBe 2L
                        }
                    }
                } finally { tasks.close() }
            }
        }
    }

    test("an old paid torch inside the new headroom is physically removed and returned to the worker bag") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("mine-v3-old-light").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("MineV3OldLight")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                try {
                    val mine = spatialBuilding("medievaloak", "miner", 2, CivPosition(0, 63, 0))
                    val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
                    val old = requireNotNull(CivsMinePlan.forBuilding(mine, 2))
                    val oldLights = old.floors.flatMap { it.torches }.toSet()
                    val currentLights = plan.floors.flatMap { it.torches }.toSet()
                    val index = plan.steps.indexOfFirst { it.after == null && it.point in oldLights && it.point !in currentLights }
                    (index >= 0) shouldBe true
                    for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                    seedMineNaturalCover(world, mine)
                    for (step in old.steps + plan.steps.take(index)) {
                        val p = CivsWorksiteLayouts.point(mine, step.point)
                        world.getBlockAt(p.x, p.y, p.z).blockData = Bukkit.createBlockData(step.after ?: "minecraft:air")
                    }
                    val top = CivsWorksiteLayouts.point(mine, plan.bottom)
                    for (y in top.y..top.y + 3) world.getBlockAt(top.x, y, top.z).blockData =
                        Bukkit.createBlockData("minecraft:ladder[facing=${plan.facing},waterlogged=false]")
                    val target = CivsWorksiteLayouts.point(mine, plan.steps[index].point)
                    world.getBlockAt(target.x, target.y, target.z).type = Material.TORCH
                    val worker = spatialWorker(mine, "IRON_PICKAXE")
                    var city = spatialCity(world.uid, listOf(mine), worker).copy(minePlans = mapOf(mine.id.toString() to
                        seedMinePaidSpine(world, mine, CivMinePlanState(CivsMinePlan.VERSION, index.toLong(),
                            v2Step = old.steps.size.toLong()))))
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        // This semantic oracle has separate bounded-yield coverage; host speed must not expire its slice.
                        val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, nanoTime = { 0L })
                        var removal: CivWorldWork? = null
                        for (action in 0 until 64) {
                            val currentWorker = city.workers.single()
                            repeat(256) { if (runtime.plan(city, currentWorker) == null) runtime.pulse(listOf(city)) }
                            val selected = requireNotNull(runtime.plan(city, currentWorker)) { runtime.debug().toString() }
                            if (selected.changes.single().position == target) { removal = selected; break }
                            // Legacy open rooms can need paid boundary repairs before the old torch is reachable.
                            withClue(selected) { selected.status shouldBe "securing-mine" }
                            runtime.validate(city, selected)
                            val after = CivsRules.finishWorldWork(city, selected, 20_000 + action * 5_000L)
                            selected.changes.forEach { cell ->
                                world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.before
                                world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData = Bukkit.createBlockData(cell.after)
                            }
                            city = after
                            runtime.invalidate(currentWorker.id, selected)
                        }
                        val work = requireNotNull(removal) { runtime.debug().toString() }
                        work.changes.single().position shouldBe target
                        work.changes.single().before shouldBe "minecraft:torch"
                        work.changes.single().after shouldBe "minecraft:air"
                        work.outputs shouldBe mapOf("TORCH" to 1L)
                        work.inputs shouldBe emptyMap()
                        runtime.validate(city, work)
                        val after = CivsRules.finishWorldWork(city, work, 500_000)
                        CivsEquipment.plainCount(after.workers.single(), "TORCH") shouldBe CivsEquipment.plainCount(city.workers.single(), "TORCH") + 1
                        after.minePlans!!.getValue(mine.id.toString()).v2Step shouldBe old.steps.size.toLong()
                    }
                } finally { tasks.close() }
            }
        }
    }

    test("export the actual V3 plan for the private block-model preview") {
        val directory = Path.of("build", "mine-plan-preview")
        Files.createDirectories(directory)
        val summaries = mutableListOf<Map<String, Any>>()
        for (style in listOf("nordic", "medievaloak", "caledonia")) {
            for (level in 1..3) {
                val upgraded = spatialBuilding(style, "miner", level, CivPosition(0, 63, 0))
                val geometry = requireNotNull(CivsMinePlan.forBuilding(upgraded, 3))
                if (level == 1 || level == 3) {
                    val name = if (level == 1) "$style-v3.json" else "$style-l3-v3.json"
                    Files.writeString(directory.resolve(name), Gson().toJson(mapOf(
                        "schematicId" to upgraded.schematicId, "buildingLevel" to level, "version" to geometry.version,
                        "bottom" to geometry.bottom, "center" to geometry.center, "floors" to geometry.floors,
                        "steps" to geometry.steps, "transferFeetY" to geometry.transferFeetY,
                        "transferFloor" to geometry.transferFloor, "extensionLadder" to geometry.extensionLadder,
                        "extensionBacking" to geometry.extensionBacking, "generator" to geometry.generator,
                        "generatorStanding" to geometry.generatorStanding)))
                }
                summaries += mapOf("style" to style, "level" to level, "floors" to geometry.floors.size,
                    "steps" to geometry.steps.size,
                    "clears" to geometry.steps.count { it.after == null },
                    "cobblestone" to geometry.steps.count { it.after == "minecraft:cobblestone" },
                    "ladders" to geometry.steps.count { it.after?.startsWith("minecraft:ladder[") == true },
                    "torches" to geometry.steps.count { it.after == "minecraft:torch" })
            }
        }
        Files.writeString(directory.resolve("counts.json"), Gson().toJson(summaries))
    }
})
