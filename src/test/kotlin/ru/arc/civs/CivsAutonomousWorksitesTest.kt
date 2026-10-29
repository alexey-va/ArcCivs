package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID

class CivsAutonomousWorksitesTest : FunSpec({
    test("native replay accepts natural forward growth but prioritizes an exact untouched preimage") {
        val harvest = CivPlanBlock(CivPosition(0, 64, 0), "minecraft:wheat[age=7]", "minecraft:wheat[age=0]")
        CivsAutonomousWorksites.nativeAfterMatches(harvest, "minecraft:wheat[age=2]") shouldBe true
        CivsAutonomousWorksites.nativeAfterMatches(harvest, harvest.before) shouldBe false
        CivsAutonomousWorksites.nativeAfterMatches(harvest, "minecraft:carrots[age=2]") shouldBe false
        val watering = harvest.copy(before = "minecraft:wheat[age=3]", after = "minecraft:wheat[age=4]")
        CivsAutonomousWorksites.nativeAfterMatches(watering, "minecraft:wheat[age=5]") shouldBe true
        CivsAutonomousWorksites.nativeAfterMatches(watering, "minecraft:wheat[age=2]") shouldBe false
        val soil = harvest.copy(before = "minecraft:farmland[moisture=0]", after = "minecraft:farmland[moisture=7]")
        CivsAutonomousWorksites.nativeAfterMatches(soil, "minecraft:farmland[moisture=4]") shouldBe true
        CivsAutonomousWorksites.nativeAfterMatches(soil, soil.before) shouldBe false
        CivsAutonomousWorksites.nativeAfterMatches(soil, "minecraft:dirt") shouldBe false
    }

    test("Auto visual admission strips all transferable goods and rejects invalid receipt domains") {
        val farm = spatialBuilding("nordic", "farmer", 1, CivPosition(0, 63, 0))
        val worker = CivWorker(UUID.randomUUID(), "Фермер", farm.id, nextDeliveryAt = 60_000)
        val physical = spatialCity(UUID.randomUUID(), listOf(farm), worker)
        val city = physical.copy(inventory = null, autonomy = CivAutonomy())
        val soil = CivsWorksiteLayouts.field(farm).first()
        val crop = soil.copy(y = soil.y + 1)
        val proposed = CivWorldWork(worker.id, worker.completedCycles, farm.id, farm.entrance,
            listOf(CivPlanBlock(crop, "minecraft:air", "minecraft:wheat[age=0]")),
            inputs = mapOf("WHEAT_SEEDS" to 1L), outputs = mapOf("WHEAT" to 3L), status = "working-field",
            equipmentAfter = List(CivsEquipment.SLOTS) { null })
        CivsAutonomousWorksites.visual(physical, farm, proposed) shouldBe proposed
        val work = CivsAutonomousWorksites.visual(city, farm, proposed)
        work.autonomousVisual shouldBe true
        work.inputs shouldBe emptyMap()
        work.outputs shouldBe emptyMap()
        work.equipmentAfter shouldBe null
        work.changes shouldBe proposed.changes
        city.workers.single() shouldBe worker
        for (bad in listOf(work.copy(autonomousVisual = false), work.copy(outputs = mapOf("WHEAT" to 1L)),
            work.copy(inputs = mapOf("WHEAT_SEEDS" to 1L)), work.copy(cycle = 1), work.copy(changes = emptyList()),
            work.copy(changes = listOf(work.changes.single().copy(after = "minecraft:diamond_block"))),
            work.copy(changes = listOf(work.changes.single().copy(position = crop.copy(x = crop.x + 200)))))) {
            shouldThrow<CivsRejection> { CivsAutonomousWorksites.validate(city, bad) }
        }
        shouldThrow<CivsRejection> { CivsAutonomousWorksites.validate(physical, work) }
        shouldThrow<CivsRejection> { CivsAutonomousWorksites.validate(city.copy(autonomy = CivAutonomy(paused = true)), work) }
    }

    test("Auto native farm tills plants waters and replants without a tool or inventory supply") {
        withAutoWorksite("auto-field", "farmer", Material.DIRT) { world, farm, city, runtime ->
            val worker = city.workers.single()
            val plots = CivsWorksiteLayouts.field(farm)
            plots.forEach { point ->
                world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=7]")
                world.getBlockAt(point.x, point.y + 1, point.z).blockData = Bukkit.createBlockData("minecraft:wheat[age=1]")
            }
            CivsWorksiteLayouts.irrigation(farm).forEach { world.getBlockAt(it.x, it.y, it.z).type = Material.WATER }
            val soil = plots.first()
            val crop = soil.copy(y = soil.y + 1)
            world.getBlockAt(soil.x, soil.y, soil.z).type = Material.DIRT
            world.getBlockAt(crop.x, crop.y, crop.z).type = Material.AIR
            fun next(): CivWorldWork = nextAutoWork(runtime, city).also { work ->
                work.autonomousVisual shouldBe true
                work.inputs shouldBe emptyMap()
                work.outputs shouldBe emptyMap()
                work.equipmentAfter shouldBe null
                runtime.validate(city, work)
            }
            fun apply(work: CivWorldWork) {
                // Planner-only check. Durable receipt/replay and observed session time belong to integration tests.
                work.changes.forEach { world.getBlockAt(it.position.x, it.position.y, it.position.z).blockData = Bukkit.createBlockData(it.after) }
                runtime.invalidate(worker.id)
            }
            val till = next()
            till.changes.single().position shouldBe soil
            till.changes.single().after shouldBe "minecraft:farmland[moisture=7]"
            apply(till)
            val plant = next()
            plant.changes.single().position shouldBe crop
            plant.changes.single().after shouldBe "minecraft:wheat[age=0]"
            apply(plant)
            plots.filter { it != soil }.forEach { world.getBlockAt(it.x, it.y + 1, it.z).type = Material.STONE }
            world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=0]")
            world.getBlockAt(crop.x, crop.y, crop.z).blockData = Bukkit.createBlockData("minecraft:wheat[age=3]")
            val care = next()
            care.status shouldBe "fertilizing"
            care.changes.any { it.position == crop && it.after == "minecraft:wheat[age=4]" } shouldBe true
            apply(care)
            world.getBlockAt(crop.x, crop.y, crop.z).blockData = Bukkit.createBlockData("minecraft:wheat[age=7]")
            val harvest = next()
            harvest.changes.single().position shouldBe crop
            harvest.changes.single().after shouldBe "minecraft:wheat[age=0]"
            apply(harvest)
            // Auto has no physical storage; scanning the fertilizable age must never request it.
            world.getBlockAt(crop.x, crop.y, crop.z).blockData = Bukkit.createBlockData("minecraft:wheat[age=5]")
            repeat(8) { runtime.pulse(listOf(city)) }
            city.workers.single() shouldBe worker
            city.stock shouldBe Stock(7, 8, 9)
            city.minePlans shouldBe null
        }
    }

    test("Auto care advances every immature crop by exactly one stage even in rain without inventory inputs") {
        withAutoWorksite("auto-all-stage-care", "farmer", Material.DIRT) { world, originalFarm, originalCity, runtime ->
            val worker = originalCity.workers.single()
            val plots = CivsWorksiteLayouts.field(originalFarm)
            plots.forEach { soil ->
                world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=7]")
                world.getBlockAt(soil.x, soil.y + 1, soil.z).type = Material.STONE
            }
            CivsWorksiteLayouts.irrigation(originalFarm).forEach { world.getBlockAt(it.x, it.y, it.z).type = Material.WATER }
            val crop = plots.first().let { it.copy(y = it.y + 1) }
            for ((production, name) in listOf("WHEAT" to "wheat", "CARROT" to "carrots", "POTATO" to "potatoes")) {
                val farm = originalFarm.copy(production = production)
                val city = originalCity.copy(buildings = originalCity.buildings.map { if (it.id == farm.id) farm else it })
                for (rain in listOf(false, true)) for (age in 0..6) {
                    world.setStorm(rain)
                    world.getBlockAt(crop.x, crop.y, crop.z).blockData = Bukkit.createBlockData("minecraft:$name[age=$age]")
                    runtime.invalidate(worker.id)
                    val work = nextAutoWork(runtime, city)
                    withClue("$production age=$age rain=$rain") {
                        work.status shouldBe "fertilizing"
                        work.durationMillis shouldBe 4_000L
                        work.changes shouldBe listOf(CivPlanBlock(crop, "minecraft:$name[age=$age]", "minecraft:$name[age=${age + 1}]"))
                        work.inputs shouldBe emptyMap()
                        work.outputs shouldBe emptyMap()
                        work.equipmentAfter shouldBe null
                        runtime.validate(city, work)
                        val after = CivsRules.finishAutonomousVisual(city, work)
                        after.workers shouldBe city.workers
                        after.stock shouldBe city.stock
                        after.autonomy shouldBe city.autonomy
                        after.revision shouldBe city.revision + 1
                        for (invalidAfter in listOf("minecraft:$name[age=${(age + 2) % 8}]", "minecraft:beetroots[age=1]")) {
                            shouldThrow<CivsRejection> { CivsAutonomousWorksites.validate(city,
                                work.copy(changes = listOf(work.changes.single().copy(after = invalidAfter)))) }
                        }
                        if (age == 3) CivsAutonomousWorksites.validate(city, work.copy(status = "watering"))
                    }
                }
                world.setStorm(false)
                world.getBlockAt(crop.x, crop.y, crop.z).blockData = Bukkit.createBlockData("minecraft:$name[age=7]")
                runtime.invalidate(worker.id)
                nextAutoWork(runtime, city).status shouldBe "working-field"
            }
        }
    }

    test("Auto care hands off to different known plots after each receipt and yields to a ripe crop") {
        withAutoWorksite("auto-care-handoff", "farmer", Material.DIRT, nanoTime = { 0L }) { world, farm, city, runtime ->
            val worker = city.workers.single()
            val plots = CivsWorksiteLayouts.field(farm)
            plots.forEach { soil ->
                world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=7]")
                world.getBlockAt(soil.x, soil.y + 1, soil.z).blockData = Bukkit.createBlockData("minecraft:wheat[age=1]")
            }
            CivsWorksiteLayouts.irrigation(farm).forEach { world.getBlockAt(it.x, it.y, it.z).type = Material.WATER }
            var work = nextAutoWork(runtime, city)
            fun scan() = ((runtime.debug(setOf(worker.id))["entries"] as List<*>).single() as Map<*, *>)["farmScan"] as Map<*, *>
            val visited = linkedSetOf<CivPosition>()
            repeat(12) {
                work.status shouldBe "fertilizing"
                visited.add(requireNotNull(work.focus)) shouldBe true
                runtime.validate(city, work)
                val known = scan()
                known["examined"] shouldBe plots.size
                work.changes.forEach { world.getBlockAt(it.position.x, it.position.y, it.position.z).blockData = Bukkit.createBlockData(it.after) }
                runtime.invalidate(worker.id)
                scan() shouldBe known
                // Two admitted slices suffice; wall-clock expiry is covered by the paused-field regression.
                work = (1..2).firstNotNullOfOrNull { runtime.pulse(listOf(city)); runtime.plan(city, worker) }
                    ?: error("Known care was lost after a native receipt")
            }
            val ripe = plots.last().let { it.copy(y = it.y + 1) }
            world.getBlockAt(ripe.x, ripe.y, ripe.z).blockData = Bukkit.createBlockData("minecraft:wheat[age=7]")
            repeat((plots.size + 15) / 16) { runtime.pulse(listOf(city)) }
            requireNotNull(runtime.plan(city, worker)).let {
                it.status shouldBe "working-field"
                it.focus shouldBe ripe
            }
        }
    }

    test("Auto miner excavates then installs its real ladder without inventory output or support charges") {
        withAutoWorksite("auto-mine", "miner", Material.STONE) { world, mine, initial, runtime ->
            var city = initial
            val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
            val bottom = CivsWorksiteLayouts.point(mine, plan.bottom)
            for (y in bottom.y..65) world.getBlockAt(bottom.x, y, bottom.z).blockData =
                Bukkit.createBlockData("minecraft:ladder[facing=${plan.facing},waterlogged=false]")
            val cut = nextAutoWork(runtime, city)
            cut.changes.single().after shouldBe "minecraft:air"
            cut.outputs shouldBe emptyMap()
            runtime.validate(city, cut)
            cut.changes.forEach { world.getBlockAt(it.position.x, it.position.y, it.position.z).blockData = Bukkit.createBlockData(it.after) }
            city = city.copy(minePlans = mapOf(mine.id.toString() to CivMinePlanState(CivsMinePlan.VERSION, requireNotNull(cut.miningStep))))
            runtime.invalidate(cut.workerId)
            val support = nextAutoWork(runtime, city)
            support.changes.single().after.startsWith("minecraft:ladder[") shouldBe true
            support.autonomousVisual shouldBe true
            support.inputs shouldBe emptyMap()
            support.outputs shouldBe emptyMap()
            support.equipmentAfter shouldBe null
            runtime.validate(city, support)
            // A changed protected object is still rejected before native mutation.
            val point = support.changes.single().position
            world.getBlockAt(point.x, point.y, point.z).type = Material.CHEST
            shouldThrow<CivsRejection> { runtime.validate(city, support) }
            city.workers shouldBe initial.workers
            city.stock shouldBe initial.stock
        }
    }

    test("sealed Auto field replay rechecks real occupancy and world drift without a live planner cache") {
        withAutoWorksite("auto-replay", "farmer", Material.DIRT) { world, farm, city, runtime ->
            val soil = CivsWorksiteLayouts.field(farm).first()
            val worker = city.workers.single()
            world.getBlockAt(soil.x, soil.y, soil.z).type = Material.DIRT
            val work = CivWorldWork(worker.id, worker.completedCycles, farm.id, farm.entrance,
                listOf(CivPlanBlock(soil, "minecraft:dirt", "minecraft:farmland[moisture=7]")),
                status = "working-field", autonomousVisual = true)
            runtime.mutationBlocked = { _, _ -> true }
            shouldThrow<CivsRejection> { runtime.validateAutonomousVisualReceiptNative(city, work) }.reason shouldBe "worksite-occupied"
            world.getBlockAt(soil.x, soil.y, soil.z).type shouldBe Material.DIRT
            runtime.mutationBlocked = { _, _ -> false }
            runtime.validateAutonomousVisualReceiptNative(city, work)
            world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=4]")
            runtime.invalidate(worker.id)
            runtime.validateAutonomousVisualReceiptNative(city, work)
            world.getBlockAt(soil.x, soil.y, soil.z).type = Material.DIAMOND_BLOCK
            shouldThrow<CivsRejection> { runtime.validateAutonomousVisualReceiptNative(city, work) }.reason shouldBe "world-changed"
        }
    }

    test("Auto protected domains contain every generated mine cell and field crop but exclude unrelated ground") {
        for (style in listOf("nordic", "medievaloak", "caledonia")) for (rotation in listOf(0, 90, 180, 270)) {
            val mine = spatialBuilding(style, "miner", 1, CivPosition(10, 63, -10), rotation)
            val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
            val boxes = CivsAutonomousWorksites.protectionBounds(mine)
            fun covered(point: CivPosition) = boxes.any { it.contains(point.x, point.y, point.z) }
            plan.steps.forEach { withClue("$style/$rotation ${it.point}") { covered(CivsWorksiteLayouts.point(mine, it.point)) shouldBe true } }
            covered(CivsWorksiteLayouts.point(mine, plan.center.copy(x = plan.center.x + 100))) shouldBe false
            covered(CivsWorksiteLayouts.point(mine, plan.center.copy(y = plan.lowest - 10))) shouldBe false
            val farm = spatialBuilding(style, "farmer", 1, CivPosition(10, 63, -10), rotation)
            val field = CivsAutonomousWorksites.protectionBounds(farm)
            CivsWorksiteLayouts.field(farm).forEach { soil ->
                field.any { it.contains(soil.x, soil.y, soil.z) && it.contains(soil.x, soil.y + 1, soil.z) } shouldBe true
                field.any { it.contains(soil.x, soil.y - 1, soil.z) } shouldBe false
            }
        }
    }
})

