package ru.arc.civs

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import java.util.concurrent.CompletableFuture
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.util.BoundingBox
import org.mockbukkit.mockbukkit.block.BlockMock
import org.mockbukkit.mockbukkit.block.data.BlockDataMock
import org.mockbukkit.mockbukkit.world.Coordinate
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.abs

/** Opt-in ordinary world-work replay over a union of measured native voxels. An unmeasured read fails. */
class CivsTownHallNativeReplayTest : FunSpec({
    afterEach { CivsWorksiteLayouts.installCatalog(emptyMap()) }
    test("measured town hall approaches recover through paid columns and walk their finished treads both ways") {
        val input = System.getenv("CIVS_HALL_REPLAY_INPUT") ?: return@test
        val config = JsonParser.parseString(Files.readString(Path.of(input))).asJsonObject
        val index = Files.newBufferedReader(Path.of(config["catalog"].asString)).use { JsonParser.parseReader(it).asJsonObject }
        val sites = (index.getAsJsonArray("entries").toList() + index.getAsJsonArray("retainedEntries").toList())
            .map { it.asJsonObject }.filter { it["verdict"].asString == "READY" && it.has("worksite") && !it["worksite"].isJsonNull }
            .associate { it["schematicId"].asString to it["worksite"].asJsonObject }
        CivsWorksiteLayouts.installCatalog(sites)
        val failures = config.getAsJsonArray("cases").mapNotNull { item ->
            val spec = item.asJsonObject
            runCatching { replayHall(spec, Path.of(config["output"].asString)) }.exceptionOrNull()?.let {
                val out=Path.of(config["output"].asString)
                Files.createDirectories(out)
                Files.writeString(out.resolve("${spec["name"].asString}-failure.json"),Gson().toJson(mapOf(
                    "message" to it.toString().take(5000),"trace" to it.stackTraceToString().take(12000))))
                "${spec["name"]}: ${it.toString().take(1000)}"
            }
        }
        check(failures.isEmpty()) { failures.joinToString("\n") }
    }
})

