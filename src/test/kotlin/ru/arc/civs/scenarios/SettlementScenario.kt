package ru.arc.civs.scenarios

import com.google.gson.JsonParser
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Location
import org.bukkit.block.data.BlockData
import org.bukkit.util.BoundingBox
import ru.arc.civs.*
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import org.mockbukkit.mockbukkit.world.WorldMock

/** Small domain builder: real authored worksite geometry, explicit synthetic supporting buildings. */
internal class ScenarioTown(private val worldId: UUID, val center: CivPosition) {
    val buildings = linkedMapOf<String, CivBuilding>()
    val workers = linkedMapOf<String, CivWorker>()
    val plans = mutableListOf<CivPlacementPlan>()
    var stock = Stock(0, 96, 1)
    var minePlans: Map<String, CivMinePlanState>? = null
    var sourceCity: Settlement? = null

    fun blueprint(name: String, role: String, anchor: CivPosition, rotation: Int = 0): CivBuilding =
        spatialBuilding("caledonia", role, 1, anchor, rotation).copy(id = id(name), control = null).also {
            buildings[name] = it
        }

    fun support(name: String, type: BuildingType, anchor: CivPosition): CivBuilding = CivBuilding(
        id(name), type, 1, anchor, 0, "scenario/$name", CivBox(anchor, anchor.copy(x = anchor.x + 2, y = anchor.y + 4, z = anchor.z + 2)),
        anchor.copy(x = anchor.x + 1, y = anchor.y + 1, z = anchor.z - 1), BuildingPhase.ACTIVE,
    ).also { buildings[name] = it }

    fun resident(name: String, building: CivBuilding): CivWorker =
        CivWorker(id(name), name, building.id, nextDeliveryAt = 1).also { workers[name] = it }

    fun road(name: String, floor: CivPosition): CivBuilding = support(name, BuildingType.ROAD, floor).let {
        it.copy(bounds = CivBox(floor, floor.copy(y = floor.y + 2)), entrance = floor.copy(y = floor.y + 1),
            transit = listOf(floor.copy(y = floor.y + 1))).also { road ->
            buildings[name] = road
            plans += CivPlacementPlan(road.id, id("town"), worldId, road,
                listOf(CivPlanBlock(floor, "minecraft:dirt", "minecraft:cobblestone")))
        }
    }

    fun build(): Settlement = sourceCity ?: CivsRules.validate(Settlement(id("town"), id("owner"), "Auto2 scenario", worldId,
        center, 128, "caledonia", stock, buildings.values.toList(), workers.values.toList(),
        autonomy = CivAutonomy(), minePlans = minePlans,
        forestrySeeded = buildings.values.filter { it.type == BuildingType.FORESTER }.mapTo(linkedSetOf()) { it.id.toString() }))

    private fun id(name: String) = UUID.nameUUIDFromBytes("arccivs/auto2-scenario/$name".toByteArray(Charsets.UTF_8))
}

/** Actual service/store/receipt orchestration. Arrival is explicitly supplied; this does not simulate NPC walking.
 * The bounded dirt plane outside seeded cells is synthetic. No schematic payload or private asset is embedded. */
