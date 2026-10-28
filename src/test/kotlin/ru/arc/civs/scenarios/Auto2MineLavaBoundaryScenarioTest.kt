package ru.arc.civs.scenarios

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import ru.arc.civs.*
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executor

/** Private-input planner reproduction, not resident navigation or native fluid simulation.
 * Paid cells and the supplied native windows override a modeled stone plane ending at Y63.
 * The schematic's three upper rungs are modeled explicitly. No private payload is tracked. */
class Auto2MineLavaBoundaryScenarioTest : FunSpec({
    val statePath = System.getenv("CIVS_MINE_CURRENT_STATE")
    val capturePath = System.getenv("CIVS_MINE_CURRENT_NATIVE")
    val captureSha = System.getenv("CIVS_MINE_CURRENT_NATIVE_SHA256")
    val enabled = statePath != null && capturePath != null && captureSha != null
    val continuationPaths = System.getenv("CIVS_MINE_CONTINUATION_NATIVE")?.split(',')
    val continuationShas = System.getenv("CIVS_MINE_CONTINUATION_NATIVE_SHA256")?.split(',')
    val continuationEnabled = enabled && continuationPaths != null && continuationShas != null
    fun source() = Gson().fromJson(Files.readString(Path.of(requireNotNull(statePath))), Settlement::class.java)

    test("the current sparse V6 plan has exactly one unfinished ready deep section")
        .config(enabled = enabled) {
            val city = source()
            val mine = city.buildings.single { it.type == BuildingType.QUARRY }
            val saved = city.minePlans!!.getValue(mine.id.toString())
            val plan = requireNotNull(CivsMinePlan.forBuilding(mine, saved.version))
            saved.version shouldBe 6
            saved.nextStep shouldBe 4L
            plan.floors.map { mine.anchor.y + it.feetY } shouldBe listOf(59, 55, 51, 47, 43, 39)
            plan.steps[1643].point shouldBe CivsMinePlan.localPoint(mine, CivPosition(-1842, 49, -2686))
            plan.section(1643) shouldBe "4/shaft/landing"
            CivsMineSafety.requiredCover(plan, 1643, plan.steps[1643].point) shouldBe 6
            mine.anchor.y + CivsMineSafety.ceiling(plan, 1643, plan.steps[1643].point) shouldBe 49
            val ready = plan.sections.keys.filter { section ->
                plan.sectionCount(section, saved.nextStep, saved.sectionSteps.orEmpty()) < plan.sections.getValue(section).size &&
                    plan.sectionReady(section, saved.nextStep, saved.sectionSteps.orEmpty())
            }
            ready shouldBe listOf("0/shaft/entrance", "1/shaft/landing", "2/shaft/landing", "3/shaft/landing",
                "4/shaft/landing", "5/shaft/landing", "6/gallery/main")
            plan.sectionCount("6/gallery/main", saved.nextStep, saved.sectionSteps.orEmpty()) shouldBe 33L
            plan.sections.getValue("6/gallery/main")[33] shouldBe 8750
        }

    test("captured V7 continuation preserves V6 proof and clears the neighbouring lava source without item credit")
        .config(enabled = continuationEnabled) {
            val original = source()
            val mine = original.buildings.single { it.type == BuildingType.QUARRY }
            val saved = original.minePlans!!.getValue(mine.id.toString())
            val plan = requireNotNull(CivsMinePlan.forBuilding(mine, saved.version))
            val worker = original.workers.single { it.assignment == mine.id }
            val bytes = Files.readAllBytes(Path.of(requireNotNull(capturePath)))
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } shouldBe captureSha
            val capture = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            capture["city"].asString shouldBe original.id.toString()
            capture["worker"].asString shouldBe worker.id.toString()
            capture["world"].asString shouldBe original.worldId.toString()
            capture["voxel_count"].asInt shouldBe 891
            capture["unavailable_count"].asInt shouldBe 0
            requireNotNull(continuationPaths).size shouldBe 2
            requireNotNull(continuationShas).size shouldBe 2
            val continuation = continuationPaths.zip(continuationShas).map { (path, sha) ->
                val nativeBytes = Files.readAllBytes(Path.of(path))
                MessageDigest.getInstance("SHA-256").digest(nativeBytes).joinToString("") { "%02x".format(it) } shouldBe sha
                JsonParser.parseString(nativeBytes.toString(Charsets.UTF_8)).asJsonObject.also {
                    it["city"].asString shouldBe original.id.toString()
                    it["worker"].asString shouldBe worker.id.toString()
                    it["world"].asString shouldBe original.worldId.toString()
                    it["voxel_count"].asInt shouldBe 891
                    it["unavailable_count"].asInt shouldBe 0
                }
            }
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("auto2-native-lava-boundary").also(paper.server::addWorld)
                // Only this worksite is scheduled; no Service, family, staffing or economic replay is claimed.
                val city = CivsRules.validate(original.copy(worldId = world.uid))
                for (x in -122..-110) for (z in -177..-161) world.loadChunk(x, z)
                fun seed(version: Int, prefix: Long, sections: Map<String, Long>) {
                    val geometry = requireNotNull(CivsMinePlan.forBuilding(mine, version))
                    geometry.steps.forEachIndexed { index, step -> if (geometry.completed(index, prefix, sections)) {
                        val point = CivsWorksiteLayouts.point(mine, step.point)
                        world.getBlockAt(point.x, point.y, point.z).blockData =
                            Bukkit.createBlockData(geometry.expectedAfter(index, mine.rotation))
                    } }
                }
                saved.v5Proof?.let { seed(5, it.nextStep, it.sectionSteps.orEmpty()) }
                seed(saved.version, saved.nextStep, saved.sectionSteps.orEmpty())
                saved.seals.orEmpty().forEach { (key, state) ->
                    val xyz = key.split(',').map(String::toInt)
                    val point = CivsWorksiteLayouts.point(mine, CivPosition(xyz[0], xyz[1], xyz[2]))
                    world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(state)
                }
                val rung = plan.sections.getValue("spine").first { plan.steps[it].after?.startsWith("minecraft:ladder[") == true }
                for (y in plan.bottom.y..plan.bottom.y + 2) {
                    val point = CivsWorksiteLayouts.point(mine, plan.bottom.copy(y = y))
                    val backing = CivsWorksiteLayouts.point(mine, plan.backing.copy(y = y))
                    world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(plan.expectedAfter(rung, mine.rotation))
                    world.getBlockAt(backing.x, backing.y, backing.z).type = Material.COBBLESTONE
                }
                seedNativeMineWindow(world, capture)
                continuation.forEach { seedNativeMineWindow(world, it) }
                val lava = world.getBlockAt(-1852, 41, -2687)
                val target = world.getBlockAt(-1851, 41, -2687)
                lava.blockData.asString shouldBe "minecraft:lava[level=0]"
                target.type shouldBe Material.STONE
                world.getBlockAt(-1851, 41, -2688).type shouldBe Material.AIR
                val localLava = CivsMinePlan.localPoint(mine, CivPosition(lava.x, lava.y, lava.z))
                (localLava in plan.plannedCuts) shouldBe true
                val plugin = paper.createSimplePlugin("Auto2MineLavaBoundary")
                LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        fun runtime() = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, nanoTime = { 0L })
                        fun advance(runtime: CivsWorksites, pulses: Int, snapshot: Settlement = city): CivWorldWork? {
                            repeat(pulses) {
                                world.time = 0
                                runtime.pulse(listOf(snapshot.copy(workers = listOf(worker))))
                                runtime.plan(snapshot, worker)?.let { return it }
                                paper.performTicks(5)
                            }
                            return null
                        }
                        val currentPlan = requireNotNull(CivsMinePlan.forBuilding(mine))
                        val firstRung = currentPlan.steps.drop(plan.steps.size).first {
                            it.after?.startsWith("minecraft:ladder[") == true
                        }.point.let { CivsWorksiteLayouts.point(mine, it) }
                        firstRung shouldBe CivPosition(-1841, 38, -2686)
                        val lower = world.getBlockAt(firstRung.x, firstRung.y, firstRung.z)
                        lower.type shouldBe Material.DIORITE
                        val lowerBefore = lower.blockData.asString
                        val blocked = runtime()
                        val paidOpening = world.getBlockAt(-1840, 39, -2684)
                        paidOpening.type shouldBe Material.AIR
                        val admitted = requireNotNull(advance(blocked, 512)) { blocked.debug().toString() }
                        admitted.miningPlanVersion shouldBe 7
                        admitted.changes.single().position shouldBe firstRung
                        admitted.changes.single().before shouldBe lowerBefore
                        admitted.changes.single().after shouldBe "minecraft:air"
                        admitted.standing shouldBe CivPosition(-1841, 39, -2686)
                        admitted.miningOrdinal shouldBe plan.steps.size + 1
                        blocked.validate(city, admitted)
                        // Fault injection only: a paid opening is not permission to admit native liquid.
                        paidOpening.type = Material.WATER
                        shouldThrow<CivsRejection> { blocked.validate(city, admitted) }.reason shouldBe "stale-worksite"
                        lower.blockData.asString shouldBe lowerBefore
                        paidOpening.type = Material.AIR
                        blocked.changed(world.uid, CivPosition(paidOpening.x, paidOpening.y, paidOpening.z))
                        blocked.validate(city, admitted)
                        target.type shouldBe Material.STONE
                        lava.type shouldBe Material.LAVA
                        city.minePlans shouldBe original.minePlans
                        val directory = Files.createTempDirectory("auto2-v7-native-replay-")
                        try {
                            // Seed the existing record, retaining its exact revision instead of creating a new city.
                            Files.createDirectories(directory.resolve("cities"))
                            Files.writeString(directory.resolve("cities").resolve("${city.id}.json"),
                                Gson().toJson(mapOf("schemaVersion" to CivsStore.SCHEMA_VERSION, "city" to city)))
                            CivsStore(directory, Executor(Runnable::run)).use { store ->
                                store.load().join().single() shouldBe city
                                var snapshot = city
                                var owner = blocked
                                CivsStorage(plugin) { emptyArray() }.use { storage ->
                                    storage.validateAutonomousVisualNative = { transaction ->
                                        val receipt = requireNotNull(transaction.autonomousVisual)
                                        owner.validateAutonomousVisualReceiptNative(receipt.before(transaction.after), receipt.work)
                                        null
                                    }
                                    fun commit(work: CivWorldWork) {
                                        val lavaAfter = work.changes.firstOrNull { it.position == CivPosition(lava.x, lava.y, lava.z) }?.after
                                            ?: lava.blockData.asString
                                        val targetAfter = work.changes.firstOrNull { it.position == CivPosition(target.x, target.y, target.z) }?.after
                                            ?: target.blockData.asString
                                        val after = CivsRules.finishAutonomousVisual(snapshot, work)
                                        val receipt = CivAutonomousVisualReceipt(worker, snapshot.stock, requireNotNull(snapshot.autonomy),
                                            snapshot.mineProgress, snapshot.minePlans, work, 1_000, snapshot.id,
                                            snapshot.worldId, snapshot.owner, mine)
                                        val transaction = CivInventoryTransaction(UUID.randomUUID(), snapshot.id, snapshot.revision,
                                            after, emptyList(), worldChanges = work.changes, autonomousVisual = receipt).validated()
                                        store.saveInventoryTransaction(transaction).join()
                                        val restored = store.loadInventoryTransactions().join().single().validated()
                                        storage.lock(restored)
                                        repeat(2) { storage.reconcile(restored) shouldBe CivInventoryApplyResult.Applied }
                                        snapshot = restored.after
                                        store.save(snapshot).join()
                                        store.deleteInventoryTransaction(restored.id).join() shouldBe true
                                        storage.unlock(restored.id)
                                        owner.invalidate(worker.id, work)
                                        snapshot.workers shouldBe city.workers
                                        snapshot.stock shouldBe city.stock
                                        CivsMineProgress.v6State(snapshot.minePlans!!.getValue(mine.id.toString())) shouldBe saved
                                        snapshot.minePlans!!.getValue(mine.id.toString()).v5Proof shouldBe saved.v5Proof
                                        target.blockData.asString shouldBe targetAfter
                                        lava.blockData.asString shouldBe lavaAfter
                                        paidOpening.type shouldBe Material.AIR
                                    }
                                    commit(admitted)
                                    snapshot = store.load().join().single()
                                    owner = runtime()
                                    val ladder = requireNotNull(advance(owner, 512, snapshot = snapshot)) { owner.debug().toString() }
                                    ladder.miningPlanVersion shouldBe 7
                                    ladder.changes.single().position shouldBe firstRung
                                    ladder.changes.single().after shouldBe currentPlan.expectedAfter(plan.steps.size + 1, mine.rotation)
                                    owner.validate(snapshot, ladder)
                                    commit(ladder)
                                    currentPlan.sectionCount("7/shaft/landing", snapshot.minePlans!!.getValue(mine.id.toString()).nextStep,
                                        snapshot.minePlans!!.getValue(mine.id.toString()).sectionSteps.orEmpty()) shouldBe 0L
                                    currentPlan.completed(8750, snapshot.minePlans!!.getValue(mine.id.toString()).nextStep,
                                        snapshot.minePlans!!.getValue(mine.id.toString()).sectionSteps.orEmpty()) shouldBe false
                                    // Finish the actual new ladder; no synthetic V7 paid cells are supplied.
                                    repeat(6) {
                                        val next = requireNotNull(advance(owner, 512, snapshot = snapshot)) { owner.debug().toString() }
                                        currentPlan.section(requireNotNull(next.miningOrdinal)) shouldBe "spine"
                                        owner.validate(snapshot, next)
                                        commit(next)
                                    }
                                    val beforeClearance = snapshot.minePlans!!.getValue(mine.id.toString())
                                    currentPlan.sectionCount("spine", beforeClearance.nextStep, beforeClearance.sectionSteps.orEmpty()) shouldBe
                                        currentPlan.sections.getValue("spine").size.toLong()
                                    owner = runtime()
                                    val clearance = requireNotNull(advance(owner, 2048, snapshot = snapshot)) { owner.debug().toString() }
                                    clearance.status shouldBe "securing-mine"
                                    clearance.changes.single().position shouldBe CivPosition(-1852, 41, -2687)
                                    clearance.changes.single().before shouldBe "minecraft:lava[level=0]"
                                    clearance.changes.single().after shouldBe "minecraft:air"
                                    clearance.inputs shouldBe emptyMap()
                                    clearance.outputs shouldBe emptyMap()
                                    clearance.equipmentAfter shouldBe null
                                    owner.validate(snapshot, clearance)
                                    commit(clearance)
                                    target.type shouldBe Material.STONE
                                    currentPlan.completed(8750, snapshot.minePlans!!.getValue(mine.id.toString()).nextStep,
                                        snapshot.minePlans!!.getValue(mine.id.toString()).sectionSteps.orEmpty()) shouldBe false
                                    snapshot = store.load().join().single()
                                    owner = runtime()
                                    val resumed = requireNotNull(advance(owner, 2048, snapshot = snapshot)) { owner.debug().toString() }
                                    resumed.miningOrdinal shouldBe 8750
                                    resumed.changes.single().position shouldBe CivPosition(-1851, 41, -2687)
                                    resumed.changes.single().before shouldBe "minecraft:stone"
                                    resumed.changes.single().after shouldBe "minecraft:air"
                                    owner.validate(snapshot, resumed)
                                    commit(resumed)
                                    store.loadInventoryTransactions().join() shouldBe emptyList()
                                }
                            }
                        } finally { directory.toFile().deleteRecursively() }
                    }
                }
            }
        }

    test("the supported level-two upgrade retains the complete old proof and adds an independent deeper floor")
        .config(enabled = enabled) {
            val city = source()
            val mine = city.buildings.single { it.type == BuildingType.QUARRY }
            val saved = city.minePlans!!.getValue(mine.id.toString())
            val next = mine.copy(level = 2, schematicId = mine.schematicId.dropLast(1) + "2")
            val upgraded = requireNotNull(CivsMineProgress.upgraded(mine, next, saved, null))
            val plan = requireNotNull(CivsMinePlan.forBuilding(next))
            upgraded.nextStep shouldBe 0L
            upgraded.sectionSteps shouldBe emptyMap()
            upgraded.levels!!.single().state() shouldBe saved.copy(levels = null)
            CivsMineProgress.valid(plan, upgraded, next) shouldBe true
            CivsMineProgress.validLevels(next, upgraded) shouldBe true
            next.anchor.y + plan.floors.last().feetY shouldBe 31
            val paidSpine = mapOf("spine" to plan.sections.getValue("spine").size.toLong())
            plan.sectionReady("7/shaft/landing", 0, paidSpine) shouldBe true
            val paidNewLanding = paidSpine + ("7/shaft/landing" to plan.sections.getValue("7/shaft/landing").size.toLong())
            plan.sectionReady("7/gallery/main", 0, paidNewLanding) shouldBe true
            plan.sectionCount("6/gallery/main", 0, paidNewLanding) shouldBe 0L
        }
})

private fun seedNativeMineWindow(world: CivsWorksiteWorldFixture, capture: JsonObject) {
    val min = capture.getAsJsonObject("bounds").getAsJsonObject("min")
    val palette = capture.getAsJsonArray("palette")
    for (raw in capture.getAsJsonArray("voxels")) {
        val cell = raw.asJsonArray
        check(cell[4].asBoolean)
        world.getBlockAt(min["x"].asInt + cell[0].asInt, min["y"].asInt + cell[1].asInt,
            min["z"].asInt + cell[2].asInt).blockData =
            Bukkit.createBlockData(palette[cell[3].asInt].asJsonObject["block_data"].asString)
    }
}
