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

/** Native windows override paid geometry and a declared modeled stone plane for planner/cover setup.
 * Descent uses ONLY captured cells/colliders; unknown terrain is unavailable. Not a live actor/fluid simulation. */
class Auto2MineDescentScenarioTest : FunSpec({
    val state = System.getenv("CIVS_MINE_LIVE_STATE")
    val native = System.getenv("CIVS_MINE_LIVE_NATIVE")?.split(',')
    val shas = System.getenv("CIVS_MINE_LIVE_NATIVE_SHA256")?.split(',')
    test("the current native mine has a paid descent to a bounded adjacent lava receipt and a real stone cut")
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
                val world = CivsWorksiteWorldFixture("auto2-live-descent", collisionForData = { shapes[it.asString] })
                    .also(paper.server::addWorld)
                // MockBukkit cannot decode Paper's compressed ItemStack bytes. Geometry uses the same
                // captured pickaxe material/count; native item-byte compatibility is tested by the receipt suites.
                val geometryWorker = worker.copy(items = worker.items!!.toMutableList().also { items ->
                    items[4] = items[4]?.copy(data = null)
                })
                var city = CivsRules.validate(original.copy(worldId = world.uid,
                    workers = original.workers.map { if (it.id == worker.id) geometryWorker else it }))
                val plugin = paper.createSimplePlugin("Auto2MineDescent")
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
                val terrain = hashMapOf<CivPosition, CitizenTerrainBlock>()
                captures.forEach { capture ->
                    val minimum = capture["bounds"].asJsonObject["min"].asJsonObject
                    val palette = capture["palette"].asJsonArray
                    capture["voxels"].asJsonArray.forEach { cell ->
                        val row = cell.asJsonArray
                        row[4].asBoolean shouldBe true
                        val point = CivPosition(minimum["x"].asInt + row[0].asInt, minimum["y"].asInt + row[1].asInt,
                            minimum["z"].asInt + row[2].asInt)
                        val native = palette[row[3].asInt].asJsonObject
                        val block = CitizenTerrainBlock(true, native["liquid"].asBoolean, native["door"].asBoolean,
                            native["climbable"].asBoolean, native["hot"].asBoolean, true, 1.0,
                            native["collision"].asJsonArray.map { shape -> shape.asJsonArray.let {
                                CitizenTerrainBox(it[0].asDouble, it[1].asDouble, it[2].asDouble, it[3].asDouble, it[4].asDouble, it[5].asDouble)
                            } })
                        terrain[point]?.let { it shouldBe block }
                        terrain[point] = block
                        world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(native["block_data"].asString)
                    }
                }
                val source = CivPosition(-1852, 41, -2687)
                val cut = CivPosition(-1851, 41, -2687)
                world.getBlockAt(source.x, source.y, source.z).blockData.asString shouldBe "minecraft:lava[level=0]"
                world.getBlockAt(cut.x, cut.y, cut.z).type shouldBe Material.STONE
                LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, nanoTime = { 0L })
                        var currentWorker = geometryWorker
                        var at = worker.nextDeliveryAt + 1_000L
                        val actual = captures.last()["actual"].asJsonArray
                        var feet = org.bukkit.Location(world, actual[0].asDouble, actual[1].asDouble, actual[2].asDouble)
                        runtime.residentFeet = { feet }
                        fun nextPhysical(): CivWorldWork {
                            repeat(512) {
                                world.time = 0
                                runtime.pulse(listOf(city.copy(workers = listOf(currentWorker))))
                                val work = runtime.plan(city, currentWorker)
                                if (work != null) {
                                    if (!CivsMineFallback.matches(work)) return work
                                    runtime.validate(city, work)
                                    at += work.durationMillis
                                    city = CivsRules.finishWorldWork(city, work, at)
                                    currentWorker = city.workers.single { it.id == worker.id }
                                    runtime.invalidate(worker.id, completedWork = work)
                                    paper.performTicks(120)
                                } else paper.performTicks(5)
                            }
                            error(runtime.debug().toString())
                        }
                        val work = nextPhysical()
                        work.status shouldBe "securing-mine"
                        work.focus shouldBe cut
                        work.miningFrontierBefore shouldBe "minecraft:stone"
                        work.changes.single().position shouldBe source
                        CivsMineSafety.adjacentLavaFrontier(work) shouldBe cut
                        val passage = citizenMineRoutePassage(mine, requireNotNull(work.miningAccessStep),
                            city.minePlans!!.getValue(mine.id.toString()), work.miningAccessSections)
                        val origin = CitizenPoint(feet.x, feet.y, feet.z)
                        val route = searchCapturedMine(terrain, origin, work.standing.point(), passage)
                        require(route.failure == null) { "native descent failed: $route" }
                        requireNotNull(route.path).last().point shouldBe work.standing.point()
                        println("NATIVE_DESCENT nodes=${route.nodes} path=${route.path.size} origin=$origin target=${work.standing} minY=${route.path.minOf { it.point.y }}")
                        (route.path.any { it.action == CitizenRouteAction.CLIMB && it.point.y < 65 }) shouldBe true
                        // Model arrival only along the path accepted by the same native-collider search; no off-path teleport.
                        route.path.forEach { node -> feet = org.bukkit.Location(world, node.point.x, node.point.y, node.point.z) }
                        runtime.validate(city, work)
                        val before = city
                        val after = CivsRules.finishWorldWork(before, work, at + work.durationMillis)
                        after.minePlans!!.getValue(mine.id.toString()).nextStep shouldBe saved.nextStep
                        after.minePlans.getValue(mine.id.toString()).sectionSteps!!.getValue("6/gallery/main") shouldBe 33L
                        after.workers.single { it.id == worker.id }.items shouldBe currentWorker.items
                        CivsStorage(plugin) { emptyArray() }.use { storage ->
                            storage.replaceCities(listOf(before))
                            val id = java.util.UUID.randomUUID()
                            storage.reserve(before, id)
                            val prepared = storage.prepareWork(before, mine.id, emptyMap(), emptyMap(), work.changes, work)
                            val tx = CivInventoryTransaction(id, before.id, before.revision,
                                after.copy(inventory = prepared.inventoryAfter), prepared.changes,
                                worldChanges = prepared.worldChanges, mine = prepared.mine).validated()
                            storage.seal(tx)
                            storage.validateMineNative = { transaction ->
                                val receipt = requireNotNull(transaction.mine)
                                runtime.validateMineReceiptNative(receipt.before(transaction.after), receipt.work)
                                null
                            }
                            storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
                            storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
                            city = tx.after
                        }
                        runtime.invalidate(worker.id, completedWork = work)
                        currentWorker = city.workers.single { it.id == worker.id }
                        world.getBlockAt(source.x, source.y, source.z).type shouldBe Material.AIR
                        world.getBlockAt(cut.x, cut.y, cut.z).type shouldBe Material.STONE
                        val excavation = nextPhysical()
                        excavation.focus shouldBe cut
                        excavation.changes.single().position shouldBe cut
                        excavation.changes.single().after shouldBe "minecraft:air"
                        excavation.status shouldBe "digging-mine"
                        excavation.outputs shouldBe mapOf("COBBLESTONE" to 1L)
                        runtime.validate(city, excavation)
                        val beforeCut = city
                        val cutAfter = CivsRules.finishWorldWork(beforeCut, excavation, at + 10_000)
                        CivsStorage(plugin) { emptyArray() }.use { storage ->
                            storage.replaceCities(listOf(beforeCut))
                            val id = java.util.UUID.randomUUID()
                            storage.reserve(beforeCut, id)
                            val prepared = storage.prepareWork(beforeCut, mine.id, emptyMap(), emptyMap(), excavation.changes, excavation)
                            val tx = CivInventoryTransaction(id, beforeCut.id, beforeCut.revision,
                                cutAfter.copy(inventory = prepared.inventoryAfter), prepared.changes,
                                worldChanges = prepared.worldChanges, mine = prepared.mine).validated()
                            storage.seal(tx)
                            storage.validateMineNative = { transaction ->
                                val receipt = requireNotNull(transaction.mine)
                                runtime.validateMineReceiptNative(receipt.before(transaction.after), receipt.work)
                                null
                            }
                            storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
                            storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
                            city = tx.after
                        }
                        world.getBlockAt(cut.x, cut.y, cut.z).type shouldBe Material.AIR
                        val committedProof = requireNotNull(city.minePlans).getValue(mine.id.toString())
                        committedProof.sectionSteps!!.getValue("6/gallery/main") shouldBe 34L
                        committedProof.v6Proof shouldBe saved.v6Proof
                        committedProof.v5Proof shouldBe saved.v5Proof
                        CivsEquipment.plainCount(city.workers.single { it.id == worker.id }, "COBBLESTONE") shouldBe
                            CivsEquipment.plainCount(currentWorker, "COBBLESTONE") + 1

                    }
                }
            }
        }
})