internal class SettlementScenario private constructor(
    val paper: MockBukkitTestRuntime,
    val town: ScenarioTown,
    val world: WorldMock,
    citizenRuntime: CivsCitizens? = null,
    initialAt: Long = 1_000_000L,
    private val clockObserver: (Long) -> Unit = {},
) : AutoCloseable {
    private val root = Files.createTempDirectory("arccivs-settlement-scenario-")
    private val plugin = paper.createSimplePlugin("SettlementScenario")
    private val io = ScenarioIo()
    private var tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    private var store = CivsStore(root, io)
    private val actualResidents = citizenRuntime != null
    private var citizens = citizenRuntime ?: mockk<CivsCitizens>(relaxed = true)
    private val blueprints = mockk<CivsBlueprints>()
    private val renderer = mockk<CivsPreviewRenderer>(relaxed = true)
    private val boundary = mockk<CivsBoundaryRenderer>(relaxed = true)
    private val trace = ArrayDeque<String>()
    var now = initialAt
    private var service: CivsService
    private var worksites: CivsWorksites
    private var roads: CivsRoadMaintenance
    val city: Settlement get() = service.settlements().single()
    val pendingIo: Int get() = io.pending

    init {
        if (!actualResidents) {
            every { citizens.closeAsync() } returns CompletableFuture.completedFuture(null)
            every { citizens.autonomousVisualWork(any(), any()) } returns null
            every { citizens.pendingStockDelivery(any(), any()) } returns null
        }
        every { renderer.drained() } returns CompletableFuture.completedFuture(null)
        every { boundary.drained() } returns CompletableFuture.completedFuture(null)
        // This scenario never asks the development planner to select a random catalog family.
        every { blueprints.availableStyles(any()) } returns emptyList()
        if (actualResidents) every { blueprints.autonomousSource(any(), any()) } returns null
        every { blueprints.maximumLevel(any()) } answers { firstArg<CivBuilding>().type.maxLevel }
        paper.addPlayer("ScenarioObserver").also { observer ->
            observer.teleport(Location(world, town.center.x + .5, town.center.y.toDouble(), town.center.z + .5))
            observer.openInventory(Bukkit.createInventory(null, 9))
        }
        val initial = town.build()
        if (actualResidents && initial.revision > 0) {
            // Import an explicitly supplied local snapshot as an existing city, preserving its revision/items.
            CivsRules.validate(initial)
            val directory = Files.createDirectories(root.resolve("cities"))
            Files.writeString(directory.resolve("${initial.id}.json"), com.google.gson.Gson().toJson(
                mapOf("schemaVersion" to CivsStore.SCHEMA_VERSION, "city" to initial)))
        } else await(store.save(initial))
        town.plans.forEach { await(store.savePlan(it)) }
        service = createService()
        worksites = serviceField("worksites")
        roads = serviceField("roadMaintenance")
        pinWorkBudget()
        await(service.start())
        record("started revision=${city.revision} stock=${city.stock}")
    }

    private fun createService(): CivsService = CivsService(plugin, tasks,
        CivsSettings(root.resolve("unused-private-schematics"), setOf(world.name), 128, 10, 50_000, 4, 120, 48, 96),
        store, blueprints, Executor(Runnable::run), previewRenderer = renderer, citizenRuntime = citizens,
        clock = { now }, boundaryRenderer = boundary, storageAdapter = CivsStorage(plugin) { emptyArray() },
        supplyExecutor = Executor(Runnable::run)).also { created ->
            if (actualResidents) {
                // Plan-board packets are unrelated to resident arrival/receipts; retain its spatial guards.
                val boards = mockk<CivsPlanBoardRenderer>(relaxed = true)
                every { boards.closeAsync() } returns CompletableFuture.completedFuture(null)
                CivsService::class.java.getDeclaredField("planBoards").also { it.isAccessible = true }.set(created, boards)
            }
        }

    /** Same private callback seam used by CivsServiceTest; production API and scheduling stay untouched. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> serviceField(name: String): T = CivsService::class.java.getDeclaredField(name)
        .also { it.isAccessible = true }.get(service) as T

    private fun pinWorkBudget() {
        // Avoid MockK/JIT wall time deciding whether a bounded scan gets another slice.
        CivsWorksites::class.java.getDeclaredField("nanoTime").also { it.isAccessible = true }.set(worksites, { 0L })
    }

    fun nextWork(resident: String, maximumPulses: Int = 2_000): CivWorldWork {
        val workerId = town.workers.getValue(resident).id
        repeat(maximumPulses) {
            io.runNext()
            val current = city
            val worker = current.workers.single { it.id == workerId }
            worksites.pulse(listOf(current))
            val next = if (current.building(worker.assignment!!)?.type == BuildingType.BUILDER_HUT)
                roads.plan(current, worker) else worksites.plan(current, worker)
            if (next != null) {
                record("planned $resident ${next.status} cycle=${next.cycle} mine=${next.miningStep} focus=${next.focus} changes=${next.changes.size}")
                return next
            }
            tick()
        }
        error("No work for $resident within $maximumPulses pulses\n${diagnostics()}")
    }

    @Suppress("UNCHECKED_CAST")
    fun observedCompletion(work: CivWorldWork, nextAt: Long = city.workers.single { it.id == work.workerId }.nextDeliveryAt): CompletableFuture<Settlement> {
        val worker = city.workers.single { it.id == work.workerId }
        now = maxOf(now, worker.nextDeliveryAt) + work.durationMillis
        if (work.autonomousVisual) every { citizens.autonomousVisualWork(city.id, worker.id) } returns work
        record("observed completion ${work.status} before=${city.revision} io=${io.pending}")
        val pending = delivery.invoke(service, city.id, worker.id, work.buildingId, work.cycle,
            nextAt, null, now) as CompletableFuture<Settlement>
        pending.whenComplete { saved, failure -> record("completion result revision=${saved?.revision} " +
            "failure=${failure?.let { CivsDiagnostics.rejection(it)?.reason ?: it }}") }
        return pending
    }

    fun complete(work: CivWorldWork): Settlement = await(observedCompletion(work)).also {
        record("committed ${work.status} revision=${it.revision} stock=${it.stock}")
    }

    /** The actual CivsCitizens callback supplies its own observed work and arrival proof. */
    @Suppress("UNCHECKED_CAST")
    fun residentDelivery(workerId: UUID, token: CitizenToken, at: Long): CompletableFuture<Settlement> {
        check(actualResidents)
        now = at
        record("resident delivery worker=$workerId cycle=${token.completedCycles}")
        return delivery.invoke(service, city.id, workerId, token.assignmentId, token.completedCycles,
            token.nextDeliveryAt, token.constructionId, at) as CompletableFuture<Settlement>
    }

    fun advanceProductionTicks(ticks: Int) {
        require(ticks in 0..24_000)
        repeat(ticks) {
            now += 50
            clockObserver(now)
            io.runNext()
            tick()
        }
    }

    fun workPlan(city: Settlement, worker: CivWorker): CivWorldWork? = worksites.plan(city, worker)
    fun workIdleReason(city: Settlement, worker: CivWorker): String = worksites.idleReason(city, worker)
    fun rejectedStanding(city: Settlement, worker: CivWorker, work: CivWorldWork): Boolean = worksites.rejectStanding(city, worker, work)
    fun worksiteDebug(workerId: UUID): Map<String, Any?> = worksites.debug(setOf(workerId))
    fun supplyDebug(workerId: UUID): Map<String, Any?> = serviceField<CivsSupplyDispatcher>("supply").debug(city.id, workerId)
    fun courierPlan(city: Settlement, worker: CivWorker): CivCourierPlan? = service.courierPlan(city, worker)
    fun pickupCargo(cityId: UUID, workerId: UUID, cargo: CivCargo): CompletableFuture<Settlement> =
        service.pickupCargo(cityId, workerId, cargo)
    fun dropoffCargo(cityId: UUID, workerId: UUID, cargo: CivCargo, destination: UUID): CompletableFuture<Settlement> =
        service.dropoffCargo(cityId, workerId, cargo, destination)

    /** One real queued filesystem operation, without running the next server callback. */
    fun persistOne(): Boolean = io.runNext().also { record("filesystem step pending=${io.pending}") }

    fun savedCity(): Settlement = CivsStore(root, Executor(Runnable::run)).use { it.load().join().single() }
    fun receipts(): List<CivInventoryTransaction> = CivsStore(root, Executor(Runnable::run)).use { it.loadInventoryTransactions().join() }

    fun restart() {
        check(!actualResidents) { "A real resident fixture needs a fresh packet/NPC owner when restarted" }
        restartService()
    }

    /** Store, Service and Citizens recreation in the same fixture world/JVM, not a process crash. */
    fun restartResidents(runtime: ResidentRuntime) {
        check(actualResidents && citizens === runtime.owner)
        restartService {
            runtime.recreateResidents()
            citizens = runtime.owner
            runtime.city = savedCity()
        }
    }

    private fun restartService(recreateResidents: () -> Unit = {}) {
        record("restart diskRevision=${savedCity().revision} receipts=${receipts().map { it.id }}")
        service.close()
        tasks.close()
        store.close()
        io.discardPending()
        recreateResidents()
        tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
        store = CivsStore(root, io)
        service = createService()
        worksites = serviceField("worksites")
        roads = serviceField("roadMaintenance")
        pinWorkBudget()
        if (!actualResidents) every { citizens.autonomousVisualWork(any(), any()) } returns null
        await(service.start())
        // Startup exposes records before receipt recovery; wait on the durable owner, not a fixed delay.
        advanceUntil("native receipt retirement") { receipts().isEmpty() }
        record("recovered revision=${city.revision} stock=${city.stock}")
    }

    fun snapshot(points: Iterable<CivPosition>): Map<CivPosition, String> = points.associateWith(::state)
    fun state(point: CivPosition): String = world.getBlockAt(point.x, point.y, point.z).blockData.asString
    fun put(point: CivPosition, state: String) { world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(state) }
    fun unchanged(before: Map<CivPosition, String>) {
        val changed = before.keys.firstOrNull { state(it) != before.getValue(it) }
        check(changed == null) { "Changed scenario cell $changed: expected ${before[changed]}, actual ${state(requireNotNull(changed))}" }
    }

    fun advanceUntil(description: String, condition: () -> Boolean) {
        repeat(2_000) {
            if (condition()) return
            io.runNext()
            tick()
        }
        error("$description did not finish within 2000 steps\n${diagnostics()}")
    }

    fun <T> await(future: CompletableFuture<T>): T {
        advanceUntil("future") { future.isDone }
        return future.join()
    }

    private fun tick() { paper.performTicks(1) }
    private fun record(value: String) { if (trace.size == 64) trace.removeFirst(); trace.addLast("$now $value") }
    fun diagnostics(): String = "Scenario trace:\n${trace.joinToString("\n")}\n" +
        "io=${io.pending} worksites=${worksites.debug(town.workers.values.mapTo(hashSetOf()) { it.id })}"

    override fun close() {
        service.close()
        tasks.close()
        store.close()
        root.toFile().deleteRecursively()
    }

    companion object {
        private val delivery = CivsService::class.java.getDeclaredMethod("deliver", UUID::class.java, UUID::class.java,
            UUID::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE, UUID::class.java, java.lang.Long.TYPE)
            .also { it.isAccessible = true }

        fun attachResidents(runtime: ResidentRuntime, source: Settlement, plans: List<CivPlacementPlan>,
            initialAt: Long = 1_000_000L): SettlementScenario {
            val town = ScenarioTown(runtime.world.uid, source.center)
            town.sourceCity = source
            source.buildings.forEach { town.buildings[it.id.toString()] = it }
            source.workers.forEach { town.workers[it.name] = it }
            town.plans += plans
            return SettlementScenario(runtime.paper, town, runtime.world, runtime.owner, initialAt) { runtime.navigationNow = it }
        }

        fun run(name: String, center: CivPosition, capture: RecordedNativeMap? = null,
            setup: ScenarioTown.(CivsWorksiteWorldFixture) -> Unit,
            scenario: SettlementScenario.() -> Unit) = failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture(name, Material.DIRT, capture?.let { it::collision }).also(paper.server::addWorld)
                val town = ScenarioTown(world.uid, center).also { it.setup(world) }
                SettlementScenario(paper, town, world).use { run ->
                    try { run.scenario() }
                    catch (failure: Throwable) { throw AssertionError(run.diagnostics(), failure) }
                }
            }
        }
    }
}

