package ru.arc.civs

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock
import ru.arc.civs.scenarios.CapturedWorksiteWorld
import ru.arc.civs.scenarios.RecordedNativeMap
import ru.arc.civs.scenarios.pinnedScenarioResource
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID

/** Public native surface replay: private blueprint cells are excluded and unknown reads fail explicitly. */
class CivsNativeForestryRegressionTest : FunSpec({
    for ((root, supported, accessible) in listOf(
        Triple(CivPosition(-1884, 64, -2688), CivPosition(-1887, 63, -2687), true),
        Triple(CivPosition(-1888, 64, -2698), CivPosition(-1890, 64, -2700), false),
    )) test("captured Auto2 oak $root preserves native ordinary access $accessible") {
            failOnUnsupportedMockBukkitOperation {
                val capture = RecordedNativeMap.fromResourceRle("/scenarios/auto2-forest-roots-surface.json",
                    "e21bd41bb477a33fe64a3a80072bff1a366e8755c9889646b586a19c7cd8f291")
                MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                    val world = CapturedWorksiteWorld("auto2-native-oak-${root.z}", capture).also(paper.server::addWorld)
                    val city = capturedForestryCity(world.uid)
                    val worker = city.workers.single()
                    val mill = requireNotNull(city.building(worker.assignment!!))
                    val unchanged = capture.states.filterKeys { it.y >= 63 }
                    world.getBlockAt(root.x, root.y - 1, root.z).type shouldBe Material.DIRT
                    world.declaredConstraints.isEmpty() shouldBe true
                    val nativeStances = buildList {
                        for (dy in -4..2) for (dx in -3..3) for (dz in -3..3) {
                            if (dx == 0 && dz == 0) continue
                            val point = root.copy(x = root.x + dx, y = root.y + dy, z = root.z + dz)
                            if (citizenStandable(world, point) &&
                                citizenPacketBodyClear(world, CitizenPoint(point.x + .5, point.y.toDouble(), point.z + .5), false) &&
                                citizenCanReachBlock(world, Location(world, point.x + .5, point.y + 1.62, point.z + .5), root)) add(point)
                        }
                    }
                    withClue("Exact native ordinary stances: $nativeStances") {
                        (supported in nativeStances) shouldBe accessible
                    }
                    citizenStandable(world, supported) shouldBe true
                    citizenPacketBodyClear(world, CitizenPoint(supported.x + .5, supported.y.toDouble(), supported.z + .5), false) shouldBe true
                    citizenCanReachBlock(world, Location(world, supported.x + .5, supported.y + 1.62, supported.z + .5), root) shouldBe accessible
                    CivsForestry.crownClear(city, listOf(root), "OAK_LOG", CivsForestry.paths(city)) shouldBe true

                    // Derive the exact native tree, then test its ordinary admitted-job approach.
                    // This isolates the observed stance failure from an unrelated field/plant scan.
                    val scan = CivsForestry.Scan(root, "OAK_LOG")
                    repeat(100) { if (!scan.complete && !scan.failed) scan.advance(192) { capture.states[it] } }
                    val job = withClue("Native crown: ${scan.failureReason} ${scan.failurePoint}") {
                        requireNotNull(scan.job(listOf(root)))
                    }
                    CivsForestry.valid(city, mill.id.toString(), job) shouldBe true
                    val active = city.copy(forestryJobs = mapOf(mill.id.toString() to job))
                    val plugin = paper.createSimplePlugin("NativeForestryRegression")
                    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                    try {
                        CivsWorld(plugin, tasks) { listOf(active) }.use { access ->
                            val worksites = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, nanoTime = { 0L })
                            var accessDenied = false
                            var admissions = 0
                            repeat(80) {
                                if (worksites.plan(active, worker) == null) {
                                    (Bukkit.getScheduler() as BukkitSchedulerMock).performTicks(5)
                                    worksites.pulse(listOf(active))
                                    admissions++
                                    if (worksites.idleReason(active, worker) == "worksite-no-access") accessDenied = true
                                }
                            }
                            if (accessible) {
                                val work = withClue(worksites.debug(setOf(worker.id))) { requireNotNull(worksites.plan(active, worker)) }
                                work.status shouldBe "felling-tree"
                                work.focus shouldBe root
                                work.remoteWork shouldBe false
                                (work.standing in nativeStances) shouldBe true
                                worksites.validate(active, work)
                            } else {
                                worksites.plan(active, worker) shouldBe null
                                accessDenied shouldBe true
                            }
                            println("Native forestry replay root=$root admissions=$admissions work=${worksites.plan(active, worker)?.status}" +
                                " standing=${worksites.plan(active, worker)?.standing} accessDenied=$accessDenied")
                            // Selection cannot create a receipt, spend stock, or remove native leaves/logs.
                            active.stock shouldBe city.stock
                            active.workers shouldBe city.workers
                            unchanged.forEach { (point, state) -> world.getBlockAt(point.x, point.y, point.z).blockData.asString shouldBe state }
                        }
                    } finally { tasks.close() }
                }
            }
        }
})

private fun capturedForestryCity(world: UUID): Settlement {
    val layout = JsonParser.parseString(pinnedScenarioResource("/scenarios/auto2-forest-city-geometry.json",
        "404bb8db3f0c5b70fb64cb810f40ace14a02e45c2d193ae212a353c2d91164a7")).asJsonObject
    val metadata = layout.getAsJsonObject("city")
    fun JsonObject.point() = CivPosition(get("x").asInt, get("y").asInt, get("z").asInt)
    val buildings = layout.getAsJsonArray("buildings").map { entry ->
        val building = entry.asJsonObject
        val bounds = building.getAsJsonObject("actual_bounds")
        CivBuilding(UUID.fromString(building["id"].asString), BuildingType.valueOf(building["type"].asString),
            building["level"].asInt, building.getAsJsonObject("anchor").point(), building["rotation"].asInt,
            building["schematic"].asString, CivBox(bounds.getAsJsonObject("min").point(), bounds.getAsJsonObject("max").point()),
            building.getAsJsonObject("entrance").point(), BuildingPhase.valueOf(building["phase"].asString),
            control = building.get("control")?.takeUnless { it.isJsonNull }?.asJsonObject?.point(),
            production = building.get("production")?.takeUnless { it.isJsonNull }?.asString,
            transit = building.getAsJsonArray("transit").map { it.asJsonObject.point() })
    }
    val worker = CivWorker(UUID(0, 155), "Native forester",
        UUID.fromString("be480ebd-4dff-4dfc-a740-4e78d331558e"), completedCycles = 155, hunger = 17)
    // No Lands provider is installed in this fixture; the owner is deliberately synthetic.
    return Settlement(UUID(0, 2), UUID(0, 1), "Auto2 geometry", world,
        metadata.getAsJsonObject("center").point(), metadata["radius"].asInt,
        metadata["style"].asString, Stock(234, 12, 223), buildings, listOf(worker), autonomy = CivAutonomy())
}
