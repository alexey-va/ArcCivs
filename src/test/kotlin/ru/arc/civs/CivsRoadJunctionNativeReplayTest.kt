package ru.arc.civs

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.util.BoundingBox
import org.mockbukkit.mockbukkit.block.BlockMock
import org.mockbukkit.mockbukkit.world.Coordinate
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Opt-in replay of native bytes. Uncaptured terrain is an error, never fixture ground. */
class CivsRoadJunctionNativeReplayTest : FunSpec({
    test("the captured town hall junction repairs through paid receipts and preserves both local routes") {
        val capturePath = System.getenv("CIVS_JUNCTION_NATIVE_INPUT") ?: return@test
        val layoutPath = requireNotNull(System.getenv("CIVS_JUNCTION_LAYOUT_INPUT"))
        val output = Path.of(requireNotNull(System.getenv("CIVS_ROAD_PREVIEW_OUT"))).also(Files::createDirectories)
        val gson = GsonBuilder().setPrettyPrinting().create()
        val capture = JsonParser.parseString(Files.readString(Path.of(capturePath))).asJsonObject
        val layout = JsonParser.parseString(Files.readString(Path.of(layoutPath))).asJsonObject
        val bounds = gson.fromJson(capture["bounds"], CivBox::class.java)
        capture["unavailable_count"].asInt shouldBe 0
        val palette = capture.getAsJsonArray("palette").map { it.asJsonObject }
        val before = capture.getAsJsonArray("voxels").associate { value ->
            val row = value.asJsonArray
            row[4].asBoolean shouldBe true
            CivPosition(bounds.min.x + row[0].asInt, bounds.min.y + row[1].asInt, bounds.min.z + row[2].asInt) to
                palette[row[3].asInt]["block_data"].asString
        }
        val oldStair = "minecraft:dark_oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]"
        before[CivPosition(-1867, 63, -2772)] shouldBe oldStair
        before[CivPosition(-1866, 63, -2771)] shouldBe oldStair
        before[CivPosition(-1868, 63, -2772)] shouldBe "minecraft:dirt_path"
        val collisions = palette.associate { cell -> cell["block_data"].asString to cell.getAsJsonArray("collision").map { value ->
            val box = value.asJsonArray
            BoundingBox(box[0].asDouble, box[1].asDouble, box[2].asDouble, box[3].asDouble, box[4].asDouble, box[5].asDouble)
        } }
        MockBukkitTestRuntime.open().use { paper ->
            var enforceCapture = false
            val world = object : CivsWorksiteWorldFixture("captured-junction", ground = Material.AIR, collisionForData = { data ->
                collisions[data.asString]?.map { it.clone() } ?: if (CivsRoadMaintenance.surface(data.asString))
                    roadUpkeepBoxes(data).map { it.at(CivPosition(0, 0, 0)) } else null
            }) {
                override fun getBlockAt(point: Coordinate): BlockMock {
                    if (enforceCapture) require(CivPosition(point.x, point.y, point.z) in before) {
                        "Native junction replay requested an uncaptured cell: $point"
                    }
                    return super.getBlockAt(point)
                }
            }.also(paper.server::addWorld)
            for (x in (bounds.min.x shr 4)..(bounds.max.x shr 4)) for (z in (bounds.min.z shr 4)..(bounds.max.z shr 4))
                world.loadChunk(x, z)
            before.forEach { (p, data) -> world.getBlockAt(p.x, p.y, p.z).setBlockData(Bukkit.createBlockData(data), false) }
            enforceCapture = true
            fun position(value: com.google.gson.JsonElement): CivPosition = gson.fromJson(value, CivPosition::class.java)
            val buildings = layout.getAsJsonArray("buildings").map { value ->
                val b = value.asJsonObject
                CivBuilding(UUID.fromString(b["id"].asString), BuildingType.valueOf(b["type"].asString), b["level"].asInt,
                    position(b["anchor"]), b["rotation"].asInt, b["schematic"].asString,
                    gson.fromJson(b["actual_bounds"], CivBox::class.java), position(b["entrance"]), BuildingPhase.valueOf(b["phase"].asString),
                    control = b["control"]?.takeUnless { it.isJsonNull }?.let(::position),
                    transit = b.getAsJsonArray("transit")?.map(::position)?.takeIf { it.isNotEmpty() })
            }
            val cityData = layout.getAsJsonObject("city")
            val builder = buildings.first { it.type == BuildingType.BUILDER_HUT && it.phase == BuildingPhase.ACTIVE }
            var city = Settlement(UUID.fromString(cityData["id"].asString), UUID.fromString(cityData["owner"].asString),
                "Native junction replay", world.uid, position(cityData["center"]), cityData["radius"].asInt,
                cityData["style"].asString, Stock(1_000, 1_000, 1_000), buildings,
                listOf(CivWorker(UUID(0, 13), "Builder", builder.id, nextDeliveryAt = 1)), autonomy = CivAutonomy())
            val plans = layout.getAsJsonArray("access_routes").map { value ->
                val route = value.asJsonObject
                val source = buildings.single { it.id.toString() == route["building"].asString }
                CivPlacementPlan(source.id, city.id, city.worldId, source, emptyList(), terrain = CivTerraformPlan(source.bounds,
                    emptyList(), route.getAsJsonArray("positions").map(::position), source.anchor.y, 0, 0))
            }
            val immutablePlans = gson.toJson(plans)
            val originalAccess = CivAutonomousAccess.from(city, plans)
            val initial = CivsRoadMaintenance.prepare(city, plans).targets.single { target -> target.routeOverride != null &&
                target.junction != null && bounds.contains(target.floor.x, target.floor.y, target.floor.z) }
            val junction = requireNotNull(initial.junction)
            val warehouse = junction.owners.single { it.source.type == BuildingType.WAREHOUSE }.source
            val native = CivsRoadWorld(paper.createSimplePlugin("CapturedJunction"), { listOf(city) }, { _, _, _ -> true }, { false })
            val receipts = mutableListOf<CivRoadUpkeepReceipt>()
            val refusals = mutableListOf<Map<String, Any?>>()
            for (pass in 0 until 64) {
                val targets = CivsRoadMaintenance.prepare(city, plans).targets.filter { it.junction != null &&
                    bounds.contains(it.floor.x, it.floor.y, it.floor.z) }
                var progressed = false
                for (target in targets) {
                    val work = native.captureRoadUpkeep(city, city.workers.single(), target)
                    if (work == null) {
                        val block = world.getBlockAt(target.floor.x, target.floor.y, target.floor.z)
                        if (!CivsWorld.sameConstructionState(block.blockData, Bukkit.createBlockData(target.after)))
                            refusals += mapOf("floor" to target.floor, "actual" to block.blockData.asString, "desired" to target.after)
                        continue
                    }
                    native.validateRoadUpkeep(city, work)
                    val at = 3_000L + receipts.size * 4_000L
                    val after = CivsRoadMaintenance.complete(city, work, at)
                    val receipt = gson.fromJson(gson.toJson(CivRoadUpkeepReceipt(city.workers.single(), city.stock, work, at).detached()),
                        CivRoadUpkeepReceipt::class.java)
                    receipt.validated(after)
                    CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision, after, emptyList(),
                        worldChanges = work.changes, upkeep = receipt).validated()
                    work.changes.asReversed().forEach { change ->
                        val block = world.getBlockAt(change.position.x, change.position.y, change.position.z)
                        block.blockData.asString shouldBe change.before
                        block.setBlockData(Bukkit.createBlockData(change.after), false)
                        native.validateRoadUpkeep(receipt.before(after), receipt.work)
                    }
                    native.roadUpkeepPending(city, work) shouldBe false
                    city = after
                    receipts += receipt
                    progressed = true
                    if (target.routeOverride != null) break
                }
                if (!progressed) break
            }
            val finalTargets = CivsRoadMaintenance.prepare(city, plans).targets.filter { it.junction != null &&
                bounds.contains(it.floor.x, it.floor.y, it.floor.z) }
            val unresolved = finalTargets.filter { target -> !CivsWorld.sameConstructionState(
                world.getBlockAt(target.floor.x, target.floor.y, target.floor.z).blockData, Bukkit.createBlockData(target.after)) }
            val access = CivAutonomousAccess.from(city, plans)
            val walks = junction.owners.map { owner ->
                val route = access.routes.getValue(owner.source.id)
                val indices = route.indices.filter { civsTerrainKey(route[it].x, route[it].z) in junction.columns() }
                val section = route.subList(maxOf(0, indices.first() - 1), minOf(route.size, indices.last() + 2))
                mapOf("building" to owner.source.id, "route" to section, "forward" to roadHalfStepWalk(world, section),
                    "reverse" to roadHalfStepWalk(world, section.asReversed()))
            }
            val afterNative = before.map { (p, _) -> mapOf("position" to p,
                "data" to world.getBlockAt(p.x, p.y, p.z).blockData.asString) }
            val paidPositions = receipts.flatMap { it.work.changes }.map { it.position }.toSet()
            before.filter { (p, data) -> world.getBlockAt(p.x, p.y, p.z).blockData.asString != data }.keys.all { it in paidPositions } shouldBe true
            Files.writeString(output.resolve("captured-junction-replay.json"), gson.toJson(mapOf(
                "kind" to "exact-native-capture-offline-paid-receipt-replay-not-live-acceptance",
                "captureInput" to capturePath, "layoutInput" to layoutPath, "captureAt" to capture["captured_at_ms"].asLong,
                "bounds" to bounds, "before" to before.map { (p, data) -> mapOf("position" to p, "data" to data) },
                "after" to afterNative, "receipts" to receipts, "unresolved" to unresolved, "refusals" to refusals,
                "walks" to walks, "routeOverrides" to city.roadRouteOverrides,
                "limitations" to listOf("Exact native blocks and collisions replayed in MockBukkit; no live write or native client traversal.",
                    "One funded fixture builder bypasses the captured runtime schedule and inventory; receipt debit remains validated.",
                    "Lands permits all in this replay; deny cases belong to the mandatory native fixture test."))))
            receipts.count { it.work.upkeep?.routeOverride != null } shouldBe 1
            unresolved shouldBe emptyList()
            walks.all { it["forward"] != null && it["reverse"] != null } shouldBe true
            access.routes.getValue(warehouse.id) shouldBe originalAccess.routes.getValue(warehouse.id)
            gson.toJson(plans) shouldBe immutablePlans
            finalTargets.all { native.captureRoadUpkeep(city, city.workers.single(), it) == null } shouldBe true
        }
    }
})
