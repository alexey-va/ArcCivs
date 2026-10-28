package ru.arc.civs.scenarios

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.HeightMap
import org.bukkit.Material
import org.bukkit.plugin.Plugin
import ru.arc.civs.*
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executor

/** Controlled subsurface model: actual public surface/blueprint and V5 paid progress,
 * generated natural stone below the public window. Native receipts are real;
 * resident movement and hidden player changes are outside this model. */
class Auto2MineCoverScenarioTest : FunSpec({
    val savedState = System.getenv("CIVS_SCENARIO_MINE_STATE")
    test("the Auto2 shallow V5 mine finds new safely covered work after its compact branches are exhausted")
        .config(enabled = savedState != null) {
            withAuto2MineCoverModel(requireNotNull(savedState)) {
                var admitted: CivWorldWork? = null
                repeat(480) {
                    if (admitted == null) {
                        worksites.pulse(listOf(city))
                        admitted = worksites.plan(city, worker)
                        paper.performTicks(5)
                    }
                }
                check(admitted != null) {
                    "No safely covered work after 480 production five-tick pulses; " +
                        "source state revision=${sourceRevision}, source V5=$before, " +
                        "floorFeet=${plan.floors.map { CivsWorksiteLayouts.point(mine, it.cells.first().copy(y = it.feetY)).y }}, " +
                        "controlled natural-stone continuation below public Y56; ${worksites.debug(setOf(worker.id))}"
                }
                val work = requireNotNull(admitted)
                work.autonomousVisual shouldBe true
                work.outputs shouldBe emptyMap()
                worksites.validate(city, work)
                city.stock.food shouldBe 1
                immutableRoofs.forEach { (p, state) -> world.getBlockAt(p.x, p.y, p.z).blockData.asString shouldBe state }
            }
        }

    test("accepted native V6 actions survive restart and reach a new full-cover gallery without economic credit")
        .config(enabled = savedState != null) {
            withAuto2MineCoverModel(requireNotNull(savedState)) {
                var current = city
                var runtime = worksites
                val currentPlan = requireNotNull(CivsMinePlan.forBuilding(mine, 6))
                val key = mine.id.toString()
                val frozen = CivMineV5Proof(before.nextStep, before.sectionSteps, before.excavation, before.seals)
                val retainedSpine = plan.sections.getValue("spine").filter {
                    plan.completed(it, before.nextStep, before.sectionSteps.orEmpty())
                }.map { CivsWorksiteLayouts.point(mine, plan.steps[it].point) }.associateWith { p ->
                    world.getBlockAt(p.x, p.y, p.z).blockData.asString
                }
                val directory = Files.createTempDirectory("auto2-deep-mine-receipts-")
                var reached = false
                var actions = 0
                val deniedSections = linkedMapOf<String, String>()
                var lastAccepted: String? = null
                var lowest = mine.anchor.y + plan.lowest
                val paidBlocks = plan.positions.associate { local ->
                    val point = CivsWorksiteLayouts.point(mine, local)
                    point to world.getBlockAt(point.x, point.y, point.z).blockData.asString
                }.toMutableMap()
                try {
                    CivsStore(directory, Executor(Runnable::run)).use { store ->
                        store.save(current).join()
                        CivsStorage(plugin) { emptyArray() }.use { storage ->
                            storage.validateAutonomousVisualNative = { transaction ->
                                val receipt = requireNotNull(transaction.autonomousVisual)
                                runtime.validateAutonomousVisualReceiptNative(receipt.before(transaction.after), receipt.work)
                                null
                            }
                            while (actions < 1_500 && !reached) {
                                val worker = current.workers.single()
                                var work: CivWorldWork? = null
                                repeat(480) {
                                    if (work == null) {
                                        runtime.pulse(listOf(current))
                                        work = runtime.plan(current, worker)
                                        mineSectionDenial(runtime, mine)?.let { (section, denial) -> deniedSections[section] = denial }
                                        paper.performTicks(5)
                                    }
                                }
                                val accepted = requireNotNull(work) {
                                    "Deepening stopped after $actions accepted native actions; lowest=$lowest; " +
                                        "state=${current.minePlans}; lastAccepted=$lastAccepted; " +
                                        "deepestLandingCover=${mineLandingCover(world, mine, currentPlan)}; " +
                                        "deniedSections=$deniedSections; debug=${runtime.debug(setOf(worker.id))}"
                                }
                                accepted.miningPlanVersion shouldBe 6
                                accepted.miningGenerator shouldBe false
                                accepted.autonomousVisual shouldBe true
                                accepted.inputs shouldBe emptyMap()
                                accepted.outputs shouldBe emptyMap()
                                runtime.validate(current, accepted)
                                lastAccepted = "status=${accepted.status}, focus=${accepted.focus}, ordinal=${accepted.miningOrdinal}, " +
                                    "miningExcavation=${accepted.miningExcavation}, changes=${accepted.changes}"
                                if (actions == 0) {
                                    val rung = CivsWorksiteLayouts.point(mine, plan.bottom.copy(y = plan.lowest))
                                    val block = world.getBlockAt(rung.x, rung.y, rung.z)
                                    val state = block.blockData.asString
                                    block.type = Material.AIR
                                    shouldThrow<CivsRejection> { runtime.validate(current, accepted) }.reason shouldBe "stale-worksite"
                                    block.blockData = Bukkit.createBlockData(state)
                                    runtime.validate(current, accepted)
                                    current shouldBe city
                                }
                                if (actions == 0) println("Auto2 V6 first native work: status=${accepted.status} " +
                                    "focus=${accepted.focus}, change=${accepted.changes.single()}, standing=${accepted.standing}, " +
                                    "oldLowest=${mine.anchor.y + plan.lowest}")
                                val after = CivsRules.finishAutonomousVisual(current, accepted)
                                val receipt = CivAutonomousVisualReceipt(worker, current.stock, requireNotNull(current.autonomy),
                                    current.mineProgress, current.minePlans, accepted, actions.toLong(), current.id,
                                    current.worldId, current.owner, mine)
                                val transaction = CivInventoryTransaction(UUID.randomUUID(), current.id, current.revision,
                                    after, emptyList(), worldChanges = accepted.changes, autonomousVisual = receipt).validated()
                                store.saveInventoryTransaction(transaction).join()
                                val restored = store.loadInventoryTransactions().join().single().validated()
                                storage.lock(restored)
                                repeat(2) { storage.reconcile(restored) shouldBe CivInventoryApplyResult.Applied }
                                accepted.changes.forEach { cell ->
                                    world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.after
                                    if (cell.position in paidBlocks) paidBlocks[cell.position] = cell.after
                                    lowest = minOf(lowest, cell.position.y)
                                }
                                current = restored.after
                                current.workers shouldBe city.workers
                                current.stock shouldBe city.stock
                                current.stock.food shouldBe 1
                                current.minePlans!!.getValue(key).v5Proof shouldBe frozen
                                current.minePlans!!.getValue(key).v5Step shouldBe before.v5Step
                                CivsMineProgress.v5State(current.minePlans!!.getValue(key)) shouldBe before
                                store.save(current).join() shouldBe current
                                store.deleteInventoryTransaction(restored.id).join() shouldBe true
                                storage.unlock(restored.id)
                                runtime.invalidate(worker.id, accepted)
                                actions++
                                if (actions == 2) {
                                    current = store.load().join().single()
                                    current.minePlans!!.getValue(key).v5Proof shouldBe frozen
                                    runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, nanoTime = { 0L })
                                }
                                val ordinal = requireNotNull(accepted.miningOrdinal)
                                val step = currentPlan.steps[ordinal]
                                val local = CivsMinePlan.localPoint(mine, accepted.changes.single().position)
                                reached = accepted.status == "digging-mine" && accepted.changes.single().after == "minecraft:air" &&
                                    step.stage == "gallery" && (step.floor ?: 0) > plan.floors.size &&
                                    CivsMineSafety.requiredCover(currentPlan, ordinal, local) == CivsMineSafety.FULL_COVER
                                if (reached) {
                                    val point = accepted.changes.single().position
                                    val cover = CivMineCover(world.uid, mine.id, point.x, point.z,
                                        world.getHighestBlockYAt(point.x, point.z, HeightMap.MOTION_BLOCKING_NO_LEAVES), world.minHeight)
                                    while (!cover.finished) cover.step { y -> world.getBlockAt(point.x, y, point.z).type }
                                    val ceiling = mine.anchor.y + CivsMineSafety.ceiling(currentPlan, ordinal, local)
                                    (requireNotNull(cover.ground) - ceiling >= 16) shouldBe true
                                    println("Auto2 V6 deep native gallery: actions=$actions, lowest=$lowest, point=$point, " +
                                        "floor=${step.floor}, naturalCover=${cover.ground!! - ceiling}, proof=${current.minePlans!![key]}")
                                }
                                retainedSpine.forEach { (p, state) -> world.getBlockAt(p.x, p.y, p.z).blockData.asString shouldBe state }
                                immutableRoofs.forEach { (p, state) -> world.getBlockAt(p.x, p.y, p.z).blockData.asString shouldBe state }
                            }
                            check(reached) { "No full-cover new V6 gallery after $actions native actions; ${runtime.debug()}" }
                            // Native commits may change only their explicit paid cells, including after restart/replay.
                            paidBlocks.forEach { (p, state) -> world.getBlockAt(p.x, p.y, p.z).blockData.asString shouldBe state }
                            store.loadInventoryTransactions().join() shouldBe emptyList()
                            store.load().join().single() shouldBe current
                        }
                    }
                } finally { directory.toFile().deleteRecursively() }
            }
        }
})

