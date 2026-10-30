package ru.arc.civs

import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

class CivsLayoutDebugTest : FunSpec({
    test("detached export writes bounded atomic bytes and its receipt hashes the exact owned file") {
        layoutDebugScenario { h ->
            val transit = mutableListOf(CivPosition(3, 64, 0))
            val road = h.city.buildings.last().copy(id = UUID.randomUUID(), type = BuildingType.ROAD, transit = transit)
            val buildings = (h.city.buildings + road).toMutableList()
            val city = h.city.copy(buildings = buildings)
            val runtimeRows = mutableListOf<Any>(mapOf("worker" to city.workers.first().id, "phase" to "WALKING"))
            val runtime = mapOf("citizens" to mapOf("total" to 9, "truncated" to true, "entries" to runtimeRows))
            val result = h.debug.export(city, "fixture-world", "test-version", runtime, null) {
                city.copy(revision = 42, stock = Stock(1, 2, 3))
            }
            Files.exists(h.root.resolve("debug")) shouldBe false
            result.isDone shouldBe false
            transit += CivPosition(4, 64, 0)
            buildings.clear()
            runtimeRows.clear()
            h.finish()
            val receipt = result.join()
            receipt["path"] shouldBe "debug/layout/${city.id}.json"
            receipt["layout_unchanged"] shouldBe false // The caller deliberately mutated the original building list.
            receipt["completion_revision"] shouldBe 42L
            val file = h.root.resolve(receipt["path"].toString())
            file.normalize().startsWith(h.root) shouldBe true
            val bytes = Files.readAllBytes(file)
            receipt["bytes"] shouldBe bytes.size
            receipt["sha256"] shouldBe MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val document = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            document["schema"].asInt shouldBe 1
            document["plugin_version"].asString shouldBe "test-version"
            document.getAsJsonArray("buildings").size() shouldBe 3
            document.getAsJsonArray("buildings").last().asJsonObject.getAsJsonArray("transit").size() shouldBe 1
            document.getAsJsonObject("runtime").getAsJsonObject("citizens").let {
                it["total"].asInt shouldBe 9
                it["truncated"].asBoolean shouldBe true
                it.getAsJsonArray("entries").size() shouldBe 1
            }
            document.getAsJsonObject("evidence")["native_connectivity_proven"].asBoolean shouldBe false
            document.getAsJsonObject("frame").has("court") shouldBe true
            document.getAsJsonArray("placed_infrastructure").size() shouldBe 1
            document.getAsJsonArray("missing_plan_ids").size() shouldBe 3
            document["surface"].isJsonNull shouldBe true
            Files.list(file.parent).use { files -> files.map { it.fileName.toString() }.toList() } shouldBe listOf("${city.id}.json")
        }
    }

    test("route provenance accepts only matching active retained journals and separates construction drafts") {
        val city = layoutDebugCity()
        val house = city.buildings.last()
        val route = (3..5).map { CivPosition(it, 64, 0) }
        val plan = CivPlacementPlan(house.id, city.id, city.worldId, house.copy(phase = BuildingPhase.CONSTRUCTING),
            listOf(CivPlanBlock(house.anchor, "minecraft:air", "minecraft:stone")), "a".repeat(64),
            CivTerraformPlan(house.bounds, emptyList(), route, 63, 0, 0))
        val retained = CivsLayoutDebug.planRow(city, house.id, "retained-building", plan)
        retained["status"] shouldBe "validated"
        retained["route_validation"] shouldBe "validated-retained-route"
        retained["validated_route"] shouldBe route
        val malformed = CivsLayoutDebug.planRow(city, house.id, "retained-building",
            plan.copy(terrain = plan.terrain!!.copy(route = listOf(route.first(), route.last()))))
        malformed["status"] shouldBe "unvalidated"
        malformed["route_validation"] shouldBe "invalid-saved-route"
        malformed["validated_route"] shouldBe null
        val draft = CivsLayoutDebug.planRow(city, house.id, "current-construction", plan)
        draft["route_validation"] shouldBe "construction-draft"
        draft["validated_route"] shouldBe null
        val upgraded = house.copy(level = 2, bounds = house.bounds.copy(max = house.bounds.max.copy(y = 72)))
        val order = CivConstruction(UUID.randomUUID(), house.id, 2, 1, 0, ConstructionPhase.CONSTRUCTING,
            Stock(), plannedBuilding = upgraded)
        val upgradeDraft = CivsLayoutDebug.planRow(city.copy(construction = order), order.id, "current-construction",
            plan.copy(id = order.id, building = upgraded))
        upgradeDraft["route_validation"] shouldBe "construction-draft"
        upgradeDraft["validated_route"] shouldBe null
        val wrongWorld = CivsLayoutDebug.planRow(city, house.id, "retained-building", plan.copy(worldId = UUID.randomUUID()))
        wrongWorld["route_validation"] shouldBe "identity-mismatch"
        val missing = CivsLayoutDebug.planRow(city, city.buildings.first().id, "retained-building", null)
        val document = CivsLayoutDebug.document(city, null, "test", 1, listOf(retained, malformed, draft, wrongWorld, missing), null)
        (document["access_routes"] as List<*>).size shouldBe 1
        document["missing_plan_ids"] shouldBe listOf(city.buildings.first().id)
        document["unvalidated_plan_ids"] shouldBe listOf(house.id, house.id, house.id)
    }

    test("a retained journal cannot validate another building's otherwise matching geometry and route") {
        val city = layoutDebugCity()
        val requested = city.buildings.first().id
        val other = city.buildings.last()
        val route = (3..5).map { CivPosition(it, 64, 0) }
        val plan = CivPlacementPlan(requested, city.id, city.worldId, other.copy(phase = BuildingPhase.CONSTRUCTING),
            listOf(CivPlanBlock(other.anchor, "minecraft:air", "minecraft:stone")), "a".repeat(64),
            CivTerraformPlan(other.bounds, emptyList(), route, 63, 0, 0))
        val row = CivsLayoutDebug.planRow(city, requested, "retained-building", plan)
        row["status"] shouldBe "unvalidated"
        row["route_validation"] shouldBe "identity-mismatch"
        row["validated_route"] shouldBe null
        val document = CivsLayoutDebug.document(city, null, "test", 1, listOf(row), null)
        document["access_routes"] shouldBe emptyList<Any>()
        document["unvalidated_plan_ids"] shouldBe listOf(requested)
    }

    test("plan reads are sequential and one per city plus the global limit bound outstanding exports") {
        layoutDebugScenario { h ->
            val reads = mutableListOf<Pair<UUID, CompletableFuture<CivPlacementPlan?>>>()
            h.loader = { id -> CompletableFuture<CivPlacementPlan?>().also { reads += id to it } }
            val first = h.export()
            val sameCity = h.export()
            failureReason(sameCity) shouldBe "layout-export-city-busy"
            val second = h.export(h.city.copy(id = UUID.randomUUID()))
            val excess = h.export(h.city.copy(id = UUID.randomUUID()))
            failureReason(excess) shouldBe "layout-export-busy"
            reads shouldBe emptyList()
            h.work()
            reads.size shouldBe 2 // The first journal of each admitted city; second journals are not retained yet.
            first.isDone shouldBe false
            reads.first().second.complete(null)
            h.work()
            reads.size shouldBe 3
            reads[1].second.complete(null)
            h.work()
            reads.size shouldBe 4
            reads.drop(2).forEach { it.second.complete(null) }
            h.finish()
            first.isDone shouldBe true
            second.isDone shouldBe true
            h.export().also { retry ->
                h.work()
                reads.last().second.complete(null)
                h.work()
                reads.last().second.complete(null)
                h.finish()
                retry.isDone shouldBe true
            }
            h.rejectWork = true
            shouldThrow<CompletionException> { h.export().join() }
            h.rejectWork = false
            h.loader = { CompletableFuture.completedFuture(null) }
            h.export().also { h.finish() }.join()["city"] shouldBe h.city.id
        }
    }

    test("detached replay preserves path and water evidence used by the production terrain solver") {
        val surface = CivTerrainSurface(-64, 320, linkedMapOf(
            civsTerrainKey(0, 0) to CivTerrainSurfaceColumn(63, false, dirtPath = true),
            civsTerrainKey(1, 0) to CivTerrainSurfaceColumn(63, false, waterTopY = 63, waterBottomY = 58),
        ))
        val columns = CivsLayoutDebug.surfaceRow(surface, 10, 20)["columns"] as List<*>
        (columns[0] as Map<*, *>)["dirt_path"] shouldBe true
        (columns[1] as Map<*, *>)["water_top_y"] shouldBe 63
        (columns[1] as Map<*, *>)["water_bottom_y"] shouldBe 58
    }

    test("optional native surface preserves partial coverage and empty loaded coverage is a structured failure") {
        val surface = CivTerrainSurface(-64, 320, linkedMapOf(
            civsTerrainKey(-2, 3) to CivTerrainSurfaceColumn(63, true, topY = 68),
            civsTerrainKey(-1, 3) to CivTerrainSurfaceColumn(-64, false, loaded = false),
        ))
        val row = CivsLayoutDebug.surfaceRow(surface, 10, 20)
        row["atomic"] shouldBe false
        row["loaded_only"] shouldBe true
        row["chunks_loaded_by_export"] shouldBe 0
        (row["coverage"] as Map<*, *>)["fraction_loaded"] shouldBe 0.5
        (row["columns"] as List<*>).first().let { column ->
            (column as Map<*, *>)["x"] shouldBe -2
            column["ground_y"] shouldBe 63
            column["top_y"] shouldBe 68
        }
        shouldThrow<CivsRejection> { CivsLayoutDebug.surfaceRow(surface.copy(columns = emptyMap()), 10, 20) }
            .reason shouldBe "terrain-chunks-unloaded"
        layoutDebugScenario { h ->
            var captures = 0
            val native = CompletableFuture<CivTerrainSurface>()
            val result = h.debug.export(h.city, null, "test", emptyMap(), { captures++; native }) { h.city }
            captures shouldBe 1
            h.work()
            result.isDone shouldBe false
            native.complete(surface)
            h.finish()
            val document = JsonParser.parseString(Files.readString(h.root.resolve(result.join()["path"].toString()))).asJsonObject
            document.getAsJsonObject("surface").getAsJsonObject("coverage")["unloaded"].asInt shouldBe 1
        }
    }

    test("cancellation and close release native scans while accepted work drains without late files") {
        layoutDebugScenario { h ->
            val native = CompletableFuture<CivTerrainSurface>()
            val first = h.debug.export(h.city, null, "test", emptyMap(), { native }) { h.city }
            first.cancel(false) shouldBe true
            native.isCancelled shouldBe true
            h.finish()
            Files.exists(h.root.resolve("debug")) shouldBe false
            val delayed = CompletableFuture<CivPlacementPlan?>()
            h.loader = { delayed }
            val second = h.export()
            h.work()
            h.debug.close()
            failureReason(second) shouldBe "layout-export-closed"
            h.debug.drained().isDone shouldBe false
            delayed.complete(null)
            h.finish()
            h.debug.drained().isDone shouldBe true
            failureReason(h.export()) shouldBe "layout-export-closed"
            Files.exists(h.root.resolve("debug")) shouldBe false
        }
    }

    test("file cap and symlink rejection preserve the previous snapshot and clean temporary files") {
        layoutDebugScenario { h ->
            val initial = h.export().also { h.finish() }.join()
            val file = h.root.resolve(initial["path"].toString())
            val original = Files.readAllBytes(file)
            val tooLarge = h.export(h.city.copy(name = "x".repeat(CivsLayoutDebug.MAX_BYTES)))
            h.finish()
            failureReason(tooLarge) shouldBe "layout-export-too-large"
            Files.readAllBytes(file).contentEquals(original) shouldBe true
            Files.list(file.parent).use { it.count() } shouldBe 1L
        }
        layoutDebugScenario { h ->
            val outside = Files.createTempDirectory("civs-layout-outside")
            try {
                Files.createSymbolicLink(h.root.resolve("debug"), outside)
                val result = h.export()
                h.finish()
                shouldThrow<CompletionException> { result.join() }
                Files.list(outside).use { it.count() } shouldBe 0L
            } finally { Files.deleteIfExists(outside) }
        }
    }

    test("deterministic paths replace one city file and finite retention keeps only the newest 32 exports") {
        layoutDebugScenario { h ->
            repeat(34) { number ->
                val city = h.city.copy(id = UUID(1, number.toLong()))
                h.export(city).also { h.finish() }.join()["path"] shouldBe "debug/layout/${city.id}.json"
            }
            val directory = h.root.resolve("debug/layout")
            Files.list(directory).use { it.count() } shouldBe 32L
            val current = h.city.copy(id = UUID(1, 33))
            h.export(current).also { h.finish() }.join()["path"] shouldBe "debug/layout/${current.id}.json"
            Files.list(directory).use { it.count() } shouldBe 32L
            Files.exists(directory.resolve("${current.id}.json")) shouldBe true
        }
    }

    test("layout comparison ignores ordinary stock revision changes and detects geometry and queue changes") {
        val city = layoutDebugCity()
        CivsLayoutDebug.sameLayout(city, city.copy(revision = 100, stock = Stock(1, 2, 3))) shouldBe true
        CivsLayoutDebug.sameLayout(city, city.copy(buildings = city.buildings.dropLast(1))) shouldBe false
        CivsLayoutDebug.sameLayout(city, city.copy(constructionQueue = listOf(city.buildings.last().id))) shouldBe false
        CivsLayoutDebug.sameLayout(city, city.copy(autonomy = CivAutonomy(layoutHalfWidth = 40))) shouldBe false
    }
})