private fun replayHall(spec: JsonObject, out: Path) {
    val gson = GsonBuilder().setPrettyPrinting().create()
    val name = spec["name"].asString
    val slim = JsonParser.parseString(Files.readString(Path.of(spec["layout"].asString))).asJsonObject
    val captures = spec.getAsJsonArray("captures").map { JsonParser.parseString(Files.readString(Path.of(it.asString))).asJsonObject }
    val worldId = UUID.fromString(slim.getAsJsonObject("city")["worldId"].asString)
    val (initialCity, plans) = hallReplayPlans(slim,name,worldId,gson)
    val hall = initialCity.buildings.single { it.type == BuildingType.TOWN_HALL && it.phase == BuildingPhase.ACTIVE }
    if (spec["planOnly"]?.asBoolean == true) {
        check(captures.isEmpty() && !spec.has("semanticAir"))
        val saved = gson.toJson(plans)
        val access = CivAutonomousAccess.from(initialCity,plans)
        val layout = checkNotNull(access.hallApproach(initialCity)) { "$name refused the plan-only Hall approach" }
        val work = CivsRoadMaintenance.prepare(initialCity,plans,proposeCuts=false).targets.filter { it.hall != null }
        val expected = spec.getAsJsonArray("ownedTargets").associate { row ->
            gson.fromJson(row.asJsonObject["floor"],CivPosition::class.java) to row.asJsonObject["after"].asString
        }
        work.associate { it.floor to it.after } shouldBe expected
        layout.surfaces shouldBe expected
        val retained = spec.getAsJsonArray("retainedTerminal").map { gson.fromJson(it,CivPosition::class.java) }.toSet()
        layout.retainedTerminalFloors shouldBe retained
        layout.witness.branches.size shouldBe spec["exactBranchCount"].asInt
        for (branch in layout.witness.branches) {
            val route = access.routes.getValue(branch.owner)
            layout.travel(route) shouldBe route
            val prefix = spec.getAsJsonArray("outboundSharedPrefix").map { gson.fromJson(it,CivPosition::class.java) }
            route.take(prefix.size) shouldBe prefix
        }
        layout.witness.crossings.orEmpty().size shouldBe spec["terminalStreetOwners"].asInt
        gson.toJson(plans) shouldBe saved
        Files.createDirectories(out)
        Files.writeString(out.resolve("$name.json"),gson.toJson(mapOf("case" to name,"planOnly" to true,
            "nativeCells" to 0,"allTargets" to work.map { mapOf("floor" to it.floor,"after" to it.after) },
            "retainedTerminalFloors" to retained,"exactBranchCount" to layout.witness.branches.size,
            "collisionProof" to false)))
        return
    }
    val native = linkedMapOf<CivPosition, String>()
    val collisions = mutableMapOf<String, List<BoundingBox>>()
    for (capture in captures) {
        capture["unavailable_count"].asInt shouldBe 0
        capture["voxel_count"].asInt shouldBe 891
        capture["world"].asString shouldBe worldId.toString()
        val base = gson.fromJson(capture.getAsJsonObject("bounds")["min"], CivPosition::class.java)
        val palette = capture.getAsJsonArray("palette").map { cell ->
            val row = cell.asJsonObject
            val state = row["block_data"].asString
            val boxes = row.getAsJsonArray("collision").map { v ->
                val b = v.asJsonArray
                BoundingBox(b[0].asDouble, b[1].asDouble, b[2].asDouble, b[3].asDouble, b[4].asDouble, b[5].asDouble)
            }
            collisions.put(state, boxes)?.let { it shouldBe boxes }
            state
        }
        for (cell in capture.getAsJsonArray("voxels")) {
            val row = cell.asJsonArray
            row[4].asBoolean shouldBe true
            val p = CivPosition(base.x + row[0].asInt, base.y + row[1].asInt, base.z + row[2].asInt)
            val state = palette[row[3].asInt]
            native.put(p, state)?.let { it shouldBe state }
        }
    }
    var semanticAirCells = 0
    for (item in spec.getAsJsonArray("semanticAir")?.toList().orEmpty()) {
        val expected = item.asJsonObject
        val bytes = Files.readAllBytes(Path.of(expected["file"].asString))
        CivsGateRefit.sha256(bytes) shouldBe expected["sha256"].asString
        val report = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        report["schema"].asInt shouldBe 1
        report["world"].asString shouldBe worldId.toString()
        report["city"].asString shouldBe slim.getAsJsonObject("city")["id"].asString
        report["capture_sha256"].asString shouldBe expected["captureSha256"].asString
        report["runtime_jar_sha256"].asString shouldBe expected["runtimeJarSha256"].asString
        report["not_atomic_with_earlier_capsules"].asBoolean shouldBe true
        report["source_voxel_count"].asInt shouldBe 891
        val checks = report.getAsJsonObject("assertions")
        val cells = if (report.has("position")) {
            val at = gson.fromJson(report["position"],CivPosition::class.java)
            at shouldBe gson.fromJson(expected["position"],CivPosition::class.java)
            for (key in listOf("available","minecraft_air","empty_collision","not_liquid","non_tile_implied_by_native_air_state"))
                checks[key].asInt shouldBe 1
            listOf(at)
        } else {
            report["bounds"] shouldBe expected["bounds"]
            val box = gson.fromJson(report["bounds"],CivBox::class.java)
            box.max shouldBe box.min.copy(y=box.min.y+1)
            checks["available_minecraft_air_empty_collision_not_liquid"].asInt shouldBe 2
            checks["non_tile_implied_by_native_air_state"].asInt shouldBe 2
            listOf(box.min,box.max)
        }
        for (key in listOf("known","matching","total")) report[key].asInt shouldBe cells.size
        report["unavailable_count"].asInt shouldBe 0
        for (cell in cells) check(native.putIfAbsent(cell,"minecraft:air") == null)
        check(collisions.getValue("minecraft:air").isEmpty())
        semanticAirCells += cells.size
    }
    MockBukkitTestRuntime.open(HallReplayServer(collisions.keys)).use { paper ->
        var loaded = false
        val world = object : CivsWorksiteWorldFixture("captured-$name-hall", ground = Material.AIR, collisionForData = { data ->
            collisions[data.asString]?.map { it.clone() } ?: when {
                data.material == Material.DIRT -> listOf(BoundingBox(0.0,0.0,0.0,1.0,1.0,1.0))
                data.asString == CivsEntrancePath.GRASS || data.asString in CivsWorksiteLayouts.HALL_PAVING ||
                    CivsRoadMaintenance.surface(data.asString) -> roadUpkeepBoxes(data).map { it.at(CivPosition(0,0,0)) }
                else -> error("Missing native collision in $name: ${data.asString}")
            }
        }) {
            override fun getUID() = worldId
            override fun getBlockAt(point: Coordinate): BlockMock {
                val p = CivPosition(point.x, point.y, point.z)
                check(!loaded || p in native) { "$name requested unmeasured $p" }
                return super.getBlockAt(point)
            }
        }.also(paper.server::addWorld)
        native.keys.map { (it.x shr 4) to (it.z shr 4) }.toSet().forEach { (x,z) -> world.loadChunk(x,z) }
        fun read(p: CivPosition) = world.getBlockAt(p.x,p.y,p.z)
        native.forEach { (p, state) -> read(p).setBlockData(Bukkit.createBlockData(state), false) }
        loaded = true
        var city = initialCity
        val planImage = Gson().toJson(plans)
        val access = CivAutonomousAccess.from(city, plans)
        val approach = requireNotNull(access.hallApproach(city)) {
            val proof=requireNotNull(access.hallWitness)
            val raw=CivsTownHallApproach.from(city,hall,proof)
            val conflicts=raw?.surfaces?.keys?.mapNotNull { floor ->
                val hits=city.buildings.filter { other ->
                    other.id!=hall.id && (CivsAutonomousGeometry.horizontalContains(CivsAutonomousGeometry.futureBounds(city,other),floor) ||
                        other.control?.let { it.x==floor.x && it.z==floor.z }==true ||
                        other.transit.orEmpty().any { it.x==floor.x && it.z==floor.z })
                }
                if (hits.isEmpty()) null else "$floor ${hits.map{it.id}}"
            }
            val blocker=raw?.admissionConflict(city,access)
            "$name has no funded Hall approach; layout=${raw?.surfaces?.size}, blocker=$blocker, conflicts=$conflicts, branches=${proof.branches.size}, crossings=${proof.crossings?.size}"
        }
        val all = CivsRoadMaintenance.prepare(city,plans,proposeCuts=false).targets.filter { it.hall!=null && it.source==hall }
        all.associate { it.floor to it.after } shouldBe approach.surfaces
        val front = CivsAutonomousGeometry.frontage(hall)
        val axis = requireNotNull(CivsWorksiteLayouts.hallRows(hall)).first
        axis shouldBe spec["axis"].asInt
        val feet = spec.getAsJsonArray("feet").map { it.asInt }
        fun point(lane: Int, y: Int, depth: Int) = CivPosition(hall.entrance.x + lane*front.z + depth*front.x,
            y,hall.entrance.z - lane*front.x + depth*front.z)
        val junction=spec.getAsJsonArray("transverseTerminal")?.map { row ->
            val v=row.asJsonArray
            point(v[0].asInt,v[1].asInt,feet.lastIndex) to v[2].asString
        }.orEmpty().toMap()
        val minDepth = spec["minDepth"].asInt
        val rowGrades = (minDepth..feet.lastIndex).associateWith { d -> if (d<=0) feet.first()-1 else maxOf(feet[d],feet[d-1])-1 }
        val required = buildSet {
            for ((d,y) in rowGrades) for (s in axis-1..axis+1) {
                val flat = point(s,y,d)
                val p=junction.keys.firstOrNull { it.x==flat.x && it.z==flat.z } ?: flat
                if (hall.control?.let { it.x == p.x && it.z == p.z } != true) add(p)
            }
            for (d in 1..feet.lastIndex) if (feet[d] != feet[d-1]) for (s in listOf(axis-2,axis+2)) {
                val previous = point(s,rowGrades.getValue(d),d)
                val lower = point(s,feet.last()-1,d)
                add(if (approach.loweredFrom(lower) == previous) lower else previous)
            }
        }
        val targets = all.filter { target ->
            target.floor in required || spec["includeSeams"]?.asBoolean == true && target.floor in native &&
                (1..CivsAutonomousTerraform.MAXIMUM_CHANGE).firstNotNullOfOrNull { dy ->
                    val at=target.floor.copy(y=target.floor.y-dy)
                    val block=native[at]
                    when {
                        block==null -> false
                        CivsTownHallApproach.air(block) || CivsTownHallApproach.cover(block) || approach.paidFinish(at,block) -> null
                        else -> true
                    }
                }==true && (-2..CivsRoadMaintenance.MAX_CLEARANCE).all { dy -> target.floor.copy(y=target.floor.y+dy) in native } &&
                listOf(1 to 0,-1 to 0,0 to 1,0 to -1).all { (x,z) -> target.floor.copy(x=target.floor.x+x,z=target.floor.z+z) in native }
        }
        targets.filter { it.floor in required }.map { it.floor }.toSet() shouldBe required - junction.keys
        for ((floor,after) in junction) {
            check(all.none { it.floor.x==floor.x && it.floor.z==floor.z })
            read(floor).blockData.asString shouldBe after
        }
        val retired = spec.getAsJsonArray("retiredPaidShoulders")?.map { gson.fromJson(it,CivPosition::class.java) }.orEmpty()
        for (floor in retired) {
            check(approach.retiredShoulder(floor))
            check(approach.paidFinish(floor,native.getValue(floor)))
            targets.single { it.floor == floor }.after shouldBe CivsEntrancePath.GRASS
        }
        val stairRows = targets.filter { "stairs[" in it.after }
        if (feet.zipWithNext().any { (a,b) -> a != b }) check(stairRows.isNotEmpty())
        targets.first().let { sample ->
            val witness = requireNotNull(sample.hall)
            check(runCatching { sample.copy(after="minecraft:gold_block").validated(city) }.isFailure)
            check(runCatching { sample.copy(floor=sample.floor.copy(x=sample.floor.x+15)).validated(city) }.isFailure)
            val changed = witness.copy(branches=witness.branches.mapIndexed { index, b ->
                if (index==0) b.copy(routeSha256="0".repeat(64)) else b })
            check(changed != access.hallWitness)
        }
        var permit = true
        val plugin=paper.createSimplePlugin("HallReplay")
        val road = CivsRoadWorld(plugin,{ listOf(city) },{ _,_,_ -> permit },{ false })
        val receipts = arrayListOf<CivRoadUpkeepReceipt>()
        val changes = arrayListOf<CivPlanBlock>()
        val walks = arrayListOf<Int>()
        val terminalWalks=arrayListOf<Int>()
        fun unmeasuredStances(floor: CivPosition): Set<CivPosition> =
            listOf(1 to 0,-1 to 0,0 to 1,0 to -1).flatMap { (x,z) ->
                (-3..3).map { y -> floor.copy(x=floor.x+x,y=floor.y+y,z=floor.z+z) }
            }.filter { p -> (-2..2).any { dy -> p.copy(y=p.y+dy) !in native } }.toSet()
        val failure = runCatching {
            for (pass in 0..40) {
                var progress = false
                for (target in targets) {
                    val captured = road.captureRoadUpkeep(city,city.workers.single(),target,
                        rejectedStandings=unmeasuredStances(target.floor)) ?: continue
                    if (receipts.isEmpty()) {
                        permit = false
                        road.captureRoadUpkeep(city,city.workers.single(),target,rejectedStandings=unmeasuredStances(target.floor)) shouldBe null
                        permit = true
                    }
                    val work = captured.copy(inputs=CivsRoadMaintenance.inputs(city,captured))
                    val at = 3000L + receipts.size*4000L
                    road.validateRoadUpkeep(city,work)
                    val beforeWorker = city.workers.single()
                    val after = CivsRoadMaintenance.complete(city,work,at)
                    val cost = 1L + work.changes.count { it.position.y < target.floor.y }
                    work.inputs shouldBe mapOf("COBBLESTONE" to cost)
                    val receipt = gson.fromJson(gson.toJson(CivRoadUpkeepReceipt(beforeWorker,city.stock,work,at).detached()),
                        CivRoadUpkeepReceipt::class.java)
                    receipt.validated(after)
                    val tx=CivInventoryTransaction(UUID.randomUUID(),city.id,city.revision,after,emptyList(),worldChanges=work.changes,upkeep=receipt)
                    tx.validated()
                    if (receipts.isEmpty()) {
                        val store=mockk<CivsStore>().also { it.stubCheckpointAcknowledgements() }
                        val storage=mockk<CivsStorage>(relaxed=true)
                        every { store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(tx))
                        every { store.loadConsumedItems() } returns CompletableFuture.completedFuture(CivConsumedItemIndex.EMPTY)
                        every { storage.waitingForPlayer(tx) } returns false
                        val tasks=LifecycleTaskScope(BukkitTaskScheduler(plugin))
                        try {
                            var checks=0
                            val journal=CivsInventoryJournal(plugin,tasks,store,storage,{ mapOf(city.id to after) },
                                mutableSetOf(),mutableSetOf(),{ false },{ error("unverified publication") },
                                { _,_ -> error("unverified city mutation") },{},{},{ _,_,_ -> null },{},{ _,_ -> after },
                                {},{ 0L },verifyHallCaptures={ checks++;CompletableFuture.failedFuture(CivsRejection("stale-worksite")) })
                            val loading=journal.bootstrap()
                            paper.performTicks(8)
                            loading.join()
                            journal.recover()
                            paper.performTicks(8)
                            checks shouldBe 1
                            journal.blockedCount shouldBe 1
                            verify(exactly=0) { storage.reconcile(tx) }
                        } finally { tasks.close() }
                    }
                    for (change in work.changes.asReversed()) {
                        read(change.position).blockData.asString shouldBe change.before
                        read(change.position).setBlockData(Bukkit.createBlockData(change.after),false)
                        road.validateRoadUpkeep(receipt.before(after),receipt.work)
                    }
                    changes.addAll(work.changes)
                    receipts += receipt
                    city = after
                    progress = true
                }
                if (!progress) break
            }
            val wrong = targets.mapNotNull { target ->
                val actual = read(target.floor).blockData
                if (CivsRoadMaintenance.sameSurface(target,actual,Bukkit.createBlockData(target.after))) null else
                    "${target.floor} expected=${target.after} actual=${actual.asString}"
            }
            if (wrong.isNotEmpty()) {
                val guards=targets.mapNotNull { target ->
                    val focus=target.floor
                    val original=native[focus] ?: return@mapNotNull null
                    if (read(focus).blockData.asString==target.after) return@mapNotNull null
                    val changed=(1 until CivsAutonomousTerraform.MAXIMUM_CHANGE).mapNotNull { dy ->
                        val cell=focus.copy(y=focus.y-dy)
                        native[cell]?.takeIf { approach.paidFinish(cell,it) }?.let { CivPlanBlock(cell,it,"minecraft:dirt") }
                    }+CivPlanBlock(focus,original,target.after)
                    val checks=approach.crossingChecks(focus,changed)
                    val failed=checks.filter { check -> check.before.isEmpty() || roadHalfStepWalk(world,check.before)!=null }
                        .map { check -> mapOf("old" to check.before,"new" to check.after,
                            "newWalkNow" to runCatching { roadHalfStepWalk(world,check.after)!=null }.getOrNull()) }
                    mapOf("target" to focus,"before" to original,"crossingChecks" to failed)
                }
                Files.createDirectories(out)
                Files.writeString(out.resolve("$name-walk-dependencies.json"),gson.toJson(guards))
            }
            check(wrong.isEmpty()) { "$name has ${wrong.size} unresolved columns: ${wrong.joinToString()}" }
            for (floor in retired) {
                val change = changes.single { it.position == floor }
                change.before shouldBe native.getValue(floor)
                change.after shouldBe CivsEntrancePath.GRASS
                check(receipts.single { it.work.upkeep?.floor == floor }.work.changes == listOf(change))
            }
            for (s in axis-1..axis+1) {
                val allSteps = rowGrades.map { (d,y) ->
                    val regular=point(s,y+1,d)
                    junction.keys.firstOrNull { it.x==regular.x && it.z==regular.z }?.let { it.copy(y=it.y+1) } ?: regular
                }
                val controlIndex = allSteps.indexOfLast { p -> hall.control?.let { p.x==it.x && p.z==it.z } == true }
                val path = allSteps.drop(controlIndex+1)
                val entrance=if (junction.isEmpty()) path else path.dropLast(1)
                roadHalfStepWalk(world,entrance)?.size?.let(walks::add) ?: error("$name lane=$s failed outward: $entrance")
                roadHalfStepWalk(world,entrance.asReversed()) ?: error("$name lane=$s failed inward")
                if (junction.keys.any { it.x==path.last().x && it.z==path.last().z }) {
                    val d=feet.lastIndex
                    val join=listOf(entrance.last(),point(axis,rowGrades.getValue(d-1)+1,d-1),
                        point(axis,rowGrades.getValue(d)+1,d),path.last())
                    terminalWalks+=checkNotNull(roadHalfStepWalk(world,join)) { "$name cross street: $join" }.size
                    checkNotNull(roadHalfStepWalk(world,join.asReversed())) { "$name reverse cross street" }
                }
            }
            for ((floor,after) in junction) read(floor).blockData.asString shouldBe after
            val joins = mutableListOf<Map<String,Any>>()
            // At a cardinal edge a .299-wide body crosses its two endpoint columns only.
            fun known(step: CivPosition) = (-2..2).all { dy -> step.copy(y=step.y+dy) in native }
            for (branch in approach.witness.branches) {
                val projected = approach.travel(access.routes.getValue(branch.owner))
                val measured = projected.takeWhile(::known).take(14)
                if (measured.size < 2) continue
                val outward=roadHalfStepWalk(world,measured)!=null
                val inward=roadHalfStepWalk(world,measured.asReversed())!=null
                val retained = measured.filter { step -> civsTerrainKey(step.x,step.z) !in approach.columns }
                joins.add(mapOf("owner" to branch.owner, "route" to measured, "retainedStreetStances" to retained,
                    "outward" to outward, "inward" to inward))
            }
            Files.createDirectories(out)
            Files.writeString(out.resolve("$name-branches.json"), gson.toJson(joins))
            if (spec["requireMeasuredBranches"]?.asBoolean == true) {
                check(joins.size == approach.witness.branches.size && joins.all { it["outward"] == true && it["inward"] == true }) {
                    "$name has an unwalkable measured Hall branch: $joins"
                }
            }
            targets.all { target -> road.captureRoadUpkeep(city,city.workers.single(),target,
                rejectedStandings=unmeasuredStances(target.floor))==null } shouldBe true
            Gson().toJson(plans) shouldBe planImage
            CivAutonomousAccess.from(city,plans).routes shouldBe access.routes
        }.exceptionOrNull()
        Files.createDirectories(out)
        Files.writeString(out.resolve("$name.json"),gson.toJson(mapOf("case" to name,"error" to failure?.toString(),
            "trace" to failure?.stackTraceToString()?.take(7000),"nativeCells" to native.size,"semanticAirCells" to semanticAirCells,
            "multiEpochUnion" to (semanticAirCells > 0 || captures.size > 1),"totalAdmitted" to all.size,"assertedColumns" to targets.size,
            "unmeasuredTargetCount" to (all.size-targets.size),
            "excludedStandingCandidates" to targets.sumOf { unmeasuredStances(it.floor).size },
            "standingCriterion" to "Reject candidate feet if any block in its vertical [feet-2, feet+2] is unmeasured.",
            "receipts" to receipts.size,"debit" to receipts.sumOf { it.work.inputs.getValue("COBBLESTONE") },
            "allTargets" to all.map { mapOf("floor" to it.floor,"after" to it.after) },
            "walkSamples" to walks,"terminalStreetWalkSamples" to terminalWalks,"changes" to changes,"targets" to targets.map { mapOf("floor" to it.floor,"after" to it.after) })))
        failure?.let { throw it }
    }
}

