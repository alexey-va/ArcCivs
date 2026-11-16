package ru.arc.civs

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import org.mockbukkit.mockbukkit.block.BlockMock
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlin.math.floor

class CivsNavigationDebugFileTest : FunSpec({
    test("an armed next request preserves gameplay and roundtrips exact weighted batches through the real search") {
        navigationFileFixture { h ->
            val baseline = h.request()
            h.finish(baseline)
            baseline.failure shouldBe "capture-budget"
            val arm = h.arm()
            val unrelated = h.request()
            var contexts = 0
            h.owner.attachDebug(unrelated, h.identity.copy(worker = UUID(0, 99)), true) { contexts++; h.context() }
            h.finish(unrelated)
            contexts shouldBe 0
            arm.completion.isDone shouldBe false
            val selected = h.request()
            h.attach(selected)
            h.finish(selected)
            selected.failure shouldBe baseline.failure
            selected.nodeCount shouldBe baseline.nodeCount
            selected.capturedBlocks shouldBe baseline.capturedBlocks
            h.io.pending.size shouldBe 1
            arm.completion.isDone shouldBe false
            val reads = h.nativeReads
            h.io.runNext()
            h.nativeReads shouldBe reads
            val receipt = arm.completion.join()
            receipt["state"] shouldBe "saved"
            receipt["reported_blocks"] shouldBe selected.capturedBlocks
            receipt["actual_blocks"] shouldBe h.settings.maximumCapturedBlocks
            (selected.capturedBlocks < h.settings.maximumCapturedBlocks) shouldBe true
            val encoded = Files.readString(h.root.resolve(receipt["path"] as String))
            for (secret in listOf("PRIVATE_CITY", "PRIVATE_RESIDENT", "PRIVATE_PRODUCTION", h.city.owner.toString(),
                "buildingMaterials", "stock", "inventory", "workers", "nbt")) encoded.contains(secret) shouldBe false
            val json = JsonParser.parseString(encoded).asJsonObject
            json["native_reads_added"].asInt shouldBe 0
            json["pending_cursor"].isJsonObject shouldBe true
            json["policy"].asJsonObject["geometry"].asJsonObject["allowedBuildings"].asJsonArray.size() shouldBe 0
            json["context"].asJsonObject.has("policy") shouldBe false
            json["policy"].asJsonObject["geometry"].asJsonObject["canopyMaterials"].asJsonArray.map { it.asString }.toSet() shouldBe
                (Tag.LOGS.values + Tag.LEAVES.values).map { it.name }.toSet()
            json["chunk_generations"].asJsonArray.isEmpty shouldBe false
            val costs = json["resolved_costs"].asJsonArray.map { it.asJsonObject["cost"].asDouble }.toSet()
            costs.containsAll(setOf(1.0, 1.5, 2.25)) shouldBe true
            replayNavigationCapture(json)
            // IO retains only the completed detached frame, and no stale request owns another route.
            h.owner.pulse()
            h.owner.debugSelected(h.identity.city, h.identity.worker) shouldBe false
        }
    }

    test("one slot expires without an active search and rejects stale intents and unsupported requests without cancelling gameplay") {
        navigationFileFixture { h ->
            val expired = h.arm()
            shouldThrow<CivsRejection> { h.arm() }.reason shouldBe "navigation-file-busy"
            h.now = 120_000_000_000L
            h.owner.pulse()
            navigationFileFailure(expired.completion) shouldBe "navigation-capture-expired"
            val stale = h.arm()
            h.current = false
            h.owner.pulse()
            navigationFileFailure(stale.completion) shouldBe "stale-navigation-intent"
            h.current = true
            val unsupported = h.arm()
            val route = h.request()
            h.owner.attachDebug(route, h.identity, false, h::context)
            navigationFileFailure(unsupported.completion) shouldBe "unsupported-navigation-request"
            route.searching shouldBe true
            h.finish(route)
            route.failure shouldBe "capture-budget"
            h.io.pending shouldBe emptyList()
            Files.exists(h.root.resolve("debug")) shouldBe false
        }
    }

    test("changed intent or cancelled preparation retires only the recorder and a successful request is reported honestly") {
        navigationFileFixture { h ->
            val changed = h.arm()
            val first = h.request(); h.attach(first)
            h.current = false; h.owner.pulse()
            navigationFileFailure(changed.completion) shouldBe "stale-navigation-intent"
            h.finish(first)
            first.failure shouldBe "capture-budget"
            h.current = true
            val cancelled = h.arm()
            val second = h.request(); h.attach(second)
            second.cancel(); h.owner.pulse()
            navigationFileFailure(cancelled.completion) shouldBe "navigation-request-retired"
            val succeeded = h.arm()
            val target = CitizenPoint(-1.5, 64.0, .5)
            val third = h.request(target); h.attach(third, target)
            h.finish(third)
            third.failure shouldBe null
            succeeded.completion.join()["state"] shouldBe "not-captured"
            succeeded.completion.join()["outcome"] shouldBe "ready"
            h.io.pending shouldBe emptyList()
        }
    }

    test("file limits and rejected storage work fail closed while close drains an already pending write") {
        navigationFileFixture { h ->
            val rejected = h.arm { CivsNavigationDebugFile(h.root, Executor { throw RejectedExecutionException("PRIVATE_PATH") }).save(it) }
            val first = h.request(); h.attach(first); h.finish(first)
            shouldThrow<CompletionException> { rejected.completion.join() }.cause!!::class shouldBe RejectedExecutionException::class
            first.failure shouldBe "capture-budget"
            val small = h.arm { CivsNavigationDebugFile(h.root, h.io, maximumBytes = 128).save(it) }
            val second = h.request(); h.attach(second); h.finish(second)
            h.io.runNext()
            navigationFileFailure(small.completion) shouldBe "navigation-file-too-large"
            Files.exists(h.root.resolve("debug")) shouldBe false
            val pending = h.arm()
            val third = h.request(); h.attach(third); h.finish(third)
            h.owner.clear()
            h.owner.drained().isDone shouldBe false
            h.io.runNext()
            pending.completion.join()["state"] shouldBe "saved"
            h.owner.drained().join() shouldBe Unit
        }
    }

    test("console arming validates exact selector and readiness and reports completion only on the current lifecycle") {
        val scheduler = TestTaskScheduler()
        val tasks = LifecycleTaskScope(scheduler)
        var ready = true
        var active = "active"
        var calls = 0
        val result = CompletableFuture<Map<String, Any?>>()
        val host = mockk<CivsUiHost> { every { ready() } answers { ready } }
        val command = CivsCommand(host, mockk(), mockk(), tasks, hotloadStatus = { mapOf("state" to active) },
            armNavigationCapture = { city, worker, cycle, goal ->
                city shouldBe UUID(0, 1); worker shouldBe UUID(0, 2); cycle shouldBe 1692L
                goal shouldBe CivPosition(12306, 89, 8312)
                calls++
                mapOf("state" to "armed") to result
            })
        val valid = arrayOf("debug-navigation-file", "arm", UUID(0, 1).toString(), UUID(0, 2).toString(),
            "1692", "12306", "89", "8312")
        for ((sender, denied) in listOf(mockk<Player>(relaxed = true) to null, mockk<CommandSender>(relaxed = true) to null,
            mockk<ConsoleCommandSender>(relaxed = true) to "arccivs.use", mockk<ConsoleCommandSender>(relaxed = true) to "arccivs.admin")) {
            val messages = mutableListOf<String>()
            every { sender.hasPermission(any<String>()) } returns true
            if (denied != null) every { sender.hasPermission(denied) } returns false
            every { sender.sendMessage(capture(messages)) } returns Unit
            command.onCommand(sender, mockk(), "civs", valid)
            navigationFileReceipt(messages.single())["error"].asString shouldBe "not-authorized"
        }
        val messages = mutableListOf<String>()
        val console = mockk<ConsoleCommandSender> {
            every { hasPermission(any<String>()) } returns true
            every { sendMessage(capture(messages)) } returns Unit
        }
        for ((args, error) in listOf(valid.dropLast(1).toTypedArray() to "invalid-navigation-arguments",
            (valid + "extra") to "invalid-navigation-arguments",
            valid.copyOf().also { it[1] = "now" } to "invalid-navigation-arguments",
            valid.copyOf().also { it[2] = "bad" } to "invalid-city-id",
            valid.copyOf().also { it[3] = "bad" } to "invalid-worker-id",
            valid.copyOf().also { it[4] = "-1" } to "invalid-navigation-cycle",
            valid.copyOf().also { it[5] = "0.5" } to "invalid-navigation-goal")) {
            command.onCommand(console, mockk(), "civs", args)
            navigationFileReceipt(messages.last())["error"].asString shouldBe error
        }
        ready = false
        command.onCommand(console, mockk(), "civs", valid)
        navigationFileReceipt(messages.last())["error"].asString shouldBe "not-ready"
        ready = true; active = "preparing"
        command.onCommand(console, mockk(), "civs", valid)
        navigationFileReceipt(messages.last())["error"].asString shouldBe "not-ready"
        active = "active"
        calls shouldBe 0
        messages.clear()
        command.onCommand(console, mockk(), "civs", valid)
        calls shouldBe 1
        navigationFileReceipt(messages.single())["state"].asString shouldBe "armed"
        Thread { result.complete(mapOf("state" to "saved", "path" to "debug/navigation/latest.json")) }.apply { start(); join() }
        messages.size shouldBe 1
        scheduler.executeImmediate()
        navigationFileReceipt(messages.last())["state"].asString shouldBe "saved"
        val stale = CompletableFuture<Map<String, Any?>>()
        val closed = CivsCommand(host, mockk(), mockk(), tasks,
            armNavigationCapture = { _, _, _, _ -> mapOf("state" to "armed") to stale })
        messages.clear()
        closed.onCommand(console, mockk(), "civs", valid)
        tasks.close()
        Thread { stale.completeExceptionally(java.io.IOException("PRIVATE_IO_PATH")) }.apply { start(); join() }
        scheduler.executeImmediate()
        navigationFileReceipt(messages.single())["state"].asString shouldBe "armed"
        CivsCommand.completions(true, true, arrayOf("debug-navigation"), true) shouldBe listOf("debug-navigation-file")
        CivsCommand.completions(true, true, arrayOf("debug-navigation"), false) shouldBe emptyList()
    }
})

