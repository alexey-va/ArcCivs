package ru.arc.civs.scenarios

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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

/** Private-input planner reproduction, not resident navigation or native fluid simulation.
 * Paid cells and the supplied native window override a modeled stone plane ending at Y63.
 * The schematic's three upper rungs are modeled explicitly. No private payload is tracked. */
class Auto2MineLavaBoundaryScenarioTest : FunSpec({
    val statePath = System.getenv("CIVS_MINE_CURRENT_STATE")
    val capturePath = System.getenv("CIVS_MINE_CURRENT_NATIVE")
    val captureSha = System.getenv("CIVS_MINE_CURRENT_NATIVE_SHA256")
    val enabled = statePath != null && capturePath != null && captureSha != null
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

    test("the captured inner lava blocks the remaining deep cut while the stone-only control admits that exact cut")
        .config(enabled = enabled) {
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
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("auto2-native-lava-boundary").also(paper.server::addWorld)
                // Only this worksite is scheduled; no Service, family, staffing or economic replay is claimed.
                val city = original.copy(worldId = world.uid, workers = listOf(worker))
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
                        fun advance(runtime: CivsWorksites, pulses: Int, reasons: MutableSet<String> = linkedSetOf()): CivWorldWork? {
                            repeat(pulses) {
                                runtime.pulse(listOf(city))
                                runtime.plan(city, worker)?.let { return it }
                                val entry = (runtime.debug(setOf(worker.id))["entries"] as List<*>).single() as Map<*, *>
                                (entry["need"] as? Map<*, *>)?.get("reason")?.let { reasons += it.toString() }
                                paper.performTicks(5)
                            }
                            return null
                        }
                        val blocked = runtime()
                        val reasons = linkedSetOf<String>()
                        advance(blocked, 1024, reasons) shouldBe null
                        ("mine-thin-cover" in reasons) shouldBe true
                        ("adjacent-liquid" in reasons) shouldBe true
                        target.type shouldBe Material.STONE
                        lava.type shouldBe Material.LAVA
                        city.minePlans shouldBe original.minePlans
                        worker.completedCycles shouldBe 350L
                        // Counterfactual in MockBukkit only; neither the server nor the capture file is changed.
                        lava.type = Material.STONE
                        val control = runtime()
                        val admitted = requireNotNull(advance(control, 512)) { control.debug().toString() }
                        admitted.miningOrdinal shouldBe 8750
                        admitted.changes.single().position shouldBe CivPosition(-1851, 41, -2687)
                        admitted.standing shouldBe CivPosition(-1851, 39, -2686)
                        admitted.miningSections!!.getValue("6/gallery/main") shouldBe 34L
                        admitted.miningStep shouldBe 4L
                        control.validate(city, admitted)
                        target.type shouldBe Material.STONE
                        city.minePlans shouldBe original.minePlans
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
            next.anchor.y + plan.floors.last().feetY shouldBe 35
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
