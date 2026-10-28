package ru.arc.civs.scenarios

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.util.BoundingBox
import ru.arc.civs.*
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Private native windows override paid geometry and an explicitly modeled stone plane.
 * The fallback stance and blocked lava/cut assertions below are inside captured cells. */
class Auto2MineUnavailableScenarioTest : FunSpec({
    val state = System.getenv("CIVS_MINE_LIVE_STATE")
    val native = System.getenv("CIVS_MINE_LIVE_NATIVE")?.split(',')
    val shas = System.getenv("CIVS_MINE_LIVE_NATIVE_SHA256")?.split(',')
    test("the live blocked lava face retains native guards and Auto earns one bounded fallback item")
        .config(enabled = state != null && native != null && shas != null) {
            val original = Gson().fromJson(Files.readString(Path.of(requireNotNull(state))), Settlement::class.java)
            val mine = original.buildings.single { it.type == BuildingType.QUARRY }
            val worker = original.workers.single { it.assignment == mine.id }
            val saved = original.minePlans!!.getValue(mine.id.toString())
            saved.version shouldBe 7
            saved.nextStep shouldBe 5L
            requireNotNull(native).size shouldBe requireNotNull(shas).size
            val captures = native.zip(shas).map { (file, sha) ->
                val bytes = Files.readAllBytes(Path.of(file))
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } shouldBe sha
                JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject.also {
                    it["city"].asString shouldBe original.id.toString()
                    it["worker"].asString shouldBe worker.id.toString()
                    it["world"].asString shouldBe original.worldId.toString()
                    it["source"].asString shouldBe "fresh-native-command"
                    it["unavailable_count"].asInt shouldBe 0
                }
            }
            val shapes = captures.flatMap { it["palette"].asJsonArray }.associate { row ->
                row.asJsonObject["block_data"].asString to row.asJsonObject["collision"].asJsonArray.map { box ->
                    val values = box.asJsonArray.map { it.asDouble }
                    BoundingBox(values[0], values[1], values[2], values[3], values[4], values[5])
                }
            }
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("auto2-live-unavailable", collisionForData = { shapes[it.asString] })
                    .also(paper.server::addWorld)
                val city = CivsRules.validate(original.copy(worldId = world.uid))
                val plugin = paper.createSimplePlugin("Auto2MineUnavailable")
                for (x in -122..-110) for (z in -177..-161) world.loadChunk(x, z)
                fun seed(version: Int, prefix: Long, sections: Map<String, Long>) {
                    val plan = requireNotNull(CivsMinePlan.forBuilding(mine, version))
                    plan.steps.forEachIndexed { ordinal, step -> if (plan.completed(ordinal, prefix, sections)) {
                        val point = CivsWorksiteLayouts.point(mine, step.point)
                        world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(plan.expectedAfter(ordinal, mine.rotation))
                    } }
                }
                saved.v5Proof?.let { seed(5, it.nextStep, it.sectionSteps.orEmpty()) }
                saved.v6Proof?.let { seed(6, it.nextStep, it.sectionSteps.orEmpty()) }
                seed(7, saved.nextStep, saved.sectionSteps.orEmpty())
                captures.forEach { capture ->
                    val minimum = capture["bounds"].asJsonObject["min"].asJsonObject
                    val palette = capture["palette"].asJsonArray
                    capture["voxels"].asJsonArray.forEach { cell ->
                        val row = cell.asJsonArray
                        row[4].asBoolean shouldBe true
                        world.getBlockAt(minimum["x"].asInt + row[0].asInt, minimum["y"].asInt + row[1].asInt,
                            minimum["z"].asInt + row[2].asInt).blockData =
                            Bukkit.createBlockData(palette[row[3].asInt].asJsonObject["block_data"].asString)
                    }
                }
                val source = CivPosition(-1852, 41, -2687)
                val cut = CivPosition(-1851, 41, -2687)
                world.getBlockAt(source.x, source.y, source.z).blockData.asString shouldBe "minecraft:lava[level=0]"
                world.getBlockAt(cut.x, cut.y, cut.z).type shouldBe Material.STONE
                LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, nanoTime = { 0L })
                        var selected: CivWorldWork? = null
                        for (admission in 1..32) {
                            runtime.pulse(listOf(city.copy(workers = listOf(worker))))
                            selected = runtime.plan(city, worker)
                            if (selected != null) break
                            paper.performTicks(5)
                        }
                        val work = requireNotNull(selected) { runtime.debug().toString() }
                        CivsMineFallback.matches(work) shouldBe true
                        work.standing shouldBe CivPosition(-1844, 71, -2690)
                        runtime.validate(city, work)
                        val after = CivsRules.finishWorldWork(city, work, 10_000)
                        after.minePlans shouldBe city.minePlans
                        after.workers.size shouldBe city.workers.size
                        after.stock shouldBe city.stock
                        after.workers.single { it.id == worker.id }.completedCycles shouldBe worker.completedCycles + 1
                        world.getBlockAt(source.x, source.y, source.z).type shouldBe Material.LAVA
                        world.getBlockAt(cut.x, cut.y, cut.z).type shouldBe Material.STONE
                    }
                }
            }
        }
})