private fun navigationFileFixture(run: (NavigationFileFixture) -> Unit) {
    MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper -> NavigationFileFixture(paper).use(run) }
}

private class NavigationFileFixture(private val paper: MockBukkitTestRuntime) : AutoCloseable {
    val root = Files.createTempDirectory("arccivs-navigation-file-")
    var nativeReads = 0
    private val main = Thread.currentThread()
    val world = object : CivsWorksiteWorldFixture("navigation-file") {
        override fun getBlockAt(x: Int, y: Int, z: Int): BlockMock {
            check(Thread.currentThread() === main) { "Debug IO read native world" }
            nativeReads++
            return super.getBlockAt(x, y, z)
        }
    }.also(paper.server::addWorld)
    val city = Settlement(UUID(0, 1), UUID(0, 900), "PRIVATE_CITY", world.uid, CivPosition(0, 64, 0),
        96, "fixture", Stock(wood = 12345), emptyList(), emptyList())
    val identity = CitizenNavigationDebugIdentity(city.id, UUID(0, 2), UUID(0, 3), world.uid,
        CitizenToken(UUID(0, 4), 1692, 1_000, null, UUID(0, 5)), CivPosition(20, 64, 0))
    val origin = CitizenPoint(.5, 64.0, .5)
    val target = CitizenPoint(20.5, 64.0, .5)
    val settings = CivsNavigationSettings(searchNodesPerBatch = 8, maximumCapturedBlocks = 512)
    val paths = CivsPaths()
    private val plugin = paper.createSimplePlugin("NavigationFile")
    private val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    private val work = BudgetedTaskQueue(tasks, 10000, nanoTime = { 0L })
    private val workers = ArrayDeque<Runnable>()
    var now = 0L
    var current = true
    val owner = CivsResidentNavigation(tasks, work, Executor { workers.addLast(it) }, settings,
        nanoTime = { now }, snapshotContextFree = { true })
    val io = NavigationFileExecutor()
    private val file = CivsNavigationDebugFile(root, io)
    init {
        for (x in -2..2) for (z in -2..2) world.loadChunk(x, z)
        for (z in -20..20) for (y in 64..68) world.getBlockAt(1, y, z).type = Material.STONE
        world.getBlockAt(-1, 63, 0).type = Material.DIRT_PATH
        paths.begin(city).network.cells += CivPosition(0, 63, 1)
    }
    fun arm(save: (CitizenNavigationDebugFrame) -> CompletableFuture<Map<String, Any?>> = file::save) =
        owner.armDebug(identity, { current }, save)
    fun context(target: CitizenPoint = this.target) = CitizenNavigationDebugContext(identity, city.revision, "fixture",
        origin, target, 1_000, paths.begin(city, preferCourierRoads = true).weights.debugPolicy(emptySet(), Tag.LOGS.values.toSet(), Tag.LEAVES.values.toSet()))
    fun attach(route: CitizenPacketRoute, target: CitizenPoint = this.target) =
        owner.attachDebug(route, identity, true) { context(target) }
    fun request(target: CitizenPoint = this.target): CitizenPacketRoute {
        val trip = paths.begin(city, preferCourierRoads = true)
        val territory = CivRouteTerritory(city)
        val policy = CitizenSnapshotPolicy({ p, _ -> territory.allows(p, emptySet(), null) && territory.controls.clear(p) },
            territory.controls::stepClear, supportCost = trip.weights.detached(Tag.LOGS.values.toSet(), Tag.LEAVES.values.toSet()))
        return owner.request(world, Location(world, origin.x, origin.y, origin.z), Location(world, target.x, target.y, target.z),
            { territory.allows(it, emptySet(), null) }, policy.stepAllowed, { trip.cost(it) { 1.0 } }, null, policy)
    }
    fun finish(route: CitizenPacketRoute) {
        repeat(200) {
            if (!route.searching) return
            while (workers.isNotEmpty()) workers.removeFirst().run()
            paper.performTicks(1)
            owner.pulse()
        }
        error("Navigation did not finish: ${route.preparationPhase}")
    }
    override fun close() {
        owner.clear(); work.close(); tasks.close()
        root.toFile().deleteRecursively()
    }
}

