package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.structure.StructureRotation
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation

class CivsWorksiteContextTest : FunSpec({
    for (liquid in listOf(false, true)) test("the former V5 step 1818 target observes a protected neighbour with liquid $liquid without modifying it") {
        contextWorld("mine-1818-$liquid", Material.STONE) { world, runtime, access ->
            val mine = spatialBuilding("caledonia", "miner", 1, CivPosition(-1844, 70, -2696), 180).copy(
                bounds = CivBox(CivPosition(-1853, 65, -2695), CivPosition(-1835, 87, -2677)),
                entrance = CivPosition(-1844, 71, -2696), control = CivPosition(-1844, 71, -2689))
            val forest = spatialBuilding("nordic", "lumberjack", 1, CivPosition(-1870, 63, -2689)).copy(
                bounds = CivBox(CivPosition(-1876, 63, -2695), CivPosition(-1864, 72, -2683)))
            val worker = spatialWorker(mine, "IRON_PICKAXE")
            val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
            val physicalTarget = CivPosition(19, -9, -10)
            // Current plans extend the shaft before the galleries; preserve this physical hazard,
            // not the historical V5 ordinal of the excavation.
            val targetOrdinal = geometry.steps.indexOfFirst { it.point == physicalTarget && it.after == null }
            check(targetOrdinal >= 0) { "Current mine plan lost the protected-neighbour target $physicalTarget" }
            var city = spatialCity(world.uid, listOf(mine, forest), worker).copy(
                center = mine.anchor, radius = 80,
                minePlans = mapOf(mine.id.toString() to CivMinePlanState(CivsMinePlan.VERSION, targetOrdinal.toLong())))
            access(city)
            for (x in -119..-112) for (z in -172..-165) world.loadChunk(x, z)
            geometry.steps[targetOrdinal].point shouldBe physicalTarget
            // This hazard-observation regression has genuine deep cover. The separate
            // sparse-section regression retains the live shallow surface and defers it.
            seedMineNaturalCover(world, mine)
            seedContextMine(world, mine, targetOrdinal)
            // This hazard fixture supplies the completed access in both its proof and native cells.
            city = city.copy(minePlans = mapOf(mine.id.toString() to seedMinePaidSpine(world, mine,
                city.minePlans!!.getValue(mine.id.toString()))))
            access(city)
            val target = CivPosition(-1863, 61, -2686)
            val neighbour = CivPosition(-1864, 61, -2686)
            world.getBlockAt(neighbour.x, neighbour.y, neighbour.z).type = Material.STONE
            // The support reserve remains protected: this is an observation, not
            // permission to dig a neighbour's three foundation layers.
            CivsMinePlan.belowFoundation(forest, neighbour.y) shouldBe false
            repeat(80) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val work = withClue(runtime.debug()) { requireNotNull(runtime.plan(city, worker)) }
            work.changes.single().position shouldBe target
            work.changes.single().after shouldBe "minecraft:air"
            runtime.validate(city, work)
            val autoCity = city.copy(inventory = null, autonomy = CivAutonomy())
            val visual = CivsAutonomousWorksites.visual(autoCity, mine, work)
            runtime.validateAutonomousVisualReceiptNative(autoCity, visual)
            if (liquid) {
                world.getBlockAt(neighbour.x, neighbour.y, neighbour.z).type = Material.WATER
                shouldThrow<CivsRejection> { runtime.validate(city, work) }.reason shouldBe "stale-worksite"
                shouldThrow<CivsRejection> { runtime.validateAutonomousVisualReceiptNative(autoCity, visual) }.reason shouldBe "stale-worksite"
                runtime.changed(world.uid, neighbour)
                runtime.invalidate(worker.id)
                repeat(160) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
                // A blocked upper section may defer; it must never cut the wet target or neighbour.
                runtime.plan(city, worker)?.changes.orEmpty().none { it.position == target || it.position == neighbour } shouldBe true
            } else {
                // Both a real foundation layer and its reserved support below remain
                // forbidden mutation targets even though neighbouring hazard reads work.
                for (bottom in listOf(target.y, target.y + 2)) {
                    val protected = autoCity.copy(buildings = autoCity.buildings.map {
                        if (it.id != forest.id) it else it.copy(bounds = it.bounds.copy(
                            min = it.bounds.min.copy(y = bottom), max = it.bounds.max.copy(x = target.x)))
                    })
                    shouldThrow<CivsRejection> { runtime.validateAutonomousVisualReceiptNative(protected, visual) }
                        .reason shouldBe "protected-area"
                }
                // The exact same read boundary is required at final admission.
                world.getBlockAt(neighbour.x, neighbour.y, neighbour.z).type = Material.WATER
                shouldThrow<CivsRejection> { runtime.validate(city, work) }.reason shouldBe "stale-worksite"
                shouldThrow<CivsRejection> { runtime.validateAutonomousVisualReceiptNative(autoCity, visual) }.reason shouldBe "stale-worksite"
                world.getBlockAt(neighbour.x, neighbour.y, neighbour.z).type = Material.STONE
                runtime.validate(city, work)
            }
            world.getBlockAt(target.x, target.y, target.z).type shouldBe Material.STONE
            world.getBlockAt(neighbour.x, neighbour.y, neighbour.z).type shouldBe if (liquid) Material.WATER else Material.STONE
        }
    }

    test("a real resident's first rejected forestry stance tries another stance at the same tree") {
        contextWorld("forest-real-feet", Material.DIRT) { world, runtime, access ->
            val mill = spatialBuilding("nordic", "lumberjack", 1, CivPosition(0, 63, 0)).copy(production = "OAK_LOG")
            val worker = spatialWorker(mill, "IRON_AXE").copy(items = null, inventory = null)
            val city = spatialCity(world.uid, listOf(mill), worker).copy(inventory = null, autonomy = CivAutonomy())
            access(city)
            val root = CivPosition(10, 64, 10)
            smallContextOak(world, root)
            // A real actor outside remote reach makes the ordinary alternative path observable.
            runtime.residentFeet = { Location(world, 10.5, 64.0, -9.5) }
            val first = contextNext(runtime, city)
            first.focus shouldBe root
            runtime.rejectStanding(city, worker, first) shouldBe true
            val second = contextNext(runtime, city)
            second.focus shouldBe first.focus
            (second.standing != first.standing) shouldBe true
            second.copy(standing = first.standing) shouldBe first
            runtime.validate(city, second)
            (contextEntry(runtime, worker.id)["deferredForestApproaches"] as Map<*, *>)["total"] shouldBe 0
        }
    }

    test("completed forest coverage survives supplies cycles and repeated scattered planting") {
        contextWorld("forest-retained-planting", Material.DIRT) { world, runtime, access ->
            val mill = spatialBuilding("nordic", "lumberjack", 1, CivPosition(0, 63, 0)).copy(production = "BIRCH_LOG")
            var worker = spatialWorker(mill, "IRON_AXE").copy(items = null, inventory = null)
            var city = spatialCity(world.uid, listOf(mill), worker).copy(inventory = null, autonomy = CivAutonomy(), stock = Stock(20))
            access(city)
            // Native path surface inside the planting envelope, without an authored road.
            for (z in -25..15) world.getBlockAt(17, 63, z).type = Material.DIRT_PATH
            val planted = mutableListOf<CivPosition>()
            var actions = 0
            while (planted.size < 3 && actions < 9) {
                val work = contextNext(runtime, city)
                runtime.validate(city, work)
                val search = contextEntry(runtime, worker.id)["forestSearch"] as Map<*, *>
                search["column"] shouldBe search["columns"]
                work.durationMillis shouldBe if (work.status == "watering") 4_000L else 3_000L
                if (work.status == "planting-tree") {
                    val point = work.changes.single().position
                    (kotlin.math.abs(point.x - 17) > CivsForestry.crownRadius("BIRCH_LOG")) shouldBe true
                    for (previous in planted) {
                        (kotlin.math.abs(point.x - previous.x) > 3 || kotlin.math.abs(point.z - previous.z) > 3) shouldBe true
                    }
                    planted += point
                }
                work.changes.forEach { cell -> world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
                    .blockData = Bukkit.createBlockData(cell.after) }
                city = CivsRules.finishWorldWork(city, work, 1_000L + actions * 5_000)
                worker = city.workers.single()
                access(city)
                runtime.invalidate(worker.id, completedWork = work)
                val retained = contextEntry(runtime, worker.id)["forestSearch"] as Map<*, *>
                retained["column"] shouldBe retained["columns"]
                actions++
            }
            planted.size shouldBe 3
            city.stock.wood shouldBe 17L
            worker.completedCycles shouldBe actions.toLong()
            runtime.forgetWorld(world.uid)
            (runtime.debug(setOf(worker.id))["entries"] as List<*>).isEmpty() shouldBe true
        }
    }

    for (units in listOf(128, 192)) test("retained planting gets a bounded action when the column quota exceeds the forest budget $units") {
        contextWorld("forest-column-budget-$units", Material.DIRT,
            settings = CivsWorksiteSettings(forestWorkUnitsPerPulse = units, maximumForestColumnsPerPulse = 10_000)) { world, runtime, access ->
            val mill = spatialBuilding("nordic", "lumberjack", 1, CivPosition(0, 63, 0)).copy(
                id = java.util.UUID(0, 1001), production = "BIRCH_LOG")
            val worker = spatialWorker(mill, "IRON_AXE").copy(items = null, inventory = null)
            val city = spatialCity(world.uid, listOf(mill), worker).copy(
                inventory = null, autonomy = CivAutonomy(), stock = Stock(20))
            access(city)
            // The initial field sweep must finish, then its retained safe site earns
            // native validation instead of losing every pulse to a rolling re-scan.
            repeat(300) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val work = withClue(runtime.debug(setOf(worker.id))) { requireNotNull(runtime.plan(city, worker)) }
            work.status shouldBe "planting-tree"
            val search = contextEntry(runtime, worker.id)["forestSearch"] as Map<*, *>
            search["column"] shouldBe search["columns"]
            runtime.validate(city, work)
            // Selection alone must not mutate the field, spend supplies, or credit a cycle.
            world.getBlockAt(work.focus!!.x, work.focus.y, work.focus.z).type shouldBe Material.AIR
            city.stock.wood shouldBe 20L
            worker.completedCycles shouldBe 0L
        }
    }

    test("metadata-only protection removal reopens a tree while supply and revision updates retain coverage") {
        contextWorld("forest-protection-metadata", Material.DIRT) { world, runtime, access ->
            val mill = spatialBuilding("nordic", "lumberjack", 1, CivPosition(0, 63, 0)).copy(production = "OAK_LOG")
            var worker = spatialWorker(mill, "IRON_AXE").copy(items = null, inventory = null)
            var city = spatialCity(world.uid, listOf(mill), worker).copy(inventory = null, autonomy = CivAutonomy(), stock = Stock(20))
            access(city)
            val firstRoot = CivPosition(-15, 64, -26)
            val otherRoot = CivPosition(10, 64, -26)
            smallContextOak(world, firstRoot)
            smallContextOak(world, otherRoot)
            val first = contextNext(runtime, city)
            first.focus shouldBe firstRoot
            runtime.rejectStanding(city, worker, first) shouldBe true
            val blocker = spatialBuilding("nordic", "lumberjack", 1, firstRoot.copy(y = 63)).copy(
                bounds = CivBox(firstRoot.copy(y = 63), firstRoot.copy(y = 70)), entrance = firstRoot, control = firstRoot)
            city = city.copy(buildings = city.buildings + blocker)
            access(city)
            val other = contextNext(runtime, city)
            other.focus shouldBe otherRoot
            val before = contextEntry(runtime, worker.id)["forestSearch"] as Map<*, *>
            city = city.copy(revision = city.revision + 17, stock = Stock(21))
            access(city)
            repeat(4) { runtime.pulse(listOf(city)) }
            runtime.plan(city, worker) shouldBe other
            val retained = contextEntry(runtime, worker.id)["forestSearch"] as Map<*, *>
            retained["column"] shouldBe before["column"]
            retained["mature"] shouldBe before["mature"]
            // No changed() callback and no native mutation: only protection metadata changes.
            city = city.copy(buildings = city.buildings.filterNot { it.id == blocker.id })
            access(city)
            runtime.invalidate(worker.id)
            var reopened = contextNext(runtime, city)
            // A known unaffected tree may retain priority while the touched column
            // is refreshed. Completing that one real action must expose the reopened root.
            if (reopened.focus == otherRoot) {
                runtime.validate(city, reopened)
                reopened.changes.forEach { cell -> world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
                    .blockData = Bukkit.createBlockData(cell.after) }
                city = CivsRules.finishWorldWork(city, reopened, 10_000)
                worker = city.workers.single()
                access(city)
                runtime.invalidate(worker.id, completedWork = reopened)
                reopened = contextNext(runtime, city)
            }
            reopened.focus shouldBe firstRoot
            runtime.validate(city, reopened)
        }
    }

    test("an operation lock released without a terrain event retries only its tree and retains forest coverage") {
        val root = CivPosition(-15, 64, -26)
        val blocked = root.copy(y = root.y + 1)
        var denied = true
        contextWorld("forest-transient-observation", Material.DIRT, locked = { denied && it == blocked }) { world, runtime, access ->
            val mill = spatialBuilding("nordic", "lumberjack", 1, CivPosition(0, 63, 0)).copy(production = "OAK_LOG")
            val worker = spatialWorker(mill, "IRON_AXE").copy(items = null, inventory = null)
            val city = spatialCity(world.uid, listOf(mill), worker).copy(inventory = null, autonomy = CivAutonomy(),
                forestrySeeded = setOf(mill.id.toString()))
            access(city)
            smallContextOak(world, root)
            repeat(300) { runtime.pulse(listOf(city)) }
            runtime.plan(city, worker) shouldBe null
            val before = contextEntry(runtime, worker.id)["forestSearch"] as Map<*, *>
            before["column"] shouldBe before["columns"]
            before["unsuitable"] shouldBe 0
            before["retryRoots"] shouldBe 1
            denied = false
            repeat(4) { runtime.pulse(listOf(city)); runtime.plan(city, worker) shouldBe null }
            (Bukkit.getScheduler() as org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock).performTicks(100)
            val work = contextNext(runtime, city)
            work.focus shouldBe root
            work.status shouldBe "felling-tree"
            val after = contextEntry(runtime, worker.id)["forestSearch"] as Map<*, *>
            after["column"] shouldBe before["column"]
            after["retryRoots"] shouldBe 0
            runtime.validate(city, work)
        }
    }

    test("an observed log without a natural crown remains structurally unsuitable after the retry interval") {
        contextWorld("forest-structural-observation", Material.DIRT) { world, runtime, access ->
            val mill = spatialBuilding("nordic", "lumberjack", 1, CivPosition(0, 63, 0)).copy(production = "OAK_LOG")
            val worker = spatialWorker(mill, "IRON_AXE").copy(items = null, inventory = null)
            val city = spatialCity(world.uid, listOf(mill), worker).copy(inventory = null, autonomy = CivAutonomy(),
                forestrySeeded = setOf(mill.id.toString()))
            access(city)
            world.getBlockAt(-15, 64, -26).type = Material.OAK_LOG
            repeat(300) { runtime.pulse(listOf(city)) }
            val before = contextEntry(runtime, worker.id)["forestSearch"] as Map<*, *>
            before["unsuitable"] shouldBe 1
            before["retryRoots"] shouldBe 0
            (Bukkit.getScheduler() as org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock).performTicks(200)
            repeat(30) { runtime.pulse(listOf(city)) }
            runtime.plan(city, worker) shouldBe null
            val after = contextEntry(runtime, worker.id)["forestSearch"] as Map<*, *>
            after["unsuitable"] shouldBe 1
            after["retryRoots"] shouldBe 0
        }
    }

    for (temporary in listOf(true, false)) test("planting distinguishes a ${if (temporary) "temporary lock" else "native path"} from a permanently unavailable site") {
        val root = CivPosition(-15, 64, -26)
        val neighbour = root.copy(x = root.x + 1, y = root.y - 1)
        var denied = temporary
        contextWorld("forest-plant-observation-$temporary", Material.DIRT, locked = { denied && it == neighbour }) { world, runtime, access ->
            val mill = spatialBuilding("nordic", "lumberjack", 1, CivPosition(0, 63, 0)).copy(production = "BIRCH_LOG")
            val worker = spatialWorker(mill, "IRON_AXE").copy(items = null, inventory = null)
            val city = spatialCity(world.uid, listOf(mill), worker).copy(inventory = null, autonomy = CivAutonomy(), stock = Stock(20))
            access(city)
            // Keep one eligible soil site, so recovery must revisit this same candidate.
            val bounds = CivsWorksiteLayouts.site(mill)!!.getAsJsonObject("forester").getAsJsonObject("searchBounds")
            val min = CivsWorksiteLayouts.vector(bounds["min"])
            val max = CivsWorksiteLayouts.vector(bounds["max"])
            for (x in min.x..max.x) for (z in min.z..max.z) {
                val point = CivsWorksiteLayouts.point(mill, CivPosition(x, 1, z))
                if (point != root) world.getBlockAt(point.x, 63, point.z).type = Material.STONE
            }
            if (!temporary) world.getBlockAt(neighbour.x, neighbour.y, neighbour.z).type = Material.DIRT_PATH
            repeat(300) { runtime.pulse(listOf(city)) }
            runtime.plan(city, worker) shouldBe null
            val before = contextEntry(runtime, worker.id)["forestSearch"] as Map<*, *>
            before["column"] shouldBe before["columns"]
            before["unplantable"] shouldBe if (temporary) 0 else 1
            before["retryRoots"] shouldBe if (temporary) 1 else 0
            denied = false
            repeat(4) { runtime.pulse(listOf(city)); runtime.plan(city, worker) shouldBe null }
            (Bukkit.getScheduler() as org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock).performTicks(100)
            if (temporary) {
                val work = contextNext(runtime, city)
                work.status shouldBe "planting-tree"
                work.focus shouldBe root
                val after = contextEntry(runtime, worker.id)["forestSearch"] as Map<*, *>
                after["column"] shouldBe before["column"]
                after["retryRoots"] shouldBe 0
                runtime.validate(city, work)
            } else {
                repeat(30) { runtime.pulse(listOf(city)); runtime.plan(city, worker) shouldBe null }
                val after = contextEntry(runtime, worker.id)["forestSearch"] as Map<*, *>
                after["unplantable"] shouldBe 1
                after["retryRoots"] shouldBe 0
            }
        }
    }

    test("an incomplete forest sweep reports searching rather than waiting for growth") {
        contextWorld("forest-truthful-yield", Material.DIRT) { world, runtime, access ->
            val mill = spatialBuilding("nordic", "lumberjack", 1, CivPosition(0, 63, 0)).copy(production = "OAK_LOG")
            val worker = spatialWorker(mill, "IRON_AXE").copy(items = null, inventory = null)
            val city = spatialCity(world.uid, listOf(mill), worker).copy(inventory = null, autonomy = CivAutonomy())
            access(city)
            runtime.pulse(listOf(city))
            runtime.plan(city, worker) shouldBe null
            runtime.idleReason(city, worker) shouldBe "searching-work"
            val search = contextEntry(runtime, worker.id)["forestSearch"] as Map<*, *>
            ((search["column"] as Int) < (search["columns"] as Int)) shouldBe true
        }
    }
})