/** Replays the captured states and native collision boxes only; unknown terrain is not inferred. */
internal class RecordedNativeMap private constructor(fixture: com.google.gson.JsonObject) {
    constructor(path: String, sha256: String) : this(JsonParser.parseString(pinnedScenarioResource(path, sha256)).asJsonObject)
    private val minimum = fixture.getAsJsonObject("bounds").getAsJsonObject("min")
    private val palette = fixture.getAsJsonArray("palette").map { it.asJsonObject }
    val states: Map<CivPosition, String> = fixture.getAsJsonArray("voxels").associate { encoded ->
        val cell = encoded.asJsonArray
        CivPosition(minimum["x"].asInt + cell[0].asInt, minimum["y"].asInt + cell[1].asInt,
            minimum["z"].asInt + cell[2].asInt) to palette[cell[3].asInt]["block_data"].asString
    }
    val unknownOpaqueIdentity: Set<CivPosition> = fixture.getAsJsonArray("voxels").mapNotNull { encoded ->
        val cell = encoded.asJsonArray
        if (cell.size() <= 5 || !cell[5].asBoolean) null else CivPosition(minimum["x"].asInt + cell[0].asInt,
            minimum["y"].asInt + cell[1].asInt, minimum["z"].asInt + cell[2].asInt)
    }.toSet()
    private val columns = states.keys.groupBy { it.x to it.z }.mapValues { (_, points) -> points.sortedByDescending { it.y } }
    val loadedChunks: Set<Pair<Int, Int>> = fixture.getAsJsonArray("voxels").mapNotNull { encoded ->
        val cell = encoded.asJsonArray
        if (cell.size() > 4 && !cell[4].asBoolean) null else
            ((minimum["x"].asInt + cell[0].asInt) shr 4) to ((minimum["z"].asInt + cell[2].asInt) shr 4)
    }.toSet()
    private val collisions = palette.mapNotNull { shape -> shape.getAsJsonArray("collision")?.let { boxes ->
        shape["block_data"].asString to boxes.map { encoded -> encoded.asJsonArray.let {
            BoundingBox(it[0].asDouble, it[1].asDouble, it[2].asDouble, it[3].asDouble, it[4].asDouble, it[5].asDouble)
        } }
    } }.toMap()