private class NavigationFileExecutor : Executor {
    val pending = mutableListOf<Runnable>()
    override fun execute(command: Runnable) { pending += command }
    fun runNext() { Thread(pending.removeAt(0), "navigation-file-test").apply { start(); join() } }
}

private fun navigationFileFailure(future: CompletableFuture<*>) =
    CivsDiagnostics.rejection(shouldThrow<CompletionException> { future.join() })?.reason
private fun navigationFileReceipt(line: String) = JsonParser.parseString(line.removePrefix("ARCCIVS_NAVIGATION_FILE ")).asJsonObject

/** Deserialize only the artifact, then use the actual private search class; no original object supplies replay data. */
private fun replayNavigationCapture(json: JsonObject) {
    val gson = Gson()
    fun point(value: JsonElement) = gson.fromJson(value, CitizenPoint::class.java)
    val context = json["context"].asJsonObject
    val settings = gson.fromJson(json["settings"], CivsNavigationSettings::class.java)
    val origin = point(context["origin"])
    val target = point(context["target"])
    val bounds = CitizenSearchBounds.between(origin, target, settings.initialHorizontalMargin, settings.initialVerticalMargin)
    fun probe(value: JsonElement): CitizenSearchProbe = value.asJsonObject.let {
        CitizenSearchProbe(gson.fromJson(it["node"], CitizenSearchNode::class.java), point(it["from"]), it["incoming"].asBoolean,
            bounds = bounds.takeIf { _ -> it["initial_bounds"].asBoolean })
    }
    val palette = json["palette"].asJsonArray.map { entry -> entry.asJsonObject.let {
        CitizenTerrainBlock(it["loaded"].asBoolean, it["liquid"].asBoolean, it["door"].asBoolean, it["climbable"].asBoolean,
            it["hot"].asBoolean, it["allowed"].asBoolean, if (it["cost"].isJsonNull) Double.POSITIVE_INFINITY else it["cost"].asDouble,
            it["boxes"].asJsonArray.map { box -> gson.fromJson(box, CitizenTerrainBox::class.java) })
    } }
    fun capture(value: JsonElement): CitizenSearchCapture = value.asJsonObject.let {
        val probe = probe(it["probe"])
        val node = probe.node
        val y = floor(node.y).toInt()
        val cells = it["cells"].asJsonArray
        var index = 0
        val blocks = buildMap {
            for (x in node.x - 1..node.x + 1) for (z in node.z - 1..node.z + 1) for (by in y - 3..y + 3)
                put(CivPosition(x, by, z), palette[cells[index++].asInt])
        }
        CitizenSearchCapture(probe, blocks, it["edges"].asJsonArray.map { edge -> gson.fromJson(edge, CitizenSearchEdge::class.java) })
    }
    fun signature(probe: CitizenSearchProbe) = listOf(probe.node, probe.from, probe.incoming, probe.bounds != null)
    val search = CitizenNavigationSearch(origin, target, json["minimum_y"].asInt, json["maximum_y"].asInt, emptyList(), settings)
    var last: CitizenSearchResult? = null
    for (round in json["rounds"].asJsonArray.map(JsonElement::getAsJsonObject)) {
        val replay = search.advance(round["captures"].asJsonArray.map(::capture))
        replay.nodes shouldBe round["nodes"].asInt
        replay.probes.map(::signature) shouldBe round["probes"].asJsonArray.map(::probe).map(::signature)
        last = replay
    }
    val terminal = json["terminal"].asJsonObject
    if (!terminal["budget_call"].isJsonNull) last = search.finishAtBudget(terminal["budget_call"].asString)
    requireNotNull(last).nodes shouldBe terminal["nodes"].asInt
    last.failure shouldBe terminal["failure"].asString
    last.path shouldBe null
    last.continuation shouldBe false
    last.blockedPositions shouldBe terminal["blocked_positions"].asJsonArray.map { gson.fromJson(it, CivPosition::class.java) }
}