/** Read only the existing execution diagnostic; native selection and its limits stay untouched. */
private fun mineSectionDenial(runtime: CivsWorksites, mine: CivBuilding): Pair<String, String>? {
    val executions = CivsWorksites::class.java.getDeclaredField("mineExecutions").also { it.isAccessible = true }
        .get(runtime) as Map<*, *>
    val execution = executions[mine.id] ?: return null
    val section = execution.javaClass.getDeclaredField("lastDeniedSection").also { it.isAccessible = true }
        .get(execution) as? String ?: return null
    val denial = execution.javaClass.getDeclaredField("lastDenial").also { it.isAccessible = true }.get(execution)
    return section to denial.toString()
}

private fun mineLandingCover(world: CivsWorksiteWorldFixture, mine: CivBuilding, plan: CivsMinePlan): String {
    val ordinal = plan.sections.getValue("${plan.floors.last().index}/shaft/landing").first()
    val local = plan.steps[ordinal].point
    val point = CivsWorksiteLayouts.point(mine, local)
    val cover = CivMineCover(world.uid, mine.id, point.x, point.z,
        world.getHighestBlockYAt(point.x, point.z, HeightMap.MOTION_BLOCKING_NO_LEAVES), world.minHeight)
    while (!cover.finished) cover.step { y -> world.getBlockAt(point.x, y, point.z).type }
    val ceiling = mine.anchor.y + CivsMineSafety.ceiling(plan, ordinal, local)
    return "point=$point naturalGround=${cover.ground} ceiling=$ceiling cover=${cover.ground?.minus(ceiling)}"
}