    fun collision(data: BlockData): List<BoundingBox>? = collisions[data.asString]
    fun column(x: Int, z: Int): List<CivPosition> = columns[x to z]
        ?: error("Missing native capture column x=$x z=$z")
    fun hasColumn(x: Int, z: Int): Boolean = x to z in columns
    fun seed(world: CivsWorksiteWorldFixture) {
        states.forEach { (point, state) -> world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(state) }
    }

    companion object {
        fun fromResourceRle(path: String, sha256: String): RecordedNativeMap {
            val encoded = JsonParser.parseString(pinnedScenarioResource(path, sha256)).asJsonObject
            val version = encoded["version"].asInt
            check(version in 1..2 && encoded["order"].asString == "y,z,x")
            val bounds = com.google.gson.Gson().fromJson(encoded["bounds"], CivBox::class.java)
            fun extent(min: Int, max: Int): Int {
                val value = max.toLong() - min.toLong() + 1
                check(value in 1..2_000_000) { "Invalid terrain fixture bounds" }
                return value.toInt()
            }
            val width = extent(bounds.min.x, bounds.max.x)
            val depth = extent(bounds.min.z, bounds.max.z)
            val height = extent(bounds.min.y, bounds.max.y)
            val volume = width.toLong() * depth * height
            check(volume <= 2_000_000) { "Terrain fixture is too large" }
            val runs = encoded.getAsJsonArray("runs")
            val palette = encoded.getAsJsonArray("palette")
            val voxels = com.google.gson.JsonArray()
            var index = 0
            check(runs.size() % 2 == 0)
            for (run in 0 until runs.size() step 2) {
                val count = runs[run].asInt; val value = runs[run + 1].asInt
                check(count > 0 && (value in 0 until palette.size() || version == 2 && value == -1) && index.toLong() + count <= volume)
                repeat(count) {
                    if (value >= 0) voxels.add(com.google.gson.JsonArray().also { cell ->
                        cell.add(index % width); cell.add(index / (width * depth)); cell.add(index / width % depth)
                        cell.add(value); cell.add(true)
                        val shape = palette[value].asJsonObject
                        cell.add(if (version == 1) shape["block_data"].asString == "minecraft:stone"
                            else shape["unknown_opaque_identity"]?.asBoolean ?: false)
                    })
                    index++
                }
            }
            check(index.toLong() == volume)
            val fixture = com.google.gson.JsonObject()
            fixture.add("bounds", encoded["bounds"]); fixture.add("palette", palette); fixture.add("voxels", voxels)
            return RecordedNativeMap(fixture)
        }

        fun fromFile(path: Path, sha256: String): RecordedNativeMap {
            check(Files.isRegularFile(path)) { "Missing explicitly requested native capture: $path" }
            val bytes = Files.readAllBytes(path)
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(actual == sha256) { "Native capture hash mismatch for $path: expected $sha256, got $actual" }
            return RecordedNativeMap(JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject)
        }

        /** Public scene RLE preserves visible states and opaque geometry, not hidden block identities.
         * Exact native state colliders come from the already-pinned atelier facts, never Material.isSolid(). */
        fun fromPublicRle(root: Path, nativeFacts: Path, bounds: CivBox,
            nativeLowerCaptures: List<com.google.gson.JsonObject> = emptyList(), nativeWorldId: UUID? = null): RecordedNativeMap {
            val descriptor = JsonParser.parseString(Files.readString(root.resolve("terrain.json"))).asJsonObject
            check(!descriptor["stale"].asBoolean) { "Public terrain fixture was already stale at capture" }
            val factsBytes = Files.readAllBytes(nativeFacts)
            val factsHash = MessageDigest.getInstance("SHA-256").digest(factsBytes).joinToString("") { "%02x".format(it) }
            check(factsHash == "8958e27b69248f53aa3015793d345207e979987fe0401574a362b9a21912050f") {
                "Wrong pinned Purpur 1.21.11 native block facts: $factsHash"
            }
            val facts = JsonParser.parseString(factsBytes.toString(Charsets.UTF_8)).asJsonObject.getAsJsonObject("states")
            val fixture = com.google.gson.JsonObject()
            fixture.add("bounds", com.google.gson.Gson().toJsonTree(bounds))
            val palette = com.google.gson.JsonArray()
            val paletteIds = linkedMapOf<String, Int>()
            fun paletteId(state: String): Int = paletteIds.getOrPut(state) {
                val shape = com.google.gson.JsonObject()
                shape.addProperty("block_data", state)
                // Preserve unrelated visible states even when the existing facts have no sample.
                // Strict worlds refuse such a state if a collision read actually needs it.
                facts.getAsJsonObject(state)?.let { shape.add("collision", it.getAsJsonArray("boxes")) }
                palette.add(shape)
                palette.size() - 1
            }
            val voxels = com.google.gson.JsonArray()
            for (entry in descriptor.getAsJsonArray("chunks")) {
                val chunk = entry.asJsonObject
                val x = chunk["x"].asInt; val z = chunk["z"].asInt
                if (x * 16 > bounds.max.x || x * 16 + 15 < bounds.min.x ||
                    z * 16 > bounds.max.z || z * 16 + 15 < bounds.min.z) continue
                val source = root.resolve("chunk-$x-$z.json")
                check(Files.isRegularFile(source)) { "Missing public chunk fixture $source" }
                val data = JsonParser.parseString(Files.readString(source)).asJsonObject
                check(data["x"].asInt == x && data["z"].asInt == z)
                val minY = data["minY"].asInt; val height = data["height"].asInt
                val sourcePalette = data.getAsJsonArray("palette").map { it.asString }
                val runs = data.getAsJsonArray("runs")
                check(runs.size() % 2 == 0)
                var index = 0
                for (run in 0 until runs.size() step 2) {
                    val count = runs[run].asInt
                    val state = sourcePalette[runs[run + 1].asInt]
                    check(count > 0 && index.toLong() + count <= 16L * 16 * height)
                    repeat(count) {
                        val point = CivPosition(x * 16 + index % 16, minY + index / 256, z * 16 + index / 16 % 16)
                        if (bounds.contains(point.x, point.y, point.z)) voxels.add(com.google.gson.JsonArray().also { cell ->
                            cell.add(point.x - bounds.min.x); cell.add(point.y - bounds.min.y); cell.add(point.z - bounds.min.z)
                            cell.add(paletteId(state)); cell.add(true)
                            cell.add(data["occlusionEncoding"]?.asString == "opaque-fill" && state == "minecraft:stone")
                        })
                        index++
                    }
                }
                check(index == 16 * 16 * height) { "Incomplete public RLE in $source" }
            }
            // Retain facts for legal work after-states even if no current cell uses them.
            for (state in listOf("minecraft:air", "minecraft:oak_sapling[stage=0]", "minecraft:oak_sapling[stage=1]")) paletteId(state)
            val expected = (bounds.max.x - bounds.min.x + 1L) * (bounds.max.y - bounds.min.y + 1L) * (bounds.max.z - bounds.min.z + 1L)
            check(voxels.size().toLong() == expected) { "Requested public capture bounds have missing encoded cells: ${voxels.size()}/$expected" }
            fixture.add("palette", palette); fixture.add("voxels", voxels)
            val lowerCells = if (nativeLowerCaptures.isEmpty()) 0 else {
                val scene = descriptor["sceneId"].asString
                check(scene.startsWith("civs:")) { "Native lower capture requires a city scene" }
                appendNativeLowerCaptures(fixture, nativeLowerCaptures, UUID.fromString(scene.removePrefix("civs:")),
                    requireNotNull(nativeWorldId) { "Native lower capture requires the original source world UUID" })
            }
            val result = RecordedNativeMap(fixture)
            check(result.states.size.toLong() == expected + lowerCells) {
                "Requested public/native capture has missing or repeated cells: ${result.states.size}/${expected + lowerCells}"
            }
            return result
        }
    }
}

/** Queue only the real store's IO; the production service still owns every transition. */
private class ScenarioIo : Executor {
    private val pendingTasks = ArrayDeque<Runnable>()
    val pending get() = pendingTasks.size
    override fun execute(command: Runnable) { pendingTasks.addLast(command) }
    fun runNext(): Boolean = pendingTasks.removeFirstOrNull()?.let { it.run(); true } ?: false
    fun discardPending() { pendingTasks.clear() }
}

internal fun pinnedScenarioResource(path: String, sha256: String): String {
    val bytes = requireNotNull(SettlementScenario::class.java.getResourceAsStream(path)).use { it.readBytes() }
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } shouldBe sha256
    return bytes.toString(Charsets.UTF_8)
}