private fun contextWorld(name: String, ground: Material, locked: (CivPosition) -> Boolean = { false },
    settings: CivsWorksiteSettings = CivsWorksiteSettings(),
    scenario: (CivsWorksiteWorldFixture, CivsWorksites, (Settlement) -> Unit) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
        val world = CivsWorksiteWorldFixture(name, ground).also(paper.server::addWorld)
        val plugin = paper.createSimplePlugin("RetainedWorksites")
        val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
        var cities = emptyList<Settlement>()
        try {
            for (x in -4..4) for (z in -4..4) world.loadChunk(x, z)
            CivsWorld(plugin, tasks) { cities }.use { access ->
                val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, point -> locked(point) },
                    nanoTime = { 0L }, settings = settings)
                scenario(world, runtime) { cities = listOf(it) }
            }
        } finally { tasks.close() }
    }
}

private fun contextNext(runtime: CivsWorksites, city: Settlement): CivWorldWork {
    val worker = city.workers.single()
    repeat(2_000) {
        runtime.pulse(listOf(city))
        runtime.plan(city, worker)?.let { return it }
    }
    error("No work: ${runtime.debug(setOf(worker.id))}")
}

private fun contextEntry(runtime: CivsWorksites, workerId: java.util.UUID): Map<*, *> =
    (runtime.debug(setOf(workerId))["entries"] as List<*>).single() as Map<*, *>

