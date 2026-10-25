package ru.arc.civs

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.util.BoundingBox
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Opt-in preview of a fresh detached capture. No server connection or native mutation is performed. */
class CivsRoadPassagePreviewTest : FunSpec({
    test("fresh captured road replays actual maintenance admission and exports exact before after states") {
        val capturePath = System.getenv("CIVS_ROAD_NATIVE_INPUT") ?: return@test
        val layoutPath = requireNotNull(System.getenv("CIVS_ROAD_LAYOUT_INPUT"))
        val out = Path.of(requireNotNull(System.getenv("CIVS_ROAD_PREVIEW_OUT")))
        val gson = GsonBuilder().setPrettyPrinting().create()
        val capture = JsonParser.parseString(Files.readString(Path.of(capturePath))).asJsonObject
            .getAsJsonArray("captures")[1].asJsonObject.getAsJsonObject("state")
        val layout = JsonParser.parseString(Files.readString(Path.of(layoutPath))).asJsonObject.getAsJsonObject("layout")
        fun position(value: com.google.gson.JsonElement): CivPosition = gson.fromJson(value, CivPosition::class.java)
        val bounds = gson.fromJson(capture["bounds"], CivBox::class.java)
        val palette = capture.getAsJsonArray("palette").map { it.asJsonObject }
        val original = capture.getAsJsonArray("voxels").associate { element ->
            val row = element.asJsonArray
            row[4].asBoolean shouldBe true
            bounds.min.copy(x = bounds.min.x + row[0].asInt, y = bounds.min.y + row[1].asInt,
                z = bounds.min.z + row[2].asInt) to palette[row[3].asInt]["block_data"].asString
        }
        val nativeCollision = palette.associate { cell -> cell["block_data"].asString to cell.getAsJsonArray("collision").map { element ->
            val box = element.asJsonArray
            BoundingBox(box[0].asDouble, box[1].asDouble, box[2].asDouble, box[3].asDouble, box[4].asDouble, box[5].asDouble)
        } }
        MockBukkitTestRuntime.open().use { paper ->
            val world = CivsWorksiteWorldFixture("upkeep-native-replay", collisionForData = { data ->
                nativeCollision[data.asString]?.map { it.clone() } ?: if (CivsRoadMaintenance.surface(data.asString))
                    roadUpkeepBoxes(data).map { it.at(CivPosition(0, 0, 0)) } else null
            }).also(paper.server::addWorld)
            for (x in (bounds.min.x shr 4)..(bounds.max.x shr 4)) for (z in (bounds.min.z shr 4)..(bounds.max.z shr 4))
                world.loadChunk(x, z)
            original.forEach { (p, data) -> world.getBlockAt(p.x, p.y, p.z).setBlockData(Bukkit.createBlockData(data), false) }
            val cityJson = layout.getAsJsonObject("city")
            val buildings = layout.getAsJsonArray("buildings").map { element ->
                val b = element.asJsonObject
                CivBuilding(UUID.fromString(b["id"].asString), BuildingType.valueOf(b["type"].asString), b["level"].asInt,
                    position(b["anchor"]), b["rotation"].asInt, b["schematic"].asString,
                    gson.fromJson(b["actual_bounds"], CivBox::class.java), position(b["entrance"]), BuildingPhase.valueOf(b["phase"].asString),
                    control = b["control"]?.takeUnless { it.isJsonNull }?.let(::position),
                    transit = b.getAsJsonArray("transit")?.map(::position)?.takeIf { it.isNotEmpty() })
            }
            val builder = buildings.first { it.type == BuildingType.BUILDER_HUT }
            var city = Settlement(id = UUID.fromString(cityJson["id"].asString),
                owner = UUID.fromString(cityJson["owner"].asString), name = "Native replay", worldId = world.uid,
                center = position(cityJson["center"]), radius = cityJson["radius"].asInt,
                style = cityJson["style"].asString, stock = Stock(1_000, 1_000, 1_000), buildings = buildings,
                workers = listOf(CivWorker(UUID(0, 13), "Builder", builder.id, nextDeliveryAt = 1)), autonomy = CivAutonomy())
            val plans = layout.getAsJsonArray("access_routes").map { element ->
                val route = element.asJsonObject
                val building = buildings.single { it.id.toString() == route["building"].asString }
                val points = route.getAsJsonArray("positions").map(::position)
                CivPlacementPlan(building.id, city.id, city.worldId, building, emptyList(), terrain = CivTerraformPlan(
                    building.bounds, emptyList(), points, building.anchor.y, 0, 0))
            }
            val network = CivsRoadMaintenance.prepare(city, plans)
            // Every admitted target has native evidence for support, lateral guards and the full capped cut.
            val targets = network.targets.filter { p -> p.floor.let {
                it.x in bounds.min.x + 1 until bounds.max.x && it.z in bounds.min.z + 1 until bounds.max.z &&
                    it.y in bounds.min.y + 1..bounds.max.y - CivsRoadMaintenance.MAX_CLEARANCE - 1 &&
                    p.neighbours.all { n -> bounds.contains(n.x, n.y, n.z) && bounds.contains(n.x, n.y + 3, n.z) }
            } }
            val protected = buildings.filter { it.type != BuildingType.ROAD }.map { CivsAutonomousGeometry.futureBounds(city, it) }
            val receipts = mutableListOf<CivWorldWork>()
            val plugin = paper.createSimplePlugin("RoadCaptureReplay")
            LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
                CivsWorld(plugin, tasks, { 0L }) { listOf(city) }.use { native ->
                    for (pass in targets.indices) {
                        var progressed = false
                        for (target in targets) {
                            val work = native.captureRoadUpkeep(city, city.workers.single(), target) ?: continue
                            native.validateRoadUpkeep(city, work)
                            val after = CivsRoadMaintenance.complete(city, work, receipts.size * 4_000L)
                            CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision, after, emptyList(),
                                worldChanges = work.changes, upkeep = CivRoadUpkeepReceipt(city.workers.single(), city.stock, work,
                                    receipts.size * 4_000L)).validated()
                            work.changes.forEach { change ->
                                protected.none { CivsAutonomousGeometry.horizontalContains(it, change.position) } shouldBe true
                                val block = world.getBlockAt(change.position.x, change.position.y, change.position.z)
                                block.blockData.asString shouldBe change.before
                                block.setBlockData(Bukkit.createBlockData(change.after), false)
                            }
                            native.validateRoadUpkeep(city, work)
                            native.roadUpkeepPending(city, work) shouldBe false
                            city = after
                            receipts += work
                            progressed = true
                        }
                        if (!progressed) break
                    }
                    fun cell(p: CivPosition, data: String) = mapOf("position" to p, "data" to data)
                    val after = original.map { (p, _) -> cell(p, world.getBlockAt(p.x, p.y, p.z).blockData.asString) }
                    val unresolved = targets.filter { target ->
                        !CivsWorld.sameConstructionState(world.getBlockAt(target.floor.x, target.floor.y, target.floor.z).blockData,
                            Bukkit.createBlockData(target.after))
                    }.map { target -> mapOf("floor" to target.floor, "desired" to target.after,
                        "actual" to world.getBlockAt(target.floor.x, target.floor.y, target.floor.z).blockData.asString,
                        "support" to world.getBlockAt(target.floor.x, target.floor.y - 1, target.floor.z).blockData.asString) }
                    // Diagnostic probes explain refusal without making a refused cell part of the preview.
                    val diagnostics = targets.map { target ->
                        val p = target.floor
                        val block = world.getBlockAt(p.x, p.y, p.z)
                        val cover = CivsRoadMaintenance.covers(p, (1..CivsRoadMaintenance.MAX_CLEARANCE).map { dy ->
                            world.getBlockAt(p.x, p.y + dy, p.z).blockData.asString
                        })
                        val floorChange = if (CivsWorld.sameConstructionState(block.blockData, Bukkit.createBlockData(target.after)))
                            null else CivPlanBlock(p, block.blockData.asString, target.after)
                        val changes = listOfNotNull(floorChange) + cover.orEmpty()
                        val stands = buildList {
                            for (dy in listOf(1, 0, 2, 3)) for ((dx, dz) in listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)) {
                                val standing = CivPosition(p.x + dx, p.y + dy, p.z + dz)
                                val standable = citizenStandable(world, standing)
                                val clear = citizenPacketBodyClear(world, CitizenPoint(standing.x + .5, standing.y.toDouble(), standing.z + .5), false)
                                if (!standable || !clear) continue
                                val focus = changes.asReversed().firstOrNull { citizenCanReachBlock(world,
                                    Location(world, standing.x + .5, standing.y + 1.62, standing.z + .5), it.position) }?.position
                                val allowed = city.contains(standing.x, standing.z) && citizenControlClear(city, standing) &&
                                    protected.none { CivsAutonomousGeometry.horizontalContains(it, standing) }
                                val result = if (focus == null || cover == null || changes.isEmpty()) "no-reachable-change" else try {
                                    native.validateRoadUpkeep(city, CivWorldWork(city.workers.single().id, city.workers.single().completedCycles,
                                        builder.id, standing, changes, status = "repairing-road", focus = focus, upkeep = target))
                                    "validated"
                                } catch (e: CivsRejection) { e.reason }
                                add(mapOf("standing" to standing, "allowed" to allowed, "focus" to focus, "validation" to result))
                            }
                        }
                        mapOf("floor" to p, "actual" to block.blockData.asString, "desired" to target.after,
                            "support" to world.getBlockAt(p.x, p.y - 1, p.z).blockData.asString,
                            "coverAccepted" to (cover != null), "stands" to stands)
                    }
                    Files.createDirectories(out)
                    Files.writeString(out.resolve("native-diagnostics.json"), gson.toJson(mapOf(
                        "networkTargets" to network.targets.size, "capturedTargets" to targets.size,
                        "receipts" to receipts.size, "targets" to diagnostics)))
                    receipts.isNotEmpty() shouldBe true
                    fun feet(p: CivPosition): CitizenPoint {
                        val block = world.getBlockAt(p.x, p.y - 1, p.z)
                        val top = requireNotNull(block.collisionShape.boundingBoxes
                            .filter { .5 in it.minX..it.maxX && .5 in it.minZ..it.maxZ }.maxOfOrNull { it.maxY })
                        return CitizenPoint(p.x + .5, p.y - 1.0 + top, p.z + .5)
                    }
                    fun captured(p: CivPosition) = p.x in bounds.min.x + 1 until bounds.max.x &&
                        p.z in bounds.min.z + 1 until bounds.max.z && p.y - 1 >= bounds.min.y && p.y + 3 <= bounds.max.y
                    val routeChecks = plans.flatMap { plan -> plan.terrain!!.route.zipWithNext().filter { (a, b) -> captured(a) && captured(b) }
                        .map { (a, b) ->
                            val from = feet(a); val to = feet(b)
                            val clear = citizenPacketBodyClear(world, from, false) && citizenPacketBodyClear(world, to, false) &&
                                citizenPacketSupported(world, from) && citizenPacketSupported(world, to) &&
                                citizenPacketStepClear(world, from, to, false) && citizenPacketStepClear(world, to, from, false)
                            clear shouldBe true
                            mapOf("from" to from, "to" to to, "bodySupportedBidirectionalStep" to clear)
                        } }
                    routeChecks.isNotEmpty() shouldBe true
                    Files.createDirectories(out)
                    Files.writeString(out.resolve("native-replay.json"), gson.toJson(mapOf(
                        "kind" to "fresh-native-blocks-offline-production-algorithm-replay-not-live-acceptance",
                        "captureInput" to capturePath, "layoutInput" to layoutPath,
                        "captureAt" to capture["captured_at_ms"].asLong, "layoutAt" to layout["captured_at_ms"].asLong,
                        "sourceRevision" to layout["source_revision"].asLong, "bounds" to bounds,
                        "before" to original.map { (p, data) -> cell(p, data) }, "after" to after,
                        "targets" to targets, "receipts" to receipts, "unresolved" to unresolved, "routeChecks" to routeChecks,
                        "excludedByCaptureEnvelope" to network.targets.count { it !in targets && bounds.contains(it.floor.x, it.floor.y, it.floor.z) },
                        "limitations" to listOf("MockBukkit state uses captured native collision and exact new-road boxes.",
                            "Replay uses one funded fixture builder after construction priority, not the captured runtime schedule or stock.",
                            "No entity occupancy, Lands roles, live activation or client traversal is established by replay.",
                            "Missing native support or guard cells are excluded; no implied ground is filled."))))
                }
            }
        }
    }
})