private fun layoutDebugCity(): Settlement {
    val hall = CivBuilding(UUID(0, 1), BuildingType.TOWN_HALL, 1, CivPosition(0, 63, 0), 0, "fixture-hall",
        CivBox(CivPosition(-3, 63, -6), CivPosition(3, 69, -1)), CivPosition(0, 64, 0), BuildingPhase.ACTIVE)
    val house = hall.copy(id = UUID(0, 2), type = BuildingType.HOUSE, anchor = CivPosition(5, 63, 0),
        entrance = CivPosition(5, 64, 0), bounds = CivBox(CivPosition(4, 63, -4), CivPosition(7, 68, -1)))
    return Settlement(UUID(0, 3), UUID(0, 4), "Layout fixture", UUID(0, 5), CivPosition(0, 63, 0), 96, "fixture",
        Stock(), listOf(hall, house), listOf(CivWorker(UUID(0, 6), "Worker", house.id)), revision = 7)
}

private fun failureReason(future: CompletableFuture<*>): String? =
    CivsDiagnostics.rejection(shouldThrow<CompletionException> { future.join() })?.reason

private fun layoutDebugScenario(action: (LayoutDebugScenario) -> Unit) {
    LayoutDebugScenario().use(action)
}

private class LayoutDebugScenario : AutoCloseable {
    val root: Path = Files.createTempDirectory("civs-layout-debug")
    val city = layoutDebugCity()
    private val queued = ArrayDeque<Runnable>()
    private val scheduler = TestTaskScheduler()
    private val tasks = LifecycleTaskScope(scheduler)
    var rejectWork = false
    var loader: (UUID) -> CompletableFuture<CivPlacementPlan?> = { CompletableFuture.completedFuture(null) }
    val debug = CivsLayoutDebug(root, tasks, Executor {
        if (rejectWork) throw java.util.concurrent.RejectedExecutionException("fixture-rejection")
        queued.add(it)
    }, { loader(it) })
    fun export(city: Settlement = this.city) = debug.export(city, "fixture", "test", emptyMap(), null) { city }
    fun work() {
        var steps = 0
        while (queued.isNotEmpty()) {
            check(++steps <= 1000) { "Unbounded export work" }
            queued.removeFirst().run()
        }
    }
    fun finish() { work(); scheduler.executeImmediate(); work() }
    override fun close() {
        debug.close()
        tasks.close()
        work()
        Files.walk(root).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
