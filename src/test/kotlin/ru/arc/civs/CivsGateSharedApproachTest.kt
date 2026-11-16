package ru.arc.civs

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.util.BoundingBox
import org.mockbukkit.mockbukkit.block.BlockMock
import org.mockbukkit.mockbukkit.block.data.BlockDataMock
import org.mockbukkit.mockbukkit.world.Coordinate
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.chunk.PaperChunkKey
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class CivsGateSharedApproachTest : FunSpec({
    test("an unloaded earlier gate does not starve the second eligible gate and candidate capacity stays bounded") {
        SharedGateFixture().use { f ->
            fun shifted(p: CivPosition) = p.copy(z = p.z - 60)
            fun moved(b: CivBuilding, id: UUID) = b.copy(id = id, anchor = shifted(b.anchor),
                bounds = CivBox(shifted(b.bounds.min), shifted(b.bounds.max)), entrance = shifted(b.entrance),
                control = b.control?.let(::shifted), transit = b.transit?.map(::shifted),
                schematicId = if (b.type == BuildingType.GATE) b.schematicId.replace(b.id.toString(), id.toString()) else b.schematicId)
            val earlyGate = moved(requireNotNull(f.city.building(f.gate)), UUID(318, 90))
            val earlyDonor = moved(requireNotNull(f.city.building(f.forester)), UUID(318, 91))
            listOf(earlyGate, earlyDonor).all { a -> f.city.buildings.none { b ->
                CivsAutonomousGeometry.overlaps(a.bounds, b.bounds) } } shouldBe true
            val added = listOf(f.gate to earlyGate, f.forester to earlyDonor).map { (id, b) ->
                f.plans.single { it.building.id == id }.let { p -> p.copy(id = b.id, building = moved(p.building, b.id),
                    terrain = p.terrain!!.let { t -> t.copy(bounds = CivBox(shifted(t.bounds.min), shifted(t.bounds.max)),
                        route = t.route.map(::shifted), blocks = t.blocks.map { it.copy(position = shifted(it.position)) }) }) }
            }
            f.city = CivsRules.validate(f.city.copy(buildings = listOf(earlyGate, earlyDonor) + f.city.buildings))
            val access = CivAutonomousAccess.from(f.city, added + f.plans)
            val proposals = CivsGateSharedApproaches.candidates(f.city, access)
            proposals.map { CivsGateSharedApproaches.proof(it)!!.source.id } shouldBe listOf(earlyGate.id, f.gate)
            val targets = CivsRoadMaintenance.prepare(f.city, access).targets.filter {
                it.source.id == earlyGate.id || it.source.id == f.gate }
            targets.first().source.id shouldBe earlyGate.id
            f.capture(targets.first()) shouldBe null // Its native chunk is unavailable; no synthetic blocks are added.
            targets.firstNotNullOfOrNull { f.capture(it) }!!.upkeep!!.source.id shouldBe f.gate
            // The admission cap depends only on occupied override slots, not unrelated owners' geometry.
            val occupied = (1 until CivsRules.MAX_BUILDINGS).associate { UUID(319, it.toLong()).toString() to proposals.first() }
            CivsGateSharedApproaches.candidates(f.city.copy(roadRouteOverrides = occupied), access).size shouldBe 1
            CivsGateSharedApproaches.candidates(f.city.copy(roadRouteOverrides = occupied +
                (UUID(319, 99).toString() to proposals.first())), access) shouldBe emptyList()
        }
    }

    test("durable shared-route recovery loads the walk-only chunk before and after city publication") {
        for (published in listOf(false, true)) SharedGateFixture().use { f ->
            val before = f.city
            val target = CivsGateSharedApproaches.targets(before, CivAutonomousAccess.from(before, f.plans)).first()
            val work = requireNotNull(f.capture(target)).let { it.copy(inputs = CivsRoadMaintenance.inputs(before, it)) }
            val after = CivsRoadMaintenance.complete(before, work, 3_000L)
            val tx = CivInventoryTransaction(UUID(318, 80), before.id, before.revision, after, emptyList(),
                worldChanges = work.changes, upkeep = CivRoadUpkeepReceipt(before.workers.single(), before.stock, work, 3_000L)).validated()
            if (published) f.city = after
            f.recovery(tx).use { recovery ->
                val walkOnly = PaperChunkKey(before.worldId, 767, 810)
                work.changes.none { it.position.x shr 4 == walkOnly.x && it.position.z shr 4 == walkOnly.z } shouldBe true
                f.world.unloadChunk(walkOnly.x, walkOnly.z) shouldBe true
                f.await(recovery.journal.bootstrap())
                recovery.journal.recover()
                f.ticks()
                recovery.quarantined shouldBe true
                recovery.journal.blockedCount shouldBe 0
                recovery.journal.pendingCount shouldBe 1
                recovery.verifications shouldBe 1
                work.changes.forEach { f.state(it.position) shouldBe it.before }
                f.city shouldBe if (published) after else before
                val chunks = recovery.journal.pendingNativeChunks().single()
                chunks shouldBe setOf(walkOnly, PaperChunkKey(before.worldId, 767, 811))
                chunks.forEach { f.world.loadChunk(it.x, it.z) }
                recovery.journal.recover()
                f.ticks()
                f.city shouldBe after
                work.changes.forEach { f.state(it.position) shouldBe it.after }
                recovery.journal.pendingCount shouldBe 0
                recovery.journal.pendingNativeChunks() shouldBe emptyList()
                recovery.quarantined shouldBe false
                recovery.retirements shouldBe 1
                recovery.verifications shouldBe 2
                CivsEquipment.usableCount(f.city.workers.single(), "COBBLESTONE") shouldBe 63
                f.city.workers.single().completedCycles shouldBe 1L
            }
        }
    }

    test("published shared-route recovery still rejects a changed immutable donor source") {
        SharedGateFixture().use { f ->
            val before = f.city
            val target = CivsGateSharedApproaches.targets(before, CivAutonomousAccess.from(before, f.plans)).first()
            val work = requireNotNull(f.capture(target)).let { it.copy(inputs = CivsRoadMaintenance.inputs(before, it)) }
            val after = CivsRoadMaintenance.complete(before, work, 3_000L)
            val tx = CivInventoryTransaction(UUID(318, 81), before.id, before.revision, after, emptyList(),
                worldChanges = work.changes, upkeep = CivRoadUpkeepReceipt(before.workers.single(), before.stock, work, 3_000L)).validated()
            f.city = after
            work.changes.forEach { f.put(it.position, it.after) }
            val changed = f.plans.map { if (it.building.id == f.forester) it.copy(capturedSha256 = "c".repeat(64)) else it }
            f.recovery(tx, changed).use { recovery ->
                f.await(recovery.journal.bootstrap())
                recovery.journal.recover()
                f.ticks()
                recovery.verifications shouldBe 1
                recovery.retirements shouldBe 0
                recovery.journal.pendingCount shouldBe 1
                recovery.journal.blockedCount shouldBe 1
                recovery.quarantined shouldBe true
                f.city shouldBe after
            }
        }
    }

    test("captured Auto5 pair uses the accepted Hall flight and retires only its exact obsolete gate pavement") {
        SharedGateFixture().use { f ->
            val before = f.city
            val access = CivAutonomousAccess.from(before, f.plans)
            val repair = CivsGateSharedApproaches.candidates(before, access).single()
            repair.originalRoute.size shouldBe 16
            repair.route.size shouldBe 20
            repair.route.first() shouldBe repair.originalRoute.first()
            repair.route.last() shouldBe repair.originalRoute.last()
            repair.route.takeLast(3) shouldBe listOf(CivPosition(12285,65,12980), CivPosition(12285,65,12979), CivPosition(12285,65,12978))
            repair.runs() shouldBe emptyList()
            val proof = requireNotNull(CivsGateSharedApproaches.proof(repair))
            for (rotation in 0..3) {
                fun transform(p: CivPosition): CivPosition {
                    var x = p.x - before.center.x; var z = p.z - before.center.z
                    repeat(rotation) { val prior = x; x = -z; z = prior }
                    return CivPosition(x - 191, p.y + 4, z + 707)
                }
                CivsGateSharedApproaches.replacement(repair.originalRoute.map(::transform), proof.donorRoute.map(::transform),
                    proof.donorTravel.map(::transform)) shouldBe repair.route.map(::transform)
            }
            (roadHalfStepWalk(f.world, repair.route) != null) shouldBe true
            (roadHalfStepWalk(f.world, repair.route.asReversed()) != null) shouldBe true
            roadHalfStepWalk(f.world, listOf(CivPosition(12277,65,12978), CivPosition(12277,65,12979), CivPosition(12277,65,12980))) shouldBe null
            val original = f.measured.associateWith(f::state)
            val planImage = Gson().toJson(f.plans)
            val targets = CivsRoadMaintenance.prepare(before, access).targets.filter { it.source.id == f.gate }
            targets.first().floor shouldBe CivPosition(12285,64,12979)
            val cleared = targets.filter { it.wallCleanup != null }.map { it.floor }.toSet()
            cleared shouldBe (12279..12283).map { CivPosition(it,64,12978) }.toSet()
            var paid = 0
            val written = hashSetOf<CivPosition>()
            for (pass in 0..10) {
                val next = CivsRoadMaintenance.prepare(f.city, f.plans).targets.filter { it.source.id == f.gate }
                    .firstNotNullOfOrNull { f.capture(it) } ?: break
                val cityBefore = f.city
                val work = next.copy(inputs = CivsRoadMaintenance.inputs(cityBefore, next))
                val after = CivsRoadMaintenance.complete(cityBefore, work, 3_000L + paid * 4_000)
                val receipt = CivRoadUpkeepReceipt(cityBefore.workers.single(), cityBefore.stock, work, 3_000L + paid * 4_000)
                val tx = CivInventoryTransaction(UUID(318, paid.toLong() + 1), cityBefore.id, cityBefore.revision,
                    after, emptyList(), worldChanges = work.changes, upkeep = receipt).validated()
                Gson().fromJson(Gson().toJson(tx), CivInventoryTransaction::class.java).validated() shouldBe tx
                receipt.before(after) shouldBe cityBefore
                f.native.validateRoadUpkeep(cityBefore, work)
                for (change in work.changes) {
                    f.put(change.position, change.after)
                    written += change.position
                    // Every partial native apply can replay while the old city is still durable.
                    f.native.validateRoadUpkeep(cityBefore, work)
                }
                // The same receipt remains recoverable after its after-city is already published.
                f.native.validateRoadUpkeep(after, work)
                f.city = after
                paid++
            }
            paid shouldBe cleared.size + 1
            f.city.roadRouteOverrides?.get(f.gate.toString()) shouldBe repair
            f.city.workers.single().completedCycles shouldBe paid.toLong()
            CivsEquipment.usableCount(f.city.workers.single(), "COBBLESTONE") shouldBe 64 - paid
            cleared.forEach { f.state(it) shouldBe CivsEntrancePath.GRASS }
            for ((p, state) in original) if (p !in written) f.state(p) shouldBe state
            Gson().toJson(f.plans) shouldBe planImage
            val finalAccess = CivAutonomousAccess.from(f.city, f.plans)
            CivsRoadMaintenance.prepare(f.city, finalAccess).targets.filter { it.source.id == f.gate }
                .all { f.capture(it) == null } shouldBe true
            civsPlanBoardSnapshot(f.city, f.plans).routes.getValue(f.gate) shouldBe repair.route
            civsPlanBoardSnapshot(f.city, f.plans).routes.getValue(f.forester) shouldBe requireNotNull(access.hallApproach(before)).travel(access.routes.getValue(f.forester))
            val adopted = CivSettlementPlan(1, 1, 48, emptyList(), mapOf(f.gate.toString() to repair.originalRoute,
                f.forester.toString() to proof.donorRoute), emptyList(), listOf(proof.source.bounds), listOf(0))
            val withPlan = f.city.copy(autonomy = f.city.autonomy!!.copy(plan = adopted))
            CivsSettlementPlans.current(withPlan) shouldBe adopted
            val board = civsPlanBoardSnapshot(withPlan, f.plans)
            board.plan!!.routes.getValue(f.gate.toString()) shouldBe repair.route
            val drawn = (board.routes.values + board.plan!!.routes.values).flatten().mapTo(hashSetOf()) { it.x to it.z }
            cleared.none { (it.x to it.z) in drawn } shouldBe true
            // The old adopted geometry would redraw the removed branch as a future sketch.
            CivsPlanBoardGeometry.layout(board.copy(plan = adopted)).patches.any { it.key.startsWith("future-layout") } shouldBe true
            CivsPlanBoardGeometry.layout(board).patches.none { it.key.startsWith("future-layout") } shouldBe true
            val futureId = UUID(318, 99).toString()
            val futureRoute = listOf(before.center.copy(z = before.center.z + 20), before.center.copy(x = before.center.x + 1, z = before.center.z + 20))
            val futurePlan = adopted.copy(routes = adopted.routes + (futureId to futureRoute))
            val futureCity = withPlan.copy(autonomy = withPlan.autonomy!!.copy(plan = futurePlan))
            val futureBoard = civsPlanBoardSnapshot(futureCity, f.plans)
            futureBoard.plan!!.routes.getValue(futureId) shouldBe futureRoute
            CivsPlanBoardGeometry.layout(futureBoard).patches.any { it.key.startsWith("future-layout") } shouldBe true
            CivsSettlementPlans.current(withPlan) shouldBe adopted
            CivsSettlementPlans.current(futureCity) shouldBe futurePlan
            val preferred = CivsPaths.RetainedRoads.from(f.city, finalAccess)
            (CivsGateSharedApproaches.connector(repair) in preferred) shouldBe true
        }
    }

    test("a stale donor capture or geometry and a pending construction never licenses the shared approach") {
        SharedGateFixture().use { f ->
            val access = CivAutonomousAccess.from(f.city, f.plans)
            val repair = CivsGateSharedApproaches.candidates(f.city, access).single()
            val proof = requireNotNull(CivsGateSharedApproaches.proof(repair))
            val stale = f.city.copy(buildings = f.city.buildings.map { if (it.id == f.forester) it.copy(level = it.level + 1) else it })
            CivsGateSharedApproaches.current(stale, repair, access) shouldBe false
            val captures = access.sharedRoadCaptures.toMutableMap()
            captures[f.forester] = captures.getValue(f.forester).let { it.copy(capture = it.capture.copy(sha256 = "c".repeat(64))) }
            CivsGateSharedApproaches.current(f.city, repair, access.copy(sharedRoadCaptures = captures)) shouldBe false
            CivsGateSharedApproaches.targets(f.city.copy(constructionQueue = listOf(f.gate)), access) shouldBe emptyList()
            CivsGateSharedApproaches.candidates(f.city.copy(roadRouteOverrides = mapOf(f.forester.toString() to
                CivRoadRouteOverride(proof.donorRoute, proof.donorRoute))), access) shouldBe emptyList()
            val admin = f.plans.map { if (it.building.id == f.gate) it.copy(adminPlacement = true) else it }
            CivsGateSharedApproaches.targets(f.city, CivAutonomousAccess.from(f.city, admin)) shouldBe emptyList()
            val after = f.city.copy(roadRouteOverrides = mapOf(f.gate.toString() to repair))
            val hall = requireNotNull(proof.hall)
            val branch = hall.branches.first()
            val invalidHall = listOf(
                hall.copy(branches = hall.branches + branch),
                hall.copy(branches = List(CivsRules.MAX_SITES + 1) { branch.copy(owner = UUID(999, it.toLong())) }),
                hall.copy(branches = listOf(branch.copy(routeSha256 = "invalid"))),
                hall.copy(branches = listOf(branch.copy(prefix = branch.prefix.take(1)))),
                hall.copy(branches = listOf(branch.copy(prefix = branch.prefix + branch.prefix.last()))),
                hall.copy(crossings = listOf(branch, branch)),
            )
            for (invalid in invalidHall) {
                val detour = repair.gateDetours!!.single().copy(sharedApproach = proof.copy(hall = invalid))
                shouldThrowAny { CivsRules.validate(after.copy(roadRouteOverrides = mapOf(f.gate.toString() to
                    repair.copy(gateDetours = listOf(detour))))) }
            }
            // Permanent accepted gate geometry is not invalidated by a later donor removal.
            repair.validated(after.copy(buildings = after.buildings.filterNot { it.id == f.forester }), proof.source)
        }
    }

    test("protected native connector and a changed walking witness are rejected without an earthwork cut") {
        SharedGateFixture().use { f ->
            val access = CivAutonomousAccess.from(f.city, f.plans)
            val target = CivsGateSharedApproaches.targets(f.city, access).first()
            val original = f.state(target.floor)
            for (material in listOf("minecraft:bedrock", "minecraft:stone_bricks", "minecraft:water", "minecraft:oak_log")) {
                f.put(target.floor, material)
                f.capture(target) shouldBe null
            }
            f.put(target.floor, original)
            val obstruction = CivPosition(12280,65,12980)
            val prior = f.state(obstruction)
            f.put(obstruction, "minecraft:grass_block[snowy=false]")
            f.capture(target) shouldBe null
            f.put(obstruction, prior)
            val work = requireNotNull(f.capture(target))
            work.changes.all { it.position.x == target.floor.x && it.position.z == target.floor.z && it.position.y >= target.floor.y } shouldBe true
            shouldThrowAny { CivsRoadMaintenance.validateChanges(f.city, target,
                work.changes + CivPlanBlock(obstruction, "minecraft:grass_block[snowy=false]", "minecraft:air")) }
        }
    }
})