private fun nextAutoWork(runtime: CivsWorksites, city: Settlement): CivWorldWork {
    repeat(256) {
        runtime.pulse(listOf(city))
        runtime.plan(city, city.workers.single())?.let { return it }
    }
    error("Auto planner blocked: ${runtime.idleReason(city, city.workers.single())}")
}

private fun withAutoWorksite(name: String, role: String, ground: Material,
    nanoTime: () -> Long = System::nanoTime,
    body: (CivsWorksiteWorldFixture, CivBuilding, Settlement, CivsWorksites) -> Unit) {
    failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture(name, ground).also(paper.server::addWorld)
            val plugin = paper.createSimplePlugin("AutoWorksite")
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            try {
                for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                val building = spatialBuilding("medievaloak", role, 1, CivPosition(0, 63, 0))
                val worker = CivWorker(UUID.randomUUID(), "Рабочий", building.id, nextDeliveryAt = 60_000)
                val city = spatialCity(world.uid, listOf(building), worker).copy(inventory = null,
                    autonomy = CivAutonomy(), stock = Stock(7, 8, 9))
                CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                    val runtime = CivsWorksites(plugin, access, { _, _ -> error("Auto visual work requested native inventory supplies") },
                        { _, _ -> false }, nanoTime = nanoTime)
                    body(world, building, city, runtime)
                }
            } finally { tasks.close() }
        }
    }
}
