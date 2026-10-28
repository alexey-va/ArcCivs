package ru.arc.civs

import com.google.gson.Gson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.HeightMap
import org.bukkit.Material
import org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock
import ru.arc.civs.scenarios.RecordedNativeMap
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

/** Production-derived partial map. Unknown and outside-field columns are conservative synthetic barriers, not terrain observations. */
class CivsAuto2ObservedPlantingTest : FunSpec({
    val statePath = System.getenv("CIVS_AUTO2_FOREST_CITY")
    val stateHash = System.getenv("CIVS_AUTO2_FOREST_CITY_SHA256")
    val terrainPath = System.getenv("CIVS_AUTO2_FOREST_TERRAIN")
    val terrainHash = System.getenv("CIVS_AUTO2_FOREST_TERRAIN_SHA256")
    test("forester plants additional saplings in observed Auto2 terrain with conservative unknown boundaries")
        .config(enabled = statePath != null && stateHash != null && terrainPath != null && terrainHash != null) {
            val bytes = Files.readAllBytes(Path.of(requireNotNull(statePath)))
            check(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == stateHash)
            val source = Gson().fromJson(bytes.toString(Charsets.UTF_8), Settlement::class.java)
            val workerId = source.workers.single { w -> w.assignment?.let(source::building)?.type == BuildingType.FORESTER }.id
            val originalWorker = source.workers.single { it.id == workerId }
            val mill = requireNotNull(source.building(requireNotNull(originalWorker.assignment)))
            val capture = RecordedNativeMap.fromRleFile(Path.of(requireNotNull(terrainPath)), requireNotNull(terrainHash))
            val bounds = requireNotNull(CivsWorksiteLayouts.site(mill)).getAsJsonObject("forester").getAsJsonObject("searchBounds")
            val min = CivsWorksiteLayouts.vector(bounds["min"]); val max = CivsWorksiteLayouts.vector(bounds["max"])
            val columns = (min.z..max.z).flatMap { z -> (min.x..max.x).map { x ->
                CivsWorksiteLayouts.point(mill, CivPosition(x, 0, z)).let { it.x to it.z }
            } }.toSet()
            val known = columns.filterTo(hashSetOf()) { (x, z) ->
                (mill.anchor.y - 4..mill.anchor.y + 6).all { y -> CivPosition(x, y, z) in capture.states }
            }
            check(known.size in 1 until columns.size) { "Expected a partial real field, got ${known.size}/${columns.size}" }
            val targetSaplings = System.getenv("CIVS_AUTO2_FOREST_TARGET_SAPLINGS")?.toInt() ?: 12
            check(targetSaplings >= 12)
            val observedRoads = capture.states.filterValues { it.substringBefore('[') == "minecraft:dirt_path" }
                .keys.mapTo(hashSetOf()) { it.x to it.z }
            val knownSaplings = capture.states.filter { (point, state) ->
                (point.x to point.z) in known && state.substringBefore('[').endsWith("_sapling")
            }.keys.toMutableSet()
            check(knownSaplings.size >= 2) { "Expected the observed saplings from this snapshot" }
            failOnUnsupportedMockBukkitOperation {
                MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                    val world = Auto2ObservedForestWorld(capture, mill.anchor.y + 1).also(paper.server::addWorld)
                    val plugin = paper.createSimplePlugin("Auto2ObservedForest")
                    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                    try {
                        capture.seed(world)
                        val minX = columns.minOf { it.first } - 14; val maxX = columns.maxOf { it.first } + 14
                        val minZ = columns.minOf { it.second } - 14; val maxZ = columns.maxOf { it.second } + 14
                        var syntheticColumns = 0
                        for (x in minX..maxX) for (z in minZ..maxZ) if ((x to z) !in known) {
                            world.getBlockAt(x, mill.anchor.y, z).type = Material.DIRT
                            world.getBlockAt(x, mill.anchor.y + 1, z).blockData = Bukkit.createBlockData("minecraft:oak_sapling[stage=1]")
                            syntheticColumns++
                        }
                        for (x in (minX shr 4)..(maxX shr 4)) for (z in (minZ shr 4)..(maxZ shr 4)) world.loadChunk(x, z)
                        var city = source.copy(worldId = world.uid)
                        CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                            val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, nanoTime = { 0L })
                            var planted = 0
                            var pulses = 0
                            while (knownSaplings.size < targetSaplings && pulses < 2_000) {
                                (Bukkit.getScheduler() as BukkitSchedulerMock).performTicks(5)
                                runtime.pulse(listOf(city))
                                val worker = city.workers.single { it.id == workerId }
                                val work = runtime.plan(city, worker)
                                if (work != null) {
                                    runtime.validate(city, work)
                                    val after = CivsRules.finishWorldWork(city, work, 2_000_000L + pulses * 1_000L)
                                    val receipt = CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision, after, emptyList(),
                                        worldChanges = work.changes, forestry = CivForestryReceipt(worker, city.stock,
                                            city.forestryJobs?.get(work.buildingId.toString()), city.forestryJobs == null,
                                            work, 2_000_000L + pulses * 1_000L, city.forestrySeeded)).validated()
                                    CivsStorage(plugin) { emptyArray() }.use { storage ->
                                        storage.replaceCities(listOf(city)); storage.reserve(city, receipt.id); storage.seal(receipt)
                                        storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
                                        storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
                                        storage.unlock(receipt.id)
                                    }
                                    if (work.status == "planting-tree") {
                                        val roots = CivsForestry.plantingRoots(work)
                                        check(roots.all { (it.x to it.z) in known && it in capture.states }) { "Planting escaped observed terrain" }
                                        check(roots.all { root -> knownSaplings.none { prior ->
                                            kotlin.math.abs(root.x - prior.x) < 4 && kotlin.math.abs(root.z - prior.z) < 4
                                        } }) { "Separate trees were planted closer than three free blocks" }
                                        check(roots.all { root -> observedRoads.none { (x, z) ->
                                            kotlin.math.abs(root.x - x) < 4 && kotlin.math.abs(root.z - z) < 4
                                        } }) { "A sapling was planted within three blocks of an observed road" }
                                        knownSaplings.addAll(roots); planted += roots.size
                                    }
                                    runtime.invalidate(workerId, work)
                                    city = after
                                }
                                pulses++
                            }
                            check(knownSaplings.size >= targetSaplings) {
                                "Auto2 observed field: saplings=${knownSaplings.size}, planted=$planted, knownColumns=${known.size}, " +
                                    "syntheticBoundary=$syntheticColumns, pulses=$pulses, debug=${runtime.debug()}"
                            }
                            println("Auto2 observed field: saplings=${knownSaplings.size} planted=$planted knownColumns=${known.size} " +
                                "syntheticBoundary=$syntheticColumns pulses=$pulses; upper uncaptured terrain and times are not native proof")
                        }
                    } finally { tasks.close() }
                }
            }
        }
})

/** Observed columns use their captured vertical states; unknown and outside-field columns receive a synthetic sapling barrier. */
private class Auto2ObservedForestWorld(private val capture: RecordedNativeMap, private val saplingY: Int) :
    CivsWorksiteWorldFixture("auto2-observed-forest", Material.DIRT) {
    override fun getHighestBlockYAt(x: Int, z: Int, heightMap: HeightMap): Int {
        if (!capture.hasColumn(x, z)) return saplingY - 1
        return capture.column(x, z).firstOrNull { p ->
            val block = getBlockAt(p.x, p.y, p.z)
            when (heightMap) {
                HeightMap.WORLD_SURFACE -> !block.type.isAir
                HeightMap.MOTION_BLOCKING -> block.type.isSolid || block.isLiquid
                HeightMap.MOTION_BLOCKING_NO_LEAVES -> (block.type.isSolid || block.isLiquid) && !block.type.name.endsWith("_LEAVES")
                else -> error("Unsupported height map $heightMap")
            }
        }?.y ?: error("No observed ground in column $x,$z")
    }
}