private fun smallContextOak(world: CivsWorksiteWorldFixture, root: CivPosition) {
    for (dy in 0..2) world.getBlockAt(root.x, root.y + dy, root.z).type = Material.OAK_LOG
    world.getBlockAt(root.x, root.y + 3, root.z).blockData = Bukkit.createBlockData(
        "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]")
}

private fun seedContextMine(world: CivsWorksiteWorldFixture, mine: CivBuilding, prefix: Int) {
    val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
    fun data(state: String) = Bukkit.createBlockData(state).also {
        if (!it.material.isAir) it.rotate(StructureRotation.CLOCKWISE_180)
    }
    for (step in geometry.steps.take(prefix)) {
        val point = CivsWorksiteLayouts.point(mine, step.point)
        world.getBlockAt(point.x, point.y, point.z).blockData = data(step.after ?: "minecraft:air")
    }
    val bottom = CivsWorksiteLayouts.point(mine, geometry.bottom)
    for (dy in 0..4) {
        world.getBlockAt(bottom.x, bottom.y + dy, bottom.z).blockData =
            data(if (dy <= 2) "minecraft:ladder[facing=${geometry.facing},waterlogged=false]" else "minecraft:air")
        val backing = CivsWorksiteLayouts.point(mine, geometry.backing).copy(y = bottom.y + dy)
        world.getBlockAt(backing.x, backing.y, backing.z).type = Material.COBBLESTONE
    }
}
