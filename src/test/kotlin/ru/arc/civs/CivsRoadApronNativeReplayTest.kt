package ru.arc.civs

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor

/** Opt-in native terrain replay; every unknown cell is an error, never implicit fixture AIR. */
class CivsRoadApronNativeReplayTest : FunSpec({
    afterEach { CivsWorksiteLayouts.installCatalog(emptyMap()) }
    test("the captured Auto1 house apron completes through paid receipts and real local tread walks") {
        val capturePath = System.getenv("CIVS_APRON_NATIVE_INPUT") ?: return@test
        val layoutPath = requireNotNull(System.getenv("CIVS_APRON_LAYOUT_INPUT"))
        val output = Path.of(requireNotNull(System.getenv("CIVS_APRON_REPLAY_OUT"))).also(Files::createDirectories)
        val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
        val captureBytes = Files.readAllBytes(Path.of(capturePath))
        val layoutBytes = Files.readAllBytes(Path.of(layoutPath))
        val capture = JsonParser.parseString(String(captureBytes)).asJsonObject
        val layout = JsonParser.parseString(String(layoutBytes)).asJsonObject
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val door = System.getenv("CIVS_APRON_DOOR_INPUT")?.let { JsonParser.parseString(Files.readString(Path.of(it))).asJsonObject }
        val captures = listOfNotNull(capture, door)
        val boxes = captures.map { gson.fromJson(it["bounds"], CivBox::class.java) }
        val bounds = boxes.reduce(CivsAutonomousGeometry::union)
        val before = linkedMapOf<CivPosition, String>()
        for ((i, input) in captures.withIndex()) {
            input["unavailable_count"].asInt shouldBe 0
            input["voxel_count"].asInt shouldBe 891
            input["world"].asString shouldBe capture["world"].asString
            val palette = input.getAsJsonArray("palette").map { it.asJsonObject }
            val min = boxes[i].min
            input.getAsJsonArray("voxels").forEach { value ->
                val row = value.asJsonArray
                row[4].asBoolean shouldBe true
                val p = CivPosition(min.x + row[0].asInt, min.y + row[1].asInt, min.z + row[2].asInt)
                val state = palette[row[3].asInt]["block_data"].asString
                before.put(p, state)?.let { it shouldBe state }
            }
        }
        val palette = captures.flatMap { input -> input.getAsJsonArray("palette").map { it.asJsonObject } }
        if (door != null) {
            val catalog = Path.of(requireNotNull(System.getenv("CIVS_APRON_CATALOG_INPUT")))
            val index = Files.newBufferedReader(catalog).use { JsonParser.parseReader(it).asJsonObject }
            CivsWorksiteLayouts.installCatalog(CivBlueprintCatalog.parse(index).worksiteLayouts)
        }
        val collisions = palette.associate { cell -> cell["block_data"].asString to cell.getAsJsonArray("collision").map { value ->
            val box = value.asJsonArray
            BoundingBox(box[0].asDouble, box[1].asDouble, box[2].asDouble, box[3].asDouble, box[4].asDouble, box[5].asDouble)
        } }
        val worldId = UUID.fromString(capture["world"].asString)
        MockBukkitTestRuntime.open(ApronNativeServer(collisions.keys)).use { paper ->
            var enforceCapture = false
            val unknownReads = linkedSetOf<CivPosition>()
            val world = object : CivsWorksiteWorldFixture("captured-house-apron", ground = Material.AIR, collisionForData = { data ->
                collisions[data.asString]?.map { it.clone() } ?: if (CivsRoadMaintenance.surface(data.asString))
                    roadUpkeepBoxes(data).map { it.at(CivPosition(0, 0, 0)) }
                else error("Uncaptured apron collision state: ${data.asString}")
            }) {
                override fun getUID(): UUID = worldId
                override fun getBlockAt(point: Coordinate): BlockMock {
                    val p = CivPosition(point.x, point.y, point.z)
                    if (enforceCapture && p !in before) {
                        unknownReads += p
                        error("Native apron replay requested an uncaptured cell: $p")
                    }
                    return super.getBlockAt(point)
                }
            }.also(paper.server::addWorld)
            for (x in (bounds.min.x shr 4)..(bounds.max.x shr 4)) for (z in (bounds.min.z shr 4)..(bounds.max.z shr 4))
                world.loadChunk(x, z)
            before.forEach { (p, data) -> world.getBlockAt(p.x, p.y, p.z).setBlockData(Bukkit.createBlockData(data), false) }
            enforceCapture = true
            fun read(p: CivPosition) = world.getBlockAt(p.x, p.y, p.z)
            fun state() = before.keys.associateWith { read(it).blockData.asString }
            // Diagnostic only: retain the existing roadHalfStepWalk oracle and expose its quarter-step contacts on failure.
            fun trace(route: List<CivPosition>): List<Map<String, Any?>> {
                val samples = mutableListOf<Map<String, Any?>>()
                var previous: CitizenPoint? = null
                for ((a, b) in route.zipWithNext()) for (index in 0..4) {
                    if (previous != null && index == 0) continue
                    val t = index / 4.0
                    val x = a.x + .5 + (b.x - a.x) * t
                    val z = a.z + .5 + (b.z - a.z) * t
                    val expected = a.y + (b.y - a.y) * t
                    val contacts = mutableListOf<BoundingBox>()
                    val contactCells = mutableListOf<Map<String, Any?>>()
                    for (bx in floor(x - .299).toInt()..floor(x + .299).toInt())
                        for (bz in floor(z - .299).toInt()..floor(z + .299).toInt())
                            for (by in floor(expected).toInt() - 2..floor(expected).toInt()) {
                                val block = read(CivPosition(bx, by, bz))
                                val local = block.collisionShape.boundingBoxes.filter { it.maxX > x - bx - .299 &&
                                    it.minX < x - bx + .299 && it.maxZ > z - bz - .299 && it.minZ < z - bz + .299 }
                                if (local.isNotEmpty()) contactCells += mapOf("position" to CivPosition(bx, by, bz),
                                    "data" to block.blockData.asString, "tops" to local.map { by + it.maxY })
                                contacts += local.map { it.clone().shift(bx.toDouble(), by.toDouble(), bz.toDouble()) }
                            }
                    val top = contacts.maxOfOrNull { it.maxY }
                    if (top == null) { samples += mapOf("edge" to listOf(a, b), "quarter" to index,
                        "x" to x, "z" to z, "top" to null, "walkGate" to false); continue }
                    val point = CitizenPoint(x, top, z)
                    val body = citizenPacketBodyClear(world, point, false)
                    val forward = previous?.let { citizenPacketStepClear(world, it, point, false) }
                    val reverse = previous?.let { citizenPacketStepClear(world, point, it, false) }
                    val rise = previous?.let { abs(point.y - it.y) }
                    val lower = previous?.let { minOf(point.y, it.y) }
                    val pathLip = rise != null && rise <= 9.0 / 16.0 + .0001 &&
                        abs(requireNotNull(lower) - floor(lower) - 15.0 / 16.0) < .0001
                    samples += mapOf("edge" to listOf(a, b), "quarter" to index, "point" to point,
                        "previous" to previous, "contactCells" to contactCells,
                        "supported" to citizenPacketSupported(world, point, false), "bodyClear" to body,
                        "stepForward" to forward, "stepReverse" to reverse, "rise" to rise, "pathLip" to pathLip,
                        "walkGate" to (body && (previous == null || (rise!! <= .5001 || pathLip) && forward == true && reverse == true)))
                    previous = point
                }
                return samples
            }
            val buildings = layout.getAsJsonArray("buildings").map { gson.fromJson(it, CivBuilding::class.java) }
            val house = buildings.single { it.id == UUID.fromString("e5bcba5b-ff05-3f77-adf0-0023e7ecc01e") }
            val builder = buildings.first { it.type == BuildingType.BUILDER_HUT && it.phase == BuildingPhase.ACTIVE }
            val header = layout.getAsJsonObject("city")
            val adopted = layout["adopted_geometry"]?.takeUnless { it.isJsonNull }?.let { gson.fromJson(it, CivSettlementPlan::class.java) }
            val overrides = layout["roadRouteOverrides"]?.takeUnless { it.isJsonNull }?.asJsonObject?.entrySet()
                ?.associate { it.key to gson.fromJson(it.value, CivRoadRouteOverride::class.java) }
            val worker = CivsEquipment.replace(CivWorker(UUID(0, 298), "Replay builder", builder.id, nextDeliveryAt = 1),
                MutableList<CivResidentItem?>(CivsEquipment.SLOTS) { null }.also {
                    it[CivsEquipment.BAG] = CivResidentItem("COBBLESTONE", 64)
                })
            var city = CivsRules.validate(Settlement(UUID.fromString(header["id"].asString), UUID.fromString(header["owner"].asString),
                "Native Auto1 apron replay", world.uid, gson.fromJson(header["center"], CivPosition::class.java),
                header["radius"].asInt, header["style"].asString, Stock(), buildings, listOf(worker),
                revision = layout["revision"].asLong, autonomy = CivAutonomy(plan = adopted),
                inventory = CivInventory(CivStorageMode.PHYSICAL), roadRouteOverrides = overrides))
            val plans = layout.getAsJsonArray("plans").map { value ->
                val p = value.asJsonObject
                val source = gson.fromJson(p["building"], CivBuilding::class.java)
                val terrain = p["terrain"]?.takeUnless { it.isJsonNull }?.asJsonObject?.let { t ->
                    CivTerraformPlan(gson.fromJson(t["bounds"], CivBox::class.java), emptyList(),
                        t.getAsJsonArray("route").map { gson.fromJson(it, CivPosition::class.java) }, source.anchor.y, 0, 0)
                }
                CivPlacementPlan(UUID.fromString(p["id"].asString), city.id, city.worldId, source,
                    p.getAsJsonArray("blocks")?.map { gson.fromJson(it, CivPlanBlock::class.java) }.orEmpty(),
                    capturedSha256 = p["capturedSha256"]?.takeUnless { it.isJsonNull }?.asString, terrain = terrain,
                    adminPlacement = p["adminPlacement"]?.asBoolean ?: false)
            }
            val immutablePlans = gson.toJson(plans)
            val originalAccess = CivAutonomousAccess.from(city, plans)
            val originalOverrides = city.roadRouteOverrides
            val canonical = CivsAutonomousGeometry.approach(house).mapTo(linkedSetOf()) { civsTerrainKey(it.x, it.z) }
            canonical.size shouldBe 12
            val oldProfile = originalAccess.routes.values.flatMap { CivsAutonomousTerraform.roadProfile(it).values }
            val allowedColumns = canonical + oldProfile.map { civsTerrainKey(it.x, it.z) } +
                (9659..9660).map { civsTerrainKey(12256, it) } + CivsWorksiteLayouts.forecourt(house).keys.map { civsTerrainKey(it.x, it.z) } +
                originalAccess.infrastructure.flatMap { it.transit }.map { civsTerrainKey(it.x, it.z) }
            val grassIsland = CivPosition(12259, 66, 9654)
            val expected = buildMap {
                for (x in 12257..12259) for (z in 9657..9660) {
                    val floor = CivPosition(x, if (z < 9659) 66 else 67, z)
                    put(floor, if (z == 9659) "minecraft:spruce_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]"
                        else "minecraft:dirt_path")
                }
                for (z in 9657..9660) {
                    put(CivPosition(12260, if (z >= 9659) 67 else 66, z), CivsEntrancePath.GRASS)
                    put(CivPosition(12261, 66, z), CivsEntrancePath.GRASS)
                    if (z >= 9659) put(CivPosition(12256, 67, z), CivsEntrancePath.GRASS)
                }
                if (door != null) for (x in 12257..12259) for (z in 9661..9664)
                    put(CivPosition(x, 67, z), if (z <= 9662) "minecraft:dirt_path" else "minecraft:stone_bricks")
                if (grassIsland.z > bounds.min.z) put(grassIsland, "minecraft:dirt_path")
            }
            val restricted = expected.keys.mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }
            fun targets() = CivsRoadMaintenance.prepare(city, plans, proposeCuts = false).targets.filter {
                civsTerrainKey(it.floor.x, it.floor.z) in restricted }
            var permit = true
            val native = CivsRoadWorld(paper.createSimplePlugin("CapturedApron"), { listOf(city) }, { _, _, _ -> permit }, { false })
            val receipts = mutableListOf<CivRoadUpkeepReceipt>()
            val refusals = mutableListOf<Map<String, Any?>>()
            val walks = mutableListOf<Map<String, Any?>>()
            var negativeChecks = false
            var canonicalTargets = emptyList<CivRoadUpkeep>()
            val failure = runCatching {
                canonicalTargets = targets().filter { civsTerrainKey(it.floor.x, it.floor.z) in canonical }
                canonicalTargets.size shouldBe 12
                targets().associate { it.floor to it.after } shouldBe expected
                for (pass in 0 until 32) {
                    var progressed = false
                    for (target in targets()) {
                        val captured = native.captureRoadUpkeep(city, city.workers.single(), target)
                        if (captured == null) {
                            if (!CivsRoadMaintenance.sameSurface(target, read(target.floor).blockData, Bukkit.createBlockData(target.after)))
                                refusals += mapOf("pass" to pass, "floor" to target.floor,
                                    "actual" to read(target.floor).blockData.asString, "desired" to target.after)
                            continue
                        }
                        val work = captured.copy(inputs = CivsRoadMaintenance.inputs(city, captured))
                        val cost = 1L + work.changes.count { it.position.y < target.floor.y }
                        work.inputs shouldBe mapOf("COBBLESTONE" to cost)
                        native.validateRoadUpkeep(city, work)
                        if (!negativeChecks && work.changes.any { it.position == target.floor }) {
                            val unchanged = state()
                            permit = false
                            native.captureRoadUpkeep(city, city.workers.single(), target) shouldBe null
                            permit = true
                            state() shouldBe unchanged
                            negativeChecks = true
                        }
                        val beforeWorker = city.workers.single()
                        val at = 3_000L + receipts.size * 4_000L
                        val after = CivsRoadMaintenance.complete(city, work, at)
                        (CivsEquipment.usableCount(beforeWorker, "COBBLESTONE") -
                            CivsEquipment.usableCount(after.workers.single(), "COBBLESTONE")) shouldBe cost.toInt()
                        after.stock shouldBe city.stock
                        after.workers.single().skills shouldBe beforeWorker.skills
                        val receipt = gson.fromJson(gson.toJson(CivRoadUpkeepReceipt(beforeWorker, city.stock, work, at).detached()),
                            CivRoadUpkeepReceipt::class.java)
                        receipt.validated(after)
                        CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision, after, emptyList(),
                            worldChanges = work.changes, upkeep = receipt).validated()
                        work.changes.asReversed().forEach { change ->
                            (civsTerrainKey(change.position.x, change.position.z) in allowedColumns) shouldBe true
                            read(change.position).blockData.asString shouldBe change.before
                            read(change.position).setBlockData(Bukkit.createBlockData(change.after), false)
                            native.validateRoadUpkeep(receipt.before(after), receipt.work)
                        }
                        native.roadUpkeepPending(city, work) shouldBe false
                        city = after
                        receipts += receipt
                        progressed = true
                    }
                    if (!progressed) break
                }
                canonicalTargets = targets().filter { civsTerrainKey(it.floor.x, it.floor.z) in canonical }
                canonicalTargets.size shouldBe 12
                canonicalTargets.forEach { target ->
                    (1..CivsRoadMaintenance.MAX_CLEARANCE).all { read(target.floor.copy(y = target.floor.y + it)).type.isAir } shouldBe true
                }
                expected.forEach { (p, block) -> read(p).blockData.asString shouldBe block }
                for (x in listOf(12256, 12260)) for (z in 9659..9660)
                    read(CivPosition(x, 66, z)).blockData.asString shouldBe "minecraft:dirt"
                read(CivPosition(12259, 66, 9659)).blockData.asString shouldBe "minecraft:dirt"
                negativeChecks shouldBe true
                CivAutonomousAccess.from(city, plans).routes shouldBe originalAccess.routes
                city.roadRouteOverrides shouldBe originalOverrides
                city.buildings shouldBe buildings
                gson.toJson(plans) shouldBe immutablePlans
                val paid = receipts.flatMap { it.work.changes }.mapTo(hashSetOf()) { it.position }
                state().filter { (p, data) -> before[p] != data }.keys.all { it in paid } shouldBe true
                targets().all { native.captureRoadUpkeep(city, city.workers.single(), it) == null } shouldBe true
                unknownReads shouldBe emptySet()
                val paidRoute = originalAccess.routes.getValue(house.id)
                val projected = requireNotNull(originalAccess.entrancePath(city, house, paidRoute)).travel(paidRoute)
                val routes = (12257..12259).map { x ->
                    "north-$x" to (9656..if (door == null) 9660 else 9664).map { CivPosition(x, if (it <= 9658) 67 else 68, it) }
                } + ("projected-side-entry" to projected.takeLast(7))
                routes.forEach { (name, route) ->
                    val forward = roadHalfStepWalk(world, route)
                    val reverse = roadHalfStepWalk(world, route.asReversed())
                    val forwardTrace = if (forward == null) trace(route) else null
                    val reverseTrace = if (reverse == null) trace(route.asReversed()) else null
                    walks += mapOf("name" to name, "route" to route, "forward" to forward, "reverse" to reverse,
                        "edges" to route.zipWithNext().map { (a, b) -> mapOf("from" to a, "to" to b,
                            "forward" to roadHalfStepWalk(world, listOf(a, b)), "reverse" to roadHalfStepWalk(world, listOf(b, a))) },
                        "forwardTrace" to forwardTrace, "reverseTrace" to reverseTrace,
                        "firstForwardFailure" to forwardTrace?.firstOrNull { it["walkGate"] == false },
                        "firstReverseFailure" to reverseTrace?.firstOrNull { it["walkGate"] == false })
                    (forward != null && reverse != null) shouldBe true
                }
            }.exceptionOrNull()
            Files.writeString(output.resolve("captured-house-apron-replay.json"), gson.toJson(mapOf(
                "kind" to "exact-native-block-and-collision-offline-paid-receipt-replay-not-live-acceptance",
                "captureInput" to capturePath, "layoutInput" to layoutPath,
                "captureSha256" to hash(captureBytes), "layoutSha256" to hash(layoutBytes),
                "captureAt" to capture["captured_at_ms"].asLong, "bounds" to bounds,
                "canonicalTargets" to canonicalTargets, "receipts" to receipts, "refusals" to refusals,
                "walks" to walks, "permissionDenyChecked" to negativeChecks, "unknownReads" to unknownReads,
                "expectedFinishes" to expected.map { (p, data) -> mapOf("position" to p, "expected" to data, "actual" to read(p).blockData.asString) },
                "afterChanges" to state().filter { (p, data) -> before[p] != data },
                "failure" to failure?.let { mapOf("type" to it.javaClass.name, "message" to it.message) },
                "foregroundIslandIncluded" to (grassIsland in expected),
                "limitations" to listOf("One fixture builder carries64cobblestone; captured runtime staffing/supply schedule is not replayed.",
                    "Native palette collisions and guarded fixture world are used, not a live server or native Minecraft client.",
                    "Claims allow the positive flow; explicit denied permission is checked without changing terrain.",
                    "Synthetic tile/liquid/attachment negatives are outside this captured palette and not replayed here."))))
            Files.readAllBytes(Path.of(capturePath)).contentEquals(captureBytes) shouldBe true
            Files.readAllBytes(Path.of(layoutPath)).contentEquals(layoutBytes) shouldBe true
            failure?.let { throw it }
        }
    }
})

/** A few captured static blocks lack a MockBukkit state parser. Their exact collision comes from the capture. */
private class ApronNativeServer(private val captured: Set<String>) : CivsWorksiteServerFixture() {
    override fun createBlockData(data: String): BlockData {
        val name = data.substringBefore('[')
        if (data !in captured || name !in setOf("minecraft:wildflowers", "minecraft:cobblestone_wall", "minecraft:spruce_fence"))
            return super.createBlockData(data)
        return object : BlockDataMock(requireNotNull(Material.matchMaterial(name))) {
            override fun getAsString(): String = data
            override fun getAsString(hideUnspecified: Boolean): String = data
            override fun clone(): BlockDataMock = this@ApronNativeServer.createBlockData(data) as BlockDataMock
        }
    }
}
