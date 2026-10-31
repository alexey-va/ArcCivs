package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

class CivsVillageSearchCaptureTest : FunSpec({
    test("a rejected proof records exact detached replay inputs on its planning worker and rethrows the original refusal") {
        villageCaptureFixture { f ->
            val failure = f.refusal()
            (f.reject(failure = failure) === failure) shouldBe true
            val captured = f.read(0)
            captured.schema shouldBe 1
            captured.operation shouldBe f.operation
            captured.reference shouldBe f.reference
            captured.point shouldBe f.draft.center
            captured.style shouldBe f.draft.style
            captured.pluginVersion shouldBe "capture-test"
            captured.capturedAtUtc shouldBe "2026-10-31T12:00:00Z"
            captured.draft shouldBe f.draft
            captured.surfaceOrigin shouldBe "native-survey-with-prepared-hall"
            captured.surface shouldBe f.surface
            captured.templates shouldBe f.templates.mapValues { (_, pair) -> CivVillageSearchTemplate(pair.first, pair.second) }
            captured.partial shouldBe f.partial
            captured.reason shouldBe failure.reason
            captured.details shouldBe mapOf("type" to "HOUSE", "stage" to "frontage", "frontage_candidates" to "0")
            f.clockCalls.get() shouldBe 1
            f.logs.single().second shouldBe "village-capture-test-1"
            f.logs.single().first.contains("path=debug/village-search/rejected-0.json state=written") shouldBe true
            val text = Files.readString(f.file(0))
            text.contains("\"cells\"") shouldBe false
            text.contains("\"items\"") shouldBe false
            text.contains("private-item-payload") shouldBe false
            (Files.size(f.file(0)) <= CivsVillageSearchCapture.MAXIMUM_BYTES) shouldBe true
            Files.list(f.directory).use { it.count() } shouldBe 1L
        }
    }

    test("only the first two inspected failures are captured and another search reuses the same two fixed files") {
        villageCaptureFixture { f ->
            repeat(3) { index ->
                val failure = f.refusal()
                (f.reject(draft = f.draft.copy(name = "Candidate $index"), failure = failure) === failure) shouldBe true
            }
            f.read(0).draft.name shouldBe "Candidate 0"
            f.read(1).draft.name shouldBe "Candidate 1"
            Files.list(f.directory).use { it.map { path -> path.fileName.toString() }.toList().toSet() } shouldBe
                setOf("rejected-0.json", "rejected-1.json")
            f.logs.size shouldBe 2
            val next = UUID(0, 200)
            repeat(2) { f.reject(operation = next) }
            f.read(0).operation shouldBe next
            f.read(1).operation shouldBe next
            Files.list(f.directory).use { it.count() } shouldBe 2L
            f.logs.size shouldBe 4
        }
    }

    test("a successful proof or refusal before inspection writes nothing and does not consume a capture slot") {
        villageCaptureFixture { f ->
            f.worker.submit {
                f.capture.prove(f.operation, f.reference, f.draft, f.surface, f.templates) { inspect ->
                    inspect(f.partial, f.infrastructure)
                    42
                }
            }.get(5, TimeUnit.SECONDS) shouldBe 42
            Files.exists(f.directory) shouldBe false
            val failure = f.refusal()
            (f.reject(partial = null, failure = failure) === failure) shouldBe true
            Files.exists(f.directory) shouldBe false
            f.logs.toList() shouldBe emptyList()
            f.reject()
            Files.isRegularFile(f.file(0)) shouldBe true
            Files.exists(f.file(1)) shouldBe false
        }
    }

    test("accidental Main execution performs no serialization or IO while failed capture attempts stay bounded") {
        villageCaptureFixture { f ->
            repeat(3) {
                val failure = f.refusal()
                val caught = shouldThrow<CivsRejection> {
                    f.capture.prove<Unit>(f.operation, f.reference, f.draft, f.surface, f.templates) { inspect ->
                        inspect(f.partial, f.infrastructure)
                        throw failure
                    }
                }
                (caught === failure) shouldBe true
            }
            f.clockCalls.get() shouldBe 0
            Files.exists(f.root.resolve("debug")) shouldBe false
            f.logs.size shouldBe 2
            f.logs.all { it.first.contains("state=failed reason=IllegalStateException") && it.first.length < 256 } shouldBe true
        }
    }

    test("surface and payload limits reject capture without replacing the refusal or overwriting a previous witness") {
        villageCaptureFixture { f ->
            f.reject()
            val original = Files.readString(f.file(0))
            val overSurface = f.surface.copy(columns = (0..65_536).associate {
                civsTerrainKey(it, 0) to CivTerrainSurfaceColumn(63, true)
            })
            val itemWorker = CivWorker(UUID(0, 90), "Resident", null,
                items = listOf(CivResidentItem("STONE", 1, "private-item-payload")))
            val oversized = f.partial.copy(plots = f.partial.plots.map { plot ->
                plot.copy(building = plot.building.copy(schematicId = "x".repeat(CivsVillageSearchCapture.MAXIMUM_BYTES)))
            })
            val failures = listOf(
                Triple(f.draft, overSurface, f.partial),
                Triple(f.draft.copy(workers = listOf(itemWorker)), f.surface, f.partial),
                Triple(f.draft.copy(corpses = listOf(CivCorpse(itemWorker, f.draft.center, 1, 2, null, null, "CUSTOM"))),
                    f.surface, f.partial),
                Triple(f.draft, f.surface, oversized))
            for ((index, input) in failures.withIndex()) {
                val failure = f.refusal()
                (f.reject(operation = UUID(0, 300 + index.toLong()), draft = input.first,
                    surface = input.second, partial = input.third, failure = failure) === failure) shouldBe true
                Files.readString(f.file(0)) shouldBe original
            }
            val missingTemplate = f.refusal()
            (f.reject(operation = UUID(0, 400), templates = f.templates - BuildingType.HOUSE,
                failure = missingTemplate) === missingTemplate) shouldBe true
            Files.readString(f.file(0)) shouldBe original
            Files.list(f.directory).use { it.count() } shouldBe 1L
            f.logs.any { it.first.contains("reason=village-capture-invalid-surface") } shouldBe true
            f.logs.any { it.first.contains("reason=village-capture-invalid-draft") } shouldBe true
            f.logs.any { it.first.contains("reason=village-capture-invalid-templates") } shouldBe true
            f.logs.any { it.first.contains("reason=village-capture-too-large") } shouldBe true
        }
    }

    for (link in listOf("debug", "debug/village-search", "debug/village-search/rejected-0.json"))
    test("a symlink at $link cannot redirect rejection data outside the plugin directory") {
        villageCaptureFixture { f ->
            val outside = Files.createTempDirectory("village-capture-outside-")
            val secret = outside.resolve("secret.json").also { Files.writeString(it, "unchanged") }
            try {
                val target = f.root.resolve(link)
                Files.createDirectories(requireNotNull(target.parent))
                Files.createSymbolicLink(target, if (link.endsWith(".json")) secret else outside)
                val failure = f.refusal()
                (f.reject(failure = failure) === failure) shouldBe true
                Files.readString(secret) shouldBe "unchanged"
                Files.list(outside).use { it.count() } shouldBe 1L
                f.logs.single().first.contains("state=failed") shouldBe true
            } finally { outside.toFile().deleteRecursively() }
        }
    }

    test("throwing diagnostic handlers cannot replace either a written or failed capture refusal or reset its two attempt budget") {
        villageCaptureFixture { f ->
            val loggingCalls = AtomicInteger()
            f.logger.addHandler(object : Handler() {
                override fun publish(record: LogRecord) { loggingCalls.incrementAndGet(); error("diagnostic sink failed") }
                override fun flush() = Unit
                override fun close() = Unit
            })
            val first = f.refusal()
            (f.reject(failure = first) === first) shouldBe true
            val second = f.refusal()
            (f.reject(draft = f.draft.copy(workers = listOf(CivWorker(UUID(0, 9), "Resident", null))),
                failure = second) === second) shouldBe true
            val third = f.refusal()
            (f.reject(failure = third) === third) shouldBe true
            loggingCalls.get() shouldBe 2
            Files.isRegularFile(f.file(0)) shouldBe true
            Files.exists(f.file(1)) shouldBe false
        }
    }
})

