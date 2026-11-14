package ru.arc.civs

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Stairs
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
        val bounds = gson.fromJson(capture["bounds"], CivBox::class.java)
        capture["unavailable_count"].asInt shouldBe 0
        capture["voxel_count"].asInt shouldBe 891
        val palette = capture.getAsJsonArray("palette").map { it.asJsonObject }
        val before = capture.getAsJsonArray("voxels").associate { value ->
            val row = value.asJsonArray
            row[4].asBoolean shouldBe true
            CivPosition(bounds.min.x + row[0].asInt, bounds.min.y + row[1].asInt, bounds.min.z + row[2].asInt) to
                palette[row[3].asInt]["block_data"].asString
        }
        before.size shouldBe 891
        val collisions = palette.associate { cell -> cell["block_data"].asString to cell.getAsJsonArray("collision").map { value ->
            val box = value.asJsonArray
            BoundingBox(box[0].asDouble, box[1].asDouble, box[2].asDouble, box[3].asDouble, box[4].asDouble, box[5].asDouble)
        } }
        val worldId = UUID.fromString(capture["world"].asString)
        MockBukkitTestRuntime.open(ApronNativeServer()).use { paper ->
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
                originalAccess.infrastructure.flatMap { it.transit }.map { civsTerrainKey(it.x, it.z) }
            // Two native-cell margins leave room for real stance/body/side checks. The isolated bank at minZ is deliberately excluded.
            val localPaid = oldProfile.filter { it.x in bounds.min.x + 2..bounds.max.x - 2 &&
                it.z in bounds.min.z + 2..bounds.max.z - 2 }.map { civsTerrainKey(it.x, it.z) }
            val restricted = canonical + localPaid
            (restricted.size <= 24) shouldBe true
            fun targets() = CivsRoadMaintenance.prepare(city, plans, proposeCuts = false, restrictTo = restricted).targets
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
                for (x in 12257..12258) for (z in 9657..9658)
                    canonicalTargets.single { it.floor.x == x && it.floor.z == z }.floor.y shouldBe 66
                canonicalTargets.single { it.floor == CivPosition(12258, 67, 9659) }.after shouldBe
                    "minecraft:spruce_stairs[facing=west,half=bottom,shape=outer_left,waterlogged=false]"
                canonicalTargets.single { it.floor == CivPosition(12257, 67, 9659) }.after shouldBe
                    "minecraft:spruce_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]"
                for (pass in 0 until 32) {
                    var progressed = false
                    for (target in targets()) {
                        val captured = native.captureRoadUpkeep(city, city.workers.single(), target)
                        if (captured == null) {
                            if (!CivsWorld.sameConstructionState(read(target.floor).blockData, Bukkit.createBlockData(target.after)))
                                refusals += mapOf("pass" to pass, "floor" to target.floor,
                                    "actual" to read(target.floor).blockData.asString, "desired" to target.after)
                            continue
                        }
                        val work = captured.copy(inputs = CivsRoadMaintenance.inputs(city, captured))
                        work.inputs shouldBe mapOf("COBBLESTONE" to 1L)
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
                            CivsEquipment.usableCount(after.workers.single(), "COBBLESTONE")) shouldBe 1
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
                    CivsWorld.sameConstructionState(read(target.floor).blockData, Bukkit.createBlockData(target.after)) shouldBe true
                    (1..CivsRoadMaintenance.MAX_CLEARANCE).all { read(target.floor.copy(y = target.floor.y + it)).type.isAir } shouldBe true
                }
                for (x in 12257..12258) for (z in 9657..9658) read(CivPosition(x, 67, z)).type.isAir shouldBe true
                // The generic construction matcher intentionally ignores stair shape. This replay must prove the actual corner was paid and installed.
                val corner = read(CivPosition(12258, 67, 9659)).blockData as Stairs
                corner.facing shouldBe BlockFace.WEST
                corner.shape shouldBe Stairs.Shape.OUTER_LEFT
                val westLane = read(CivPosition(12257, 67, 9659)).blockData as Stairs
                westLane.facing shouldBe BlockFace.SOUTH
                westLane.shape shouldBe Stairs.Shape.STRAIGHT
                negativeChecks shouldBe true
                CivAutonomousAccess.from(city, plans).routes shouldBe originalAccess.routes
                city.roadRouteOverrides shouldBe originalOverrides
                city.buildings shouldBe buildings
                gson.toJson(plans) shouldBe immutablePlans
                val paid = receipts.flatMap { it.work.changes }.mapTo(hashSetOf()) { it.position }
                state().filter { (p, data) -> before[p] != data }.keys.all { it in paid } shouldBe true
                targets().all { native.captureRoadUpkeep(city, city.workers.single(), it) == null } shouldBe true
                unknownReads shouldBe emptySet()
                val routes = listOf(
                    "old-paid-entry" to listOf(CivPosition(12260, 67, 9660), CivPosition(12259, 68, 9660), house.entrance),
                    "north-west" to ((9656..9660).map { CivPosition(12257, if (it <= 9658) 67 else 68, it) } + house.entrance),
                    "north-centre" to (9656..9660).map { CivPosition(12258, if (it <= 9658) 67 else 68, it) },
                )
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
                "actualCorner" to read(CivPosition(12258, 67, 9659)).blockData.asString,
                "cornerReceipts" to receipts.count { receipt -> receipt.work.changes.any { it.position == CivPosition(12258, 67, 9659) } },
                "afterChanges" to state().filter { (p, data) -> before[p] != data },
                "failure" to failure?.let { mapOf("type" to it.javaClass.name, "message" to it.message) },
                "isolatedBank" to mapOf("position" to CivPosition(12259, 66, 9654), "status" to "LIMITED_CAPTURE_BOUNDARY",
                    "reason" to "At minZ; native side/neighbour checks need uncapturedZ9653. No fabricated cells or repair."),
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

/** Pinned MockBukkit lacks wildflower properties; keep the exact native material/state and captured empty shape. */
private class ApronNativeServer : CivsWorksiteServerFixture() {
    override fun createBlockData(data: String): BlockData {
        if (data.substringBefore('[') != "minecraft:wildflowers") return super.createBlockData(data)
        return object : BlockDataMock(Material.WILDFLOWERS) {
            override fun getAsString(): String = data
            override fun getAsString(hideUnspecified: Boolean): String = data
            override fun clone(): BlockDataMock = this@ApronNativeServer.createBlockData(data) as BlockDataMock
        }
    }
}