private class Auto2MineCoverModel(val paper: MockBukkitTestRuntime, val world: CivsWorksiteWorldFixture,
    val plugin: Plugin, val access: CivsWorld, val city: Settlement, val worker: CivWorker, val mine: CivBuilding,
    val before: CivMinePlanState, val plan: CivsMinePlan, val immutableRoofs: Map<CivPosition, String>,
    val worksites: CivsWorksites, val sourceRevision: Long)

private fun withAuto2MineCoverModel(savedState: String, scenario: Auto2MineCoverModel.() -> Unit) {
    val root = Path.of(requireNotNull(System.getenv("CIVS_SCENARIO_PUBLIC_MAP")) {
        "CIVS_SCENARIO_PUBLIC_MAP is required for the explicitly requested mine scenario"
    }).toRealPath()
    val facts = Path.of(requireNotNull(System.getenv("CIVS_SCENARIO_NATIVE_FACTS"))).toRealPath()
    val envelope = CivBox(CivPosition(-1894, 56, -2721), CivPosition(-1826, 90, -2658))
    val publicMap = RecordedNativeMap.fromPublicRle(root, facts, envelope)
    val layout = JsonParser.parseString(Files.readString(root.resolve("layout.json"))).asJsonObject
    val surface = layout.getAsJsonObject("surface").getAsJsonArray("columns").associate { encoded ->
        val column = encoded.asJsonObject
        (column["x"].asInt to column["z"].asInt) to column
    }
    val saved = JsonParser.parseString(Files.readString(Path.of(requireNotNull(savedState)))).asJsonObject
    val payload = if (saved.has("result")) JsonParser.parseString(saved.getAsJsonObject("result")["stdout"].asString).asJsonObject else saved
    val gson = Gson()
    val mineSnapshot = if (payload.has("buildings")) payload.getAsJsonArray("buildings").single()
        else layout.getAsJsonArray("saved_plans").single {
            it.asJsonObject.getAsJsonObject("building_snapshot")["type"].asString == "QUARRY"
        }.asJsonObject["building_snapshot"]
    val sourceMine = gson.fromJson(mineSnapshot, CivBuilding::class.java)
    val before = gson.fromJson(payload["minePlan"], CivMinePlanState::class.java)
    before.version shouldBe 5
    val source = privateBlueprint(requireNotNull(System.getenv("CIVS_SCENARIO_SCHEMATICS_ROOT")),
        sourceMine.schematicId, "38b4a7dba7245e08db6929f30178d04f35af2d12fb8c803e64babd9ada3dd634")
    source.volume shouldBe 8303L

    failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = object : CivsWorksiteWorldFixture("auto2-shallow-cover-model", Material.STONE, publicMap::collision) {
                override fun getHighestBlockYAt(x: Int, z: Int): Int = getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING)
                override fun getHighestBlockYAt(x: Int, z: Int, heightMap: HeightMap): Int {
                    if (!publicMap.hasColumn(x, z)) return requireNotNull(surface[x to z]) {
                        "Missing actual heightfield for modeled mine column x=$x z=$z"
                    }["top_y"].asInt
                    return publicMap.column(x, z).first { p ->
                    val block = getBlockAt(p.x, p.y, p.z)
                    when (heightMap) {
                        HeightMap.WORLD_SURFACE -> !block.type.isAir
                        HeightMap.MOTION_BLOCKING -> block.type.isSolid || block.isLiquid
                        HeightMap.MOTION_BLOCKING_NO_LEAVES -> (block.type.isSolid || block.isLiquid) && !block.type.name.endsWith("_LEAVES")
                        else -> error("Unspecified cover-model height map $heightMap")
                    }
                    }.y
                }
            }.also(paper.server::addWorld)
            val buildings = layout.getAsJsonArray("saved_plans").map { entry ->
                gson.fromJson(entry.asJsonObject["building_snapshot"], CivBuilding::class.java).copy(phase = BuildingPhase.ACTIVE)
            }
            val mine = buildings.single { it.id == sourceMine.id }
            mine.anchor shouldBe sourceMine.anchor
            mine.bounds shouldBe sourceMine.bounds
            val worker = CivWorker(UUID.fromString("679ad6aa-2a43-4c17-89ba-16589232fed3"), "Регина", mine.id,
                completedCycles = 301, nextDeliveryAt = 1)
            val cityMeta = layout.getAsJsonObject("city")
            val city = CivsRules.validate(Settlement(UUID.fromString(cityMeta["id"].asString),
                UUID.fromString(cityMeta["owner"].asString), "Auto2 cover model", world.uid,
                gson.fromJson(cityMeta["center"], CivPosition::class.java), cityMeta["radius"].asInt,
                cityMeta["style"].asString, Stock(64, 64, 1), buildings, listOf(worker), autonomy = CivAutonomy(),
                minePlans = mapOf(mine.id.toString() to before)))
            System.getenv("CIVS_SCENARIO_CITY_STATE")?.let { privatePath ->
                val privateCity = gson.fromJson(Files.readString(Path.of(privatePath)), Settlement::class.java)
                privateCity.building(mine.id) shouldBe mine
                privateCity.minePlans!!.getValue(mine.id.toString()) shouldBe before
            }
            val plan = requireNotNull(CivsMinePlan.forBuilding(mine, before.version))
            val modeledColumns = plan.positions.flatMap { local ->
                val p = CivsWorksiteLayouts.point(mine, local)
                (-3..3).flatMap { dx -> (-3..3).map { dz -> p.x + dx to p.z + dz } }
            }.toSet()
            for ((x, z) in modeledColumns) {
                val column = surface[x to z]
                if (column == null) {
                    check(publicMap.hasColumn(x, z)) { "Missing actual surface for controlled natural-stone column $x,$z" }
                    continue // The later public overlay supplies this out-of-city observed column.
                }
                check(column["loaded"].asBoolean)
                world.loadChunk(x shr 4, z shr 4)
                val ground = column["ground_y"].asInt
                if (ground >= 64) for (y in 64..ground) world.getBlockAt(x, y, z).type = Material.STONE
                else for (y in ground + 1..63) world.getBlockAt(x, y, z).type = Material.AIR
            }
            // Paid prefix/section history constructs only its own executed geometry.
            for ((index, step) in plan.steps.withIndex()) if (plan.completed(index, before.nextStep, before.sectionSteps.orEmpty())) {
                val point = CivsWorksiteLayouts.point(mine, step.point)
                world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(plan.expectedAfter(index, mine.rotation))
            }
            RecordedNativeMap("/mine-regina-floor3-native.json",
                "9f29038a0e6495a53066411c378a5615232fbaef9434068c6597cfd8a3131bbc").seed(world)
            publicMap.loadedChunks.forEach { (x, z) -> world.loadChunk(x, z) }
            publicMap.seed(world)
            // Native .101 need debug identified this exact current target as STONE.
            world.getBlockAt(-1845, 53, -2696).type = Material.STONE
            val immutableRoofs = buildings.flatMap { b -> listOf(b.bounds.max, b.bounds.min) }
                .associateWith { p -> world.getBlockAt(p.x, p.y, p.z).blockData.asString }
            val plugin = paper.createSimplePlugin("Auto2MineCoverModel")
            LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
                CivsWorld(plugin, tasks, { 0L }) { listOf(city) }.use { access ->
                    val worksites = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, nanoTime = { 0L })
                    Auto2MineCoverModel(paper, world, plugin, access, city, worker, mine, before, plan,
                        immutableRoofs, worksites, payload["revision"].asLong).scenario()
                }
            }
        }
    }
}