/** Missing native cells are unavailable; paid/modelled geometry cannot manufacture a route. */
private fun searchCapturedMine(terrain: Map<CivPosition, CitizenTerrainBlock>, from: CitizenPoint, to: CitizenPoint,
    passage: CitizenMinePassage): CitizenSearchResult {
    val search = CitizenNavigationSearch(from, to, -64, 320, emptyList())
    val visited = mutableListOf<CitizenPoint>()
    var result = search.advance(emptyList())
    while (result.probes.isNotEmpty()) result = search.advance(result.probes.map { probe ->
        visited += probe.node.point()
        val y = kotlin.math.floor(probe.node.y).toInt()
        val blocks = buildMap {
            for (x in probe.node.x - 1..probe.node.x + 1) for (z in probe.node.z - 1..probe.node.z + 1)
                for (by in y - 3..y + 3) {
                    val point = CivPosition(x, by, z)
                    put(point, (terrain[point] ?: CitizenTerrainBlock.UNAVAILABLE).copy(
                        allowed = passage.allows(CitizenPoint(x + .5, by.toDouble(), z + .5))))
                }
        }
        CitizenSearchCapture(probe, blocks, citizenSearchCandidates(probe, blocks, -64, 320).map {
            it.copy(permitted = passage.allows(it.node.point()))
        })
    })
    if (result.failure != null) println("NATIVE_ROUTE_VISITED=" + visited.distinct().sortedBy { it.y })
    return result
}