private fun hallReplayPlans(slim: JsonObject,name: String,worldId: UUID,gson: Gson): Pair<Settlement,List<CivPlacementPlan>> {
    val buildings = slim.getAsJsonArray("buildings").map { gson.fromJson(it,CivBuilding::class.java) }
    val builder = buildings.first { it.type==BuildingType.BUILDER_HUT && it.phase==BuildingPhase.ACTIVE }
    val header = slim.getAsJsonObject("city")
    val adopted = slim["adopted_geometry"]?.takeUnless { it.isJsonNull }?.let { gson.fromJson(it,CivSettlementPlan::class.java) }
    val overrides = slim["roadRouteOverrides"]?.takeUnless { it.isJsonNull }?.asJsonObject?.entrySet()
        ?.associate { it.key to gson.fromJson(it.value,CivRoadRouteOverride::class.java) }
    val worker = CivsEquipment.replace(CivWorker(UUID(0,302), "Hall replay", builder.id, nextDeliveryAt = 1),
        MutableList<CivResidentItem?>(CivsEquipment.SLOTS) { null }.also { bag ->
            for (slot in CivsEquipment.BAG..CivsEquipment.BAG + 2) bag[slot] = CivResidentItem("COBBLESTONE",64)
        })
    val city = CivsRules.validate(Settlement(UUID.fromString(header["id"].asString),UUID.fromString(header["owner"].asString),name,
        worldId,gson.fromJson(header["center"],CivPosition::class.java),header["radius"].asInt,header["style"].asString,Stock(),
        buildings,listOf(worker),revision=slim["revision"].asLong,autonomy=CivAutonomy(plan=adopted),
        inventory=CivInventory(CivStorageMode.PHYSICAL),roadRouteOverrides=overrides))
    val plans = slim.getAsJsonArray("plans").map { v ->
        val p = v.asJsonObject
        val source = gson.fromJson(p["building"],CivBuilding::class.java)
        val terrain = p["terrain"]?.takeUnless { it.isJsonNull }?.asJsonObject?.let { t ->
            CivTerraformPlan(gson.fromJson(t["bounds"],CivBox::class.java),emptyList(),
                t.getAsJsonArray("route").map { gson.fromJson(it,CivPosition::class.java) },source.anchor.y,0,0)
        }
        CivPlacementPlan(UUID.fromString(p["id"].asString),UUID.fromString(p["cityId"].asString),UUID.fromString(p["worldId"].asString),source,emptyList(),
            capturedSha256=p["capturedSha256"]?.takeUnless { it.isJsonNull }?.asString,terrain=terrain,
            adminPlacement=p["adminPlacement"]?.asBoolean ?: false)
    }
    return city to plans
}

private class HallReplayServer(private val palette: Set<String>): CivsWorksiteServerFixture() {
    override fun createBlockData(data: String): BlockData {
        val type = data.substringBefore('[')
        if (data !in palette || type !in setOf("minecraft:snow","minecraft:leaf_litter","minecraft:wildflowers","minecraft:cobblestone_wall",
                "minecraft:spruce_fence","minecraft:oak_fence")) return super.createBlockData(data)
        return object : BlockDataMock(requireNotNull(Material.matchMaterial(type))) {
            override fun getAsString() = data
            override fun getAsString(hideUnspecified: Boolean) = data
            override fun clone() = this@HallReplayServer.createBlockData(data) as BlockDataMock
        }
    }
}