private class VillageCaptureFixture(val root: Path) : AutoCloseable {
    val worker = BoundedAsyncExecutor("village-capture-test", 1, 16)
    val logs = ConcurrentLinkedQueue<Pair<String, String>>()
    val logger = Logger.getAnonymousLogger().apply {
        useParentHandlers = false
        addHandler(object : Handler() {
            override fun publish(record: LogRecord) { logs += record.message to Thread.currentThread().name }
            override fun flush() = Unit
            override fun close() = Unit
        })
    }
    val clockCalls = AtomicInteger()
    val operation = UUID(0, 100)
    val reference = UUID(0, 101)
    private val hall = CivBuilding(UUID(0, 1), BuildingType.TOWN_HALL, 1, CivPosition(0, 63, 0), 0,
        "fixture", CivBox(CivPosition(-2, 63, -2), CivPosition(2, 68, 2)), CivPosition(0, 64, 3),
        BuildingPhase.CONSTRUCTING, CivPosition(0, 64, 0))
    val draft = CivsAutonomy.found(UUID(0, 2), UUID(0, 3), "Draft village", UUID(0, 4), hall.control!!,
        128, "nordic", hall, 1_000)
    val surface = CivTerrainSurface(-64, 320, (-1..1).flatMap { x -> (-1..1).map { z ->
        civsTerrainKey(x, z) to CivTerrainSurfaceColumn(63, true, topY = 67)
    } }.toMap())
    val templates = CivsAutonomy.profile.distinct().associateWith { type ->
        val current = hall.copy(id = UUID(0, 20 + type.ordinal.toLong()), type = type)
        current to current.copy(level = type.maxLevel)
    }
    val partial = CivSettlementPlan(1, 0, 72, listOf(CivSettlementPlot(draft.buildings.single(), hall.bounds)),
        emptyMap(), emptyList(), emptyList())
    val infrastructure = CivSettlementInfrastructure(emptyList(), mapOf("unplaced-target" to 0), false)
    val capture = CivsVillageSearchCapture(root, "capture-test", logger) {
        check(!Bukkit.isPrimaryThread())
        clockCalls.incrementAndGet()
        Instant.parse("2026-10-31T12:00:00Z")
    }
    val directory: Path get() = root.resolve("debug/village-search")
    fun file(slot: Int): Path = directory.resolve("rejected-$slot.json")
    fun read(slot: Int): CivVillageSearchCaptureRecord = Gson().fromJson(Files.readString(file(slot)), CivVillageSearchCaptureRecord::class.java)
    fun refusal() = CivsRejection("autonomy-no-site", mapOf("type" to "HOUSE", "stage" to "frontage",
        "frontage_candidates" to "0", "samples" to "discarded", "item_payload" to "private-item-payload"))

    fun reject(operation: UUID = this.operation, draft: Settlement = this.draft, surface: CivTerrainSurface = this.surface,
        templates: Map<BuildingType, Pair<CivBuilding, CivBuilding>> = this.templates,
        partial: CivSettlementPlan? = this.partial, failure: Throwable = refusal()): Throwable {
        val result = worker.submit<Unit> {
            capture.prove(operation, reference, draft, surface, templates) { inspect ->
                partial?.let { inspect(it, infrastructure) }
                throw failure
            }
        }
        return checkNotNull(shouldThrow<ExecutionException> { result.get(5, TimeUnit.SECONDS) }.cause)
    }

    override fun close() { worker.closeAsync().get(5, TimeUnit.SECONDS); root.toFile().deleteRecursively() }
}

private fun villageCaptureFixture(run: (VillageCaptureFixture) -> Unit) {
    MockBukkitTestRuntime.open().use {
        VillageCaptureFixture(Files.createTempDirectory("village-search-capture-test-")).use(run)
    }
}