private class SharedGateFixture : AutoCloseable {
    private val gson = Gson()
    private val input = requireNotNull(javaClass.classLoader.getResourceAsStream("gate-shared-auto5.json")).bufferedReader().use {
        JsonParser.parseReader(it).asJsonObject
    }
    private val palette = input.getAsJsonArray("palette").map { it.asJsonObject }
    private val collisions = palette.associate { row -> row["block_data"].asString to row.getAsJsonArray("collision").map { value ->
        val a = value.asJsonArray.map { it.asDouble }; BoundingBox(a[0],a[1],a[2],a[3],a[4],a[5])
    } }
    private val paper = MockBukkitTestRuntime.open(SharedGateServer(collisions.keys))
    private val worldId = UUID.fromString(input.getAsJsonObject("city")["worldId"].asString)
    val measured = input.getAsJsonArray("cells").map { a -> a.asJsonArray.let { CivPosition(it[0].asInt,it[1].asInt,it[2].asInt) } }.toSet()
    private var loaded = false
    val world = object : CivsWorksiteWorldFixture("shared-gate", ground = Material.AIR, collisionForData = { data ->
        collisions[data.asString]?.map { it.clone() } ?: when {
            CivsRoadMaintenance.surface(data.asString) || data.material == Material.GRASS_BLOCK -> roadUpkeepBoxes(data).map { it.at(CivPosition(0,0,0)) }
            data.material.isSolid -> listOf(BoundingBox(0.0,0.0,0.0,1.0,1.0,1.0))
            else -> emptyList()
        }
    }) {
        override fun getUID() = worldId
        override fun getBlockAt(point: Coordinate): BlockMock {
            check(!loaded || CivPosition(point.x,point.y,point.z) in measured) { "Unmeasured shared-road read: $point" }
            return super.getBlockAt(point)
        }
    }.also(paper.server::addWorld)
    val gate = UUID.fromString(input["gate"].asString)
    val forester = UUID.fromString(input["forester"].asString)
    var city: Settlement
    val plans: List<CivPlacementPlan>
    val native: CivsRoadWorld
    private val plugin = paper.createSimplePlugin("SharedGateTest")
    init {
        CivsWorksiteLayouts.installCatalog(input.getAsJsonObject("sites").entrySet().associate { it.key to it.value.asJsonObject })
        measured.map { (it.x shr 4) to (it.z shr 4) }.toSet().forEach { (x,z) -> world.loadChunk(x,z) }
        input.getAsJsonArray("cells").forEach { a -> a.asJsonArray.let { row ->
            put(CivPosition(row[0].asInt,row[1].asInt,row[2].asInt), palette[row[3].asInt]["block_data"].asString)
        } }
        loaded = true
        val buildings = input.getAsJsonArray("buildings").map { gson.fromJson(it,CivBuilding::class.java) }
        val hut = buildings.single { it.type == BuildingType.BUILDER_HUT }
        val worker = CivsEquipment.replace(CivWorker(UUID(318,1),"Builder",hut.id,nextDeliveryAt = 1),
            MutableList<CivResidentItem?>(CivsEquipment.SLOTS) { null }.also { it[CivsEquipment.BAG] = CivResidentItem("COBBLESTONE",64) })
        val h = input.getAsJsonObject("city")
        city = CivsRules.validate(Settlement(UUID.fromString(h["id"].asString),UUID.fromString(h["owner"].asString),"Shared road",worldId,
            gson.fromJson(h["center"],CivPosition::class.java),h["radius"].asInt,h["style"].asString,Stock(),buildings,listOf(worker),
            autonomy = CivAutonomy(),inventory = CivInventory(CivStorageMode.PHYSICAL)))
        plans = input.getAsJsonArray("plans").map { value ->
            val p = value.asJsonObject; val source = gson.fromJson(p["building"],CivBuilding::class.java)
            val terrain = p["terrain"]?.takeUnless { it.isJsonNull }?.asJsonObject?.let { t ->
                CivTerraformPlan(gson.fromJson(t["bounds"],CivBox::class.java),t.getAsJsonArray("blocks").map { gson.fromJson(it,CivPlanBlock::class.java) },
                    t.getAsJsonArray("route").map { gson.fromJson(it,CivPosition::class.java) },source.anchor.y,0,0)
            }
            CivPlacementPlan(UUID.fromString(p["id"].asString),city.id,worldId,source,emptyList(),capturedSha256=p["capturedSha256"].asString,
                terrain=terrain,adminPlacement=p["adminPlacement"]?.asBoolean ?: false)
        }
        native = CivsRoadWorld(plugin, { listOf(city) }, { _,_,_ -> true }, { false })
    }
    fun state(p: CivPosition) = world.getBlockAt(p.x,p.y,p.z).blockData.asString
    fun put(p: CivPosition, data: String) = world.getBlockAt(p.x,p.y,p.z).setBlockData(Bukkit.createBlockData(data),false)
    fun capture(target: CivRoadUpkeep): CivWorldWork? {
        val unmeasured = listOf(1 to 0,-1 to 0,0 to 1,0 to -1).flatMap { (x,z) -> (-3..3).map { y ->
            target.floor.copy(x=target.floor.x+x,y=target.floor.y+y,z=target.floor.z+z)
        } }.filter { p -> (-2..2).any { p.copy(y=p.y+it) !in measured } }.toSet()
        return native.captureRoadUpkeep(city,city.workers.single(),target,rejectedStandings=unmeasured)
    }
    fun ticks() { paper.performTicks(12) }
    fun <T> await(future: CompletableFuture<T>): T { ticks(); future.isDone shouldBe true; return future.join() }
    fun recovery(tx: CivInventoryTransaction, saved: List<CivPlacementPlan> = plans) = Recovery(tx, saved)
    inner class Recovery(tx: CivInventoryTransaction, saved: List<CivPlacementPlan>) : AutoCloseable {
        private val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
        private val store = mockk<CivsStore>()
        private val storage = CivsStorage(plugin) { emptyArray() }
        private val unhealthy = hashSetOf<UUID>()
        private val busy = hashSetOf<UUID>()
        val quarantined get() = city.id in unhealthy
        var retirements = 0
        var verifications = 0
        val journal: CivsInventoryJournal
        init {
            storage.replaceCities(listOf(city))
            storage.validateUpkeepNative = { receipt ->
                try { native.validateRoadUpkeep(requireNotNull(receipt.upkeep).before(receipt.after), receipt.upkeep.work); null }
                catch (error: CivsRejection) {
                    if (error.reason in setOf("chunks-unloaded", "chunk-unloaded", "world-unloaded", "world-unavailable"))
                        CivInventoryApplyResult.Retry(mapOf("reason" to error.reason))
                    else CivInventoryApplyResult.Conflict(mapOf("reason" to error.reason))
                }
            }
            every { store.save(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { store.deleteInventoryTransaction(any()) } answers { retirements++; CompletableFuture.completedFuture(true) }
            every { store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(tx))
            every { store.loadConsumedItems() } returns CompletableFuture.completedFuture(CivConsumedItemIndex.EMPTY)
            every { store.planGeneration } returns 0L
            every { store.loadPlan(any()) } answers { CompletableFuture.completedFuture(saved.singleOrNull { it.id == firstArg<UUID>() }) }
            val authority = mockk<CivsService>()
            every { authority.tracked<Any>() } answers { CompletableFuture<Any>() }
            val records = CivsCityRecords({ mapOf(city.id to city) }, busy, unhealthy, { false }, { false }, { null }, { 0L }, { 0 })
            val plans = CivsCityPlans(authority, records, tasks, store, Executor(Runnable::run), mockk(relaxed = true))
            journal = CivsInventoryJournal(plugin, tasks, store, storage, { mapOf(city.id to city) }, busy, unhealthy, { false },
                { value -> city = value; storage.updateCity(value); CompletableFuture.completedFuture(Unit) },
                { _, _ -> error("Shared approach has exactly one paid city transition") }, {}, {}, { _, _, _ -> null }, {},
                { _, _ -> city }, {}, { 0L }, verifyHallCaptures = { verifications++; plans.verifyHallCaptures(it) })
        }
        override fun close() { storage.close(); tasks.close() }
    }
    override fun close() { CivsWorksiteLayouts.installCatalog(emptyMap()); paper.close() }
}

private class SharedGateServer(private val palette: Set<String>) : CivsWorksiteServerFixture() {
    override fun createBlockData(data: String): BlockData {
        if (data !in palette || data.substringBefore('[') != "minecraft:snow") return super.createBlockData(data)
        return object : BlockDataMock(Material.SNOW) {
            override fun getAsString() = data
            override fun getAsString(hideUnspecified: Boolean) = data
            override fun clone() = this@SharedGateServer.createBlockData(data) as BlockDataMock
        }
    }
}
